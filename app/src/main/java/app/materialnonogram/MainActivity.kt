package app.materialnonogram

import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.materialnonogram.data.Motion
import app.materialnonogram.ui.home.HomeScreen
import app.materialnonogram.ui.kit.Buzz
import app.materialnonogram.ui.kit.LocalBuzz
import app.materialnonogram.ui.kit.LocalSprings
import app.materialnonogram.ui.kit.Springs
import app.materialnonogram.ui.play.PlayScreen
import app.materialnonogram.ui.results.ResultsScreen
import app.materialnonogram.ui.settings.SettingsScreen
import app.materialnonogram.ui.stats.StatsScreen
import app.materialnonogram.ui.theme.NonogramTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val buzz = Buzz(this)
        val systemReduced = SystemSettings.Global.getFloat(contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            val s by model.settings.collectAsStateWithLifecycle()
            LaunchedEffect(s.haptics) { buzz.level = s.haptics }
            LaunchedEffect(s.kanjiName) { app.materialnonogram.data.LauncherName.apply(applicationContext, s.kanjiName) }
            val reduced = when (s.motion) { Motion.SYSTEM -> systemReduced; Motion.REDUCED -> true; Motion.FULL -> false }
            val springs = remember(reduced) { Springs(reduced) }
            NonogramTheme(mode = s.theme, seed = s.seed, amoled = s.amoled) {
                CompositionLocalProvider(LocalBuzz provides buzz, LocalSprings provides springs) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        BackHandler(model.screen != Screen.HOME) {
                            when (model.screen) {
                                Screen.PLAY -> model.leavePlay()
                                Screen.SETTINGS -> model.screen = model.settingsFrom
                                else -> model.screen = Screen.HOME
                            }
                        }
                        AnimatedContent(
                            targetState = model.screen,
                            transitionSpec = {
                                if (targetState == Screen.PLAY && model.intro != null) {
                                    androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
                                } else {
                                    (fadeIn(tween(220, 60)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320)))
                                        .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 1.02f, animationSpec = tween(200)))
                                }
                            },
                            label = "screen",
                        ) { screen ->
                            when (screen) {
                                Screen.HOME -> HomeScreen(model, s)
                                Screen.PLAY -> model.current?.let { g ->
                                    PlayScreen(
                                        g, s,
                                        onBack = model::leavePlay,
                                        onSettings = { model.openSettings(Screen.PLAY) },
                                        onHint = model::hint,
                                        onFinished = model::onFinished,
                                        halfwayLine = { shown -> app.materialnonogram.game.halfwayLine(g, model.history, shown) },
                                        introActive = model.intro != null,
                                        revealed = model.revealed,
                                        introKind = when (model.intro) { is app.materialnonogram.ui.play.Intro.Continue -> 1; else -> 0 },
                                        onBoardGeo = { model.boardGeo = it },
                                        thinking = model.thinking,
                                    )
                                }
                                Screen.RESULTS -> ResultsScreen(model, s)
                                Screen.STATS -> StatsScreen(model, s)
                                Screen.SETTINGS -> SettingsScreen(model, s)
                            }
                        }
                        val intro = model.intro
                        val geo = model.boardGeo
                        val cur = model.current
                        if (intro != null && geo != null && cur != null && model.screen == Screen.PLAY) {
                            app.materialnonogram.ui.play.IntroOverlay(intro, geo, cur, onReveal = { model.revealed = true }, onDone = { model.intro = null })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app pauses the game and writes it now, not after the autosave's debounce.
        model.onStop()
    }
}
