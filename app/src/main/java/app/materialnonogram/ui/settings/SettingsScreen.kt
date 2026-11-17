package app.materialnonogram.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.material3.MaterialShapes
import app.materialnonogram.AppModel
import app.materialnonogram.BuildConfig
import app.materialnonogram.data.Haptics
import app.materialnonogram.data.Motion
import app.materialnonogram.data.Settings
import app.materialnonogram.ui.home.DIFFICULTY_TECH
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Morphs
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.drawShape
import app.materialnonogram.ui.kit.ts
import app.materialnonogram.ui.play.TIER_NAMES
import app.materialnonogram.engine.Size as PSize
import app.materialnonogram.engine.Tier
import app.materialnonogram.ui.theme.Seed
import app.materialnonogram.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Settings, variant A of board 11 (chosen 2026-10-27): one grouped page in game order. Every row
 * says what it does in plain words, with an example where one helps (the user found the first
 * wording unreadable). Pick-one choices are Home's tiles; on/off is a switch.
 */
@Composable
fun SettingsScreen(model: AppModel, s: Settings) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val scroll = rememberScrollState()
    val set: ((Settings) -> Settings) -> Unit = { f -> model.updateSettings(f) }
    val inGame = model.current
    val collapsed = scroll.value > 120

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Surface(color = if (collapsed) cs.surfaceContainer else cs.surface) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SquishIcon("arrow_back", "Back", { model.screen = model.settingsFrom })
                Spacer(Modifier.width(8.dp))
                Text("Settings", Modifier.graphicsLayer { alpha = if (collapsed) 1f else 0f }, style = ts(22f, 600, cs.onSurface))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 48.dp)) {
            Text("Settings", Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp), style = ts(45f, 500, cs.onSurface))

            Section("Difficulties on Home")
            Pad { Checks(TIER_NAMES, { s.tierOn(Tier.entries[it]) }, shape = { Shp.difficulty[it] }, tips = DIFFICULTY_TECH) { k -> set { it.copy(tiers = it.tiers xor (1 shl k)) } } }
            Info("info", "Tap one to show or hide it on Home. Hold one to hear what it asks of you.")

            Section("Sizes on Home")
            Pad { Checks(PSize.entries.map { it.label }, { s.sizeOn(PSize.entries[it]) }, grid = { PSize.entries[it] }) { k -> set { it.copy(sizes = it.sizes xor (1 shl k)) } } }
            Info("info", "20×20 opens zoomed in: pinch to zoom, two fingers to move, one finger always draws.")

            Section("Mistakes")
            Pad {
                TileRow(
                    listOf(
                        Choice("Flag them", "A wrong mark is refused at once and costs a life", heartsIcon = true),
                        Choice("Don’t flag them", "Wrong marks stay until you spot them. No lives", icon = "shield"),
                    ),
                    if (s.flagMistakes) 0 else 1, height = 112.dp,
                ) { k -> set { it.copy(flagMistakes = k == 0) } }
            }
            Pad(top = 4.dp) {
                Box(Modifier.graphicsLayer { alpha = if (s.flagMistakes) 1f else 0.38f }) {
                    TileRow(listOf(Choice("3 lives"), Choice("1 life")), if (s.lives == 1) 1 else 0, height = 60.dp, enabled = s.flagMistakes) { k ->
                        set { it.copy(lives = if (k == 0) 3 else 1) }
                    }
                }
            }
            Info(
                "lock",
                (if (s.flagMistakes) "A wrong fill or a wrong cross both count. Lose your last life and the game is over. " else "With mistakes unflagged, a wrong mark can make the puzzle unsolvable. ") +
                    if (inGame != null) "The game you’re in the middle of keeps its old rules." else "",
            )

            Section("Help while you play")
            BandCard(s)
            Spacer(Modifier.height(8.dp))
            Group {
                SwitchRow("close", "Cross off finished lines", "When a row or column matches its clue, its other cells get crosses. Counts as some help", s.autoCross) { v -> set { it.copy(autoCross = v) } }
                Box(Modifier.graphicsLayer { alpha = if (s.flagMistakes) 0.38f else 1f }) {
                    SwitchRow("flag", "Flag a broken clue", "Mistakes unflagged only: a clue turns red when the finished runs in its line can't fit it", s.contradictionFlag && !s.flagMistakes) { v -> if (!s.flagMistakes) set { it.copy(contradictionFlag = v) } }
                }
                Box(Modifier.graphicsLayer { alpha = if (s.flagMistakes) 0.38f else 1f }) {
                    SwitchRow("fact_check", "Check button", "Mistakes unflagged only: asks whether any mark on the board is wrong. Using it counts like a hint", s.checkButton && !s.flagMistakes) { v -> if (!s.flagMistakes) set { it.copy(checkButton = v) } }
                }
                SwitchRow("lightbulb", "Hints", "A hint button in Play. Each tap tells you more: which line, which cell, why; then it places it", s.hints) { v -> set { it.copy(hints = v) } }
            }

            Section("Marking cells")
            Group {
                SwitchRow("touch_app", "Hold for the other tool", "Rest your finger on a cell and the stroke switches from Fill to Cross, or back", s.longPress) { v -> set { it.copy(longPress = v) } }
                SwitchRow("timer", "Show the timer", "You can also tap it during a game to hide it. Your time is recorded either way", s.showTimer) { v -> set { it.copy(showTimer = v) } }
                SwitchRow("flag", "Halfway message", "Once a game, when half the cells are marked: your pace if you're ahead, otherwise a word of encouragement", s.halfwayMessage) { v -> set { it.copy(halfwayMessage = v) } }
            }

            Section("Look")
            Label("Theme")
            Pad { Segmented(listOf("Like phone", "Light", "Dark"), s.theme.ordinal, icons = listOf("brightness_auto", "light_mode", "dark_mode")) { k -> set { it.copy(theme = ThemeMode.entries[k]) } } }
            Spacer(Modifier.height(8.dp))
            Group { SwitchRow("contrast", "Pure black", "In dark theme, black instead of dark grey", s.amoled) { v -> set { it.copy(amoled = v) } } }
            Label("Colour")
            Swatches(s.seed) { seed -> buzz.toggle(); set { it.copy(seed = seed) } }
            Spacer(Modifier.height(8.dp))
            Group {
                SwitchRow("animation", "Reduce motion", "Simple fades instead of bounces and morphs. Matches your phone until you change it", s.motion == Motion.REDUCED) { v ->
                    set { it.copy(motion = if (v) Motion.REDUCED else Motion.FULL) }
                }
            }

            Label("App name on your phone")
            Pad { Segmented(listOf("Nonogram", "イラロジ"), if (s.kanjiName) 1 else 0) { k -> set { it.copy(kanjiName = k == 1) } } }
            Info("info", "The name under the icon. Switching it can take the icon off your home screen; it stays in All apps.")

            Section("Vibration", "Tap one to try it")
            Pad {
                TileRow(
                    listOf(Choice("Off", "No vibration", icon = "vibration"), Choice("Some", "Only mistakes and wins", icon = "vibration"), Choice("All", "Every tap", icon = "vibration")),
                    s.haptics.ordinal, height = 112.dp,
                ) { k -> buzz.sample(Haptics.entries[k]); set { it.copy(haptics = Haptics.entries[k]) } }
            }

            Section("Your data")
            ExportRows(model)

            Section("About")
            Group {
                GroupRow { RowBody("info", "Material Nonogram ${BuildConfig.VERSION_NAME}", "Puzzle maker v${app.materialnonogram.engine.Generator.VERSION} · grader v${app.materialnonogram.engine.Grader.VERSION} · ${app.materialnonogram.engine.Effort.VERSION}") {} }
                GroupRow { RowBody("license", "Open-source licences", "Google Sans Flex and Noto Sans JP (OFL 1.1), Material Symbols (Apache 2.0)", chevron = false) {} }
            }
        }
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Section(t: String, aside: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, Modifier.weight(1f), style = ts(14f, 650, cs.primary))
        if (aside != null) Text(aside, style = ts(12.5f, 500, cs.onSurfaceVariant))
    }
}

