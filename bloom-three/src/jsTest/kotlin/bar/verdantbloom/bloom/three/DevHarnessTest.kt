package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.Palettes
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.StubBloomWorld
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * NOT a unit test: a browser-only dev harness for looking at the renderer before :site wires it in.
 * On node (gradlew :bloom-three:jsNodeTest) there is no document and this does nothing.
 * In a browser, bloom-three/dev/index.html loads the compiled TEST module; kotlin-test has no framework
 * there and simply runs every test once, which starts [DevHarness] when it finds `#bloom-three-dev`.
 * It lives in jsTest so that none of it can reach the production bundle. See bloom-three/dev/README.md.
 */
class DevHarnessTest {
    @Test
    fun startsOnlyOnTheDevPage() {
        if (jsTypeOf(js("globalThis.document")) == "undefined") return
        val host = document.getElementById("bloom-three-dev") as? HTMLElement ?: return
        DevHarness(host).start()
    }
}

private class DevHarness(private val host: HTMLElement) {
    private val query = URLSearchParams(window.location.search)
    private val world = StubBloomWorld()
    private var night = query.get("theme") != "day"
    private var louche = query.get("louche")?.toDoubleOrNull() ?: 0.35
    private var yaw = 0.4
    private var pitch = -0.35
    private var distance = query.get("distance")?.toDoubleOrNull() ?: 9.0
    private var autoOrbit = query.get("still") == null
    private var dragging = false
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastMs = 0.0
    private var frames = 0
    private var fpsSince = 0.0
    private val labels = ArrayList<HTMLElement>()
    private val status = document.getElementById("bloom-three-dev-status") as? HTMLElement

    private fun palette(): Palette = if (night) Palettes.ABSINTHE_ABYSS else Palettes.NIGHTSHADE_HERBARIUM

    fun start() {
        world.ghostEnabled = query.get("ghost") != null
        val ladder = RendererKind.ladderFrom(RendererKind.fromQuery(query.get("renderer"))).filter { it != RendererKind.CANVAS2D }
        tryRung(ladder, 0)
    }

    private fun tryRung(ladder: List<RendererKind>, index: Int) {
        if (index >= ladder.size) {
            say("no GPU rung could start (this is where :site falls to bloom-2d)")
            return
        }
        val renderer = BloomThree.create(ladder[index], BloomThreeOptions(louche = louche)) ?: return tryRung(ladder, index + 1)
        world.advanceTo(Date.now() / 1000.0)
        renderer.attach(host, world, palette()).then { ok ->
            if (ok) run(renderer) else {
                console.warn("[bloom-three dev] ${ladder[index].queryValue} did not start, next rung")
                tryRung(ladder, index + 1)
            }
        }
    }

    private fun orbit(): Quat {
        // yaw about Y, then pitch about the camera's X
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        return Quat(cy * cp, cy * sp, sy * cp, -sy * sp)
    }

    private fun run(renderer: ThreeBloomRenderer) {
        document.documentElement?.setAttribute("data-theme", palette().name)
        window.asDynamic().__bloomThreeDev = renderer
        for (ring in 0 until RingIds.COUNT) {
            val el = document.createElement("div") as HTMLElement
            el.className = "vb-dev-label"
            el.textContent = when (ring) {
                RingIds.VISITOR -> "YOU"
                RingIds.GHOST -> "ghost"
                else -> world.rings[ring].orderCode
            }
            host.appendChild(el)
            labels.add(el)
        }

        fun fit() = renderer.resize(host.clientWidth, host.clientHeight, minOf(2.0, window.devicePixelRatio))
        fit()
        window.addEventListener("resize", { fit() })

        host.addEventListener("pointerdown", { e ->
            e as MouseEvent
            dragging = true; lastX = e.clientX.toDouble(); lastY = e.clientY.toDouble()
        })
        window.addEventListener("pointerup", { dragging = false })
        host.addEventListener("pointermove", { e ->
            e as MouseEvent
            val rect = host.getBoundingClientRect()
            val x = e.clientX - rect.left
            val y = e.clientY - rect.top
            if (dragging) {
                yaw -= (e.clientX - lastX) * 0.006
                pitch = (pitch - (e.clientY - lastY) * 0.006).coerceIn(-1.5, 1.5)
                lastX = e.clientX.toDouble(); lastY = e.clientY.toDouble()
                autoOrbit = false
            }
            renderer.setHighlight(renderer.pick(x, y))
        })
        host.addEventListener("click", { e ->
            e as MouseEvent
            val rect = host.getBoundingClientRect()
            val hit = renderer.pick(e.clientX - rect.left, e.clientY - rect.top)
            say("pick = $hit")
            if (hit != RingIds.NONE) renderer.ripple(0.6)
        })
        host.addEventListener("wheel", { e ->
            e as WheelEvent
            distance = (distance * (if (e.deltaY > 0) 1.1 else 0.9)).coerceIn(0.4, 30.0)
        })
        window.addEventListener("keydown", { e ->
            when ((e as KeyboardEvent).key) {
                "b" -> renderer.ripple(1.0)
                "g" -> world.ghostEnabled = !world.ghostEnabled
                "l" -> { louche = if (louche >= 0.99) 0.0 else (louche + 0.25).coerceAtMost(1.0); renderer.setLouche(louche) }
                "t" -> {
                    night = !night
                    renderer.setPalette(palette())
                    document.documentElement?.setAttribute("data-theme", palette().name)
                }
                "o" -> autoOrbit = !autoOrbit
            }
        })

        fun tick(ms: Double) {
            val dt = if (lastMs == 0.0) 0.016 else ((ms - lastMs) / 1000.0).coerceIn(0.0, 0.25)
            lastMs = ms
            val now = Date.now() / 1000.0
            if (autoOrbit) yaw += dt * 0.12
            world.advanceTo(now)
            renderer.view = BloomView(orbit = orbit(), distance = distance)
            renderer.frame(now, dt)
            for (ring in 0 until RingIds.COUNT) {
                val a = renderer.cardAnchor(ring)
                val s = labels[ring].style
                s.display = if (a.visible) "block" else "none"
                s.transform = "translate(${a.x.toInt()}px, ${a.y.toInt()}px)"
                s.opacity = a.alpha.toString()
            }
            frames++
            if (ms - fpsSince > 1000.0) {
                say("${renderer.kind.queryValue} rung on ${renderer.backendName} | ${frames} fps | louche $louche | keys: b bell, g ghost, l louche, t theme, o orbit")
                frames = 0
                fpsSince = ms
            }
            window.requestAnimationFrame { tick(it) }
        }
        window.requestAnimationFrame { tick(it) }
    }

    private fun say(text: String) {
        status?.textContent = text
        console.log("[bloom-three dev] $text")
    }
}
