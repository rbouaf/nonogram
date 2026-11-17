package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {
    private fun line(s: String): ByteArray = ByteArray(s.length) { when (s[it]) { '#' -> FILLED; 'x' -> EMPTY; else -> UNKNOWN } }

    /** Following hints alone solves every fixture, every hint is sound, and the rungs used mirror the grade. */
    @Test fun hintsSolveEveryFixture() {
        for (gen in Fixtures.generated) {
            val p = gen.puzzle; val a = Analyzer(p); val sol = p.solutionBoard()
            val board = ByteArray(p.cells)
            var lookAheads = 0; var hints = 0
            while (true) {
                val h = a.hint(board) ?: break
                hints++
                assertEquals("hint value", sol[h.cell], h.value)
                assertEquals(UNKNOWN, board[h.cell])
                assertTrue(h.cell in Lines.cells(p.w, p.h, h.line))
                if (h.rung.isLine) {
                    assertTrue(h.cell in h.lineCells)
                    for (c in h.lineCells) assertTrue(c in Lines.cells(p.w, p.h, h.line))
                } else {
                    lookAheads++
                    assertEquals(if (h.value == FILLED) EMPTY else FILLED, h.assumed)
                    assertTrue(h.brokenLine in 0 until p.w + p.h)
                }
                board[h.cell] = h.value
            }
            assertTrue(board.contentEquals(sol))
            if (gen.tier.isLineOnly) assertEquals(0, lookAheads) else assertTrue("${gen.size} ${gen.tier}", lookAheads > 0)
            assertTrue(hints > 0)
        }
    }

    @Test fun hintIgnoresWrongMarks() {
        val gen = Fixtures.generated.first { it.size == Size.S10 && it.tier == Tier.MEDIUM }
        val p = gen.puzzle; val a = Analyzer(p)
        val board = ByteArray(p.cells) { if (p.filled(it)) EMPTY else FILLED } // every mark wrong
        val h = a.hint(board)!!
        assertEquals(a.hint(ByteArray(p.cells))!!.cell, h.cell)
        assertNull(a.hint(p.solutionBoard()))
    }

    @Test fun forcingRungOnABlankBoardMatchesTheFirstRound() {
        // Every cell L1 places in the grader's first round is forced at L1 from blank.
        for (gen in Fixtures.generated.filter { it.size == Size.S10 }) {
            val p = gen.puzzle; val a = Analyzer(p)
            val blank = ByteArray(p.cells)
            val s = Solver(p.clues); val next = ByteArray(p.cells)
            for (i in 0 until p.w + p.h) s.lineStep(i, Rung.L1, blank, next)
            for (c in 0 until p.cells) {
                val r = a.forcingRung(blank, c)
                if (next[c] != UNKNOWN) assertEquals(Rung.L1, r) else assertTrue(r == null || r > Rung.L1)
            }
        }
    }

    @Test fun lookAheadAndGuessesAreDetected() {
        val gen = Fixtures.generated.first { it.size == Size.S15 && it.tier == Tier.MASTER }
        val p = gen.puzzle; val a = Analyzer(p)
        // Walk the hints up to the first look-ahead: that cell is forced at P1, not by any line rung.
        val board = ByteArray(p.cells)
        while (true) {
            val h = a.hint(board)!!
            if (h.rung == Rung.P1) {
                assertEquals(Rung.P1, a.forcingRung(board, h.cell))
                val m = a.analyzeMark(board, h.cell)
                assertEquals(Rung.P1, m.rung); assertTrue(m.availability >= 1); assertEquals(p.cells, m.reference)
                assertTrue(m.effort10 >= Effort.W10[Rung.P1.ordinal])
                // Some unknown cell here is not forced even by look-ahead: placing it is a guess.
                val guessCell = (0 until p.cells).firstOrNull { board[it] == UNKNOWN && a.forcingRung(board, it, Rung.P1) == null }
                assertNotNull(guessCell)
                val g = a.analyzeMark(board, guessCell!!, Rung.P1)
                assertTrue(g.isGuess); assertEquals(0, g.effort10)
                break
            }
            board[h.cell] = h.value
        }
    }

    @Test fun lineMarkAvailability() {
        val p = Fixtures.generated.first { it.size == Size.S10 && it.tier == Tier.EASY }.puzzle
        val a = Analyzer(p); val blank = ByteArray(p.cells)
        val h = a.hint(blank)!!
        val m = a.analyzeMark(blank, h.cell)
        assertEquals(h.rung, m.rung)
        assertEquals(p.w + p.h, m.reference)
        assertTrue(m.availability in 1..p.w + p.h)
        assertEquals(Effort.effort10(m.rung, m.reference, m.availability), m.effort10)
        // A known cell earns nothing.
        val b = blank.copyOf(); b[h.cell] = h.value
        assertNull(a.analyzeMark(b, h.cell).rung)
    }

    @Test fun satisfied() {
        assertTrue(LineCheck.isSatisfied(intArrayOf(2, 1), line("##x#.")))
        assertTrue(LineCheck.isSatisfied(intArrayOf(2, 1), line(".##.#")))
        assertFalse(LineCheck.isSatisfied(intArrayOf(2, 1), line("###.#")))
        assertFalse(LineCheck.isSatisfied(intArrayOf(2, 1), line("##...")))
        assertTrue(LineCheck.isSatisfied(IntArray(0), line("x..x.")))
        assertFalse(LineCheck.isSatisfied(IntArray(0), line("x.#x.")))
    }

    /** Tatham's weak check: only complete runs count, and only an unknown cell may separate non-consecutive clues. */
    @Test fun weakContradictionCheck() {
        val cl = intArrayOf(1, 2, 1)
        assertFalse(LineCheck.contradicts(cl, line("........")))
        assertFalse(LineCheck.contradicts(cl, line("#x##x#..")))
        assertFalse(LineCheck.contradicts(cl, line(".###....")))   // unbounded run: not checked (weak by design)
        assertTrue(LineCheck.contradicts(cl, line("x###x...")))    // complete run of 3: no 3 in the clue
        assertTrue(LineCheck.contradicts(cl, line("##x.....")))    // a run at the edge must be the first clue
        assertFalse(LineCheck.contradicts(cl, line(".##x....")))   // ...unless an unknown precedes it
        assertTrue(LineCheck.contradicts(cl, line("#x#x....")))    // 1 then 1 consecutive, but the clue is 1 2
        assertFalse(LineCheck.contradicts(cl, line("#x#.....")))   // the second run is not complete
        assertTrue(LineCheck.contradicts(cl, line("#x##x#x#")))    // one run too many, all complete
        assertTrue(LineCheck.contradicts(cl, line("#x##xxxx")))    // finished line, a run missing
        assertTrue(LineCheck.contradicts(IntArray(0), line("x#x")))
        assertFalse(LineCheck.contradicts(IntArray(0), line("x.x")))
    }

    @Test fun integerLogAndEffort() {
        assertEquals(3L shl 16, Effort.log2Q16(8))
        assertEquals(0L, Effort.log2Q16(1))
        assertEquals(103_872L, Effort.log2Q16(3)) // log2 3 = 1.5849625 · 65536 = 103872.6, truncated
        assertEquals(1000, Effort.scan1000(40, 40))
        assertEquals(2106, Effort.scan1000(40, 1)) // 1 + 0.3 · ln 40 = 2.1067
        assertEquals(10, Effort.effort10(Rung.L1, 30, 30))
        assertEquals(211, Effort.effort10(Rung.P1, 40, 1))
        assertEquals(0, Effort.effort10(null, 40, 1))
        assertEquals("effort-v1", Effort.VERSION)
    }
}
