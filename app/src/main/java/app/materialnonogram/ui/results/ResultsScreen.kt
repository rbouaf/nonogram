package app.materialnonogram.ui.results

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.materialnonogram.AppModel
import app.materialnonogram.Screen
import app.materialnonogram.data.Settings
import app.materialnonogram.engine.Lines
import app.materialnonogram.engine.Rung
import app.materialnonogram.engine.Size as PSize
import app.materialnonogram.game.Accolade
import app.materialnonogram.game.BAND_NAMES
import app.materialnonogram.game.Ev
import app.materialnonogram.game.Game
import app.materialnonogram.game.Mark
import app.materialnonogram.game.NET_NAMES
import app.materialnonogram.game.OpenCode
import app.materialnonogram.game.Report
import app.materialnonogram.game.Source
import app.materialnonogram.game.Status
import app.materialnonogram.game.StrikeKind
import app.materialnonogram.game.StrokeRecord
import app.materialnonogram.game.accolades
import app.materialnonogram.game.buildReport
import app.materialnonogram.game.clock
import app.materialnonogram.game.clockMs
import app.materialnonogram.game.median
import app.materialnonogram.game.net
import app.materialnonogram.game.recordKey
import app.materialnonogram.game.signedDelta
import app.materialnonogram.ui.kit.Hearts
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
import app.materialnonogram.ui.play.GridGeo
import app.materialnonogram.ui.play.TIER_NAMES
import kotlinx.coroutines.delay

/**
 * End of game (BUILD-PLAN §5.2; Sudoku's locked board 6 with nonogram content, `5-results-stats`):
 *
 * - **Won:** the solved board shrinks into the hero and the pattern is the trophy; the time at display
 *   size with its thousandths frozen; accolades as MaterialShapes; four stat tiles; the timeline (progress
 *   against your best, every stroke a bar); and a replay scrubbed by stroke on a mini board.
 * - **Lost:** hearts, the time survived, how far you got, and the board you had at each strike with what
 *   kind of strike it was.
 *
 * Every number is recomputed from the log by [Report]: an unrated one at once, the rated one off the main
 * thread ([buildReport]) a moment later.
 */
@Composable
fun ResultsScreen(model: AppModel, settings: Settings) {
    val g = model.finished ?: return
    val quick = remember(g) { Report(g, null) }
    val rated by produceState<Report?>(null, g) { value = buildReport(g) }
    val report = rated ?: quick
    val prior = model.history.filter { it.id != g.id }
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val clip = LocalClipboardManager.current
    // The trophy's place on screen, for the board → hero flight.
    var trophy by remember { mutableStateOf<Rect?>(null) }
    val from = model.finishedFrom
    val flight = remember(g) { Animatable(if (g.status == Status.WON && from != null) 0f else 1f) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 104.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = cs.surfaceContainer, contentColor = cs.onSurface, shape = RoundedCornerShape(50)) {
                        Row(Modifier.height(40.dp).padding(start = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ShapeGlyph(Shp.difficulty[g.deal.tier.ordinal], 22.dp, cs.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text("${TIER_NAMES[g.deal.tier.ordinal]} · ${g.w}×${g.h}", style = ts(15f, 650, tnum = true))
                        }
                    }
                    WordChip(null, if (g.net() == 0) "${g.rules.lives} lives" else NET_NAMES[g.net()], leading = {
                        if (g.net() == 2) Icon(Sym["shield"], null, Modifier.size(16.dp)) else ShapeGlyph(Shp.heart, 15.dp, cs.onSurfaceVariant, outline = true)
                    })
                    Spacer(Modifier.weight(1f))
                    if (g.status == Status.WON) {
                        // D10: the puzzle is its bitmap, and this is its checked code. Tap to copy.
                        val code = g.deal.shareCode
                        Surface(
                            onClick = { clip.setText(AnnotatedString(code)); buzz.toggle() },
                            color = cs.surfaceContainer, contentColor = cs.onSurfaceVariant, shape = RoundedCornerShape(50),
                            modifier = Modifier.semantics { contentDescription = "Copy the puzzle's code" },
                        ) {
                            Text(code.take(9) + "…", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = ts(13f, 600, wdth = 88, tnum = true), maxLines = 1)
                        }
                    }
                }
                if (g.status == Status.WON) Won(g, report, prior, hideTrophy = flight.value < 1f) { trophy = it }
                else Lost(g, report)
                val help = (if (report.assisted) "Assisted · " else "") + BAND_NAMES[g.band]
                Text(help + " · records are kept apart for each kind of help", Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp), style = ts(12f, 500, cs.onSurfaceVariant, lineHeight = 16f))
            }
            Actions(g, model, settings)
        }
        // The pattern is the trophy: the solved board shrinks from where it was in Play into the hero.
        val to = trophy
        if (from != null && to != null && flight.value < 1f) TrophyFlight(g, from, to, flight.value)
        val springs = LocalSprings.current
        LaunchedEffect(g, to != null) {
            if (to != null && flight.value < 1f) { delay(80); flight.animateTo(1f, springs.slow()) }
        }
    }
}

