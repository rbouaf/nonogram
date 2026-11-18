package app.materialnonogram.game

import app.materialnonogram.engine.Analyzer
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Mark.CROSSED
import app.materialnonogram.game.Mark.FILLED
import app.materialnonogram.game.Mark.UNKNOWN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 5×5 used by the model tests. Lines 0–4 are rows, 5–9 columns. */
internal val P5: Puzzle = Puzzle.of(
    "###..",
    "#.#.#",
    "#####",
    "..#..",
    "##.##",
)

internal fun c5(r: Int, c: Int) = r * 5 + c

internal fun game5(flag: Boolean = false, lives: Int = 3, autoCross: Boolean = false, contra: Boolean = false, check: Boolean = false, hints: Boolean = true) =
    Game("t", Deal(P5, Tier.EASY, 0L, 1), Rules.of(flag, lives, autoCross, contra, check, hints))

/** Collects every effect emitted synchronously (the flow is replay-free; tryEmit lands in the buffer). */
internal fun Game.drainFx(): List<Fx> = buildList { while (true) add(pollFx() ?: break) }

/** Replays the log's board-changing events from blank: must equal the real board, always. */
internal fun replay(g: Game): ByteArray {
    val b = ByteArray(g.deal.cells)
    for (e in g.events) if (e.changesBoard) b[e.cell] = e.value.toByte()
    return b
}

class GameTest {
    // ── Strokes ─────────────────────────────────────────────────────────────────────────────────

    @Test fun aTapFillsOneCellAndIsOneUndoUnit() {
        val g = game5()
        g.tap(c5(0, 0), Tool.FILL)
        assertEquals(FILLED, g.board[0])
        assertEquals(listOf(Ev.MARK), g.events.map { it.kind })
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(UNKNOWN, g.board[0])
        g.redo()
        assertEquals(FILLED, g.board[0])
    }

    // ── Flag mode: judged marks are final (user, 2026-11-17) ───────────────────────────────────────

    @Test fun flagModeATapOnAFillNeverClearsItAndThereIsNoUndo() {
        val g = game5(flag = true)
        g.tap(c5(0, 0), Tool.FILL)
        assertEquals(FILLED, g.board[0])
        assertFalse(g.canUndo)
        assertFalse(g.undo())
        g.tap(c5(0, 0), Tool.FILL)                    // used to clear it
        g.tap(c5(0, 0), Tool.CROSS)                   // used to cost a life
        assertEquals(FILLED, g.board[0])
        assertEquals(3, g.lives)
    }

    @Test fun flagModeADragFromAFillCarriesOnFillingTheUnknownCells() {
        val g = game5(flag = true)
        g.tap(c5(2, 0), Tool.FILL)
        g.beginStroke(c5(2, 0), Tool.FILL)
        g.extendStroke(c5(2, 4))
        g.endStroke()
        assertEquals(List(5) { FILLED }, (0..4).map { g.board[c5(2, it)] })
        assertEquals(3, g.lives)
    }

    @Test fun flagModeHoldingOnAFillSwitchesToolWithoutTouchingIt() {
        val g = game5(flag = true)
        g.tap(c5(0, 2), Tool.FILL)                    // r0 is ###..
        g.beginStroke(c5(0, 2), Tool.FILL)
        assertTrue(g.switchStrokeTool())
        assertEquals(FILLED, g.board[c5(0, 2)])
        g.extendStroke(c5(0, 4))
        g.endStroke()
        assertEquals(listOf(FILLED, CROSSED, CROSSED), (2..4).map { g.board[c5(0, it)] })
        assertEquals(3, g.lives)
    }

    @Test fun theCountAboveTheFingerIncludesMarksAlreadyThere() {
        val g = game5(flag = true)
        g.tap(c5(2, 1), Tool.FILL); g.tap(c5(2, 2), Tool.FILL)       // r2 is #####
        g.beginStroke(c5(2, 1), Tool.FILL)                             // from a fill: nothing to change yet
        g.extendStroke(c5(2, 2))
        assertEquals(2, g.stroke!!.count)                              // used to say 0
        g.extendStroke(c5(2, 4))
        assertEquals(4, g.stroke!!.count)                              // 1–4: two already filled, two new
        g.endStroke()
        val h = game5()
        h.beginStroke(c5(2, 0), Tool.FILL); h.extendStroke(c5(2, 3))
        assertEquals(4, h.stroke!!.count)                              // a plain stroke counts as before
    }

