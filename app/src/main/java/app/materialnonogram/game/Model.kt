package app.materialnonogram.game

import app.materialnonogram.engine.Cell
import app.materialnonogram.engine.Generated
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Grade
import app.materialnonogram.engine.Hint
import app.materialnonogram.engine.Importer
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.SgtId
import app.materialnonogram.engine.ShareCode
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier

/** Cell states on the play board. The same bytes as the engine's [Cell]: a cross *is* the claim "empty". */
object Mark {
    const val UNKNOWN: Byte = Cell.UNKNOWN
    const val FILLED: Byte = Cell.FILLED
    const val CROSSED: Byte = Cell.EMPTY
}

/** The two always-visible tool buttons (D5). */
enum class Tool(val mark: Byte) {
    FILL(Mark.FILLED), CROSS(Mark.CROSSED);

    val other: Tool get() = if (this == FILL) CROSS else FILL
}

enum class Status { PLAYING, WON, LOST }

/** Who put a mark on the board. Only [PLAYER] marks are the player's own (effort, accuracy, strokes). */
enum class Source { PLAYER, HINT, AUTO }

enum class Origin { GENERATED, IMPORTED }

/**
 * What a game was dealt. **A puzzle is its solution bitmap** (D10): [puzzle] is always stored, the
 * clues are derived from it, and [seed] is internal (`-1` for an import).
 */
data class Deal(
    val puzzle: Puzzle,
    val tier: Tier,
    val seed: Long,
    val generatorVersion: Int,
    val origin: Origin = Origin.GENERATED,
) {
    val w: Int get() = puzzle.w
    val h: Int get() = puzzle.h
    val cells: Int get() = puzzle.cells
    /** Null for an imported custom size. */
    val size: Size? get() = puzzle.size
    val shareCode: String get() = ShareCode.encode(puzzle)
    /** "Copy as text": Simon Tatham's Pattern game ID. */
    val sgtId: String get() = SgtId.encode(puzzle)

    companion object {
        fun of(g: Generated) = Deal(g.puzzle, g.tier, g.seed, g.generatorVersion)

        /** An opened code ([Importer.open]); the tier is estimated from its grade by [tierOf]. */
        fun imported(r: Importer.Result): Deal? {
            val p = r.puzzle ?: return null
            return Deal(p, tierOf(r.grade ?: return null, p), -1L, Generator.VERSION, Origin.IMPORTED)
        }

        /**
         * The generator's tier rule read backwards (ENGINE.md "Tiers"): look-ahead steps decide Expert and
         * Master; a line-only puzzle is placed by its fill in the generator's bands.
         */
        fun tierOf(g: Grade, p: Puzzle): Tier {
            if (g.usesP2 || g.p1Steps >= 4) return Tier.MASTER
            if (g.p1Steps >= 1) return Tier.EXPERT
            val fill = p.filledCount() * 1000 / p.cells
            return when {
                fill >= 620 && g.firstPassPermille >= 400 -> Tier.EASY
                fill >= 550 -> Tier.MEDIUM
                else -> Tier.HARD
            }
        }
    }
}

/**
 * The rules a run was started under (SOLVE-CONDITIONS, BUILD-PLAN §5.1). The **net** ([flagMistakes],
 * [lives]) and the oracles ([checkButton], [hints]) are locked for the run. The two aids ([autoCross],
 * [contradictionFlag]) are only the starting values: they may change mid-run through [Game.setAids],
 * and the run keeps the most assisted band it reached.
 */
data class Rules(
    val flagMistakes: Boolean = true,
    /** 1 or 3 in flag mode; 0 when mistakes are not flagged. */
    val lives: Int = 3,
    val autoCross: Boolean = true,
    /** Don't-flag mode only: a clue turns red when finished runs contradict it (Tatham's weak check). */
    val contradictionFlag: Boolean = false,
    /** Don't-flag mode only: an on-demand yes/no check, logged; it makes the run assisted. */
    val checkButton: Boolean = false,
    val hints: Boolean = true,
) {
    init {
        require(if (flagMistakes) lives == 1 || lives == 3 else lives == 0) { "lives must be 1 or 3 when flagging, 0 otherwise" }
    }

    val band: Int get() = bandOf(autoCross, contradictionFlag, flagMistakes)

    companion object {
        /** Flag mode or not, with the lives that go with it. */
        fun of(flagMistakes: Boolean, lives: Int = 3, autoCross: Boolean = true, contradictionFlag: Boolean = false, checkButton: Boolean = false, hints: Boolean = true) =
            Rules(flagMistakes, if (flagMistakes) lives else 0, autoCross, contradictionFlag && !flagMistakes, checkButton && !flagMistakes, hints)
    }
}

/**
 * Aid band (BUILD-PLAN §5.1): 0 none · 1 auto-cross (labour) · 2 contradiction flag (a logical check,
 * one step up). The flag only exists in don't-flag mode, where wrong marks can stand.
 */
fun bandOf(autoCross: Boolean, contradictionFlag: Boolean, flagMistakes: Boolean): Int = when {
    contradictionFlag && !flagMistakes -> 2
    autoCross -> 1
    else -> 0
}

