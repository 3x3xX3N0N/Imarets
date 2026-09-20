package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Palettes
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.StubBloomWorld
import bar.verdantbloom.bloom.api.Vec3
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Headless end-to-end checks with a recording fake 2D context: no DOM, no canvas, node only. */
class PainterAndRendererTest {
    private fun paintOnce(
        quality: Quality2d,
        louche: Double,
        ghost: Boolean = false,
        highlight: Int = RingIds.NONE,
    ): dynamic {
        val world = StubBloomWorld()
        world.ghostEnabled = ghost
        world.advanceTo(5000.0)
        val proj = Projector()
        proj.set(BloomView(), 375.0, 667.0)
        val scene = Scene2d(256)
        scene.update(world, proj, RippleField(), 5000.0)
        val painter = Painter2d()
        painter.setPalette(Palettes.ABSINTHE_ABYSS, world.rings)
        painter.louche = louche
        painter.highlight = highlight
        val ctx = fakeContext()
        painter.paint(ctxOf(ctx), scene, proj, 2.0, quality, 5000.0, 0.0)
        ctx.painterDrawCalls = painter.drawCalls
        return ctx
    }

    private fun ops(ctx: dynamic): List<dynamic> {
        val n = ctx.ops.length as Int
        return List(n) { ctx.ops[it] }
    }

    @Test
    fun everyCasingIsFollowedByItsLineAndStateIsLeftClean() {
        val ctx = paintOnce(Quality2d.STANDARD, louche = 0.5)
        val list = ops(ctx)
        var casings = 0
        for (i in list.indices) {
            val op = list[i]
            if (op.op == "stroke" && op.mode == "destination-out") {
                casings++
                val line = list[i + 1]
                assertEquals("stroke", line.op as String)
                assertEquals("source-over", line.mode as String)
                assertEquals("butt", op.cap as String)
                assertEquals("round", line.cap as String)
                assertTrue((op.width as Double) > (line.width as Double) + 5.9, "the gap is at least 3 px each side")
            }
        }
        // at most one casing per chunk of the 11 live rings, and only where rings come close to each other
        assertTrue(casings in 1..11 * 32, "casings: $casings")
        // every chunk of the 11 live rings is stroked (nothing culled at the overview distance), plus the three disc rims
        assertEquals(11 * 32 + 3, list.count { it.op == "stroke" && it.mode == "source-over" && it.cap == "round" })
        assertEquals("source-over", ctx.globalCompositeOperation as String)
        assertEquals(1.0, ctx.globalAlpha as Double)
        assertEquals(0.0, ctx.shadowBlur as Double)
        assertEquals(listOf(2.0, 0.0, 0.0, 2.0, 0.0, 0.0), List(6) { (ctx.transform[it] as Number).toDouble() })
        assertEquals(1, ctx.counts.clearRect as Int)
        assertEquals(ctx.painterDrawCalls as Int, (ctx.counts.stroke as Int) + (ctx.counts.fill as Int) + (ctx.counts.fillRect as Int))
    }

    @Test
    fun glowGoesBehindTheLinesAndLoucheZeroSwitchesItOff() {
        val dry = ops(paintOnce(Quality2d.STANDARD, louche = 0.0))
        assertTrue(dry.none { it.mode == "destination-over" })

        val wet = ops(paintOnce(Quality2d.STANDARD, louche = 1.0))
        val firstGlow = wet.indexOfFirst { it.mode == "destination-over" }
        assertTrue(firstGlow > 0)
        assertTrue(wet.drop(firstGlow).all { it.mode == "destination-over" }, "pass 2 comes after all of pass 1")
        assertEquals(11 * 3, wet.count { it.op == "stroke" && it.mode == "destination-over" })
        assertEquals(1, wet.count { it.op == "fillRect" }) // the haze
    }

