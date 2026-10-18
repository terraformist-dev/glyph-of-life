package dev.terraformist.glyphlife

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** How fast the colony evolves while the toy is awake. */
enum class Speed(val label: String, val generationMs: Long) {
    SLOW("Slow", 500L),
    MEDIUM("Medium", 200L),
    FAST("Fast", 100L),
}

/** The choices made in the app (or by long-pressing), shared by the app and the Glyph Toy. */
object Settings {
    private const val PREFS = "glyph_life"
    private const val KEY_STYLE = "seed_style"
    private const val KEY_SPEED = "speed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun style(context: Context): SeedStyle =
        SeedStyle.entries.firstOrNull { it.name == prefs(context).getString(KEY_STYLE, null) } ?: SeedStyle.SOUP

    fun setStyle(context: Context, style: SeedStyle) {
        prefs(context).edit().putString(KEY_STYLE, style.name).apply()
    }

    fun speed(context: Context): Speed =
        Speed.entries.firstOrNull { it.name == prefs(context).getString(KEY_SPEED, null) } ?: Speed.MEDIUM

    fun setSpeed(context: Context, speed: Speed) {
        prefs(context).edit().putString(KEY_SPEED, speed.name).apply()
    }
}

/**
 * Notices a shake: three firm jolts within about a second (the same feel as Dotchi).
 * Only listens between [start] and [stop], so it costs nothing while the toy is resting.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var running = false
    private val jolts = ArrayDeque<Long>()
    private var lastShakeAt = 0L

    fun start() {
        if (running || manager == null) return
        running = true
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
        jolts.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g > SHAKE_G) {
            if (jolts.isEmpty() || now - jolts.last() > JOLT_GAP_MS) jolts.addLast(now)
            while (jolts.isNotEmpty() && now - jolts.first() > SHAKE_WINDOW_MS) jolts.removeFirst()
            if (jolts.size >= JOLTS_FOR_SHAKE && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                lastShakeAt = now
                jolts.clear()
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val SHAKE_G = 2.2f
        const val JOLT_GAP_MS = 120L
        const val SHAKE_WINDOW_MS = 1_000L
        const val JOLTS_FOR_SHAKE = 3
        const val SHAKE_COOLDOWN_MS = 1_500L
    }
}
