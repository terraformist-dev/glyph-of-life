package dev.terraformist.glyphlife

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app screen: a live colony, the same as the Glyph Toy shows, plus the seed style and speed.
 * Tap the picture (or shake the phone) for a new colony; long-press it for the next seed style.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val life = Life()
    private val glow = Glow()
    private var lastFrameAt = 0L
    private var sinceGeneration = 0L
    private var settledAt = 0L
    private var status = ""

    private lateinit var picture: ImageView
    private lateinit var bitmap: Bitmap
    private lateinit var stats: TextView
    private lateinit var statusText: TextView
    private val styleButtons = mutableMapOf<SeedStyle, TextView>()
    private val speedButtons = mutableMapOf<Speed, TextView>()

    private val shake by lazy { ShakeDetector(this) { handler.post { newColony(jiggle = true) } } }

    private val loop = object : Runnable {
        override fun run() {
            frame()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildLayout())
        picture.setOnClickListener { newColony(jiggle = true) }
        picture.setOnLongClickListener {
            chooseStyle(Settings.style(this).next())
            true
        }
        newColony(jiggle = false)
    }

    override fun onResume() {
        super.onResume()
        showChoices()
        shake.start()
        lastFrameAt = 0L
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    override fun onPause() {
        super.onPause()
        shake.stop()
        handler.removeCallbacks(loop)
    }

    private fun chooseStyle(style: SeedStyle) {
        Settings.setStyle(this, style)
        showChoices()
        newColony(jiggle = false)
    }

    private fun chooseSpeed(speed: Speed) {
        Settings.setSpeed(this, speed)
        showChoices()
    }

    private fun newColony(jiggle: Boolean) {
        life.seed(Settings.style(this))
        glow.ripple()
        settledAt = 0L
        sinceGeneration = 0L
        status = "Seeded: ${life.seedName}"
        if (jiggle) {
            ObjectAnimator.ofFloat(picture, "translationX", 0f, -18f, 15f, -12f, 8f, -4f, 0f).setDuration(350).start()
            ObjectAnimator.ofFloat(picture, "rotation", 0f, -3f, 2f, -2f, 1f, 0f).setDuration(350).start()
        }
    }

    private fun frame() {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameAt == 0L) 0L else (now - lastFrameAt).coerceIn(0L, 200L)
        lastFrameAt = now
        val generationMs = Settings.speed(this).generationMs

        if (glow.revealing) {
            sinceGeneration = 0L
        } else {
            sinceGeneration += dt
            while (sinceGeneration >= generationMs) {
                sinceGeneration -= generationMs
                life.step()
            }
        }
        if (life.settled) {
            if (settledAt == 0L) {
                settledAt = now
                status = "${life.settledReason} after ${life.generation} generations"
            } else if (now - settledAt >= SETTLED_HOLD_MS) {
                newColony(jiggle = false)
                status = "Started again: ${life.seedName}"
            }
        }

        DotPainter.paintInto(bitmap, glow.update(life.cells, dt))
        picture.invalidate()
        stats.text = "GEN ${life.generation}  ·  ${life.population} ALIVE"
        statusText.text = status
    }

    private fun showChoices() {
        val style = Settings.style(this)
        val speed = Settings.speed(this)
        styleButtons.forEach { (s, b) -> paintChip(b, s == style) }
        speedButtons.forEach { (s, b) -> paintChip(b, s == speed) }
    }

    // ---- Layout (built in code: black, white dots, quiet grey text) ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int, mono: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(0f, 1.2f)
    }

    private fun chip(label: String): TextView = text(13f, Color.WHITE, mono = true).apply {
        text = label.uppercase()
        gravity = Gravity.CENTER
        letterSpacing = 0.06f
        setPadding(dp(8), dp(12), dp(8), dp(12))
        isClickable = true
        isFocusable = true
    }

    private fun paintChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), if (selected) Color.WHITE else 0xFF5A5A5A.toInt())
            setColor(if (selected) Color.WHITE else Color.BLACK)
        }
    }

    private fun chipRow(chips: List<TextView>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
    }

    private fun label(t: String) = text(12f, 0xFF9A9A9A.toInt(), mono = true).apply {
        text = t
        letterSpacing = 0.15f
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
            setBackgroundColor(Color.BLACK)
        }

        column.addView(label("GLYPH LIFE"))
        column.addView(gap(28))

        bitmap = DotPainter.paint(IntArray(Disc.CELLS), 32)
        picture = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF111111.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageBitmap(bitmap)
            contentDescription = "The colony. Tap for a new one, long-press for the next seed style."
        }
        column.addView(picture)
        column.addView(gap(24))

        stats = text(14f, Color.WHITE, mono = true).apply { letterSpacing = 0.08f }
        column.addView(stats)
        column.addView(gap(6))
        statusText = text(14f, 0xFF9A9A9A.toInt())
        column.addView(statusText)
        column.addView(gap(32))

        column.addView(label("SEED STYLE"))
        column.addView(gap(10))
        val styles = SeedStyle.entries.map { s ->
            chip(s.label).also { c ->
                styleButtons[s] = c
                c.setOnClickListener { chooseStyle(s) }
            }
        }
        column.addView(chipRow(styles), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(gap(24))

        column.addView(label("SPEED"))
        column.addView(gap(10))
        val speeds = Speed.entries.map { s ->
            chip(s.label).also { c ->
                speedButtons[s] = c
                c.setOnClickListener { chooseSpeed(s) }
            }
        }
        column.addView(chipRow(speeds), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(gap(36))

        column.addView(text(15f, Color.WHITE).apply { text = "On the back of your phone" })
        column.addView(gap(8))
        column.addView(text(14f, 0xFFB0B0B0.toInt()).apply {
            text = "Add it in Settings › Glyph Interface › Glyph Toys.\n\n" +
                "Shake the phone for a new colony. Long-press the Glyph Button for the next seed style: " +
                "random soup, mandala, then the classics.\n\n" +
                "When a colony dies out or gets stuck in a loop, a new one starts by itself.\n\n" +
                "With always-on, it moves one generation a minute. Touch the Glyph Button to wake it " +
                "up for a minute at full speed.\n\n" +
                "Tap the picture above for a new colony here, or long-press it for the next style."
        })
        column.addView(gap(32))

        column.addView(text(12f, 0xFF8A8A8A.toInt(), mono = true).apply {
            text = "After John Conway's Game of Life (1970)"
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(column)
        }
    }

    companion object {
        private const val FRAME_MS = 33L
        private const val SETTLED_HOLD_MS = 2_500L
    }
}
