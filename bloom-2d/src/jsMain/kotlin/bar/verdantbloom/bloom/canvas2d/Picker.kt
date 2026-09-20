package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.RingIds

/**
 * Screen-space picking on the projected arrays of a [Scene2d]. No allocation.
 *
 * Rule:
 * 1. A DIRECT hit is a segment within [DIRECT_HIT_PX] of the pointer (the pointer is on the line).
 *    Among direct hits the NEAREST IN DEPTH wins - the ring painted on top is the one you clicked.
 * 2. Otherwise the ring with the smallest distance wins, if that distance is within the slop.
 * The ghost ring, disabled rings and rings faded below [MIN_ALPHA] are never picked.
 */
object Picker {
    const val DIRECT_HIT_PX: Double = 4.0
    const val MIN_ALPHA: Double = 0.05

    fun pick(scene: Scene2d, near: Double, x: Double, y: Double, slopPx: Double): Int {
        if (scene.frames == 0) return RingIds.NONE
        return pick(scene.sx, scene.sy, scene.sd, scene.segments, scene.ringPoints, scene.ringAlpha, near, x, y, slopPx)
    }

    /**
     * @param stride samples reserved per ring in [sx] / [sy] / [sd] (ring r starts at `r * stride`)
     * @param ringPoints how many of them are valid per ring (closed loop, first point not repeated)
     */
    fun pick(
        sx: FloatArray,
        sy: FloatArray,
        sd: FloatArray,
        stride: Int,
        ringPoints: IntArray,
        ringAlpha: DoubleArray,
        near: Double,
        x: Double,
        y: Double,
        slopPx: Double,
    ): Int {
        if (x != x || y != y) return RingIds.NONE
        val slop = if (slopPx > 0.0) slopPx else 0.0
        val slop2 = slop * slop
        val direct = if (slop < DIRECT_HIT_PX) slop else DIRECT_HIT_PX
        val direct2 = direct * direct

        var bestRing = RingIds.NONE
        var bestDist2 = Double.MAX_VALUE
        var directRing = RingIds.NONE
        var directDepth = Double.MAX_VALUE

        for (ring in 0 until RingIds.COUNT) {
            if (ring == RingIds.GHOST) continue
            val n = ringPoints[ring]
            if (n < 2 || ringAlpha[ring] < MIN_ALPHA) continue
            val base = ring * stride
            for (i in 0 until n) {
                val i0 = base + i
                val i1 = base + (if (i + 1 == n) 0 else i + 1)
                val d0 = sd[i0].toDouble()
                val d1 = sd[i1].toDouble()
                if (d0 <= near || d1 <= near) continue
                val ax = sx[i0].toDouble()
                val ay = sy[i0].toDouble()
                val ex = sx[i1] - ax
                val ey = sy[i1] - ay
                val len2 = ex * ex + ey * ey
                var t = if (len2 > 1e-12) ((x - ax) * ex + (y - ay) * ey) / len2 else 0.0
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                val qx = ax + ex * t - x
                val qy = ay + ey * t - y
                val dist2 = qx * qx + qy * qy
                if (dist2 > slop2) continue
                if (dist2 < bestDist2) {
                    bestDist2 = dist2
                    bestRing = ring
                }
                if (dist2 <= direct2) {
                    val depth = d0 + (d1 - d0) * t
                    if (depth < directDepth) {
                        directDepth = depth
                        directRing = ring
                    }
                }
            }
        }
        return if (directRing != RingIds.NONE) directRing else bestRing
    }
}
