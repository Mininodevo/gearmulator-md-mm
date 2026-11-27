// JNI bridge: runs the Machinedrum / Monomachine emulation (md::Device inside synthLib::Plugin)
// and plays it through AAudio. A render thread produces audio into a ring buffer so that the
// (not realtime-safe) emulator never runs inside the AAudio callback. UI input is queued and
// applied by the render thread.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "synthLib/plugin.h"
#include "synthLib/midiTypes.h"
#include "mdLib/mddevice.h"
#include "mdLib/mdpanel.h"
#include "mdLib/mdtypes.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "mdmm", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "mdmm", __VA_ARGS__)

namespace
{
	constexpr uint32_t kBlock = 256;          // frames per emulator call
	constexpr uint32_t kRingFrames = 16384;   // power of two
	constexpr uint32_t kTargetFill = 4096;    // frames we try to keep buffered (~85 ms @ 48k): more headroom against CPU hiccups
	constexpr uint32_t kResumeFill = 2048;    // after an underrun: stay silent until this many frames are buffered again
	                                          // (one clean gap instead of a burst of clicks)

	// true  = render thread runs ONLY on the fastest core (on a Pixel 9: the Cortex-X4), no migration between cores
	// false = old behaviour: fast cores + mid cores (the thread may hop between cores with different clocks)
	constexpr bool kPinToFastestCoreOnly = true;

	struct Cmd
	{
		enum Kind : uint8_t { Button, Encoder, Midi } kind;
		int a, b, c;
	};

	struct Engine
	{
		md::MachineModel model = md::MachineModel::Machinedrum;
		md::Device* device = nullptr;                 // owned by us, Plugin does not delete it
		std::unique_ptr<synthLib::Plugin> plugin;
		md::PanelRowState rows;

		std::mutex cmdMutex;
		std::deque<Cmd> cmds;

		AAudioStream* stream = nullptr;
		int sampleRate = 48000;

		std::vector<float> ring = std::vector<float>(kRingFrames * 2);
		std::atomic<uint64_t> readPos{0};
		std::atomic<uint64_t> writePos{0};
		std::atomic<uint32_t> underruns{0};
		bool rebuffering = false;            // only touched by the audio callback
		std::atomic<uint32_t> loadAvg{0};    // emulation time / audio time, in percent (smoothed)
		std::atomic<uint32_t> loadPeak{0};   // same, slowly decaying peak

		std::atomic<bool> running{false};
		std::thread renderThread;
	};

	std::mutex g_mutex;
	Engine* g_engine = nullptr;

	void applyCommand(Engine& e, const Cmd& c, bool& panelEventSent)
	{
		switch(c.kind)
		{
		case Cmd::Button:
			{
				const auto packet = md::panelPacket(e.model, static_cast<md::PanelControl>(c.a));
				if(!packet)
					return;
				const auto merged = c.b ? e.rows.press(*packet) : e.rows.release(*packet);
				e.plugin->withDeviceLocked([&](synthLib::Device* d)
				{
					return static_cast<md::Device*>(d)->sendPanelEvent(merged.row, merged.mask);
				});
				panelEventSent = true;
			}
			break;
		case Cmd::Encoder:
			{
				const auto cmd = md::panelEncoderCommand(e.model, static_cast<md::PanelEncoder>(c.a));
				if(!cmd)
					return;
				const uint8_t arg = c.b > 0 ? 0x01 : 0xff;
				e.plugin->withDeviceLocked([&](synthLib::Device* d)
				{
					return static_cast<md::Device*>(d)->sendPanelEvent(*cmd, arg);
				});
				panelEventSent = true;
			}
			break;
		case Cmd::Midi:
			{
				synthLib::SMidiEvent ev(synthLib::MidiEventSource::Host,
					static_cast<uint8_t>(c.a), static_cast<uint8_t>(c.b), static_cast<uint8_t>(c.c));
				e.plugin->addMidiEvent(ev);
			}
			break;
		}
	}

	// MIDI is applied right away; panel edges are spaced one emulator block apart so that a quick
	// tap (down + up) is never collapsed into a single scan of the front panel.
	void drainCommands(Engine& e)
	{
		bool panelEventSent = false;
		for(;;)
		{
			Cmd c{};
			{
				std::lock_guard lock(e.cmdMutex);
				if(e.cmds.empty())
					return;
				if(e.cmds.front().kind != Cmd::Midi && panelEventSent)
					return;
				c = e.cmds.front();
				e.cmds.pop_front();
			}
			applyCommand(e, c, panelEventSent);
		}
	}

