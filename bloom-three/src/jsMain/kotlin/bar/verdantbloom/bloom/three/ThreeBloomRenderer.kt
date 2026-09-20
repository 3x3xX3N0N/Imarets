package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.CardAnchor
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Vec3
import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.jsObject
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.atan

/**
 * bloom-api BloomRenderer on three's WebGPURenderer. ONE code path for both GPU rungs:
 * WEBGPU = WebGPURenderer(), WEBGL = WebGPURenderer(forceWebGL = true) (WebGL2 backend).
 *
 * Owns its canvas, renderer, scene and camera; owns NO loop and NO input listeners. The caller drives it:
 * `world.advanceTo(now); renderer.view = v; renderer.frame(now, dt)`, then reads [cardAnchor].
 */
class ThreeBloomRenderer internal constructor(
    override val kind: RendererKind,
    private val options: BloomThreeOptions,
) : BloomRenderer, BloomAnchorSource {

    private var renderer: THREE.WebGPURenderer? = null
    private var canvas: HTMLCanvasElement? = null
    private var scene: THREE.Scene? = null
    private var camera: THREE.PerspectiveCamera? = null
    private var graph: BloomSceneGraph? = null

    private var cssWidth = 0
    private var cssHeight = 0
    private var attachStarted = false
    private var initialized = false
    private var ready = false
    private var disposed = false
    private var pendingLouche = options.louche
    private val clearColor = THREE.Color(0x000000)
    private val lookTarget = THREE.Vector3()

    override var view: BloomView = BloomView()

    /** True once attach() resolved true and until dispose(). */
    val isReady: Boolean get() = ready && !disposed

    /** "webgpu" or "webgl2" once ready, else null. */
    val backendName: String?
        get() {
            val r = renderer ?: return null
            if (!ready) return null
            return if (r.backend.isWebGPUBackend == true) "webgpu" else "webgl2"
        }

    /** The scene objects, for inspection and tests. Null before attach / after dispose. */
    val sceneGraph: BloomSceneGraph? get() = graph

    override fun attach(host: HTMLElement, world: BloomWorld, palette: Palette): Promise<Boolean> = Promise { resolve, _ ->
        if (disposed || attachStarted || kind == RendererKind.CANVAS2D) {
            resolve(false)
            return@Promise
        }
        attachStarted = true
        try {
            if (kind == RendererKind.WEBGPU && !hasWebGpu()) {
                // no adapter API at all: say so at once; three would otherwise fall back to WebGL2 by itself
                resolve(false)
                return@Promise
            }
            val wantAntialias = options.antialias
            val webgl = kind != RendererKind.WEBGPU
            val r = THREE.WebGPURenderer(
                jsObject {
                    antialias = wantAntialias
                    forceWebGL = webgl
                },
            )
            renderer = r
            val c = r.domElement
            canvas = c
            c.setAttribute("aria-hidden", "true")
            c.className = "vb-bloom-canvas"
            val style = c.style
            style.position = "absolute"
            style.left = "0"
            style.top = "0"
            style.width = "100%"
            style.height = "100%"
            style.display = "block"
            host.insertBefore(c, host.firstChild) // under whatever cards the caller adds

            val starting: dynamic = r.init()
            starting.then(
                { _: dynamic ->
                    initialized = true
                    if (disposed) {
                        // dispose() ran while the adapter / context was still being created
                        releaseRenderer(r)
                        resolve(false)
                    } else if (kind == RendererKind.WEBGPU && r.backend.isWebGPUBackend != true) {
                        // three fell back to WebGL2 silently; that is the NEXT rung's job, not this one's
                        teardown()
                        resolve(false)
                    } else {
                        val s = THREE.Scene()
                        val g = BloomSceneGraph(world, palette, options)
                        g.setLouche(pendingLouche)
                        s.add(g.root)
                        scene = s
                        graph = g
                        camera = THREE.PerspectiveCamera(view.fovDegrees, 1.0, CAMERA_NEAR, CAMERA_FAR)
                        applyClearColor(palette)
                        ready = true
                        if (cssWidth == 0 || cssHeight == 0) {
                            val dpr = js("(typeof window !== 'undefined' && window.devicePixelRatio) || 1").unsafeCast<Double>()
                            resize(host.clientWidth, host.clientHeight, if (dpr > 2.0) 2.0 else dpr)
                        } else {
                            resize(cssWidth, cssHeight, r.getPixelRatio())
                        }
                        resolve(true)
                    }
                },
                { error: dynamic ->
                    console.warn("[bloom-three] ${kind.queryValue} rung could not start", error)
                    teardown()
                    resolve(false)
                },
            )
        } catch (e: Throwable) {
            console.warn("[bloom-three] ${kind.queryValue} rung could not start", e)
            teardown()
            resolve(false)
        }
    }

    private fun hasWebGpu(): Boolean =
        js("typeof navigator !== 'undefined' && !!navigator.gpu").unsafeCast<Boolean>()

    private fun applyClearColor(palette: Palette) {
        clearColor.setHex(palette.ground)
        renderer?.setClearColor(clearColor, 1.0)
    }

    override fun resize(cssWidth: Int, cssHeight: Int, pixelRatio: Double) {
        this.cssWidth = if (cssWidth > 0) cssWidth else 0
        this.cssHeight = if (cssHeight > 0) cssHeight else 0
        val r = renderer ?: return
        if (!ready || this.cssWidth == 0 || this.cssHeight == 0) return
        r.setPixelRatio(if (pixelRatio.isFinite() && pixelRatio > 0.0) pixelRatio else 1.0)
        r.setSize(this.cssWidth, this.cssHeight, false) // CSS size stays 100% of the host
    }

    override fun frame(unixSeconds: Double, dtSeconds: Double) {
        if (!isReady) return
        val r = renderer ?: return
        val s = scene ?: return
        val cam = camera ?: return
        val g = graph ?: return
        // never draw into a 0 x 0 canvas (hidden tab / pane): it only produces framebuffer warnings
        if (cssWidth <= 0 || cssHeight <= 0) return

        val p = g.projector
        p.setViewport(cssWidth.toDouble(), cssHeight.toDouble())
        p.setFromView(view)
        cam.fov = 2.0 * atan(p.tanHalfFov) * 180.0 / PI // same (sanitised) fov the projector uses
        cam.aspect = p.aspect
        cam.position.set(p.camX, p.camY, p.camZ)
        cam.up.set(p.upX, p.upY, p.upZ)
        lookTarget.set(p.camX + p.fwdX, p.camY + p.fwdY, p.camZ + p.fwdZ)
        cam.lookAt(lookTarget)
        cam.updateProjectionMatrix()

        g.update(unixSeconds, dtSeconds)
        r.render(s, cam)
    }

    override fun setPalette(palette: Palette) {
        graph?.setPalette(palette)
        applyClearColor(palette)
    }

    override fun setLouche(amount: Double) {
        pendingLouche = amount
        graph?.setLouche(amount)
    }

    override fun ripple(magnitude: Double) {
        graph?.ripple(magnitude)
    }

    override fun setHighlight(ringId: Int) {
        graph?.setHighlight(ringId)
    }

    override fun pick(screenX: Double, screenY: Double, slopPx: Double): Int =
        graph?.pick(screenX, screenY, slopPx) ?: RingIds.NONE

    override fun cardAnchor(ringId: Int): CardAnchor =
        graph?.cardAnchor(ringId) ?: CardAnchor(ringId, 0.0, 0.0, 0.0, 0.0, 0.0, false)

    override fun project(point: Vec3): CardAnchor =
        graph?.project(point) ?: CardAnchor(RingIds.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false)

    override fun anchorPoint(ringId: Int): Vec3 = graph?.anchorPoint(ringId) ?: Vec3.ZERO

    private fun teardown() {
        ready = false
        graph?.dispose()
        graph = null
        scene = null
        camera = null
        val c = canvas
        if (c != null) c.parentNode?.removeChild(c)
        canvas = null
        val r = renderer
        renderer = null
        if (r != null) releaseRenderer(r)
    }

    /**
     * three's dispose() is async and, on a renderer that never finished init(), first awaits init() again.
     * So: only dispose what was initialised, and swallow a late rejection instead of leaking it as an
     * unhandled promise rejection.
     */
    private fun releaseRenderer(r: THREE.WebGPURenderer) {
        if (!initialized) return
        try {
            val pending: dynamic = r.asDynamic().dispose()
            if (pending != null && jsTypeOf(pending.then) == "function") {
                pending.then({ }, { e: dynamic -> console.warn("[bloom-three] renderer.dispose failed", e) })
            }
        } catch (e: Throwable) {
            console.warn("[bloom-three] renderer.dispose failed", e)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        teardown()
    }

    companion object {
        const val CAMERA_NEAR = 0.01
        const val CAMERA_FAR = 200.0
    }
}
