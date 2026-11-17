package app.materialnonogram.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.materialnonogram.AppModel
import app.materialnonogram.Screen
import app.materialnonogram.game.OpenCode
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.ShapeGlyph
import app.materialnonogram.ui.kit.Shp
import app.materialnonogram.ui.kit.SquishIcon
import app.materialnonogram.ui.kit.Sym
import app.materialnonogram.ui.kit.ts
import app.materialnonogram.ui.play.TIER_NAMES
import kotlinx.coroutines.launch

/**
 * Home's "Open a code" (BUILD-PLAN §5.2, D10): paste a share code or a Simon Tatham Pattern game ID; it is
 * graded off the main thread and either refused in plain words or offered to play, showing its tier and
 * size but never its pattern. A page, not a dialog (Home's no-dialogs rule).
 */
@Composable
fun OpenCodeScreen(model: AppModel) {
    val cs = MaterialTheme.colorScheme
    val buzz = LocalBuzz.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // The field's text stays local (CLAUDE.md: never feed a text field back from a store).
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<OpenCode.Outcome?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun open(t: String = text) {
        if (busy || t.isBlank()) return
        busy = true; outcome = null
        scope.launch {
            val o = model.openCode(t)
            busy = false; outcome = o
            // The answer (and Play) sits under the keyboard: put it away.
            keyboard?.hide(); focusManager.clearFocus()
            if (o is OpenCode.Outcome.Ok) buzz.settle() else buzz.lowTick(0.8f)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SquishIcon("arrow_back", "Back", { model.screen = Screen.HOME })
            Spacer(Modifier.width(8.dp))
            Text("Open a code", style = ts(22f, 600, cs.onSurface))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Paste a puzzle someone shared with you, or a game ID from Simon Tatham's Pattern. It opens as a new game; your records keep it apart by size.",
                Modifier.padding(horizontal = 6.dp), style = ts(14f, 460, cs.onSurfaceVariant, lineHeight = 20f),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    // A hardware Enter arrives as one typed "\n", not as the IME action: treat it as Open.
                    // A pasted share message keeps its line breaks (it grew by more than one character).
                    if (v.length == text.length + 1 && v.endsWith("\n")) { text = v.trimEnd(); open(text) } else if (v != text) { text = v; outcome = null }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
                label = { Text("Share code or Pattern ID") },
                placeholder = { Text("1H2K5-8M3QX-… or 10x10:3/1.2/…", style = ts(16f, 450, cs.onSurfaceVariant, tnum = true)) },
                textStyle = ts(18f, 520, cs.onSurface, tnum = true, lineHeight = 26f),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { open() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = {
                        val pasted = clip.getText()?.text.orEmpty()
                        buzz.toggle()
                        if (pasted.isNotBlank()) { text = pasted.trim(); open(text) }
                    },
                    color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym["content_paste"], null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Paste and open", style = ts(16f, 620))
                    }
                }
                Surface(
                    onClick = { buzz.press(); open() }, enabled = text.isNotBlank() && !busy,
                    color = cs.primary, contentColor = cs.onPrimary, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(0.7f).height(56.dp).graphicsLayer { alpha = if (text.isNotBlank() && !busy) 1f else 0.38f },
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("Open", style = ts(16f, 650)) }
                }
            }
            Box(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = if (busy) "busy" else outcome,
                    transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.94f)).togetherWith(fadeOut(tween(100))) },
                    label = "outcome",
                ) { o ->
                    when (o) {
                        "busy" -> Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(Modifier.size(56.dp))
                            Text("Checking it can be solved by logic…", style = ts(13f, 500, cs.onSurfaceVariant))
                        }
                        is OpenCode.Outcome.Refused -> Refusal(o)
                        is OpenCode.Outcome.Ok -> Ready(o) { buzz.start(); model.play(o.deal) }
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Refusal(o: OpenCode.Outcome.Refused) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Sym["info"], null, Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(o.title, style = ts(17f, 650))
                Text(o.detail, style = ts(14f, 460, lineHeight = 20f))
            }
        }
    }
}

/** The opened puzzle: its tier's shape, tier and size, where it came from, and Play. Never the pattern. */
@Composable
private fun Ready(o: OpenCode.Outcome.Ok, onPlay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val d = o.deal
    val tier = d.tier.ordinal
    Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeGlyph(Shp.difficulty[tier], 52.dp, cs.onPrimaryContainer)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("${TIER_NAMES[tier]} · ${d.w}×${d.h}", style = ts(26f, 640, lineHeight = 30f, tnum = true))
                    Text(
                        (if (o.fromSgt) "From a Pattern game ID" else "From a share code") + if (o.rotated) ", turned on its side to fit" else "",
                        style = ts(13.5f, 500, cs.onPrimaryContainer.copy(alpha = 0.8f)),
                    )
                }
            }
            Text(
                "Its difficulty is read off the logic it needs, the same way as a puzzle made here.",
                style = ts(13.5f, 460, cs.onPrimaryContainer.copy(alpha = 0.85f), lineHeight = 19f),
            )
            Surface(onClick = onPlay, color = cs.onPrimaryContainer, contentColor = cs.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym["play_fill"], null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                    Text("Play it", style = ts(16f, 650))
                }
            }
        }
    }
}
