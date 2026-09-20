package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.RendererKind

/**
 * Entry point of bloom-three. :site calls exactly [create]; keep the signature.
 * See bloom-three/API.md for everything else.
 */
object BloomThree {
    /**
     * @param kind WEBGPU or WEBGL (WEBGL = WebGPURenderer with forceWebGL, i.e. the WebGL2 backend).
     * @return a renderer that is not attached yet, or null for CANVAS2D (that rung is bloom-2d's).
     *   Whether the rung can really start is decided by attach(): it resolves false, never rejects.
     */
    fun create(kind: RendererKind): BloomRenderer? = create(kind, BloomThreeOptions())

    fun create(kind: RendererKind, options: BloomThreeOptions): ThreeBloomRenderer? =
        if (kind == RendererKind.CANVAS2D) null else ThreeBloomRenderer(kind, options)

    /**
     * Embedded mode: only the scene objects, for a host that owns renderer, scene, camera and frame loop.
     * Add `graph.root` to the host scene and call `graph.updateFromCamera(...)` once per frame before rendering.
     */
    fun createSceneGraph(world: BloomWorld, palette: Palette, options: BloomThreeOptions = BloomThreeOptions()): BloomSceneGraph =
        BloomSceneGraph(world, palette, options)
}
