package app.materialnonogram.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** CIE L*C*h° (D65) ↔ sRGB. Copied from Notes' NotePalette.kt (the suite copies, never shares). */
internal object Lch {

    fun hueOf(c: Color): Float {
        val (_, a, b) = lab(c)
        val h = Math.toDegrees(atan2(b, a)).toFloat()
        return (h + 360) % 360
    }

    /**
     * The colour at tone [l], chroma [c], hue [h], with chroma reduced until it fits in sRGB —
     * tone and hue are never traded, because tone is the contrast guarantee.
     */
    fun color(l: Double, c: Double, h: Float): Color {
        var lo = 0.0
        var hi = c
        if (inGamut(l, hi, h)) return toColor(l, hi, h)
        repeat(18) {
            val mid = (lo + hi) / 2
            if (inGamut(l, mid, h)) lo = mid else hi = mid
        }
        return toColor(l, lo, h)
    }

    private fun inGamut(l: Double, c: Double, h: Float): Boolean =
        linear(l, c, h).all { it in -1e-4..1.0001 }

    private fun toColor(l: Double, c: Double, h: Float): Color {
        val (r, g, b) = linear(l, c, h).map { gamma(it.coerceIn(0.0, 1.0)) }
        return Color(r.toFloat(), g.toFloat(), b.toFloat())
    }

    private fun linear(l: Double, c: Double, h: Float): List<Double> {
        val hr = Math.toRadians(h.toDouble())
        val a = c * cos(hr)
        val b = c * sin(hr)
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        val x = XN * finv(fx)
        val y = finv(fy)
        val z = ZN * finv(fz)
        return listOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
    }

    private fun lab(c: Color): Triple<Double, Double, Double> {
        val r = ungamma(c.red.toDouble())
        val g = ungamma(c.green.toDouble())
        val b = ungamma(c.blue.toDouble())
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Chroma of a colour — used by tests to check the palette is actually coloured. */
    fun chromaOf(c: Color): Double = lab(c).let { (_, a, b) -> hypot(a, b) }

    /** Tone (L*) of a colour. */
    fun toneOf(c: Color): Double = lab(c).first

    private const val XN = 0.95047
    private const val ZN = 1.08883
    private const val E = 216.0 / 24389
    private const val K = 24389.0 / 27

    private fun f(t: Double) = if (t > E) cbrt(t) else (K * t + 16) / 116
    private fun finv(f: Double) = (f * f * f).let { if (it > E) it else (116 * f - 16) / K }
    private fun gamma(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun ungamma(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/**
 * The cookie mark's colour (Settings → Look → Mark empty cells with): a hue the theme is *not* using,
 * so a cookie can never be mistaken for a fill (`tertiary`) or the selection (`primary`). The middle of
 * the largest arc of the colour wheel at least 40° from primary and tertiary (Notes' NotePalette
 * method), at a tone that stands off the wells: L* 72 on dark, 52 on light.
 */
fun cookieColor(scheme: androidx.compose.material3.ColorScheme, dark: Boolean): Color {
    val used = buildList {
        add(scheme.primary); add(scheme.tertiary)
        if (Lch.chromaOf(scheme.secondaryContainer) > 12) add(scheme.secondaryContainer)
    }.filter { Lch.chromaOf(it) > 6 }.map { Lch.hueOf(it) }.sorted()
    val hue = if (used.isEmpty()) 25f else used.indices.map { i ->
        val from = used[i]
        val to = if (i + 1 < used.size) used[i + 1] else used[0] + 360
        from to (to - from)
    }.maxBy { it.second }.let { (from, gap) -> (from + gap / 2) % 360 }
    return if (dark) Lch.color(72.0, 52.0, hue) else Lch.color(52.0, 56.0, hue)
}
