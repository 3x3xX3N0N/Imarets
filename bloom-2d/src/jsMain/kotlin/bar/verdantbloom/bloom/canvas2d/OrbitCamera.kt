package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * The small camera of the 2D rung: pure maths over a [BloomView], no DOM. It produces exactly the view
 * numbers the three.js rungs consume, so the site may use it for every rung or not at all.
 *
 * Gestures (all in CSS px relative to the viewport given to [setViewport]):
 * - [rotateBy]   trackball: drag right turns the bloom right, drag down tips its top towards you
 * - [panBy]      moves the target in the screen plane, content follows the finger 1:1 at the target depth
 * - [zoomAt]     dolly that keeps the bloom point under the cursor / pinch centre where it is
 * - [pinch]      two-finger zoom + pan + twist (roll)
 * - [release]    ends a drag; rotation and pan keep going and decay (inertia)
 * - [flyTo]      tween target / distance / orbit to a (moving) anchor, then keep following it
 *
 * Call [update] once per frame; it returns true when [view] changed. [view] allocates a new BloomView only
 * when something changed since the last read.
 */
class OrbitCamera(initial: BloomView = BloomView()) {
    private var orbit: Quat = QuatMath.normalize(initial.orbit)
    private var distance: Double = initial.distance
    private var target: Vec3 = initial.target
    private var fovDegrees: Double = initial.fovDegrees

    private var cached: BloomView = BloomView(orbit, distance, target, fovDegrees)
    private var dirty = false

    var viewportWidth: Double = 800.0
        private set
    var viewportHeight: Double = 600.0
        private set

    var minDistance: Double = 0.6
    var maxDistance: Double = 40.0

    /** Radians of rotation for a drag across the full viewport height. */
    var rotateSpeed: Double = PI

    /** Seconds for inertia to fall to 1/e. */
    var inertiaSeconds: Double = 0.32

    /** Distance [flyTo] ends at when the caller does not say. Near enough for a full card. */
    var focusDistance: Double = 2.4

    /** Distance and target of [flyHome]. */
    var homeDistance: Double = initial.distance
    var homeTarget: Vec3 = initial.target

    // inertia, CSS px per second
    private var velRotX = 0.0
    private var velRotY = 0.0
    private var velPanX = 0.0
    private var velPanY = 0.0
    private var dragging = false
    private var coasting = false

    // tween
    private var flying = false
    private var flyElapsed = 0.0
    private var flyDuration = 0.0
    private var flyFromOrbit: Quat = orbit
    private var flyFromTarget: Vec3 = target
    private var flyFromLogDistance = 0.0
    private var flyToLogDistance = 0.0
    private var flyFaceAnchor = true
    private var anchorSource: (() -> Vec3)? = null

    /** True while a [flyTo] tween is running. */
    val isFlying: Boolean get() = flying

    /** True after a [flyTo] arrived and the target keeps tracking the moving anchor. Panning ends it. */
    var isFollowing: Boolean = false
        private set

    val view: BloomView
        get() {
            if (dirty) {
                cached = BloomView(orbit, distance, target, fovDegrees)
                dirty = false
            }
            return cached
        }

    fun setViewport(cssWidth: Double, cssHeight: Double) {
        if (cssWidth > 0.0) viewportWidth = cssWidth
        if (cssHeight > 0.0) viewportHeight = cssHeight
    }

    /** CSS px per bloom unit on the plane through the target (same formula as [BloomView]'s contract). */
    val pxPerUnit: Double get() = viewportHeight / (2.0 * distance * tan(fovDegrees * PI / 360.0))

    /** Adopt [v] at once: cancels any tween, follow and inertia. */
    fun jumpTo(v: BloomView) {
        orbit = QuatMath.normalize(v.orbit)
        distance = v.distance.coerceIn(minDistance, maxDistance)
        target = v.target
        fovDegrees = v.fovDegrees
        stopMotion()
        isFollowing = false
        anchorSource = null
        dirty = true
    }

    // ---------------------------------------------------------------------------------------- gestures

    /** A pointer went down: stop coasting, keep any follow. */
    fun grab() {
        dragging = true
        coasting = false
        velRotX = 0.0; velRotY = 0.0; velPanX = 0.0; velPanY = 0.0
        if (flying) finishFlight(interrupted = true) // the follow offset finishes the approach without a jump
    }

