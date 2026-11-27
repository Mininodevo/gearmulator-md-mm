package dev.gearmulator.mdmm

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The whole front panel, drawn in a 980x580 logical space and scaled to fit.
 * Buttons are momentary and multi-touch (hold FUNC with one finger, press with another).
 * Knobs turn by dragging up/down.
 */
class PanelView(context: Context) : View(context) {

    private class Btn(val label: String, val id: Int, val r: RectF)
    private class Knob(val label: String, val id: Int, val cx: Float, val cy: Float, val rad: Float)
    private sealed class Target {
        class B(val b: Btn) : Target()
        class K(val k: Knob, var lastY: Float, var acc: Float = 0f) : Target()
    }

    private val buttons = ArrayList<Btn>()
    private val knobs = ArrayList<Knob>()
    private val lcdRect = RectF(20f, 20f, 532f, 276f)           // 128x64 at 4x
    private val triggerIds = 0..15

    private val lcdPixels = IntArray(128 * 64)
    private val lcdBitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val leds = IntArray(48)

    private val active = HashMap<Int, Target>()
    private val pressed = HashSet<Int>()

    private val bg = Paint().apply { color = 0xFF1B1B1D.toInt() }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A40.toInt() }
    private val btnDown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0A030.toInt() }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55555C.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15f; textAlign = Paint.Align.CENTER
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lcdPaint = Paint().apply { isFilterBitmap = false }
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 12f }

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    init {
        // data entry A..H, then LEVEL and SOUND SELECTION (md::PanelEncoder ordinals 0..9)
        for (i in 0 until 8) knobs += Knob(('A' + i).toString(), i, 52f + i * 64f, 330f, 26f)
        knobs += Knob("LEVEL", 8, 600f, 80f, 34f)
        knobs += Knob("SOUND", 9, 700f, 80f, 34f)

        // md::PanelControl ordinals, see mdpanel.h
        val grid = listOf(
            listOf("FUNC" to 24, "KIT" to 25, "ENTER" to 26, "EXIT" to 27, "REC" to 37, "PLAY" to 38),
            listOf("◄" to 30, "▲" to 28, "▼" to 29, "►" to 31, "STOP" to 39, "TEMPO" to 22),
            listOf("A" to 33, "B" to 34, "C" to 35, "D" to 36, "GRP" to 32, "SCALE" to 42),
            listOf("PG-" to 41, "PG+" to 40, "S/E/R" to 23, "PAT/SNG" to 43, "TRGSEL" to 44, "SONG" to 45),
            listOf("CLS/EXT" to 46, "TRK1" to 16, "TRK2" to 17, "TRK3" to 18, "TRK4" to 19, "TRK5" to 20),
            listOf("TRK6" to 21),
        )
        grid.forEachIndexed { row, items ->
            items.forEachIndexed { col, (label, id) ->
                val x = 560f + col * 66f
                val y = 150f + row * 54f
                buttons += Btn(label, id, RectF(x, y, x + 60f, y + 46f))
            }
        }
        for (i in triggerIds) {
            val x = 20f + i * 59.5f
            buttons += Btn((i + 1).toString(), i, RectF(x, 505f, x + 54f, 565f))
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            NativeBridge.readLcd(lcdPixels, 0xFF101810.toInt(), 0xFFA9C26A.toInt())
            NativeBridge.readLeds(leds)
            lcdBitmap.setPixels(lcdPixels, 0, 128, 0, 0, 128, 64)
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = min(w / 980f, h / 580f)
        offX = (w - 980f * scale) / 2f
        offY = (h - 580f * scale) / 2f
    }

    override fun onDraw(c: Canvas) {
        c.drawPaint(bg)
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)

        c.drawRect(lcdRect.left - 6, lcdRect.top - 6, lcdRect.right + 6, lcdRect.bottom + 6, Paint().apply { color = Color.BLACK })
        c.drawBitmap(lcdBitmap, null, lcdRect, lcdPaint)

        for (k in knobs) {
            c.drawCircle(k.cx, k.cy, k.rad, knobPaint)
            c.drawLine(k.cx, k.cy, k.cx, k.cy - k.rad, text)
            c.drawText(k.label, k.cx, k.cy + k.rad + 18f, text)
        }

        for (b in buttons) {
            val isDown = b.id in pressed
            c.drawRoundRect(b.r, 8f, 8f, if (isDown) btnDown else btnPaint)
            c.drawText(b.label, b.r.centerX(), b.r.centerY() + 5f, text)
        }

        // step LEDs above the trigger buttons
        for (i in triggerIds) {
            val b = buttons.first { it.id == i && it.r.top > 400f }
            ledPaint.color = when (leds[i]) {
                1 -> 0xFF40E060.toInt(); 2 -> 0xFFFF3030.toInt(); 3 -> 0xFFFFD030.toInt()
                else -> 0xFF303030.toInt()
            }
            c.drawCircle(b.r.centerX(), b.r.top - 12f, 6f, ledPaint)
        }

        c.drawText("underruns: ${NativeBridge.underruns()}", 20f, 12f, hud)
        c.restore()
    }

    private fun toLogical(x: Float, y: Float) = PointF((x - offX) / scale, (y - offY) / scale)

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
                } ?: knobs.firstOrNull { k ->
                    val dx = p.x - k.cx; val dy = p.y - k.cy
                    dx * dx + dy * dy <= (k.rad + 14f) * (k.rad + 14f)
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
                        NativeBridge.encoder(t.k.id, steps)
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
        }
    }

    private fun releaseAll() = active.keys.toList().forEach { release(it) }
}