    @Test
    fun qualityLevelsChangeOnlyTheGlowPass() {
        val lean = paintOnce(Quality2d.LEAN, louche = 1.0)
        val rich = paintOnce(Quality2d.RICH, louche = 1.0)
        val standard = paintOnce(Quality2d.STANDARD, louche = 1.0)
        assertEquals(11 * 1, ops(lean).count { it.op == "stroke" && it.mode == "destination-over" })
        assertEquals(11 * 2, ops(rich).count { it.op == "stroke" && it.mode == "destination-over" })
        assertEquals(0.0, lean.maxShadowBlur as Double)
        assertEquals(0.0, standard.maxShadowBlur as Double)
        assertTrue((rich.maxShadowBlur as Double) >= 16.0, "blur is scaled by the pixel ratio")
        assertEquals(0.0, rich.shadowBlur as Double)
    }

    @Test
    fun glowLayerTakesTheLouchePassAtQuarterResolution() {
        val world = StubBloomWorld()
        world.advanceTo(5000.0)
        val proj = Projector()
        proj.set(BloomView(), 375.0, 667.0)
        val scene = Scene2d(256)
        scene.update(world, proj, RippleField(), 5000.0)
        val painter = Painter2d()
        painter.setPalette(Palettes.ABSINTHE_ABYSS, world.rings)
        painter.louche = 1.0
        val main = fakeContext()
        val side = fakeContext()
        val layerCanvas: dynamic = js("({ width: 0, height: 0 })")
        val layer = GlowLayer(layerCanvas.unsafeCast<org.w3c.dom.HTMLCanvasElement>(), ctxOf(side))
        assertFalse(layer.usable)
        layer.resize(375, 667)
        assertEquals(94, layerCanvas.width as Int) // ceil(375 / 4)
        assertEquals(167, layerCanvas.height as Int)

        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.LEAN, 5000.0, 0.0, layer)
        // the main canvas got the crisp pass only: the layer sits under it in the DOM, nothing is copied across
        val mainOps = ops(main)
        assertEquals(0, mainOps.count { it.mode == "destination-over" })
        assertEquals(0, main.counts.fillRect as Int)
        assertEquals(0, main.counts.drawImage as Int)
        // the layer got the glow: one stroke per live ring at LEAN, the haze, all plain source-over at scale 0.25
        assertEquals(11, side.counts.stroke as Int)
        assertEquals(1, side.counts.fillRect as Int)
        assertTrue(ops(side).all { it.mode == "source-over" })
        assertEquals(0.25, (side.transform[0] as Number).toDouble())
        // 64 of the 256 samples per ring are enough for something this soft
        assertEquals(11 * 63, side.counts.lineTo as Int)

