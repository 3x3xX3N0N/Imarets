package com.example.spacegraphkt

import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.external.VecTween
import com.example.spacegraphkt.zui.Easing
import com.example.spacegraphkt.zui.HashRouter
import com.example.spacegraphkt.zui.HashState
import com.example.spacegraphkt.zui.LodLevel
import com.example.spacegraphkt.zui.LodThresholds
import com.example.spacegraphkt.zui.PinchTracker
import com.example.spacegraphkt.zui.Route
import com.example.spacegraphkt.zui.TapSlop
import com.example.spacegraphkt.zui.Tour
import com.example.spacegraphkt.zui.TourStop
import com.example.spacegraphkt.zui.Tween
import com.example.spacegraphkt.zui.TweenGroup
import com.example.spacegraphkt.zui.ViewMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TweenTest {
    @Test
    fun easingsHitBothEndsAndClamp() {
        for (e in listOf(Easing.LINEAR, Easing.OUT_QUAD, Easing.OUT_CUBIC, Easing.OUT_QUART, Easing.IN_OUT_CUBIC)) {
            assertEquals(0.0, e.ease(0.0))
            assertEquals(1.0, e.ease(1.0))
            assertEquals(0.0, e.ease(-3.0))
            assertEquals(1.0, e.ease(7.0))
        }
    }

    @Test
    fun easeOutIsMonotonicAndFrontLoaded() {
        var last = -1.0
        for (i in 0..100) {
            val v = Easing.OUT_CUBIC.ease(i / 100.0)
            assertTrue(v >= last, "not monotonic at $i")
            last = v
        }
        assertEquals(0.875, Easing.OUT_CUBIC.ease(0.5))
        assertTrue(Easing.OUT_CUBIC.ease(0.25) > 0.5, "ease-out covers more than half the way in the first quarter")
    }

    @Test
    fun tweenSamplesArePureAndLandExactly() {
        val t = Tween(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.1 + 0.2, -100.0), startMs = 1000.0, durationMs = 400.0)
        val out = DoubleArray(2)
        assertFalse(t.sample(1000.0, out))
        assertEquals(0.0, out[0]); assertEquals(100.0, out[1])
        assertFalse(t.sample(1200.0, out))
        assertEquals(0.875 * (0.1 + 0.2), out[0]); assertEquals(100.0 - 200.0 * 0.875, out[1])
        // sampling backwards gives the same answer as before: no hidden state
        t.sample(1000.0, out)
        assertEquals(0.0, out[0])
        assertTrue(t.sample(99999.0, out))
        assertEquals(0.1 + 0.2, out[0], "end value is copied, not recomputed")
        assertEquals(-100.0, out[1])
        assertTrue(t.isFinished(1400.0))
        assertFalse(t.isFinished(1399.0))
    }

    @Test
    fun zeroDurationIsFinishedImmediately() {
        val out = DoubleArray(1)
        assertTrue(Tween(doubleArrayOf(1.0), doubleArrayOf(2.0), 0.0, 0.0).sample(0.0, out))
        assertEquals(2.0, out[0])
    }

    @Test
    fun mismatchedSizesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Tween(doubleArrayOf(1.0), doubleArrayOf(1.0, 2.0), 0.0, 1.0) }
    }

    @Test
    fun groupOverwritesKillsAndCompletes() {
        val g = TweenGroup<String>()
        var a = 0.0
        var done = 0
        g.start("a", Tween(doubleArrayOf(0.0), doubleArrayOf(10.0), 0.0, 100.0), { done++ }) { a = it[0] }
        g.start("a", Tween(doubleArrayOf(0.0), doubleArrayOf(20.0), 0.0, 100.0), { done += 10 }) { a = it[0] }
        assertEquals(1, g.activeCount, "second tween on the same key replaces the first")
        g.update(50.0)
        assertEquals(17.5, a)
        g.update(100.0)
        assertEquals(20.0, a)
        assertEquals(10, done, "only the surviving tween completes")
        assertFalse(g.isActive("a"))

        g.start("b", Tween(doubleArrayOf(0.0), doubleArrayOf(1.0), 0.0, 100.0), { done = -1 }) { a = it[0] }
        assertTrue(g.kill("b"))
        g.update(1000.0)
        assertEquals(10, done, "a killed tween never calls onDone")
        assertEquals(20.0, a)
    }

    @Test
    fun completionCallbackMayStartANewTween() {
        val g = TweenGroup<String>()
        var v = 0.0
        g.start("k", Tween(doubleArrayOf(0.0), doubleArrayOf(1.0), 0.0, 10.0), {
            g.start("k", Tween(doubleArrayOf(1.0), doubleArrayOf(5.0), 10.0, 10.0)) { v = it[0] }
        }) { v = it[0] }
        g.update(10.0)
        assertTrue(g.isActive("k"))
        g.update(20.0)
        assertEquals(5.0, v)
    }

    @Test
    fun vectorTweensAreKeyedByIdentityNotByValue() {
        // THREE.Vector3 has a component-wise equals(); two equal-valued vectors must still tween independently
        val p = THREE.Vector3(0.0, 0.0, 0.0)
        val q = THREE.Vector3(0.0, 0.0, 0.0)
        VecTween.to(p, 10.0, 0.0, 0.0, 1.0, nowMs = 0.0)
        VecTween.to(q, 0.0, 10.0, 0.0, 1.0, nowMs = 0.0)
        assertTrue(VecTween.isTweening(p) && VecTween.isTweening(q))
        VecTween.killTweensOf(q)
        assertTrue(VecTween.isTweening(p))
        var arrived = false
        VecTween.to(q, 0.0, 4.0, 0.0, 1.0, nowMs = 0.0) { arrived = true }
        VecTween.update(2000.0)
        assertEquals(10.0, p.x); assertEquals(4.0, q.y)
        assertTrue(arrived)
    }
}

