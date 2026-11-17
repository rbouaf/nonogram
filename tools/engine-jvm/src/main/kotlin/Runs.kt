package app.materialnonogram.engine.tools

import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Grader
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.SplitMix64
import app.materialnonogram.engine.Tier

// Research for "more numbers per row" (2026-11-17): how many clue runs the generator's lines carry, and
// prototype pattern knobs that raise it. Nothing here is on the app's accept path; the knobs replicate
// Generator.generate's loop with a different bitmap, so a knob can be shipped later as a new
// Generator.VERSION (or a "Pattern" setting) if it is chosen.
//   runs n [sizes]            the real generator: runs per row / column / line, per size x offered tier
//   knobs n [sizes] [tiers] [knobs]  each knob through the generator's loop: runs, attempts, ms, failures
//   show n [sizes]            each knob's accepted Medium for seed n, side by side
//   yield n [sizes]           each knob's raw candidates per tier band: empty-line, line-, P1-solvable shares

/** Integer-only bitmap makers. All draw exactly w*h 16-bit noise values, like Generator.blob. */
private class Knob(val name: String, val fillShift: Int = 0, val make: (Int, Int, Int, SplitMix64) -> BooleanArray)

private val KNOBS = listOf(
    Knob("blob") { w, h, f, r -> Generator.blob(w, h, f, r) },
    Knob("blend6") { w, h, f, r -> kernelBlob(w, h, f, r, box = true, centre = 6) },
    Knob("blend12") { w, h, f, r -> kernelBlob(w, h, f, r, box = true, centre = 12) },
    Knob("blend24") { w, h, f, r -> kernelBlob(w, h, f, r, box = true, centre = 24) },
    Knob("plus") { w, h, f, r -> kernelBlob(w, h, f, r, box = false, centre = 0) },
    Knob("fill-100", fillShift = -100) { w, h, f, r -> Generator.blob(w, h, f, r) },
    Knob("raw") { w, h, f, r -> kernelBlob(w, h, f, r, box = false, centre = -1) },
)

/**
 * box = true: 3×3 box sum scaled by 36/count (Generator.blob's key) plus [centre] × the cell's own noise.
 * box = false, centre = 0: plus-shaped kernel (cell + 4 neighbours) scaled by 60/count (exact for 3, 4, 5).
 * centre = -1: no smoothing at all (the cell's own noise; random dots, the reference extreme).
 * The box term's spread is 36·σ/3 = 12σ, so centre 12 gives the raw noise an equal share of the variance.
 */
private fun kernelBlob(w: Int, h: Int, fill: Int, rng: SplitMix64, box: Boolean, centre: Int): BooleanArray {
    val n = w * h
    val noise = IntArray(n) { (rng.next() ushr 48).toInt() }
    val key = LongArray(n)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val v: Int = if (centre < 0) noise[i] else if (box) {
            var sum = 0; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { sum += noise[yy * w + xx]; cnt++ }
            }
            sum * (36 / cnt) + centre * noise[i]
        } else {
            var sum = noise[i]; var cnt = 1
            if (x > 0) { sum += noise[i - 1]; cnt++ }
            if (x < w - 1) { sum += noise[i + 1]; cnt++ }
            if (y > 0) { sum += noise[i - w]; cnt++ }
            if (y < h - 1) { sum += noise[i + w]; cnt++ }
            sum * (60 / cnt)
        }
        key[i] = ((0x7FFFFFFFL - v) shl 32) or i.toLong()
    }
    key.sort()
    val k = (n * fill + 500) / 1000
    val g = BooleanArray(n)
    for (j in 0 until k) g[(key[j] and 0xFFFFFFFFL).toInt()] = true
    return g
}

/** Runs per line: rows, columns. */
private class RunStats {
    var lines = 0; var runs = 0L; var ge4 = 0; var ge5 = 0; var max = 0
    var rowLines = 0; var rowRuns = 0L; var colLines = 0; var colRuns = 0L
    var puzzles = 0; var sumMaxRow = 0; var sumMaxCol = 0
    fun add(p: Puzzle) {
        val c = p.clues; var mr = 0; var mc = 0
        for (i in 0 until p.h + p.w) {
            val k = c.line(i).size
            lines++; runs += k; if (k >= 4) ge4++; if (k >= 5) ge5++; max = maxOf(max, k)
            if (i < p.h) { rowLines++; rowRuns += k; mr = maxOf(mr, k) } else { colLines++; colRuns += k; mc = maxOf(mc, k) }
        }
        puzzles++; sumMaxRow += mr; sumMaxCol += mc
    }
    fun fmt() = "mean %.2f (row %.2f col %.2f) | >=4 %5.1f%% >=5 %4.1f%% | widest row clue mean %.1f, col %.1f | max %d".format(
        runs.toDouble() / lines, rowRuns.toDouble() / rowLines, colRuns.toDouble() / colLines,
        100.0 * ge4 / lines, 100.0 * ge5 / lines, sumMaxRow.toDouble() / puzzles, sumMaxCol.toDouble() / puzzles, max)
}

fun runs(n: Int, sizes: List<Size>) {
    println("# runs: the real generator (VERSION ${Generator.VERSION}), seeds 0..${n - 1}. Runs (clue numbers) per line")
    for (s in sizes) for (t in Tier.entries) {
        if (!Generator.isOffered(s, t)) continue
        val st = RunStats()
        for (seed in 0 until n) st.add(Generator.generate(s, t, seed.toLong()).puzzle)
        println("%-6s %-6s %s".format(s.name, t.name, st.fmt()))
    }
}