        // LEAN refreshes the layer every other frame (it simply keeps its pixels in between)
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.LEAN, 5000.0, 0.0, layer)
        assertEquals(11, side.counts.stroke as Int)
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.LEAN, 5000.0, 0.0, layer)
        assertEquals(22, side.counts.stroke as Int)
        // STANDARD refreshes every frame, and a stale layer always does
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.STANDARD, 5000.0, 0.0, layer)
        assertEquals(22 + 22, side.counts.stroke as Int)
        // RICH blurs inside the small layer: a quarter of the radius, a sixteenth of the pixels
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.RICH, 5000.0, 0.0, layer)
        assertTrue((side.maxShadowBlur as Double) in 2.0..8.0, "blur in layer px: ${side.maxShadowBlur}")
        assertEquals(0.0, main.maxShadowBlur as Double)
        // louche at 0: the layer is wiped once and then left alone
        painter.louche = 0.0
        val clears = side.counts.clearRect as Int
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.STANDARD, 5000.0, 0.0, layer)
        painter.paint(ctxOf(main), scene, proj, 2.0, Quality2d.STANDARD, 5000.0, 0.0, layer)
        assertEquals(clears + 1, side.counts.clearRect as Int)
        assertTrue(layer.blank)
    }

    @Test
    fun ghostIsDashedAndCutsNoGaps() {
        val without = ops(paintOnce(Quality2d.STANDARD, louche = 0.0))
        val with = ops(paintOnce(Quality2d.STANDARD, louche = 0.0, ghost = true))
        assertEquals(0, without.count { it.dashed == true })
        assertEquals(32, with.count { it.op == "stroke" && it.dashed == true })
        assertEquals(without.count { it.mode == "destination-out" }, with.count { it.mode == "destination-out" })
    }

    @Test
    fun highlightWidensOneRingAndDimsTheOthers() {
        val plain = ops(paintOnce(Quality2d.STANDARD, louche = 0.0))
        val lit = ops(paintOnce(Quality2d.STANDARD, louche = 0.0, highlight = 9))
        fun lines(list: List<dynamic>, style: String) = list.filter { it.op == "stroke" && it.mode == "source-over" && it.style == style }
        val top = Css.hex(Palettes.ABSINTHE_ABYSS.ringTop) // ring 9 is the only TOP SHELF ring
        val well = Css.hex(Palettes.ABSINTHE_ABYSS.ringWell)
        assertEquals(32, lines(plain, top).size)
        val widthPlain = lines(plain, top).sumOf { it.width as Double }
        val widthLit = lines(lit, top).sumOf { it.width as Double }
        assertTrue(abs(widthLit / widthPlain - Painter2d.HIGHLIGHT_WIDTH) < 1e-9)
        val alphaPlain = lines(plain, well).sumOf { it.alpha as Double }
        val alphaLit = lines(lit, well).sumOf { it.alpha as Double }
        assertTrue(abs(alphaLit / alphaPlain - Painter2d.HIGHLIGHT_DIM) < 1e-9)
    }

    @Test
    fun discsAreDrawnWithTheOriginalTriangleWedges() {
        val ctx = paintOnce(Quality2d.STANDARD, louche = 0.0)
        // StubBloomWorld has three discs: each is 1 circle (arc) + 3 wedges of moveTo + 2 lineTo, no arcs for wedges
        assertEquals(3 + 1, ctx.counts.arc as Int) // three discs + the visitor bead
        val fills = ops(ctx).filter { it.op == "fill" }
        assertEquals(3 * 2 + 1, fills.size)
        val styles = fills.map { it.style as String }.toSet()
        assertTrue("#2040ff" in styles && "#ffe020" in styles, "carried colours are used: $styles")
        assertTrue(Css.hex(Palettes.ABSINTHE_ABYSS.accent) in styles, "a THEME disc takes the palette: $styles")
    }

    @Test
    fun noDomMeansNoRung() {
        assertFalse(Bloom2d.isSupported())
        assertNull(Bloom2d.create())
    }

    @Test
    fun demoReportsNullInsteadOfThrowingWithoutCanvas2d() {
        var called = false
        var handle: Bloom2dDemo.Handle? = null
        Bloom2dDemo.start(elementOf(fakeHost(375, 667, null)), onReady = { called = true; handle = it })
        assertTrue(called)
        assertNull(handle)
    }

    @Test
    fun detachedRendererIsInert() {
        val r = Canvas2dRenderer()
        assertEquals(RendererKind.CANVAS2D, r.kind)
        assertFalse(r.isAttached)
        r.resize(375, 667, 2.0)
        r.frame(1.0, 0.016)
        assertEquals(RingIds.NONE, r.pick(10.0, 10.0))
        assertFalse(r.cardAnchor(3).visible)
        r.ripple(1.0)
        r.setLouche(0.7)
        r.setHighlight(99)
        r.flyTo(3)
        r.dispose()
        r.dispose()
    }

    @Test
    fun attachResolvesFalseWithoutA2dContextAndLeavesNothingBehind(): Promise<Unit> {
        val host = fakeHost(375, 667, null)
        val r = Canvas2dRenderer()
        return r.attach(elementOf(host), StubBloomWorld(), Palettes.ABSINTHE_ABYSS).then { ok ->
            assertFalse(ok)
            assertFalse(r.isAttached)
            assertEquals(0, host.children.length as Int)
        }
    }

    @Test
    fun phoneSizedEndToEnd(): Promise<Unit> {
        val ctx = fakeContext()
        val host = fakeHost(375, 667, ctx, devicePixelRatio = 3.0)
        val world = StubBloomWorld()
        world.advanceTo(86_400.0)
        val r = Canvas2dRenderer()
        return r.attach(elementOf(host), world, Palettes.NIGHTSHADE_HERBARIUM).then { ok ->
            assertTrue(ok)
            val canvas = host.canvases[0]
            // two stacked canvases: the quarter-resolution glow layer UNDER the crisp one
            assertEquals(2, host.children.length as Int)
            assertTrue(host.children[0] === host.canvases[1] && host.children[1] === canvas)
            assertEquals(94, host.canvases[1].width as Int)
            assertEquals("100%", host.canvases[1].style.width as String)
            assertEquals("true", canvas.attributes["aria-hidden"] as String)
            assertEquals("absolute", canvas.style.position as String)
            // auto pixel ratio is capped at 2, the backing store is CSS size x ratio
            assertEquals(750, canvas.width as Int)
            assertEquals(1334, canvas.height as Int)

            r.view = BloomView(distance = 9.0)
            r.frame(86_400.0, 1.0 / 60.0)
            assertTrue(r.drawCalls > 11 * 32)
            assertEquals(2.0, (ctx.transform[0] as Number).toDouble())

            // every live ring has an on-screen anchor whose pxPerUnit obeys the contract at the target depth
            for (ring in 0..RingIds.VISITOR) {
                val a = r.cardAnchor(ring)
                assertTrue(a.visible, "ring $ring visible")
                // (the stub's visitor ring is wider than a phone: its anchor may sit a few px past the edge, inside the card margin)
                assertTrue(a.x in -40.0..415.0 && a.y in 0.0..667.0, "ring $ring anchor near the screen: ${a.x}, ${a.y}")
                assertTrue(a.alpha > 0.4 && a.alpha <= 1.0)
                assertEquals(ring, a.ringId)
                // picking at a ring's own anchor finds a ring (its own unless another one is in front right there)
                assertTrue(r.pick(a.x, a.y, 24.0) != RingIds.NONE, "pick at the anchor of ring $ring")
            }
            assertFalse(r.cardAnchor(RingIds.GHOST).visible)
            assertFalse(r.cardAnchor(42).visible)
            val centre = r.project(Vec3.ZERO)
            assertTrue(abs(centre.x - 187.5) < 1e-9 && abs(centre.y - 333.5) < 1e-9)
            assertTrue(abs(centre.pxPerUnit - 667.0 / (2.0 * 9.0 * kotlin.math.tan(25.0 * kotlin.math.PI / 180.0))) < 1e-9)
            assertTrue(abs(centre.depth - 9.0) < 1e-12)
            assertFalse(r.project(Vec3(0.0, 0.0, 50.0)).visible) // behind the camera

            // pick agrees with the anchor of an outer ring seen face on
            val visitor = r.cardAnchor(RingIds.VISITOR)
            assertEquals(RingIds.VISITOR, r.pick(visitor.x, visitor.y, 6.0))
            assertEquals(RingIds.NONE, r.pick(-500.0, -500.0, 24.0))

            // a hidden pane (0 x 0) draws nothing and does not throw
            val strokesBefore = ctx.counts.stroke as Int
            r.resize(0, 0, 2.0)
            r.frame(86_400.1, 0.1)
            assertEquals(strokesBefore, ctx.counts.stroke as Int)
            assertFalse(r.cardAnchor(0).visible)
            r.resize(375, 667, 2.0)

            // own camera: flyTo tweens the view towards the ring and the renderer reports it
            val start = r.view
            r.flyTo(9, seconds = 0.5)
            var t = 86_400.2
            repeat(40) {
                world.advanceTo(t)
                r.frame(t, 1.0 / 60.0)
                t += 1.0 / 60.0
            }
            assertTrue(r.view.distance < start.distance - 1.0, "flew in: ${r.view.distance}")
            val focus = r.cardAnchor(9)
            assertTrue(abs(focus.x - 187.5) < 0.5 && abs(focus.y - 333.5) < 0.5, "the card is centred: ${focus.x}, ${focus.y}")
            assertTrue(Cards2d.detail(focus.pxPerUnit) == CardDetail.FULL, "near enough for the full card: ${focus.pxPerUnit}")

            // writing the view back unchanged keeps the camera; writing a new one takes over
            r.view = r.view
            assertTrue(r.camera.isFollowing)
            r.view = BloomView(distance = 7.0)
            assertFalse(r.camera.isFollowing)

            r.ripple(1.0)
            r.frame(t, 1.0 / 60.0)
            r.setPalette(Palettes.ABSINTHE_ABYSS)
            r.frame(t + 0.02, 1.0 / 60.0)

            r.dispose()
            assertEquals(0, host.children.length as Int)
            assertFalse(r.isAttached)
            r.dispose()
        }
    }
}
