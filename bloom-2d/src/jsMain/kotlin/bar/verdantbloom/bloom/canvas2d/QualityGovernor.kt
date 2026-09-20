package bar.verdantbloom.bloom.canvas2d

/**
 * Picks a [Quality2d] from the frame intervals the host reports. This rung exists for machines WITHOUT
 * a working GPU path, where the canvas is often rasterised in software and `shadowBlur` alone can cost
 * more than a whole frame - so RICH has to be earned and is given up for good on the first stumble.
 *
 * - start at STANDARD
 * - promote STANDARD -> RICH after [PROMOTE_FRAMES] consecutive smooth frames (at most once per session
 *   after a demotion: a rung that fell out of RICH never goes back)
 * - demote one level after [DEMOTE_FRAMES] consecutive slow frames
 * - promote LEAN -> STANDARD after [RECOVER_FRAMES] consecutive smooth frames
 *
 * Intervals above [IGNORE_ABOVE] seconds are ignored (hidden tab, debugger, a long GC pause).
 */
class QualityGovernor(initial: Quality2d = Quality2d.STANDARD) {
    var quality: Quality2d = initial
        private set

    /** When set the governor reports this level and stops adapting. */
    var pinned: Quality2d? = null

    /** Exponential moving average of the frame interval in seconds (diagnostics). */
    var averageInterval: Double = 1.0 / 60.0
        private set

    private var smooth = 0
    private var slow = 0
    private var richBanned = false

    val effective: Quality2d get() = pinned ?: quality

    /** Feed one frame interval. @return true when [effective] changed. */
    fun sample(dtSeconds: Double): Boolean {
        if (pinned != null) return false
        if (dtSeconds != dtSeconds || dtSeconds <= 0.0 || dtSeconds > IGNORE_ABOVE) return false
        averageInterval += (dtSeconds - averageInterval) * 0.05
        if (dtSeconds > SLOW_INTERVAL) {
            slow++
            smooth = 0
        } else if (dtSeconds < SMOOTH_INTERVAL) {
            smooth++
            if (slow > 0) slow--
        } else {
            smooth = 0
        }
        val before = quality
        if (slow >= DEMOTE_FRAMES) {
            slow = 0
            smooth = 0
            if (quality == Quality2d.RICH) {
                quality = Quality2d.STANDARD
                richBanned = true
            } else if (quality == Quality2d.STANDARD) {
                quality = Quality2d.LEAN
            }
        } else if (quality == Quality2d.STANDARD && !richBanned && smooth >= PROMOTE_FRAMES) {
            smooth = 0
            quality = Quality2d.RICH
        } else if (quality == Quality2d.LEAN && smooth >= RECOVER_FRAMES) {
            smooth = 0
            quality = Quality2d.STANDARD
            richBanned = true
        }
        return quality != before
    }

    companion object {
        const val SMOOTH_INTERVAL: Double = 1.0 / 52.0
        const val SLOW_INTERVAL: Double = 1.0 / 38.0
        const val IGNORE_ABOVE: Double = 0.25
        const val PROMOTE_FRAMES: Int = 150
        const val DEMOTE_FRAMES: Int = 40
        const val RECOVER_FRAMES: Int = 600
    }
}
