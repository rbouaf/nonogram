package app.materialnonogram.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.materialnonogram.engine.Hint
import app.materialnonogram.engine.LineCheck
import app.materialnonogram.engine.Lines
import app.materialnonogram.game.Mark.CROSSED
import app.materialnonogram.game.Mark.FILLED
import app.materialnonogram.game.Mark.UNKNOWN
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A nonogram in progress (BUILD-PLAN §3). Plain Kotlin with Compose snapshot state, owned by the app
 * model; the screen reads it and calls its verbs **on the main thread**. All rule logic lives here so
 * it is unit-tested without a screen. Published arrays ([board], [auto], [satisfied], …) are fresh
 * copies that are never mutated afterwards, so a background job may read them.
 *
 * The gesture layer only feeds cells in: [beginStroke] on down, [extendStroke] on every move,
 * [switchStrokeTool] when a long-press fires, [endStroke] on lift, [cancelStroke] when a second
 * finger lands. Everything else about a stroke — line lock, never skipping a cell, the start-cell
 * rule, mistakes, undo units, the live count — is decided here.
 */
class Game(
    val id: String,
    val deal: Deal,
    val rules: Rules,
    board: ByteArray = ByteArray(deal.cells),
    auto: BooleanArray = BooleanArray(deal.cells),
    lives: Int = rules.lives,
    elapsed: Long = 0L,
    val events: MutableList<Ev> = mutableListOf(),
    status: Status = Status.PLAYING,
    val startedAt: Long = System.currentTimeMillis(),
    band: Int = rules.band,
    autoCross: Boolean = rules.autoCross,
    contradictionFlag: Boolean = rules.contradictionFlag,
    /** The real board under an open what-if layer, if one was open when the game was saved. */
    whatIfBase: ByteArray? = null,
    whatIfBaseAuto: BooleanArray? = null,
    tool: Tool = Tool.FILL,
) {
    val w = deal.w
    val h = deal.h
    val cells = deal.cells
    val lineCount = w + h
    private val sol: ByteArray = deal.puzzle.solutionBoard()
    private val clues = deal.puzzle.clues
    private val lineCells: Array<IntArray> = Array(lineCount) { Lines.cells(w, h, it) }

    // Working state; published to Compose state by publish().
    private val cur: ByteArray = board.copyOf()
    private val curAuto: BooleanArray = auto.copyOf()

    /** The board as shown: the real board, or the what-if layer over it. */
    var board: ByteArray by mutableStateOf(cur.copyOf()); private set
    /** Crosses the app placed (auto-cross), never the player's. */
    var auto: BooleanArray by mutableStateOf(curAuto.copyOf()); private set
    /** Per line: its filled cells form its clue exactly (clue greying, per line, never per number). */
    var satisfied: BooleanArray by mutableStateOf(BooleanArray(lineCount)); private set
    /** Per line: finished runs contradict the clue. Always false unless [contradictionFlag]. */
    var contradicted: BooleanArray by mutableStateOf(BooleanArray(lineCount)); private set

    var lives by mutableIntStateOf(lives); private set
    var status by mutableStateOf(status); private set
    var paused by mutableStateOf(false); private set
    /** The selected tool button (D5). A long-press switches a stroke's tool, not this. */
    var tool by mutableStateOf(tool); private set
    var hint by mutableStateOf<HintView?>(null); private set
    var stroke by mutableStateOf<StrokeView?>(null); private set

    var autoCross by mutableStateOf(autoCross); private set
    var contradictionFlag by mutableStateOf(contradictionFlag && !rules.flagMistakes); private set
    /** The most assisted aid band this run reached (BUILD-PLAN §5.1): 0..2. */
    var band by mutableIntStateOf(maxOf(band, rules.band)); private set

    private var _elapsed = mutableLongStateOf(elapsed)
    val elapsed: Long get() = _elapsed.longValue

    /** Bumped on every change worth saving. */
    var revision by mutableIntStateOf(0); private set

    val mistakes: Int get() = events.count { it.kind == Ev.MISTAKE }
    val hinted: Boolean get() = events.any { it.kind == Ev.HINT || it.kind == Ev.HINT_MARK }
    val checked: Boolean get() = events.any { it.kind == Ev.CHECK }
    /** A hint or a check (both oracles) puts the run in the assisted records. */
    val assisted: Boolean get() = hinted || checked

    private val _fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val fx: SharedFlow<Fx> = _fx
    private val recent = ArrayDeque<Fx>()
    /** Test hook: the effects emitted since the last poll, oldest first (the last 256). */
    internal fun pollFx(): Fx? = recent.removeFirstOrNull()

    private fun emit(f: Fx) {
        _fx.tryEmit(f)
        recent.addLast(f); if (recent.size > 256) recent.removeFirst()
    }

    private var strokeSeq: Int = (events.maxOfOrNull { if (it.kind == Ev.MARK || it.kind == Ev.HINT_MARK || it.kind == Ev.MISTAKE || it.kind == Ev.PROVISIONAL) it.x else -1 } ?: -1) + 1

    private class Snapshot(val board: ByteArray, val auto: BooleanArray)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    // What-if layer.
    private var base: ByteArray? = whatIfBase?.copyOf()
    private var baseAuto: BooleanArray? = whatIfBaseAuto?.copyOf() ?: whatIfBase?.let { BooleanArray(cells) }
    private var barrier = 0
    var inWhatIf by mutableStateOf(whatIfBase != null); private set
    /** Cells the what-if layer has changed (tint them), or null outside one. */
    var provisional by mutableStateOf<BooleanArray?>(null); private set

    init {
        publish()
    }

    private fun log(kind: Char, cell: Int = -1, value: Int = 0, x: Int = -1, t: Long = elapsed) {
        events += Ev(t, kind, cell, value, x); revision++
    }

    /** A cell-changing event: inside a what-if layer every change is provisional and never touches the real log. */
    private fun logCell(kind: Char, cell: Int, value: Byte, x: Int, t: Long = elapsed) =
        log(if (inWhatIf) Ev.PROVISIONAL else kind, cell, value.toInt(), x, t)

    // ── Reading ──────────────────────────────────────────────────────────────────────────────────

    /** The real board (under any what-if layer). A fresh copy. */
    fun realBoard(): ByteArray = (base ?: cur).copyOf()

    /** What to draw at [cell]: the board, with a pending stroke start shown as its target. */
    fun shown(cell: Int): Byte {
        val s = st
        return if (s != null && s.pending && s.start == cell) s.to else board[cell]
    }

    fun solutionAt(cell: Int): Byte = sol[cell]

    /** Cells marked (filled or crossed) on the real board. */
    val marked: Int get() = realBoard().count { it != UNKNOWN }

    // ── Time ─────────────────────────────────────────────────────────────────────────────────────

    fun tick(dtMs: Long) {
        if (status == Status.PLAYING && !paused) _elapsed.longValue += dtMs
    }

    fun pause(p: Boolean) {
        if (status != Status.PLAYING || p == paused) return
        // Leaving mid-stroke keeps what was drawn; a start cell never left is dropped, not judged.
        if (p) { if (st?.pending == true) cancelStroke() else endStroke() }
        paused = p
        log(if (p) Ev.PAUSE else Ev.RESUME)
    }

    private val canPlay: Boolean get() = status == Status.PLAYING && !paused

    fun selectTool(t: Tool) {
        if (t == tool) return
        tool = t
        emit(Fx.ToolSwitched(t, longPress = false))
    }

    // ── Strokes (BUILD-PLAN §4 "The stroke") ─────────────────────────────────────────────────────

    private class Entry(val cell: Int, val off: Int, val t: Long)

    private inner class StrokeState(val id: Int, val start: Int, var tool: Tool, val from: Byte, val deferred: Boolean) {
        var to: Byte = target(tool, from)
        val before: ByteArray = cur.copyOf()
        val beforeAuto: BooleanArray = curAuto.copyOf()
        val beforeSatisfied: BooleanArray = satisfied
        val beforeContradicted: BooleanArray = contradicted
        val t0: Long = elapsed
        var pending = deferred
        var startApplied = false
        var axis = -1
        var ext = 0
        val applied = ArrayList<Entry>()
        var stopped = false
        var finalized = false
        val count: Int get() = applied.size + (if (pending) 1 else 0)
    }

    private var st: StrokeState? = null

    private fun target(tool: Tool, from: Byte): Byte = if (from == tool.mark) UNKNOWN else tool.mark

    private fun showStroke() {
        val s = st
        stroke = s?.let { StrokeView(it.start, it.tool, it.from, it.to, it.axis, it.count, it.pending, it.stopped) }
    }

    /**
     * Finger down on [cell] with [tool]. The stroke will change only cells in [cell]'s current state
     * (the start-cell rule, 02 C3): to the tool's state, or back to unknown if they already hold it.
     *
     * With [deferStart] (the default, for when long-press = other tool is on) the start cell is only a
     * preview ([shown], [StrokeView.pending]) until the finger leaves it, lifts, or a long-press
     * switches the tool, so a long-press never costs a life for the tool it didn't mean. Without it the
     * cell is applied on down. Returns false if input is closed.
     */
    fun beginStroke(cell: Int, tool: Tool = this.tool, deferStart: Boolean = true): Boolean {
        if (!canPlay || cell !in 0 until cells) return false
        endStroke()
        val s = StrokeState(strokeSeq++, cell, tool, cur[cell], deferStart)
        st = s
        emit(Fx.Pressed(cell, tool, s.to))
        if (!deferStart) applyStart(s)
        showStroke()
        return true
    }

    /** A tap: down and up on one cell with [tool]. */
    fun tap(cell: Int, tool: Tool = this.tool): Boolean {
        if (!beginStroke(cell, tool)) return false
        endStroke()
        return true
    }

    /** The finger is over [cell]. Walks every cell between the last one and it along the locked axis. */
    fun extendStroke(cell: Int) {
        if (cell !in 0 until cells) return
        extendStroke(cell / w, cell % w)
    }

    /** The finger is over ([row], [col]), which may be off the grid: it is clamped to the last cell. */
    fun extendStroke(row: Int, col: Int) {
        val s = st ?: return
        if (s.stopped || s.finalized || !canPlay) return
        val r = row.coerceIn(0, h - 1); val c = col.coerceIn(0, w - 1)
        val sr = s.start / w; val sc = s.start % w
        if (s.axis < 0) {
            if (r == sr && c == sc) return
            s.axis = if (kotlin.math.abs(c - sc) >= kotlin.math.abs(r - sr)) StrokeView.AXIS_ROW else StrokeView.AXIS_COL
        }
        val t = if (s.axis == StrokeView.AXIS_ROW) c - sc else r - sr
        if (t == s.ext) return
        if (s.pending) { applyStart(s); if (s.stopped) { showStroke(); return } }
        // Pull back: revert every applied cell now outside the range start..t.
        while (s.applied.isNotEmpty() && !inRange(s.applied.last().off, t)) {
            val e = s.applied.removeLast()
            cur[e.cell] = s.before[e.cell]; curAuto[e.cell] = s.beforeAuto[e.cell]
            emit(Fx.Retracted(e.cell, s.count))
        }
        // Advance: walk every cell beyond the furthest one already walked on this side, up to t, never skipping.
        if (t != 0) {
            val dir = if (t > 0) 1 else -1
            val walked = if (s.ext != 0 && (s.ext > 0) == (t > 0)) kotlin.math.abs(s.ext) else 0
            for (k in walked + 1..kotlin.math.abs(t)) {
                val cell = offCell(s, k * dir)
                if (cur[cell] != s.from) continue // the start-cell rule: other states are passed over
                if (!apply(s, cell, k * dir)) { showStroke(); return }
                emit(Fx.Marked(cell, s.to, s.count))
            }
        }
        s.ext = t
        publish()
        showStroke()
    }

    private fun inRange(off: Int, t: Int): Boolean = off == 0 || (t != 0 && (off > 0) == (t > 0) && kotlin.math.abs(off) <= kotlin.math.abs(t))

    private fun offCell(s: StrokeState, off: Int): Int =
        if (s.axis == StrokeView.AXIS_ROW) s.start + off else s.start + off * w

    /** Applies the stroke's target at [cell], or refuses it (flag mode, wrong) and stops the stroke. */
    private fun apply(s: StrokeState, cell: Int, off: Int): Boolean {
        if (isWrong(cell, s.to)) {
            s.stopped = true
            finalize(s)
            refuse(cell, s.to, s.id)
            return false
        }
        cur[cell] = s.to; curAuto[cell] = false
        s.applied += Entry(cell, off, elapsed)
        return true
    }

    private fun applyStart(s: StrokeState) {
        s.pending = false
        if (s.startApplied) return
        if (isWrong(s.start, s.to)) { s.stopped = true; finalize(s); refuse(s.start, s.to, s.id); return }
        cur[s.start] = s.to; curAuto[s.start] = false
        s.applied += Entry(s.start, 0, s.t0)
        s.startApplied = true
        publish()
    }

    /** Flag mode judges a mark against the solution; clearing is never wrong, and nothing is judged in a what-if layer. */
    private fun isWrong(cell: Int, to: Byte): Boolean = rules.flagMistakes && !inWhatIf && to != UNKNOWN && to != sol[cell]

    /**
     * Long-press fired before the finger left the start cell: the stroke continues with the other tool
     * (D5). Returns false once the stroke has moved, stopped, or ended.
     */
    fun switchStrokeTool(): Boolean {
        val s = st ?: return false
        if (s.finalized || s.stopped || s.ext != 0 || !canPlay) return false
        if (s.startApplied) {
            cur[s.start] = s.before[s.start]; curAuto[s.start] = s.beforeAuto[s.start]
            s.applied.clear(); s.startApplied = false
        }
        s.tool = s.tool.other
        s.to = target(s.tool, s.from)
        emit(Fx.ToolSwitched(s.tool, longPress = true))
        if (!s.deferred) applyStart(s) else s.pending = true
        publish(); showStroke()
        return true
    }

    /** Lift: the stroke becomes one undo unit (a tap commits its pending start here). */
    fun endStroke() {
        val s = st ?: return
        if (!s.finalized) {
            if (s.pending) applyStart(s)
            finalize(s)
        }
        st = null
        showStroke()
    }

    /** A second finger landed (pinch/pan): the stroke is reverted and leaves nothing in the log. A stroke already stopped by a mistake has been committed and stays. */
    fun cancelStroke() {
        val s = st ?: return
        st = null
        if (!s.finalized) {
            s.before.copyInto(cur); s.beforeAuto.copyInto(curAuto)
            s.finalized = true
            publish()
            emit(Fx.StrokeCancelled)
        }
        showStroke()
    }

    /** Commits a stroke: logs its marks in the order they were made, runs the line effects, pushes one undo unit. */
    private fun finalize(s: StrokeState) {
        if (s.finalized) return
        s.finalized = true
        s.pending = false
        if (s.applied.isEmpty()) return
        val kind = Ev.MARK
        for (e in s.applied.sortedWith(compareBy<Entry>({ it.t }, { kotlin.math.abs(it.off) }))) logCell(kind, e.cell, s.to, s.id, e.t)
        commit(Snapshot(s.before, s.beforeAuto), s.beforeSatisfied, s.beforeContradicted, s.applied.last().cell, s.id)
    }

    /** After any player or hint change: auto-cross, publish, line effects, undo unit, halfway, end. */
    private fun commit(undo: Snapshot, beforeSatisfied: BooleanArray, beforeContradicted: BooleanArray, lastCell: Int, strokeId: Int) {
        val crossed = if (autoCross) autoCrossSweep(strokeId) else emptyList()
        publish()
        undoStack.addLast(undo); redoStack.clear()
        val done = (0 until lineCount).filter { satisfied[it] && !beforeSatisfied[it] }
        if (crossed.isNotEmpty()) emit(Fx.AutoCrossed(crossed))
        val broken = (0 until lineCount).filter { contradicted[it] && !beforeContradicted[it] }
        if (broken.isNotEmpty()) {
            if (!inWhatIf) for (l in broken) log(Ev.CONTRADICTION, l)
            emit(Fx.Contradiction(broken))
        }
        hint?.let { if (cur[it.hint.cell] == it.hint.value) hint = null }
        if (inWhatIf) { if (done.isNotEmpty()) emit(Fx.LinesDone(done, lastCell)); return }
        if (checkEnd(done, lastCell)) return
        if (done.isNotEmpty()) emit(Fx.LinesDone(done, lastCell))
        if (cur.count { it != UNKNOWN } * 2 >= cells && events.none { it.kind == Ev.HALFWAY }) {
            log(Ev.HALFWAY); emit(Fx.Halfway)
        }
    }

    /**
     * Auto-cross (Q6): every satisfied line's unknown cells become crosses ([Source.AUTO]); an auto
     * cross whose row and column are both no longer satisfied goes back to unknown. Logged, never the
     * player's marks.
     */
    private fun autoCrossSweep(strokeId: Int): List<Int> {
        val sat = BooleanArray(lineCount) { LineCheck.isSatisfied(clues.line(it), Lines.read(w, h, cur, it)) }
        for (i in 0 until cells) if (curAuto[i] && cur[i] == CROSSED && !sat[i / w] && !sat[h + i % w]) {
            cur[i] = UNKNOWN; curAuto[i] = false
            logCell(Ev.AUTO, i, UNKNOWN, strokeId)
        }
        val crossed = ArrayList<Int>()
        for (l in 0 until lineCount) if (sat[l]) for (i in lineCells[l]) if (cur[i] == UNKNOWN) {
            cur[i] = CROSSED; curAuto[i] = true
            logCell(Ev.AUTO, i, CROSSED, strokeId)
            crossed += i
        }
        return crossed
    }

    private fun refuse(cell: Int, value: Byte, strokeId: Int) {
        if (status != Status.PLAYING) return // the marks before it won the game
        lives -= 1
        log(Ev.MISTAKE, cell, value.toInt(), strokeId)
        emit(Fx.Mistake(cell, value, lives))
        if (lives <= 0) { status = Status.LOST; st = null; showStroke(); revision++; emit(Fx.Lost) }
    }

    /** Solved when the filled cells are the solution; crosses don't count (the puzzle is unique). */
    private fun checkEnd(done: List<Int>, lastCell: Int): Boolean {
        var solved = true; var full = true
        for (i in 0 until cells) {
            if ((cur[i] == FILLED) != (sol[i] == FILLED)) solved = false
            if (cur[i] == UNKNOWN) full = false
        }
        if (solved) {
            status = Status.WON; hint = null; undoStack.clear(); redoStack.clear(); revision++
            emit(Fx.Won(done, lastCell))
            return true
        }
        if (full) emit(Fx.FilledWrong)
        return false
    }

    private fun publish() {
        board = cur.copyOf()
        auto = curAuto.copyOf()
        satisfied = BooleanArray(lineCount) { LineCheck.isSatisfied(clues.line(it), Lines.read(w, h, cur, it)) }
        contradicted = if (contradictionFlag) BooleanArray(lineCount) { LineCheck.contradicts(clues.line(it), Lines.read(w, h, cur, it)) } else BooleanArray(lineCount)
        val b = base
        provisional = if (b == null) null else BooleanArray(cells) { cur[it] != b[it] }
    }

    // ── Undo / redo ──────────────────────────────────────────────────────────────────────────────

    val canUndo: Boolean get() = undoStack.size > barrier && status == Status.PLAYING
    val canRedo: Boolean get() = redoStack.isNotEmpty() && status == Status.PLAYING

    fun undo(): Boolean {
        if (!canPlay || st != null || !canUndo) return false
        val s = undoStack.removeLast()
        redoStack.addLast(Snapshot(cur.copyOf(), curAuto.copyOf()))
        log(Ev.UNDO); restore(s); emit(Fx.Undone)
        return true
    }

    fun redo(): Boolean {
        if (!canPlay || st != null || !canRedo) return false
        val s = redoStack.removeLast()
        undoStack.addLast(Snapshot(cur.copyOf(), curAuto.copyOf()))
        log(Ev.REDO); restore(s); emit(Fx.Redone)
        return true
    }

    private fun restore(s: Snapshot) {
        for (i in 0 until cells) {
            if (cur[i] != s.board[i]) logCell(Ev.REVERT, i, s.board[i], -1)
            cur[i] = s.board[i]; curAuto[i] = s.auto[i]
        }
        publish()
        hint?.let { if (cur[it.hint.cell] == it.hint.value) hint = null }
    }

    // ── Aids and oracles ─────────────────────────────────────────────────────────────────────────

    /**
     * Settings changed mid-run. The run keeps the most assisted band it ever reached, and the raise is
     * logged (SOLVE-CONDITIONS §5). Turning auto-cross on crosses every already-finished line at once.
     */
    fun setAids(autoCross: Boolean, contradictionFlag: Boolean) {
        if (status != Status.PLAYING) return
        val turnedOn = autoCross && !this.autoCross
        this.autoCross = autoCross
        this.contradictionFlag = contradictionFlag && !rules.flagMistakes
        val b = bandOf(autoCross, this.contradictionFlag, rules.flagMistakes)
        if (b > band) { band = b; log(Ev.AID, value = b) }
        if (turnedOn && st == null) {
            val crossed = autoCrossSweep(-1)
            publish()
            if (crossed.isNotEmpty()) emit(Fx.AutoCrossed(crossed))
        } else publish()
        revision++
    }

    /**
     * The on-demand check (don't-flag mode, when the run allows it): true if no mark on the board is
     * wrong. Logged; it makes the run assisted. Null when not available.
     */
    fun check(): Boolean? {
        if (!canPlay || rules.flagMistakes || !rules.checkButton) return null
        val ok = (0 until cells).none { cur[it] != UNKNOWN && cur[it] != sol[it] }
        log(Ev.CHECK, value = if (ok) 1 else 0)
        emit(Fx.Checked(ok))
        return ok
    }

    /** Shows [h] at stage 1. Compute it off the main thread from [realBoard] (see `Session.findHint`). */
    fun showHint(h: Hint) {
        if (!canPlay || !rules.hints || inWhatIf) return
        show(HintView(1, h))
    }

    /** Next stage of the hint showing; false if there is none or it is at stage 3. */
    fun advanceHint(): Boolean {
        val v = hint ?: return false
        if (!canPlay || v.stage >= 3) return false
        show(v.copy(stage = v.stage + 1))
        return true
    }

    private fun show(v: HintView) {
        hint = v
        log(Ev.HINT, v.hint.cell, v.stage, v.hint.rung.ordinal)
        emit(Fx.HintShown(v))
    }

    fun clearHint() { hint = null }

    /** Places the hint's cell for the player ([Ev.HINT_MARK], its own undo unit; never credited). */
    fun applyHint(): Boolean {
        val v = hint ?: return false
        if (!canPlay || st != null || inWhatIf) return false
        val c = v.hint.cell
        hint = null
        if (cur[c] == v.hint.value) return false
        val undo = Snapshot(cur.copyOf(), curAuto.copyOf())
        val bs = satisfied; val bc = contradicted
        cur[c] = v.hint.value; curAuto[c] = false
        val id = strokeSeq++
        logCell(Ev.HINT_MARK, c, v.hint.value, id)
        commit(undo, bs, bc, c, id)
        return true
    }

    // ── What-if layer (02's top-ranked extra; model only) ────────────────────────────────────────

    /**
     * Opens a provisional layer: marks made now are tinted ([provisional]), never judged, never logged
     * as real marks, and can be committed or discarded together. Undo stops at the layer.
     */
    fun beginWhatIf(): Boolean {
        if (!canPlay || inWhatIf) return false
        endStroke()
        base = cur.copyOf(); baseAuto = curAuto.copyOf()
        barrier = undoStack.size; redoStack.clear()
        inWhatIf = true
        hint = null
        log(Ev.WHATIF)
        publish()
        emit(Fx.WhatIf(WhatIfPhase.BEGIN))
        return true
    }

    /**
     * Makes the layer real, as one stroke and one undo unit. In flag mode a layer holding any wrong mark
     * is refused as a whole and costs one life (the first wrong mark made is the one reported); the
     * layer stays open. Returns whether it was committed.
     */
    fun commitWhatIf(): Boolean {
        if (!canPlay || !inWhatIf) return false
        endStroke()
        val b = base!!; val ba = baseAuto!!
        val changed = (0 until cells).filter { cur[it] != b[it] }
        if (rules.flagMistakes) {
            val wrong = changed.filter { cur[it] != UNKNOWN && cur[it] != sol[it] }.toSet()
            if (wrong.isNotEmpty()) {
                val since = events.indexOfLast { it.kind == Ev.WHATIF }
                val first = events.drop(since + 1).firstOrNull { it.kind == Ev.PROVISIONAL && it.cell in wrong && it.value.toByte() == cur[it.cell] }?.cell
                    ?: wrong.min()
                emit(Fx.WhatIf(WhatIfPhase.REFUSED))
                refuse(first, cur[first], strokeSeq++)
                return false
            }
        }
        val bs = BooleanArray(lineCount) { LineCheck.isSatisfied(clues.line(it), Lines.read(w, h, b, it)) }
        val bc = if (contradictionFlag) BooleanArray(lineCount) { LineCheck.contradicts(clues.line(it), Lines.read(w, h, b, it)) } else BooleanArray(lineCount)
        while (undoStack.size > barrier) undoStack.removeLast()
        redoStack.clear()
        base = null; baseAuto = null; barrier = 0
        inWhatIf = false
        log(Ev.COMMIT)
        val id = strokeSeq++
        for (i in changed) log(if (curAuto[i]) Ev.AUTO else Ev.MARK, i, cur[i].toInt(), id)
        emit(Fx.WhatIf(WhatIfPhase.COMMIT))
        commit(Snapshot(b, ba), bs, bc, changed.lastOrNull() ?: 0, id)
        return true
    }

    /** Drops the layer: the real board comes back exactly as it was. */
    fun discardWhatIf() {
        if (!inWhatIf) return
        cancelStroke()
        base!!.copyInto(cur); baseAuto!!.copyInto(curAuto)
        while (undoStack.size > barrier) undoStack.removeLast()
        redoStack.clear()
        base = null; baseAuto = null; barrier = 0
        inWhatIf = false
        log(Ev.DISCARD)
        publish()
        emit(Fx.WhatIf(WhatIfPhase.DISCARD))
    }

    /** The real board and auto mask under an open what-if layer, for saving. */
    internal fun whatIfState(): Pair<ByteArray, BooleanArray>? = base?.let { it.copyOf() to baseAuto!!.copyOf() }
}
