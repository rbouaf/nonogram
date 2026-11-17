package app.materialnonogram.tools

import app.materialnonogram.data.NonoJson
import app.materialnonogram.engine.Analyzer
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Lines
import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Deal
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.game.Rules
import app.materialnonogram.game.Status
import app.materialnonogram.game.Tool
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Not a test: writes a believable history of real games for the emulator's Stats and Results screenshots.
 * Every game is a real generated puzzle played by a simulated player through the real [Game] API — strokes
 * along the line a deduction forces, the odd mistake, hints on look-ahead now and then — so every number
 * Stats shows is the app's own, computed from real logs.
 *
 *   NONO_DEMO_OUT=/tmp/nono-demo ./gradlew :app:testDebugUnitTest --tests '*DemoHistory*' --rerun
 *   tools/push_demo_history.sh /tmp/nono-demo
 */
class DemoHistory {
    @Test fun write() {
        val out = System.getenv("NONO_DEMO_OUT")
        assumeTrue("set NONO_DEMO_OUT to write a demo history", out != null)
        val dir = File(out!!).apply { deleteRecursively(); mkdirs() }
        val rnd = Random(17)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val plan = listOf(Size.S5 to 10, Size.S10 to 22, Size.S15 to 16, Size.S10x15 to 8, Size.S15x20 to 6, Size.S20 to 6)
        val games = plan.flatMap { (s, n) -> List(n) { s } }.shuffled(rnd)
        games.forEachIndexed { k, size ->
            val tiers = Tier.entries.filter { Generator.isOffered(size, it) && (it < Tier.MASTER || rnd.nextInt(3) == 0) }
            val tier = tiers[rnd.nextInt(tiers.size)]
            // Spread over ~100 days, evenings mostly, with a run of recent days for a streak.
            val daysAgo = if (k >= games.size - 9) (games.size - 1 - k).toLong() else (100 - k * 100L / games.size)
            val hour = listOf(8, 12, 19, 20, 21, 22, 22, 23)[rnd.nextInt(8)]
            val start = now - daysAgo * day - ((now / 3_600_000L) % 24 - hour) * 3_600_000L - rnd.nextLong(0, 3_000_000L)
            val flag = rnd.nextInt(10) < 8
            val lives = if (flag && rnd.nextInt(6) == 0) 1 else 3
            val rules = Rules.of(flag, lives, autoCross = rnd.nextInt(4) != 0, hints = true)
            val g = Game("demo%03d".format(k), Deal.of(Generator.generate(size, tier, 1000L + k)), rules, startedAt = start)
            play(g, rnd, skill = 0.6 + 0.4 * k / games.size)
            File(dir, g.id + ".json").writeText(NonoJson.encode(g))
        }
        println("wrote ${games.size} games to $dir")
    }

    /** A player who strokes along the run a deduction forces, slower on harder rungs, and sometimes slips. */
    private fun play(g: Game, rnd: Random, skill: Double) {
        val a = Analyzer(g.deal.puzzle)
        var guard = 0
        while (g.status == Status.PLAYING && guard++ < 2000) {
            val h = a.hint(g.realBoard()) ?: break
            val think = when {
                h.rung == Rung.L1 -> 900L
                h.rung.isLine -> 1700L
                else -> 9000L
            }
            g.tick(((think + rnd.nextLong(0, think)) / skill).toLong())
            // A slip: a wrong mark next to the right one, now and then.
            if (rnd.nextInt(60) == 0) {
                val wrong = g.realBoard().indices.firstOrNull { i -> g.board[i] == Mark.UNKNOWN && !g.deal.puzzle.filled(i) && i != h.cell }
                if (wrong != null) { g.tap(wrong, Tool.FILL); if (g.status != Status.PLAYING) break; continue }
            }
            if (!h.rung.isLine && rnd.nextInt(4) == 0) { g.showHint(h); g.advanceHint() }
            val tool = if (h.value == Mark.FILLED) Tool.FILL else Tool.CROSS
            // The stroke: the contiguous run of cells the same deduction forces to the same value.
            val line = Lines.cells(g.w, g.h, h.line)
            val forced = h.lineCells.filter { c -> g.deal.puzzle.filled(c) == (h.value == Mark.FILLED) && g.board[c] == Mark.UNKNOWN }.toSet()
            val at = line.indexOf(h.cell)
            var lo = at; var hi = at
            if (at >= 0 && h.rung.isLine) {
                while (lo > 0 && line[lo - 1] in forced) lo--
                while (hi < line.lastIndex && line[hi + 1] in forced) hi++
            }
            if (at < 0 || lo == hi) { g.tap(h.cell, tool); continue }
            g.beginStroke(line[lo], tool, deferStart = false)
            for (j in lo + 1..hi) { g.tick(rnd.nextLong(30, 90)); g.extendStroke(line[j]) }
            g.endStroke()
        }
    }
}
