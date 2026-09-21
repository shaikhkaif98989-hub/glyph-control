package com.example.glyphcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager

// One shared connection to the Glyph, used by the app screen and the call service.
object GlyphLink {
    private var gm: GlyphManager? = null
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private var running: Runnable? = null
    private val pending = mutableListOf<() -> Unit>()

    @Volatile var ready = false
    @Volatile var callMode = false
    var zones = 4
    var stepMs = 300L
    var status = "Connecting..."
    var onStatus: ((String) -> Unit)? = null

    val patternNames = listOf("Off", "Chase", "Bounce", "Fill up", "Blink", "Breathe", "All on")

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
        if (started) return
        started = true
        zones = if (isModel("is25111")) 6 else 4
        val g = GlyphManager.getInstance(ctx.applicationContext)
        gm = g
        g.init(object : GlyphManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
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

    fun play(name: String) = whenReady {
        cancelLoop()
        val all = (0 until zones).toList()
        val chase = all.map { listOf(it) }
        when (name) {
            "Chase" -> startLoop(chase)
            "Bounce" -> startLoop(chase + (zones - 2 downTo 1).map { listOf(it) })
            "Fill up" -> startLoop(all.map { n -> all.take(n + 1) } + listOf(emptyList<Int>()))
            "Blink" -> startLoop(listOf(all, emptyList<Int>()))
            "Breathe" -> breathe()
            "All on" -> show(all)
            else -> show(emptyList())
        }
    }

    fun callPlay(name: String) {
        callMode = true
        play(name)
    }

    fun callEnd() {
        callMode = false
        stop()
    }
}

// Keeps running in the background and starts a pattern when the phone rings / is on a call.
class CallGlyphService : Service() {
    private var callback: TelephonyCallback? = null
    private var last = TelephonyManager.CALL_STATE_IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel("glyph", "Glyph call lights", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "glyph")
            .setContentTitle("Glyph call lights are on")
            .setContentText("Your Glyph reacts to phone calls")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        GlyphLink.start(this)
        listen()
        return START_STICKY
    }

    private fun listen() {
        if (callback != null) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val p = getSharedPreferences("glyph", MODE_PRIVATE)
                val prev = last
                last = state
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING ->
                        GlyphLink.callPlay(p.getString("ring", "Chase") ?: "Off")
                    TelephonyManager.CALL_STATE_OFFHOOK ->
                        GlyphLink.callPlay(p.getString("talk", "Off") ?: "Off")
                    else -> if (prev != TelephonyManager.CALL_STATE_IDLE) GlyphLink.callEnd()
                }
            }
        }
        try {
            tm.registerTelephonyCallback(mainExecutor, cb)
            callback = cb
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        callback?.let {
            try { getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(it) } catch (e: Throwable) {}
        }
        callback = null
        GlyphLink.callEnd()
        super.onDestroy()
    }
}
