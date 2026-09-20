package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Shelf
import bar.verdantbloom.bloom.api.TriadKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HopfAndDiscTest {
    private fun world(): HopfLorenzWorld = BloomCore.createWorld() as HopfLorenzWorld

    /** Unclamped stereographic projection of a ring in double precision: xyz per point. */
    private fun projected(w: HopfLorenzWorld, ring: Int, n: Int): DoubleArray {
        val s3 = DoubleArray(4 * n)
        assertEquals(n, w.sampleRingS3(ring, s3, n))
        val out = DoubleArray(3 * n)
        for (i in 0 until n) {
            val inv = 1.0 / (1.0 - s3[4 * i + 3])
            out[3 * i] = s3[4 * i] * inv; out[3 * i + 1] = s3[4 * i + 1] * inv; out[3 * i + 2] = s3[4 * i + 2] * inv
        }
        return out
    }

    /** Gauss linking integral of two closed polylines (midpoint rule on segment pairs). */
    private fun linking(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size / 3
        val m = b.size / 3
        var sum = 0.0
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            val ax = a[3 * i]; val ay = a[3 * i + 1]; val az = a[3 * i + 2]
            val dax = a[3 * i2] - ax; val day = a[3 * i2 + 1] - ay; val daz = a[3 * i2 + 2] - az
            val mx = ax + dax * 0.5; val my = ay + day * 0.5; val mz = az + daz * 0.5
            for (j in 0 until m) {
                val j2 = (j + 1) % m
                val bx = b[3 * j]; val by = b[3 * j + 1]; val bz = b[3 * j + 2]
                val dbx = b[3 * j2] - bx; val dby = b[3 * j2 + 1] - by; val dbz = b[3 * j2 + 2] - bz
                val rx = mx - (bx + dbx * 0.5); val ry = my - (by + dby * 0.5); val rz = mz - (bz + dbz * 0.5)
                val cx = day * dbz - daz * dby
                val cy = daz * dbx - dax * dbz
                val cz = dax * dby - day * dbx
                val r2 = rx * rx + ry * ry + rz * rz
                sum += (rx * cx + ry * cy + rz * cz) / (r2 * sqrt(r2))
            }
        }
        return sum / (4.0 * PI)
    }

    @Test
    fun anyTwoFibersLinkExactlyOnce() {
        val n = 360
        var pairs = 0
        var sign = 0
        // identity rotation (before any advanceTo) plus three real instants incl. the origin bloom
        val worlds = listOf<Double?>(null, 0.0, 1790000000.25, 1790001234.5).map { t ->
            world().also { w -> w.ghostEnabled = true; if (t != null) { w.advanceTo(t) } }
        }
        for (w in worlds) {
            val ids = (0 until 10).toList() + RingIds.VISITOR
            val curves = ids.associateWith { projected(w, it, n) }
            for (i in ids) for (j in ids) {
                if (j <= i) continue
                // a ring sweeping past the projection pole is sampled too sparsely far out for the quadrature
                if (w.ringFarRadius(i) > 9.0 || w.ringFarRadius(j) > 9.0) continue
                val lk = linking(curves.getValue(i), curves.getValue(j))
                assertTrue(abs(abs(lk) - 1.0) < 0.03, "rings $i,$j at t=${w.unixSeconds}: linking number $lk")
                val s = if (lk > 0) 1 else -1
                if (sign == 0) sign = s
                assertEquals(sign, s, "all Hopf fibers link with the same handedness")
                pairs++
            }
        }
        println("LINKING checked $pairs ring pairs, handedness $sign")
        assertTrue(pairs >= 120, "only $pairs pairs were checkable")
    }

    @Test
    fun fibersStayOnS3BeforeProjection() {
        val w = world()
        w.ghostEnabled = true
        val buf = DoubleArray(4 * 128)
        var t = 1790000000.0
        for (k in 0 until 200) {
            if (k == 100) w.rho = 41.0
            w.advanceTo(t); t += 37.7
            for (ring in 0 until RingIds.COUNT) {
                assertEquals(128, w.sampleRingS3(ring, buf, 128))
                for (i in 0 until 128) {
                    var n2 = 0.0
                    for (c in 0 until 4) n2 += buf[4 * i + c] * buf[4 * i + c]
                    assertTrue(abs(n2 - 1.0) < 1e-12, "ring $ring point $i at t=$t has |p|^2 = $n2")
                }
            }
        }
    }

    @Test
    fun southChartIsTheSameFiber() {
        // same base point through both charts must give the same circle (as a point set)
        val north = HopfFiber(); val south = HopfFiber()
        val a = 0.48; val b = -0.6; val c = -sqrt(1.0 - a * a - b * b)
        north.setBase(a, b, c, -2.0); south.setBase(a, b, c, 2.0)
        north.rotate(1.0, 0.0, 0.0, 0.0); south.rotate(1.0, 0.0, 0.0, 0.0)
        val p = DoubleArray(4)
        for (k in 0 until 16) {
            south.pointS3(k * 0.4, p)
            // distance from p to the great circle spanned by north.ra, north.rb
            var da = 0.0; var db = 0.0
            for (i in 0 until 4) { da += p[i] * north.ra[i]; db += p[i] * north.rb[i] }
            assertTrue(abs(da * da + db * db - 1.0) < 1e-12)
        }
        // and the exact south pole works
        south.setBase(0.0, 0.0, -1.0, -0.9); south.rotate(1.0, 0.0, 0.0, 0.0)
        south.pointS3(1.0, p)
        assertTrue(abs(p[0] * p[0] + p[1] * p[1] + p[2] * p[2] + p[3] * p[3] - 1.0) < 1e-12)
    }

    @Test
    fun poleBlowUpIsClampedAndFaded() {
        val w = world()
        w.ghostEnabled = true
        val maxR = w.config.maxRadius
        val buf = FloatArray(3 * 128)
        var t = 1790000000.0
        var sawFade = false
        var sawGone = false
        for (k in 0 until 400) {
            w.advanceTo(t); t += 61.3
            for (ring in 0 until RingIds.COUNT) {
                assertEquals(128, w.sampleRing(ring, buf))
                var far = 0.0
                for (i in 0 until 128) {
                    val x = buf[3 * i].toDouble(); val y = buf[3 * i + 1].toDouble(); val z = buf[3 * i + 2].toDouble()
                    assertTrue(!x.isNaN() && !y.isNaN() && !z.isNaN())
                    val r = sqrt(x * x + y * y + z * z)
                    if (r > far) far = r
                }
                assertTrue(far <= maxR * 1.0001, "ring $ring reaches $far")
                val alpha = w.ringAlpha(ring)
                assertTrue(alpha in 0.0..1.0)
                if (far < w.config.fadeStartRadius * 0.99) assertEquals(1.0, alpha)
                if (alpha < 1.0) sawFade = true
                if (alpha == 0.0) sawGone = true
                val anchor = w.ringAnchor(ring)
                val ar = sqrt(anchor.x * anchor.x + anchor.y * anchor.y + anchor.z * anchor.z)
                assertTrue(ar <= 1.2, "anchor hangs on the inner part of the ring, got radius $ar")
            }
        }
        assertTrue(sawFade && sawGone, "sweep never came near the pole: fade=$sawFade gone=$sawGone")
        // the pole itself
        val p3 = DoubleArray(3)
        HopfFiber.projectInto(0.0, 0.0, 0.0, 1.0, maxR, p3)
        assertTrue(abs(sqrt(p3[0] * p3[0] + p3[1] * p3[1] + p3[2] * p3[2]) - maxR) < 1e-9)
        // bad arguments never throw inside a frame loop
        assertEquals(0, w.sampleRing(3, FloatArray(10), 0, 128))
        assertEquals(0, w.sampleRing(99, buf))
        assertEquals(0, w.sampleRing(RingIds.NONE, buf))
    }

    @Test
    fun tenRingsByShelfLatitudeAndEvenLongitude() {
        val w = world()
        assertEquals(10, w.rings.size)
        for (shelf in Shelf.entries) {
            val rs = w.rings.filter { it.shelf == shelf }
            assertTrue(rs.all { it.latitude == w.config.shelfLatitudes.getValue(shelf) })
            val gaps = rs.zipWithNext { a, b -> b.longitude - a.longitude }
            for (g in gaps) assertTrue(abs(g - 2.0 * PI / rs.size) < 1e-12)
        }
        assertEquals(listOf(6, 3, 1), Shelf.entries.map { s -> w.rings.count { it.shelf == s } })
    }

    @Test
    fun spinDiscsStayBetweenTwoAndFiveAndFollowLobeSwitches() {
        val w = world()
        val cfg = w.config
        val validPeriods = (0 until 23).map { (7 + it) * 29.6 }
        var t = 497500.0 * 3600.0 - 40.0
        val end = t + 3.2 * 3600.0
        var lastSwitches = -1
        var switchEvents = 0
        val sizes = HashSet<Int>()
        val triads = HashSet<TriadKind>()
        val ids = HashSet<Int>()
        val ringBuf = FloatArray(3 * 128)
        var minGap = Double.MAX_VALUE
        while (t < end) {
            w.advanceTo(t)
            val discs = w.spinDiscs
            assertTrue(discs.size in cfg.spinDiscMin..cfg.spinDiscMax, "disc count ${discs.size} at t=$t")
            sizes.add(discs.size)
            for (d in discs) {
                assertTrue(d.alpha in 0.0..1.0)
                assertTrue(validPeriods.any { abs(it - d.periodSeconds) < 1e-9 }, "period ${d.periodSeconds}")
                assertTrue(d.phase >= 0.0 && d.phase < 2.0 * PI)
                assertTrue(d.nearRing in 0 until 10)
                assertEquals(cfg.spinDiscRadius, d.radius)
                assertTrue(d.bornAtUnixSeconds <= t)
                if (d.triad == TriadKind.ORIGINAL) {
                    assertEquals(0x0000ff, d.discColor); assertEquals(0xff0000, d.strokeColor); assertEquals(0xffff00, d.wedgeColor)
                }
                triads.add(d.triad); ids.add(d.id)
                // near its ring, never on it
                if (d.alpha > 0.0 && w.ringAlpha(d.nearRing) == 1.0) {
                    w.sampleRing(d.nearRing, ringBuf)
                    var best = Double.MAX_VALUE
                    for (i in 0 until 128) {
                        val dx = ringBuf[3 * i] - d.position.x; val dy = ringBuf[3 * i + 1] - d.position.y; val dz = ringBuf[3 * i + 2] - d.position.z
                        val r = sqrt(dx * dx + dy * dy + dz * dz)
                        if (r < best) best = r
                    }
                    if (best < minGap) minGap = best
                    assertTrue(best < 3.0, "disc ${d.id} is $best away from ring ${d.nearRing}")
                }
            }
            val sw = w.lobeSwitchCount
            if (lastSwitches >= 0 && sw > lastSwitches) switchEvents += sw - lastSwitches
            lastSwitches = sw
            t += 3.7
        }
        println("DISCS sizes=$sizes triads=$triads distinct=${ids.size} switches=$switchEvents minGapToRing=$minGap")
        assertTrue(switchEvents > 60, "lobe switches in 3 h: $switchEvents")
        assertTrue(sizes.size >= 3, "count should wander, saw $sizes")
        assertEquals(3, triads.size, "all three triad kinds appear")
        assertTrue(ids.size > 30, "discs come and go, saw ${ids.size}")
        assertTrue(minGap > 0.02, "a disc sat on its ring: $minGap")
    }

    @Test
    fun visitorBaseIsOnS2AndWanders() {
        val w = world()
        var t = 1790000000.0
        var minC = 1.0; var maxC = -1.0
        for (k in 0 until 600) {
            w.advanceTo(t); t += 6.0
            val b = w.visitorBase
            assertTrue(abs(b.x * b.x + b.y * b.y + b.z * b.z - 1.0) < 1e-12)
            if (b.z < minC) minC = b.z
            if (b.z > maxC) maxC = b.z
        }
        println("VISITOR base c range $minC .. $maxC")
        assertTrue(minC < -0.4 && maxC > 0.4, "visitor should cross the shelf latitudes: $minC..$maxC")
    }
}
