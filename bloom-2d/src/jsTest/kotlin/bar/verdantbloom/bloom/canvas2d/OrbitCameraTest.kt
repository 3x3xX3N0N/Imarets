package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrbitCameraTest {
    private val w = 375.0
    private val h = 667.0

    private fun camera(view: BloomView = BloomView(distance = 6.0)): OrbitCamera =
        OrbitCamera(view).also { it.setViewport(w, h) }

    /** Screen position of bloom point [p] under the camera's current view. */
    private fun screen(cam: OrbitCamera, p: Vec3): Pair<Double, Double> {
        val proj = Projector()
        proj.set(cam.view, w, h)
        assertTrue(proj.project(p.x, p.y, p.z), "point in front of the camera")
        return proj.outX to proj.outY
    }

    /** The bloom point on the target plane that is drawn at screen ([x], [y]). */
    private fun unproject(cam: OrbitCamera, x: Double, y: Double): Vec3 {
        val v = cam.view
        val right = QuatMath.rotate(v.orbit, Vec3(1.0, 0.0, 0.0))
        val up = QuatMath.rotate(v.orbit, Vec3(0.0, 1.0, 0.0))
        val a = (x - w / 2) / cam.pxPerUnit
        val b = -(y - h / 2) / cam.pxPerUnit
        return Vec3(v.target.x + right.x * a + up.x * b, v.target.y + right.y * a + up.y * b, v.target.z + right.z * a + up.z * b)
    }

    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected, got $actual")

    @Test
    fun viewIsCachedUntilSomethingChanges() {
        val cam = camera()
        val a = cam.view
        assertFalse(cam.update(0.016))
        assertSame(a, cam.view)
        cam.rotateBy(10.0, 0.0)
        assertTrue(cam.update(0.016))
        assertTrue(a != cam.view)
    }

    @Test
    fun pxPerUnitFollowsTheBloomViewContract() {
        val cam = camera(BloomView(distance = 9.0, fovDegrees = 50.0))
        near(h / (2.0 * 9.0 * kotlin.math.tan(25.0 * PI / 180.0)), cam.pxPerUnit, 1e-9)
    }

    @Test
    fun dragRightTurnsTheFrontOfTheBloomRightAndDragDownTipsItDown() {
        val cam = camera()
        val front = Vec3(0.0, 0.0, 1.0) // the point of the unit sphere facing the camera
        val (x0, y0) = screen(cam, front)
        cam.rotateBy(40.0, 0.0)
        val (x1, y1) = screen(cam, front)
        assertTrue(x1 > x0 + 5.0, "front moved right: $x0 -> $x1")
        near(y0, y1, 1e-6)
        cam.rotateBy(0.0, 40.0)
        val (_, y2) = screen(cam, front)
        assertTrue(y2 > y1 + 5.0, "front moved down: $y1 -> $y2")
        near(1.0, QuatMath.length(cam.view.orbit), 1e-12)
    }

    @Test
    fun aFullHeightDragIsHalfATurnAndTheOrbitStaysUnit() {
        val cam = camera()
        repeat(667) { cam.rotateBy(0.0, 1.0) }
        // half a turn of pitch: the camera is now behind the bloom, looking back along +Z
        val back = QuatMath.rotate(cam.view.orbit, Vec3(0.0, 0.0, 1.0))
        near(-1.0, back.z, 1e-9)
        near(1.0, QuatMath.length(cam.view.orbit), 1e-12)
    }

    @Test
    fun panMovesTheContentWithTheFinger() {
        val cam = camera(BloomView(orbit = QuatMath.axisAngle(0.3, 1.0, 0.2, 0.7), distance = 5.0, target = Vec3(0.2, 0.1, -0.3)))
        val p = cam.view.target
        val (x0, y0) = screen(cam, p)
        near(w / 2, x0); near(h / 2, y0)
        cam.panBy(30.0, -20.0)
        val (x1, y1) = screen(cam, p)
        near(x0 + 30.0, x1, 1e-6)
        near(y0 - 20.0, y1, 1e-6)
    }

    @Test
    fun zoomKeepsThePointUnderTheCursorFixed() {
        val cam = camera(BloomView(orbit = QuatMath.axisAngle(1.0, 0.5, 0.0, 0.6), distance = 8.0))
        val cursorX = 300.0
        val cursorY = 120.0
        val p = unproject(cam, cursorX, cursorY)
        val before = cam.pxPerUnit
        cam.zoomAt(0.5, cursorX, cursorY)
        near(4.0, cam.view.distance, 1e-12)
        near(before * 2.0, cam.pxPerUnit, 1e-9)
        val (x, y) = screen(cam, p)
        near(cursorX, x, 1e-6)
        near(cursorY, y, 1e-6)
    }

    @Test
    fun wheelDirectionModesAndClamps() {
        val cam = camera()
        val d0 = cam.view.distance
        cam.wheel(100.0, 0, w / 2, h / 2)
        val d1 = cam.view.distance
        assertTrue(d1 > d0, "wheel down zooms out")
        cam.wheel(-100.0, 0, w / 2, h / 2)
        near(d0, cam.view.distance, 1e-9)
        cam.wheel(3.0, 1, w / 2, h / 2) // three LINES = 48 px
        near(d0 * kotlin.math.exp(48 * OrbitCamera.WHEEL_ZOOM_PER_PX), cam.view.distance, 1e-9)
        repeat(200) { cam.wheel(-5000.0, 0, w / 2, h / 2) }
        near(cam.minDistance, cam.view.distance, 1e-12)
        repeat(200) { cam.wheel(5000.0, 0, w / 2, h / 2) }
        near(cam.maxDistance, cam.view.distance, 1e-12)
        cam.wheel(Double.NaN, 0, 0.0, 0.0)
        near(cam.maxDistance, cam.view.distance, 1e-12)
    }

    @Test
    fun pinchZoomsPansAndTwists() {
        val cam = camera()
        val p = unproject(cam, 150.0, 300.0)
        val ppu = cam.pxPerUnit
        // fingers spread to twice the span while their centre moves from (150, 300) to (180, 340)
        cam.pinch(100.0, 200.0, 150.0, 300.0, 180.0, 340.0)
        near(ppu * 2.0, cam.pxPerUnit, 1e-9)
        val (x, y) = screen(cam, p)
        near(180.0, x, 1e-6)
        near(340.0, y, 1e-6)

        // a clockwise twist (positive angle in y-down screen coordinates) turns the content clockwise
        val twistCam = camera()
        val rightHand = Vec3(1.0, 0.0, 0.0)
        val (rx0, ry0) = screen(twistCam, rightHand)
        twistCam.pinch(100.0, 100.0, w / 2, h / 2, w / 2, h / 2, 0.2)
        val (rx1, ry1) = screen(twistCam, rightHand)
        assertTrue(ry1 > ry0 + 5.0 && rx1 < rx0, "a point on the right moves down: ($rx0,$ry0) -> ($rx1,$ry1)")
        near(1.0, QuatMath.length(twistCam.view.orbit), 1e-12)
    }

    @Test
    fun inertiaCoastsDecaysAndStops() {
        val cam = camera()
        cam.grab()
        repeat(6) { cam.rotateBy(12.0, 0.0, 0.016) } // 750 px/s
        cam.release()
        assertTrue(cam.isAnimating)
        val atRelease = cam.view.orbit
        assertTrue(cam.update(0.016))
        val firstStep = 1.0 - abs(QuatMath.dot(atRelease, cam.view.orbit))
        assertTrue(firstStep > 0.0, "keeps turning after release")
        repeat(30) { cam.update(0.016) }
        val mid = cam.view.orbit
        cam.update(0.016)
        val lateStep = 1.0 - abs(QuatMath.dot(mid, cam.view.orbit))
        assertTrue(lateStep < firstStep, "and slows down: $firstStep -> $lateStep")
        repeat(400) { cam.update(0.016) }
        assertFalse(cam.isAnimating)
        val before = cam.view.orbit
        assertFalse(cam.update(0.016))
        assertSame(before, cam.view.orbit)
    }

    @Test
    fun aSlowDragOrAGrabStopsTheCoast() {
        val slow = camera()
        slow.grab()
        repeat(6) { slow.rotateBy(0.3, 0.0, 0.016) } // about 19 px/s, below the coast threshold
        slow.release()
        assertFalse(slow.isAnimating)

        val cam = camera()
        cam.grab()
        repeat(6) { cam.panBy(15.0, 5.0, 0.016) }
        cam.release()
        assertTrue(cam.isAnimating)
        cam.grab()
        cam.release()
        assertFalse(cam.isAnimating)
    }

    @Test
    fun flyToEasesToAMovingAnchorThenFollowsIt() {
        val world = LinkWorld()
        val cam = camera(BloomView(distance = 9.0))
        val ring = 1 // anchor (2, 0, 0)
        cam.flyTo({ world.ringAnchor(ring) }, seconds = 1.0)
        assertTrue(cam.isFlying)
        var last = QuatMath.length(sub(cam.view.target, world.ringAnchor(ring)))
        var lastDistance = cam.view.distance
        repeat(60) {
            cam.update(1.0 / 60.0)
            val gap = QuatMath.length(sub(cam.view.target, world.ringAnchor(ring)))
            assertTrue(gap <= last + 1e-12, "the target only ever approaches the anchor")
            assertTrue(cam.view.distance <= lastDistance + 1e-12, "the dolly only ever moves in")
            last = gap
            lastDistance = cam.view.distance
        }
        cam.update(1.0 / 60.0)
        assertFalse(cam.isFlying)
        assertTrue(cam.isFollowing)
        near(0.0, last, 1e-9)
        near(cam.focusDistance, cam.view.distance, 1e-9)
        // the camera ended up on the anchor's side of the bloom
        val back = QuatMath.rotate(cam.view.orbit, Vec3(0.0, 0.0, 1.0))
        near(1.0, back.x, 1e-9)

        // the world drifts: the followed target tracks the anchor (smoothly, so give it a moment)
        world.shift = Vec3(0.0, 0.4, -0.2)
        repeat(120) { cam.update(1.0 / 60.0) }
        near(0.0, QuatMath.length(sub(cam.view.target, world.ringAnchor(ring))), 1e-3)

        // taking the wheel ends the follow
        cam.panBy(20.0, 0.0)
        assertFalse(cam.isFollowing)
        val held = cam.view.target
        world.shift = Vec3(1.0, 1.0, 1.0)
        cam.update(1.0 / 60.0)
        assertEquals(held, cam.view.target)
    }

    @Test
    fun flyToWithZeroSecondsJumpsForReducedMotion() {
        val cam = camera(BloomView(distance = 9.0))
        cam.flyTo({ Vec3(0.0, 1.5, 0.0) }, endDistance = 3.0, seconds = 0.0)
        assertFalse(cam.isFlying)
        assertEquals(Vec3(0.0, 1.5, 0.0), cam.view.target)
        near(3.0, cam.view.distance, 1e-12)
    }

    @Test
    fun flyHomeReturnsToTheOverviewAndDoesNotFollow() {
        val cam = camera(BloomView(distance = 9.0))
        cam.flyTo({ Vec3(2.0, 0.0, 0.0) }, seconds = 0.0)
        val orbitThere = cam.view.orbit
        cam.flyHome(0.5)
        repeat(40) { cam.update(1.0 / 60.0) }
        assertFalse(cam.isFlying)
        assertFalse(cam.isFollowing)
        near(9.0, cam.view.distance, 1e-9)
        near(0.0, QuatMath.length(cam.view.target), 1e-9)
        assertEquals(orbitThere, cam.view.orbit) // going home keeps the way you were looking
    }

    @Test
    fun grabbingMidFlightHandsOverWithoutAJump() {
        val cam = camera(BloomView(distance = 9.0))
        cam.flyTo({ Vec3(2.0, 0.0, 0.0) }, seconds = 1.0)
        repeat(30) { cam.update(1.0 / 60.0) }
        val mid = cam.view.target
        cam.grab()
        assertFalse(cam.isFlying)
        cam.update(1.0 / 60.0)
        assertTrue(QuatMath.length(sub(cam.view.target, mid)) < 0.2, "no teleport when a flight is interrupted")
        cam.release()
        repeat(200) { cam.update(1.0 / 60.0) }
        near(2.0, cam.view.target.x, 1e-3) // the follow finishes the approach
    }

    @Test
    fun jumpToAdoptsAViewAndClampsDistance() {
        val cam = camera()
        cam.flyTo({ Vec3(1.0, 1.0, 1.0) })
        cam.jumpTo(BloomView(distance = 1000.0, target = Vec3(0.5, 0.0, 0.0)))
        assertFalse(cam.isAnimating)
        near(cam.maxDistance, cam.view.distance, 1e-12)
        assertEquals(Vec3(0.5, 0.0, 0.0), cam.view.target)
    }

    @Test
    fun distanceToFitShowsTheWholeBloomInPortraitAndLandscape() {
        for ((vw, vh) in listOf(375.0 to 667.0, 1280.0 to 720.0, 600.0 to 600.0)) {
            val d = OrbitCamera.distanceToFit(2.8, vw, vh, 50.0, margin = 1.0)
            val proj = Projector()
            proj.set(BloomView(distance = d), vw, vh)
            // a radius-2.8 circle in the target plane touches the narrower pair of edges and stays inside the other
            proj.project(2.8, 0.0, 0.0)
            val right = proj.outX
            proj.project(0.0, 2.8, 0.0)
            val top = proj.outY
            assertTrue(right <= vw + 1e-6 && top >= -1e-6, "$vw x $vh: right=$right top=$top")
            assertTrue(abs(right - vw) < 1e-6 || abs(top) < 1e-6, "$vw x $vh: tight on the narrow side")
        }
        assertEquals(9.0, OrbitCamera.distanceToFit(2.8, 0.0, 0.0))
    }

    @Test
    fun easeIsMonotonicWithFixedEnds() {
        assertEquals(0.0, OrbitCamera.ease(0.0))
        assertEquals(1.0, OrbitCamera.ease(1.0))
        assertEquals(0.5, OrbitCamera.ease(0.5))
        assertEquals(0.0, OrbitCamera.ease(-3.0))
        assertEquals(1.0, OrbitCamera.ease(7.0))
        var last = 0.0
        for (i in 1..100) {
            val e = OrbitCamera.ease(i / 100.0)
            assertTrue(e >= last)
            last = e
        }
    }

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
}
