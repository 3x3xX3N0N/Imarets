package bar.verdantbloom.bloom.canvas2d

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * OPT-IN pointer / wheel binding for an [OrbitCamera]. bloom-api says renderers add no navigation
 * listeners, and [Canvas2dRenderer] does not: nothing here runs unless the host constructs this class
 * (or calls `Canvas2dRenderer.enableOwnCamera`). A site that already has its own input layer can ignore it
 * and drive `renderer.view` itself.
 *
 * Gestures: one pointer drags = rotate; shift / middle / right drag = pan; wheel = zoom at the cursor;
 * two pointers = pinch zoom + pan + twist. A press that barely moves is a TAP and goes to [onTap]
 * with CSS px relative to [element] and the slop to hand to `pick` (12 mouse / pen, 24 touch).
 *
 * While attached the element gets `touch-action: none` (set through the CSSOM, restored on [detach]),
 * otherwise the browser would claim the pinch for page zoom at 375 px.
 */
class Zui2dInput(
    private val element: HTMLElement,
    private val camera: OrbitCamera,
    /** A tap. Return value is ignored. */
    var onTap: ((x: Double, y: Double, slopPx: Double) -> Unit)? = null,
    /** Pointer moved with no button down: hover feedback (x, y in CSS px relative to the element). */
    var onHover: ((x: Double, y: Double) -> Unit)? = null,
    /** Any gesture activity, as raw speed in CSS px / s. Handy for `world.perturb(POINTER, ...)`. */
    var onActivity: ((speedPxPerSecond: Double) -> Unit)? = null,
) {
    private var attached = false
    private var previousTouchAction: String = ""

    // up to two tracked pointers, fixed slots
    private val ids = IntArray(2) { NO_POINTER }
    private val xs = DoubleArray(2)
    private val ys = DoubleArray(2)
    private var count = 0

    private var downX = 0.0
    private var downY = 0.0
    private var downTime = 0.0
    private var lastMoveTime = 0.0
    private var travelled = 0.0
    private var wasMulti = false
    private var panMode = false
    private var touchLike = false

    // cached so x / y are relative to the element without a layout read per move
    private var originX = 0.0
    private var originY = 0.0

    private val onDown: (Event) -> Unit = { e -> pointerDown(e) }
    private val onMove: (Event) -> Unit = { e -> pointerMove(e) }
    private val onUp: (Event) -> Unit = { e -> pointerUp(e) }
    private val onWheel: (Event) -> Unit = { e -> wheel(e) }
    private val onContextMenu: (Event) -> Unit = { e -> e.preventDefault() }

    fun attach() {
        if (attached) return
        attached = true
        val style = element.style.asDynamic()
        previousTouchAction = (style.touchAction as? String) ?: ""
        style.touchAction = "none"
        element.addEventListener("pointerdown", onDown)
        element.addEventListener("pointermove", onMove)
        element.addEventListener("pointerup", onUp)
        element.addEventListener("pointercancel", onUp)
        element.addEventListener("lostpointercapture", onUp)
        element.addEventListener("contextmenu", onContextMenu)
        val options: dynamic = js("({})")
        options.passive = false
        element.addEventListener("wheel", onWheel, options)
    }

    fun detach() {
        if (!attached) return
        attached = false
        element.style.asDynamic().touchAction = previousTouchAction
        element.removeEventListener("pointerdown", onDown)
        element.removeEventListener("pointermove", onMove)
        element.removeEventListener("pointerup", onUp)
        element.removeEventListener("pointercancel", onUp)
        element.removeEventListener("lostpointercapture", onUp)
        element.removeEventListener("contextmenu", onContextMenu)
        element.removeEventListener("wheel", onWheel)
        ids[0] = NO_POINTER
        ids[1] = NO_POINTER
        count = 0
        camera.release()
    }

    private fun refreshOrigin() {
        val rect = element.getBoundingClientRect()
        originX = rect.left
        originY = rect.top
    }

    private fun slotOf(id: Int): Int = if (ids[0] == id) 0 else if (ids[1] == id) 1 else -1

    private fun pointerDown(event: Event) {
        val e = event.asDynamic()
        if (count >= 2) return
        refreshOrigin()
        val id = (e.pointerId as? Int) ?: 0
        if (slotOf(id) >= 0) return
        val slot = if (ids[0] == NO_POINTER) 0 else 1
        ids[slot] = id
        xs[slot] = (e.clientX as Double) - originX
        ys[slot] = (e.clientY as Double) - originY
        count++
        try {
            element.asDynamic().setPointerCapture(id)
        } catch (_: Throwable) {
            // synthetic events and some old browsers refuse capture; dragging still works inside the element
        }
        val now = timeOf(e)
        lastMoveTime = now
        if (count == 1) {
            downX = xs[slot]
            downY = ys[slot]
            downTime = now
            travelled = 0.0
            wasMulti = false
            touchLike = e.pointerType == "touch"
            val button = (e.button as? Int) ?: 0
            panMode = e.shiftKey == true || button == 1 || button == 2
            camera.grab()
        } else {
            wasMulti = true
        }
    }

    private fun pointerMove(event: Event) {
        val e = event.asDynamic()
        val x = (e.clientX as Double) - originX
        val y = (e.clientY as Double) - originY
        val slot = slotOf((e.pointerId as? Int) ?: 0)
        if (slot < 0) {
            if (count == 0) {
                refreshOrigin()
                onHover?.invoke((e.clientX as Double) - originX, (e.clientY as Double) - originY)
            }
            return
        }
        val now = timeOf(e)
        var dt = (now - lastMoveTime) / 1000.0
        if (dt < 0.001) dt = 0.001
        lastMoveTime = now
        val dx = x - xs[slot]
        val dy = y - ys[slot]
        if (count == 1) {
            xs[slot] = x
            ys[slot] = y
            travelled += sqrt(dx * dx + dy * dy)
            // below the tap threshold nothing moves, so a tap never nudges the bloom
            if (travelled >= tapSlop()) {
                if (panMode) camera.panBy(dx, dy, dt) else camera.rotateBy(dx, dy, dt)
            }
        } else {
            val other = 1 - slot
            val prevSpan = span(xs[slot], ys[slot], xs[other], ys[other])
            val prevCx = (xs[slot] + xs[other]) * 0.5
            val prevCy = (ys[slot] + ys[other]) * 0.5
            val prevAngle = atan2(ys[1] - ys[0], xs[1] - xs[0])
            xs[slot] = x
            ys[slot] = y
            val nowSpan = span(xs[slot], ys[slot], xs[other], ys[other])
            val cx = (xs[slot] + xs[other]) * 0.5
            val cy = (ys[slot] + ys[other]) * 0.5
            var twist = atan2(ys[1] - ys[0], xs[1] - xs[0]) - prevAngle
            if (twist > kotlin.math.PI) twist -= 2 * kotlin.math.PI else if (twist < -kotlin.math.PI) twist += 2 * kotlin.math.PI
            camera.pinch(prevSpan, nowSpan, prevCx, prevCy, cx, cy, twist)
        }
        onActivity?.invoke(sqrt(dx * dx + dy * dy) / dt)
        event.preventDefault()
    }

    private fun pointerUp(event: Event) {
        val e = event.asDynamic()
        val slot = slotOf((e.pointerId as? Int) ?: 0)
        if (slot < 0) return
        ids[slot] = NO_POINTER
        count--
        if (count == 1 && slot == 0) {
            // keep the survivor in slot 0 so twist angles stay consistent
            ids[0] = ids[1]; xs[0] = xs[1]; ys[0] = ys[1]
            ids[1] = NO_POINTER
        }
        if (count > 0) {
            lastMoveTime = timeOf(e)
            return
        }
        count = 0
        val now = timeOf(e)
        // a finger that rested before lifting must not fling the bloom
        if (now - lastMoveTime > REST_MS) camera.grab()
        camera.release()
        val isTap = !wasMulti && travelled < tapSlop() && now - downTime < TAP_MS && event.type == "pointerup"
        if (isTap) onTap?.invoke(downX, downY, if (touchLike) TOUCH_PICK_SLOP else MOUSE_PICK_SLOP)
    }

    private fun wheel(event: Event) {
        val e = event.asDynamic()
        event.preventDefault()
        refreshOrigin()
        val mode = (e.deltaMode as? Int) ?: 0
        val deltaY = (e.deltaY as? Double) ?: 0.0
        camera.wheel(deltaY, mode, (e.clientX as Double) - originX, (e.clientY as Double) - originY)
        onActivity?.invoke(kotlin.math.abs(deltaY) * 4.0)
    }

    private fun tapSlop(): Double = if (touchLike) TOUCH_TAP_SLOP else MOUSE_TAP_SLOP

    private fun span(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun timeOf(e: dynamic): Double {
        val t = e.timeStamp
        return if (t != null && jsTypeOf(t) == "number") (t as Double) else kotlin.js.Date.now()
    }

    companion object {
        private const val NO_POINTER = Int.MIN_VALUE
        const val MOUSE_TAP_SLOP: Double = 5.0
        const val TOUCH_TAP_SLOP: Double = 12.0
        const val TAP_MS: Double = 600.0
        const val REST_MS: Double = 90.0
        const val MOUSE_PICK_SLOP: Double = 12.0
        const val TOUCH_PICK_SLOP: Double = 24.0
    }
}
