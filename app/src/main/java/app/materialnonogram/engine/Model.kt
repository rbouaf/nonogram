package app.materialnonogram.engine

/**
 * Cell states for reasoning, one byte per cell, row-major (`cell = r * w + c`). The game's "crossed"
 * cell is [EMPTY]: a cross is the player's claim that the cell is empty.
 */
object Cell {
    const val UNKNOWN: Byte = 0
    const val FILLED: Byte = 1
    const val EMPTY: Byte = 2
}

/** The generator's sizes (D1). All tall: `w <= h`. [code] is the share code's 3-bit size code (03 §1.2). */
enum class Size(val w: Int, val h: Int, val code: Int) {
    S5(5, 5, 0),
    S10(10, 10, 1),
    S15(15, 15, 2),
    S10x15(10, 15, 3),
    S15x20(15, 20, 4),
    S20(20, 20, 5);

    val cells: Int get() = w * h
    val label: String get() = "${w}×$h"

    companion object {
        fun of(w: Int, h: Int): Size? = entries.firstOrNull { it.w == w && it.h == h }
        fun ofCode(code: Int): Size? = entries.firstOrNull { it.code == code }
    }
}

/** Difficulty tiers (D7, BUILD-PLAN §2). The first three are line logic only; the last two need look-ahead. */
enum class Tier {
    EASY, MEDIUM, HARD, EXPERT, MASTER;

    val isLineOnly: Boolean get() = this <= HARD
}

/**
 * The deduction ladder (01 §1.4), cheapest first. [L1]–[L3] are single-line rules, all collapsed into
 * one "line logic" ceiling for difficulty (01 §1.2); they differ only in per-cell weight.
 * [P1] is one look-ahead (assume a cell, propagate line logic, hit a contradiction); [P2] nests a P1
 * closure inside the propagation.
 */
enum class Rung(val label: String) {
    L1("simple overlap"),
    L2("left-right placement"),
    L3("complete line"),
    P1("look-ahead"),
    P2("deep look-ahead");

    val isLine: Boolean get() = this <= L3

    /** 1-based tag stored per cell in [Grade.tags]; 0 = never determined. */
    val tag: Byte get() = (ordinal + 1).toByte()

    companion object {
        fun ofTag(tag: Byte): Rung? = if (tag in 1..5) entries[tag - 1] else null
    }
}

/** Run lengths of the filled cells of one line. An empty line has the empty clue (never `0`). */
fun cluesOf(line: BooleanArray): IntArray {
    val tmp = IntArray(line.size); var k = 0; var run = 0
    for (b in line) { if (b) run++ else if (run > 0) { tmp[k++] = run; run = 0 } }
    if (run > 0) tmp[k++] = run
    return tmp.copyOf(k)
}

/**
 * The clue set of a `w × h` puzzle. Line indices: `0 until h` are the rows top to bottom, then
 * `h until h + w` the columns left to right. This is all the solver and grader ever see; an imported
 * clue set becomes a [Puzzle] only after the grader finishes it.
 */
class Clues(val w: Int, val h: Int, val rows: Array<IntArray>, val cols: Array<IntArray>) {
    init {
        require(w in 1..MAX_SIDE && h in 1..MAX_SIDE) { "size $w×$h" }
        require(rows.size == h && cols.size == w)
    }

    val lineCount: Int get() = w + h
    fun line(i: Int): IntArray = if (i < h) rows[i] else cols[i - h]
    fun lineLength(i: Int): Int = if (i < h) w else h

    /** The sums agree and every line's clue fits its length: necessary (not sufficient) for a solution. */
    fun isPlausible(): Boolean {
        for (i in 0 until lineCount) {
            val cl = line(i); val len = lineLength(i)
            if (cl.size > (len + 1) / 2) return false
            var need = cl.size - 1
            for (x in cl) { if (x < 1 || x > len) return false; need += x; if (need > len) return false }
        }
        return rows.sumOf { it.sum() } == cols.sumOf { it.sum() }
    }

    companion object { const val MAX_SIDE = 64 }
}

/** Grid geometry shared by the solver, grader and analysis: line ↔ cell mapping. */
object Lines {
    /** Cell index of position [i] along line [line] of a `w × h` grid. */
    fun cell(w: Int, h: Int, line: Int, i: Int): Int = if (line < h) line * w + i else i * w + (line - h)

    /** All cell indices of [line], in order (left to right, top to bottom). */
    fun cells(w: Int, h: Int, line: Int): IntArray =
        IntArray(if (line < h) w else h) { cell(w, h, line, it) }

    /** The states of [line] on [board], in order. */
    fun read(w: Int, h: Int, board: ByteArray, line: Int): ByteArray =
        ByteArray(if (line < h) w else h) { board[cell(w, h, line, it)] }

    fun rowOf(w: Int, cell: Int): Int = cell / w
    fun colLineOf(w: Int, h: Int, cell: Int): Int = h + cell % w
}

