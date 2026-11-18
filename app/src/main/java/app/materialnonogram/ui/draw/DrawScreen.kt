package app.materialnonogram.ui.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.materialnonogram.Screen
import app.materialnonogram.AppModel
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.SgtId
import app.materialnonogram.engine.ShareCode
import app.materialnonogram.game.Mark
import app.materialnonogram.game.OpenCode
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.ts
import app.materialnonogram.ui.play.Blocks
import app.materialnonogram.ui.play.ClueFont
import app.materialnonogram.ui.play.PlayLayout
import app.materialnonogram.ui.play.TIER_NAMES
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MIN_SIDE = 2
private const val MAX_SIDE = 30

/**
 * Home › Draw: make a nonogram by hand. Pick a size, draw fills (and crosses, as a drawing aid), and the
 * clues are worked out from the picture as you go; then copy it as a share code or a Pattern game ID.
 * Nothing is saved — leaving the page throws the drawing away (user, 2026-11-17). The status line says
 * whether the drawing makes a fair puzzle: one solution, reachable by logic alone.
 */
@Composable
fun DrawScreen(model: AppModel) {
    val cs = MaterialTheme.colorScheme
    var w by rememberSaveable { mutableIntStateOf(10) }
    var h by rememberSaveable { mutableIntStateOf(10) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SquishIcon("arrow_back", "Back", { if (drawing) drawing = false else model.screen = Screen.HOME })
            Spacer(Modifier.width(8.dp))
            Text(if (drawing) "Draw · $w×$h" else "Draw a puzzle", style = ts(22f, 600, cs.onSurface, tnum = true))
        }
        if (!drawing) SizePicker(w, h, { w = it }, { h = it }) { drawing = true }
        else Drawing(w, h)
    }
}

// ── Size ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SizePicker(w: Int, h: Int, onW: (Int) -> Unit, onH: (Int) -> Unit, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Pick a size, then draw. The numbers are worked out from your drawing, and when you're done you can copy it as a code to share.",
            Modifier.padding(horizontal = 6.dp), style = ts(14f, 460, cs.onSurfaceVariant, lineHeight = 20f),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((pw, ph) in listOf(5 to 5, 10 to 10, 15 to 15, 10 to 15, 15 to 20, 20 to 20, 25 to 25, 30 to 30)) {
                val on = pw == w && ph == h
                Surface(
                    onClick = { buzz.toggle(); onW(pw); onH(ph) },
                    color = if (on) cs.secondary else cs.surfaceContainerHigh, contentColor = if (on) cs.onSecondary else cs.onSurface,
                    shape = RoundedCornerShape(if (on) 24.dp else 14.dp), modifier = Modifier.height(48.dp),
                ) { Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text("$pw×$ph", style = ts(15f, 620, tnum = true)) } }
            }
        }
        Stepper("Width", w, onW)
        Stepper("Height", h, onH)
        if (maxOf(w, h) > OpenCode.MAX_SIDE) Text(
            "Bigger than ${OpenCode.MAX_SIDE}×${OpenCode.MAX_SIDE} can't be opened in this app, but its Pattern game ID works in Simon Tatham's Pattern.",
            Modifier.padding(horizontal = 6.dp), style = ts(13f, 480, cs.onSurfaceVariant, lineHeight = 18f),
        )
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = { buzz.press(); onStart() }, color = cs.primary, contentColor = cs.onPrimary, shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(bottom = 0.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym["edit"], null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text("Start drawing $w×$h", style = ts(18f, 650, tnum = true))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = ts(17f, 600, cs.onSurface))
            SquishIcon("remove", "Fewer", { if (value > MIN_SIDE) { buzz.select(); onChange(value - 1) } }, container = cs.surfaceContainerHighest, content = cs.onSurface)
            Text("$value", Modifier.width(64.dp), style = ts(26f, 560, cs.onSurface, tnum = true), textAlign = TextAlign.Center)
            SquishIcon("add", "More", { if (value < MAX_SIDE) { buzz.select(); onChange(value + 1) } }, container = cs.surfaceContainerHighest, content = cs.onSurface)
        }
    }
}

