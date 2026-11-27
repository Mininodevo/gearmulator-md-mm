package dev.gearmulator.mdmm

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Front panel of the Machinedrum or the Monomachine, laid out like the real hardware in a
 * 1700x890 logical space that is scaled to fit. The model is read from the running engine.
 *
 * Buttons are momentary and multi-touch (hold FUNCTION with one finger, press with another).
 * Knobs turn by dragging up/down.
 *
 * Performance: everything that never changes (silver plate, labels, knob bodies, idle buttons) is rendered once
 * into a bitmap. Each frame only draws the LCD, knob indicators, pressed buttons, LEDs and the HUD, and a frame
 * is only requested when something changed.
 */
class PanelView(context: Context) : View(context) {

    private companion object {
        const val W = 1700f
        const val H = 890f
        const val DARK = 0
        const val LIGHT = 1
        const val RED = 2
        const val K_DATA = 0
        const val K_JOG = 1
        const val K_DECO = 2
    }

    private class Btn(val label: String, val id: Int, val r: RectF, val style: Int)
    private class Knob(val id: Int, val cx: Float, val cy: Float, val rad: Float, val kind: Int, var angle: Float = 0f)
    private class Led(val idx: Int, val x: Float, val y: Float)
    private sealed class Target {
        class B(val b: Btn) : Target()
        class K(val k: Knob, var lastY: Float, var acc: Float = 0f) : Target()
    }

    private val mm = NativeBridge.model() == 1

    private val buttons = ArrayList<Btn>()
    private val knobs = ArrayList<Knob>()
    private val ledList = ArrayList<Led>()
    private val decor = ArrayList<(Canvas) -> Unit>()
    private val plate = RectF()
    private val lcdRect = RectF()

    private val lcdPixels = IntArray(128 * 64)
    private val lcdPrev = IntArray(128 * 64)
    private val lcdBitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val leds = IntArray(80)
    private val ledsPrev = IntArray(80)

    private val active = HashMap<Int, Target>()
    private val pressed = HashSet<Int>()

    // ------------------------------------------------------------------ colours and paints

    // Colours taken from the debranded desktop skins (mmSfx60 / mdDefault).
    private val plateColor = if (mm) 0xFFD4D5D0.toInt() else 0xFFD0D1CC.toInt()
    private val plateEdge = if (mm) 0xFF74756F.toInt() else 0xFF747570.toInt()
    private val ruleColor = 0xFF898A84.toInt()
    private val panelBlack = 0xFF20221F.toInt()
    private val panelLight = 0xFFE0E1DC.toInt()
    private val textDark = 0xFF353630.toInt()
    private val brown = if (mm) 0xFF7B4035.toInt() else 0xFF6B382E.toInt()
    private val redText = if (mm) 0xFFA04D39.toInt() else 0xFF8D493C.toInt()
    private val ledOn = if (mm) 0xFFE45B3C.toInt() else 0xFFF05A3D.toInt()
    private val ledOff = if (mm) 0xFF726B58.toInt() else 0xFF713C31.toInt()
    private val ledRingColor = if (mm) 0xFF3A3B36.toInt() else 0xFF2B0E0A.toInt()
    private val lcdOn = 0xFF1B2018.toInt()
    private val lcdOff = 0xFFC0C9BA.toInt()

