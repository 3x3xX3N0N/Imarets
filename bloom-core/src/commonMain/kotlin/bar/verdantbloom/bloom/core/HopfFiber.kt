package bar.verdantbloom.bloom.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One Hopf fiber as a great circle of S3: p(theta) = A cos(theta) + B sin(theta), A and B orthonormal
 * 4-vectors (x1, x2, x3, x4). Layout/sampling code, NOT the integrator, so trig is fine here.
 *
 * SPEC 3.1 (north chart, c != -1):
 *   p = 1/sqrt(2(1+c)) * ((1+c) cos t, a sin t - b cos t, a cos t + b sin t, (1+c) sin t)
 * which is z1 = x1 + i x4 = k (1+c) e^{it}, z2 = x3 + i x2 = k (a - ib) e^{it}.
 * Near c = -1 that chart degenerates, so the SAME fiber is written in the south chart
 *   z1 = k' (a + ib) e^{it}, z2 = k' (1-c) e^{it}, k' = 1/sqrt(2(1-c))
 * (same circle, different phase origin).
 *
 * The world rotation is the left quaternion product q * p with p read as (w, x, y, z) = (x1, x2, x3, x4):
 * an isometry of S3, so circles stay great circles and every pair stays linked exactly once.
 */
internal class HopfFiber {
    // un-rotated frame
    private val a = DoubleArray(4)
    private val b = DoubleArray(4)

    // rotated frame
    val ra = DoubleArray(4)
    val rb = DoubleArray(4)

    fun setBase(bx: Double, by: Double, bz: Double, southChartBelow: Double) {
        if (bz > southChartBelow) {
            val k = 1.0 / sqrt(2.0 * (1.0 + bz))
            a[0] = k * (1.0 + bz); a[1] = -k * by; a[2] = k * bx; a[3] = 0.0
            b[0] = 0.0; b[1] = k * bx; b[2] = k * by; b[3] = k * (1.0 + bz)
        } else {
            val k = 1.0 / sqrt(2.0 * (1.0 - bz))
            a[0] = k * bx; a[1] = 0.0; a[2] = k * (1.0 - bz); a[3] = k * by
            b[0] = -k * by; b[1] = k * (1.0 - bz); b[2] = 0.0; b[3] = k * bx
        }
    }

    fun rotate(qw: Double, qx: Double, qy: Double, qz: Double) {
        mul(qw, qx, qy, qz, a, ra)
        mul(qw, qx, qy, qz, b, rb)
    }

    /** Largest x4 on the circle: how close the fiber comes to the projection pole (0,0,0,1). */
    val maxX4: Double get() = sqrt(ra[3] * ra[3] + rb[3] * rb[3])

    /** Radius of the farthest projected point: sqrt((1+m)/(1-m)); infinite when the fiber hits the pole. */
    val farRadius: Double
        get() {
            val m = maxX4
            return if (m >= 1.0) Double.POSITIVE_INFINITY else sqrt((1.0 + m) / (1.0 - m))
        }

    /** Near-pole fade: 1 up to fadeStart, smoothstep down to 0 at maxRadius. */
    fun alpha(fadeStart: Double, maxRadius: Double): Double {
        val r = farRadius
        if (r <= fadeStart) return 1.0
        if (r >= maxRadius || maxRadius <= fadeStart) return 0.0
        val c = (r - fadeStart) / (maxRadius - fadeStart)
        return 1.0 - c * c * (3.0 - 2.0 * c)
    }

    /**
     * Theta where the label hangs: the innermost point of the projected ring (smallest x4), biased by
     * [bias] towards the point with the largest x1 so it stays well defined when the ring is a perfect
     * unit circle (x4 constant). Both terms name a geometric point of the circle, so the anchor does not
     * move when the world quaternion flips to its antipode (q and -q draw the same rings).
     */
    fun anchorTheta(bias: Double): Double {
        val vx = bias * ra[0] - ra[3]
        val vy = bias * rb[0] - rb[3]
        if (vx == 0.0 && vy == 0.0) return 0.0
        return atan2(vy, vx)
    }

    /** Point on S3 (rotated) at theta, into out[0..3]. */
    fun pointS3(theta: Double, out: DoubleArray) {
        val c = cos(theta)
        val s = sin(theta)
        for (i in 0 until 4) out[i] = ra[i] * c + rb[i] * s
    }

    /**
     * Stereographic projection from (0,0,0,1) with the blow-up clamp: points that would land outside
     * [maxRadius] are pulled back onto that sphere along their own direction. out[0..2].
     */
    fun project(p: DoubleArray, maxRadius: Double, out: DoubleArray) = projectInto(p[0], p[1], p[2], p[3], maxRadius, out)

    companion object {
        /** out = q * v (Hamilton product, scalar first). */
        fun mul(qw: Double, qx: Double, qy: Double, qz: Double, v: DoubleArray, out: DoubleArray) {
            val w = v[0]; val x = v[1]; val y = v[2]; val z = v[3]
            out[0] = qw * w - qx * x - qy * y - qz * z
            out[1] = qw * x + qx * w + qy * z - qz * y
            out[2] = qw * y - qx * z + qy * w + qz * x
            out[3] = qw * z + qx * y - qy * x + qz * w
        }

        fun projectInto(x1: Double, x2: Double, x3: Double, x4: Double, maxRadius: Double, out: DoubleArray) {
            val r2 = maxRadius * maxRadius
            val limit = (r2 - 1.0) / (r2 + 1.0) // x4 at which the projected radius equals maxRadius
            if (x4 <= limit) {
                val inv = 1.0 / (1.0 - x4)
                out[0] = x1 * inv; out[1] = x2 * inv; out[2] = x3 * inv
                return
            }
            val n = sqrt(x1 * x1 + x2 * x2 + x3 * x3)
            if (n < 1e-12) {
                // exactly at the pole: no direction. Any point of the clamp sphere will do (alpha is 0 here).
                out[0] = 0.0; out[1] = 0.0; out[2] = maxRadius
                return
            }
            val k = maxRadius / n
            out[0] = x1 * k; out[1] = x2 * k; out[2] = x3 * k
        }

        private val tables = HashMap<Int, DoubleArray>()

        /** cos/sin table for [segments] evenly spaced thetas: [cos0, sin0, cos1, sin1, ...]. */
        fun table(segments: Int): DoubleArray = tables.getOrPut(segments) {
            val t = DoubleArray(segments * 2)
            for (i in 0 until segments) {
                val th = 2.0 * PI * i / segments
                t[2 * i] = cos(th)
                t[2 * i + 1] = sin(th)
            }
            t
        }
    }
}