    /** Rotate by a drag of ([dx], [dy]) CSS px that took [dtSeconds] (0 = do not feed inertia). */
    fun rotateBy(dx: Double, dy: Double, dtSeconds: Double = 0.0) {
        if (dx == 0.0 && dy == 0.0) return
        applyRotation(dx, dy)
        if (dtSeconds > 0.0) {
            val k = velocityBlend(dtSeconds)
            velRotX += (dx / dtSeconds - velRotX) * k
            velRotY += (dy / dtSeconds - velRotY) * k
            velPanX = 0.0; velPanY = 0.0
        }
    }

    /** Pan by a drag of ([dx], [dy]) CSS px. Ends [isFollowing]: the visitor took the wheel. */
    fun panBy(dx: Double, dy: Double, dtSeconds: Double = 0.0) {
        if (dx == 0.0 && dy == 0.0) return
        isFollowing = false
        anchorSource = null
        applyPan(dx, dy)
        if (dtSeconds > 0.0) {
            val k = velocityBlend(dtSeconds)
            velPanX += (dx / dtSeconds - velPanX) * k
            velPanY += (dy / dtSeconds - velPanY) * k
            velRotX = 0.0; velRotY = 0.0
        }
    }

    /** The last pointer went up: coast if the gesture was still moving. */
    fun release() {
        dragging = false
        val rot = velRotX * velRotX + velRotY * velRotY
        val pan = velPanX * velPanX + velPanY * velPanY
        coasting = rot > MIN_COAST_SPEED * MIN_COAST_SPEED || pan > MIN_COAST_SPEED * MIN_COAST_SPEED
        if (!coasting) {
            velRotX = 0.0; velRotY = 0.0; velPanX = 0.0; velPanY = 0.0
        }
    }

    /**
     * Multiply the distance by [factor] (&gt; 1 = zoom out) keeping the bloom point that is under
     * ([cx], [cy]) on the target plane exactly where it is on screen.
     */
    fun zoomAt(factor: Double, cx: Double, cy: Double) {
        if (factor != factor || factor <= 0.0) return
        val before = pxPerUnit
        val next = (distance * factor).coerceIn(minDistance, maxDistance)
        if (next == distance) return
        if (flying) finishFlight(interrupted = true)
        distance = next
        val after = pxPerUnit
        if (!isFollowing) {
            val offX = cx - viewportWidth * 0.5
            val offY = cy - viewportHeight * 0.5
            val k = 1.0 / before - 1.0 / after
            val right = QuatMath.rotate(orbit, UNIT_X)
            val up = QuatMath.rotate(orbit, UNIT_Y)
            target = Vec3(
                target.x + (right.x * offX - up.x * offY) * k,
                target.y + (right.y * offX - up.y * offY) * k,
                target.z + (right.z * offX - up.z * offY) * k,
            )
        }
        dirty = true
    }

    /** Mouse wheel. [deltaMode] as in WheelEvent: 0 pixels, 1 lines, 2 pages. Positive deltaY zooms out. */
    fun wheel(deltaY: Double, deltaMode: Int, cx: Double, cy: Double) {
        var px = when (deltaMode) {
            1 -> deltaY * 16.0
            2 -> deltaY * viewportHeight
            else -> deltaY
        }
        if (px != px) return
        if (px > MAX_WHEEL_PX) px = MAX_WHEEL_PX else if (px < -MAX_WHEEL_PX) px = -MAX_WHEEL_PX
        zoomAt(exp(px * WHEEL_ZOOM_PER_PX), cx, cy)
    }

    /**
     * One step of a two-finger gesture. [prevSpan] / [span]: finger distance before / now;
     * ([prevCx], [prevCy]) / ([cx], [cy]): centroid before / now; [twist]: change of the finger-to-finger
     * screen angle in radians (atan2 in y-down screen coordinates, so positive = clockwise as seen).
     */
    fun pinch(prevSpan: Double, span: Double, prevCx: Double, prevCy: Double, cx: Double, cy: Double, twist: Double = 0.0) {
        if (twist != 0.0 && abs(twist) < 1.0) {
            // content turns clockwise on screen when the camera rolls counter-clockwise about its view axis
            orbit = QuatMath.normalize(QuatMath.mul(orbit, QuatMath.axisAngle(0.0, 0.0, 1.0, twist)))
            dirty = true
        }
        val dx = cx - prevCx
        val dy = cy - prevCy
        // while following, the anchor stays centred: a two-finger drag then only zooms and twists
        if (!isFollowing && (dx != 0.0 || dy != 0.0)) applyPan(dx, dy)
        if (prevSpan > 1.0 && span > 1.0) zoomAt(prevSpan / span, cx, cy)
    }

