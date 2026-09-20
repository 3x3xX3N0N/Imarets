package com.example.spacegraphkt.core

import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.data.CameraState
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.external.DEG2RAD_KT
import com.example.spacegraphkt.external.VecTween
import com.example.spacegraphkt.zui.Easing
import com.example.spacegraphkt.zui.ViewMath
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.js.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun clamp(value: Double, minVal: Double, maxVal: Double): Double = max(minVal, min(maxVal, value))

/**
 * Camera of the ZUI: pan, zoom (wheel and pinch), eased programmatic moves, view history.
 *
 * The camera follows two targets ([targetPosition], [targetLookAt]) with a little damping. Programmatic moves
 * ([moveTo], [flyTo], [back], [resetView]) tween the TARGETS with the Kotlin [VecTween] (ease-out cubic); user
 * input (pan / zoom / pinch) edits them directly and cancels any running tween.
 *
 * @param camera the camera to drive
 * @param domElement the graph container: its client rect is the viewport for every screen <-> world conversion
 * @param autoLoop true = run an own requestAnimationFrame loop (standalone use). [SpaceGraph] passes false and
 *   calls [update] from its single frame loop, so camera, frame listeners and rendering stay in one ordered tick.
 */
class CameraController(
    val camera: THREE.PerspectiveCamera,
    val domElement: HTMLElement,
    autoLoop: Boolean = true,
) {
    var isPanning: Boolean = false
        private set
    internal val panStart: THREE.Vector2 = THREE.Vector2()

    val targetPosition: THREE.Vector3 = camera.position.clone()
    val targetLookAt: THREE.Vector3 = THREE.Vector3(0.0, 0.0, 0.0)
    internal val currentLookAt: THREE.Vector3 = targetLookAt.clone()

    var zoomSpeed: Double = 0.0015
    var panSpeed: Double = 0.8
    var minZoomDist: Double = 20.0
    var maxZoomDist: Double = 15000.0

    /** Fraction of the remaining distance covered per 1/60 s. 1.0 = no damping. */
    var dampingFactor: Double = 0.12

    /** Easing of programmatic moves. */
    var easing: Easing = Easing.OUT_CUBIC

    /** true = programmatic moves cut instead of flying (prefers-reduced-motion); the site sets it. */
    var reducedMotion: Boolean = false

    val viewHistory: MutableList<CameraState> = mutableListOf()
    var maxHistory: Int = 20
    var initialState: CameraState? = null

    private val targetListeners = ArrayList<(String?) -> Unit>()

    /** Id of the node the camera is focused on / flying to, null while the visitor roams freely. */
    var currentTargetNodeId: String? = null
        set(value) {
            if (field == value) return
            field = value
            for (l in targetListeners.toList()) l(value)
        }

    val canGoBack: Boolean get() = viewHistory.isNotEmpty()

    private var animationFrameId: Int? = null
    private var lastUpdateMs: Double = 0.0
    private var disposed = false

    init {
        camera.lookAt(currentLookAt)
        if (autoLoop) loop()
    }

    private fun loop() {
        if (disposed) return
        update(Date.now())
        animationFrameId = window.requestAnimationFrame { loop() }
    }

    /** Called whenever [currentTargetNodeId] changes (focus, back, reset, or null when the visitor pans / zooms away). */
    fun addTargetListener(listener: (nodeId: String?) -> Unit): () -> Unit {
        targetListeners.add(listener)
        return { targetListeners.remove(listener) }
    }

    fun setInitialState() {
        if (initialState == null) initialState = snapshot()
    }

    /** Re-captures the "home" view used by [resetView] from the current targets. */
    fun captureInitialState() {
        initialState = snapshot()
    }

    private fun snapshot() = CameraState(
        Vector3D.fromThreeVector(targetPosition.clone()),
        Vector3D.fromThreeVector(targetLookAt.clone()),
        currentTargetNodeId,
    )

    private fun cancelTweens() {
        VecTween.killTweensOf(targetPosition)
        VecTween.killTweensOf(targetLookAt)
    }

    // ---- user input -------------------------------------------------------------------------------------------

    fun startPan(event: MouseEvent) {
        if (event.button.toInt() != 0) return
        startPanAt(event.clientX.toDouble(), event.clientY.toDouble())
    }

    fun startPanAt(clientX: Double, clientY: Double) {
        if (isPanning) return
        isPanning = true
        panStart.set(clientX, clientY)
        domElement.classList.add("panning")
        cancelTweens()
    }

    fun pan(event: MouseEvent) = panTo(event.clientX.toDouble(), event.clientY.toDouble())

    fun panTo(clientX: Double, clientY: Double) {
        if (!isPanning) return
        panByPixels(clientX - panStart.x, clientY - panStart.y)
        panStart.set(clientX, clientY)
    }

    /** Moves the view by a screen-space delta (CSS px), e.g. the midpoint travel of a two-finger drag. */
    fun panByPixels(deltaX: Double, deltaY: Double) {
        if (deltaX == 0.0 && deltaY == 0.0) return
        cancelTweens()
        currentTargetNodeId = null
        val cameraDist = camera.position.distanceTo(currentLookAt)
        val worldPerPx = ViewMath.worldPerPixel(
            max(1.0, cameraDist), camera.fov.toDouble() * DEG2RAD_KT, domElement.clientHeight.toDouble(),
        )
        val right = THREE.Vector3().setFromMatrixColumn(camera.matrixWorld, 0)
        val up = THREE.Vector3().setFromMatrixColumn(camera.matrixWorld, 1)
        val offset = right.multiplyScalar(-deltaX * worldPerPx * panSpeed).add(up.multiplyScalar(deltaY * worldPerPx * panSpeed))
        targetPosition.add(offset)
        targetLookAt.add(offset)
    }

    fun endPan() {
        if (!isPanning) return
        isPanning = false
        domElement.classList.remove("panning")
    }

    /** Wheel zoom towards the cursor. */
    fun zoom(event: WheelEvent) {
        // deltaMode 1 = lines, 2 = pages (Firefox with some mice); normalise to pixels
        val unit = when (event.deltaMode) {
            1 -> 16.0
            2 -> domElement.clientHeight.toDouble().coerceAtLeast(1.0)
            else -> 1.0
        }
        val delta = -event.deltaY * unit * zoomSpeed
        dollyBy(1.0 / 0.95.pow(delta * 12), event.clientX.toDouble(), event.clientY.toDouble())
    }

    /**
     * Zooms by [scale] (> 1 = closer, < 1 = further) towards the world point under the client coordinates.
     * Pinch gestures call this with the ratio of finger spans.
     */
    fun dollyBy(scale: Double, clientX: Double, clientY: Double) {
        if (scale <= 0.0 || scale == 1.0 || scale.isNaN()) return
        cancelTweens()
        currentTargetNodeId = null
        val currentDist = targetPosition.distanceTo(targetLookAt)
        val newDist = clamp(currentDist / scale, minZoomDist, maxZoomDist)
        val change = newDist - currentDist
        if (change == 0.0) return

        // view axis of the TARGET pose; kept fixed so zooming translates the view instead of swinging it round
        val viewDir = THREE.Vector3().copy(targetLookAt).sub(targetPosition)
        if (viewDir.length() < 1e-9) camera.getWorldDirection(viewDir) else viewDir.normalize()

        val anchor = _getLookAtPlaneIntersection(clientX, clientY)
        val direction = THREE.Vector3()
        if (anchor != null) direction.copy(anchor).sub(targetPosition).normalize() else direction.copy(viewDir)
        // change < 0 when zooming in: travel |change| towards the point under the cursor / between the fingers
        targetPosition.addScaledVector(direction, -change)
        targetLookAt.copy(targetPosition).addScaledVector(viewDir, newDist)
    }

    /** Client (viewport) coordinates -> point on the plane through [targetLookAt] facing the camera, or null. */
    fun _getLookAtPlaneIntersection(screenX: Double, screenY: Double): THREE.Vector3? {
        val rect = domElement.getBoundingClientRect()
        val w = if (rect.width > 0.0) rect.width else window.innerWidth.toDouble()
        val h = if (rect.height > 0.0) rect.height else window.innerHeight.toDouble()
        val ndc = THREE.Vector2(((screenX - rect.left) / w) * 2 - 1, -((screenY - rect.top) / h) * 2 + 1)
        val raycaster = THREE.Raycaster()
        raycaster.setFromCamera(ndc, camera)
        val camDir = THREE.Vector3()
        camera.getWorldDirection(camDir)
        val plane = THREE.Plane().setFromNormalAndCoplanarPoint(camDir.clone().negate(), targetLookAt)
        val hit = THREE.Vector3()
        return if (raycaster.ray.intersectPlane(plane, hit) != null) hit else null
    }

    // ---- programmatic moves -----------------------------------------------------------------------------------

    /**
     * Eased move of the camera targets.
     * @param lookAt point to look at; null = (x, y, 0)
     * @param onArrive called once when the tween finished (not when it was interrupted)
     */
    fun moveTo(x: Double, y: Double, z: Double, duration: Double = 0.7, lookAt: Vector3D? = null, onArrive: (() -> Unit)? = null) {
        setInitialState()
        val look = lookAt ?: Vector3D(x, y, 0.0)
        val seconds = if (reducedMotion) 0.0 else duration
        val now = Date.now()
        cancelTweens()
        VecTween.to(targetLookAt, look.x, look.y, look.z, seconds, easing, now)
        VecTween.to(targetPosition, x, y, z, seconds, easing, now, onArrive)
        if (seconds <= 0.0) snap()
    }

    /**
     * Flies to a view of [lookAt] from [distance] straight in front of it (+z), remembering where the visitor
     * came from so [back] / Esc returns there.
     */
    fun flyTo(lookAt: Vector3D, distance: Double, nodeId: String? = null, duration: Double = 0.6, pushHistory: Boolean = true, onArrive: (() -> Unit)? = null) {
        if (pushHistory) pushState()
        currentTargetNodeId = nodeId
        moveTo(lookAt.x, lookAt.y, lookAt.z + clamp(distance, minZoomDist, maxZoomDist), duration, lookAt.copy(), onArrive)
    }

    /** Back to the initial view; clears the history. */
    fun resetView(duration: Double = 0.7) {
        val home = initialState
        viewHistory.clear()
        if (home != null) moveTo(home.position.x, home.position.y, home.position.z, duration, home.lookAt)
        else moveTo(0.0, 0.0, 700.0, duration, Vector3D(0.0, 0.0, 0.0))
        currentTargetNodeId = home?.targetNodeId
    }

    fun reset(duration: Double = 0.7) = resetView(duration)

    fun pushState() {
        val state = snapshot()
        // a double click on the same card must not stack two identical entries
        if (viewHistory.lastOrNull() == state) return
        if (viewHistory.size >= maxHistory) viewHistory.removeAt(0)
        viewHistory.add(state)
    }

    /** Pops one history entry and flies there; with an empty history it resets the view. */
    fun popState(duration: Double = 0.6) {
        if (viewHistory.isEmpty()) {
            resetView(duration)
            return
        }
        val prev = viewHistory.removeAt(viewHistory.lastIndex)
        moveTo(prev.position.x, prev.position.y, prev.position.z, duration, prev.lookAt)
        currentTargetNodeId = prev.targetNodeId
    }

    /** View-history "back" (Esc). @return false when there was nothing to go back to (nothing happens). */
    fun back(duration: Double = 0.6): Boolean {
        if (viewHistory.isEmpty()) return false
        popState(duration)
        return true
    }

    fun getCurrentTargetNodeId(): String? = currentTargetNodeId
    fun setCurrentTargetNodeId(nodeId: String?) {
        currentTargetNodeId = nodeId
    }

    /** Puts the camera exactly on its targets (no damping tail). */
    fun snap() {
        camera.position.copy(targetPosition)
        currentLookAt.copy(targetLookAt)
        camera.lookAt(currentLookAt)
    }

    /** One tick: advance tweens, then damp the camera towards its targets. */
    fun update(nowMs: Double = Date.now()) {
        val dt = if (lastUpdateMs == 0.0) 1.0 / 60.0 else ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 0.1)
        lastUpdateMs = nowMs
        VecTween.update(nowMs)

        val deltaPos = targetPosition.distanceTo(camera.position)
        val deltaLookAt = targetLookAt.distanceTo(currentLookAt)
        if (deltaPos > 0.01 || deltaLookAt > 0.01) {
            val k = if (reducedMotion) 1.0 else 1.0 - (1.0 - dampingFactor.coerceIn(0.001, 1.0)).pow(dt * 60.0)
            camera.position.lerp(targetPosition, k)
            currentLookAt.lerp(targetLookAt, k)
            camera.lookAt(currentLookAt)
        } else if ((deltaPos > 0.0 || deltaLookAt > 0.0) && !VecTween.isTweening(targetPosition) && !VecTween.isTweening(targetLookAt)) {
            snap()
        }
    }

    @Deprecated("use update()", ReplaceWith("update()"))
    fun _updateLoop() = update()

    fun dispose() {
        disposed = true
        animationFrameId?.let { window.cancelAnimationFrame(it) }
        cancelTweens()
        viewHistory.clear()
        targetListeners.clear()
    }
}
