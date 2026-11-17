package app.materialnonogram.ui.play

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import app.materialnonogram.data.Settings
import app.materialnonogram.game.Fx
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.game.Status
import app.materialnonogram.game.Tool
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.MorphShape
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.morphPath
import app.materialnonogram.ui.kit.ts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val TIER_NAMES = listOf("Easy", "Medium", "Hard", "Expert", "Master")

/**
 * Play, "C · Blocks" (nonogram-mockups/html/README.md §3): Sudoku's two header bars, the field (clue
 * bands + grid, `Field`), a 40 dp strip for words, and the 72 dp tool row. The count bubble, the
 * long-press ring and the peek pill float above everything in this box's coordinates.
 */
@Composable
fun PlayScreen(
    game: Game,
    settings: Settings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onHint: () -> Unit,
    onFinished: () -> Unit,
    halfwayLine: (timerShown: Boolean) -> Pair<String, String?> = { "Halfway there" to null },
    introActive: Boolean = false,
    revealed: Boolean = true,
    introKind: Int = 0,
    onBoardGeo: (GridGeo) -> Unit = {},
    thinking: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val springs = LocalSprings.current
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    val ui = remember(game) { PlayUi() }
    var timerHidden by rememberSaveable { mutableStateOf(!settings.showTimer) }

    // ── The clock: only while resumed and unpaused, and not before the Home → Play morph has landed. ─
    var ready by remember { mutableStateOf(!introActive) }
    LaunchedEffect(introActive) { if (!introActive && !ready) { delay(100); ready = true } }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(game, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                if (ready) game.tick(now - last)
                last = now
            }
        }
    }
    val chrome = remember { Animatable(if (introActive) 0f else 1f) }
    LaunchedEffect(Unit) { if (chrome.value < 1f) { delay(180); chrome.animateTo(1f, springs.slow()) } }
    // Leaving the app pauses and hides the board.
    LaunchedEffect(game, owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) game.pause(true) }
        owner.lifecycle.addObserver(obs)
    }
    // ── End of game: the finished moment plays out on the board, then Results. ──────────────────
    var checkWord by remember { mutableStateOf<Boolean?>(null) }
    var filledWrong by remember { mutableStateOf(false) }
    LaunchedEffect(game) {
        game.fx.collect { fx ->
            when (fx) {
                is Fx.Won -> { delay(1800); onFinished() }
                Fx.Lost -> { delay(1500); onFinished() }
                is Fx.Checked -> launch { checkWord = fx.ok; delay(2600); checkWord = null }
                Fx.FilledWrong -> launch { filledWrong = true; delay(3000); filledWrong = false }
                else -> {}
            }
        }
    }
    // Aids may change mid-run; the run keeps the most assisted band it reached (Game.setAids).
    LaunchedEffect(settings.autoCross, settings.contradictionFlag) {
        val flag = settings.contradictionFlag && !game.rules.flagMistakes
        if (game.autoCross != settings.autoCross || game.contradictionFlag != flag) game.setAids(settings.autoCross, settings.contradictionFlag)
    }

    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().onGloballyPositioned { ui.playRoot = it.boundsInRoot().topLeft }) {
        val screenW = constraints.maxWidth.toFloat()
        Column(Modifier.fillMaxSize()) {
            // bar 1: back · tier/size chip · (zoom) · (check) · what-if · pause · settings
            val crowded = ui.zoomable || game.rules.checkButton
            val ib = if (crowded) 44.dp else 48.dp
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = chrome.value; translationY = -24.dp.toPx() * (1f - chrome.value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquishIcon("arrow_back", "Back to Home", onBack, size = ib)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).padding(end = 2.dp), contentAlignment = Alignment.CenterStart) {
                    LevelChip(game, settings, timerHidden, halfwayLine)
                }
                if (ui.zoomable) SquishIcon(if (ui.peek) "zoom_in_map" else "fit_screen", if (ui.peek) "Zoom back in" else "See the whole board", {
                    if (ui.peek) buzz.select() else buzz.overview(); ui.peekByPinch = false; ui.peek = !ui.peek
                }, size = ib, iconSize = 22.dp)
                if (game.rules.checkButton) SquishIcon("fact_check", "Check for wrong marks", { game.check() }, size = ib, iconSize = 22.dp)
                SquishIcon("alt_route", if (game.inWhatIf) "Leave what-if" else "What if…", {
                    // The game's WhatIf effect plays the haptic (begin, drop, or refused).
                    if (game.inWhatIf) game.discardWhatIf() else game.beginWhatIf()
                }, size = ib, iconSize = 22.dp, container = if (game.inWhatIf) cs.secondaryContainer else Color.Transparent, content = if (game.inWhatIf) cs.onSecondaryContainer else cs.onSurfaceVariant)
                SquishIcon(if (game.paused) "play_arrow" else "pause", if (game.paused) "Resume" else "Pause", {
                    buzz.toggle(); game.pause(!game.paused)
                }, size = ib)
                SquishIcon("tune", "Settings", onSettings, size = ib, iconSize = 22.dp)
            }
            // bar 2: lives (or "mistakes not flagged") · timer
            Row(
                Modifier.fillMaxWidth().height(62.dp).padding(start = 18.dp, end = 12.dp)
                    .graphicsLayer { alpha = chrome.value; translationY = -16.dp.toPx() * (1f - chrome.value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (game.rules.flagMistakes) Lives(game) else NoNet()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SquishIcon(if (timerHidden) "visibility_off" else "visibility", if (timerHidden) "Show timer" else "Hide timer", {
                        buzz.toggle(); timerHidden = !timerHidden
                    }, iconSize = 22.dp)
                    TimerText(game, timerHidden)
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val dead = game.status == Status.LOST
                Field(
                    game, ui, settings.longPress, settings.cookieCross,
                    onFlash = { scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(520)) } },
                    modifier = Modifier.fillMaxSize()
                        .then(if (game.paused) Modifier.blur(9.dp) else if (dead) Modifier.blur(5.dp) else Modifier)
                        .graphicsLayer { alpha = if (dead) 0.55f else 1f },
                    revealed = revealed, revealKind = introKind, onGeo = onBoardGeo,
                    locked = !ready, chrome = { chrome.value },
                )
                if (game.paused) PausedOverlay { buzz.toggle(); game.pause(false) }
                if (dead) LostMark()
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Strip(game, thinking, checkWord, filledWrong)
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalCookieCross provides settings.cookieCross) { Tools(game, ui, settings, onHint, Modifier.graphicsLayer { alpha = chrome.value; translationY = 48.dp.toPx() * (1f - chrome.value) }) }
            Spacer(Modifier.height(14.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalCookieCross provides settings.cookieCross) { Overlays(game, ui, screenW) }
        // The screen edge flashes error on a mistake.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.drawBehind {
                if (flash.value > 0f) {
                    val e = cs.error
                    drawRect(Brush.radialGradient(listOf(Color.Transparent, e.copy(alpha = 0.55f)), center = center, radius = size.maxDimension * 0.75f))
                    drawRect(e, style = Stroke(width = 3.dp.toPx()))
                }
            },
        )
    }
}