/**
 * One event in the append-only log. Every stat is recomputed from these; nothing derived is stored.
 * [t] is game time in ms, excluding pauses. Replaying every cell-changing event ([MARK], [HINT_MARK],
 * [AUTO], [REVERT]) in order from a blank board reproduces the real board exactly; [PROVISIONAL]
 * events belong to a what-if layer and never touch it.
 *
 * | kind | cell | value | x |
 * |---|---|---|---|
 * | [MARK] player mark | cell | new state | stroke id |
 * | [HINT_MARK] mark placed by a hint | cell | new state | stroke id |
 * | [AUTO] auto-cross (2) or auto-uncross (0) | cell | new state | stroke id that triggered it, -1 if none |
 * | [REVERT] undo/redo/cancel restoring a cell | cell | new state | -1 |
 * | [PROVISIONAL] any change inside a what-if layer | cell | new state | stroke id |
 * | [MISTAKE] refused mark (flag mode) | cell | refused state | stroke id |
 * | [HINT] hint shown | hint cell | stage 1..3 | rung ordinal |
 * | [CHECK] check pressed | -1 | 1 no wrong marks, 0 some | -1 |
 * | [CONTRADICTION] a line newly contradicts | line | 0 | -1 |
 * | [AID] aid band raised mid-run | -1 | new band | -1 |
 * | [UNDO], [REDO], [PAUSE], [RESUME], [HALFWAY], [WHATIF], [COMMIT], [DISCARD] | markers | | |
 */
data class Ev(val t: Long, val kind: Char, val cell: Int = -1, val value: Int = 0, val x: Int = -1) {
    val changesBoard: Boolean get() = kind == MARK || kind == HINT_MARK || kind == AUTO || kind == REVERT

    companion object {
        const val MARK = 'M'
        const val HINT_MARK = 'K'
        const val AUTO = 'A'
        const val REVERT = 'V'
        const val PROVISIONAL = 'm'
        const val MISTAKE = 'X'
        const val HINT = 'H'
        const val CHECK = 'C'
        const val CONTRADICTION = 'L'
        const val AID = 'a'
        const val UNDO = 'U'
        const val REDO = 'R'
        const val PAUSE = 'p'
        const val RESUME = 'r'
        const val HALFWAY = 'h'
        const val WHATIF = 'w'
        const val COMMIT = 'c'
        const val DISCARD = 'd'
    }
}

/**
 * One-shot things the screen animates and the phone feels (BUILD-PLAN §4 haptics table). Emitted on
 * [Game.fx]; dropped if nobody listens. Lines are indexed rows `0 until h`, then columns.
 */
sealed interface Fx {
    /** Finger down on [cell] (instant response: press state + haptic). [to] is what the stroke will make of it. */
    data class Pressed(val cell: Int, val tool: Tool, val to: Byte) : Fx
    /** A stroke changed [cell] (not the start cell: that one is [Pressed]). [count] is the live run count. */
    data class Marked(val cell: Int, val to: Byte, val count: Int) : Fx
    /** The finger pulled back and [cell] reverted. */
    data class Retracted(val cell: Int, val count: Int) : Fx
    /** The stroke's tool, or the selected tool, changed. [longPress] = a stroke switched by holding still. */
    data class ToolSwitched(val tool: Tool, val longPress: Boolean) : Fx
    /** A second finger landed: the stroke was reverted. */
    data object StrokeCancelled : Fx
    /** Lines satisfied by the commit that just ended ([cell] = the stroke's last cell, the wave's origin). */
    data class LinesDone(val lines: List<Int>, val cell: Int) : Fx
    /** Cells auto-crossed by that commit (draw the crosses in; never the player's marks). */
    data class AutoCrossed(val cells: List<Int>) : Fx
    /** Flag mode: a wrong mark was refused and cost a life. The stroke stopped here. */
    data class Mistake(val cell: Int, val value: Byte, val livesLeft: Int) : Fx
    data object Lost : Fx
    /** Don't-flag mode with the contradiction flag on: these lines' finished runs now contradict their clue. */
    data class Contradiction(val lines: List<Int>) : Fx
    data object Undone : Fx
    data object Redone : Fx
    data class HintShown(val view: HintView) : Fx
    data class Checked(val ok: Boolean) : Fx
    /** Half the cells are marked (counted, never checked: it leaks nothing). */
    data object Halfway : Fx
    /** Solved. [lines] were completed by the final commit, [cell] is where it ended. */
    data class Won(val lines: List<Int>, val cell: Int) : Fx
    /** Don't-flag mode: every cell is marked and the filled cells are not the solution. */
    data object FilledWrong : Fx
    data class WhatIf(val phase: WhatIfPhase) : Fx
}

enum class WhatIfPhase { BEGIN, COMMIT, DISCARD, REFUSED }

/**
 * What the hint button shows (BUILD-PLAN §3): stage 1 "[Hint.line] has a forced cell", stage 2 which
 * cell ([Hint.cell], [Hint.value]), stage 3 why ([Hint.rung]; for look-ahead "if [Hint.cell] were
 * [Hint.assumed], [Hint.brokenLine] would break").
 */
data class HintView(val stage: Int, val hint: Hint)

/** The stroke under the finger, for drawing the press state and the live-count bubble. */
data class StrokeView(
    val start: Int,
    val tool: Tool,
    /** The state the stroke changes (its start cell's state) and what it changes it to. */
    val from: Byte,
    val to: Byte,
    /** -1 not chosen yet, [AXIS_ROW] or [AXIS_COL]. */
    val axis: Int,
    /** Live run count: cells this stroke has changed, the pending start included. */
    val count: Int,
    /** The start cell is not applied yet (a long-press may still switch the tool). */
    val pending: Boolean,
    /** A refused mark stopped the stroke; it ignores further movement. */
    val stopped: Boolean,
) {
    companion object {
        const val AXIS_ROW = 0
        const val AXIS_COL = 1
    }
}
