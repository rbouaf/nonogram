package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN

/**
 * A grade record (03 §5.3). Every field except the operation counts is a function of *sets*, so it is
 * identical under all 8 transforms (tags map through the transform).
 *
 * @property solved the grader finished: the clues have exactly one solution (the uniqueness proof).
 * @property contradiction the clues have no solution at all.
 * @property ceiling the most expensive rung used, or null if nothing could be placed.
 * @property stepsAt steps per [Rung] (a line rung's step is one Jacobi round over every line; a P1
 *   step is the whole greedy forced set; a P2 step is the whole set of P2-forced cells).
 * @property cellsAt cells first placed by each [Rung].
 * @property sweeps Jacobi rounds of pure L3 from the blank grid to solved, or -1 if line logic stalls.
 * @property firstPass cells that L1 places on each line of the *blank* grid, each line on its own.
 * @property effort10 machine effort: Σ over cells of [Effort.W10] of the cell's tag.
 * @property tags per cell, the rung that first placed it ([Rung.tag]; 0 = never placed).
 * @property board the final board ([solution] when solved).
 * @property stoppedEarly a caller's limit (look-ahead steps or probe budget) cut grading short;
 *   [solved] is then false and the record is partial.
 */
class Grade(
    val w: Int,
    val h: Int,
    val solved: Boolean,
    val contradiction: Boolean,
    val ceiling: Rung?,
    val stepsAt: IntArray,
    val cellsAt: IntArray,
    val sweeps: Int,
    val firstPass: Int,
    val effort10: Int,
    val tags: ByteArray,
    val board: ByteArray,
    val stoppedEarly: Boolean,
    val lineCalls: Long,
    val probes: Long,
) {
    val p1Steps: Int get() = stepsAt[Rung.P1.ordinal]
    val p1Cells: Int get() = cellsAt[Rung.P1.ordinal]
    val p2Steps: Int get() = stepsAt[Rung.P2.ordinal]
    val usesP2: Boolean get() = p2Steps > 0
    val isLineSolvable: Boolean get() = solved && ceiling != null && ceiling.isLine
    val firstPassPermille: Int get() = firstPass * 1000 / (w * h)

    /** The solved puzzle, or null if the grader did not finish. */
    val solution: Puzzle? get() = if (solved) Puzzle.ofBoard(w, h, board) else null

    fun tagOf(cell: Int): Rung? = Rung.ofTag(tags[cell])

    /** Content equality (operation counts excluded): what the invariance tests compare. */
    override fun equals(other: Any?): Boolean = other is Grade && key() == other.key() && tags.contentEquals(other.tags)
    override fun hashCode(): Int = key().length

    /** Every scalar field in a fixed order; tags excluded (they move with a transform). */
    fun key(): String = buildString {
        append(solved).append('|').append(contradiction).append('|').append(ceiling?.name ?: "-").append('|')
        for (x in stepsAt) append(x).append(',')
        append('|'); for (x in cellsAt) append(x).append(',')
        append('|').append(sweeps).append('|').append(firstPass).append('|').append(effort10).append('|').append(stoppedEarly)
    }

    override fun toString(): String = "Grade(${w}x$h ${key()})"
}

/**
 * The order-free, easiest-first grader (03 §5.3, `NonoDet.gradeOrderFree(greedy = true)`), with P2 added.
 *
 * 1. **Line rungs, Jacobi style.** From one snapshot, every line's deductions at L1 (else L2, else L3)
 *    are computed and their union applied: one round = one step. Restart at L1 after any progress.
 * 2. **P1, greedy over the forced set.** When every line rung is stuck, every unknown cell is probed
 *    both ways on the same snapshot; of the forced cells, the ones whose placement (with L3
 *    propagation) determines the most cells are applied together as **one** P1 step.
 * 3. **P2, layered.** When P1 is stuck too, every unknown cell is probed both ways with a full
 *    line + P1 closure inside the probe; every forced cell is applied as one P2 step. Never used by the
 *    generator (it rejects anything P1 cannot finish); it exists so an imported puzzle that needs it is
 *    graded rather than refused. 01 found 1 such grid in ~87,900 unique ones.
 *
 * All three are functions of sets, so the record is invariant under every transform; the tests
 * check it on fixtures × 8 transforms.
 */
object Grader {
    /** Bumped on any change to any field of the output (03 §4). */
    const val VERSION = 1

    /** Default P2 budget in probes (a 20×20 P2 step costs ~10⁵). Deterministic: counts, not time. */
    const val DEFAULT_P2_PROBE_BUDGET = 4_000_000L

    fun grade(puzzle: Puzzle, allowP2: Boolean = true): Grade = grade(puzzle.clues, allowP2)

