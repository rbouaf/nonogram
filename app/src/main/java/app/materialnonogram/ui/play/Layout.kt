package app.materialnonogram.ui.play

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.materialnonogram.engine.Clues
import app.materialnonogram.ui.theme.flex

/**
 * Clue typography and the Play layout solver, "C · Blocks" (nonogram-mockups/html/README.md §3).
 * Every number here is the README's; dp throughout.
 */
object Blocks {
    // Sudoku's Tiles grid (user, 2026-11-17): every cell its own rounded tile, a small gap inside a 5×5
    // block and a wider gutter between blocks, no lines. Sudoku's ratios (gap 0.075·c, gutter 0.225·c),
    // with floors so a 15 dp cell still shows its gaps.
    fun minor(c: Float): Float = maxOf(1.5f, 0.075f * c)
    fun major(c: Float): Float = maxOf(4.5f, 0.225f * c)

    // Clue typography (§3.2).
    const val ROW_K = 0.86f
    const val ROW_MIN = 9f
    const val ROW_MAX = 28f
    const val ROW_WDTH = 25
    const val WGHT = 600
    const val GRAD_ON = 60
    const val GRAD_DONE = 0
    const val ROW_GAP_EM = 0.30f
    const val ROW_GAP_MIN = 3f
    const val COL_FILL = 0.66f
    const val COL2_FILL = 0.90f
    const val COL_MIN = 9f
    const val COL_MAX = 22f
    const val COL_LEAD_EM = 0.34f
    const val PAD_L = 6f
    const val PAD_R = 6f
    const val COL_PAD_B = 6f
    const val ZOOM_ROW_MAX = 17f
    const val ZOOM_COL_MAX = 15f

    /** Figure height of Google Sans Flex: `one.tf` yMax 1432 of 2000 units at every wdth. */
    const val FIG_H = 1432f / 2000f

    const val MARGIN = 8f
    const val CELL_MAX = 60f
    /**
     * Vertical bias of the clue + grid group in the field. The README's 0.6 (toward the thumb) left a
     * ~120 dp hole above the column clues on a 15×15, which is width-bound and so has ~200 dp spare; the
     * group is now centred in the free area (Phase 5).
     */
    const val BIAS = 0.5f

    // §3.5 zoom (Phase 4b).
    const val ZOOM_OPEN = 28f
    const val ZOOM_MIN = 24f
    const val ZOOM_MAX = 44f
    /** A fit cell under this gets zoom (20×20's 15.4 dp; 15×20's 21.2 dp does not). */
    const val ZOOM_BELOW = 18f

    fun pos(k: Int, c: Float): Float = k * (c + minor(c)) + (k / 5) * (major(c) - minor(c))
    fun span(n: Int, c: Float): Float = if (n <= 0) 0f else pos(n - 1, c) + c
}

/**
 * Tabular digit advances of the bundled Google Sans Flex, measured and drawn with one platform
 * [Paint] per (size, wdth, GRAD): the typeface is Compose's own resolution of [flex] (so every axis
 * and `opsz` match the rest of the app) and the paint carries `tnum` itself (CLAUDE.md: a measuring
 * paint that drops `fontFeatureSettings` silently measures proportional figures). Drawing through the
 * same paint keeps layout and ink in step, and costs a few µs a numeral where a TextLayoutResult
 * costs ~70 (measured on the emulator: 6 ms a frame for a 20×20's row band).
 */
class ClueFont(private val resolver: FontFamily.Resolver, private val density: Density) {
    private val paints = HashMap<Long, Paint>()
    private val adv = HashMap<Long, Float>()
    private val strings = Array(100) { it.toString() }

    private fun key(fs: Float, wdth: Int, grad: Int) = (Math.round(fs * 20f).toLong() shl 20) or (wdth.toLong() shl 8) or grad.toLong()

    fun paint(fs: Float, wdth: Int, grad: Int): Paint = paints.getOrPut(key(fs, wdth, grad)) {
        val tf = resolver.resolve(flex(fs, Blocks.WGHT, wdth, 0, grad), FontWeight(Blocks.WGHT)).value as Typeface
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = with(density) { fs.dp.toPx() }
            fontFeatureSettings = "tnum"
            letterSpacing = 0f
            isSubpixelText = true
        }
    }

    /** Advance of one tabular digit in em. GRAD never moves it (README §2.1), so it is measured at 0. */
    fun advEm(fs: Float, wdth: Int): Float = adv.getOrPut(key(fs, wdth, 0)) {
        val p = paint(fs, wdth, 0)
        p.measureText("0000000000") / 10f / p.textSize
    }

    /** The widest integer wdth (25–151) whose tabular advance is ≤ [targetEm]. Advance is monotonic in wdth. */
    fun solveWdth(fs: Float, targetEm: Float): Int {
        if (advEm(fs, 151) <= targetEm) return 151
        if (advEm(fs, 25) > targetEm) return 25
        var a = 25; var b = 151
        while (b - a > 1) { val m = (a + b) / 2; if (advEm(fs, m) <= targetEm) a = m else b = m }
        return a
    }

    fun text(n: Int): String = if (n in strings.indices) strings[n] else n.toString()
}

