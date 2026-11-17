package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN

/**
 * One mark rated on the player's board (01 §4.2, 03 §5.4, `effort-v1`).
 *
 * @property rung T*, the cheapest rung that forces the cell's solution value from the board, or null
 *   if nothing does: the mark was a **guess** (credited 0).
 * @property availability A: lines where a line rung ≤ T* makes progress, or for P1 the cells look-ahead
 *   forces; 1 for P2 (not scanned: too costly, and P2 only occurs in rare imports).
 * @property reference what [availability] is out of: rows + columns, or cells for look-ahead.
 * @property effort10 [Effort.effort10] of the mark.
 */
class MarkAnalysis(val cell: Int, val rung: Rung?, val availability: Int, val reference: Int, val effort10: Int) {
    val isGuess: Boolean get() = rung == null
}

/**
 * A three-step hint on the current board (BUILD-PLAN §3, 01 build §7):
 * 1. [line] has a forced cell; 2. it is [cell], and its value is [value]; 3. why: [rung], and for a
 * look-ahead, "if [cell] were [assumed], [brokenLine] would break".
 *
 * Line indices follow [Clues]: rows `0 until h`, then columns.
 */
class Hint(
    val line: Int,
    val cell: Int,
    val value: Byte,
    val rung: Rung,
    /** Every cell the same deduction forces in [line] (line rungs), or just [cell] (look-ahead). */
    val lineCells: IntArray,
    /** For look-ahead: the value assumed for [cell] to reach the contradiction (the opposite of [value]). */
    val assumed: Byte = UNKNOWN,
    /** For look-ahead: a line that has no consistent filling once [assumed] is placed and propagated. */
    val brokenLine: Int = -1,
)

/**
 * Per-mark analysis and hints for the game layer. Wrong marks are ignored when reasoning: every
 * board is first [sanitize]d against the solution, so a hint is always sound and T* is computed on
 * the player's *correct* knowledge (01 §4.2: "board values, not the player's beliefs").
 *
 * Thread-confined: create one per worker if analysing concurrently.
 */
class Analyzer(val puzzle: Puzzle) {
    private val clues = puzzle.clues
    private val w = puzzle.w
    private val h = puzzle.h
    private val solution = puzzle.solutionBoard()

    /** A copy of [board] with every mark that disagrees with the solution turned back to unknown. */
    fun sanitize(board: ByteArray): ByteArray {
        require(board.size == puzzle.cells)
        return ByteArray(board.size) { if (board[it] != UNKNOWN && board[it] != solution[it]) UNKNOWN else board[it] }
    }

    /**
     * T*: the cheapest rung that forces [cell]'s solution value on [board] (sanitized), trying L1, L2,
     * L3 on the cell's row and column, then P1 on the cell, then P2 if [maxRung] allows. Null = guess.
     * A cell already known on the board returns null.
     */
    fun forcingRung(board: ByteArray, cell: Int, maxRung: Rung = Rung.P2): Rung? {
        val k = sanitize(board)
        if (k[cell] != UNKNOWN) return null
        val s = Solver(clues)
        return forcing(s, k, cell, maxRung)
    }

    private fun forcing(s: Solver, k: ByteArray, cell: Int, maxRung: Rung): Rung? {
        val row = cell / w; val col = h + cell % w
        for (rung in arrayOf(Rung.L1, Rung.L2, Rung.L3)) {
            if (rung > maxRung) return null
            if (lineForces(s, row, rung, k, cell) || lineForces(s, col, rung, k, cell)) return rung
        }
        if (maxRung < Rung.P1) return null
        val opposite = if (solution[cell] == FILLED) EMPTY else FILLED
        if (s.probe(k, cell, opposite) == null) return Rung.P1
        if (maxRung < Rung.P2) return null
        val g = k.copyOf(); g[cell] = opposite
        return if (!s.p1Closure(g)) Rung.P2 else null
    }