    /**
     * Grades [clues]. [maxP1Steps] stops early (with [Grade.stoppedEarly]) once more look-ahead steps
     * than that would be needed — the generator's cheap reject for Expert. [p2ProbeBudget] caps P2.
     */
    fun grade(
        clues: Clues,
        allowP2: Boolean = true,
        maxP1Steps: Int = Int.MAX_VALUE,
        p2ProbeBudget: Long = DEFAULT_P2_PROBE_BUDGET,
    ): Grade {
        val s = Solver(clues)
        val n = s.cells
        var g = ByteArray(n)
        val tag = ByteArray(n)
        val stepsAt = IntArray(Rung.entries.size)
        val cellsAt = IntArray(Rung.entries.size)
        var ceiling: Rung? = null
        var contradiction = false
        var stopped = false
        val lineRungs = arrayOf(Rung.L1, Rung.L2, Rung.L3)
        var p2Limit = -1L // set on first entry to P2: the budget covers every P2 step together, not each one

        main@ while (true) {
            for (rung in lineRungs) {
                val next = g.copyOf()
                var progress = 0
                for (idx in 0 until s.lineCount) {
                    val ch = s.lineStep(idx, rung, g, next, tag, rung.tag)
                    if (ch < 0) { contradiction = true; break@main }
                    progress += ch
                }
                if (progress > 0) {
                    g = next; cellsAt[rung.ordinal] += progress; stepsAt[rung.ordinal]++
                    if (ceiling == null || rung > ceiling) ceiling = rung
                    continue@main
                }
            }
            if (Solver.unknowns(g) == 0) break

            // ---- P1, greedy
            if (stepsAt[Rung.P1.ordinal] >= maxP1Steps) { stopped = true; break }
            val unknownNow = Solver.unknowns(g)
            val value = ByteArray(n)
            val gain = IntArray(n)
            var bestGain = -1
            val ok = ProbeCache(n) // probes known to survive on this snapshot; skipping them changes no output
            for (cell in 0 until n) {
                if (g[cell] != UNKNOWN) continue
                val fKnown = ok.survives(cell, FILLED)
                val f = if (fKnown) null else s.probe(g, cell, FILLED)
                if (f != null) ok.record(g, f)
                val fOk = fKnown || f != null
                val eKnown = ok.survives(cell, EMPTY)
                val e = if (eKnown) null else s.probe(g, cell, EMPTY)
                if (e != null) ok.record(g, e)
                val eOk = eKnown || e != null
                if (!fOk && !eOk) { contradiction = true; break@main }
                if (fOk && eOk) continue
                value[cell] = if (fOk) FILLED else EMPTY
                // The surviving probe holds the propagated board: its unknown count gives the gain.
                val survivor = (if (fOk) f else e) ?: s.probe(g, cell, value[cell])!!
                gain[cell] = unknownNow - Solver.unknowns(survivor)
                if (gain[cell] > bestGain) bestGain = gain[cell]
            }
            if (bestGain >= 0) {
                val next = g.copyOf(); var forced = 0
                for (cell in 0 until n) {
                    if (value[cell] == UNKNOWN || gain[cell] != bestGain) continue
                    next[cell] = value[cell]; tag[cell] = Rung.P1.tag; forced++
                }
                g = next; cellsAt[Rung.P1.ordinal] += forced; stepsAt[Rung.P1.ordinal]++; ceiling = maxRung(ceiling, Rung.P1)
                continue@main
            }

            // ---- P2, layered
            if (!allowP2) break
            if (p2Limit < 0) p2Limit = s.probes + p2ProbeBudget
            val limit = p2Limit
            val next = g.copyOf(); var forced = 0
            for (cell in 0 until n) {
                if (g[cell] != UNKNOWN) continue
                val f = g.copyOf(); f[cell] = FILLED
                val fOk = s.p1Closure(f, limit)
                val e = g.copyOf(); e[cell] = EMPTY
                val eOk = s.p1Closure(e, limit)
                if (s.probes >= limit) { stopped = true; break@main }
                if (!fOk && !eOk) { contradiction = true; break@main }
                if (fOk && eOk) continue
                next[cell] = if (fOk) FILLED else EMPTY; tag[cell] = Rung.P2.tag; forced++
            }
            if (forced == 0) break
            g = next; cellsAt[Rung.P2.ordinal] += forced; stepsAt[Rung.P2.ordinal]++; ceiling = maxRung(ceiling, Rung.P2)
        }

        val solved = !contradiction && !stopped && Solver.unknowns(g) == 0
        var effort = 0
        for (i in 0 until n) if (tag[i] > 0) effort += Effort.W10[tag[i] - 1]

        // Sweeps: Jacobi rounds of pure L3 from blank.
        var b = ByteArray(n); var rounds = 0
        while (Solver.unknowns(b) > 0) {
            val next = b.copyOf(); var ch = 0; var bad = false
            for (idx in 0 until s.lineCount) {
                val c = s.lineStep(idx, Rung.L3, b, next)
                if (c < 0) { bad = true; break }
                ch += c
            }
            if (bad || ch == 0) break
            b = next; rounds++
        }
        val sweeps = if (Solver.unknowns(b) == 0) rounds else -1

        // First pass: L1 on each line of the blank grid, each line on its own.
        val blank = ByteArray(n); val f = ByteArray(n)
        for (idx in 0 until s.lineCount) s.lineStep(idx, Rung.L1, blank, f)
        val firstPass = n - Solver.unknowns(f)

        return Grade(
            clues.w, clues.h, solved, contradiction, ceiling, stepsAt, cellsAt, sweeps, firstPass, effort,
            tag, g, stopped, s.lineCalls, s.probes,
        )
    }

    /** Line logic alone finishes [clues] (the generator's cheap pre-filter; a unique fixpoint). */
    fun isLineSolvable(clues: Clues): Boolean {
        val s = Solver(clues); val g = ByteArray(s.cells)
        return s.propagate(g) && Solver.unknowns(g) == 0
    }

    /** Line + P1 logic finishes [clues] (sequential closure: much cheaper than [grade], same verdict). */
    fun isP1Solvable(clues: Clues): Boolean {
        val s = Solver(clues); val g = ByteArray(s.cells)
        return s.p1Closure(g) && Solver.unknowns(g) == 0
    }

    private fun maxRung(a: Rung?, b: Rung): Rung = if (a == null || b > a) b else a
}
