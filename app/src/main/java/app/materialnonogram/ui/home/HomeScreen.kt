package app.materialnonogram.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.graphics.shapes.RoundedPolygon
import app.materialnonogram.AppModel
import app.materialnonogram.R
import app.materialnonogram.Screen
import app.materialnonogram.data.Settings
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Size as PSize
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.ui.kit.Hearts
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.morphPath
import app.materialnonogram.ui.play.TIER_NAMES
import app.materialnonogram.ui.theme.flex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TitleFont = FontFamily(Font(R.font.noto_sans_jp_title, FontWeight.ExtraBold))

/** Each tier in plain words (the generator's rules, ENGINE.md "Tiers"), for Home's and Settings' long-press tips. */
val DIFFICULTY_TECH = listOf(
    "Every row and column can be worked out on its own, and your first look already places a lot. Dense patterns.",
    "Still one line at a time, but less is handed to you at the start.",
    "One line at a time, but you go round the board many times, each pass opening up the next. Sparser patterns.",
    "One to three times, no single line helps. Then imagine a cell filled, follow it, and watch a line break: that proves the cell is empty.",
    "Like Expert, but you have to look ahead four or more times. Only from 15×15 up.",
)

/**
 * Home, locked as pair T1 ↔ C1 of board 10. One box at the top holds イラロジ with nothing in
 * progress, and the Continue card with a game running; nothing below it ever moves. Then the
 * Difficulty and Length pickers, then a big Start that *is* the chosen difficulty's shape.
 */
@Composable
fun HomeScreen(model: AppModel, settings: Settings) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val running = model.current
    val enabled = Tier.entries.filter { settings.tierOn(it) }.map { it.ordinal }.ifEmpty { listOf(0) }
    val sizes = PSize.entries.filter { settings.sizeOn(it) }.ifEmpty { listOf(PSize.S10) }
    var d by remember { mutableIntStateOf(settings.lastTier.takeIf { it in enabled } ?: enabled.first()) }
    var sz by remember { mutableStateOf(PSize.entries[settings.lastSize].takeIf { it in sizes } ?: sizes.first()) }
    // D7: combinations that don't exist (5×5 and 10×10 Master) are greyed both ways; picking one side
    // moves the other to the nearest one that does.
    fun fix(tier: Int) { if (!Generator.isOffered(sz, Tier.entries[tier])) sizes.firstOrNull { Generator.isOffered(it, Tier.entries[tier]) }?.let { sz = it } }
    LaunchedEffect(d, sz) { if (Generator.isOffered(sz, Tier.entries[d])) model.prefetch(sz, Tier.entries[d]) }
    val inProgress = running != null

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SquishIcon("bar_chart", "Stats", { buzz.select(); model.screen = Screen.STATS }, container = cs.surfaceContainerHigh, content = cs.onSurface, iconSize = 22.dp)
            Spacer(Modifier.weight(1f))
            // D10: the other half of Share. Paste a code (or an SGT Pattern ID) and play it.
            SquishIcon("content_paste", "Open a code", { buzz.select(); model.screen = Screen.OPEN }, container = cs.surfaceContainerHigh, content = cs.onSurface, iconSize = 22.dp)
            Spacer(Modifier.width(8.dp))
            SquishIcon("settings", "Settings", { buzz.select(); model.openSettings(Screen.HOME) }, container = cs.surfaceContainerHigh, content = cs.onSurface, iconSize = 22.dp)
        }
        // The slot: one fixed box, two tenants (board 10).
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(236.dp)) {
            AnimatedContent(
                targetState = running,
                transitionSpec = { (fadeIn(tween(220, 40)) + scaleIn(initialScale = 0.9f)).togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.94f)) },
                contentKey = { it != null },
                label = "slot",
            ) { g ->
                if (g == null) Title() else ContinueCard(g) { intro -> buzz.press(); model.continueGame(intro) }
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Label("Difficulty")
            DifficultyTiles(enabled, d, sz) { k -> d = k; fix(k); buzz.difficulty(k); model.updateSettings { it.copy(lastTier = k, lastSize = sz.ordinal) } }
            Spacer(Modifier.height(14.dp))
            Label("Size")
            SizeTiles(sizes, sz, Tier.entries[d]) { s -> sz = s; buzz.length(sizes.indexOf(s).coerceAtMost(2)); model.updateSettings { it.copy(lastSize = s.ordinal) } }
        }
        // The big shape takes what height is left: 222 dp at most, and its echo (1.26×) must clear the pickers.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 22.dp), contentAlignment = Alignment.Center) {
            val size = minOf(222.dp, (maxHeight - 12.dp) / 1.3f)
            BigStart(d, sz, inProgress, model.starting, size) { rect -> buzz.start(); model.start(Tier.entries[d], sz, app.materialnonogram.ui.play.Intro.Start(rect, d, subdued = inProgress)) }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(
        t, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        style = TextStyle(fontFamily = flex(14f, 600), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/**
 * イラロジ (short for イラストロジック, "illustration logic") in heavy Noto Sans JP, centred on its ink
 * (CJK ink sits low in the line box). Four full-width glyphs at −0.05 em tracking span 3.8 em, so the
 * size is fitted to the slot's width, capped at the 162 dp the two-glyph Sudoku title used.
 */
@Composable
private fun Title() {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val rise = remember { Animatable(1f) }
    LaunchedEffect(Unit) { rise.snapTo(0f); delay(140); rise.animateTo(1f, springs.slow()) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().semantics { heading(); contentDescription = "Nonogram" }, contentAlignment = Alignment.Center) {
        val size = minOf(162.dp, maxWidth / 3.9f)
        Text(
            "イラロジ",
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = (-6).dp).graphicsLayer {
                translationY = (1f - rise.value) * 18.dp.toPx()
                val s = 0.94f + 0.06f * rise.value
                scaleX = s; scaleY = s
            },
            // Fixed dp, not sp: an editorial title, not body text.
            style = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.ExtraBold, fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }, lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }, letterSpacing = (-0.05).em, color = cs.onSurface),
        )
    }
}

