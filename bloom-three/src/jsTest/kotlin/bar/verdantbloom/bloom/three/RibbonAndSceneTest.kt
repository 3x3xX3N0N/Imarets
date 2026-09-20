package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.Palettes
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.StubBloomWorld
import bar.verdantbloom.bloom.api.Vec3
import bar.verdantbloom.three.THREE
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RibbonBuffersTest {
    private fun projector(distance: Double = 9.0): Projector {
        val p = Projector()
        p.setViewport(800.0, 600.0)
        p.setFromView(BloomView(distance = distance))
        return p
    }

    private fun circle(b: RibbonBuffers, ring: Int, radius: Double) {
        val o = b.centerOffset(ring)
        for (i in 0 until b.segments) {
            val a = 2 * PI * i / b.segments
            b.centers[o + i * 3] = (radius * cos(a)).toFloat()
            b.centers[o + i * 3 + 1] = (radius * sin(a)).toFloat()
            b.centers[o + i * 3 + 2] = 0f
        }
    }

    @Test
    fun bufferSizing() {
        val b = RibbonBuffers(RingIds.COUNT, 128)
        assertEquals(129 * 2, b.verticesPerRing)
        assertEquals(12 * 258, b.vertexCount)
        assertEquals(b.vertexCount * 3, b.positions.size)
        assertEquals(b.vertexCount * 4, b.shape.size)
        assertEquals(b.vertexCount * 4, b.color.size)
        assertEquals(b.vertexCount * 2, b.trim.size)
        assertEquals(12 * 128 * 6, b.indices.size)
        assertEquals(12 * 128 * 3, b.centers.size)
        assertEquals(12 * 128 * 3, b.screen.size)
        assertEquals(12 * 128, b.fade.size)
        assertEquals(11 * 128 * 6, b.indexCountFor(RingIds.GHOST), "draw range without the ghost slot")
        assertEquals(b.indexCount, b.indexCountFor(99))
    }

    @Test
    fun indicesStayInsideTheirOwnRingAndTheArcHasNoSeam() {
        val b = RibbonBuffers(3, 16)
        for (ring in 0 until 3) {
            val lo = ring * b.verticesPerRing
            val hi = lo + b.verticesPerRing
            for (k in ring * b.indicesPerRing until (ring + 1) * b.indicesPerRing) {
                assertTrue(b.indices[k] in lo until hi, "index ${b.indices[k]} of ring $ring")
            }
            assertEquals(0f, b.shape[lo * 4 + 1])
            assertEquals(1f, b.shape[(hi - 1) * 4 + 1], "closing pair has arc 1, so dashes do not run backwards")
            assertEquals(-1f, b.shape[lo * 4])
            assertEquals(1f, b.shape[(lo + 1) * 4])
        }
    }

    @Test
    fun ribbonIsAsWideOnScreenAsTheStyleSays() {
        val b = RibbonBuffers(1, 64)
        circle(b, 0, 2.0)
        val p = projector()
        val style = RingStyle().apply { coreHalfPx = 1.5; haloHalfPx = 10.0 }
        b.buildRing(0, style, 1.0, p, null, 0, 0.0, 4.0, 8.0)
        assertTrue(b.enabled[0])
        for (pair in listOf(0, 7, 31, 64)) {
            val v = pair * 2
            p.project(b.positions[v * 3].toDouble(), b.positions[v * 3 + 1].toDouble(), b.positions[v * 3 + 2].toDouble())
            val ax = p.outX; val ay = p.outY
            p.project(b.positions[v * 3 + 3].toDouble(), b.positions[v * 3 + 4].toDouble(), b.positions[v * 3 + 5].toDouble())
            val width = sqrt((p.outX - ax) * (p.outX - ax) + (p.outY - ay) * (p.outY - ay))
            // the ring lies in the focus plane, so the perspective width hint is exactly 1
            // offsets are perpendicular to the view RAY, so off-axis the radial width grows by 1 / cos(angle): under 3 % here
            assertTrue(abs(width - 20.0) < 0.6, "pair $pair is $width px wide, wanted 20")
            assertEquals(10f, b.shape[v * 4 + 2])
            assertEquals(1.5f, b.shape[v * 4 + 3])
        }
        // closing pair == first pair
        for (k in 0 until 6) assertEquals(b.positions[k], b.positions[64 * 2 * 3 + k])
    }

    @Test
    fun poleBlowUpIsClampedFadedAndNeverNaN() {
        val b = RibbonBuffers(1, 32)
        circle(b, 0, 2.0)
        val o = b.centerOffset(0)
        b.centers[o + 5 * 3] = Float.POSITIVE_INFINITY
        b.centers[o + 6 * 3 + 1] = Float.NaN
        b.centers[o + 7 * 3] = 5000f // far beyond maxRadius
        b.centers[o + 7 * 3 + 1] = 0f
        b.buildRing(0, RingStyle(), 1.0, projector(), null, 0, 0.0, 4.0, 8.0)

        assertTrue(b.positions.all { it.isFinite() }, "no NaN / Infinity reaches the GPU")
        assertTrue(b.color.all { it.isFinite() })
        assertEquals(0f, b.fade[5], "non-finite sample is invisible")
        assertEquals(0f, b.fade[6])
        assertEquals(0f, b.fade[7], "a sample at the clamp radius has fully faded")
        val r7 = sqrt(b.centers[o + 21] * b.centers[o + 21] + b.centers[o + 22] * b.centers[o + 22] + b.centers[o + 23] * b.centers[o + 23])
        assertTrue(abs(r7 - 8f) < 1e-3f, "clamped to maxRadius, got $r7")
        assertTrue(b.fade[20] > 0.5f, "the rest of the ring is untouched")
    }

    @Test
    fun partsBeyondFadeStartAreDimmerAndFarSideIsDimmerThanNearSide() {
        val b = RibbonBuffers(2, 32)
        circle(b, 0, 2.0)
        circle(b, 1, 6.0)
        val p = projector(distance = 20.0)
        b.buildRing(0, RingStyle(), 1.0, p, null, 0, 0.0, 4.0, 8.0)
        b.buildRing(1, RingStyle(), 1.0, p, null, 0, 0.0, 4.0, 8.0)
        assertTrue(b.fade[32] < b.fade[0], "radius 6 is between fadeStart 4 and max 8")

        // a ring in the XZ plane: sample 8 (z = +r) faces the camera, sample 24 (z = -r) is the far side
        val o = b.centerOffset(0)
        for (i in 0 until 32) {
            val a = 2 * PI * i / 32
            b.centers[o + i * 3] = (2 * cos(a)).toFloat(); b.centers[o + i * 3 + 1] = 0f; b.centers[o + i * 3 + 2] = (2 * sin(a)).toFloat()
        }
        b.buildRing(0, RingStyle(), 1.0, p, null, 0, 0.0, 4.0, 8.0)
        assertTrue(b.fade[8] > b.fade[24], "depth cue")
    }

    @Test
    fun hiddenRingsAndShortCounts() {
        val b = RibbonBuffers(1, 16)
        circle(b, 0, 2.0)
        b.buildRing(0, RingStyle(), 0.0, projector(), null, 0, 0.0, 4.0, 8.0)
        assertFalse(b.enabled[0], "ringAlpha 0 = hidden")
        assertTrue(b.fade.all { it == 0f })

        assertFalse(b.normalizeCount(0, 0))
        assertFalse(b.normalizeCount(0, 2))
        assertTrue(b.normalizeCount(0, 16))
        circle(b, 0, 2.0)
        val fourth = b.centers[3 * 3]
        assertTrue(b.normalizeCount(0, 8), "8 points stretched over 16 slots")
        assertEquals(fourth, b.centers[6 * 3], "slot 6 <- point 3")
    }

    @Test
    fun rippleMovesTheThreadAndFattensIt() {
        val calm = RibbonBuffers(1, 32)
        val rung = RibbonBuffers(1, 32)
        circle(calm, 0, 2.0)
        circle(rung, 0, 2.0)
        val ripple = RippleField()
        ripple.kick(1.0)
        ripple.advance(0.15)
        calm.buildRing(0, RingStyle(), 1.0, projector(), null, 0, 0.045, 4.0, 8.0)
        rung.buildRing(0, RingStyle(), 1.0, projector(), ripple, 0, 0.045, 4.0, 8.0)
        var moved = 0.0
        var widest = 0f
        for (i in 0 until 32) {
            moved = maxOf(moved, abs(rung.centers[i * 3] - calm.centers[i * 3]).toDouble(), abs(rung.centers[i * 3 + 1] - calm.centers[i * 3 + 1]).toDouble())
            widest = maxOf(widest, rung.shape[i * 2 * 4 + 3])
        }
        assertTrue(moved > 0.01 && moved < 0.2, "displacement is visible but small: $moved")
        assertTrue(widest > calm.shape[3], "thread is fatter on the crest")
    }
}

