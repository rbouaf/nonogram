package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN

/**
 * Grid-level reasoning over one clue set: line steps, the propagator (a FIFO of dirty lines solved
 * with L3 until nothing changes) and the probes behind P1 and P2. Boards are `ByteArray`s of
 * [Cell] states, row-major. Not thread-safe (scratch buffers); one per grading or analysis call.
 *
 * Every closure here is order-free in its *result*: line propagation and line + P1 propagation each
 * have a unique fixpoint (03 §5.1, measured 0 mismatches in 41,400). Only the *path* depends on
 * order, which is why the grader counts steps Jacobi-style instead of along this queue.
 *
 * [lineCalls] and [probes] count operations; budgets are expressed in them, never in time.
 */
class Solver(val clues: Clues) {
    val w = clues.w
    val h = clues.h
    val lineCount = w + h
    val cells = w * h

    private val line = LineSolver()
    private val buf = ByteArray(LineSolver.MAXN)
    private val out = ByteArray(LineSolver.MAXN)
    private val before = ByteArray(LineSolver.MAXN)
    private val queue = IntArray(lineCount)
    private val inQueue = BooleanArray(lineCount)

    var lineCalls = 0L; private set
    var probes = 0L; private set

    /** The line that failed in the most recent contradiction from [propagate] or [lineStep], or -1. */
    var lastBrokenLine = -1; private set

    fun lineLength(idx: Int): Int = if (idx < h) w else h
    fun cellOf(idx: Int, i: Int): Int = if (idx < h) idx * w + i else i * w + (idx - h)

    /**
     * Deduces line [idx] at [rung] from [src] and writes newly known cells into [dst] (which may be
     * [src]). New cells are tagged with [tagVal] in [tag] if given. Returns the number of new cells,
     * or -1 on a contradiction (the line has no consistent filling, or [dst] already disagrees, which
     * in a Jacobi round means two lines disagree).
     */
    fun lineStep(idx: Int, rung: Rung, src: ByteArray, dst: ByteArray, tag: ByteArray? = null, tagVal: Byte = 0): Int {
        val n = lineLength(idx)
        for (i in 0 until n) buf[i] = src[cellOf(idx, i)]
        lineCalls++
        if (!line.apply(rung, buf, n, clues.line(idx), out)) { lastBrokenLine = idx; return -1 }
        var ch = 0
        for (i in 0 until n) {
            val o = out[i]
            if (o == UNKNOWN) continue
            if (buf[i] != UNKNOWN) { if (o != buf[i]) { lastBrokenLine = idx; return -1 }; continue }
            val cell = cellOf(idx, i)
            val d = dst[cell]
            if (d == UNKNOWN) { dst[cell] = o; ch++; if (tag != null) tag[cell] = tagVal }
            else if (d != o) { lastBrokenLine = idx; return -1 }
        }
        return ch
    }

    /** True if line [idx] at [rung] would place at least one new cell on [g] (no contradiction). */
    fun lineProgress(idx: Int, rung: Rung, g: ByteArray): Int {
        val n = lineLength(idx)
        for (i in 0 until n) buf[i] = g[cellOf(idx, i)]
        lineCalls++
        if (!line.apply(rung, buf, n, clues.line(idx), out)) return -1
        var ch = 0
        for (i in 0 until n) if (buf[i] == UNKNOWN && out[i] != UNKNOWN) ch++ else if (buf[i] != UNKNOWN && out[i] != UNKNOWN && out[i] != buf[i]) return -1
        return ch
    }

    /** The cells of line [idx] that [rung] newly determines on [g], as (cell, value) pairs packed `cell shl 2 or value`. */
    fun lineDeductions(idx: Int, rung: Rung, g: ByteArray): IntArray? {
        val n = lineLength(idx)
        for (i in 0 until n) buf[i] = g[cellOf(idx, i)]
        lineCalls++
        if (!line.apply(rung, buf, n, clues.line(idx), out)) return null
        val res = IntArray(n); var k = 0
        for (i in 0 until n) if (buf[i] == UNKNOWN && out[i] != UNKNOWN) res[k++] = (cellOf(idx, i) shl 2) or out[i].toInt()
        return res.copyOf(k)
    }

