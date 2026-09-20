package com.example.spacegraphkt.core

import bar.verdantbloom.three.CSS3D.CSS3DRenderer
import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.jsObject
import com.example.spacegraphkt.api.AgentAPI
import com.example.spacegraphkt.data.EdgeData
import com.example.spacegraphkt.data.SpaceGraphOptions
import com.example.spacegraphkt.data.UiElements
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.external.DEG2RAD_KT
import com.example.spacegraphkt.external.generateId
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.tan

/** Background colour (0xRRGGBB) + alpha of the GPU canvas. */
data class GraphBackground(val color: Int, val alpha: Double)

/**
 * Main class that orchestrates the 3D graph visualization: nodes, edges, rendering, layout, interaction.
 *
 * BRINGUP NOTE (verdantbloom-landing): the upstream SpaceGraph.kt was a half-finished rewrite against an
 * undeclared TrikeShed `Indexed` type and a fictional WebGPUInterface; it did not match any other file
 * of the port. This class is a faithful Kotlin port of `legacy/js/spacegraph.js` `class SpaceGraph`,
 * which is the API every other engine file (UIManager, AgentAPI, nodes, ForceLayout) was written against.
 * Differences from the JS: the GPU renderer is three's WebGPURenderer (SPEC 4: one code path; pass
 * `forceWebGL = true` for the webgl rung), whose init is async, so rendering starts when [ready] resolves;
 * sizes come from the container, not the window.
 */
