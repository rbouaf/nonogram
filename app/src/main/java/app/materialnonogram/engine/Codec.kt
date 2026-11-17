package app.materialnonogram.engine

/**
 * The compact share code (03 §1.2), MSB first:
 * `version(3) · size code(3) · [w−1(6) · h−1(6) if code 7] · bitmap(w·h, row-major) · check(15)`,
 * in Crockford Base32, zero-padded to a whole character. The check is the low 15 bits of FNV-1a-64
 * over the preceding bits written as ASCII `'0'`/`'1'` (as the bench does). Without it 85% of
 * one-character typos decode to another valid, playable puzzle, so it is mandatory.
 *
 * 10 / 25 / 35 / 50 / 65 / 85 characters for 5×5 / 10×10 / 10×15 / 15×15 / 15×20 / 20×20.
 * Built from ASCII tables only; no locale anywhere.
 */
object ShareCode {
    const val CODEC_VERSION = 0
    const val CHECK_BITS = 15
    private const val CUSTOM = 7
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The code for [p], in groups of five joined by `-` if [grouped]. */
    fun encode(p: Puzzle, grouped: Boolean = true): String {
        val b = StringBuilder()
        put(b, CODEC_VERSION.toLong(), 3)
        val sc = p.size?.code ?: CUSTOM
        put(b, sc.toLong(), 3)
        if (sc == CUSTOM) { put(b, (p.w - 1).toLong(), 6); put(b, (p.h - 1).toLong(), 6) }
        for (i in 0 until p.cells) b.append(if (p.filled(i)) '1' else '0')
        put(b, fnv(b) and ((1L shl CHECK_BITS) - 1), CHECK_BITS)
        val raw = toBase32(b)
        return if (grouped) group(raw) else raw
    }

    /** Inserts `-` every five characters. */
    fun group(code: String): String = buildString {
        for (i in code.indices) { if (i > 0 && i % 5 == 0) append('-'); append(code[i]) }
    }

    /**
     * The puzzle in [code], or null if it is not a well-formed code with a matching check. Case-
     * insensitive; `O` reads as `0`, `I`/`L` as `1`; `-` and whitespace are ignored.
     */
    fun decode(code: String): Puzzle? {
        val bits = fromBase32(code) ?: return null
        if (bits.length < 6) return null
        if (bits.substring(0, 3).toInt(2) != CODEC_VERSION) return null
        val sc = bits.substring(3, 6).toInt(2)
        var pos = 6
        val w: Int; val h: Int
        if (sc == CUSTOM) {
            if (bits.length < 18) return null
            w = bits.substring(6, 12).toInt(2) + 1; h = bits.substring(12, 18).toInt(2) + 1; pos = 18
        } else {
            val s = Size.ofCode(sc) ?: return null
            w = s.w; h = s.h
        }
        val end = pos + w * h
        if (bits.length < end + CHECK_BITS) return null
        // The padding must be shorter than one character and all zero: a code of the wrong length is rejected.
        if (bits.length - (end + CHECK_BITS) >= 5) return null
        for (i in end + CHECK_BITS until bits.length) if (bits[i] != '0') return null
        val check = bits.substring(end, end + CHECK_BITS).toLong(2)
        if ((fnv(bits.subSequence(0, end)) and ((1L shl CHECK_BITS) - 1)) != check) return null
        return Puzzle(w, h, BooleanArray(w * h) { bits[pos + it] == '1' })
    }

    /** FNV-1a 64 over the characters of [s] (here always `'0'`/`'1'`). */
    fun fnv(s: CharSequence): Long {
        var x = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (i in 0 until s.length) { x = x xor s[i].code.toLong(); x *= 0x100000001b3L }
        return x
    }

    private fun put(b: StringBuilder, v: Long, n: Int) {
        for (i in n - 1 downTo 0) b.append(if ((v ushr i) and 1L != 0L) '1' else '0')
    }

    private fun toBase32(bits: CharSequence): String = buildString {
        var i = 0
        while (i < bits.length) {
            var v = 0
            for (j in 0 until 5) v = v * 2 + (if (i + j < bits.length && bits[i + j] == '1') 1 else 0)
            append(ALPHABET[v]); i += 5
        }
    }

