package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.CardAnchor
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.TriadKind
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputAndHelpersTest {
    // ------------------------------------------------------------------ Zui2dInput with synthetic pointer events

    private class Rig {
        val element: dynamic = js(
            """({
                style: { touchAction: 'pan-y' }, listeners: {}, captured: [],
                addEventListener: function (type, fn) { this.listeners[type] = fn; },
                removeEventListener: function (type) { delete this.listeners[type]; },
                getBoundingClientRect: function () { return { left: 10, top: 20 }; },
                setPointerCapture: function (id) { this.captured.push(id); }
            })""",
        )
        val camera = OrbitCamera(BloomView(distance = 6.0)).also { it.setViewport(375.0, 667.0) }
        val taps = ArrayList<Triple<Double, Double, Double>>()
        val input = Zui2dInput(elementOf(element), camera, onTap = { x, y, slop -> taps.add(Triple(x, y, slop)) })
        var prevented = 0

        fun fire(type: String, id: Int, x: Double, y: Double, timeMs: Double, pointerType: String = "touch", shift: Boolean = false) {
            val e: dynamic = js("({})")
            e.type = type
            e.pointerId = id
            e.clientX = x + 10.0 // client coordinates: the element sits at (10, 20)
            e.clientY = y + 20.0
            e.timeStamp = timeMs
            e.pointerType = pointerType
            e.button = 0
            e.shiftKey = shift
            e.preventDefault = { prevented++ }
            element.listeners[type](e)
        }

        fun wheel(deltaY: Double, x: Double, y: Double) {
            val e: dynamic = js("({})")
            e.type = "wheel"
            e.deltaY = deltaY
            e.deltaMode = 0
            e.clientX = x + 10.0
            e.clientY = y + 20.0
            e.preventDefault = { prevented++ }
            element.listeners["wheel"](e)
        }
    }

    @Test
    fun attachClaimsTouchActionAndDetachRestoresIt() {
        val rig = Rig()
        rig.input.attach()
        assertEquals("none", rig.element.style.touchAction as String)
        for (type in listOf("pointerdown", "pointermove", "pointerup", "pointercancel", "wheel", "contextmenu")) {
            assertTrue(rig.element.listeners[type] != null, "listener for $type")
        }
        rig.input.detach()
        assertEquals("pan-y", rig.element.style.touchAction as String)
        assertTrue(rig.element.listeners["pointerdown"] == null)
    }

    @Test
    fun aTapReportsElementRelativeCoordinatesAndTheTouchSlop() {
        val rig = Rig()
        rig.input.attach()
        val before = rig.camera.view
        rig.fire("pointerdown", 1, 100.0, 200.0, 1000.0)
        rig.fire("pointermove", 1, 103.0, 202.0, 1030.0) // finger wobble, below the touch tap slop
        rig.fire("pointerup", 1, 103.0, 202.0, 1090.0)
        assertEquals(listOf(Triple(100.0, 200.0, Zui2dInput.TOUCH_PICK_SLOP)), rig.taps)
        assertEquals(before, rig.camera.view, "a tap does not nudge the bloom")

        rig.fire("pointerdown", 1, 50.0, 60.0, 2000.0, pointerType = "mouse")
        rig.fire("pointerup", 1, 50.0, 60.0, 2050.0, pointerType = "mouse")
        assertEquals(Triple(50.0, 60.0, Zui2dInput.MOUSE_PICK_SLOP), rig.taps.last())
        assertEquals(listOf(1, 1), List(rig.element.captured.length as Int) { rig.element.captured[it] as Int })
    }

    @Test
    fun aDragRotatesShiftDragPansAndNeitherIsATap() {
        val rig = Rig()
        rig.input.attach()
        val v0 = rig.camera.view
        rig.fire("pointerdown", 1, 100.0, 300.0, 0.0)
        for (i in 1..10) rig.fire("pointermove", 1, 100.0 + i * 8.0, 300.0, i * 16.0)
        rig.fire("pointerup", 1, 180.0, 300.0, 170.0)
        val v1 = rig.camera.view
        assertTrue(v1.orbit != v0.orbit && v1.target == v0.target, "one finger rotates")
        assertTrue(rig.taps.isEmpty())
        assertTrue(rig.camera.isAnimating, "released while moving: the bloom coasts")

        rig.camera.jumpTo(BloomView(distance = 6.0))
        rig.fire("pointerdown", 2, 100.0, 300.0, 1000.0, pointerType = "mouse", shift = true)
        for (i in 1..10) rig.fire("pointermove", 2, 100.0 + i * 8.0, 300.0, 1000.0 + i * 16.0, pointerType = "mouse", shift = true)
        rig.fire("pointerup", 2, 180.0, 300.0, 1400.0, pointerType = "mouse", shift = true) // rested 240 ms before lifting
        val v2 = rig.camera.view
        assertTrue(v2.orbit == Quat.IDENTITY && v2.target.x < -0.1, "shift-drag pans, content follows the pointer: ${v2.target}")
        assertFalse(rig.camera.isAnimating, "a pointer that rested before lifting does not fling")
        assertTrue(rig.prevented > 0)
    }

    @Test
    fun twoFingersPinchZoomAboutTheirCentreAndNeverTap() {
        val rig = Rig()
        rig.input.attach()
        val ppu0 = rig.camera.pxPerUnit
        rig.fire("pointerdown", 1, 140.0, 300.0, 0.0)
        rig.fire("pointerdown", 2, 240.0, 300.0, 5.0)
        // spread symmetrically from a 100 px span to 200 px: centre stays at (190, 300)
        for (i in 1..10) {
            rig.fire("pointermove", 1, 140.0 - i * 5.0, 300.0, 5.0 + i * 16.0)
            rig.fire("pointermove", 2, 240.0 + i * 5.0, 300.0, 6.0 + i * 16.0)
        }
        assertTrue(abs(rig.camera.pxPerUnit / ppu0 - 2.0) < 1e-9, "span doubled, scale doubled: ${rig.camera.pxPerUnit / ppu0}")
        // a third finger is ignored while two are down
        val duringPinch = rig.camera.view
        rig.fire("pointerdown", 3, 20.0, 20.0, 180.0)
        rig.fire("pointermove", 3, 90.0, 90.0, 190.0)
        rig.fire("pointerup", 3, 90.0, 90.0, 195.0)
        assertEquals(duringPinch, rig.camera.view)
        rig.fire("pointerup", 2, 290.0, 300.0, 200.0)
        rig.fire("pointerup", 1, 90.0, 300.0, 210.0)
        assertTrue(rig.taps.isEmpty())
        assertTrue(abs(QuatMath.length(rig.camera.view.orbit) - 1.0) < 1e-12)

        // the gesture state is clean afterwards: the next tap still works
        rig.fire("pointerdown", 7, 10.0, 10.0, 1000.0)
        rig.fire("pointerup", 7, 10.0, 10.0, 1040.0)
        assertEquals(1, rig.taps.size)
    }

    @Test
    fun wheelZoomsAtTheCursorAndPreventsPageScroll() {
        val rig = Rig()
        rig.input.attach()
        val d0 = rig.camera.view.distance
        rig.wheel(-120.0, 300.0, 100.0)
        assertTrue(rig.camera.view.distance < d0)
        assertTrue(rig.camera.view.target != Vec3.ZERO, "zooming off-centre moves the target towards the cursor")
        assertEquals(1, rig.prevented)
    }

    // ------------------------------------------------------------------ Cards2d

    @Test
    fun semanticZoomLevelsHaveHysteresis() {
        assertEquals(CardDetail.CODE, Cards2d.detail(40.0))
        assertEquals(CardDetail.TITLE, Cards2d.detail(Cards2d.TITLE_FROM))
        assertEquals(CardDetail.FULL, Cards2d.detail(Cards2d.FULL_FROM))
        // right at a threshold the level does not flicker
        assertEquals(CardDetail.CODE, Cards2d.detail(Cards2d.TITLE_FROM * 1.02, CardDetail.CODE))
        assertEquals(CardDetail.TITLE, Cards2d.detail(Cards2d.TITLE_FROM * 0.98, CardDetail.TITLE))
        assertEquals(CardDetail.TITLE, Cards2d.detail(Cards2d.TITLE_FROM * 1.1, CardDetail.CODE))
        assertEquals(CardDetail.CODE, Cards2d.detail(Cards2d.TITLE_FROM * 0.9, CardDetail.TITLE))
        assertEquals(CardDetail.FULL, Cards2d.detail(Cards2d.FULL_FROM * 0.95, CardDetail.FULL))
        assertEquals(CardDetail.FULL, Cards2d.detail(1000.0, CardDetail.CODE))
        assertEquals(CardDetail.CODE, Cards2d.detail(1.0, CardDetail.FULL))
    }

    @Test
    fun cardPlacementUsesStylePropertiesOnly() {
        assertEquals(Cards2d.MIN_SCALE, Cards2d.scale(1.0))
        assertEquals(Cards2d.MAX_SCALE, Cards2d.scale(5000.0))
        assertEquals(0.5, Cards2d.scale(Cards2d.NATURAL_PX_PER_UNIT / 2))
        assertEquals("translate3d(12.3px,45.7px,0) scale(0.5) translate(-50%,-50%)", Cards2d.transform(12.34, 45.66, 0.5))
        assertTrue(Cards2d.zIndex(2.0) > Cards2d.zIndex(9.0), "nearer cards stack on top")
        assertEquals(1, Cards2d.zIndex(500.0))

        val card: dynamic = js("({ hidden: false, style: {}, attrs: {}, writes: 0 })")
        card.getAttribute = { name: String -> card.attrs[name] }
        card.setAttribute = { name: String, value: String ->
            card.attrs[name] = value
            card.writes = (card.writes as Int) + 1
        }
        val anchor = CardAnchor(ringId = 3, x = 100.0, y = 200.0, pxPerUnit = 260.0, depth = 3.0, alpha = 0.804, visible = true)
        Cards2d.place(elementOf(card), anchor, 0.0, -16.0)
        assertEquals("translate3d(100px,184px,0) scale(1) translate(-50%,-50%)", card.style.transform as String)
        assertEquals("0.8", card.style.opacity as String)
        assertEquals("1700", card.style.zIndex as String)
        assertEquals("full", card.attrs["data-detail"] as String)
        Cards2d.place(elementOf(card), anchor)
        assertEquals(1, card.writes as Int, "data-detail is written only when it changes")
        Cards2d.place(elementOf(card), anchor.copy(visible = false))
        assertTrue(card.hidden as Boolean)
        Cards2d.place(elementOf(card), anchor)
        assertFalse(card.hidden as Boolean)
    }

    // ------------------------------------------------------------------ colours, governor, spin discs

    @Test
    fun cssColours() {
        assertEquals("#020a07", Css.hex(0x020a07))
        assertEquals("#ffffff", Css.hex(0xffffff))
        assertEquals("#000000", Css.hex(0))
        assertEquals("#abcdef", Css.hex(0x7fabcdef)) // anything above 24 bits is dropped
        assertEquals("rgba(184,255,206,0.5)", Css.rgba(0xb8ffce, 0.5))
        assertEquals("rgba(0,0,0,1)", Css.rgba(0, 7.0))
    }

    @Test
    fun governorEarnsRichOnceAndGivesItUpForGood() {
        val g = QualityGovernor()
        assertEquals(Quality2d.STANDARD, g.effective)
        repeat(QualityGovernor.PROMOTE_FRAMES - 1) { assertFalse(g.sample(1.0 / 60.0)) }
        assertTrue(g.sample(1.0 / 60.0))
        assertEquals(Quality2d.RICH, g.effective)
        g.sample(5.0) // a hidden tab is not a slow frame
        assertEquals(Quality2d.RICH, g.effective)
        repeat(QualityGovernor.DEMOTE_FRAMES) { g.sample(1.0 / 25.0) }
        assertEquals(Quality2d.STANDARD, g.effective)
        repeat(QualityGovernor.PROMOTE_FRAMES * 3) { g.sample(1.0 / 60.0) }
        assertEquals(Quality2d.STANDARD, g.effective, "no second try at RICH")
        repeat(QualityGovernor.DEMOTE_FRAMES) { g.sample(1.0 / 20.0) }
        assertEquals(Quality2d.LEAN, g.effective)
        repeat(QualityGovernor.RECOVER_FRAMES) { g.sample(1.0 / 60.0) }
        assertEquals(Quality2d.STANDARD, g.effective)
        g.pinned = Quality2d.LEAN
        assertEquals(Quality2d.LEAN, g.effective)
        assertFalse(g.sample(1.0 / 60.0))
    }

    @Test
    fun spinDiscGeometryIsTheOriginals() {
        // three wedges at 0 / 120 / 240 degrees, each 60 degrees wide, closed by a straight chord
        for (wedge in 0 until 3) {
            val startDeg = wedge * 120.0
            for ((k, deg) in listOf(startDeg, startDeg + 60.0).withIndex()) {
                val i = wedge * 2 + k
                assertTrue(abs(SpinDiscGeometry.rimX(i, 12.8, 1.0, 0.0) - 12.8 * kotlin.math.cos(deg * PI / 180)) < 1e-12)
                assertTrue(abs(SpinDiscGeometry.rimY(i, 12.8, 1.0, 0.0) - 12.8 * kotlin.math.sin(deg * PI / 180)) < 1e-12)
            }
            // the chord of a 60 degree wedge is exactly one radius long: an equilateral triangle
            val dx = SpinDiscGeometry.rimX(wedge * 2 + 1, 12.8, 1.0, 0.0) - SpinDiscGeometry.rimX(wedge * 2, 12.8, 1.0, 0.0)
            val dy = SpinDiscGeometry.rimY(wedge * 2 + 1, 12.8, 1.0, 0.0) - SpinDiscGeometry.rimY(wedge * 2, 12.8, 1.0, 0.0)
            assertTrue(abs(sqrt(dx * dx + dy * dy) - 12.8) < 1e-12)
        }
        // rotating by a quarter turn sends the first rim point from +x to +y
        assertTrue(abs(SpinDiscGeometry.rimX(0, 1.0, 0.0, 1.0)) < 1e-12 && abs(SpinDiscGeometry.rimY(0, 1.0, 0.0, 1.0) - 1.0) < 1e-12)
        assertTrue(abs(SpinDiscGeometry.STROKE_FRACTION * 12.8 - 0.25) < 1e-15)

        val disc = SpinDisc(
            id = 0, position = Vec3.ZERO, orientation = Quat.IDENTITY, radius = 0.128, periodSeconds = (7 + 22) * 29.6,
            phase = 1.0, bornAtUnixSeconds = 1_700_000_000.0, alpha = 1.0, triad = TriadKind.WILD,
            discColor = 1, strokeColor = 2, wedgeColor = 3, nearRing = 0,
        )
        assertTrue(abs(SpinDiscGeometry.angle(disc, disc.bornAtUnixSeconds) - 1.0) < 1e-12)
        assertTrue(abs(SpinDiscGeometry.angle(disc, disc.bornAtUnixSeconds + disc.periodSeconds / 2) - (1.0 + PI)) < 1e-9)
        // whole periods later the disc is back where it was, even at Unix-time magnitudes
        assertTrue(abs(SpinDiscGeometry.angle(disc, disc.bornAtUnixSeconds + 1000 * disc.periodSeconds) - 1.0) < 1e-6)
        assertTrue(abs(SpinDiscGeometry.angle(disc, disc.bornAtUnixSeconds - disc.periodSeconds / 4) - (1.0 + 1.5 * PI)) < 1e-9)
    }
}
