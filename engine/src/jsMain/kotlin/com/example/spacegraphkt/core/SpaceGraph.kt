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
import com.example.spacegraphkt.zui.LodLevel
import com.example.spacegraphkt.zui.LodThresholds
import com.example.spacegraphkt.zui.ViewMath
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.min

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
 *
 * ENGINE AGENT additions (see engine/API.md): `options.readOnly`, touch gestures, ONE frame loop that also
 * drives the camera, [flyTo] / [back] / [reset], semantic zoom (`data-lod`), [RendererFactory] with a clean
 * failure signal ([ready]), and the extension points bloom-three needs: [scene] / [camera] / [gpuRenderer],
 * [addFrameListener], [addPickHook], [renderOverride] and [adoptElement].
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

    /** The shared perspective camera (same object as `_camera`, the name the ported files use). */
    val camera: THREE.PerspectiveCamera get() = _camera

    /** See [SpaceGraphOptions.readOnly]. Fixed for the lifetime of the graph. */
    val readOnly: Boolean get() = options.readOnly

    /** The rung this graph was asked to start. */
    val rung: GpuRung = if (forceWebGL) GpuRung.WEBGL else GpuRung.WEBGPU

    /** Semantic-zoom thresholds for every HTML node that has none of its own. */
    var lodThresholds: LodThresholds = options.lod

    lateinit var gpuCanvas: HTMLCanvasElement
        private set

    /** null only when three could not even construct the renderer (then [ready] resolves with ok = false). */
    var gpuRenderer: THREE.WebGPURenderer? = null
        private set

    /**
     * Resolves (never rejects) once the GPU renderer finished `init()`. `ok = false` is the clean fallback
     * signal: the caller should [dispose] this graph and start the next rung. The CSS3D layer keeps working
     * either way, so a page that ignores the result still shows its cards.
     */
    lateinit var ready: Promise<GpuInitResult>
        private set

    /** What actually started; null until [ready] resolved. */
    var gpuInit: GpuInitResult? = null
        private set

    /**
     * Replaces the engine's `renderer.render(scene, camera)` call, e.g. with `THREE.PostProcessing.render()`
     * for bloom-three's glow. Only called when the GPU is ready and the canvas is not 0x0.
     */
    var renderOverride: ((renderer: THREE.WebGPURenderer, scene: THREE.Scene, camera: THREE.PerspectiveCamera) -> Unit)? = null

    private val frameListeners = ArrayList<FrameListener>()
    private val pickHooks = ArrayList<PickHook>()
    private val lodListeners = ArrayList<(HtmlNodeElement, LodLevel, LodLevel?) -> Unit>()
    private var lastFrameMs = 0.0
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
    private var resizeObserver: dynamic = null

    init {
        _camera.position.z = 700.0
        _setupRenderers()
        setBackground(background.color, background.alpha)

        cameraController = CameraController(_camera, containerElement, autoLoop = false)
        layoutEngine = ForceLayout(this, options.layoutSettings)
        layoutEngine.enabled = options.layoutEnabled
        uiManager = UIManager(this, options.uiElements)

        if (options.defaultLighting) _setupLighting()

        centerView(null, 0.0)
        cameraController.setInitialState()

        window.addEventListener("resize", resizeListener)
        // the container can change size without the window doing so (split panes, the address bar on phones)
        val observerCtor = window.asDynamic().ResizeObserver
        if (observerCtor != null) {
            val callback: () -> Unit = { _onWindowResize() }
            resizeObserver = js("new observerCtor(callback)")
            resizeObserver.observe(containerElement)
        }

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

        val renderer = RendererFactory.create(gpuCanvas, rung)
        gpuRenderer = renderer
        if (renderer != null) {
            renderer.setPixelRatio(pixelRatio())
            renderer.setSize(width(), height())
        }
        ready = RendererFactory.init(renderer, rung).then { result ->
            gpuInit = result
            if (result.ok && !disposed) {
                gpuReady = true
            } else if (!result.ok) {
                gpuFailed = true
                console.warn("SpaceGraph: GPU rung '${rung.queryValue}' did not start (${result.reason}); CSS3D layer only.")
            }
            result
        }

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

        // Touch: the graph owns gestures that START on it (pinch zoom, drag); everything outside the container
        // scrolls normally because nothing is prevented at window level.
        containerElement.style.asDynamic().touchAction = "none"

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
        gpuRenderer?.setClearColor(color, alpha)
        gpuCanvas.style.backgroundColor =
            if (alpha == 0.0) "transparent" else "#" + color.toString(16).padStart(6, '0')
    }

    fun addNode(node: BaseNode): BaseNode {
        nodes[node.id]?.let { return it }
        nodes[node.id] = node
        node.spaceGraphInstance = this
        if (node is HtmlNodeElement && readOnly) node.setReadOnly(true)

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
        val renderer = gpuRenderer
        if (renderer != null && gpuReady && gpuCanvas.width > 0 && gpuCanvas.height > 0 && containerElement.clientWidth > 0 && containerElement.clientHeight > 0) {
            val override = renderOverride
            if (override != null) override(renderer, scene, _camera) else renderer.render(scene, _camera)
        }
        cssRenderer.render(cssScene, _camera)
    }

    /** The single frame loop: camera -> frame listeners -> nodes / edges / LOD -> draw. */
    internal fun _animate() {
        if (disposed) return
        val now = Date.now()
        val dt = if (lastFrameMs == 0.0) 1.0 / 60.0 else ((now - lastFrameMs) / 1000.0).coerceIn(0.0, 0.1)
        lastFrameMs = now
        cameraController.update(now)
        _camera.updateMatrixWorld()
        if (frameListeners.isNotEmpty()) {
            for (l in frameListeners.toList()) {
                try {
                    l.onFrame(dt, now)
                } catch (e: Throwable) {
                    console.error("SpaceGraph: frame listener threw", e)
                }
            }
        }
        _updateNodesAndEdges()
        _render()
        animationFrameId = window.requestAnimationFrame { _animate() }
    }

    private fun pixelRatio(): Double = min(window.devicePixelRatio, options.maxPixelRatio).coerceAtLeast(1.0)

    private var lastW = -1
    private var lastH = -1

    internal fun _onWindowResize() {
        if (disposed) return
        val w = width()
        val h = height()
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        _camera.aspect = aspect()
        _camera.updateProjectionMatrix()
        gpuRenderer?.setPixelRatio(pixelRatio())
        gpuRenderer?.setSize(w, h)
        cssRenderer.setSize(w, h)
    }

    // ---- extension points -------------------------------------------------------------------------------------

    /** @return a function that removes the listener again. */
    fun addFrameListener(listener: FrameListener): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
    }

    fun removeFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    /** Registers an external picker that is asked BEFORE the engine's node / edge picking. See [PickHook]. */
    fun addPickHook(hook: PickHook): () -> Unit {
        pickHooks.add(hook)
        return { pickHooks.remove(hook) }
    }

    fun removePickHook(hook: PickHook) {
        pickHooks.remove(hook)
    }

    /** @return the first hook that answered true for [event], or null. */
    internal fun askPickHooks(event: PickEvent): PickHook? {
        for (hook in pickHooks.toList()) {
            try {
                if (hook.onPick(event)) return hook
            } catch (e: Throwable) {
                console.error("SpaceGraph: pick hook threw", e)
            }
        }
        return null
    }

    internal val hasPickHooks: Boolean get() = pickHooks.isNotEmpty()

    /** Called with (node, new level, previous level or null) whenever a node's `data-lod` changes. */
    fun addLodListener(listener: (node: HtmlNodeElement, level: LodLevel, previous: LodLevel?) -> Unit): () -> Unit {
        lodListeners.add(listener)
        return { lodListeners.remove(listener) }
    }

    internal fun notifyLodChanged(node: HtmlNodeElement, level: LodLevel, previous: LodLevel?) {
        for (l in lodListeners.toList()) l(node, level, previous)
    }

    /**
     * Progressive enhancement: lifts an element that is already in the document into the graph as an HTML
     * node (see [HtmlNodeElement.adopt]). Removing the node or disposing the graph puts the element back.
     */
    fun adoptElement(
        element: HTMLElement,
        position: Vector3D,
        id: String = element.id.ifBlank { generateId("adopted") },
        width: Double? = null,
        height: Double? = null,
        billboard: Boolean = true,
    ): HtmlNodeElement {
        (nodes[id] as? HtmlNodeElement)?.let { return it }
        val node = HtmlNodeElement.adopt(element, position, id, width, height, billboard)
        addNode(node)
        return node
    }

    // ---- camera ------------------------------------------------------------------------------------------------

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

    /** Distance from which [node] fits the viewport (width-limited on a narrow phone, height-limited on a desktop). */
    fun fitDistanceFor(node: BaseNode, padding: Double = 1.25): Double {
        val fov = _camera.fov.toDouble() * DEG2RAD_KT
        return if (node is HtmlNodeElement) {
            if (node.isAdopted) node.measure()
            val scale = if (node.isAdopted) 1.0 else (node.data.contentScale ?: 1.0).coerceAtLeast(1.0)
            ViewMath.fitDistance(node.size.width * scale, node.size.height * scale, fov, aspect(), padding)
        } else {
            val d = node.getBoundingSphereRadius() * 2
            ViewMath.fitDistance(d, d, fov, aspect(), padding)
        }
    }

    fun focusOnNode(node: BaseNode?, duration: Double = 0.6, pushHistory: Boolean = false) {
        if (node == null) return
        cameraController.flyTo(node.position.copy(), fitDistanceFor(node), cameraController.currentTargetNodeId, duration, pushHistory)
    }

    /**
     * ZUI navigation: selects [node] and flies the camera to it, remembering the previous view for [back].
     * Flying to the node that is already the target does nothing (no duplicate history entry).
     * @return false when the node is unknown.
     */
    fun flyTo(node: BaseNode?, duration: Double = 0.6, select: Boolean = true, onArrive: (() -> Unit)? = null): Boolean {
        if (node == null || nodes[node.id] !== node) return false
        if (select) selectedNode = node
        if (cameraController.currentTargetNodeId == node.id) {
            onArrive?.invoke()
            return true
        }
        cameraController.flyTo(node.position.copy(), fitDistanceFor(node), node.id, duration, true, onArrive)
        return true
    }

    fun flyTo(nodeId: String, duration: Double = 0.6): Boolean = flyTo(nodes[nodeId], duration)

    /** View history back (what Esc does). @return false when the history was empty. */
    fun back(duration: Double = 0.6): Boolean {
        val went = cameraController.back(duration)
        if (went) selectedNode = cameraController.currentTargetNodeId?.let { nodes[it] }
        return went
    }

    /** Overview: initial camera, empty history, nothing selected. */
    fun reset(duration: Double = 0.7) {
        selectedNode = null
        selectedEdge = null
        cameraController.resetView(duration)
    }

    fun autoZoom(node: BaseNode?) {
        if (node == null) return
        if (cameraController.getCurrentTargetNodeId() == node.id) {
            cameraController.popState()
        } else {
            flyTo(node, 0.6, select = false)
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
        if (resizeObserver != null) resizeObserver.disconnect()
        frameListeners.clear()
        pickHooks.clear()
        lodListeners.clear()
        renderOverride = null
        cameraController.dispose()
        layoutEngine.dispose()
        nodes.values.toList().forEach { it.dispose() }
        edges.values.toList().forEach { it.dispose() }
        nodes.clear()
        edges.clear()
        scene.clear()
        cssScene.clear()
        uiManager.dispose()
        try {
            gpuRenderer?.dispose()
        } catch (e: Throwable) {
            console.warn("SpaceGraph: renderer dispose threw", e)
        }
        containerElement.style.asDynamic().touchAction = ""
        cssRenderer.domElement.remove()
        css3dContainer.remove()
        gpuCanvas.remove()
    }
}
