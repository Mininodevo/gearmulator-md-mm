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
    @JvmStatic external fun readLeds(out: IntArray)
    @JvmStatic external fun underruns(): Int
}
