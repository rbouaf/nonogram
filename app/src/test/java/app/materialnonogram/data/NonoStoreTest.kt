package app.materialnonogram.data

import app.materialnonogram.engine.ShareCode
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.EngineRater
import app.materialnonogram.game.Game
import app.materialnonogram.game.Report
import app.materialnonogram.game.Rules
import app.materialnonogram.game.Status
import app.materialnonogram.game.Tool
import app.materialnonogram.game.c5
import app.materialnonogram.game.game5
import app.materialnonogram.game.generatedGame
import app.materialnonogram.game.solveLogically
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NonoStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun assertSameGame(a: Game, b: Game) {
        assertEquals(a.id, b.id)
        assertEquals(a.deal, b.deal)
        assertEquals(a.rules, b.rules)
        assertArrayEquals(a.board, b.board)
        assertArrayEquals(a.auto, b.auto)
        assertEquals(a.events, b.events)
        assertEquals(a.status, b.status)
        assertEquals(a.elapsed, b.elapsed)
        assertEquals(a.lives, b.lives)
        assertEquals(a.band, b.band)
        assertEquals(a.autoCross, b.autoCross)
        assertEquals(a.contradictionFlag, b.contradictionFlag)
        assertEquals(a.startedAt, b.startedAt)
        assertEquals(a.tool, b.tool)
        assertEquals(a.inWhatIf, b.inWhatIf)
        assertArrayEquals(a.realBoard(), b.realBoard())
    }

    @Test fun aGameRoundTripsWithItsBitmapAndLog() {
        val g = generatedGame(Size.S15, Tier.HARD, seed = 3, rules = Rules.of(false, autoCross = true, contradictionFlag = true, checkButton = true))
        g.tick(1234)
        g.beginStroke(0, Tool.CROSS); g.extendStroke(4); g.endStroke()
        g.check()
        g.selectTool(Tool.CROSS)
        g.setAids(autoCross = false, contradictionFlag = true)
        val text = app.materialnonogram.data.NonoJson.encode(g)
        val back = NonoJson.decode(text)
        assertSameGame(g, back)
        assertTrue(text.contains("\"bits\""))
        assertEquals(ShareCode.encode(g.deal.puzzle), back.deal.shareCode)
        // The restored game plays on: a stroke gets a fresh id.
        back.tap(back.cells - 1, Tool.FILL)
        assertTrue(back.events.last { it.kind == 'M' }.x > g.events.last { it.kind == 'M' }.x)
    }

    @Test fun aFinishedGameRoundTripsToTheSameReport() {
        val g = generatedGame(Size.S10, Tier.EXPERT, rules = Rules.of(true, lives = 1))
        solveLogically(g)
        assertEquals(Status.WON, g.status)
        val back = NonoJson.decode(NonoJson.encode(g))
        assertSameGame(g, back)
        val a = Report(g, EngineRater(g.deal.puzzle)); val b = Report(back, EngineRater(back.deal.puzzle))
        assertEquals(a.effort10, b.effort10)
        assertEquals(a.progress, b.progress)
        assertEquals(a.marks, b.marks)
    }

    @Test fun anOpenWhatIfLayerRoundTrips() {
        val g = game5(flag = true)
        g.tap(c5(2, 0), Tool.FILL)
        g.beginWhatIf()
        g.tap(c5(0, 3), Tool.FILL)                     // wrong, provisional
        val back = NonoJson.decode(NonoJson.encode(g))
        assertSameGame(g, back)
        assertTrue(back.provisional!![c5(0, 3)])
        back.discardWhatIf()
        assertEquals(1, back.board.count { it != 0.toByte() })
    }

    @Test fun theStoreKeepsGamesTheCurrentPointerAndSkipsDamage() {
        val store = NonoStore(tmp.newFolder("nonograms"))
        val a = generatedGame(Size.S5, Tier.EASY, seed = 1, id = "a")
        val b = Game("b", generatedGame(Size.S10, Tier.EASY, seed = 2).deal, Rules.of(true), startedAt = 5L)
        store.save(a); store.save(b)
        store.setCurrent("b")
        assertEquals("b", store.loadCurrent()!!.id)
        java.io.File(tmp.root, "nonograms/broken.json").writeText("{not json")
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        assertTrue(store.exportAll().startsWith("[\n"))
        solveLogically(b); store.save(b)
        assertNull(store.loadCurrent())                // finished: no longer "in progress"
        store.delete("a")
        assertNull(store.load("a"))
        assertFalse(java.io.File(tmp.root, "nonograms/b.json.tmp").exists())
    }
}
