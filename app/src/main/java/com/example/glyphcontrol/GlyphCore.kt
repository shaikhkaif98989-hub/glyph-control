package com.example.glyphcontrol

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import kotlin.math.ceil

// One shared connection to the Glyph, used by the app screen and the background service.
object GlyphLink {
    private var gm: GlyphManager? = null
    private var connecting = false
    private val handler = Handler(Looper.getMainLooper())
    private var running: Runnable? = null
    private var batteryGen = 0
    private val pending = mutableListOf<() -> Unit>()

    @Volatile var ready = false
    @Volatile var callMode = false
    @Volatile var musicMode = false
    var zones = 4
    var stepMs = 300L
    var status = "Connecting..."
    var onStatus: ((String) -> Unit)? = null

    val patternNames = listOf(
        "Off", "Chase", "Reverse chase", "Bounce", "Wave",
        "Fill up", "Countdown", "Alternate", "Blink",
        "Heartbeat", "Random", "Breathe", "All on"
    )

    private fun note(s: String) {
        status = s
        onStatus?.invoke(s)
    }

    private fun isModel(m: String): Boolean = try {
        (Common::class.java.getMethod(m).invoke(null) as? Boolean) ?: false
    } catch (e: Throwable) { false }

    private fun devConst(n: String): String? = try {
        Glyph::class.java.getField(n).get(null) as? String
    } catch (e: Throwable) { null }

    fun start(ctx: Context) {
        if (ready || connecting) return
        connecting = true
        zones = if (isModel("is25111")) 6 else 4
        val g = gm ?: GlyphManager.getInstance(ctx.applicationContext).also { gm = it }
        g.init(object : GlyphManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                connecting = false
                try {
                    val d = devConst(if (zones == 6) "DEVICE_25111" else "DEVICE_25131")
                    if (d != null) g.register(d) else g.register()
                    g.openSession()
                    ready = true
                    note("Connected")
                    val todo = pending.toList()
                    pending.clear()
                    todo.forEach { it() }
                } catch (e: Throwable) { note("Can't connect: ${e.message}") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connecting = false
                ready = false
                try { g.closeSession() } catch (e: Throwable) {}
                note("Disconnected")
            }
        })
    }

    private fun whenReady(block: () -> Unit) {
        if (ready) block() else pending.add(block)
    }

    // Light exactly these zones; everything else goes off.
    fun show(list: List<Int>) {
        val g = gm ?: return
        if (!ready) { note("Not connected yet"); return }
        try {
            if (list.isEmpty()) { g.turnOff(); return }
            var b = g.getGlyphFrameBuilder()
            for (z in list) b = b.buildChannel(z)
            g.toggle(b.build())
        } catch (e: Throwable) { note("Error: ${e.message}") }
    }

    fun cancelLoop() {
        running?.let { handler.removeCallbacks(it) }
        running = null
    }

    fun stop() {
        cancelLoop()
        if (ready) show(emptyList())
    }

    private fun startLoop(frames: List<List<Int>>) {
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

    private fun breathe() {
        val g = gm ?: return
        try {
            var b = g.getGlyphFrameBuilder()
            for (z in 0 until zones) b = b.buildChannel(z)
            g.animate(b.buildPeriod(2000).buildCycles(100).buildInterval(10).build())
        } catch (e: Throwable) { note("Error: ${e.message}") }
    }

    // speedMs, when given, overrides the shared speed just for this play (used by calls/music/manual).
    fun play(name: String, speedMs: Long? = null) = whenReady {
        cancelLoop()
        if (speedMs != null) stepMs = speedMs
        val all = (0 until zones).toList()
        val chase = all.map { listOf(it) }
        when (name) {
            "Chase" -> startLoop(chase)
            "Reverse chase" -> startLoop(chase.reversed())
            "Bounce" -> startLoop(chase + (zones - 2 downTo 1).map { listOf(it) })
            "Wave" -> startLoop((0 until zones).map { s -> listOf(s, (s + 1) % zones) })
            "Fill up" -> startLoop(all.map { n -> all.take(n + 1) } + listOf(emptyList<Int>()))
            "Countdown" -> startLoop((zones downTo 0).map { n -> all.take(n) })
            "Alternate" -> startLoop(listOf(all.filterIndexed { i, _ -> i % 2 == 0 }, all.filterIndexed { i, _ -> i % 2 != 0 }))
            "Blink" -> startLoop(listOf(all, emptyList<Int>()))
            "Heartbeat" -> startLoop(listOf(all, emptyList<Int>(), all, emptyList<Int>(), emptyList<Int>(), emptyList<Int>()))
            "Random" -> startLoop(chase.shuffled())
            "Breathe" -> breathe()
            "All on" -> show(all)
            else -> show(emptyList())
        }
    }

    fun callPlay(name: String, speedMs: Long? = null) {
        callMode = true
        play(name, speedMs)
    }

    fun callEnd() {
        callMode = false
        stop()
    }

    fun musicPlay(name: String, speedMs: Long? = null) {
        musicMode = true
        play(name, speedMs)
    }

    fun musicEnd() {
        musicMode = false
        stop()
    }

    // Lights zones proportional to a battery percentage for a few seconds. Calls and music win over this.
    fun batteryShow(pct: Int, holdMs: Long = 4000) = whenReady {
        if (callMode || musicMode) return@whenReady
        cancelLoop()
        val lit = if (pct <= 0) 0 else ceil(pct / 100.0 * zones).toInt().coerceIn(1, zones)
        show((0 until lit).toList())
        val myGen = ++batteryGen
        handler.postDelayed({
            if (batteryGen == myGen && !callMode && !musicMode) show(emptyList())
        }, holdMs)
    }
}

