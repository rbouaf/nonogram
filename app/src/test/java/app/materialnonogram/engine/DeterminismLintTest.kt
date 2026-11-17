package app.materialnonogram.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Bans every source of nondeterminism in `engine/` (03 §3.4), by source scan — the same idea as Notes'
 * `RegexPortabilityTest`: these bugs pass every ordinary test on one machine and differ on another.
 * Comments and string literals are stripped before scanning, so prose may name what code may not use.
 *
 * - platform RNGs (`kotlin.random`, `java.util.Random`, `SplittableRandom`, `ThreadLocalRandom`,
 *   `Math.random`, stdlib `shuffle`/`shuffled`): only `Rng.kt`'s SplitMix64;
 * - hash-ordered iteration (`HashMap`, `HashSet`, `hashMapOf`, `hashSetOf`, a `.hashCode()` call);
 * - the clock and time budgets (`System.`, `nanoTime`, `currentTimeMillis`, `withTimeout`, `Duration`, `Instant`);
 * - floating point (`Double`, `Float`, `toDouble`, `toFloat`, decimal literals) and transcendental
 *   functions (`Math.*`, `kotlin.math` beyond `abs`/`min`/`max`, `.pow(`);
 * - comparator sorts (`sortedBy`, `sortedWith`, `sortBy`, `sortWith`): primitive sorts of unique keys only;
 * - locale formatting (`.format(`, `String.format`, `toUpperCase`, `toLowerCase`);
 * - threads (`Thread`, `parallel`, `Dispatchers`);
 * - `Regex(`: none is needed, and the JVM and Android (ICU) engines disagree (see Notes' test).
 */
class DeterminismLintTest {
    private val banned = listOf(
        "kotlin.random", "java.util.Random", "SplittableRandom", "ThreadLocalRandom", "Math.random", ".shuffle(", ".shuffled(",
        "HashMap", "HashSet", "hashMapOf", "hashSetOf", ".hashCode()",
        "System.", "nanoTime", "currentTimeMillis", "withTimeout", "Duration", "Instant",
        "Double", "Float", "toDouble", "toFloat", "Math.", ".pow(",
        "sortedBy", "sortedWith", "sortBy", "sortWith", "sortedDescending",
        ".format(", "String.format", "toUpperCase", "toLowerCase",
        "Thread", "parallel", "Dispatchers",
        "Regex(",
    )
    private val decimalLiteral = Regex("""(?<![\w.])\d+\.\d+|(?<![\w.])\d+[fF]\b|\b\d+[eE][+-]?\d+\b""")
    private val mathImport = Regex("""import\s+kotlin\.math\.(\w+)""")
    private val allowedMath = setOf("abs", "min", "max")

    private fun sources(): List<File> {
        val root = listOf(File("src/main/java/app/materialnonogram/engine"), File("../../app/src/main/java/app/materialnonogram/engine"))
            .firstOrNull { it.isDirectory }
        assumeTrue("engine sources not found from ${File(".").absolutePath}", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Removes comments and string/char literals, keeping line structure. */
    private fun strip(src: String): String {
        val out = StringBuilder(src.length); var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }; for (k in i until e) if (src[k] == '\n') out.append('\n'); i = e }
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }; out.append("\"\""); i = e }
                c == '"' -> { i++; while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ }; i++; out.append("\"\"") }
                c == '\'' -> { i++; while (i < src.length && src[i] != '\'') { if (src[i] == '\\') i++; i++ }; i++; out.append("' '") }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test fun engineHasNoSourceOfNondeterminism() {
        val offenders = mutableListOf<String>()
        val files = sources()
        assertTrue(files.size >= 8)
        for (f in files) {
            val code = strip(f.readText())
            code.lines().forEachIndexed { n, line ->
                for (b in banned) if (line.contains(b)) offenders += "${f.name}:${n + 1}: `$b` in: ${line.trim()}"
                decimalLiteral.find(line)?.let { offenders += "${f.name}:${n + 1}: floating literal `${it.value}`" }
                mathImport.find(line)?.let { if (it.groupValues[1] !in allowedMath) offenders += "${f.name}:${n + 1}: kotlin.math.${it.groupValues[1]}" }
                Regex("""kotlin\.math\.(\w+)""").findAll(line).forEach { if (it.groupValues[1] !in allowedMath) offenders += "${f.name}:${n + 1}: kotlin.math.${it.groupValues[1]}" }
            }
        }
        assertTrue(offenders.isEmpty(), "Nondeterminism in engine/ (03-DETERMINISM §3.4):\n" + offenders.joinToString("\n"))
    }

    @Test fun theScanItselfCatchesThings() {
        val bad = strip("val r = java.util.Random(1) // fine in a comment\nval x = 0.55\nval s = \"HashMap in a string\"")
        assertTrue(bad.contains("java.util.Random")); assertTrue(decimalLiteral.containsMatchIn(bad)); assertTrue(!bad.contains("HashMap"))
        assertTrue(!decimalLiteral.containsMatchIn("for (i in 0..n) a.b(1..5)"))
    }
}
