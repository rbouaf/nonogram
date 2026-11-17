package app.materialnonogram.ui.play

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.materialnonogram.engine.Lines
import app.materialnonogram.game.Fx
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.game.Status
import app.materialnonogram.game.StrokeView
import app.materialnonogram.game.Tool
import app.materialnonogram.game.WhatIfPhase
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.morphPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Play's UI state that the board and the overlays above it share: the selected cell, the finger, the
 * long-press ring, a borrowed tool, and 20×20 zoom/pan (Phase 4b).
 */
@Stable
class PlayUi {
    /** Selected cell: its row and column are highlighted through both bands. */
    var sel by mutableIntStateOf(-1)
    /** The drawing finger, in the field's pixels (null when no stroke). */
    var finger by mutableStateOf<Offset?>(null)
    /** The long-press ring's centre (field pixels) while the finger rests on its start cell. */
    var holdAt by mutableStateOf<Offset?>(null)
    var holdSeq by mutableIntStateOf(0)
    /** A long-press borrowed the other tool for this stroke. */
    var borrowed by mutableStateOf(false)
    /** Zoom (cell dp) and pan (px, ≤ 0), when the puzzle zooms. */
    var zoomC by mutableFloatStateOf(Blocks.ZOOM_OPEN)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var panSet = false
    /** Showing the whole board below the drawing floor: input off, a pill says how to get back. */
    var peek by mutableStateOf(false)
    var peekByPinch = false
    /** The pinch's running cell size while it is under the floor. */
    var pinchC = Blocks.ZOOM_OPEN
    val peekA = Animatable(0f)
    /** The board can zoom (its fit cell is under [Blocks.ZOOM_BELOW]). */
    var zoomable by mutableStateOf(false)
    /** The field's and Play's top-left in the window; overlays draw in Play's box, so they need both. */
    var fieldRoot by mutableStateOf(Offset.Zero)
    var playRoot by mutableStateOf(Offset.Zero)
    /** The field's top-left in Play's root box. */
    val fieldAt: Offset get() = fieldRoot - playRoot
    /** The current cell size in px (for overlays). */
    var cellPx by mutableFloatStateOf(0f)
}

/** The field's geometry in its own pixels: which layout, where the grid sits, and the visible window. */
private class FieldGeo(
    val L: PlayLayout,
    val d: Float,
    val gridLeft: Float,
    val gridTop: Float,
    val viewW: Float,
    val viewH: Float,
    val panX: Float,
    val panY: Float,
    val fieldW: Float,
    val fieldH: Float,
) {
    val c = L.c * d
    val zoomed get() = L.zoomed
    fun x(k: Int) = gridLeft + panX + L.pos(k) * d
    fun y(k: Int) = gridTop + panY + L.pos(k) * d
    val gridWpx get() = L.gridW * d
    val gridHpx get() = L.gridH * d
    val view = Rect(gridLeft, gridTop, gridLeft + viewW, gridTop + viewH)

    /** Column (or row) index under a field coordinate, unclamped: off the grid gives −1 or ≥ n. */
    private fun index(local: Float): Int {
        val ld = local / d
        val mi = Blocks.minor(L.c)
        val block = 5 * L.c + 4 * mi + Blocks.major(L.c)
        val b = floor(ld / block).toInt()
        val rem = ld - b * block
        return b * 5 + (rem / (L.c + mi)).toInt().coerceIn(0, 4)
    }
    fun colAt(x: Float) = if (x - gridLeft - panX < 0) -1 else index(x - gridLeft - panX)
    fun rowAt(y: Float) = if (y - gridTop - panY < 0) -1 else index(y - gridTop - panY)

    fun firstCol() = if (!zoomed) 0 else (colAt(gridLeft) - 1).coerceAtLeast(0)
    fun lastCol() = if (!zoomed) L.w - 1 else (colAt(gridLeft + viewW) + 1).coerceAtMost(L.w - 1)
    fun firstRow() = if (!zoomed) 0 else (rowAt(gridTop) - 1).coerceAtLeast(0)
    fun lastRow() = if (!zoomed) L.h - 1 else (rowAt(gridTop + viewH) + 1).coerceAtMost(L.h - 1)

    /** The minimap in the band corner (zoomed only). */
    val miniK: Float get() = minOf((L.rowBand - 12f) / L.w, (L.colBand - 10f) / L.h, 4.2f) * d
    val mini: Rect get() {
        val mw = L.w * miniK; val mh = L.h * miniK
        val l = Blocks.MARGIN * d + (L.rowBand * d - mw) / 2
        val t = (L.colBand * d - mh) / 2 - 2 * d
        return Rect(l, t, l + mw, t + mh)
    }

    fun clampPanX(p: Float) = p.coerceIn(minOf(0f, viewW - gridWpx), 0f)
    fun clampPanY(p: Float) = p.coerceIn(minOf(0f, viewH - gridHpx), 0f)

    companion object {
        fun of(L: PlayLayout, d: Float, fieldW: Float, fieldH: Float, panX: Float, panY: Float): FieldGeo {
            val m = Blocks.MARGIN * d
            return if (L.zoomed) {
                val gl = m + L.rowBand * d
                val gt = L.colBand * d
                val vw = fieldW - m - gl; val vh = fieldH - gt
                val g = FieldGeo(L, d, gl, gt, vw, vh, 0f, 0f, fieldW, fieldH)
                FieldGeo(L, d, gl, gt, vw, vh, g.clampPanX(panX), g.clampPanY(panY), fieldW, fieldH)
            } else {
                val extra = fieldW - 2 * m - L.rowBand * d - L.gridW * d
                val gl = m + L.rowBand * d + maxOf(0f, extra / 2)
                val spare = fieldH - L.colBand * d - L.gridH * d
                val gt = L.colBand * d + maxOf(0f, spare) * Blocks.BIAS
                FieldGeo(L, d, gl, gt, L.gridW * d, L.gridH * d, 0f, 0f, fieldW, fieldH)
            }
        }
    }
}

/** Colours by role (README §3.3), resolved once per scheme. */
private class Roles(cs: ColorScheme) {
    val dark = cs.surface.luminance() < 0.5f
    /** The cookie mark: a hue the theme isn't using (Settings → Mark empty cells with). */
    val cookie = app.materialnonogram.ui.theme.cookieColor(cs, dark)
    /** A finishing line's ripple: wells toward primaryContainer, fills toward primary (Sudoku's highlight). */
    val ripWell = cs.primaryContainer
    val ripFill = cs.primary
    /**
     * Sudoku's empty-tile tone (its `tileTones`): in dark themes the stock containers sit ~1.1:1 above the
     * page, so a grid of separate tiles would melt into its gaps; a share of the text colour lifts them.
     */
    val well = if (dark) lerp(cs.surfaceContainerHighest, cs.onSurface, 0.07f) else cs.surfaceContainer
    val wellHl = lerp(well, cs.primary, 0.18f)
    val fill = cs.tertiary
    val fillHl = lerp(cs.tertiary, cs.primary, 0.22f)
    val provisional = cs.secondary
    val cross = cs.onSurfaceVariant
    val autoCross = cs.outline
    val ink = cs.onSurface
    val done = if (dark) cs.outline else lerp(cs.surface, cs.outline, 0.75f)
    val sel = cs.primary
    val pill = cs.primary.copy(alpha = 0.18f)
    val error = cs.error
    val errorWash = cs.errorContainer.copy(alpha = 0.4f)
    val hintWash = cs.secondaryContainer
    val ring = cs.primary
    val pin = cs.outlineVariant
    val miniBg = cs.surfaceContainerHigh
}

