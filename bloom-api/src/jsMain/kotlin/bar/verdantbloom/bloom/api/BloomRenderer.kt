package bar.verdantbloom.bloom.api

import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/** Rungs of the rendering ladder (SPEC 4). `?renderer=webgpu|webgl|2d`. */
enum class RendererKind(val queryValue: String) {
    WEBGPU("webgpu"),
    WEBGL("webgl"),
    CANVAS2D("2d"),
    ;

    companion object {
        /** Default rung is WEBGL (default). Unknown / missing values give [default]. */
        fun fromQuery(value: String?, default: RendererKind = WEBGL): RendererKind =
            entries.firstOrNull { it.queryValue == value } ?: default

        /** Fallback order starting at [from]: webgpu -> webgl -> 2d. */
        fun ladderFrom(from: RendererKind): List<RendererKind> = entries.filter { it.ordinal >= from.ordinal }
    }
}

/**
 * The camera / ZUI state, owned by the caller (site), applied by the renderer. The camera sits at
 * target + orbit * (0, 0, distance), looks at [target], up = orbit * (0, 1, 0).
 * bloom-2d uses the same numbers: orbit rotates the projected points, distance sets the zoom
 * (scale = viewport height / (2 * distance * tan(fov / 2))).
 */
data class BloomView(
    val orbit: Quat = Quat.IDENTITY,
    val distance: Double = 9.0,
    val target: Vec3 = Vec3.ZERO,
    val fovDegrees: Double = 50.0,
)

/**
 * Where a DOM card / label for one ring belongs on screen. Coordinates are CSS pixels relative to
 * the host element's top-left corner.
 */
data class CardAnchor(
    val ringId: Int,
    val x: Double,
    val y: Double,
    /** CSS px per bloom unit at the anchor's depth - drives semantic zoom (far = order code, near = full card). */
    val pxPerUnit: Double,
    /** Camera-space depth, bigger = farther; use for z-index ordering. */
    val depth: Double,
    /** Ring alpha (near-pole fade) multiplied by any renderer fade. */
    val alpha: Double,
    /** false when behind the camera, off-screen by more than a card, or ring disabled. */
    val visible: Boolean,
)

/**
 * Contract implemented by BOTH bloom-three (WebGPURenderer, optionally forceWebGL) and bloom-2d (Canvas2D).
 * The caller owns the clock, the world and the view; per animation frame it does
 * `world.advanceTo(now); renderer.view = ...; renderer.frame(now, dt)` and then positions DOM cards
 * from [cardAnchor]. Renderers never add DOM event listeners for navigation: input belongs to the caller,
 * which uses [pick].
 */
interface BloomRenderer {
    val kind: RendererKind

    /**
     * Create the canvas inside [host] (position: absolute, inset 0; the renderer never styles [host] itself)
     * and build scene objects for [world]. Resolves true when ready to [frame]; resolves FALSE (never rejects)
     * when this rung cannot start (no WebGPU adapter, no WebGL, no 2D context), after cleaning up anything
     * it added, so the caller can try the next rung.
     */
    fun attach(host: HTMLElement, world: BloomWorld, palette: Palette): Promise<Boolean>

    /** Host size in CSS px changed. [pixelRatio] is already capped by the caller. */
    fun resize(cssWidth: Int, cssHeight: Int, pixelRatio: Double)

    /** Camera / ZUI state. Assigning is cheap; applied on the next [frame]. */
    var view: BloomView

    /** Re-sample rings + spin discs from the world (already advanced by the caller) and draw one frame. */
    fun frame(unixSeconds: Double, dtSeconds: Double)

    fun setPalette(palette: Palette)

    /** "Louche" knob 0..1: glow / milky cloudiness of the bloom (never of the chrome). */
    fun setLouche(amount: Double)

    /** Bell: send a ripple through the rings. [magnitude] 0..1 (scaled reply latency). */
    fun ripple(magnitude: Double)

    /** Highlight a ring (hover / focused card); RingIds.NONE clears. */
    fun setHighlight(ringId: Int)

    /**
     * Ring under the pointer. [screenX]/[screenY] are CSS px relative to the host's top-left.
     * Returns a ring id (0..9 or RingIds.VISITOR) or RingIds.NONE. The ghost ring is never pickable.
     * Must be tolerant: about 12 CSS px of slop, 24 for touch callers who pass [slopPx].
     */
    fun pick(screenX: Double, screenY: Double, slopPx: Double = 12.0): Int

    /** Screen position for ring [ringId]'s card, from world.ringAnchor(ringId) under the current view. */
    fun cardAnchor(ringId: Int): CardAnchor

    /** Project any bloom-space point with the current view (same frame of reference as [cardAnchor]). */
    fun project(point: Vec3): CardAnchor

    /** Remove the canvas, free GPU resources, stop any internal loops. Idempotent. */
    fun dispose()
}