class HashRouterTest {
    @Test
    fun parsesEveryRoute() {
        assertEquals(HashState(), HashRouter.parse(""))
        assertEquals(HashState(), HashRouter.parse("#"))
        assertEquals(Route.Pour("mini"), HashRouter.parse("#pour/mini").route)
        assertEquals(Route.Tab, HashRouter.parse("#tab").route)
        assertEquals(Route.PourList, HashRouter.parse("#list").route)
        assertEquals(Route.Sign, HashRouter.parse("sign").route)
    }

    @Test
    fun timeOverrideCoexistsWithRoutes() {
        assertEquals(HashState(Route.Home, "0"), HashRouter.parse("#t=0"))
        assertEquals(HashState(Route.Pour("mini"), "86400"), HashRouter.parse("#pour/mini&t=86400"))
        assertEquals(HashState(Route.PourList, "5"), HashRouter.parse("#t=5&list"))
        assertEquals(1700000000.5, HashRouter.parse("#tab&t=1700000000.5").tSeconds)
    }

    @Test
    fun rubbishIsIgnoredNeverThrown() {
        assertEquals(HashState(), HashRouter.parse("#nonsense"))
        assertEquals(Route.Home, HashRouter.parse("#pour/").route)
        assertEquals(Route.Home, HashRouter.parse("#pour/<img src=x onerror=alert(1)>").route)
        assertEquals(Route.Home, HashRouter.parse("#pour/a/b").route)
        assertNull(HashRouter.parse("#t=-5").t)
        assertNull(HashRouter.parse("#t=abc").t)
        assertNull(HashRouter.parse("#t=1e9").t)
        assertNull(HashRouter.parse("#t=").t)
        assertNull(HashRouter.parse("#t=1.2.3").t)
        assertEquals(HashState(), HashRouter.parse("#&&&"))
        assertEquals("7", HashRouter.parse("#t=7&t=9").t, "first t wins")
        assertEquals(Route.Tab, HashRouter.parse("#tab&list").route, "first route wins")
    }

    @Test
    fun emitIsCanonicalAndRoundTrips() {
        assertEquals("", HashRouter.emit(HashState()))
        assertEquals("#pour/flagship", HashRouter.emit(HashState(Route.Pour("flagship"))))
        assertEquals("#t=0", HashRouter.emit(HashState(t = "0")))
        assertEquals("#list&t=0", HashRouter.emit(HashState(Route.PourList, "0")))
        for (hash in listOf("", "#tab", "#list", "#sign", "#pour/mini", "#t=0", "#pour/coder&t=1700000000", "#sign&t=12.5&renderer=2d")) {
            assertEquals(hash, HashRouter.emit(HashRouter.parse(hash)), "round trip of '$hash'")
        }
        assertEquals("#list&t=5", HashRouter.emit(HashRouter.parse("#t=5&list")), "route is emitted first")
    }

    @Test
    fun navigateKeepsTheClockAndExtras() {
        assertEquals("#pour/dark&t=0", HashRouter.navigate("#t=0", Route.Pour("dark")))
        assertEquals("#t=0", HashRouter.navigate("#pour/dark&t=0", Route.Home))
        assertEquals("#tab&t=3&x=y", HashRouter.navigate("#list&t=3&x=y", Route.Tab))
        assertEquals("", HashRouter.navigate("#sign", Route.Home))
    }

    @Test
    fun emittedTimeIsReadableByTheBloomApiSyntax() {
        // bloom-api TimeOverride wants `t=` at the start of the hash or right after '&'
        val hash = HashRouter.emit(HashState(Route.Pour("mini"), "0"))
        assertTrue(hash.contains("&t=0") || hash.startsWith("#t=0"))
    }
}

