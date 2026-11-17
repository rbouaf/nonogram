package app.materialnonogram.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.materialnonogram.AppModel
import app.materialnonogram.Screen
import app.materialnonogram.data.Settings
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Size as PSize
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.BAND_NAMES
import app.materialnonogram.game.BestCell
import app.materialnonogram.game.Game
import app.materialnonogram.game.NET_NAMES
import app.materialnonogram.game.Status
import app.materialnonogram.game.bestsMatrix
import app.materialnonogram.game.clock
import app.materialnonogram.game.clockMs
import app.materialnonogram.game.historyPoints
import app.materialnonogram.game.lookAhead
import app.materialnonogram.game.net
import app.materialnonogram.game.runningBest
import app.materialnonogram.game.streaks
import app.materialnonogram.game.totals
import app.materialnonogram.game.trailingGeoMean
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.SectionHead
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.drawShape
import app.materialnonogram.ui.kit.ts
import app.materialnonogram.ui.play.TIER_NAMES
import app.materialnonogram.ui.results.MiniGeo
import app.materialnonogram.ui.results.MiniGrid
import app.materialnonogram.ui.results.solutionBoard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Stats (BUILD-PLAN §5.3; Sudoku's locked board 6, `5-results-stats`): one filter block scopes everything
 * under it — difficulty, size (each as its grid outline), the net and the help — then activity, the
 * size × difficulty matrix of bests, history, look-ahead, when you play, and totals. Every number is
 * recomputed from the game logs; per-game summaries come from [AppModel.summarise], cached by game id and
 * engine version and filled in the background.
 */
@Composable
fun StatsScreen(model: AppModel, settings: Settings) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val all = model.history
    LaunchedEffect(all.size) { model.summarise(all) }
    var tierF by remember { mutableIntStateOf(-1) }   // -1 = all
    var sizeF by remember { mutableStateOf<PSize?>(null) }
    var netF by remember { mutableIntStateOf(-1) }
    var aidF by remember { mutableIntStateOf(-1) }
    fun helpOk(g: Game) = (netF < 0 || g.net() == netF) && (aidF < 0 || (if (aidF == 3) g.assisted else !g.assisted && g.band == aidF))
    val scoped = all.filter { helpOk(it) }
    val games = scoped.filter { g -> (tierF < 0 || g.deal.tier.ordinal == tierF) && (sizeF == null || g.deal.size == sizeF) }
    val won = games.filter { it.status == Status.WON }
    val playedTiers = all.map { it.deal.tier.ordinal }.distinct().sorted()
    val playedSizes = all.mapNotNull { it.deal.size }.distinct().sortedBy { it.ordinal }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SquishIcon("arrow_back", "Back", { model.screen = Screen.HOME })
            Text("Stats", Modifier.weight(1f).padding(start = 4.dp), style = ts(24f, 560, cs.onSurface))
        }
        // Filters: one block above everything they scope.
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(listOf(-1) + playedTiers, tierF, label = { if (it < 0) null else TIER_NAMES[it] }, onPick = { buzz.toggle(); tierF = it }) { d, on ->
                if (d < 0) Text("All", style = ts(14f, 620)) else ShapeGlyph(Shp.difficulty[d], 16.dp, if (on) cs.onSecondaryContainer else cs.onSurfaceVariant)
            }
            val sizeOpts = listOf(-1) + playedSizes.map { it.ordinal }
            Segmented(sizeOpts, sizeF?.ordinal ?: -1, label = { if (it < 0) null else PSize.entries[it].label }, onPick = { buzz.toggle(); sizeF = if (it < 0) null else PSize.entries[it] }) { k, on ->
                if (k < 0) Text("All", style = ts(14f, 620)) else SizeGlyph(PSize.entries[k], 1.0f, if (on) cs.onSecondaryContainer else cs.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(if (netF < 0) "Any net" else NET_NAMES[netF], netF >= 0) { buzz.toggle(); netF = if (netF >= 2) -1 else netF + 1 }
                FilterChip(if (aidF < 0) "Any help" else if (aidF == 3) "Assisted" else BAND_NAMES[aidF], aidF >= 0) { buzz.toggle(); aidF = if (aidF >= 3) -1 else aidF + 1 }
                Spacer(Modifier.weight(1f))
                Text("${games.size} games · ${won.size} won", style = ts(12.5f, 560, cs.onSurfaceVariant, tnum = true))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (all.isEmpty()) {
                Text("Finish a game and your stats start here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                return@Column
            }
            Activity(games)
            Bests(model, settings, scoped, tierF, sizeF)
            History(won, perCell = sizeF == null)
            lookAhead(games, model.summaries)?.let { LookAheadCard(it) }
            WhenYouPlay(won)
            TotalsCard(games, model)
        }
    }
}