	// The emulator runs ColdFire + both DSPs on this one thread, so it needs one fast core. Pin it to
	// everything except the slowest cluster (on a Pixel 9: the 3 mid cores and the big core).
	void pinToFastCores()
	{
		const long n = sysconf(_SC_NPROCESSORS_CONF);
		if(n <= 1 || n > CPU_SETSIZE)
			return;

		std::vector<long> freq(static_cast<size_t>(n), 0);
		long minF = 0, maxF = 0;
		for(long i = 0; i < n; ++i)
		{
			char path[96];
			std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", i);
			FILE* f = std::fopen(path, "r");
			if(!f)
				return;                       // not readable: leave the placement to Android
			long v = 0;
			const int ok = std::fscanf(f, "%ld", &v);
			std::fclose(f);
			if(ok != 1)
				return;
			freq[static_cast<size_t>(i)] = v;
			minF = i == 0 ? v : std::min(minF, v);
			maxF = i == 0 ? v : std::max(maxF, v);
		}
		if(minF == maxF)
			return;                           // no big.LITTLE

		cpu_set_t set;
		CPU_ZERO(&set);
		for(long i = 0; i < n; ++i)
		{
			if(kPinToFastestCoreOnly ? freq[static_cast<size_t>(i)] == maxF : freq[static_cast<size_t>(i)] > minF)
				CPU_SET(static_cast<int>(i), &set);
		}
		if(sched_setaffinity(0, sizeof(set), &set) == 0)
			LOGI("render thread pinned to the %s", kPinToFastestCoreOnly ? "fastest core only" : "fast cores");
		else
			LOGE("sched_setaffinity failed");
	}

	// Android Dynamic Performance Framework (API 33+): tells the system how much CPU the thread needs so that
	// it raises the clock before we underrun. Looked up at run time, so older devices simply skip it.
	class PerfHint
	{
	public:
		PerfHint() = default;
		PerfHint(const PerfHint&) = delete;
		PerfHint& operator=(const PerfHint&) = delete;
		~PerfHint()
		{
			if(m_session && m_close)
				m_close(m_session);
		}

		void start(const int32_t _tid, const int64_t _targetNs)
		{
			void* lib = dlopen("libandroid.so", RTLD_NOW);
			if(!lib)
				return;
			auto getManager = reinterpret_cast<void* (*)()>(dlsym(lib, "APerformanceHint_getManager"));
			auto create = reinterpret_cast<void* (*)(void*, const int32_t*, size_t, int64_t)>(dlsym(lib, "APerformanceHint_createSession"));
			m_report = reinterpret_cast<int (*)(void*, int64_t)>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
			m_close = reinterpret_cast<void (*)(void*)>(dlsym(lib, "APerformanceHint_closeSession"));
			if(!getManager || !create || !m_report || !m_close)
				return;
			void* manager = getManager();
			if(!manager)
				return;
			m_session = create(manager, &_tid, 1, _targetNs);
			LOGI(m_session ? "performance hint session active" : "performance hint session not available");
		}

		void report(const int64_t _actualNs)
		{
			if(m_session)
				m_report(m_session, _actualNs);
		}

	private:
		void* m_session = nullptr;
		int (*m_report)(void*, int64_t) = nullptr;
		void (*m_close)(void*) = nullptr;
	};

	void renderLoop(Engine* e)
	{
		const auto tid = static_cast<int32_t>(syscall(SYS_gettid));
		setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -16);   // THREAD_PRIORITY_AUDIO, ignored if refused
		pinToFastCores();

		const double blockNs = 1e9 * kBlock / static_cast<double>(e->sampleRate);
		PerfHint hint;
		hint.start(tid, static_cast<int64_t>(blockNs * 0.6));   // ask for ~40% headroom

		std::vector<float> l(kBlock), r(kBlock);
		synthLib::TAudioInputs in{};
		synthLib::TAudioOutputs out{};
		out[0] = l.data();
		out[1] = r.data();

		double avg = 0.0, peak = 0.0;
		auto lastLog = std::chrono::steady_clock::now();