/** Scene objects are plain three objects + TSL node graphs: they can be built on node, only drawing needs a GPU. */
class BloomSceneGraphTest {
    private val view = BloomView(distance = 9.0)

    private fun graph(world: BloomWorld = StubBloomWorld(), options: BloomThreeOptions = BloomThreeOptions()): BloomSceneGraph {
        world.advanceTo(1_789_000_000.0)
        val g = BloomThree.createSceneGraph(world, Palettes.ABSINTHE_ABYSS, options)
        g.projector.setViewport(1000.0, 700.0)
        g.projector.setFromView(view)
        return g
    }

    private fun mesh(g: BloomSceneGraph, name: String): THREE.Mesh = assertNotNull(g.root.getObjectByName(name)).unsafeCast<THREE.Mesh>()

    @Test
    fun geometryWrapsTheKotlinArraysAndIsUpdatedInPlace() {
        val world = StubBloomWorld()
        val g = graph(world)
        val geometry = mesh(g, "vb-ring-thread").geometry
        assertSame(geometry, mesh(g, "vb-ring-glow").geometry, "glow and thread share ONE geometry")
        val position = geometry.getAttribute("position")
        assertSame<Any?>(g.buffers.positions, position.array, "zero copy: the attribute IS the FloatArray")
        assertSame<Any?>(g.buffers.color, geometry.getAttribute(RingLayer.ATTR_COLOR).array)
        assertEquals(g.buffers.vertexCount, position.count)
        assertEquals(g.buffers.indexCount, assertNotNull(geometry.index).count)
        assertEquals(THREE.DynamicDrawUsage, position.usage)

        g.update(1_789_000_000.0, 0.016)
        val before = g.buffers.positions[0]
        val versionBefore = position.asDynamic().version as Int
        world.advanceTo(1_789_000_040.0)
        g.update(1_789_000_040.0, 0.016)
        assertSame<Any?>(g.buffers.positions, geometry.getAttribute("position").array, "same array after a frame: nothing reallocated")
        assertTrue(g.buffers.positions[0] != before, "the stub world moved, so did the vertices")
        assertTrue((position.asDynamic().version as Int) > versionBefore, "needsUpdate was raised")
    }

