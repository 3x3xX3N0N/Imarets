package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Perspective camera math in plain Kotlin (no three, no DOM): bloom space -> CSS px relative to the host.
 * The three camera of [ThreeBloomRenderer] is set from the same numbers, so what this class projects is
 * what the GPU draws. Used for ribbon widths (px -> world), picking, card anchors and [project].
 *
 * No allocation: [project] writes its result into the `out*` fields.
 */
class Projector {
    var camX = 0.0; private set
    var camY = 0.0; private set
    var camZ = 9.0; private set

    var rightX = 1.0; private set
    var rightY = 0.0; private set
    var rightZ = 0.0; private set
    var upX = 0.0; private set
    var upY = 1.0; private set
    var upZ = 0.0; private set

    /** Unit vector from the camera INTO the scene. */
    var fwdX = 0.0; private set
    var fwdY = 0.0; private set
    var fwdZ = -1.0; private set

    var tanHalfFov = tan(25.0 * PI / 180.0); private set
    var aspect = 1.0; private set

    /** Viewport in CSS px. */
    var width = 1.0; private set
    var height = 1.0; private set

    /** View-space depth of the look-at target (= BloomView.distance in standalone mode). */
    var focusDepth = 9.0; private set

    /** Depths below this count as "behind the camera". */
    var near = DEFAULT_NEAR

    // ---- result of the last project() call
    var outX = 0.0; private set
    var outY = 0.0; private set
    var outDepth = 0.0; private set
    var outPxPerUnit = 0.0; private set

    fun setViewport(cssWidth: Double, cssHeight: Double) {
        width = max(1.0, cssWidth)
        height = max(1.0, cssHeight)
        aspect = width / height
    }

    /** Camera at target + orbit * (0, 0, distance), up = orbit * (0, 1, 0) (bloom-api BloomView). */
    fun setFromView(view: BloomView) {
        val q = view.orbit
        var qw = q.w; var qx = q.x; var qy = q.y; var qz = q.z
        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (n > 1e-12 && n.isFinite()) {
            qw /= n; qx /= n; qy /= n; qz /= n
        } else {
            qw = 1.0; qx = 0.0; qy = 0.0; qz = 0.0
        }
        // columns of the rotation matrix of q
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        rightX = 1 - 2 * (yy + zz); rightY = 2 * (xy + wz); rightZ = 2 * (xz - wy)
        upX = 2 * (xy - wz); upY = 1 - 2 * (xx + zz); upZ = 2 * (yz + wx)
        val backX = 2 * (xz + wy); val backY = 2 * (yz - wx); val backZ = 1 - 2 * (xx + yy)
        fwdX = -backX; fwdY = -backY; fwdZ = -backZ
        val d = if (view.distance.isFinite()) max(MIN_DISTANCE, view.distance) else 9.0
        camX = view.target.x + backX * d
        camY = view.target.y + backY * d
        camZ = view.target.z + backZ * d
        focusDepth = d
        val fov = if (view.fovDegrees.isFinite()) view.fovDegrees.coerceIn(5.0, 150.0) else 50.0
        tanHalfFov = tan(fov * PI / 360.0)
    }

    /**
     * Embedded mode: camera pose given directly, already expressed in bloom space (the host transforms its
     * camera into the bloom group's local frame). Vectors must be unit length and orthogonal.
     */
    fun setFromBasis(
        cx: Double, cy: Double, cz: Double,
        rx: Double, ry: Double, rz: Double,
        ux: Double, uy: Double, uz: Double,
        fx: Double, fy: Double, fz: Double,
        tanHalfFovY: Double,
        focus: Double,
    ) {
        camX = cx; camY = cy; camZ = cz
        rightX = rx; rightY = ry; rightZ = rz
        upX = ux; upY = uy; upZ = uz
        fwdX = fx; fwdY = fy; fwdZ = fz
        tanHalfFov = max(1e-4, tanHalfFovY)
        focusDepth = max(MIN_DISTANCE, focus)
    }

    /** View-space depth (distance along the forward axis) of a bloom-space point. */
    fun depthOf(x: Double, y: Double, z: Double): Double =
        (x - camX) * fwdX + (y - camY) * fwdY + (z - camZ) * fwdZ

    /** Bloom units covered by one CSS px at view-space [depth]. */
    fun worldPerPx(depth: Double): Double = 2.0 * tanHalfFov * max(depth, near) / height

    /** CSS px per bloom unit at view-space [depth]. */
    fun pxPerUnit(depth: Double): Double = height / (2.0 * tanHalfFov * max(depth, near))

    /**
     * Project a bloom-space point. Results land in [outX], [outY] (CSS px, origin top-left of the host),
     * [outDepth], [outPxPerUnit]. Returns false when the point is behind the near plane; the outputs are
     * then still finite (computed at the near depth) but meaningless for placement.
     */
    fun project(x: Double, y: Double, z: Double): Boolean {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val depth = dx * fwdX + dy * fwdY + dz * fwdZ
        val d = max(depth, near)
        val xc = dx * rightX + dy * rightY + dz * rightZ
        val yc = dx * upX + dy * upY + dz * upZ
        val k = 1.0 / (d * tanHalfFov)
        outX = (xc * k / aspect * 0.5 + 0.5) * width
        outY = (0.5 - yc * k * 0.5) * height
        outDepth = depth
        outPxPerUnit = height * 0.5 * k
        return depth > near
    }

    companion object {
        const val DEFAULT_NEAR = 0.02
        const val MIN_DISTANCE = 0.05
    }
}
