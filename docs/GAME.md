# Nonogram game model (Phase 2)

`app/src/main/java/app/materialnonogram/game/` and `data/NonoStore.kt`. No UI. Built from
`nonogram-mockups/BUILD-PLAN.md` §3, §4 "The stroke" and §5.1–5.3, on Sudoku's game layer (now
`legacy/game/`) and on the engine (`docs/ENGINE.md`). Tests: `app/src/test/java/app/materialnonogram/game/`
and `…/data/NonoStoreTest.kt` (56 tests).

| File | What |
|---|---|
| `game/Model.kt` | `Mark`, `Tool`, `Status`, `Source`, `Origin`, `Deal`, `Rules`, `bandOf`, `Ev` (log codes), `Fx` (effects), `HintView`, `StrokeView` |
| `game/Game.kt` | The game: strokes, mistakes, auto-cross, lines, hints, check, what-if, undo/redo, time |
| `game/Report.kt` | `Rater`/`EngineRater`, `Report` and its records, `RecordKey`, `accolades`, `halfwayLine`, clocks |
| `game/Session.kt` | `PuzzleShop` (prefetch, Master in the background), `findHint`, `buildReport` |
| `data/NonoStore.kt` | One JSON file per game in `filesDir/nonograms/`; `NonoJson` is the format |

The legacy Sudoku `GameStore`, `AppModel` and `ui/` are untouched and still drive the app until the
Phase 4 switch-over.

## Cells, deals, rules

- Boards are `ByteArray(w * h)`, row-major, `Mark.UNKNOWN 0 / FILLED 1 / CROSSED 2`: the engine's
  `Cell` bytes, so a board goes straight into `Analyzer` and `LineCheck`. Lines are rows `0 until h`,
  then columns `h until h + w`.
- `Deal(puzzle, tier, seed, generatorVersion, origin)`: **the puzzle is its bitmap** (D10). The clues,
  `shareCode` and `sgtId` are derived. Build one with `Deal.of(generated)` or `Deal.imported(importerResult)`.
  An import's tier is read off its grade (`Deal.tierOf`: P1 steps → Expert/Master, else the generator's
  fill bands) and its seed is −1.
- `Rules.of(flagMistakes, lives = 3, autoCross = true, contradictionFlag = false, checkButton = false, hints = true)`.
  The net (`flagMistakes`, `lives` ∈ {1, 3}, or 0 when not flagging) and the oracles (`checkButton`,
  `hints`) are **locked for the run**. `contradictionFlag` and `checkButton` only exist in don't-flag
  mode; `Rules.of` drops them in flag mode. `autoCross` and `contradictionFlag` are starting values:
  `Game.setAids` may change them mid-run.
- **Aid band** (§5.1): `0` none, `1` auto-cross (labour), `2` contradiction flag (a logical check). The
  run keeps the highest band it reached, and each raise is logged.

## The API the UI calls

Everything is on the main thread unless marked. Every `var` is Compose snapshot state; published arrays
are fresh copies that are never mutated, so a background job may read them.