class TourTest {
    private fun tour(wrap: Boolean = true) = Tour(
        listOf(TourStop("a", "A", Route.Pour("a")), TourStop("b", "B", Route.Pour("b")), TourStop("c", "C", Route.Tab)), wrap,
    )

    @Test
    fun startsAtTheOverview() {
        val t = tour()
        assertNull(t.current)
        assertEquals(-1, t.index)
        assertEquals("- / 3", t.positionLabel)
        assertEquals("a", t.next()?.id)
        assertEquals("1 / 3", t.positionLabel)
    }

    @Test
    fun prevFromTheOverviewGoesToTheLastStop() {
        assertEquals("c", tour().prev()?.id)
        assertEquals("c", tour(wrap = false).prev()?.id)
    }

    @Test
    fun wrapsBothWays() {
        val t = tour()
        t.next(); t.next(); t.next()
        assertEquals("c", t.current?.id)
        assertEquals("a", t.next()?.id)
        assertEquals("c", t.prev()?.id)
        assertTrue(t.hasNext && t.hasPrev)
    }

    @Test
    fun withoutWrapItStopsAtTheEnds() {
        val t = tour(wrap = false)
        t.goTo("c")
        assertFalse(t.hasNext)
        assertEquals("c", t.next()?.id)
        t.goTo(0)
        assertFalse(t.hasPrev)
        assertEquals("a", t.prev()?.id)
        assertEquals(0, t.index)
    }

    @Test
    fun everyStopIsReachableWithNextAlone() {
        val t = tour()
        val seen = HashSet<String>()
        repeat(t.size) { seen.add(t.next()!!.id) }
        assertEquals(setOf("a", "b", "c"), seen)
    }

    @Test
    fun listenersFireOnRealMovesOnly() {
        val t = tour()
        val log = ArrayList<String>()
        val remove = t.addListener { stop, index -> log.add("${stop?.id}@$index") }
        t.next()
        t.goTo("a")
        t.goTo("nope")
        t.goTo(99)
        t.sync("c")
        t.prev()
        t.leave()
        t.leave()
        assertEquals(listOf("a@0", "b@1", "null@-1"), log)
        remove()
        t.next()
        assertEquals(3, log.size)
    }

    @Test
    fun syncFollowsWithoutNotifying() {
        val t = tour()
        var fired = 0
        t.addListener { _, _ -> fired++ }
        t.sync("b")
        assertEquals("b", t.current?.id)
        t.sync("unknown")
        assertNull(t.current)
        t.sync(null)
        assertEquals(0, fired)
    }

    @Test
    fun changeListenersSeeEveryChangeIncludingSync() {
        val t = tour()
        val nav = ArrayList<String?>()
        val shown = ArrayList<String?>()
        t.addListener { stop, _ -> nav.add(stop?.id) }
        t.addChangeListener { stop, _ -> shown.add(stop?.id) }
        t.next()
        t.sync("c")
        t.sync("c")
        t.sync(null)
        t.prev()
        assertEquals(listOf<String?>("a", "c"), nav)
        assertEquals(listOf<String?>("a", "c", null, "c"), shown)
    }

    @Test
    fun setStopsKeepsTheCurrentStopById() {
        val t = tour()
        t.goTo("b")
        t.setStops(listOf(TourStop("z"), TourStop("b")))
        assertEquals(1, t.index)
        t.setStops(listOf(TourStop("z")))
        assertNull(t.current)
    }

    @Test
    fun emptyTourAndDuplicateIds() {
        val t = Tour()
        assertNull(t.next()); assertNull(t.prev())
        assertFalse(t.hasNext || t.hasPrev)
        assertFailsWith<IllegalArgumentException> { Tour(listOf(TourStop("a"), TourStop("a"))) }
    }
}

class LodTest {
    private val lod = LodThresholds(nearDistance = 500.0, midDistance = 1000.0, hysteresis = 0.1)

    @Test
    fun plainLevelsFromDistance() {
        assertEquals(LodLevel.NEAR, lod.levelFor(10.0))
        assertEquals(LodLevel.NEAR, lod.levelFor(500.0))
        assertEquals(LodLevel.MID, lod.levelFor(500.1))
        assertEquals(LodLevel.MID, lod.levelFor(1000.0))
        assertEquals(LodLevel.FAR, lod.levelFor(1000.1))
        assertEquals(LodLevel.FAR, lod.levelFor(Double.POSITIVE_INFINITY))
    }

