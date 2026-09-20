package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Vec3
import bar.verdantbloom.three.THREE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun near(expected: Double, actual: Double, eps: Double, what: String = "") =
    assertTrue(abs(expected - actual) <= eps, "$what expected $expected got $actual (eps $eps)")

class ProjectorTest {
    private fun projector(view: BloomView, w: Double = 800.0, h: Double = 600.0): Projector {
        val p = Projector()
        p.setViewport(w, h)
        p.setFromView(view)
        return p
    }

    @Test
    fun targetLandsInTheViewportCentreAtViewDistance() {
        val view = BloomView(distance = 9.0, target = Vec3(0.5, -0.25, 1.0))
        val p = projector(view)
        assertTrue(p.project(0.5, -0.25, 1.0))
        near(400.0, p.outX, 1e-9, "x")
        near(300.0, p.outY, 1e-9, "y")
        near(9.0, p.outDepth, 1e-9, "depth")
        // bloom-api: pxPerUnit = height / (2 * distance * tan(fov / 2)) at the target plane
        near(600.0 / (2 * 9.0 * tan(25.0 * PI / 180.0)), p.outPxPerUnit, 1e-9, "pxPerUnit")
        near(1.0, p.pxPerUnit(9.0) * p.worldPerPx(9.0), 1e-12, "px <-> world")
    }

    @Test
    fun screenAxesFollowTheOrbit() {
        val p = projector(BloomView())
        p.project(1.0, 0.0, 0.0)
        assertTrue(p.outX > 400.0, "+x is right of centre")
        p.project(0.0, 1.0, 0.0)
        assertTrue(p.outY < 300.0, "+y is ABOVE centre (CSS y grows downwards)")

        // quarter turn about Y: the camera sits on +X, so bloom -Z is now on the right
        val q = Quat(cos(PI / 4), 0.0, sin(PI / 4), 0.0)
        val turned = projector(BloomView(orbit = q))
        near(9.0, turned.camX, 1e-9, "camX")
        near(0.0, turned.camZ, 1e-9, "camZ")
        turned.project(0.0, 0.0, -1.0)
        assertTrue(turned.outX > 400.0)
    }

    @Test
    fun pointsBehindTheCameraAreReportedAndStayFinite() {
        val p = projector(BloomView(distance = 2.0))
        assertFalse(p.project(0.0, 0.0, 5.0))
        assertTrue(p.outX.isFinite() && p.outY.isFinite() && p.outPxPerUnit.isFinite())
    }

    @Test
    fun rubbishViewsAreSanitised() {
        val p = projector(BloomView(orbit = Quat(0.0, 0.0, 0.0, 0.0), distance = Double.NaN, fovDegrees = Double.NaN))
        assertTrue(p.project(0.0, 0.0, 0.0))
        assertTrue(p.outX.isFinite() && p.outDepth.isFinite())
    }

    /** The GPU path uses a THREE.PerspectiveCamera set from the same numbers: both must agree. */
    @Test
    fun agreesWithThreePerspectiveCamera() {
        val axis = THREE.Vector3(0.3, 1.0, -0.2).normalize()
        val tq = THREE.Quaternion().setFromAxisAngle(axis, 0.9)
        val view = BloomView(orbit = Quat(tq.w, tq.x, tq.y, tq.z), distance = 6.5, target = Vec3(0.2, 0.1, -0.3), fovDegrees = 42.0)
        val p = projector(view, 1024.0, 640.0)

        val cam = THREE.PerspectiveCamera(42.0, 1024.0 / 640.0, 0.01, 200.0)
        cam.position.set(p.camX, p.camY, p.camZ)
        cam.up.set(p.upX, p.upY, p.upZ)
        cam.lookAt(THREE.Vector3(p.camX + p.fwdX, p.camY + p.fwdY, p.camZ + p.fwdZ))
        cam.updateProjectionMatrix()
        cam.updateMatrixWorld(true)

        val samples = listOf(Vec3(1.0, 2.0, 0.5), Vec3(-2.0, 0.3, 1.5), Vec3(0.0, -1.0, -2.0))
        for (s in samples) {
            p.project(s.x, s.y, s.z)
            val ndc = THREE.Vector3(s.x, s.y, s.z).project(cam)
            near((ndc.x * 0.5 + 0.5) * 1024.0, p.outX, 1e-6, "x of $s")
            near((0.5 - ndc.y * 0.5) * 640.0, p.outY, 1e-6, "y of $s")
        }
    }
}