private class Outcome(val puzzle: Puzzle?, val attempts: Int)

/** Generator.generate's accept path with [knob]'s bitmap; same sub-seeds, bands (shifted), rules and budgets. */
private fun generateWith(knob: Knob, s: Size, t: Tier, seed: Long): Outcome {
    val band = Generator.band(t)
    val lo = (band.first + knob.fillShift).coerceAtLeast(50); val hi = (band.last + 1 + knob.fillShift).coerceAtLeast(lo + 1)
    val salt = Generator.domain(Generator.VERSION, s.w, s.h, t) // the same noise for every knob: a paired comparison; "blob" = the real generator
    for (k in 0 until Generator.maxAttempts(t)) {
        val rng = SplitMix64(SplitMix64.subseed(seed, salt, k))
        val fill = lo + rng.nextInt(hi - lo)
        val p = Puzzle(s.w, s.h, knob.make(s.w, s.h, fill, rng))
        if (p.hasEmptyLine()) continue
        val clues = p.clues
        val lineOnly = Grader.isLineSolvable(clues)
        if (t.isLineOnly != lineOnly) continue
        if (!lineOnly && !Grader.isP1Solvable(clues)) continue
        val g = Grader.grade(clues, allowP2 = false, maxP1Steps = if (t == Tier.EXPERT) Generator.EXPERT_MAX_P1 else Int.MAX_VALUE)
        if (Generator.accepts(t, s, g)) return Outcome(p, k + 1)
    }
    return Outcome(null, Generator.maxAttempts(t))
}

fun knobs(n: Int, sizes: List<Size>, tiers: List<Tier>, only: List<String>? = null) {
    for (k in 0 until 20) Generator.generate(Size.S10, Tier.EXPERT, 1000L + k) // warm up the JIT
    println("# knobs: seeds 0..${n - 1} through the generator's loop. ms p50/p90/mean | attempts mean/max | failed | runs per line")
    for (s in sizes) for (t in tiers) {
        if (!Generator.isOffered(s, t)) continue
        for (knob in KNOBS.filter { only == null || it.name in only }) {
            val ms = ArrayList<Double>(); var att = 0L; var maxAtt = 0; var fail = 0; val st = RunStats()
            for (seed in 0 until n) {
                val t0 = System.nanoTime(); val o = generateWith(knob, s, t, seed.toLong()); ms.add((System.nanoTime() - t0) / 1e6)
                att += o.attempts; maxAtt = maxOf(maxAtt, o.attempts)
                if (o.puzzle == null) fail++ else st.add(o.puzzle)
            }
            val so = ms.sorted()
            println("%-6s %-6s %-8s %8.2f %8.2f %8.2f | %7.1f %5d | fail %2d | %s".format(
                s.name, t.name, knob.name, so[(n - 1) / 2], so[(n - 1) * 9 / 10], ms.average(),
                att.toDouble() / n, maxAtt, fail, if (st.puzzles > 0) st.fmt() else "-"))
        }
    }
}

fun knobYield(n: Int, sizes: List<Size>) {
    println("# yield: n raw candidates per knob x size x tier band. empty% | of empty-free: line% P1% stuck% | runs of empty-free candidates")
    for (s in sizes) for (t in listOf(Tier.EASY, Tier.MEDIUM, Tier.HARD, Tier.EXPERT, Tier.MASTER)) for (knob in KNOBS) {
        val band = Generator.band(t)
        val lo = (band.first + knob.fillShift).coerceAtLeast(50); val hi = (band.last + 1 + knob.fillShift).coerceAtLeast(lo + 1)
        var empty = 0; var line = 0; var p1 = 0; var stuck = 0; val st = RunStats()
        for (i in 0 until n) {
            val rng = SplitMix64(SplitMix64.subseed(i.toLong(), 0x5EEDL + s.ordinal * 10 + t.ordinal, 0))
            val fill = lo + rng.nextInt(hi - lo)
            val p = Puzzle(s.w, s.h, knob.make(s.w, s.h, fill, rng))
            if (p.hasEmptyLine()) { empty++; continue }
            st.add(p)
            if (Grader.isLineSolvable(p.clues)) line++ else if (Grader.isP1Solvable(p.clues)) p1++ else stuck++
        }
        val ok = (n - empty).coerceAtLeast(1)
        println("%-6s %-6s %-8s empty %5.1f | line %5.1f P1 %5.1f stuck %5.1f | %s".format(
            s.name, t.name, knob.name, 100.0 * empty / n, 100.0 * line / ok, 100.0 * p1 / ok, 100.0 * stuck / ok,
            if (st.puzzles > 0) st.fmt() else "-"))
    }
}

fun show(seed: Int, sizes: List<Size>) {
    for (s in sizes) {
        val ps = KNOBS.map { it.name to generateWith(it, s, Tier.MEDIUM, seed.toLong()).puzzle!! }
        println(ps.joinToString("  ") { it.first.padEnd(s.w) })
        for (r in 0 until s.h) println(ps.joinToString("  ") { (_, p) -> (0 until s.w).joinToString("") { if (p.filled(r * s.w + it)) "#" else "." } })
        println()
    }
}
