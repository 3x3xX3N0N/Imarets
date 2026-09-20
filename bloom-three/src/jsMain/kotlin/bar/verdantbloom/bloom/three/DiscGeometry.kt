package bar.verdantbloom.bloom.three

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flat geometry (local XY plane, unit radius) of a spin disc, SPEC 3.4: a disc with three 60-degree
 * wedges centred on 0 / 120 / 240 degrees, inside a stroke.
 *
 * The three parts do NOT overlap (wedge sectors, the three sectors between them, and the stroke annulus),
 * so they can share one plane with no z-fighting and no draw-order tricks, seen from either side.
 * Pure Kotlin: sizes are testable without three.
 */
class SectorMesh(val positions: FloatArray, val indices: IntArray)

object DiscGeometry {
    /** Outer radius of fill and wedges = inner radius of the stroke. */
    const val STROKE_INNER = 0.88
    const val WEDGE_DEGREES = 60.0
    const val STEPS_PER_SECTOR = 10
    const val STROKE_STEPS = 60

    /** Wedges: three sectors centred on 0, 120, 240 degrees. */
    fun wedges(): SectorMesh = sectors(startDegrees = -WEDGE_DEGREES / 2)

    /** Disc fill: the three sectors between the wedges. */
    fun fill(): SectorMesh = sectors(startDegrees = WEDGE_DEGREES / 2)

    fun sectorVertexCount(steps: Int = STEPS_PER_SECTOR): Int = 3 * (steps + 2)
    fun sectorIndexCount(steps: Int = STEPS_PER_SECTOR): Int = 3 * steps * 3
    fun annulusVertexCount(steps: Int = STROKE_STEPS): Int = (steps + 1) * 2
    fun annulusIndexCount(steps: Int = STROKE_STEPS): Int = steps * 6

    private fun sectors(startDegrees: Double, steps: Int = STEPS_PER_SECTOR): SectorMesh {
        val pos = FloatArray(sectorVertexCount(steps) * 3)
        val idx = IntArray(sectorIndexCount(steps))
        var v = 0
        var k = 0
        for (s in 0 until 3) {
            val a0 = (startDegrees + 120.0 * s) * PI / 180.0
            val sweep = WEDGE_DEGREES * PI / 180.0
            val centre = v
            v++ // centre vertex stays at the origin
            for (i in 0..steps) {
                val a = a0 + sweep * i / steps
                pos[v * 3] = (STROKE_INNER * cos(a)).toFloat()
                pos[v * 3 + 1] = (STROKE_INNER * sin(a)).toFloat()
                if (i > 0) {
                    idx[k++] = centre
                    idx[k++] = v - 1
                    idx[k++] = v
                }
                v++
            }
        }
        return SectorMesh(pos, idx)
    }

    /** Stroke: annulus from [STROKE_INNER] to 1. */
    fun stroke(steps: Int = STROKE_STEPS): SectorMesh {
        val pos = FloatArray(annulusVertexCount(steps) * 3)
        val idx = IntArray(annulusIndexCount(steps))
        for (i in 0..steps) {
            val a = 2.0 * PI * i / steps
            val c = cos(a); val s = sin(a)
            pos[i * 6] = (STROKE_INNER * c).toFloat()
            pos[i * 6 + 1] = (STROKE_INNER * s).toFloat()
            pos[i * 6 + 3] = c.toFloat()
            pos[i * 6 + 4] = s.toFloat()
        }
        var k = 0
        for (i in 0 until steps) {
            val a = i * 2
            idx[k++] = a; idx[k++] = a + 1; idx[k++] = a + 2
            idx[k++] = a + 1; idx[k++] = a + 3; idx[k++] = a + 2
        }
        return SectorMesh(pos, idx)
    }

    /**
     * Rotation angle (radians, 0..2pi) of a disc at [unixSeconds]. The elapsed time is reduced modulo the
     * period BEFORE it is scaled, so precision does not decay at Unix-time magnitudes.
     */
    fun angle(phase: Double, bornAtUnixSeconds: Double, periodSeconds: Double, unixSeconds: Double): Double {
        if (!(periodSeconds > 0.0) || !periodSeconds.isFinite()) return phase
        var turn = ((unixSeconds - bornAtUnixSeconds) % periodSeconds) / periodSeconds
        if (turn < 0.0) turn += 1.0
        var a = (phase + 2.0 * PI * turn) % (2.0 * PI)
        if (a < 0.0) a += 2.0 * PI
        return a
    }
}
