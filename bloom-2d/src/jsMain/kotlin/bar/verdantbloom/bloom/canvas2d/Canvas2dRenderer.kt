package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.CardAnchor
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Vec3
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The no-WebGL rung: [BloomRenderer] on a Canvas2D context. See API.md in this module.
 *
 * Contract behaviour is exactly bloom-api's: the caller owns clock, world and view, and this class adds
 * no listeners. On top of that it carries an OPTIONAL self-contained ZUI ([enableOwnCamera], [flyTo],
 * [flyHome]) so the rung is a usable interactive page even before / without the site's own input layer.
 * "Last writer wins": assigning [view] always overrides the own camera for that frame.
 */
class Canvas2dRenderer : BloomRenderer {
    override val kind: RendererKind = RendererKind.CANVAS2D

    private var host: HTMLElement? = null
    private var canvas: HTMLCanvasElement? = null
    private var ctx: CanvasRenderingContext2D? = null
    private var world: BloomWorld? = null

    private val projector = Projector()
    private val scene = Scene2d()
    private val painter = Painter2d()
    private val ripples = RippleField()
    private var glowLayer: GlowLayer? = null

    /** Adaptive glow quality. `governor.pinned = Quality2d.LEAN` (etc.) switches adaptation off. */
    val governor = QualityGovernor()

    /** The optional own camera; inert until [enableOwnCamera] or [flyTo]. */
    val camera = OrbitCamera()
    private var ownCamera = false
    private var cameraStale = true
    private var input: Zui2dInput? = null

    private var cssWidth = 0
    private var cssHeight = 0
    private var pixelRatio = 1.0
    private var projectorStale = true
    private var currentView = BloomView()

    /**
     * Samples per ring at STANDARD / RICH quality (LEAN uses half). Default 256, the rung's design load;
     * `world.config.fiberSegments` is a fine value too. Takes effect on the next frame.
     */
    var segments: Int = Scene2d.DEFAULT_SEGMENTS
        set(value) {
            field = value.coerceIn(Scene2d.MIN_SEGMENTS, Scene2d.MAX_SEGMENTS)
            applyQuality()
        }

    /** True between a successful [attach] and [dispose]. */
    val isAttached: Boolean get() = ctx != null

    /** The canvas this renderer made, null when detached. For diagnostics; do not restyle it. */
    val canvasElement: HTMLCanvasElement? get() = canvas

    /** stroke + fill calls of the last frame. */
    val drawCalls: Int get() = painter.drawCalls

    override var view: BloomView
        get() = currentView
        set(value) {
            if (value == currentView) return // writing back what was read must not cancel a tween
            currentView = value
            projectorStale = true
            if (ownCamera) camera.jumpTo(value) else cameraStale = true
        }

    override fun attach(host: HTMLElement, world: BloomWorld, palette: Palette): Promise<Boolean> {
        dispose()
        return Promise.resolve(tryAttach(host, world, palette))
    }

    private fun tryAttach(host: HTMLElement, world: BloomWorld, palette: Palette): Boolean {
        var made: HTMLCanvasElement? = null
        try {
            val document = host.ownerDocument ?: return false
            // unsafeCast, not `as`: a checked cast is an instanceof against a global that headless tests do not have
            val element = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
            made = element
            val raw: dynamic = element.asDynamic().getContext("2d")
            if (raw == null) return false
            val context = raw.unsafeCast<CanvasRenderingContext2D>()
            element.setAttribute("aria-hidden", "true")
            element.className = CANVAS_CLASS
            val style = element.style
            style.position = "absolute"
            style.left = "0"
            style.top = "0"
            style.width = "100%"
            style.height = "100%"
            style.display = "block"
            // below the DOM cards the host puts in the same element
            host.insertBefore(element, host.firstChild)

            this.host = host
            this.canvas = element
            this.ctx = context
            this.world = world
            glowLayer = makeGlowLayer(host, element)
            painter.setPalette(palette, world.rings)
            applyQuality()
            val ratio = host.ownerDocument?.defaultView?.devicePixelRatio ?: 1.0
            resize(host.clientWidth, host.clientHeight, if (ratio > MAX_AUTO_PIXEL_RATIO) MAX_AUTO_PIXEL_RATIO else ratio)
            return true
        } catch (e: Throwable) {
            console.warn("[bloom-2d] attach failed", e)
            made?.let { it.parentNode?.removeChild(it) }
            releaseAll()
            return false
        }
    }

    override fun resize(cssWidth: Int, cssHeight: Int, pixelRatio: Double) {
        this.cssWidth = if (cssWidth > 0) cssWidth else 0
        this.cssHeight = if (cssHeight > 0) cssHeight else 0
        this.pixelRatio = if (pixelRatio != pixelRatio) 1.0 else pixelRatio.coerceIn(0.5, 4.0)
        projectorStale = true
        val element = canvas ?: return
        if (this.cssWidth == 0 || this.cssHeight == 0) return // keep the old backing store; frame() skips drawing
        sizeBackingStore(element)
        glowLayer?.resize(this.cssWidth, this.cssHeight)
        camera.setViewport(this.cssWidth.toDouble(), this.cssHeight.toDouble())
    }