/** Each filled cell flies from its board tile to its trophy tile; the colour drains from tertiary to the hero's ink. */
@Composable
private fun TrophyFlight(g: Game, from: GridGeo, to: Rect, p: Float) {
    val cs = MaterialTheme.colorScheme
    val geo = trophyGeo(g)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val d = density
        // Board ink for the first half of the flight, hero ink by the landing: a straight lerp of the two goes grey midway.
        val col = lerp(cs.tertiary, cs.onPrimaryContainer, ((p - 0.45f) / 0.45f).coerceIn(0f, 1f))
        for (i in 0 until g.cells) {
            if (!g.deal.puzzle.filled(i)) continue
            val a = from.tile(i)
            val bx = to.left + geo.pos(i % g.w) * d; val by = to.top + geo.pos(i / g.w) * d; val bs = geo.cell * d
            val l = a.left + (bx - a.left) * p; val t = a.top + (by - a.top) * p
            val s = a.width + (bs - a.width) * p
            drawRoundRect(col, Offset(l, t) - origin, Size(s, s), CornerRadius(s * 0.24f))
        }
    }
}

/** README §3.7 draws a 15×15 trophy at 6.6 dp a cell; small boards grow to fill the same ~120 dp. */
private fun trophyGeo(g: Game) = MiniGeo.fit(g.w, g.h, 120f, 18f)

// ── Won ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Won(g: Game, r: Report, prior: List<Game>, hideTrophy: Boolean, onTrophy: (Rect) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val key = g.recordKey()
    val sameKey = prior.filter { it.status == Status.WON && it.recordKey() == key }
    val best = sameKey.minByOrNull { it.elapsed }
    val usual = prior.filter { it.status == Status.WON && it.w == g.w && it.h == g.h && it.deal.tier == g.deal.tier && it.net() == g.net() }
        .takeLast(30).map { it.elapsed }.median()
    Hero(cs.primaryContainer, cs.onPrimaryContainer, g.deal.tier.ordinal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (best == null || g.elapsed < best.elapsed) "Solved · new record" else "Solved", style = ts(13f, 600, cs.onPrimaryContainer.copy(alpha = 0.8f)))
                BigTime(g.elapsed, 64f, cs.onPrimaryContainer)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (best != null) Pill(
                        "${signedDelta(g.elapsed - best.elapsed).substringBefore('.')} vs ${clock(best.elapsed)}",
                        if (g.elapsed < best.elapsed) cs.onPrimaryContainer else cs.onPrimaryContainer.copy(alpha = 0.12f),
                        if (g.elapsed < best.elapsed) cs.primaryContainer else cs.onPrimaryContainer,
                    )
                    if (usual != null) Pill("${clock(usual)} usual", cs.onPrimaryContainer.copy(alpha = 0.12f), cs.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(10.dp))
            val geo = trophyGeo(g)
            MiniGrid(
                geo, solutionBoard({ g.deal.puzzle.filled(it) }, g.cells), cs.onPrimaryContainer, null,
                Modifier.onGloballyPositioned { onTrophy(it.boundsInRoot()) }.graphicsLayer { alpha = if (hideTrophy) 0f else 1f }
                    .semantics { contentDescription = "The pattern you uncovered" },
            )
        }
    }
    Accolades(accolades(r, prior))
    StatTiles(g, r)
    Timeline(g, r, sameKey.minByOrNull { it.elapsed }?.let { Report(it, null) })
}

