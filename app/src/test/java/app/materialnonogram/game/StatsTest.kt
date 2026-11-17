package app.materialnonogram.game

import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private fun won(size: Size, tier: Tier, seed: Long, dt: Long, start: Long, rules: Rules = Rules.of(true)): Game {
        val g = Game("g$seed-$start", Deal.of(app.materialnonogram.engine.Generator.generate(size, tier, seed)), rules, startedAt = start)
        solveLogically(g, dt)
        assertEquals(Status.WON, g.status)
        return g
    }

    @Test fun bestsGroupBySizeAndTierAndKeepTheLastTen() {
        val a = (0 until 12).map { won(Size.S5, Tier.EASY, it.toLong(), 1000L + it * 10, start = it * 1000L) }
        val b = won(Size.S10, Tier.MEDIUM, 3, 500, start = 50_000)
        val m = bestsMatrix(a + b)
        val cell = m[Triple(5, 5, Tier.EASY)]!!
        assertEquals(12, cell.count)
        assertEquals(a.minBy { it.elapsed }.id, cell.best.id)
        assertEquals(10, cell.lastTen.size)
        assertEquals(a.last().elapsed, cell.lastTen.last())
        assertEquals(1, m[Triple(10, 10, Tier.MEDIUM)]!!.count)
        assertNull(m[Triple(15, 15, Tier.EASY)])
    }

    @Test fun historyIsSecondsPerCellWhenSizesMix() {
        val a = won(Size.S5, Tier.EASY, 1, 1000, start = 0)
        val b = won(Size.S10, Tier.EASY, 1, 1000, start = 1)
        val p = historyPoints(listOf(b, a), perCell = true)
        assertEquals(listOf(a.id, b.id), p.map { it.game.id })
        assertEquals(a.elapsed / 1000.0 / 25, p[0].y, 1e-9)
        assertEquals(b.elapsed.toDouble(), historyPoints(listOf(b), perCell = false)[0].y, 0.0)
        assertEquals(listOf(4.0, 2.0, 2.0), runningBest(listOf(4.0, 2.0, 3.0)))
        assertEquals(2.0, trailingGeoMean(listOf(1.0, 4.0))[1], 1e-9)
    }

    @Test fun summariesFeedTotalsAndLookAhead() {
        val ex = won(Size.S15, Tier.EXPERT, 1, 700, start = 0)
        val ea = won(Size.S10, Tier.EASY, 2, 700, start = 1)
        val r = Report(ex, EngineRater(ex.deal.puzzle))
        val sums = mapOf(ex.id to GameSummary.of(r), ea.id to GameSummary.of(Report(ea)))
        val t = totals(listOf(ex, ea), sums)
        assertEquals(2, t.games); assertEquals(2, t.won); assertEquals(100, t.wonShare)
        assertEquals(ex.cells + ea.cells, t.decided)
        assertEquals(r.strokeCount + Report(ea).strokeCount, t.strokes)
        val la = lookAhead(listOf(ex, ea), sums)!!
        assertEquals(1, la.games); assertEquals(0, la.pending)
        assertTrue(la.found >= 1)
        assertEquals(r.lookAheadUnaided.size, la.found)
        assertNotNull(la.medianRecent)
        // Unrated summaries count as pending, never as zero found.
        val pending = lookAhead(listOf(ex), mapOf(ex.id to GameSummary.of(Report(ex))))!!
        assertEquals(1, pending.pending); assertEquals(0, pending.found)
        assertNull(lookAhead(listOf(ea), sums))
        assertTrue(GameSummary.key("x", true) != GameSummary.key("x", false))
    }

    @Test fun streaksCountBackFromTodayOrYesterday() {
        val today = LocalDate.of(2026, 11, 17)
        val days = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(5), today.minusDays(6), today.minusDays(7), today.minusDays(8))
        assertEquals(2 to 4, streaks(days, today))
        assertEquals(3 to 4, streaks(days + today, today))
        assertEquals(0 to 0, streaks(emptySet(), today))
    }

    @Test fun aSlipNamesTheLineThatRulesItOut() {
        val g = generatedGame(Size.S10, Tier.EASY, seed = 5, rules = Rules.of(true))
        val a = app.materialnonogram.engine.Analyzer(g.deal.puzzle)
        // Mark the first cell a line-logic hint is about with the wrong value.
        val h = a.hint(g.realBoard())!!
        assertTrue(h.rung.isLine)
        g.tick(1000)
        g.tap(h.cell, if (h.value == Mark.FILLED) Tool.CROSS else Tool.FILL)
        val s = Report(g, EngineRater(g.deal.puzzle)).strikes.single()
        assertEquals(StrikeKind.SLIP, s.kind)
        assertTrue(s.rung!! <= Rung.L3)
        val cells = app.materialnonogram.engine.Lines.cells(g.w, g.h, s.line)
        assertTrue(h.cell in cells)
    }
}