    private fun fromBase32(s: String): String? {
        val b = StringBuilder()
        for (ch in s) {
            var c = ch
            if (c == '-' || c == ' ' || c == '\n' || c == '\t' || c == '\r') continue
            if (c in 'a'..'z') c = 'A' + (c - 'a')
            if (c == 'O') c = '0'
            if (c == 'I' || c == 'L') c = '1'
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            for (j in 4 downTo 0) b.append(if ((v shr j) and 1 != 0) '1' else '0')
        }
        return b.toString()
    }
}

/**
 * Simon Tatham's Pattern descriptive game ID (03 §1.3): `WxH:` then the columns left to right, then
 * the rows top to bottom; numbers joined by `.`, lines by `/`, an empty line is an empty field
 * (SGT rejects `0`). SGT's own parser and solver accepted 280 of 280 of these.
 */
object SgtId {
    fun encode(p: Puzzle): String = encode(p.clues)

    fun encode(c: Clues): String = buildString {
        append(c.w).append('x').append(c.h).append(':')
        for (i in 0 until c.w + c.h) {
            if (i > 0) append('/')
            val cl = if (i < c.w) c.cols[i] else c.rows[i - c.w]
            for (j in cl.indices) { if (j > 0) append('.'); append(cl[j]) }
        }
    }

    /** Why a text did not parse as an SGT descriptive ID. */
    enum class Problem { NOT_SGT, RANDOM_SEED, GIVENS, BAD_SIZE, BAD_LINE_COUNT, BAD_NUMBER, CLUE_TOO_LONG }

    class Parsed(val clues: Clues?, val problem: Problem?)

    /**
     * Parses `WxH:desc` or `N:desc`. Refuses SGT's random-seed form (`params#seed`, not portable
     * across SGT versions), the `,` givens suffix, `0`, and any line whose clue cannot fit.
     */
    fun parse(text: String): Parsed {
        val t = text.trim()
        val colon = t.indexOf(':'); val hash = t.indexOf('#')
        if (hash >= 0 && (colon < 0 || hash < colon)) return Parsed(null, Problem.RANDOM_SEED)
        if (colon < 0) return Parsed(null, Problem.NOT_SGT)
        val params = t.substring(0, colon); val desc = t.substring(colon + 1)
        val x = params.indexOf('x')
        val w = parseInt(if (x < 0) params else params.substring(0, x)) ?: return Parsed(null, Problem.NOT_SGT)
        val h = if (x < 0) w else parseInt(params.substring(x + 1)) ?: return Parsed(null, Problem.NOT_SGT)
        if (w !in 1..Clues.MAX_SIDE || h !in 1..Clues.MAX_SIDE) return Parsed(null, Problem.BAD_SIZE)
        if (desc.indexOf(',') >= 0) return Parsed(null, Problem.GIVENS)
        val fields = desc.split('/')
        if (fields.size != w + h) return Parsed(null, Problem.BAD_LINE_COUNT)
        val lines = arrayOfNulls<IntArray>(w + h)
        for (i in fields.indices) {
            val f = fields[i]
            val len = if (i < w) h else w
            if (f.isEmpty()) { lines[i] = IntArray(0); continue }
            val parts = f.split('.')
            // A line of length len holds at most (len + 1) / 2 runs; checking before allocating, and
            // stopping as soon as the running need exceeds len, keeps hostile input from overflowing.
            if (parts.size > (len + 1) / 2) return Parsed(null, Problem.CLUE_TOO_LONG)
            val cl = IntArray(parts.size)
            var need = parts.size - 1
            for (j in parts.indices) {
                val v = parseInt(parts[j]) ?: return Parsed(null, Problem.BAD_NUMBER)
                if (v < 1) return Parsed(null, Problem.BAD_NUMBER)
                need += v
                if (need > len) return Parsed(null, Problem.CLUE_TOO_LONG)
                cl[j] = v
            }
            lines[i] = cl
        }
        val cols = Array(w) { lines[it]!! }
        val rows = Array(h) { lines[w + it]!! }
        return Parsed(Clues(w, h, rows, cols), null)
    }

    /** Decimal digits only, at most 4 of them; no sign, no locale. */
    private fun parseInt(s: String): Int? {
        if (s.isEmpty() || s.length > 4) return null
        var v = 0
        for (ch in s) { if (ch !in '0'..'9') return null; v = v * 10 + (ch - '0') }
        return v
    }
}

/**
 * Canonical form (03 §2.3): minlex over the D4 transforms whose output is tall (`w ≤ h`) — 8 for a
 * square, 4 for a tall rectangle, the 4 transposing ones for a wide puzzle. Bits are compared row-major
 * with filled = 1 and the first cell most significant. Colour inversion is *not* an equivalence.
 */
