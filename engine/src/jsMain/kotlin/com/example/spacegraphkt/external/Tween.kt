package com.example.spacegraphkt.external

import bar.verdantbloom.three.THREE
import kotlin.js.Date

/**
 * Small Kotlin replacement for the two GSAP camera tweens (SPEC 2: GSAP is removed).
 * Same surface the engine used from gsap: to / killTweensOf / isTweening, with gsap's "power3.out" ease.
 * Not self-driven: the owner calls [update] once per animation frame (CameraController._updateLoop does).
 */
object VecTween {
    private class Active(
        val target: THREE.Vector3,
        val fromX: Double, val fromY: Double, val fromZ: Double,
        val toX: Double, val toY: Double, val toZ: Double,
        val startMs: Double, val durationMs: Double,
    )

    private val active = ArrayList<Active>()

    /** power3.out: 1 - (1 - t)^3 */
    private fun ease(t: Double): Double {
        val u = 1.0 - t
        return 1.0 - u * u * u
    }

    /** Tween [target] to (x, y, z) over [durationSeconds]; overwrites any running tween of the same target. */
    fun to(target: THREE.Vector3, x: Double, y: Double, z: Double, durationSeconds: Double) {
        killTweensOf(target)
        if (durationSeconds <= 0.0) {
            target.set(x, y, z)
            return
        }
        active.add(Active(target, target.x, target.y, target.z, x, y, z, Date.now(), durationSeconds * 1000.0))
    }

    fun killTweensOf(target: THREE.Vector3) {
        active.removeAll { it.target === target }
    }

    fun isTweening(target: THREE.Vector3): Boolean = active.any { it.target === target }

    /** Advance every tween to [nowMs]; finished tweens land exactly on their end value and are dropped. */
    fun update(nowMs: Double = Date.now()) {
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            val t = ((nowMs - a.startMs) / a.durationMs).coerceIn(0.0, 1.0)
            val e = ease(t)
            a.target.set(
                a.fromX + (a.toX - a.fromX) * e,
                a.fromY + (a.toY - a.fromY) * e,
                a.fromZ + (a.toZ - a.fromZ) * e,
            )
            if (t >= 1.0) it.remove()
        }
    }
}
