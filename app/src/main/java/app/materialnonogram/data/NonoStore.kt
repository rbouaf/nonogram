package app.materialnonogram.data

import android.content.Context
import app.materialnonogram.engine.Puzzle
import app.materialnonogram.engine.ShareCode
import app.materialnonogram.engine.Tier
import app.materialnonogram.game.Deal
import app.materialnonogram.game.Ev
import app.materialnonogram.game.Game
import app.materialnonogram.game.Origin
import app.materialnonogram.game.Rules
import app.materialnonogram.game.Status
import app.materialnonogram.game.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every nonogram is one JSON file in `filesDir/nonograms/` (apart from the legacy Sudoku `games/`, so
 * both stores work until the UI switches over): the deal **with its solution bitmap** (D10), the
 * rules, the board, and the full event log. The log is the record; every stat is recomputed from it.
 * Files are written whole, through a temp file and a rename, so a crash mid-write never leaves half a
 * game. Undo history is not persisted (as in Sudoku).
 */
class NonoStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "nonograms"))

    init { dir.mkdirs() }

    fun save(g: Game) {
        val f = File(dir, "${g.id}.json")
        val tmp = File(dir, "${g.id}.json.tmp")
        tmp.writeText(NonoJson.encode(g))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private val pointer = File(dir, "current.txt")

    /** The game in progress, read before the first frame so Home's slot never swaps at launch. */
    fun loadCurrent(): Game? = runCatching { pointer.readText().trim() }.getOrNull()?.let { load(it) }?.takeIf { it.status == Status.PLAYING }
    fun setCurrent(id: String?) { if (id == null) pointer.delete() else pointer.writeText(id) }

    fun load(id: String): Game? = runCatching { NonoJson.decode(File(dir, "$id.json").readText()) }.getOrNull()

    fun delete(id: String) { File(dir, "$id.json").delete() }

    /** Every readable game, oldest first. A damaged file is skipped, never fatal. */
    fun all(): List<Game> = files().mapNotNull { runCatching { NonoJson.decode(it.readText()) }.getOrNull() }.sortedBy { it.startedAt }

    /** Every game file, verbatim, as one JSON array. */
    fun exportAll(): String = files().sortedBy { it.name }.joinToString(",\n", "[\n", "\n]") { it.readText() }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList()
}

/** The game file format, version 1. */
object NonoJson {
    const val VERSION = 1

    fun encode(g: Game): String {
        val d = g.deal
        val o = JSONObject()
            .put("v", VERSION)
            .put("id", g.id)
            .put("w", d.w).put("h", d.h)
            .put("tier", d.tier.name)
            .put("seed", d.seed)
            .put("generator", d.generatorVersion)
            .put("origin", d.origin.name)
            .put("bits", bits(d.puzzle))
            .put("share", ShareCode.encode(d.puzzle, grouped = false)) // informational; the bits are the identity
            .put("rules", JSONObject()
                .put("flag", g.rules.flagMistakes).put("lives", g.rules.lives)
                .put("autoCross", g.rules.autoCross).put("contradiction", g.rules.contradictionFlag)
                .put("check", g.rules.checkButton).put("hints", g.rules.hints))
            .put("band", g.band)
            .put("autoCross", g.autoCross)
            .put("contradiction", g.contradictionFlag)
            .put("startedAt", g.startedAt)
            .put("status", g.status.name)
            .put("elapsed", g.elapsed)
            .put("livesLeft", g.lives)
            .put("tool", g.tool.name)
            .put("board", g.board.joinToString("") { it.toString() })
            .put("auto", g.auto.joinToString("") { if (it) "1" else "0" })
            .put("events", JSONArray().apply { g.events.forEach { put(JSONArray().put(it.t).put(it.kind.toString()).put(it.cell).put(it.value).put(it.x)) } })
        g.whatIfState()?.let { (b, a) ->
            o.put("whatIf", JSONObject().put("board", b.joinToString("") { it.toString() }).put("auto", a.joinToString("") { if (it) "1" else "0" }))
        }
        return o.toString()
    }

    fun decode(text: String): Game {
        val o = JSONObject(text)
        require(o.getInt("v") <= VERSION) { "game file v${o.getInt("v")} is newer than this app" }
        val w = o.getInt("w"); val h = o.getInt("h")
        val bits = o.getString("bits")
        require(bits.length == w * h)
        val puzzle = Puzzle(w, h, BooleanArray(w * h) { bits[it] == '1' })
        val deal = Deal(puzzle, Tier.valueOf(o.getString("tier")), o.getLong("seed"), o.getInt("generator"), Origin.valueOf(o.optString("origin", Origin.GENERATED.name)))
        val r = o.getJSONObject("rules")
        val rules = Rules(r.getBoolean("flag"), r.getInt("lives"), r.getBoolean("autoCross"), r.getBoolean("contradiction"), r.getBoolean("check"), r.getBoolean("hints"))
        val ev = o.getJSONArray("events")
        val events = MutableList(ev.length()) { k ->
            val a = ev.getJSONArray(k)
            Ev(a.getLong(0), a.getString(1)[0], a.getInt(2), a.getInt(3), a.getInt(4))
        }
        val wi = o.optJSONObject("whatIf")
        return Game(
            id = o.getString("id"), deal = deal, rules = rules,
            board = board(o.getString("board"), w * h), auto = flags(o.getString("auto"), w * h),
            lives = o.getInt("livesLeft"), elapsed = o.getLong("elapsed"),
            events = events, status = Status.valueOf(o.getString("status")),
            startedAt = o.getLong("startedAt"), band = o.getInt("band"),
            autoCross = o.getBoolean("autoCross"), contradictionFlag = o.getBoolean("contradiction"),
            whatIfBase = wi?.let { board(it.getString("board"), w * h) },
            whatIfBaseAuto = wi?.let { flags(it.getString("auto"), w * h) },
            tool = Tool.valueOf(o.optString("tool", Tool.FILL.name)),
        )
    }

    private fun bits(p: Puzzle) = buildString(p.cells) { for (i in 0 until p.cells) append(if (p.filled(i)) '1' else '0') }
    private fun board(s: String, n: Int): ByteArray { require(s.length == n); return ByteArray(n) { (s[it] - '0').toByte().also { v -> require(v in 0..2) } } }
    private fun flags(s: String, n: Int): BooleanArray { require(s.length == n); return BooleanArray(n) { s[it] == '1' } }
}
