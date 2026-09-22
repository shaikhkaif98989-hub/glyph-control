package com.example.glyphcontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var callsTile: TextView
    private lateinit var ringTile: TextView
    private lateinit var talkTile: TextView
    private lateinit var musicTile: TextView
    private lateinit var songTile: TextView
    private lateinit var chargeTile: TextView
    private val tiles = mutableListOf<TextView>()
    private var zoneOn = BooleanArray(4)
    private val prefs by lazy { getSharedPreferences("glyph", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GlyphLink.start(this)
        zoneOn = BooleanArray(GlyphLink.zones)
        setContentView(buildUi())
        GlyphLink.onStatus = { status.text = it }
        status.text = GlyphLink.status
        if (anyAutoOn()) {
            startForegroundService(Intent(this, CallGlyphService::class.java))
        }
    }

    override fun onPause() {
        if (!GlyphLink.callMode && !GlyphLink.musicMode) GlyphLink.stop()
        super.onPause()
    }

    override fun onDestroy() {
        GlyphLink.onStatus = null
        super.onDestroy()
    }

    private fun anyAutoOn() =
        prefs.getBoolean("calls", false) || prefs.getBoolean("music", false) || prefs.getBoolean("charge", false)

    private fun hasPhonePermission() =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun restartService() {
        val i = Intent(this, CallGlyphService::class.java)
        if (anyAutoOn()) startForegroundService(i) else stopService(i)
    }

    private fun toggleCalls() {
        if (prefs.getBoolean("calls", false)) {
            prefs.edit().putBoolean("calls", false).apply()
            restartService()
            styleAuto()
        } else if (hasPhonePermission()) {
            prefs.edit().putBoolean("calls", true).apply()
            restartService()
            styleAuto()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS), 7
            )
        }
    }

    private fun toggleMusic() {
        val on = !prefs.getBoolean("music", false)
        prefs.edit().putBoolean("music", on).apply()
        restartService()
        styleAuto()
    }

    private fun toggleCharge() {
        val on = !prefs.getBoolean("charge", false)
        prefs.edit().putBoolean("charge", on).apply()
        restartService()
        styleAuto()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasPhonePermission()) {
            prefs.edit().putBoolean("calls", true).apply()
            restartService()
            styleAuto()
        } else {
            status.text = "Allow the Phone permission to use call lights"
        }
    }

    private fun cycle(key: String, def: String): String {
        val names = GlyphLink.patternNames
        val cur = prefs.getString(key, def) ?: def
        val next = names[(names.indexOf(cur) + 1) % names.size]
        prefs.edit().putString(key, next).apply()
        return next
    }

    private fun applyManual() {
        GlyphLink.cancelLoop()
        GlyphLink.show(zoneOn.indices.filter { zoneOn[it] })
    }

    private fun setAll(on: Boolean) {
        zoneOn.fill(on)
        tiles.indices.forEach { styleTile(it) }
        applyManual()
    }

    private fun testBattery() {
        val bm = getSystemService(BatteryManager::class.java) ?: return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) GlyphLink.batteryShow(pct)
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

    private fun styleAuto() {
        val calls = prefs.getBoolean("calls", false)
        callsTile.text = if (calls) "Call lights: ON (tap to turn off)" else "Call lights: OFF (tap to turn on)"
        callsTile.setTextColor(if (calls) Color.BLACK else Color.WHITE)
        callsTile.background = rounded(if (calls) Color.WHITE else 0xFF26262B.toInt())
        ringTile.text = "When phone rings: ${prefs.getString("ring", "Chase")}  (tap to change)"
        talkTile.text = "While on a call: ${prefs.getString("talk", "Off")}  (tap to change)"

        val music = prefs.getBoolean("music", false)
        musicTile.text = if (music) "Music lights: ON (tap to turn off)" else "Music lights: OFF (tap to turn on)"
        musicTile.setTextColor(if (music) Color.BLACK else Color.WHITE)
        musicTile.background = rounded(if (music) Color.WHITE else 0xFF26262B.toInt())
        songTile.text = "While music plays: ${prefs.getString("song", "Bounce")}  (tap to change)"

        val charge = prefs.getBoolean("charge", false)
        chargeTile.text = if (charge) "Charging lights: ON (tap to turn off)" else "Charging lights: OFF (tap to turn on)"
        chargeTile.setTextColor(if (charge) Color.BLACK else Color.WHITE)
        chargeTile.background = rounded(if (charge) Color.WHITE else 0xFF26262B.toInt())
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

        for (i in 0 until GlyphLink.zones) {
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

        root.addView(label("Patterns", 16f).apply { setPadding(0, dp(24), 0, dp(8)) })
        root.addView(row(pill("Chase") { GlyphLink.play("Chase") }, pill("Bounce") { GlyphLink.play("Bounce") }))
        root.addView(row(pill("Fill up") { GlyphLink.play("Fill up") }, pill("Blink") { GlyphLink.play("Blink") }))
        root.addView(row(pill("Breathe") { GlyphLink.play("Breathe") }, pill("Stop") { setAll(false) }))

        root.addView(label("Pattern speed (right = faster)", 14f, 0xFF9A9AA0.toInt()).apply {
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(SeekBar(this).apply {
            max = 940
            progress = 700
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { GlyphLink.stepMs = 1000L - p }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        root.addView(label("Automatic (phone calls)", 16f).apply { setPadding(0, dp(24), 0, dp(8)) })
        callsTile = pill("") { toggleCalls() }
        ringTile = pill("") { val n = cycle("ring", "Chase"); GlyphLink.play(n); styleAuto() }
        talkTile = pill("") { val n = cycle("talk", "Off"); GlyphLink.play(n); styleAuto() }
        root.addView(row(callsTile))
        root.addView(row(ringTile))
        root.addView(row(talkTile))

        root.addView(label("Automatic (music)", 16f).apply { setPadding(0, dp(24), 0, dp(8)) })
        musicTile = pill("") { toggleMusic() }
        songTile = pill("") { val n = cycle("song", "Bounce"); GlyphLink.play(n); styleAuto() }
        root.addView(row(musicTile))
        root.addView(row(songTile))

        root.addView(label("Automatic (charging)", 16f).apply { setPadding(0, dp(24), 0, dp(8)) })
        root.addView(label("Shows how full the battery is for a few seconds when you wake the phone while it's plugged in", 13f, 0xFF9A9AA0.toInt()))
        chargeTile = pill("") { toggleCharge() }
        root.addView(row(chargeTile))
        root.addView(row(pill("Test now") { testBattery() }))

        styleAuto()

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        }
    }
}