/**
 * Sudoku's segmented filter row: connected tiles, the chosen one widens, goes full-round and shows its
 * name. Role.Tab in a selectableGroup.
 */
@Composable
private fun Segmented(opts: List<Int>, sel: Int, label: (Int) -> String?, onPick: (Int) -> Unit, glyph: @Composable (Int, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.height(40.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        opts.forEachIndexed { k, d ->
            val on = d == sel
            val first = k == 0; val last = k == opts.lastIndex
            val name = label(d)
            Surface(
                color = if (on) cs.secondaryContainer else cs.surfaceContainerHigh, contentColor = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
                shape = RoundedCornerShape(if (on || first) 20.dp else 8.dp, if (on || last) 20.dp else 8.dp, if (on || last) 20.dp else 8.dp, if (on || first) 20.dp else 8.dp),
                modifier = Modifier.weight(if (on && name != null) 1.9f else 1f).height(40.dp)
                    .semantics { role = Role.Tab; selected = on; contentDescription = name ?: "All" }
                    .clickable { onPick(d) },
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    glyph(d, on)
                    if (on && name != null) { Spacer(Modifier.width(6.dp)); Text(name, style = ts(13.5f, 640, wdth = 90, tnum = true), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SizeGlyph(s: PSize, k: Float, color: Color) {
    Canvas(Modifier.size((s.w * k).dp, (s.h * k).dp)) {
        val c = k.dp.toPx(); val sw = 1.dp.toPx()
        drawRoundRect(color, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(1.5.dp.toPx()), style = Stroke(sw * 1.2f))
        for (x in 5 until s.w step 5) drawLine(color.copy(alpha = 0.6f), Offset(x * c, 0f), Offset(x * c, size.height), sw * 0.8f)
        for (y in 5 until s.h step 5) drawLine(color.copy(alpha = 0.6f), Offset(0f, y * c), Offset(size.width, y * c), sw * 0.8f)
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, color = if (on) cs.secondaryContainer else Color.Transparent, contentColor = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp), border = if (on) null else BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(Modifier.height(32.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ts(13f, 600)); Icon(Sym["expand_more"], null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun CardTitle(title: String, aside: String?) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = ts(16f, 650, cs.onSurface))
        if (aside != null) Text(aside, Modifier.weight(1f).padding(start = 10.dp), style = ts(12f, 520, cs.onSurfaceVariant), textAlign = TextAlign.End, maxLines = 2)
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
private fun date(ms: Long): String { val d = day(ms); return "${d.dayOfMonth} ${d.month.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH)}" }
private fun hm(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} h ${m % 60} m" else "$m m" }

/** The streak, and 26 weeks of day tiles (minutes played that day; streak days are round). */
@Composable
private fun Activity(games: List<Game>) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val byDay = games.groupBy { day(it.startedAt) }.mapValues { (_, v) -> v.sumOf { it.elapsed } }
    val today = LocalDate.now(zone)
    val (streak, longest) = streaks(byDay.keys, today)
    val week = games.filter { day(it.startedAt) > today.minusDays(7) }.sumOf { it.elapsed }
    val end = if (today in byDay) today else today.minusDays(1)
    val streakDays = (0 until streak).map { end.minusDays(it.toLong()) }.toSet()
    val maxMs = (byDay.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                ShapeGlyph(MaterialShapes.Sunny, 48.dp, if (streak > 0) cs.tertiary else cs.surfaceContainerHighest)
                Icon(Sym["local_fire_department"], null, Modifier.size(22.dp), tint = if (streak > 0) cs.onTertiary else cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text("$streak", style = ts(34f, 480, cs.onSurface, wdth = 88, tnum = true, lineHeight = 36f))
            Text(if (streak == 1) " day" else " days", Modifier.padding(top = 8.dp), style = ts(15f, 600, cs.onSurface))
            Spacer(Modifier.weight(1f))
            Text("longest $longest · ${hm(week)} this week", style = ts(12.5f, 540, cs.onSurfaceVariant, tnum = true))
        }
        Spacer(Modifier.height(12.dp))
        val axis = ts(10f, 560, cs.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(104.dp).semantics { contentDescription = "Streak $streak days, longest $longest. Played on ${byDay.size} days." }) {
            val weeks = 26
            val left = 14.dp.toPx(); val top = 14.dp.toPx()
            val pitch = (size.width - left) / weeks
            val s = minOf(pitch * 0.82f, (size.height - top) / 7 * 0.82f)
            val gp = (size.height - top) / 7 - s
            val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
            listOf("M" to 0, "W" to 2, "F" to 4).forEach { (l, r) -> drawText(measurer, l, Offset(0f, top + r * (s + gp) - 2.dp.toPx()), axis) }
            var lastMonth = -1; var lastLabelX = -100f
            for (w in 0 until weeks) for (dd in 0 until 7) {
                val dt = start.plusDays((w * 7 + dd).toLong())
                if (dt.isAfter(today)) continue
                val lx = left + w * pitch
                if (dd == 0 && dt.monthValue != lastMonth) {
                    lastMonth = dt.monthValue
                    if (lx - lastLabelX > 28.dp.toPx()) { drawText(measurer, dt.month.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH), Offset(lx, -2.dp.toPx()), axis); lastLabelX = lx }
                }
                val o = Offset(lx, top + dd * (s + gp))
                val ms = byDay[dt]
                val c = if (ms == null) cs.surfaceContainerHighest else lerp(cs.primaryContainer, cs.primary, (ms.toFloat() / maxMs).coerceIn(0.15f, 1f))
                if (dt in streakDays) drawCircle(c, s / 2, o + Offset(s / 2, s / 2)) else drawRoundRect(c, o, Size(s, s), CornerRadius(s * 0.22f))
                if (dt == today) drawRoundRect(cs.tertiary, o - Offset(1.dp.toPx(), 1.dp.toPx()), Size(s + 2.dp.toPx(), s + 2.dp.toPx()), CornerRadius(s * 0.3f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

// ── Personal bests: a size × difficulty matrix (§5.3) ─────────────────────────────────────────────

/**
 * Sizes down the side, difficulties across. A tile is the best time and how many solves (darker = more),
 * "—" where the combination doesn't exist, a dashed "not yet" where it does but has no win. The net and
 * help filters choose the records; the difficulty and size filters only light up their row and column.
 * Tapping a tile opens its card: the last ten as a sparkline and the PB game.
 */
@Composable
private fun Bests(model: AppModel, settings: Settings, scoped: List<Game>, tierF: Int, sizeF: PSize?) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val m = bestsMatrix(scoped)
    val sizes = PSize.entries.filter { settings.sizeOn(it) || m.keys.any { k -> k.first == it.w && k.second == it.h } }
    val tiers = Tier.entries.filter { settings.tierOn(it) || m.keys.any { k -> k.third == it } }
    var open by remember { mutableStateOf<Triple<Int, Int, Tier>?>(null) }
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    Card {
        CardTitle("Personal bests", "size × difficulty · stronger = more solves")
        Row(Modifier.padding(start = 64.dp, bottom = 6.dp)) {
            tiers.forEach { t ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ShapeGlyph(Shp.difficulty[t.ordinal], 18.dp, if (tierF < 0 || tierF == t.ordinal) cs.onSurfaceVariant else cs.outlineVariant)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sizes.forEach { s ->
                val rowOn = sizeF == null || sizeF == s
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.width(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(15.dp), contentAlignment = Alignment.Center) { SizeGlyph(s, 0.7f, if (rowOn) cs.onSurfaceVariant else cs.outlineVariant) }
                        Spacer(Modifier.width(3.dp))
                        Text(s.label, style = ts(12f, 600, if (rowOn) cs.onSurfaceVariant else cs.outlineVariant, wdth = 75, tnum = true), maxLines = 1, softWrap = false)
                    }
                    tiers.forEach { t ->
                        val key = Triple(s.w, s.h, t)
                        val cell = m[key]
                        val lit = rowOn && (tierF < 0 || tierF == t.ordinal)
                        MatrixTile(cell, Generator.isOffered(s, t), lit, recent = cell != null && cell.best.startedAt > weekAgo, chosen = open == key, Modifier.weight(1f)) {
                            buzz.select(); open = if (open == key) null else key
                        }
                    }
                }
            }
        }
        Text(
            "— means that pairing doesn't exist (5×5 and 10×10 have no Master). Tap a tile for its last ten and the best game.",
            Modifier.padding(start = 2.dp, top = 10.dp), style = ts(11.5f, 500, cs.onSurfaceVariant, lineHeight = 15f),
        )
        val sel = open?.let { m[it] }
        AnimatedVisibility(sel != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (sel != null) BestCard(sel) { buzz.press(); model.replay(sel.best) }
        }
    }
}

@Composable
private fun MatrixTile(cell: BestCell?, offered: Boolean, lit: Boolean, recent: Boolean, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val h = 46.dp
    when {
        !offered -> Box(modifier.height(h).semantics { contentDescription = "Not offered" }, contentAlignment = Alignment.Center) { Text("—", style = ts(14f, 500, cs.outlineVariant)) }
        cell == null -> Box(
            modifier.height(h).semantics { contentDescription = "Not solved yet" }.drawDashed(cs.outlineVariant),
            contentAlignment = Alignment.Center,
        ) { Text("not yet", style = ts(10.5f, 540, cs.onSurfaceVariant.copy(alpha = if (lit) 1f else 0.5f))) }
        else -> {
            // Stronger tertiary = more solves: one solve is a faint wash, a dozen or more is full tertiary.
            val f = ((cell.count - 1) / 11f).coerceIn(0f, 1f)
            val bg = lerp(cs.surfaceContainerHighest, cs.tertiary, 0.22f + 0.78f * f).let { if (lit) it else lerp(it, cs.surfaceContainer, 0.6f) }
            // Whichever ink contrasts more: the two contrast ratios cross at a luminance of about 0.18.
            val fg = if (bg.luminance() > 0.18f) Color(0xFF1B1B1F) else Color(0xFFF4F0F4)
            val (mm, _) = clockMs(cell.best.elapsed)
            Surface(
                onClick = onClick, color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp),
                border = when { chosen -> BorderStroke(2.dp, cs.onSurface); recent -> BorderStroke(2.dp, cs.tertiary); else -> null },
                modifier = modifier.height(h).semantics { contentDescription = "Best ${clock(cell.best.elapsed)}, ${cell.count} solves" },
            ) {
                Column(Modifier.padding(start = 6.dp, top = 5.dp)) {
                    Text(mm, style = ts(14.5f, 650, wdth = 85, tnum = true, lineHeight = 17f), maxLines = 1)
                    Text("×${cell.count}", style = ts(10.5f, 560, fg.copy(alpha = 0.75f), tnum = true, lineHeight = 12f))
                }
            }
        }
    }
}

private fun Modifier.drawDashed(c: Color) = this.padding(1.dp).drawBehind {
    drawRoundRect(c, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

/** A tapped tile: the best, the last ten as bars (the best lit), and the PB game's pattern, to play again. */
@Composable
private fun BestCard(cell: BestCell, onReplay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val b = cell.best
    Surface(color = cs.surfaceContainerHighest, shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeGlyph(Shp.difficulty[cell.tier.ordinal], 18.dp, cs.secondary); Spacer(Modifier.width(6.dp))
                    Text("${TIER_NAMES[cell.tier.ordinal]} · ${cell.w}×${cell.h}", style = ts(14f, 650, cs.onSurface, tnum = true))
                }
                val (mm, frac) = clockMs(b.elapsed)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(mm, style = ts(32f, 420, cs.onSurface, wdth = 86, tnum = true, lineHeight = 34f))
                    Text(frac, Modifier.padding(bottom = 3.dp), style = ts(15f, 500, cs.onSurfaceVariant, wdth = 86, tnum = true))
                }
                Text("best on ${date(b.startedAt)} · ${cell.count} ${if (cell.count == 1) "solve" else "solves"}", style = ts(12f, 520, cs.onSurfaceVariant))
                Spark(cell.lastTen, b.elapsed)
                Text("last ${cell.lastTen.size}, oldest first", style = ts(11f, 520, cs.onSurfaceVariant))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniGrid(MiniGeo.fit(b.w, b.h, 92f, 12f), solutionBoard({ b.deal.puzzle.filled(it) }, b.cells), cs.onSurface, cs.surfaceContainer)
                Surface(onClick = onReplay, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym["replay"], null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Play again", style = ts(12.5f, 620))
                    }
                }
            }
        }
    }
}

@Composable
private fun Spark(xs: List<Long>, best: Long) {
    val cs = MaterialTheme.colorScheme
    val mx = (xs.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val pitch = size.width / 10f
        xs.forEachIndexed { i, x ->
            val h = size.height * (0.2f + 0.8f * x / mx)
            drawRoundRect(if (x == best) cs.tertiary else cs.onSurfaceVariant.copy(alpha = 0.45f), Offset(i * pitch, size.height - h), Size(pitch * 0.6f, h), CornerRadius(2.dp.toPx()))
        }
    }
}

// ── History ──────────────────────────────────────────────────────────────────────────────────────

/**
 * One dot per solve, its difficulty's shape. One size: time on a log axis. All sizes: seconds per cell
 * on a log axis, dot size by grid (raw time would compare 25 cells with 400). The trailing mean of 10
 * (geometric) and the stepped best.
 */
@Composable
private fun History(won: List<Game>, perCell: Boolean) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axis = ts(10.5f, 560, cs.onSurfaceVariant, tnum = true)
    val pts = historyPoints(won, perCell)
    if (pts.isEmpty()) return
    val ys = pts.map { it.y }
    val mean = trailingGeoMean(ys)
    val best = runningBest(ys)
    val path = remember { Path() }
    Card {
        CardTitle("History", if (perCell) "all sizes · seconds per cell · dot size = grid" else "time · log scale")
        Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = "History of ${pts.size} solves" }) {
            val left = 30.dp.toPx(); val bottom = size.height - 16.dp.toPx(); val top = 8.dp.toPx()
            val lo = ys.min() * 0.85; val hi = maxOf(ys.max() * 1.15, lo * 2)
            fun y(v: Double) = (bottom - (bottom - top) * ((ln(v) - ln(lo)) / (ln(hi) - ln(lo)))).toFloat()
            fun x(i: Int) = left + (size.width - left - 8.dp.toPx()) * (if (pts.size == 1) 0.5f else i.toFloat() / (pts.size - 1))
            val ticks = if (perCell) listOf(0.1 to "0.1s", 0.25 to "¼s", 0.5 to "½s", 1.0 to "1s", 2.0 to "2s", 4.0 to "4s", 8.0 to "8s", 16.0 to "16s")
            else listOf(10, 20, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 3600).map { s -> s * 1000.0 to (if (s < 60) "${s}s" else "${s / 60}m") }
            ticks.filter { it.first in lo..hi }.forEach { (v, l) ->
                drawLine(cs.outlineVariant.copy(alpha = 0.5f), Offset(left, y(v)), Offset(size.width, y(v)), 1f)
                drawText(measurer, l, Offset(0f, y(v) - 7.dp.toPx()), axis)
            }
            pts.forEachIndexed { i, p ->
                val t = p.game.deal.tier.ordinal
                val s = (if (perCell) 5f + sqrt(p.game.cells.toFloat()) * 0.45f else 9f).dp.toPx()
                drawShape(Morphs.of(Shp.difficulty[t], Shp.difficulty[t]), 0f, cs.onSurfaceVariant.copy(alpha = 0.55f), Size(s, s), Offset(x(i) - s / 2, y(p.y) - s / 2), path)
            }
            if (pts.size >= 2) {
                val m = Path(); mean.forEachIndexed { i, v -> if (i == 0) m.moveTo(x(i), y(v)) else m.lineTo(x(i), y(v)) }
                drawPath(m, cs.primary, style = Stroke(2.dp.toPx()))
                val b = Path(); b.moveTo(x(0), y(best[0]))
                for (i in 1 until best.size) { b.lineTo(x(i), y(best[i - 1])); b.lineTo(x(i), y(best[i])) }
                drawPath(b, cs.tertiary, style = Stroke(1.5.dp.toPx()))
            }
            drawText(measurer, if (pts.size == 1) "1 solve" else "${pts.size} solves ago", Offset(left, bottom + 2.dp.toPx()), axis)
            val today = measurer.measure("today", axis)
            drawText(today, topLeft = Offset(size.width - today.size.width, bottom + 2.dp.toPx()))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendLine(cs.primary, "mean of 10"); Spacer(Modifier.width(12.dp)); LegendLine(cs.tertiary, "best")
            if (perCell) { Spacer(Modifier.width(12.dp)); Text("filter one size for time", style = ts(11.5f, 520, cs.onSurfaceVariant)) }
        }
    }
}

@Composable
private fun LegendLine(c: Color, label: String) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.size(14.dp, 8.dp)) { drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx()) }
    Spacer(Modifier.width(4.dp))
    Text(label, style = ts(12f, 540, cs.onSurfaceVariant))
}

// ── Look-ahead ───────────────────────────────────────────────────────────────────────────────────

/** Nonogram's counterpart of Sudoku's per-technique breakdown: look-ahead found yourself versus by hint, and how fast. */
@Composable
private fun LookAheadCard(la: app.materialnonogram.game.LookAheadSummary) {
    val cs = MaterialTheme.colorScheme
    Card {
        CardTitle("Look-ahead", "Expert and Master · ${la.games} ${if (la.games == 1) "game" else "games"}" + if (la.pending > 0) " · rating ${la.pending}…" else "")
        if (la.found + la.byHint == 0) {
            Text(if (la.pending > 0) "Working through your games…" else "No look-ahead steps found yet. They are the cells no single line can decide.", style = ts(13f, 500, cs.onSurfaceVariant, lineHeight = 18f))
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(la.share * 100).toInt()}%", style = ts(34f, 440, cs.onSurface, wdth = 88, tnum = true))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val w = size.width * la.share
                    drawRoundRect(cs.secondaryContainer, cornerRadius = CornerRadius(6.dp.toPx()))
                    if (w > 0f) drawRoundRect(cs.tertiary, size = Size(w, size.height), cornerRadius = CornerRadius(6.dp.toPx()))
                }
                Row(Modifier.padding(top = 4.dp)) {
                    Text("found yourself · ${la.found}", Modifier.weight(1f), style = ts(11.5f, 560, cs.onSurfaceVariant, tnum = true))
                    Text("by hint · ${la.byHint}", style = ts(11.5f, 560, cs.onSurfaceVariant, tnum = true))
                }
            }
        }
        la.medianRecent?.let { med ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(med + 500) / 1000}", style = ts(34f, 440, cs.onSurface, wdth = 88, tnum = true))
                Text(" s", style = ts(16f, 560, cs.onSurface))
                Spacer(Modifier.width(12.dp))
                val before = la.medianBefore
                val trend = if (before != null && before > 0) {
                    val pct = ((med - before) * 100 / before).toInt()
                    if (pct <= 0) " · ↘ ${-pct}% vs the ${la.window} before" else " · ↗ $pct% vs the ${la.window} before"
                } else ""
                Text("median time to find one, last ${la.window}$trend", style = ts(12f, 520, cs.onSurfaceVariant, lineHeight = 16f))
            }
        }
    }
}

