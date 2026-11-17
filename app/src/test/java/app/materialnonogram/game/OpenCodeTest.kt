package app.materialnonogram.game

import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.ShareCode
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.SgtId
import app.materialnonogram.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeTest {
    private fun ok(text: String) = OpenCode.open(text) as? OpenCode.Outcome.Ok ?: error("refused: ${(OpenCode.open(text) as OpenCode.Outcome.Refused).title}")
    private fun refused(text: String) = OpenCode.open(text) as? OpenCode.Outcome.Refused ?: error("opened $text")

    @Test fun aShareCodeOpensAsTheSamePuzzleAndTier() {
        val gen = Generator.generate(Size.S10, Tier.MEDIUM, 4)
        val r = ok(ShareCode.encode(gen.puzzle))
        assertEquals(gen.puzzle, r.deal.puzzle)
        assertEquals(Origin.IMPORTED, r.deal.origin)
        assertFalse(r.fromSgt); assertFalse(r.rotated)
        assertTrue(r.deal.tier <= Tier.HARD)
    }

    @Test fun anSgtIdOpensAndAWideOneIsTurnedTall() {
        val gen = Generator.generate(Size.S10x15, Tier.EASY, 2)
        val tall = ok(SgtId.encode(gen.puzzle))
        assertTrue(tall.fromSgt)
        assertEquals(gen.puzzle, tall.deal.puzzle)
        val wide = ok(SgtId.encode(gen.puzzle.transform(app.materialnonogram.engine.Transform.ROTATE_CW)))
        assertTrue(wide.rotated); assertEquals(10, wide.deal.w); assertEquals(15, wide.deal.h)
    }

    @Test fun aWholeShareMessageOrPatternLineOpens() {
        val gen = Generator.generate(Size.S15, Tier.HARD, 7)
        val deal = Deal.of(gen)
        assertEquals(gen.puzzle, ok(OpenCode.shareText(deal, "Hard")).deal.puzzle)
        assertEquals(gen.puzzle, ok("Game ID: " + SgtId.encode(gen.puzzle) + "  ").deal.puzzle)
        assertEquals(deal.shareCode, OpenCode.extract("look: " + deal.shareCode + " (nice)"))
    }

    @Test fun aTypoIsCaughtByTheCheck() {
        val code = ShareCode.encode(Generator.generate(Size.S5, Tier.EASY, 1).puzzle, grouped = false)
        val typo = code.substring(0, 4) + (if (code[4] == 'A') 'B' else 'A') + code.substring(5)
        assertEquals("One character is off", refused(typo).title)
    }

    @Test fun refusalsSayWhatIsWrong() {
        assertEquals("Not a puzzle code", refused("hello there").title)
        val code = ShareCode.encode(Generator.generate(Size.S10, Tier.EASY, 1).puzzle)
        assertEquals("Part of the code is missing", refused(code.dropLast(3)).title)
        assertEquals("The code is too long", refused(code + "7").title)
        assertEquals("Nothing to open", refused("   ").title)
        assertEquals("That is a random seed", refused("10x10#123456").title)
        // A 2×2 diagonal: two solutions.
        assertEquals("Needs guessing", refused("2x2:1/1/1/1").title)
    }

    @Test fun boardsBeyondTwentyAreRefusedBeforeGrading() {
        val big = Puzzle(25, 25, BooleanArray(625) { (it / 25 + it % 25) % 3 != 0 })
        val r = refused(ShareCode.encode(big))
        assertEquals("Too big for now", r.title)
        assertTrue(r.detail.contains("25×25"))
        assertEquals(25 to 25, OpenCode.sizeOf(SgtId.encode(big)))
    }
}
