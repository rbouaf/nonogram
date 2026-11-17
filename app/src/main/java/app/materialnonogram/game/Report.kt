package app.materialnonogram.game

import app.materialnonogram.engine.Analyzer
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Solver
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Mark.UNKNOWN
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * How one mark was rated on the board the player had (01 §4.2, `effort-v1`): T* ([rung], null = a
 * guess), availability out of [reference], effort ×10, and for a line rung the [line] that forces it
 * (rows `0 until h`, then columns), else -1.
 */
data class Rating(val rung: Rung?, val availability: Int, val reference: Int, val effort10: Int, val line: Int = -1)

/** Rates a mark on [before] (the board just before it, the cell itself unknown). The engine supplies the real one; tests can supply anything. */
fun interface Rater { fun rate(before: ByteArray, cell: Int): Rating }

/** The engine's rater: [Analyzer.analyzeMark] plus which line forced it. Thread-confined, like the Analyzer. */
class EngineRater(puzzle: Puzzle) : Rater {
    private val analyzer = Analyzer(puzzle)
    private val solver = Solver(puzzle.clues)
    private val w = puzzle.w
    private val h = puzzle.h

    override fun rate(before: ByteArray, cell: Int): Rating {
        val m = analyzer.analyzeMark(before, cell)
        val rung = m.rung
        val line = if (rung != null && rung.isLine) {
            val row = cell / w
            if (solver.lineDeductions(row, rung, analyzer.sanitize(before))?.any { it shr 2 == cell } == true) row else h + cell % w
        } else -1
        return Rating(rung, m.availability, m.reference, m.effort10, line)
    }
}

/** One mark that set a cell (clears are not marks). */
data class MarkRecord(
    val t: Long,
    val cell: Int,
    val value: Byte,
    val source: Source,
    val stroke: Int,
    val correct: Boolean,
    /** The first time this cell held its solution value: the only mark that can earn effort. */
    val first: Boolean,
    /** Present for the player's first-correct and wrong marks when the report was given a [Rater]. */
    val rating: Rating?,
    /**
     * No rung forced it, or (flag mode is an oracle) it came right after a refusal at the same cell.
     * A guess earns no effort.
     */
    val guess: Boolean,
) {
    val credited: Boolean get() = source == Source.PLAYER && first && correct && !guess
    val effort10: Int get() = if (credited) rating?.effort10 ?: 0 else 0
}

/**
 * Strike kinds (BUILD-PLAN §5.2), judged on the board the player had: [SLIP] a line alone shows the mark
 * is wrong (a misread clue); [GUESS] nothing forced the cell either way; [WRONG] the cell was forced the
 * other way, but only by look-ahead.
 */
enum class StrikeKind { SLIP, GUESS, WRONG }

/** A wrong mark: refused in flag mode, or left standing in don't-flag mode. [board] is the board just before it. */
class Strike(
    val t: Long,
    val cell: Int,
    val value: Byte,
    val refused: Boolean,
    /** Null when the report had no [Rater]. */
    val kind: StrikeKind?,
    val rung: Rung?,
    /** Correct cells on the board at that moment. */
    val decided: Int,
    val board: ByteArray,
)

/** One stroke (or one hint placement, or one committed what-if layer), for the strokes-as-bars chart and the replay scrub. */
class StrokeRecord(
    val id: Int,
    val source: Source,
    val t0: Long,
    val t1: Long,
    /** Cells the stroke set or cleared, in the order they were made. */
    val cells: IntArray,
    val value: Byte,
    /** Cells auto-crossed (or uncrossed) as a result. */
    val autoCells: IntArray,
    /** The board after the stroke and its auto-crosses: the replay frame. */
    val board: ByteArray,
    /** The hardest rung among its credited marks ("row 4: line logic"); null if none was rated. */
    val rung: Rung?,
    /** The line it ran along, or for one cell the line that forced it; -1 if unknown. */
    val line: Int,
    val guesses: Int,
    /** Flag mode: the stroke ended in a refused mark. */
    val struck: Boolean,
)

data class ProgressPoint(val t: Long, val decided: Int)