    private fun lineForces(s: Solver, line: Int, rung: Rung, k: ByteArray, cell: Int): Boolean {
        val d = s.lineDeductions(line, rung, k) ?: return false
        for (x in d) if (x shr 2 == cell) return true
        return false
    }

    /**
     * Rates the mark the player is about to make (or just made) at [cell], on [before] — the board
     * *before* the mark. Use for correct marks; for a wrong mark the result describes the cell's
     * solution value, which is what the player failed to find.
     */
    fun analyzeMark(before: ByteArray, cell: Int, maxRung: Rung = Rung.P2): MarkAnalysis {
        val k = sanitize(before)
        if (k[cell] != UNKNOWN) return MarkAnalysis(cell, null, 0, 0, 0)
        val s = Solver(clues)
        val rung = forcing(s, k, cell, maxRung) ?: return MarkAnalysis(cell, null, 0, 0, 0)
        val lines = clues.lineCount
        return when {
            rung.isLine -> {
                var a = 0
                for (idx in 0 until lines) {
                    var progress = false
                    for (r in Rung.entries) {
                        if (r > rung) break
                        if (s.lineProgress(idx, r, k) > 0) { progress = true; break }
                    }
                    if (progress) a++
                }
                MarkAnalysis(cell, rung, a, lines, Effort.effort10(rung, lines, maxOf(a, 1)))
            }
            rung == Rung.P1 -> {
                var a = 0
                for (c in 0 until puzzle.cells) {
                    if (k[c] != UNKNOWN) continue
                    val opposite = if (solution[c] == FILLED) EMPTY else FILLED
                    if (s.probe(k, c, opposite) == null) a++
                }
                MarkAnalysis(cell, rung, a, puzzle.cells, Effort.effort10(rung, puzzle.cells, maxOf(a, 1)))
            }
            else -> MarkAnalysis(cell, rung, 1, puzzle.cells, Effort.effort10(rung, puzzle.cells, 1))
        }
    }

    /**
     * The next hint on [board] (sanitized), or null if the board is complete (or, for a puzzle the
     * grader did not finish, if nothing can be deduced).
     *
     * The cheapest rung wins. Among lines at that rung, the one that places the most cells, then
     * rows before columns, then the lower index. The cell is the one nearest the line's centre (lower
     * index on ties). Look-ahead picks, like the grader, a forced cell that unlocks the most, then
     * centre-out, then row-major (03 §5.4: a UI tie-break, not part of any grade).
     */
    fun hint(board: ByteArray, maxRung: Rung = Rung.P2): Hint? {
        val k = sanitize(board)
        if (Solver.unknowns(k) == 0) return null
        val s = Solver(clues)
        for (rung in arrayOf(Rung.L1, Rung.L2, Rung.L3)) {
            if (rung > maxRung) return null
            var bestLine = -1; var best: IntArray? = null
            for (idx in 0 until clues.lineCount) {
                val d = s.lineDeductions(idx, rung, k) ?: continue
                if (d.isNotEmpty() && (best == null || d.size > best.size)) { best = d; bestLine = idx }
            }
            if (best != null) {
                val n = clues.lineLength(bestLine)
                var pick = -1; var pickDist = Int.MAX_VALUE
                for (x in best) {
                    val c = x shr 2
                    val pos = if (bestLine < h) c % w else c / w
                    val dist = kotlin.math.abs(2 * pos - (n - 1))
                    if (dist < pickDist) { pickDist = dist; pick = x }
                }
                return Hint(bestLine, pick shr 2, (pick and 3).toByte(), rung, IntArray(best.size) { best[it] shr 2 })
            }
        }
        if (maxRung < Rung.P1) return null
        val unknownNow = Solver.unknowns(k)
        var pick = -1; var pickGain = -1; var pickDist = Int.MAX_VALUE
        for (c in 0 until puzzle.cells) {
            if (k[c] != UNKNOWN) continue
            val opposite = if (solution[c] == FILLED) EMPTY else FILLED
            if (s.probe(k, c, opposite) != null) continue
            val placed = s.probe(k, c, solution[c]) ?: continue
            val gain = unknownNow - Solver.unknowns(placed)
            val dist = centreDistance(c)
            if (gain > pickGain || (gain == pickGain && dist < pickDist)) { pick = c; pickGain = gain; pickDist = dist }
        }
        if (pick >= 0) {
            val opposite = if (solution[pick] == FILLED) EMPTY else FILLED
            val g = k.copyOf(); g[pick] = opposite
            s.propagateFrom(g, pick)
            return Hint(pick / w, pick, solution[pick], Rung.P1, intArrayOf(pick), opposite, s.lastBrokenLine)
        }
        if (maxRung < Rung.P2) return null
        for (c in centreOutOrder()) {
            if (k[c] != UNKNOWN) continue
            val opposite = if (solution[c] == FILLED) EMPTY else FILLED
            val g = k.copyOf(); g[c] = opposite
            if (!s.p1Closure(g)) return Hint(c / w, c, solution[c], Rung.P2, intArrayOf(c), opposite, s.lastBrokenLine)
        }
        return null
    }