		while(e->running.load(std::memory_order_relaxed))
		{
			const uint64_t w = e->writePos.load(std::memory_order_relaxed);
			const uint64_t rd = e->readPos.load(std::memory_order_acquire);

			if((w - rd) + kBlock > kTargetFill)
			{
				std::this_thread::sleep_for(std::chrono::milliseconds(1));
				continue;
			}

			const auto t0 = std::chrono::steady_clock::now();
			drainCommands(*e);
			e->plugin->process(in, out, kBlock, 120.0, 0.0, false, false);
			const double busyNs = std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - t0).count();

			hint.report(static_cast<int64_t>(busyNs));
			const double load = busyNs / blockNs;
			avg += (load - avg) * 0.02;
			peak = std::max(load, peak * 0.995);
			e->loadAvg.store(static_cast<uint32_t>(avg * 100.0 + 0.5), std::memory_order_relaxed);
			e->loadPeak.store(static_cast<uint32_t>(peak * 100.0 + 0.5), std::memory_order_relaxed);

			const auto now = std::chrono::steady_clock::now();
			if(now - lastLog >= std::chrono::seconds(1))
			{
				lastLog = now;
				LOGI("load avg=%u%% peak=%u%% underruns=%u buffered=%u frames",
					e->loadAvg.load(std::memory_order_relaxed), e->loadPeak.load(std::memory_order_relaxed),
					e->underruns.load(std::memory_order_relaxed),
					static_cast<uint32_t>(e->writePos.load(std::memory_order_relaxed) - e->readPos.load(std::memory_order_relaxed)));
			}

			for(uint32_t i = 0; i < kBlock; ++i)
			{
				const size_t idx = static_cast<size_t>((w + i) & (kRingFrames - 1)) * 2;
				e->ring[idx] = l[i];
				e->ring[idx + 1] = r[i];
			}
			e->writePos.store(w + kBlock, std::memory_order_release);
		}
	}

	aaudio_data_callback_result_t onAudio(AAudioStream*, void* user, void* data, int32_t frames)
	{
		auto* e = static_cast<Engine*>(user);
		auto* dst = static_cast<float*>(data);

		const uint64_t rd = e->readPos.load(std::memory_order_relaxed);
		const uint64_t w = e->writePos.load(std::memory_order_acquire);
		const uint64_t avail = w - rd;

		// After an underrun, play silence until the buffer has recovered. Otherwise the audio would
		// restart with a few frames, run dry again right away and produce a rapid series of clicks.
		if(e->rebuffering)
		{
			if(avail < kResumeFill)
			{
				std::fill(dst, dst + frames * 2, 0.0f);
				return AAUDIO_CALLBACK_RESULT_CONTINUE;
			}
			e->rebuffering = false;
		}

		const int32_t n = static_cast<int32_t>(std::min<uint64_t>(avail, static_cast<uint64_t>(frames)));

		for(int32_t i = 0; i < n; ++i)
		{
			const size_t idx = static_cast<size_t>((rd + i) & (kRingFrames - 1)) * 2;
			dst[i * 2] = e->ring[idx];
			dst[i * 2 + 1] = e->ring[idx + 1];
		}
		if(n < frames)
		{
			std::fill(dst + n * 2, dst + frames * 2, 0.0f);
			e->underruns.fetch_add(1, std::memory_order_relaxed);
			e->rebuffering = true;
		}
		e->readPos.store(rd + n, std::memory_order_release);
		return AAUDIO_CALLBACK_RESULT_CONTINUE;
	}

	void destroy(Engine* e)
	{
		if(!e)
			return;
		e->running = false;
		if(e->renderThread.joinable())
			e->renderThread.join();
		if(e->stream)
		{
			AAudioStream_requestStop(e->stream);
			AAudioStream_close(e->stream);
			e->stream = nullptr;
		}
		e->plugin.reset();
		delete e->device;
		e->device = nullptr;
		delete e;
	}

	std::string startEngine(const int model, const std::vector<uint8_t>& rom, const std::string& romName,
		const std::string& home)
	{
		auto* e = new Engine();
		e->model = model == 1 ? md::MachineModel::Monomachine : md::MachineModel::Machinedrum;

		// 1. audio stream first, so that we know the real sample rate
		AAudioStreamBuilder* builder = nullptr;
		if(AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
		{
			destroy(e);
			return "AAudio: could not create stream builder";
		}
		AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
		AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
		AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
		AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
		AAudioStreamBuilder_setChannelCount(builder, 2);
		AAudioStreamBuilder_setDataCallback(builder, onAudio, e);
		const auto res = AAudioStreamBuilder_openStream(builder, &e->stream);
		AAudioStreamBuilder_delete(builder);
		if(res != AAUDIO_OK)
		{
			destroy(e);
			return std::string("AAudio: could not open stream: ") + AAudio_convertResultToText(res);
		}
		e->sampleRate = AAudioStream_getSampleRate(e->stream);

		// 2. emulated device (boots the firmware, can take a while)
		try
		{
			synthLib::DeviceCreateParams params;
			params.customData = md::deviceCustomData(e->model);
			params.romData = rom;
			params.romName = romName;
			params.homePath = home;
			params.hostSamplerate = static_cast<float>(e->sampleRate);

			auto dev = std::make_unique<md::Device>(params);
			if(!dev->isValid())
			{
				destroy(e);
				return "The firmware ROM was rejected (wrong model or not a supported version).";
			}
			e->device = dev.release();
		}
		catch(const std::exception& ex)
		{
			destroy(e);
			return std::string("Device error: ") + ex.what();
		}
		catch(...)
		{
			destroy(e);
			return "Unknown error while creating the device";
		}

		e->plugin = std::make_unique<synthLib::Plugin>(e->device, [](synthLib::Device*) {});
		e->plugin->setHostSamplerate(static_cast<float>(e->sampleRate), static_cast<float>(md::g_samplerate));
		e->plugin->setBlockSize(kBlock);
		e->plugin->reserveMidiEventCapacity();
		e->plugin->setMidiClockEnabled(false);   // no host transport on a phone

		// 3. go
		e->running = true;
		e->renderThread = std::thread(renderLoop, e);
		// let the emulator fill the buffer first: right after boot the JIT is still translating and slowest
		for(int i = 0; i < 500 && e->writePos.load(std::memory_order_acquire) < kTargetFill; ++i)
			std::this_thread::sleep_for(std::chrono::milliseconds(10));
		const auto startRes = AAudioStream_requestStart(e->stream);
		if(startRes != AAUDIO_OK)
		{
			destroy(e);
			return std::string("AAudio: could not start stream: ") + AAudio_convertResultToText(startRes);
		}

		std::lock_guard lock(g_mutex);
		destroy(g_engine);
		g_engine = e;
		LOGI("started, model=%d, samplerate=%d", model, e->sampleRate);
		return {};
	}

	void push(const Cmd& c)
	{
		std::lock_guard lock(g_mutex);
		if(!g_engine)
			return;
		std::lock_guard l(g_engine->cmdMutex);
		if(g_engine->cmds.size() < 512)
			g_engine->cmds.push_back(c);
	}
}

