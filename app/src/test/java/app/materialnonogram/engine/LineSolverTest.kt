package app.materialnonogram.engine

import app.materialnonogram.engine.Cell.EMPTY
import app.materialnonogram.engine.Cell.FILLED
import app.materialnonogram.engine.Cell.UNKNOWN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSolverTest {
    /**
     * The bench's self-test (01 §1.2): random partial lines of length 1–12, a third of the cells
     * revealed and one in ten made possibly inconsistent, checked against brute-force enumeration of
     * every filling. L3 must be exact (feasibility and every cell); L1 and L2 must be sound, and L2 may
     * never claim more than L3.
     */
    @Test fun completeMatchesBruteForce() {
        val rng = SplitMix64(3)
        val solver = LineSolver()
        // Per length, the clue of every mask, as an index into a per-length table of distinct clues.
        val clueIds = Array(13) { n -> IntArray(1 shl n) }
        val clueOf = Array(13) { ArrayList<IntArray>() }
        for (n in 1..12) for (m in 0 until (1 shl n)) {
            val cl = cluesOf(BooleanArray(n) { (m shr it) and 1 != 0 })
            var id = clueOf[n].indexOfFirst { it.contentEquals(cl) }
            if (id < 0) { id = clueOf[n].size; clueOf[n].add(cl) }
            clueIds[n][m] = id
        }
        var consistent = 0
        val out = ByteArray(12); val o1 = ByteArray(12); val o2 = ByteArray(12)
        repeat(200_000) {
            val n = 1 + rng.nextInt(12)
            val solMask = rng.nextInt(1 shl n)
            val clueId = clueIds[n][solMask]
            val cl = clueOf[n][clueId]
            val c = ByteArray(n)
            for (i in 0 until n) if (rng.nextInt(3) == 0) c[i] = if ((solMask shr i) and 1 != 0) FILLED else EMPTY
            if (rng.nextInt(10) == 0) c[rng.nextInt(n)] = if (rng.nextInt(2) == 0) FILLED else EMPTY
            var fMask = 0; var eMask = 0
            for (i in 0 until n) { if (c[i] == FILLED) fMask = fMask or (1 shl i); if (c[i] == EMPTY) eMask = eMask or (1 shl i) }
            var any = false; var canF = 0; var canE = 0
            for (m in 0 until (1 shl n)) {
                if (fMask and m.inv() != 0 || eMask and m != 0 || clueIds[n][m] != clueId) continue
                any = true; canF = canF or m; canE = canE or m.inv()
            }
            val res = solver.complete(c, n, cl, out)
            assertEquals("feasibility ${c.toList()} ${cl.toList()}", any, res)
            if (!any) return@repeat
            consistent++
            for (i in 0 until n) {
                val f = (canF shr i) and 1 != 0; val e = (canE shr i) and 1 != 0
                val exp = if (f && e) UNKNOWN else if (f) FILLED else EMPTY
                assertEquals("cell $i of ${c.toList()} ${cl.toList()}", exp, out[i])
            }
            if (solver.simple(c, n, cl, o1)) for (i in 0 until n) {
                assertTrue("L1 unsound", c[i] != UNKNOWN || o1[i] == UNKNOWN || o1[i] == out[i])
            }
            assertTrue(solver.leftRight(c, n, cl, o2))
            for (i in 0 until n) {
                if (o2[i] != UNKNOWN) assertEquals("L2 unsound or claims more than L3", out[i], o2[i])
            }
        }
        assertTrue("consistent lines checked: $consistent", consistent > 150_000)
    }

    /** 03 §5.3: every line rule must be reversal-symmetric, or the grader would not be transform-invariant. */
    @Test fun rulesAreReversalSymmetric() {
        val rng = SplitMix64(11)
        val solver = LineSolver()
        val a = ByteArray(20); val b = ByteArray(20)
        repeat(50_000) {
            val n = 1 + rng.nextInt(20)
            val sol = BooleanArray(n) { rng.nextInt(2) == 0 }
            val cl = cluesOf(sol)
            val c = ByteArray(n) { i -> if (rng.nextInt(3) == 0) (if (sol[i]) FILLED else EMPTY) else UNKNOWN }
            val rc = c.reversedArray(); val rcl = cl.reversedArray()
            for (rung in arrayOf(Rung.L1, Rung.L2, Rung.L3)) {
                val ok = solver.apply(rung, c, n, cl, a)
                val okR = solver.apply(rung, rc, n, rcl, b)
                assertEquals(ok, okR)
                if (ok) assertArrayEquals("$rung on ${c.toList()} ${cl.toList()}", a.copyOf(n), b.copyOf(n).reversedArray())
            }
        }
    }

    /** 01 §1.2: the smallest gap between left-right overlap and complete solving. */
    @Test fun completeSeesWhatOverlapMisses() {
        val c = byteArrayOf(UNKNOWN, UNKNOWN, FILLED, UNKNOWN, UNKNOWN)
        assertArrayEquals(byteArrayOf(UNKNOWN, EMPTY, FILLED, EMPTY, UNKNOWN), LineSolver.settle(c, intArrayOf(1, 1)))
        assertArrayEquals(c, LineSolver.settle(c, intArrayOf(1, 1), Rung.L2))
    }

    @Test fun blankLineOverlap() {
        // 10 cells, clue 7: overlap fills cells 3..6 at every rung.
        val blank = ByteArray(10)
        for (rung in arrayOf(Rung.L1, Rung.L2, Rung.L3)) {
            val o = LineSolver.settle(blank, intArrayOf(7), rung)!!
            assertEquals(rung.name, "0001111000", o.joinToString("") { it.toString() })
        }
        assertEquals("2222222222", LineSolver.settle(blank, IntArray(0))!!.joinToString("") { it.toString() })
        assertFalse(LineSolver().complete(byteArrayOf(FILLED, FILLED, FILLED), 3, intArrayOf(1, 1), ByteArray(3)))
    }
}
