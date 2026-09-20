package bar.verdantbloom.bloom.three

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How one ring is drawn this frame. Mutable and reused: one instance per ring slot, no per-frame allocation. */
class RingStyle {
    /** Base colour, LINEAR rgb. */
    var r = 1.0
    var g = 1.0
    var b = 1.0

    /** "Hot" colour (LINEAR) the base mixes towards with [heat] and with the ripple pulse. */
    var hotR = 1.0
    var hotG = 1.0
    var hotB = 1.0

    /** 0..1 mix of base -> hot (highlight). */
    var heat = 0.0

    /** Half width of the solid thread, CSS px. */
    var coreHalfPx = 1.15

    /** Half width of the whole ribbon (thread + glow), CSS px. Always >= coreHalfPx + 2. */
    var haloHalfPx = 8.0

    /** Multiplies the ring's alpha (ghost = faint). */
    var alphaMul = 1.0

    /** Multiplies only the glow of this ring (visitor = brighter). */
    var haloBoost = 1.0

    /** Dashes per turn, 0 = solid. Must be an integer value so the pattern closes. */
    var dashCount = 0.0
}

/**
 * CPU side of the ring geometry. ALL rings live in one set of typed arrays which three wraps zero-copy
 * (a Kotlin FloatArray is a Float32Array), so a frame is: sample -> [buildRing] -> flag needsUpdate.
 *
 * TECHNIQUE: each ring is a camera-facing triangle ribbon built here on the CPU. Widths are given in CSS
 * px and converted to bloom units per sample with the projector, so a thread keeps its thickness at any
 * zoom. This is plain indexed triangles, which is why it looks the same on the WebGPU and the WebGL2
 * backend of WebGPURenderer (no line primitives, no `linewidth`, no instancing).
 *
 * Layout per ring: (segments + 1) vertex PAIRS. The extra pair repeats sample 0 with arc = 1 so that the
 * arc coordinate (dashes) has no seam.
 *  - positions  xyz
 *  - shape      across (-1 | +1), arc 0..1, ribbon half width px, thread half width px
 *  - color      linear rgb + alpha (ring alpha x distance fades)
 *  - trim       dashes per turn, glow boost
 */
class RibbonBuffers(val ringCount: Int, val segments: Int) {
    init {
        require(ringCount > 0) { "ringCount" }
        require(segments >= MIN_SEGMENTS) { "segments must be >= $MIN_SEGMENTS" }
    }

    val pairsPerRing: Int = segments + 1
    val verticesPerRing: Int = pairsPerRing * 2
    val vertexCount: Int = ringCount * verticesPerRing
    val indicesPerRing: Int = segments * 6
    val indexCount: Int = ringCount * indicesPerRing

    /** Centre lines, xyz per sample. world.sampleRing writes straight into this at [centerOffset]. */
    val centers = FloatArray(ringCount * segments * 3)

    val positions = FloatArray(vertexCount * 3)
    val shape = FloatArray(vertexCount * 4)
    val color = FloatArray(vertexCount * 4)
    val trim = FloatArray(vertexCount * 2)
    val indices = IntArray(indexCount)

    /** Per centre sample: screen x, screen y (CSS px), view depth. Filled by [buildRing]; read by pick + anchors. */
    val screen = FloatArray(ringCount * segments * 3)

    /** Per centre sample: final alpha 0..1 (0 for a hidden ring). */
    val fade = FloatArray(ringCount * segments)

    /** Per ring: drawn this frame. */
    val enabled = BooleanArray(ringCount)

    private val pulse = DoubleArray(segments)

    init {
        for (ring in 0 until ringCount) {
            val v0 = ring * verticesPerRing
            for (p in 0 until pairsPerRing) {
                val arc = p.toFloat() / segments
                for (s in 0..1) {
                    val v = v0 + p * 2 + s
                    shape[v * 4] = if (s == 0) -1f else 1f
                    shape[v * 4 + 1] = arc
                }
            }
            val i0 = ring * indicesPerRing
            for (seg in 0 until segments) {
                val a = v0 + seg * 2
                val o = i0 + seg * 6
                indices[o] = a
                indices[o + 1] = a + 1
                indices[o + 2] = a + 2
                indices[o + 3] = a + 1
                indices[o + 4] = a + 3
                indices[o + 5] = a + 2
            }
        }
    }