    @Test
    fun tenModelsPlusVisitorAndTheGhostOnlyWhenEnabled() {
        val world = StubBloomWorld()
        val g = graph(world)
        g.update(1_789_000_000.0, 0.016)
        for (ring in 0..RingIds.VISITOR) assertTrue(g.buffers.enabled[ring], "ring $ring")
        assertFalse(g.buffers.enabled[RingIds.GHOST])
        val geometry = mesh(g, "vb-ring-thread").geometry
        assertEquals(g.buffers.indexCountFor(RingIds.GHOST), geometry.drawRange.count as Int)
        assertEquals(3, g.liveDiscCount, "the stub world has three discs")

        world.ghostEnabled = true
        g.update(1_789_000_000.0, 0.016)
        assertTrue(g.buffers.enabled[RingIds.GHOST])
        assertEquals(g.buffers.indexCount, geometry.drawRange.count as Int)
        val ghostVertex = RingIds.GHOST * g.buffers.verticesPerRing
        assertEquals(BloomSceneGraph.GHOST_DASHES.toFloat(), g.buffers.trim[ghostVertex * 2], "ghost is dashed")
        assertEquals(0f, g.buffers.trim[0], "models are solid")
        assertTrue(g.buffers.color[ghostVertex * 4 + 3] < g.buffers.color[3], "ghost is fainter")
        assertEquals(RingIds.NONE, pickOn(g, RingIds.GHOST), "ghost is never pickable")
    }

    /** Screen position of the sample of [ring] that is farthest from every other ring, then pick there. */
    private fun pickOn(g: BloomSceneGraph, ring: Int): Int {
        val n = g.segments
        val s = g.buffers.screen
        var bestI = 0
        var bestGap = -1.0
        for (i in 0 until n) {
            val x = s[(ring * n + i) * 3]; val y = s[(ring * n + i) * 3 + 1]
            var gap = Double.MAX_VALUE
            for (other in 0 until RingIds.COUNT) {
                if (other == ring || !g.buffers.enabled[other] || other == RingIds.GHOST) continue
                for (j in 0 until n) {
                    val dx = s[(other * n + j) * 3] - x; val dy = s[(other * n + j) * 3 + 1] - y
                    gap = minOf(gap, sqrt((dx * dx + dy * dy).toDouble()))
                }
            }
            if (gap > bestGap) { bestGap = gap; bestI = i }
        }
        return g.pick(s[(ring * n + bestI) * 3].toDouble(), s[(ring * n + bestI) * 3 + 1].toDouble(), 12.0)
    }