class SpaceGraph(
    val containerElement: HTMLElement,
    uiElements: UiElements? = null,
    val options: SpaceGraphOptions = SpaceGraphOptions(uiElements = uiElements),
    /** true = WebGL2 backend of WebGPURenderer (the default rung); false = try WebGPU, three falls back to WebGL2 itself. */
    val forceWebGL: Boolean = true,
) {
    val nodes: MutableMap<String, BaseNode> = LinkedHashMap()
    val edges: MutableMap<String, Edge> = LinkedHashMap()

    var selectedNode: BaseNode? = null
        set(value) {
            if (field === value) return
            field?.setSelectedStyle(false)
            field = value
            value?.setSelectedStyle(true)
            if (value != null) selectedEdge = null
        }

    var selectedEdge: Edge? = null
        set(value) {
            if (field === value) return
            field?.setHighlight(false)
            uiManager.hideEdgeMenu()
            field = value
            value?.setHighlight(true)
            if (value != null) {
                selectedNode = null
                uiManager.showEdgeMenu(value)
            }
        }

    var isLinking: Boolean = false
    var linkSourceNode: BaseNode? = null
    var tempLinkLine: THREE.Line? = null
    var agentApi: AgentAPI? = null

    var background: GraphBackground = GraphBackground(options.backgroundColor, options.backgroundAlpha)
        private set

    val scene: THREE.Scene = THREE.Scene()
    val cssScene: THREE.Scene = THREE.Scene()
    val _camera: THREE.PerspectiveCamera = THREE.PerspectiveCamera(70, aspect(), 1, 20000)

    lateinit var gpuCanvas: HTMLCanvasElement
        private set
    lateinit var gpuRenderer: THREE.WebGPURenderer
        private set
    lateinit var css3dContainer: HTMLDivElement
        private set
    lateinit var cssRenderer: CSS3DRenderer
        private set

    val cameraController: CameraController
    val layoutEngine: ForceLayout
    val uiManager: UIManager

    /** True once WebGPURenderer.init() resolved; before that only the CSS3D layer is drawn. */
    var gpuReady: Boolean = false
        private set
    var gpuFailed: Boolean = false
        private set
    private var disposed = false
    private var animationFrameId: Int? = null
    private val resizeListener: (Event) -> Unit = { _onWindowResize() }

    init {
        _camera.position.z = 700.0
        _setupRenderers()
        setBackground(background.color, background.alpha)

        cameraController = CameraController(_camera, containerElement)
        layoutEngine = ForceLayout(this, options.layoutSettings)
        uiManager = UIManager(this, options.uiElements)

        _setupLighting()

        centerView(null, 0.0)
        cameraController.setInitialState()

        window.addEventListener("resize", resizeListener)

        _animate()
        layoutEngine.start()
    }

    private fun width(): Int = containerElement.clientWidth.takeIf { it > 0 } ?: window.innerWidth
    private fun height(): Int = containerElement.clientHeight.takeIf { it > 0 } ?: window.innerHeight
    private fun aspect(): Double = width().toDouble() / height().toDouble().coerceAtLeast(1.0)

    internal fun _setupRenderers() {
        gpuCanvas = (containerElement.querySelector("#webgl-canvas") as? HTMLCanvasElement)
            ?: (document.createElement("canvas") as HTMLCanvasElement).also {
                it.id = "webgl-canvas"
                containerElement.appendChild(it)
            }
        gpuCanvas.style.position = "absolute"
        gpuCanvas.style.asDynamic().inset = "0"
        gpuCanvas.style.zIndex = "1"

        gpuRenderer = THREE.WebGPURenderer(jsObject {
            this.canvas = gpuCanvas
            this.antialias = true
            this.alpha = true
            this.forceWebGL = this@SpaceGraph.forceWebGL
        })
        gpuRenderer.setPixelRatio(window.devicePixelRatio)
        gpuRenderer.setSize(width(), height())
        gpuRenderer.init().then<Unit>(
            { _: dynamic -> gpuReady = true },
            { err: Throwable ->
                gpuFailed = true
                console.error("SpaceGraph: GPU renderer failed to initialise; CSS3D layer only.", err)
            },
        )

        css3dContainer = (containerElement.querySelector("#css3d-container") as? HTMLDivElement)
            ?: (document.createElement("div") as HTMLDivElement).also {
                it.id = "css3d-container"
                containerElement.appendChild(it)
            }
        css3dContainer.style.position = "absolute"
        css3dContainer.style.asDynamic().inset = "0"
        css3dContainer.style.width = "100%"
        css3dContainer.style.height = "100%"
        css3dContainer.style.asDynamic().pointerEvents = "none"
        css3dContainer.style.zIndex = "2"

        cssRenderer = CSS3DRenderer()
        cssRenderer.setSize(width(), height())
        css3dContainer.appendChild(cssRenderer.domElement)
    }

    internal fun _setupLighting() {
        scene.add(THREE.AmbientLight(0xffffff, 0.8))
        val directional = THREE.DirectionalLight(0xffffff, 0.7)
        directional.position.set(0.5, 1.0, 0.75)
        scene.add(directional)
    }

    fun setBackground(color: Int = 0x000000, alpha: Double = 0.0) {
        background = GraphBackground(color, alpha)
        gpuRenderer.setClearColor(color, alpha)
        gpuCanvas.style.backgroundColor =
            if (alpha == 0.0) "transparent" else "#" + color.toString(16).padStart(6, '0')
    }

    fun addNode(node: BaseNode): BaseNode {
        nodes[node.id]?.let { return it }
        nodes[node.id] = node
        node.spaceGraphInstance = this

        when (node) {
            is HtmlNodeElement -> cssScene.add(node.css3dObject)
            is ShapeNode -> scene.add(node.mesh)
            else -> (node.threeJsObject as? THREE.Object3D)?.let { scene.add(it) }
        }
        node.labelObject?.let { cssScene.add(it) }

        layoutEngine.addNode(node)
        return node
    }

    /** @return the removed node, or null when [nodeId] is unknown. */
    fun removeNode(nodeId: String): BaseNode? {
        val node = nodes[nodeId] ?: return null
        if (selectedNode === node) selectedNode = null
        if (linkSourceNode === node) uiManager.cancelLinking()

        edges.values.filter { it.source === node || it.target === node }.forEach { removeEdge(it.id) }

        layoutEngine.removeNode(node)
        node.dispose()
        nodes.remove(nodeId)
        return node
    }

    fun addEdge(source: BaseNode?, target: BaseNode?, data: EdgeData? = null, label: String? = null): Edge? {
        if (source == null || target == null || source === target) return null
        if (edges.values.any { (it.source === source && it.target === target) || (it.source === target && it.target === source) }) {
            console.warn("Duplicate edge ignored:", source.id, target.id)
            return null
        }
        val edgeId = data?.id?.takeIf { it.isNotBlank() } ?: generateId("edge")
        val edgeData = (data ?: EdgeData(id = edgeId)).copy(id = edgeId)
        if (label != null) edgeData.label = label
        val edge = Edge(edgeId, source, target, edgeData)
        edge.spaceGraphInstance = this
        edges[edgeId] = edge
        scene.add(edge.threeJsLine)
        layoutEngine.addEdge(edge)
        return edge
    }

    /** @return the removed edge, or null when [edgeId] is unknown. */
    fun removeEdge(edgeId: String): Edge? {
        val edge = edges[edgeId] ?: return null
        if (selectedEdge === edge) selectedEdge = null
        layoutEngine.removeEdge(edge)
        edge.dispose()
        edges.remove(edgeId)
        return edge
    }

    fun getNodeById(id: String): BaseNode? = nodes[id]
    fun getEdgeById(id: String): Edge? = edges[id]

    fun _updateNodesAndEdges() {
        nodes.values.forEach { it.update() }
        edges.values.forEach { it.update() }
        uiManager.updateEdgeMenuPosition()
    }

    internal fun _render() {
        // a hidden / zero-sized host gives a 0x0 drawing buffer; rendering into it spams GL_INVALID_FRAMEBUFFER_OPERATION
        if (gpuReady && gpuCanvas.width > 0 && gpuCanvas.height > 0) gpuRenderer.render(scene, _camera)
        cssRenderer.render(cssScene, _camera)
    }

    internal fun _animate() {
        if (disposed) return
        _updateNodesAndEdges()
        _render()
        animationFrameId = window.requestAnimationFrame { _animate() }
    }

    internal fun _onWindowResize() {
        _camera.aspect = aspect()
        _camera.updateProjectionMatrix()
        gpuRenderer.setSize(width(), height())
        cssRenderer.setSize(width(), height())
    }

    fun centerView(targetPosition: Vector3D? = null, duration: Double = 0.7) {
        val target = when {
            targetPosition != null -> targetPosition
            nodes.isNotEmpty() -> {
                var x = 0.0
                var y = 0.0
                var z = 0.0
                nodes.values.forEach { x += it.position.x; y += it.position.y; z += it.position.z }
                Vector3D(x / nodes.size, y / nodes.size, z / nodes.size)
            }
            else -> Vector3D(0.0, 0.0, 0.0)
        }
        val distance = if (nodes.size > 1) 700.0 else 400.0
        cameraController.moveTo(target.x, target.y, target.z + distance, duration, target)
    }

    fun focusOnNode(node: BaseNode?, duration: Double = 0.6, pushHistory: Boolean = false) {
        if (node == null) return
        val fov = _camera.fov.toDouble() * DEG2RAD_KT
        val nodeSize = node.getBoundingSphereRadius() * 2
        val distance = (nodeSize / (2 * tan(fov / 2))) + 50
        if (pushHistory) cameraController.pushState()
        cameraController.moveTo(node.position.x, node.position.y, node.position.z + distance, duration, node.position.copy())
    }

    fun autoZoom(node: BaseNode?) {
        if (node == null) return
        if (cameraController.getCurrentTargetNodeId() == node.id) {
            cameraController.popState()
        } else {
            cameraController.pushState()
            cameraController.setCurrentTargetNodeId(node.id)
            focusOnNode(node, 0.6, false)
        }
    }

    private fun ndc(screenX: Double, screenY: Double): THREE.Vector2 {
        val rect = containerElement.getBoundingClientRect()
        val w = rect.width.coerceAtLeast(1.0)
        val h = rect.height.coerceAtLeast(1.0)
        return THREE.Vector2(((screenX - rect.left) / w) * 2 - 1, -((screenY - rect.top) / h) * 2 + 1)
    }

    /** Client (viewport) coordinates -> world point on the plane z = [targetZ]. */
    fun screenToWorld(screenX: Double, screenY: Double, targetZ: Double = 0.0): Vector3D? {
        val raycaster = THREE.Raycaster()
        raycaster.setFromCamera(ndc(screenX, screenY), _camera)
        val plane = THREE.Plane(THREE.Vector3(0.0, 0.0, 1.0), -targetZ)
        val hit = THREE.Vector3()
        return if (raycaster.ray.intersectPlane(plane, hit) != null) Vector3D.fromThreeVector(hit) else null
    }

    /** @return the [ShapeNode] or [Edge] under the client coordinates, or null. (HTML nodes are hit-tested by the DOM.) */
    fun intersectedObject(screenX: Double, screenY: Double): Any? {
        val raycaster = THREE.Raycaster()
        raycaster.setFromCamera(ndc(screenX, screenY), _camera)
        raycaster.params.Line.threshold = 5.0

        val shapeNodes = nodes.values.filterIsInstance<ShapeNode>()
        if (shapeNodes.isNotEmpty()) {
            val hits = raycaster.intersectObjects(shapeNodes.map { it.mesh }.toTypedArray())
            if (hits.isNotEmpty()) {
                val mesh = hits[0].obj
                shapeNodes.firstOrNull { it.mesh === mesh }?.let { return it }
            }
        }
        if (edges.isNotEmpty()) {
            val hits = raycaster.intersectObjects(edges.values.map { it.threeJsLine }.toTypedArray())
            if (hits.isNotEmpty()) {
                val line = hits[0].obj
                edges.values.firstOrNull { it.threeJsLine === line }?.let { return it }
            }
        }
        return null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        animationFrameId?.let { window.cancelAnimationFrame(it) }
        window.removeEventListener("resize", resizeListener)
        cameraController.dispose()
        layoutEngine.dispose()
        nodes.values.toList().forEach { it.dispose() }
        edges.values.toList().forEach { it.dispose() }
        nodes.clear()
        edges.clear()
        scene.clear()
        cssScene.clear()
        uiManager.dispose()
        gpuRenderer.dispose()
        cssRenderer.domElement.remove()
        css3dContainer.remove()
        gpuCanvas.remove()
        console.log("SpaceGraph disposed.")
    }
}
