package com.example.spacegraphkt.zui

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Semantic-zoom level of an HTML node. The engine writes [attr] into the node element's `data-lod`
 * attribute so CSS can swap content, e.g. `.node-html[data-lod="far"] .card-body { display: none }`.
 */
enum class LodLevel(val attr: String) {
    FAR("far"), MID("mid"), NEAR("near");

    companion object {
        const val DATA_ATTRIBUTE = "data-lod"
        fun fromAttr(value: String?): LodLevel? = entries.firstOrNull { it.attr == value }
    }
}

/**
 * Camera-distance thresholds (world units, the engine's CSS px at scale 1) with hysteresis so a node sitting
 * on a boundary does not flicker between two levels.
 *
 * distance <= [nearDistance] -> NEAR ; <= [midDistance] -> MID ; else FAR. When a previous level is given, a
 * boundary only counts as crossed once the distance is past it by the [hysteresis] fraction.
 *
 * Defaults fit the engine's camera: the overview sits at 700, `flyTo` parks at roughly 200-450 for a card.
 */
data class LodThresholds(
    val nearDistance: Double = 520.0,
    val midDistance: Double = 1100.0,
    val hysteresis: Double = 0.08,
) {
    init {
        require(nearDistance > 0.0 && midDistance > nearDistance) { "LodThresholds: need 0 < near < mid" }
        require(hysteresis >= 0.0 && hysteresis < 0.5) { "LodThresholds: hysteresis must be in [0, 0.5)" }
    }

    fun levelFor(distance: Double, previous: LodLevel? = null): LodLevel {
        if (distance.isNaN()) return previous ?: LodLevel.FAR
        if (previous == null || hysteresis == 0.0) return plain(distance)
        val lo = 1.0 - hysteresis
        val hi = 1.0 + hysteresis
        return when (previous) {
            LodLevel.NEAR -> when {
                distance <= nearDistance * hi -> LodLevel.NEAR
                distance <= midDistance * hi -> LodLevel.MID
                else -> LodLevel.FAR
            }
            LodLevel.MID -> when {
                distance < nearDistance * lo -> LodLevel.NEAR
                distance <= midDistance * hi -> LodLevel.MID
                else -> LodLevel.FAR
            }
            LodLevel.FAR -> when {
                distance < nearDistance * lo -> LodLevel.NEAR
                distance < midDistance * lo -> LodLevel.MID
                else -> LodLevel.FAR
            }
        }
    }

    private fun plain(distance: Double): LodLevel = when {
        distance <= nearDistance -> LodLevel.NEAR
        distance <= midDistance -> LodLevel.MID
        else -> LodLevel.FAR
    }
}

/** Pure camera maths shared by the camera controller and its tests. */
object ViewMath {
    fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Distance at which a [width] x [height] rectangle facing a perspective camera fills the viewport
     * ([fovRadians] is the VERTICAL field of view, [aspect] = viewport width / height), times [padding].
     * On a 375 px portrait phone the width term wins, which is what keeps a card inside the screen.
     */
    fun fitDistance(width: Double, height: Double, fovRadians: Double, aspect: Double, padding: Double = 1.25): Double {
        val halfTan = tan(fovRadians / 2.0)
        if (halfTan <= 0.0) return 0.0
        val forHeight = height / (2.0 * halfTan)
        val forWidth = width / (2.0 * halfTan * max(aspect, 1e-6))
        return max(forHeight, forWidth) * padding
    }

    /** World units covered by one CSS pixel at [distance] for a viewport [viewportHeightPx] tall. */
    fun worldPerPixel(distance: Double, fovRadians: Double, viewportHeightPx: Double): Double =
        2.0 * tan(fovRadians / 2.0) * distance / max(viewportHeightPx, 1.0)
}

/**
 * Two-finger gesture maths: feed the two touch points each move, get the zoom factor and the pan of the
 * midpoint since the previous call. Pure, so pinch behaviour is unit-tested without a touch screen.
 */
class PinchTracker {
    data class Step(
        /** > 1 = fingers moved apart (zoom in), < 1 = pinched together (zoom out). */
        val scale: Double,
        /** Movement of the midpoint in px since the last step. */
        val panX: Double,
        val panY: Double,
        /** Current midpoint in the same coordinates as the input (client px). */
        val centerX: Double,
        val centerY: Double,
    )

    private var lastSpan = 0.0
    private var lastCx = 0.0
    private var lastCy = 0.0
    var active: Boolean = false
        private set

    fun begin(x1: Double, y1: Double, x2: Double, y2: Double) {
        lastSpan = ViewMath.distance(x1, y1, 0.0, x2, y2, 0.0)
        lastCx = (x1 + x2) / 2.0
        lastCy = (y1 + y2) / 2.0
        active = true
    }

    fun move(x1: Double, y1: Double, x2: Double, y2: Double): Step {
        if (!active) begin(x1, y1, x2, y2)
        val span = ViewMath.distance(x1, y1, 0.0, x2, y2, 0.0)
        val cx = (x1 + x2) / 2.0
        val cy = (y1 + y2) / 2.0
        // fingers almost on top of each other give a wild ratio: ignore the scale part for that step
        val scale = if (lastSpan < MIN_SPAN_PX || span < MIN_SPAN_PX) 1.0 else (span / lastSpan).coerceIn(0.5, 2.0)
        val step = Step(scale, cx - lastCx, cy - lastCy, cx, cy)
        lastSpan = span
        lastCx = cx
        lastCy = cy
        return step
    }

    fun end() {
        active = false
    }

    companion object {
        const val MIN_SPAN_PX = 8.0
    }
}

/** Click / tap discrimination: a press that travels further than the slop is a drag, not a tap. */
object TapSlop {
    const val MOUSE_PX = 4.0
    const val TOUCH_PX = 12.0

    fun slopFor(pointerType: String?): Double = if (pointerType == "touch" || pointerType == "pen") TOUCH_PX else MOUSE_PX

    fun isTap(startX: Double, startY: Double, x: Double, y: Double, pointerType: String?): Boolean =
        ViewMath.distance(startX, startY, 0.0, x, y, 0.0) <= slopFor(pointerType)
}