    /** Manhattan distance from the grid centre, doubled to stay integral. */
    private fun centreDistance(c: Int): Int =
        kotlin.math.abs(2 * (c / w) - (h - 1)) + kotlin.math.abs(2 * (c % w) - (w - 1))

    private fun centreOutOrder(): IntArray {
        val keys = LongArray(puzzle.cells) { (centreDistance(it).toLong() shl 32) or it.toLong() }
        keys.sort()
        return IntArray(keys.size) { (keys[it] and 0xFFFFFFFFL).toInt() }
    }
}

/** Line-state helpers for the game: satisfied clues and Tatham's weak contradiction flag. */
object LineCheck {
    /** The filled cells of [cells] form exactly [clue] (unknown and crossed both count as not filled). */
    fun isSatisfied(clue: IntArray, cells: ByteArray): Boolean {
        var j = 0; var run = 0
        for (i in 0..cells.size) {
            if (i < cells.size && cells[i] == FILLED) { run++; continue }
            if (run > 0) { if (j >= clue.size || clue[j] != run) return false; j++; run = 0 }
        }
        return j == clue.size
    }

    /**
     * Simon Tatham's deliberately *weak* error check (Pattern, `check_errors`, MIT licence): only
     * **complete runs** — filled runs bounded at both ends by a crossed cell or the edge — are checked,
     * and they must appear in the clue in order. Runs separated only by crosses must be consecutive in
     * the clue; an unknown cell between two runs breaks that requirement. A stronger check (the line
     * solver) would light up deductions the player has not made, which is a spoiler.
     */
    fun contradicts(clue: IntArray, cells: ByteArray): Boolean {
        val st = ErrState(clue)
        var runLen = -1
        for (j in -1..cells.size) {
            val v = if (j < 0 || j == cells.size) EMPTY else cells[j]
            when (v) {
                UNKNOWN -> { runLen = -1; st.ncontig = 0 }
                FILLED -> if (runLen >= 0) runLen++
                else -> { if (runLen > 0 && !st.found(runLen)) return true; runLen = 0 }
            }
        }
        return !st.found(0)
    }

    /** Port of SGT's `errcheck_state` / `errcheck_found_run`: the clue is padded with a 0 at each end. */
    private class ErrState(val clue: IntArray) {
        var rowPos = 0
        var ncontig = 1 // the initial zero run has been seen

        private fun data(k: Int): Int = if (k < 0 || k >= clue.size) 0 else clue[k]

        fun found(r: Int): Boolean {
            var newPos = rowPos
            while (newPos <= clue.size) {
                var ok = data(newPos) == r
                var i = 1
                while (ok && i <= ncontig) { if (data(newPos - i) != data(rowPos - i)) ok = false; i++ }
                if (ok) { rowPos = newPos + 1; ncontig++; return true }
                newPos++
            }
            return false
        }
    }
}
