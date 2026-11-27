package dev.gearmulator.mdmm

object NativeBridge {
    init { System.loadLibrary("mdmm") }

    /** model: 0 = Machinedrum, 1 = Monomachine. Returns an error message, or null on success. Blocks while the firmware boots. */
    @JvmStatic external fun start(model: Int, rom: ByteArray, romName: String, homePath: String): String?
    @JvmStatic external fun stop()

    /** [control] is the ordinal of md::PanelControl (see PanelView). */
    @JvmStatic external fun button(control: Int, down: Boolean)
    /** [encoder] is the ordinal of md::PanelEncoder; positive steps = clockwise. */
    @JvmStatic external fun encoder(encoder: Int, steps: Int)
    @JvmStatic external fun midi(a: Int, b: Int, c: Int)

    @JvmStatic external fun readLcd(out: IntArray, on: Int, off: Int)
    /** [out] must hold 80 ints: 0..15 steps, 16..31 drum LEDs, 32..39 status, 40..46 mode (MD), 48..67 raw panel LEDs (MM) */
    @JvmStatic external fun readLeds(out: IntArray)
    /** 0 = Machinedrum, 1 = Monomachine (the running engine) */
    @JvmStatic external fun model(): Int
    @JvmStatic external fun underruns(): Int
    /** low 16 bits: average emulation load in %, high 16 bits: peak load in % (100 = exactly real time) */
    @JvmStatic external fun perf(): Int
}
