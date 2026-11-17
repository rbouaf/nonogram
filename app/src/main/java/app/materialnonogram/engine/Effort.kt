package app.materialnonogram.engine

/**
 * The per-mark effort model `effort-v1` (01 §4.2, 03 §5.4), in integers.
 *
 * `E = w(T*) · S(A)`, `S(A) = 1 + 0.3 · ln(ref / A)`, where T* is the cheapest rung that forces the
 * mark and A its availability on the player's board: the number of lines where a line rung ≤ T*
 * makes progress (`ref` = rows + columns), or for a look-ahead mark the number of cells look-ahead
 * forces (`ref` = cells, so `A ≤ ref` and `S ≥ 1` in both cases). Few open moves ⇒ costlier find.
 *
 * Weights ×10 so everything stays integer (03 §3.4: no floating point in the engine). `ln` is a
 * fixed-point log computed bit by bit, never `Math.log`, whose results are not bit-for-bit specified.
 */
object Effort {
    const val VERSION = "effort-v1"

    /** Per-rung weight ×10, indexed by [Rung.ordinal]: L1 1.0, L2 1.5, L3 2.0, P1 10, P2 30. */
    val W10: IntArray = intArrayOf(10, 15, 20, 100, 300)

    /** α = 0.3 as permille. */
    private const val ALPHA_PERMILLE = 300L

    /** ln 2 in Q16 (0.693147 · 65536 = 45426.1). */
    private const val LN2_Q16 = 45426L

    /** log2(x) in Q16 for x ≥ 1, by repeated squaring of the normalised mantissa. Exact to 16 fraction bits (truncated). */
    fun log2Q16(x: Long): Long {
        require(x >= 1)
        val ip = 63 - java.lang.Long.numberOfLeadingZeros(x)
        var m = if (ip >= 30) x ushr (ip - 30) else x shl (30 - ip) // Q30 in [1, 2)
        var frac = 0L
        for (i in 1..16) {
            m = (m * m) ushr 30
            if (m >= (2L shl 30)) { m = m ushr 1; frac = frac or (1L shl (16 - i)) }
        }
        return (ip.toLong() shl 16) + frac
    }

    /** ln(x) in Q16. */
    fun lnQ16(x: Long): Long = log2Q16(x) * LN2_Q16 shr 16

    /** S(A) ×1000 for availability [a] out of [ref]; 1000 when a ≥ ref. */
    fun scan1000(ref: Int, a: Int): Int {
        require(a >= 1 && ref >= 1)
        if (a >= ref) return 1000
        return (1000 + ALPHA_PERMILLE * (lnQ16(ref.toLong()) - lnQ16(a.toLong())) / 65536).toInt()
    }

    /** Effort ×10 of one mark: 0 for a guess ([rung] null or [a] 0). */
    fun effort10(rung: Rung?, ref: Int, a: Int): Int {
        if (rung == null || a <= 0) return 0
        return (W10[rung.ordinal] * scan1000(ref, a) + 500) / 1000
    }
}