class RingPickerTest {
    private val segments = 8
    private val rings = 3

    /** Ring r = a regular octagon of radius 40 + 40r around (200, 200), at depth 5 + r. */
    private fun screen(depthOf: (Int) -> Double = { 5.0 + it }): FloatArray {
        val s = FloatArray(rings * segments * 3)
        for (r in 0 until rings) for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            val o = (r * segments + i) * 3
            s[o] = (200 + (40 + 40 * r) * cos(a)).toFloat()
            s[o + 1] = (200 + (40 + 40 * r) * sin(a)).toFloat()
            s[o + 2] = depthOf(r).toFloat()
        }
        return s
    }

    private val allOn = BooleanArray(3) { true }
    private fun fade(v: Float = 1f) = FloatArray(rings * segments) { v }

    private fun pick(x: Double, y: Double, slop: Double = 12.0, screen: FloatArray = screen(), fade: FloatArray = fade(), pickable: BooleanArray = allOn, enabled: BooleanArray = allOn) =
        RingPicker.pick(x, y, slop, screen, fade, enabled, pickable, rings, segments, 0.02)

    @Test
    fun nearestRingWithinTheSlopWins() {
        assertEquals(0, pick(243.0, 200.0))
        assertEquals(1, pick(276.0, 200.0))
        assertEquals(2, pick(318.0, 203.0))
        assertEquals(RingIds.NONE, pick(260.0, 200.0), "20 px from both neighbours, slop 12")
        assertEquals(0, pick(258.0, 200.0, slop = 24.0), "touch slop reaches ring 0 (18 px) before ring 1 (22 px)")
        assertEquals(RingIds.NONE, pick(200.0, 200.0), "centre of the rings")
    }

    @Test
    fun distanceIsToTheSegmentNotToTheVertices() {
        // midpoint of ring 0's first edge: far from both vertices, exactly on the segment
        val s = screen()
        val mx = (s[0] + s[3]) / 2.0
        val my = (s[1] + s[4]) / 2.0
        assertEquals(0, pick(mx, my, slop = 0.5))
    }

    @Test
    fun unpickableDisabledFadedAndBehindRingsAreSkipped() {
        assertEquals(RingIds.NONE, pick(240.0, 200.0, pickable = booleanArrayOf(false, true, true)))
        assertEquals(RingIds.NONE, pick(240.0, 200.0, enabled = booleanArrayOf(false, true, true)))
        val faded = fade()
        for (i in 0 until segments) faded[i] = 0.01f
        assertEquals(RingIds.NONE, pick(240.0, 200.0, fade = faded))
        assertEquals(RingIds.NONE, pick(240.0, 200.0, screen = screen { if (it == 0) -1.0 else 5.0 + it }))
        assertEquals(RingIds.NONE, pick(Double.NaN, 200.0))
    }

    @Test
    fun whenTwoRingsAreEquallyNearTheFrontOneWins() {
        // rings 0 and 1 drawn on top of each other; ring 1 is nearer to the camera
        val s = screen { if (it == 1) 2.0 else 6.0 }
        for (i in 0 until segments * 3) s[segments * 3 + i] = if (i % 3 == 2) 2f else s[i]
        assertEquals(1, pick(240.0, 200.0, screen = s))
    }
}

class AnchorTrackerTest {
    private val n = 16

    private fun screen(nearest: Int): FloatArray {
        val s = FloatArray(n * 3)
        for (i in 0 until n) {
            s[i * 3] = 400f
            s[i * 3 + 1] = 300f
            // depth 5..7, minimum at `nearest`
            s[i * 3 + 2] = (6.0 - cos(2 * PI * (i - nearest) / n)).toFloat()
        }
        return s
    }

    private val fade = FloatArray(n) { 1f }

