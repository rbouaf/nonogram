package app.materialnonogram.engine

/** Shared test fixtures, computed once per test JVM. */
object Fixtures {
    /** Two generated puzzles per size × offered tier (seeds 0, 1). */
    val generated: List<Generated> by lazy {
        Size.entries.flatMap { s ->
            Tier.entries.filter { Generator.isOffered(s, it) }.flatMap { t -> (0L..1L).map { Generator.generate(s, t, it) } }
        }
    }

    /** One record of `golden/bench-grades.tsv`: a blob graded by the bench's `NonoDet.gradeGreedy`. */
    class BenchRecord(val line: String) {
        private val f = line.split('\t')
        val puzzle = Puzzle(f[0].toInt(), f[1].toInt(), BooleanArray(f[2].length) { f[2][it] == '1' })
        val solved = f[3].toBoolean()
        val ceiling = f[4].toInt() // 1..4 = L1..P1
        val steps = f[5].split(',').map { it.toInt() }
        val cells = f[6].split(',').map { it.toInt() }
        val effort10 = f[7].toInt()
        val sweeps = f[8].toInt()
        val firstPass = f[9].toInt()
        val shareCode = f[10]
        val sgtId = f[11]
        val canonT = f[12].toInt()
        val canonAutos = f[13].toInt()
    }

    val bench: List<BenchRecord> by lazy {
        val text = Fixtures::class.java.getResourceAsStream("/golden/bench-grades.tsv")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() }.map { BenchRecord(it) }
    }

    fun resource(name: String): String =
        Fixtures::class.java.getResourceAsStream("/golden/$name")!!.bufferedReader().readText()
}