/**
 * Everything Results and Stats show about one game, computed only from its log (BUILD-PLAN §5.2).
 * Nothing here is stored: when a metric changes, every past game gets the new one.
 *
 * With a [rater] the report rates every first-correct and every wrong player mark on the board the
 * player had. That is the expensive part (one [Analyzer.analyzeMark] per mark: on a 20×20 it is
 * dominated by look-ahead marks), so build reports off the main thread. [isCancelled] is polled once
 * per rated mark; once it returns true the report stops rating and finishes unrated ([complete] false)
 * instead of throwing. Without a rater everything but effort, rung-based guesses, strike kinds, the
 * hardest step and look-ahead is still available.
 */
class Report(val game: Game, rater: Rater? = null, isCancelled: () -> Boolean = { false }) {
    val marks: List<MarkRecord>
    val strikes: List<Strike>
    val strokes: List<StrokeRecord>
    val progress: List<ProgressPoint>
    /** Every mark was rated (a rater was given and the build was not cancelled). */
    val complete: Boolean
    /** Distinct hints asked for (stage 1s), and those that pointed at a look-ahead step. */
    val hints: Int
    val lookAheadHints: Int
    val checks: Int
    /** Clears the player made (not marks). */
    val clears: Int
    /** Board-changing events placed by auto-cross (crosses only). */
    val autoCrosses: Int

    private val sol = game.deal.puzzle.solutionBoard()
    val cells: Int = game.cells
    private val revealedCells: Set<Int>

    init {
        val events = game.events.toList()
        val b = ByteArray(cells)
        var decided = 0
        val everCorrect = BooleanArray(cells)
        val refusedLast = BooleanArray(cells)
        val revealed = HashSet<Int>()
        val ms = ArrayList<MarkRecord>(); val ss = ArrayList<Strike>(); val sk = ArrayList<StrokeRecord>()
        val pr = ArrayList<ProgressPoint>()
        var rated = rater != null
        var nHints = 0; var nLookHints = 0; var nChecks = 0; var nClears = 0; var nAuto = 0

        // The stroke being gathered.
        var openId = -2; var openSrc = Source.PLAYER; var t0 = 0L; var t1 = 0L; var openValue: Byte = 0
        val openCells = ArrayList<Int>(); val openAuto = ArrayList<Int>(); var openRung: Rung? = null; var openLine = -1
        var openGuesses = 0; var openStruck = false
        fun close() {
            if (openId == -2) return
            if (openCells.isNotEmpty()) {
                val line = when {
                    openCells.size >= 2 && openCells.all { it / game.w == openCells[0] / game.w } -> openCells[0] / game.w
                    openCells.size >= 2 && openCells.all { it % game.w == openCells[0] % game.w } -> game.h + openCells[0] % game.w
                    openCells.size >= 2 -> -1 // a committed what-if layer: no single line
                    else -> openLine
                }
                sk += StrokeRecord(openId, openSrc, t0, t1, openCells.toIntArray(), openValue, openAuto.toIntArray(), b.copyOf(), openRung, line, openGuesses, openStruck)
            }
            openId = -2; openCells.clear(); openAuto.clear(); openRung = null; openLine = -1; openGuesses = 0; openStruck = false
        }

        fun rate(cell: Int): Rating? {
            if (!rated) return null
            if (isCancelled()) { rated = false; return null }
            val before = b.copyOf(); before[cell] = UNKNOWN
            return rater!!.rate(before, cell)
        }

        fun kindOf(r: Rating?): StrikeKind? = when {
            r == null -> null
            r.rung == null -> StrikeKind.GUESS
            r.rung.isLine -> StrikeKind.SLIP
            else -> StrikeKind.WRONG
        }

        fun set(cell: Int, v: Byte) {
            val was = b[cell] == sol[cell]
            b[cell] = v
            val now = v == sol[cell]
            if (now) everCorrect[cell] = true
            if (was != now) decided += if (now) 1 else -1
        }

        for (e in events) {
            val belongs = e.x == openId && (e.kind == Ev.MARK || e.kind == Ev.HINT_MARK || e.kind == Ev.AUTO || e.kind == Ev.MISTAKE)
            // Markers a commit logs between a stroke's marks and its refusal don't end the stroke.
            if (!belongs && e.kind != Ev.HALFWAY && e.kind != Ev.CONTRADICTION) close()
            val before = decided
            when (e.kind) {
                Ev.MARK, Ev.HINT_MARK -> {
                    val src = if (e.kind == Ev.MARK) Source.PLAYER else Source.HINT
                    val v = e.value.toByte()
                    if (openId == -2) { openId = e.x; openSrc = src; t0 = e.t; openValue = v }
                    t1 = e.t
                    openCells += e.cell
                    if (v == UNKNOWN) { if (src == Source.PLAYER) nClears++ }
                    else {
                        val correct = v == sol[e.cell]
                        val first = correct && !everCorrect[e.cell]
                        val r = if (src == Source.PLAYER && (first || !correct)) rate(e.cell) else null
                        val guess = src == Source.PLAYER && first && (refusedLast[e.cell] || (r != null && r.rung == null))
                        val m = MarkRecord(e.t, e.cell, v, src, e.x, correct, first, r, guess)
                        ms += m
                        if (m.credited && r?.rung != null && (openRung == null || r.rung > openRung!!)) { openRung = r.rung; openLine = r.line }
                        if (guess) openGuesses++
                        if (!correct && src == Source.PLAYER) ss += Strike(e.t, e.cell, v, false, kindOf(r), r?.rung, decided, b.copyOf())
                        refusedLast[e.cell] = false
                    }
                    set(e.cell, v)
                }
                Ev.AUTO -> {
                    if (e.value.toByte() == Mark.CROSSED) nAuto++
                    if (openId == e.x && e.x >= 0) openAuto += e.cell
                    set(e.cell, e.value.toByte())
                }
                Ev.REVERT -> set(e.cell, e.value.toByte())
                Ev.MISTAKE -> {
                    val r = rate(e.cell)
                    ss += Strike(e.t, e.cell, e.value.toByte(), true, kindOf(r), r?.rung, decided, b.copyOf())
                    refusedLast[e.cell] = true
                    if (belongs) openStruck = true
                }
                Ev.HINT -> {
                    if (e.value == 1) { nHints++; if (e.x >= Rung.P1.ordinal) nLookHints++ }
                    if (e.value >= 2) revealed += e.cell
                }
                Ev.CHECK -> nChecks++
            }
            if (decided != before) {
                if (pr.isNotEmpty() && pr.last().t == e.t) pr[pr.size - 1] = ProgressPoint(e.t, decided) else pr += ProgressPoint(e.t, decided)
            }
        }
        close()
        if (game.status == Status.WON && (pr.isEmpty() || pr.last().decided < cells)) pr += ProgressPoint(game.elapsed, cells)

        marks = ms; strikes = ss; strokes = sk; progress = pr
        complete = rated
        hints = nHints; lookAheadHints = nLookHints; checks = nChecks; clears = nClears; autoCrosses = nAuto
        revealedCells = revealed
    }

