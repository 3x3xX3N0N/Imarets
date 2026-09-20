package bar.verdantbloom.bloom.three

import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.asFloat32Array

/**
 * three.js side of the rings: ONE BufferGeometry over the [RibbonBuffers] arrays (zero copy), drawn three times:
 *  1. casing - depth ONLY (no colour): the thread plus a few px either side, pushed back a hair with
 *     polygonOffset. It draws nothing, but whatever lies BEHIND it (another fiber's glow and thread) fails the
 *     depth test there, so at a crossing the far fiber is interrupted and the near one runs through: the
 *     over/under of the Hopf link reads like a knot diagram, on any ground colour.
 *  2. glow  - the whole ribbon width, soft bell falloff, depth tested but not written, additive at night and
 *     normal blending by day.
 *  3. thread - the solid core, anti-aliased in the shader from px distances, depth written.
 * All materials are MeshBasicNodeMaterial + TSL, the only custom-shading route that works on both backends
 * of WebGPURenderer (ShaderMaterial and onBeforeCompile do not).
 */
internal class RingLayer(val buffers: RibbonBuffers) {
    val geometry = THREE.BufferGeometry()

    private val positionAttr = dynamicAttribute(buffers.positions, 3)
    private val shapeAttr = dynamicAttribute(buffers.shape, 4)
    private val colorAttr = dynamicAttribute(buffers.color, 4)
    private val trimAttr = dynamicAttribute(buffers.trim, 2)

    // uniforms shared by both materials; update through .value
    val haloGain: dynamic = TSL.uniform(0.45)
    val glowMix: dynamic = TSL.uniform(0.5)
    val glowColor: dynamic = TSL.uniform(THREE.Color(0xb8ffce))
    val groundColor: dynamic = TSL.uniform(THREE.Color(0x020a07))
    val casingPx: dynamic = TSL.uniform(1.6)

    val casingMaterial: THREE.MeshBasicNodeMaterial
    val haloMaterial: THREE.MeshBasicNodeMaterial
    val coreMaterial: THREE.MeshBasicNodeMaterial
    val casingMesh: THREE.Mesh
    val haloMesh: THREE.Mesh
    val coreMesh: THREE.Mesh

    init {
        geometry.setAttribute("position", positionAttr)
        geometry.setAttribute(ATTR_SHAPE, shapeAttr)
        geometry.setAttribute(ATTR_COLOR, colorAttr)
        geometry.setAttribute(ATTR_TRIM, trimAttr)
        geometry.setIndex(THREE.Uint32BufferAttribute(buffers.indices.asDynamic(), 1))

        casingMaterial = buildCasing()
        haloMaterial = buildHalo()
        coreMaterial = buildCore()

        casingMesh = THREE.Mesh(geometry, casingMaterial)
        casingMesh.name = "vb-ring-casing"
        casingMesh.frustumCulled = false
        casingMesh.renderOrder = ORDER_CASING

        haloMesh = THREE.Mesh(geometry, haloMaterial)
        haloMesh.name = "vb-ring-glow"
        haloMesh.frustumCulled = false // vertices move every frame; never trust a stale bounding sphere
        haloMesh.renderOrder = ORDER_HALO

        coreMesh = THREE.Mesh(geometry, coreMaterial)
        coreMesh.name = "vb-ring-thread"
        coreMesh.frustumCulled = false
        coreMesh.renderOrder = ORDER_CORE
    }

    private fun dynamicAttribute(array: FloatArray, itemSize: Int): THREE.BufferAttribute {
        val attr = THREE.BufferAttribute(array.asFloat32Array(), itemSize)
        attr.setUsage(THREE.DynamicDrawUsage)
        return attr
    }

    private fun dashMask(shape: dynamic, trim: dynamic): dynamic {
        val dashed: dynamic = TSL.step(0.5, trim.x)
        val pattern: dynamic = TSL.step(0.5, TSL.fract(shape.y.mul(trim.x)))
        return TSL.mix(TSL.float(1.0), pattern, dashed)
    }

    private fun buildHalo(): THREE.MeshBasicNodeMaterial {
        val shape: dynamic = TSL.attribute(ATTR_SHAPE, "vec4")
        val col: dynamic = TSL.attribute(ATTR_COLOR, "vec4")
        val trim: dynamic = TSL.attribute(ATTR_TRIM, "vec2")
        val t: dynamic = TSL.abs(shape.x)
        // bell that is exactly 0 at the ribbon edge: (exp(-4.5 t^2) - exp(-4.5)) / (1 - exp(-4.5))
        val bell: dynamic = TSL.max(TSL.exp(t.mul(t).mul(-4.5)).sub(0.011109).div(0.988891), TSL.float(0.0))
        val alpha: dynamic = col.w.mul(bell).mul(haloGain).mul(trim.y).mul(dashMask(shape, trim))

        val m = THREE.MeshBasicNodeMaterial()
        m.name = "vb-ring-glow"
        m.colorNode = TSL.mix(col.xyz, glowColor, glowMix)
        m.opacityNode = TSL.min(alpha, TSL.float(1.0))
        m.transparent = true
        m.depthTest = true // cut by the casing of any fiber in front
        m.depthWrite = false
        m.side = THREE.DoubleSide
        m.blending = THREE.AdditiveBlending
        m.asDynamic().forceSinglePass = true
        return m
    }

