package app.materialnonogram.game

import app.materialnonogram.engine.Analyzer
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Mark.FILLED
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun generatedGame(size: Size, tier: Tier, seed: Long = 0, rules: Rules = Rules.of(true), id: String = "g$seed") =
    Game(id, Deal.of(Generator.generate(size, tier, seed)), rules, startedAt = 0L)

/** A player who always marks the cell the next hint would point at: every mark is forced. */
internal fun solveLogically(g: Game, dt: Long = 1000, useHintFor: (Rung) -> Boolean = { false }) {
    val a = Analyzer(g.deal.puzzle)
    while (g.status == Status.PLAYING) {
        val h = a.hint(g.realBoard()) ?: break
        g.tick(dt)
        if (useHintFor(h.rung)) { g.showHint(h); g.advanceHint() }
        g.tap(h.cell, if (h.value == FILLED) Tool.FILL else Tool.CROSS)
    }
}

class ReportTest {
    @Test fun aLogicalSolveIsAccuratePureAndRated() {
        val g = generatedGame(Size.S10, Tier.MEDIUM)
        solveLogically(g)
        assertEquals(Status.WON, g.status)
        val r = Report(g, EngineRater(g.deal.puzzle))
        assertTrue(r.complete); assertTrue(r.rated)
        assertEquals(1.0, r.accuracy, 0.0)
        assertEquals(0, r.guesses)
        assertTrue(r.flawless)
        assertTrue(r.effort > 0)
        assertEquals(r.marks.sumOf { it.rating?.effort10 ?: 0 }, r.effort10)
        val hard = r.hardest!!
        assertTrue(hard.rating!!.rung!!.isLine)
        assertTrue(hard.rating!!.line in 0 until g.lineCount)
        assertEquals(r.marks.size, r.strokeCount)    // one tap per mark
        assertEquals(1, r.longestStroke)
        assertTrue(r.rhythm in 1..100)
        // Progress only ever rises in a clean solve, and ends with every cell decided.
        assertTrue(r.progress.zipWithNext().all { (a, b) -> b.decided > a.decided && b.t >= a.t })
        assertEquals(g.cells, r.progress.last().decided)
        assertEquals(g.elapsed / 1000.0 / 100, r.secondsPerCell, 1e-9)
        assertTrue(r.lookAheadUnaided.isEmpty())
    }

    @Test fun strokesAreBarsWithAReplayFrameAndTheirLine() {
        val g = game5(autoCross = true)
        g.tick(1000)
        g.beginStroke(c5(2, 0), Tool.FILL); g.extendStroke(c5(2, 4)); g.endStroke()
        g.tick(1000)
        g.beginStroke(c5(0, 0), Tool.FILL); g.extendStroke(c5(0, 2)); g.endStroke()   // finishes row 0: auto-crosses
        val after = g.realBoard()
        g.tap(c5(3, 2), Tool.FILL)
        val r = Report(g)
        assertEquals(listOf(5, 3, 1), r.strokes.map { it.cells.size })
        assertEquals(listOf(2, 0, -1), r.strokes.map { it.line })
        assertEquals(listOf(c5(0, 3), c5(0, 4)), r.strokes[1].autoCells.toList())
        assertArrayEquals(after, r.strokes[1].board)
        assertEquals(5, r.longestStroke)
        assertEquals(3.0, r.cellsPerStroke, 1e-9)
        assertFalse(r.byHand)                          // auto-cross placed cells
    }

    @Test fun strikesAreClassifiedFromTheBoardTheyWereMadeOn() {
        val rungs = mapOf(c5(0, 3) to null, c5(4, 2) to Rung.P1)
        val fake = Rater { _, cell -> if (cell == c5(2, 0)) Rating(Rung.L1, 1, 10, 10, 2) else Rating(rungs[cell], 1, 25, 0) }
        val g = game5(flag = true)
        g.tap(c5(2, 0), Tool.CROSS)                    // forced filled by row 2 alone: a slip
        g.tap(c5(0, 3), Tool.FILL)                     // nothing forced it: a guess
        g.tap(c5(4, 2), Tool.FILL)                     // only look-ahead forced it empty: wrong
        val r = Report(g, fake)
        assertEquals(listOf(StrikeKind.SLIP, StrikeKind.GUESS, StrikeKind.WRONG), r.strikes.map { it.kind })
        assertTrue(r.strikes.all { it.refused })
        assertEquals(0.0, r.accuracy, 0.0)
        assertEquals(Status.LOST, g.status)
        // The real engine agrees about the slip.
        assertEquals(StrikeKind.SLIP, Report(g, EngineRater(P5)).strikes.first().kind)
    }

