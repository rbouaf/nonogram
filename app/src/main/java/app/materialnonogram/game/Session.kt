package app.materialnonogram.game

import app.materialnonogram.engine.Analyzer
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Hint
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * Puzzles ready before Start is pressed (UI-agnostic; Sudoku's `AppModel.prefetch`, generalised).
 *
 * Easy–Expert take ≤ 0.2 s anywhere, but **Master takes 0.1–1.5 s** (ENGINE.md "Timings"), so the
 * shop keeps one puzzle per (size, tier) ready and [prefetchMasters] pre-generates every offered Master
 * on a single background lane, so Master generation never competes with an interactive [take].
 * Every generation is cancellable ([Generator.generate]'s `isCancelled` reads the coroutine).
 *
 * Thread-safe; call from anywhere. [seeds] is the only source of randomness (tests pass a fixed one).
 */
class PuzzleShop(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val seeds: () -> Long = { SecureRandom().nextLong() },
) {
    private val ready = HashMap<Pair<Size, Tier>, Deferred<Deal>>()
    private val background = dispatcher.limitedParallelism(1)

    /** Starts generating a puzzle for ([size], [tier]) unless one is ready or on its way. */
    fun prefetch(size: Size, tier: Tier) = prefetchOn(size, tier, dispatcher)

    /** Every offered Master, one after another on the background lane. */
    fun prefetchMasters(sizes: Collection<Size> = Size.entries) {
        for (s in sizes) if (Generator.isOffered(s, Tier.MASTER)) prefetchOn(s, Tier.MASTER, background)
    }

    private fun prefetchOn(size: Size, tier: Tier, on: CoroutineDispatcher) {
        if (!Generator.isOffered(size, tier)) return
        synchronized(ready) {
            val have = ready[size to tier]
            if (have != null && !have.isCancelled) return
            ready[size to tier] = scope.async(on) { generate(size, tier) }
        }
    }

    /** True if a puzzle for ([size], [tier]) is already generated. */
    fun isReady(size: Size, tier: Tier): Boolean = synchronized(ready) { ready[size to tier]?.isCompleted == true }

    /**
     * The ready puzzle for ([size], [tier]), or the one on its way, or a fresh one; then the next one
     * starts in the background. Fails if the pair is not offered (5×5 and 10×10 Master).
     */
    suspend fun take(size: Size, tier: Tier): Deal {
        require(Generator.isOffered(size, tier)) { "$tier is not offered at ${size.label}" }
        val pending = synchronized(ready) { ready.remove(size to tier) }
        val deal = pending?.let { runCatching { it.await() }.getOrNull() }
            ?: withContext(dispatcher) { generate(size, tier) }
        prefetchOn(size, tier, if (tier == Tier.MASTER) background else dispatcher)
        return deal
    }

    /** Cancels everything in flight and forgets every ready puzzle. */
    fun clear() = synchronized(ready) { ready.values.forEach { it.cancel() }; ready.clear() }

    private suspend fun generate(size: Size, tier: Tier): Deal {
        val ctx = coroutineContext
        val g = Generator.generate(size, tier, seeds()) { !ctx.isActive }
        ctx.ensureActive()
        return Deal.of(g)
    }
}

/**
 * The next hint for [game], computed off the main thread (a look-ahead hint on a 20×20 probes every
 * unknown cell). Call [Game.showHint] with the result on the main thread. Null when nothing is left.
 */
suspend fun findHint(game: Game, dispatcher: CoroutineDispatcher = Dispatchers.Default): Hint? {
    val board = game.realBoard()
    return withContext(dispatcher) { Analyzer(game.deal.puzzle).hint(board) }
}

/**
 * The full report, rated by the engine, off the main thread. Cancelling the coroutine stops the rating
 * (the report comes back with [Report.complete] false). The game should not be mutated meanwhile: a
 * finished game never is.
 */
suspend fun buildReport(game: Game, dispatcher: CoroutineDispatcher = Dispatchers.Default): Report =
    withContext(dispatcher) {
        val ctx = coroutineContext
        Report(game, EngineRater(game.deal.puzzle)) { !ctx.isActive }
    }