```kotlin
class Game(id: String, deal: Deal, rules: Rules, …restore params…)

// State
val board: ByteArray            // as shown (the what-if layer included)
val auto: BooleanArray          // crosses placed by auto-cross
val satisfied: BooleanArray     // per line: grey the clue (colour + GRAD, never wght)
val contradicted: BooleanArray  // per line: the contradiction flag (all false unless it is on)
val stroke: StrokeView?         // start, tool, from, to, axis, count (the bubble), pending, stopped
val lives: Int; val status: Status; val paused: Boolean; val tool: Tool
val hint: HintView?             // stage 1..3 + the engine's Hint
val inWhatIf: Boolean; val provisional: BooleanArray?   // cells to tint
val autoCross: Boolean; val contradictionFlag: Boolean; val band: Int
val elapsed: Long; val revision: Int    // revision bumps on every change worth saving
val canUndo: Boolean; val canRedo: Boolean
val mistakes: Int; val hinted: Boolean; val checked: Boolean; val assisted: Boolean
val fx: SharedFlow<Fx>
fun shown(cell: Int): Byte      // board[cell], with a pending start drawn as its target
fun realBoard(): ByteArray      // the board under any what-if layer (copy)
fun solutionAt(cell: Int): Byte

// Input (BUILD-PLAN §4 "The stroke")
fun selectTool(t: Tool)
fun beginStroke(cell: Int, tool: Tool = this.tool, deferStart: Boolean = true): Boolean  // on down
fun extendStroke(cell: Int)                         // on every move (and each historical sample)
fun extendStroke(row: Int, col: Int)                // the same, clamping off-grid positions
fun switchStrokeTool(): Boolean                     // long-press timeout, before leaving the start cell
fun endStroke()                                     // on lift
fun cancelStroke()                                  // a second finger landed
fun tap(cell: Int, tool: Tool = this.tool): Boolean // begin + end
fun undo(): Boolean; fun redo(): Boolean

// Time
fun tick(dtMs: Long); fun pause(p: Boolean)

// Aids and oracles
fun setAids(autoCross: Boolean, contradictionFlag: Boolean)
fun check(): Boolean?                               // null when unavailable
fun showHint(h: Hint); fun advanceHint(): Boolean; fun applyHint(): Boolean; fun clearHint()

// What-if layer (model only)
fun beginWhatIf(): Boolean; fun commitWhatIf(): Boolean; fun discardWhatIf()

// Off the main thread (game/Session.kt)
class PuzzleShop(scope: CoroutineScope, dispatcher = Dispatchers.Default, seeds: () -> Long = SecureRandom) {
    fun prefetch(size: Size, tier: Tier); fun prefetchMasters(sizes: Collection<Size> = Size.entries)
    fun isReady(size: Size, tier: Tier): Boolean; suspend fun take(size: Size, tier: Tier): Deal; fun clear()
}
suspend fun findHint(game: Game, dispatcher = Dispatchers.Default): Hint?
suspend fun buildReport(game: Game, dispatcher = Dispatchers.Default): Report

// Records and results (game/Report.kt)
class Report(game: Game, rater: Rater? = null, isCancelled: () -> Boolean = { false })
fun Game.net(): Int; fun Game.recordKey(): RecordKey; fun Game.halfwayTime(): Long?
fun accolades(r: Report, prior: List<Game>): List<Accolade>
fun halfwayLine(g: Game, prior: List<Game>, timerShown: Boolean): Pair<String, String?>
fun clock(ms: Long): String; fun clockMs(ms: Long): Pair<String, String>; fun signedDelta(ms: Long): String

// Persistence (data/NonoStore.kt)
class NonoStore(dir: File) { constructor(context: Context)
    fun save(g: Game); fun load(id: String): Game?; fun all(): List<Game>; fun delete(id: String)
    fun loadCurrent(): Game?; fun setCurrent(id: String?); fun exportAll(): String }
object NonoJson { fun encode(g: Game): String; fun decode(text: String): Game }
```

### Driving a stroke from `pointerInput`

Hit-test the pointer to `(row, col)` on the measured grid and pass it straight in; the model does the
rest. On down: `beginStroke(cell, game.tool, deferStart = longPressEnabled)`. On every move: call
`extendStroke(row, col)` for each `change.historical` sample and then the current position. When the
long-press timeout fires and the finger is still on the start cell: `switchStrokeTool()` (it returns
false once the finger has left the cell). On up: `endStroke()`. When a second pointer goes down, or the
gesture is cancelled: `cancelStroke()`.

## Stroke semantics

- **Start-cell rule (02 C3).** The stroke's `from` is its start cell's state, and it changes only
  cells in that state. `to` is the tool's state, or `UNKNOWN` if `from` already is the tool's state:

  | Start cell | Fill | Cross |
  |---|---|---|
  | unknown | fills unknowns | crosses unknowns |
  | filled | clears fills | turns fills into crosses |
  | crossed | turns crosses into fills | clears crosses |

  So passing over other marks never destroys them, and overwriting only happens when you start on the
  kind you mean to change.
