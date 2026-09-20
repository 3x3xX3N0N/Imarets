package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.StubBloomWorld
import bar.verdantbloom.bloom.api.TriadKind
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DepthSortAndSceneTest {
    @Test
    fun sortsFarToNearAndIsStable() {
        val depth = floatArrayOf(3f, 9f, 1f, 9f, 5f, 0.5f)
        val order = IntArray(depth.size)
        DepthSort.reset(order)
        DepthSort.sortFarToNear(order, depth)
        assertEquals(listOf(1, 3, 4, 0, 2, 5), order.toList()) // the two 9s keep their original order
        assertTrue(DepthSort.isSortedFarToNear(order, depth))
    }

    @Test
    fun hiddenItemsSortToTheFarEndAndNaNDoesNoHarm() {
        val depth = floatArrayOf(2f, Float.POSITIVE_INFINITY, 7f, Float.POSITIVE_INFINITY, 4f)
        val order = IntArray(depth.size)
        DepthSort.reset(order)
        DepthSort.sortFarToNear(order, depth)
        assertEquals(listOf(1, 3, 2, 4, 0), order.toList())
        // the scene never produces NaN depths; if one slipped in, the sort still ends with a permutation
        val broken = floatArrayOf(2f, Float.NaN, 7f, 1f, Float.NaN, 4f)
        val order2 = IntArray(broken.size)
        DepthSort.reset(order2)
        DepthSort.sortFarToNear(order2, broken)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), order2.toSet())
    }

    @Test
    fun brokenSamplesAreCulledInsteadOfPoisoningTheSort() {
        val nan: (Double) -> Vec3 = { t -> if (t < 1.0) Vec3(Double.NaN, 0.0, 0.0) else Vec3(2.0 * kotlin.math.cos(t), 2.0 * kotlin.math.sin(t), 0.0) }
        val world = LinkWorld(extra = mapOf(4 to nan))
        val proj = Projector()
        proj.set(BloomView(distance = 6.0), 800.0, 600.0)
        val scene = Scene2d(64)
        scene.update(world, proj, RippleField(), 0.0)
        for (i in 0 until scene.itemCount) assertTrue(scene.itemDepth[i] == scene.itemDepth[i], "item $i has a NaN depth")
        val cpr = scene.chunksPerRing
        assertFalse(scene.itemVisible[4 * cpr], "the chunk holding the NaN samples is hidden")
        assertTrue(scene.itemVisible[4 * cpr + cpr / 2], "the healthy part of the ring is still drawn")
        assertTrue(DepthSort.isSortedFarToNear(scene.order, scene.itemDepth, scene.itemCount))
        assertFalse(proj.project(Double.NaN, 0.0, 0.0))
    }

    @Test
    fun aPersistentOrderIsReSortedAfterSmallChanges() {
        val n = 400
        val depth = FloatArray(n) { (it * 37 % n).toFloat() }
        val order = IntArray(n)
        DepthSort.reset(order)
        DepthSort.sortFarToNear(order, depth)
        assertTrue(DepthSort.isSortedFarToNear(order, depth))
        for (i in 0 until n step 7) depth[i] += 1.5f // a frame later a few items changed places
        assertFalse(DepthSort.isSortedFarToNear(order, depth))
        DepthSort.sortFarToNear(order, depth)
        assertTrue(DepthSort.isSortedFarToNear(order, depth))
        assertEquals(n, order.toSet().size) // still a permutation
    }

    @Test
    fun sceneSamplesEveryEnabledRingAndHidesTheGhost() {
        val world = StubBloomWorld()
        world.advanceTo(1234.5)
        val proj = Projector()
        proj.set(BloomView(), 800.0, 600.0)
        val scene = Scene2d(256)
        scene.update(world, proj, RippleField(), 1234.5)
        assertEquals(256, scene.segments)
        assertEquals(8, scene.chunkSize)
        assertEquals(32, scene.chunksPerRing)
        for (ring in 0..RingIds.VISITOR) assertEquals(256, scene.ringPoints[ring], "ring $ring")
        assertEquals(0, scene.ringPoints[RingIds.GHOST])
        assertEquals(0.0, scene.ringAlpha[RingIds.GHOST])
        for (c in 0 until scene.chunksPerRing) assertFalse(scene.itemVisible[RingIds.GHOST * scene.chunksPerRing + c])
        assertTrue(DepthSort.isSortedFarToNear(scene.order, scene.itemDepth, scene.itemCount))
        assertEquals(3, scene.discCount)

        world.ghostEnabled = true
        scene.update(world, proj, RippleField(), 1234.5)
        assertEquals(256, scene.ringPoints[RingIds.GHOST])
        assertTrue(scene.itemVisible[RingIds.GHOST * scene.chunksPerRing])
    }

    @Test
    fun chunkDepthIsTheMeanOfItsPointsIncludingTheSharedEndPoint() {
        val world = LinkWorld()
        val proj = Projector()
        proj.set(BloomView(orbit = QuatMath.axisAngle(1.0, 0.0, 0.0, -0.9), distance = 5.0), 800.0, 600.0)
        val scene = Scene2d(64)
        scene.update(world, proj, RippleField(), 0.0)
        assertEquals(2, scene.chunkSize)
        val ring = 1
        val base = ring * scene.segments
        // an inner chunk, and the last chunk which wraps round to point 0
        for (c in listOf(5, scene.chunksPerRing - 1)) {
            val start = c * scene.chunkSize
            var sum = 0.0
            for (k in start..start + scene.chunkSize) sum += scene.sd[base + k % scene.segments]
            val expected = sum / (scene.chunkSize + 1)
            assertTrue(abs(expected - scene.itemDepth[ring * scene.chunksPerRing + c]) < 1e-5)
        }
    }

    /**
     * The point of the whole depth sort: a Hopf link must READ as a link. At every place where the two rings
     * cross on screen, the ring that is really nearer there must be painted later (on top), and because the
     * rings are linked both kinds of crossing (0 over 1, 1 over 0) must occur.
     */
    @Test
    fun linkedRingsAlternateOverAndUnderAtTheirScreenCrossings() {
        for (segments in listOf(64, 256)) {
            val world = LinkWorld()
            val proj = Projector()
            val orbit = QuatMath.mul(QuatMath.axisAngle(0.0, 1.0, 0.0, 0.4), QuatMath.axisAngle(1.0, 0.0, 0.0, -0.9))
            proj.set(BloomView(orbit = orbit, distance = 6.0, target = Vec3(0.5, 0.0, 0.0)), 800.0, 600.0)
            val scene = Scene2d(segments)
            scene.update(world, proj, RippleField(), 0.0)

            val rank = IntArray(scene.itemCount)
            for (i in 0 until scene.itemCount) rank[scene.order[i]] = i

            var zeroOver = 0
            var oneOver = 0
            val n = scene.segments
            for (i in 0 until n) {
                val a0 = i
                val a1 = (i + 1) % n
                for (j in 0 until n) {
                    val b0 = n + j
                    val b1 = n + (j + 1) % n
                    val ax = scene.sx[a0].toDouble(); val ay = scene.sy[a0].toDouble()
                    val rx = scene.sx[a1] - ax; val ry = scene.sy[a1] - ay
                    val bx = scene.sx[b0].toDouble(); val by = scene.sy[b0].toDouble()
                    val sx = scene.sx[b1] - bx; val sy = scene.sy[b1] - by
                    val den = rx * sy - ry * sx
                    if (abs(den) < 1e-12) continue
                    val t = ((bx - ax) * sy - (by - ay) * sx) / den
                    val u = ((bx - ax) * ry - (by - ay) * rx) / den
                    if (t < 0.0 || t >= 1.0 || u < 0.0 || u >= 1.0) continue
                    val depthA = scene.sd[a0] + (scene.sd[a1] - scene.sd[a0]) * t
                    val depthB = scene.sd[b0] + (scene.sd[b1] - scene.sd[b0]) * u
                    val rankA = rank[0 * scene.chunksPerRing + i / scene.chunkSize]
                    val rankB = rank[1 * scene.chunksPerRing + j / scene.chunkSize]
                    assertTrue(abs(depthA - depthB) > 0.2, "linked rings are well separated in depth where they cross")
                    if (depthA < depthB) {
                        assertTrue(rankA > rankB, "ring 0 is nearer at this crossing, so it must be painted after ring 1")
                        zeroOver++
                    } else {
                        assertTrue(rankB > rankA, "ring 1 is nearer at this crossing, so it must be painted after ring 0")
                        oneOver++
                    }
                }
            }
            assertTrue(zeroOver >= 1 && oneOver >= 1, "segments=$segments: a link shows both crossing kinds, got $zeroOver / $oneOver")
            assertEquals(0, (zeroOver + oneOver) % 2, "closed curves cross an even number of times")
        }
    }

    @Test
    fun gapsAreCutOnlyWhereRingsComeCloseAndAlwaysAtCrossings() {
        // rings 0 and 1 are linked; ring 3 floats far away from everything; the ghost crosses ring 0 everywhere
        val lonely: (Double) -> Vec3 = { t -> Vec3(3.2 + 0.4 * kotlin.math.cos(t), 2.0 + 0.4 * kotlin.math.sin(t), 0.0) }
        val ghost: (Double) -> Vec3 = { t -> Vec3(1.02 * kotlin.math.cos(t), 1.02 * kotlin.math.sin(t), 0.0) }
        val world = LinkWorld(extra = mapOf(3 to lonely, RingIds.GHOST to ghost))
        val proj = Projector()
        val orbit = QuatMath.mul(QuatMath.axisAngle(0.0, 1.0, 0.0, 0.4), QuatMath.axisAngle(1.0, 0.0, 0.0, -0.9))
        proj.set(BloomView(orbit = orbit, distance = 4.5, target = Vec3(1.2, 0.6, 0.0)), 1280.0, 720.0)
        val scene = Scene2d(256)
        scene.update(world, proj, RippleField(), 0.0)
        val cpr = scene.chunksPerRing
        fun casings(ring: Int) = (0 until cpr).count { scene.itemCasing[ring * cpr + it] }

        assertEquals(0, casings(3), "a ring near nothing cuts nothing")
        assertEquals(0, casings(RingIds.GHOST), "the ghost cuts nothing")
        assertTrue(casings(0) in 1 until cpr / 2, "ring 0 cuts gaps only near its two crossings (not along the ghost): ${casings(0)}")
        assertTrue(casings(1) in 1 until cpr / 2, "ring 1 likewise: ${casings(1)}")

        // every real screen crossing of ring 0 and ring 1 has the casing flag on BOTH chunks involved
        val n = scene.segments
        var crossings = 0
        for (i in 0 until n) {
            for (j in 0 until n) {
                val a0 = i; val a1 = (i + 1) % n
                val b0 = n + j; val b1 = n + (j + 1) % n
                val ax = scene.sx[a0].toDouble(); val ay = scene.sy[a0].toDouble()
                val rx = scene.sx[a1] - ax; val ry = scene.sy[a1] - ay
                val bx = scene.sx[b0].toDouble(); val by = scene.sy[b0].toDouble()
                val qx = scene.sx[b1] - bx; val qy = scene.sy[b1] - by
                val den = rx * qy - ry * qx
                if (abs(den) < 1e-12) continue
                val t = ((bx - ax) * qy - (by - ay) * qx) / den
                val u = ((bx - ax) * ry - (by - ay) * rx) / den
                if (t < 0.0 || t >= 1.0 || u < 0.0 || u >= 1.0) continue
                crossings++
                assertTrue(scene.itemCasing[0 * cpr + i / scene.chunkSize], "ring 0 chunk at a crossing")
                assertTrue(scene.itemCasing[1 * cpr + j / scene.chunkSize], "ring 1 chunk at a crossing")
            }
        }
        assertTrue(crossings >= 2)
    }

    @Test
    fun spinDiscBecomesASortedItemWithAnAffineFrame() {
        val disc = SpinDisc(
            id = 7, position = Vec3(0.5, 0.25, 1.0), orientation = Quat.IDENTITY, radius = 0.128,
            periodSeconds = 7 * 29.6, phase = 0.5, bornAtUnixSeconds = 100.0, alpha = 1.0,
            triad = TriadKind.ORIGINAL, discColor = 0x0000ff, strokeColor = 0xff0000, wedgeColor = 0xffff00, nearRing = 0,
        )
        val world = LinkWorld(spinDiscs = listOf(disc))
        val proj = Projector()
        proj.set(BloomView(distance = 5.0), 800.0, 600.0)
        val scene = Scene2d(64)
        scene.update(world, proj, RippleField(), 100.0 + 7 * 29.6 / 4) // a quarter period after birth
        assertEquals(1, scene.discCount)
        val item = scene.discItemBase
        assertTrue(scene.itemVisible[item])
        assertTrue(abs(scene.itemDepth[item] - 4.0f) < 1e-5f)
        val k = proj.focal / 4.0
        // facing the camera squarely: local x -> screen right, local y -> screen UP (negative screen y), no shear
        assertTrue(abs(scene.discA[0] - k) < 1e-9 && abs(scene.discD[0] + k) < 1e-9)
        assertTrue(abs(scene.discB[0]) < 1e-9 && abs(scene.discC[0]) < 1e-9)
        assertTrue(abs(scene.discE[0] - (400.0 + 0.5 * k)) < 1e-9 && abs(scene.discF[0] - (300.0 - 0.25 * k)) < 1e-9)
        assertTrue(abs(scene.discAngle[0] - (0.5 + PI / 2)) < 1e-9)
        for (slot in 1 until scene.maxDiscs) assertFalse(scene.itemVisible[scene.discItemBase + slot])
        // the disc sits between the far and the near part of ring 1 in the drawing order
        assertTrue(DepthSort.isSortedFarToNear(scene.order, scene.itemDepth, scene.itemCount))
    }

    @Test
    fun rippleDisplacesRingsRadiallyAndOnlyWhileItLasts() {
        val world = LinkWorld()
        val proj = Projector()
        proj.set(BloomView(distance = 6.0), 800.0, 600.0)
        val scene = Scene2d(64)
        val ripples = RippleField()
        scene.update(world, proj, ripples, 0.0)
        val still = scene.pts.copyOf()

        ripples.kick(1.0)
        ripples.advance(0.3) // wave front at about one bloom unit: right on ring 0
        scene.update(world, proj, ripples, 0.0)
        var moved = 0.0
        for (i in 0 until 64) {
            val x0 = still[i * 3]; val y0 = still[i * 3 + 1]
            val x1 = scene.pts[i * 3]; val y1 = scene.pts[i * 3 + 1]
            assertTrue(abs(x0 * y1 - y0 * x1) < 1e-5, "displacement is along the radius")
            moved += abs(x1 - x0) + abs(y1 - y0)
        }
        assertTrue(moved > 0.5, "ring 0 visibly moved, total $moved")

        var t = 0.0
        while (t < RippleField.LIFE_SECONDS + 0.2) {
            ripples.advance(0.1); t += 0.1
        }
        assertFalse(ripples.active)
        assertEquals(0.0, ripples.glow)
        scene.update(world, proj, ripples, 0.0)
        assertTrue(still.contentEquals(scene.pts))
    }
}