    // ---------------------------------------------------------------------------------------- tween

    /**
     * Fly to a moving anchor. [anchor] is asked again on every [update] (rings drift with the world), both
     * during the tween and afterwards while [isFollowing].
     * @param endDistance final distance, default [focusDistance]
     * @param seconds tween length; 0 or less jumps (what a reduced-motion host should pass)
     * @param faceAnchor also turn the orbit so the camera sits on the anchor's side of the bloom
     */
    fun flyTo(anchor: () -> Vec3, endDistance: Double = focusDistance, seconds: Double = 0.9, faceAnchor: Boolean = true) {
        startFlight(anchor, endDistance, seconds, faceAnchor, follow = true)
    }

    /** Fly back to the overview ([homeTarget], [homeDistance]); the orbit is left as it is. */
    fun flyHome(seconds: Double = 0.9) {
        val home = homeTarget
        startFlight({ home }, homeDistance, seconds, faceAnchor = false, follow = false)
    }

    private fun startFlight(anchor: () -> Vec3, endDistance: Double, seconds: Double, faceAnchor: Boolean, follow: Boolean) {
        stopMotion()
        followAfterFlight = follow
        anchorSource = anchor
        flyFromOrbit = orbit
        flyFromTarget = target
        flyFromLogDistance = ln(distance)
        flyToLogDistance = ln(endDistance.coerceIn(minDistance, maxDistance))
        flyFaceAnchor = faceAnchor
        flyElapsed = 0.0
        flyDuration = seconds
        isFollowing = false
        flying = true
        if (seconds <= 0.0) {
            applyFlight(1.0)
            finishFlight(interrupted = false)
        }
    }

    private var followAfterFlight = true
    private var followOffX = 0.0
    private var followOffY = 0.0
    private var followOffZ = 0.0

    /** Advance inertia, tween and follow by [dtSeconds]. @return true when [view] changed. */
    fun update(dtSeconds: Double): Boolean {
        val dt = if (dtSeconds != dtSeconds) 0.0 else dtSeconds.coerceIn(0.0, 0.1)
        if (flying) {
            flyElapsed += dt
            val t = if (flyDuration <= 0.0) 1.0 else (flyElapsed / flyDuration).coerceIn(0.0, 1.0)
            applyFlight(ease(t))
            if (t >= 1.0) finishFlight(interrupted = false)
        } else if (isFollowing) {
            val source = anchorSource
            if (source != null) {
                // target = anchor + offset. The offset is zero after a completed flight (exact tracking, no lag);
                // after an INTERRUPTED flight it is what was left to travel, and it decays so there is no jump.
                val a = source()
                if (followOffX != 0.0 || followOffY != 0.0 || followOffZ != 0.0) {
                    val k = exp(-dt / FOLLOW_SMOOTH_SECONDS)
                    followOffX *= k; followOffY *= k; followOffZ *= k
                    if (followOffX * followOffX + followOffY * followOffY + followOffZ * followOffZ < 1e-16) {
                        followOffX = 0.0; followOffY = 0.0; followOffZ = 0.0
                    }
                }
                val nx = a.x + followOffX
                val ny = a.y + followOffY
                val nz = a.z + followOffZ
                if (nx != target.x || ny != target.y || nz != target.z) {
                    target = Vec3(nx, ny, nz)
                    dirty = true
                }
            }
        }
        if (coasting && !dragging && dt > 0.0) {
            if (velRotX != 0.0 || velRotY != 0.0) applyRotation(velRotX * dt, velRotY * dt)
            if (velPanX != 0.0 || velPanY != 0.0) applyPan(velPanX * dt, velPanY * dt)
            val decay = exp(-dt / inertiaSeconds)
            velRotX *= decay; velRotY *= decay; velPanX *= decay; velPanY *= decay
            val speed2 = velRotX * velRotX + velRotY * velRotY + velPanX * velPanX + velPanY * velPanY
            if (speed2 < STOP_COAST_SPEED * STOP_COAST_SPEED) {
                coasting = false
                velRotX = 0.0; velRotY = 0.0; velPanX = 0.0; velPanY = 0.0
            }
        }
        return dirty
    }

    /** True while the camera still moves on its own (tween, follow or inertia). */
    val isAnimating: Boolean get() = flying || isFollowing || coasting

    // ---------------------------------------------------------------------------------------- internals