/** C1: obscured board left; difficulty, length, time and hearts in a column; Continue at its foot. */
@Composable
private fun ContinueCard(g: Game, onClick: (app.materialnonogram.ui.play.Intro) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    var cardRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var thumbAt by remember { mutableStateOf(Offset.Zero) }
    val tg = thumbGeo(g)
    fun intro() = with(density) {
        app.materialnonogram.ui.play.Intro.Continue(
            cardRect, 40.dp.toPx(),
            app.materialnonogram.ui.play.GridGeo(thumbAt + Offset(tg.pad.toPx(), tg.pad.toPx()), tg.cell.value, density.density, g.w, g.h, tg.gap.toPx(), tg.gut.toPx()),
        )
    }
    val springs = LocalSprings.current
    var pressed by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, springs.fast(), label = "p")
    val desc = "Continue, ${TIER_NAMES[g.deal.tier.ordinal]}, ${g.w} by ${g.h}, ${timeLabel(g.elapsed)}" +
        if (g.rules.flagMistakes) ", ${g.lives} of ${g.rules.lives} lives" else ""
    Surface(
        color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
        shape = RoundedCornerShape((40 + 12 * p).dp),
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { cardRect = it.boundsInRoot() }
            .graphicsLayer { val s = 1f - 0.025f * p; scaleX = s; scaleY = s }
            .semantics { role = Role.Button; contentDescription = desc; heading() }
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick(intro()) }) },
    ) {
        Row(Modifier.padding(start = 13.dp, top = 13.dp, bottom = 13.dp, end = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(g, tg, radius = 26.dp, modifier = Modifier.onGloballyPositioned { thumbAt = it.boundsInRoot().topLeft })
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeGlyph(Shp.difficulty[g.deal.tier.ordinal], 22.dp, cs.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(TIER_NAMES[g.deal.tier.ordinal], maxLines = 1, style = TextStyle(fontFamily = flex(26f, 680), fontSize = 26.sp, lineHeight = 30.sp))
                }
                Text("${g.w}×${g.h}", modifier = Modifier.graphicsLayer { alpha = 0.8f }, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(start = 2.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym["timer"], null, Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(timeLabel(g.elapsed), style = TextStyle(fontFamily = flex(30f, 600), fontSize = 30.sp, lineHeight = 30.sp, fontFeatureSettings = "tnum"))
                    }
                    if (g.rules.flagMistakes) Hearts(g.lives, g.rules.lives, 22.dp, spent = cs.onPrimaryContainer.copy(alpha = 0.55f))
                }
                // Inverted from the card: filled primary sits too close to primaryContainer in a dark scheme.
                Surface(color = cs.onPrimaryContainer, contentColor = cs.primaryContainer, shape = RoundedCornerShape((28 - 12 * p).dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym["play_fill"], null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Continue", style = TextStyle(fontFamily = flex(16f, 650), fontSize = 16.sp))
                    }
                }
            }
        }
    }
}

