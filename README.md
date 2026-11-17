# Material Nonogram (イラロジ)

An offline nonogram (picture logic, イラストロジック) for Android, built in Material 3 Expressive. No ads, no
accounts, no network.

![Home, a 15×15 game, end of game and stats](docs/screenshots.png)

- **Six sizes:** 5×5, 10×10 and 15×15, plus 10×15, 15×20 and 20×20 from Settings. 20×20 opens zoomed in:
  one finger always draws, two fingers pinch and pan, the clue bands stay pinned, and a stroke that
  reaches the edge scrolls the board along with it.
- **Five difficulties**, graded by the logic a puzzle needs: Easy, Medium and Hard are solvable one line at
  a time; Expert and Master need looking ahead (Master only from 15×15 up). Hold a difficulty on Home to
  read what it asks of you.
- **Every puzzle generated on the phone**, deterministic and unique, as a smoothed abstract pattern —
  never a picture, never AI art. Each one is proven solvable by logic before you see it.
- **Strokes that feel right:** slide across cells to fill or cross a whole run in one go; it locks to its
  row or column, never skips a cell, and only changes cells like the one it started on. Hold still to
  borrow the other tool.
- **Mistakes your way:** flag them (3 lives or 1) or don't; optional auto-cross, broken-clue flag, check
  button and three-step hints. Records are kept apart by how much help a game had.
- **Clue numerals set in Google Sans Flex's width axis:** thin, tall row clues and short, wide column clues.
- **End of game:** the solved pattern flies into the hero as your trophy; accolades; accuracy, effort,
  hardest step and rhythm; a timeline of every stroke with a replay you scrub stroke by stroke; and for a
  loss, the board you had at each strike and whether it was a slip, a guess or a wrong look-ahead.
- **Stats from the full move log:** streak, a size × difficulty table of bests, history in seconds per
  cell, how often you find look-ahead steps yourself, when you play, totals.
- **Share a puzzle** as a checked code (a typo is caught, not turned into another puzzle), or copy it as a
  Simon Tatham's Pattern game ID. **Open a code** on Home plays any shared code or Pattern ID that logic
  alone can solve, up to 20×20.
- Dynamic colour or four seeds, light/dark and pure black, reduced motion, haptics, TalkBack labels for
  every clue and cell, and the launcher name as Nonogram or イラロジ.

Android 12+.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest :app:assembleDebug   # tests + debug APK (app.materialnonogram.debug)
tools/build_release.sh                                # release APK, aligned and signed with ~/.android/debug.keystore
tools/build_release.sh -i SERIAL                      # ... and install it on one device
```

The release is minified with R8 and signed with the local debug keystore, so it updates an installed copy
in place with its data. `build_release.sh` checks the alignment and the signing certificate and prints
`lastUpdateTime` of `app.materialnonogram` on every connected device.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/app/materialnonogram/engine/` | Puzzle engine: line solver, look-ahead prober, grader, generator, share codes, SGT IDs, importer. Pure Kotlin, deterministic. [`docs/ENGINE.md`](docs/ENGINE.md) |
| `…/game/` | The game: strokes, mistakes, auto-cross, hints, what-if, the event log, `Report` (everything Results and Stats show, recomputed from the log), `OpenCode`, Stats aggregation. [`docs/GAME.md`](docs/GAME.md) |
| `…/data/` | Settings (DataStore) and one JSON file per game |
| `…/ui/` | Home, Open a code, Play (the Blocks board), Results, Stats, Settings, and the shared kit (springs, haptics, MaterialShapes) |
| `tools/` | `build_release.sh`, `gen_icons.py` (Material Symbols), `gen_launcher_icon.py`, `drive_nono.py` and `pinch.py` (drive the debug build on an emulator), `push_demo_history.sh`, `shot.sh` |

The design and its decisions live next door in `../nonogram-mockups/` (`DECISIONS.md`, `BUILD-PLAN.md`,
the HTML boards and their screenshots).
