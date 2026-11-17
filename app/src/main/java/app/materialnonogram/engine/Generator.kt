package app.materialnonogram.engine

import kotlin.coroutines.cancellation.CancellationException

/** A generated puzzle and how it was made. Store [puzzle] (the bitmap), never only the seed (03 §4). */
class Generated(
    val puzzle: Puzzle,
    val size: Size,
    val tier: Tier,
    val seed: Long,
    /** The accepted attempt k; the result is "the lowest k that passes". */
    val attempt: Int,
    val fillPermille: Int,
    val grade: Grade,
    /** The attempt budget ran out and the best candidate seen was returned instead (deterministic too). */
    val fallback: Boolean,
    val generatorVersion: Int = Generator.VERSION,
)

/**
 * Deterministic `(generatorVersion, size, tier, seed) → puzzle` (03 §3.2, `NonoDet.generate`).
 *
 * Each attempt k draws from its own sub-seed, picks a fill in the tier's permille band, makes an
 * integer-only blob (D3), rejects any all-empty line (D10; full lines are allowed since VERSION 2), then the cheap pre-filters (line-solvable or not,
 * then line + P1 solvable), then grades with the order-free [Grader] and checks the tier rule. A
 * finished grade is the uniqueness proof; there is never a uniqueness search. Budgets count attempts.
 */
object Generator {
    /**
     * Covers everything on the accept path: PRNG use, blob, bands, empty-line rule, budgets, tier rule (03 §4).
     * 1: rejected full and empty lines. 2 (2026-11-17): full lines allowed, empty lines still rejected (D10).
     */
    const val VERSION = 2

    /** Fill bands in permille, `[lo, hi)`, indexed by [Tier.ordinal]. */
    private val BAND_LO = intArrayOf(620, 550, 450, 300, 250)
    private val BAND_HI = intArrayOf(700, 620, 550, 450, 380)

    /** Easy: L1 alone on the blank grid places at least this share of cells. */
    const val EASY_MIN_FIRST_PASS_PERMILLE = 400

    /** Expert = 1..[EXPERT_MAX_P1] greedy look-ahead steps; Master = more, or any P2. */
    const val EXPERT_MAX_P1 = 3

    /**
     * Hard: Jacobi L3 sweeps from blank ≥ the size's median for line-solvable, empty-line-free Hard-band
     * blobs, indexed by [Size.ordinal]. Measured by `tools/engine-jvm` `Bench sweeps 3000` (docs/ENGINE.md).
     * VERSION 2 moved 5×5 from 4 to 3 (full lines are allowed now); the other sizes did not move.
     */
    private val HARD_MIN_SWEEPS = intArrayOf(3, 5, 8, 6, 9, 11)

    /** Attempt budgets per tier. Accepts take 1–2 attempts for line tiers and tens to hundreds for look-ahead ones. */
    private val MAX_ATTEMPTS = intArrayOf(2_000, 2_000, 4_000, 20_000, 20_000)

    fun band(tier: Tier): IntRange = BAND_LO[tier.ordinal] until BAND_HI[tier.ordinal]
    fun hardMinSweeps(size: Size): Int = HARD_MIN_SWEEPS[size.ordinal]
    fun maxAttempts(tier: Tier): Int = MAX_ATTEMPTS[tier.ordinal]

    /**
     * Whether [tier] exists at [size] (D7/D10): Master is never offered at 5×5 or 10×10. Under the
     * order-free look-ahead count a 10×10 essentially never needs more than three look-aheads.
     */
    fun isOffered(size: Size, tier: Tier): Boolean = !(tier == Tier.MASTER && (size == Size.S5 || size == Size.S10))

    /** `(version << 32) | (w << 20) | (h << 8) | tier`: separates sizes and tiers, so seeds never collide across them. */
    fun domain(version: Int, w: Int, h: Int, tier: Tier): Long =
        (version.toLong() shl 32) or (w.toLong() shl 20) or (h.toLong() shl 8) or tier.ordinal.toLong()

    /** The tier rule on a finished grade. */
    fun accepts(tier: Tier, size: Size, g: Grade): Boolean {
        if (!g.solved) return false
        val p1 = g.p1Steps
        return when (tier) {
            Tier.EASY -> p1 == 0 && !g.usesP2 && g.firstPassPermille >= EASY_MIN_FIRST_PASS_PERMILLE
            Tier.MEDIUM -> p1 == 0 && !g.usesP2
            Tier.HARD -> p1 == 0 && !g.usesP2 && g.sweeps >= HARD_MIN_SWEEPS[size.ordinal]
            Tier.EXPERT -> p1 in 1..EXPERT_MAX_P1 && !g.usesP2
            Tier.MASTER -> p1 > EXPERT_MAX_P1 || g.usesP2
        }
    }