    override fun frame(unixSeconds: Double, dtSeconds: Double) {
        val context = ctx ?: return
        val w = world ?: return
        if (cssWidth == 0 || cssHeight == 0) return // never draw into a 0x0 host (hidden pane)
        if (ownCamera) {
            camera.update(dtSeconds)
            currentView = camera.view
        }
        projector.set(currentView, cssWidth.toDouble(), cssHeight.toDouble())
        projectorStale = false
        ripples.advance(dtSeconds)
        governor.sample(dtSeconds)
        if (governor.effective != appliedQuality) applyQuality() // covers the governor AND a host pinning a level
        scene.update(w, projector, ripples, unixSeconds)
        painter.paint(context, scene, projector, effectiveRatio, governor.effective, unixSeconds, ripples.glow, glowLayer)
    }

    override fun setPalette(palette: Palette) {
        val w = world ?: return
        painter.setPalette(palette, w.rings)
        glowLayer?.stale = true
    }

    override fun setLouche(amount: Double) {
        painter.louche = if (amount != amount) 0.0 else amount.coerceIn(0.0, 1.0)
        glowLayer?.stale = true
    }

    /**
     * The quarter-resolution canvas for the louche pass, inserted right UNDER [above] and stretched to the same
     * box by CSS. Null (the glow is then drawn into the main canvas) if it cannot be made.
     */
    private fun makeGlowLayer(host: HTMLElement, above: HTMLCanvasElement): GlowLayer? {
        var made: HTMLCanvasElement? = null
        try {
            val element = host.ownerDocument!!.createElement("canvas").unsafeCast<HTMLCanvasElement>()
            made = element
            val raw: dynamic = element.asDynamic().getContext("2d")
            if (raw == null) return null
            element.setAttribute("aria-hidden", "true")
            element.className = GLOW_CLASS
            val style = element.style
            style.position = "absolute"
            style.left = "0"
            style.top = "0"
            style.width = "100%"
            style.height = "100%"
            style.display = "block"
            host.insertBefore(element, above)
            return GlowLayer(element, raw.unsafeCast<CanvasRenderingContext2D>())
        } catch (e: Throwable) {
            made?.let { it.parentNode?.removeChild(it) }
            return null
        }
    }

    override fun ripple(magnitude: Double) {
        ripples.kick(magnitude)
    }

    override fun setHighlight(ringId: Int) {
        val next = if (ringId in 0 until RingIds.COUNT) ringId else RingIds.NONE
        if (next != painter.highlight) glowLayer?.stale = true
        painter.highlight = next
    }

    override fun pick(screenX: Double, screenY: Double, slopPx: Double): Int =
        if (ctx == null) RingIds.NONE else Picker.pick(scene, projector.near, screenX, screenY, slopPx)

    override fun cardAnchor(ringId: Int): CardAnchor {
        val w = world
        if (w == null || ringId !in 0 until RingIds.COUNT) return hidden(ringId)
        val alpha = w.ringAlpha(ringId)
        if (!(alpha > 0.0)) return hidden(ringId)
        return projectPoint(ringId, w.ringAnchor(ringId), if (alpha > 1.0) 1.0 else alpha)
    }

    override fun project(point: Vec3): CardAnchor = projectPoint(RingIds.NONE, point, 1.0)

    private fun projectPoint(ringId: Int, p: Vec3, alpha: Double): CardAnchor {
        if (cssWidth == 0 || cssHeight == 0) return hidden(ringId)
        if (projectorStale) {
            projector.set(currentView, cssWidth.toDouble(), cssHeight.toDouble())
            projectorStale = false
        }
        // ride the bell with the ring the card hangs on
        val k = if (ripples.active) 1.0 + ripples.displacement(sqrt(p.x * p.x + p.y * p.y + p.z * p.z)) else 1.0
        val inFront = projector.project(p.x * k, p.y * k, p.z * k)
        if (!inFront) return CardAnchor(ringId, 0.0, 0.0, 0.0, projector.outDepth, 0.0, false)
        val x = projector.outX
        val y = projector.outY
        val onScreen = x >= -CARD_MARGIN && x <= cssWidth + CARD_MARGIN && y >= -CARD_MARGIN && y <= cssHeight + CARD_MARGIN
        var fade = 1.25 - 0.4 * (projector.outDepth / projector.distance)
        if (fade > 1.0) fade = 1.0 else if (fade < 0.45) fade = 0.45
        return CardAnchor(ringId, x, y, projector.outScale, projector.outDepth, alpha * fade, onScreen)
    }

    private fun hidden(ringId: Int) = CardAnchor(ringId, 0.0, 0.0, 0.0, 0.0, 0.0, false)

    override fun dispose() {
        input?.detach()
        input = null
        canvas?.let { it.parentNode?.removeChild(it) }
        glowLayer?.canvas?.let { it.parentNode?.removeChild(it) }
        releaseAll()
    }

