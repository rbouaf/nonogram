# Nonogram engine (Phase 1)

`app/src/main/java/app/materialnonogram/engine/`, pure Kotlin, stdlib only, no Android imports. Built from
`nonogram-mockups/BUILD-PLAN.md` §2 and `reference/03-DETERMINISM.md`, ported from `reference/bench/NonoDet.java`
(the determinism-correct prototype). Tests: `app/src/test/java/app/materialnonogram/engine/`, fixtures in
`app/src/test/resources/golden/`.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest                                   # everything, legacy included
./gradlew -p tools/engine-jvm test                                 # the engine alone, on a plain JVM
./gradlew -p tools/engine-jvm test --tests '*Exhaustive*' -Dslow=1 # + exhaustive 5×5 (minutes)
./gradlew -p tools/engine-jvm run -Dargs="gen 50"                  # timings; also survey | lookahead | sweeps | golden
tools/engine-jvm/device.sh emulator-5554 gen 20                    # the same bench on ART via app_process
```

## Files

| File | What |
|---|---|
| `Rng.kt` | SplitMix64, copied from `legacy/engine/Rng.kt` (same vectors as Sudoku and the bench) |
| `Model.kt` | `Cell`, `Size`, `Tier`, `Rung`, `Clues`, `Puzzle`, `Lines`, `Transform`, `cluesOf` |
| `LineSolver.kt` | the complete O(k·ℓ) line DP (L3) and the cheaper L2/L1 views |
| `Solver.kt` | line steps, the dirty-line propagator, probes, the line + P1 closure, `ProbeCache` |
| `Grader.kt` | the order-free easiest-first grader and the `Grade` record |
| `Generator.kt` | tiers, fill bands, the integer blob, `generate` |
| `Codec.kt` | `ShareCode`, `SgtId`, `Canonical`, `Importer` |
| `Analysis.kt` | `Analyzer` (per-mark T\*, availability, effort; hints), `LineCheck` |
| `Effort.kt` | `effort-v1` weights and an integer-only `ln` |

## The API the game layer calls

Boards are `ByteArray(w * h)`, row-major, of `Cell.UNKNOWN / FILLED / EMPTY` (a cross is `EMPTY`). Line
indices are rows `0 until h`, then columns `h until h + w`.

```kotlin
// Sizes, tiers, puzzles
enum class Size(val w: Int, val h: Int, val code: Int) { S5, S10, S15, S10x15, S15x20, S20 }   // all tall
enum class Tier { EASY, MEDIUM, HARD, EXPERT, MASTER; val isLineOnly: Boolean }
enum class Rung { L1, L2, L3, P1, P2; val isLine: Boolean; val label: String }
class Puzzle(w: Int, h: Int, bits: BooleanArray) {                  // a puzzle IS its solution bitmap
    val clues: Clues; fun filled(cell: Int): Boolean; fun solutionBoard(): ByteArray
    fun transform(t: Int): Puzzle; fun hasFlatLine(): Boolean; val size: Size?
}
class Clues(w, h, rows: Array<IntArray>, cols: Array<IntArray>) { fun line(i: Int): IntArray }
object Lines { fun cells(w, h, line): IntArray; fun read(w, h, board, line): ByteArray }

// Generation (deterministic; run Expert at 20×20 and every Master on a background thread)
object Generator {
    const val VERSION = 1
    fun isOffered(size: Size, tier: Tier): Boolean            // false for 5×5 and 10×10 Master
    fun generate(size: Size, tier: Tier, seed: Long, isCancelled: () -> Boolean = { false }): Generated
}
class Generated(puzzle, size, tier, seed, attempt, fillPermille, grade: Grade, fallback: Boolean, generatorVersion)

// Grading (also re-grade on load when Grader.VERSION changed)
object Grader {
    const val VERSION = 1
    fun grade(puzzle: Puzzle, allowP2: Boolean = true): Grade
    fun grade(clues: Clues, allowP2 = true, maxP1Steps = Int.MAX_VALUE, p2ProbeBudget = 4_000_000L): Grade
}
class Grade { solved; contradiction; ceiling: Rung?; stepsAt[5]; cellsAt[5]; p1Steps; p1Cells; p2Steps; usesP2;
              sweeps; firstPass; firstPassPermille; effort10; tags: ByteArray; tagOf(cell): Rung?; solution: Puzzle?; key() }