object Canonical {
    class Result(val puzzle: Puzzle, val transform: Int, val automorphisms: Int)

    fun of(p: Puzzle): Result {
        var best: Puzzle? = null; var bestT = -1; var autos = 0
        for (t in 0 until 8) {
            if (Transform.width(p.w, p.h, t) > Transform.height(p.w, p.h, t)) continue
            val o = p.transform(t)
            val cmp = if (best == null) -1 else compare(o, best)
            if (cmp < 0) { best = o; bestT = t; autos = 1 } else if (cmp == 0) autos++
        }
        return Result(best!!, bestT, autos)
    }

    /** The class key: the canonical representative's share code, ungrouped. Equal for every member of a class. */
    fun classKey(p: Puzzle): String = ShareCode.encode(of(p).puzzle, grouped = false)

    /** A 64-bit hash of the class (FNV-1a over the canonical bits), for use as a database key. */
    fun classHash(p: Puzzle): Long {
        val c = of(p).puzzle
        val s = StringBuilder(c.cells + 8)
        s.append(c.w).append('x').append(c.h).append(':')
        for (i in 0 until c.cells) s.append(if (c.filled(i)) '1' else '0')
        return ShareCode.fnv(s)
    }

    private fun compare(a: Puzzle, b: Puzzle): Int {
        for (i in 0 until a.cells) {
            val x = a.filled(i); val y = b.filled(i)
            if (x != y) return if (x) 1 else -1
        }
        return 0
    }
}

/** "Open a code": a share code or an SGT ID → a puzzle the grader finishes, turned tall (03 §1, D10). */
object Importer {
    enum class Source { SHARE_CODE, SGT_ID }

    enum class Refusal {
        /** Neither a share code nor an SGT ID. */
        UNREADABLE,
        /** Looks like a share code but the check does not match: a typo. */
        BAD_CHECK,
        /** An SGT random seed (`10x10#123…`): not portable across SGT versions. */
        SGT_SEED,
        /** SGT's `,` givens suffix: this app has no givens. */
        SGT_GIVENS,
        /** The clues have no solution. */
        NO_SOLUTION,
        /** The grader cannot finish it: several solutions, or it needs guessing. */
        NOT_UNIQUE,
    }

    class Result(
        val puzzle: Puzzle?,
        val grade: Grade?,
        val source: Source?,
        /** The import was wide and was turned 90° clockwise to fit the phone. */
        val rotated: Boolean,
        val refusal: Refusal?,
    ) {
        val ok: Boolean get() = puzzle != null
    }

    fun open(text: String): Result {
        val t = text.trim()
        if (t.indexOf(':') >= 0 || t.indexOf('#') >= 0) {
            val parsed = SgtId.parse(t)
            val clues = parsed.clues ?: return refuse(
                when (parsed.problem) {
                    SgtId.Problem.RANDOM_SEED -> Refusal.SGT_SEED
                    SgtId.Problem.GIVENS -> Refusal.SGT_GIVENS
                    SgtId.Problem.CLUE_TOO_LONG -> Refusal.NO_SOLUTION
                    else -> Refusal.UNREADABLE
                },
            )
            if (!clues.isPlausible()) return refuse(Refusal.NO_SOLUTION)
            return finish(clues, Source.SGT_ID)
        }
        val compact = t.filter { it != '-' && it != ' ' }
        if (compact.isEmpty() || compact.any { !it.isLetterOrDigit() || it.code > 127 }) return refuse(Refusal.UNREADABLE)
        val p = ShareCode.decode(t) ?: return refuse(Refusal.BAD_CHECK)
        return finish(p.clues, Source.SHARE_CODE)
    }

    /** Grades [clues] (P2 allowed) and accepts only a finished grade; a wide result is turned tall. */
    fun finish(clues: Clues, source: Source): Result {
        val g = Grader.grade(clues, allowP2 = true)
        if (g.contradiction) return refuse(Refusal.NO_SOLUTION)
        val p = g.solution ?: return refuse(Refusal.NOT_UNIQUE)
        if (p.isTall) return Result(p, g, source, rotated = false, refusal = null)
        val tall = p.transform(Transform.ROTATE_CW)
        return Result(tall, Grader.grade(tall), source, rotated = true, refusal = null)
    }

    private fun refuse(r: Refusal) = Result(null, null, null, rotated = false, refusal = r)
}