// ── When you play, totals ────────────────────────────────────────────────────────────────────────

/** By hour: a radial of 24 wedges (length = games). By weekday: bars. Unchanged from Sudoku. */
@Composable
private fun WhenYouPlay(won: List<Game>) {
    if (won.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val hours = IntArray(24); val dows = IntArray(7)
    won.forEach { val z = Instant.ofEpochMilli(it.startedAt).atZone(zone); hours[z.hour]++; dows[z.dayOfWeek.value - 1]++ }
    val bestHour = hours.indices.maxBy { hours[it] }
    Card {
        CardTitle("When you play", "${won.size} solves")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = ts(19f, 640, cs.onSurface)
            val small = ts(10f, 560, cs.onSurfaceVariant)
            Canvas(Modifier.size(124.dp)) {
                val c = center; val r0 = size.minDimension * 0.24f; val r1 = size.minDimension * 0.48f
                val mx = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
                for (h in 0 until 24) {
                    val len = r0 + (r1 - r0) * hours[h] / mx
                    drawArc(if (h == bestHour) cs.primary else cs.primary.copy(alpha = 0.45f), h * 15f - 90f - 6f, 12f, true, topLeft = c - Offset(len, len), size = Size(len * 2, len * 2))
                }
                drawCircle(cs.surfaceContainer, r0 * 0.95f)
                val t = measurer.measure("${bestHour}h", label)
                drawText(t, topLeft = c - Offset(t.size.width / 2f, t.size.height / 2f + 5.dp.toPx()))
                val s = measurer.measure("best hour", small)
                drawText(s, topLeft = c + Offset(-s.size.width / 2f, 6.dp.toPx()))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val mx = (dows.maxOrNull() ?: 1).coerceAtLeast(1)
                DayOfWeek.entries.forEachIndexed { k, dw ->
                    Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dw.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH), Modifier.width(32.dp), style = ts(11.5f, 580, cs.onSurfaceVariant))
                        Box(Modifier.weight(1f)) {
                            Canvas(Modifier.fillMaxWidth().height(10.dp)) { drawRoundRect(cs.primary, Offset.Zero, Size(size.width * dows[k] / mx, size.height), CornerRadius(4.dp.toPx())) }
                        }
                        Text("${dows[k]}", Modifier.width(28.dp).padding(start = 6.dp), style = ts(11.5f, 580, cs.onSurface, tnum = true))
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(games: List<Game>, model: AppModel) {
    val cs = MaterialTheme.colorScheme
    val t = totals(games, model.summaries)
    val pending = games.count { model.summaries[it.id] == null }
    val tiles = listOf(
        "${t.games}" to "games · ${t.wonShare}% won",
        hm(t.timeMs) to "solving",
        "%,d".format(t.decided) to "cells decided",
        "%,d".format(t.strokes) to "strokes",
        "${t.longestStroke}" to "longest stroke",
        "%.2f".format(t.strikesPerGame) to "strikes / game",
    )
    Card {
        CardTitle("Totals", (games.minOfOrNull { it.startedAt }?.let { "since " + date(it) } ?: "") + if (pending > 0) " · counting…" else "")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (v, l) ->
                        Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(start = 11.dp, end = 8.dp, top = 9.dp, bottom = 9.dp)) {
                                Text(v, style = ts(20f, 520, cs.onSurface, wdth = 90, tnum = true), maxLines = 1)
                                Text(l, style = ts(11.5f, 560, cs.onSurfaceVariant), maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}