// ── Floating above the board: count bubble, long-press ring and tip, peek pill ───────────────────

@Composable
private fun Overlays(game: Game, ui: PlayUi, screenW: Float) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val d = density.density
    val longMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val ring = remember { Animatable(0f) }
    LaunchedEffect(ui.holdSeq) {
        ring.snapTo(0f)
        if (ui.holdAt != null) ring.animateTo(1f, tween(longMs.toInt(), easing = LinearEasing))
    }
    val hold = ui.holdAt
    if (hold != null) {
        val at = ui.fieldAt + hold
        Canvas(Modifier.fillMaxSize()) {
            val p = ring.value
            // Taps never see it: the ring only fades in once the hold is clearly a hold.
            val a = ((p - 0.25f) / 0.2f).coerceIn(0f, 1f)
            if (a <= 0f) return@Canvas
            val r = 30.dp.toPx()
            drawCircle(cs.tertiary.copy(alpha = 0.25f * a), r, at, style = Stroke(4.dp.toPx()))
            drawArc(cs.tertiary.copy(alpha = a), -90f, 360f * p, false, Offset(at.x - r, at.y - r), Size(2 * r, 2 * r), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
    val stroke = game.stroke
    val finger = ui.finger
    if (stroke != null && finger != null && game.status == Status.PLAYING) {
        val f = ui.fieldAt + finger
        val tip = ui.borrowed && stroke.axis < 0
        if (stroke.axis >= 0 || tip) {
            Bubble(
                at = f, column = stroke.axis == 1, screenW = screenW, d = d,
                tool = stroke.tool, text = if (tip) (if (stroke.tool == Tool.CROSS) "Cross" else "Fill") else stroke.count.toString(), word = tip,
            )
        }
    }
    if (ui.peek) {
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, (ui.fieldAt.y + 12 * d).roundToInt()) }, contentAlignment = Alignment.TopCenter) {
            Surface(color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.height(36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Sym["zoom_in_map"], null, Modifier.size(18.dp))
                    Text(if (ui.peekByPinch) "Release to zoom back" else "Tap a spot to zoom in", style = ts(14f, 600))
                }
            }
        }
    }
}

