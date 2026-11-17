package app.materialnonogram.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private val blob10 = Puzzle(10, 10, Generator.blob(10, 10, 600, SplitMix64(7)))
    private val webpbn1 = Puzzle(5, 10, BooleanArray(50) { "01100011010010101110101001010000110010100101111000"[it] == '1' })

    @Test fun benchVectors() {
        assertEquals("0QR7R-QZW7W-7W7GZ-H7HKX-3XNB0", ShareCode.encode(blob10))
        assertEquals("3H15HMNTJGS9F1YKG", ShareCode.encode(webpbn1, grouped = false)) // custom size 5×10
        assertEquals("5x10:2.1/2.1.3/7/1.3/2.1/2/2.1/1.1/3/1.1/1.1/2/1.1/1.2/2", SgtId.encode(webpbn1))
    }

    /** The share code, SGT ID and canonical transform agree with the bench on 504 blobs of every size. */
    @Test fun matchesTheBench() {
        for (r in Fixtures.bench) {
            assertEquals(r.shareCode, ShareCode.encode(r.puzzle, grouped = false))
            assertEquals(r.sgtId, SgtId.encode(r.puzzle))
            val c = Canonical.of(r.puzzle)
            assertEquals(r.canonT, c.transform); assertEquals(r.canonAutos, c.automorphisms)
        }
    }

    @Test fun lengthsBySize() {
        val expected = mapOf(Size.S5 to 10, Size.S10 to 25, Size.S10x15 to 35, Size.S15 to 50, Size.S15x20 to 65, Size.S20 to 85)
        for ((s, len) in expected) {
            val p = Puzzle(s.w, s.h, Generator.blob(s.w, s.h, 500, SplitMix64(s.ordinal.toLong())))
            assertEquals(s.name, len, ShareCode.encode(p, grouped = false).length)
        }
    }

    @Test fun roundTrips() {
        var n = 0
        for (s in Size.entries) for (seed in 0 until 50) {
            val r = SplitMix64(seed * 31L + s.w)
            val p = Puzzle(s.w, s.h, Generator.blob(s.w, s.h, 300 + r.nextInt(400), r))
            assertEquals(p, ShareCode.decode(ShareCode.encode(p)))
            assertEquals(p, ShareCode.decode(ShareCode.encode(p, grouped = false)))
            n++
        }
        // custom sizes, including the 64×64 limit and a 1×1
        for ((w, h) in listOf(1 to 1, 5 to 10, 15 to 10, 7 to 3, 64 to 64, 30 to 30)) {
            val r = SplitMix64(w * 100L + h)
            val p = Puzzle(w, h, BooleanArray(w * h) { r.nextInt(2) == 0 })
            assertEquals("${w}x$h", p, ShareCode.decode(ShareCode.encode(p)))
            n++
        }
        assertEquals(306, n)
    }

    @Test fun aliasesCaseAndSeparators() {
        val code = ShareCode.encode(blob10, grouped = false)
        assertEquals(blob10, ShareCode.decode(code.lowercase()))
        assertEquals(blob10, ShareCode.decode(code.replace('0', 'O')))
        assertEquals(blob10, ShareCode.decode(code.replace('1', 'l')))
        assertEquals(blob10, ShareCode.decode(" " + ShareCode.group(code).replace("-", " - ") + "\n"))
    }

    /**
     * 03 §1.2: without the check, 85% of one-character typos decode to another valid puzzle. With it,
     * every single-character substitution of these codes is rejected (all 31 alternatives at every position).
     */
    @Test fun everySingleCharacterTypoIsRejected() {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val codes = listOf(blob10) + Size.entries.map { s -> Puzzle(s.w, s.h, Generator.blob(s.w, s.h, 450, SplitMix64(99L + s.ordinal))) }
        var typos = 0
        for (p in codes) {
            val code = ShareCode.encode(p, grouped = false)
            for (i in code.indices) for (ch in alphabet) {
                if (ch == code[i]) continue
                val bad = code.substring(0, i) + ch + code.substring(i + 1)
                assertNull("typo $bad of $code decoded", ShareCode.decode(bad)); typos++
            }
        }
        assertEquals(295 * 31, typos)
    }

    @Test fun malformedCodesAreRejected() {
        val code = ShareCode.encode(blob10, grouped = false)
        assertNull(ShareCode.decode(code.dropLast(1)))
        assertNull(ShareCode.decode(code + "0"))
        assertNull(ShareCode.decode(code.replaceFirst(code[3], 'U'))) // U is not Crockford
        assertNull(ShareCode.decode(""))
        assertNull(ShareCode.decode("Z" + code.drop(1))) // codec version 3 bits = 7
    }

    @Test fun sgtParsing() {
        val parsed = SgtId.parse("5x10:2.1/2.1.3/7/1.3/2.1/2/2.1/1.1/3/1.1/1.1/2/1.1/1.2/2").clues!!
        assertEquals(SgtId.encode(webpbn1), SgtId.encode(parsed))
        // bare N, empty lines
        val sq = SgtId.parse("3:1//1/1//1").clues!!
        assertEquals(3, sq.w); assertEquals(3, sq.h); assertEquals(0, sq.cols[1].size)
        assertEquals(SgtId.Problem.BAD_NUMBER, SgtId.parse("3:1/0/1/1//1").problem)
        assertEquals(SgtId.Problem.GIVENS, SgtId.parse("3:1//1/1//1,a").problem)
        assertEquals(SgtId.Problem.RANDOM_SEED, SgtId.parse("10x10#123456789012345").problem)
        assertEquals(SgtId.Problem.BAD_LINE_COUNT, SgtId.parse("3:1//1/1/").problem)
        assertEquals(SgtId.Problem.CLUE_TOO_LONG, SgtId.parse("3:2.1//1/1//1").problem)
        assertEquals(SgtId.Problem.NOT_SGT, SgtId.parse("hello").problem)
        assertEquals(SgtId.Problem.BAD_SIZE, SgtId.parse("65x2:" + "/".repeat(66)).problem)
    }

    @Test fun sgtRoundTripsThroughTheGrader() {
        for (gen in Fixtures.generated) {
            val r = Importer.open(SgtId.encode(gen.puzzle))
            assertTrue(r.ok); assertEquals(gen.puzzle, r.puzzle); assertEquals(Importer.Source.SGT_ID, r.source)
            assertFalse(r.rotated)
            assertEquals(gen.grade.key(), r.grade!!.key())
        }
    }

    @Test fun importerAcceptsShareCodes() {
        val gen = Fixtures.generated.first { it.size == Size.S15 && it.tier == Tier.EXPERT }
        val r = Importer.open(ShareCode.encode(gen.puzzle))
        assertTrue(r.ok); assertEquals(gen.puzzle, r.puzzle); assertEquals(Importer.Source.SHARE_CODE, r.source)
    }

    @Test fun importerTurnsWideTall() {
        val tall = Fixtures.generated.first { it.size == Size.S10x15 }.puzzle
        val wide = tall.transform(4) // 15 wide × 10 tall
        assertEquals(15, wide.w)
        for (text in listOf(SgtId.encode(wide), ShareCode.encode(wide))) {
            val r = Importer.open(text)
            assertTrue(r.ok); assertTrue(r.rotated)
            assertEquals(10, r.puzzle!!.w); assertEquals(15, r.puzzle!!.h)
            assertEquals(Canonical.classKey(tall), Canonical.classKey(r.puzzle!!))
        }
    }

    @Test fun importerRefusals() {
        val code = ShareCode.encode(blob10, grouped = false)
        assertEquals(Importer.Refusal.BAD_CHECK, Importer.open(code.substring(0, 5) + (if (code[5] == 'A') 'B' else 'A') + code.substring(6)).refusal)
        assertEquals(Importer.Refusal.UNREADABLE, Importer.open("not a puzzle!").refusal)
        assertEquals(Importer.Refusal.SGT_SEED, Importer.open("10x10#99").refusal)
        assertEquals(Importer.Refusal.SGT_GIVENS, Importer.open("2:1/1/1/1,x").refusal)
        assertEquals(Importer.Refusal.NOT_UNIQUE, Importer.open("2:1/1/1/1").refusal) // the 2×2 checkerboard
        assertEquals(Importer.Refusal.NOT_UNIQUE, Importer.open(ShareCode.encode(Puzzle.of("#.", ".#"))).refusal)
        assertEquals(Importer.Refusal.NO_SOLUTION, Importer.open("2:2/2/1/").refusal)
    }

    @Test fun canonicalFormIsInvariant() {
        val puzzles = Fixtures.generated.map { it.puzzle } + Fixtures.bench.take(100).map { it.puzzle }
        for (p in puzzles) {
            val key = Canonical.classKey(p); val hash = Canonical.classHash(p)
            val c = Canonical.of(p)
            assertTrue(c.puzzle.isTall)
            assertEquals(c.puzzle, p.transform(c.transform))
            for (t in 0 until 8) {
                val q = p.transform(t)
                assertEquals(key, Canonical.classKey(q)); assertEquals(hash, Canonical.classHash(q))
            }
        }
    }

    @Test fun symmetricPatternHasAutomorphisms() {
        val plus = Puzzle.of("..#..", "..#..", "#####", "..#..", "..#..")
        assertEquals(8, Canonical.of(plus).automorphisms)
        val bar = Puzzle.of("##...", ".....", ".....", ".....", "...##")
        assertEquals(2, Canonical.of(bar).automorphisms) // the identity and the 180° turn
        assertNotNull(Canonical.of(bar))
    }
}