/** A row or column finishing: a wave runs along it from where the stroke ended (Sudoku's unit wave). */
private class Wave(val dist: IntArray, val t: Animatable<Float, *>, val end: Float)

private const val WAVE_STEP = 45f
/** Each tile's ripple: 150 ms up with an overshoot (Sudoku's hitIn), 330 ms back down. */
private const val WAVE_UP = 150f
private const val WAVE_LEN = 480f

private fun ripple(local: Float): Float =
    if (local < WAVE_UP) easeOutBack(local / WAVE_UP) else 0.5f * (1f + kotlin.math.cos(PI.toFloat() * (local - WAVE_UP) / (WAVE_LEN - WAVE_UP)))

/** MaterialShapes.Square at the cell size and the cookie at its mark size, rebuilt only when a size changes. */
private class TileShapes {
    private var tileC = -1f
    private val tilePath = Path()
    private var cookieS = -1f
    private val cookiePath = Path()
    fun tile(c: Float): Path {
        if (c != tileC) { morphPath(Morphs.of(Shp.square, Shp.square), 0f, Size(c, c), tilePath); tileC = c }
        return tilePath
    }
    /** About MaterialShapes.Square's corner, for rings drawn around a tile. */
    fun tileRadius(c: Float) = 0.28f * c
    fun cookie(s: Float): Path {
        if (s != cookieS) { morphPath(Morphs.of(MaterialShapes.Cookie4Sided, MaterialShapes.Cookie4Sided), 0f, Size(s, s), cookiePath); cookieS = s }
        return cookiePath
    }
}

/**
 * The Blocks board (README §3, "C · Blocks"): both clue bands and the grid on one Canvas, with the
 * stroke gesture (BUILD-PLAN §4 "The stroke") and 20×20 zoom (Phase 4b) on one pointerInput.
 *
 * Every animated value is read in the draw phase, so animations redraw without recomposing.
 */
