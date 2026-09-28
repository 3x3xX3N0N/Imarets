package com.example.spacegraphkt.core

import bar.verdantbloom.three.CSS3D.CSS3DObject
import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.jsObject
import com.example.spacegraphkt.data.NodeData
import com.example.spacegraphkt.data.Size
import com.example.spacegraphkt.data.UiElements
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.external.generateId
import com.example.spacegraphkt.zui.PinchTracker
import com.example.spacegraphkt.zui.TapSlop
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLLIElement
import org.w3c.dom.Node
import org.w3c.dom.css.CSSStyleDeclaration
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.get
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import org.w3c.dom.set
import kotlin.math.max

internal data class Point(var x: Double = 0.0, var y: Double = 0.0)

internal data class PointerState(
    var down: Boolean = false,
    var primary: Boolean = false,
    var secondary: Boolean = false,
    var middle: Boolean = false,
    var potentialClick: Boolean = true,
    var lastPos: Point = Point(),
    var startPos: Point = Point(),
    var pointerType: String = "mouse",
)

internal data class MenuItemData(
    val label: String,
    val action: String,
    val data: Map<String, String> = emptyMap(),
    val type: String = "item",
    val classNames: String = "",
    val disabled: Boolean = false,
)

/**
 * All pointer / keyboard / wheel interaction of a [SpaceGraph].
 *
 * Editor mode (the POC): node drag, resize, context menus, link drawing, delete, contenteditable.
 * Read-only mode (`SpaceGraphOptions.readOnly`, the landing page): none of the above exists - the editor chrome
 * is not even added to the document. Pan, wheel zoom, touch (one finger pans, two fingers pinch-zoom and pan,
 * tap selects / flies to a node), selection, Esc = view history back all still work.
 *
 * Every pointer gesture first goes to the graph's [PickHook]s (bloom-three's ring picker), see [PickPhase].
 *
 * DOM code is CSP-safe: createElement + textContent + CSSOM style properties; no markup strings, no inline handlers.
 */
