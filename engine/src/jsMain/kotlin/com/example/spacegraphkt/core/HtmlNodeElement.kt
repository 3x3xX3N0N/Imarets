package com.example.spacegraphkt.core

import bar.verdantbloom.three.CSS3D.CSS3DObject
import bar.verdantbloom.three.jsObject
import com.example.spacegraphkt.data.NodeData
import com.example.spacegraphkt.data.Size
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.external.generateId
import com.example.spacegraphkt.zui.LodLevel
import com.example.spacegraphkt.zui.LodThresholds
import com.example.spacegraphkt.zui.ViewMath
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A [BaseNode] shown as an HTML element in 3D space through three's CSS3DRenderer.
 *
 * Two ways to get one:
 *  - the constructor BUILDS an element (`.node-html` > `.node-inner-wrapper` > `.node-content` + editor chrome);
 *  - [adopt] (or passing [existingElement]) LIFTS an element that is already in the document into the graph -
 *    progressive enhancement: the no-JS page keeps its cards, the ZUI moves the same DOM nodes into space.
 *    [dispose] / [release] puts an adopted element back exactly where and how it was.
 *
 * DOM code is CSP-safe: elements are built with createElement / textContent, styles through CSSOM properties.
 * `data.content` is TEXT, never parsed as HTML.
 *
 * Semantic zoom: every frame the node derives a [LodLevel] from the camera distance and mirrors it into the
 * element's `data-lod` attribute ("far" | "mid" | "near") - only written when it changes.
 */