- **Line lock** from the second cell entered. The axis is the larger of |Δcol| and |Δrow| from the
  start, with rows winning ties. After that, sideways drift is projected onto the line, and positions
  off the grid are clamped to its edge.
- **Never skips a cell.** Each `extendStroke` walks every cell between the furthest point already walked
  and the target, so one pointer event from a flick fills the whole run.
- **Pulling back reverts.** The stroke covers the range from its start to the finger. Pulling back
  reverts the overshoot (`Fx.Retracted`). Crossing over the start reverts one side and walks the other.
- **Pending start.** With `deferStart`, the start cell is only a preview (`shown()`,
  `StrokeView.pending`) until the finger leaves it, lifts, or a long-press switches the tool. This
  means a long-press never costs a life for the tool it didn't mean. Pausing drops a pending start
  instead of judging it. Without `deferStart`, the cell is applied on down, and `switchStrokeTool`
  reverts it and applies the other tool.
- **One undo unit per stroke**, auto-crosses included. Undo and redo are unlimited within a session;
  the history is not persisted, as in Sudoku. A new stroke clears redo. Winning clears both stacks.
- **Live count:** `StrokeView.count` is the cells the stroke has changed, the pending start included.
- **Logging:** the marks of a stroke are logged when it ends (or is stopped), each stamped with the
  game time its cell was entered, in entry order. A cancelled stroke leaves nothing in the log.

## Mistakes (D4) and lines

- **Flag mode.** A mark that disagrees with the solution is refused: a wrong fill **or** a wrong cross.
  Clearing is never wrong.
  - The stroke stops at the first refused cell. The cells before it stand and are committed as the
    stroke, and the refusal costs **one** life (`Ev.MISTAKE`, `Fx.Mistake`).
  - At 0 lives the status becomes `LOST` (`Fx.Lost`), and input stops.
  - If the cells before the refusal solved the puzzle, the run is won and nothing is charged.
- **Don't-flag mode.** Marks are free and no lives are kept. When every cell is marked and the filled
  cells are not the solution, `Fx.FilledWrong` is emitted.
  - With the contradiction flag on, `contradicted[line]` follows `LineCheck.contradicts` (Tatham's weak
    check: complete runs only). A line that newly contradicts emits `Fx.Contradiction` and logs
    `Ev.CONTRADICTION`.
  - `check()` answers yes or no, is logged, and makes the run assisted.
- **Satisfied lines.** `satisfied[line]` means the line's filled cells form its clue exactly. Clues grey
  per line, never per number.
- **Auto-cross.** After each commit, every satisfied line's unknown cells become crosses, logged as
  `Ev.AUTO` with the triggering stroke's id and flagged in `auto`. An auto-cross whose row and column
  are both no longer satisfied (the player cleared a fill) goes back to unknown, also as `Ev.AUTO`.
  Turning the aid on mid-run crosses every already-finished line at once.
- **Solved** when the filled cells equal the solution; crosses don't matter. This emits
  `Fx.Won(lines, cell)` with the lines the final commit completed and where it ended.
- **Halfway** is reached when half the cells are marked. It is counted, never checked, and logged once
  (`Ev.HALFWAY`, `Fx.Halfway`).

## Hints, what-if

- **Hints.** `findHint(game)` runs `Analyzer.hint` on the real board, off the main thread. Pass the
  result to `showHint` for stage 1, then `advanceHint` to stages 2 and 3; each stage logs `Ev.HINT`
  with the stage and the rung.
  - `applyHint()` places the cell (`Ev.HINT_MARK`, its own undo unit). That mark is never credited.
  - The hint clears once its cell holds its value.
  - There are no hints while `rules.hints` is false, or inside a what-if layer.
