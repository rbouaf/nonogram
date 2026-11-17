package app.materialnonogram.engine.tools

import app.materialnonogram.engine.Generated
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Grader
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.SplitMix64
import app.materialnonogram.engine.Tier

// Calibration and timing for docs/ENGINE.md. Not part of the app; it may read the clock.
//   ../../gradlew -p tools/engine-jvm run -Dargs="<mode> [n] [sizes]"
// Modes:
//   survey n      per size x fill: flat-line share, line-solvable, P1-solvable, greedy P1 steps p50/p90/max
//   lookahead n   per size: histogram of greedy P1 steps over n P1-solved blobs in the Expert+Master fills
//   sweeps n      per size: Jacobi sweeps of line-solvable, flat-free blobs in the Hard band (the Hard floor)
//   golden n      the golden seed -> puzzle table (app/src/test/resources/golden/gen-v1.tsv), seeds 0..n-1
//   gen n         per size x offered tier: generate seeds 0..n-1; ms/accept, attempts, fallbacks, digest
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "gen"
    val n = args.getOrNull(1)?.toInt() ?: 50
    val sizes = args.getOrNull(2)?.split(",")?.map { s -> Size.entries.first { it.name == s } } ?: Size.entries
    when (mode) {
        "survey" -> survey(n, sizes)
        "lookahead" -> lookahead(n, sizes)
        "sweeps" -> sweeps(n, sizes)
        "golden" -> golden(n)
        "gen" -> gen(n, sizes, args.getOrNull(3)?.split(",")?.map { Tier.valueOf(it) } ?: Tier.entries)
        else -> println("modes: survey lookahead sweeps gen")
    }
}

private fun q(xs: List<Int>, f: Int): Int = if (xs.isEmpty()) -1 else xs.sorted()[(xs.size - 1) * f / 100]
private fun blob(s: Size, fill: Int, i: Int, salt: Long): Puzzle =
    Puzzle(s.w, s.h, Generator.blob(s.w, s.h, fill, SplitMix64(SplitMix64.subseed(i.toLong(), salt * 1000 + fill * 7L + s.ordinal, 0))))

private fun survey(n: Int, sizes: List<Size>) {
    println("# survey: blob at fill, n=$n. flat% | line% P1% stuck% (of flat-free) | greedy P1 steps p50/p90/max | Master(>3)% of P1-solved")
    for (s in sizes) for (fill in 200..700 step 50) {
        var flat = 0; var line = 0; var p1 = 0; var stuck = 0; val steps = ArrayList<Int>()
        for (i in 0 until n) {
            val p = blob(s, fill, i, 1)
            if (p.hasFlatLine()) { flat++; continue }
            if (Grader.isLineSolvable(p.clues)) { line++; continue }
            if (!Grader.isP1Solvable(p.clues)) { stuck++; continue }
            p1++; steps.add(Grader.grade(p.clues, allowP2 = false).p1Steps)
        }
        val ok = (n - flat).coerceAtLeast(1)
        println("%-6s %3d | flat %5.1f | %5.1f %5.1f %5.1f | %d/%d/%d | %5.1f".format(
            s.name, fill, 100.0 * flat / n, 100.0 * line / ok, 100.0 * p1 / ok, 100.0 * stuck / ok,
            q(steps, 50), q(steps, 90), steps.maxOrNull() ?: -1, if (steps.isEmpty()) 0.0 else 100.0 * steps.count { it > 3 } / steps.size))
    }
}

private fun lookahead(n: Int, sizes: List<Size>) {
    println("# lookahead: n P1-solved flat-free blobs per size at fill 220..449; histogram of greedy P1 steps 1..12+, then p50/p90/p99/max")
    for (s in sizes) {
        val steps = ArrayList<Int>(); var i = 0; var drawn = 0
        val r = SplitMix64(s.ordinal * 7919L + 17)
        while (steps.size < n && drawn < n * 4000) {
            val fill = 220 + r.nextInt(230)
            val p = blob(s, fill, i++, 2); drawn++
            if (p.hasFlatLine() || Grader.isLineSolvable(p.clues) || !Grader.isP1Solvable(p.clues)) continue
            steps.add(Grader.grade(p.clues, allowP2 = false).p1Steps)
        }
        val hist = IntArray(13); for (x in steps) hist[minOf(x, 12)]++
        println("%-6s n=%d drawn=%d | %s | p50 %d p90 %d p99 %d max %d | >3: %.1f%%".format(
            s.name, steps.size, drawn, (1..12).joinToString(" ") { "${it}:${hist[it]}" },
            q(steps, 50), q(steps, 90), q(steps, 99), steps.maxOrNull() ?: -1, 100.0 * steps.count { it > 3 } / steps.size.coerceAtLeast(1)))
    }
}

