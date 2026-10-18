package dev.terraformist.glyphlife

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy

/**
 * The Glyph Toy: Conway's Game of Life on the round Glyph Matrix on the back of the phone.
 *
 *  - Long-press the Glyph Button: next seed style (random soup -> mandala -> classics) and a fresh colony.
 *  - Shake the phone: a fresh colony in the same style.
 *  - When the colony dies out or gets stuck in a loop, it holds the last picture for a moment
 *    and then starts again by itself, so it never sits there doing nothing.
 *
 * Awake: for a minute after the toy is picked, after a long-press, a touch of the Glyph Button
 * or a shake, and whenever the screen is on. It evolves at the speed chosen in the app
 * (5 generations a second by default), with soft fading trails.
 *
 * Always-on (screen off, toy left running): the phone wakes us once a minute, so the colony
 * moves on one generation a minute, a little dimmer, with a faint trace of the cells that just
 * died. Animating non-stop there would keep the phone awake all night.
 *
 * Shake while resting: the phone's low-power "significant motion" sensor wakes us when the phone
 * is moved, and we then listen for a shake for a few seconds before going back to sleep.
 */
class LifeToyService : Service() {

    private var matrix: GlyphMatrixManager? = null
    private var connected = false

    private val life = Life()
    private val glow = Glow()

    /** Keep animating until this time even if the screen is off. */
    private var animateUntil = 0L

    /** Listening for a shake (without animating) until this time. */
    private var listenUntil = 0L

    private var generationMs = Speed.MEDIUM.generationMs
    private var lastFrameAt = 0L
    private var sinceGeneration = 0L

