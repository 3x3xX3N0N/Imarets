package com.example.spacegraphkt.core

import org.w3c.dom.pointerevents.PointerEvent

/** Called once per animation frame, after the camera moved and before nodes are updated and the scene is drawn. */
fun interface FrameListener {
    /**
     * @param dtSeconds time since the previous frame, clamped to 0..0.1 (a hidden tab does not produce a jump)
     * @param nowMs `Date.now()` of this frame
     */
    fun onFrame(dtSeconds: Double, nowMs: Double)
}

/** Where in a pointer gesture a [PickHook] is being asked. */
enum class PickPhase {
    /** Pointer went down. Return true to CLAIM the whole gesture: the engine then ignores this pointer (no node drag, no pan) until it is released, and keeps forwarding [MOVE] / [UP] / [CANCEL] to the claiming hook only. */
    DOWN,

    /** Movement of a pointer this hook claimed at [DOWN]. Return value ignored. */
    MOVE,

    /** Release of a pointer this hook claimed at [DOWN]. Return value ignored. */
    UP,

    /** The claimed pointer was cancelled by the browser (touch became a scroll, etc.). Return value ignored. */
    CANCEL,

    /** Pointer moves with no button down. Return true to say "I have something under the cursor": the engine skips its own edge-hover highlight. */
    HOVER,

    /**
     * An unclaimed pointer was pressed and released without travelling (a click / tap), asked BEFORE the engine's
     * own node and edge picking. Return true to take it: the engine then neither selects, focuses nor deselects.
     * This is the hook bloom-three uses: `renderer.pick(x, y, slop) != NONE` -> fly to that ring's card.
     */
    TAP,
}

/**
 * A pointer event as seen by a [PickHook]. [x] / [y] are CSS px relative to the graph container's top-left
 * corner - the same frame as bloom-api `BloomRenderer.pick(screenX, screenY, slopPx)`.
 */
class PickEvent(
    val phase: PickPhase,
    val x: Double,
    val y: Double,
    /** "mouse", "touch" or "pen". */
    val pointerType: String,
    /** Suggested pick slop in px: 12 for mouse, 24 for touch (CONTRACTS section 5). */
    val slopPx: Double,
    val pointerId: Int,
    /**
     * The engine node whose DOM element (or mesh) is under the pointer, found WITHOUT acting on it; null over
     * empty space. A ring picker normally declines when this is an HTML card, so a card in front of a ring wins.
     */
    val nodeUnderPointer: BaseNode?,
    val original: PointerEvent,
)

/**
 * Lets an external picker (bloom-three's ring picking) claim pointer events before the engine's node picking.
 * Hooks are asked in registration order; the first one returning true wins.
 */
fun interface PickHook {
    fun onPick(event: PickEvent): Boolean
}