    private fun releaseAll() {
        painter.releaseContext()
        ripples.clear()
        glowLayer = null
        host = null
        canvas = null
        ctx = null
        world = null
        ownCamera = false
        cameraStale = true
    }

    private var appliedQuality: Quality2d? = null

    /** Device px per CSS px actually used for the backing store: LEAN trades a quarter of the resolution for fill rate. */
    private var effectiveRatio = 1.0

    private fun sizeBackingStore(element: HTMLCanvasElement) {
        val lean = governor.effective == Quality2d.LEAN
        effectiveRatio = if (lean && pixelRatio > 1.0) maxOf(1.0, pixelRatio * LEAN_RESOLUTION) else pixelRatio
        if (cssWidth == 0 || cssHeight == 0) return
        val bw = maxOf(1, (cssWidth * effectiveRatio).roundToInt())
        val bh = maxOf(1, (cssHeight * effectiveRatio).roundToInt())
        if (element.width != bw) element.width = bw
        if (element.height != bh) element.height = bh
    }

    private fun applyQuality() {
        appliedQuality = governor.effective
        glowLayer?.stale = true
        val lean = governor.effective == Quality2d.LEAN
        if (lean) {
            scene.configure(maxOf(LEAN_MIN_SEGMENTS, segments / 2), LEAN_CHUNKS_PER_RING)
        } else {
            scene.configure(segments, Scene2d.TARGET_CHUNKS_PER_RING)
        }
        canvas?.let { sizeBackingStore(it) }
    }

    // ------------------------------------------------------------------------------ optional own ZUI

    /**
     * Let this renderer drive its own [view] from [camera]: each [frame] advances the camera (inertia, tween,
     * follow) and adopts its view. When [inputElement] is given, pointer / wheel / pinch listeners are bound
     * to it ([Zui2dInput]); pass null to feed the camera yourself.
     * @param onRingTap what a tap on a ring does; default flies to it. The site would push history here.
     * @param onHoverRing hover feedback; default highlights the ring.
     */
    fun enableOwnCamera(
        inputElement: HTMLElement? = host,
        onRingTap: ((ringId: Int) -> Unit)? = null,
        onHoverRing: ((ringId: Int) -> Unit)? = null,
    ): OrbitCamera {
        syncCamera()
        ownCamera = true
        input?.detach()
        input = null
        if (inputElement != null) {
            val binding = Zui2dInput(inputElement, camera)
            binding.onTap = { x, y, slop ->
                val ring = pick(x, y, slop)
                if (ring != RingIds.NONE) {
                    if (onRingTap != null) onRingTap(ring) else flyTo(ring)
                }
            }
            binding.onHover = { x, y ->
                val ring = pick(x, y)
                if (onHoverRing != null) onHoverRing(ring) else setHighlight(ring)
            }
            binding.attach()
            input = binding
        }
        return camera
    }

    /** The input binding made by [enableOwnCamera], e.g. to hook `onActivity` up to `world.perturb`. */
    val ownInput: Zui2dInput? get() = input

    /** Hand the view back to the caller and remove any listeners [enableOwnCamera] added. */
    fun disableOwnCamera() {
        ownCamera = false
        input?.detach()
        input = null
    }

    /**
     * Fly to ring [ringId]'s card anchor and keep following it as the world drifts. Switches to the own camera.
     * @param seconds tween length; pass 0 for reduced motion (jump)
     */
    fun flyTo(ringId: Int, seconds: Double = 0.9, distance: Double = camera.focusDistance) {
        val w = world ?: return
        if (ringId !in 0 until RingIds.COUNT || !(w.ringAlpha(ringId) > 0.0)) return
        if (!ownCamera) {
            syncCamera()
            ownCamera = true
        }
        camera.flyTo({ w.ringAnchor(ringId) }, distance, seconds)
    }

    /** Fly back to the overview. Switches to the own camera. */
    fun flyHome(seconds: Double = 0.9) {
        if (!ownCamera) {
            syncCamera()
            ownCamera = true
        }
        camera.flyHome(seconds)
    }

    private fun syncCamera() {
        if (cameraStale) {
            camera.jumpTo(currentView)
            cameraStale = false
        }
        if (cssWidth > 0 && cssHeight > 0) camera.setViewport(cssWidth.toDouble(), cssHeight.toDouble())
    }

    /** One line for a HUD or a bug report. */
    fun diagnostics(): String =
        "2d ${cssWidth}x$cssHeight @${effectiveRatio} q=${governor.effective} seg=${scene.segments} chunks=${scene.chunksPerRing} " +
            "draws=${painter.drawCalls} dt=${(governor.averageInterval * 1000.0).roundToInt()}ms"

    companion object {
        const val CANVAS_CLASS: String = "vb-bloom-2d"
        const val GLOW_CLASS: String = "vb-bloom-2d-glow"
        const val LEAN_CHUNKS_PER_RING: Int = 16
        const val LEAN_RESOLUTION: Double = 0.75
        const val MAX_AUTO_PIXEL_RATIO: Double = 2.0
        const val CARD_MARGIN: Double = 160.0
        const val LEAN_MIN_SEGMENTS: Int = 64
    }
}