    private fun AnchorTracker.step(screen: FloatArray, home: Int, dt: Double = 1.0 / 60) =
        update(0, home, screen, fade, 800.0, 600.0, 0.02, 24.0, dt)

    @Test
    fun firstFrameSnapsToTheCameraFacingSample() {
        val t = AnchorTracker(1, n)
        t.step(screen(nearest = 5), home = 5)
        assertEquals(5, t.targetOf(0))
        assertEquals(5.0, t.position[0])
    }

    @Test
    fun homeAnchorPullsTheChoiceButFacingWins() {
        val t = AnchorTracker(1, n)
        t.step(screen(nearest = 4), home = 8)
        val target = t.targetOf(0)
        assertTrue(target in 4..6, "between the facing sample (4) and home (8), closer to facing; got $target")
    }

    @Test
    fun smallChangesDoNotRetargetAndBigOnesGlide() {
        val t = AnchorTracker(1, n)
        t.step(screen(nearest = 4), home = 4)
        t.step(screen(nearest = 5), home = 4) // one sample of drift: inside the hysteresis
        assertEquals(4, t.targetOf(0))
        assertEquals(4.0, t.position[0])

        t.step(screen(nearest = 12), home = 12) // the ring turned half way round
        assertEquals(12, t.targetOf(0))
        val moved = abs(t.position[0] - 4.0)
        assertTrue(moved > 0.0 && moved <= AnchorTracker.GLIDE_TURNS_PER_SECOND * n / 60.0 + 1e-9, "one frame of glide, got $moved")
        repeat(600) { t.step(screen(nearest = 12), home = 12) }
        near(12.0, t.position[0], 1e-9, "settles on the target")
    }

    @Test
    fun glideTakesTheShortWayAcrossTheSeam() {
        val t = AnchorTracker(1, n)
        t.step(screen(nearest = 15), home = 15)
        t.step(screen(nearest = 2), home = 2, dt = 0.05)
        val p = t.position[0]
        assertTrue(p > 15.0 || p < 2.0, "went 15 -> 0 -> 2, not backwards through 8; got $p")
        assertTrue(p >= 0.0 && p < n)
    }

    @Test
    fun interpolatesPerSampleData() {
        val t = AnchorTracker(1, n)
        t.step(screen(nearest = 3), home = 3)
        val data = FloatArray(n * 3) { it.toFloat() }
        assertEquals(data[3 * 3 + 1].toDouble(), t.sample(0, data, 3, 1))
    }
}

class RippleFieldTest {
    @Test
    fun silentUntilKickedThenDecays() {
        val r = RippleField()
        r.advance(1.0)
        assertFalse(r.active)
        r.sample(0.3, 0)
        assertEquals(0.0, r.outWave)

        r.kick(1.0)
        assertTrue(r.active)
        r.advance(0.1)
        val early = r.envelope(0, 0)
        assertTrue(early > 0.5, "full kick, just after the attack: $early")
        repeat(8) { r.advance(0.25) }
        val late = r.envelope(0, 0)
        assertTrue(late < early * 0.3, "decays: $late vs $early")
        repeat(30) { r.advance(0.25) }
        assertFalse(r.active)
    }

    @Test
    fun closesOnItselfAndReachesOuterRingsLater() {
        val r = RippleField()
        r.kick(0.5)
        r.advance(0.2)
        r.sample(0.0, 0)
        val w0 = r.outWave
        r.sample(1.0, 0)
        near(w0, r.outWave, 1e-9, "arc 0 == arc 1")
        assertTrue(r.envelope(0, 0) > 0.0)
        assertEquals(0.0, r.envelope(0, 11), "ring 11 is reached after 11 * RING_DELAY = 0.66 s")
    }

    @Test
    fun aZeroKickStillRingsAndJunkIsIgnored() {
        val r = RippleField()
        r.kick(0.0)
        r.advance(0.1)
        assertTrue(r.envelope(0, 0) > 0.0)
        r.kick(Double.NaN)
        r.advance(Double.NaN)
        r.sample(0.5, 0)
        assertTrue(r.outWave.isFinite() && r.outPulse in 0.0..1.0)
    }
}

