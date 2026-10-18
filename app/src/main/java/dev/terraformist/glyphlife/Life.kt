package dev.terraformist.glyphlife

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The round Glyph Matrix: a 25x25 grid with the corners cut off, 489 dots in all.
 * Cells outside the circle don't exist, and count as permanently dead, so the edge is a wall.
 */
object Disc {
    const val N = 25
    const val CELLS = N * N
    private const val CENTRE = 12

    /** How many dots each row has, top to bottom (the real matrix outline). */
    private val ROW_WIDTHS = intArrayOf(
        7, 11, 15, 17, 19, 21, 21, 23, 23, 25, 25, 25, 25, 25, 25, 25, 23, 23, 21, 21, 19, 17, 15, 11, 7,
    )

    /** True for the 489 cells that are real dots. */
    val inside = BooleanArray(CELLS).also { mask ->
        for (y in 0 until N) {
            val start = (N - ROW_WIDTHS[y]) / 2
            for (x in start until start + ROW_WIDTHS[y]) mask[y * N + x] = true
        }
    }

    /** Distance of each cell from the centre, for the ripple when a new seed appears. */
    val distance = FloatArray(CELLS) { i ->
        val dx = i % N - CENTRE
        val dy = i / N - CENTRE
        sqrt((dx * dx + dy * dy).toFloat())
    }

    /** For each real dot, its real neighbours (up to 8). */
    val neighbours: Array<IntArray> = Array(CELLS) { i ->
        if (!inside[i]) IntArray(0) else {
            val x = i % N
            val y = i / N
            val list = ArrayList<Int>(8)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until N && ny in 0 until N && inside[ny * N + nx]) list.add(ny * N + nx)
            }
            list.toIntArray()
        }
    }

    const val MID = CENTRE

    fun set(cells: BooleanArray, x: Int, y: Int) {
        if (x in 0 until N && y in 0 until N && inside[y * N + x]) cells[y * N + x] = true
    }
}

/** How a new colony is started. Long-press the Glyph Button to move to the next one. */
enum class SeedStyle(val label: String) {
    SOUP("Random soup"),
    MANDALA("Mandala"),
    CLASSICS("Classics");

    fun next(): SeedStyle = entries[(ordinal + 1) % entries.size]
}

/** Famous small starting patterns ('X' = alive). Dropped in the middle, turned and flipped at random. */
private val CLASSICS: Map<String, List<String>> = mapOf(
    "R-pentomino" to listOf(".XX", "XX.", ".X."),
    "Acorn" to listOf(".X.....", "...X...", "XX..XXX"),
    "Diehard" to listOf("......X.", "XX......", ".X...XXX"),
    "Pi-heptomino" to listOf("XXX", "X.X", "X.X"),
    "B-heptomino" to listOf("X.XX", "XXX.", ".X.."),
    "Thunderbird" to listOf("XXX", "...", ".X.", ".X.", ".X."),
)

/**
 * Conway's Game of Life on the disc.
 * A live cell with 2 or 3 live neighbours survives; a dead cell with exactly 3 comes alive.
 *
 * It also notices when the colony has settled: either everything died, or the pattern has
 * started repeating itself (a still life or a blinker), so the toy knows to start again.
 */
class Life(private val random: Random = Random.Default) {

    var cells = BooleanArray(Disc.CELLS)
        private set

    /** The cells from one generation ago (for the faint trail in always-on). */
    var previous = BooleanArray(Disc.CELLS)
        private set

    var generation = 0
        private set

    /** What was planted: "random soup", "8-fold mandala", "Acorn"… */
    var seedName = ""
        private set

    var population = 0
        private set

    /** True once the colony has died out or is going round in a loop. */
    var settled = false
        private set

    /** Why it settled, in words. */
    val settledReason: String
        get() = if (population < MIN_ALIVE) "Died out" else "Settled into a loop"

    private val recent = ArrayDeque<BooleanArray>()
    private var repeats = 0

    fun seed(style: SeedStyle) {
        val next = BooleanArray(Disc.CELLS)
        seedName = when (style) {
            SeedStyle.SOUP -> soup(next)
            SeedStyle.MANDALA -> mandala(next)
            SeedStyle.CLASSICS -> classic(next)
        }
        previous = BooleanArray(Disc.CELLS)
        cells = next
        generation = 0
        recent.clear()
        repeats = 0
        settled = false
        population = cells.count { it }
    }

    /** Move on one generation. */
    fun step() {
        val next = nextGeneration(cells)
        previous = cells
        cells = next
        generation++
        population = next.count { it }

        // Have we seen this exact picture recently? Then it's going round in a loop.
        if (recent.any { it.contentEquals(next) }) repeats++ else repeats = 0
        recent.addLast(next)
        if (recent.size > HISTORY) recent.removeFirst()
        if (repeats >= LOOPS_BEFORE_SETTLED || population < MIN_ALIVE || generation >= MAX_GENERATIONS) settled = true
    }

    // ---- Seeds ----

    private fun soup(out: BooleanArray): String {
        for (i in 0 until Disc.CELLS) out[i] = Disc.inside[i] && random.nextFloat() < 0.34f
        return "random soup"
    }