@Composable
private fun Hero(bg: Color, fg: Color, difficulty: Int, content: @Composable () -> Unit) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(32.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
        Box(
            // A huge cropped MaterialShape behind the number (Mosaic's tactic); drawn, never measured.
            Modifier.drawBehind {
                val s = 230.dp.toPx()
                drawShape(Morphs.of(Shp.difficulty[difficulty], Shp.difficulty[difficulty]), 0f, fg.copy(alpha = 0.1f), Size(s, s), Offset(size.width - s + 66.dp.toPx(), -74.dp.toPx()))
            },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

/** m:ss at display size, thousandths frozen and quieter (Sudoku report 04 §5; README §3.7: 64 sp wght 330 wdth 82). */
@Composable
private fun BigTime(ms: Long, size: Float, color: Color) {
    val (mm, frac) = clockMs(ms)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(mm, style = ts(size, 330, color, wdth = 82, tnum = true, lineHeight = size * 0.98f))
        Text(frac, Modifier.padding(start = 2.dp, bottom = (size * 0.1f).dp).graphicsLayer { alpha = 0.72f }, style = ts(size * 0.4f, 420, color, wdth = 82, tnum = true, lineHeight = size * 0.4f))
    }
}

/**
 * Accolades in one grid of up to six a row (board 5): earned ones are their MaterialShape in
 * tertiaryContainer, revealed one by one; missed ones are a dashed outline with the reason under them.
 */
@Composable
private fun Accolades(list: List<Accolade>) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val buzz = LocalBuzz.current
    val ordered = list.filter { it.earned } + list.filter { !it.earned }
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.chunked(6).forEachIndexed { row, items ->
            Row(Modifier.fillMaxWidth()) {
                items.forEachIndexed { k, a ->
                    val n = row * 6 + k
                    val s = remember { Animatable(if (a.earned) 0f else 1f) }
                    LaunchedEffect(a.key) { if (a.earned) { delay(650L + n * 110); buzz.select(); s.animateTo(1f, springs.fast()) } }
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = a.label + if (a.earned) ", earned" else ", missed" + if (a.why.isNotEmpty()) ": ${a.why}" else "" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(50.dp).graphicsLayer { scaleX = s.value; scaleY = s.value; rotationZ = -90f * (1 - s.value) }, contentAlignment = Alignment.Center) {
                            if (a.earned) {
                                ShapeGlyph(badgeShape(a.key), 50.dp, if (a.key == "record") cs.tertiary else cs.tertiaryContainer)
                                Icon(Sym[badgeIcon(a.key)], null, Modifier.size(22.dp), tint = if (a.key == "record") cs.onTertiary else cs.onTertiaryContainer)
                            } else {
                                Canvas(Modifier.size(44.dp)) {
                                    drawCircle(cs.outline, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                                }
                                Icon(Sym[badgeIcon(a.key)], null, Modifier.size(20.dp), tint = cs.outline)
                            }
                        }
                        Text(a.label, style = ts(12f, 620, if (a.earned) cs.onSurface else cs.onSurfaceVariant, lineHeight = 14f), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (!a.earned && a.why.isNotEmpty()) Text(a.why, style = ts(10.5f, 500, cs.onSurfaceVariant, lineHeight = 12f), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                repeat(6 - items.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun badgeShape(k: String): RoundedPolygon = when (k) {
    "record" -> MaterialShapes.Sunny
    "unaided" -> MaterialShapes.Clover4Leaf
    "pure" -> MaterialShapes.Gem
    "fast" -> MaterialShapes.Pill
    "byhand" -> MaterialShapes.Flower
    "flawless" -> MaterialShapes.Cookie9Sided
    "sawahead" -> MaterialShapes.Arch
    "sudden" -> MaterialShapes.Heart
    else -> MaterialShapes.Pentagon
}

private fun badgeIcon(k: String) = when (k) {
    "record" -> "emoji_events"; "unaided" -> "neurology"; "pure" -> "diamond"; "fast" -> "bolt"; "byhand" -> "touch_app"
    "flawless" -> "shield"; "sawahead" -> "visibility"; "sudden" -> "favorite"; else -> "shield"
}

private fun lineName(g: Game, line: Int, cap: Boolean = false) =
    (if (line < g.h) "row ${line + 1}" else "column ${line - g.h + 1}").let { if (cap) it.replaceFirstChar { c -> c.uppercase() } else it }

@Composable
private fun StatTiles(g: Game, r: Report) {
    val player = r.playerMarks
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("Accuracy", "${(r.accuracy * 100).toInt()}%", "${player.count { it.correct }} of ${player.size + r.strikes.count { it.refused }}", Modifier.weight(1f))
        if (r.rated) {
            StatTile("Effort/min", "${r.effortPerMin}", "${r.effort} total", Modifier.weight(1f))
            val h = r.hardest
            val rung = h?.rating?.rung
            val big = when {
                h == null || rung == null -> "—"
                rung.isLine && h.rating!!.line >= 0 -> lineName(g, h.rating!!.line, cap = true)
                else -> "r${h.cell / g.w + 1}c${h.cell % g.w + 1}"
            }
            val sub = if (h == null || rung == null) "" else (if (rung.isLine) "line logic" else "look-ahead") + " · effort " + "%.1f".format(h.effort10 / 10.0)
            StatTile("Hardest step", big, sub, Modifier.weight(1.15f), word = true)
        } else {
            StatTile("Per cell", "%.1fs".format(r.secondsPerCell), "${r.cells} cells", Modifier.weight(1f))
            StatTile("Strokes", "${r.strokeCount}", "longest ${r.longestStroke}", Modifier.weight(1.15f))
        }
        val word = when { r.rhythm == 0 -> "—"; r.rhythm >= 70 -> "steady"; r.rhythm >= 45 -> "bursty"; else -> "uneven" }
        StatTile("Rhythm", word, if (r.rhythm == 0) "too short" else "consistency ${r.rhythm}", Modifier.weight(1f), word = true)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier, word: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = modifier.height(100.dp)) {
        Column(Modifier.padding(start = 11.dp, end = 8.dp, top = 10.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = ts(12f, 600, cs.onSurfaceVariant), maxLines = 1)
            Text(value, style = if (word) ts(18f, 600, cs.onSurface, wdth = 88, lineHeight = 22f) else ts(26f, 480, cs.onSurface, wdth = 88, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = ts(11.5f, 520, cs.onSurfaceVariant, tnum = true, lineHeight = 14f), maxLines = 2)
        }
    }
}

// ── Timeline and replay ──────────────────────────────────────────────────────────────────────────

/**
 * Monkeytype's grammar (§5.2): one hero series, everything else recessive. The line is cells decided over
 * time against a dashed ghost of your best for the same record; under it every stroke is a bar as tall as
 * the cells it covered (crosses dimmer); mistakes are ×, hints and checks small glyphs on the axis. Drag
 * along it to scrub the replay, stroke by stroke.
 */
@Composable
private fun Timeline(g: Game, r: Report, best: Report?) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val strokes = r.strokes
    if (strokes.isEmpty()) return
    // Opens on the longest sweep: the nonogram moment worth replaying.
    var k by remember(r.strokes.size) { mutableIntStateOf(strokes.indices.maxBy { strokes[it].cells.size }) }
    val measurer = rememberTextMeasurer()
    val axis = ts(10.5f, 560, cs.onSurfaceVariant, tnum = true)
    val end = maxOf(r.time, best?.time ?: 0L).coerceAtLeast(1L)
    val n = maxOf(g.w, g.h)
    val hints = remember(g) { g.events.filter { it.kind == Ev.HINT && it.value == 1 }.map { it.t } }
    val checks = remember(g) { g.events.filter { it.kind == Ev.CHECK }.map { it.t } }
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Timeline", Modifier.weight(1f), style = ts(15f, 650, cs.onSurface))
                Legend(cs.primary, "you"); Spacer(Modifier.width(10.dp))
                if (best != null) { Legend(cs.outline, "best", dashed = true); Spacer(Modifier.width(10.dp)) }
                Legend(cs.onSurfaceVariant, "strokes", bar = true)
            }
            fun pick(x: Float, w: Float, left: Float) {
                val t = ((x - left) / (w - left)).coerceIn(0f, 1f) * end
                var j = strokes.indexOfLast { it.t0 <= t }.coerceAtLeast(0)
                if (j + 1 < strokes.size && kotlin.math.abs(strokes[j + 1].t0 - t) < kotlin.math.abs(strokes[j].t0 - t)) j++
                if (j != k) { k = j; buzz.select() }
            }
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .pointerInput(r) { detectTapGestures { pick(it.x, size.width.toFloat(), 26.dp.toPx()) } }
                    // Horizontal only, so a vertical swipe still scrolls the page.
                    .pointerInput(r) { detectHorizontalDragGestures { ch, _ -> pick(ch.position.x, size.width.toFloat(), 26.dp.toPx()) } }
                    .semantics { contentDescription = "Timeline: ${strokes.size} strokes over ${clock(r.time)}. Drag to replay stroke by stroke." },
            ) {
                val left = 26.dp.toPx(); val w = size.width
                val axisY = size.height - 18.dp.toPx()
                val barH = 24.dp.toPx()
                val top = 10.dp.toPx(); val base = axisY - barH - 8.dp.toPx()
                fun x(t: Long) = left + (w - left - 4.dp.toPx()) * t / end
                fun y(c: Int) = base - (base - top) * c / g.cells
                drawLine(cs.outlineVariant, Offset(left, axisY), Offset(w, axisY), 1.dp.toPx())
                drawText(measurer, "${g.cells}", Offset(0f, top - 6.dp.toPx()), axis)
                drawText(measurer, "0", Offset(0f, base - 8.dp.toPx()), axis)
                // Best: a dashed ghost.
                if (best != null && best.progress.isNotEmpty()) {
                    val p = Path().apply { moveTo(x(0), y(0)); best.progress.forEach { pt -> lineTo(x(pt.t), y(pt.decided)) } }
                    drawPath(p, cs.outline, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                }
                // You: the hero line with a soft area.
                val you = Path().apply { moveTo(x(0), y(0)); r.progress.forEach { pt -> lineTo(x(pt.t), y(pt.decided)) } }
                val area = Path().apply { addPath(you); lineTo(x(r.progress.lastOrNull()?.t ?: 0L), base); lineTo(x(0), base); close() }
                drawPath(area, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.26f), cs.primary.copy(alpha = 0.02f)), top, base))
                drawPath(you, cs.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                // Strokes as bars: height = cells / line length.
                strokes.forEachIndexed { i, s ->
                    val h = (barH * s.cells.size / n).coerceIn(2.dp.toPx(), barH)
                    val lit = i == k
                    val c = when {
                        lit -> cs.tertiary
                        s.source == Source.HINT -> cs.secondary.copy(alpha = 0.7f)
                        s.value == Mark.CROSSED -> cs.onSurfaceVariant.copy(alpha = 0.35f)
                        else -> cs.onSurfaceVariant.copy(alpha = 0.7f)
                    }
                    val bw = if (lit) 3.dp.toPx() else 2.dp.toPx()
                    drawRoundRect(c, Offset(x(s.t0) - bw / 2, axisY - h), Size(bw, h), CornerRadius(1.dp.toPx()))
                }
                // Mistakes as ×, hints and checks as small glyphs on the axis.
                r.strikes.forEach { s ->
                    val cx = x(s.t); val cy = axisY - barH - 2.dp.toPx(); val a = 4.dp.toPx()
                    drawLine(cs.error, Offset(cx - a, cy - a), Offset(cx + a, cy + a), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(cs.error, Offset(cx + a, cy - a), Offset(cx - a, cy + a), 2.dp.toPx(), StrokeCap.Round)
                }
                hints.forEach { t -> drawCircle(cs.secondary, 3.5.dp.toPx(), Offset(x(t), axisY + 1.dp.toPx())) }
                checks.forEach { t ->
                    val c = Offset(x(t), axisY + 1.dp.toPx()); val a = 4.dp.toPx()
                    drawPath(Path().apply { moveTo(c.x, c.y - a); lineTo(c.x + a, c.y); lineTo(c.x, c.y + a); lineTo(c.x - a, c.y); close() }, cs.secondary)
                }
                // Scrub line, at the stroke's end, with a dot on the line.
                val sk = strokes[k]
                val decided = r.progress.lastOrNull { it.t <= sk.t1 }?.decided ?: 0
                drawLine(cs.onSurface.copy(alpha = 0.7f), Offset(x(sk.t1), top - 4.dp.toPx()), Offset(x(sk.t1), axisY), 1.5.dp.toPx())
                drawCircle(cs.tertiary, 5.dp.toPx(), Offset(x(sk.t1), y(decided)))
                // Minutes.
                val mins = (end / 60_000).toInt()
                val step = when { mins >= 30 -> 10; mins >= 12 -> 5; mins >= 5 -> 2; else -> 1 }
                if (mins == 0) {
                    val secs = (end / 1000).toInt(); val ss = when { secs > 40 -> 15; secs > 20 -> 10; else -> 5 }
                    for (s in 0..secs step ss) drawText(measurer, if (s == 0) "0" else "0:${s.toString().padStart(2, '0')}", Offset(x(s * 1000L) - 6.dp.toPx(), axisY + 3.dp.toPx()), axis)
                } else for (m in 0..mins step step) drawText(measurer, if (m == 0) "0" else "$m:00", Offset(x(m * 60_000L) - 6.dp.toPx(), axisY + 3.dp.toPx()), axis)
            }
            Replay(g, r, k)
        }
    }
}

@Composable
private fun Legend(c: Color, label: String, dashed: Boolean = false, bar: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(if (bar) 6.dp else 14.dp, 10.dp)) {
            if (bar) drawRoundRect(c.copy(alpha = 0.7f), Offset(size.width / 2 - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            else drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) else null)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = ts(11.5f, 560, cs.onSurfaceVariant))
    }
}

