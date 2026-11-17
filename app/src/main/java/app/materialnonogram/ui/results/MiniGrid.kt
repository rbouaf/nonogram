package app.materialnonogram.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.materialnonogram.game.Mark

/** A small board's geometry in dp: [cell], the gap inside a 5×5 block, the gutter between blocks. */
class MiniGeo(val w: Int, val h: Int, val cell: Float, val gap: Float, val gut: Float) {
    fun pos(k: Int) = k * (cell + gap) + (k / 5) * (gut - gap)
    val width get() = pos(w - 1) + cell
    val height get() = pos(h - 1) + cell

    companion object {
        /** Blocks C's proportions (README §3.1) at a small size: hairline 0.12·c, gutter 0.36·c. */
        fun of(w: Int, h: Int, cell: Float) = MiniGeo(w, h, cell, cell * 0.12f, cell * 0.36f)

        /** The biggest cell that fits [w]×[h] in [box] dp, capped at [max]. */
        fun fit(w: Int, h: Int, box: Float, max: Float): MiniGeo {
            val n = maxOf(w, h)
            val c = box / (n + (n - 1) * 0.12f + ((n - 1) / 5) * 0.24f)
            return of(w, h, minOf(c, max))
        }
    }
}

/**
 * A board drawn small (Results' trophy, replay and strike boards; Stats' PB card). Fills in [fill]; empty
 * wells in [well] (null hides them, for the trophy); crosses as a small × in [cross] if given. [lit] cells
 * are drawn in [litColor] (a stroke in the replay); [bad] is the struck cell, a full [badColor] tile.
 */
@Composable
fun MiniGrid(
    geo: MiniGeo,
    board: ByteArray,
    fill: Color,
    well: Color?,
    modifier: Modifier = Modifier,
    cross: Color? = null,
    lit: Set<Int> = emptySet(),
    litColor: Color = fill,
    bad: Int = -1,
    badColor: Color = Color.Red,
    radius: Float = 0.24f,
) {
    Canvas(modifier.size(geo.width.dp, geo.height.dp)) {
        val d = density
        val c = geo.cell * d
        val r = CornerRadius(c * radius)
        for (i in 0 until geo.w * geo.h) {
            val o = Offset(geo.pos(i % geo.w) * d, geo.pos(i / geo.w) * d)
            val v = board.getOrElse(i) { Mark.UNKNOWN }
            when {
                i == bad -> drawRoundRect(badColor, o, Size(c, c), r)
                v == Mark.FILLED -> drawRoundRect(if (i in lit) litColor else fill, o, Size(c, c), r)
                else -> {
                    if (well != null) drawRoundRect(well, o, Size(c, c), r)
                    if (i in lit) drawRoundRect(litColor, o, Size(c, c), r, style = Stroke(maxOf(1f, c * 0.16f)))
                    if (v == Mark.CROSSED && cross != null && c >= 3.5f * d) {
                        val k = c * 0.2f
                        val m = o + Offset(c / 2, c / 2)
                        val col = if (i in lit) litColor else cross
                        drawLine(col, m + Offset(-k, -k), m + Offset(k, k), c * 0.12f, StrokeCap.Round)
                        drawLine(col, m + Offset(k, -k), m + Offset(-k, k), c * 0.12f, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** The solution as a bitmap board (filled / unknown). */
fun solutionBoard(bits: (Int) -> Boolean, n: Int): ByteArray = ByteArray(n) { if (bits(it)) Mark.FILLED else Mark.UNKNOWN }