/**
 * The live count (README §3.4): a 48 dp pill, min 64 dp, `inverseSurface`, 28 sp wght 620 tnum, the
 * tool's 16 dp glyph in `inversePrimary`, a 14 dp tail. Row strokes: centre 76 dp above the touch
 * point, clamped 12 dp inside the screen. Column strokes: 60 dp beside the finger, toward the middle.
 */
@Composable
private fun Bubble(at: Offset, column: Boolean, screenW: Float, d: Float, tool: Tool, text: String, word: Boolean) {
    val cs = MaterialTheme.colorScheme
    var size by remember { mutableStateOf(IntSize(0, 0)) }
    val bw = size.width.toFloat().coerceAtLeast(64 * d)
    val bh = 48 * d
    val left: Float
    val top: Float
    val tailLeft: Boolean?
    if (!column) {
        left = (at.x - bw / 2).coerceIn(12 * d, screenW - 12 * d - bw)
        top = (at.y - 76 * d - bh / 2).coerceAtLeast(4 * d)
        tailLeft = null
    } else {
        val right = at.x > screenW / 2
        left = if (right) at.x - 60 * d - bw else at.x + 60 * d
        top = at.y - bh / 2
        tailLeft = !right
    }
    Box(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.onSizeChanged { size = it }) {
        Canvas(Modifier.matchParentSizeCompat(bw, bh, d)) {
            // The tail: a 14 dp square turned 45°, under the pill.
            val t = 14.dp.toPx()
            val c = if (tailLeft == null) Offset((at.x - left).coerceIn(t, bw - t), bh) else if (tailLeft) Offset(0f, bh / 2) else Offset(bw, bh / 2)
            withTransform({ rotate(45f, c) }) { drawRoundRect(cs.inverseSurface, Offset(c.x - t / 2, c.y - t / 2), Size(t, t), CornerRadius(2.dp.toPx())) }
        }
        Surface(color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
            Row(
                Modifier.height(48.dp).widthIn(min = 64.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                ToolGlyph(tool, if (word) 18.dp else 16.dp, cs.inversePrimary)
                Text(text, maxLines = 1, style = if (word) ts(17f, 650) else ts(28f, 620, tnum = true, lineHeight = 28f))
            }
        }
    }
}

private fun Modifier.matchParentSizeCompat(w: Float, h: Float, d: Float) = this.size((w / d).dp, (h / d).dp)

/** Settings → Look → "Mark empty cells with": the cookie instead of the ×. Provided by [PlayScreen]. */
val LocalCookieCross = androidx.compose.runtime.compositionLocalOf { false }

/** Fill is a rounded square (the filled cell); Cross is Symbols' close, or the four-sided cookie. */
@Composable
fun ToolGlyph(tool: Tool, size: androidx.compose.ui.unit.Dp, color: Color, cookie: Boolean = LocalCookieCross.current) {
    if (tool == Tool.CROSS && cookie) Canvas(Modifier.size(size)) {
        val s = this.size.width * 0.8f
        val p = app.materialnonogram.ui.kit.morphPath(app.materialnonogram.ui.kit.Morphs.of(androidx.compose.material3.MaterialShapes.Cookie4Sided, androidx.compose.material3.MaterialShapes.Cookie4Sided), 0f, Size(s, s))
        translate((this.size.width - s) / 2, (this.size.height - s) / 2) { drawPath(p, color) }
    }
    else if (tool == Tool.CROSS) Icon(Sym["close"], null, Modifier.size(size), tint = color)
    else Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        drawRoundRect(color, Offset(4 * k, 4 * k), Size(16 * k, 16 * k), CornerRadius(4.5f * k))
    }
}