/** The board after stroke k, its cells lit, and what that stroke was ("row 4: line logic"). */
@Composable
private fun Replay(g: Game, r: Report, k: Int) {
    val cs = MaterialTheme.colorScheme
    val s = r.strokes[k]
    val lit = remember(s) { s.cells.toSet() }
    Row(Modifier.padding(start = 4.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MiniGrid(MiniGeo.fit(g.w, g.h, 104f, 6f), s.board, cs.onSurfaceVariant.copy(alpha = 0.75f), cs.surfaceContainerHighest, cross = cs.outline, lit = lit, litColor = cs.tertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Stroke ${k + 1} of ${r.strokes.size} · ${clock(s.t0)}", style = ts(15f, 650, cs.onSurface, tnum = true))
            Text(strokeWords(g, s), style = ts(13f, 500, cs.onSurfaceVariant, lineHeight = 18f))
            val (word, good) = strokeTier(g, s)
            Surface(color = if (good) cs.primaryContainer else cs.surfaceContainerHighest, contentColor = if (good) cs.onPrimaryContainer else cs.onSurface, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym[if (good) "check" else if (s.source == Source.HINT) "lightbulb" else "help"], null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                    Text(word, style = ts(12.5f, 620), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun strokeWords(g: Game, s: StrokeRecord): String {
    val n = s.cells.size
    val what = when (s.value) { Mark.FILLED -> "filled"; Mark.CROSSED -> "crossed"; else -> "cleared" }
    val where = if (s.line >= 0 && n >= 2) lineName(g, s.line, cap = true) else "r${s.cells[0] / g.w + 1}c${s.cells[0] % g.w + 1}"
    val base = when {
        s.source == Source.HINT -> "$where, placed by a hint"
        n == 1 -> "$where, $what"
        else -> "$where, $n $what in one drag"
    }
    val auto = if (s.autoCells.isNotEmpty()) " · ${s.autoCells.size} crossed for you" else ""
    return base + auto + if (s.struck) " · stopped by a mistake" else ""
}

/** What justified the stroke: its hardest rated mark's rung, or a guess. */
private fun strokeTier(g: Game, s: StrokeRecord): Pair<String, Boolean> {
    val rung = s.rung
    return when {
        s.source == Source.HINT -> "Hint" to false
        rung == null && s.guesses > 0 -> "A guess: nothing forced it yet" to false
        rung == null -> (if (s.value == Mark.UNKNOWN) "A clear" else "Already decided") to false
        rung.isLine -> "Line logic: " + (if (s.line >= 0) lineName(g, s.line) + " " else "") + rung.label to true
        rung == Rung.P1 -> "Look-ahead" to true
        else -> "Deep look-ahead" to true
    }
}

// ── Lost ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Lost(g: Game, r: Report) {
    val cs = MaterialTheme.colorScheme
    Hero(cs.surfaceContainerHigh, cs.onSurface, g.deal.tier.ordinal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hearts(0, g.rules.lives, 26.dp, spent = cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Out of lives", style = ts(28f, 560, cs.onSurface))
        }
        Spacer(Modifier.height(10.dp))
        Text("Time survived", style = ts(13f, 600, cs.onSurfaceVariant))
        BigTime(g.elapsed, 64f, cs.onSurface)
    }
    // How far you got: one tile per cell, decided ones lit, the strikes in error where they happened.
    val strikeAt = r.strikes.map { it.decided }.toSet()
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("How far you got", Modifier.weight(1f), style = ts(15f, 650, cs.onSurface))
                Text("${r.decided} of ${r.cells} cells · ${100 * r.decided / r.cells.coerceAtLeast(1)}%", style = ts(12.5f, 540, cs.onSurfaceVariant, tnum = true))
            }
            Spacer(Modifier.height(10.dp))
            val cols = 25
            val rows = (r.cells + cols - 1) / cols
            Canvas(Modifier.fillMaxWidth().height((rows * 10).dp).semantics { contentDescription = "${r.decided} of ${r.cells} cells decided" }) {
                val gp = 2.dp.toPx(); val s = (size.width - gp * (cols - 1)) / cols
                val sh = minOf(s, 8.dp.toPx())
                for (i in 0 until r.cells) {
                    val o = Offset((i % cols) * (s + gp), (i / cols) * (sh + gp))
                    val c = when { i in strikeAt -> cs.error; i < r.decided -> cs.tertiary; else -> cs.surfaceContainerHighest }
                    drawRoundRect(c, o, Size(s, sh), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
    if (r.strikes.isNotEmpty()) Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                val words = listOf("", "The strike", "The two strikes", "The three strikes")
                Text(words.getOrElse(r.strikes.size) { "The ${r.strikes.size} strikes" }, Modifier.weight(1f), style = ts(15f, 650, cs.onSurface))
                Text("the board you had each time", style = ts(12.5f, 520, cs.onSurfaceVariant))
            }
            r.strikes.forEachIndexed { i, s ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(cs.outlineVariant.copy(alpha = 0.5f)) })
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniGrid(MiniGeo.fit(g.w, g.h, 78f, 5f), s.board, cs.onSurfaceVariant.copy(alpha = 0.7f), cs.surfaceContainerHighest, bad = s.cell, badColor = cs.error)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${clock(s.t)} · r${s.cell / g.w + 1}c${s.cell % g.w + 1}", style = ts(15f, 650, cs.onSurface, tnum = true))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val k = s.kind
                            if (k != null) {
                                Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, shape = RoundedCornerShape(50)) {
                                    Text(when (k) { StrikeKind.SLIP -> "Slip"; StrikeKind.GUESS -> "Guess"; StrikeKind.WRONG -> "Wrong" }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = ts(12f, 650))
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        Text(strikeWords(g, s), style = ts(13f, 500, cs.onSurfaceVariant, lineHeight = 18f))
                    }
                }
            }
        }
    }
    // The same timeline a win gets: progress over time, every stroke, and the strikes where they fell.
    Timeline(g, r, null)
    Text("A loss keeps no time record. It counts once in your stats as a loss.", Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp), style = ts(12f, 500, cs.onSurfaceVariant, lineHeight = 16f))
}

