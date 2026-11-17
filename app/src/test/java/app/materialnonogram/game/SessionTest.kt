package app.materialnonogram.game

import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SessionTest {
    @Test fun takeServesThePrefetchedPuzzleAndStartsTheNext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seed = AtomicLong(100)
        val shop = PuzzleShop(scope, seeds = { seed.getAndIncrement() })
        shop.prefetch(Size.S10, Tier.HARD)
        val d = shop.take(Size.S10, Tier.HARD)
        assertEquals(100L, d.seed)                     // the prefetched one
        assertEquals(Size.S10, d.size); assertEquals(Tier.HARD, d.tier)
        assertEquals(Generator.generate(Size.S10, Tier.HARD, 100).puzzle, d.puzzle)
        assertEquals(101L, shop.take(Size.S10, Tier.HARD).seed)  // the one started by the first take
        scope.cancel()
    }

    @Test fun mastersArePreGeneratedInTheBackground() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shop = PuzzleShop(scope, seeds = { 7L })
        shop.prefetchMasters(listOf(Size.S5, Size.S15))
        assertFalse(shop.isReady(Size.S5, Tier.MASTER))  // not offered
        withTimeout(30_000) { while (!shop.isReady(Size.S15, Tier.MASTER)) kotlinx.coroutines.delay(20) }
        val d = shop.take(Size.S15, Tier.MASTER)
        assertEquals(Tier.MASTER, d.tier)
        assertTrue(Generator.isOffered(Size.S15, Tier.MASTER))
        shop.clear()
        scope.cancel()
    }

    @Test fun hintsAndReportsRunOffTheMainThread() = runBlocking {
        val g = generatedGame(Size.S10, Tier.EASY)
        val h = findHint(g)!!
        g.showHint(h)
        assertEquals(1, g.hint!!.stage)
        solveLogically(g)
        val r = buildReport(g)
        assertTrue(r.complete)
        assertTrue(r.effort > 0)
    }
}
