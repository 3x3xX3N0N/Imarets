package bar.verdantbloom.bloom.core

/**
 * Integer-only hash PRNG (SPEC 3.2). 32-bit wrap-around arithmetic only (xor, shift, multiply), so the
 * stream is identical on every Kotlin target and every browser. Counter based: value k of stream `seed`
 * is mix(seed + k * GOLDEN), so it never depends on call history beyond the counter.
 */
class HashPrng(private val seed: Int) {
    private var counter: Int = 0

    fun nextInt(): Int {
        counter += 1
        return mix(seed + counter * GOLDEN)
    }

    /** Uniform in 0 until bound (bound > 0). Uses the high bits; the tiny modulo bias is irrelevant here. */
    fun nextInt(bound: Int): Int = (nextInt() ushr 8) % bound

    /** Uniform in [0, 1) with 24 bits: an exact Int to Double conversion times an exact power of two. */
    fun nextUnit(): Double = (nextInt() ushr 8) * INV_2_24

    /** Uniform in [-1, 1). */
    fun nextSigned(): Double = nextUnit() * 2.0 - 1.0

    fun nextBoolean(): Boolean = (nextInt() ushr 31) == 1

    companion object {
        private const val GOLDEN: Int = -1640531527 // 0x9E3779B9
        private const val INV_2_24: Double = 1.0 / 16777216.0

        /** lowbias32 finaliser (Chris Wellons, public domain). */
        fun mix(value: Int): Int {
            var h = value
            h = h xor (h ushr 16)
            h *= 0x7feb352d
            h = h xor (h ushr 15)
            h *= -2073319797 // 0x846ca68b
            h = h xor (h ushr 16)
            return h
        }

        /** Seed for hour H (may be negative: hours before 1970). Folds both halves of the Long. */
        fun seedForEpoch(epoch: Long, salt: Int = 0): Int {
            val lo = epoch.toInt()
            val hi = (epoch shr 32).toInt()
            return mix(lo xor mix(hi xor 0x5bd1e995) xor mix(salt + 0x1b873593))
        }
    }
}