/**
 * A puzzle *is* its solution bitmap (03 §1.1): `w`, `h` and the cells row-major. The clues are always
 * derived from it. Immutable.
 */
class Puzzle(val w: Int, val h: Int, bits: BooleanArray) {
    private val bits: BooleanArray = bits.copyOf()

    init {
        require(w in 1..Clues.MAX_SIDE && h in 1..Clues.MAX_SIDE) { "size $w×$h" }
        require(bits.size == w * h) { "bitmap has ${bits.size} cells, expected ${w * h}" }
    }

    val cells: Int get() = w * h
    val size: Size? get() = Size.of(w, h)
    val isTall: Boolean get() = w <= h

    val clues: Clues by lazy {
        val line = BooleanArray(maxOf(w, h))
        val rows = Array(h) { r -> for (c in 0 until w) line[c] = bits[r * w + c]; cluesOf(line.copyOf(w)) }
        val cols = Array(w) { c -> for (r in 0 until h) line[r] = bits[r * w + c]; cluesOf(line.copyOf(h)) }
        Clues(w, h, rows, cols)
    }

    fun filled(cell: Int): Boolean = bits[cell]
    fun filled(r: Int, c: Int): Boolean = bits[r * w + c]

    /** The solution as cell states (every cell [Cell.FILLED] or [Cell.EMPTY]). */
    fun solutionBoard(): ByteArray = ByteArray(cells) { if (bits[it]) Cell.FILLED else Cell.EMPTY }
    fun solution(): BooleanArray = bits.copyOf()
    fun filledCount(): Int = bits.count { it }

    /** True if some row or column is entirely filled or entirely empty (rejected by D10). */
    fun hasFlatLine(): Boolean {
        for (r in 0 until h) {
            var n = 0; for (c in 0 until w) if (bits[r * w + c]) n++
            if (n == 0 || n == w) return true
        }
        for (c in 0 until w) {
            var n = 0; for (r in 0 until h) if (bits[r * w + c]) n++
            if (n == 0 || n == h) return true
        }
        return false
    }

    /** The image under D4 transform [t] (see [Transform]). */
    fun transform(t: Int): Puzzle {
        val o = BooleanArray(cells)
        for (i in 0 until cells) o[Transform.mapCell(w, h, t, i)] = bits[i]
        return Puzzle(Transform.width(w, h, t), Transform.height(w, h, t), o)
    }

    override fun equals(other: Any?): Boolean =
        other is Puzzle && other.w == w && other.h == h && other.bits.contentEquals(bits)

    override fun hashCode(): Int = 31 * (31 * w + h) + bits.contentHashCode()

    /** `#` and `.` rows joined by `/`. */
    override fun toString(): String = buildString {
        for (r in 0 until h) { if (r > 0) append('/'); for (c in 0 until w) append(if (bits[r * w + c]) '#' else '.') }
    }

    companion object {
        /** Builds a puzzle from rows of `#` (filled) and anything else (empty). */
        fun of(vararg rows: String): Puzzle {
            val h = rows.size; val w = rows[0].length
            require(rows.all { it.length == w })
            return Puzzle(w, h, BooleanArray(w * h) { rows[it / w][it % w] == '#' })
        }

        /** A solved board (no [Cell.UNKNOWN]) as a puzzle. */
        fun ofBoard(w: Int, h: Int, board: ByteArray): Puzzle {
            require(board.none { it == Cell.UNKNOWN })
            return Puzzle(w, h, BooleanArray(w * h) { board[it] == Cell.FILLED })
        }
    }
}

/**
 * The dihedral group D4 on a `w × h` grid (03 §2.1). `t` in 0..7: bit 2 = transpose first, then
 * bit 0 = mirror left-right and bit 1 = mirror top-bottom. `t = 5` is a 90° clockwise rotation.
 * Only `t < 4` keeps a rectangle's shape.
 */
object Transform {
    const val IDENTITY = 0
    const val ROTATE_CW = 5

    fun width(w: Int, h: Int, t: Int): Int = if (t and 4 != 0) h else w
    fun height(w: Int, h: Int, t: Int): Int = if (t and 4 != 0) w else h

    fun mapCell(w: Int, h: Int, t: Int, cell: Int): Int {
        var r = cell / w; var c = cell % w; var ww = w; var hh = h
        if (t and 4 != 0) { val x = r; r = c; c = x; ww = h; hh = w }
        if (t and 1 != 0) c = ww - 1 - c
        if (t and 2 != 0) r = hh - 1 - r
        return r * ww + c
    }

    /** Maps a per-cell array through [t] (e.g. grade tags). */
    fun bytes(w: Int, h: Int, t: Int, a: ByteArray): ByteArray {
        val o = ByteArray(a.size); for (i in a.indices) o[mapCell(w, h, t, i)] = a[i]; return o
    }
}