fun timeLabel(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/** The Continue thumbnail's geometry: cells sized so any board fits a 172 dp square, block gutters every five. */
class ThumbGeo(val cell: Dp, val gap: Dp, val gut: Dp, val pad: Dp, val w: Int, val h: Int) {
    fun p(k: Int) = pad + (cell + gap) * k + (gut - gap) * (k / 5)
    val width: Dp get() = p(w - 1) + cell + pad
    val height: Dp get() = p(h - 1) + cell + pad
}

fun thumbGeo(g: Game, box: Dp = 172.dp): ThumbGeo {
    val n = maxOf(g.w, g.h)
    val blocks = (n - 1) / 5
    val pad = 8.dp
    // n·c + (n−1)·0.08c + blocks·0.22c = box − 2·pad
    val c = (box - pad * 2) / (n + (n - 1) * 0.08f + blocks * 0.22f)
    return ThumbGeo(c, c * 0.08f, c * 0.3f, pad, g.w, g.h)
}

/** The board as it stands: Play's wells and fills, small, on the page colour. */
@Composable
fun Thumb(g: Game, t: ThumbGeo, radius: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier.size(t.width, t.height)) {
        drawRoundRect(cs.surface, cornerRadius = CornerRadius(radius.toPx()))
        val c = t.cell.toPx()
        val b = g.board
        for (i in 0 until g.w * g.h) {
            val color = when (b[i]) {
                Mark.FILLED -> cs.tertiary
                Mark.CROSSED -> lerp(cs.surfaceContainerHighest, cs.onSurfaceVariant, 0.25f)
                else -> cs.surfaceContainerHighest
            }
            drawRoundRect(color, Offset(t.p(i % g.w).toPx(), t.p(i / g.w).toPx()), Size(c, c), CornerRadius(c * 0.12f))
        }
    }
}

/** Connected corners (28 outer / 8 inner) for a 3-column grid of n tiles. */
private fun corners(k: Int, n: Int, outer: Dp, inner: Dp): RoundedCornerShape {
    val rows = (n + 2) / 3; val r = k / 3; val c = k % 3
    val rowN = minOf(3, n - r * 3); val topN = minOf(3, n)
    val tl = r == 0 && c == 0; val tr = r == 0 && c == topN - 1
    val bl = r == rows - 1 && c == 0; val br = r == rows - 1 && c == rowN - 1
    return RoundedCornerShape(if (tl) outer else inner, if (tr) outer else inner, if (br) outer else inner, if (bl) outer else inner)
}

