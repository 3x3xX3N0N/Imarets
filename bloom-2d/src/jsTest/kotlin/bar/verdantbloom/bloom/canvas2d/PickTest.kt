package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Identity orbit, camera on +Z looking at the origin, 800 x 600.
 * Ring 0: unit circle facing the camera. Ring 1: edge-on, drawn as the horizontal line from the centre to
 * two units right, its z > 0 half nearer than ring 0 and its z < 0 half farther.
 */
class PickTest {
    private val cx = 400.0
    private val cy = 300.0

    private fun circle(radius: Double, z: Double): (Double) -> Vec3 = { t -> Vec3(radius * cos(t), radius * sin(t), z) }

    private class Fixture(val scene: Scene2d, val proj: Projector) {
        fun pick(x: Double, y: Double, slop: Double = 12.0) = Picker.pick(scene, proj.near, x, y, slop)
    }

    private fun fixture(world: LinkWorld, segments: Int = 256): Fixture {
        val proj = Projector()
        proj.set(BloomView(distance = 6.0), 800.0, 600.0)
        val scene = Scene2d(segments)
        scene.update(world, proj, RippleField(), 0.0)
        return Fixture(scene, proj)
    }

    @Test
    fun nothingIsPickedBeforeTheFirstFrame() {
        assertEquals(RingIds.NONE, Picker.pick(Scene2d(64), 0.05, 400.0, 300.0, 50.0))
    }

    @Test
    fun hitsOnTheLineWithinTheSlopAndMissesOutsideIt() {
        val f = fixture(LinkWorld())
        val ppu = f.proj.pxPerUnitAtTarget
        val leftmost = cx - ppu // ring 0 at (-1, 0, 0), far away from ring 1
        assertEquals(0, f.pick(leftmost, cy))
        assertEquals(0, f.pick(leftmost - 10.0, cy, 12.0))
        assertEquals(RingIds.NONE, f.pick(leftmost - 10.0, cy, 5.0))
        assertEquals(0, f.pick(leftmost - 20.0, cy, 24.0)) // touch slop
        assertEquals(RingIds.NONE, f.pick(leftmost - 30.0, cy, 24.0))
        assertEquals(RingIds.NONE, f.pick(cx - ppu * 0.5, cy - ppu * 0.3)) // empty space inside ring 0
        assertEquals(RingIds.NONE, f.pick(Double.NaN, cy))
        assertEquals(RingIds.NONE, f.pick(leftmost - 2.0, cy, -3.0)) // a negative slop is treated as zero
    }

    @Test
    fun atACrossingTheNearerRingWins() {
        val f = fixture(LinkWorld())
        val ppu = f.proj.pxPerUnitAtTarget
        // ring 0 passes through (1, 0, 0); ring 1's near half covers the same screen spot and is on top
        assertEquals(1, f.pick(cx + ppu, cy))
        // once ring 1 has faded out near the pole it no longer takes the click
        val faded = fixture(LinkWorld(alphas = mapOf(1 to 0.01)))
        assertEquals(0, faded.pick(cx + ppu, cy))
    }

    @Test
    fun aNearMissPrefersTheCloserLineNotTheNearerDepth() {
        // ring 3 (radius 2) at z = 0, ring 5 (radius 2.3) much nearer at z = 2
        val world = LinkWorld(extra = mapOf(3 to circle(2.0, 0.0), 5 to circle(2.3, 2.0)))
        val f = fixture(world)
        f.proj.project(-2.0, 0.0, 0.0)
        val x3 = f.proj.outX
        f.proj.project(-2.3, 0.0, 2.0)
        val x5 = f.proj.outX
        // 7 px outside ring 3: not a direct hit on anything, ring 3 is the closer line
        val probe = x3 + 7.0 * (if (x5 < x3) 1.0 else -1.0)
        assertEquals(3, f.pick(probe, cy, 12.0))
        assertEquals(5, f.pick(x5, cy, 12.0))
    }

    @Test
    fun visitorIsPickableGhostNever() {
        val world = LinkWorld(extra = mapOf(RingIds.VISITOR to circle(2.5, 0.0), RingIds.GHOST to circle(3.2, 0.0)))
        val f = fixture(world)
        f.proj.project(-2.5, 0.0, 0.0)
        assertEquals(RingIds.VISITOR, f.pick(f.proj.outX, cy))
        f.proj.project(-3.2, 0.0, 0.0)
        assertEquals(256, f.scene.ringPoints[RingIds.GHOST]) // it IS drawn...
        assertEquals(RingIds.NONE, f.pick(f.proj.outX, cy, 8.0)) // ...but never picked
    }

    @Test
    fun segmentsBehindTheCameraAreIgnored() {
        // a ring that passes behind the camera: its projected junk must not catch clicks
        val world = LinkWorld(extra = mapOf(4 to { t -> Vec3(0.2 * cos(t), 1.0, 6.0 + 0.5 * sin(t)) }))
        val f = fixture(world)
        assertEquals(RingIds.NONE, f.pick(0.0, 0.0, 12.0))
        assertEquals(1, f.pick(cx + f.proj.pxPerUnitAtTarget, cy))
    }
}