@Composable
fun Field(
    game: Game,
    ui: PlayUi,
    longPress: Boolean,
    /** Settings → Look: empty cells marked with the cookie instead of the ×. */
    cookie: Boolean,
    onFlash: () -> Unit,
    modifier: Modifier = Modifier,
    revealed: Boolean = true,
    /** 0: cells pop out of the Start shape from the centre; 1: the Continue thumbnail already landed. */
    revealKind: Int = 0,
    onGeo: (GridGeo) -> Unit = {},
    /** No input until the clock may start. */
    locked: Boolean = false,
    /** Clue bands fade in with the chrome. */
    chrome: () -> Float = { 1f },
) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val buzz = LocalBuzz.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val font = remember(density, resolver) { ClueFont(resolver, density) }
    val roles = remember(cs) { Roles(cs) }
    val clues = game.deal.puzzle.clues
    val W = game.w; val H = game.h

    // ── Effects state ────────────────────────────────────────────────────────────────────────────
    val pops = remember(game) { mutableStateMapOf<Int, Animatable<Float, *>>() }
    val crossIn = remember(game) { mutableStateMapOf<Int, Animatable<Float, *>>() }
    val waves = remember(game) { mutableStateListOf<Wave>() }
    val shake = remember { Animatable(0f) }
    val lineShake = remember(game) { mutableStateMapOf<Int, Animatable<Float, *>>() }
    val blink = remember(game) { mutableStateMapOf<Int, Animatable<Float, *>>() }
    var burstCell by remember { mutableIntStateOf(-1) }
    val burst = remember { Animatable(0f) }
    val won = remember(game) { Animatable(if (game.status == Status.WON) 1f else 0f) }
    val breathe = remember { Animatable(0f) }
    // The deal-in: one clock in ms, each cell pops at its own delay from the centre.
    val deal = remember { Animatable(if (revealed) 1e6f else 0f) }
    LaunchedEffect(revealed) {
        if (!revealed || deal.value >= 1e5f) return@LaunchedEffect
        if (springs.reduced || revealKind == 1) { deal.snapTo(1e6f); return@LaunchedEffect }
        deal.animateTo(DEAL_STEP * 24f + 400f, tween((DEAL_STEP * 24f + 400f).toInt(), easing = LinearEasing))
        deal.snapTo(1e6f)
    }
    // A hint breathes: the line at stage 1, the cell from stage 2.
    val hint = game.hint
    LaunchedEffect(hint?.stage, hint?.hint?.cell) {
        if (hint == null) { breathe.snapTo(0f); return@LaunchedEffect }
        while (true) { breathe.animateTo(1f, tween(650)); breathe.animateTo(0.25f, tween(650)) }
    }

    fun pop(cell: Int) {
        if (springs.reduced) return
        val a = Animatable(0.55f)
        pops[cell] = a
        scope.launch { a.animateTo(1f, springs.hitIn()); if (pops[cell] === a) pops.remove(cell) }
    }
    fun drawIn(cell: Int) {
        if (springs.reduced) return
        val a = Animatable(0f)
        crossIn[cell] = a
        scope.launch { a.animateTo(1f, tween(150)); if (crossIn[cell] === a) crossIn.remove(cell) }
    }
    fun wave(cells: Map<Int, Int>) {
        if (springs.reduced || cells.isEmpty()) return
        val dist = IntArray(W * H) { -1 }
        for ((c, k) in cells) dist[c] = k
        val end = (cells.values.max()) * WAVE_STEP + WAVE_LEN
        val wv = Wave(dist, Animatable(0f), end)
        waves += wv
        scope.launch { wv.t.animateTo(end, tween(end.toInt(), easing = LinearEasing)); waves.remove(wv) }
    }
    fun lineWave(lines: List<Int>, origin: Int): Int {
        val m = HashMap<Int, Int>()
        val or = origin / W; val oc = origin % W
        for (l in lines) for (j in Lines.cells(W, H, l)) {
            val k = if (l < H) abs(j % W - oc) else abs(j / W - or)
            m[j] = minOf(m[j] ?: Int.MAX_VALUE, k)
        }
        wave(m)
        return (m.values.maxOrNull() ?: 0) + 1
    }

    // ── One-shot effects and haptics (BUILD-PLAN §4 table) ────────────────────────────────────────
    val onFlashNow by rememberUpdatedState(onFlash)
    LaunchedEffect(game) {
        var lastTick = 0L
        fun tick(to: Byte, count: Int = 0) {
            // Every fifth cell of a run is a firmer detent and always plays; the rest are rate-limited,
            // so a flick across 15 cells reads as a run of ticks, not one buzz.
            val now = SystemClock.uptimeMillis()
            if (count > 0 && count % 5 == 0) { lastTick = now; buzz.detent(); return }
            if (now - lastTick < 28) return
            lastTick = now
            when (to) { Mark.FILLED -> buzz.fill(); Mark.CROSSED -> buzz.cross(); else -> buzz.erase() }
        }
        game.fx.collect { fx ->
            when (fx) {
                is Fx.Pressed -> { tick(fx.to); if (fx.to == Mark.FILLED) pop(fx.cell) else if (fx.to == Mark.CROSSED) drawIn(fx.cell) }
                is Fx.Marked -> { tick(fx.to, fx.count); if (fx.to == Mark.FILLED) pop(fx.cell) else if (fx.to == Mark.CROSSED) drawIn(fx.cell) }
                is Fx.Retracted -> { pops.remove(fx.cell); crossIn.remove(fx.cell); buzz.retract() }
                Fx.StrokeCancelled -> buzz.cancel()
                is Fx.ToolSwitched -> buzz.mode()
                is Fx.AutoCrossed -> fx.cells.forEach { drawIn(it) }
                is Fx.LinesDone -> {
                    val steps = lineWave(fx.lines, fx.cell)
                    buzz.unitDone(fx.lines.size, steps.coerceIn(3, 12), WAVE_STEP.toInt())
                }
                is Fx.Mistake -> {
                    buzz.mistake()
                    scope.launch { delay(180); buzz.heartCrack() }
                    onFlashNow()
                    burstCell = fx.cell
                    scope.launch {
                        burst.snapTo(0f); burst.animateTo(1f, springs.hitIn())
                        delay(470)
                        burst.animateTo(0f, springs.hitOut())
                        burstCell = -1
                    }
                    if (!springs.reduced) scope.launch {
                        // The board kicks sideways and rings out: −11, 9, −6, 3, −1 dp (Sudoku board 1 keyframes).
                        for ((x, ms) in listOf(-11f to 42, 9f to 59, -6f to 59, 3f to 67, -1f to 76, 0f to 117)) shake.animateTo(x * density.density, tween(ms))
                    }
                }
                Fx.Lost -> { delay(620); buzz.lost() }
                is Fx.Contradiction -> {
                    // The refused note's "no": a short shake of the line, and its clue blinks.
                    buzz.lowTick(0.8f)
                    for (l in fx.lines) {
                        val s = Animatable(0f); lineShake[l] = s
                        scope.launch {
                            if (!springs.reduced) for (x in listOf(-5f, 4f, -2.5f, 1f)) s.animateTo(x * density.density, tween(40))
                            s.animateTo(0f, springs.hitOut()); lineShake.remove(l)
                        }
                        val b = Animatable(1f); blink[l] = b
                        scope.launch { b.animateTo(0f, tween(700)); blink.remove(l) }
                    }
                }
                Fx.Undone -> buzz.undo()
                Fx.Redone -> buzz.redo()
                is Fx.HintShown -> buzz.hint()
                is Fx.HintPlaced -> { buzz.hintPlace(); if (fx.to == Mark.FILLED) pop(fx.cell) else if (fx.to == Mark.CROSSED) drawIn(fx.cell) }
                is Fx.Checked -> if (fx.ok) buzz.checkOk() else buzz.refused()
                is Fx.WhatIf -> when (fx.phase) {
                    WhatIfPhase.BEGIN -> buzz.whatIfBegin()
                    WhatIfPhase.COMMIT -> buzz.whatIfKeep()
                    WhatIfPhase.DISCARD -> buzz.whatIfDrop()
                    WhatIfPhase.REFUSED -> buzz.refused()
                }
                is Fx.Won -> {
                    buzz.won()
                    // The crosses fade and the filled cells settle into the pattern; a wave runs out from the last cell.
                    val o = fx.cell.coerceIn(0, W * H - 1)
                    val m = HashMap<Int, Int>()
                    for (i in 0 until W * H) if (game.board[i] == Mark.FILLED) m[i] = (hypot((i % W - o % W).toFloat(), (i / W - o / W).toFloat()) * 0.7f).toInt()
                    wave(m)
                    scope.launch { won.animateTo(1f, tween(if (springs.reduced) 200 else 700)) }
                }
                Fx.FilledWrong -> buzz.mistake()
                else -> {}
            }
        }
    }

    BoxWithConstraints(modifier) {
        var rootAt by remember { mutableStateOf(Offset.Zero) }
        val fieldW = constraints.maxWidth.toFloat()
        val fieldH = constraints.maxHeight.toFloat()
        val d = density.density
        val fit = remember(game.deal, fieldW, fieldH) { PlayLayout.solve(clues, fieldW / d - 2 * Blocks.MARGIN, fieldH / d, font) }
        val zoomable = fit.c < Blocks.ZOOM_BELOW
        ui.zoomable = zoomable
        val zc = (Math.round(ui.zoomC * 4f) / 4f)
        val zl = if (zoomable) remember(game.deal, fieldW, fieldH, zc) { PlayLayout.solve(clues, fieldW / d - 2 * Blocks.MARGIN, fieldH / d, font, zoomC = zc) } else null
        val zoomGeo = zl?.let { FieldGeo.of(it, d, fieldW, fieldH, ui.panX, ui.panY) }
        if (zoomGeo != null && !ui.panSet) {
            // Opens on the middle of the board.
            ui.panX = zoomGeo.clampPanX((zoomGeo.viewW - zoomGeo.gridWpx) / 2); ui.panY = zoomGeo.clampPanY((zoomGeo.viewH - zoomGeo.gridHpx) / 2); ui.panSet = true
        }
        val fitGeo = FieldGeo.of(fit, d, fieldW, fieldH, 0f, 0f)
        val geo = if (zoomGeo != null && !ui.peek) zoomGeo else fitGeo
        ui.cellPx = geo.c
        val geoState = rememberUpdatedState(geo)
        // Debug builds log where the grid is, so tools/drive_nono.py can aim `adb shell input` at cells.
        if (app.materialnonogram.BuildConfig.DEBUG) {
            val root = rootAt
            LaunchedEffect(root, geo.x(0), geo.y(0), geo.c, geo.view) {
                android.util.Log.d("NonoGeo", "x0=${root.x + geo.x(0)} y0=${root.y + geo.y(0)} c=${geo.c} minor=${Blocks.minor(geo.L.c) * d} major=${Blocks.major(geo.L.c) * d} w=$W h=$H " +
                    "view=${root.x + geo.view.left},${root.y + geo.view.top},${root.x + geo.view.right},${root.y + geo.view.bottom} zoomed=${geo.zoomed} mini=${root.x + geo.mini.left},${root.y + geo.mini.top},${root.x + geo.mini.right},${root.y + geo.mini.bottom}")
            }
        }
        val zoomGeoState = rememberUpdatedState(zoomGeo)
        LaunchedEffect(ui.peek) { ui.peekA.animateTo(if (ui.peek) 1f else 0f, if (ui.peek) springs.effects() else springs.spatial()) }
        // Zoomed in, a hint brings what it points at into view: its line at stage 1, its cell after.
        val hintNow = game.hint
        LaunchedEffect(hintNow?.stage, hintNow?.hint?.cell) {
            val zg = zoomGeoState.value ?: return@LaunchedEffect
            val h = hintNow ?: return@LaunchedEffect
            if (ui.peek) return@LaunchedEffect
            val cell = h.hint.cell
            val line = h.hint.line
            val r = cell / W; val c = cell % W
            val showRow = h.stage >= 2 || line < H
            val showCol = h.stage >= 2 || line >= H
            val cx = zg.x(c) + zg.c / 2; val cy = zg.y(r) + zg.c / 2
            val inX = cx in zg.view.left + zg.c..zg.view.right - zg.c
            val inY = cy in zg.view.top + zg.c..zg.view.bottom - zg.c
            if ((!showCol || inX) && (!showRow || inY)) return@LaunchedEffect
            val fx = if (showCol && !inX) (zg.L.pos(c) + zg.L.c / 2) / zg.L.gridW else (zg.view.center.x - zg.gridLeft - zg.panX) / zg.gridWpx
            val fy = if (showRow && !inY) (zg.L.pos(r) + zg.L.c / 2) / zg.L.gridH else (zg.view.center.y - zg.gridTop - zg.panY) / zg.gridHpx
            panTo(ui, zg, fx, fy, springs)
        }

        val path = remember { Path() }
        val shapePath = remember { Path() }
        val shapes = remember { TileShapes() }
        val paused = game.paused
        val dealFrom = remember(game) { if (ui.sel >= 0) ui.sel else (H / 2) * W + W / 2 }

        fun DrawScope.drawField(g: FieldGeo, alpha: Float, chromeA: Float) {
            if (alpha <= 0.001f) return
            val L = g.L
            val c = g.c
            val one = d
            // One read of each state per frame; the pending start cell shows as its target (instant response).
            val b = game.board.copyOf()
            val stroke = game.stroke
            if (stroke != null && stroke.pending) b[stroke.start] = stroke.to
            val sat = game.satisfied
            val bad = game.contradicted
            val auto = game.auto
            val prov = game.provisional
            val sel = ui.sel
            val selR = if (sel >= 0) sel / W else -1
            val selC = if (sel >= 0) sel % W else -1
            val playing = game.status == Status.PLAYING
            val wonV = won.value
            val hv = game.hint
            val brV = breathe.value
            val hintLine = if (hv != null && hv.stage == 1) hv.hint.line else -1
            val hintCell = if (hv != null && hv.stage >= 2) hv.hint.cell else -1
            val dealT = deal.value
            val dealOn = revealKind == 0 && dealT < 1e5f
            val rip = if (waves.isEmpty()) null else FloatArray(W * H).also { r ->
                for (wv in waves) {
                    val t = wv.t.value
                    for (i in r.indices) {
                        val k = wv.dist[i]; if (k < 0) continue
                        val local = t - k * WAVE_STEP
                        if (local in 0f..WAVE_LEN) r[i] = maxOf(r[i], ripple(local))
                    }
                }
            }
            // Each effect map is read once per frame into a plain array: snapshot-map reads per cell add up.
            val shakeL = FloatArray(W + H).also { a -> for ((l, v) in lineShake) a[l] = v.value }
            val blinkL = FloatArray(W + H).also { a -> for ((l, v) in blink) a[l] = v.value }
            val popC = if (pops.isEmpty()) null else FloatArray(W * H) { 1f }.also { a -> for ((i, v) in pops) a[i] = v.value }
            val inC = if (crossIn.isEmpty()) null else FloatArray(W * H) { 1f }.also { a -> for ((i, v) in crossIn) a[i] = v.value }
            fun filled(x: Int, y: Int) = x in 0 until W && y in 0 until H && b[y * W + x] == Mark.FILLED
            fun dealScale(i: Int): Float {
                if (!revealed) return 0f
                if (!dealOn) return 1f
                val dist = hypot((i % W - dealFrom % W).toFloat(), (i / W - dealFrom / W).toFloat())
                val q = ((dealT - dist * DEAL_STEP) / 260f).coerceIn(0f, 1f)
                return easeOutBack(q)
            }
            val rows = g.firstRow()..g.lastRow()
            val cols = g.firstCol()..g.lastCol()
            /** An empty-cell mark at full size about (cx, cy): Symbols' × as two round-capped strokes, or the cookie. */
            fun DrawScope.mark(i: Int, cx: Float, cy: Float, c: Float, a0: Float) {
                val a = auto[i]
                val p = inC?.get(i) ?: 1f
                // An auto-cross is quieter: smaller, and the × in outline grey.
                val k = (if (a) 0.72f else 1f) * (0.55f + 0.45f * p)
                val provisional = prov?.get(i) == true
                if (cookie) {
                    val s = 0.6f * c
                    val col = if (provisional) roles.provisional else roles.cookie
                    val cc = col.copy(alpha = col.alpha * a0 * p.coerceIn(0f, 1f) * (if (a) 0.7f else 1f))
                    scale(k, k, Offset(cx, cy)) { translate(cx - s / 2, cy - s / 2) { drawPath(shapes.cookie(s), cc) } }
                    return
                }
                val half = 0.19f * c * k
                val sw = (if (a) 0.09f else 0.105f) * c
                val col = when { provisional -> roles.provisional; a -> roles.autoCross; else -> roles.cross }
                val cc = col.copy(alpha = col.alpha * a0 * p.coerceIn(0f, 1f))
                drawLine(cc, Offset(cx - half, cy - half), Offset(cx + half, cy + half), sw, StrokeCap.Round)
                drawLine(cc, Offset(cx + half, cy - half), Offset(cx - half, cy + half), sw, StrokeCap.Round)
            }
            val bandA = alpha * chromeA * (if (paused) 0f else 1f)

            // ── Row band: thin numerals (wdth 25), right-aligned to the grid ──────────────────────
            val rowClip = if (g.zoomed) Rect(0f, g.gridTop, g.gridLeft, g.fieldH) else Rect(0f, 0f, g.fieldW, g.fieldH)
            clipRect(rowClip.left, rowClip.top, rowClip.right, rowClip.bottom) {
                val left = Blocks.MARGIN * one
                for (y in rows) {
                    val top = g.y(y)
                    val sh = shakeL[y]
                    val bl = blinkL[y]
                    val lineA = alpha * chromeA
                    if (bad[y] || bl > 0f) pill(path, left, top, g.gridLeft, top + c, c / 2, true, roles.errorWash.copy(alpha = roles.errorWash.alpha * lineA * maxOf(if (bad[y]) 1f else 0f, bl)))
                    else if (y == selR && playing) pill(path, left, top, g.gridLeft, top + c, c / 2, true, roles.pill.copy(alpha = roles.pill.alpha * lineA))
                    if (y == hintLine) pill(path, left, top, g.gridLeft, top + c, c / 2, true, roles.hintWash.copy(alpha = brV * lineA))
                    if (bandA <= 0f) continue
                    val clue = clues.rows[y]
                    val color = when { bad[y] -> roles.error; sat[y] -> roles.done; y == selR && playing -> roles.sel; else -> roles.ink }
                    val grad = if (sat[y] && !bad[y]) Blocks.GRAD_DONE else Blocks.GRAD_ON
                    var x = g.gridLeft - Blocks.PAD_R * one + sh
                    val baseline = top + c / 2 + Blocks.FIG_H * L.fsR * one / 2
                    val p = font.paint(L.fsR, Blocks.ROW_WDTH, grad)
                    p.color = color.copy(alpha = color.alpha * bandA).toArgb()
                    for (k in clue.indices.reversed()) {
                        val t = font.text(clue[k])
                        x -= p.measureText(t)
                        drawContext.canvas.nativeCanvas.drawText(t, x, baseline, p)
                        x -= L.rowGap * one
                    }
                }
            }
            // ── Column band: wide numerals (wdth 151, two digits their own wdth), bottom-aligned ────
            val colClip = if (g.zoomed) Rect(g.gridLeft, 0f, g.gridLeft + g.viewW, g.gridTop) else Rect(0f, 0f, g.fieldW, g.fieldH)
            clipRect(colClip.left, colClip.top, colClip.right, colClip.bottom) {
                val bandTop = if (g.zoomed) 0f else g.gridTop - L.colBand * one
                for (x in cols) {
                    val line = H + x
                    val left = g.x(x)
                    val sh = shakeL[line]
                    val bl = blinkL[line]
                    val lineA = alpha * chromeA
                    if (bad[line] || bl > 0f) pill(path, left, bandTop, left + c, g.gridTop, c / 2, false, roles.errorWash.copy(alpha = roles.errorWash.alpha * lineA * maxOf(if (bad[line]) 1f else 0f, bl)))
                    else if (x == selC && playing) pill(path, left, bandTop, left + c, g.gridTop, c / 2, false, roles.pill.copy(alpha = roles.pill.alpha * lineA))
                    if (line == hintLine) pill(path, left, bandTop, left + c, g.gridTop, c / 2, false, roles.hintWash.copy(alpha = brV * lineA))
                    if (bandA <= 0f) continue
                    val clue = clues.cols[x]
                    val color = when { bad[line] -> roles.error; sat[line] -> roles.done; x == selC && playing -> roles.sel; else -> roles.ink }
                    val grad = if (sat[line] && !bad[line]) Blocks.GRAD_DONE else Blocks.GRAD_ON
                    val cx = left + c / 2
                    val fs = L.fsC
                    val figH = Blocks.FIG_H * fs * one
                    val pitch = L.colPitch * one
                    val argb = color.copy(alpha = color.alpha * bandA).toArgb()
                    for (k in clue.indices) {
                        val n = clue[clue.size - 1 - k]
                        val p = font.paint(fs, if (n >= 10) L.wdth2 else L.wdth1, grad)
                        p.color = argb
                        val t = font.text(n)
                        val baseline = g.gridTop - Blocks.COL_PAD_B * one - k * pitch - (pitch - figH) / 2 + sh
                        drawContext.canvas.nativeCanvas.drawText(t, cx - p.measureText(t) / 2f, baseline, p)
                    }
                }
            }

            // ── The grid ────────────────────────────────────────────────────────────────────────
            // 8 dp of overhang lets a column-0 outline or pop spill into the clue gap; once a zoomed grid is
            // panned left, column 0 slides under the row band, so the overhang shrinks to nothing or it
            // paints over the last digit of every row clue.
            val overhang = if (g.zoomed) (8 * one + g.panX).coerceAtLeast(0f) else 8 * one
            clipRect(g.view.left - overhang, if (g.zoomed) g.gridTop else 0f, if (g.zoomed) g.view.right else g.fieldW, g.fieldH) {
                // Sudoku's Tiles (user, 2026-11-17): every cell is its own MaterialShapes.Square tile with a
                // gap around it. A filled cell is the same tile in the fill colour; a finishing line morphs
                // its tiles toward SoftBurst in a wave (Sudoku's unit ripple), turning and swelling as it passes.
                val tile = shapes.tile(c)
                val tileR = shapes.tileRadius(c)
                val crossA = alpha * (1f - wonV)
                for (y in rows) for (x in cols) {
                    val i = y * W + x
                    val ds = dealScale(i); if (ds <= 0.001f) continue
                    val X = g.x(x) + shakeL[y]; val Y = g.y(y) + shakeL[H + x]
                    val cx = X + c / 2; val cy = Y + c / 2
                    val hl = playing && (y == selR || x == selC)
                    val isFill = !paused && b[i] == Mark.FILLED
                    val isProv = prov?.get(i) == true
                    var col = when {
                        isFill && isProv -> roles.provisional
                        isFill -> if (hl) roles.fillHl else roles.fill
                        hl -> roles.wellHl
                        else -> roles.well
                    }
                    if (!isFill) {
                        if (hintLine >= 0 && (if (hintLine < H) y == hintLine else x == hintLine - H)) col = lerp(col, roles.hintWash, brV * 0.9f)
                        if (hintCell == i) col = lerp(col, roles.hintWash, brV)
                    }
                    val rv = rip?.get(i) ?: 0f
                    if (rv > 0f) col = lerp(col, if (isFill) roles.ripFill else roles.ripWell, rv.coerceIn(0f, 1f) * 0.92f)
                    val sc = ds * (if (isFill) popC?.get(i) ?: 1f else 1f) * (1f + 0.22f * rv)
                    val ca = col.copy(alpha = col.alpha * alpha)
                    val crossed = !paused && b[i] == Mark.CROSSED && crossA > 0.001f
                    if (rv > 0.01f) {
                        // The ripple: Square → SoftBurst, +14° and +22 % at the crest (Sudoku's drawTile).
                        morphPath(Morphs.of(Shp.square, Shp.ripple), rv, Size(c, c), shapePath)
                        rotate(14f * rv, Offset(cx, cy)) {
                            scale(sc, sc, Offset(cx, cy)) {
                                translate(X, Y) { drawPath(shapePath, ca) }
                                if (crossed) mark(i, cx, cy, c, crossA)
                            }
                        }
                        continue
                    }
                    if (sc == 1f) translate(X, Y) { drawPath(tile, ca) }
                    else scale(sc, sc, Offset(cx, cy)) { translate(X, Y) { drawPath(tile, ca) } }
                    if (crossed) { if (ds == 1f) mark(i, cx, cy, c, crossA) else scale(ds, ds, Offset(cx, cy)) { mark(i, cx, cy, c, crossA) } }
                }
                // The hint's cell: a breathing ring.
                if (hintCell >= 0 && !paused) {
                    val X = g.x(hintCell % W); val Y = g.y(hintCell / W)
                    val o = 2f * one
                    drawRoundRect(roles.ring.copy(alpha = brV * alpha), Offset(X - o, Y - o), Size(c + 2 * o, c + 2 * o), CornerRadius(minOf(c / 2, 6f * one) + o), style = Stroke(2.5f * one))
                }
                // The stroke's extent: a primary capsule around the run.
                val fing = ui.finger
                if (stroke != null && stroke.axis >= 0 && fing != null && sel >= 0 && playing) {
                    val sr = stroke.start / W; val sc = stroke.start % W
                    val (x0, y0, x1, y1) = if (stroke.axis == StrokeView.AXIS_ROW) listOf(minOf(sc, selC), sr, maxOf(sc, selC), sr) else listOf(sc, minOf(sr, selR), sc, maxOf(sr, selR))
                    val o = 3f * one
                    val l = g.x(x0) - o; val t = g.y(y0) - o; val r = g.x(x1) + c + o; val bt = g.y(y1) + c + o
                    drawRoundRect(roles.ring.copy(alpha = alpha), Offset(l, t), Size(r - l, bt - t), CornerRadius(minOf(14f * one, c / 2 + o)), style = Stroke(2.5f * one))
                } else if (sel >= 0 && playing && !paused) {
                    // Focus ring: 2.5 dp primary, 1 dp outset.
                    val X = g.x(selC); val Y = g.y(selR)
                    val o = 1f * one + 1.25f * one
                    val rad = shapes.tileRadius(c) + o
                    drawRoundRect(roles.ring.copy(alpha = alpha), Offset(X - o, Y - o), Size(c + 2 * o, c + 2 * o), CornerRadius(rad), style = Stroke(2.5f * one))
                }
                // A refused mark: MaterialShapes.Burst at 1.6·c in error, turned −8°.
                val bc = burstCell
                if (bc >= 0 && burst.value > 0.01f) {
                    val X = g.x(bc % W); val Y = g.y(bc / W)
                    val s = c * 1.6f * burst.value.coerceAtLeast(0f)
                    morphPath(Morphs.of(MaterialShapes.Burst, MaterialShapes.Burst), 0f, Size(s, s), shapePath)
                    shapePath.translate(Offset(X + c / 2 - s / 2, Y + c / 2 - s / 2))
                    rotate(-8f, Offset(X + c / 2, Y + c / 2)) { drawPath(shapePath, roles.error.copy(alpha = alpha)) }
                }
            }

            // ── Zoom furniture: pinned band edges and the minimap in the band corner ─────────────
            if (g.zoomed) {
                drawRect(roles.pin.copy(alpha = alpha), Offset(g.gridLeft - one, 0f), Size(one, g.fieldH))
                drawRect(roles.pin.copy(alpha = alpha), Offset(Blocks.MARGIN * one, g.gridTop - one), Size(g.fieldW - 2 * Blocks.MARGIN * one, one))
                val m = g.mini
                val k = g.miniK
                drawRoundRect(roles.miniBg.copy(alpha = alpha), m.topLeft, m.size, CornerRadius(3f * one))
                if (!paused) for (i in 0 until W * H) if (b[i] == Mark.FILLED) drawRect(roles.fill.copy(alpha = alpha), Offset(m.left + (i % W) * k, m.top + (i / W) * k), Size(k, k))
                val vx = (-g.panX / g.gridWpx).coerceIn(0f, 1f); val vy = (-g.panY / g.gridHpx).coerceIn(0f, 1f)
                val vw = (g.viewW / g.gridWpx).coerceAtMost(1f); val vh = (g.viewH / g.gridHpx).coerceAtMost(1f)
                val vr = Rect(m.left + vx * m.width, m.top + vy * m.height, m.left + (vx + vw).coerceAtMost(1f) * m.width, m.top + (vy + vh).coerceAtMost(1f) * m.height)
                drawRoundRect(roles.sel.copy(alpha = 0.14f * alpha), vr.topLeft, vr.size, CornerRadius(2f * one))
                drawRoundRect(roles.sel.copy(alpha = alpha), vr.topLeft, vr.size, CornerRadius(2f * one), style = Stroke(1.5f * one))
            }
        }

        val exploring = rememberTouchExploration()
        val marked = game.board.count { it != Mark.UNKNOWN }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "Board, $W by $H, $marked of ${W * H} cells marked" +
                        if (exploring) ". Touch a clue to hear it; double-tap a cell to mark it with the current tool." else ""
                }
                .onGloballyPositioned { c ->
                    ui.fieldRoot = c.boundsInRoot().topLeft
                    val o = c.boundsInRoot().topLeft
                    rootAt = o
                    val g = geoState.value
                    val vis = Rect(o.x + g.gridLeft, o.y + g.gridTop, o.x + g.gridLeft + minOf(g.viewW, g.gridWpx), o.y + g.gridTop + minOf(g.viewH, g.gridHpx))
                    onGeo(GridGeo(Offset(o.x + g.x(0), o.y + g.y(0)), g.L.c, d, W, H, Blocks.minor(g.L.c) * d, Blocks.major(g.L.c) * d, vis))
                }
                .graphicsLayer { translationX = shake.value }
                .pointerInput(game, locked, longPress, zoomable) {
                    if (locked) return@pointerInput
                    val longMs = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (game.status != Status.PLAYING || game.paused) return@awaitEachGesture
                        val g0 = geoState.value
                        val zg = zoomGeoState.value
                        // The minimap jumps; a tap on the overview zooms back in there.
                        if (zg != null && !ui.peek && g0.mini.contains(down.position)) {
                            val fx = (down.position.x - g0.mini.left) / g0.mini.width
                            val fy = (down.position.y - g0.mini.top) / g0.mini.height
                            scope.launch { panTo(ui, zg, fx, fy, springs) }
                            buzz.select()
                            return@awaitEachGesture
                        }
                        if (zg != null && ui.peek && !ui.peekByPinch) {
                            if (g0.view.contains(down.position)) {
                                val fx = (down.position.x - g0.gridLeft) / g0.gridWpx
                                val fy = (down.position.y - g0.gridTop) / g0.gridHpx
                                ui.panX = zg.clampPanX(zg.viewW / 2 - fx * zg.gridWpx); ui.panY = zg.clampPanY(zg.viewH / 2 - fy * zg.gridHpx)
                            }
                            ui.peek = false
                            buzz.select()
                            return@awaitEachGesture
                        }
                        var drawing = false
                        var startCell = -1
                        val selBefore = ui.sel
                        /** The geometry as of now: auto-scroll moves the pan between frames, faster than recomposition. */
                        fun now(): FieldGeo {
                            val z = zoomGeoState.value
                            return if (z != null && !ui.peek) FieldGeo.of(z.L, d, z.fieldW, z.fieldH, ui.panX, ui.panY) else geoState.value
                        }
                        fun clampToView(p: Offset): Offset {
                            val g = now()
                            if (!g.zoomed) return p
                            return Offset(p.x.coerceIn(g.view.left, g.view.right - 1f), p.y.coerceIn(g.view.top, g.view.bottom - 1f))
                        }
                        fun extend(p0: Offset) {
                            val g = now()
                            val p = clampToView(p0)
                            game.extendStroke(g.rowAt(p.y).coerceIn(-1, H), g.colAt(p.x).coerceIn(-1, W))
                            ui.finger = p0
                            val s = game.stroke ?: return
                            val r = g.rowAt(p.y).coerceIn(0, H - 1); val c = g.colAt(p.x).coerceIn(0, W - 1)
                            ui.sel = when (s.axis) {
                                StrokeView.AXIS_ROW -> (s.start / W) * W + c
                                StrokeView.AXIS_COL -> r * W + s.start % W
                                else -> s.start
                            }
                            if (s.axis >= 0) ui.holdAt = null
                        }
                        if (!ui.peek && g0.view.contains(down.position)) {
                            val r = g0.rowAt(down.position.y); val c = g0.colAt(down.position.x)
                            if (r in 0 until H && c in 0 until W && game.beginStroke(r * W + c, game.tool, deferStart = longPress)) {
                                drawing = true; startCell = r * W + c
                                ui.sel = startCell; ui.finger = down.position; ui.borrowed = false
                                if (longPress) { ui.holdAt = Offset(g0.x(c) + g0.c / 2, g0.y(r) + g0.c / 2); ui.holdSeq++ }
                                down.consume()
                            }
                        }
                        var longDone = !longPress || !drawing
                        var transforming = false
                        val deadline = down.uptimeMillis + longMs
                        var last = down.uptimeMillis
                        var fingerAt = down.position
                        var scrolledAt = 0L
                        /**
                         * Zoomed in, a stroke that reaches the visible edge along its own axis scrolls the board
                         * under the finger (Phase 5 gap a): the deeper into the edge band, the faster, up to ten
                         * cells a second. Returns the pan velocity in px/ms.
                         */
                        fun edgeVelocity(): Offset {
                            val g = now()
                            val s = game.stroke ?: return Offset.Zero
                            if (!g.zoomed || !drawing || s.axis < 0 || s.stopped) return Offset.Zero
                            val band = maxOf(g.c * 0.8f, 20f * d)
                            val max = 10f * (g.c + Blocks.minor(g.L.c) * d) / 1000f
                            fun v(p: Float, lo: Float, hi: Float): Float = when {
                                p > hi - band -> -max * ((p - (hi - band)) / band).coerceIn(0.25f, 1f)
                                p < lo + band -> max * (((lo + band) - p) / band).coerceIn(0.25f, 1f)
                                else -> 0f
                            }
                            return if (s.axis == StrokeView.AXIS_ROW) Offset(v(fingerAt.x, g.view.left, g.view.right), 0f)
                            else Offset(0f, v(fingerAt.y, g.view.top, g.view.bottom))
                        }
                        while (true) {
                            val onStart = game.stroke?.let { it.axis < 0 && !it.stopped } == true
                            val waitLong = drawing && !longDone && onStart
                            val scroll = edgeVelocity()
                            val scrolling = scroll != Offset.Zero
                            if (!scrolling) scrolledAt = 0L
                            val timeout: Long? = when {
                                waitLong -> maxOf(1L, deadline - last)
                                scrolling -> 16L
                                else -> null
                            }
                            val ev: PointerEvent? = if (timeout != null) withTimeoutOrNull(timeout) { awaitPointerEvent() } else awaitPointerEvent()
                            if (ev == null) {
                                if (waitLong) {
                                    longDone = true
                                    if (game.switchStrokeTool()) ui.borrowed = true
                                    ui.holdAt = null
                                } else if (scrolling) {
                                    val t = SystemClock.uptimeMillis()
                                    val dt = if (scrolledAt == 0L) 16L else (t - scrolledAt).coerceIn(1L, 50L)
                                    scrolledAt = t
                                    val g = now()
                                    ui.panX = g.clampPanX(ui.panX + scroll.x * dt)
                                    ui.panY = g.clampPanY(ui.panY + scroll.y * dt)
                                    extend(fingerAt)
                                }
                                continue
                            }
                            last = ev.changes.maxOf { it.uptimeMillis }
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2 || transforming) {
                                // A second finger cancels the stroke (it is reverted) and becomes pinch/pan.
                                if (drawing) { game.cancelStroke(); drawing = false; ui.finger = null; ui.holdAt = null; ui.borrowed = false; ui.sel = selBefore }
                                if (zg != null && pressed >= 2) {
                                    // Each pinch starts its running size from the current zoom.
                                    if (!transforming && !ui.peek) ui.pinchC = ui.zoomC
                                    transforming = true
                                    transform(ev, ui, zoomGeoState.value ?: zg, d, buzz)
                                    ev.changes.forEach { it.consume() }
                                }
                                if (pressed == 0) break
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                // The lift can carry the last stretch of a flick: walk it before ending.
                                if (drawing) { for (h in ch.historical) extend(h.position); extend(ch.position); game.endStroke() }
                                drawing = false
                                break
                            }
                            if (drawing) {
                                // Never skip a cell: walk every historical sample, then the current position.
                                for (h in ch.historical) extend(h.position)
                                extend(ch.position)
                                fingerAt = ch.position
                                ch.consume()
                                if (game.stroke == null) drawing = false
                            }
                        }
                        if (drawing) game.endStroke()
                        ui.finger = null; ui.holdAt = null; ui.borrowed = false
                        if (ui.peekByPinch) { ui.peekByPinch = false; ui.peek = false }
                    }
                }
                .drawBehind {
                    val pk = ui.peekA.value
                    val chromeA = chrome()
                    if (zoomGeo != null && pk > 0.001f && pk < 0.999f) {
                        drawField(zoomGeo, 1f - pk, chromeA)
                        drawField(fitGeo, pk, chromeA)
                    } else drawField(geo, 1f, chromeA)
                },
        )
        // The board is one Canvas, so TalkBack sees nothing in it. With touch exploration on, invisible nodes
        // sit over every visible clue and cell: clues read their numbers, cells their state, and a cell's
        // double-tap marks it with the current tool (custom actions offer both tools). Phase 5 gap b.
        if (exploring && !locked && game.status == Status.PLAYING && !game.paused) A11yLayer(game, geo, clues)
    }
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    androidx.compose.runtime.DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

