package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomRenderer
import bar.verdantbloom.bloom.api.RendererKind

/**
 * Entry point of bloom-three. PLACEHOLDER from bringup.
 * The bloom-three agent implements [create]; keep the signature, :site calls exactly this.
 */
object BloomThree {
    /** @param kind WEBGPU or WEBGL (WEBGL = WebGPURenderer with forceWebGL). Returns null until implemented. */
    fun create(kind: RendererKind): BloomRenderer? = null
}
