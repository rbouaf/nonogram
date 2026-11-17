package app.materialnonogram

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.materialnonogram.data.NonoJson
import app.materialnonogram.data.NonoStore
import app.materialnonogram.data.Settings
import app.materialnonogram.data.SettingsStore
import app.materialnonogram.engine.Generator
import app.materialnonogram.engine.Size
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Game
import app.materialnonogram.game.PuzzleShop
import app.materialnonogram.game.Status
import app.materialnonogram.game.findHint
import app.materialnonogram.ui.play.GridGeo
import app.materialnonogram.ui.play.Intro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Screen { HOME, PLAY, RESULTS, STATS, SETTINGS }

/** The app's one model: settings, the game in progress, finished games, and where we are. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val settingsStore = SettingsStore(app)
    val settings: StateFlow<Settings> = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, runBlocking { Settings() })
    private val store = NonoStore(app)
    private val saveLock = Mutex()
    /** Puzzles ready before Start is pressed; every offered Master is pre-generated on a background lane. */
    private val shop = PuzzleShop(viewModelScope)

    var screen by mutableStateOf(Screen.HOME)
    /** Where Settings returns to. */
    var settingsFrom by mutableStateOf(Screen.HOME)
    var current by mutableStateOf<Game?>(null); private set
    var finished by mutableStateOf<Game?>(null); private set
    var history by mutableStateOf<List<Game>>(emptyList()); private set
    var starting by mutableStateOf(false); private set
    /** A hint is being computed (a look-ahead hint on a 20×20 probes every unknown cell). */
    var thinking by mutableStateOf(false); private set

    /** Home → Play morph in flight (ui/play/Intro.kt), the board's measured place, and whether Play may show it. */
    var intro by mutableStateOf<Intro?>(null)
    var boardGeo by mutableStateOf<GridGeo?>(null)
    var revealed by mutableStateOf(true)
    private fun beginIntro(i: Intro?) { intro = i; revealed = i == null; boardGeo = null }

    init {
        current = store.loadCurrent()
        if (BuildConfig.DEBUG) current?.let { logSolution(it) }
        viewModelScope.launch(Dispatchers.IO) {
            history = store.all().filter { it.status != Status.PLAYING }
        }
        viewModelScope.launch { snapshotFlow { current?.id }.collect { id -> withContext(Dispatchers.IO) { store.setCurrent(id) } } }
        // Autosave: every change, debounced; the write itself is never cancelled, so a back gesture can't lose a move.
        viewModelScope.launch {
            snapshotFlow { current?.let { it to it.revision } }.filterNotNull().debounce(250).collect { (g, _) -> save(g) }
        }
        // Master takes up to ~1.5 s to generate: have one of each ready long before anyone asks.
        shop.prefetchMasters()
    }

    /**
     * The JSON is built on the main thread, where the game is mutated, so the event list is never read
     * mid-append; only the file write goes to IO.
     */
    private suspend fun save(g: Game) {
        val text = NonoJson.encode(g)
        withContext(NonCancellable + Dispatchers.IO) { saveLock.withLock { store.write(g.id, text) } }
    }

    /** Leaving the app: pause and write now, not after the debounce. */
    fun onStop() {
        val g = current ?: return
        g.pause(true)
        viewModelScope.launch { save(g) }
    }

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch { settingsStore.update(f) }

    fun prefetch(size: Size, tier: Tier) = shop.prefetch(size, tier)

    /** Deals a new puzzle for ([tier], [size]). The seed is internal; the puzzle is its bitmap (D10). */
    fun start(tier: Tier, size: Size, from: Intro? = null) {
        if (starting || !Generator.isOffered(size, tier)) return
        starting = true
        val s = settings.value
        viewModelScope.launch {
            val deal = shop.take(size, tier)
            val old = current
            // Replacing is final (as Sudoku): the old game keeps its log as abandoned.
            if (old != null && old.status == Status.PLAYING) save(old)
            val g = Game(id = newId(), deal = deal, rules = s.rules())
            if (BuildConfig.DEBUG) logSolution(g)
            current = g
            save(g)
            starting = false
            beginIntro(from)
            screen = Screen.PLAY
        }
    }

    /** Debug builds only: the solution, so tools/drive_nono.py can play a game end to end on the emulator. */
    private fun logSolution(g: Game) {
        val bits = buildString { for (i in 0 until g.cells) append(if (g.deal.puzzle.filled(i)) '1' else '0') }
        android.util.Log.d("NonoSol", "id=${g.id} w=${g.w} h=${g.h} bits=$bits")
    }

    private fun newId() = "n" + System.currentTimeMillis().toString(36)

    /** Same puzzle again: a fresh run of the same bitmap, under today's rules. */
    fun replay(old: Game) {
        val g = Game(id = newId(), deal = old.deal, rules = settings.value.rules())
        current = g
        viewModelScope.launch { save(g) }
        beginIntro(null)
        screen = Screen.PLAY
    }

    /** One row per game; the log itself goes in the JSON export. */
    fun exportCsv(): String = buildString {
        appendLine("id,started,w,h,tier,status,time_ms,flag_mistakes,lives,lives_left,band,assisted,mistakes,share")
        (history + listOfNotNull(current)).forEach { g ->
            appendLine(listOf(g.id, g.startedAt, g.w, g.h, g.deal.tier, g.status, g.elapsed, g.rules.flagMistakes, g.rules.lives, g.lives, g.band, g.assisted, g.mistakes, g.deal.shareCode.replace(" ", "")).joinToString(","))
        }
    }

    fun exportJson(): String = store.exportAll()

    fun continueGame(from: Intro? = null) {
        if (current == null) return
        beginIntro(from)
        screen = Screen.PLAY
    }

    fun leavePlay() {
        current?.let { g -> g.pause(false); g.clearHint(); viewModelScope.launch { save(g) } }
        screen = Screen.HOME
    }

    fun onFinished() {
        val g = current ?: return
        viewModelScope.launch { save(g) }
        finished = g
        history = history + g
        current = null
        screen = Screen.RESULTS
    }

    fun openSettings(from: Screen) { settingsFrom = from; screen = Screen.SETTINGS }

    // ── Hints (BUILD-PLAN §3): line → cell → why, then a fourth press places it ──────────────────
    fun hint() {
        val g = current ?: return
        if (!g.rules.hints || g.status != Status.PLAYING || g.paused || g.inWhatIf || thinking) return
        val now = g.hint
        if (now != null) {
            if (!g.advanceHint()) g.applyHint()
            return
        }
        thinking = true
        viewModelScope.launch {
            val h = runCatching { findHint(g) }.getOrNull()
            thinking = false
            if (h != null && current === g && g.status == Status.PLAYING) g.showHint(h)
        }
    }
}
