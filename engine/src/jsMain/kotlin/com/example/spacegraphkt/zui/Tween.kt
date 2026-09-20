package com.example.spacegraphkt.zui

/**
 * Easing curves for the ZUI tween. Pure Kotlin, no DOM, no three: unit-tested on node.
 * All curves map 0 -> 0 and 1 -> 1 and clamp their input.
 */
fun interface Easing {
    fun ease(t: Double): Double

    companion object {
        val LINEAR = Easing { it.coerceIn(0.0, 1.0) }

        /** gsap "power2.out". */
        val OUT_QUAD = Easing { val u = 1.0 - it.coerceIn(0.0, 1.0); 1.0 - u * u }

        /** gsap "power3.out" - the camera default (what the two GSAP tweens of the JS original used). */
        val OUT_CUBIC = Easing { val u = 1.0 - it.coerceIn(0.0, 1.0); 1.0 - u * u * u }

        /** gsap "power4.out". */
        val OUT_QUART = Easing { val u = 1.0 - it.coerceIn(0.0, 1.0); 1.0 - u * u * u * u }

        val IN_OUT_CUBIC = Easing {
            val t = it.coerceIn(0.0, 1.0)
            if (t < 0.5) 4.0 * t * t * t else { val u = -2.0 * t + 2.0; 1.0 - u * u * u / 2.0 }
        }
    }
}

/**
 * One tween over an n-component value. Stateless with respect to time: [sample] is a pure function of
 * `nowMs`, so the owner decides the clock (rAF timestamp, Date.now, or a fake clock in tests).
 */
class Tween(
    from: DoubleArray,
    to: DoubleArray,
    val startMs: Double,
    val durationMs: Double,
    val easing: Easing = Easing.OUT_CUBIC,
) {
    private val from: DoubleArray = from.copyOf()
    private val to: DoubleArray = to.copyOf()

    init {
        require(from.size == to.size) { "Tween: from has ${from.size} components, to has ${to.size}" }
    }

    val size: Int get() = from.size

    /** Linear progress 0..1 at [nowMs]; a non-positive duration is finished immediately. */
    fun progress(nowMs: Double): Double =
        if (durationMs <= 0.0) 1.0 else ((nowMs - startMs) / durationMs).coerceIn(0.0, 1.0)

    fun isFinished(nowMs: Double): Boolean = progress(nowMs) >= 1.0

    /**
     * Writes the value at [nowMs] into [out] (needs [size] slots) and returns true when the tween is finished.
     * A finished tween writes EXACTLY the end value (no floating point residue).
     */
    fun sample(nowMs: Double, out: DoubleArray): Boolean {
        val p = progress(nowMs)
        if (p >= 1.0) {
            for (i in to.indices) out[i] = to[i]
            return true
        }
        val e = easing.ease(p)
        for (i in from.indices) out[i] = from[i] + (to[i] - from[i]) * e
        return false
    }

    fun end(): DoubleArray = to.copyOf()
}

/**
 * A set of keyed tweens advanced together. A new tween on a key replaces the running one (gsap "overwrite").
 * Keys are compared by IDENTITY (`===`) on purpose: THREE.Vector3 has its own component-wise `equals`, which
 * Kotlin's `==` would pick up, and two different vectors holding equal numbers must not share a tween.
 * (JS strings and numbers still compare by value under `===`.)
 */
class TweenGroup<K : Any> {
    private class Entry<K>(val key: K, val tween: Tween, val apply: (DoubleArray) -> Unit, val onDone: (() -> Unit)?) {
        val scratch = DoubleArray(tween.size)
    }

    private val entries = ArrayList<Entry<K>>()

    val activeCount: Int get() = entries.size

    fun start(key: K, tween: Tween, onDone: (() -> Unit)? = null, apply: (DoubleArray) -> Unit) {
        kill(key)
        entries.add(Entry(key, tween, apply, onDone))
    }

    /** Stops the tween of [key] where it is; its onDone is NOT called. @return true if one was running. */
    fun kill(key: K): Boolean = entries.removeAll { it.key === key }

    fun killAll() = entries.clear()

    fun isActive(key: K): Boolean = entries.any { it.key === key }

    /** Advances every tween to [nowMs]; finished ones apply their exact end value, are dropped, then call onDone. */
    fun update(nowMs: Double) {
        if (entries.isEmpty()) return
        var done: ArrayList<Entry<K>>? = null
        for (e in entries.toList()) {
            val finished = e.tween.sample(nowMs, e.scratch)
            e.apply(e.scratch)
            if (finished) (done ?: ArrayList<Entry<K>>().also { done = it }).add(e)
        }
        val finishedEntries = done ?: return
        entries.removeAll { it in finishedEntries }
        for (e in finishedEntries) e.onDone?.invoke()
    }
}