    private fun applyRotation(dx: Double, dy: Double) {
        val k = rotateSpeed / viewportHeight
        // camera-local yaw then pitch; negative angles: the camera goes left / up so the content goes right / down
        val yaw = QuatMath.axisAngle(0.0, 1.0, 0.0, -dx * k)
        val pitch = QuatMath.axisAngle(1.0, 0.0, 0.0, -dy * k)
        orbit = QuatMath.normalize(QuatMath.mul(orbit, QuatMath.mul(yaw, pitch)))
        dirty = true
    }

    private fun applyPan(dx: Double, dy: Double) {
        val k = 1.0 / pxPerUnit
        val right = QuatMath.rotate(orbit, UNIT_X)
        val up = QuatMath.rotate(orbit, UNIT_Y)
        target = Vec3(
            target.x + (-right.x * dx + up.x * dy) * k,
            target.y + (-right.y * dx + up.y * dy) * k,
            target.z + (-right.z * dx + up.z * dy) * k,
        )
        dirty = true
    }

    private fun applyFlight(e: Double) {
        val source = anchorSource ?: return
        val a = source()
        target = QuatMath.lerp(flyFromTarget, a, e)
        distance = exp(flyFromLogDistance + (flyToLogDistance - flyFromLogDistance) * e)
        if (flyFaceAnchor && QuatMath.length(a) > 1e-6) {
            // turn the START orbit by the shortest arc that brings its back vector onto the anchor direction:
            // the camera ends up outside the bloom on the anchor's side, with no surprise roll
            val back = QuatMath.rotate(flyFromOrbit, UNIT_Z)
            val goal = QuatMath.normalize(QuatMath.mul(QuatMath.fromTo(back, a), flyFromOrbit))
            orbit = QuatMath.slerp(flyFromOrbit, goal, e)
        }
        dirty = true
    }

    private fun finishFlight(interrupted: Boolean) {
        flying = false
        isFollowing = followAfterFlight && anchorSource != null
        followOffX = 0.0; followOffY = 0.0; followOffZ = 0.0
        val source = anchorSource
        if (interrupted && isFollowing && source != null) {
            val a = source()
            followOffX = target.x - a.x
            followOffY = target.y - a.y
            followOffZ = target.z - a.z
        }
        if (!isFollowing) anchorSource = null
        followAfterFlight = true
    }

    private fun stopMotion() {
        flying = false
        coasting = false
        dragging = false
        followAfterFlight = true
        velRotX = 0.0; velRotY = 0.0; velPanX = 0.0; velPanY = 0.0
    }

    private fun velocityBlend(dt: Double): Double {
        // time-constant blend so the estimate does not depend on the event rate
        val k = 1.0 - exp(-dt / VELOCITY_SMOOTH_SECONDS)
        return if (k > 1.0) 1.0 else k
    }

    companion object {
        private val UNIT_X = Vec3(1.0, 0.0, 0.0)
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        private val UNIT_Z = Vec3(0.0, 0.0, 1.0)
        const val WHEEL_ZOOM_PER_PX: Double = 0.0015
        const val MAX_WHEEL_PX: Double = 240.0
        const val MIN_COAST_SPEED: Double = 40.0
        const val STOP_COAST_SPEED: Double = 4.0
        const val VELOCITY_SMOOTH_SECONDS: Double = 0.06
        const val FOLLOW_SMOOTH_SECONDS: Double = 0.12

        /**
         * Camera distance at which a sphere of [radius] bloom units about the target fits a [cssWidth] x [cssHeight]
         * viewport. The BloomView scale is defined by the HEIGHT, so on a portrait phone the default distance of 9
         * lets the bloom spill over the sides; use this for the overview distance instead.
         * [margin] &gt; 1 leaves air (and room for the perspective bulge of the near half).
         */
        fun distanceToFit(radius: Double, cssWidth: Double, cssHeight: Double, fovDegrees: Double = 50.0, margin: Double = 1.2): Double {
            if (!(cssWidth > 0.0) || !(cssHeight > 0.0) || !(radius > 0.0)) return 9.0
            val aspect = cssWidth / cssHeight
            val narrow = if (aspect < 1.0) aspect else 1.0
            return radius * margin / (tan(fovDegrees * PI / 360.0) * narrow)
        }

        /** power3 in-out: gentle start (no lurch on tap), soft landing. */
        fun ease(t: Double): Double {
            val u = t.coerceIn(0.0, 1.0)
            return if (u < 0.5) 4.0 * u * u * u else 1.0 - 4.0 * (1.0 - u) * (1.0 - u) * (1.0 - u)
        }
    }
}