    @Test
    fun pickFindsEveryRingAndNothingInTheCorner() {
        val g = graph()
        g.update(1_789_000_000.0, 0.016)
        for (ring in 0..RingIds.VISITOR) assertEquals(ring, pickOn(g, ring), "pick on ring $ring")
        assertEquals(RingIds.NONE, g.pick(2.0, 2.0, 12.0))
        assertEquals(RingIds.NONE, g.pick(-500.0, 1e9, 24.0))
    }

    @Test
    fun cardAnchorsSitOnTheirRingFacingTheCameraAndHoldStill() {
        val world = StubBloomWorld()
        val g = graph(world)
        g.update(1_789_000_000.0, 0.016)
        val n = g.segments
        for (ring in 0..RingIds.VISITOR) {
            val a = g.cardAnchor(ring)
            assertEquals(ring, a.ringId)
            assertTrue(a.visible, "ring $ring anchor visible")
            assertTrue(a.x in 0.0..1000.0 && a.y in 0.0..700.0)
            assertTrue(a.pxPerUnit > 0 && a.alpha > 0.3)
            // on the ring: the anchor's screen point coincides with one of the ring's samples
            var gap = Double.MAX_VALUE
            var meanDepth = 0.0
            for (i in 0 until n) {
                val dx = g.buffers.screen[(ring * n + i) * 3] - a.x
                val dy = g.buffers.screen[(ring * n + i) * 3 + 1] - a.y
                gap = minOf(gap, sqrt(dx * dx + dy * dy))
                meanDepth += g.buffers.screen[(ring * n + i) * 3 + 2] / n
            }
            assertTrue(gap < 0.5, "anchor of ring $ring is $gap px off its ring")
            assertTrue(a.depth <= meanDepth + 1e-6, "anchor of ring $ring is on the camera side of the ring")
            val p = g.anchorPoint(ring)
            val back = g.project(p)
            assertTrue(abs(back.x - a.x) < 1e-6 && abs(back.y - a.y) < 1e-6, "anchorPoint projects onto cardAnchor")
        }
        // one more frame a moment later: the anchor glides, it does not jump
        val before = g.cardAnchor(3)
        world.advanceTo(1_789_000_000.016)
        g.update(1_789_000_000.016, 0.016)
        val after = g.cardAnchor(3)
        assertTrue(abs(after.x - before.x) < 3.0 && abs(after.y - before.y) < 3.0, "anchor moved ${after.x - before.x}, ${after.y - before.y}")

        assertFalse(g.cardAnchor(RingIds.GHOST).visible, "ghost off = no card")
        assertFalse(g.cardAnchor(99).visible)
        assertFalse(g.project(Vec3(0.0, 0.0, 50.0)).visible, "behind the camera")
    }

    @Test
    fun worldAnchorModeIsTheLiteralContract() {
        val world = StubBloomWorld()
        val g = graph(world, BloomThreeOptions(anchorMode = AnchorMode.WORLD))
        g.update(1_789_000_000.0, 0.016)
        val expected = g.project(world.ringAnchor(4))
        val got = g.cardAnchor(4)
        assertEquals(expected.x, got.x)
        assertEquals(expected.y, got.y)
        assertEquals(world.ringAnchor(4), g.anchorPoint(4))
    }

    @Test
    fun paletteLoucheHighlightAndRippleReachTheBuffers() {
        val g = graph()
        g.update(1_789_000_000.0, 0.016)
        val well = g.ringColorHex(0)
        assertTrue(g.ringColorHex(5) != well, "rings of one shelf are tinted apart")
        assertTrue(g.ringColorHex(6) != well && g.ringColorHex(9) != g.ringColorHex(6), "shelves differ")
        val haloBefore = g.buffers.shape[2]
        val coreBefore = g.buffers.shape[3]

        g.setLouche(1.0)
        g.setHighlight(0)
        repeat(60) { g.update(1_789_000_000.0, 0.016) }
        assertTrue(g.buffers.shape[2] > haloBefore * 1.5f, "louche widens the glow")
        assertTrue(g.buffers.shape[3] > coreBefore * 1.4f, "highlight thickens the thread")
        val visitorVertex = RingIds.VISITOR * g.buffers.verticesPerRing
        assertTrue(g.buffers.trim[visitorVertex * 2 + 1] > g.buffers.trim[g.buffers.verticesPerRing * 2 + 1], "visitor glows more than a model")

        g.setPalette(Palettes.NIGHTSHADE_HERBARIUM)
        assertTrue(g.ringColorHex(0) != well)
        assertEquals(THREE.NormalBlending, mesh(g, "vb-ring-glow").material.blending, "no additive glow on paper")
        g.setPalette(Palettes.ABSINTHE_ABYSS)
        assertEquals(THREE.AdditiveBlending, mesh(g, "vb-ring-glow").material.blending)

        g.ripple(1.0)
        assertTrue(g.ripples.active)
        g.update(1_789_000_000.0, 0.1)
        assertTrue(g.buffers.positions.all { it.isFinite() })
    }

