package bar.verdantbloom.bloom.api

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element

/** Reads the active [Palette] from the CSS custom properties named in [PaletteCss]. */
object PaletteDom {
    /**
     * @param root element carrying `data-theme` (default: `<html>`).
     * Any property that is missing or not a hex colour falls back to the matching built-in palette value.
     */
    fun read(root: Element? = document.documentElement): Palette {
        val theme = root?.getAttribute(PaletteCss.THEME_ATTRIBUTE)
        val fallback = if (theme == PaletteCss.THEME_DAY) Palettes.NIGHTSHADE_HERBARIUM else Palettes.ABSINTHE_ABYSS
        if (root == null) return fallback
        val style = window.getComputedStyle(root)
        fun c(name: String, default: Int): Int = PaletteCss.parseHex(style.getPropertyValue(name)) ?: default
        return Palette(
            name = theme ?: fallback.name,
            night = fallback.night,
            ground = c(PaletteCss.GROUND, fallback.ground),
            ink = c(PaletteCss.INK, fallback.ink),
            rule = c(PaletteCss.RULE, fallback.rule),
            glow = c(PaletteCss.GLOW, fallback.glow),
            accent = c(PaletteCss.ACCENT, fallback.accent),
            accent2 = c(PaletteCss.ACCENT_2, fallback.accent2),
            warm = c(PaletteCss.WARM, fallback.warm),
            alert = c(PaletteCss.ALERT, fallback.alert),
            ringWell = c(PaletteCss.RING_WELL, fallback.ringWell),
            ringCall = c(PaletteCss.RING_CALL, fallback.ringCall),
            ringTop = c(PaletteCss.RING_TOP, fallback.ringTop),
            ringVisitor = c(PaletteCss.RING_VISITOR, fallback.ringVisitor),
            ringGhost = c(PaletteCss.RING_GHOST, fallback.ringGhost),
        )
    }
}