private fun sweeps(n: Int, sizes: List<Size>) {
    println("# sweeps: n line-solvable flat-free blobs per size at the Hard band ${Generator.band(Tier.HARD)}; Jacobi L3 sweeps p10/p25/p50/p75/p90/max")
    for (s in sizes) {
        val sw = ArrayList<Int>(); var i = 0
        val r = SplitMix64(s.ordinal * 31L + 5)
        while (sw.size < n) {
            val band = Generator.band(Tier.HARD)
            val fill = band.first + r.nextInt(band.last - band.first + 1)
            val p = blob(s, fill, i++, 3)
            if (p.hasFlatLine() || !Grader.isLineSolvable(p.clues)) continue
            sw.add(Grader.grade(p.clues).sweeps)
        }
        println("%-6s %d/%d/%d/%d/%d/%d".format(s.name, q(sw, 10), q(sw, 25), q(sw, 50), q(sw, 75), q(sw, 90), sw.max()))
    }
}

private fun fnvLong(h0: Long, v: Long): Long { var h = h0; for (i in 0 until 8) { h = h xor ((v ushr (8 * i)) and 0xFF); h *= 0x100000001b3L }; return h }

fun digest(h0: Long, g: Generated): Long {
    var h = fnvLong(h0, g.seed); h = fnvLong(h, g.attempt.toLong()); h = fnvLong(h, g.fillPermille.toLong())
    for (i in 0 until g.puzzle.cells) h = fnvLong(h, if (g.puzzle.filled(i)) 1 else 0)
    for (ch in g.grade.key()) h = fnvLong(h, ch.code.toLong())
    return h
}

private fun gen(n: Int, sizes: List<Size>, tiers: List<Tier>) {
    for (k in 0 until 20) Generator.generate(Size.S10, Tier.EXPERT, 1000L + k) // warm up the JIT
    println("# gen: seeds 0..${n - 1}. ms/accept p50 p90 max mean | attempts mean max | fallbacks | P1 steps p50/max | digest")
    var all = -0x340d631b7bdddcdbL; val t00 = System.nanoTime()
    for (s in sizes) for (t in tiers) {
        if (!Generator.isOffered(s, t)) continue
        val ms = ArrayList<Double>(); var att = 0L; var maxAtt = 0; var fb = 0; val p1 = ArrayList<Int>()
        var dg = -0x340d631b7bdddcdbL
        for (seed in 0 until n) {
            val t0 = System.nanoTime(); val g = Generator.generate(s, t, seed.toLong()); ms.add((System.nanoTime() - t0) / 1e6)
            att += g.attempt + 1; maxAtt = maxOf(maxAtt, g.attempt + 1); if (g.fallback) fb++
            p1.add(g.grade.p1Steps); dg = digest(dg, g)
        }
        all = fnvLong(all, dg)
        val sorted = ms.sorted()
        println("%-6s %-6s %9.2f %9.2f %9.2f %9.2f | %7.1f %6d | fb %d | %d/%d | %016x".format(
            s.name, t.name, sorted[(n - 1) / 2], sorted[(n - 1) * 9 / 10], sorted.last(), ms.average(),
            att.toDouble() / n, maxAtt, fb, q(p1, 50), p1.max(), dg))
    }
    println("ALL %016x (%.1f s)".format(all, (System.nanoTime() - t00) / 1e9))
}

private fun golden(n: Int) {
    println("# Generator.VERSION ${Generator.VERSION}: size, tier, seed, share code, attempt, fill permille, grade key")
    for (s in Size.entries) for (t in Tier.entries) {
        if (!Generator.isOffered(s, t)) continue
        for (seed in 0 until n) {
            val g = Generator.generate(s, t, seed.toLong())
            println("${s.name}\t${t.name}\t$seed\t${app.materialnonogram.engine.ShareCode.encode(g.puzzle, grouped = false)}\t${g.attempt}\t${g.fillPermille}\t${g.grade.key()}")
        }
    }
}
