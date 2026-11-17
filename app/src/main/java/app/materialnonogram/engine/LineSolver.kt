package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN

/**
 * Single-line deduction, ported from the bench (`NonoDet.Line`, itself `NonoBench.Line` unchanged).
 *
 * - [complete] (L3) is the forward/backward DP: it fixes exactly the cells that take the same value in
 *   every filling of the line consistent with the clue and the known cells (Batenburg & Kosters'
 *   *Settle*), so it subsumes every human single-line rule (01 §1.2). O(k·ℓ).
 * - [leftRight] (L2) and [simple] (L1) are deliberately weaker *views*, used only to give the easiest
 *   deductions a lower per-cell weight. Both are sound and reversal-symmetric.
 *
 * Each call writes the line's deduced states to `out` (known cells copied through) and returns false
 * if the line is contradictory. Not thread-safe: holds scratch buffers. One per [Solver].
 */
class LineSolver {
    private val fw = Array(MAXK + 1) { BooleanArray(MAXN + 2) }
    private val bw = Array(MAXK + 1) { BooleanArray(MAXN + 2) }
    private val pf = IntArray(MAXN + 1)
    private val pe = IntArray(MAXN + 1)
    private val diff = IntArray(MAXN + 1)
    private val minS = IntArray(MAXK)
    private val maxS = IntArray(MAXK)
    private val full = ByteArray(MAXN)

    fun apply(rung: Rung, c: ByteArray, n: Int, cl: IntArray, out: ByteArray): Boolean = when (rung) {
        Rung.L1 -> simple(c, n, cl, out)
        Rung.L2 -> leftRight(c, n, cl, out)
        else -> complete(c, n, cl, out)
    }

    /** L3, complete. */
    fun complete(c: ByteArray, n: Int, cl: IntArray, out: ByteArray): Boolean {
        val k = cl.size
        pf[0] = 0; pe[0] = 0
        for (i in 0 until n) {
            pf[i + 1] = pf[i] + (if (c[i] == FILLED) 1 else 0)
            pe[i + 1] = pe[i] + (if (c[i] == EMPTY) 1 else 0)
        }
        if (k == 0) {
            if (pf[n] != 0) return false
            for (i in 0 until n) out[i] = EMPTY
            return true
        }
        // fw[j][i]: the first j blocks fit in cells [0, i) with cell i-1 not filled by them as a prefix end
        for (i in 0..n) fw[0][i] = pf[i] == 0
        for (j in 1..k) {
            val len = cl[j - 1]; val f = fw[j]; val fp = fw[j - 1]
            for (i in 0..n) {
                var v = i > 0 && c[i - 1] != FILLED && f[i - 1]
                if (!v) {
                    val s = i - len
                    if (s >= 0 && pe[i] - pe[s] == 0) v = if (j == 1) fp[s] else (s >= 1 && c[s - 1] != FILLED && fp[s - 1])
                }
                f[i] = v
            }
        }
        if (!fw[k][n]) return false
        // bw[j][i]: blocks j..k-1 fit in cells [i, n)
        for (i in 0..n) bw[k][i] = pf[n] - pf[i] == 0
        for (j in k - 1 downTo 0) {
            val len = cl[j]; val b = bw[j]; val bn = bw[j + 1]
            for (i in n downTo 0) {
                var v = i < n && c[i] != FILLED && b[i + 1]
                if (!v) {
                    val e = i + len
                    if (e <= n && pe[e] - pe[i] == 0) v = if (j == k - 1) bn[e] else (e < n && c[e] != FILLED && bn[e + 1])
                }
                b[i] = v
            }
        }
        for (i in 0..n) diff[i] = 0
        for (j in 0 until k) {
            val len = cl[j]; minS[j] = Int.MAX_VALUE; maxS[j] = -1
            var s = 0
            while (s + len <= n) {
                val e = s + len
                if (pe[e] - pe[s] == 0) {
                    val left = if (j == 0) fw[0][s] else (s >= 1 && c[s - 1] != FILLED && fw[j][s - 1])
                    if (left) {
                        val right = if (j == k - 1) bw[k][e] else (e < n && c[e] != FILLED && bw[j + 1][e + 1])
                        if (right) {
                            diff[s]++; diff[e]--
                            if (s < minS[j]) minS[j] = s
                            maxS[j] = s
                        }
                    }
                }
                s++
            }
        }
        var run = 0
        for (i in 0 until n) {
            run += diff[i]
            val canFill = run > 0
            var canEmpty = false
            if (c[i] != FILLED) { var j = 0; while (j <= k && !canEmpty) { canEmpty = fw[j][i] && bw[j][i + 1]; j++ } }
            if (!canFill && !canEmpty) return false
            out[i] = if (canFill && canEmpty) UNKNOWN else if (canFill) FILLED else EMPTY
        }
        return true
    }

    /**
     * L2: the leftmost and rightmost legal start of every block given all known cells; a block's
     * overlap is filled, cells no block can reach are empty. Weaker than [complete] when a block's
     * identity is ambiguous (`1 1` on `??#??`).
     */
    fun leftRight(c: ByteArray, n: Int, cl: IntArray, out: ByteArray): Boolean {
        if (!complete(c, n, cl, full)) return false
        val k = cl.size
        for (i in 0 until n) out[i] = c[i]
        if (k == 0) { for (i in 0 until n) out[i] = EMPTY; return true }
        for (j in 0 until k) for (i in maxS[j] until minS[j] + cl[j]) out[i] = FILLED
        for (i in 0 until minS[0]) out[i] = EMPTY
        for (i in maxS[k - 1] + cl[k - 1] until n) out[i] = EMPTY
        for (j in 0 until k - 1) for (i in maxS[j] + cl[j] until minS[j + 1]) out[i] = EMPTY
        return true
    }

    /**
     * L1: the overlap of the clue inside the line's free span (empty cells at either end trimmed), plus
     * "all boxes found ⇒ the rest are empty". Deliberately weak; sound.
     */
    fun simple(c: ByteArray, n: Int, cl: IntArray, out: ByteArray): Boolean {
        for (i in 0 until n) out[i] = c[i]
        val k = cl.size
        var sum = 0; for (x in cl) sum += x
        var filled = 0; for (i in 0 until n) if (c[i] == FILLED) filled++
        if (filled == sum) { for (i in 0 until n) if (out[i] == UNKNOWN) out[i] = EMPTY; return true }
        if (k == 0) { for (i in 0 until n) out[i] = EMPTY; return true }
        var a = 0; var b = n
        while (a < n && c[a] == EMPTY) a++
        while (b > a && c[b - 1] == EMPTY) b--
        val span = b - a; val need = sum + k - 1
        if (need > span) return false
        val slack = span - need; var pos = a
        for (j in 0 until k) {
            for (i in pos + slack until pos + cl[j]) out[i] = FILLED
            if (slack == 0 && j + 1 < k) out[pos + cl[j]] = EMPTY
            pos += cl[j] + 1
        }
        return true
    }

    companion object {
        const val MAXN = Clues.MAX_SIDE
        const val MAXK = MAXN / 2 + 1

        /** Convenience for callers outside the hot path: the L3 settle of one line, or null if contradictory. */
        fun settle(cells: ByteArray, clue: IntArray, rung: Rung = Rung.L3): ByteArray? {
            val out = ByteArray(cells.size)
            return if (LineSolver().apply(rung, cells, cells.size, clue, out)) out else null
        }
    }
}