    /**
     * L3 propagation to the fixpoint, in place. Lines start dirty if [dirty] is null or marks them.
     * Returns false on a contradiction ([lastBrokenLine] says where).
     */
    fun propagate(g: ByteArray, dirty: BooleanArray? = null): Boolean {
        val lc = lineCount
        var qh = 0; var qt = 0; var qn = 0
        for (i in 0 until lc) inQueue[i] = false
        for (i in 0 until lc) if (dirty == null || dirty[i]) { queue[qt] = i; qt = (qt + 1) % lc; inQueue[i] = true; qn++ }
        while (qn > 0) {
            val idx = queue[qh]; qh = (qh + 1) % lc; qn--; inQueue[idx] = false
            val n = lineLength(idx)
            for (i in 0 until n) before[i] = g[cellOf(idx, i)]
            val ch = lineStep(idx, Rung.L3, g, g)
            if (ch < 0) return false
            if (ch == 0) continue
            for (i in 0 until n) if (before[i] == UNKNOWN && g[cellOf(idx, i)] != UNKNOWN) {
                val other = if (idx < h) h + i else i
                if (!inQueue[other]) { inQueue[other] = true; queue[qt] = other; qt = (qt + 1) % lc; qn++ }
            }
        }
        return true
    }

    /** Propagates after [cell] was set on [g], in place: only its row and column start dirty. */
    fun propagateFrom(g: ByteArray, cell: Int): Boolean {
        val d = BooleanArray(lineCount); d[cell / w] = true; d[h + cell % w] = true
        return propagate(g, d)
    }

    /** Assume [cell] = [v] on a copy of [g] and propagate. Null on a contradiction, else the propagated copy. */
    fun probe(g: ByteArray, cell: Int, v: Byte): ByteArray? {
        probes++
        val g2 = g.copyOf(); g2[cell] = v
        return if (propagateFrom(g2, cell)) g2 else null
    }

    /**
     * Line + P1 closure in place (sequential; the result is a unique fixpoint, so order is irrelevant).
     * Stops early, returning true with cells still unknown, once [probes] exceeds [probeLimit].
     * Returns false on a contradiction.
     *
     * Probes are skipped when already known to survive: if `c = v` propagates without contradiction
     * to a board where `d = x`, then `d = x` alone reaches a subset of that consistent board, so it
     * survives too (monotonicity). The cache is cleared whenever the board changes.
     */
    fun p1Closure(g: ByteArray, probeLimit: Long = Long.MAX_VALUE): Boolean {
        if (!propagate(g)) return false
        val ok = ProbeCache(cells)
        var changed = true
        while (changed) {
            changed = false
            ok.clear()
            for (cell in 0 until cells) {
                if (g[cell] != UNKNOWN) continue
                if (probes >= probeLimit) return true
                var f: ByteArray? = null
                if (!ok.survives(cell, FILLED)) {
                    f = probe(g, cell, FILLED)
                    if (f == null) {
                        g[cell] = EMPTY
                        if (!propagateFrom(g, cell)) return false
                        changed = true; ok.clear(); continue
                    }
                    ok.record(g, f)
                }
                if (!ok.survives(cell, EMPTY)) {
                    val e = probe(g, cell, EMPTY)
                    if (e == null) {
                        if (f != null) f.copyInto(g) else { g[cell] = FILLED; if (!propagateFrom(g, cell)) return false }
                        changed = true; ok.clear(); continue
                    }
                    ok.record(g, e)
                }
            }
        }
        return true
    }

    companion object {
        fun unknowns(g: ByteArray): Int { var u = 0; for (b in g) if (b == UNKNOWN) u++; return u }
    }
}

/** Which single-cell assumptions are known to survive propagation on the current board (see [Solver.p1Closure]). */
class ProbeCache(cells: Int) {
    private val fill = BooleanArray(cells)
    private val empty = BooleanArray(cells)

    fun clear() { fill.fill(false); empty.fill(false) }

    fun survives(cell: Int, v: Byte): Boolean = if (v == FILLED) fill[cell] else empty[cell]

    /** [result] is a consistent propagation of [g] plus one assumption: every cell it decided survives as decided. */
    fun record(g: ByteArray, result: ByteArray) {
        for (d in g.indices) {
            if (g[d] != UNKNOWN) continue
            when (result[d]) { FILLED -> fill[d] = true; EMPTY -> empty[d] = true }
        }
    }
}
