package app.materialnonogram.ui.play

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.materialnonogram.engine.Clues
import app.materialnonogram.ui.theme.flex

/**
 * Clue typography and the Play layout solver, "C · Blocks" (nonogram-mockups/html/README.md §3).
 * Every number here is the README's; dp throughout.
 */
object Blocks {
    // Grid C: 1 dp hairlines inside a 5×5 block, 4 dp gutters between blocks, no frame.
    const val MINOR = 1f
    const val MAJOR = 4f

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
    /** Vertical bias: 60 % of the spare height goes above the group, toward the thumb. */
    const val BIAS = 0.6f

    // §3.5 zoom (Phase 4b).
    const val ZOOM_OPEN = 28f
    const val ZOOM_MIN = 24f
    const val ZOOM_MAX = 44f
    /** A fit cell under this gets zoom (20×20's 15.4 dp; 15×20's 21.2 dp does not). */
    const val ZOOM_BELOW = 18f

    fun pos(k: Int, c: Float): Float = k * (c + MINOR) + (k / 5) * (MAJOR - MINOR)
    fun span(n: Int, c: Float): Float = if (n <= 0) 0f else pos(n - 1, c) + c
}

/**
 * Tabular digit advances of the bundled Google Sans Flex, measured with the same [TextStyle] the
 * clues are drawn with (so `tnum`, `opsz` and every axis match; CLAUDE.md: a measuring path must copy
 * `fontFeatureSettings`). Sizes are dp; `opsz` = size (D2).
 */
class ClueFont(private val measurer: TextMeasurer, private val density: Density) {
    private val adv = HashMap<Long, Float>()
    private val layouts = HashMap<String, TextLayoutResult>()

    fun style(fs: Float, wdth: Int, grad: Int): TextStyle = TextStyle(
        fontFamily = flex(fs, Blocks.WGHT, wdth, 0, grad),
        fontSize = with(density) { fs.dp.toSp() },
        lineHeight = with(density) { fs.dp.toSp() },
        fontFeatureSettings = "tnum",
        letterSpacing = 0.sp,
    )

    /** Advance of one tabular digit in em. GRAD never moves it (README §2.1), so it is measured at 0. */
    fun advEm(fs: Float, wdth: Int): Float {
        val key = (Math.round(fs * 20f).toLong() shl 16) or wdth.toLong()
        return adv.getOrPut(key) {
            val r = measurer.measure("0000000000", style(fs, wdth, 0), softWrap = false)
            r.size.width / 10f / with(density) { fs.dp.toPx() }
        }
    }

    /** The widest integer wdth (25–151) whose tabular advance is ≤ [targetEm]. Advance is monotonic in wdth. */
    fun solveWdth(fs: Float, targetEm: Float): Int {
        if (advEm(fs, 151) <= targetEm) return 151
        if (advEm(fs, 25) > targetEm) return 25
        var a = 25; var b = 151
        while (b - a > 1) { val m = (a + b) / 2; if (advEm(fs, m) <= targetEm) a = m else b = m }
        return a
    }

    /** One clue numeral, laid out once per (text, size, wdth, GRAD). Colour is applied when drawn. */
    fun numeral(text: String, fs: Float, wdth: Int, grad: Int): TextLayoutResult {
        val key = "$text|${Math.round(fs * 20f)}|$wdth|$grad"
        return layouts.getOrPut(key) { measurer.measure(text, style(fs, wdth, grad), softWrap = false) }
    }
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
                val cw = (availW - band - Blocks.span(w, 0f)) / w
                val ch = (availH - colBand(clues, colFs(c, false, font)) - Blocks.span(h, 0f)) / h
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