    val won: Boolean get() = game.status == Status.WON
    val lost: Boolean get() = game.status == Status.LOST
    /** Active time, pauses excluded. */
    val time: Long get() = game.elapsed

    /** Correct cells at the end ("how far you got" when lost). */
    val decided: Int get() = progress.lastOrNull()?.decided ?: 0

    val playerMarks: List<MarkRecord> get() = marks.filter { it.source == Source.PLAYER }

    /** Correct marks out of all marks, refused ones counted (BUILD-PLAN §5.2). */
    val accuracy: Double
        get() {
            val pm = playerMarks
            val all = pm.size + strikes.count { it.refused }
            return if (all == 0) 1.0 else pm.count { it.correct }.toDouble() / all
        }

    /** True when ratings exist for this report (a rater was given). */
    val rated: Boolean get() = complete && marks.any { it.rating != null }

    val guesses: Int get() = marks.count { it.guess }
    val effort10: Int get() = marks.sumOf { it.effort10 }
    /** Effort in line-logic units (an L1 mark at full availability is 1). */
    val effort: Int get() = (effort10 + 5) / 10
    val effortPerMin: Int get() = if (time <= 0) 0 else (effort10 * 6_000L / time).toInt()

    /** The credited mark with the most effort ("Look-ahead at r7c3", "Line logic, row 12"); earliest on ties. */
    val hardest: MarkRecord? get() = marks.filter { it.credited && it.rating?.rung != null }.maxWithOrNull(compareBy<MarkRecord> { it.effort10 }.thenByDescending { it.t })