/** One puzzle's geometry at one cell size: cell, numeral sizes and widths, band sizes. All dp. */
data class PlayLayout(
    val w: Int,
    val h: Int,
    val c: Float,
    val fsR: Float,
    val fsC: Float,
    val rowGap: Float,
    val rowBand: Float,
    val colBand: Float,
    val colPitch: Float,
    val wdth1: Int,
    val wdth2: Int,
    val zoomed: Boolean,
) {
    val gridW: Float get() = Blocks.span(w, c)
    val gridH: Float get() = Blocks.span(h, c)
    fun pos(k: Int) = Blocks.pos(k, c)

    companion object {
        private fun rowFs(c: Float, zoom: Boolean) = (Blocks.ROW_K * c).coerceIn(Blocks.ROW_MIN, if (zoom) Blocks.ZOOM_ROW_MAX else Blocks.ROW_MAX)
        private fun rowGap(fs: Float) = maxOf(Blocks.ROW_GAP_MIN, Blocks.ROW_GAP_EM * fs)
        private fun digits(n: Int) = if (n >= 10) 2 else 1

        private fun rowBand(clues: Clues, fs: Float, font: ClueFont): Float {
            val a = font.advEm(fs, Blocks.ROW_WDTH) * fs
            val widest = clues.rows.maxOf { r -> if (r.isEmpty()) a else r.sumOf { digits(it) } * a + (r.size - 1) * rowGap(fs) }
            return widest + Blocks.PAD_L + Blocks.PAD_R
        }

        /** A one-digit numeral at wdth 151 fills 66 % of the cell (D2: short and wide). */
        private fun colFs(c: Float, zoom: Boolean, font: ClueFont): Float {
            var fs = 12f
            repeat(4) { fs = (Blocks.COL_FILL * c / font.advEm(fs, 151)).coerceIn(6f, 60f) }
            return fs.coerceIn(Blocks.COL_MIN, if (zoom) Blocks.ZOOM_COL_MAX else Blocks.COL_MAX)
        }

        private fun pitch(fs: Float) = Blocks.FIG_H * fs + maxOf(2.5f, Blocks.COL_LEAD_EM * fs)
        private fun colBand(clues: Clues, fs: Float) = maxOf(1, clues.cols.maxOf { it.size }) * pitch(fs) + Blocks.COL_PAD_B

        /**
         * The cell size for [clues] in a field of [availW] × [availH] dp (row band + grid share availW),
         * by fixed-point iteration: row band (from its own clues) → cell → numeral size → row band.
         * With [zoomC] the cell is given and the numerals take their zoom caps.
         */
        fun solve(clues: Clues, availW: Float, availH: Float, font: ClueFont, zoomC: Float? = null): PlayLayout {
            val w = clues.w; val h = clues.h
            val zoom = zoomC != null
            var c = zoomC ?: 20f
            if (zoomC == null) repeat(12) {
                val band = rowBand(clues, rowFs(c, false), font)
                // The gaps grow with the cell, so take them at the current estimate; the iteration settles it.
                val cw = (availW - band - (Blocks.span(w, c) - w * c)) / w
                val ch = (availH - colBand(clues, colFs(c, false, font)) - (Blocks.span(h, c) - h * c)) / h
                c = minOf(cw, ch, Blocks.CELL_MAX).coerceAtLeast(6f)
            }
            val fsR = rowFs(c, zoom)
            val fsC = colFs(c, zoom, font)
            return PlayLayout(
                w, h, c, fsR, fsC,
                rowGap = rowGap(fsR),
                rowBand = rowBand(clues, fsR, font),
                colBand = colBand(clues, fsC),
                colPitch = pitch(fsC),
                wdth1 = 151,
                wdth2 = font.solveWdth(fsC, Blocks.COL2_FILL * c / 2f / fsC),
                zoomed = zoom,
            )
        }
    }
}
