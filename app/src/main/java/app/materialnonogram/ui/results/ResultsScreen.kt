package app.materialnonogram.ui.results

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.materialnonogram.AppModel
import app.materialnonogram.data.Settings
import app.materialnonogram.engine.Rung
import app.materialnonogram.game.Accolade
import app.materialnonogram.game.BAND_NAMES
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.game.NET_NAMES
import app.materialnonogram.game.Report
import app.materialnonogram.game.Status
import app.materialnonogram.game.StrikeKind
import app.materialnonogram.game.accolades
import app.materialnonogram.game.buildReport
import app.materialnonogram.game.clock
import app.materialnonogram.game.clockMs
import app.materialnonogram.game.median
import app.materialnonogram.game.net
import app.materialnonogram.game.recordKey
import app.materialnonogram.game.signedDelta
import app.materialnonogram.ui.kit.Hearts
import app.materialnonogram.ui.kit.LevelChip
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.Pill
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.WordChip
import app.materialnonogram.ui.kit.drawShape
import app.materialnonogram.ui.kit.ts
import app.materialnonogram.ui.play.TIER_NAMES
import kotlinx.coroutines.delay

/**
 * End of game, the minimal Phase 4 version (BUILD-PLAN §5.2 is the full one, Phase 5): the time on
 * the tier's shape with the pattern as the trophy, accolades, four stat tiles, and the actions.
 * Every number comes from the log through [Report].
 */
@Composable
fun ResultsScreen(model: AppModel, settings: Settings) {
    val g = model.finished ?: return
    // Unrated at once (instant), then rated in the background (EngineRater, ~50–500 ms on a 20×20).
    val quick = remember(g) { Report(g, null) }
    val rated by androidx.compose.runtime.produceState<Report?>(null, g) { value = buildReport(g) }
    val report = rated ?: quick
    val prior = model.history.filter { it.id != g.id }
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val buzz = LocalBuzz.current

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 104.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = cs.surfaceContainer, contentColor = cs.onSurface, shape = RoundedCornerShape(50)) {
                    Row(Modifier.height(40.dp).padding(start = 6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShapeGlyph(Shp.difficulty[g.deal.tier.ordinal], 28.dp, cs.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(TIER_NAMES[g.deal.tier.ordinal], style = ts(15f, 650))
                        Spacer(Modifier.width(8.dp))
                        Text("${g.w}×${g.h}", style = ts(13f, 600, cs.onSurfaceVariant, wdth = 90, tnum = true))
                    }
                }
                WordChip(null, if (g.net() == 0) "${g.rules.lives} lives" else NET_NAMES[g.net()], leading = {
                    if (g.net() == 2) Icon(Sym["shield"], null, Modifier.size(16.dp)) else ShapeGlyph(Shp.heart, 15.dp, cs.onSurfaceVariant, outline = true)
                })
                Spacer(Modifier.weight(1f))
                // D10: the puzzle is its bitmap; share the checked code ("Copy as text" is the SGT ID, Phase 5).
                SquishIcon("share", "Copy puzzle code", { clip.setText(AnnotatedString(g.deal.shareCode)); buzz.toggle() }, size = 40.dp, iconSize = 20.dp)
            }
            if (g.status == Status.WON) Won(g, report, prior) else Lost(g, report)
            Text(
                if (report.assisted) "Assisted · ${BAND_NAMES[g.band]}" else BAND_NAMES[g.band],
                Modifier.padding(start = 22.dp, top = 12.dp), style = ts(12f, 540, cs.onSurfaceVariant),
            )
        }
        Actions(g, model)
    }
}

@Composable
private fun Won(g: Game, r: Report, prior: List<Game>) {
    val cs = MaterialTheme.colorScheme
    val key = g.recordKey()
    val sameKey = prior.filter { it.status == Status.WON && it.recordKey() == key }
    val best = sameKey.minByOrNull { it.elapsed }
    val usual = prior.filter { it.status == Status.WON && it.w == g.w && it.h == g.h && it.deal.tier == g.deal.tier && it.net() == g.net() }
        .takeLast(30).map { it.elapsed }.median()
    Hero(cs.primaryContainer, cs.onPrimaryContainer, g.deal.tier.ordinal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (best == null || g.elapsed < best.elapsed) "Solved · new record" else "Solved", style = ts(12f, 600, cs.onPrimaryContainer.copy(alpha = 0.8f)))
                BigTime(g.elapsed, 64f, cs.onPrimaryContainer)
            }
            // The pattern is the trophy (README §3.7): the solution at 6.6 dp a cell.
            Pattern(g, cs.onPrimaryContainer)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (best != null) Pill(
                "${signedDelta(g.elapsed - best.elapsed)} vs best ${clockMs(best.elapsed).let { it.first + it.second }}",
                if (g.elapsed < best.elapsed) cs.onPrimaryContainer else cs.onPrimaryContainer.copy(alpha = 0.12f),
                if (g.elapsed < best.elapsed) cs.primaryContainer else cs.onPrimaryContainer,
            )
            if (usual != null) Pill("${clock(usual)} usual", cs.onPrimaryContainer.copy(alpha = 0.12f), cs.onPrimaryContainer)
        }
    }
    Accolades(accolades(r, prior))
    StatTiles(g, r)
}