// Sharing and import
object ShareCode { fun encode(p: Puzzle, grouped: Boolean = true): String; fun decode(code: String): Puzzle? }
object SgtId { fun encode(p: Puzzle): String; fun parse(text: String): SgtId.Parsed }
object Canonical { fun of(p: Puzzle): Canonical.Result; fun classKey(p: Puzzle): String; fun classHash(p: Puzzle): Long }
object Importer { fun open(text: String): Importer.Result }    // share code or SGT ID → graded, tall puzzle, or a Refusal

// Per mark, hints, line state
class Analyzer(puzzle: Puzzle) {
    fun forcingRung(board: ByteArray, cell: Int, maxRung: Rung = Rung.P2): Rung?        // null = guess
    fun analyzeMark(before: ByteArray, cell: Int, maxRung: Rung = Rung.P2): MarkAnalysis // rung, availability, effort10
    fun hint(board: ByteArray, maxRung: Rung = Rung.P2): Hint?   // line → cell → why (rung, or assumed + brokenLine)
}
object LineCheck {
    fun isSatisfied(clue: IntArray, cells: ByteArray): Boolean    // filled cells form exactly the clue
    fun contradicts(clue: IntArray, cells: ByteArray): Boolean    // Tatham's weak check: complete runs only
}
object Effort { const val VERSION = "effort-v1"; val W10: IntArray; fun effort10(rung: Rung?, ref: Int, a: Int): Int }
```

Everything an `Analyzer` reasons from is first *sanitized*: marks that disagree with the solution become
unknown, so a hint is always sound and T\* is computed on the player's correct knowledge (01 §4.2). For a
P1 hint, `Hint.line` is the cell's row; `brokenLine` is a line that has no consistent filling once `assumed` is
placed and propagated ("if r7c3 were filled, column 3 would break"). Hint tie-breaks (a UI matter, 03 §5.4):
cheapest rung; then the line that places the most cells, rows before columns, lower index; the cell nearest
the line's centre. A look-ahead hint picks, like the grader, a forced cell that unlocks the most, then
centre-out, then row-major.

## Grading

`Grader.grade` is the order-free easiest-first grader of 03 §5.3, ported line for line from
`NonoDet.gradeOrderFree(greedy = true)` and **checked equal to it field by field on 504 blobs** (every size,
250–650‰; `golden/bench-grades.tsv`, written by the bench itself):

1. L1, else L2, else L3, Jacobi style: every line deduced from one snapshot, the union applied, one round = one step.
2. P1, greedy: probe every unknown cell both ways; of the forced cells, apply all those whose placement
   (with L3 propagation) determines the most cells, as **one** step.
3. P2, layered (new; the bench had none): a full line + P1 closure inside each probe; all forced cells as one
   step. The generator never uses it (it rejects anything P1 cannot finish); it exists so an import that needs
   it is graded instead of refused. Capped by a probe budget.

Every field is a function of sets, so the record is identical under all 8 transforms (tested on 56 generated
puzzles and 60 bench blobs × 8, tags mapped through the transform) and equals the grade of the canonical form.

Speed: a probe `c = v` that survives propagation proves every cell it decided would survive as decided (by
monotonicity), so those probes are skipped until the board changes (`ProbeCache`). The outputs are unchanged
(the bench match and the golden tables pin that); Master generation got ~2× faster.

## Tiers

| Tier | Rule (Generator.VERSION 1) | Fill band ‰ |
|---|---|---|
| Easy | line logic only; L1 on the blank grid places ≥ 400‰ of the cells | 620–699 |
| Medium | line logic only | 550–619 |
| Hard | line logic only; Jacobi L3 sweeps ≥ the size's median: 5×5 4 · 10×10 5 · 15×15 8 · 10×15 6 · 15×20 9 · 20×20 11 | 450–549 |
| Expert | 1–3 greedy look-ahead (P1) steps | 300–449 |
| Master | ≥ 4 greedy P1 steps (or any P2) | 250–379 |

Every tier also rejects any all-filled or all-empty line (D10). `isOffered` is false for 5×5 and 10×10 Master.

**The Expert/Master cut, measured on the order-free count** (`Bench lookahead 300`: 300 P1-solved, flat-free
blobs per size, fill 220–449, histogram of greedy P1 steps):

| Size | 1 | 2 | 3 | ≥ 4 | p50 / p90 / p99 / max |
|---|---|---|---|---|---|
| 5×5 | 300 | 0 | 0 | 0 | 1 / 1 / 1 / 1 |
| 10×10 | 270 | 25 | 2 | **3 (1.0%)** | 1 / 1 / 3 / 5 |
| 10×15 | 246 | 42 | 7 | **5 (1.7%)** | 1 / 2 / 4 / 8 |
| 15×15 | 211 | 48 | 10 | **31 (10.3%)** | 1 / 4 / 9 / 11 |
| 15×20 | 187 | 52 | 28 | **33 (11.0%)** | 1 / 4 / 8 / 13 |
| 20×20 | 134 | 58 | 43 | **65 (21.7%)** | 2 / 6 / 15 / 25 |

The cut stays at **Expert 1–3 / Master ≥ 4**. At 15×15 the ≥ 4 mass (10%) is three times the mass at exactly 3,
so the cut sits in a trough and Master is a distinct population, not the tail of Expert. Master is common from
15×15 up, rare at 10×15 (offered, but the slowest cell to generate), and ~1% of the already rare look-ahead
10×10s — so 10×10 Master *can* occur (03 said "never seen"; it saw ~55 samples), but too rarely to offer, as D7
decided. A cut at 2/3 would make Master 4% of look-ahead 10×15s and 2% of 10×10s, blurring "Master means the
same at every size".

**What D10 costs** (`Bench survey 1500`: share of blobs with a flat line, by fill): it is cheap from 15×15 up at
mid fills (13–18% at 500‰) but heavy at the extremes — **5×5 Easy loses 90–99.7% of its candidates** (a 5×5 at
650‰ almost always has a full row), and sparse Master fills lose 45–88% at 15×15 (250–300‰) and 26–74% at 20×20.
Generation absorbs it (5×5 Easy takes ~36 attempts, still 0.1 ms). Master's band moved from the bench's 220–350
to **250–379** because below 250‰ nearly every blob has an empty line; per-draw Master yield peaks at 300–350‰.

## Timings

`Bench gen` (`ms per accepted puzzle`, p50 / p90 / max; attempts mean; seeds 0–49 on the JVM, 0–19 on ART).
**JVM:** JBR 21 on the Apple M4 (release-like: JIT, no debug). **ART:** the arm64 Android 16 emulator on the
same Mac, the bench dexed with `d8 --release` and run through `app_process` (`tools/engine-jvm/device.sh`) — the
Pixel 10 was not connected, so ART numbers are relative; 03 measured the phone at roughly the Mac's speed.

| Size | Easy | Medium | Hard | Expert | Master |
|---|---|---|---|---|---|
| 5×5 JVM | 0.12 / 0.22 / 0.32 | 0.07 / 0.10 / 0.15 | 0.07 / 0.12 / 0.25 | 0.24 / 0.78 / 1.7 (77 att.) | — |
| 5×5 ART | 0.19 / 1.2 / 4.3 | 0.11 / 0.73 / 9.2 | 0.42 / 1.1 / 5.5 | 0.89 / 5.7 / 7.3 | — |
| 10×10 JVM | 0.17 / 0.25 / 0.32 | 0.17 / 0.23 / 0.41 | 0.20 / 0.36 / 0.75 | 2.1 / 4.0 / 4.6 (39 att.) | — |
| 10×10 ART | 0.22 / 0.61 / 1.0 | 0.24 / 0.63 / 5.8 | 0.37 / 1.0 / 1.7 | 4.1 / 12 / 20 | — |
| 15×15 JVM | 0.41 / 0.57 / 1.2 | 0.48 / 0.61 / 0.72 | 1.1 / 2.5 / 5.3 | 9.3 / 18 / 27 | **97 / 252 / 776** (181 att.) |
| 15×15 ART | 0.65 / 1.4 / 3.8 | 0.57 / 1.1 / 2.7 | 3.0 / 9.5 / 17 | 12 / 33 / 50 | **134 / 251 / 371** |
| 10×15 JVM | 0.23 / 0.31 / 0.79 | 0.26 / 0.38 / 0.48 | 0.45 / 0.69 / 1.1 | 4.9 / 9.7 / 17 | **100 / 427 / 566** (1,201 att.) |
| 10×15 ART | 0.29 / 0.69 / 3.9 | 0.39 / 0.67 / 0.98 | 0.61 / 1.0 / 1.1 | 6.9 / 13 / 19 | **178 / 838 / 1,451** |
| 15×20 JVM | 0.61 / 1.2 / 1.9 | 0.77 / 1.1 / 1.3 | 1.3 / 3.0 / 4.8 | 17 / 35 / 54 | **83 / 302 / 557** (67 att.) |
| 15×20 ART | 0.67 / 1.3 / 5.5 | 0.78 / 1.1 / 4.8 | 1.4 / 2.4 / 3.9 | 26 / 39 / 50 | **93 / 230 / 358** |
| 20×20 JVM | 1.0 / 2.3 / 5.7 | 1.3 / 1.8 / 2.6 | 3.3 / 7.5 / 11 | 47 / 100 / 185 | **166 / 414 / 573** (37 att.) |
| 20×20 ART | 1.2 / 4.7 / 5.5 | 1.3 / 2.2 / 3.9 | 3.3 / 6.8 / 8.9 | 49 / 104 / 112 | **200 / 923 / 1,396** |

No fallback in 1,400 JVM + 560 ART generations. Easy–Hard are ≤ 17 ms worst case everywhere; Expert ≤ 0.2 s;
**Master is 0.1–1.5 s and must be pre-generated on a background thread** (pass `isCancelled`), as Sudoku
prefetches. 10×15 Master is the slowest cell because Master is rare at that size (~1,200 attempts per accept).

**Determinism across runtimes:** the JVM and ART runs of `gen 20` (560 puzzles, all 28 cells) give identical
per-cell digests and the same overall digest, **`81cd02873c59f091`**. It differs from the bench's
`b7658175018c472e` by design (D10, the Hard floor, the P1 pre-filter and Master's band all changed the accept
path); `golden/gen-v1.tsv` pins the Kotlin engine's own seeds 0–4 of every cell.

## Tests (55 engine tests; the slow 5×5 one skipped by default)

- **Outside numbers:** the line DP against brute force on 200,000 random partial lines (L1/L2 sound, L2 never
  claims more than L3, all three reversal-symmetric); exhaustive 3×3 and **4×4: 65,536 grids → 52,362 unique,
  51,234 line-solvable** (OEIS A242876 / A391619), 1,128 needing P1, 0 stuck. Optional exhaustive 5×5 (`-Dslow=1`,
  3 min): 25,309,575 / 24,976,511 / 333,064 / 0, matching 01 §5.1.
- **The bench:** grades, share codes, SGT IDs and canonical transforms equal `NonoDet`'s on 504 blobs.
- **Golden:** PRNG vectors (Sudoku's and 03's), the blob picture and its share code, webpbn #1 (clues, SGT ID,
  custom-size code `3H15HMNTJGS9F1YKG`), seed → puzzle for every offered cell (`gen-v1.tsv`).
- **Codec:** round trips at every size and custom sizes up to 64×64; every one of 9,145 single-character typos
  rejected; truncation, extension, bad symbols and versions rejected; `O/I/L`, case, groups and spaces accepted;
  SGT parse errors (`0`, `,` givens, `#` seeds, bad counts); importer refusals; wide imports turned tall.