@Composable
private fun Label(t: String) = Text(t, Modifier.padding(start = 22.dp, top = 14.dp, bottom = 6.dp), style = ts(12f, 560, MaterialTheme.colorScheme.onSurfaceVariant))

@Composable
private fun Pad(top: Dp = 0.dp, content: @Composable () -> Unit) = Box(Modifier.padding(start = 16.dp, end = 16.dp, top = top)) { content() }

@Composable
private fun Info(icon: String, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(Sym[icon], null, Modifier.size(16.dp).padding(top = 1.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = ts(12.5f, 450, cs.onSurfaceVariant, lineHeight = 17f))
    }
}

/** M3E grouped list: 24 dp outer corners, 4 dp inner, 2 dp gaps. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
private fun GroupRow(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun Lead(icon: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        ShapeGlyph(MaterialShapes.Cookie4Sided, 40.dp, cs.secondaryContainer)
        Icon(Sym[icon], null, Modifier.size(22.dp), tint = cs.onSecondaryContainer)
    }
}

@Composable
private fun RowBody(icon: String, title: String, sub: String?, chevron: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Lead(icon); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        if (chevron) Icon(Sym["chevron_right"], null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: String, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val buzz = LocalBuzz.current
    GroupRow {
        Box(Modifier.semantics { role = Role.Switch; stateDescription = if (on) "On" else "Off" }) {
            RowBody(icon, title, sub, trailing = {
                Switch(
                    checked = on, onCheckedChange = { buzz.toggle(); onChange(it) },
                    thumbContent = if (on) ({ Icon(Sym["check"], null, Modifier.size(SwitchDefaults.IconSize)) }) else null,
                )
            }) { buzz.toggle(); onChange(!on) }
        }
    }
}

private data class Choice(val name: String, val sub: String? = null, val icon: String? = null, val heartsIcon: Boolean = false)

/** Home's tiles: connected corners at rest, springing to full corners and secondary when chosen. */
@Composable
private fun TileRow(items: List<Choice>, sel: Int, height: Dp, enabled: Boolean = true, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val springs = LocalSprings.current
    val buzz = LocalBuzz.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { k, c ->
            val on = k == sel
            val t by animateFloatAsState(if (on) 1f else 0f, springs.fast(), label = "t")
            val col by animateFloatAsState(if (on) 1f else 0f, springs.effects(), label = "c")
            val outer = 28f; val inner = 8f
            val first = k == 0; val last = k == items.lastIndex
            fun r(edge: Boolean) = ((if (edge) outer else inner) + ((height.value / 2).coerceAtMost(36f) - (if (edge) outer else inner)) * t).dp
            val fg = lerp(cs.onSurface, cs.onSecondary, col)
            Surface(
                color = lerp(cs.surfaceContainerHighest, cs.secondary, col), contentColor = fg,
                shape = RoundedCornerShape(r(first), r(last), r(last), r(first)),
                modifier = Modifier.weight(1f).height(height)
                    .semantics { role = Role.RadioButton; selected = on; contentDescription = c.name }
                    .clickable(enabled = enabled) { buzz.toggle(); onPick(k) },
            ) {
                Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (c.heartsIcon) app.materialnonogram.ui.kit.Hearts(3, 3, 22.dp, color = fg)
                    else if (c.icon != null) Icon(Sym[c.icon], null, Modifier.size(26.dp))
                    if (c.heartsIcon || c.icon != null) Spacer(Modifier.height(6.dp))
                    Text(c.name, style = ts(16f, if (on) 720 else 580), maxLines = 1)
                    if (c.sub != null) Text(c.sub, style = ts(12.5f, 440, if (on) fg.copy(alpha = 0.8f) else cs.onSurfaceVariant, lineHeight = 16f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** ButtonGroup of toggleableItem()s, connected [M3E-NEW]; Role.RadioButton by hand. */
@Composable
private fun Segmented(labels: List<String>, sel: Int, icons: List<String>? = null, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { k, l ->
            val on = k == sel
            val first = k == 0; val last = k == labels.lastIndex
            val o = if (on) 24.dp else 24.dp; val i = if (on) 24.dp else 6.dp
            Surface(
                color = if (on) cs.secondary else cs.surfaceContainerHighest, contentColor = if (on) cs.onSecondary else cs.onSurface,
                shape = RoundedCornerShape(if (first) o else i, if (last) o else i, if (last) o else i, if (first) o else i),
                modifier = Modifier.weight(1f).height(48.dp).semantics { role = Role.RadioButton; selected = on }.clickable { buzz.toggle(); onPick(k) },
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (icons != null) { Icon(Sym[icons[k]], null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(l, style = ts(14.5f, if (on) 720 else 560), maxLines = 1)
                }
            }
        }
    }
}

/** Multi-select tiles with a check badge, three to a row; at least one stays on (the last refuses and shakes). */
@Composable
private fun Checks(labels: List<String>, isOn: (Int) -> Boolean, shape: ((Int) -> RoundedPolygon)? = null, grid: ((Int) -> PSize)? = null, tips: List<String>? = null, onToggle: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val scope = rememberCoroutineScope()
    var tip by remember { mutableStateOf(-1) }
    val shakes = remember { List(labels.size) { Animatable(0f) } }
    val rows = (labels.size + 2) / 3
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in 0..2) {
                val k = r * 3 + c
                if (k >= labels.size) { Spacer(Modifier.weight(1f)); continue }
                val on = isOn(k)
                val count = labels.indices.count { isOn(it) }
                Surface(
                    color = if (on) cs.secondaryContainer else Color.Transparent, contentColor = if (on) cs.onSecondaryContainer else cs.onSurface,
                    shape = RoundedCornerShape(if (on) 34.dp else 12.dp),
                    border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
                    modifier = Modifier.weight(1f).height(84.dp)
                        .graphicsLayer { translationX = shakes[k].value }
                        .semantics { role = Role.Checkbox; stateDescription = if (on) "Shown on Home" else "Hidden" }
                        .pointerInput(on, count) {
                            detectTapGestures(
                                onTap = {
                                    if (on && count == 1) {
                                        buzz.lowTick(0.8f)
                                        scope.launch { for (x in listOf(-8f, 7f, -5f, 3f, 0f)) shakes[k].animateTo(x, tween(45, easing = LinearEasing)) }
                                    } else { buzz.toggle(); onToggle(k) }
                                },
                                onLongPress = if (tips != null) ({ _ -> buzz.select(); tip = if (tip == k) -1 else k }) else null,
                            )
                        },
                ) {
                    Box {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val ink = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant
                            if (shape != null) ShapeGlyph(shape(k), 30.dp, ink)
                            if (grid != null) {
                                val g = grid(k)
                                Canvas(Modifier.size((g.w * 1.5f).dp, (g.h * 1.5f).dp)) {
                                    drawRoundRect(ink, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(1.3.dp.toPx()))
                                    val cc = 1.5.dp.toPx()
                                    for (x in 5 until g.w step 5) drawLine(ink.copy(alpha = 0.6f), Offset(x * cc, 0f), Offset(x * cc, size.height), 0.8.dp.toPx())
                                    for (y in 5 until g.h step 5) drawLine(ink.copy(alpha = 0.6f), Offset(0f, y * cc), Offset(size.width, y * cc), 0.8.dp.toPx())
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(labels[k], style = ts(15f, if (on) 700 else 560, tnum = true))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).drawBehind {
                            if (on) drawCircle(cs.onSecondaryContainer) else drawCircle(cs.outline, style = Stroke(1.5.dp.toPx()))
                        }, contentAlignment = Alignment.Center) {
                            if (on) Icon(Sym["check"], null, Modifier.size(14.dp), tint = cs.secondaryContainer)
                        }
                    }
                }
            }
        }
        if (tips != null && tip >= 0) Text("${labels[tip]}: ${tips[tip]}", Modifier.padding(start = 6.dp, top = 6.dp), style = ts(12.5f, 500, cs.onSurfaceVariant, lineHeight = 17f))
    }
}

/**
 * Which records the current mix counts for (BUILD-PLAN §5.1), in words: the aid band (none, auto-cross,
 * contradiction flag) is chosen here, and a hint or a check sends a run to its own assisted table.
 */
@Composable
private fun BandCard(s: Settings) {
    val cs = MaterialTheme.colorScheme
    val b = when { s.contradictionFlag && !s.flagMistakes -> 2; s.autoCross -> 1; else -> 0 }
    val why = when (b) {
        2 -> "a red clue points at a contradiction you'd otherwise have to find"
        1 -> "crossing off a finished line is done for you"
        else -> "nothing here does any of the work for you"
    }
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym["emoji_events"], null, Modifier.size(18.dp), tint = cs.onSurface)
                Spacer(Modifier.width(8.dp))
                Text("Which records this counts for", style = ts(14f, 600, cs.onSurface))
            }
            Row(Modifier.semantics { contentDescription = "Records: ${app.materialnonogram.game.BAND_NAMES[b]}" }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val shapes = listOf(MaterialShapes.Square, MaterialShapes.Cookie4Sided, MaterialShapes.Cookie7Sided)
                for (k in 0..2) {
                    val on = k == b
                    Surface(
                        color = if (on) cs.secondary else cs.surfaceContainerHighest, contentColor = if (on) cs.onSecondary else cs.onSurfaceVariant,
                        shape = RoundedCornerShape(if (on || k == 0) 22.dp else 6.dp, if (on || k == 2) 22.dp else 6.dp, if (on || k == 2) 22.dp else 6.dp, if (on || k == 0) 22.dp else 6.dp),
                        modifier = Modifier.weight(if (k == 2) 1.3f else 1f).height(44.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            ShapeGlyph(shapes[k], 14.dp, if (on) cs.onSecondary else cs.onSurfaceVariant)
                            Spacer(Modifier.width(5.dp))
                            Text(listOf("No help", "Auto-cross", "Red clues")[k], style = ts(13f, if (on) 760 else 560, wdth = 90), maxLines = 1)
                        }
                    }
                }
            }
            Text(
                "${app.materialnonogram.game.BAND_NAMES[b]}, because $why. Best times are kept apart for each, so a game with more help never beats one with less. A game where you use a hint or the check button goes to its own assisted records.",
                Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), style = ts(12.5f, 450, cs.onSurfaceVariant, lineHeight = 17f),
            )
        }
    }
}