    fun centerOffset(ring: Int): Int = ring * segments * 3

    /** Index count that draws rings 0 until [rings] (used to leave the last slot, the ghost, out of the draw range). */
    fun indexCountFor(rings: Int): Int = rings.coerceIn(0, ringCount) * indicesPerRing

    /** Radius (CSS px) of the circle through three consecutive projected samples; huge when straight or unknown. */
    private fun screenTurnRadius(ring: Int, ip: Int, i: Int, inx: Int, near: Double): Double {
        val a = (ring * segments + ip) * 3
        val b = (ring * segments + i) * 3
        val c = (ring * segments + inx) * 3
        if (screen[a + 2] <= near || screen[b + 2] <= near || screen[c + 2] <= near) return STRAIGHT
        val ux = screen[b] - screen[a].toDouble(); val uy = screen[b + 1] - screen[a + 1].toDouble()
        val wx = screen[c] - screen[b].toDouble(); val wy = screen[c + 1] - screen[b + 1].toDouble()
        val cross = abs(ux * wy - uy * wx)
        val dot = ux * wx + uy * wy
        val turn = atan2(cross, dot) // 0 = straight on, pi = doubled back
        if (turn < 1e-6) return STRAIGHT
        val step = 0.5 * (sqrt(ux * ux + uy * uy) + sqrt(wx * wx + wy * wy))
        return step / turn
    }

    /** Collapse a ring to nothing (alpha 0, degenerate triangles) and mark it unpickable. */
    fun hideRing(ring: Int) {
        enabled[ring] = false
        val v0 = ring * verticesPerRing
        for (v in v0 until v0 + verticesPerRing) {
            positions[v * 3] = 0f; positions[v * 3 + 1] = 0f; positions[v * 3 + 2] = 0f
            color[v * 4 + 3] = 0f
        }
        val f0 = ring * segments
        for (i in f0 until f0 + segments) fade[i] = 0f
    }

    /**
     * The world wrote [written] points (normally == segments). Fewer than 3 cannot make a loop -> false.
     * A short count is stretched over the full slot by nearest-neighbour so the fixed index buffer stays valid.
     */
    fun normalizeCount(ring: Int, written: Int): Boolean {
        if (written < 3) return false
        if (written >= segments) return true
        val c0 = centerOffset(ring)
        for (i in segments - 1 downTo 0) {
            val s = i * written / segments
            centers[c0 + i * 3] = centers[c0 + s * 3]
            centers[c0 + i * 3 + 1] = centers[c0 + s * 3 + 1]
            centers[c0 + i * 3 + 2] = centers[c0 + s * 3 + 2]
        }
        return true
    }

