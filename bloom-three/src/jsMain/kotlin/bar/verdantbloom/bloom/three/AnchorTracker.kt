package bar.verdantbloom.bloom.three

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/** Where cardAnchor() hangs a ring's card. */
enum class AnchorMode {
    /**
     * A point on the ring that faces the camera, is on screen and is not faded out, with a preference for
     * the world's own anchor (world.ringAnchor). Hysteresis + a glide along the ring keep it stable.
     */
    CAMERA_FACING,

    /** Exactly world.ringAnchor(ringId), whatever the camera does (what bloom-api describes literally). */
    WORLD,
}

/**
 * Chooses and tracks ONE sample position per ring for the card anchor. Pure math over the screen cache.
 *
 * Score per sample = nearness to the camera + closeness (along the ring) to the world's home anchor
 * + on-screen + not-faded. The target only changes when another sample beats the current target by
 * [HYSTERESIS]; the anchor then glides along the ring towards it, it never jumps (except the first frame).
 */
class AnchorTracker(private val ringCount: Int, private val segments: Int) {
    /** Fractional sample index per ring, 0 <= pos < segments. */
    val position = DoubleArray(ringCount)
    private val target = IntArray(ringCount)
    private val started = BooleanArray(ringCount)

    fun reset(ring: Int) {
        started[ring] = false
    }

    fun targetOf(ring: Int): Int = target[ring]

    /**
     * @param home sample index nearest world.ringAnchor(ring)
     * @param marginPx samples closer than this to the viewport edge count as off screen
     */
    fun update(
        ring: Int,
        home: Int,
        screen: FloatArray,
        fade: FloatArray,
        width: Double,
        height: Double,
        near: Double,
        marginPx: Double,
        dtSeconds: Double,
    ) {
        val base = ring * segments
        var dMin = Double.MAX_VALUE
        var dMax = -Double.MAX_VALUE
        var fMax = 0.0
        for (i in 0 until segments) {
            val d = screen[(base + i) * 3 + 2].toDouble()
            if (d < dMin) dMin = d
            if (d > dMax) dMax = d
            val f = fade[base + i].toDouble()
            if (f > fMax) fMax = f
        }
        val span = if (dMax - dMin > 1e-9) dMax - dMin else 1.0
        val fNorm = if (fMax > 1e-6) 1.0 / fMax else 0.0

        var best = target[ring].coerceIn(0, segments - 1)
        var bestScore = -Double.MAX_VALUE
        var currentScore = -Double.MAX_VALUE
        for (i in 0 until segments) {
            val s = (base + i) * 3
            val depth = screen[s + 2].toDouble()
            var score = W_FACING * (dMax - depth) / span
            score += W_HOME * (0.5 + 0.5 * cos(2.0 * PI * (i - home) / segments))
            val sx = screen[s].toDouble(); val sy = screen[s + 1].toDouble()
            val onScreen = depth > near && sx >= marginPx && sx <= width - marginPx && sy >= marginPx && sy <= height - marginPx
            if (onScreen) score += W_ONSCREEN
            score += W_FADE * fade[base + i] * fNorm
            if (score > bestScore) {
                bestScore = score
                best = i
            }
            if (i == target[ring]) currentScore = score
        }

        if (!started[ring]) {
            started[ring] = true
            target[ring] = best
            position[ring] = best.toDouble()
            return
        }
        if (bestScore > currentScore + HYSTERESIS) target[ring] = best

        // glide the shorter way round
        var delta = target[ring] - position[ring]
        val half = segments / 2.0
        if (delta > half) delta -= segments else if (delta < -half) delta += segments
        val step = GLIDE_TURNS_PER_SECOND * segments * (if (dtSeconds.isFinite()) dtSeconds.coerceIn(0.0, 0.1) else 0.0)
        val move = if (abs(delta) <= step) delta else if (delta > 0) step else -step
        var p = position[ring] + move
        p -= floor(p / segments) * segments
        if (p >= segments) p = 0.0
        position[ring] = p
    }

    /** Interpolated value of channel [channel] (0..stride-1) of a per-sample array at this ring's anchor. */
    fun sample(ring: Int, data: FloatArray, stride: Int, channel: Int): Double {
        val p = position[ring]
        val i0 = min(segments - 1, p.toInt())
        val i1 = if (i0 == segments - 1) 0 else i0 + 1
        val t = p - i0
        val a = data[(ring * segments + i0) * stride + channel].toDouble()
        val b = data[(ring * segments + i1) * stride + channel].toDouble()
        return a + (b - a) * t
    }

    companion object {
        const val W_FACING = 1.0
        const val W_HOME = 0.6
        const val W_ONSCREEN = 0.8
        const val W_FADE = 0.5
        const val HYSTERESIS = 0.25
        const val GLIDE_TURNS_PER_SECOND = 0.6
    }
}
