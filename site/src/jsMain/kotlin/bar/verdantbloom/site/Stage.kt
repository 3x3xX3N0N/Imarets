package bar.verdantbloom.site

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.ClockSource
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Vec3
import bar.verdantbloom.bloom.canvas2d.Bloom2d
import bar.verdantbloom.bloom.canvas2d.Cards2d
import bar.verdantbloom.bloom.canvas2d.OrbitCamera
import bar.verdantbloom.bloom.canvas2d.Zui2dInput
import bar.verdantbloom.bloom.three.BloomAnchorSource
import bar.verdantbloom.bloom.three.BloomThree
import bar.verdantbloom.bloom.three.ThreeBloomRenderer
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.min

/**
 * The stage: renderer ladder, the one frame loop, the camera, the card layer.
 *
 * ONE code path for every rung (SPEC 4): whichever [BloomRenderer] the ladder lands on gets the same
 * [OrbitCamera] view each frame and answers `cardAnchor` / `pick`; the pour cards are clones of the document's
 * own articles, moved with CSS transforms (DOM-CONTRACT 3.2). The camera and the pointer / wheel / pinch input are
 * bloom-2d's pure-maths `OrbitCamera` + `Zui2dInput`, which work on a `BloomView` and so drive the three rungs too.
 */
internal class BloomStage(
    val world: BloomWorld,
    var clock: ClockSource,
    private val requested: RendererKind,
) {
    private val stage: HTMLElement? = byId("stage")
    private val host: HTMLElement? = byId("bloom-host")
    private val cardsLayer: HTMLElement? = byId("bloom-cards")
    private val notice: HTMLElement? = byId("bloom-notice")

    var renderer: BloomRenderer? = null
        private set
    val camera = OrbitCamera(BloomView())
    private var input: Zui2dInput? = null

    private val cards = arrayOfNulls<HTMLElement>(10)
    private var visitorTag: HTMLElement? = null

    private var palette: Palette? = null
    private var louche = 0.35
    private var width = 0
    private var height = 0
    private var lastFrameMs = 0.0
    private var running = false
    private var atHome = true
    private var hoverRing = RingIds.NONE

    /** The ring the camera was sent to (its card may open fully); NONE = overview / free flight. */
    var focusRing: Int = RingIds.NONE
        private set

    var reducedMotion = false

    /** A ring (or its card) was tapped. */
    var onRingChosen: (ringId: Int) -> Unit = {}

    /** Called once per frame with the bloom's Unix time, after the draw. */
    var onFrame: (unixSeconds: Double, dtSeconds: Double) -> Unit = { _, _ -> }

    /** True once every rung has refused: the static plate stays and ring stops fall back to the document. */
    var failed = false
        private set

    val available: Boolean get() = stage != null && host != null && !failed

    // ------------------------------------------------------------------------------------------ ladder

    fun start(initialPalette: Palette) {
        palette = initialPalette
        if (host == null || stage == null) return
        buildCards()
        tryRung(RendererKind.ladderFrom(requested), 0)
    }

    private fun tryRung(ladder: List<RendererKind>, index: Int) {
        val host = host ?: return
        if (index >= ladder.size) {
            failed = true
            showNotice("all-failed", sticky = true)
            console.warn("[site] no renderer rung could start; the static plate stays")
            return
        }
        val kind = ladder[index]
        val candidate: BloomRenderer? = try {
            if (kind == RendererKind.CANVAS2D) Bloom2d.create() else BloomThree.create(kind)
        } catch (e: Throwable) {
            console.warn("[site] creating the ${kind.queryValue} rung threw", e)
            null
        }
        if (candidate == null) {
            tryRung(ladder, index + 1)
            return
        }
        var settled = false
        fun fail(reason: Any?) {
            if (settled) return
            settled = true
            console.info("[site] rung ${kind.queryValue} did not start", reason)
            try {
                candidate.dispose()
            } catch (e: Throwable) {
                // a rung that failed to start must not take the page down with it
            }
            tryRung(ladder, index + 1)
        }
        try {
            val attaching: dynamic = candidate.attach(host, world, palette ?: return)
            attaching.then({ ok: Boolean ->
                if (ok) {
                    if (!settled) {
                        settled = true
                        onAttached(candidate, kind)
                    }
                } else {
                    fail("attach resolved false")
                }
                null
            }, { error: dynamic ->
                fail(error)
                null
            })
        } catch (e: Throwable) {
            fail(e)
        }
    }

    private fun onAttached(r: BloomRenderer, kind: RendererKind) {
        renderer = r
        r.setLouche(louche)
        stage?.setAttribute("data-renderer", kind.queryValue)
        val backend = (r as? ThreeBloomRenderer)?.backendName ?: if (kind == RendererKind.CANVAS2D) "canvas2d" else kind.queryValue
        stage?.setAttribute("data-backend", backend)
        console.info("[site] renderer rung: ${kind.queryValue} (requested ${requested.queryValue}, backend $backend)")
        if (kind != requested) {
            showNotice(if (kind == RendererKind.WEBGL) "webgpu-to-webgl" else "webgl-to-2d", sticky = false)
        }
        val host = host ?: return
        input = Zui2dInput(
            host,
            camera,
            onTap = { x, y, slop -> tap(x, y, slop) },
            onHover = { x, y -> hover(x, y) },
            onActivity = { speed ->
                atHome = false
                world.perturb(PerturbKind.POINTER, speed)
            },
        ).also { it.attach() }
        if (!running) {
            running = true
            lastFrameMs = perfNow()
            window.requestAnimationFrame { frame(it) }
        }
    }

    private fun showNotice(key: String, sticky: Boolean) {
        val el = notice ?: return
        val text = el.data("msg-$key") ?: return
        el.textContent = text
        el.hidden = false
        if (!sticky) window.setTimeout({ el.hidden = true }, 9000)
    }

    // ------------------------------------------------------------------------------------------ cards

    private fun buildCards() {
        val layer = cardsLayer ?: return
        for (article in document.all("article.vb-pour[data-ring-id]")) {
            val ring = article.data("ring-id")?.toIntOrNull() ?: continue
            if (ring !in 0..9 || cards[ring] != null) continue
            val card = article.cloneNode(true) as HTMLElement
            stripIds(card)
            card.setAttribute("data-lod", "far")
            card.hidden = true
            card.addEventListener("click", { event ->
                val target = event.target as? Element
                // links and buttons inside a card keep their own behaviour
                if (target?.closest("a, button, input, select, textarea, summary") == null) onRingChosen(ring)
            })
            layer.appendChild(card)
            cards[ring] = card
        }
        visitorTag = layer.first(".vb-ringtag[data-ring-id=\"10\"]")
    }

    /** Ids must stay unique in the document; a clone keeps none, nor references to them. */
    private fun stripIds(root: HTMLElement) {
        root.removeAttribute("id")
        root.removeAttribute("aria-labelledby")
        for (el in root.all("[id]")) el.removeAttribute("id")
        for (el in root.all("[aria-labelledby], [aria-describedby]")) {
            el.removeAttribute("aria-labelledby")
            el.removeAttribute("aria-describedby")
        }
        val name = root.first(".vb-pour__name")?.textContent
        if (name != null) root.setAttribute("aria-label", name)
    }

    private fun placeCards(r: BloomRenderer) {
        for (ring in 0..9) {
            val card = cards[ring] ?: continue
            Cards2d.place(card, r.cardAnchor(ring))
            if (card.hidden) continue
            val detail = card.getAttribute(Cards2d.DETAIL_ATTRIBUTE)
            val focused = ring == focusRing
            // Only the pour the visitor went to opens fully. While one is open, and at the overview, the others
            // shrink to their order code so the bloom stays readable; in free flight the hovered ring shows "mid".
            val lod = when {
                focused -> if (detail == "full") "near" else "mid"
                focusRing != RingIds.NONE -> "far"
                ring == hoverRing && detail != "code" && detail != null -> "mid"
                else -> "far"
            }
            setAttr(card, "data-lod", lod)
            setAttr(card, "data-highlight", if (focused) "true" else "false")
            if (focused) {
                card.style.zIndex = "3000"
                card.style.opacity = "1"
            }
        }
        val tag = visitorTag
        if (tag != null) Cards2d.place(tag, r.cardAnchor(RingIds.VISITOR), 0.0, -14.0)
    }

    // ------------------------------------------------------------------------------------------ input

    private fun tap(x: Double, y: Double, slop: Double) {
        val r = renderer ?: return
        val ring = r.pick(x, y, slop)
        world.perturb(PerturbKind.TOUCH, slop)
        when {
            ring in 0..9 -> onRingChosen(ring)
            ring == RingIds.VISITOR -> r.ripple(0.15)
        }
    }

    private fun hover(x: Double, y: Double) {
        val r = renderer ?: return
        val ring = r.pick(x, y, 12.0)
        if (ring == hoverRing) return
        hoverRing = ring
        r.setHighlight(if (ring != RingIds.NONE) ring else focusRing)
        host?.style?.cursor = if (ring in 0..9) "pointer" else ""
    }

    // ------------------------------------------------------------------------------------------ camera

    private fun anchorOf(ring: Int): Vec3 {
        val source = renderer as? BloomAnchorSource
        return source?.anchorPoint(ring) ?: world.ringAnchor(ring)
    }

    fun flyToRing(ring: Int) {
        if (ring !in 0..9) return
        focusRing = ring
        atHome = false
        renderer?.setHighlight(ring)
        camera.flyTo({ anchorOf(ring) }, camera.focusDistance, if (reducedMotion) 0.0 else 0.9)
    }

    fun flyHome() {
        focusRing = RingIds.NONE
        renderer?.setHighlight(hoverRing)
        camera.flyHome(if (reducedMotion) 0.0 else 0.9)
        atHome = true
    }

    // ------------------------------------------------------------------------------------------ look

    fun setPalette(p: Palette) {
        palette = p
        renderer?.setPalette(p)
    }

    fun setLouche(amount: Double) {
        louche = amount
        renderer?.setLouche(amount)
    }

    fun ripple(magnitude: Double) {
        renderer?.ripple(magnitude)
    }

    // ------------------------------------------------------------------------------------------ loop

    private fun syncSize(r: BloomRenderer) {
        val host = host ?: return
        val w = host.clientWidth
        val h = host.clientHeight
        if (w == width && h == height) return
        val delta = abs(w - width) + abs(h - height)
        val first = width == 0 && height == 0
        width = w
        height = h
        r.resize(w, h, min(window.devicePixelRatio, 2.0))
        if (w <= 0 || h <= 0) return
        camera.setViewport(w.toDouble(), h.toDouble())
        // the view scale hangs on the HEIGHT, so a portrait phone needs a longer overview distance
        camera.homeDistance = OrbitCamera.distanceToFit(OVERVIEW_RADIUS, w.toDouble(), h.toDouble())
        if (atHome && !camera.isFlying) camera.jumpTo(BloomView(camera.view.orbit, camera.homeDistance, camera.homeTarget))
        if (!first) world.perturb(PerturbKind.RESIZE, delta.toDouble())
    }

    private fun frame(timestampMs: Double) {
        if (!running) return
        window.requestAnimationFrame { frame(it) }
        val r = renderer ?: return
        val dt = ((timestampMs - lastFrameMs) / 1000.0).coerceIn(0.0, 0.1)
        lastFrameMs = timestampMs
        try {
            syncSize(r)
            val now = clock.nowUnixSeconds()
            world.advanceTo(now)
            camera.update(dt)
            r.view = camera.view
            r.frame(now, dt)
            if (width > 0 && height > 0) placeCards(r)
            onFrame(now, dt)
        } catch (e: Throwable) {
            // one bad frame must not kill the loop; say so once per distinct message
            val message = e.message ?: "unknown"
            if (message != lastFrameError) {
                lastFrameError = message
                console.warn("[site] frame failed", e)
            }
        }
    }

    private var lastFrameError: String? = null

    private companion object {
        /** Bloom units that must fit in the overview: the unit-ish model rings plus room for the wider ones. */
        const val OVERVIEW_RADIUS = 3.2
    }
}