- **What-if.** `beginWhatIf()` opens a provisional layer; `provisional` marks the cells it changed.
  - Inside the layer nothing is judged, every change is logged as `Ev.PROVISIONAL` (which never touches
    the real board), and undo stops at the layer's start.
  - `commitWhatIf()` makes the layer real as one stroke and one undo unit (`Ev.COMMIT`, then `MARK`s
    and `AUTO`s).
  - In flag mode, a layer holding any wrong mark is refused as a whole. It costs one life, reported at
    the first wrong mark made, and the layer stays open.
  - `discardWhatIf()` brings the real board back exactly as it was.

## Effects (`Fx`) and the haptics table (BUILD-PLAN §4)

| Fx | When | Suggested haptic / effect |
|---|---|---|
| `Pressed(cell, tool, to)` | finger down | press state; `select()` if `to` is a fill, `lowTick()` if a cross |
| `Marked(cell, to, count)` | each further cell the stroke changes | the same tick (rate-limit on flicks); cell springs / cross draws in; bubble = `count` |
| `Retracted(cell, count)` | the finger pulled back | update the bubble; optional light tick |
| `ToolSwitched(tool, longPress)` | tool button or long-press | `mode()`; the tool button morphs |
| `StrokeCancelled` | second finger | none (the board reverts) |
| `LinesDone(lines, cell)` | a commit satisfied lines | `unitDone(count = line length)`; wave along each line from `cell`; clue greys |
| `AutoCrossed(cells)` | auto-cross placed crosses | crosses draw in (quiet) |
| `Mistake(cell, value, livesLeft)` | flag mode refusal | `mistake()` + `heartCrack()`; shake |
| `Lost` | out of lives | `lost()`; the lost sequence |
| `Contradiction(lines)` | lines newly contradict | `lowTick(0.8f)`; the line shakes "no", the clue blinks |
| `Undone` / `Redone` | undo / redo | `undo()` |
| `HintShown(view)` | each hint stage | `hint()`; the line breathes, then the cell |
| `Checked(ok)` | check | a yes or no beat |
| `Halfway` | half the cells marked | `halfway()`; the chip's halfway message (`halfwayLine`) |
| `Won(lines, cell)` | solved | `won()`; crosses fade, the pattern settles |
| `FilledWrong` | don't-flag, full and wrong | the Sudoku "filled wrong" cue |
| `WhatIf(phase)` | BEGIN / COMMIT / DISCARD / REFUSED | layer tint in / out (REFUSED comes with a `Mistake`) |

`Fx` is a `SharedFlow` with a 64-slot buffer that drops the oldest effect when it overflows, so
effects emitted while nobody collects are lost. That is fine: the board state is the truth.

## The event log

`Ev(t, kind, cell, value, x)`. `t` is game time in ms, excluding pauses.

Replaying `MARK`, `HINT_MARK`, `AUTO` and `REVERT` in order from a blank board reproduces the real board
exactly. A randomised test checks this after every verb, in both modes and with what-if layers.

| Code | Kind | cell | value | x |
|---|---|---|---|---|
| `M` | player mark (a clear is value 0) | cell | new state | stroke id |
| `K` | mark placed by a hint | cell | new state | stroke id |
| `A` | auto-cross (2) / auto-uncross (0) | cell | new state | triggering stroke id, −1 if none |
| `V` | cell restored by undo/redo | cell | new state | −1 |
| `m` | any change inside a what-if layer | cell | new state | stroke id |
| `X` | refused mark (flag mode) | cell | refused state | stroke id |
| `H` | hint stage shown | hint cell | stage 1–3 | rung ordinal |
| `C` | check | −1 | 1 no wrong marks, 0 some | |
| `L` | a line newly contradicts | line | | |
| `a` | aid band raised mid-run | | new band | |
| `U` `R` | undo / redo (followed by `V`s) | | | |
| `p` `r` | pause / resume | | | |
| `h` | halfway shown | | | |
| `w` `c` `d` | what-if begin / commit / discard | | | |