// Runs in the background: lights the Glyph for calls, music, and a charging-pickup battery display.
class CallGlyphService : Service() {
    private var callback: TelephonyCallback? = null
    private var last = TelephonyManager.CALL_STATE_IDLE
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var musicPlaying = false
    private var screenReceiver: BroadcastReceiver? = null

    private val poll = object : Runnable {
        override fun run() {
            checkMusic()
            handler.postDelayed(this, 1000)
        }
    }

    // Reconnects to the Glyph if the connection ever drops (e.g. a scheduled Glyph-off period).
    private val healthCheck = object : Runnable {
        override fun run() {
            if (!GlyphLink.ready) GlyphLink.start(this@CallGlyphService)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel("glyph", "Glyph lights", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "glyph")
            .setContentTitle("Glyph lights are on")
            .setContentText("Your Glyph reacts to calls, music and charging")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        GlyphLink.start(this)
        handler.post(healthCheck)
        sync()
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("glyph", MODE_PRIVATE)

    // Turn each feature on or off according to the saved settings.
    private fun sync() {
        val p = prefs()
        if (p.getBoolean("calls", false)) listen() else unlisten()
        if (p.getBoolean("music", false)) startWatch() else stopWatch()
        if (p.getBoolean("charge", false)) startChargeWatch() else stopChargeWatch()
    }

    private fun listen() {
        if (callback != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val p = prefs()
                val prev = last
                last = state
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING ->
                        GlyphLink.callPlay(
                            p.getString("ring", "Chase") ?: "Off",
                            p.getInt("ringSpeedMs", 300).toLong()
                        )
                    TelephonyManager.CALL_STATE_OFFHOOK ->
                        GlyphLink.callPlay(
                            p.getString("talk", "Off") ?: "Off",
                            p.getInt("talkSpeedMs", 300).toLong()
                        )
                    else -> if (prev != TelephonyManager.CALL_STATE_IDLE) {
                        GlyphLink.callEnd()
                        checkMusic(true)
                    }
                }
            }
        }
        try {
            tm.registerTelephonyCallback(mainExecutor, cb)
            callback = cb
        } catch (e: SecurityException) {
        }
    }

    private fun unlisten() {
        val cb = callback ?: return
        try { getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) } catch (e: Throwable) {}
        callback = null
        last = TelephonyManager.CALL_STATE_IDLE
        if (GlyphLink.callMode) GlyphLink.callEnd()
    }

    private fun startWatch() {
        if (watching) return
        watching = true
        handler.post(poll)
    }

    private fun stopWatch() {
        if (!watching) return
        watching = false
        handler.removeCallbacks(poll)
        if (musicPlaying && !GlyphLink.callMode) GlyphLink.musicEnd()
        musicPlaying = false
    }

    private fun hasUsageAccess(): Boolean = try {
        val aom = getSystemService(AppOpsManager::class.java) ?: return false
        aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    // Which app was last brought to the foreground. Looks back a few hours so a long session
    // (with no new "switched app" event) still counts, not just the first few seconds.
    private fun foregroundApp(): String? = try {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 3 * 60 * 60 * 1000, end)
        var pkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
        }
        pkg
    } catch (e: Throwable) { null }

    // Only these apps count as "music" — safer than guessing from the audio itself, since
    // YouTube's audio looks identical to a music app's audio as far as Android can tell.
    private val musicApps = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music",
        "com.amazon.mp3", "com.soundcloud.android", "deezer.android.app", "com.google.android.music",
        "com.google.android.apps.podcasts", "com.bsbportal.music", "com.gaana"
    )

    // Looks at whether music is playing; a phone call always has priority.
    private fun checkMusic(force: Boolean = false) {
        val am = getSystemService(AudioManager::class.java) ?: return
        val now = hasUsageAccess() && musicApps.contains(foregroundApp()) && am.isMusicActive
        if (now == musicPlaying && !force) return
        musicPlaying = now
        if (GlyphLink.callMode) return
        val p = prefs()
        if (now) {
            GlyphLink.musicPlay(p.getString("song", "Bounce") ?: "Off", p.getInt("songSpeedMs", 300).toLong())
        } else {
            GlyphLink.musicEnd()
        }
    }

    // Shows the battery percentage when you plug in, or when the screen turns on while charging.
    private fun startChargeWatch() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_POWER_CONNECTED -> showBatteryIfCharging()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(r, filter, RECEIVER_NOT_EXPORTED)
        screenReceiver = r
    }

    private fun stopChargeWatch() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (e: Throwable) {} }
        screenReceiver = null
    }

    private fun showBatteryIfCharging() {
        val bm = getSystemService(BatteryManager::class.java) ?: return
        if (!bm.isCharging) return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) GlyphLink.batteryShow(pct)
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheck)
        unlisten()
        stopWatch()
        stopChargeWatch()
        GlyphLink.callEnd()
        GlyphLink.musicEnd()
        super.onDestroy()
    }
}
