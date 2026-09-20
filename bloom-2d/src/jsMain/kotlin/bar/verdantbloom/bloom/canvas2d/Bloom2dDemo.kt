package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.Palettes
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.StubBloomWorld
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * Reference wiring of the 2D rung, and the harness bloom-2d was checked with in a browser: own camera +
 * input, a requestAnimationFrame loop on the wall clock, resize handling, and one DOM label per ring placed
 * with [Cards2d]. NOT used by :site (the integrator owns the real page); webpack drops it from the bundle
 * when nothing references it.
 *
 * Labels are plain elements with class `vb-2d-label` and `data-ring` / `data-detail` attributes; position
 * and opacity are set through style properties only (CSP-safe).
 */
object Bloom2dDemo {
    class Handle internal constructor(
        val renderer: Canvas2dRenderer,
        val world: BloomWorld,
        private val stopLoop: () -> Unit,
    ) {
        @JsName("stop")
        fun stop() = stopLoop()

        @JsName("ring")
        fun ring(magnitude: Double) = renderer.ripple(magnitude)

        @JsName("flyTo")
        fun flyTo(ringId: Int) = renderer.flyTo(ringId)

        @JsName("home")
        fun home() = renderer.flyHome()

        @JsName("diagnostics")
        fun diagnostics(): String = renderer.diagnostics()

        @JsName("louche")
        fun louche(amount: Double) = renderer.setLouche(amount)

        @JsName("highlight")
        fun highlight(ringId: Int) = renderer.setHighlight(ringId)

        @JsName("ghost")
        fun ghost(on: Boolean) {
            world.ghostEnabled = on
        }

        /** Pin the glow quality: "lean", "standard", "rich"; anything else hands it back to the governor. */
        @JsName("pin")
        fun pin(level: String) {
            renderer.governor.pinned = Quality2d.entries.firstOrNull { it.name.equals(level, ignoreCase = true) }
        }

        /**
         * Draw [frames] frames back to back and return the mean milliseconds per frame. With [sync] each frame is
         * forced to finish with a 1 px read-back (Canvas2D work is otherwise deferred); on a GPU-backed canvas that
         * read-back itself stalls, so the honest numbers are sync = true on a software canvas
         * (`willReadFrequently`) and sync = false (command recording only) on a GPU one.
         * Works in a throttled / background tab where requestAnimationFrame says nothing about cost.
         */
        @JsName("bench")
        fun bench(frames: Int, sync: Boolean = true): Double {
            val canvas: dynamic = renderer.canvasElement ?: return -1.0
            val context: dynamic = canvas.getContext("2d")
            val start: Double = js("performance.now()").unsafeCast<Double>()
            var t = world.unixSeconds
            for (i in 0 until frames) {
                t += 1.0 / 60.0
                world.advanceTo(t)
                renderer.frame(t, 1.0 / 60.0)
                if (sync) context.getImageData(0, 0, 1, 1)
            }
            val end: Double = js("performance.now()").unsafeCast<Double>()
            return (end - start) / frames
        }
    }

    /** Bloom units the overview should show around the centre (the stub's visitor ring has radius 2.6). */
    const val OVERVIEW_RADIUS: Double = 2.8

    /**
     * Start the demo inside [host] (which must be positioned and sized by the page's stylesheet).
     * Resolves through [onReady] with a [Handle], or with null when Canvas2D is unavailable.
     */
    @JsName("start")
    fun start(
        host: HTMLElement,
        world: BloomWorld = StubBloomWorld(),
        palette: Palette = Palettes.ABSINTHE_ABYSS,
        onReady: ((Handle?) -> Unit)? = null,
    ) {
        val renderer = Bloom2d.createCanvas2d()
        if (renderer == null) {
            onReady?.invoke(null)
            return
        }
        renderer.attach(host, world, palette).then { ok ->
            if (!ok) {
                onReady?.invoke(null)
            } else {
                onReady?.invoke(run(host, world, renderer))
            }
        }
    }

    private fun run(host: HTMLElement, world: BloomWorld, renderer: Canvas2dRenderer): Handle {
        val document = host.ownerDocument!!
        val labels = ArrayList<HTMLElement>(RingIds.COUNT)
        for (ring in 0 until RingIds.COUNT) {
            val label = document.createElement("div") as HTMLElement
            label.className = "vb-2d-label"
            label.setAttribute("data-ring", ring.toString())
            label.textContent = when (ring) {
                RingIds.VISITOR -> "YOU"
                RingIds.GHOST -> "GHOST"
                else -> world.rings.getOrNull(ring)?.orderCode ?: ring.toString()
            }
            label.style.position = "absolute"
            label.style.left = "0"
            label.style.top = "0"
            label.style.asDynamic().transformOrigin = "0 0"
            label.style.asDynamic().pointerEvents = "none"
            host.appendChild(label)
            labels.add(label)
        }

        renderer.enableOwnCamera(host)

        var running = true
        var lastMs = Date.now()
        var lastW = -1
        var lastH = -1
        var lastRatio = -1.0

        lateinit var loop: (Double) -> Unit // one closure for every frame, not one per frame

        fun tick() {
            if (!running) return
            val nowMs = Date.now()
            val dt = (nowMs - lastMs) / 1000.0
            lastMs = nowMs
            val w = host.clientWidth
            val h = host.clientHeight
            val ratio = window.devicePixelRatio.coerceAtMost(Canvas2dRenderer.MAX_AUTO_PIXEL_RATIO)
            if (w != lastW || h != lastH || ratio != lastRatio) {
                val first = lastW < 0
                lastW = w; lastH = h; lastRatio = ratio
                renderer.resize(w, h, ratio)
                // overview distance that fits the bloom into THIS viewport (portrait phones need more than 9)
                val fit = OrbitCamera.distanceToFit(OVERVIEW_RADIUS, w.toDouble(), h.toDouble())
                renderer.camera.homeDistance = fit
                if (first && w > 0 && h > 0) renderer.view = renderer.view.copy(distance = fit)
            }
            val now = nowMs / 1000.0
            world.advanceTo(now)
            renderer.frame(now, dt)
            for (ring in 0 until RingIds.COUNT) Cards2d.place(labels[ring], renderer.cardAnchor(ring), 0.0, -16.0)
            window.requestAnimationFrame(loop)
        }
        loop = { tick() }
        window.requestAnimationFrame(loop)

        return Handle(renderer, world) {
            running = false
            for (label in labels) label.parentNode?.removeChild(label)
            renderer.dispose()
        }
    }
}