// ── Header pieces ──────────────────────────────────────────────────────────────────────────────

/**
 * Tier shape + name + size (`_nono.css .lvl`). At halfway it becomes the encouragement: it fills
 * with tertiaryContainer, the shape spins half a turn, and two lines replace the name for 2.6 s.
 */
@Composable
private fun LevelChip(game: Game, settings: Settings, timerHidden: Boolean, line: (Boolean) -> Pair<String, String?>) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val buzz = LocalBuzz.current
    var message by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(game, settings.halfwayMessage) {
        game.fx.collect { fx ->
            if (fx is Fx.Halfway && settings.halfwayMessage) {
                message = line(!timerHidden)
                buzz.halfway()
                launch { spin.snapTo(0f); spin.animateTo(180f, springs.slow()) }
                delay(2600)
                message = null
            }
        }
    }
    val lit by animateFloatAsState(if (message != null) 1f else 0f, springs.effects(), label = "lit")
    val tier = game.deal.tier.ordinal
    Surface(
        color = lerp(cs.surfaceContainer, cs.tertiaryContainer, lit), contentColor = lerp(cs.onSurface, cs.onTertiaryContainer, lit),
        shape = RoundedCornerShape(50), modifier = Modifier.animateContentSize(springs.spatial()),
    ) {
        Row(Modifier.height(40.dp).padding(start = 6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeGlyph(Shp.difficulty[tier], 28.dp, lerp(cs.secondary, cs.onTertiaryContainer, lit), Modifier.graphicsLayer { rotationZ = spin.value })
            Spacer(Modifier.width(8.dp))
            val m = message
            if (m == null) {
                Text(TIER_NAMES[tier], maxLines = 1, style = ts(15f, 650))
                Spacer(Modifier.width(8.dp))
                Text("${game.w}×${game.h}", maxLines = 1, overflow = TextOverflow.Clip, style = ts(13f, 600, cs.onSurfaceVariant, wdth = 90, tnum = true))
            } else if (m.second == null) {
                Text(m.first, maxLines = 1, style = ts(15f, 700))
            } else {
                Column(verticalArrangement = Arrangement.Center) {
                    Text(m.first, maxLines = 1, style = ts(11f, 600, lineHeight = 13f))
                    Text(m.second!!, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(14f, 700, tnum = true, lineHeight = 17f))
                }
            }
        }
    }
}

@Composable
private fun NoNet() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Sym["visibility"], null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        Text("Mistakes not flagged", style = ts(13f, 550, cs.onSurfaceVariant))
    }
}

/** Lives as MaterialShapes.Heart; a lost heart splits along a crack and its halves fall away. */
@Composable
private fun Lives(game: Game) {
    val cs = MaterialTheme.colorScheme
    val n = game.rules.lives
    val breaks = remember { List(n) { Animatable(0f) } }
    LaunchedEffect(game) {
        game.fx.collect { fx ->
            if (fx is Fx.Mistake && fx.livesLeft in 0 until n) {
                val b = breaks[fx.livesLeft]
                b.snapTo(0f); b.animateTo(1f, tween(520))
            }
        }
    }
    val path = remember { androidx.compose.ui.graphics.Path() }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "${game.lives} of $n lives" }) {
        for (k in 0 until n) {
            val alive = k < game.lives
            val b = breaks[k].value
            Canvas(Modifier.size(26.dp)) {
                morphPath(Morphs.of(Shp.heart, Shp.heart), 0f, size, path)
                if (alive) { drawPath(path, cs.error); return@Canvas }
                drawPath(path, cs.outlineVariant, style = Stroke(size.width * 0.09f))
                if (b in 0.001f..0.999f) {
                    val w = size.width; val h = size.height
                    val crack = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0.52f * w, 0f); lineTo(0.44f * w, 0.28f * h); lineTo(0.58f * w, 0.5f * h); lineTo(0.42f * w, 0.72f * h); lineTo(0.5f * w, h)
                    }
                    val leftClip = androidx.compose.ui.graphics.Path().apply { addPath(crack); lineTo(0f, h); lineTo(0f, 0f); close() }
                    val rightClip = androidx.compose.ui.graphics.Path().apply { addPath(crack); lineTo(w, h); lineTo(w, 0f); close() }
                    val a = (1f - b).coerceIn(0f, 1f)
                    val dx = 9.dp.toPx() * b; val dy = 16.dp.toPx() * b * b
                    for ((clip, dir) in listOf(leftClip to -1f, rightClip to 1f)) {
                        withTransform({
                            translate(dir * dx, dy)
                            rotate(dir * 40f * b, Offset(w / 2, h / 2))
                            clipPath(clip)
                        }) { drawPath(path, cs.error.copy(alpha = a)) }
                    }
                }
            }
        }
    }
}