@Composable
private fun DifficultyTiles(enabled: List<Int>, sel: Int, size: PSize, onPick: (Int) -> Unit) {
    var tip by remember { mutableIntStateOf(-1) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        enabled.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { c, k ->
                    val idx = r * 3 + c
                    Box(Modifier.weight(1f)) {
                        PickTile(
                            on = k == sel, height = if (enabled.size > 3) 72.dp else 92.dp, rest = corners(idx, enabled.size, 28.dp, 8.dp), full = 46.dp,
                            onClick = { onPick(k) }, onLongPress = { tip = k },
                            label = TIER_NAMES[k], shape = Shp.difficulty[k], compact = enabled.size > 3,
                            greyed = !Generator.isOffered(size, Tier.entries[k]),
                        )
                        if (tip == k) TechTip(k, size) { tip = -1 }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TechTip(k: Int, size: PSize, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    LaunchedEffect(k) { delay(4200); onDismiss() }
    Popup(popupPositionProvider = AboveAnchor(gap), onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Surface(color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = Modifier.width(272.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeGlyph(Shp.difficulty[k], 18.dp, cs.inversePrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(TIER_NAMES[k], style = TextStyle(fontFamily = flex(15f, 650), fontSize = 15.sp))
                }
                Spacer(Modifier.height(4.dp))
                Text(DIFFICULTY_TECH[k], style = MaterialTheme.typography.bodyMedium)
                if (!Generator.isOffered(size, Tier.entries[k])) {
                    Spacer(Modifier.height(6.dp))
                    Text("Not at ${size.label}: pick 15×15 or larger.", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = flex(14f, 620)), color = cs.inversePrimary)
                }
            }
        }
    }
}

/** Places a popup centred above its anchor, [gap] px clear of it, kept inside the window. */
private class AboveAnchor(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(gap)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/** D7: one row of the enabled sizes, never two; each tile draws its grid outline to scale. */
@Composable
private fun SizeTiles(sizes: List<PSize>, sel: PSize, tier: Tier, onPick: (PSize) -> Unit) {
    val six = sizes.size >= 6
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sizes.forEachIndexed { k, s ->
            Box(Modifier.weight(1f)) {
                PickTile(
                    on = s == sel, height = 72.dp, rest = rowCorners(k, sizes.size, 24.dp, 8.dp), full = 36.dp,
                    onClick = { onPick(s) }, label = s.label, grid = s, gridK = if (six) 1.5f else 1.6f, compact = true,
                    gridBox = (sizes.maxOf { it.h } * (if (six) 1.5f else 1.6f)).dp,
                    labelSize = if (six) 13f else 14f, labelWdth = if (six) 80 else 90,
                    greyed = !Generator.isOffered(s, tier),
                )
            }
        }
    }
}

private fun rowCorners(k: Int, n: Int, outer: Dp, inner: Dp) =
    RoundedCornerShape(if (k == 0) outer else inner, if (k == n - 1) outer else inner, if (k == n - 1) outer else inner, if (k == 0) outer else inner)

/**
 * ToggleButton with connected → CornerFull shapes [M3E-NEW]; selectableGroup + Role.RadioButton by
 * hand. The checked tile springs to full corners and fills secondary; its icon scales and turns.
 */
@Composable
private fun PickTile(
    on: Boolean, height: Dp, rest: RoundedCornerShape, full: Dp, onClick: () -> Unit, label: String,
    shape: RoundedPolygon? = null, onLongPress: (() -> Unit)? = null,
    grid: PSize? = null, gridK: Float = 1.6f, compact: Boolean = false, labelSize: Float = 16f, labelWdth: Int = 100,
    greyed: Boolean = false,
    /** Every size glyph sits on one baseline this tall, so the labels line up across the row. */
    gridBox: Dp = 0.dp,
) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val t by animateFloatAsState(if (on) 1f else 0f, springs.fast(), label = "on")
    val c by animateFloatAsState(if (on) 1f else 0f, springs.effects(), label = "c")
    var pressed by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, springs.fast(), label = "p")
    val bg = lerp(cs.surfaceContainerHighest, cs.secondary, c)
    val fg = lerp(cs.onSurface, cs.onSecondary, c)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(height)
            .graphicsLayer { val s = 1f - 0.05f * p; scaleX = s; scaleY = s; alpha = if (greyed) 0.38f else 1f }
            .semantics { role = Role.RadioButton; selected = on; contentDescription = if (greyed) "$label, not available" else label }
            .pointerInput(onLongPress, greyed) {
                detectTapGestures(
                    onPress = { if (!greyed) { pressed = true; tryAwaitRelease(); pressed = false } },
                    onTap = { if (!greyed) onClick() else onLongPress?.invoke() },
                    onLongPress = onLongPress?.let { f -> { _: Offset -> f() } },
                )
            }
            .drawBehind {
                val sz = this.size
                val restO = rest.createOutline(sz, layoutDirection, density)
                val r = full.toPx()
                // Corners tween from connected to full (the outline lerp, per corner).
                val path = Path()
                val o = restO as androidx.compose.ui.graphics.Outline.Rounded
                val rr = o.roundRect
                fun mix(a: Float) = a + (minOf(r, sz.height / 2) - a) * t
                path.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        0f, 0f, sz.width, sz.height,
                        CornerRadius(mix(rr.topLeftCornerRadius.x)), CornerRadius(mix(rr.topRightCornerRadius.x)),
                        CornerRadius(mix(rr.bottomRightCornerRadius.x)), CornerRadius(mix(rr.bottomLeftCornerRadius.x)),
                    ),
                )
                drawPath(path, bg)
                // Greyed both ways (D7): a slash across the tile.
                if (greyed) drawLine(lerp(cs.onSurface, bg, 0.3f), Offset(sz.width * 0.2f, sz.height * 0.8f), Offset(sz.width * 0.8f, sz.height * 0.2f), 2.dp.toPx())
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
            if (shape != null) ShapeGlyph(shape, if (compact) 28.dp else 34.dp, lerp(cs.onSurfaceVariant, cs.onSecondary, c), Modifier.graphicsLayer {
                val s = 1f + 0.16f * t; scaleX = s; scaleY = s; rotationZ = -12f * t
            })
            if (grid != null) Box(Modifier.height(gridBox), contentAlignment = Alignment.BottomCenter) { GridGlyph(grid, gridK, lerp(cs.onSurfaceVariant, cs.onSecondary, c)) }
            Text(label, color = fg, maxLines = 1, style = TextStyle(fontFamily = flex(labelSize, if (on) 720 else 560, wdth = labelWdth), fontSize = labelSize.sp, lineHeight = (labelSize + 4).sp, fontFeatureSettings = "tnum"))
        }
    }
}