// ── Drawing ───────────────────────────────────────────────────────────────────────────────────────

/** What the drawing makes as a puzzle, worked out in the background after each change. */
private sealed interface Verdict {
    data object Checking : Verdict
    data class Fair(val tier: String) : Verdict
    data class Unfair(val why: String) : Verdict
}

@Composable
private fun Drawing(w: Int, h: Int) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val clip = LocalClipboardManager.current
    val board = remember(w, h) { ByteArray(w * h) }
    var rev by remember(w, h) { mutableIntStateOf(0) }
    var fill by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf<String?>(null) }
    // Only fills make the puzzle; crosses are a drawing aid. (Puzzle.ofBoard wants a finished board, so build it directly.)
    fun puzzle() = Puzzle(w, h, BooleanArray(w * h) { board[it] == Mark.FILLED })
    val clues = remember(w, h, rev) { puzzle().clues }
    var verdict by remember { mutableStateOf<Verdict>(Verdict.Checking) }
    LaunchedEffect(w, h, rev) {
        verdict = Verdict.Checking
        delay(250) // a drag changes many cells; grade once it pauses
        val code = ShareCode.encode(puzzle())
        verdict = withContext(Dispatchers.Default) {
            when (val o = OpenCode.open(code)) {
                is OpenCode.Outcome.Ok -> Verdict.Fair(TIER_NAMES[o.deal.tier.ordinal])
                is OpenCode.Outcome.Refused -> Verdict.Unfair(o.title)
                else -> Verdict.Unfair("Can't be checked")
            }
        }
    }
    LaunchedEffect(copied) { if (copied != null) { delay(1800); copied = null } }

    Column(Modifier.fillMaxSize()) {
        DrawBoard(w, h, board, clues, rev, fill, Modifier.weight(1f).fillMaxWidth()) { rev++ }
        // Status: is it a fair puzzle?
        Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            val (icon, text, tone) = when {
                copied != null -> Triple("check", copied!!, cs.primary)
                verdict is Verdict.Fair -> Triple("check", "A fair puzzle · ${(verdict as Verdict.Fair).tier}", cs.primary)
                verdict is Verdict.Unfair -> Triple("info", (verdict as Verdict.Unfair).why, cs.error)
                else -> Triple("schedule", "Checking…", cs.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym[icon], null, Modifier.size(18.dp), tint = tone); Spacer(Modifier.width(6.dp))
                Text(text, style = ts(14f, 600, tone), maxLines = 1)
            }
        }
        // Tools: Fill | Cross, then the bulk actions, then copy.
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (f in listOf(true, false)) {
                val on = fill == f
                Surface(
                    color = if (on) cs.tertiary else cs.secondaryContainer, contentColor = if (on) cs.onTertiary else cs.onSecondaryContainer,
                    shape = if (f) RoundedCornerShape(32.dp, if (on) 32.dp else 10.dp, if (on) 32.dp else 10.dp, 32.dp) else RoundedCornerShape(if (on) 32.dp else 10.dp, 32.dp, 32.dp, if (on) 32.dp else 10.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.RadioButton) { buzz.mode(); fill = f },
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (f) Canvas(Modifier.size(20.dp)) { drawRoundRect(if (on) cs.onTertiary else cs.onSecondaryContainer, cornerRadius = CornerRadius(5.dp.toPx())) }
                        else Icon(Sym["close"], null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (f) "Fill" else "Cross", style = ts(17f, 650))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("grid_on", "Cross the rest", Modifier.weight(1.25f)) {
                var n = 0
                for (i in board.indices) if (board[i] == Mark.UNKNOWN) { board[i] = Mark.CROSSED; n++ }
                if (n > 0) { buzz.unitDone(1, 6, 40); rev++ }
            }
            Action("restart_alt", "Clear", Modifier.weight(0.85f)) { board.fill(Mark.UNKNOWN); buzz.undo(); rev++ }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("content_copy", "Copy code", Modifier.weight(1f), primary = true) {
                clip.setText(AnnotatedString(ShareCode.encode(puzzle()))); buzz.settle(); copied = "Code copied"
            }
            Action("content_copy", "Copy as text", Modifier.weight(1f)) {
                clip.setText(AnnotatedString(SgtId.encode(puzzle()))); buzz.settle(); copied = "Pattern game ID copied"
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Action(icon: String, label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, color = if (primary) cs.primary else cs.surfaceContainerHigh, contentColor = if (primary) cs.onPrimary else cs.onSurface,
        shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxHeight(),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym[icon], null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(label, style = ts(15f, 620), maxLines = 1)
        }
    }
}

/**
 * The drawing board: Play's tiles and clue bands, with live clues. A drag paints every cell it passes
 * (freehand, never skipping one): with the tool's mark, or — if it began on a cell already holding that
 * mark — clearing. The layout only ever grows its clue bands, so the grid doesn't jump while you draw.
 */
@Composable
private fun DrawBoard(w: Int, h: Int, board: ByteArray, clues: app.materialnonogram.engine.Clues, rev: Int, fill: Boolean, modifier: Modifier, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val font = remember(density, resolver) { ClueFont(resolver, density) }
    val dark = cs.surface.luminance() < 0.5f
    val well = if (dark) lerp(cs.surfaceContainerHighest, cs.onSurface, 0.07f) else cs.surfaceContainer
    val fillC = cs.tertiary
    val crossC = cs.onSurfaceVariant
    val ink = cs.onSurface
    BoxWithConstraints(modifier.padding(horizontal = Blocks.MARGIN.dp)) {
        val d = density.density
        val availW = constraints.maxWidth / d; val availH = constraints.maxHeight / d
        // Layout from the clues, but the bands never shrink back: keep the roomiest so far.
        // It starts with room for a typical clue (a quarter of the line in numbers, two-digit on big boards),
        // so the first strokes don't shove the grid aside; only a busier drawing grows it from there.
        val kept = remember(w, h, availW, availH) {
            val big = if (maxOf(w, h) >= 10) 10 else 1
            val roomy = app.materialnonogram.engine.Clues(w, h, Array(h) { IntArray(maxOf(2, (w + 3) / 4)) { big } }, Array(w) { IntArray(maxOf(2, (h + 3) / 4)) { big } })
            mutableStateOf<PlayLayout?>(PlayLayout.solve(roomy, availW, availH, font))
        }
        val now = PlayLayout.solve(clues, availW, availH, font)
        val k0 = kept.value
        val L = if (k0 == null || now.rowBand > k0.rowBand + 0.5f || now.colBand > k0.colBand + 0.5f) now.also { kept.value = it } else k0
        val c = L.c
        val gridW = Blocks.span(w, c); val gridH = Blocks.span(h, c)
        val left = (L.rowBand + (availW - L.rowBand - gridW) / 2f).coerceAtLeast(L.rowBand)
        val top = L.colBand + ((availH - L.colBand - gridH) / 2f).coerceAtLeast(0f)
        // The cell under a point (px), nearest centre within half a gutter of it; −1 off the grid.
        fun cellAt(p: Offset): Int {
            fun idx(v: Float, n: Int): Int {
                var best = -1; var bd = Float.MAX_VALUE
                for (k in 0 until n) { val dd = kotlin.math.abs(v - (Blocks.pos(k, c) + c / 2)); if (dd < bd) { bd = dd; best = k } }
                return if (bd <= (c + Blocks.major(c)) / 2) best else -1
            }
            val col = idx(p.x / d - left, w); val row = idx(p.y / d - top, h)
            return if (col < 0 || row < 0) -1 else row * w + col
        }
        // The gesture outlives a recomposition (the layout can grow mid-drag), so it reads these through state.
        val cellAtNow = rememberUpdatedState(::cellAt)
        val changed = rememberUpdatedState(onChange)
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "Drawing board, $w by $h. Drag to draw." }
                .pointerInput(w, h, fill) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = cellAtNow.value(down.position); if (start < 0) return@awaitEachGesture
                        val mark = if (fill) Mark.FILLED else Mark.CROSSED
                        val to = if (board[start] == mark) Mark.UNKNOWN else mark
                        fun set(i: Int) {
                            if (i < 0 || board[i] == to) return
                            board[i] = to; changed.value()
                            when (to) { Mark.FILLED -> buzz.fill(); Mark.CROSSED -> buzz.cross(); else -> buzz.erase() }
                        }
                        set(start)
                        var last = start
                        // Walk every cell between the last one and the finger (a straight line in cells), so a fast flick skips none.
                        fun walk(p: Offset) {
                            val i = cellAtNow.value(p); if (i < 0 || i == last) return
                            var r0 = last / w; var c0 = last % w; val r1 = i / w; val c1 = i % w
                            val dr = kotlin.math.abs(r1 - r0); val dc = kotlin.math.abs(c1 - c0)
                            val sr = if (r1 > r0) 1 else -1; val sc = if (c1 > c0) 1 else -1
                            var err = dc - dr
                            while (r0 != r1 || c0 != c1) {
                                val e2 = 2 * err
                                if (e2 > -dr) { err -= dr; c0 += sc }
                                if (e2 < dc) { err += dc; r0 += sr }
                                set(r0 * w + c0)
                            }
                            last = i
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            for (hp in ch.historical) walk(hp.position)
                            walk(ch.position)
                            ch.consume()
                            if (!ch.pressed) break
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") rev
            val one = d
            val cpx = c * one
            val r = 0.3f * cpx
            // Row clues, right-aligned to the grid; column clues, bottom-aligned above it.
            val pr = font.paint(L.fsR, Blocks.ROW_WDTH, Blocks.GRAD_ON).apply { color = ink.toArgb() }
            for (y in 0 until h) {
                var x = (left - Blocks.PAD_R) * one
                val cy = (top + Blocks.pos(y, c) + c / 2) * one
                val base = cy + Blocks.FIG_H * L.fsR * one / 2
                for (n in clues.rows[y].reversed()) {
                    val t = font.text(n); x -= pr.measureText(t)
                    drawContext.canvas.nativeCanvas.drawText(t, x, base, pr); x -= L.rowGap * one
                }
            }
            val figH = Blocks.FIG_H * L.fsC * one
            val pitch = L.colPitch * one
            for (x in 0 until w) {
                val cx = (left + Blocks.pos(x, c) + c / 2) * one
                val col = clues.cols[x]
                for (k in col.indices) {
                    val n = col[col.size - 1 - k]
                    val p = font.paint(L.fsC, if (n >= 10) L.wdth2 else L.wdth1, Blocks.GRAD_ON).apply { color = ink.toArgb() }
                    val t = font.text(n)
                    val baseline = (top - Blocks.COL_PAD_B) * one - k * pitch - (pitch - figH) / 2
                    drawContext.canvas.nativeCanvas.drawText(t, cx - p.measureText(t) / 2f, baseline, p)
                }
            }
            // Tiles.
            for (y in 0 until h) for (x in 0 until w) {
                val v = board[y * w + x]
                val o = Offset((left + Blocks.pos(x, c)) * one, (top + Blocks.pos(y, c)) * one)
                drawRoundRect(if (v == Mark.FILLED) fillC else well, o, Size(cpx, cpx), CornerRadius(r))
                if (v == Mark.CROSSED) {
                    val m = o + Offset(cpx / 2, cpx / 2); val a = 0.19f * cpx; val sw = 0.105f * cpx
                    drawLine(crossC, m + Offset(-a, -a), m + Offset(a, a), sw, StrokeCap.Round)
                    drawLine(crossC, m + Offset(a, -a), m + Offset(-a, a), sw, StrokeCap.Round)
                }
            }
        }
    }
}