    /** First-correct player decisions, the unit of rhythm and seconds per cell. */
    private val decisions: List<MarkRecord> get() = marks.filter { it.source == Source.PLAYER && it.first && it.correct }

    /**
     * Rhythm: Monkeytype's consistency (kogasa) on cells decided per 15 s bucket, as Sudoku. 100 is a
     * metronome; low is bursts and stalls. 0 under 30 s or 4 decisions.
     */
    val rhythm: Int
        get() {
            val ds = decisions
            if (time < 30_000 || ds.size < 4) return 0
            val n = (time / 15_000).toInt() + 1
            val bucket = DoubleArray(n)
            ds.forEach { bucket[(it.t / 15_000).toInt().coerceAtMost(n - 1)] += 1.0 }
            val mean = bucket.average()
            if (mean == 0.0) return 0
            val sd = sqrt(bucket.sumOf { (it - mean) * (it - mean) } / n)
            val cov = sd / mean
            return (100 * (1 - tanh(cov + cov * cov * cov / 3 + cov * cov * cov * cov * cov / 5))).toInt().coerceIn(0, 100)
        }

    /** The player's own strokes (not hint placements). */
    val playerStrokes: List<StrokeRecord> get() = strokes.filter { it.source == Source.PLAYER }
    val strokeCount: Int get() = playerStrokes.size
    val longestStroke: Int get() = playerStrokes.maxOfOrNull { it.cells.size } ?: 0
    val cellsPerStroke: Double get() = if (strokeCount == 0) 0.0 else playerStrokes.sumOf { it.cells.size }.toDouble() / strokeCount

    /** Look-ahead marks (T* ≥ P1) the player found without a hint pointing at the cell. */
    val lookAheadUnaided: List<MarkRecord>
        get() = marks.filter { it.credited && it.rating?.rung?.let { r -> r >= Rung.P1 } == true && it.cell !in revealedCells }

    /** Time spent finding each unaided look-ahead: the gap since the previous decision (Stats' median). */
    val lookAheadGaps: List<Long>
        get() {
            val ds = decisions
            val out = ArrayList<Long>()
            for ((k, m) in ds.withIndex()) if (m.credited && m.rating?.rung?.let { it >= Rung.P1 } == true && m.cell !in revealedCells) out += m.t - (if (k == 0) 0L else ds[k - 1].t)
            return out
        }

    /** Active seconds per cell: Stats' y axis when sizes are mixed. */
    val secondsPerCell: Double get() = time / 1000.0 / cells

    val assisted: Boolean get() = game.assisted
    val band: Int get() = game.band
    val net: Int get() = game.net()
    /** Every cell set by the player: auto-cross never placed one and no hint did. */
    val byHand: Boolean get() = autoCrosses == 0 && marks.none { it.source == Source.HINT }
    /** No wrong mark: no refusals, and none left standing (SOLVE-CONDITIONS §5). */
    val flawless: Boolean get() = strikes.isEmpty()
}

/**
 * Records (SOLVE-CONDITIONS, BUILD-PLAN §5.1): never ranked across size × tier × net × aid band, and a
 * hinted or checked run goes to its own assisted table. Keyed by width and height so an imported
 * custom size gets its own table ([size] is null for those).
 */
data class RecordKey(val w: Int, val h: Int, val tier: Tier, val net: Int, val band: Int, val assisted: Boolean) {
    val size get() = app.materialnonogram.engine.Size.of(w, h)
}

/** 0 Lives (3) · 1 Sudden death (1 life) · 2 No net. */
fun Game.net(): Int = when { !rules.flagMistakes -> 2; rules.lives == 1 -> 1; else -> 0 }
fun Game.recordKey() = RecordKey(w, h, deal.tier, net(), band, assisted)

val NET_NAMES = listOf("Lives", "Sudden death", "No net")
val BAND_NAMES = listOf("No help", "Auto-cross", "Contradiction flag")

data class Accolade(val key: String, val label: String, val earned: Boolean, val why: String)

private fun Game.sameKind(o: Game) = o.w == w && o.h == h && o.deal.tier == deal.tier && o.net() == net()