    /** When the colony settled (0 = still going). */
    private var settledAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphLife:animation").apply {
            setReferenceCounted(false)
        }
    }

    private val shake by lazy { ShakeDetector(applicationContext) { main.post { onShake() } } }

    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motionArmed = false
            main.post { listenForShake() }
        }
    }
    private var motionArmed = false

    private val tick = object : Runnable {
        override fun run() {
            if (!connected) return
            val now = System.currentTimeMillis()
            if (isAwake(now)) {
                animateFrame(now)
                main.postDelayed(this, FRAME_MS)
            } else {
                // Resting: one still picture, then let the phone sleep until something wakes us.
                lastFrameAt = 0L
                glow.snap(life.cells)
                send(Glow.still(life, AOD_ALIVE, AOD_TRAIL))
                restIfIdle()
            }
        }
    }

    private val stopListening = Runnable { restIfIdle() }

    private val buttonEvents = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            when (msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)) {
                GlyphToy.EVENT_CHANGE -> onLongPress()
                GlyphToy.EVENT_AOD -> onAlwaysOnTick()
                GlyphToy.EVENT_ACTION_DOWN, GlyphToy.EVENT_ACTION_UP -> wakeFor(ACTIVE_MS)
            }
        }
    }

    private val messenger = Messenger(buttonEvents)

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) wakeFor(ACTIVE_MS)
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder {
        val manager = GlyphMatrixManager.getInstance(applicationContext)
        matrix = manager
        manager.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                manager.register(Glyph.DEVICE_23112)
                connected = true
                reseed()
                wakeFor(ACTIVE_MS)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                main.removeCallbacks(tick)
                main.removeCallbacks(stopListening)
                shake.stop()
                disarmMotion()
                releaseWakeLock()
            }
        })
        if (!receiverRegistered) {
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(tick)
        main.removeCallbacks(stopListening)
        shake.stop()
        disarmMotion()
        releaseWakeLock()
        if (receiverRegistered) {
            unregisterReceiver(screenOnReceiver)
            receiverRegistered = false
        }
        connected = false
        matrix?.let {
            try {
                it.turnOff()
            } catch (e: Exception) {
                Log.w(TAG, "turnOff failed", e)
            }
            it.unInit()
        }
        matrix = null
        return false
    }

    // ---- What makes things happen ----

    private fun onLongPress() {
        val style = Settings.style(applicationContext).next()
        Settings.setStyle(applicationContext, style)
        reseed()
        wakeFor(ACTIVE_MS)
    }

    private fun onShake() {
        if (!connected) return
        reseed()
        wakeFor(ACTIVE_MS)
    }

    /** Once a minute while always-on: one generation, drawn as a still, dimmer picture. */
    private fun onAlwaysOnTick() {
        if (!connected) return
        val now = System.currentTimeMillis()
        if (isAwake(now)) return // already animating; the minute tick changes nothing
        try {
            wakeLock.acquire(3_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
        if (life.settled) {
            life.seed(Settings.style(applicationContext))
        } else {
            life.step()
        }
        glow.snap(life.cells)
        send(Glow.still(life, AOD_ALIVE, AOD_TRAIL))
        restIfIdle()
    }

    /** The phone was moved while resting: listen for a shake for a few seconds. */
    private fun listenForShake() {
        if (!connected) return
        val now = System.currentTimeMillis()
        if (isAwake(now)) return
        listenUntil = now + LISTEN_MS
        try {
            wakeLock.acquire(LISTEN_MS + 1_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
        shake.start()
        main.removeCallbacks(stopListening)
        main.postDelayed(stopListening, LISTEN_MS)
    }

    // ---- Running the colony ----

    private fun reseed() {
        life.seed(Settings.style(applicationContext))
        glow.ripple()
        settledAt = 0L
        sinceGeneration = 0L
    }

    private fun isAwake(now: Long) = power.isInteractive || now < animateUntil

    /** Animate for [ms], keeping the processor awake just long enough to do it. */
    private fun wakeFor(ms: Long) {
        if (!connected) return
        val now = System.currentTimeMillis()
        animateUntil = maxOf(animateUntil, now + ms)
        generationMs = Settings.speed(applicationContext).generationMs
        try {
            wakeLock.acquire(animateUntil - now + 1_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
        disarmMotion()
        shake.start()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun animateFrame(now: Long) {
        val dt = if (lastFrameAt == 0L) 0L else (now - lastFrameAt).coerceIn(0L, 200L)
        lastFrameAt = now

        if (glow.revealing) {
            sinceGeneration = 0L
        } else {
            sinceGeneration += dt
            while (sinceGeneration >= generationMs) {
                sinceGeneration -= generationMs
                life.step()
            }
        }

        // Died out or looping: let it be seen for a moment, then start again.
        if (life.settled) {
            if (settledAt == 0L) settledAt = now
            else if (now - settledAt >= SETTLED_HOLD_MS) reseed()
        }

        send(glow.update(life.cells, dt))
    }

    /** Nothing is keeping us awake any more: stop listening and let the phone sleep. */
    private fun restIfIdle() {
        val now = System.currentTimeMillis()
        if (!connected || isAwake(now) || now < listenUntil) return
        shake.stop()
        armMotion()
        releaseWakeLock()
    }

    private fun armMotion() {
        if (motionArmed || !connected) return
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let {
            motionArmed = sensorManager.requestTriggerSensor(motionTrigger, it)
        }
    }

    private fun disarmMotion() {
        if (!motionArmed) return
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let {
            sensorManager.cancelTriggerSensor(motionTrigger, it)
        }
        motionArmed = false
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
    }

    /** Send brightness levels (0..255) to the matrix, which takes 0..4095 per LED. */
    private fun send(levels: IntArray) {
        val manager = matrix ?: return
        if (!connected) return
        val frame = IntArray(levels.size) { levels[it] * 4095 / 255 }
        try {
            manager.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    companion object {
        private const val TAG = "GlyphLife"
        private const val ACTIVE_MS = 60_000L
        private const val LISTEN_MS = 8_000L
        private const val FRAME_MS = 50L
        private const val SETTLED_HOLD_MS = 2_500L
        private const val AOD_ALIVE = 153 // 60%
        private const val AOD_TRAIL = 30  // the faint trace of cells that just died
    }
}
