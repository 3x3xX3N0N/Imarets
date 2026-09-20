package bar.verdantbloom.bloom.api

/** Source of "now". */
fun interface ClockSource {
    /** Unix time in seconds, fractional. */
    fun nowUnixSeconds(): Double
}

/**
 * `#t=<unix>` override (SPEC 3.2): the clock STARTS at [startUnixSeconds] at the moment of construction
 * and then flows at real speed. `#t=0` is the origin bloom, 1 Jan 1970.
 */
class OverrideClock(private val wall: ClockSource, val startUnixSeconds: Double) : ClockSource {
    private val wallAtStart: Double = wall.nowUnixSeconds()
    override fun nowUnixSeconds(): Double = startUnixSeconds + (wall.nowUnixSeconds() - wallAtStart)
}

object TimeOverride {
    /**
     * Extracts the `t=<unix>` override from a location hash such as `#t=0`, `#pour/mini&t=86400`
     * or `#t=1758326400&x`. The `t=` key must start the hash or follow `&`.
     * Returns null when absent or not a finite non-negative number.
     */
    fun parse(hash: String): Double? {
        val body = hash.removePrefix("#")
        for (part in body.split('&')) {
            if (part.startsWith("t=")) {
                val v = part.substring(2).toDoubleOrNull() ?: return null
                return if (v.isNaN() || v.isInfinite() || v < 0.0) null else v
            }
        }
        return null
    }

    /** Wraps [wall] when [hash] carries an override, otherwise returns [wall] unchanged. */
    fun clockFor(hash: String, wall: ClockSource): ClockSource {
        val t = parse(hash) ?: return wall
        return OverrideClock(wall, t)
    }
}