    /**
     * Turn the centre line of [ring] into ribbon vertices, screen cache and fades.
     *
     * Pole handling: non-finite samples are replaced by the previous good one and get alpha 0; every sample
     * is clamped to [maxRadius]; alpha falls from 1 at [fadeStartRadius] to 0 at [maxRadius], so the part of
     * a fiber that runs out towards the projection pole thins into nothing instead of drawing a huge arc.
     *
     * @param ringAlpha world.ringAlpha(id)
     * @param ripple null or inactive = no ripple work at all
     * @param rippleOrder which ring the wave reaches first (0) ... last
     * @param rippleDisplacement radial displacement per unit of wave, e.g. 0.045 (smaller under reduced motion)
     */
    fun buildRing(
        ring: Int,
        style: RingStyle,
        ringAlpha: Double,
        proj: Projector,
        ripple: RippleField?,
        rippleOrder: Int,
        rippleDisplacement: Double,
        fadeStartRadius: Double,
        maxRadius: Double,
    ) {
        val n = segments
        val c0 = centerOffset(ring)
        val rippling = ripple != null && ripple.active
        val baseAlpha = (if (ringAlpha.isFinite()) ringAlpha.coerceIn(0.0, 1.0) else 0.0) * style.alphaMul
        if (baseAlpha <= ALPHA_EPS) {
            hideRing(ring)
            return
        }
        enabled[ring] = true

        // depth cue: samples nearer than the focus are full strength, the far side of the bloom is dimmer
        val cueNear = proj.focusDepth - fadeStartRadius
        val cueSpan = max(1e-6, 2.0 * fadeStartRadius)
        val fadeSpan = max(1e-6, maxRadius - fadeStartRadius)

        // ---- pass 1: sanitise, clamp, ripple, project, fade
        var lastX = 0.0; var lastY = 0.0; var lastZ = maxRadius
        for (i in 0 until n) {
            var x = centers[c0 + i * 3].toDouble()
            var y = centers[c0 + i * 3 + 1].toDouble()
            var z = centers[c0 + i * 3 + 2].toDouble()
            var good = 1.0
            if (!(x.isFinite() && y.isFinite() && z.isFinite())) {
                x = lastX; y = lastY; z = lastZ; good = 0.0
            }
            var rad = sqrt(x * x + y * y + z * z)
            if (rad > maxRadius) {
                val k = maxRadius / rad
                x *= k; y *= k; z *= k; rad = maxRadius
            }
            lastX = x; lastY = y; lastZ = z

            var p = 0.0
            if (rippling) {
                ripple.sample(i.toDouble() / n, rippleOrder)
                val k = 1.0 + rippleDisplacement * ripple.outWave
                x *= k; y *= k; z *= k
                p = ripple.outPulse
            }
            pulse[i] = p
            centers[c0 + i * 3] = x.toFloat()
            centers[c0 + i * 3 + 1] = y.toFloat()
            centers[c0 + i * 3 + 2] = z.toFloat()

            proj.project(x, y, z)
            val depth = proj.outDepth
            val s0 = (ring * n + i) * 3
            screen[s0] = proj.outX.toFloat()
            screen[s0 + 1] = proj.outY.toFloat()
            screen[s0 + 2] = depth.toFloat()

            val radial = 1.0 - smooth((rad - fadeStartRadius) / fadeSpan)
            val cue = DEPTH_CUE_FLOOR + (1.0 - DEPTH_CUE_FLOOR) * (1.0 - smooth((depth - cueNear) / cueSpan))
            val nearFade = smooth((depth - NEAR_FADE_START) / (NEAR_FADE_END - NEAR_FADE_START))
            fade[ring * n + i] = (baseAlpha * good * radial * cue * nearFade).toFloat()
        }

        // ---- pass 2: ribbon
        val v0 = ring * verticesPerRing
        var sideX = proj.rightX; var sideY = proj.rightY; var sideZ = proj.rightZ
        var firstSideX = sideX; var firstSideY = sideY; var firstSideZ = sideZ
        val dash = style.dashCount.toFloat()
        val boost = style.haloBoost.toFloat()
        for (pIdx in 0 until pairsPerRing) {
            val i = if (pIdx == n) 0 else pIdx
            val ip = if (i == 0) n - 1 else i - 1
            val inx = if (i == n - 1) 0 else i + 1
            val x = centers[c0 + i * 3].toDouble()
            val y = centers[c0 + i * 3 + 1].toDouble()
            val z = centers[c0 + i * 3 + 2].toDouble()
            val tx = centers[c0 + inx * 3] - centers[c0 + ip * 3].toDouble()
            val ty = centers[c0 + inx * 3 + 1] - centers[c0 + ip * 3 + 1].toDouble()
            val tz = centers[c0 + inx * 3 + 2] - centers[c0 + ip * 3 + 2].toDouble()
            val vx = proj.camX - x; val vy = proj.camY - y; val vz = proj.camZ - z
            // side = tangent x view: perpendicular to the thread as seen from the camera
            val cx = ty * vz - tz * vy
            val cy = tz * vx - tx * vz
            val cz = tx * vy - ty * vx
            val len = sqrt(cx * cx + cy * cy + cz * cz)
            if (pIdx == n) {
                // closing pair: exactly the side of pair 0, so the loop shuts without a crack
                sideX = firstSideX; sideY = firstSideY; sideZ = firstSideZ
            } else if (len > 1e-12) {
                // sin of the angle between thread and view ray. Where the thread runs (almost) straight at the
                // camera - the hairpin ends of a ring seen edge-on - the cross product is tiny and its direction
                // is noise; lean on the previous side there instead of letting the ribbon flap into a star.
                val tLen = sqrt(tx * tx + ty * ty + tz * tz)
                val vLen = sqrt(vx * vx + vy * vy + vz * vz)
                val sinAngle = if (tLen * vLen > 1e-18) len / (tLen * vLen) else 0.0
                val lean = ((GRAZING_SIN - sinAngle) / GRAZING_SIN).coerceIn(0.0, 1.0)
                val bx = cx / len * (1.0 - lean) + sideX * lean
                val by = cy / len * (1.0 - lean) + sideY * lean
                val bz = cz / len * (1.0 - lean) + sideZ * lean
                val bLen = sqrt(bx * bx + by * by + bz * bz)
                if (bLen > 1e-6) {
                    sideX = bx / bLen; sideY = by / bLen; sideZ = bz / bLen
                }
            } // else: degenerate tangent, keep the previous side
            if (pIdx == 0) {
                firstSideX = sideX; firstSideY = sideY; firstSideZ = sideZ
            }

            val pl = pulse[i]
            val widthMul = 1.0 + RIPPLE_WIDTH * pl
            val depth = screen[(ring * n + i) * 3 + 2].toDouble()
            // perspective hint: threads near the camera are a little fatter, far ones a little thinner
            val persp = (1.0 + PERSPECTIVE_WIDTH * (proj.focusDepth / max(depth, proj.near) - 1.0)).coerceIn(0.6, 2.2)
            val corePx = style.coreHalfPx * widthMul * persp
            // A ribbon wider than the turn it goes round folds over itself (bright blotches, star-shaped tips where
            // a ring is seen edge-on). Keep the glow narrower than the local SCREEN radius of curvature: the glow
            // tapers into a tight turn instead of bursting out of it.
            val turnRadiusPx = screenTurnRadius(ring, ip, i, inx, proj.near)
            val haloWanted = min(style.haloHalfPx * widthMul * persp, turnRadiusPx * TURN_RADIUS_SHARE)
            val haloPx = max(haloWanted, corePx + 2.0)
            val hw = haloPx * proj.worldPerPx(depth)

            val heat = min(1.0, style.heat + RIPPLE_HEAT * pl)
            val cr = (style.r + (style.hotR - style.r) * heat).toFloat()
            val cg = (style.g + (style.hotG - style.g) * heat).toFloat()
            val cb = (style.b + (style.hotB - style.b) * heat).toFloat()
            val a = fade[ring * n + i]

            for (s in 0..1) {
                val v = v0 + pIdx * 2 + s
                val sign = if (s == 0) -1.0 else 1.0
                positions[v * 3] = (x + sideX * hw * sign).toFloat()
                positions[v * 3 + 1] = (y + sideY * hw * sign).toFloat()
                positions[v * 3 + 2] = (z + sideZ * hw * sign).toFloat()
                shape[v * 4 + 2] = haloPx.toFloat()
                shape[v * 4 + 3] = corePx.toFloat()
                color[v * 4] = cr
                color[v * 4 + 1] = cg
                color[v * 4 + 2] = cb
                color[v * 4 + 3] = a
                trim[v * 2] = dash
                trim[v * 2 + 1] = boost
            }
        }
    }

    companion object {
        const val MIN_SEGMENTS = 8
        const val ALPHA_EPS = 1e-4
        const val DEPTH_CUE_FLOOR = 0.42
        const val NEAR_FADE_START = 0.04
        const val NEAR_FADE_END = 0.30
        const val PERSPECTIVE_WIDTH = 0.35
        const val RIPPLE_WIDTH = 0.9
        const val RIPPLE_HEAT = 0.8

        /** Below this sin(angle between thread and view ray) the ribbon side leans on its predecessor. */
        const val GRAZING_SIN = 0.2

        /** Glow half width is at most this share of the local screen-space turn radius. */
        const val TURN_RADIUS_SHARE = 0.85
        const val STRAIGHT = 1.0e9

        /** smoothstep on an already normalised argument. */
        fun smooth(t: Double): Double {
            val k = t.coerceIn(0.0, 1.0)
            return k * k * (3.0 - 2.0 * k)
        }
    }
}
