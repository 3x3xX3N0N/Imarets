package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.CardAnchor
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

/** Semantic zoom level of a card (SPEC 3.1: far = order code only, near = full card). */
enum class CardDetail(val attributeValue: String) {
    CODE("code"),
    TITLE("title"),
    FULL("full"),
}

/**
 * Places DOM cards over the canvas from [CardAnchor]s. Works for ANY rung's anchors (nothing here is
 * Canvas2D specific); it lives in bloom-2d because on this rung DOM cards moved by CSS transforms are the
 * only way text gets on screen.
 *
 * CSP-safe: only `style` PROPERTIES and `data-*` / `hidden` attributes are touched, never a style attribute
 * string. Expected CSS for a card: `position: absolute; left: 0; top: 0; transform-origin: 0 0; will-change: transform`.
 * The card is centred on the anchor by the transform itself, so it needs no known size.
 */
object Cards2d {
    /** px per bloom unit below which only the order code shows. */
    const val TITLE_FROM: Double = 95.0

    /** px per bloom unit from which the full card shows. */
    const val FULL_FROM: Double = 210.0

    /** px per bloom unit at which a card is drawn at its natural size (scale 1). */
    const val NATURAL_PX_PER_UNIT: Double = 260.0
    const val MIN_SCALE: Double = 0.4
    const val MAX_SCALE: Double = 1.0

    const val DETAIL_ATTRIBUTE: String = "data-detail"

    /** Hysteresis-free level for a zoom; use [detail] with `current` to avoid flicker at a threshold. */
    fun detail(pxPerUnit: Double): CardDetail = when {
        pxPerUnit >= FULL_FROM -> CardDetail.FULL
        pxPerUnit >= TITLE_FROM -> CardDetail.TITLE
        else -> CardDetail.CODE
    }

    /** Level with 8 % hysteresis around the thresholds, given the level the card shows now. */
    fun detail(pxPerUnit: Double, current: CardDetail): CardDetail {
        val up = 1.08
        val down = 0.92
        return when (current) {
            CardDetail.CODE -> if (pxPerUnit >= FULL_FROM * up) CardDetail.FULL else if (pxPerUnit >= TITLE_FROM * up) CardDetail.TITLE else CardDetail.CODE
            CardDetail.TITLE -> if (pxPerUnit >= FULL_FROM * up) CardDetail.FULL else if (pxPerUnit < TITLE_FROM * down) CardDetail.CODE else CardDetail.TITLE
            CardDetail.FULL -> if (pxPerUnit < TITLE_FROM * down) CardDetail.CODE else if (pxPerUnit < FULL_FROM * down) CardDetail.TITLE else CardDetail.FULL
        }
    }

    /** Card scale for a zoom: grows with the bloom but never past natural size, never below legibility. */
    fun scale(pxPerUnit: Double): Double = (pxPerUnit / NATURAL_PX_PER_UNIT).coerceIn(MIN_SCALE, MAX_SCALE)

    /** The CSS transform that centres a card on ([x], [y]) at [scale]. Rounded to 0.1 px / 0.001 to keep strings short. */
    fun transform(x: Double, y: Double, scale: Double): String {
        val tx = (x * 10.0).roundToInt() / 10.0
        val ty = (y * 10.0).roundToInt() / 10.0
        val s = (scale * 1000.0).roundToInt() / 1000.0
        return "translate3d(${tx}px,${ty}px,0) scale($s) translate(-50%,-50%)"
    }

    /**
     * Move [card] to [anchor]: transform, opacity, z-index (nearer = higher), `hidden` when not visible, and the
     * `data-detail` attribute (written only when it changes) for the stylesheet to switch content on.
     * @param offsetY CSS px to shift the card off the ring (e.g. -18 to sit above the anchor)
     */
    fun place(card: HTMLElement, anchor: CardAnchor, offsetX: Double = 0.0, offsetY: Double = 0.0) {
        if (!anchor.visible || anchor.alpha <= 0.02) {
            if (!card.hidden) card.hidden = true
            return
        }
        if (card.hidden) card.hidden = false
        val now = card.getAttribute(DETAIL_ATTRIBUTE)
        val current = when (now) {
            CardDetail.FULL.attributeValue -> CardDetail.FULL
            CardDetail.TITLE.attributeValue -> CardDetail.TITLE
            else -> CardDetail.CODE
        }
        val next = detail(anchor.pxPerUnit, current)
        if (now != next.attributeValue) card.setAttribute(DETAIL_ATTRIBUTE, next.attributeValue)
        val style = card.style
        style.transform = transform(anchor.x + offsetX, anchor.y + offsetY, scale(anchor.pxPerUnit))
        style.opacity = ((anchor.alpha.coerceIn(0.0, 1.0) * 100.0).roundToInt() / 100.0).toString()
        style.zIndex = zIndex(anchor.depth).toString()
    }

    /** Nearer cards on top: depth 0 -> 2000, falling 100 per bloom unit, floor at 1. */
    fun zIndex(depth: Double): Int {
        val z = 2000 - (depth * 100.0).roundToInt()
        return if (z < 1) 1 else z
    }
}