open class HtmlNodeElement(
    id: String,
    position: Vector3D,
    data: NodeData,
    var size: Size,
    var billboard: Boolean,
    existingElement: HTMLElement? = null,
) : BaseNode(id, position, data, data.mass) {

    /** State needed to undo an adoption. */
    private class Adoption(
        val parent: Node?,
        val nextSibling: Node?,
        val cssText: String,
        val addedClasses: List<String>,
        val previousNodeId: String?,
        val previousLod: String?,
    )

    private var adoption: Adoption? = null

    /** true when the element came from the document ([adopt]) instead of being built here. */
    val isAdopted: Boolean get() = adoption != null

    private var contentEl: HTMLElement? = null
    private var controlsEl: HTMLElement? = null
    private var resizeHandleEl: HTMLElement? = null

    val htmlElement: HTMLElement = if (existingElement != null) adoptElement(existingElement) else _createHtmlElement()
    val css3dObject: CSS3DObject = CSS3DObject(htmlElement)

    /** Current semantic zoom level; null until the first frame inside a graph. */
    var lod: LodLevel? = null
        private set

    /** Per-node thresholds; null = the graph's (`SpaceGraph.lodThresholds`). A big card can turn NEAR from further away. */
    var lodThresholds: LodThresholds? = null

    /** Camera distance measured on the last frame (world units). */
    var cameraDistance: Double = Double.NaN
        private set

    var readOnly: Boolean = false
        private set

    init {
        this.threeJsObject = css3dObject
        css3dObject.position.set(position.x, position.y, position.z)
        css3dObject.userData = jsObject { this.nodeId = this@HtmlNodeElement.id; this.type = "html-node" }

        this.size.width = data.width ?: this.size.width
        this.size.height = data.height ?: this.size.height
        this.billboard = data.billboard ?: this.billboard

        data.backgroundColor?.let { setBackgroundColor(it) }
        if (!isAdopted) data.contentScale?.let { setContentScale(it) }

        htmlElement.style.width = "${this.size.width}px"
        // an adopted card keeps its natural height unless one was given: its content changes with the LOD
        if (!isAdopted || data.height != null) htmlElement.style.height = "${this.size.height}px"
        // the CSS3D layer itself is pointer-events: none so the GPU canvas below stays reachable
        htmlElement.style.asDynamic().pointerEvents = "auto"
    }

    private fun el(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private fun controlButton(classes: String, title: String, glyph: String): HTMLElement =
        el("button", "node-quick-button $classes").also {
            it.setAttribute("type", "button")
            it.title = title
            it.textContent = glyph
        }

    internal fun _createHtmlElement(): HTMLElement {
        val root = el("div", "node-html")
        if (data.type.isNotBlank()) root.classList.add("type-${data.type.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}")
        if (data.type == "note") root.classList.add("note-node")
        root.id = "node-html-$id"
        root.setAttribute("data-node-id", id)

        val wrapper = el("div", "node-inner-wrapper")
        val content = el("div", "node-content")
        content.setAttribute("spellcheck", "false")
        content.style.whiteSpace = "pre-wrap"
        content.textContent = data.content ?: data.label ?: id
        content.style.transform = "scale(${data.contentScale ?: 1.0})"
        wrapper.appendChild(content)

        val controls = el("div", "node-controls")
        controls.appendChild(controlButton("node-content-zoom-in", "Zoom In Content (+)", "+"))
        controls.appendChild(controlButton("node-content-zoom-out", "Zoom Out Content (-)", "-"))
        controls.appendChild(controlButton("node-grow", "Grow Node (Ctrl++)", "➚"))
        controls.appendChild(controlButton("node-shrink", "Shrink Node (Ctrl+-)", "➘"))
        controls.appendChild(controlButton("delete-button node-delete", "Delete Node (Del)", "×"))
        wrapper.appendChild(controls)
        root.appendChild(wrapper)

        val handle = el("div", "resize-handle")
        handle.title = "Resize Node"
        root.appendChild(handle)

        contentEl = content
        controlsEl = controls
        resizeHandleEl = handle
        _initContentEditable(content)
        return root
    }

    private fun adoptElement(element: HTMLElement): HTMLElement {
        val added = ArrayList<String>()
        for (c in listOf("node-html", "node-adopted")) {
            if (!element.classList.contains(c)) {
                element.classList.add(c)
                added.add(c)
            }
        }
        adoption = Adoption(
            parent = element.parentNode,
            nextSibling = element.nextSibling,
            cssText = element.style.cssText,
            addedClasses = added,
            previousNodeId = element.getAttribute("data-node-id"),
            previousLod = element.getAttribute(LodLevel.DATA_ATTRIBUTE),
        )
        element.setAttribute("data-node-id", id)
        contentEl = element.querySelector(".node-content") as? HTMLElement
        return element
    }

    private val isEditableNow: Boolean get() = data.editable == true && !readOnly && !isAdopted

    internal fun _initContentEditable(content: HTMLElement) {
        var debounceTimer: Int? = null
        content.addEventListener("input", {
            if (isEditableNow) {
                debounceTimer?.let { window.clearTimeout(it) }
                debounceTimer = window.setTimeout({
                    val text = content.textContent ?: ""
                    data.content = text
                    data.label = text
                }, 300)
            }
        })
        // while editing, the graph must not start a node drag / zoom from inside the text
        content.addEventListener("pointerdown", { e -> if (isEditableNow) e.stopPropagation() })
        content.addEventListener("wheel", { e ->
            if (isEditableNow && (content.scrollHeight > content.clientHeight || content.scrollWidth > content.clientWidth)) e.stopPropagation()
        }, jsObject { this.passive = true })
        applyEditable()
    }

    private fun applyEditable() {
        val content = contentEl ?: return
        if (isAdopted) return
        if (isEditableNow) content.contentEditable = "true" else content.removeAttribute("contenteditable")
    }

    /**
     * Read-only presentation: removes the node control buttons and the resize handle from the DOM and turns
     * contenteditable off. [SpaceGraph.addNode] applies the graph's `options.readOnly` automatically.
     */
    fun setReadOnly(flag: Boolean) {
        readOnly = flag
        htmlElement.classList.toggle("read-only", flag)
        val controls = controlsEl
        val handle = resizeHandleEl
        if (flag) {
            controls?.remove()
            handle?.remove()
        } else if (!isAdopted) {
            if (controls != null && controls.parentNode == null) htmlElement.querySelector(".node-inner-wrapper")?.appendChild(controls)
            if (handle != null && handle.parentNode == null) htmlElement.appendChild(handle)
        }
        applyEditable()
    }

    open fun setSize(width: Double, height: Double, scaleContent: Boolean) {
        val oldWidth = size.width
        val oldHeight = size.height
        size.width = max(80.0, width)
        size.height = max(40.0, height)
        htmlElement.style.width = "${size.width}px"
        htmlElement.style.height = "${size.height}px"
        if (scaleContent && oldWidth > 0 && oldHeight > 0) {
            setContentScale((data.contentScale ?: 1.0) * sqrt((size.width * size.height) / (oldWidth * oldHeight)))
        }
        data.width = size.width
        data.height = size.height
        spaceGraphInstance?.layoutEngine?.kick()
    }

    /** Re-reads the rendered size of the element (adopted cards whose height follows their content / LOD). */
    fun measure() {
        val w = htmlElement.offsetWidth.toDouble()
        val h = htmlElement.offsetHeight.toDouble()
        if (w > 0) size.width = w
        if (h > 0) size.height = h
    }

    open fun setContentScale(scale: Double) {
        val newScale = scale.coerceIn(0.3, 3.0)
        data.contentScale = newScale
        contentEl?.style?.transform = "scale($newScale)"
    }

    /** @param color any CSS colour; exposed to the stylesheet as the custom property `--node-bg`. */
    open fun setBackgroundColor(color: String) {
        data.backgroundColor = color
        htmlElement.style.setProperty("--node-bg", color)
    }

    fun adjustContentScale(deltaFactor: Double) = setContentScale((data.contentScale ?: 1.0) * deltaFactor)

    fun adjustNodeSize(factor: Double) = setSize(size.width * factor, size.height * factor, false)

    override fun update() {
        css3dObject.position.set(position.x, position.y, position.z)
        val graph = spaceGraphInstance ?: return
        val camera = graph._camera
        if (billboard) css3dObject.quaternion.copy(camera.quaternion)
        val cp = camera.position
        cameraDistance = ViewMath.distance(cp.x, cp.y, cp.z, position.x, position.y, position.z)
        applyLod((lodThresholds ?: graph.lodThresholds).levelFor(cameraDistance, lod))
    }

    /** Forces a level (tests, or a host that drives semantic zoom from something else than camera distance). */
    fun applyLod(level: LodLevel) {
        if (level == lod) return
        val previous = lod
        lod = level
        htmlElement.setAttribute(LodLevel.DATA_ATTRIBUTE, level.attr)
        spaceGraphInstance?.notifyLodChanged(this, level, previous)
    }

    /**
     * Undoes an adoption: the element goes back to its original parent / position with its original inline
     * style, classes and attributes. No-op for built elements. Called by [dispose].
     */
    fun release() {
        val a = adoption ?: return
        adoption = null
        val e = htmlElement
        e.remove()
        e.style.cssText = a.cssText
        for (c in a.addedClasses) e.classList.remove(c)
        e.classList.remove("selected")
        e.classList.remove("dragging")
        e.classList.remove("read-only")
        if (a.previousNodeId != null) e.setAttribute("data-node-id", a.previousNodeId) else e.removeAttribute("data-node-id")
        if (a.previousLod != null) e.setAttribute(LodLevel.DATA_ATTRIBUTE, a.previousLod) else e.removeAttribute(LodLevel.DATA_ATTRIBUTE)
        val parent = a.parent
        if (parent != null) {
            val before = a.nextSibling?.takeIf { it.parentNode === parent }
            parent.insertBefore(e, before)
        }
    }

    override fun dispose() {
        if (isAdopted) release() else htmlElement.remove()
        super.dispose()
    }

    override fun getBoundingSphereRadius(): Double =
        sqrt(size.width * size.width + size.height * size.height) / 2.0 * (data.contentScale ?: 1.0)

    override fun setSelectedStyle(selected: Boolean) {
        htmlElement.classList.toggle("selected", selected)
        if (selected) htmlElement.setAttribute("aria-current", "true") else htmlElement.removeAttribute("aria-current")
    }

    open fun startResize() {
        htmlElement.classList.add("resizing")
        spaceGraphInstance?.layoutEngine?.fixNode(this)
    }

    open fun resize(newWidth: Double, newHeight: Double) = setSize(newWidth, newHeight, false)

    open fun endResize() {
        htmlElement.classList.remove("resizing")
        spaceGraphInstance?.layoutEngine?.releaseNode(this)
    }

    companion object {
        /**
         * Adopts an element that already exists in the document as a graph node (it is NOT cloned: CSS3DRenderer
         * moves this very element into its layer on the next frame, and [release] / dispose moves it back).
         *
         * @param element the card; measure happens now, so it should be attached and displayed
         * @param id node id; default: the element's `id` attribute, else generated
         * @param width / height world size = CSS px at scale 1; default: the element's current offset size.
         *   Only the width is pinned on the element (height stays `auto`) unless [height] is given.
         */
        fun adopt(
            element: HTMLElement,
            position: Vector3D,
            id: String = element.id.ifBlank { generateId("adopted") },
            width: Double? = null,
            height: Double? = null,
            billboard: Boolean = true,
            label: String? = null,
        ): HtmlNodeElement {
            val w = width ?: element.offsetWidth.toDouble().takeIf { it > 0.0 } ?: 320.0
            val measuredH = element.offsetHeight.toDouble().takeIf { it > 0.0 } ?: 200.0
            val data = NodeData(
                id = id, label = label ?: id, type = "adopted",
                width = w, height = height, contentScale = 1.0, editable = false, billboard = billboard,
            )
            return HtmlNodeElement(id, position, data, Size(w, height ?: measuredH), billboard, element)
        }
    }
}