/** A strike in words, judged on the board you had (§5.2): slip / guess / wrong. */
private fun strikeWords(g: Game, s: app.materialnonogram.game.Strike): String {
    val mark = if (s.value == Mark.FILLED) "a fill" else "a cross"
    val should = if (s.value == Mark.FILLED) "empty" else "filled"
    return when (s.kind) {
        StrikeKind.SLIP -> if (s.line >= 0) {
            val clue = g.deal.puzzle.clues.line(s.line).joinToString(" ").ifEmpty { "0" }
            "${lineName(g, s.line, cap = true)}'s $clue already rules out $mark here: a misread clue"
        } else "Its own row or column already rules out $mark here: a misread clue"
        StrikeKind.GUESS -> "Nothing on the board forced this cell either way"
        StrikeKind.WRONG -> "It had to be $should, but from this board only looking ahead shows it"
        null -> "A wrong ${if (s.value == Mark.FILLED) "fill" else "cross"}"
    }
}

// ── Actions ──────────────────────────────────────────────────────────────────────────────────────

/** Home · Same puzzle · New game · Share: Sudoku's ButtonGroup look [M3E-NEW] over a scrim. */
@Composable
private fun Actions(g: Game, model: AppModel, settings: Settings) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val won = g.status == Status.WON
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(104.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(0.3f to Color.Transparent, 0.3f to cs.surface.copy(alpha = 0f), 1f to cs.surface)) }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = { buzz.select(); model.screen = Screen.HOME }, color = cs.surfaceContainerHigh, contentColor = cs.onSurface, shape = RoundedCornerShape(28.dp, 8.dp, 8.dp, 28.dp), modifier = Modifier.size(54.dp, 60.dp).semantics { contentDescription = "Home" }) {
                Box(contentAlignment = Alignment.Center) { Icon(Sym["home"], null, Modifier.size(24.dp)) }
            }
            Surface(onClick = { buzz.press(); model.replay(g) }, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(60.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym["replay"], null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (won) "Same puzzle" else "Try again", style = ts(15.5f, 620, wdth = 90), maxLines = 1)
                }
            }
            val size = g.deal.size ?: PSize.entries[settings.lastSize]
            Surface(
                onClick = { buzz.start(); model.start(g.deal.tier, size) }, color = cs.primary, contentColor = cs.onPrimary,
                shape = if (won) RoundedCornerShape(8.dp) else RoundedCornerShape(8.dp, 28.dp, 28.dp, 8.dp), modifier = Modifier.weight(1f).height(60.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym["add"], null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp))
                    Text("New game", style = ts(15.5f, 620, wdth = 90), maxLines = 1)
                }
            }
            if (won) ShareButton(g)
        }
    }
}

