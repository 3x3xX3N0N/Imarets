package bar.verdantbloom.site

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.ClockSource
import bar.verdantbloom.bloom.api.PaletteDom
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.TimeOverride
import bar.verdantbloom.bloom.canvas2d.Bloom2d
import bar.verdantbloom.bloom.core.BloomCore
import bar.verdantbloom.bloom.three.BloomThree
import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.main.runEngineDemo
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date

/**
 * BRINGUP HELLO WORLD. Proves the pipeline: Kotlin/JS IR -> webpack -> one bundle with three from npm,
 * rendering the SpaceGraph engine demo graph. The integrator replaces this with the real wiring;
 * the pieces it will need are already touched here (renderer ladder, clock override, palette, world).
 */
fun main() {
    if (document.readyState.toString() == "loading") {
        document.addEventListener("DOMContentLoaded", { start() })
    } else {
        start()
    }
}

private fun start() {
    val requested = RendererKind.fromQuery(URLSearchParams(window.location.search).get("renderer"))
    val clock: ClockSource = TimeOverride.clockFor(window.location.hash) { Date.now() / 1000.0 }
    val world = BloomCore.createWorld()
    world.advanceTo(clock.nowUnixSeconds())
    val palette = PaletteDom.read()

    // Renderer ladder (SPEC 4). The placeholders return null until bloom-three / bloom-2d land.
    val bloomRenderer: BloomRenderer? = RendererKind.ladderFrom(requested).firstNotNullOfOrNull { kind ->
        if (kind == RendererKind.CANVAS2D) Bloom2d.create() else BloomThree.create(kind)
    }

    status(
        "three r${THREE.REVISION} | renderer=${requested.queryValue} | bloom renderer=${bloomRenderer?.kind ?: "placeholder"} | " +
            "rings=${world.rings.size} | t=${world.unixSeconds.toLong()} | theme=${palette.name}",
    )

    val space = document.getElementById("space") as? HTMLElement ?: return
    if (requested == RendererKind.CANVAS2D) {
        status("renderer=2d: the engine demo needs a GPU rung; bloom-2d will take over this rung.")
        return
    }
    try {
        runEngineDemo(space, forceWebGL = requested != RendererKind.WEBGPU)
    } catch (e: Throwable) {
        console.error("engine demo failed to start", e)
        status("engine demo failed to start: ${e.message}")
    }
}

private fun status(text: String) {
    console.log("[site] $text")
    val el = document.getElementById("bringup-status") ?: return
    el.textContent = text
}
