package bar.verdantbloom.bloom.api

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloomApiTest {
    @Test
    fun pourListHasTenRingsOnThreeShelves() {
        val rings = PourList.rings()
        assertEquals(10, rings.size)
        assertEquals(listOf(6, 3, 1), Shelf.entries.map { s -> rings.count { it.shelf == s } })
        assertEquals("W01", rings[0].orderCode)
        assertEquals("T01", rings[9].orderCode)
        assertTrue(rings.single { it.guestTap }.id == "vision")
        rings.forEachIndexed { i, r ->
            assertEquals(i, r.index)
            val b = r.base
            assertTrue(abs(sqrt(b.x * b.x + b.y * b.y + b.z * b.z) - 1.0) < 1e-12)
            assertTrue(b.z > -0.99)
        }
    }

    @Test
    fun timeOverrideParsing() {
        assertEquals(0.0, TimeOverride.parse("#t=0"))
        assertEquals(86400.0, TimeOverride.parse("#pour/mini&t=86400"))
        assertNull(TimeOverride.parse("#list"))
        assertNull(TimeOverride.parse("#t=abc"))
        assertNull(TimeOverride.parse("#t=-5"))
        assertNull(TimeOverride.parse(""))
    }

    @Test
    fun overrideClockStartsAtOverrideAndFlows() {
        var wall = 1_000_000.0
        val clock = TimeOverride.clockFor("#t=0") { wall }
        assertEquals(0.0, clock.nowUnixSeconds())
        wall += 2.5
        assertEquals(2.5, clock.nowUnixSeconds())
    }

    @Test
    fun stubWorldIsDeterministicAndSamplesAllRings() {
        val a = StubBloomWorld()
        val b = StubBloomWorld()
        a.advanceTo(1234.5)
        b.advanceTo(99.0)
        b.advanceTo(1234.5)
        val n = a.config.fiberSegments
        val fa = FloatArray(3 * n)
        val fb = FloatArray(3 * n)
        for (id in 0 until RingIds.COUNT) {
            val wrote = a.sampleRing(id, fa)
            assertEquals(wrote, b.sampleRing(id, fb))
            assertEquals(if (id == RingIds.GHOST) 0 else n, wrote)
            assertTrue(fa.contentEquals(fb))
        }
        a.ghostEnabled = true
        assertEquals(n, a.sampleRing(RingIds.GHOST, fa))
        assertTrue(a.spinDiscs.size in a.config.spinDiscMin..a.config.spinDiscMax)
    }

    @Test
    fun rhoIsClamped() {
        val w = StubBloomWorld()
        w.rho = 5.0
        assertEquals(25.0, w.rho)
        w.rho = 99.0
        assertEquals(45.0, w.rho)
    }

    @Test
    fun paletteHexParsing() {
        assertEquals(0x020a07, PaletteCss.parseHex(" #020a07 "))
        assertEquals(0xffaa00, PaletteCss.parseHex("#fa0"))
        assertNull(PaletteCss.parseHex("rgb(1,2,3)"))
        assertTrue(Palettes.forLocalHour(23).night)
        assertTrue(!Palettes.forLocalHour(12).night)
    }
}