/** m:ss in display weight, hundredths smaller and quieter; tabular figures. */
@Composable
private fun TimerText(game: Game, hidden: Boolean) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val wide by animateFloatAsState(if (hidden) 0f else 1f, springs.spatial(), label = "t")
    val ms = game.elapsed
    val cs100 = ms / 10
    val s = cs100 / 100
    val mm = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    val hh = "." + (cs100 % 100).toString().padStart(2, '0')
    Row(
        Modifier.graphicsLayer { alpha = wide; scaleX = 0.6f + 0.4f * wide; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .widthIn(max = (150 * wide).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(mm, maxLines = 1, softWrap = false, style = ts(40f, 380, cs.onSurface, wdth = 92, tnum = true, lineHeight = 40f).copy(letterSpacing = (-0.5).sp))
        Text(hh, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 1.dp, bottom = 4.dp), style = ts(22f, 480, cs.onSurfaceVariant, wdth = 92, tnum = true, lineHeight = 22f))
    }
}

@Composable
private fun PausedOverlay(onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onResume, color = cs.primary, contentColor = cs.onPrimary, shape = MorphShape(Morphs.of(Shp.highlighted, Shp.highlighted), 0f),
        modifier = Modifier.size(112.dp).semantics { contentDescription = "Resume" },
    ) { Box(contentAlignment = Alignment.Center) { Icon(Sym["play_arrow"], null, Modifier.size(48.dp)) } }
}

/** Run lost: the board sinks and one big error tile is left. */
@Composable
private fun LostMark() {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val s = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(500); s.animateTo(1f, springs.spatial()) }
    Box(Modifier.size(112.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }, contentAlignment = Alignment.Center) {
        ShapeGlyph(Shp.highlighted, 112.dp, cs.error)
        Icon(Sym["close"], null, Modifier.size(44.dp), tint = cs.onError)
    }
}

// ── The strip: hint words, the contradiction flag, check answers, what-if ────────────────────────

private fun lineName(game: Game, line: Int) = if (line < game.h) "Row ${line + 1}" else "Column ${line - game.h + 1}"

@Composable
private fun Strip(game: Game, thinking: Boolean, checkWord: Boolean?, filledWrong: Boolean) {
    val cs = MaterialTheme.colorScheme
    val h = game.hint
    val bad = game.contradicted.indexOfFirst { it }
    when {
        game.inWhatIf -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Sym["alt_route"], null, Modifier.size(18.dp), tint = cs.secondary)
            Text("What if…", style = ts(14f, 600, cs.onSurfaceVariant))
            StripButton("Drop", cs.surfaceContainerHigh, cs.onSurface) { game.discardWhatIf() }
            StripButton("Keep", cs.secondary, cs.onSecondary) { game.commitWhatIf() }
        }
        h != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Sym["lightbulb"], null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            val hint = h.hint
            val r = hint.cell / game.w + 1; val c = hint.cell % game.w + 1
            val what = if (hint.value == Mark.FILLED) "filled" else "empty"
            val text = when (h.stage) {
                1 -> "${lineName(game, hint.line)} has a forced cell"
                2 -> "Row $r, column $c is $what"
                else -> if (hint.rung.isLine) "${lineName(game, hint.line)}: ${hint.rung.label}"
                else "If it were ${if (hint.assumed == Mark.FILLED) "filled" else "empty"}, ${if (hint.brokenLine >= 0) lineName(game, hint.brokenLine).lowercase() else "a line"} would break"
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(14f, 640, cs.onSurface))
        }
        bad >= 0 -> Text("${lineName(game, bad)} can't fit its clue any more", maxLines = 1, style = ts(14f, 600, cs.error))
        checkWord != null -> Text(if (checkWord) "No wrong marks so far" else "Something on the board is wrong", style = ts(14f, 600, if (checkWord) cs.onSurfaceVariant else cs.error))
        filledWrong -> Text("Every cell is marked, but something is wrong", style = ts(14f, 600, cs.error))
        thinking -> Text("Looking for a step…", style = ts(14f, 560, cs.onSurfaceVariant))
    }
}