class DiscGeometryTest {
    @Test
    fun buffersAreSizedAsDeclaredAndStayInsideTheUnitDisc() {
        for (mesh in listOf(DiscGeometry.wedges(), DiscGeometry.fill())) {
            assertEquals(DiscGeometry.sectorVertexCount() * 3, mesh.positions.size)
            assertEquals(DiscGeometry.sectorIndexCount(), mesh.indices.size)
            assertTrue(mesh.indices.all { it in 0 until DiscGeometry.sectorVertexCount() })
        }
        val stroke = DiscGeometry.stroke()
        assertEquals(DiscGeometry.annulusVertexCount() * 3, stroke.positions.size)
        assertEquals(DiscGeometry.annulusIndexCount(), stroke.indices.size)
        assertTrue(stroke.indices.all { it in 0 until DiscGeometry.annulusVertexCount() })
        for (mesh in listOf(DiscGeometry.wedges(), DiscGeometry.fill(), stroke)) {
            for (v in 0 until mesh.positions.size / 3) {
                val r = sqrt(mesh.positions[v * 3] * mesh.positions[v * 3] + mesh.positions[v * 3 + 1] * mesh.positions[v * 3 + 1])
                assertTrue(r <= 1.0001f)
                assertEquals(0f, mesh.positions[v * 3 + 2])
            }
        }
    }

    @Test
    fun threeSixtyDegreeWedgesCentredOn0_120_240() {
        val w = DiscGeometry.wedges()
        val perSector = DiscGeometry.STEPS_PER_SECTOR + 2
        for (s in 0 until 3) {
            val first = s * perSector + 1
            val last = s * perSector + perSector - 1
            fun angle(v: Int) = kotlin.math.atan2(w.positions[v * 3 + 1].toDouble(), w.positions[v * 3].toDouble()) * 180 / PI
            val centre = 120.0 * s
            fun wrap(a: Double): Double { var d = a; while (d > 180) d -= 360; while (d < -180) d += 360; return d }
            near(-30.0, wrap(angle(first) - centre), 1e-3, "wedge $s start")
            near(30.0, wrap(angle(last) - centre), 1e-3, "wedge $s end")
        }
    }

    @Test
    fun angleIsExactAtUnixTimeMagnitudes() {
        val period = (7 + 3) * 29.6
        val born = 1_789_000_000.0
        near(1.0, DiscGeometry.angle(1.0, born, period, born), 1e-12)
        near(1.0 + PI, DiscGeometry.angle(1.0, born, period, born + period / 2), 1e-6, "half a period later")
        near(DiscGeometry.angle(0.5, born, period, born + 10.0), DiscGeometry.angle(0.5, born, period, born + 10.0 + 1000 * period), 1e-4, "periodic")
        val before = DiscGeometry.angle(0.0, born, period, born - 1.0)
        assertTrue(before in 0.0..(2 * PI), "time before birth stays in range: $before")
        assertEquals(2.0, DiscGeometry.angle(2.0, born, 0.0, born + 5), "a zero period cannot divide")
    }
}

class ColorMathTest {
    @Test
    fun srgbTransferAndMixing() {
        assertEquals(0.0, ColorMath.srgbToLinear(0.0))
        near(1.0, ColorMath.srgbToLinear(1.0), 1e-12)
        near(0.21404, ColorMath.srgbToLinear(0.5), 1e-4)
        // same function three applies in Color.setHex
        val c = THREE.Color().setHex(0x3dffc0)
        near(c.r, ColorMath.srgbToLinear(ColorMath.red(0x3dffc0)), 1e-6)
        near(c.b, ColorMath.srgbToLinear(ColorMath.blue(0x3dffc0)), 1e-6)

        assertEquals(0x102030, ColorMath.mixHex(0x102030, 0xffffff, 0.0))
        assertEquals(0xffffff, ColorMath.mixHex(0x102030, 0xffffff, 1.0))
        assertEquals(0x808080, ColorMath.mixHex(0x000000, 0xffffff, 0.5))
        assertTrue(ColorMath.luminance(0xefe8d8) > 0.7 && ColorMath.luminance(0x020a07) < 0.01)
    }
}
