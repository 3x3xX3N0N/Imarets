package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion / vector helpers for the 2D camera. `Quat` is scalar FIRST (w, x, y, z), as in bloom-api.
 * These allocate (immutable data classes) and are meant for camera code that runs a handful of times
 * per frame, NOT for the per-point hot path - that one lives in [Projector] and works on plain doubles.
 */
object QuatMath {
    /** Hamilton product a * b: rotate by b first, then by a. */
    fun mul(a: Quat, b: Quat): Quat = Quat(
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    )

    fun conj(q: Quat): Quat = Quat(q.w, -q.x, -q.y, -q.z)

    fun dot(a: Quat, b: Quat): Double = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z

    fun length(q: Quat): Double = sqrt(dot(q, q))

    /** Unit quaternion in the direction of [q]; identity when [q] is (almost) zero. */
    fun normalize(q: Quat): Quat {
        val l = length(q)
        if (l < 1e-12) return Quat.IDENTITY
        val k = 1.0 / l
        return Quat(q.w * k, q.x * k, q.y * k, q.z * k)
    }

    /** Rotation of [angle] radians about the (not necessarily unit) axis (ax, ay, az). */
    fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double): Quat {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l < 1e-12) return Quat.IDENTITY
        val s = sin(angle / 2) / l
        return Quat(cos(angle / 2), ax * s, ay * s, az * s)
    }

    /** q * v * q^-1 for a unit [q]. */
    fun rotate(q: Quat, v: Vec3): Vec3 {
        // t = 2 * (q.xyz cross v); v' = v + w * t + q.xyz cross t
        val tx = 2 * (q.y * v.z - q.z * v.y)
        val ty = 2 * (q.z * v.x - q.x * v.z)
        val tz = 2 * (q.x * v.y - q.y * v.x)
        return Vec3(
            v.x + q.w * tx + (q.y * tz - q.z * ty),
            v.y + q.w * ty + (q.z * tx - q.x * tz),
            v.z + q.w * tz + (q.x * ty - q.y * tx),
        )
    }

    /** Spherical interpolation along the shorter arc, t in 0..1. */
    fun slerp(a: Quat, b: Quat, t: Double): Quat {
        var d = dot(a, b)
        var bw = b.w
        var bx = b.x
        var by = b.y
        var bz = b.z
        if (d < 0) {
            d = -d; bw = -bw; bx = -bx; by = -by; bz = -bz
        }
        if (d > 0.9995) {
            return normalize(Quat(a.w + (bw - a.w) * t, a.x + (bx - a.x) * t, a.y + (by - a.y) * t, a.z + (bz - a.z) * t))
        }
        val theta = acos(d.coerceIn(-1.0, 1.0))
        val s = sin(theta)
        val ka = sin((1 - t) * theta) / s
        val kb = sin(t * theta) / s
        return Quat(a.w * ka + bw * kb, a.x * ka + bx * kb, a.y * ka + by * kb, a.z * ka + bz * kb)
    }

    /** Shortest-arc rotation taking direction [u] to direction [v] (neither needs to be unit length). */
    fun fromTo(u: Vec3, v: Vec3): Quat {
        val lu = length(u)
        val lv = length(v)
        if (lu < 1e-12 || lv < 1e-12) return Quat.IDENTITY
        val ux = u.x / lu
        val uy = u.y / lu
        val uz = u.z / lu
        val vx = v.x / lv
        val vy = v.y / lv
        val vz = v.z / lv
        val d = ux * vx + uy * vy + uz * vz
        if (d < -0.999999) {
            // opposite: any axis perpendicular to u, half a turn
            return if (abs(ux) < 0.9) {
                axisAngle(0.0, uz, -uy, kotlin.math.PI) // u cross X
            } else {
                axisAngle(-uz, 0.0, ux, kotlin.math.PI) // u cross Y
            }
        }
        return normalize(Quat(1 + d, uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx))
    }

    fun length(v: Vec3): Double = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 =
        Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
}