@Composable
private fun StripButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = ts(14f, 650))
    }
}

// ── Tool row (README §3.4): undo/redo pair · Fill | Cross · hint ───────────────────────────────

@Composable
private fun Tools(game: Game, ui: PlayUi, settings: Settings, onHint: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    // `canUndo` reads plain stacks; the revision makes this recompose after every change.
    @Suppress("UNUSED_VARIABLE") val rev = game.revision
    val b = game.board
    Row(modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Flag mode: every mark was judged right as it was made, so there is nothing to undo (a what-if
        // layer is the exception: its guesses are unjudged). Fill and Cross take the room.
        if (!game.marksFinal) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolKey("undo", "Undo", RoundedCornerShape(24.dp, 8.dp, 8.dp, 24.dp), 48.dp, game.canUndo && b.isNotEmpty()) { game.undo() }
            ToolKey("redo", "Redo", RoundedCornerShape(8.dp, 24.dp, 24.dp, 8.dp), 48.dp, game.canRedo) { game.redo() }
        }
        Row(Modifier.weight(1f).fillMaxHeight().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val strokeTool = game.stroke?.tool
            for (t in Tool.entries) {
                ToolToggle(
                    t, on = game.tool == t, borrowed = ui.borrowed && strokeTool == t && game.tool != t,
                    first = t == Tool.FILL, modifier = Modifier.weight(1f),
                ) { if (game.tool != t) game.selectTool(t) else buzz.toggle() }
            }
        }
        ToolKey("lightbulb", "Hint", RoundedCornerShape(28.dp), 56.dp, settings.hints && game.rules.hints && !game.inWhatIf) { onHint() }
    }
}

@Composable
private fun ToolKey(icon: String, label: String, shape: RoundedCornerShape, width: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, springs.fast(), label = "s")
    Surface(
        color = cs.surfaceContainerHigh, contentColor = cs.onSurface, shape = shape,
        modifier = Modifier.size(width, 72.dp)
            .graphicsLayer { scaleX = s; scaleY = s; alpha = if (enabled) 1f else 0.38f }
            .semantics { contentDescription = label }
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) { Box(contentAlignment = Alignment.Center) { Icon(Sym[icon], null, Modifier.size(22.dp)) } }
}

/**
 * Fill | Cross: two connected toggles (ButtonGroup's look [M3E-NEW], built by hand because ToggleButton
 * hardcodes Role.Checkbox). Corners 36 outer / 10 inner; checked → full corners, `tertiary`; a tool
 * borrowed by a long-press goes `tertiaryContainer`. Role.RadioButton in a selectableGroup.
 */
@Composable
private fun ToolToggle(tool: Tool, on: Boolean, borrowed: Boolean, first: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val round = on || borrowed
    val inner by animateDpAsState(if (round) 36.dp else 10.dp, springs.fast(), label = "inner")
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, springs.fast(), label = "s")
    val c by animateFloatAsState(if (on) 1f else 0f, springs.effects(), label = "c")
    val bc by animateFloatAsState(if (borrowed) 1f else 0f, springs.effects(), label = "b")
    val bg = lerp(lerp(cs.secondaryContainer, cs.tertiary, c), cs.tertiaryContainer, bc)
    val fg = lerp(lerp(cs.onSecondaryContainer, cs.onTertiary, c), cs.onTertiaryContainer, bc)
    val shape = if (first) RoundedCornerShape(36.dp, inner, inner, 36.dp) else RoundedCornerShape(inner, 36.dp, 36.dp, inner)
    val label = if (tool == Tool.FILL) "Fill" else "Cross"
    Surface(
        color = bg, contentColor = fg, shape = shape,
        modifier = modifier.fillMaxHeight()
            .graphicsLayer { scaleX = s; scaleY = s }
            .selectable(on, interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ToolGlyph(tool, 24.dp, fg)
            Spacer(Modifier.width(8.dp))
            Text(label, style = ts(17f, 650))
        }
    }
}