    @Test
    fun embeddedModeReadsAHostCameraThroughTheGroupTransform() {
        val g = graph()
        val scene = THREE.Scene()
        scene.add(g.root)
        g.root.scale.setScalar(100.0) // an engine scene in CSS px units
        g.root.position.set(50.0, -20.0, 0.0)
        val cam = THREE.PerspectiveCamera(50.0, 1000.0 / 700.0, 1.0, 5000.0)
        cam.position.set(50.0, -20.0, 900.0)
        cam.lookAt(THREE.Vector3(50.0, -20.0, 0.0))
        g.updateFromCamera(cam, 1000, 700, 1_789_000_000.0, 0.016)
        // the bloom origin is the host camera's look-at point: centre of the viewport, 9 bloom units away
        val origin = g.project(Vec3.ZERO)
        assertTrue(abs(origin.x - 500.0) < 1e-6 && abs(origin.y - 350.0) < 1e-6)
        assertTrue(abs(origin.depth - 9.0) < 1e-9)
        assertTrue(g.buffers.positions.all { it.isFinite() })
        assertEquals(3, pickOn(g, 3))
    }

    @Test
    fun disposeIsIdempotentAndDetaches() {
        val g = graph()
        val scene = THREE.Scene()
        scene.add(g.root)
        g.dispose()
        g.dispose()
        assertNull(g.root.parent)
        g.update(1_789_000_000.0, 0.016) // no-op, no throw
        assertEquals(RingIds.NONE, g.pick(500.0, 350.0, 12.0))
        assertFalse(g.cardAnchor(0).visible)
    }
}

class BloomThreeEntryTest {
    @Test
    fun createReturnsARendererForGpuRungsOnly() {
        assertNull(BloomThree.create(RendererKind.CANVAS2D))
        assertEquals(RendererKind.WEBGL, assertNotNull(BloomThree.create(RendererKind.WEBGL)).kind)
        assertEquals(RendererKind.WEBGPU, assertNotNull(BloomThree.create(RendererKind.WEBGPU)).kind)
        assertTrue(THREE.REVISION.isNotEmpty())
    }

    @Test
    fun unattachedRendererIsInert() {
        val r = assertNotNull(BloomThree.create(RendererKind.WEBGL, BloomThreeOptions()))
        r.view = BloomView(distance = 4.0)
        r.resize(800, 600, 2.0)
        r.frame(0.0, 0.016)
        r.setLouche(0.5); r.ripple(1.0); r.setHighlight(3); r.setPalette(Palettes.NIGHTSHADE_HERBARIUM)
        assertEquals(RingIds.NONE, r.pick(10.0, 10.0))
        assertFalse(r.cardAnchor(0).visible)
        assertFalse(r.project(Vec3.ZERO).visible)
        assertNull(r.backendName)
        r.dispose()
        r.dispose()
    }

    /** node has no canvas, no WebGL, no navigator.gpu: exactly the "rung cannot start" case. Must resolve false, not reject. */
    @Test
    fun attachResolvesFalseWhereThereIsNoGpu(): Promise<Unit> {
        if (jsTypeOf(js("globalThis.document")) != "undefined") return Promise.resolve(Unit) // dev harness page: skip
        val host = js("({ insertBefore: function () {}, firstChild: null, clientWidth: 0, clientHeight: 0 })").unsafeCast<HTMLElement>()
        val webgl = assertNotNull(BloomThree.create(RendererKind.WEBGL))
        val webgpu = assertNotNull(BloomThree.create(RendererKind.WEBGPU))
        return webgl.attach(host, StubBloomWorld(), Palettes.ABSINTHE_ABYSS).then { ok ->
            assertFalse(ok, "webgl rung on node")
            webgl.dispose()
        }.then {
            webgpu.attach(host, StubBloomWorld(), Palettes.ABSINTHE_ABYSS)
        }.then { ok ->
            assertFalse(ok, "webgpu rung on node")
        }
    }
}