/** D10: Share sends the checked code through the share sheet; "Copy as text" copies the SGT Pattern ID. */
@Composable
private fun ShareButton(g: Game) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { buzz.select(); open = true }, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(8.dp, 28.dp, 28.dp, 8.dp), modifier = Modifier.size(54.dp, 60.dp).semantics { contentDescription = "Share this puzzle" }) {
            Box(contentAlignment = Alignment.Center) { Icon(Sym["share"], null, Modifier.size(22.dp)) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = cs.surfaceContainerHigh) {
            DropdownMenuItem(
                text = { Column { Text("Share code", style = ts(15f, 600)); Text(g.deal.shareCode, style = ts(11.5f, 500, cs.onSurfaceVariant, wdth = 78, tnum = true), maxLines = 1) } },
                leadingIcon = { Icon(Sym["share"], null, Modifier.size(20.dp)) },
                onClick = {
                    open = false
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, OpenCode.shareText(g.deal, TIER_NAMES[g.deal.tier.ordinal]))
                    ctx.startActivity(Intent.createChooser(send, "Share this puzzle"))
                },
            )
            DropdownMenuItem(
                text = { Text("Copy code", style = ts(15f, 600)) },
                leadingIcon = { Icon(Sym["content_copy"], null, Modifier.size(20.dp)) },
                onClick = { open = false; clip.setText(AnnotatedString(g.deal.shareCode)); buzz.toggle() },
            )
            DropdownMenuItem(
                text = { Column { Text("Copy as text", style = ts(15f, 600)); Text("Simon Tatham's Pattern game ID", style = ts(12f, 500, cs.onSurfaceVariant)) } },
                leadingIcon = { Icon(Sym["content_paste"], null, Modifier.size(20.dp)) },
                onClick = { open = false; clip.setText(AnnotatedString(g.deal.sgtId)); buzz.toggle() },
            )
        }
    }
}