    @Test
    fun hysteresisStopsFlickerAtABoundary() {
        // zooming out from NEAR: still NEAR until 550
        assertEquals(LodLevel.NEAR, lod.levelFor(540.0, LodLevel.NEAR))
        assertEquals(LodLevel.MID, lod.levelFor(551.0, LodLevel.NEAR))
        // zooming in from MID: still MID until below 450
        assertEquals(LodLevel.MID, lod.levelFor(460.0, LodLevel.MID))
        assertEquals(LodLevel.NEAR, lod.levelFor(449.0, LodLevel.MID))
        // same around the far boundary
        assertEquals(LodLevel.MID, lod.levelFor(1090.0, LodLevel.MID))
        assertEquals(LodLevel.FAR, lod.levelFor(1101.0, LodLevel.MID))
        assertEquals(LodLevel.FAR, lod.levelFor(910.0, LodLevel.FAR))
        assertEquals(LodLevel.MID, lod.levelFor(899.0, LodLevel.FAR))
        // jitter of +-5 around 500 never changes the level once it is set
        var level = lod.levelFor(495.0)
        var changes = 0
        for (i in 0 until 50) {
            val next = lod.levelFor(if (i % 2 == 0) 505.0 else 495.0, level)
            if (next != level) changes++
            level = next
        }
        assertEquals(0, changes)
    }

    @Test
    fun bigJumpsSkipLevels() {
        assertEquals(LodLevel.FAR, lod.levelFor(5000.0, LodLevel.NEAR))
        assertEquals(LodLevel.NEAR, lod.levelFor(50.0, LodLevel.FAR))
    }

    @Test
    fun nanKeepsThePreviousLevel() {
        assertEquals(LodLevel.MID, lod.levelFor(Double.NaN, LodLevel.MID))
        assertEquals(LodLevel.FAR, lod.levelFor(Double.NaN))
    }

    @Test
    fun attributeValuesAreStable() {
        assertEquals(listOf("far", "mid", "near"), LodLevel.entries.map { it.attr })
        assertEquals("data-lod", LodLevel.DATA_ATTRIBUTE)
        assertEquals(LodLevel.MID, LodLevel.fromAttr("mid"))
        assertNull(LodLevel.fromAttr("huge"))
    }

    @Test
    fun badThresholdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LodThresholds(500.0, 400.0) }
        assertFailsWith<IllegalArgumentException> { LodThresholds(hysteresis = 0.9) }
    }

    @Test
    fun defaultsPutTheOverviewAtMidAndAFocusedCardAtNear() {
        val d = LodThresholds()
        assertEquals(LodLevel.MID, d.levelFor(700.0))
        assertEquals(LodLevel.NEAR, d.levelFor(300.0))
        assertEquals(LodLevel.FAR, d.levelFor(2500.0))
    }
}

class ViewMathTest {
    private val fov = 70.0 * PI / 180.0

    @Test
    fun fitDistanceIsWidthLimitedOnAPhone() {
        val desktop = ViewMath.fitDistance(320.0, 200.0, fov, 16.0 / 9.0, padding = 1.0)
        val phone = ViewMath.fitDistance(320.0, 200.0, fov, 375.0 / 667.0, padding = 1.0)
        assertTrue(phone > desktop * 2, "portrait needs to stand much further back: $phone vs $desktop")
        // at that distance the card spans exactly the viewport width
        val visibleWidth = 2.0 * kotlin.math.tan(fov / 2) * phone * (375.0 / 667.0)
        assertTrue(abs(visibleWidth - 320.0) < 1e-9)
    }

    @Test
    fun worldPerPixelMatchesTheFrustum() {
        val wpp = ViewMath.worldPerPixel(700.0, fov, 800.0)
        assertTrue(abs(wpp * 800.0 - 2.0 * kotlin.math.tan(fov / 2) * 700.0) < 1e-9)
    }

    @Test
    fun pinchGivesScaleAndMidpointPan() {
        val p = PinchTracker()
        p.begin(100.0, 100.0, 200.0, 100.0)
        val spread = p.move(50.0, 100.0, 250.0, 100.0)
        assertEquals(2.0, spread.scale)
        assertEquals(0.0, spread.panX); assertEquals(0.0, spread.panY)
        val drag = p.move(60.0, 130.0, 260.0, 130.0)
        assertEquals(1.0, drag.scale)
        assertEquals(10.0, drag.panX); assertEquals(30.0, drag.panY)
        assertEquals(160.0, drag.centerX)
        val squeeze = p.move(110.0, 130.0, 210.0, 130.0)
        assertEquals(0.5, squeeze.scale)
        // fingers on top of each other do not explode
        assertEquals(1.0, p.move(160.0, 130.0, 161.0, 130.0).scale)
        p.end()
        assertFalse(p.active)
    }

    @Test
    fun tapSlopIsWiderForFingers() {
        assertTrue(TapSlop.isTap(0.0, 0.0, 3.0, 0.0, "mouse"))
        assertFalse(TapSlop.isTap(0.0, 0.0, 8.0, 0.0, "mouse"))
        assertTrue(TapSlop.isTap(0.0, 0.0, 8.0, 0.0, "touch"))
        assertFalse(TapSlop.isTap(0.0, 0.0, 10.0, 10.0, "touch"))
    }
}