/** Accolades, measured off the log (SOLVE-CONDITIONS §5, BUILD-PLAN §5.2), plus nonogram's "Saw ahead". */
fun accolades(r: Report, prior: List<Game>): List<Accolade> {
    val g = r.game
    if (!r.won) return emptyList()
    val sameKey = prior.filter { it.status == Status.WON && it.recordKey() == g.recordKey() && it.id != g.id }
    val best = sameKey.minOfOrNull { it.elapsed }
    val sameFast = prior.filter { it.status == Status.WON && g.sameKind(it) && it.id != g.id }.takeLast(30).map { it.elapsed }.sorted()
    val median = if (sameFast.size >= 10) sameFast[sameFast.size / 2] else null
    val lookAhead = g.deal.tier >= Tier.EXPERT
    val unaidedLa = r.lookAheadUnaided.size
    return listOf(
        Accolade("record", "New record", best == null || g.elapsed < best, if (best == null) "first of its kind" else "best was ${clock(best)}"),
        Accolade("unaided", "Unaided", !r.assisted, listOfNotNull(plural(r.hints, "hint").takeIf { r.hints > 0 }, plural(r.checks, "check").takeIf { r.checks > 0 }).joinToString(", ")),
        Accolade("byhand", "By hand", r.byHand, if (r.autoCrosses > 0) "auto-cross" else "hint placed a cell"),
        Accolade("fast", "Fast", median != null && g.elapsed < median, median?.let { "usual ${clock(it)}" } ?: "after 10 games"),
        Accolade("pure", "Pure logic", r.rated && r.guesses == 0, plural(r.guesses, "guess", "guesses")),
        Accolade("flawless", "Flawless", r.flawless, plural(r.strikes.size, "strike")),
        Accolade("sawahead", "Saw ahead", lookAhead && r.rated && r.lookAheadHints == 0 && unaidedLa > 0,
            if (r.lookAheadHints > 0) plural(r.lookAheadHints, "look-ahead hint") else "no look-ahead found"),
        Accolade("sudden", "Sudden death", g.net() == 1, ""),
        Accolade("nonet", "No net", g.net() == 2, ""),
    ).filter { it.earned || it.key in setOf("unaided", "byhand", "pure", "flawless", "fast") || (it.key == "sawahead" && lookAhead) }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

fun clock(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
fun clockMs(ms: Long): Pair<String, String> = clock(ms) to "." + (ms % 1000).toString().padStart(3, '0')
fun signedDelta(ms: Long): String { val a = kotlin.math.abs(ms); return (if (ms < 0) "−" else "+") + clock(a) + "." + (a % 1000).toString().padStart(3, '0') }

/** Game time when half the cells were first marked, replayed from the log; null if it never got there. */
fun Game.halfwayTime(): Long? {
    val need = (cells + 1) / 2
    val b = ByteArray(cells); var n = 0
    for (e in events) if (e.changesBoard) {
        val was = b[e.cell] != UNKNOWN
        b[e.cell] = e.value.toByte()
        val now = b[e.cell] != UNKNOWN
        if (was != now) n += if (now) 1 else -1
        if (n >= need) return e.t
    }
    return null
}

/**
 * The halfway message (unchanged from Sudoku): first match wins and it is never discouraging —
 * record pace · ahead of your usual · no mistakes yet · halfway there. Pace lines need the timer
 * shown; with it hidden, time is never mentioned.
 */
fun halfwayLine(g: Game, prior: List<Game>, timerShown: Boolean): Pair<String, String?> {
    val now = g.elapsed
    if (timerShown) {
        val best = prior.filter { it.status == Status.WON && it.recordKey() == g.recordKey() }.minByOrNull { it.elapsed }?.halfwayTime()
        if (best != null && now < best) return "Halfway" to "Record pace"
        val usual = prior.filter { it.status == Status.WON && g.sameKind(it) }.takeLast(30).mapNotNull { it.halfwayTime() }
        if (usual.size >= 3) {
            val m = usual.median()!!
            if (now < m) return "Halfway" to "${clock(m - now)} ahead of usual"
        }
    }
    if (g.rules.flagMistakes && g.mistakes == 0) return "Halfway" to "No mistakes yet"
    return "Halfway there" to null
}

fun List<Long>.median(): Long? = if (isEmpty()) null else sorted()[size / 2]