/** Five colour sources, each its own MaterialShape; Wallpaper = dynamic colour. */
@Composable
private fun Swatches(sel: Seed?, onPick: (Seed?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val items: List<Triple<Seed?, String, RoundedPolygon>> = listOf(
        Triple(null, "Wallpaper", MaterialShapes.Circle), Triple(Seed.AMBER, "Amber", MaterialShapes.Cookie9Sided),
        Triple(Seed.TEAL, "Teal", MaterialShapes.Clover4Leaf), Triple(Seed.VIOLET, "Violet", MaterialShapes.Flower), Triple(Seed.ROSE, "Rose", MaterialShapes.Puffy),
    )
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            items.forEach { (seed, name, shape) ->
                val on = seed == sel
                val scheme = seed?.let { if (dark) it.dark else it.light }
                Column(Modifier.clickable { onPick(seed) }.semantics { role = Role.RadioButton; selected = on; contentDescription = name }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        ShapeGlyph(shape, 56.dp, scheme?.primaryContainer ?: cs.primaryContainer)
                        if (seed == null) ShapeGlyph(shape, 28.dp, cs.tertiaryContainer)
                        if (on) Box(Modifier.size(28.dp).drawBehind { drawCircle(scheme?.onPrimaryContainer ?: cs.onPrimaryContainer) }, contentAlignment = Alignment.Center) {
                            Icon(Sym["check"], null, Modifier.size(18.dp), tint = scheme?.primaryContainer ?: cs.primaryContainer)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(name, style = ts(12f, if (on) 720 else 520, if (on) cs.onSurface else cs.onSurfaceVariant))
                }
            }
        }
    }
}


/** CSV of games and the raw event logs, written through the Storage Access Framework. */
@Composable
private fun ExportRows(model: AppModel) {
    val ctx = LocalContext.current
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(model.exportCsv().toByteArray()) }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(model.exportJson().toByteArray()) }
    }
    val n = model.history.size + (if (model.current != null) 1 else 0)
    Group {
        GroupRow { RowBody("download", "Export your games", "All $n games as a spreadsheet file (CSV)", chevron = true) { csv.launch("nonogram-games.csv") } }
        GroupRow { RowBody("history", "Export every move", "Each move of each game with its timestamp, for your own analysis", chevron = true) { json.launch("nonogram-moves.json") } }
    }
}