    @Test fun fastGivensStartCrossedAreLoggedAndNeverChange() {
        val g = Game("f", Deal(P5, Tier.FAST, 0L, 1), Rules.of(false))
        g.placeGivens()
        val given = (0 until 25).filter { g.isGiven(it) }
        assertTrue(given.isNotEmpty())
        assertTrue(given.all { g.board[it] == CROSSED && !P5.filled(it) })
        assertArrayEquals(g.board, replay(g))                        // the log starts from the givens
        val c = given.first()
        g.tap(c, Tool.CROSS); g.tap(c, Tool.FILL)                    // neither clears nor overwrites a given
        assertEquals(CROSSED, g.board[c])
        assertEquals(given.size, Report(g).decided)                   // they count as decided from the start
    }

    @Test fun aWhatIfStillAllowsUndoInFlagMode() {
        val g = game5(flag = true)
        g.beginWhatIf()
        g.tap(c5(0, 0), Tool.FILL)
        assertTrue(g.canUndo)
    }

    @Test fun aStrokeLocksToItsRowAndNeverSkipsACell() {
        val g = game5()
        g.beginStroke(c5(2, 0), Tool.FILL)
        g.extendStroke(c5(2, 4))                       // a flick: one pointer event four cells away
        assertEquals(StrokeView.AXIS_ROW, g.stroke!!.axis)
        assertEquals(5, g.stroke!!.count)
        g.endStroke()
        for (c in 0..4) assertEquals(FILLED, g.board[c5(2, c)])
        assertEquals(5, g.events.count { it.kind == Ev.MARK })
        assertEquals(1, g.events.map { it.x }.distinct().size)  // one stroke id
        assertNull(g.stroke)
    }

    @Test fun offAxisDriftIsClampedOntoTheLockedLine() {
        val g = game5()
        g.beginStroke(c5(0, 0), Tool.FILL)
        g.extendStroke(c5(0, 1))                       // second cell: locks to row 0
        g.extendStroke(3, 2)                           // finger drifts down: stays in row 0
        g.extendStroke(-4, 99)                         // off the grid: clamped to the last cell
        g.endStroke()
        for (c in 0..4) assertEquals(FILLED, g.board[c5(0, c)])
        for (r in 1..4) assertEquals(UNKNOWN, g.board[c5(r, 0)])
    }

    @Test fun theAxisIsTheLargerDeltaWhenTheSecondCellIsDiagonal() {
        val g = game5()
        g.beginStroke(c5(0, 0), Tool.FILL)
        g.extendStroke(c5(2, 1))
        assertEquals(StrokeView.AXIS_COL, g.stroke!!.axis)
        g.endStroke()
        assertEquals(listOf(c5(0, 0), c5(1, 0), c5(2, 0)), g.board.indices.filter { g.board[it] == FILLED })
    }

