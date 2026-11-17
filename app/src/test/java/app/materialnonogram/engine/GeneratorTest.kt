package app.materialnonogram.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

class GeneratorTest {
    /** `NonoDet vectors`: blob(10×10, 600‰, Rng(7)), identical on the JVM and the Pixel 10. */
    @Test fun blobVector() {
        val b = Puzzle(10, 10, Generator.blob(10, 10, 600, SplitMix64(7)))
        assertEquals(
            ".#####..../.#####...#/.#########/##....####/##....####/##....####/....######/...#..####/...##..###/##.#...###",
            b.toString(),
        )
    }

    @Test fun blobFillIsExact() {
        for (s in Size.entries) for (fill in intArrayOf(0, 1, 250, 500, 999, 1000)) {
            val b = Generator.blob(s.w, s.h, fill, SplitMix64(fill.toLong()))
            assertEquals("${s.name} $fill", (s.cells * fill + 500) / 1000, b.count { it })
        }
    }

    /** D10 as amended 2026-11-17: an empty line is a flat line, and so is a full one, but only the empty one is rejected. */
    @Test fun emptyAndFullLines() {
        val full = Puzzle(3, 3, BooleanArray(9) { it < 3 || it % 3 == 0 })          // ###/#../#..
        assertTrue(full.hasFullLine()); assertFalse(full.hasEmptyLine()); assertTrue(full.hasFlatLine())
        val empty = Puzzle(3, 3, BooleanArray(9) { it == 0 || it == 1 || it == 3 || it == 7 }) // ##./#../.#.
        assertTrue(empty.hasEmptyLine()); assertFalse(empty.hasFullLine())
        val neither = Puzzle(3, 3, BooleanArray(9) { it == 0 || it == 4 || it == 8 || it == 1 })
        assertFalse(neither.hasEmptyLine()); assertFalse(neither.hasFullLine()); assertFalse(neither.hasFlatLine())
    }

    /** Version 2 allows full lines: the high-fill tiers produce them (most 5×5 and 10×10 Easies have one). */
    @Test fun fullLinesAreGenerated() {
        val easy = (0L until 20L).map { Generator.generate(Size.S10, Tier.EASY, it).puzzle }
        assertTrue(easy.count { it.hasFullLine() } >= 5)
        assertTrue(easy.none { it.hasEmptyLine() })
    }

    @Test fun offeredCells() {
        assertFalse(Generator.isOffered(Size.S5, Tier.MASTER))
        assertFalse(Generator.isOffered(Size.S10, Tier.MASTER))
        for (s in Size.entries) for (t in Tier.entries) {
            if (t == Tier.MASTER && (s == Size.S5 || s == Size.S10)) continue
            assertTrue("${s.name} $t", Generator.isOffered(s, t))
        }
        assertFailsWith<IllegalArgumentException> { Generator.generate(Size.S10, Tier.MASTER, 0) }
    }

    /**
     * Every offered size × tier generates within its attempt budget, without falling back, and the
     * result obeys every rule: no empty line (D10), the tier's rule, a finished grade, the right size.
     */
    @Test fun everyOfferedCellGeneratesWithinBudget() {
        for (gen in Fixtures.generated) {
            val what = "${gen.size.name} ${gen.tier} seed ${gen.seed}"
            assertFalse(what, gen.fallback)
            assertTrue(what, gen.attempt < Generator.maxAttempts(gen.tier))
            assertEquals(what, gen.size.w, gen.puzzle.w); assertEquals(what, gen.size.h, gen.puzzle.h)
            assertFalse(what, gen.puzzle.hasEmptyLine())
            assertTrue(what, gen.grade.solved)
            assertTrue(what, Generator.accepts(gen.tier, gen.size, gen.grade))
            assertTrue(what, gen.fillPermille in Generator.band(gen.tier))
            assertEquals(what, gen.puzzle.filledCount(), (gen.size.cells * gen.fillPermille + 500) / 1000)
            when (gen.tier) {
                Tier.EASY, Tier.MEDIUM, Tier.HARD -> assertTrue(what, gen.grade.isLineSolvable)
                Tier.EXPERT -> assertTrue(what, gen.grade.p1Steps in 1..Generator.EXPERT_MAX_P1)
                Tier.MASTER -> assertTrue(what, gen.grade.p1Steps > Generator.EXPERT_MAX_P1)
            }
            if (gen.tier == Tier.EASY) assertTrue(what, gen.grade.firstPassPermille >= Generator.EASY_MIN_FIRST_PASS_PERMILLE)
            if (gen.tier == Tier.HARD) assertTrue(what, gen.grade.sweeps >= Generator.hardMinSweeps(gen.size))
        }
    }

    /** More seeds for the cheap tiers: still no fallback anywhere. */
    @Test fun lineTiersNeverFallBack() {
        for (s in Size.entries) for (t in listOf(Tier.EASY, Tier.MEDIUM, Tier.HARD, Tier.EXPERT)) for (seed in 2L until 12L) {
            val g = Generator.generate(s, t, seed)
            assertFalse("${s.name} $t $seed", g.fallback)
        }
    }

    @Test fun sameArgumentsSamePuzzle() {
        for (gen in Fixtures.generated.filter { it.tier != Tier.MASTER }) {
            val again = Generator.generate(gen.size, gen.tier, gen.seed)
            assertEquals(gen.puzzle, again.puzzle); assertEquals(gen.attempt, again.attempt); assertEquals(gen.grade.key(), again.grade.key())
        }
    }

    @Test fun cancellation() {
        var polls = 0
        assertFailsWith<CancellationException> { Generator.generate(Size.S20, Tier.MASTER, 0) { ++polls; true } }
        assertEquals(1, polls)
    }

    /**
     * Golden seed → puzzle for `Generator.VERSION` 2: `golden/gen-v2.tsv` holds seeds 0–4 of every
     * offered cell (share code, attempt, fill, grade key). A mismatch is a bug or a version bump,
     * never a judgement call (03 §4). Regenerate with `Bench golden 5` on tools/engine-jvm.
     */
    @Test fun goldenSeedToPuzzle() {
        assertEquals(2, Generator.VERSION)
        val rows = Fixtures.resource("gen-v${Generator.VERSION}.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(28 * 5, rows.size)
        for (row in rows) {
            val f = row.split('\t')
            val g = Generator.generate(Size.valueOf(f[0]), Tier.valueOf(f[1]), f[2].toLong())
            assertEquals(row, f[3], ShareCode.encode(g.puzzle, grouped = false))
            assertEquals(row, f[4].toInt(), g.attempt)
            assertEquals(row, f[5].toInt(), g.fillPermille)
            assertEquals(row, f[6], g.grade.key())
        }
    }
}