/**
 * The big Start: a Surface whose shape is a Morph between the previous and the chosen difficulty's
 * MaterialShape. With a game running it steps back (0.84×, secondaryContainer, "New game").
 */
@Composable
private fun BigStart(d: Int, sz: PSize, inProgress: Boolean, starting: Boolean, size: Dp, onGo: (androidx.compose.ui.geometry.Rect) -> Unit) {
    var shapeRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf(Shp.difficulty[d]) }
    var to by remember { mutableStateOf(Shp.difficulty[d]) }
    val morph = remember { Animatable(1f) }
    var turn by remember { mutableFloatStateOf(0f) }
    var echoTurn by remember { mutableFloatStateOf(-14f) }
    LaunchedEffect(d) {
        val target = Shp.difficulty[d]
        if (target != to) {
            from = to; to = target; turn += 24f; echoTurn -= 30f
            morph.snapTo(0f); morph.animateTo(1f, springs.slow())
        }
    }
    val spin by animateFloatAsState(turn, springs.slow(), label = "turn")
    val echoSpin by animateFloatAsState(echoTurn, springs.slow(), label = "eturn")
    val run by animateFloatAsState(if (inProgress) 1f else 0f, springs.slow(), label = "run")
    val runC by animateFloatAsState(if (inProgress) 1f else 0f, springs.effects(), label = "runC")
    val lenScale = 1f
    var pressed by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, springs.fast(), label = "p")
    val go = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val path = remember { Path() }
    val k = size / 222.dp

    Box(Modifier.fillMaxWidth().height(size * 1.3f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size)
                .graphicsLayer { val s = lenScale * (1f - 0.16f * run); scaleX = s; scaleY = s }
                .onGloballyPositioned { shapeRect = it.boundsInRoot() }
                .semantics { role = Role.Button; contentDescription = (if (inProgress) "New game, " else "Start, ") + "${TIER_NAMES[d]}, ${sz.w} by ${sz.h}" }
                .pointerInput(inProgress, starting) {
                    detectTapGestures(
                        onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                        onTap = {
                            if (starting) return@detectTapGestures
                            scope.launch { ring.snapTo(0.01f); ring.animateTo(1f, tween(700)); ring.snapTo(0f) }
                            scope.launch {
                                go.snapTo(0f)
                                go.animateTo(1f, tween(700))
                                go.snapTo(0f)
                            }
                            onGo(shapeRect)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val s = this.size
                // The echo: a larger copy behind, counter-turning.
                if (run < 0.99f) {
                    val e = s.width * 1.26f
                    morphPath(Morphs.of(from, to), morph.value, Size(e, e), path)
                    path.translate(Offset(-(e - s.width) / 2, -(e - s.height) / 2))
                    rotateAbout(echoSpin, s) { drawPath(path, cs.surfaceContainerHigh.copy(alpha = 1f - run)) }
                }
                if (ring.value > 0f) drawCircle(cs.primary.copy(alpha = 0.9f * (1f - ring.value)), radius = s.width / 2 * (0.9f + 0.7f * ring.value), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                // The press: squish and an 8° turn; the go: 30° back-swing then a quarter wind.
                val g = go.value
                val wind = if (g < 0.25f) -30f * (g / 0.25f) else -30f + 120f * ((g - 0.25f) / 0.75f)
                val gs = if (g < 0.25f) 1f - 0.1f * (g / 0.25f) else 0.9f + 0.1f * ((g - 0.25f) / 0.75f)
                val sc = (1f - 0.07f * p) * (if (g > 0f) gs else 1f)
                morphPath(Morphs.of(from, to), morph.value, s, path)
                rotateAbout(spin + 8f * p + (if (g > 0f) wind else 0f), s) {
                    scaleAbout(sc, s) { drawPath(path, lerp(cs.primary, cs.secondaryContainer, runC)) }
                }
            }
            // Labels: two layers cross-faded by one float.
            Column(Modifier.graphicsLayer { alpha = 1f - runC; val q = (1f - 0.1f * runC) * k; scaleX = q; scaleY = q }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Sym["play_fill"], null, Modifier.size(36.dp), tint = cs.onPrimary)
                Text(if (starting && !inProgress) "Starting" else "Start", color = cs.onPrimary, style = TextStyle(fontFamily = flex(30f, 680), fontSize = 30.sp, lineHeight = 34.sp))
                Text("${TIER_NAMES[d]} · ${sz.label}", color = cs.onPrimary.copy(alpha = 0.85f), style = TextStyle(fontFamily = flex(15f, 480), fontSize = 15.sp, lineHeight = 20.sp))
            }
            Column(Modifier.graphicsLayer { alpha = runC; val q = (0.9f + 0.1f * runC) * k; scaleX = q; scaleY = q }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Sym["add"], null, Modifier.size(32.dp), tint = cs.onSecondaryContainer)
                Text(if (starting) "Starting" else "New game", color = cs.onSecondaryContainer, style = TextStyle(fontFamily = flex(28f, 680), fontSize = 28.sp, lineHeight = 34.sp))
                Text("${TIER_NAMES[d]} · ${sz.label}", color = cs.onSecondaryContainer.copy(alpha = 0.85f), style = TextStyle(fontFamily = flex(15f, 480), fontSize = 15.sp, lineHeight = 20.sp))
            }
        }
    }
}

/** A size's grid outline to scale ([k] dp a cell), with block lines every five. */
@Composable
private fun GridGlyph(s: PSize, k: Float, color: Color) {
    Canvas(Modifier.size((s.w * k).dp, (s.h * k).dp)) {
        val c = k.dp.toPx()
        val sw = 1.dp.toPx()
        drawRoundRect(color, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw * 1.3f))
        for (x in 5 until s.w step 5) drawLine(color.copy(alpha = 0.6f), Offset(x * c, 0f), Offset(x * c, size.height), sw * 0.8f)
        for (y in 5 until s.h step 5) drawLine(color.copy(alpha = 0.6f), Offset(0f, y * c), Offset(size.width, y * c), sw * 0.8f)
    }
}

private inline fun DrawScope.rotateAbout(deg: Float, s: Size, block: DrawScope.() -> Unit) =
    rotate(deg, Offset(s.width / 2, s.height / 2), block)

private inline fun DrawScope.scaleAbout(k: Float, s: Size, block: DrawScope.() -> Unit) =
    scale(k, k, Offset(s.width / 2, s.height / 2), block)

