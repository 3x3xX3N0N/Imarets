package com.example.spacegraphkt.core

import com.example.spacegraphkt.data.*
import com.example.spacegraphkt.external.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.css.CSSStyleDeclaration
import kotlin.math.max

// Import CSS3D types from external module (assumed to be defined in threejs_interop.kt)
import com.example.spacegraphkt.external.CSS3DRenderer
import com.example.spacegraphkt.external.CSS3DScene
import com.example.spacegraphkt.external.CSS3DCamera
import com.example.spacegraphkt.external.CSS3DObject

/**
 * Main class that orchestrates the 3D graph visualization.
 * Manages nodes, edges, rendering, layout, and user interactions.
 */
class SpaceGraph actual constructor(
    actual val containerElement: HTMLElement,
    actual val options: SpaceGraphOptions = SpaceGraphOptions()
) {
    // Core components
    actual val camera: CameraController = CameraController(this)
    actual val layout: ForceLayout = ForceLayout(this)
    actual val uiManager: UIManager = UIManager(this, options.uiElements)
    actual val webgpuInterface: WebGPUInterface = WebGPUInterfaceWasm()

    // State
    internal var isInitialized = false
    internal var isDisposed = false
    internal var animationFrameId: Int? = null
    internal var lastFrameTime = 0.0

    // CSS3D renderer for HTML elements
// Replace MutableList with Indexed
actual val nodes: Indexed<BaseNode> = Indexed()
// Replace MutableList with Indexed for edges
actual val edges: Indexed<Edge> = Indexed()
    internal val cssRenderer = CSS3DRenderer()
    internal val cssScene = CSS3DScene()
    internal val cssCamera = CSS3DCamera()

    // WebGPU canvas
    internal val canvas: HTMLCanvasElement = document.createElement("canvas") as HTMLCanvasElement
    internal val cssContainer: HTMLDivElement = document.createElement("div") as HTMLDivElement

    init {
        _setupRenderers()
        _bindEvents()
        _initialize()
    }

    internal fun _setupRenderers() {
        // Set up CSS3D container
        cssContainer.style.position = "absolute"
        cssContainer.style.top = "0"
        cssContainer.style.left = "0"
        cssContainer.style.width = "100%"
        cssContainer.style.height = "100%"
        cssContainer.style.pointerEvents = "none"
        containerElement.appendChild(cssContainer)

        // Set up WebGPU canvas
        canvas.style.position = "absolute"
        canvas.style.top = "0"
        canvas.style.left = "0"
        canvas.style.width = "100%"
        canvas.style.height = "100%"
        canvas.style.pointerEvents = "auto"
        containerElement.appendChild(canvas)

        // Initialize CSS3D renderer
        cssRenderer.setSize(containerElement.clientWidth, containerElement.clientHeight)
        cssRenderer.domElement = cssContainer
        cssScene.add(cssCamera)
    }

    internal fun _bindEvents() {
        window.addEventListener("resize", { _onResize() })
    }

    internal fun _initialize() {
        if (isInitialized) return

        // Initialize WebGPU
        webgpuInterface.initialize(canvas)

        // Set initial camera position
        camera.setPosition(0.0, 0.0, 1000.0)
        camera.lookAt(0.0, 0.0, 0.0)

        // Start animation loop
        _animate(0.0)

        isInitialized = true
    }

    internal fun _animate(time: Double) {
        if (isDisposed) return

        // Calculate delta time
        val deltaTime = if (lastFrameTime == 0.0) 0.0 else time - lastFrameTime
        lastFrameTime = time

        // Update layout
        layout.update(deltaTime)

        // Update camera
        camera.update(deltaTime)

        // Update CSS3D camera to match main camera
        cssCamera.position.copy(camera.position)
        cssCamera.quaternion.copy(camera.quaternion)
        cssCamera.updateMatrix()

        // Render WebGPU scene
        webgpuInterface.render()

        // Render CSS3D scene
        cssRenderer.render(cssScene, cssCamera)

        // Request next frame
        animationFrameId = window.requestAnimationFrame(::_animate)
    }

    internal fun _onResize() {
        if (isDisposed) return

        val width = containerElement.clientWidth
        val height = containerElement.clientHeight

        // Update WebGPU viewport
        webgpuInterface.resize(width, height)

        // Update CSS3D renderer
        cssRenderer.setSize(width, height)

        // Update camera aspect ratio
        camera.setAspect(width.toDouble() / height.toDouble())
    }

    actual fun addNode(node: BaseNode) {
        if (isDisposed) return

        // Add node to layout
// Replace List with Indexed in layout.addNode
    layout.addNode(node)
        layout.addNode(node)

        // Add node to WebGPU scene
        webgpuInterface.addNode(node)

        // If node has HTML content, add to CSS3D scene
        if (node is HtmlNodeElement) {
            cssScene.add(node.cssObject)
        }
    }

    actual fun removeNode(node: BaseNode) {
        if (isDisposed) return

        // Remove node from layout
        layout.removeNode(node)

        // Remove node from WebGPU scene
        webgpuInterface.removeNode(node)

        // If node has HTML content, remove from CSS3D scene
        if (node is HtmlNodeElement) {
            cssScene.remove(node.cssObject)
        }
    }

    actual fun addEdge(edge: Edge) {
        if (isDisposed) return

        // Add edge to layout
        layout.addEdge(edge)

        // Add edge to WebGPU scene
        webgpuInterface.addEdge(edge)
    }

    actual fun removeEdge(edge: Edge) {
        if (isDisposed) return

        // Remove edge from layout
        layout.removeEdge(edge)

        // Remove edge from WebGPU scene
        webgpuInterface.removeEdge(edge)
    }

    actual fun getNodeById(id: String): BaseNode? = layout.getNodeById(id)

    actual fun getEdgeById(id: String): Edge? = layout.getEdgeById(id)

    actual fun intersectedObject(x: Double, y: Double): Any? {
        // Convert screen coordinates to normalized device coordinates
        val width = containerElement.clientWidth
        val height = containerElement.clientHeight
        val ndcX = (x / width) * 2 - 1
        val ndcY = -((y / height) * 2 - 1)

        // Perform intersection test with WebGPU
        return webgpuInterface.intersect(ndcX, ndcY)
    }

    actual fun dispose() {
        if (isDisposed) return

        // Stop animation loop
        animationFrameId?.let { window.cancelAnimationFrame(it) }
        animationFrameId = null

        // Dispose WebGPU resources
        webgpuInterface.dispose()

        // Dispose CSS3D resources
        cssScene.clear()
        cssRenderer.dispose()

        // Remove event listeners
        window.removeEventListener("resize", { _onResize() })

        isDisposed = true
    }
}