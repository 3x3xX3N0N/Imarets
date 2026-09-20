package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.RingIds
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class WorldClockTest {
    private fun world(): HopfLorenzWorld = BloomCore.createWorld() as HopfLorenzWorld

    /** Everything observable, as exact values (Float/Double equality is bit equality here, no NaNs). */
    private fun snapshot(w: BloomWorld): List<Any> {
        val out = ArrayList<Any>()
        out.add(w.worldState); out.add(w.visitorState); out.add(w.worldRotation); out.add(w.visitorBase)
        out.add(w.lobeSwitchCount); out.add(w.spinDiscs)
        val buf = FloatArray(3 * 64)
        for (ring in 0 until RingIds.COUNT) {
            val n = w.sampleRing(ring, buf, 0, 64)
            out.add(n)
            if (n > 0) out.add(buf.toList())
            out.add(w.ringAlpha(ring)); out.add(w.ringAnchor(ring))
        }
        return out
    }

    @Test
    fun oneJumpEqualsManySmallCalls() {
        val targets = listOf(
            1790000000.25,              // mid hour
            497300.0 * 3600.0 + 4.7,    // just after the top of an hour
            497300.0 * 3600.0 - 5.3,    // inside the hourly blend window
            0.0,
        )
        for (t in targets) {
            val a = world().also { it.ghostEnabled = true; it.advanceTo(t) }
            val b = world().also { it.ghostEnabled = true }
            var s = t - 600.0
            while (s < t) { b.advanceTo(s); s += 0.37 }
            b.advanceTo(t)
            assertEquals(snapshot(a), snapshot(b), "jump vs small steps at t=$t")

            // any call order: forwards past it, then back
            val c = world().also { it.ghostEnabled = true }
            c.advanceTo(t + 5000.0)
            c.advanceTo(t)
            assertEquals(snapshot(a), snapshot(c), "back-jump at t=$t")
        }
    }

    @Test
    fun goldenOriginBloom() {
        val w = world().also { it.advanceTo(0.0) }
        println("GOLDEN t=0 ${w.worldState} ${w.worldRotation} lobes=${w.lobeSwitchCount} discs=${w.spinDiscs.size}")
        assertEquals(-15.493364965129265, w.worldState.x)
        assertEquals(-15.428209513096917, w.worldState.y)
        assertEquals(36.647435465590824, w.worldState.z)
        assertEquals(GOLDEN_0_QW, w.worldRotation.w)
        assertEquals(GOLDEN_0_QX, w.worldRotation.x)
        assertEquals(GOLDEN_0_QY, w.worldRotation.y)
        assertEquals(GOLDEN_0_QZ, w.worldRotation.z)
        assertEquals(0, w.lobeSwitchCount)
        assertEquals(3, w.spinDiscs.size)
        assertEquals(w.worldState, w.visitorState)
    }

    @Test
    fun goldenLaterInstant() {
        val w = world().also { it.advanceTo(1790000000.25) }
        println("GOLDEN t=1790000000.25 ${w.worldState} ${w.worldRotation} lobes=${w.lobeSwitchCount} discs=${w.spinDiscs.size}")
        assertEquals(-0.2329384712746158, w.worldState.x)
        assertEquals(-1.223884915212022, w.worldState.y)
        assertEquals(19.345942211552497, w.worldState.z)
        assertEquals(GOLDEN_1_QW, w.worldRotation.w)
        assertEquals(GOLDEN_1_QX, w.worldRotation.x)
        assertEquals(GOLDEN_1_QY, w.worldRotation.y)
        assertEquals(GOLDEN_1_QZ, w.worldRotation.z)
        assertEquals(10, w.lobeSwitchCount)
        assertEquals(4, w.spinDiscs.size)
    }

    @Test
    fun hashPrngIsIntegerExact() {
        assertEquals(-1651612217, HashPrng.mix(1))
        assertEquals(521788364, HashPrng.seedForEpoch(0L, 1))
        assertEquals(-1125295979, HashPrng.seedForEpoch(-1L, 1))
        val p = HashPrng(42)
        for (i in 0 until 1000) {
            val u = p.nextUnit()
            assertTrue(u >= 0.0 && u < 1.0)
            assertTrue(p.nextInt(23) in 0 until 23)
        }
    }

    @Test
    fun fullHourCatchUpIsUnderBudget() {
        var best = Double.MAX_VALUE
        var steps = 0
        for (i in 0 until 5) {
            val w = world()
            val mark = TimeSource.Monotonic.markNow()
            w.advanceTo((497301.0 + i) * 3600.0 + 3599.95) // worst case: whole hour + the next hour's lead-in
            val ms = mark.elapsedNow().inWholeMicroseconds / 1000.0
            if (ms < best) best = ms
            steps = w.lastCatchUpSteps
        }
        println("CATCHUP best-of-5 ms=$best steps=$steps")
        assertTrue(steps >= 36000, "expected a full hour of steps, got $steps")
        assertTrue(best < 100.0, "full 3600 s catch-up took $best ms (budget 100 ms)")
    }

    @Test
    fun rotationStaysUnitAndHourlyHandOverIsContinuous() {
        val w = world()
        val top = 497400.0 * 3600.0
        var t = top - 20.0
        w.advanceTo(t)
        var prev = w.worldRotation
        var prevState = w.worldState
        while (t < top + 5.0) {
            t += 0.05
            w.advanceTo(t)
            val q = w.worldRotation
            val n = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
            assertTrue(abs(n - 1.0) < 1e-12, "norm $n at $t")
            // q and -q are the same rings (antipodal map sends every great circle to itself); the hand-over
            // may end on either, so continuity is measured on the rotation, not on the sign
            val dPlus = sqrt((q.w - prev.w).sq() + (q.x - prev.x).sq() + (q.y - prev.y).sq() + (q.z - prev.z).sq())
            val dMinus = sqrt((q.w + prev.w).sq() + (q.x + prev.x).sq() + (q.y + prev.y).sq() + (q.z + prev.z).sq())
            val dq = if (dPlus < dMinus) dPlus else dMinus
            assertTrue(dq < 0.05, "rotation jumped by $dq at ${t - top}")
            val s = w.worldState
            val ds = sqrt((s.x - prevState.x).sq() + (s.y - prevState.y).sq() + (s.z - prevState.z).sq())
            assertTrue(ds < 2.5, "state jumped by $ds at ${t - top}")
            prev = q; prevState = s
        }
    }

    private fun Double.sq() = this * this

    @Test
    fun visitorEqualsWorldUntilFirstPerturbationThenDiverges() {
        val w = world()
        val t0 = 497223.0 * 3600.0 + 3000.0
        w.advanceTo(t0)
        assertEquals(w.worldState, w.visitorState)
        // crosses an hour boundary unperturbed: still the world
        var t = t0
        while (t < t0 + 1500.0) { t += 1.3; w.advanceTo(t); assertEquals(w.worldState, w.visitorState) }
        assertTrue(!w.visitorDiverged)

        w.perturb(PerturbKind.SWITCH, 1.0)
        assertTrue(w.visitorDiverged)
        assertNotEquals(w.worldState, w.visitorState)
        val d0 = dist(w)
        assertTrue(d0 > 0.0 && d0 < 1e-8, "one switch flip is about 1e-9, got $d0")

        var maxD = 0.0
        val end = t + 2600.0 // stays inside one hour: divergence must come from chaos, not from the re-seed
        while (t < end) { t += 2.0; w.advanceTo(t); val d = dist(w); if (d > maxD) maxD = d }
        println("VISITOR max divergence after 2600 s from 1e-9: $maxD")
        assertTrue(maxD > 1.0, "chaos should have amplified 1e-9 to order 1, got $maxD")
        // the world itself is untouched by the visitor
        val fresh = world().also { it.advanceTo(t) }
        assertEquals(fresh.worldState, w.worldState)
        assertEquals(fresh.worldRotation, w.worldRotation)
    }

    private fun dist(w: BloomWorld): Double {
        val a = w.worldState; val b = w.visitorState
        return sqrt((a.x - b.x).sq() + (a.y - b.y).sq() + (a.z - b.z).sq())
    }

    @Test
    fun perturbationsScaleByKindAndClamp() {
        fun kick(kind: PerturbKind, magnitude: Double): Double {
            val w = world(); w.advanceTo(1790000000.0); w.perturb(kind, magnitude); return dist(w)
        }
        val pointerSlow = kick(PerturbKind.POINTER, 10.0)
        val pointerFast = kick(PerturbKind.POINTER, 2000.0)
        assertTrue(pointerFast > pointerSlow * 50, "$pointerFast vs $pointerSlow")
        assertTrue(kick(PerturbKind.BELL, 1e9) <= 1e-3 * sqrt(3.0) * 1.0001, "clamped to maxPerturbation per axis")
        assertTrue(kick(PerturbKind.SCROLL, 0.0) > 0.0, "nothing is inert")
        assertTrue(kick(PerturbKind.TILT, Double.NaN) > 0.0)
        val sw = kick(PerturbKind.SWITCH, 12345.0)
        assertTrue(abs(sw - 1e-9) < 1e-11, "switch flip is exactly switchEpsilon, got $sw")
        for (k in PerturbKind.entries) assertTrue(kick(k, 50.0) in 1e-13..2e-3, "kind $k")
    }

    @Test
    fun rhoKnobIsClampedAndOnlyMovesTheVisitor() {
        val w = world()
        w.rho = 5.0; assertEquals(25.0, w.rho)
        w.rho = 500.0; assertEquals(45.0, w.rho)
        w.rho = Double.NaN; assertEquals(45.0, w.rho)
        val t = 1790000000.0
        w.advanceTo(t); w.advanceTo(t + 600.0)
        val fresh = world().also { it.advanceTo(t + 600.0) }
        assertEquals(fresh.worldState, w.worldState)
        assertNotEquals(w.worldState, w.visitorState)
    }

    @Test
    fun neverSettlesAcrossTheRhoRange() {
        val cfg = BloomCore.DEFAULT_CONFIG
        for (rho in listOf(25.0, 26.5, 28.0, 33.3, 40.0, 45.0)) {
            for (seed in 0 until 3) {
                val track = EpochTrack(cfg, CoreTuning(), 1000L + seed, 10)
                val p = LorenzPoint(track.cur.x, track.cur.y, track.cur.z)
                val total = 400_000 // 1000 Lorenz time units = 11 hours of page time
                var switchesInLastQuarter = 0
                var minSpeed2 = Double.MAX_VALUE
                var maxAbs = 0.0
                for (i in 0 until total) {
                    val px = p.x; val py = p.y; val pz = p.z
                    p.step(cfg.sigma, rho, cfg.beta, cfg.lorenzDt)
                    if (i >= total * 3 / 4) {
                        if ((px >= 0.0) != (p.x >= 0.0)) switchesInLastQuarter++
                        val v2 = (p.x - px).sq() + (p.y - py).sq() + (p.z - pz).sq()
                        if (v2 < minSpeed2) minSpeed2 = v2
                    }
                    val m = maxOf(abs(p.x), abs(p.y), abs(p.z))
                    if (m > maxAbs) maxAbs = m
                }
                assertTrue(switchesInLastQuarter > 20, "rho=$rho seed=$seed settled: $switchesInLastQuarter lobe switches late on")
                assertTrue(minSpeed2 > 1e-10, "rho=$rho seed=$seed stopped moving")
                assertTrue(maxAbs < 120.0, "rho=$rho seed=$seed escaped: $maxAbs")
            }
        }
        // and through the public API: visitor at both ends of the knob keeps moving for an hour
        for (rho in listOf(-1.0, 1e6)) {
            val w = world(); w.rho = rho
            var t = 1790000000.0
            w.advanceTo(t)
            var last = w.visitorState
            for (i in 0 until 60) {
                t += 60.0; w.advanceTo(t)
                assertNotEquals(last, w.visitorState); last = w.visitorState
            }
        }
    }

    @Test
    fun everyHourStartsOnTheAttractor() {
        val cfg = BloomCore.DEFAULT_CONFIG
        for (h in -50L..150L) {
            val tr = EpochTrack(cfg, CoreTuning(), h * 7919L, 10)
            val c = tr.cur
            assertTrue(abs(c.x) < 25 && abs(c.y) < 32 && c.z > 0.5 && c.z < 55, "hour $h starts at ${c.x},${c.y},${c.z}")
            assertTrue(abs(c.qw * c.qw + c.qx * c.qx + c.qy * c.qy + c.qz * c.qz - 1.0) < 1e-12)
        }
    }

    @Test
    fun driftRateAndReducedMotionSlowButNeverStop() {
        fun turned(setup: (HopfLorenzWorld) -> Unit): Double {
            val w = world(); setup(w)
            val t = 1790000000.0
            w.advanceTo(t)
            val a = w.worldRotation
            var s = t
            while (s < t + 5.0) { s += 0.1; w.advanceTo(s) }
            val b = w.worldRotation
            return sqrt((a.w - b.w).sq() + (a.x - b.x).sq() + (a.y - b.y).sq() + (a.z - b.z).sq())
        }
        val normal = turned { }
        val reduced = turned { it.reducedMotion = true }
        val fast = turned { it.driftRate = 4.0 }
        val slowest = turned { it.reducedMotion = true; it.driftRate = 0.0 }
        println("DRIFT 5 s chord: normal=$normal reduced=$reduced fast=$fast slowest=$slowest")
        assertTrue(normal > 0.01, "bloom barely moves: $normal")
        assertTrue(reduced > 0.0 && reduced < normal / 20.0, "reduced motion: $reduced vs $normal")
        assertTrue(fast > normal * 2.5, "4x knob: $fast vs $normal")
        assertTrue(slowest > 0.0, "NEVER perfectly still")

        val w = world()
        w.driftRate = 0.0; assertEquals(0.02, w.driftRate)
        w.driftRate = 99.0; assertEquals(4.0, w.driftRate)
        assertTrue(w.effectiveRate > 0.0)

        // canonical state is unaffected by the display clock, and the offset relaxes back to 0
        var t = 1790000000.0
        w.advanceTo(t)
        for (i in 0 until 100) { t += 0.1; w.advanceTo(t) }
        assertTrue(w.displayOffsetSeconds > 20.0)
        assertEquals(world().also { it.advanceTo(t) }.worldState, w.worldState)
        w.driftRate = 1.0
        for (i in 0 until 3000) { t += 0.1; w.advanceTo(t) }
        assertEquals(0.0, w.displayOffsetSeconds)
        assertEquals(world().also { it.advanceTo(t) }.worldRotation, w.worldRotation)
    }

    @Test
    fun ghostIsTheUnperturbedWorld() {
        val w = world()
        val t = 1790000000.0
        w.advanceTo(t)
        val buf = FloatArray(3 * 32)
        assertEquals(0, w.sampleRing(RingIds.GHOST, buf, 0, 32))
        assertEquals(0.0, w.ringAlpha(RingIds.GHOST))
        w.ghostEnabled = true
        val g = FloatArray(3 * 32); val v = FloatArray(3 * 32)
        assertEquals(32, w.sampleRing(RingIds.GHOST, g, 0, 32))
        assertEquals(32, w.sampleRing(RingIds.VISITOR, v, 0, 32))
        assertEquals(g.toList(), v.toList(), "unperturbed visitor rides on the ghost")
        w.perturb(PerturbKind.BELL, 800.0)
        w.advanceTo(t + 1800.0)
        w.sampleRing(RingIds.GHOST, g, 0, 32); w.sampleRing(RingIds.VISITOR, v, 0, 32)
        assertNotEquals(g.toList(), v.toList())
        val clean = world().also { it.ghostEnabled = true; it.advanceTo(t + 1800.0) }
        val g2 = FloatArray(3 * 32); clean.sampleRing(RingIds.GHOST, g2, 0, 32)
        assertEquals(g2.toList(), g.toList(), "ghost ignores the visitor's perturbations")
    }
}

// Rotation goldens are filled from a run of this build (exact doubles; any change means the bloom changed).
internal const val GOLDEN_0_QW = 0.7007712858661619
internal const val GOLDEN_0_QX = 0.5928881571629101
internal const val GOLDEN_0_QY = -0.11425343165039252
internal const val GOLDEN_0_QZ = -0.3799334038454156
internal const val GOLDEN_1_QW = -0.24456497334676072
internal const val GOLDEN_1_QX = 0.5552661332813437
internal const val GOLDEN_1_QY = 0.6421708820855175
internal const val GOLDEN_1_QZ = 0.46849125204660075
