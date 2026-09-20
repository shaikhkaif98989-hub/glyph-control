package com.example.glyphcontrol

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager

// Phone (4b) has 4 Glyph zones (index 0..3). Phone (4a) has 6.
class MainActivity : Activity() {
    private var gm: GlyphManager? = null
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private var running: Runnable? = null
    private var stepMs = 300L
    private lateinit var status: TextView
    private val tiles = mutableListOf<TextView>()
    private var zoneOn = BooleanArray(4)
    private var zones = 4

    private fun isModel(m: String): Boolean = try {
        (Common::class.java.getMethod(m).invoke(null) as? Boolean) ?: false
    } catch (e: Throwable) { false }

    private fun devConst(n: String): String? = try {
        Glyph::class.java.getField(n).get(null) as? String
    } catch (e: Throwable) { null }

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            val g = gm ?: return
            try {
                val d = devConst(if (zones == 6) "DEVICE_25111" else "DEVICE_25131")
                if (d != null) g.register(d) else g.register()
                g.openSession()
                ready = true
                status.text = "Connected"
            } catch (e: Throwable) { status.text = "Can't connect: ${e.message}" }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ready = false
            try { gm?.closeSession() } catch (e: Throwable) {}
            status.text = "Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        zones = if (isModel("is25111")) 6 else 4
        zoneOn = BooleanArray(zones)
        setContentView(buildUi())
        gm = GlyphManager.getInstance(applicationContext).also { it.init(callback) }
    }

    override fun onPause() {
        stopPattern()
        try { gm?.turnOff() } catch (e: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        stopPattern()
        try { gm?.turnOff(); gm?.closeSession() } catch (e: Throwable) {}
        try { gm?.unInit() } catch (e: Throwable) {}
        super.onDestroy()
    }

    // Light exactly these zones; everything else goes off.
    private fun show(list: List<Int>) {
        val g = gm ?: return
        if (!ready) { status.text = "Not connected yet"; return }
        try {
            if (list.isEmpty()) { g.turnOff(); return }
            var b = g.getGlyphFrameBuilder()
            for (z in list) b = b.buildChannel(z)
            g.toggle(b.build())
        } catch (e: Throwable) { status.text = "Error: ${e.message}" }
    }

    private fun breathe() {
        stopPattern()
        val g = gm ?: return
        if (!ready) { status.text = "Not connected yet"; return }
        try {
            var b = g.getGlyphFrameBuilder()
            for (z in 0 until zones) b = b.buildChannel(z)
            g.animate(b.buildPeriod(2000).buildCycles(100).buildInterval(10).build())
        } catch (e: Throwable) { status.text = "Error: ${e.message}" }
    }

    private fun startPattern(frames: List<List<Int>>) {
        stopPattern()
        var i = 0
        val r = object : Runnable {
            override fun run() {
                show(frames[i % frames.size])
                i++
                handler.postDelayed(this, stepMs)
            }
        }
        running = r
        handler.post(r)
    }

    private fun stopPattern() {
        running?.let { handler.removeCallbacks(it) }
        running = null
    }

    private fun applyManual() {
        stopPattern()
        show(zoneOn.indices.filter { zoneOn[it] })
    }

    private fun setAll(on: Boolean) {
        zoneOn.fill(on)
        tiles.indices.forEach { styleTile(it) }
        applyManual()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun pill(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(0xFF26262B.toInt())
        setPadding(dp(8), dp(16), dp(8), dp(16))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun styleTile(i: Int) {
        val on = zoneOn[i]
        tiles[i].text = if (on) "Box ${i + 1}   ON" else "Box ${i + 1}   OFF"
        tiles[i].setTextColor(if (on) Color.BLACK else Color.WHITE)
        tiles[i].background = rounded(if (on) Color.WHITE else 0xFF2A2A2E.toInt())
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(48), dp(16), dp(24))
        }
        root.addView(label("Glyph Control", 28f, bold = true))
        status = label("Connecting...", 14f, 0xFF9A9AA0.toInt())
        root.addView(status)
        root.addView(label("Tap a box to turn it on or off", 16f).apply {
            setPadding(0, dp(24), 0, dp(8))
        })

        for (i in 0 until zones) {
            val t = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
                ).apply { setMargins(0, dp(6), 0, dp(6)) }
                setOnClickListener {
                    zoneOn[i] = !zoneOn[i]
                    styleTile(i)
                    applyManual()
                }
            }
            tiles.add(t)
            root.addView(t)
            styleTile(i)
        }

        root.addView(row(pill("All on") { setAll(true) }, pill("All off") { setAll(false) }))

        val all = (0 until zones).toList()
        val chase = all.map { listOf(it) }
        val bounce = chase + (zones - 2 downTo 1).map { listOf(it) }
        val fill = all.map { n -> all.take(n + 1) } + listOf(emptyList<Int>())

        root.addView(label("Patterns", 16f).apply { setPadding(0, dp(24), 0, dp(8)) })
        root.addView(row(pill("Chase") { startPattern(chase) }, pill("Bounce") { startPattern(bounce) }))
        root.addView(row(pill("Fill up") { startPattern(fill) }, pill("Blink") { startPattern(listOf(all, emptyList<Int>())) }))
        root.addView(row(pill("Breathe") { breathe() }, pill("Stop") { setAll(false) }))

        root.addView(label("Pattern speed (right = faster)", 14f, 0xFF9A9AA0.toInt()).apply {
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(SeekBar(this).apply {
            max = 940
            progress = 700
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { stepMs = 1000L - p }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        }
    }
}