extern "C"
{
JNIEXPORT jstring JNICALL Java_dev_gearmulator_mdmm_NativeBridge_start(JNIEnv* env, jclass, jint model,
	jbyteArray rom, jstring romName, jstring homePath)
{
	const auto len = env->GetArrayLength(rom);
	std::vector<uint8_t> data(static_cast<size_t>(len));
	env->GetByteArrayRegion(rom, 0, len, reinterpret_cast<jbyte*>(data.data()));

	auto toStr = [&](jstring s)
	{
		const char* c = env->GetStringUTFChars(s, nullptr);
		std::string r(c);
		env->ReleaseStringUTFChars(s, c);
		return r;
	};

	const auto err = startEngine(model, data, toStr(romName), toStr(homePath));
	return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_stop(JNIEnv*, jclass)
{
	Engine* e = nullptr;
	{
		std::lock_guard lock(g_mutex);
		e = g_engine;
		g_engine = nullptr;
	}
	destroy(e);
}

JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_button(JNIEnv*, jclass, jint control, jboolean down)
{
	push({Cmd::Button, control, down ? 1 : 0, 0});
}

JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_encoder(JNIEnv*, jclass, jint enc, jint steps)
{
	const int count = std::min(std::abs(steps), 8);
	for(int i = 0; i < count; ++i)
		push({Cmd::Encoder, enc, steps > 0 ? 1 : -1, 0});
}

JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_midi(JNIEnv*, jclass, jint a, jint b, jint c)
{
	push({Cmd::Midi, a, b, c});
}

// 128x64 pixels, row-major, filled with _on / _off
JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_readLcd(JNIEnv* env, jclass, jintArray out, jint on, jint off)
{
	std::lock_guard lock(g_mutex);
	if(!g_engine || !g_engine->device)
		return;
	const auto fp = g_engine->device->getFrontPanelSnapshot();
	jint buf[md::FrontPanel::g_lcdWidth * md::FrontPanel::g_lcdHeight];
	for(uint32_t y = 0; y < md::FrontPanel::g_lcdHeight; ++y)
		for(uint32_t x = 0; x < md::FrontPanel::g_lcdWidth; ++x)
			buf[y * md::FrontPanel::g_lcdWidth + x] = fp.getLcdPixel(x, y) ? on : off;
	env->SetIntArrayRegion(out, 0, md::FrontPanel::g_lcdWidth * md::FrontPanel::g_lcdHeight, buf);
}

// out[0..15] step LEDs (0 off, 1 green, 2 red, 3 yellow), out[16..31] drum LEDs, out[32..39] status, out[40..46] mode
// Monomachine only: out[48..67] raw panel LEDs in this order: page 0..6, bank A-D, bank E-H, pattern, song, tempo,
// record, trig amp, trig filter, trig lfo, track page 0..3 (the MM uses a different LED bank layout than the MD)
JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_readLeds(JNIEnv* env, jclass, jintArray out)
{
	std::lock_guard lock(g_mutex);
	if(!g_engine || !g_engine->device)
		return;
	const auto fp = g_engine->device->getFrontPanelSnapshot();
	const bool mm = g_engine->model == md::MachineModel::Monomachine;
	jint buf[80] = {};
	for(uint32_t i = 0; i < 16; ++i)
	{
		buf[i] = mm ? static_cast<jint>(fp.getMonomachineStepLedColor(i)) : (fp.getStepLed(i) ? 1 : 0);
		buf[16 + i] = fp.getDrumLed(i) ? 1 : 0;
	}
	for(uint32_t i = 0; i < 8; ++i)
		buf[32 + i] = fp.getStatusLed(static_cast<md::FrontPanel::StatusLed>(i)) ? 1 : 0;
	for(uint32_t i = 0; i < 7; ++i)
		buf[40 + i] = fp.getModeLed(static_cast<md::FrontPanel::ModeLed>(i)) ? 1 : 0;
	if(mm)
	{
		static const uint8_t kMmLeds[20][2] =
		{
			{ 0x25, 4 }, { 0x25, 5 }, { 0x25, 6 }, { 0x25, 7 }, { 0x26, 0 }, { 0x26, 1 }, { 0x26, 2 },
			{ 0x26, 3 }, { 0x26, 4 }, { 0x26, 5 }, { 0x26, 6 }, { 0x26, 7 },
			{ 0x27, 0 }, { 0x27, 1 }, { 0x27, 2 }, { 0x27, 3 },
			{ 0x27, 4 }, { 0x27, 5 }, { 0x27, 6 }, { 0x27, 7 },
		};
		for(uint32_t i = 0; i < 20; ++i)
			buf[48 + i] = (fp.getLedBankRaw(kMmLeds[i][0]) & (1u << kMmLeds[i][1])) == 0 ? 1 : 0;   // active low
	}
	env->SetIntArrayRegion(out, 0, 80, buf);
}

// 0 = Machinedrum, 1 = Monomachine (of the running engine)
JNIEXPORT jint JNICALL Java_dev_gearmulator_mdmm_NativeBridge_model(JNIEnv*, jclass)
{
	std::lock_guard lock(g_mutex);
	return g_engine && g_engine->model == md::MachineModel::Monomachine ? 1 : 0;
}

// low 16 bits: average load in percent, high 16 bits: peak load in percent (100 = exactly real time)
JNIEXPORT jint JNICALL Java_dev_gearmulator_mdmm_NativeBridge_perf(JNIEnv*, jclass)
{
	std::lock_guard lock(g_mutex);
	if(!g_engine)
		return 0;
	const uint32_t a = std::min<uint32_t>(g_engine->loadAvg.load(), 0xFFFFu);
	const uint32_t p = std::min<uint32_t>(g_engine->loadPeak.load(), 0x7FFFu);
	return static_cast<jint>(a | (p << 16));
}

JNIEXPORT jint JNICALL Java_dev_gearmulator_mdmm_NativeBridge_underruns(JNIEnv*, jclass)
{
	std::lock_guard lock(g_mutex);
	return g_engine ? static_cast<jint>(g_engine->underruns.load()) : 0;
}
}
