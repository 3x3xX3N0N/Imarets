package bar.verdantbloom.bloom.api

/** Immutable 3-vector. Bloom space is right-handed; unit = radius of S3 before projection. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * Unit quaternion, scalar FIRST (w, x, y, z).
 * NOTE: three.js `Quaternion` is scalar LAST (x, y, z, w) - convert at the renderer boundary.
 * Used both as an S3 rotation of the whole fibration (world quaternion) and as an ordinary
 * R3 orientation (spin disc facing, view orbit).
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)
    }
}

/** One point of a Lorenz trajectory. */
data class LorenzState(val x: Double, val y: Double, val z: Double) {
    /** Which lobe the trajectory is on: +1 (x >= 0) or -1. A change of this sign is a "lobe switch". */
    val lobe: Int get() = if (x >= 0.0) 1 else -1
}
