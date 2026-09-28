package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The world as a pure function of Unix time: picks the hour track, catches it up, interpolates between
 * the two bracketing RK4 steps for smooth display, and in the last [CoreTuning.epochBlendSeconds] of an
 * hour blends towards the next hour's track (which starts its lead-in there).
 *
 * Same t in, same bits out, whatever the call history: tracks only ever step forward one RK4 step at a
 * time from their hashed seed, and everything else is computed from t alone.
 */
internal class WorldTimeline(
    private val config: BloomConfig,
    private val tuning: CoreTuning,
    private val ringCount: Int,
) {
    private var track: EpochTrack? = null
    private var next: EpochTrack? = null

    var unixSeconds: Double = Double.NaN
        private set

    // interpolated + blended outputs
    var x = 0.0; var y = 0.0; var z = 0.0
    var qw = 1.0; var qx = 0.0; var qy = 0.0; var qz = 0.0

    /** Blended state at the bracketing steps (what a freshly diverging visitor starts from). */
    var curX = 0.0; var curY = 0.0; var curZ = 0.0
    var nxtX = 0.0; var nxtY = 0.0; var nxtZ = 0.0

    /** 0 outside the blend window, 0..1 (smoothstep) inside it. */
    var blend = 0.0
        private set

    /** Whole steps since Unix 0 on this clock, and the fraction towards the next one (what the visitor counts). */
    var globalStep = 0.0
        private set
    var frac = 0.0
        private set

    /** RK4 steps the last advanceTo had to run (for the catch-up budget measurement). */
    var lastStepsRun: Int = 0
        private set

    val lobeSwitchCount: Int get() = track?.lobeSwitches ?: 0
    val epoch: Long get() = track?.epoch ?: 0L

    /** Track whose discs are shown right now, and the extra alpha factor from the hourly hand-over. */
    val discTrack: EpochTrack? get() = if (blend >= 0.5) next else track
    val discBlendAlpha: Double get() {
        val a = if (blend >= 0.5) 2.0 * blend - 1.0 else 1.0 - 2.0 * blend
        return if (a < 0.0) 0.0 else if (a > 1.0) 1.0 else a
    }

    fun advanceTo(t: Double) {
        if (t.isNaN() || t.isInfinite()) return
        val epochSeconds = config.epochSeconds.toDouble()
        val sps = config.stepsPerSecond.toDouble()
        var h = floor(t / epochSeconds)
        var sec = t - h * epochSeconds
        if (sec < 0.0) { h -= 1.0; sec += epochSeconds }
        if (sec >= epochSeconds) { h += 1.0; sec -= epochSeconds }
        val hour = h.toLong()
        var ran = 0

        var tr = track
        val lead = EpochTrack.leadStepsOf(config, tuning)
        val u = sec * sps + lead
        val n = floor(u).toInt()
        val f = u - n
        if (tr == null || tr.epoch != hour || n < tr.step) {
            val nx = next
            tr = if (nx != null && nx.epoch == hour && n >= nx.step) nx else EpochTrack(config, tuning, hour, ringCount)
            track = tr
            next = null
        }
        ran += n - tr.step
        tr.advanceToStep(n)
        globalStep = h * epochSeconds * sps + (n - lead)
        frac = f

        val windowStart = epochSeconds - tuning.epochBlendSeconds
        var nx: EpochTrack? = null
        var s = 0.0
        var f2 = 0.0
        if (lead > 0 && sec >= windowStart) {
            val u2 = (sec - windowStart) * sps
            val n2 = floor(u2).toInt()
            f2 = u2 - n2
            nx = next
            if (nx == null || nx.epoch != hour + 1 || n2 < nx.step) {
                nx = EpochTrack(config, tuning, hour + 1, ringCount)
            }
            ran += n2 - nx.step
            nx.advanceToStep(n2)
            val lin = u2 / lead
            val c = if (lin < 0.0) 0.0 else if (lin > 1.0) 1.0 else lin
            s = c * c * (3.0 - 2.0 * c)
        }
        next = nx
        blend = s
        lastStepsRun = ran
        unixSeconds = t

        // interpolate inside the current track
        val a = tr.cur
        val b = tr.nxt
        curX = a.x; curY = a.y; curZ = a.z
        nxtX = b.x; nxtY = b.y; nxtZ = b.z
        x = a.x + (b.x - a.x) * f
        y = a.y + (b.y - a.y) * f
        z = a.z + (b.z - a.z) * f
        nlerp(a.qw, a.qx, a.qy, a.qz, b.qw, b.qx, b.qy, b.qz, f)
        if (nx == null) return

        val w0 = qw; val w1 = qx; val w2 = qy; val w3 = qz
        val c = nx.cur
        val d = nx.nxt
        curX += (c.x - curX) * s; curY += (c.y - curY) * s; curZ += (c.z - curZ) * s
        nxtX += (d.x - nxtX) * s; nxtY += (d.y - nxtY) * s; nxtZ += (d.z - nxtZ) * s
        x += (c.x + (d.x - c.x) * f2 - x) * s
        y += (c.y + (d.y - c.y) * f2 - y) * s
        z += (c.z + (d.z - c.z) * f2 - z) * s
        nlerp(c.qw, c.qx, c.qy, c.qz, d.qw, d.qx, d.qy, d.qz, f2)
        // Hemisphere chosen ONCE per hand-over from two fixed instants (old track at the window start,
        // new track at its step 0), so it cannot flip mid-blend and does not depend on call history.
        val dot = if (tr.windowCaptured) {
            tr.windowQw * nx.startQw + tr.windowQx * nx.startQx + tr.windowQy * nx.startQy + tr.windowQz * nx.startQz
        } else 1.0
        val sign = if (dot < 0.0) -1.0 else 1.0
        nlerp(w0, w1, w2, w3, sign * qw, sign * qx, sign * qy, sign * qz, s)
    }

    /** Normalised lerp into qw..qz. Short arcs only (adjacent steps, or a hemisphere-fixed blend). */
    private fun nlerp(
        aw: Double, ax: Double, ay: Double, az: Double,
        bw: Double, bx: Double, by: Double, bz: Double, f: Double,
    ) {
        val w = aw + (bw - aw) * f
        val i = ax + (bx - ax) * f
        val j = ay + (by - ay) * f
        val k = az + (bz - az) * f
        val n2 = w * w + i * i + j * j + k * k
        if (n2 < 1e-12) {
            qw = bw; qx = bx; qy = by; qz = bz
            return
        }
        val inv = 1.0 / sqrt(n2)
        qw = w * inv; qx = i * inv; qy = j * inv; qz = k * inv
    }
}