- **Invariance:** grade under all 8 transforms; canonical form of every transform; grade of the canonical form.
- **Generator:** every offered size × tier within budget with no fallback, obeying its rule; no flat lines; exact
  fill; cancellation.
- **Analysis:** hints alone solve every fixture, every hint sound, look-ahead hints exactly on look-ahead tiers;
  wrong marks ignored; T\* matches the grader's first round; guesses detected; P2's closure-in-a-probe sound and
  at least as strong as P1; Tatham's weak check case by case; the integer `ln`.
- **`DeterminismLintTest`:** source scan of `engine/` (comments and strings stripped) for platform RNGs, hash
  collections, `System.`/clock/`Duration`, floating point and decimal literals, `Math.*`/`kotlin.math` beyond
  `abs/min/max`, comparator sorts, locale formatting, threads and `Regex(` (none is needed, so the ICU/JVM trap of
  Notes' `RegexPortabilityTest` cannot arise).

## Where this departs from the plan

- **P2 is layered and import-only.** The generator rejects whatever P1 cannot finish, so no generated puzzle uses
  P2; no natural P2 puzzle exists to pin at these sizes, so a test checks its core directly.
- **effort-v1 availability for look-ahead marks is out of the cell count**, not rows + columns, so `S(A) ≥ 1`;
  `ln` is integer fixed-point (01 §4.2 wrote it in floating point). A P2 mark's availability is 1 (not scanned).
- **Hint line choice** is "places the most cells, rows first"; 03 §5.4 proposed centre-out (still used for the cell).
- Not built: the `.non` file export, the daily seed and its 4-character fingerprint (no daily puzzle, D10), and
  running the golden files under `connectedAndroidTest`.
- `tools/engine-jvm` now builds the new engine; it no longer builds `legacy/engine`.
