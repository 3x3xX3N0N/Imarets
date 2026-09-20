package bar.verdantbloom.bloom.three

import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.asFloat32Array

/**
 * The cloudy part of "louche": one camera-facing quad behind the rings with a soft radial falloff in the
 * glow colour. At louche 0 it is not drawn at all. The four corners are rewritten in place each frame from
 * the projector's right / up vectors, so it needs no camera object and works embedded in a host scene.
 */
internal class VeilLayer(private val glowColor: dynamic) {
    private val positions = FloatArray(12)
    private val positionAttr = THREE.BufferAttribute(positions.asFloat32Array(), 3)
    val geometry = THREE.BufferGeometry()
    val gain: dynamic = TSL.uniform(0.0)
    val material: THREE.MeshBasicNodeMaterial
    val mesh: THREE.Mesh

    init {
        positionAttr.setUsage(THREE.DynamicDrawUsage)
        geometry.setAttribute("position", positionAttr)
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        geometry.setAttribute("uv", THREE.BufferAttribute(uvs.asFloat32Array(), 2))
        geometry.setIndex(THREE.Uint16BufferAttribute(intArrayOf(0, 1, 2, 0, 2, 3).asDynamic(), 1))

        val d: dynamic = TSL.length(TSL.uv().sub(0.5)).mul(2.0)
        val fall: dynamic = TSL.float(1.0).sub(TSL.smoothstep(TSL.float(0.0), TSL.float(1.0), d))

        material = THREE.MeshBasicNodeMaterial()
        material.name = "vb-veil"
        material.colorNode = glowColor
        material.opacityNode = fall.mul(fall).mul(gain)
        material.transparent = true
        material.depthTest = false
        material.depthWrite = false
        material.side = THREE.DoubleSide
        material.blending = THREE.AdditiveBlending
        material.asDynamic().forceSinglePass = true

        mesh = THREE.Mesh(geometry, material)
        mesh.name = "vb-veil"
        mesh.frustumCulled = false
        mesh.renderOrder = RingLayer.ORDER_VEIL
        mesh.visible = false
    }

    fun setNight(night: Boolean) {
        val wanted = if (night) THREE.AdditiveBlending else THREE.NormalBlending
        if (material.blending != wanted) {
            material.blending = wanted
            material.needsUpdate = true
        }
    }

    fun update(proj: Projector, radius: Double, gainValue: Double) {
        gain.value = gainValue
        mesh.visible = gainValue > 0.002
        if (!mesh.visible) return
        var k = 0
        for (corner in 0 until 4) {
            val sx = if (corner == 0 || corner == 3) -radius else radius
            val sy = if (corner < 2) -radius else radius
            positions[k++] = (proj.rightX * sx + proj.upX * sy).toFloat()
            positions[k++] = (proj.rightY * sx + proj.upY * sy).toFloat()
            positions[k++] = (proj.rightZ * sx + proj.upZ * sy).toFloat()
        }
        positionAttr.needsUpdate = true
    }

    fun dispose() {
        geometry.dispose()
        material.dispose()
    }
}