private fun stateWord(v: Byte) = when (v) { Mark.FILLED -> "filled"; Mark.CROSSED -> "crossed"; else -> "empty" }

@Composable
private fun A11yLayer(game: Game, g: FieldGeo, clues: app.materialnonogram.engine.Clues) {
    val W = game.w; val H = game.h
    val b = game.board
    val sat = game.satisfied
    val bad = game.contradicted
    val tool = game.tool
    val d = LocalDensity.current.density
    @Composable
    fun node(l: Float, t: Float, w: Float, h: Float, mod: Modifier) {
        androidx.compose.foundation.layout.Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(l.toInt(), t.toInt()) }
                .size((w / d).dp, (h / d).dp).then(mod),
        )
    }
    fun clueText(line: Int): String {
        val c = clues.line(line)
        val name = if (line < H) "Row ${line + 1}" else "Column ${line - H + 1}"
        return "$name clue " + (if (c.isEmpty()) "0" else c.joinToString(" ")) + when { bad[line] -> ", can't fit any more"; sat[line] -> ", done"; else -> "" }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        val bandTop = if (g.zoomed) 0f else g.gridTop - g.L.colBand * d
        // Only cells whose centre is on screen: a zoomed board draws a margin of hidden ones under the bands.
        val cols = (g.firstCol()..g.lastCol()).filter { g.x(it) + g.c / 2 in g.view.left..g.view.right }
        val rows = (g.firstRow()..g.lastRow()).filter { g.y(it) + g.c / 2 in g.view.top..g.view.bottom }
        for (x in cols) node(g.x(x), bandTop, g.c, g.gridTop - bandTop, Modifier.semantics { contentDescription = clueText(H + x) })
        for (y in rows) {
            node(Blocks.MARGIN * d, g.y(y), g.gridLeft - Blocks.MARGIN * d, g.c, Modifier.semantics { contentDescription = clueText(y) })
            for (x in cols) {
                val i = y * W + x
                val v = b[i]
                val act = if (tool == Tool.FILL) (if (v == Mark.FILLED) "Clear" else "Fill") else (if (v == Mark.CROSSED) "Clear" else "Cross")
                node(g.x(x), g.y(y), g.c, g.c, Modifier.semantics {
                    contentDescription = "Row ${y + 1}, column ${x + 1}, ${stateWord(v)}"
                    role = androidx.compose.ui.semantics.Role.Button
                    onClick(label = act) { game.tap(i, tool); true }
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Fill") { game.tap(i, Tool.FILL) },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Cross") { game.tap(i, Tool.CROSS) },
                    )
                })
            }
        }
    }
}

