package app.materialnonogram.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.morphPath
import kotlinx.coroutines.launch

/**
 * Home → Play (Sudoku's two morphs, landing on the nonogram grid's own geometry):
 *
 * - **Start**: the big Start shape *is* the board. It flies to the grid's place, grows to its size and
 *   morphs from the tier's MaterialShape to a Square while its colour drains from primary to the
 *   wells' tone; then the cells pop out of it in a wave from the centre.
 * - **Continue**: the card's thumbnail *is* the board. Each of its blocks flies to its own cell in a
 *   wave from the middle, its colour draining into the cell's; the card opens to the screen and fades.
 *
 * The overlay is drawn in window coordinates above everything; Play hides its grid until the hand-off.
 * The game clock waits for the whole thing plus 100 ms.
 */
sealed interface Intro {
    /** `subdued`: it was the secondaryContainer "New game" shape, not the primary Start. */
    data class Start(val rect: Rect, val tier: Int, val subdued: Boolean = false) : Intro
    data class Continue(val card: Rect, val cardRadius: Float, val thumb: GridGeo) : Intro
}

/**
 * A grid's geometry in window pixels: origin of cell (0,0), the cell in dp, px per dp, the size, the
 * gap inside a 5×5 block and the gutter between blocks (px), and the part of it on screen.
 */
data class GridGeo(
    val origin: Offset,
    val cellDp: Float,
    val d: Float,
    val w: Int,
    val h: Int,
    val minor: Float,
    val major: Float,
    val visible: Rect? = null,
) {
    val cell: Float get() = cellDp * d
    fun pos(k: Int) = k * (cell + minor) + (k / 5) * (major - minor)
    fun tile(i: Int) = Rect(Offset(origin.x + pos(i % w), origin.y + pos(i / w)), Size(cell, cell))
    val bounds: Rect get() = visible ?: Rect(origin, Size(pos(w - 1) + cell, pos(h - 1) + cell))
}

@Composable
fun IntroOverlay(intro: Intro, board: GridGeo, game: Game, onReveal: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val p = remember(intro) { Animatable(0f) }
    val fade = remember(intro) { Animatable(1f) }
    val path = remember { Path() }
    LaunchedEffect(intro, board) {
        // Hand over to the real board as soon as the shape has all but landed, and fade right then:
        // a spring's last few pixels take as long as the rest.
        val fadeMs = if (intro is Intro.Start) 170 else 160
        val scope = this
        var revealed = false
        var fading: kotlinx.coroutines.Job? = null
        fun reveal() {
            if (revealed) return
            revealed = true
            onReveal()
            fading = scope.launch { fade.animateTo(0f, tween(fadeMs)) }
        }
        if (springs.reduced) p.snapTo(1f)
        else p.animateTo(1f, spring(0.86f, 240f, 0.002f)) { if (value > 0.93f) reveal() }
        reveal()
        fading?.join()
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val e = p.value
        val a = fade.value
        when (intro) {
            is Intro.Start -> {
                val r = lerp(intro.rect, board.bounds, e)
                val m = Morphs.of(Shp.difficulty[intro.tier], MaterialShapes.Square)
                morphPath(m, e.coerceIn(0f, 1.05f), r.size, path)
                path.translate(r.topLeft)
                rotate(-70f * (1f - e).coerceAtLeast(0f), r.center) {
                    val c0 = if (intro.subdued) cs.secondaryContainer else cs.primary
                    drawPath(path, lerp(c0, cs.surfaceContainerHigh, e.coerceIn(0f, 1f)).copy(alpha = a))
                }
            }
            is Intro.Continue -> {
                val full = Rect(Offset.Zero, size)
                val bg = lerp(intro.card, full, e.coerceIn(0f, 1f))
                val rad = intro.cardRadius * (1f - e.coerceIn(0f, 1f))
                drawRoundRect(cs.primaryContainer.copy(alpha = (1f - e).coerceIn(0f, 1f) * a), bg.topLeft, bg.size, CornerRadius(rad))
                val board0 = game.board
                val n = board.w * board.h
                val cx = board.w / 2f; val cy = board.h / 2f
                val maxD = kotlin.math.hypot(cx, cy).coerceAtLeast(1f)
                val clip = board.visible
                for (i in 0 until n) {
                    val dd = kotlin.math.hypot(i % board.w - cx, i / board.w - cy) / maxD
                    val q = ((e * 1.45f) - dd * 0.45f).coerceIn(0f, 1f)
                    val qe = 1f - (1f - q) * (1f - q)
                    val r = lerp(intro.thumb.tile(i), board.tile(i), qe)
                    val filled = board0[i] == Mark.FILLED
                    val c0 = if (filled) cs.tertiary else cs.surfaceContainerHighest
                    val c1 = if (filled) cs.tertiary else cs.surfaceContainerHigh
                    val draw = { drawRoundRect(lerp(c0, c1, qe).copy(alpha = a), r.topLeft, r.size, CornerRadius(r.width * 0.12f)) }
                    if (clip != null && qe > 0.5f) clipRect(clip.left, clip.top, clip.right, clip.bottom) { draw() } else draw()
                }
            }
        }
    }
}
