package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected, got $actual")

    @Test
    fun targetLandsInTheCentreAtTheContractScale() {
        val p = Projector()
        val view = BloomView(distance = 9.0, target = Vec3(0.3, -0.2, 0.1), fovDegrees = 50.0)
        p.set(view, 375.0, 667.0)
        assertTrue(p.project(0.3, -0.2, 0.1))
        near(187.5, p.outX)
        near(333.5, p.outY)
        near(9.0, p.outDepth)
        // bloom-api: pxPerUnit = height / (2 * distance * tan(fov / 2)) at the target plane
        val contract = 667.0 / (2.0 * 9.0 * tan(25.0 * PI / 180.0))
        near(contract, p.outScale, 1e-9)
        near(contract, p.pxPerUnitAtTarget, 1e-9)
    }

    @Test
    fun axesPointRightAndUpAndTowardsTheViewer() {
        val p = Projector()
        p.set(BloomView(distance = 10.0), 800.0, 600.0)
        val ppu = p.pxPerUnitAtTarget
        assertTrue(p.project(1.0, 0.0, 0.0))
        near(400.0 + ppu, p.outX)
        near(300.0, p.outY)
        assertTrue(p.project(0.0, 1.0, 0.0))
        near(400.0, p.outX)
        near(300.0 - ppu, p.outY) // +y is UP on screen, screen y grows down
        assertTrue(p.project(0.0, 0.0, 1.0))
        near(9.0, p.outDepth) // +z is towards the camera
        assertTrue(p.outScale > ppu) // nearer is bigger
    }

    @Test
    fun pointsAtOrBehindTheNearPlaneAreCulled() {
        val p = Projector()
        p.set(BloomView(distance = 4.0), 800.0, 600.0)
        assertFalse(p.project(0.0, 0.0, 4.0)) // the camera position itself
        assertFalse(p.project(0.0, 0.0, 5.0)) // behind the camera
        assertFalse(p.project(0.0, 0.0, 4.0 - Projector.NEAR))
        assertTrue(p.project(0.0, 0.0, 4.0 - 2 * Projector.NEAR))
        assertEquals(0.0, run { p.project(0.0, 0.0, 9.0); p.outScale })
    }

    @Test
    fun orbitMovesTheCameraAroundTheTarget() {
        val p = Projector()
        // quarter turn about Y: the camera now sits on +X and looks down -X
        val orbit = QuatMath.axisAngle(0.0, 1.0, 0.0, PI / 2)
        p.set(BloomView(orbit = orbit, distance = 5.0), 800.0, 600.0)
        assertTrue(p.project(1.0, 0.0, 0.0))
        near(400.0, p.outX, 1e-7)
        near(300.0, p.outY, 1e-7)
        near(4.0, p.outDepth, 1e-9)
        // world -Z is now on the camera's right
        assertTrue(p.project(0.0, 0.0, -1.0))
        assertTrue(p.outX > 400.0)
    }

    @Test
    fun matrixAgreesWithQuaternionRotationForArbitraryViews() {
        val p = Projector()
        var seed = 12345
        fun rnd(): Double {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 8) and 0xffff) / 65535.0 * 2.0 - 1.0
        }
        repeat(50) {
            val orbit = QuatMath.normalize(Quat(rnd(), rnd(), rnd(), rnd()))
            val target = Vec3(rnd(), rnd(), rnd())
            val view = BloomView(orbit, 6.0 + rnd(), target, 40.0 + 20.0 * rnd())
            p.set(view, 640.0, 480.0)
            val pt = Vec3(rnd(), rnd(), rnd()) // |pt - target| <= 2 * sqrt(3) < min distance 5
            // reference: camera space = orbit^-1 * (pt - target)
            val rel = Vec3(pt.x - target.x, pt.y - target.y, pt.z - target.z)
            val cam = QuatMath.rotate(QuatMath.conj(orbit), rel)
            val depth = view.distance - cam.z
            val focal = 480.0 / (2.0 * tan(view.fovDegrees * PI / 360.0))
            assertTrue(p.project(pt.x, pt.y, pt.z))
            near(depth, p.outDepth, 1e-9)
            near(320.0 + cam.x * focal / depth, p.outX, 1e-7)
            near(240.0 - cam.y * focal / depth, p.outY, 1e-7)
        }
    }

    @Test
    fun aDenormalisedOrbitDoesNotShearThePicture() {
        val a = Projector()
        val b = Projector()
        val q = QuatMath.normalize(Quat(0.7, 0.1, -0.5, 0.3))
        a.set(BloomView(orbit = q), 800.0, 600.0)
        b.set(BloomView(orbit = Quat(q.w * 1.7, q.x * 1.7, q.y * 1.7, q.z * 1.7)), 800.0, 600.0)
        a.project(1.0, 2.0, -0.5)
        b.project(1.0, 2.0, -0.5)
        near(a.outX, b.outX, 1e-9)
        near(a.outY, b.outY, 1e-9)
        near(a.outDepth, b.outDepth, 1e-9)
    }

    @Test
    fun projectArrayMatchesProjectPointByPoint() {
        val p = Projector()
        p.set(BloomView(orbit = QuatMath.axisAngle(1.0, 1.0, 0.0, 0.8), distance = 3.0), 375.0, 667.0)
        val n = 16
        val src = FloatArray(3 + n * 3) // leading junk to prove the offset is honoured
        for (i in 0 until n) {
            src[3 + i * 3] = (i * 0.3 - 2.0).toFloat()
            src[4 + i * 3] = (1.0 - i * 0.1).toFloat()
            src[5 + i * 3] = (i * 0.5 - 1.0).toFloat() // some of these end up behind the camera
        }
        val sx = FloatArray(n + 5)
        val sy = FloatArray(n + 5)
        val sd = FloatArray(n + 5)
        p.projectArray(src, 3, n, sx, sy, sd, 5)
        var culled = 0
        for (i in 0 until n) {
            val ok = p.project(src[3 + i * 3].toDouble(), src[4 + i * 3].toDouble(), src[5 + i * 3].toDouble())
            if (ok) {
                // float32 storage: tolerance relative to the magnitude
                near(p.outX, sx[5 + i].toDouble(), 1e-2 + abs(p.outX) * 1e-6)
                near(p.outY, sy[5 + i].toDouble(), 1e-2 + abs(p.outY) * 1e-6)
                near(p.outDepth, sd[5 + i].toDouble(), 1e-5)
            } else {
                culled++
                assertTrue(sd[5 + i] <= p.near)
            }
        }
        assertTrue(culled in 1 until n, "the fixture should mix visible and culled points, culled=$culled")
    }

    @Test
    fun quaternionHelpersAreConsistent() {
        val q = QuatMath.axisAngle(0.0, 0.0, 1.0, PI / 2)
        val v = QuatMath.rotate(q, Vec3(1.0, 0.0, 0.0))
        near(0.0, v.x); near(1.0, v.y); near(0.0, v.z)
        val back = QuatMath.rotate(QuatMath.conj(q), v)
        near(1.0, back.x); near(0.0, back.y)
        // fromTo takes u onto v, including the antiparallel case
        val u = Vec3(0.2, -0.7, 0.4)
        val w = Vec3(-1.0, 0.3, 0.9)
        val r = QuatMath.rotate(QuatMath.fromTo(u, w), u)
        val k = QuatMath.length(u) / QuatMath.length(w)
        near(w.x * k, r.x, 1e-9); near(w.y * k, r.y, 1e-9); near(w.z * k, r.z, 1e-9)
        val flipped = QuatMath.rotate(QuatMath.fromTo(u, Vec3(-u.x, -u.y, -u.z)), u)
        near(-u.x, flipped.x, 1e-9); near(-u.y, flipped.y, 1e-9); near(-u.z, flipped.z, 1e-9)
        // slerp: ends, halfway, unit length, shorter arc
        val a = Quat.IDENTITY
        val b = QuatMath.axisAngle(0.0, 1.0, 0.0, 1.2)
        near(1.0, QuatMath.dot(QuatMath.slerp(a, b, 0.0), a), 1e-12)
        near(1.0, abs(QuatMath.dot(QuatMath.slerp(a, b, 1.0), b)), 1e-12)
        val half = QuatMath.slerp(a, b, 0.5)
        near(1.0, QuatMath.length(half), 1e-12)
        near(1.0, abs(QuatMath.dot(half, QuatMath.axisAngle(0.0, 1.0, 0.0, 0.6))), 1e-12)
        val negB = Quat(-b.w, -b.x, -b.y, -b.z) // same rotation, opposite sign
        near(1.0, abs(QuatMath.dot(QuatMath.slerp(a, negB, 0.5), half)), 1e-12)
        near(1.0, sqrt(QuatMath.dot(QuatMath.mul(b, b), QuatMath.mul(b, b))), 1e-12)
    }
}