What the log does **not** hold: effort, rungs, guesses and strike kinds. They are recomputed from the
board each mark was made on, so a change to `effort-v1` or to the grader reaches every past game.

## Report (`game/Report.kt`)

`Report(game, rater)` replays the log. It needs nothing stored beyond the log and the bitmap.

With `EngineRater(puzzle)` it rates every first-correct and every wrong player mark on the board the
player had: `Analyzer.analyzeMark`, plus the forcing line for line rungs. `buildReport(game)` does this
off the main thread, and cancelling it stops the rating (`complete` = false) instead of throwing.

Cost, measured on the JVM (M4) for a solved 20×20 Master:

| Solve order | Marks | Time | Look-ahead marks |
|---|---|---|---|
| Logical | 400 | 52 ms | 6 |
| Row-major (the worst case) | 392 | 498 ms | 123 (P1 + P2) |

| Field | Meaning |
|---|---|
| `marks: List<MarkRecord>` | every mark that set a cell: t, cell, value, source, stroke, correct, `first` (first time the cell held its solution value), `rating`, `guess`; `credited` / `effort10` |
| `strikes: List<Strike>` | refused marks and wrong marks left standing: t, cell, value, `refused`, `kind` (SLIP / GUESS / WRONG), rung, `decided` then, `board` then |
| `strokes: List<StrokeRecord>` | one per stroke, hint placement or committed layer: id, source, t0/t1, `cells`, value, `autoCells`, `board` after (the replay frame), hardest `rung`, `line`, guesses, `struck` |
| `progress: List<ProgressPoint>` | correct cells over time (any source); ends at every cell when won |
| `accuracy` | correct player marks / (player marks + refused marks) |
| `effort10`, `effort`, `effortPerMin` | Σ effort of credited marks (×10; in L1 units; per active minute) |
| `hardest` | the credited mark with the most effort: `rating.rung` + `cell` (+ `rating.line` for line rungs) |
| `rhythm` | kogasa consistency (Sudoku's formula) on first-correct player decisions per 15 s; 0 under 30 s or 4 decisions |
| `strokeCount`, `longestStroke`, `cellsPerStroke` | the player's own strokes |
| `guesses` | marks with no forcing rung, or right after a refusal at the same cell |
| `lookAheadUnaided`, `lookAheadGaps`, `lookAheadHints` | look-ahead marks found without the hint revealing the cell; the time each took; hints that pointed at a look-ahead |
| `hints`, `checks`, `clears`, `autoCrosses` | counts |
| `secondsPerCell`, `decided`, `time`, `won`, `lost`, `assisted`, `band`, `net`, `byHand`, `flawless`, `complete`, `rated` | as named |

Definitions:

- **Credited.** A mark is credited if a player made it, it was the cell's first correct decision, and
  it was not a guess. Only credited marks earn effort. The board it is rated on includes auto-crosses and
  hint cells (01 §4.2: K_t), and the analyzer ignores wrong marks.
- **Strike kinds** (§5.2) come from the board just before the strike:
  - **slip:** the solution value is forced by L1–L3 on the cell's own row or column, i.e. a line alone
    shows the mark is wrong;
  - **wrong:** it is forced only by look-ahead;
  - **guess:** nothing forces it.
- **Records:** `RecordKey(w, h, tier, net, band, assisted)`, where net is 0 Lives (3), 1 Sudden death
  and 2 No net.
- **`accolades(report, priorGames)`:** New record · Unaided · By hand (no auto-cross or hint cell ever
  placed) · Fast (under the median of the last 30 same size/tier/net wins, after 10) · Pure logic (rated,
  no guesses) · Flawless (no strikes, either mode) · **Saw ahead** (Expert/Master: rated, no look-ahead
  hint, at least one look-ahead found) · Sudden death · No net.
  - A miss is shown, with its reason, for Unaided, By hand, Pure logic, Flawless, Fast and (on
    look-ahead tiers) Saw ahead.
- **`halfwayLine`** is unchanged from Sudoku: record pace · ahead of usual · no mistakes yet · halfway
  there. `halfwayTime` replays the log to the first moment half the cells were marked.

For Stats, which builds a report per game, compute reports off the main thread and keep them in memory
by `(game.id, Effort.VERSION, Grader.VERSION)`.

## Persistence (`data/NonoStore.kt`)

One `filesDir/nonograms/<id>.json` per game, written through a temp file and a rename. `current.txt`
names the game in progress. A damaged file is skipped by `all()`, never fatal.

Format v1:

- `v`, `id`, `w`, `h`, `tier`, `seed`, `generator`, `origin`;
- **`bits`**: the solution bitmap, `0`/`1` row-major, always stored;
- `share`: the code, for reading only (the bitmap is the identity);
- `rules {flag, lives, autoCross, contradiction, check, hints}`;
- `band`, `autoCross`, `contradiction` (the current aids);
- `startedAt`, `status`, `elapsed`, `livesLeft`, `tool`;
- `board` and `auto` as digit strings;
- `whatIf {board, auto}` when a layer is open;
- `events`, as `[t, kind, cell, value, x]` arrays.

Round trips are tested, including the report recomputed from the reloaded game and an open what-if
layer.

## Where this departs from the brief

- **Pulling the finger back reverts cells.** §4 says the stroke "fills every cell between the start and
  the finger"; the model takes that literally, so an overshoot can be pulled back instead of undone.
- **A pending start cell (`deferStart`).** It is not in the plan. Without it, a long-press in flag mode
  would judge, and charge a life for, the tool the player was about to switch away from.
- **Stroke marks are logged when the stroke ends**, each with its own entry time, rather than as the
  finger moves. That is what makes a cancelled or pulled-back stroke leave no trace.
- **What-if in flag mode** refuses a wrong layer as a whole, for one life, and keeps the layer open.
  The plan left this open.
- **Any `check()` makes the run assisted**, even one that says "no wrong marks" on an empty board, as
  SOLVE-CONDITIONS treats every oracle use.
- **Flawless counts wrong marks left standing in don't-flag mode too**, as SOLVE-CONDITIONS §5 defines
  it. Sudoku's code only awarded it in flag mode.
- **`RecordKey` holds `w, h`, not `Size`**, so an imported custom size gets its own table; `.size` gives
  the `Size` back where there is one.
- **Undo history is not persisted** (as in Sudoku), even though the log could rebuild it.
- **Hints are not available inside a what-if layer.**

## Phase 5 additions

| File | What |
|---|---|
| `game/OpenCode.kt` | Home's "Open a code": `extract` finds the code in a whole share message or a Pattern line; `open` refuses in plain words (not a code, part missing, too long, one character off, random seed, givens, no solution, needs guessing, too big for now) or returns a `Deal` with where it came from and whether it was turned tall. `shareText` is what Share sends. Boards above 20×20 are refused from the code's header, before any grading. |
| `game/Stats.kt` | `GameSummary` (the few numbers Stats needs from a `Report`, keyed by `id + effort version + grader version + rated`), `bestsMatrix`, `historyPoints` (ms for one size, seconds per cell for all), `trailingGeoMean`, `runningBest`, `lookAhead` (found yourself vs by hint, median time to find one, recent against before; unrated games count as pending, never as zero), `totals`, `streaks`. |
| `Report.Strike.line` | For a slip, the row or column whose own logic rules the mark out, so Results can quote its clue. |

`AppModel.summarise(games)` fills `summaries` in the background: every game unrated first (a log replay),
then Expert and Master rated for look-ahead. Results build their own rated report with `buildReport`.

`app/src/test/.../tools/DemoHistory.kt` is not a test: with `NONO_DEMO_OUT` set it writes ~70 real games
(real puzzles, played through the real `Game` API by a simulated player) for Stats screenshots;
`tools/push_demo_history.sh` copies them into the debug build.
