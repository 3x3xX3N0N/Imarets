package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.SpinDisc
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Geometry of one spin disc, taken from the reference page:
 *
 *     <g id="seed" stroke-width=".25">
 *       <circle r="12.8" fill="blue" stroke="red"/>
 *       <path fill="yellow" d="M0 0 L r 0  r*cos60 r*sin60 z"/>   and the same at rotate(120), rotate(240)
 *
 * The original calls `generateWedgeString` (NOT the arc variant), so every "wedge" is a TRIANGLE:
 * centre, rim point at the start angle, rim point 60 degrees on, joined by a straight chord.
 * That chord is what makes the figure flicker, so it is kept. Everything here is in units of the radius.
 */
object SpinDiscGeometry {
    /** Original disc radius in its own px units. */
    const val ORIGINAL_RADIUS: Double = 12.8

    /** Original stroke width (.25) as a fraction of the radius. */
    const val STROKE_FRACTION: Double = 0.25 / ORIGINAL_RADIUS

    const val WEDGE_COUNT: Int = 3
    const val WEDGE_SWEEP_DEGREES: Double = 60.0
    const val WEDGE_STEP_DEGREES: Double = 120.0

    private val HALF_SQRT3: Double = sqrt(3.0) / 2.0

    /**
     * Unit-circle rim points of the three wedges at rotation 0, as cos/sin pairs in drawing order:
     * wedge k uses entries 2k (start, at 120k degrees) and 2k+1 (end, at 120k + 60 degrees).
     */
    val RIM_COS: DoubleArray = doubleArrayOf(1.0, 0.5, -0.5, -1.0, -0.5, 0.5)
    val RIM_SIN: DoubleArray = doubleArrayOf(0.0, HALF_SQRT3, HALF_SQRT3, 0.0, -HALF_SQRT3, -HALF_SQRT3)

    /** Rotation angle in radians at [unixSeconds], reduced to (-2pi, 2pi). See [SpinDisc]. */
    fun angle(disc: SpinDisc, unixSeconds: Double): Double {
        val turns = (unixSeconds - disc.bornAtUnixSeconds) / disc.periodSeconds
        val frac = turns - kotlin.math.floor(turns)
        return (disc.phase + 2.0 * PI * frac) % (2.0 * PI)
    }

    /**
     * Rim point [index] (0..5) of a disc of [radius] rotated by an angle whose cosine / sine are
     * [cosA] / [sinA]. Writes nothing, returns x; use [rimY] for y. (Two functions instead of a pair: no allocation.)
     */
    fun rimX(index: Int, radius: Double, cosA: Double, sinA: Double): Double =
        radius * (RIM_COS[index] * cosA - RIM_SIN[index] * sinA)

    fun rimY(index: Int, radius: Double, cosA: Double, sinA: Double): Double =
        radius * (RIM_COS[index] * sinA + RIM_SIN[index] * cosA)
}
