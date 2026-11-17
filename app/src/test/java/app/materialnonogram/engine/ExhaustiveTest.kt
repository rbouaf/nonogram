package app.materialnonogram.engine

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pins the solver to outside numbers (01 §5.1). Over every n×n grid: how many have a unique clue set
 * (OEIS A242876), and how many of those line logic finishes (A391619). An incomplete line solver would
 * undercount line-solvable; an unsound one would "solve" a non-unique grid. And every unique grid that
 * line logic leaves stuck is finished by look-ahead (P1).
 */
class ExhaustiveTest {
    private class Counts(val unique: Int, val lineSolvable: Int, val p1Only: Int, val stuck: Int)

    private fun count(n: Int): Counts {
        val total = 1 shl (n * n)
        // Distinct line clues, by mask.
        val ids = IntArray(1 shl n); val seen = ArrayList<IntArray>()
        for (m in 0 until (1 shl n)) {
            val cl = cluesOf(BooleanArray(n) { (m shr it) and 1 != 0 })
            var id = seen.indexOfFirst { it.contentEquals(cl) }
            if (id < 0) { id = seen.size; seen.add(cl) }
            ids[m] = id
        }
        val base = seen.size.toLong()
        val keys = LongArray(total)
        for (g in 0 until total) {
            var key = 0L
            for (r in 0 until n) key = key * base + ids[(g shr (r * n)) and ((1 shl n) - 1)]
            for (c in 0 until n) {
                var m = 0; for (r in 0 until n) if ((g shr (r * n + c)) and 1 != 0) m = m or (1 shl r)
                key = key * base + ids[m]
            }
            keys[g] = key
        }
        val sorted = keys.copyOf(); sorted.sort()
        var unique = 0; var line = 0; var p1 = 0; var stuck = 0
        for (g in 0 until total) {
            val pos = sorted.binarySearch(keys[g])
            val dup = (pos > 0 && sorted[pos - 1] == keys[g]) || (pos + 1 < total && sorted[pos + 1] == keys[g])
            if (dup) continue
            unique++
            val p = Puzzle(n, n, BooleanArray(n * n) { (g shr it) and 1 != 0 })
            when {
                Grader.isLineSolvable(p.clues) -> line++
                Grader.isP1Solvable(p.clues) -> p1++
                else -> stuck++
            }
        }
        return Counts(unique, line, p1, stuck)
    }

    @Test fun threeByThree() {
        val c = count(3)
        assertEquals(384, c.unique); assertEquals(384, c.lineSolvable); assertEquals(0, c.stuck)
    }

    @Test fun fourByFour() {
        val c = count(4)
        assertEquals(52_362, c.unique)       // OEIS A242876(4)
        assertEquals(51_234, c.lineSolvable) // OEIS A391619(4)
        assertEquals(1_128, c.p1Only)
        assertEquals(0, c.stuck)
    }

    /** Slow (minutes): `-Dslow=1` on tools/engine-jvm. */
    @Test fun fiveByFive() {
        assumeTrue(System.getProperty("slow").orEmpty().isNotEmpty())
        val c = count(5)
        assertEquals(25_309_575, c.unique)
        assertEquals(24_976_511, c.lineSolvable)
        assertEquals(333_064, c.p1Only)
        assertEquals(0, c.stuck)
    }
}