class UIManager(
    val spaceGraph: SpaceGraph,
    val uiElements: UiElements?,
) {
    val container: HTMLElement = spaceGraph.containerElement
    val readOnly: Boolean = spaceGraph.options.readOnly
    private val draggableNodes: Boolean = spaceGraph.options.draggableNodes && !readOnly
    private val focusOnTap: Boolean = spaceGraph.options.focusOnTap

    val contextMenuElement: HTMLDivElement
    val confirmDialogElement: HTMLDivElement
    val statusIndicatorElement: HTMLDivElement
    var edgeMenuObject: CSS3DObject? = null

    internal var draggedNode: BaseNode? = null
    internal var resizedNode: HtmlNodeElement? = null
    internal var hoveredEdge: Edge? = null
    internal var resizeStartPos: Point = Point()
    internal var resizeStartSize: Size = Size(0.0, 0.0)
    internal var dragOffset: THREE.Vector3 = THREE.Vector3()

    internal val pointerState: PointerState = PointerState()
    internal var confirmCallback: (() -> Unit)? = null

    /** Node the current press started on (read-only mode selects on TAP, not on press, so a drag over a card pans). */
    private var pressedNode: BaseNode? = null

    // touch bookkeeping: pointers that are down on the container and not claimed by a pick hook
    private val touches = LinkedHashMap<Int, Point>()
    private val claimed = HashMap<Int, PickHook>()
    private val pinch = PinchTracker()
    private var multiTouchGesture = false

    private var confirmMessageEl: HTMLElement? = null
    private var confirmYesEl: HTMLElement? = null
    private var confirmNoEl: HTMLElement? = null
    private var statusTimer: Int? = null

    // Listener references are kept: `this::fn` creates a NEW function object each time, so removeEventListener
    // with a fresh reference (what the upstream dispose did) never removed anything.
    private val onPointerDown: (Event) -> Unit = { _onPointerDown(it.unsafeCast<PointerEvent>()) }
    private val onPointerMove: (Event) -> Unit = { _onPointerMove(it.unsafeCast<PointerEvent>()) }
    private val onPointerUp: (Event) -> Unit = { _onPointerUp(it.unsafeCast<PointerEvent>(), false) }
    private val onPointerCancel: (Event) -> Unit = { _onPointerUp(it.unsafeCast<PointerEvent>(), true) }
    private val onContextMenu: (Event) -> Unit = { _onContextMenu(it.unsafeCast<PointerEvent>()) }
    private val onDocumentClick: (Event) -> Unit = { _onDocumentClick(it.unsafeCast<MouseEvent>()) }
    private val onKeyDown: (Event) -> Unit = { _onKeyDown(it.unsafeCast<KeyboardEvent>()) }
    private val onWheel: (Event) -> Unit = { _onWheel(it.unsafeCast<WheelEvent>()) }
    private val onContextMenuClick: (Event) -> Unit = { _onContextMenuClick(it.unsafeCast<MouseEvent>()) }
    private val onConfirmYes: (Event) -> Unit = { _onConfirmYes() }
    private val onConfirmNo: (Event) -> Unit = { _onConfirmNo() }

    init {
        contextMenuElement = uiElements?.contextMenuEl?.unsafeCast<HTMLDivElement>() ?: _createDefaultContextMenuElement()
        confirmDialogElement = uiElements?.confirmDialogEl?.unsafeCast<HTMLDivElement>() ?: _createDefaultConfirmDialogElement()
        statusIndicatorElement = uiElements?.statusIndicatorEl?.unsafeCast<HTMLDivElement>() ?: _createDefaultStatusIndicatorElement()

        // read-only: the editor chrome never enters the document
        if (!readOnly) {
            val body = document.body
            if (body != null) {
                if (!body.contains(contextMenuElement)) body.appendChild(contextMenuElement)
                if (!body.contains(confirmDialogElement)) body.appendChild(confirmDialogElement)
                if (!body.contains(statusIndicatorElement)) body.appendChild(statusIndicatorElement)
            }
        }
        _bindEvents()
    }

    private fun div(id: String, className: String): HTMLDivElement =
        (document.createElement("div") as HTMLDivElement).also { it.id = id; it.className = className }

    internal fun _createDefaultContextMenuElement(): HTMLDivElement = div("sg-context-menu", "context-menu").apply {
        style.apply { position = "absolute"; zIndex = "10000"; display = "none"; backgroundColor = "white"; border = "1px solid #ccc"; boxShadow = "2px 2px 5px rgba(0,0,0,0.2)"; minWidth = "150px"; padding = "5px 0" }
    }

    internal fun _createDefaultConfirmDialogElement(): HTMLDivElement = div("sg-confirm-dialog", "dialog").apply {
        style.display = "none"
        setAttribute("role", "dialog")
        val panel = document.createElement("div") as HTMLElement
        panel.style.apply {
            padding = "20px"; background = "white"; border = "1px solid #555"; boxShadow = "3px 3px 8px rgba(0,0,0,0.3)"
            position = "fixed"; top = "50%"; left = "50%"; transform = "translate(-50%,-50%)"; zIndex = "10001"
        }
        val message = document.createElement("p") as HTMLElement
        message.id = "sg-confirm-message"
        message.style.margin = "0 0 15px"
        message.textContent = "Are you sure?"
        val yes = document.createElement("button") as HTMLElement
        yes.id = "sg-confirm-yes"
        yes.setAttribute("type", "button")
        yes.style.marginRight = "10px"
        yes.textContent = "Yes"
        val no = document.createElement("button") as HTMLElement
        no.id = "sg-confirm-no"
        no.setAttribute("type", "button")
        no.textContent = "No"
        panel.appendChild(message)
        panel.appendChild(yes)
        panel.appendChild(no)
        appendChild(panel)
    }

    internal fun _createDefaultStatusIndicatorElement(): HTMLDivElement = div("sg-status-indicator", "status-indicator").apply {
        setAttribute("role", "status")
        style.apply { position = "fixed"; bottom = "20px"; left = "50%"; transform = "translateX(-50%)"; padding = "10px 20px"; backgroundColor = "rgba(0,0,0,0.7)"; color = "white"; zIndex = "10002"; display = "none"; transition = "opacity 0.5s"; opacity = "0" }
    }

    internal fun _bindEvents() {
        container.addEventListener("pointerdown", onPointerDown)
        window.addEventListener("pointermove", onPointerMove)
        window.addEventListener("pointerup", onPointerUp)
        window.addEventListener("pointercancel", onPointerCancel)
        window.addEventListener("keydown", onKeyDown)
        // non-passive so the graph can keep the page from scrolling WHILE THE WHEEL IS OVER THE GRAPH; nothing is
        // registered for wheel / touch outside the container, so the rest of the page scrolls normally
        container.addEventListener("wheel", onWheel, jsObject { passive = false })
        if (readOnly) return
        container.addEventListener("contextmenu", onContextMenu)
        document.addEventListener("click", onDocumentClick, true)
        contextMenuElement.addEventListener("click", onContextMenuClick)
        confirmMessageEl = confirmDialogElement.querySelector("#sg-confirm-message") as? HTMLElement
        confirmYesEl = confirmDialogElement.querySelector("#sg-confirm-yes") as? HTMLElement
        confirmNoEl = confirmDialogElement.querySelector("#sg-confirm-no") as? HTMLElement
        confirmYesEl?.addEventListener("click", onConfirmYes)
        confirmNoEl?.addEventListener("click", onConfirmNo)
    }

    internal fun _updatePointerState(e: PointerEvent, isDown: Boolean) {
        pointerState.down = isDown
        pointerState.primary = isDown && e.button.toInt() == 0
        pointerState.secondary = isDown && e.button.toInt() == 2
        pointerState.middle = isDown && e.button.toInt() == 1
        if (isDown) {
            pointerState.potentialClick = true
            pointerState.startPos = Point(e.clientX.toDouble(), e.clientY.toDouble())
            pointerState.pointerType = pointerTypeOf(e)
        }
        pointerState.lastPos = Point(e.clientX.toDouble(), e.clientY.toDouble())
    }

    private fun pointerTypeOf(e: PointerEvent): String {
        val t: String? = e.asDynamic().pointerType as? String
        return if (t.isNullOrEmpty()) "mouse" else t
    }

    private fun pointerIdOf(e: PointerEvent): Int = (e.asDynamic().pointerId as? Int) ?: 0

    internal data class TargetInfo(
        val element: HTMLElement?, val nodeHtmlElement: HTMLElement?, val resizeHandle: HTMLElement?,
        val nodeControlsButton: HTMLButtonElement?, val contentEditable: HTMLElement?, val interactiveInNode: HTMLElement?,
        val node: BaseNode?, val intersectedEdge: Edge?,
    )

    /** Finds what is under the pointer without acting on it. HTML nodes are hit-tested by the DOM, shapes and edges by ray. */
    internal fun _getTargetInfo(event: MouseEvent): TargetInfo {
        val element = document.elementFromPoint(event.clientX.toDouble(), event.clientY.toDouble()) as? HTMLElement
        val nodeHtmlElement = element?.closest(".node-html") as? HTMLElement
        val resizeHandle = element?.closest(".resize-handle") as? HTMLElement
        val nodeControlsButton = element?.closest(".node-controls button") as? HTMLButtonElement
        val contentEditable = element?.closest("[contenteditable='true']") as? HTMLElement
        val interactiveInNode = if (nodeHtmlElement == null) null
        else element.closest("a[href], button, input, select, textarea, label, summary, [data-sg-interactive]") as? HTMLElement

        var node: BaseNode? = nodeHtmlElement?.getAttribute("data-node-id")?.let { spaceGraph.getNodeById(it) }
        var intersectedEdge: Edge? = null

        val isDirectHtmlInteraction = nodeHtmlElement != null && (resizeHandle != null || nodeControlsButton != null || contentEditable != null || interactiveInNode != null)
        if (!isDirectHtmlInteraction && nodeHtmlElement == null) {
            when (val hit = spaceGraph.intersectedObject(event.clientX.toDouble(), event.clientY.toDouble())) {
                is Edge -> intersectedEdge = hit
                is BaseNode -> node = hit
            }
        }
        return TargetInfo(element, nodeHtmlElement, resizeHandle, nodeControlsButton, contentEditable, interactiveInNode, node, intersectedEdge)
    }

    private fun pickEvent(phase: PickPhase, e: PointerEvent, node: BaseNode?): PickEvent {
        val rect = container.getBoundingClientRect()
        val type = pointerTypeOf(e)
        return PickEvent(
            phase, e.clientX - rect.left, e.clientY - rect.top, type,
            if (type == "mouse") 12.0 else 24.0, pointerIdOf(e), node, e,
        )
    }

    private fun firstTwoTouches(): List<Point> = touches.values.take(2)

    internal fun _onPointerDown(e: PointerEvent) {
        val type = pointerTypeOf(e)
        val id = pointerIdOf(e)

        // a primary touch starts a new gesture: drop anything a lost pointerup may have left behind
        if (type == "touch" && e.asDynamic().isPrimary == true) {
            touches.clear()
            pinch.end()
            multiTouchGesture = false
        }

        // a second finger turns whatever the first one was doing into a pinch / two-finger pan
        if (type == "touch" && touches.isNotEmpty()) {
            touches[id] = Point(e.clientX.toDouble(), e.clientY.toDouble())
            if (touches.size == 2) {
                _abortSinglePointerGesture()
                val (a, b) = firstTwoTouches()
                pinch.begin(a.x, a.y, b.x, b.y)
                multiTouchGesture = true
            }
            pointerState.potentialClick = false
            return
        }

        _updatePointerState(e, true)
        val targetInfo = _getTargetInfo(e)
        pressedNode = null

        if (spaceGraph.hasPickHooks) {
            val hook = spaceGraph.askPickHooks(pickEvent(PickPhase.DOWN, e, targetInfo.node))
            if (hook != null) {
                claimed[id] = hook
                pointerState.potentialClick = false
                _hideContextMenu()
                return
            }
        }
        if (type == "touch") {
            touches[id] = Point(e.clientX.toDouble(), e.clientY.toDouble())
            multiTouchGesture = false
        }

        if (!readOnly && targetInfo.nodeControlsButton != null && targetInfo.node is HtmlNodeElement) {
            e.preventDefault(); e.stopPropagation()
            _handleNodeControlButtonClick(targetInfo.nodeControlsButton, targetInfo.node)
            _hideContextMenu(); return
        }
        if (!readOnly && targetInfo.resizeHandle != null && targetInfo.node is HtmlNodeElement) {
            e.preventDefault(); e.stopPropagation()
            resizedNode = targetInfo.node.also { it.startResize() }
            resizeStartPos = Point(e.clientX.toDouble(), e.clientY.toDouble()); resizeStartSize = targetInfo.node.size.copy()
            container.style.cursor = "nwse-resize"; _hideContextMenu(); return
        }
        _hideContextMenu()
        val node = targetInfo.node
        if (node != null) {
            if (targetInfo.interactiveInNode != null || targetInfo.contentEditable != null) {
                // a link / button / form field inside a card: the browser handles it, the graph only notes the selection
                pointerState.potentialClick = false
                if (spaceGraph.selectedNode != node) spaceGraph.selectedNode = node
                return
            }
            pressedNode = node
            if (draggableNodes && pointerState.primary) {
                e.preventDefault()
                draggedNode = node.also { it.startDrag() }
                val worldPos = spaceGraph.screenToWorld(e.clientX.toDouble(), e.clientY.toDouble(), node.position.z)
                dragOffset = worldPos?.toThreeVector()?.sub(node.position.toThreeVector()) ?: THREE.Vector3()
                container.style.cursor = "grabbing"
                if (spaceGraph.selectedNode != node) spaceGraph.selectedNode = node
                return
            }
            // nodes that cannot be dragged behave like background for panning: on a phone a focused card fills the
            // screen, and the visitor must still be able to drag away from it
            if (pointerState.primary) spaceGraph.cameraController.startPanAt(e.clientX.toDouble(), e.clientY.toDouble())
        } else if (targetInfo.intersectedEdge != null) {
            e.preventDefault(); spaceGraph.selectedEdge = targetInfo.intersectedEdge
        } else if (pointerState.primary) {
            spaceGraph.cameraController.startPanAt(e.clientX.toDouble(), e.clientY.toDouble())
        }
    }

    private fun _abortSinglePointerGesture() {
        resizedNode?.let { it.endResize(); resizedNode = null }
        draggedNode?.let { it.endDrag(); draggedNode = null }
        spaceGraph.cameraController.endPan()
        pressedNode = null
        pointerState.potentialClick = false
    }

    internal fun _handleNodeControlButtonClick(button: HTMLButtonElement, node: HtmlNodeElement) {
        if (readOnly) return
        val actionMap = mapOf<String, () -> Unit>(
            "node-delete" to { _showConfirm("Delete node \"${node.id.take(10)}...\"?") { spaceGraph.removeNode(node.id) } },
            "node-content-zoom-in" to { node.adjustContentScale(1.15) }, "node-content-zoom-out" to { node.adjustContentScale(1 / 1.15) },
            "node-grow" to { node.adjustNodeSize(1.2) }, "node-shrink" to { node.adjustNodeSize(0.8) },
        )
        for (i in 0 until button.classList.length) {
            val action = actionMap[button.classList.item(i) ?: continue]
            if (action != null) { action.invoke(); return }
        }
    }

    internal fun _onPointerMove(e: PointerEvent) {
        val id = pointerIdOf(e)
        claimed[id]?.let { hook ->
            try { hook.onPick(pickEvent(PickPhase.MOVE, e, null)) } catch (err: Throwable) { console.error("SpaceGraph: pick hook threw", err) }
            return
        }
        val type = pointerTypeOf(e)
        if (type == "touch") {
            val p = touches[id] ?: return // a touch that did not start on the graph is none of our business
            p.x = e.clientX.toDouble(); p.y = e.clientY.toDouble()
            if (pinch.active && touches.size >= 2) {
                val (a, b) = firstTwoTouches()
                val step = pinch.move(a.x, a.y, b.x, b.y)
                val camera = spaceGraph.cameraController
                camera.dollyBy(step.scale, step.centerX, step.centerY)
                camera.panByPixels(step.panX, step.panY)
                return
            }
            if (multiTouchGesture) return // the finger left over after a pinch resumes panning below only once re-armed
        }

        if (pointerState.down && pointerState.potentialClick &&
            !TapSlop.isTap(pointerState.startPos.x, pointerState.startPos.y, e.clientX.toDouble(), e.clientY.toDouble(), pointerState.pointerType)
        ) pointerState.potentialClick = false
        pointerState.lastPos = Point(e.clientX.toDouble(), e.clientY.toDouble())

        resizedNode?.let { node ->
            e.preventDefault()
            node.resize(resizeStartSize.width + (e.clientX - resizeStartPos.x), resizeStartSize.height + (e.clientY - resizeStartPos.y))
            return
        }
        draggedNode?.let { node ->
            e.preventDefault()
            val worldPos = spaceGraph.screenToWorld(e.clientX.toDouble(), e.clientY.toDouble(), node.position.z)
            worldPos?.let { node.drag(Vector3D.fromThreeVector(it.toThreeVector().sub(dragOffset))) }
            return
        }
        if (spaceGraph.isLinking) {
            e.preventDefault(); _updateTempLinkLine(e.clientX.toDouble(), e.clientY.toDouble())
            val targetInfo = _getTargetInfo(e)
            clearLinkingTargets()
            val target = targetInfo.node
            if (target != null && target != spaceGraph.linkSourceNode && target is HtmlNodeElement) target.htmlElement.classList.add("linking-target")
            return
        }
        if (pointerState.primary && spaceGraph.cameraController.isPanning) {
            // the slop keeps a shaky tap from nudging the view
            if (!pointerState.potentialClick) spaceGraph.cameraController.panTo(e.clientX.toDouble(), e.clientY.toDouble())
            return
        }
        if (!pointerState.down && type == "mouse" && container.contains(e.target as? Node)) {
            val targetInfo = _getTargetInfo(e)
            val hookHasIt = spaceGraph.hasPickHooks && spaceGraph.askPickHooks(pickEvent(PickPhase.HOVER, e, targetInfo.node)) != null
            val current = if (hookHasIt) null else targetInfo.intersectedEdge
            if (hoveredEdge != current) {
                hoveredEdge?.takeIf { it != spaceGraph.selectedEdge }?.setHighlight(false)
                hoveredEdge = current
                hoveredEdge?.takeIf { it != spaceGraph.selectedEdge }?.setHighlight(true)
            }
        }
    }

    /** pointerup and pointercancel ([cancelled] = true: never a tap). */
    internal fun _onPointerUp(e: PointerEvent, cancelled: Boolean) {
        val id = pointerIdOf(e)
        claimed.remove(id)?.let { hook ->
            try { hook.onPick(pickEvent(if (cancelled) PickPhase.CANCEL else PickPhase.UP, e, null)) } catch (err: Throwable) { console.error("SpaceGraph: pick hook threw", err) }
            _updatePointerState(e, false)
            return
        }
        val type = pointerTypeOf(e)
        if (type == "touch") {
            if (touches.remove(id) == null) return
            if (multiTouchGesture) {
                if (touches.size < 2) pinch.end()
                if (touches.isEmpty()) {
                    multiTouchGesture = false
                    _updatePointerState(e, false)
                }
                return // lifting fingers after a pinch is never a tap
            }
        }

        // presses that started somewhere else on the page are not ours (this listener sits on window)
        if (!pointerState.down) return

        container.style.cursor = if (spaceGraph.isLinking) "crosshair" else ""
        resizedNode?.let { it.endResize(); resizedNode = null }
        draggedNode?.let { it.endDrag(); draggedNode = null }

        val isTap = !cancelled && pointerState.potentialClick
        if (spaceGraph.isLinking && e.button.toInt() == 0 && !cancelled) {
            _completeLinking(e)
        } else if (isTap && e.button.toInt() == 1) {
            _getTargetInfo(e).node?.let { spaceGraph.autoZoom(it); e.preventDefault() }
        } else if (isTap && e.button.toInt() == 0) {
            val targetInfo = _getTargetInfo(e)
            val taken = spaceGraph.hasPickHooks && spaceGraph.askPickHooks(pickEvent(PickPhase.TAP, e, targetInfo.node)) != null
            if (!taken) {
                val node = targetInfo.node?.takeIf { pressedNode == null || it === pressedNode }
                when {
                    node != null -> {
                        if (spaceGraph.selectedNode !== node) spaceGraph.selectedNode = node
                        if (focusOnTap) spaceGraph.flyTo(node)
                    }
                    targetInfo.intersectedEdge == null -> {
                        spaceGraph.selectedNode = null
                        spaceGraph.selectedEdge = null
                    }
                }
            }
        }

        spaceGraph.cameraController.endPan()
        pressedNode = null
        _updatePointerState(e, false)
        clearLinkingTargets()
    }

    internal fun _onContextMenu(e: PointerEvent) {
        if (readOnly) return // the browser's own menu stays available on the landing page
        e.preventDefault(); _hideContextMenu()
        val targetInfo = _getTargetInfo(e)
        val items: List<MenuItemData> = when {
            targetInfo.node != null -> { spaceGraph.selectedNode = targetInfo.node; _getContextMenuItemsNode(targetInfo.node) }
            targetInfo.intersectedEdge != null -> { spaceGraph.selectedEdge = targetInfo.intersectedEdge; _getContextMenuItemsEdge(targetInfo.intersectedEdge) }
            else -> {
                spaceGraph.selectedNode = null; spaceGraph.selectedEdge = null
                _getContextMenuItemsBackground(spaceGraph.screenToWorld(e.clientX.toDouble(), e.clientY.toDouble(), 0.0))
            }
        }
        if (items.isNotEmpty()) _showContextMenu(e.clientX.toDouble(), e.clientY.toDouble(), items)
    }

    internal fun _onDocumentClick(e: MouseEvent) {
        if (!contextMenuElement.contains(e.target as? Node)) _hideContextMenu()
        edgeMenuObject?.element?.let {
            if (!it.contains(e.target as? Node)) {
                val probe = PointerEvent("pointerdown", PointerEventInit(clientX = e.clientX, clientY = e.clientY, button = e.button))
                if (spaceGraph.selectedEdge != _getTargetInfo(probe).intersectedEdge) spaceGraph.selectedEdge = null
            }
        }
    }

    internal fun _getContextMenuItemsNode(node: BaseNode): List<MenuItemData> = mutableListOf<MenuItemData>().apply {
        if (node is HtmlNodeElement && node.data.editable == true) add(MenuItemData("Edit Content", "edit-node", mapOf("nodeId" to node.id)))
        add(MenuItemData("Start Link", "start-link", mapOf("nodeId" to node.id)))
        add(MenuItemData("Auto Zoom / Back", "autozoom-node", mapOf("nodeId" to node.id)))
        add(MenuItemData("", "", type = "separator"))
        add(MenuItemData("Delete Node", "delete-node", mapOf("nodeId" to node.id), classNames = "delete-action"))
    }

    internal fun _getContextMenuItemsEdge(edge: Edge): List<MenuItemData> = listOf(
        MenuItemData("Edit Edge Style...", "edit-edge", mapOf("edgeId" to edge.id)),
        MenuItemData("Reverse Edge Direction", "reverse-edge", mapOf("edgeId" to edge.id)),
        MenuItemData("", "", type = "separator"),
        MenuItemData("Delete Edge", "delete-edge", mapOf("edgeId" to edge.id), classNames = "delete-action"),
    )

    internal fun _getContextMenuItemsBackground(worldPos: Vector3D?): List<MenuItemData> = mutableListOf<MenuItemData>().apply {
        worldPos?.let { pos ->
            val posStr = JSON.stringify(jsObject { x = pos.x; y = pos.y; z = pos.z })
            add(MenuItemData("Create Note Here", "create-note", mapOf("position" to posStr)))
            add(MenuItemData("Create Box Here", "create-box", mapOf("position" to posStr)))
            add(MenuItemData("Create Sphere Here", "create-sphere", mapOf("position" to posStr)))
        }
        add(MenuItemData("", "", type = "separator"))
        add(MenuItemData("Center View", "center-view"))
        add(MenuItemData("Reset Zoom & Pan", "reset-view"))
        add(MenuItemData(if (spaceGraph.background.alpha == 0.0) "Set Dark Background" else "Set Transparent BG", "toggle-background"))
    }

    internal fun _onContextMenuClick(event: MouseEvent) {
        if (readOnly) return
        val li = (event.target as? HTMLElement)?.closest("li") as? HTMLLIElement ?: return
        val action = li.dataset["action"] ?: return; _hideContextMenu()
        val data = li.dataset
        val nodeId = data["nodeId"]
        val edgeId = data["edgeId"]
        val position = data["position"]

        when (action) {
            "edit-node" -> nodeId?.let { spaceGraph.getNodeById(it) }?.let { if (it is HtmlNodeElement && it.data.editable == true) (it.htmlElement.querySelector(".node-content") as? HTMLElement)?.focus() }
            "delete-node" -> nodeId?.let { nid -> _showConfirm("Delete node \"${nid.take(10)}...\"?") { spaceGraph.removeNode(nid) } }
            "delete-edge" -> edgeId?.let { eid -> _showConfirm("Delete edge \"${eid.take(10)}...\"?") { spaceGraph.removeEdge(eid) } }
            "autozoom-node" -> nodeId?.let { spaceGraph.getNodeById(it) }?.let { spaceGraph.autoZoom(it) }
            "create-note" -> position?.let { _createNodeFromMenu(it, "note", jsObject { content = "New Note" }) }
            "create-box" -> position?.let { _createNodeFromMenu(it, "shape", jsObject { label = "Box"; shapeType = "box"; shapeColor = (kotlin.js.Math.random() * 0xFFFFFF).toInt() }) }
            "create-sphere" -> position?.let { _createNodeFromMenu(it, "shape", jsObject { label = "Sphere"; shapeType = "sphere"; shapeColor = (kotlin.js.Math.random() * 0xFFFFFF).toInt() }) }
            "center-view" -> spaceGraph.centerView(null, 0.7)
            "reset-view" -> spaceGraph.reset()
            "start-link" -> nodeId?.let { spaceGraph.getNodeById(it) }?.let { _startLinking(it) }
            "reverse-edge" -> edgeId?.let { spaceGraph.getEdgeById(it) }?.apply { val temp = source; source = target; target = temp; update(); spaceGraph.layoutEngine.kick() }
            "edit-edge" -> edgeId?.let { spaceGraph.getEdgeById(it) }?.let { spaceGraph.selectedEdge = it }
            "toggle-background" -> spaceGraph.setBackground(if (spaceGraph.background.alpha == 0.0) 0x101018 else 0x000000, if (spaceGraph.background.alpha == 0.0) 1.0 else 0.0)
            else -> console.warn("Unknown context menu action: $action")
        }
    }

    internal fun _createNodeFromMenu(positionData: String, type: String, nodeCustomData: dynamic) {
        if (readOnly) return
        try {
            val posJson = JSON.parse<dynamic>(positionData)
            val pos = Vector3D(posJson.x as Double, posJson.y as Double, posJson.z as Double)
            val nodeId = generateId("node-kt-")
            val baseData = NodeData(id = nodeId, label = nodeCustomData.label as? String ?: type, type = type, custom = nodeCustomData)
            val finalData = when (type) {
                "note" -> baseData.copy(content = nodeCustomData.content as? String ?: "New Note", editable = true, width = 200.0, height = 100.0)
                "shape" -> baseData.copy(shapeType = nodeCustomData.shapeType as? String ?: "sphere", shapeColor = nodeCustomData.shapeColor as? Int ?: 0xffffff, shapeSize = nodeCustomData.shapeSize as? Double ?: 50.0)
                else -> baseData
            }
            val newNode: BaseNode = when (type) {
                "note" -> NoteNode(nodeId, pos, finalData)
                "shape" -> ShapeNode(nodeId, pos, finalData, finalData.shapeType!!, finalData.shapeSize!!, finalData.shapeColor!!)
                else -> BaseNodeImpl(nodeId, pos, finalData)
            }
            spaceGraph.addNode(newNode)
            spaceGraph.layoutEngine.kick()
            window.setTimeout({
                spaceGraph.focusOnNode(newNode, 0.6, true); spaceGraph.selectedNode = newNode
                if (newNode is NoteNode) (newNode.htmlElement.querySelector(".node-content") as? HTMLElement)?.focus()
            }, 100)
        } catch (err: Throwable) {
            console.error("Failed to create node from menu:", err)
        }
    }

    internal class BaseNodeImpl(id: String, position: Vector3D, data: NodeData) : BaseNode(id, position, data, data.mass) {
        override fun update() {}
    }

    internal fun _showContextMenu(x: Double, y: Double, items: List<MenuItemData>) {
        if (readOnly) return
        while (contextMenuElement.firstChild != null) contextMenuElement.removeChild(contextMenuElement.firstChild!!)
        val ul = document.createElement("ul") as HTMLElement
        ul.style.apply { listStyle = "none"; margin = "0"; padding = "0" }
        items.forEach { item ->
            val li = document.createElement("li") as HTMLLIElement
            if (item.type == "separator") {
                li.style.apply { height = "1px"; backgroundColor = "#eee"; margin = "5px 0" }
            } else {
                li.textContent = item.label
                li.style.apply { padding = "8px 15px"; cursor = "pointer" }
                if (item.disabled) { li.style.opacity = "0.5"; li.style.asDynamic().pointerEvents = "none" }
                else {
                    li.addEventListener("mouseenter", { li.style.backgroundColor = "#f0f0f0" })
                    li.addEventListener("mouseleave", { li.style.backgroundColor = "white" })
                }
                item.data.forEach { (k, v) -> li.dataset[k] = v }; li.dataset["action"] = item.action
                if (item.classNames.isNotBlank()) item.classNames.split(" ").forEach { li.classList.add(it) }
            }
            ul.appendChild(li)
        }
        contextMenuElement.appendChild(ul)
        contextMenuElement.style.display = "block"
        val menuWidth = contextMenuElement.offsetWidth; val menuHeight = contextMenuElement.offsetHeight
        var finalX = x + 5; var finalY = y + 5
        if (finalX + menuWidth > window.innerWidth) finalX = x - menuWidth - 5
        if (finalY + menuHeight > window.innerHeight) finalY = y - menuHeight - 5
        contextMenuElement.style.apply { left = "${max(5.0, finalX)}px"; top = "${max(5.0, finalY)}px" }
    }

    internal fun _hideContextMenu() { contextMenuElement.style.display = "none" }

    internal fun _showConfirm(message: String, onConfirm: () -> Unit) {
        if (readOnly) return
        (confirmMessageEl ?: confirmDialogElement.querySelector("#sg-confirm-message") as? HTMLElement)?.textContent = message
        confirmCallback = onConfirm; confirmDialogElement.style.display = "block"
    }
    internal fun _hideConfirm() { confirmDialogElement.style.display = "none"; confirmCallback = null }
    internal fun _onConfirmYes() { val cb = confirmCallback; _hideConfirm(); cb?.invoke() }
    internal fun _onConfirmNo() { _hideConfirm() }

    fun _startLinking(sourceNode: BaseNode) {
        if (readOnly || spaceGraph.isLinking) return
        spaceGraph.isLinking = true; spaceGraph.linkSourceNode = sourceNode
        container.style.cursor = "crosshair"; _createTempLinkLine(sourceNode)
        showStatus("Linking: Click on target node, or ESC to cancel.", 0)
        spaceGraph.agentApi?.dispatchGraphEvent("linkStarted", jsObject { this.sourceNodeId = sourceNode.id })
    }

    internal fun _createTempLinkLine(sourceNode: BaseNode) {
        _removeTempLinkLine()
        val material = THREE.LineDashedMaterial(jsObject { color = 0xffaa00; linewidth = 2; dashSize = 8; gapSize = 4; transparent = true; opacity = 0.9; depthTest = false })
        val points = arrayOf(sourceNode.position.toThreeVector(), sourceNode.position.toThreeVector().clone())
        val geometry = THREE.BufferGeometry().setFromPoints(points)
        spaceGraph.tempLinkLine = THREE.Line(geometry, material).apply { computeLineDistances(); renderOrder = 1 }
        spaceGraph.scene.add(spaceGraph.tempLinkLine!!)
    }

    internal fun _updateTempLinkLine(screenX: Double, screenY: Double) {
        val line = spaceGraph.tempLinkLine ?: return; val sourceNode = spaceGraph.linkSourceNode ?: return
        spaceGraph.screenToWorld(screenX, screenY, sourceNode.position.z)?.let {
            val positions = line.geometry.attributes["position"].unsafeCast<THREE.BufferAttribute>()
            positions.setXYZ(1, it.x, it.y, it.z); positions.needsUpdate = true
            line.geometry.computeBoundingSphere(); line.computeLineDistances()
        }
    }

    internal fun _removeTempLinkLine() {
        spaceGraph.tempLinkLine?.let { line -> line.geometry.dispose(); line.material.dispose(); spaceGraph.scene.remove(line); spaceGraph.tempLinkLine = null }
    }

    fun _completeLinking(e: PointerEvent) {
        val targetInfo = _getTargetInfo(e); _removeTempLinkLine()
        val sourceNode = spaceGraph.linkSourceNode
        if (targetInfo.node != null && targetInfo.node != sourceNode && sourceNode != null) {
            spaceGraph.addEdge(sourceNode, targetInfo.node)?.let {
                spaceGraph.agentApi?.dispatchGraphEvent("linkCompletedViaUI", jsObject { this.edgeId = it.id; this.sourceNodeId = it.source.id; this.targetNodeId = it.target.id })
            }
        } else {
            spaceGraph.agentApi?.dispatchGraphEvent("linkCancelled", jsObject { this.sourceNodeId = sourceNode?.id; this.reason = "No valid target" })
        }
        cancelLinking()
    }

    fun cancelLinking() {
        val wasLinking = spaceGraph.isLinking
        val sourceId = spaceGraph.linkSourceNode?.id
        _removeTempLinkLine(); spaceGraph.isLinking = false; spaceGraph.linkSourceNode = null
        container.style.cursor = ""
        clearLinkingTargets()
        if (wasLinking) {
            hideStatus()
            spaceGraph.agentApi?.dispatchGraphEvent("linkCancelled", jsObject { this.sourceNodeId = sourceId; this.reason = "User cancelled" })
        }
    }

    internal fun _onKeyDown(e: KeyboardEvent) {
        val activeEl = document.activeElement
        val isEditing = activeEl != null && (activeEl.tagName == "INPUT" || activeEl.tagName == "TEXTAREA" || activeEl.tagName == "SELECT" || (activeEl as? HTMLElement)?.isContentEditable == true)
        if (isEditing && e.key != "Escape") return

        if (readOnly) {
            // Read-only: the ONLY key the graph takes is Esc = view history back. Space, Enter, +/-, Delete and
            // Backspace stay with the page (scrolling, form fields, links).
            if (e.key == "Escape" && !isEditing) {
                val handled = spaceGraph.back() || (spaceGraph.selectedNode != null || spaceGraph.selectedEdge != null).also {
                    if (it) { spaceGraph.selectedNode = null; spaceGraph.selectedEdge = null }
                }
                if (handled) e.preventDefault()
            }
            return
        }

        val selectedNode = spaceGraph.selectedNode; val selectedEdge = spaceGraph.selectedEdge
        var handled = false
        when (e.key) {
            "Delete", "Backspace" -> {
                selectedNode?.let { _showConfirm("Delete node \"${it.id.take(10)}...\"?") { spaceGraph.removeNode(it.id) }; handled = true }
                selectedEdge?.let { _showConfirm("Delete edge \"${it.id.take(10)}...\"?") { spaceGraph.removeEdge(it.id) }; handled = true }
            }
            "Escape" -> {
                if (spaceGraph.isLinking) { cancelLinking(); handled = true }
                else if (contextMenuElement.style.display == "block") { _hideContextMenu(); handled = true }
                else if (confirmDialogElement.style.display == "block") { _hideConfirm(); handled = true }
                else if (edgeMenuObject?.visible == true) { spaceGraph.selectedEdge = null; handled = true }
                else if (spaceGraph.back()) { handled = true }
                else if (selectedNode != null || selectedEdge != null) { spaceGraph.selectedNode = null; spaceGraph.selectedEdge = null; handled = true }
            }
            "Enter" -> if (selectedNode is NoteNode) { (selectedNode.htmlElement.querySelector(".node-content") as? HTMLElement)?.focus(); handled = true }
            "+", "=" -> if (selectedNode is HtmlNodeElement) { if (e.ctrlKey || e.metaKey) selectedNode.adjustNodeSize(1.2) else selectedNode.adjustContentScale(1.15); handled = true }
            "-", "_" -> if (selectedNode is HtmlNodeElement) { if (e.ctrlKey || e.metaKey) selectedNode.adjustNodeSize(0.8) else selectedNode.adjustContentScale(1 / 1.15); handled = true }
            " " -> when {
                selectedNode != null -> { spaceGraph.focusOnNode(selectedNode, 0.5, true); handled = true }
                selectedEdge != null -> {
                    val midPoint = selectedEdge.source.position.toThreeVector().lerp(selectedEdge.target.position.toThreeVector(), 0.5)
                    val dist = selectedEdge.source.position.distanceTo(selectedEdge.target.position)
                    spaceGraph.cameraController.pushState()
                    spaceGraph.cameraController.moveTo(midPoint.x, midPoint.y, midPoint.z + dist * 0.6 + 100, 0.5, Vector3D.fromThreeVector(midPoint))
                    handled = true
                }
                else -> { spaceGraph.centerView(null, 0.7); handled = true }
            }
        }
        if (handled) e.preventDefault()
    }

    internal fun _onWheel(e: WheelEvent) {
        val targetInfo = _getTargetInfo(e)
        if (targetInfo.element?.closest(".node-controls, .edge-menu-frame") != null || targetInfo.contentEditable != null) return
        // a scrollable region inside a card keeps its own wheel
        val scroller = targetInfo.element?.closest("[data-sg-scroll]") as? HTMLElement
        if (scroller != null && scroller.scrollHeight > scroller.clientHeight) return
        e.preventDefault()
        if (!readOnly && (e.ctrlKey || e.metaKey)) {
            (targetInfo.node as? HtmlNodeElement)?.adjustContentScale(if (e.deltaY < 0) 1.1 else (1 / 1.1))
        } else {
            spaceGraph.cameraController.zoom(e)
        }
    }

    fun showEdgeMenu(edge: Edge) {
        if (readOnly) return
        if (edgeMenuObject?.userData?.edgeId == edge.id && edgeMenuObject?.visible == true) return
        hideEdgeMenu()

        val menuElement = (document.createElement("div") as HTMLDivElement).apply {
            className = "edge-menu-frame"; dataset["edgeId"] = edge.id
            style.apply { padding = "5px"; background = "rgba(50,50,50,0.8)"; display = "flex"; asDynamic().gap = "5px"; asDynamic().pointerEvents = "auto" }
        }
        fun button(title: String, action: String, glyph: String, danger: Boolean = false) {
            val b = document.createElement("button") as HTMLElement
            b.setAttribute("type", "button")
            b.title = title
            b.setAttribute("data-action", action)
            b.textContent = glyph
            if (danger) { b.classList.add("delete"); b.style.color = "red" }
            menuElement.appendChild(b)
        }
        button("Color (NYI)", "color", "C")
        button("Thickness (NYI)", "thickness", "T")
        button("Style (NYI)", "style", "S")
        button("Constraint (NYI)", "constraint", "K")
        button("Delete Edge", "delete", "×", danger = true)
        menuElement.addEventListener("click", { event ->
            ((event.target as? HTMLElement)?.closest("button") as? HTMLElement)?.getAttribute("data-action")?.let { action ->
                event.stopPropagation()
                when (action) {
                    "delete" -> _showConfirm("Delete edge \"${edge.id.take(10)}...\"?") { spaceGraph.removeEdge(edge.id) }
                    else -> showStatus("Edge style editing for '$action' not implemented yet.", 2000)
                }
            }
        })
        menuElement.addEventListener("pointerdown", { it.stopPropagation() })
        menuElement.addEventListener("wheel", { it.stopPropagation() })

        edgeMenuObject = CSS3DObject(menuElement).apply { userData = jsObject { this.edgeId = edge.id } }
        spaceGraph.cssScene.add(edgeMenuObject!!)
        updateEdgeMenuPosition()
    }

    fun hideEdgeMenu() {
        edgeMenuObject?.let { menu -> menu.element.remove(); menu.parent?.remove(menu); edgeMenuObject = null }
    }

    fun updateEdgeMenuPosition() {
        val edge = spaceGraph.selectedEdge ?: return; val menu = edgeMenuObject ?: return
        menu.position.copy(edge.source.position.toThreeVector().clone().lerp(edge.target.position.toThreeVector(), 0.5))
        menu.quaternion.copy(spaceGraph._camera.quaternion)
    }

    /** Toast at the bottom of the page. In read-only mode there is no toast element in the document: this is a no-op. */
    fun showStatus(message: String, duration: Int = 3000) {
        if (readOnly) return
        statusTimer?.let { window.clearTimeout(it) }
        statusIndicatorElement.textContent = message; statusIndicatorElement.style.opacity = "1"; statusIndicatorElement.style.display = "block"
        if (duration > 0) statusTimer = window.setTimeout({ hideStatus() }, duration)
    }

    fun hideStatus() {
        statusIndicatorElement.style.opacity = "0"
        window.setTimeout({ if (statusIndicatorElement.style.opacity == "0") statusIndicatorElement.style.display = "none" }, 500)
    }

    fun dispose() {
        container.removeEventListener("pointerdown", onPointerDown)
        window.removeEventListener("pointermove", onPointerMove)
        window.removeEventListener("pointerup", onPointerUp)
        window.removeEventListener("pointercancel", onPointerCancel)
        window.removeEventListener("keydown", onKeyDown)
        container.removeEventListener("wheel", onWheel)
        container.removeEventListener("contextmenu", onContextMenu)
        document.removeEventListener("click", onDocumentClick, true)
        contextMenuElement.removeEventListener("click", onContextMenuClick)
        confirmYesEl?.removeEventListener("click", onConfirmYes)
        confirmNoEl?.removeEventListener("click", onConfirmNo)
        statusTimer?.let { window.clearTimeout(it) }

        hideEdgeMenu()
        // only remove chrome this manager created; elements handed in through UiElements belong to the host page
        if (uiElements?.contextMenuEl == null) contextMenuElement.remove()
        if (uiElements?.confirmDialogEl == null) confirmDialogElement.remove()
        if (uiElements?.statusIndicatorEl == null) statusIndicatorElement.remove()
        container.style.cursor = ""
        container.classList.remove("panning")

        draggedNode = null; resizedNode = null; hoveredEdge = null; confirmCallback = null; pressedNode = null
        touches.clear(); claimed.clear()
    }
}

internal fun CSSStyleDeclaration.apply(block: CSSStyleDeclaration.() -> Unit): Unit = block()

internal fun clearLinkingTargets() {
    val list = document.querySelectorAll(".node-html.linking-target")
    for (i in 0 until list.length) (list.item(i) as? HTMLElement)?.classList?.remove("linking-target")
}