    @Test fun aCellMarkedRightAfterItsRefusalIsAGuess() {
        val g = game5(flag = true)
        g.tap(c5(2, 0), Tool.CROSS)                    // refused
        g.tap(c5(2, 0), Tool.FILL)                     // the oracle told them
        g.tap(c5(2, 1), Tool.FILL)
        val r = Report(g, EngineRater(P5))
        val m = r.marks.first { it.cell == c5(2, 0) }
        assertTrue(m.guess); assertFalse(m.credited); assertEquals(0, m.effort10)
        assertFalse(r.marks.first { it.cell == c5(2, 1) }.guess)
        assertEquals(1, r.guesses)
        assertEquals(2.0 / 3, r.accuracy, 1e-9)
    }

    @Test fun inDontFlagModeAWrongMarkLeftStandingIsAStrike() {
        val g = game5()
        g.tap(c5(2, 0), Tool.CROSS)
        g.tap(c5(2, 1), Tool.FILL)
        val r = Report(g)
        assertEquals(1, r.strikes.size)
        assertFalse(r.strikes[0].refused)
        assertNull(r.strikes[0].kind)                  // no rater
        assertFalse(r.flawless)
        assertEquals(0.5, r.accuracy, 1e-9)
    }

    @Test fun onlyTheFirstCorrectDecisionOfACellEarnsEffort() {
        val g = game5()
        g.tap(c5(2, 0), Tool.FILL)
        g.tap(c5(2, 0), Tool.FILL)                     // cleared
        g.tap(c5(2, 0), Tool.FILL)                     // decided again
        val r = Report(g, EngineRater(P5))
        assertEquals(listOf(true, false), r.marks.map { it.first })
        assertEquals(1, r.clears)
        assertTrue(r.marks[0].credited); assertFalse(r.marks[1].credited)
    }

    @Test fun hintPlacementsAreNotTheirsAndMakeTheRunAssisted() {
        val g = game5()
        g.showHint(Analyzer(P5).hint(g.realBoard())!!)
        g.applyHint()
        val r = Report(g, EngineRater(P5))
        assertEquals(Source.HINT, r.marks.single().source)
        assertEquals(0, r.effort10)
        assertEquals(1, r.hints)
        assertTrue(r.assisted)
        assertFalse(r.byHand)
        assertEquals(0, r.strokeCount)
        assertEquals(1, r.strokes.size)
    }

    @Test fun lookAheadFoundAloneEarnsSawAheadAndByHintDoesNot() {
        val alone = generatedGame(Size.S10, Tier.EXPERT)
        solveLogically(alone)
        val ra = Report(alone, EngineRater(alone.deal.puzzle))
        assertTrue(ra.lookAheadUnaided.isNotEmpty())
        assertEquals(ra.lookAheadUnaided.size, ra.lookAheadGaps.size)
        assertEquals(0, ra.lookAheadHints)
        assertTrue(accolades(ra, emptyList()).first { it.key == "sawahead" }.earned)
        assertEquals(Rung.P1, ra.hardest!!.rating!!.rung)

        val helped = generatedGame(Size.S10, Tier.EXPERT)
        solveLogically(helped, useHintFor = { it >= Rung.P1 })
        val rh = Report(helped, EngineRater(helped.deal.puzzle))
        assertTrue(rh.lookAheadHints > 0)
        assertTrue(rh.lookAheadUnaided.isEmpty())
        val saw = accolades(rh, emptyList()).first { it.key == "sawahead" }
        assertFalse(saw.earned)
        assertTrue(rh.assisted)
        assertTrue(helped.recordKey().assisted)
    }

