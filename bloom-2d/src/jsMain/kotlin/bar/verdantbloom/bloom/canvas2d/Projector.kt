package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import kotlin.math.PI
import kotlin.math.tan

/**
 * Perspective projection of bloom space to CSS pixels for one [BloomView], with the SAME numbers the
 * three.js rungs use: camera at `target + orbit * (0, 0, distance)`, looking at `target`,
 * up = `orbit * (0, 1, 0)`, vertical field of view `fovDegrees`.
 *
 * Mutable and allocation free on purpose: [set] once per frame, then [project] / [projectArray] write
 * into fields / caller-owned arrays. Screen x grows right, y grows DOWN, origin = host top-left.
 */
class Projector {
    // camera basis in bloom space (rows of the view rotation): right, up, back (back points AT the viewer)
    var rx = 1.0; private set
    var ry = 0.0; private set
    var rz = 0.0; private set
    var ux = 0.0; private set
    var uy = 1.0; private set
    var uz = 0.0; private set
    var bx = 0.0; private set
    var by = 0.0; private set
    var bz = 1.0; private set

    var targetX = 0.0; private set
    var targetY = 0.0; private set
    var targetZ = 0.0; private set
    var distance = 9.0; private set

    /** Focal length in CSS px: a point at depth d is drawn at `focal / d` px per bloom unit. */
    var focal = 1.0; private set
    var width = 0.0; private set
    var height = 0.0; private set

    /** Anything at or nearer than this camera-space depth is culled. */
    val near: Double = NEAR

    // results of the last project() call
    var outX = 0.0; private set
    var outY = 0.0; private set

    /** Camera-space depth of the last projected point: distance along the view axis, bigger = farther. */
    var outDepth = 0.0; private set

    /** CSS px per bloom unit at the last projected point's depth (0 when culled). */
    var outScale = 0.0; private set

    /** CSS px per bloom unit on the plane through the target (the [BloomView] contract number). */
    val pxPerUnitAtTarget: Double get() = focal / distance

    fun set(view: BloomView, cssWidth: Double, cssHeight: Double) {
        val q = view.orbit
        // normalise defensively: a slightly denormalised orbit must not shear the picture
        val n = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
        val s = if (n > 1e-20) 2.0 / n else 0.0
        val w = q.w
        val x = q.x
        val y = q.y
        val z = q.z
        rx = 1 - s * (y * y + z * z); ry = s * (x * y + w * z); rz = s * (x * z - w * y)
        ux = s * (x * y - w * z); uy = 1 - s * (x * x + z * z); uz = s * (y * z + w * x)
        bx = s * (x * z + w * y); by = s * (y * z - w * x); bz = 1 - s * (x * x + y * y)
        targetX = view.target.x
        targetY = view.target.y
        targetZ = view.target.z
        distance = if (view.distance > MIN_DISTANCE) view.distance else MIN_DISTANCE
        width = cssWidth
        height = cssHeight
        val fov = view.fovDegrees.coerceIn(1.0, 170.0)
        focal = cssHeight / (2.0 * tan(fov * PI / 360.0))
    }

    /**
     * Project one bloom-space point into [outX], [outY], [outDepth], [outScale].
     * @return false when the point is at or behind the near plane (outX / outY are then meaningless).
     */
    fun project(x: Double, y: Double, z: Double): Boolean {
        val px = x - targetX
        val py = y - targetY
        val pz = z - targetZ
        val depth = distance - (px * bx + py * by + pz * bz)
        outDepth = depth
        if (!(depth > NEAR)) { // also true for NaN
            outScale = 0.0
            return false
        }
        val k = focal / depth
        outScale = k
        outX = width * 0.5 + (px * rx + py * ry + pz * rz) * k
        outY = height * 0.5 - (px * ux + py * uy + pz * uz) * k
        return true
    }

    /**
     * Project [count] xyz triples from [src] (starting at float index [srcOffset]) into the parallel
     * arrays [sx] / [sy] / [sd] starting at [dstOffset]. Culled points get `sd <= near` and sx = sy = 0.
     */
    fun projectArray(src: FloatArray, srcOffset: Int, count: Int, sx: FloatArray, sy: FloatArray, sd: FloatArray, dstOffset: Int) {
        val hw = width * 0.5
        val hh = height * 0.5
        var s = srcOffset
        for (i in 0 until count) {
            val px = src[s] - targetX
            val py = src[s + 1] - targetY
            val pz = src[s + 2] - targetZ
            s += 3
            val depth = distance - (px * bx + py * by + pz * bz)
            val o = dstOffset + i
            if (!(depth > NEAR)) { // behind the near plane, or NaN from a broken sample: culled, never NaN in sd
                if (depth == depth) sd[o] = depth.toFloat() else sd[o] = 0f
                sx[o] = 0f
                sy[o] = 0f
            } else {
                val k = focal / depth
                sd[o] = depth.toFloat()
                sx[o] = (hw + (px * rx + py * ry + pz * rz) * k).toFloat()
                sy[o] = (hh - (px * ux + py * uy + pz * uz) * k).toFloat()
            }
        }
    }

    companion object {
        const val NEAR: Double = 0.05
        const val MIN_DISTANCE: Double = 0.1
    }
}