    private fun buildCasing(): THREE.MeshBasicNodeMaterial {
        val shape: dynamic = TSL.attribute(ATTR_SHAPE, "vec4")
        val col: dynamic = TSL.attribute(ATTR_COLOR, "vec4")
        val trim: dynamic = TSL.attribute(ATTR_TRIM, "vec2")
        val dist: dynamic = TSL.abs(shape.x).mul(shape.z) // px from the centre line
        val edge: dynamic = shape.w.add(casingPx)
        val mask: dynamic = TSL.float(1.0).sub(TSL.smoothstep(edge.sub(AA_PX), edge.add(AA_PX), dist))

        val m = THREE.MeshBasicNodeMaterial()
        m.name = "vb-ring-casing"
        m.colorNode = groundColor // never written, colorWrite is off
        m.opacityNode = col.w.mul(mask).mul(dashMask(shape, trim))
        m.alphaTest = 0.3 // faint (fading) stretches of a ring cut no gap
        m.transparent = false
        m.colorWrite = false
        m.depthTest = true
        m.depthWrite = true
        m.side = THREE.DoubleSide
        val d = m.asDynamic()
        d.polygonOffset = true // keep the ring's own glow and thread (same triangles, same depth) in front of its casing
        d.polygonOffsetFactor = 1.0
        d.polygonOffsetUnits = 2.0
        return m
    }

    private fun buildCore(): THREE.MeshBasicNodeMaterial {
        val shape: dynamic = TSL.attribute(ATTR_SHAPE, "vec4")
        val col: dynamic = TSL.attribute(ATTR_COLOR, "vec4")
        val trim: dynamic = TSL.attribute(ATTR_TRIM, "vec2")
        val dist: dynamic = TSL.abs(shape.x).mul(shape.z) // px from the centre line
        val coreMask: dynamic = TSL.float(1.0).sub(TSL.smoothstep(shape.w.sub(AA_PX), shape.w.add(AA_PX), dist))

        val m = THREE.MeshBasicNodeMaterial()
        m.name = "vb-ring-thread"
        m.colorNode = col.xyz
        m.opacityNode = col.w.mul(coreMask).mul(dashMask(shape, trim))
        m.transparent = true
        m.alphaTest = 0.02 // discard the empty part of the wide ribbon so it writes no depth
        m.depthTest = true
        m.depthWrite = true
        m.side = THREE.DoubleSide
        m.blending = THREE.NormalBlending
        m.asDynamic().forceSinglePass = true
        return m
    }

    /** Night: glow adds light. Day (paper ground): adding light is invisible, so the glow is an ink bleed. */
    fun setNight(night: Boolean) {
        val wanted = if (night) THREE.AdditiveBlending else THREE.NormalBlending
        if (haloMaterial.blending != wanted) {
            haloMaterial.blending = wanted
            haloMaterial.needsUpdate = true
        }
    }

    /** Call once per frame after the buffers were rebuilt. [drawnRings] = leading ring slots to draw. */
    fun flush(drawnRings: Int) {
        positionAttr.needsUpdate = true
        shapeAttr.needsUpdate = true
        colorAttr.needsUpdate = true
        trimAttr.needsUpdate = true
        geometry.setDrawRange(0, buffers.indexCountFor(drawnRings))
    }

    /** [px] of clear ground either side of the thread at crossings; 0 switches the casing pass off. */
    fun setCasing(px: Double) {
        casingPx.value = px
        casingMesh.visible = px > 0.0
    }

    fun dispose() {
        geometry.dispose()
        casingMaterial.dispose()
        haloMaterial.dispose()
        coreMaterial.dispose()
    }

    companion object {
        const val ATTR_SHAPE = "vbShape"
        const val ATTR_COLOR = "vbColor"
        const val ATTR_TRIM = "vbTrim"
        const val AA_PX = 0.6
        const val ORDER_VEIL = -10
        const val ORDER_CASING = -5
        const val ORDER_HALO = 0
        const val ORDER_DISC = 5
        const val ORDER_CORE = 10
    }
}