    @Test fun pullingBackRevertsTheOvershootAndCrossingTheStartSwapsSides() {
        val g = game5()
        g.beginStroke(c5(2, 2), Tool.FILL)
        g.extendStroke(c5(2, 4))
        g.extendStroke(c5(2, 3))
        assertEquals(UNKNOWN, g.board[c5(2, 4)])
        assertEquals(2, g.stroke!!.count)
        g.extendStroke(c5(2, 0))                       // past the start to the other side
        assertEquals(UNKNOWN, g.board[c5(2, 3)])
        assertEquals(3, g.stroke!!.count)
        g.endStroke()
        assertEquals(listOf(10, 11, 12), g.board.indices.filter { g.board[it] == FILLED })
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun aStrokeOnlyChangesCellsInItsStartCellsState() {
        val g = game5()
        g.tap(c5(2, 2), Tool.CROSS)
        // Fill from an unknown cell passes over the cross.
        g.beginStroke(c5(2, 0), Tool.FILL); g.extendStroke(c5(2, 4)); g.endStroke()
        assertEquals(CROSSED, g.board[c5(2, 2)])
        assertEquals(4, g.board.count { it == FILLED })
        // Fill from a filled cell clears filled cells only.
        g.beginStroke(c5(2, 4), Tool.FILL); g.extendStroke(c5(2, 0)); g.endStroke()
        assertEquals(0, g.board.count { it == FILLED })
        assertEquals(CROSSED, g.board[c5(2, 2)])
        // Cross from a filled cell turns fills into crosses; unknowns untouched.
        g.tap(c5(0, 0), Tool.FILL); g.tap(c5(0, 1), Tool.FILL)
        g.beginStroke(c5(0, 0), Tool.CROSS); g.extendStroke(c5(0, 4)); g.endStroke()
        assertEquals(CROSSED, g.board[c5(0, 0)]); assertEquals(CROSSED, g.board[c5(0, 1)])
        assertEquals(UNKNOWN, g.board[c5(0, 2)])
        // Fill from a crossed cell turns crosses into fills.
        g.beginStroke(c5(0, 1), Tool.FILL); g.extendStroke(c5(0, 0)); g.endStroke()
        assertEquals(FILLED, g.board[c5(0, 0)]); assertEquals(FILLED, g.board[c5(0, 1)])
        // Cross from a crossed cell erases crosses.
        g.tap(c5(2, 2), Tool.CROSS)
        assertEquals(UNKNOWN, g.board[c5(2, 2)])
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun undoAndRedoAreUnlimitedAndANewStrokeClearsRedo() {
        val g = game5()
        for (c in 0..4) g.tap(c5(2, c), Tool.FILL)
        repeat(5) { g.undo() }
        assertFalse(g.canUndo)
        assertEquals(0, g.board.count { it != UNKNOWN })
        repeat(3) { g.redo() }
        assertEquals(3, g.board.count { it == FILLED })
        assertTrue(g.canRedo)
        g.tap(c5(0, 0), Tool.CROSS)
        assertFalse(g.canRedo)
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun aSecondFingerCancelsTheStrokeAndLeavesNoTrace() {
        val g = game5()
        g.tap(c5(0, 0), Tool.FILL)
        val before = g.events.size
        g.beginStroke(c5(2, 0), Tool.FILL); g.extendStroke(c5(2, 3))
        g.cancelStroke()
        assertEquals(0, (0..4).count { g.board[c5(2, it)] != UNKNOWN })
        assertEquals(before, g.events.size)
        g.undo()                                       // the undo unit is the earlier tap, not the cancelled stroke
        assertEquals(UNKNOWN, g.board[0])
        assertTrue(g.drainFx().contains(Fx.StrokeCancelled))
    }

    @Test fun longPressSwitchesTheToolOnlyBeforeLeavingTheStartCell() {
        val g = game5()
        g.beginStroke(c5(0, 0), Tool.FILL)
        assertTrue(g.stroke!!.pending)
        assertEquals(UNKNOWN, g.board[0])
        assertEquals(FILLED, g.shown(0))               // the press preview
        assertTrue(g.switchStrokeTool())
        assertEquals(Tool.CROSS, g.stroke!!.tool)
        g.extendStroke(c5(0, 2))
        assertFalse(g.switchStrokeTool())              // left the start cell: too late
        g.endStroke()
        assertEquals(listOf(CROSSED, CROSSED, CROSSED), (0..2).map { g.board[it] })
        assertEquals(Tool.FILL, g.tool)                // the selected tool button is unchanged
    }

    @Test fun anImmediateStartAppliesOnDownAndSwitchingReappliesIt() {
        val g = game5()
        g.beginStroke(c5(0, 0), Tool.FILL, deferStart = false)
        assertEquals(FILLED, g.board[0])
        g.switchStrokeTool()
        assertEquals(CROSSED, g.board[0])
        g.endStroke()
        assertEquals(listOf(CROSSED.toInt()), g.events.filter { it.kind == Ev.MARK }.map { it.value })
    }

    @Test fun effectsOfAFlick() {
        val g = game5()
        g.drainFx()
        g.beginStroke(c5(2, 0), Tool.FILL); g.extendStroke(c5(2, 3)); g.endStroke()
        val fx = g.drainFx()
        assertEquals(Fx.Pressed(c5(2, 0), Tool.FILL, FILLED), fx[0])
        assertEquals(listOf(2, 3, 4), fx.filterIsInstance<Fx.Marked>().map { it.count })
    }

    // ── Mistakes (D4) ───────────────────────────────────────────────────────────────────────────

    @Test fun flagModeStopsAStrokeAtItsFirstWrongCellForOneLife() {
        val g = game5(flag = true)
        g.beginStroke(c5(1, 0), Tool.FILL)
        g.extendStroke(c5(1, 4))                       // r1 = #.#.#: c1 is wrong
        assertTrue(g.stroke!!.stopped)
        g.extendStroke(c5(1, 0)); g.extendStroke(c5(1, 4))  // ignored once stopped
        g.endStroke()
        assertEquals(listOf(FILLED, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN), (0..4).map { g.board[c5(1, it)] })
        assertEquals(2, g.lives)
        assertEquals(listOf(Ev.MARK, Ev.MISTAKE), g.events.map { it.kind })
        assertEquals(1, g.drainFx().count { it is Fx.Mistake })
        assertFalse(g.canUndo)                          // flag mode: the fill is judged and final
        assertTrue(Report(g).strokes.single().struck)
    }

    @Test fun flagModeASwipeThatFinishesItsRowStopsThereForFree() {
        val g = game5(flag = true)
        g.beginStroke(c5(0, 0), Tool.FILL)
        g.extendStroke(c5(0, 4))                       // r0 = ###..: complete at c2, then c3 and c4 are passed over
        g.endStroke()
        assertEquals(listOf(FILLED, FILLED, FILLED, UNKNOWN, UNKNOWN), (0..4).map { g.board[c5(0, it)] })
        assertEquals(3, g.lives)
        assertTrue(g.events.none { it.kind == Ev.MISTAKE })
        g.tap(c5(0, 4), Tool.FILL)                     // a tap in the finished row takes no fill either
        assertEquals(UNKNOWN, g.board[c5(0, 4)]); assertEquals(3, g.lives)
        g.tap(c5(0, 4), Tool.CROSS)                    // crosses are still welcome
        assertEquals(CROSSED, g.board[c5(0, 4)])
    }

    @Test fun pausingDropsAPendingStartInsteadOfJudgingIt() {
        val g = game5(flag = true)
        g.beginStroke(c5(0, 3), Tool.FILL)            // wrong, but the finger never left or lifted
        g.pause(true)
        assertEquals(3, g.lives)
        assertEquals(UNKNOWN, g.board[c5(0, 3)])
        assertNull(g.stroke)
    }

    @Test fun aWrongCrossCostsALifeAndARightFillIsFinal() {
        val g = game5(flag = true)
        g.tap(c5(0, 0), Tool.CROSS)
        assertEquals(UNKNOWN, g.board[0]); assertEquals(2, g.lives)
        g.tap(c5(0, 0), Tool.FILL)
        g.tap(c5(0, 0), Tool.FILL)                     // used to clear it; flag-mode marks are final
        assertEquals(FILLED, g.board[0]); assertEquals(2, g.lives)
    }

    @Test fun aWrongPendingStartSwitchedByLongPressIsNoMistake() {
        val g = game5(flag = true)
        g.beginStroke(c5(0, 3), Tool.FILL)             // empty in the solution
        g.switchStrokeTool()
        g.endStroke()
        assertEquals(CROSSED, g.board[c5(0, 3)])
        assertEquals(3, g.lives)
    }

    @Test fun theLastLifeEndsTheRunAndInputStops() {
        val g = game5(flag = true, lives = 1)
        g.tap(c5(0, 3), Tool.FILL)
        assertEquals(Status.LOST, g.status)
        assertTrue(g.drainFx().contains(Fx.Lost))
        assertFalse(g.beginStroke(0, Tool.FILL))
        assertEquals(UNKNOWN, g.board[0])
    }

    @Test fun dontFlagLetsWrongMarksStandAndAFullWrongGridSaysSo() {
        val g = game5()
        g.tap(c5(0, 3), Tool.FILL)
        assertEquals(FILLED, g.board[c5(0, 3)])
        assertEquals(0, g.lives)
        for (i in 0 until 25) if (g.board[i] == UNKNOWN) g.tap(i, if (P5.filled(i)) Tool.FILL else Tool.CROSS)
        assertEquals(Status.PLAYING, g.status)
        assertTrue(g.drainFx().contains(Fx.FilledWrong))
        g.tap(c5(0, 3), Tool.CROSS)                    // fills → cross (start-cell rule)
        assertEquals(Status.WON, g.status)
    }

    // ── Lines, auto-cross, solving ──────────────────────────────────────────────────────────────

    @Test fun autoCrossFinishesALineAsAutoAndIsPartOfTheStrokesUndo() {
        val g = game5(autoCross = true)
        g.beginStroke(c5(0, 0), Tool.FILL); g.extendStroke(c5(0, 2)); g.endStroke()
        assertTrue(g.satisfied[0])
        assertEquals(CROSSED, g.board[c5(0, 3)]); assertEquals(CROSSED, g.board[c5(0, 4)])
        assertTrue(g.auto[c5(0, 3)])
        val autos = g.events.filter { it.kind == Ev.AUTO }
        assertEquals(listOf(c5(0, 3), c5(0, 4)), autos.map { it.cell })
        assertEquals(g.events.first().x, autos.first().x)
        val fx = g.drainFx()
        assertTrue(fx.any { it is Fx.LinesDone && 0 in it.lines })
        assertTrue(fx.any { it is Fx.AutoCrossed && it.cells.size == 2 })
        g.undo()
        assertEquals(0, g.board.count { it != UNKNOWN })
    }

    @Test fun breakingAFinishedLineTakesItsAutoCrossesBack() {
        val g = game5(autoCross = true)
        g.beginStroke(c5(0, 0), Tool.FILL); g.extendStroke(c5(0, 2)); g.endStroke()
        g.tap(c5(0, 1), Tool.FILL)                      // clear one fill: row 0 no longer satisfied
        assertFalse(g.satisfied[0])
        assertEquals(UNKNOWN, g.board[c5(0, 3)])
        assertEquals(Ev.AUTO, g.events.last().kind)
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun autoCrossOffLeavesLinesAlone() {
        val g = game5(autoCross = false)
        g.beginStroke(c5(0, 0), Tool.FILL); g.extendStroke(c5(0, 2)); g.endStroke()
        assertTrue(g.satisfied[0])
        assertEquals(UNKNOWN, g.board[c5(0, 3)])
    }

    @Test fun solvedWhenTheFilledCellsAreTheSolutionWhateverTheCrosses() {
        val g = game5(flag = true)
        var last = -1
        for (r in 0..4) for (c in 0..4) if (P5.filled(r, c)) { g.tick(500); g.tap(c5(r, c), Tool.FILL); last = c5(r, c) }
        assertEquals(Status.WON, g.status)
        val won = g.drainFx().filterIsInstance<Fx.Won>().single()
        assertEquals(last, won.cell)
        assertTrue(won.lines.containsAll(listOf(4, 5 + 4)))  // r4 and c4 finished by the last fill
        assertFalse(g.beginStroke(c5(0, 3), Tool.CROSS))
        assertFalse(g.canUndo)
    }

    @Test fun theContradictionFlagLightsOnlyFinishedRunsThatBreakTheClue() {
        val g = game5(contra = true)
        assertEquals(2, g.band)
        g.tap(c5(3, 0), Tool.FILL)                    // r3 = ..#.. : a lone run, still open
        assertFalse(g.contradicted[3])
        g.tap(c5(3, 1), Tool.CROSS)                   // closes the run "1" at c0: fits clue [1] in order
        assertFalse(g.contradicted[3])
        g.tap(c5(3, 3), Tool.FILL); g.tap(c5(3, 4), Tool.CROSS); g.tap(c5(3, 2), Tool.CROSS)
        assertTrue(g.contradicted[3])                 // two complete runs against a one-number clue
        assertTrue(g.drainFx().any { it is Fx.Contradiction && 3 in it.lines })
        assertEquals(1, g.events.count { it.kind == Ev.CONTRADICTION })
    }

    @Test fun theContradictionFlagIsInertInFlagMode() {
        val g = game5(flag = true, contra = true)
        assertFalse(g.contradictionFlag)
        assertEquals(0, g.band)
    }

    @Test fun halfwayIsLoggedOnceAtHalfTheCellsMarked() {
        val g = game5()
        for (i in 0 until 25) {
            g.tap(i, if (P5.filled(i)) Tool.FILL else Tool.CROSS)
            assertEquals(if (i + 1 >= 13) 1 else 0, g.events.count { it.kind == Ev.HALFWAY })
            if (g.status != Status.PLAYING) break
        }
    }

    // ── Aids and oracles ────────────────────────────────────────────────────────────────────────

    @Test fun checkIsAYesNoAidOnlyInDontFlagMode() {
        assertNull(game5(flag = true, check = true).check())
        assertNull(game5(check = false).check())
        val g = game5(check = true)
        assertFalse(g.assisted)
        assertEquals(true, g.check())
        assertTrue(g.assisted)                        // any check is an oracle event, even an empty one
        g.tap(c5(0, 3), Tool.FILL)
        assertEquals(false, g.check())
        assertEquals(listOf(1, 0), g.events.filter { it.kind == Ev.CHECK }.map { it.value })
    }

    @Test fun aidsChangedMidRunRaiseTheBandAndNeverLowerIt() {
        val g = game5(autoCross = false)
        assertEquals(0, g.band)
        g.beginStroke(c5(0, 0), Tool.FILL); g.extendStroke(c5(0, 2)); g.endStroke()
        g.setAids(autoCross = true, contradictionFlag = false)
        assertEquals(1, g.band)
        assertEquals(CROSSED, g.board[c5(0, 3)])      // a finished line is crossed as soon as the aid is on
        g.setAids(autoCross = false, contradictionFlag = false)
        assertEquals(1, g.band)
        g.setAids(autoCross = false, contradictionFlag = true)
        assertEquals(2, g.band)
        assertEquals(listOf(1, 2), g.events.filter { it.kind == Ev.AID }.map { it.value })
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun hintsRunThreeStagesThenPlaceTheCellAsAHint() {
        val g = game5()
        val h = Analyzer(P5).hint(g.realBoard())!!
        g.showHint(h)
        assertEquals(1, g.hint!!.stage)
        assertTrue(g.advanceHint()); assertTrue(g.advanceHint())
        assertEquals(3, g.hint!!.stage)
        assertFalse(g.advanceHint())
        assertTrue(g.applyHint())
        assertEquals(h.value, g.board[h.cell])
        assertNull(g.hint)
        assertEquals(Ev.HINT_MARK, g.events.last { it.changesBoard }.kind)
        assertEquals(listOf(1, 2, 3), g.events.filter { it.kind == Ev.HINT }.map { it.value })
        assertTrue(g.assisted)
        g.undo()
        assertEquals(UNKNOWN, g.board[h.cell])
    }

    @Test fun hintsOffMeansNoHint() {
        val g = game5(hints = false)
        g.showHint(Analyzer(P5).hint(g.realBoard())!!)
        assertNull(g.hint)
        assertFalse(g.assisted)
    }

    @Test fun aHintDisappearsOnceItsCellIsDecided() {
        val g = game5()
        val h = Analyzer(P5).hint(g.realBoard())!!
        g.showHint(h)
        g.tap(h.cell, if (h.value == FILLED) Tool.FILL else Tool.CROSS)
        assertNull(g.hint)
    }

    // ── What-if ─────────────────────────────────────────────────────────────────────────────────

    @Test fun whatIfMarksAreProvisionalUntilCommitted() {
        val g = game5(flag = true)
        g.tap(c5(2, 0), Tool.FILL)
        assertTrue(g.beginWhatIf())
        g.beginStroke(c5(2, 1), Tool.FILL); g.extendStroke(c5(2, 4)); g.endStroke()
        assertEquals(5, (0..4).count { g.board[c5(2, it)] == FILLED })
        assertEquals(1, (0..4).count { g.realBoard()[c5(2, it)] == FILLED })
        assertTrue(g.provisional!![c5(2, 3)])
        assertEquals(1, g.events.count { it.kind == Ev.MARK })
        assertTrue(g.commitWhatIf())
        assertFalse(g.inWhatIf)
        assertEquals(5, g.events.count { it.kind == Ev.MARK })
        assertArrayEquals(g.board, replay(g))
        assertFalse(g.undo())                           // committed in flag mode: judged, so final
        assertEquals(5, (0..4).count { g.board[c5(2, it)] == FILLED })
    }

    @Test fun whatIfNeverChargesALifeUntilAWrongHypothesisIsCommitted() {
        val g = game5(flag = true)
        g.beginWhatIf()
        g.tap(c5(0, 3), Tool.FILL)                    // wrong, but provisional
        g.tap(c5(0, 0), Tool.FILL)                    // right
        assertEquals(3, g.lives)
        assertEquals(FILLED, g.board[c5(0, 3)])
        assertFalse(g.commitWhatIf())                 // refused as a whole, one life
        assertEquals(2, g.lives)
        assertTrue(g.inWhatIf)
        assertEquals(c5(0, 3), g.events.last { it.kind == Ev.MISTAKE }.cell)
        g.discardWhatIf()
        assertEquals(0, g.board.count { it != UNKNOWN })
        assertArrayEquals(g.board, replay(g))
    }

    @Test fun undoInsideWhatIfStopsAtTheLayer() {
        val g = game5()
        g.tap(0, Tool.FILL)
        g.beginWhatIf()
        g.tap(1, Tool.FILL)
        g.undo()
        assertFalse(g.canUndo)
        assertEquals(FILLED, g.board[0])
        g.discardWhatIf()
        assertTrue(g.canUndo)
    }

    // ── Time and the log ────────────────────────────────────────────────────────────────────────

    @Test fun pauseStopsTheClockAndEndsAStroke() {
        val g = game5()
        g.tick(1000)
        g.beginStroke(0, Tool.FILL); g.extendStroke(2)
        g.pause(true)
        assertNull(g.stroke)
        assertEquals(3, g.board.count { it == FILLED })
        g.tick(5000)
        assertEquals(1000, g.elapsed)
        g.pause(false)
        g.tick(10)
        assertEquals(1010, g.elapsed)
        assertEquals(listOf(Ev.PAUSE, Ev.RESUME), g.events.filter { it.kind == Ev.PAUSE || it.kind == Ev.RESUME }.map { it.kind })
    }

    @Test fun marksAreStampedWithTheGameTimeTheFingerEnteredThem() {
        val g = game5()
        g.tick(100)
        g.beginStroke(c5(2, 0), Tool.FILL)
        g.tick(50); g.extendStroke(c5(2, 1))
        g.tick(50); g.extendStroke(c5(2, 2))
        g.tick(400); g.endStroke()
        assertEquals(listOf(100L, 150L, 200L), g.events.filter { it.kind == Ev.MARK }.map { it.t })
    }

    /** Random play of every verb: the log must always replay to the real board. */
    @Test fun theLogAlwaysReplaysToTheBoard() {
        val rnd = java.util.Random(7)
        repeat(30) { round ->
            val g = game5(flag = round % 2 == 0, autoCross = round % 3 != 0, contra = round % 5 == 0)
            repeat(120) {
                if (g.status != Status.PLAYING) return@repeat
                when (rnd.nextInt(12)) {
                    0 -> g.undo()
                    1 -> g.redo()
                    2 -> if (g.inWhatIf) g.discardWhatIf() else g.beginWhatIf()
                    3 -> if (g.inWhatIf) g.commitWhatIf()
                    4 -> { g.beginStroke(rnd.nextInt(25), Tool.entries[rnd.nextInt(2)]); g.extendStroke(rnd.nextInt(5), rnd.nextInt(5)); g.cancelStroke() }
                    else -> {
                        g.beginStroke(rnd.nextInt(25), Tool.entries[rnd.nextInt(2)], deferStart = rnd.nextBoolean())
                        if (rnd.nextBoolean()) g.switchStrokeTool()
                        repeat(rnd.nextInt(4)) { g.extendStroke(rnd.nextInt(7) - 1, rnd.nextInt(7) - 1) }
                        g.endStroke()
                    }
                }
                g.tick(100)
                assertArrayEquals("round $round", g.realBoard(), replay(g))
            }
        }
    }

    @Test fun reportRungIsAvailableForHintEvents() {
        val g = game5()
        val h = Analyzer(P5).hint(g.realBoard())!!
        g.showHint(h)
        assertEquals(h.rung, Rung.entries[g.events.last().x])
    }
}
