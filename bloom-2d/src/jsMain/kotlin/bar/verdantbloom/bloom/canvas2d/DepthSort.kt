package bar.verdantbloom.bloom.canvas2d

/**
 * Painter's-algorithm ordering without allocation.
 *
 * [order] is a PERSISTENT permutation of `0 until count`: it is kept between frames, so after the first
 * frame it is almost sorted already and the insertion sort below runs in close to linear time
 * (the bloom drifts slowly; only a few items swap places per frame).
 */
object DepthSort {
    /** Fill [order] with the identity permutation. */
    fun reset(order: IntArray, count: Int = order.size) {
        for (i in 0 until count) order[i] = i
    }

    /**
     * Sort [order] so that `depth[order[0]] >= depth[order[1]] >= ...` : FARTHEST FIRST, which is the
     * drawing order (near things are painted last, on top). Stable.
     *
     * Depths must not be NaN ([Scene2d] gives hidden and non-finite items +Infinity). A NaN would not hang or
     * corrupt the permutation, it would merely end up in an arbitrary place.
     * The loop deliberately has no "if NaN then Infinity" fix-up: in V8 (node 24, TurboFan) a local that merges a
     * typed-array load with the constant Infinity is kept as a TAGGED value and every load gets boxed - measured
     * at 10 KB of heap numbers per call for 392 items, against 0 without it.
     */
    fun sortFarToNear(order: IntArray, depth: FloatArray, count: Int = order.size) {
        for (i in 1 until count) {
            val item = order[i]
            val d = depth[item]
            var j = i - 1
            while (j >= 0) {
                if (depth[order[j]] >= d) break
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = item
        }
    }

    /** True when [order] is a far-to-near ordering of [depth] (for tests and debugging, not for the hot path). */
    fun isSortedFarToNear(order: IntArray, depth: FloatArray, count: Int = order.size): Boolean {
        for (i in 1 until count) if (depth[order[i - 1]] < depth[order[i]]) return false
        return true
    }
}
