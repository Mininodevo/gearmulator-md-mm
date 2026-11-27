package dev.gearmulator.mdmm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var menu: LinearLayout
    private lateinit var status: TextView
    private var panel: PanelView? = null
    private var running = false

    private val modelName = arrayOf("Machinedrum", "Monomachine")
    private fun romFile(model: Int) = File(filesDir, if (model == 0) "rom_md.bin" else "rom_mm.bin")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(27, 27, 29)) }
        setContentView(root)
        buildMenu()
    }

    private fun buildMenu() {
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(40, 20, 40, 20)
            text = "Import the firmware ROM of your own hardware (8 MB .bin) once, then start."
        }
        menu.addView(status)
        for (m in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(Button(this).apply {
                text = "Start ${modelName[m]}"
                setOnClickListener { startModel(m) }
            })
            row.addView(Button(this).apply {
                text = "Import ${modelName[m]} ROM…"
                setOnClickListener { pickRom(m) }
            })
            menu.addView(row)
        }
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))
    }

    private fun pickRom(model: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, model)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode !in 0..1) return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size != 0x800000) {
                status.text = "That file is ${bytes.size} bytes, expected 8388608 (8 MiB)."
                return
            }
            romFile(requestCode).writeBytes(bytes)
            status.text = "${modelName[requestCode]} ROM imported."
        } catch (e: Exception) {
            status.text = "Import failed: ${e.message}"
        }
    }

    private fun startModel(model: Int) {
        val f = romFile(model)
        if (!f.exists()) { status.text = "Import the ${modelName[model]} ROM first."; return }
        status.text = "Booting ${modelName[model]}… (this can take a few seconds)"
        menu.visibility = View.VISIBLE
        Thread {
            val home = File(filesDir, "data_${model}").apply { mkdirs() }.absolutePath
            val err = NativeBridge.start(model, f.readBytes(), f.name, home)
            runOnUiThread {
                if (err != null) {
                    status.text = err
                } else {
                    running = true
                    panel = PanelView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
                    menu.visibility = View.GONE
                    hideSystemBars()
                }
            }
        }.start()
    }

    private fun stopEmulation() {
        if (!running) return
        running = false
        panel?.let { root.removeView(it) }
        panel = null
        NativeBridge.stop()
        menu.visibility = View.VISIBLE
        status.text = "Stopped. State is not kept between sessions."
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (running) stopEmulation() else @Suppress("DEPRECATION") super.onBackPressed() }
    override fun onStop() { super.onStop(); stopEmulation() }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
