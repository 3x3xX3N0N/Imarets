package com.example.spacegraphkt.core

import com.example.spacegraphkt.data.NodeData
import com.example.spacegraphkt.data.Vector3D
import bar.verdantbloom.three.CSS3D.CSS3DObject
import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.external.generateId

/**
 * Abstract base class for all nodes in the SpaceGraph.
 * It provides common properties like ID, position, data, and mass,
 * as well as base implementations for node behaviors.
 *
 * @property id Unique identifier for the node. Auto-generated if not provided.
 * @property position Current 3D position of the node. See [Vector3D].
 * @property data Data object associated with the node, containing properties like label, type, and custom attributes. See [NodeData].
 * @property mass Mass of the node, used by the [ForceLayout] engine for physics calculations.
 * @property spaceGraphInstance A reference to the [SpaceGraph] instance this node belongs to. Null if not added to a graph.
 * @property threeJsObject The primary Three.js object representing this node in the WebGL scene (e.g., a [THREE.Mesh] for [ShapeNode])
 *                         or CSS scene (e.g., a [CSS3DObject] for [HtmlNodeElement]). This is a dynamic type.
 * @property labelObject An optional [CSS3DObject] used for displaying text labels, typically for [ShapeNode]s or complex HTML nodes.
 */
// bringup fix: these were `open var` and re-declared with `override var` in every subclass, so this
// class's init block read the subclass's not-yet-initialised backing fields (undefined in JS) and threw.
abstract class BaseNode constructor(
    var id: String,
    var position: Vector3D,
    var data: NodeData,
    var mass: Double
) {
    var spaceGraphInstance: SpaceGraph? = null
    open var threeJsObject: dynamic = null
    open var labelObject: CSS3DObject? = null

    init {
        if (id.isBlank()) {
            this.id = generateId("node-kt-") // Ensure ID is generated if blank
        }
        // Ensure the data object also has the final, potentially generated, ID.
        this.data = this.data.copy(id = this.id)
    }

    /**
     * Sets the 3D position of the node.
     * Also updates the position of the associated [threeJsObject] and [labelObject] if they exist.
     * @param x The new x-coordinate.
     * @param y The new y-coordinate.
     * @param z The new z-coordinate.
     */
    open fun setPosition(x: Double, y: Double, z: Double) {
        this.position.set(x, y, z)
        threeJsObject?.position?.set(x,y,z) // Update Three.js object's position
        // Basic label positioning, subclasses might provide more sophisticated offset logic in update()
        labelObject?.position?.set(x,y,z)
    }

    /**
     * Abstract method to be implemented by subclasses for updating the node's state or appearance each frame.
     * This is often used for tasks like billboarding labels to face the camera.
     */
    abstract fun update()

    /**
     * Disposes of the node and its associated resources, removing them from the scene.
     * This includes disposing Three.js geometries, materials, and removing objects from their parents.
     */
    open fun dispose() {
        // engine agent fix: `threeJsObject` is dynamic, and `?.let` / `.unsafeCast` on a dynamic receiver compile to
        // JS member calls that do not exist (dispose threw "e.let is not a function"). Use a typed local instead.
        val obj: THREE.Object3D? = (threeJsObject as Any?) as? THREE.Object3D
        if (obj != null) {
            obj.parent?.remove(obj)
            if (obj is THREE.Mesh) {
                obj.geometry.dispose()
                val material: dynamic = obj.material
                if (material != null && material.dispose != undefined) material.dispose()
            }
        }
        labelObject?.let { lbl ->
            lbl.parent?.remove(lbl)
            // (lbl.element as? HTMLElement)?.remove() // If labelObject.element needs manual DOM removal
        }
        threeJsObject = null
        labelObject = null
        spaceGraphInstance = null // Clear reference
    }

    /**
     * Gets the bounding sphere radius of the node.
     * Used by layout algorithms for collision detection and spacing.
     * Subclasses should override this to provide an accurate radius based on their specific shape or size.
     * @return The radius of the node's bounding sphere. Defaults to 10.0.
     */
    open fun getBoundingSphereRadius(): Double = 10.0

    /**
     * Sets the visual style of the node to indicate selection state.
     * Subclasses should override this to implement specific visual feedback (e.g., highlighting, changing border).
     * @param selected True if the node is selected, false otherwise.
     */
    open fun setSelectedStyle(selected: Boolean) {
        // Basic example for ShapeNodes (emissive highlight)
        val mesh = (threeJsObject as Any?) as? THREE.Mesh
        if (mesh != null) {
            val material: dynamic = mesh.material
            if (material != null && material.emissive != undefined) {
                material.emissive.setHex(if (selected) 0x888800 else 0x000000)
                material.needsUpdate = true
            }
        }
        // HTML nodes will typically handle this by adding/removing CSS classes.
    }

    /**
     * Called when a drag operation starts on this node.
     * Typically invoked by the [UIManager]. This implementation fixes the node in the [ForceLayout].
     */
    open fun startDrag() {
        spaceGraphInstance?.layoutEngine?.fixNode(this)
        (threeJsObject as? CSS3DObject)?.element?.classList?.add("dragging")
    }

    /**
     * Called during a drag operation with the new proposed position.
     * Updates the node's position.
     * @param newPosition The new [Vector3D] position for the node.
     */
    open fun drag(newPosition: Vector3D) {
        setPosition(newPosition.x, newPosition.y, newPosition.z)
    }

    /**
     * Called when a drag operation ends on this node.
     * Typically invoked by the [UIManager]. This implementation releases the node in the [ForceLayout] and triggers a layout kick.
     */
    open fun endDrag() {
        spaceGraphInstance?.layoutEngine?.releaseNode(this)
        spaceGraphInstance?.layoutEngine?.kick() // Re-energize layout after manual move
        (threeJsObject as? CSS3DObject)?.element?.classList?.remove("dragging")
    }
}
