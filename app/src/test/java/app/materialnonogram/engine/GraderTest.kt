package app.materialnonogram.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraderTest {
    /**
     * The Kotlin port grades 504 blobs exactly as the bench's `NonoDet.gradeGreedy` does (03 §5.3):
     * every field, solved or stuck, on every size, from 250‰ to 650‰ fill.
     */
    @Test fun matchesTheBenchGrader() {
        assertEquals(504, Fixtures.bench.size)
        for (r in Fixtures.bench) {
            val g = Grader.grade(r.puzzle, allowP2 = false)
            val what = r.line.take(60)
            assertEquals(what, r.solved, g.solved)
            assertEquals(what, r.ceiling, (g.ceiling?.ordinal ?: -1) + 1)
            assertEquals(what, r.steps, g.stepsAt.take(4))
            assertEquals(what, r.cells, g.cellsAt.take(4))
            assertEquals(what, r.effort10, g.effort10)
            assertEquals(what, r.sweeps, g.sweeps)
            assertEquals(what, r.firstPass, g.firstPass)
            assertFalse(g.contradiction)
            if (g.solved) assertEquals(r.puzzle, g.solution)
        }
    }

    /** 03 §5.3: every field is identical under all 8 transforms, wide outputs included; tags move with the cells. */
    @Test fun gradeIsInvariantUnderEveryTransform() {
        val puzzles = Fixtures.generated.map { it.puzzle } + Fixtures.bench.filter { it.solved }.take(60).map { it.puzzle }
        for (p in puzzles) {
            val g = Grader.grade(p)
            for (t in 0 until 8) {
                val q = p.transform(t)
                val gt = Grader.grade(q)
                assertEquals("$p t=$t", g.key(), gt.key())
                assertArrayEquals("$p t=$t tags", Transform.bytes(p.w, p.h, t, g.tags), gt.tags)
            }
        }
    }

    /** A cheap cross-check that would catch an asymmetric rule the Jacobi rounds still hide. */
    @Test fun canonicalRepresentativeGradesTheSame() {
        for (gen in Fixtures.generated) {
            assertEquals(gen.grade.key(), Grader.grade(Canonical.of(gen.puzzle).puzzle).key())
        }
    }

    @Test fun generatedGradesAreTheFullGrade() {
        // The generator grades with P2 off and Expert's early stop; an accepted record must equal the full grade.
        for (gen in Fixtures.generated) assertEquals(gen.grade.key(), Grader.grade(gen.puzzle).key())
    }

    @Test fun webpbnOne() {
        // Jan Wolter's demo puzzle #1 (5 wide × 10 tall), goal from webpbn's .non export.
        val goal = "01100011010010101110101001010000110010100101111000"
        val p = Puzzle(5, 10, BooleanArray(50) { goal[it] == '1' })
        assertEquals("2|2,1|1,1|3|1,1|1,1|2|1,1|1,2|2", p.clues.rows.joinToString("|") { it.joinToString(",") })
        assertEquals("2,1|2,1,3|7|1,3|2,1", p.clues.cols.joinToString("|") { it.joinToString(",") })
        val g = Grader.grade(p)
        assertTrue(g.solved)
        assertEquals(p, g.solution)
    }

    @Test fun nonUniqueIsNotSolved() {
        // The 2×2 checkerboard: two solutions, so no sound deduction places anything.
        val g = Grader.grade(Puzzle.of("#.", ".#"))
        assertFalse(g.solved); assertFalse(g.contradiction); assertNull(g.ceiling); assertNull(g.solution)
        assertEquals(4, Solver.unknowns(g.board))
    }

    @Test fun contradictoryCluesAreFlagged() {
        val c = Clues(2, 2, arrayOf(intArrayOf(2), intArrayOf(2)), arrayOf(intArrayOf(1), IntArray(0)))
        assertTrue(Grader.grade(c).contradiction)
    }

    @Test fun lineSolvableVerdictsAgree() {
        for (r in Fixtures.bench) {
            val g = Grader.grade(r.puzzle, allowP2 = false)
            assertEquals(g.isLineSolvable, Grader.isLineSolvable(r.puzzle.clues))
            assertEquals(g.solved, Grader.isP1Solvable(r.puzzle.clues))
        }
    }

    @Test fun earlyStopIsReported() {
        val master = Fixtures.generated.first { it.tier == Tier.MASTER }
        val g = Grader.grade(master.puzzle.clues, maxP1Steps = 1)
        assertTrue(g.stoppedEarly); assertFalse(g.solved); assertEquals(1, g.p1Steps)
    }

    @Test fun tagsCoverEveryCellOfASolvedPuzzle() {
        for (gen in Fixtures.generated) {
            val g = gen.grade
            assertTrue(g.tags.all { it in 1..4 })
            for (rung in Rung.entries) assertEquals(g.cellsAt[rung.ordinal], g.tags.count { it == rung.tag })
            assertNotNull(g.ceiling)
            assertEquals(Rung.entries.filter { g.cellsAt[it.ordinal] > 0 }.max(), g.ceiling)
        }
    }

    /**
     * P2's core — a full line + P1 closure inside a probe — is sound and at least as strong as P1. No
     * natural puzzle at the app's sizes needs P2 (01 found 1 in ~87,900 unique grids, at 30×30), so
     * this checks the machinery directly at every Expert puzzle's first stuck point.
     */
    @Test fun deepProbeIsSoundAndSubsumesLookAhead() {
        for (gen in Fixtures.generated.filter { it.tier == Tier.EXPERT && it.size != Size.S20 }) {
            val p = gen.puzzle; val sol = p.solutionBoard()
            val s = Solver(p.clues); val g = ByteArray(p.cells)
            assertTrue(s.propagate(g))
            for (c in 0 until p.cells) {
                if (g[c] != Cell.UNKNOWN) continue
                val p1Forced = s.probe(g, c, Cell.FILLED) == null || s.probe(g, c, Cell.EMPTY) == null
                val f = g.copyOf(); f[c] = Cell.FILLED; val fOk = s.p1Closure(f)
                val e = g.copyOf(); e[c] = Cell.EMPTY; val eOk = s.p1Closure(e)
                assertTrue(fOk || eOk)
                if (!fOk) assertEquals(Cell.EMPTY, sol[c])
                if (!eOk) assertEquals(Cell.FILLED, sol[c])
                if (p1Forced) assertFalse(fOk && eOk)
            }
        }
    }
}