    @Test fun recordsAreKeyedAndAccoladesComeFromTheLog() {
        val slow = generatedGame(Size.S10, Tier.EASY, id = "slow")
        solveLogically(slow, dt = 3000)
        val fast = generatedGame(Size.S10, Tier.EASY, id = "fast")
        solveLogically(fast, dt = 1000)
        assertEquals(RecordKey(10, 10, Tier.EASY, 0, 1, false), fast.recordKey())
        assertEquals(Size.S10, fast.recordKey().size)
        val r = Report(fast, EngineRater(fast.deal.puzzle))
        val acc = accolades(r, listOf(slow)).associateBy { it.key }
        assertTrue(acc.getValue("record").earned)
        assertTrue(acc.getValue("unaided").earned)
        assertTrue(acc.getValue("pure").earned)
        assertTrue(acc.getValue("flawless").earned)
        assertFalse(acc.getValue("byhand").earned)     // auto-cross was on
        assertFalse(acc.getValue("fast").earned)       // needs 10 prior games
        assertFalse("sawahead" in acc)                 // not a look-ahead tier
        assertFalse(accolades(Report(slow), listOf(fast)).any { it.key == "record" })  // a missed record is not shown
        // A different net is a different table.
        val sudden = generatedGame(Size.S10, Tier.EASY, rules = Rules.of(true, lives = 1), id = "sd")
        solveLogically(sudden, dt = 5000)
        val sa = accolades(Report(sudden), listOf(fast)).associateBy { it.key }
        assertTrue(sa.getValue("record").earned)
        assertTrue(sa.getValue("sudden").earned)
    }

    @Test fun halfwayTimeAndLine() {
        val g = game5(flag = true)
        assertEquals("Halfway" to "No mistakes yet", halfwayLine(g, emptyList(), true))
        g.tap(c5(0, 3), Tool.FILL)                     // a strike
        assertEquals("Halfway there" to null, halfwayLine(g, emptyList(), true))
        val slow = game5(flag = true)
        for (i in 0 until 25) { slow.tick(5000); slow.tap(i, if (P5.filled(i)) Tool.FILL else Tool.CROSS); if (slow.status != Status.PLAYING) break }
        assertEquals(13 * 5000L, slow.halfwayTime())
        assertEquals("Halfway" to "Record pace", halfwayLine(g, listOf(slow), true))
        assertEquals("Halfway there" to null, halfwayLine(g, listOf(slow), false))
        assertNull(g.halfwayTime())
    }

    @Test fun aCancelledReportStopsRatingAndSaysSo() {
        val g = generatedGame(Size.S10, Tier.MEDIUM)
        solveLogically(g)
        val r = Report(g, EngineRater(g.deal.puzzle), isCancelled = { true })
        assertFalse(r.complete)
        assertTrue(r.marks.all { it.rating == null })
        assertNotNull(r.progress.lastOrNull())
    }

    @Test fun lostRunsReportHowFarTheyGot() {
        val g = game5(flag = true, lives = 1)
        g.tap(c5(2, 0), Tool.FILL); g.tap(c5(2, 1), Tool.FILL)
        g.tap(c5(0, 3), Tool.FILL)
        val r = Report(g)
        assertTrue(r.lost)
        assertEquals(2, r.decided)
        assertEquals(2, r.strikes.single().decided)
        assertEquals(FILLED, r.strikes.single().board[c5(2, 1)])
        assertTrue(accolades(r, emptyList()).isEmpty())
    }

    /**
     * How long a full report takes on the worst case: a 20×20 Master solved in row-major order, so most
     * marks are rated by look-ahead with a full availability sweep. Prints; the bound only catches a
     * pathological regression.
     */
    @Test fun reportCostAt20x20() {
        val gen = Generator.generate(Size.S20, Tier.MASTER, 0)
        for ((label, logical) in listOf("logical" to true, "row-major" to false)) {
            val g = Game("perf", Deal.of(gen), Rules.of(false, autoCross = false), startedAt = 0L)
            if (logical) solveLogically(g, dt = 2000)
            else for (i in 0 until g.cells) { g.tick(2000); g.tap(i, if (gen.puzzle.filled(i)) Tool.FILL else Tool.CROSS) }
            assertEquals(Status.WON, g.status)
            val t0 = System.nanoTime()
            val r = Report(g, EngineRater(gen.puzzle))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val byRung = r.marks.groupingBy { it.rating?.rung?.name ?: "guess" }.eachCount()
            println("Report 20x20 Master $label: ${r.marks.size} marks rated in $ms ms; $byRung; effort ${r.effort}")
            assertTrue(ms < 60_000)
        }
    }
}