@Composable
private fun Pattern(g: Game, color: Color) {
    val k = minOf(6.6f, 120f / maxOf(g.w, g.h))
    Canvas(Modifier.size((g.w * k).dp, (g.h * k).dp)) {
        val c = k.dp.toPx()
        for (i in 0 until g.w * g.h) if (g.deal.puzzle.filled(i)) drawRect(color, Offset((i % g.w) * c, (i / g.w) * c), Size(c + 0.5f, c + 0.5f))
    }
}

@Composable
private fun StatTiles(g: Game, r: Report) {
    val player = r.playerMarks
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("Accuracy", "${(r.accuracy * 100).toInt()}", "%", "${player.count { it.correct }} of ${player.size + r.strikes.count { it.refused }}", Modifier.weight(0.92f))
        if (r.rated) {
            StatTile("Effort/min", "${r.effortPerMin}", "", "${r.effort} total", Modifier.weight(1f))
            val h = r.hardest
            val rung = h?.rating?.rung
            val where = h?.let { if (rung != null && rung.isLine && it.rating!!.line >= 0) (if (it.rating!!.line < g.h) "row ${it.rating!!.line + 1}" else "column ${it.rating!!.line - g.h + 1}") else "r${it.cell / g.w + 1}c${it.cell % g.w + 1}" }
            StatTile("Hardest step", rung?.let { if (it.isLine) "Line logic" else "Look-ahead" } ?: "—", "", where?.let { "$it · ${h.effort10 / 10}" } ?: "", Modifier.weight(1.42f), word = true)
        } else {
            StatTile("Per cell", "%.1f".format(r.secondsPerCell), "s", "${r.cells} cells", Modifier.weight(1f))
            StatTile("Strokes", "${r.strokeCount}", "", "longest ${r.longestStroke}", Modifier.weight(1.42f))
        }
        StatTile("Rhythm", "${r.rhythm}", "", when { r.rhythm >= 70 -> "steady"; r.rhythm >= 45 -> "bursty"; else -> "uneven" }, Modifier.weight(0.9f))
    }
}

@Composable
private fun Lost(g: Game, r: Report) {
    val cs = MaterialTheme.colorScheme
    Hero(cs.surfaceContainerHigh, cs.onSurface, g.deal.tier.ordinal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hearts(0, g.rules.lives, 24.dp, spent = cs.onSurface)
            Spacer(Modifier.width(10.dp))
            Text("Out of lives", style = ts(26f, 560, cs.onSurface))
        }
        Spacer(Modifier.height(12.dp))
        Text("Time survived", style = ts(12f, 600, cs.onSurfaceVariant))
        BigTime(g.elapsed, 58f, cs.onSurface)
    }
    // How far you got: cells decided out of the total.
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("How far you got", Modifier.weight(1f), style = ts(14f, 650, cs.onSurface))
                Text("${r.decided} of ${r.cells} cells · ${100 * r.decided / r.cells.coerceAtLeast(1)}%", style = ts(12f, 540, cs.onSurfaceVariant, tnum = true))
            }
            Spacer(Modifier.height(9.dp))
            Canvas(Modifier.fillMaxWidth().height(((r.cells + 24) / 25 * 9).dp)) {
                val cols = 25; val gp = 1.5.dp.toPx(); val s = (size.width - gp * (cols - 1)) / cols
                val sh = minOf(s, 7.5.dp.toPx())
                for (i in 0 until r.cells) {
                    val o = Offset((i % cols) * (s + gp), (i / cols) * (sh + gp))
                    drawRoundRect(if (i < r.decided) cs.tertiary else cs.surfaceContainerHighest, o, Size(s, sh), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            r.strikes.forEach { s ->
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(Sym["close"], null, Modifier.size(16.dp), tint = cs.error) }
                    Text(clock(s.t), Modifier.width(44.dp), style = ts(12.5f, 650, cs.onSurface, tnum = true))
                    Text("r${s.cell / g.w + 1}c${s.cell % g.w + 1}", Modifier.width(52.dp), style = ts(12.5f, 540, cs.onSurfaceVariant, tnum = true))
                    val what = if (s.value == Mark.FILLED) "fill" else "cross"
                    Text(
                        when (s.kind) {
                            StrikeKind.SLIP -> "Slip: its line alone rules out a $what"
                            StrikeKind.GUESS -> "Guess: nothing forced it yet"
                            StrikeKind.WRONG -> "Wrong: only look-ahead shows it"
                            null -> "A wrong $what"
                        },
                        style = ts(12.5f, 540, cs.onSurfaceVariant), maxLines = 1,
                    )
                }
            }
        }
    }
    Text("Losses keep no time record. This one counts once in your stats as a loss.", Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp), style = ts(12f, 500, cs.onSurfaceVariant, lineHeight = 16f))
}

