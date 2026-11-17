package app.materialnonogram.game

import app.materialnonogram.engine.Effort
import app.materialnonogram.engine.Grader
import app.materialnonogram.engine.Tier
import java.time.LocalDate

/**
 * What Stats needs from one game's [Report] (BUILD-PLAN §5.3). Small on purpose: a report keeps a board
 * per stroke, a summary keeps a few numbers, so hundreds can be cached. [rated] summaries carry the
 * look-ahead figures; unrated ones leave them empty.
 */
class GameSummary(
    val id: String,
    val decided: Int,
    val strokes: Int,
    val longestStroke: Int,
    val rated: Boolean,
    val lookAheadFound: Int,
    val lookAheadHints: Int,
    /** Time to find each unaided look-ahead step, in the order they were found. */
    val lookAheadGaps: List<Long>,
) {
    companion object {
        fun of(r: Report) = GameSummary(
            r.game.id, r.decided, r.strokeCount, r.longestStroke, r.rated,
            if (r.rated) r.lookAheadUnaided.size else 0, r.lookAheadHints, if (r.rated) r.lookAheadGaps else emptyList(),
        )

        /** Look-ahead only exists on Expert and Master, so only those are worth rating for Stats. */
        fun wantsRating(g: Game) = g.deal.tier >= Tier.EXPERT

        /** Cache key: a new effort model or grader recomputes every past game (GAME.md "Report"). */
        fun key(id: String, rated: Boolean) = "$id|${Effort.VERSION}|g${Grader.VERSION}|${if (rated) "r" else "u"}"
    }
}

/** One cell of the personal-bests matrix: a size × tier and every win in it under the current filters. */
class BestCell(val w: Int, val h: Int, val tier: Tier, val wins: List<Game>) {
    val best: Game = wins.minBy { it.elapsed }
    val count: Int get() = wins.size
    /** The last ten wins' times, oldest first (the sparkline). */
    val lastTen: List<Long> = wins.sortedBy { it.startedAt }.takeLast(10).map { it.elapsed }
}

/** Personal bests by (w, h, tier). Imports of custom sizes get their own cells, like their own records. */
fun bestsMatrix(won: List<Game>): Map<Triple<Int, Int, Tier>, BestCell> =
    won.filter { it.status == Status.WON }.groupBy { Triple(it.w, it.h, it.deal.tier) }
        .mapValues { (k, v) -> BestCell(k.first, k.second, k.third, v) }

/** A history dot: when, the y value (ms, or seconds per cell), and what it was. */
class HistoryPoint(val game: Game, val y: Double)

/**
 * History (§5.3): one dot per solve, oldest first. With one size the y is the time in ms; with sizes mixed,
 * raw time would compare 25 cells with 400, so the y is seconds per cell.
 */
fun historyPoints(won: List<Game>, perCell: Boolean): List<HistoryPoint> =
    won.filter { it.status == Status.WON }.sortedBy { it.startedAt }
        .map { HistoryPoint(it, if (perCell) it.elapsed / 1000.0 / it.cells else it.elapsed.toDouble()) }

/** The trailing geometric mean of [n] (log-time charts read a mean of logs, not of times). */
fun trailingGeoMean(ys: List<Double>, n: Int = 10): List<Double> = ys.indices.map { i ->
    val w = ys.subList(maxOf(0, i - n + 1), i + 1)
    kotlin.math.exp(w.sumOf { kotlin.math.ln(it.coerceAtLeast(1e-9)) } / w.size)
}

/** The running best: each value is the lowest seen so far. */
fun runningBest(ys: List<Double>): List<Double> {
    var b = Double.MAX_VALUE
    return ys.map { b = minOf(b, it); b }
}

/** Look-ahead (§5.3): found yourself versus shown by a hint, and the median time to find one, recent against before. */
class LookAheadSummary(
    val games: Int,
    val found: Int,
    val byHint: Int,
    /** Median of the last [window] times to find a step, and of the [window] before those. */
    val medianRecent: Long?,
    val medianBefore: Long?,
    val window: Int,
    /** Games still being rated in the background. */
    val pending: Int,
) {
    val share: Float get() = if (found + byHint == 0) 0f else found.toFloat() / (found + byHint)
}

fun lookAhead(games: List<Game>, summaries: Map<String, GameSummary>, window: Int = 20): LookAheadSummary? {
    val la = games.filter { GameSummary.wantsRating(it) }.sortedBy { it.startedAt }
    if (la.isEmpty()) return null
    val sums = la.mapNotNull { g -> summaries[g.id]?.takeIf { it.rated } }
    val gaps = sums.flatMap { it.lookAheadGaps }
    val recent = gaps.takeLast(window)
    val before = gaps.dropLast(window).takeLast(window)
    return LookAheadSummary(
        la.size, sums.sumOf { it.lookAheadFound }, sums.sumOf { it.lookAheadHints },
        recent.median(), before.takeIf { it.size >= 5 }?.median(), window, la.size - sums.size,
    )
}

/** Totals (§5.3). [summaries] supplies cells decided and strokes; games without one yet count as 0 there. */
class Totals(
    val games: Int,
    val won: Int,
    val lost: Int,
    val timeMs: Long,
    val decided: Int,
    val strokes: Int,
    val longestStroke: Int,
    val strikes: Int,
    val hints: Int,
) {
    val wonShare: Int get() = if (games == 0) 0 else 100 * won / games
    val strikesPerGame: Double get() = if (games == 0) 0.0 else strikes.toDouble() / games
}

fun totals(games: List<Game>, summaries: Map<String, GameSummary>): Totals {
    val sums = games.mapNotNull { summaries[it.id] }
    return Totals(
        games.size, games.count { it.status == Status.WON }, games.count { it.status == Status.LOST },
        games.sumOf { it.elapsed }, sums.sumOf { it.decided }, sums.sumOf { it.strokes }, sums.maxOfOrNull { it.longestStroke } ?: 0,
        games.sumOf { it.mistakes }, games.sumOf { g -> g.events.count { it.kind == Ev.HINT && it.value == 1 } },
    )
}

/** Current streak (ending today, or yesterday if today has nothing yet) and the longest run of consecutive days. */
fun streaks(days: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
    var streak = 0
    var d = if (today in days) today else today.minusDays(1)
    while (d in days) { streak++; d = d.minusDays(1) }
    var longest = 0; var run = 0; var prev: LocalDate? = null
    for (x in days.sorted()) { run = if (prev != null && prev.plusDays(1) == x) run + 1 else 1; longest = maxOf(longest, run); prev = x }
    return streak to longest
}
