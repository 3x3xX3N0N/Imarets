package bar.verdantbloom.bloom.canvas2d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The bell (SPEC 3.3): each [kick] launches a spherical wave packet from the bloom centre. While a packet
 * passes a ring point, the point is pushed along its radius: `p' = p * (1 + displacement(|p|))`.
 * Up to [capacity] packets overlap; a new kick when all slots are busy replaces the oldest.
 * Fixed arrays, no allocation after construction.
 */
class RippleField(private val capacity: Int = 4) {
    private val age = DoubleArray(capacity)
    private val amplitude = DoubleArray(capacity)
    private val live = BooleanArray(capacity)

    /** True while at least one packet is still travelling. */
    var active: Boolean = false
        private set

    /** 0..1, how loud the bell still rings; the painter adds it to the glow. */
    var glow: Double = 0.0
        private set

    /** Launch a packet. [magnitude] 0..1 (values outside are clamped); 0 is ignored. */
    fun kick(magnitude: Double) {
        val m = if (magnitude != magnitude) 0.0 else magnitude.coerceIn(0.0, 1.0)
        if (m <= 0.0) return
        var slot = -1
        var oldest = 0
        for (i in 0 until capacity) {
            if (!live[i]) {
                slot = i
                break
            }
            if (age[i] > age[oldest]) oldest = i
        }
        if (slot < 0) slot = oldest
        live[slot] = true
        age[slot] = 0.0
        amplitude[slot] = MAX_AMPLITUDE * (0.25 + 0.75 * m)
        active = true
        refreshGlow()
    }

    /** Advance every packet by [dtSeconds] (negative / NaN / huge steps are clamped to 0..0.1). */
    fun advance(dtSeconds: Double) {
        if (!active) return
        val dt = if (dtSeconds != dtSeconds) 0.0 else dtSeconds.coerceIn(0.0, 0.1)
        var any = false
        for (i in 0 until capacity) {
            if (!live[i]) continue
            age[i] += dt
            if (age[i] > LIFE_SECONDS) live[i] = false else any = true
        }
        active = any
        refreshGlow()
    }

    private fun refreshGlow() {
        var g = 0.0
        for (i in 0 until capacity) if (live[i]) g += (amplitude[i] / MAX_AMPLITUDE) * exp(-age[i] / DECAY_SECONDS)
        glow = if (g > 1.0) 1.0 else g
    }

    /** Relative radial displacement at distance [r] from the centre (about -0.12..0.12). */
    fun displacement(r: Double): Double {
        if (!active) return 0.0
        var d = 0.0
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val u = (r - SPEED * age[i]) / SIGMA
            if (abs(u) > 3.0) continue
            d += amplitude[i] * exp(-age[i] / DECAY_SECONDS) * exp(-u * u) * cos(2.0 * PI * (r - SPEED * age[i]) / WAVELENGTH)
        }
        return d
    }

    /** Displace [count] xyz triples of [pts] in place, starting at float index [offset]. */
    fun displace(pts: FloatArray, offset: Int, count: Int) {
        if (!active) return
        var o = offset
        for (i in 0 until count) {
            val x = pts[o].toDouble()
            val y = pts[o + 1].toDouble()
            val z = pts[o + 2].toDouble()
            val k = 1.0 + displacement(sqrt(x * x + y * y + z * z))
            pts[o] = (x * k).toFloat()
            pts[o + 1] = (y * k).toFloat()
            pts[o + 2] = (z * k).toFloat()
            o += 3
        }
    }

    fun clear() {
        for (i in 0 until capacity) live[i] = false
        active = false
        glow = 0.0
    }

    companion object {
        /** Bloom units per second the wave front travels. */
        const val SPEED: Double = 3.2
        const val SIGMA: Double = 0.9
        const val WAVELENGTH: Double = 1.1
        const val DECAY_SECONDS: Double = 1.4
        const val LIFE_SECONDS: Double = 4.5
        const val MAX_AMPLITUDE: Double = 0.12
    }
}
