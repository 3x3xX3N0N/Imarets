package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.RingIds
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Picking against the projected centre lines (the screen cache that the ribbon builder fills each frame).
 * Pure math over typed arrays: point-to-segment distance in CSS px, nearest ring within the slop wins, and
 * when two rings are about equally near the pointer the one in FRONT wins.
 */
object RingPicker {
    /** Two candidate distances closer than this (px) are a tie, settled by depth. */
    const val TIE_PX = 3.0

    /** Samples fainter than this cannot be picked (faded towards the pole, hidden ring). */
    const val MIN_PICK_ALPHA = 0.06f

    /**
     * @param screen x, y, depth per sample, [ringCount] * [segments] * 3
     * @param fade alpha per sample
     * @param enabled per ring
     * @param pickable per ring (ghost = false)
     * @param near depths <= near are behind the camera
     * @return ring slot or RingIds.NONE
     */
    fun pick(
        x: Double,
        y: Double,
        slopPx: Double,
        screen: FloatArray,
        fade: FloatArray,
        enabled: BooleanArray,
        pickable: BooleanArray,
        ringCount: Int,
        segments: Int,
        near: Double,
    ): Int {
        if (!(x.isFinite() && y.isFinite()) || slopPx < 0.0) return RingIds.NONE
        var best = RingIds.NONE
        var bestDist = Double.MAX_VALUE
        var bestDepth = Double.MAX_VALUE
        for (ring in 0 until ringCount) {
            if (!enabled[ring] || !pickable[ring]) continue
            var ringDist = Double.MAX_VALUE
            var ringDepth = Double.MAX_VALUE
            val base = ring * segments
            for (i in 0 until segments) {
                val j = if (i == segments - 1) 0 else i + 1
                val a = (base + i) * 3
                val b = (base + j) * 3
                val da = screen[a + 2].toDouble()
                val db = screen[b + 2].toDouble()
                if (da <= near || db <= near) continue
                if (fade[base + i] < MIN_PICK_ALPHA && fade[base + j] < MIN_PICK_ALPHA) continue
                val ax = screen[a].toDouble(); val ay = screen[a + 1].toDouble()
                val bx = screen[b].toDouble(); val by = screen[b + 1].toDouble()
                val ex = bx - ax; val ey = by - ay
                val len2 = ex * ex + ey * ey
                var t = if (len2 > 1e-12) ((x - ax) * ex + (y - ay) * ey) / len2 else 0.0
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                val px = ax + ex * t - x
                val py = ay + ey * t - y
                val d = sqrt(px * px + py * py)
                if (d < ringDist) {
                    ringDist = d
                    ringDepth = da + (db - da) * t
                }
            }
            if (ringDist > slopPx) continue
            val tie = abs(ringDist - bestDist) <= TIE_PX
            val better = if (best == RingIds.NONE) true else if (tie) ringDepth < bestDepth else ringDist < bestDist
            if (better) {
                best = ring
                bestDist = ringDist
                bestDepth = ringDepth
            }
        }
        return best
    }
}