private const val DEAL_STEP = 26f
/** The overview opens when a pinch reaches 70 % of the zoom floor (16.8 dp; a fitted 20×20 is 15.4). */
private const val PEEK_AT = Blocks.ZOOM_MIN * 0.7f
private const val PEEK_BACK = PEEK_AT * 1.12f

private fun easeOutBack(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val c1 = 1.4f; val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/** A band highlight: rounded at the free end ([rowBand]: left; else top), square where it meets the grid. */
private fun DrawScope.pill(path: Path, l: Float, t: Float, r: Float, b: Float, rad: Float, rowBand: Boolean, color: Color) {
    if (color.alpha <= 0f) return
    path.rewind()
    val z = CornerRadius.Zero; val k = CornerRadius(rad)
    path.addRoundRect(if (rowBand) RoundRect(l, t, r, b, k, z, z, k) else RoundRect(l, t, r, b, k, k, z, z))
    drawPath(path, color)
}

/** Spring the zoomed view so the minimap point ([fx], [fy]) is centred. */
private suspend fun panTo(ui: PlayUi, g: FieldGeo, fx: Float, fy: Float, springs: app.materialnonogram.ui.kit.Springs) {
    val tx = g.clampPanX(g.viewW / 2 - fx * g.gridWpx); val ty = g.clampPanY(g.viewH / 2 - fy * g.gridHpx)
    val ax = Animatable(ui.panX); val ay = Animatable(ui.panY)
    kotlinx.coroutines.coroutineScope {
        launch { ax.animateTo(tx, springs.spatial()) { ui.panX = value } }
        launch { ay.animateTo(ty, springs.spatial()) { ui.panY = value } }
    }
}

/**
 * Two fingers: pinch about the centroid and pan. The zoom stops at its floor ([Blocks.ZOOM_MIN]); only a
 * pinch carried well past it, to about the overview's own cell size ([PEEK_AT]), opens the overview,
 * and it closes again a little above that ([PEEK_BACK]), so a pinch hovering at the floor can't flicker
 * between them. It used to open the moment the floor was crossed — 15 % from the opening zoom — and
 * fired on ordinary zooming out (user, 2026-11-17).
 */
private fun transform(ev: PointerEvent, ui: PlayUi, g: FieldGeo, d: Float, buzz: app.materialnonogram.ui.kit.Buzz) {
    val zoom = ev.calculateZoom()
    val pan = ev.calculatePan()
    val centroid = ev.calculateCentroid(useCurrent = true)
    if (centroid == Offset.Unspecified) return
    // The button's overview ignores pinches; a tap zooms back in.
    if (ui.peek && !ui.peekByPinch) return
    val raw = (ui.pinchC * zoom).coerceIn(8f, Blocks.ZOOM_MAX * 1.25f)
    ui.pinchC = raw
    if (ui.peek) {
        if (raw < PEEK_BACK) return
        ui.peek = false; ui.peekByPinch = false
        buzz.zoomStop()
    } else if (raw < PEEK_AT) {
        ui.peek = true; ui.peekByPinch = true
        buzz.overview()
        return
    }
    val newC = raw.coerceIn(Blocks.ZOOM_MIN, Blocks.ZOOM_MAX)
    if ((newC == Blocks.ZOOM_MIN || newC == Blocks.ZOOM_MAX) && ui.zoomC != newC) buzz.zoomStop()
    val oldW = Blocks.span(g.L.w, ui.zoomC) * d; val newW = Blocks.span(g.L.w, newC) * d
    val oldH = Blocks.span(g.L.h, ui.zoomC) * d; val newH = Blocks.span(g.L.h, newC) * d
    val fx = (centroid.x - g.gridLeft - ui.panX) / oldW
    val fy = (centroid.y - g.gridTop - ui.panY) / oldH
    ui.zoomC = newC
    // Below the floor the running size keeps going down (toward the overview); above the ceiling it doesn't bank.
    ui.pinchC = raw.coerceAtMost(Blocks.ZOOM_MAX)
    ui.panX = (centroid.x - g.gridLeft - fx * newW + pan.x).coerceIn(minOf(0f, g.viewW - newW), 0f)
    ui.panY = (centroid.y - g.gridTop - fy * newH + pan.y).coerceIn(minOf(0f, g.viewH - newH), 0f)
}