    // skin images (assets/skins); null = fall back to the drawn look
    private fun asset(name: String): Bitmap? = try {
        context.assets.open("skins/$name").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    private val knobBmp: Bitmap? = asset(if (mm) "mm_knob.png" else "md_knob.png")
    private val screwBmp: Bitmap? = asset(if (mm) "mm_screw.png" else "md_screw.png")
    private val wheelBmp: Bitmap? = if (mm) null else asset("md_wheel.png")   // 8x6 frames of 128 px, 360 degrees
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private fun tp(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = condensed; textAlign = Paint.Align.CENTER
    }

    private val pRed = tp(redText, 13.5f)
    private val pDark = tp(textDark, 13f)
    private val pWhite = tp(Color.WHITE, 12f)
    private val pBrown = tp(brown, 12f)
    private val pTitle = tp(Color.WHITE, 30f)
    private val pElektron = tp(0xFFC2CCDA.toInt(), 66f).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC) }
    private val pHud = tp(0xFF888888.toInt(), 13f)

    private val bgPaint = Paint().apply { color = 0xFF111113.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFF050506.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dimple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFB030.toInt() }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; color = ledRingColor }
    private val lcdPaint = Paint().apply { isFilterBitmap = false }
    private val arrowPath = Path()
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // soft glow around lit LEDs: [0] normal colour, [1] green, [2] red, [3] yellow (Monomachine step LEDs)
    private val glows = Array(4) { i ->
        val col = when (i) { 1 -> 0xFF9FC568.toInt(); 2 -> 0xFFE65D42.toInt(); 3 -> 0xFFE4C45C.toInt(); else -> ledOn }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 17f, (col and 0x00FFFFFF) or 0x90000000.toInt(), col and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
    }

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var staticBmp: Bitmap? = null
    private val buildKind = if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "DEBUG" else "release"
    private var hudText = ""
    private var hudText2 = ""
    private var lastHudMs = 0L
    private var dirty = true
    private val tmpPoint = PointF()

    init {
        if (mm) buildMm() else buildMd()
    }

    // ------------------------------------------------------------------ layout registration helpers

    private fun deco(f: (Canvas) -> Unit) { decor.add(f) }

    private fun btn(label: String, id: Int, cx: Float, cy: Float, w: Float, h: Float, style: Int = DARK) {
        buttons.add(Btn(label, id, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), style))
    }

    private fun knob(id: Int, cx: Float, cy: Float, rad: Float, kind: Int = K_DATA, angle: Float = 0f) {
        knobs.add(Knob(id, cx, cy, rad, kind, angle))
    }

    private fun led(idx: Int, x: Float, y: Float) { ledList.add(Led(idx, x, y)) }

    private fun text(s: String, x: Float, y: Float, p: Paint, align: Paint.Align = Paint.Align.CENTER) {
        deco { c -> p.textAlign = align; c.drawText(s, x, y, p) }
    }

    private fun vline(x: Float, y1: Float, y2: Float) = deco { c -> stroke.color = ruleColor; stroke.strokeWidth = 1.6f; c.drawLine(x, y1, x, y2, stroke) }
    private fun hline(y: Float, x1: Float, x2: Float) = deco { c -> stroke.color = ruleColor; stroke.strokeWidth = 1.6f; c.drawLine(x1, y, x2, y, stroke) }

    /** label with a line on either side, like "—— MAIN OUT ——" or the bracketed "DATA ENTRY" */
    private fun bracket(s: String, x1: Float, x2: Float, y: Float, p: Paint = pDark, ticks: Boolean = false) {
        deco { c ->
            p.textAlign = Paint.Align.CENTER
            val mid = (x1 + x2) / 2f
            val half = p.measureText(s) / 2f + 7f
            stroke.color = redText; stroke.strokeWidth = 1.5f
            c.drawLine(x1, y - 4f, mid - half, y - 4f, stroke)
            c.drawLine(mid + half, y - 4f, x2, y - 4f, stroke)
            if (ticks) {
                c.drawLine(x1, y - 4f, x1, y + 8f, stroke)
                c.drawLine(x2, y - 4f, x2, y + 8f, stroke)
            }
            c.drawText(s, mid, y, p)
        }
    }

    /** brown name plate under a button */
    private fun tag(s: String, cx: Float, cy: Float, w: Float) {
        deco { c ->
            val r = RectF(cx - w / 2, cy - 9f, cx + w / 2, cy + 9f)
            fill.color = brown
            c.drawRect(r, fill)
            pWhite.textAlign = Paint.Align.CENTER
            c.drawText(s, cx, cy + 4.5f, pWhite)
        }
    }

    /** number plate of a trig key: filled brown for 1/5/9/13, outlined otherwise */
    private fun trigTag(num: Int, name: String?, cx: Float, cy: Float, w: Float) {
        val filled = (num - 1) % 4 == 0
        deco { c ->
            val r = RectF(cx - w / 2, cy - 9f, cx + w / 2, cy + 9f)
            if (filled) {
                fill.color = brown; c.drawRect(r, fill)
            } else {
                stroke.color = brown; stroke.strokeWidth = 1.5f; c.drawRect(r, stroke)
            }
            val p = if (filled) pWhite else pBrown
            if (name == null) {
                p.textAlign = Paint.Align.CENTER
                c.drawText(num.toString(), cx, cy + 4.5f, p)
            } else {
                p.textAlign = Paint.Align.LEFT
                c.drawText(num.toString(), r.left + 5f, cy + 4.5f, p)
                p.textAlign = Paint.Align.RIGHT
                c.drawText(name, r.right - 5f, cy + 4.5f, p)
                p.textAlign = Paint.Align.CENTER
            }
        }
    }

    /** light grey plate with dark text (MD sound selection names) */
    private fun nameBox(s: String, cx: Float, cy: Float, w: Float) {
        deco { c ->
            val r = RectF(cx - w / 2, cy - 9f, cx + w / 2, cy + 9f)
            fill.color = 0xFFE2E1D9.toInt(); c.drawRect(r, fill)
            stroke.color = 0xFF686963.toInt(); stroke.strokeWidth = 1.2f; c.drawRect(r, stroke)
            pDark.textAlign = Paint.Align.CENTER
            c.drawText(s, cx, cy + 4.5f, pDark)
        }
    }

    private fun screw(x: Float, y: Float) {
        deco { c ->
            val sb = screwBmp
            if (sb != null) {
                dstRect.set(x - 22f, y - 22f, x + 22f, y + 22f)
                c.drawBitmap(sb, null, dstRect, bmpPaint)
                return@deco
            }
            fill.shader = RadialGradient(x - 5f, y - 5f, 26f, intArrayOf(0xFFEDEDED.toInt(), 0xFF9A9AA0.toInt(), 0xFF5E5E64.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, 19f, fill)
            fill.shader = null
            stroke.color = 0xFF3C3C40.toInt(); stroke.strokeWidth = 2f
            c.drawCircle(x, y, 19f, stroke)
            stroke.strokeWidth = 3.2f
            c.drawLine(x - 9f, y - 9f, x + 9f, y + 9f, stroke)
            c.drawLine(x - 9f, y + 9f, x + 9f, y - 9f, stroke)
            c.drawLine(x - 12f, y, x + 12f, y, stroke)
            c.drawLine(x, y - 12f, x, y + 12f, stroke)
        }
    }

    private fun functionFrame(cx: Float, cy: Float) {
        deco { c ->
            fill.color = brown
            c.drawRect(cx - 45f, cy - 38f, cx + 45f, cy + 32f, fill)
            pWhite.textAlign = Paint.Align.CENTER
            c.drawText("FUNCTION", cx, cy - 22f, pWhite)
        }
    }

    private fun masterVolume(cx: Float, cy: Float, rad: Float, labelY: Float) {
        knob(-1, cx, cy, rad, K_DECO)
        text("I", cx, cy - rad - 14f, pDark)
        deco { c ->
            fill.color = 0xFF222226.toInt()
            c.drawCircle(cx - rad - 8f, cy + rad * 0.55f, 3.5f, fill)
            c.drawCircle(cx + rad + 8f, cy + rad * 0.55f, 3.5f, fill)
        }
        text("MASTER VOLUME", cx, labelY, pDark)
    }

    /** the black area around the LCD, fading to light on the upper right like the real front panel */
    private fun displayArea(l: Float, t: Float, r: Float, b: Float, whiteFrom: Float?) {
        deco { c ->
            c.save()
            c.clipRect(l, t, r, b)
            fill.color = panelBlack
            c.drawRect(l, t, r, b, fill)
            if (whiteFrom != null) {
                fill.color = panelLight
                c.drawRect(l, whiteFrom, r, b, fill)
            }
            fill.shader = LinearGradient(r - 270f, t, r + 10f, t + 190f, 0x00D0D1CC, plateColor, Shader.TileMode.CLAMP)
            c.drawRect(r - 270f, t, r, t + 190f, fill)
            fill.shader = null
            c.restore()
        }
    }

    // the skins are debranded: no manufacturer logo
    @Suppress("UNUSED_PARAMETER")
    private fun elektronLogo(cx: Float, y: Float) {}

    // ------------------------------------------------------------------ Machinedrum layout

    private fun buildMd() {
        plate.set(60f, 33f, 1649f, 857f)
        lcdRect.set(608f, 226f, 938f, 391f)

        listOf(100f to 62f, 970f to 62f, 1607f to 62f, 100f to 831f, 852f to 831f, 1607f to 831f).forEach { screw(it.first, it.second) }

        // panel sections
        vline(540f, 33f, 660f); vline(1008f, 33f, 660f); hline(172f, 1008f, 1649f)
        hline(485f, 60f, 1649f); hline(660f, 60f, 1649f); vline(1370f, 485f, 660f); hline(88f, 540f, 1008f)
        vline(540f, 485f, 660f); vline(1008f, 485f, 660f)

        // jack labels along the top
        listOf("PHONES" to 243f, "A / LEFT" to 328f, "B / RIGHT" to 413f, "C" to 497f, "D" to 582f, "E" to 664f, "F" to 748f,
            "A" to 833f, "B" to 914f, "IN" to 1070f, "OUT" to 1165f, "THRU" to 1257f, "DC IN" to 1392f, "POWER" to 1530f)
            .forEach { text(it.first, it.second, 52f, pRed) }
        bracket("MAIN OUT", 308f, 405f, 70f, pRed)
        bracket("INPUT", 812f, 940f, 70f, pRed)
        bracket("MIDI", 1070f, 1280f, 70f, pRed)

        // display area
        displayArea(541f, 88f, 1005f, 480f, 365f)
        elektronLogo(1400f, 130f)

        // left side: master volume, classic/extended, sound selection, jog wheel, function
        masterVolume(143f, 135f, 31f, 188f)
        led(40, 115f, 226f); led(41, 115f, 249f)
        text("CLASSIC", 130f, 231f, pDark, Paint.Align.LEFT)
        text("EXTENDED", 130f, 254f, pDark, Paint.Align.LEFT)
        btn("CLS/EXT", 46, 143f, 298f, 80f, 42f)
        bracket("SOUND SELECTION", 235f, 476f, 119f, pDark, true)
        val row1 = listOf("BD", "SD", "HT", "MD", "LT", "CP", "RS", "CB")
        val row2 = listOf("CH", "OH", "RC", "CC", "M1", "M2", "M3", "M4")
        for (i in 0 until 8) {
            val x = 249f + i * 30.4f
            led(16 + i, x, 140f); nameBox(row1[i], x, 163f, 27f)
            led(24 + i, x, 190f); nameBox(row2[i], x, 212f, 27f)
        }
        knob(9, 355f, 347f, 98f, K_JOG, 233f)
        functionFrame(143f, 423f)
        btn("FUNC", 24, 143f, 428f, 76f, 36f, LIGHT)

        // data entry
        bracket("DATA ENTRY", 1186f, 1610f, 207f, pDark)
        val kx = floatArrayOf(1214f, 1335f, 1457f, 1578f)
        for (i in 0 until 4) {
            knob(i, kx[i], 255f, 31f); tag(('A' + i).toString(), kx[i], 302f, 26f)
            knob(4 + i, kx[i], 366f, 31f); tag(('E' + i).toString(), kx[i], 413f, 26f)
        }
        knob(8, 1081f, 255f, 31f)
        text("LEVEL", 1081f, 305f, pDark)
        led(45, 1054f, 329f); text("TEMPO", 1077f, 333f, pDark, Paint.Align.LEFT)
        btn("TEMPO", 22, 1081f, 377f, 78f, 42f)
        led(39, 1245f, 452f); text("SYNTHESIS", 1258f, 457f, pDark, Paint.Align.LEFT)
        led(38, 1329f, 452f); text("EFFECTS", 1344f, 457f, pDark, Paint.Align.LEFT)
        led(37, 1412f, 452f); text("ROUTING", 1427f, 457f, pDark, Paint.Align.LEFT)
        btn("S/E/R", 23, 1542f, 452f, 76f, 42f, LIGHT)
        text("L", 1596f, 443f, pDark); text("F", 1596f, 455f, pDark); text("O", 1596f, 467f, pDark)

        // pattern selection / bank group
        btn("GRP", 32, 128f, 527f, 78f, 42f)
        text("BANK GROUP", 130f, 572f, pDark)
        bracket("PATTERN SELECTION", 90f, 522f, 501f, pDark, true)
        val bx = floatArrayOf(210f, 297f, 383f, 469f)
        for (i in 0 until 4) {
            text(('A' + i).toString(), bx[i], 519f, pDark)
            text(('E' + i).toString(), bx[i], 544f, pDark)
        }
        led(42, 512f, 515f); led(43, 512f, 539f)
        val bankNames = listOf("MUTE", "ACCENT", "SWING", "SLIDE")
        for (i in 0 until 4) { btn(bankNames[i], 33 + i, bx[i], 600f, 78f, 42f); tag(bankNames[i], bx[i], 636f, 74f) }

        // enter / exit / cursor keys
        btn("ENTER", 26, 665f, 527f, 76f, 42f, LIGHT)
        text("ENTER", 592f, 522f, pRed); text("YES", 596f, 540f, pDark)
        btn("EXIT", 27, 665f, 599f, 76f, 42f, LIGHT)
        text("EXIT", 597f, 595f, pRed); text("NO", 599f, 613f, pDark)
        btn("UP", 28, 854f, 527f, 78f, 42f)
        btn("LEFT", 30, 767f, 600f, 78f, 42f)
        btn("DOWN", 29, 854f, 600f, 78f, 42f)
        btn("RIGHT", 31, 941f, 600f, 78f, 42f)

        // copy / clear / paste
        deco { c ->
            fill.color = 0xFF222222.toInt()
            c.drawCircle(1114f, 558f, 6f, fill)
            val p = Path(); p.moveTo(1197f, 551f); p.lineTo(1197f, 565f); p.lineTo(1210f, 558f); p.close(); c.drawPath(p, fill)
            c.drawRect(1281f, 552f, 1293f, 564f, fill)
        }
        led(44, 1046f, 600f)
        btn("REC", 37, 1114f, 599f, 76f, 42f, RED)
        btn("PLAY", 38, 1201f, 599f, 76f, 42f)
        btn("STOP", 39, 1287f, 599f, 76f, 42f, LIGHT)
        tag("COPY", 1114f, 636f, 74f); tag("CLEAR", 1201f, 636f, 74f); tag("PASTE", 1287f, 636f, 74f)

        // pattern / song, global, kit / song setup
        led(35, 1428f, 528f); text("PATTERN", 1442f, 533f, pDark, Paint.Align.LEFT)
        led(36, 1428f, 551f); text("SONG", 1442f, 556f, pDark, Paint.Align.LEFT)
        bracket("KIT", 1522f, 1600f, 562f, pDark)
        btn("GLOBAL", 43, 1456f, 600f, 78f, 42f)
        btn("SONG SETUP", 25, 1560f, 600f, 78f, 42f)
        tag("GLOBAL", 1459f, 636f, 74f); tag("SONG SETUP", 1560f, 636f, 82f)

        // trig keys
        val names = listOf("BD", "SD", "HT", "MT", "LT", "CP", "RS", "CB", "CH", "OH", "RC", "CC", "M1", "M2", "M3", "M4")
        for (i in 0 until 16) {
            val x = 158f + i * 86.7f
            led(i, x, 688f)
            btn((i + 1).toString(), i, x, 746f, 76f, 76f)
            trigTag(i + 1, names[i], x, 799f, 76f)
        }

        // 1/64 and scale setup
        bracket("1/64", 1512f, 1608f, 692f, pDark, true)
        val px = floatArrayOf(1520f, 1547f, 1574f, 1601f)
        val pIdx = intArrayOf(32, 33, 34, 46)
        for (i in 0 until 4) { led(pIdx[i], px[i], 713f); text("${i + 1}:4", px[i], 733f, tp(0xFF444444.toInt(), 11f)) }
        btn("SCALE", 42, 1560f, 762f, 78f, 42f)
        tag("SCALE SETUP", 1560f, 799f, 82f)
    }

    // ------------------------------------------------------------------ Monomachine layout

    private fun buildMm() {
        plate.set(74f, 33f, 1650f, 848f)
        lcdRect.set(343f, 223f, 667f, 385f)

        listOf(108f to 62f, 983f to 62f, 1614f to 62f, 108f to 820f, 852f to 820f, 1614f to 820f).forEach { screw(it.first, it.second) }

        // panel sections
        vline(301f, 33f, 660f)
        hline(482f, 74f, 1650f); hline(660f, 74f, 1650f)
        vline(682f, 482f, 660f); vline(1138f, 482f, 660f); vline(1432f, 88f, 660f)
        vline(1253f, 88f, 478f)
        hline(88f, 301f, 1650f)
        for (k in 1..5) hline(88f + k * 72f, 1432f, 1650f)
        hline(520f, 1432f, 1650f)

        listOf("PHONES" to 236f, "A / LEFT" to 320f, "B / RIGHT" to 403f, "C" to 486f, "D" to 569f, "E" to 652f, "F" to 736f,
            "A" to 819f, "B" to 903f, "IN" to 1060f, "OUT" to 1153f, "THRU" to 1245f, "DC IN" to 1420f, "POWER" to 1573f)
            .forEach { text(it.first, it.second, 52f, pRed) }
        bracket("MAIN OUT", 293f, 432f, 70f, pRed)
        bracket("INPUT", 800f, 920f, 70f, pRed)
   