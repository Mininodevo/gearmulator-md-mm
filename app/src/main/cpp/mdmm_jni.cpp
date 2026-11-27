// JNI bridge: runs the Machinedrum / Monomachine emulation (md::Device inside synthLib::Plugin)
// and plays it through AAudio. A render thread produces audio into a ring buffer so that the
// (not realtime-safe) emulator never runs inside the AAudio callback. UI input is queued and
// applied by the render thread.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
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
	constexpr uint32_t kTargetFill = 2048;    // frames we try to keep buffered (~43 ms @ 48k)

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

	void renderLoop(Engine* e)
	{
		std::vector<float> l(kBlock), r(kBlock);
		synthLib::TAudioInputs in{};
		synthLib::TAudioOutputs out{};
		out[0] = l.data();
		out[1] = r.data();

		while(e->running.load(std::memory_order_relaxed))
		{
			const uint64_t w = e->writePos.load(std::memory_order_relaxed);
			const uint64_t rd = e->readPos.load(std::memory_order_acquire);

			if((w - rd) + kBlock > kTargetFill)
			{
				std::this_thread::sleep_for(std::chrono::milliseconds(1));
				continue;
			}

			drainCommands(*e);
			e->plugin->process(in, out, kBlock, 120.0, 0.0, false, false);

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
		const int32_t n = static_cast<int32_t>(std::min<uint64_t>(w - rd, static_cast<uint64_t>(frames)));

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
JNIEXPORT void JNICALL Java_dev_gearmulator_mdmm_NativeBridge_readLeds(JNIEnv* env, jclass, jintArray out)
{
	std::lock_guard lock(g_mutex);
	if(!g_engine || !g_engine->device)
		return;
	const auto fp = g_engine->device->getFrontPanelSnapshot();
	const bool mm = g_engine->model == md::MachineModel::Monomachine;
	jint buf[48] = {};
	for(uint32_t i = 0; i < 16; ++i)
	{
		buf[i] = mm ? static_cast<jint>(fp.getMonomachineStepLedColor(i)) : (fp.getStepLed(i) ? 1 : 0);
		buf[16 + i] = fp.getDrumLed(i) ? 1 : 0;
	}
	for(uint32_t i = 0; i < 8; ++i)
		buf[32 + i] = fp.getStatusLed(static_cast<md::FrontPanel::StatusLed>(i)) ? 1 : 0;
	for(uint32_t i = 0; i < 7; ++i)
		buf[40 + i] = fp.getModeLed(static_cast<md::FrontPanel::ModeLed>(i)) ? 1 : 0;
	env->SetIntArrayRegion(out, 0, 48, buf);
}

JNIEXPORT jint JNICALL Java_dev_gearmulator_mdmm_NativeBridge_underruns(JNIEnv*, jclass)
{
	std::lock_guard lock(g_mutex);
	return g_engine ? static_cast<jint>(g_engine->underruns.load()) : 0;
}
}
