package bar.verdantbloom.bloom.three

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The service-bell ripple (SPEC 3.3 BELL): a decaying travelling wave that runs along every ring, the
 * inner rings first. Pure math, fixed capacity, no allocation after construction.
 *
 * Per sample it yields a signed [outWave] (radial displacement) and a 0..1 [outPulse] (extra brightness
 * and width).
 */
class RippleField(private val capacity: Int = 4) {
    private val startedAt = DoubleArray(capacity) { NEVER }
    private val magnitude = DoubleArray(capacity)
    private var nextSlot = 0

    /** Seconds of renderer time (sum of frame dt). */
    var now = 0.0
        private set

    var outWave = 0.0
        private set
    var outPulse = 0.0
        private set

    fun advance(dtSeconds: Double) {
        if (dtSeconds.isFinite() && dtSeconds > 0.0) now += min(dtSeconds, MAX_DT)
    }

    /** [kick] 0..1. A zero kick still rings faintly: the bell always answers. */
    fun kick(kick: Double) {
        val m = if (kick.isFinite()) kick.coerceIn(0.0, 1.0) else 0.0
        startedAt[nextSlot] = now
        magnitude[nextSlot] = MIN_KICK + (1.0 - MIN_KICK) * m
        nextSlot = (nextSlot + 1) % capacity
    }

    /** True while any ripple can still move a vertex. When false the ribbon builder skips [sample]. */
    val active: Boolean
        get() {
            for (i in 0 until capacity) if (now - startedAt[i] < LIFETIME) return true
            return false
        }

    /** Envelope of ripple [slot] for ring number [order] (0 = innermost, hit first). */
    fun envelope(slot: Int, order: Int): Double {
        val age = now - startedAt[slot] - order * RING_DELAY
        if (age <= 0.0 || age > LIFETIME) return 0.0
        val attack = min(1.0, age / ATTACK)
        return magnitude[slot] * attack * exp(-age / DECAY)
    }

    /**
     * [arc] 0..1 along the ring. WAVES is an integer so the wave closes on itself at arc 1 -> 0.
     */
    fun sample(arc: Double, order: Int) {
        var wave = 0.0
        var pulse = 0.0
        for (i in 0 until capacity) {
            val env = envelope(i, order)
            if (env <= 0.0) continue
            val age = now - startedAt[i] - order * RING_DELAY
            val s = sin(2.0 * PI * (WAVES * arc - SPEED_HZ * age))
            wave += env * s
            pulse += env * (0.5 + 0.5 * s)
        }
        outWave = wave.coerceIn(-1.5, 1.5)
        outPulse = pulse.coerceIn(0.0, 1.0)
    }

    companion object {
        const val NEVER = -1.0e9
        const val LIFETIME = 6.0
        const val ATTACK = 0.08
        const val DECAY = 1.1
        const val RING_DELAY = 0.06
        const val WAVES = 3
        const val SPEED_HZ = 1.6
        const val MIN_KICK = 0.25
        const val MAX_DT = 0.25
    }
}
