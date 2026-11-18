package app.materialnonogram.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Rules
import app.materialnonogram.ui.theme.Seed
import app.materialnonogram.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Haptics { OFF, SOME, ALL }
enum class Motion { SYSTEM, REDUCED, FULL }

/** Every nonogram setting (BUILD-PLAN §5, DECISIONS D4/D5/D7). Defaults are the decided defaults. */
data class Settings(
    /** Tiers shown on Home, bit k = [Tier] ordinal k. Fast, Easy, Medium, Hard by default; Expert, Master from Settings (D7). */
    val tiers: Int = 0b100111,
    /** Sizes shown on Home, bit k = [Size] ordinal k. 5×5, 10×10, 15×15 by default; 10×15, 15×20, 20×20 from Settings (D7). */
    val sizes: Int = 0b000111,
    /** D4: flag mistakes (a wrong mark is refused and costs a life) with 3 lives or 1; or don't flag them. */
    val flagMistakes: Boolean = true,
    val lives: Int = 3,
    /** Q6: a finished line's other cells become crosses. Labour: aid band 1. */
    val autoCross: Boolean = true,
    /** Don't-flag mode only: a clue turns red when finished runs contradict it. Aid band 2. */
    val contradictionFlag: Boolean = false,
    /** Don't-flag mode only: an on-demand yes/no check. An oracle: the run becomes assisted. */
    val checkButton: Boolean = false,
    val hints: Boolean = true,
    /** D5: holding still on the start cell switches the stroke to the other tool. */
    val longPress: Boolean = true,
    /** How an empty cell is marked on the board: Symbols' ×, or a four-sided cookie in a hue the theme isn't using. */
    val cookieCross: Boolean = false,
    val showTimer: Boolean = true,
    /** Once per game at half the cells: pace, or a word of encouragement. */
    val halfwayMessage: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    /** null = wallpaper colour. */
    val seed: Seed? = null,
    val motion: Motion = Motion.SYSTEM,
    val haptics: Haptics = Haptics.ALL,
    /** The name under the launcher icon: false "Nonogram", true "イラロジ". */
    val kanjiName: Boolean = false,
    // Home's last picks.
    val lastTier: Int = 0,
    val lastSize: Int = 1,
) {
    fun tierOn(t: Tier) = (tiers shr t.ordinal) and 1 == 1
    fun sizeOn(s: Size) = (sizes shr s.ordinal) and 1 == 1

    /** The rules a new run is locked to (D4, BUILD-PLAN §5.1). */
    fun rules(): Rules = Rules.of(flagMistakes, lives, autoCross, contradictionFlag, checkButton, hints)
}

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val tiers = intPreferencesKey("tiers")
        /** Set once Fast has been offered: older installs get Fast switched on once (it is on by default). */
        val fastSeen = booleanPreferencesKey("fast_seen")
        val sizes = intPreferencesKey("sizes")
        val flag = booleanPreferencesKey("flag_mistakes")
        val lives = intPreferencesKey("lives")
        val autoCross = booleanPreferencesKey("auto_cross")
        val contra = booleanPreferencesKey("contradiction_flag")
        val check = booleanPreferencesKey("check_button")
        val hints = booleanPreferencesKey("hints")
        val longPress = booleanPreferencesKey("long_press")
        val cookie = booleanPreferencesKey("cookie_cross")
        val timer = booleanPreferencesKey("show_timer")
        val halfway = booleanPreferencesKey("halfway_message")
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled")
        val seed = stringPreferencesKey("seed")
        val motion = stringPreferencesKey("motion")
        val haptics = stringPreferencesKey("haptics")
        val kanji = booleanPreferencesKey("kanji_name")
        val lastT = intPreferencesKey("last_tier")
        val lastS = intPreferencesKey("last_size")
    }

    val flow: Flow<Settings> = context.store.data.map { p -> read(p) }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            tiers = (p[K.tiers] ?: d.tiers).let { if (p[K.fastSeen] == null) it or (1 shl Tier.FAST.ordinal) else it },
            sizes = p[K.sizes] ?: d.sizes,
            flagMistakes = p[K.flag] ?: d.flagMistakes,
            lives = (p[K.lives] ?: d.lives).let { if (it == 1) 1 else 3 },
            autoCross = p[K.autoCross] ?: d.autoCross,
            contradictionFlag = p[K.contra] ?: d.contradictionFlag,
            checkButton = p[K.check] ?: d.checkButton,
            hints = p[K.hints] ?: d.hints,
            longPress = p[K.longPress] ?: d.longPress,
            cookieCross = p[K.cookie] ?: d.cookieCross,
            showTimer = p[K.timer] ?: d.showTimer,
            halfwayMessage = p[K.halfway] ?: d.halfwayMessage,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            amoled = p[K.amoled] ?: d.amoled,
            seed = p[K.seed]?.let { s -> Seed.entries.firstOrNull { it.name == s } },
            motion = p[K.motion]?.let { runCatching { Motion.valueOf(it) }.getOrNull() } ?: d.motion,
            haptics = p[K.haptics]?.let { runCatching { Haptics.valueOf(it) }.getOrNull() } ?: d.haptics,
            kanjiName = p[K.kanji] ?: d.kanjiName,
            lastTier = (p[K.lastT] ?: d.lastTier).coerceIn(0, Tier.entries.size - 1),
            lastSize = (p[K.lastS] ?: d.lastSize).coerceIn(0, Size.entries.size - 1),
        )
    }

    suspend fun update(f: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = f(read(p))
            p[K.tiers] = s.tiers; p[K.sizes] = s.sizes; p[K.fastSeen] = true
            p[K.flag] = s.flagMistakes; p[K.lives] = s.lives
            p[K.autoCross] = s.autoCross; p[K.contra] = s.contradictionFlag; p[K.check] = s.checkButton
            p[K.hints] = s.hints; p[K.longPress] = s.longPress; p[K.cookie] = s.cookieCross
            p[K.timer] = s.showTimer; p[K.halfway] = s.halfwayMessage
            p[K.theme] = s.theme.name; p[K.amoled] = s.amoled
            if (s.seed == null) p.remove(K.seed) else p[K.seed] = s.seed.name
            p[K.motion] = s.motion.name; p[K.haptics] = s.haptics.name
            p[K.kanji] = s.kanjiName
            p[K.lastT] = s.lastTier; p[K.lastS] = s.lastSize
        }
    }
}