    /** The tier a finished grade falls in, by the generator's ladder (ignoring fill and the Easy/Hard extras). */
    fun tierOf(g: Grade): Tier? = when {
        !g.solved -> null
        g.usesP2 || g.p1Steps > EXPERT_MAX_P1 -> Tier.MASTER
        g.p1Steps > 0 -> Tier.EXPERT
        else -> null
    }

    /**
     * Tatham-style blob (D3), integer only: 16-bit noise per cell, one 3×3 box *sum* scaled by
     * `36 / count` (exact for counts 4, 6, 9), then exactly `round(n · fill‰ / 1000)` cells filled,
     * highest value first, ties to the lower index — one primitive sort of unique keys, so the result
     * cannot depend on the sort algorithm.
     */
    fun blob(w: Int, h: Int, fillPermille: Int, rng: SplitMix64): BooleanArray {
        require(w >= 3 && h >= 3) { "blob needs w, h >= 3" }
        val n = w * h
        val noise = IntArray(n) { (rng.next() ushr 48).toInt() }
        val key = LongArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { sum += noise[yy * w + xx]; cnt++ }
            }
            val v = sum * (36 / cnt) // ≤ 9 · 65535 · 4 < 2^22
            key[y * w + x] = ((0x3FFFFF - v).toLong() shl 32) or (y * w + x).toLong()
        }
        key.sort()
        val k = (n * fillPermille + 500) / 1000
        val g = BooleanArray(n)
        for (i in 0 until k) g[(key[i] and 0xFFFFFFFFL).toInt()] = true
        return g
    }

    /** Attempt [k] alone (used by calibration and tests): the candidate bitmap and its fill. */
    fun candidate(size: Size, tier: Tier, seed: Long, k: Int): Pair<Puzzle, Int> {
        val rng = SplitMix64(SplitMix64.subseed(seed, domain(VERSION, size.w, size.h, tier), k))
        val lo = BAND_LO[tier.ordinal]; val hi = BAND_HI[tier.ordinal]
        val fill = lo + rng.nextInt(hi - lo)
        return Puzzle(size.w, size.h, blob(size.w, size.h, fill, rng)) to fill
    }

    /**
     * The puzzle for `(VERSION, size, tier, seed)`. Pure: the same arguments give the same puzzle on
     * any JVM or ART. [isCancelled] is polled once per attempt (for background prefetch) and throws
     * [CancellationException]; it cannot change the result, only abandon it.
     */
    fun generate(size: Size, tier: Tier, seed: Long, isCancelled: () -> Boolean = { false }): Generated {
        require(isOffered(size, tier)) { "$tier is not offered at ${size.label}" }
        var fallback: Generated? = null
        var fallbackP1 = -1
        for (k in 0 until MAX_ATTEMPTS[tier.ordinal]) {
            if (isCancelled()) throw CancellationException("generation cancelled")
            val (p, fill) = candidate(size, tier, seed, k)
            if (p.hasEmptyLine()) continue
            val clues = p.clues
            val lineOnly = Grader.isLineSolvable(clues)
            if (tier.isLineOnly != lineOnly) continue
            if (!lineOnly && !Grader.isP1Solvable(clues)) continue
            val maxP1 = if (tier == Tier.EXPERT) EXPERT_MAX_P1 else Int.MAX_VALUE
            val g = Grader.grade(clues, allowP2 = false, maxP1Steps = maxP1)
            if (accepts(tier, size, g)) return Generated(p, size, tier, seed, k, fill, g, fallback = false)
            // Deterministic fallback: line tiers keep the first line-solvable candidate; look-ahead
            // tiers the one with the most P1 steps, lowest k on ties (an early-stopped Expert counts as most).
            val score = if (g.stoppedEarly) Int.MAX_VALUE else if (g.solved) g.p1Steps else -1
            val better = if (tier.isLineOnly) fallback == null && g.solved else score > fallbackP1
            if (better) {
                fallbackP1 = score
                fallback = Generated(p, size, tier, seed, k, fill, g, fallback = true)
            }
        }
        // Never seen: line tiers accept within a few attempts. A look-ahead tier that found no
        // P1-solvable candidate at all falls back to the same seed's Hard puzzle.
        val fb = fallback ?: if (tier.isLineOnly) error("no line-solvable ${size.label} candidate in ${MAX_ATTEMPTS[tier.ordinal]} attempts")
        else return generate(size, Tier.HARD, seed).let {
            Generated(it.puzzle, size, tier, seed, it.attempt, it.fillPermille, it.grade, fallback = true)
        }
        // A fallback's grade may be partial (early stop); store the full record.
        return Generated(fb.puzzle, size, tier, seed, fb.attempt, fb.fillPermille, Grader.grade(fb.puzzle, allowP2 = false), fallback = true)
    }
}