    /**
     * Scatter cells in one eighth of a central disc and mirror it into the other seven.
     * Symmetrical starts often burn out within a few seconds, so we quietly try a few behind
     * the scenes and keep the first one that lives for a good while (or the longest-lived).
     */
    private fun mandala(out: BooleanArray): String {
        var best = BooleanArray(Disc.CELLS)
        var bestLifetime = -1
        for (attempt in 0 until MANDALA_TRIES) {
            val candidate = mandalaCandidate()
            val lifetime = lifetime(candidate, MANDALA_GOOD)
            if (lifetime > bestLifetime) {
                best = candidate
                bestLifetime = lifetime
            }
            if (lifetime >= MANDALA_GOOD) break
        }
        best.copyInto(out)
        return "8-fold mandala"
    }

    private fun mandalaCandidate(): BooleanArray {
        val out = BooleanArray(Disc.CELLS)
        val r = 8 + random.nextInt(4)
        val density = 0.25f + random.nextFloat() * 0.15f
        val c = Disc.MID
        for (y in 0..r) for (x in 0..y) {
            if (x * x + y * y > r * r || random.nextFloat() >= density) continue
            for ((a, b) in listOf(x to y, y to x)) {
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) Disc.set(out, c + sx * a, c + sy * b)
            }
        }
        return out
    }

    /** How many generations [start] lasts before settling, looking no further than [limit]. */
    private fun lifetime(start: BooleanArray, limit: Int): Int {
        var c = start
        val seen = ArrayDeque<BooleanArray>()
        var loops = 0
        for (gen in 1..limit) {
            c = nextGeneration(c)
            if (seen.any { it.contentEquals(c) }) loops++ else loops = 0
            seen.addLast(c)
            if (seen.size > HISTORY) seen.removeFirst()
            if (loops >= LOOPS_BEFORE_SETTLED || c.count { it } < MIN_ALIVE) return gen
        }
        return limit
    }

    private fun classic(out: BooleanArray): String {
        val name = CLASSICS.keys.random(random)
        var rows = CLASSICS.getValue(name).map { it.toCharArray() }
        repeat(random.nextInt(4)) {
            // Turn a quarter clockwise.
            val h = rows.size
            val w = rows[0].size
            rows = List(w) { x -> CharArray(h) { y -> rows[h - 1 - y][x] } }
        }
        if (random.nextBoolean()) rows = rows.map { it.reversedArray() }
        val h = rows.size
        val w = rows[0].size
        val ox = Disc.MID - w / 2
        val oy = Disc.MID - h / 2
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == 'X') Disc.set(out, ox + x, oy + y) } }
        return name
    }

    companion object {
        private const val HISTORY = 60              // spots loops up to 60 generations long
        private const val LOOPS_BEFORE_SETTLED = 3
        private const val MIN_ALIVE = 3
        private const val MAX_GENERATIONS = 1_500   // start again eventually, whatever happens
        private const val MANDALA_TRIES = 40
        private const val MANDALA_GOOD = 80         // generations a mandala should last

        /** The Life rules, applied once to the whole disc. */
        fun nextGeneration(now: BooleanArray): BooleanArray {
            val next = BooleanArray(Disc.CELLS)
            for (i in 0 until Disc.CELLS) {
                if (!Disc.inside[i]) continue
                var n = 0
                for (j in Disc.neighbours[i]) if (now[j]) n++
                next[i] = if (now[i]) n == 2 || n == 3 else n == 3
            }
            return next
        }
    }
}

/**
 * Turns the on/off cells into brightness, with soft edges:
 *  - newborn cells fade in quickly, dying cells leave a fading trail (the afterglow);
 *  - after a new seed, the cells appear in a ripple spreading out from the middle.
 * Brightness levels are 0..255.
 */
class Glow {
    private val bright = FloatArray(Disc.CELLS)
    private var reveal = Float.MAX_VALUE

    /** True while the new-seed ripple is still spreading. */
    val revealing: Boolean get() = reveal < REVEAL_DONE

    /** Start the ripple for a fresh seed. */
    fun ripple() {
        reveal = 0f
    }

    /** Jump straight to the cells with no fading (for still pictures). */
    fun snap(cells: BooleanArray) {
        for (i in bright.indices) bright[i] = if (cells[i]) 1f else 0f
        reveal = Float.MAX_VALUE
    }

    /** Advance the fades by [dtMs] and return brightness levels. */
    fun update(cells: BooleanArray, dtMs: Long): IntArray {
        val frames = dtMs / 16f
        val rise = 1f - Math.pow(1.0 - RISE, frames.toDouble()).toFloat()
        val fall = Math.pow(FALL, frames.toDouble()).toFloat()
        if (reveal < Float.MAX_VALUE) reveal += dtMs * REVEAL_SPEED
        return IntArray(Disc.CELLS) { i ->
            if (!Disc.inside[i]) 0 else {
                bright[i] = if (cells[i]) bright[i] + (1f - bright[i]) * rise else bright[i] * fall
                val b = if (Disc.distance[i] <= reveal) bright[i] else 0f
                if (b < 0.03f) 0 else (b * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    companion object {
        private const val RISE = 0.4       // share of the way to full brightness per 16 ms
        private const val FALL = 0.88      // brightness kept per 16 ms by a dying cell
        private const val REVEAL_SPEED = 0.045f // cells per ms
        private const val REVEAL_DONE = 6f // generations start once the ripple is this far out

        /** A still picture: live cells at [alive], cells that died this generation at [trail]. */
        fun still(life: Life, alive: Int, trail: Int): IntArray = IntArray(Disc.CELLS) { i ->
            when {
                !Disc.inside[i] -> 0
                life.cells[i] -> alive
                life.previous[i] -> trail
                else -> 0
            }
        }
    }
}
