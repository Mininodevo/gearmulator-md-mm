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
    private var hudText = ""
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
        bracket("MIDI", 1049f, 1266f, 70f, pRed)

        // black display area with the logo
        displayArea(301f, 88f, 1252f, 478f, null)
        elektronLogo(1120f, 150f)
        text("DATA ENTRY", 946f, 195f, pRed)
        text("LEVEL", 762f, 195f, tp(Color.WHITE, 12.5f))

        // left side
        masterVolume(185f, 167f, 30f, 222f)
        led(59, 156f, 267f); text("TEMPO", 177f, 272f, pDark, Paint.Align.LEFT)
        btn("TEMPO", 22, 185f, 312f, 74f, 42f)
        functionFrame(185f, 418f)
        btn("FUNC", 24, 185f, 426f, 70f, 36f, LIGHT)

        // data entry
        knob(8, 762f, 142f, 28f)
        val kx = floatArrayOf(762f, 882f, 1003f, 1123f)
        val white = tp(Color.WHITE, 12.5f)
        for (i in 0 until 4) {
            knob(i, kx[i], 252f, 28f); text(('A' + i).toString(), kx[i], 305f, white)
            knob(4 + i, kx[i], 362f, 28f); text(('E' + i).toString(), kx[i], 415f, white)
        }

        // edit pages
        text("EDIT", 1340f, 135f, pDark)
        btn("PG-", 41, 1340f, 193f, 70f, 38f, LIGHT)
        val pages = listOf("SYNTHESIS", "AMPLIFICATION", "FILTER", "EFFECTS", "LFO 1", "LFO 2", "LFO 3")
        for (i in pages.indices) {
            val y = 232f + i * 25.5f
            led(48 + i, 1201f, y)
            deco { c -> stroke.color = 0xFF8E8E8E.toInt(); stroke.strokeWidth = 1.4f; c.drawLine(1212f, y, 1242f, y, stroke) }
            text(pages[i], 1262f, y + 5f, pDark, Paint.Align.LEFT)
        }
        btn("PG+", 40, 1340f, 423f, 70f, 38f, LIGHT)

        // tracks
        for (t in 0 until 6) {
            val y = 122f + t * 72f
            led(16 + t, 1459f, y)
            text("TRACK", 1496f, y - 8f, pRed)
            text((t + 1).toString(), 1496f, y + 14f, tp(redText, 20f))
            btn("TRK${t + 1}", 16 + t, 1568f, y, 72f, 38f, LIGHT)
            text("M", 1623f, y - 14f, tp(Color.WHITE, 9f)); text("U", 1623f, y - 4f, tp(Color.WHITE, 9f))
            text("T", 1623f, y + 6f, tp(Color.WHITE, 9f)); text("E", 1623f, y + 16f, tp(Color.WHITE, 9f))
        }

        // kit / song setup, poly
        bracket("KIT / SONG", 100f, 176f, 535f, pDark)
        text("SETUP", 138f, 549f, pDark)
        led(57, 207f, 510f); text("PATTERN", 219f, 515f, pDark, Paint.Align.LEFT)
        led(58, 207f, 535f); text("SONG", 219f, 540f, pDark, Paint.Align.LEFT)
        btn("GLOBAL", 25, 138f, 592f, 72f, 38f)
        btn("POLY", 45, 233f, 592f, 72f, 38f)
        tag("GLOBAL", 138f, 628f, 74f); tag("POLY", 233f, 628f, 74f)

        // enter / exit / cursor keys
        btn("ENTER", 26, 355f, 520f, 70f, 38f, LIGHT)
        text("ENTER YES", 355f, 497f, pRed)
        btn("EXIT", 27, 355f, 592f, 70f, 38f, LIGHT)
        text("EXIT NO", 355f, 568f, pRed)
        btn("UP", 28, 540f, 520f, 72f, 38f)
        btn("LEFT", 30, 454f, 592f, 72f, 38f)
        btn("DOWN", 29, 540f, 592f, 72f, 38f)
        btn("RIGHT", 31, 626f, 592f, 72f, 38f)

        // bank
        text("BANK", 740f, 496f, pDark)
        btn("MUTE", 32, 740f, 522f, 72f, 38f)
        tag("MUTE", 740f, 559f, 74f)
        val bx = floatArrayOf(825f, 911f, 996f, 1082f)
        for (i in 0 until 4) {
            text(('A' + i).toString(), bx[i], 515f, pDark)
            text(('E' + i).toString(), bx[i], 540f, pDark)
        }
        led(55, 1110f, 510f); led(56, 1110f, 536f)
        val bankNames = listOf("ARP", "TRANSP", "SWING", "SLIDE")
        for (i in 0 until 4) { btn(bankNames[i], 33 + i, bx[i], 592f, 72f, 38f); tag(bankNames[i], bx[i], 628f, 74f) }

        // copy / clear / paste
        deco { c ->
            fill.color = 0xFF222222.toInt()
            val p = Path(); p.moveTo(1280f, 537f); p.lineTo(1280f, 550f); p.lineTo(1293f, 543f); p.close(); c.drawPath(p, fill)
            c.drawRect(1364f, 537f, 1376f, 549f, fill)
        }
        led(60, 1199f, 543f)
        btn("REC", 37, 1199f, 592f, 72f, 38f, RED)
        btn("PLAY", 38, 1285f, 592f, 72f, 38f)
        btn("STOP", 39, 1370f, 592f, 72f, 38f, LIGHT)
        tag("COPY", 1199f, 628f, 74f); tag("CLEAR", 1285f, 628f, 74f); tag("PASTE", 1370f, 628f, 74f)

        // trig select
        led(61, 1472f, 556f); text("AMP", 1492f, 561f, pDark, Paint.Align.LEFT)
        led(62, 1472f, 581f); text("FILTER", 1492f, 586f, pDark, Paint.Align.LEFT)
        led(63, 1472f, 607f); text("LFO", 1492f, 612f, pDark, Paint.Align.LEFT)
        text("ALL", 1492f, 633f, pDark, Paint.Align.LEFT)
        text("TRIG SELECT", 1585f, 561f, pDark)
        btn("MIDI SEQ", 44, 1585f, 592f, 72f, 38f)
        tag("MIDI SEQ", 1585f, 628f, 74f)

        // trig keys
        for (i in 0 until 16) {
            val x = 168f + i * 85.7f
            led(i, x, 681f)
            btn((i + 1).toString(), i, x, 737f, 72f, 72f)
            trigTag(i + 1, null, x, 790f, 74f)
        }

        // 1/64 and scale setup
        bracket("1/64", 1524f, 1610f, 691f, pDark, true)
        val px = floatArrayOf(1532f, 1558f, 1583f, 1609f)
        for (i in 0 until 4) { led(64 + i, px[i], 707f); text("${i + 1}:4", px[i], 727f, tp(0xFF444444.toInt(), 11f)) }
        btn("SCALE", 42, 1572f, 754f, 72f, 38f)
        tag("SCALE SETUP", 1572f, 790f, 78f)
    }

    // ------------------------------------------------------------------ static layer

    private fun rebuildStatic(w: Int, h: Int) {
        staticBmp?.recycle()
        staticBmp = null
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(bgPaint)
        c.translate(offX, offY)
        c.scale(scale, scale)

        drawPlate(c)
        for (d in decor) d(c)
        for (k in knobs) drawKnobBody(c, k)
        for (b in buttons) drawBtn(c, b)
        staticBmp = bmp
    }

    private fun drawPlate(c: Canvas) {
        // drop shadow, then the flat faceplate colour of the skin
        fill.color = 0xFF000000.toInt()
        c.drawRect(plate.left - 3f, plate.top - 3f, plate.right + 3f, plate.bottom + 3f, fill)
        fill.color = plateColor
        c.drawRect(plate, fill)
        stroke.color = plateEdge; stroke.strokeWidth = 3f
        c.drawRect(plate, stroke)
    }

    private fun drawKnobBody(c: Canvas, k: Knob) {
        val kb = knobBmp
        if (k.kind == K_JOG && wheelBmp != null) return          // drawn per frame in onDraw
        if (kb != null) {
            fill.color = 0x40000000
            c.drawCircle(k.cx + 2f, k.cy + 3f, k.rad + 2f, fill)
            dstRect.set(k.cx - k.rad - 3f, k.cy - k.rad - 3f, k.cx + k.rad + 3f, k.cy + k.rad + 3f)
            c.drawBitmap(kb, null, dstRect, bmpPaint)
            return
        }
        // soft shadow
        fill.color = 0x40000000
        c.drawCircle(k.cx + 2f, k.cy + 3f, k.rad + 2f, fill)
        fill.shader = RadialGradient(
            k.cx - k.rad * 0.3f, k.cy - k.rad * 0.35f, k.rad * 1.45f,
            intArrayOf(0xFF5A5A62.toInt(), 0xFF1E1E22.toInt(), 0xFF09090B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(k.cx, k.cy, k.rad, fill)
        fill.shader = null
        c.drawCircle(k.cx, k.cy, k.rad, knobRing)
    }

    private fun drawBtn(c: Canvas, b: Btn) {
        val r = b.r
        val (top, bottom) = when (b.style) {
            LIGHT -> 0xFFF0F0EA.toInt() to 0xFFD6D5CB.toInt()
            RED -> 0xFFD65540.toInt() to 0xFFA63C2B.toInt()
            else -> 0xFF4A4C46.toInt() to 0xFF2B2D29.toInt()
        }
        fill.color = 0x38000000
        c.drawRoundRect(r.left + 1.5f, r.top + 3f, r.right + 1.5f, r.bottom + 3f, 7f, 7f, fill)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 7f, 7f, fill)
        fill.shader = null
        stroke.color = if (b.style == DARK) 0xFF08080B.toInt() else 0xFF222226.toInt()
        stroke.strokeWidth = if (b.style == DARK) 2f else 3.4f
        val inset = stroke.strokeWidth / 2f
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, 7f, 7f, stroke)

        val cx = r.centerX(); val cy = r.centerY(); val a = 9f
        when (b.id) {
            28 -> tri(c, cx - a, cy + a * 0.7f, cx + a, cy + a * 0.7f, cx, cy - a)
            29 -> tri(c, cx - a, cy - a * 0.7f, cx + a, cy - a * 0.7f, cx, cy + a)
            30 -> tri(c, cx + a * 0.7f, cy - a, cx + a * 0.7f, cy + a, cx - a, cy)
            31 -> tri(c, cx - a * 0.7f, cy - a, cx - a * 0.7f, cy + a, cx + a, cy)
        }
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        arrowPath.rewind()
        arrowPath.moveTo(x1, y1); arrowPath.lineTo(x2, y2); arrowPath.lineTo(x3, y3); arrowPath.close()
        c.drawPath(arrowPath, whiteFill)
    }

    // ------------------------------------------------------------------ refresh loop

    private val refresh = object : Runnable {
        override fun run() {
            NativeBridge.readLcd(lcdPixels, lcdOn, lcdOff)
            NativeBridge.readLeds(leds)

            if (!lcdPixels.contentEquals(lcdPrev)) {
                System.arraycopy(lcdPixels, 0, lcdPrev, 0, lcdPixels.size)
                lcdBitmap.setPixels(lcdPixels, 0, 128, 0, 0, 128, 64)
                dirty = true
            }
            if (!leds.contentEquals(ledsPrev)) {
                System.arraycopy(leds, 0, ledsPrev, 0, leds.size)
                dirty = true
            }

            // HUD text only rebuilt twice per second (no String garbage every frame)
            val now = System.currentTimeMillis()
            if (now - lastHudMs >= 500) {
                lastHudMs = now
                val perf = NativeBridge.perf()
                val s = "underruns: ${NativeBridge.underruns()}   load: ${perf and 0xFFFF}%   peak: ${perf ushr 16}%"
                if (s != hudText) { hudText = s; dirty = true }
            }

            if (dirty) { dirty = false; invalidate() }
            // ~30 fps is plenty for an LCD and LEDs; redrawing at 120 Hz steals CPU from the emulator
            postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = min(w / W, h / H)
        offX = (w - W * scale) / 2f
        offY = (h - H * scale) / 2f
        rebuildStatic(w, h)
        dirty = true
    }

    // ------------------------------------------------------------------ dynamic layer

    override fun onDraw(c: Canvas) {
        val sb = staticBmp
        if (sb != null) c.drawBitmap(sb, 0f, 0f, null) else c.drawPaint(bgPaint)

        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)

        c.drawBitmap(lcdBitmap, null, lcdRect, lcdPaint)

        // knob indicators (endless encoders, so they rotate freely)
        for (k in knobs) {
            val wb = wheelBmp
            if (k.kind == K_JOG && wb != null) {
                // jog wheel: 48 pre-rendered frames over 360 degrees (7.5 degrees each)
                val a = ((k.angle % 360f) + 360f) % 360f
                val idx = Math.round(a / 7.5f) % 48
                val sx = (idx % 8) * 128
                val sy = (idx / 8) * 128
                srcRect.set(sx, sy, sx + 128, sy + 128)
                fill.color = 0x40000000
                c.drawCircle(k.cx + 2f, k.cy + 3f, k.rad + 2f, fill)
                dstRect.set(k.cx - k.rad - 3f, k.cy - k.rad - 3f, k.cx + k.rad + 3f, k.cy + k.rad + 3f)
                c.drawBitmap(wb, srcRect, dstRect, bmpPaint)
                continue
            }
            c.save()
            c.rotate(k.angle, k.cx, k.cy)
            if (k.kind == K_JOG) c.drawCircle(k.cx, k.cy - k.rad * 0.66f, 17f, dimple)
            else c.drawCircle(k.cx, k.cy - k.rad * 0.72f, 3.6f, dot)
            c.restore()
        }

        // pressed buttons
        for (b in buttons) if (b.id in pressed) c.drawRoundRect(b.r, 7f, 7f, pressPaint)

        // LEDs
        for (l in ledList) {
            val v = leds[l.idx]
            val stepMm = mm && l.idx < 16
            if (v != 0) {
                val gi = if (stepMm) v.coerceIn(1, 3) else 0
                c.save(); c.translate(l.x, l.y); c.drawCircle(0f, 0f, 17f, glows[gi]); c.restore()
                ledPaint.color = when {
                    !stepMm -> ledOn
                    v == 1 -> 0xFF9FC568.toInt()
                    v == 2 -> 0xFFE65D42.toInt()
                    else -> 0xFFE4C45C.toInt()
                }
            } else {
                ledPaint.color = ledOff
            }
            c.drawCircle(l.x, l.y, 7f, ledPaint)
            c.drawCircle(l.x, l.y, 7f, ledRing)
        }

        pHud.textAlign = Paint.Align.LEFT
        c.drawText(hudText, 12f, 880f, pHud)
        c.restore()
    }

    // ------------------------------------------------------------------ touch

    private fun toLogical(x: Float, y: Float): PointF {
        tmpPoint.set((x - offX) / scale, (y - offY) / scale)
        return tmpPoint
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val p = toLogical(ev.getX(i), ev.getY(i))
                val id = ev.getPointerId(i)
                buttons.firstOrNull { it.r.contains(p.x, p.y) }?.let {
                    active[id] = Target.B(it)
                    pressed += it.id
                    NativeBridge.button(it.id, true)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dirty = true
                } ?: knobs.firstOrNull { k ->
                    val dx = p.x - k.cx; val dy = p.y - k.cy
                    k.kind != K_DECO && dx * dx + dy * dy <= (k.rad + 12f) * (k.rad + 12f)
                }?.let { active[id] = Target.K(it, p.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val t = active[ev.getPointerId(i)] as? Target.K ?: continue
                    val y = toLogical(0f, ev.getY(i)).y
                    t.acc += t.lastY - y            // up = clockwise
                    t.lastY = y
                    val steps = (t.acc / 10f).toInt()
                    if (steps != 0) {
                        t.acc -= steps * 10f
                        t.k.angle = (t.k.angle + steps * 8f) % 360f
                        NativeBridge.encoder(t.k.id, steps)
                        dirty = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) -1 else ev.actionIndex
                if (idx < 0) releaseAll() else release(ev.getPointerId(idx))
            }
        }
        return true
    }

    private fun release(pointerId: Int) {
        val t = active.remove(pointerId)
        if (t is Target.B) {
            pressed -= t.b.id
            NativeBridge.button(t.b.id, false)
            dirty = true
        }
    }

    private fun releaseAll() = active.keys.toList().forEach { release(it) }
}
