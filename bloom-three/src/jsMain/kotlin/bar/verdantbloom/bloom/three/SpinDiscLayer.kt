package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.TriadKind
import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.asFloat32Array
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spin discs (SPEC 3.4): a fixed pool of slots, each a Group of three flat meshes (fill, wedges, stroke)
 * over shared unit geometries from [DiscGeometry]. Per frame a slot only gets a new position, quaternion,
 * scale and opacity; colours are touched when they actually change. Nothing is allocated after construction.
 */
internal class SpinDiscLayer(capacity: Int) {
    val group = THREE.Group()

    private val fillGeometry = geometryOf(DiscGeometry.fill())
    private val wedgeGeometry = geometryOf(DiscGeometry.wedges())
    private val strokeGeometry = geometryOf(DiscGeometry.stroke())

    private class Slot(val node: THREE.Group, val fill: THREE.MeshBasicMaterial, val wedge: THREE.MeshBasicMaterial, val stroke: THREE.MeshBasicMaterial) {
        var fillHex = -1
        var wedgeHex = -1
        var strokeHex = -1
    }

    private val slots: Array<Slot>
    private val spin = THREE.Quaternion()

    /** Discs drawn in the last [update]. */
    var liveCount = 0
        private set

    init {
        group.name = "vb-spin-discs"
        slots = Array(max(1, capacity)) { i ->
            val node = THREE.Group()
            node.name = "vb-spin-disc-$i"
            node.visible = false
            val fill = material()
            val wedge = material()
            val stroke = material()
            node.add(part(fillGeometry, fill), part(wedgeGeometry, wedge), part(strokeGeometry, stroke))
            group.add(node)
            Slot(node, fill, wedge, stroke)
        }
    }

    private fun geometryOf(mesh: SectorMesh): THREE.BufferGeometry {
        val g = THREE.BufferGeometry()
        g.setAttribute("position", THREE.BufferAttribute(mesh.positions.asFloat32Array(), 3))
        g.setIndex(THREE.Uint16BufferAttribute(mesh.indices.asDynamic(), 1))
        return g
    }

    private fun material(): THREE.MeshBasicMaterial {
        val m = THREE.MeshBasicMaterial()
        m.transparent = true
        m.side = THREE.DoubleSide
        m.depthTest = true
        m.depthWrite = true // discs are solid: a thread behind a disc is hidden, one in front draws over it
        m.asDynamic().forceSinglePass = true
        return m
    }

    private fun part(geometry: THREE.BufferGeometry, material: THREE.Material): THREE.Mesh {
        val mesh = THREE.Mesh(geometry, material)
        mesh.frustumCulled = false
        mesh.renderOrder = RingLayer.ORDER_DISC
        return mesh
    }

    fun update(
        discs: List<SpinDisc>,
        unixSeconds: Double,
        palette: Palette,
        proj: Projector,
        fadeStartRadius: Double,
        maxRadius: Double,
    ) {
        var used = 0
        for (index in discs.indices) {
            if (used >= slots.size) break
            val disc = discs[index]
            val p = disc.position
            if (!(p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) || !(disc.radius > 0.0)) continue
            val rad = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            val radial = 1.0 - RibbonBuffers.smooth((rad - fadeStartRadius) / max(1e-6, maxRadius - fadeStartRadius))
            val depth = proj.depthOf(p.x, p.y, p.z)
            val nearFade = RibbonBuffers.smooth((depth - RibbonBuffers.NEAR_FADE_START) / (RibbonBuffers.NEAR_FADE_END - RibbonBuffers.NEAR_FADE_START))
            val alpha = disc.alpha.coerceIn(0.0, 1.0) * radial * nearFade
            if (alpha < 0.004) continue

            val slot = slots[used++]
            slot.node.visible = true
            slot.node.position.set(p.x, p.y, p.z)
            val o = disc.orientation
            val half = DiscGeometry.angle(disc.phase, disc.bornAtUnixSeconds, disc.periodSeconds, unixSeconds) / 2.0
            spin.set(0.0, 0.0, sin(half), cos(half))
            slot.node.quaternion.set(o.x, o.y, o.z, o.w).normalize().multiply(spin) // Quat is scalar first, three scalar last
            slot.node.scale.setScalar(disc.radius)

            val themed = disc.triad == TriadKind.THEME
            val fillHex = if (themed) palette.accent else disc.discColor
            val strokeHex = if (themed) palette.alert else disc.strokeColor
            val wedgeHex = if (themed) palette.warm else disc.wedgeColor
            if (slot.fillHex != fillHex) { slot.fillHex = fillHex; slot.fill.color.setHex(fillHex) }
            if (slot.strokeHex != strokeHex) { slot.strokeHex = strokeHex; slot.stroke.color.setHex(strokeHex) }
            if (slot.wedgeHex != wedgeHex) { slot.wedgeHex = wedgeHex; slot.wedge.color.setHex(wedgeHex) }
            slot.fill.opacity = alpha
            slot.wedge.opacity = alpha
            slot.stroke.opacity = alpha
        }
        liveCount = used
        for (i in used until slots.size) slots[i].node.visible = false
    }

    fun dispose() {
        fillGeometry.dispose()
        wedgeGeometry.dispose()
        strokeGeometry.dispose()
        for (slot in slots) {
            slot.fill.dispose()
            slot.wedge.dispose()
            slot.stroke.dispose()
        }
    }
}
