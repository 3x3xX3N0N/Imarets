package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomRenderer

/**
 * Entry point of bloom-2d, the no-WebGL rung of the rendering ladder (SPEC 4).
 * :site calls exactly [create]; keep the signature.
 */
object Bloom2d {
    /**
     * A fresh [Canvas2dRenderer], or null when this environment has no DOM / no Canvas2D at all
     * (node, a worker). A null here means "rung unavailable": the ladder has nothing below this rung, so
     * the site then leaves the no-JS document as it is.
     * Whether a 2D context can really be obtained is only known at `attach`, which resolves false if not.
     */
    fun create(): BloomRenderer? = if (isSupported()) Canvas2dRenderer() else null

    /** Same as [create] with the concrete type, for hosts that want the optional own camera (`flyTo`, `enableOwnCamera`). */
    fun createCanvas2d(): Canvas2dRenderer? = if (isSupported()) Canvas2dRenderer() else null

    fun isSupported(): Boolean =
        js("typeof document !== 'undefined' && typeof window !== 'undefined' && typeof window.CanvasRenderingContext2D !== 'undefined'")
            .unsafeCast<Boolean>()
}
