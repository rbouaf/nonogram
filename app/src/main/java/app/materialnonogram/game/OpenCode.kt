package app.materialnonogram.game

import app.materialnonogram.engine.Importer
import app.materialnonogram.engine.ShareCode
import app.materialnonogram.engine.SgtId

/**
 * Home's "Open a code" (BUILD-PLAN §5.2, D10): a share code or a Simon Tatham Pattern ID becomes a
 * [Deal], or a refusal said in plain words. Grading an import can take a while (P2 is allowed), so
 * call [open] off the main thread.
 */
object OpenCode {
    /** The biggest board Play handles today (D1; sizes above 20×20 are BUILD-PLAN §7). */
    const val MAX_SIDE = 20
    const val MIN_SIDE = 2

    sealed class Outcome {
        /** [rotated]: the code was wide and was turned on its side to fit the phone. */
        class Ok(val deal: Deal, val rotated: Boolean, val fromSgt: Boolean) : Outcome()
        class Refused(val title: String, val detail: String) : Outcome()
    }

    /** The puzzle's size read off the code without grading it, so a huge one is refused at once. Null if unreadable. */
    fun sizeOf(text: String): Pair<Int, Int>? {
        val t = text.trim()
        return if (t.indexOf(':') >= 0) SgtId.parse(t).clues?.let { it.w to it.h }
        else ShareCode.decode(t)?.let { it.w to it.h }
    }

    /**
     * The code inside [text]: a whole share message ("Medium · 15×15 … 1H2K5-…"), or a line copied from
     * Pattern. An SGT ID wins (`WxH:` or `WxH#`), else the longest dash-grouped run of letters and digits
     * at least ten long (the shortest code, 5×5, is ten). Otherwise the text as it is.
     */
    fun extract(text: String): String {
        SGT.find(text)?.let { return it.value }
        val tokens = TOKEN.findAll(text).map { it.value }.filter { t -> t.count { it != '-' } >= 10 && t.any { it.isDigit() } }
        return tokens.maxByOrNull { it.length } ?: text.trim()
    }

    // No braces in either pattern: ICU and java.util.regex disagree about a bare `}` (CLAUDE.md).
    private val SGT = Regex("[0-9]+x[0-9]+[:#][0-9A-Za-z./,#]*")
    private val TOKEN = Regex("[0-9A-Za-z]+(-[0-9A-Za-z]+)*")

    /** Share text for [deal]: what it is, the code, and where to open it. [extract] reads it back. */
    fun shareText(deal: Deal, tierName: String): String =
        (if (tierName.first().lowercaseChar() in "aeiou") "An" else "A") + " $tierName ${deal.w}×${deal.h} nonogram\n${deal.shareCode}\nOpen it in Material Nonogram: Home › Open a code"

    fun open(text: String): Outcome {
        val t = extract(text)
        if (t.isEmpty()) return refused("Nothing to open", "Paste a share code or a Pattern game ID first.")
        sizeOf(t)?.let { (w, h) ->
            if (maxOf(w, h) > MAX_SIDE) return refused("Too big for now", "That is a $w×$h puzzle. This app plays boards up to $MAX_SIDE×$MAX_SIDE.")
            if (minOf(w, h) < MIN_SIDE) return refused("Too small", "That is a $w×$h puzzle; a board needs at least two rows and two columns.")
        }
        shapeProblem(t)?.let { return it }
        val r = Importer.open(t)
        val refusal = r.refusal
        if (refusal != null || !r.ok) return when (refusal) {
            Importer.Refusal.BAD_CHECK -> refused("One character is off", "The code's check doesn't match, so a letter or digit was mistyped. Codes ignore case and dashes, and read O as 0 and I or L as 1.")
            Importer.Refusal.SGT_SEED -> refused("That is a random seed", "Pattern turns a seed (the part after #) into a different puzzle in each version. In Pattern, use Game › Specific… and copy the ID with a colon instead.")
            Importer.Refusal.SGT_GIVENS -> refused("Given cells aren't supported", "That ID starts with some cells already filled in. Remove the part after the comma to play it from blank.")
            Importer.Refusal.NO_SOLUTION -> refused("These clues have no solution", "No grid fits every row and column, so it can't be played.")
            Importer.Refusal.NOT_UNIQUE -> refused("Needs guessing", "Logic alone can't finish this one: it has more than one solution, or it needs a guess somewhere. Every puzzle here can be solved without guessing.")
            else -> refused("Not a puzzle code", NOT_A_CODE)
        }
        val deal = Deal.imported(r) ?: return refused("Not a puzzle code", "The code opened but could not be graded.")
        return Outcome.Ok(deal, r.rotated, r.source == Importer.Source.SGT_ID)
    }

    /**
     * A share code's first character holds the codec version (0) and most of the size, so it is one of
     * 0–3, and a known size fixes the length. Text that fails either is not a typo of a code but something
     * else, or a code cut short in the copy.
     */
    private fun shapeProblem(t: String): Outcome.Refused? {
        if (t.indexOf(':') >= 0 || t.indexOf('#') >= 0) return null
        val c = t.uppercase().filter { it != '-' && !it.isWhitespace() }.replace('O', '0').replace('I', '1').replace('L', '1')
        if (c.isEmpty() || c[0] !in "0123") return refused("Not a puzzle code", NOT_A_CODE)
        val sizeCode = ALPHABET.indexOf(c[0]).shl(5).or(ALPHABET.indexOf(c.getOrElse(1) { '0' }).coerceAtLeast(0)).shr(4) and 7
        val size = app.materialnonogram.engine.Size.ofCode(sizeCode) ?: return null
        val chars = (6 + size.w * size.h + app.materialnonogram.engine.ShareCode.CHECK_BITS + 4) / 5
        return when {
            c.length < chars -> refused("Part of the code is missing", "A ${size.label} code has $chars characters; this one has ${c.length}. Copy it again, all of it.")
            c.length > chars -> refused("The code is too long", "A ${size.label} code has $chars characters; this one has ${c.length}. Check that nothing was pasted twice.")
            else -> null
        }
    }

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val NOT_A_CODE = "Paste a share code (letters and digits in groups of five) or a Pattern game ID (like 5x5:2/1.1/…)."

    private fun refused(title: String, detail: String) = Outcome.Refused(title, detail)
}
