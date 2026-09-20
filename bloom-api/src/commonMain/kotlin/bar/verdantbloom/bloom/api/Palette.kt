package bar.verdantbloom.bloom.api

/**
 * Scene colours, all 0xRRGGBB. Source of truth is CSS: custom properties on `:root[data-theme]`
 * named by [PaletteCss]; Kotlin reads them so the 3D scene matches the chrome (SPEC 3.5).
 */
data class Palette(
    val name: String,
    val night: Boolean,
    val ground: Int,
    val ink: Int,
    val rule: Int,
    val glow: Int,
    val accent: Int,
    val accent2: Int,
    val warm: Int,
    val alert: Int,
    val ringWell: Int,
    val ringCall: Int,
    val ringTop: Int,
    val ringVisitor: Int,
    val ringGhost: Int,
) {
    fun shelfColor(shelf: Shelf): Int = when (shelf) {
        Shelf.WELL -> ringWell
        Shelf.CALL -> ringCall
        Shelf.TOP_SHELF -> ringTop
    }

    /** Colour for any ring id given the model list. */
    fun ringColor(ringId: Int, rings: List<ModelRing>): Int = when (ringId) {
        RingIds.VISITOR -> ringVisitor
        RingIds.GHOST -> ringGhost
        else -> if (ringId in rings.indices) shelfColor(rings[ringId].shelf) else ink
    }
}

/** Names of the CSS custom properties. Values MUST be written as #rrggbb (or #rgb) in the stylesheet. */
object PaletteCss {
    const val THEME_ATTRIBUTE = "data-theme"
    const val THEME_NIGHT = "absinthe-abyss"
    const val THEME_DAY = "nightshade-herbarium"

    const val GROUND = "--vb-ground"
    const val INK = "--vb-ink"
    const val RULE = "--vb-rule"
    const val GLOW = "--vb-glow"
    const val ACCENT = "--vb-accent"
    const val ACCENT_2 = "--vb-accent-2"
    const val WARM = "--vb-warm"
    const val ALERT = "--vb-alert"
    const val RING_WELL = "--vb-ring-well"
    const val RING_CALL = "--vb-ring-call"
    const val RING_TOP = "--vb-ring-top"
    const val RING_VISITOR = "--vb-ring-visitor"
    const val RING_GHOST = "--vb-ring-ghost"

    /** Parses "#rgb" / "#rrggbb" (surrounding whitespace allowed). Returns null for anything else. */
    fun parseHex(css: String): Int? {
        val s = css.trim()
        if (!s.startsWith("#")) return null
        val h = s.substring(1)
        val full = when (h.length) {
            3 -> buildString { for (c in h) { append(c); append(c) } }
            6 -> h
            else -> return null
        }
        return full.toIntOrNull(16)
    }
}

/** Fallback palettes used when CSS cannot be read (tests, no stylesheet). The stylesheet wins at runtime. */
object Palettes {
    val ABSINTHE_ABYSS = Palette(
        name = PaletteCss.THEME_NIGHT, night = true,
        ground = 0x020a07, ink = 0xd8f2e0, rule = 0x9fe8b8, glow = 0xb8ffce,
        accent = 0x3dffc0, accent2 = 0x19c9a3, warm = 0xc9a24a, alert = 0xff9d3c,
        ringWell = 0x3dffc0, ringCall = 0xb8ffce, ringTop = 0xc9a24a,
        ringVisitor = 0xffe9a8, ringGhost = 0x3a5a4a,
    )
    val NIGHTSHADE_HERBARIUM = Palette(
        name = PaletteCss.THEME_DAY, night = false,
        ground = 0xefe8d8, ink = 0x1d1a16, rule = 0x1d1a16, glow = 0x6b4a8c,
        accent = 0x4b2a6e, accent2 = 0xa3195b, warm = 0x7a5a1e, alert = 0x7d1414,
        ringWell = 0x4b2a6e, ringCall = 0xa3195b, ringTop = 0x7d1414,
        ringVisitor = 0x1d1a16, ringGhost = 0xb8ad98,
    )

    /** Night is 19:00-07:00 visitor LOCAL time (default). */
    fun forLocalHour(hour: Int): Palette =
        if (hour >= 19 || hour < 7) ABSINTHE_ABYSS else NIGHTSHADE_HERBARIUM
}