/** Home · Same puzzle · New game: a ButtonGroup [M3E-NEW] over a scrim. */
@Composable
private fun Actions(g: Game, model: AppModel) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(104.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(0.38f to Color.Transparent, 0.38f to cs.surface.copy(alpha = 0f), 1f to cs.surface)) }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SquishIcon("home", "Home", { buzz.select(); model.screen = app.materialnonogram.Screen.HOME }, size = 56.dp, container = cs.surfaceContainerHigh, content = cs.onSurface, pressedCorner = 16.dp)
            Surface(onClick = { buzz.press(); model.replay(g) }, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).height(56.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym["replay"], null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (g.status == Status.WON) "Same puzzle" else "Try it again", style = ts(16f, 620), maxLines = 1)
                }
            }
            val size = g.deal.size
            if (size != null) Surface(onClick = { buzz.start(); model.start(g.deal.tier, size) }, color = cs.primary, contentColor = cs.onPrimary, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1.2f).height(56.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("New game", style = ts(16f, 620), maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    ShapeGlyph(Shp.difficulty[g.deal.tier.ordinal], 20.dp, cs.onPrimary.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun Hero(bg: Color, fg: Color, difficulty: Int, content: @Composable () -> Unit) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(32.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
        Box(
            // A huge cropped MaterialShape behind the number (Mosaic's tactic); drawn, never measured.
            Modifier.drawBehind {
                val s = 230.dp.toPx()
                drawShape(Morphs.of(Shp.difficulty[difficulty], Shp.difficulty[difficulty]), 0f, fg.copy(alpha = 0.13f), Size(s, s), Offset(size.width - s + 66.dp.toPx(), -74.dp.toPx()))
            },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

/** m:ss at display size, thousandths frozen and quieter (report 04 §5). */
@Composable
private fun BigTime(ms: Long, size: Float, color: Color) {
    val (mm, frac) = clockMs(ms)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(mm, style = ts(size, 330, color, wdth = 82, tnum = true, lineHeight = size * 0.98f))
        Text(frac, Modifier.padding(start = 2.dp, bottom = (size * 0.1f).dp).graphicsLayer { alpha = 0.78f }, style = ts(size * 0.45f, 420, color, wdth = 82, tnum = true, lineHeight = size * 0.45f))
    }
}

/** Earned: the badge shape in tertiaryContainer, revealed one by one. Missed: a dashed outline and the reason. */
@Composable
private fun Accolades(list: List<Accolade>) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val earned = list.filter { it.earned }
    val missed = list.filter { !it.earned }
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        earned.take(4).forEachIndexed { k, a ->
            val s = remember { Animatable(0f) }
            LaunchedEffect(a.key) { delay(700L + k * 90); s.animateTo(1f, springs.fast()) }
            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(52.dp).graphicsLayer { scaleX = s.value; scaleY = s.value; rotationZ = -90f * (1 - s.value) }, contentAlignment = Alignment.Center) {
                    ShapeGlyph(badgeShape(a.key), 52.dp, cs.tertiaryContainer)
                    Icon(Sym[badgeIcon(a.key)], null, Modifier.size(24.dp), tint = cs.onTertiaryContainer)
                }
                Text(a.label, style = ts(12f, 600, cs.onSurface), maxLines = 2)
            }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            missed.take(3).forEach { a ->
                Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShapeGlyph(badgeShape(a.key), 22.dp, cs.outline, outline = true)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(a.label, style = ts(12.5f, 650, cs.onSurface), maxLines = 1)
                            if (a.why.isNotEmpty()) Text(a.why, style = ts(11f, 540, cs.onSurfaceVariant, lineHeight = 13f), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun badgeShape(k: String) = when (k) {
    "unaided" -> MaterialShapes.Cookie9Sided
    "byhand" -> MaterialShapes.Flower
    "fast" -> MaterialShapes.Arrow
    "pure" -> MaterialShapes.Clover4Leaf
    "flawless" -> MaterialShapes.Gem
    "record" -> MaterialShapes.SoftBurst
    "sudden" -> MaterialShapes.Heart
    else -> MaterialShapes.Pentagon
}
private fun badgeIcon(k: String) = when (k) {
    "unaided" -> "lightbulb"; "byhand" -> "edit"; "fast" -> "bolt"; "pure" -> "check"
    "flawless" -> "star"; "record" -> "emoji_events"; "sudden" -> "favorite"; else -> "shield"
}

@Composable
private fun StatTile(label: String, value: String, unit: String, sub: String, modifier: Modifier, word: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = ts(12f, 600, cs.onSurfaceVariant), maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = if (word) ts(17f, 560, cs.onSurface, wdth = 90, lineHeight = 20f) else ts(25f, 480, cs.onSurface, wdth = 90, tnum = true), maxLines = if (word) 2 else 1)
                if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 1.dp, bottom = 2.dp), style = ts(15f, 520, cs.onSurfaceVariant))
            }
            Text(sub, style = ts(11.5f, 520, cs.onSurfaceVariant, tnum = true), maxLines = 1)
        }
    }
}

