package com.example.spacegraphkt.external

import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.zui.Easing
import com.example.spacegraphkt.zui.Tween
import com.example.spacegraphkt.zui.TweenGroup
import kotlin.js.Date

/**
 * Small Kotlin replacement for the two camera tweens of the JS original (SPEC 2: the tween library is removed).
 * Surface: to / killTweensOf / isTweening, ease-out cubic by default. The maths lives in the pure
 * [Tween] / [TweenGroup] classes; this object only binds them to THREE.Vector3 targets.
 * Not self-driven: the owner calls [update] once per animation frame (CameraController.update does).
 */
object VecTween {
    private val group = TweenGroup<THREE.Vector3>()

    /** Tween [target] to (x, y, z) over [durationSeconds]; overwrites any running tween of the same target. */
    fun to(
        target: THREE.Vector3,
        x: Double, y: Double, z: Double,
        durationSeconds: Double,
        easing: Easing = Easing.OUT_CUBIC,
        nowMs: Double = Date.now(),
        onComplete: (() -> Unit)? = null,
    ) {
        group.kill(target)
        if (durationSeconds <= 0.0) {
            target.set(x, y, z)
            onComplete?.invoke()
            return
        }
        val tween = Tween(
            doubleArrayOf(target.x, target.y, target.z), doubleArrayOf(x, y, z),
            nowMs, durationSeconds * 1000.0, easing,
        )
        group.start(target, tween, onComplete) { v -> target.set(v[0], v[1], v[2]) }
    }

    fun killTweensOf(target: THREE.Vector3) {
        group.kill(target)
    }

    fun isTweening(target: THREE.Vector3): Boolean = group.isActive(target)

    /** Advance every tween to [nowMs]; finished tweens land exactly on their end value and are dropped. */
    fun update(nowMs: Double = Date.now()) = group.update(nowMs)
}
