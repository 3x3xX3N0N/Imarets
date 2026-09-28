package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.TriadKind
import kotlin.math.PI
import kotlin.math.sqrt

/** Everything about a spin disc that does not depend on the current world rotation. */
internal class DiscSeed(
    val id: Int,
    val nearRing: Int,
    /** +1 or -1: which side (in latitude) of the ring's base point the disc fiber sits. */
    val latitudeSign: Int,
    /** -1..1, times CoreTuning.discLongitudeJitter. */
    val longitudeJitter: Double,
    /** -1..1, times CoreTuning.discThetaSpread, relative to the ring's anchor theta. */
    val thetaOffset: Double,
    val orientation: Quat,
    val periodSeconds: Double,
    val phase: Double,
    val bornAt: Double,
    val triad: TriadKind,
    val discColor: Int,
    val strokeColor: Int,
    val wedgeColor: Int,
) {
    var dyingAt: Double = Double.POSITIVE_INFINITY

    fun alphaAt(t: Double, fade: Double): Double {
        if (fade <= 0.0) return if (t >= dyingAt) 0.0 else 1.0
        var a = (t - bornAt) / fade
        if (t > dyingAt) {
            val out = 1.0 - (t - dyingAt) / fade
            if (out < a) a = out
        }
        return if (a < 0.0) 0.0 else if (a > 1.0) 1.0 else a
    }
}

/**
 * One hour of the world: the hashed initial condition for epoch H, integrated step by step.
 *
 * Step 0 sits [leadSteps] BEFORE the top of the hour (the blend lead-in, see CoreTuning.epochBlendSeconds),
 * step k is at unix time epochStart + (k - leadSteps) / stepsPerSecond. The state at step k depends on
 * k only, never on how advanceToStep was called, which is what makes the bloom a pure function of time.
 *
 * Holds the state at [step] (cur) and at step + 1 (nxt) so callers can interpolate for display.
 */
internal class EpochTrack(
    private val config: BloomConfig,
    private val tuning: CoreTuning,
    val epoch: Long,
    private val ringCount: Int,
) {
    val leadSteps: Int = leadStepsOf(config, tuning)
    val epochStartUnix: Double = epoch.toDouble() * config.epochSeconds
    /** Step index at which the NEXT hour's lead-in starts. */
    val windowStartStep: Int = config.epochSeconds * config.stepsPerSecond

    val cur = LorenzQuat()
    val nxt = LorenzQuat()
    var step: Int = 0
        private set

    /** World lobe switches since the top of the hour (steps after leadSteps only). */
    var lobeSwitches: Int = 0
        private set

    /** Quaternion at [windowStartStep]; valid once the track has passed it. Fixes the blend hemisphere. */
    var windowQw = 1.0; var windowQx = 0.0; var windowQy = 0.0; var windowQz = 0.0
    var windowCaptured = false
        private set

    /** The hour's first quaternion (step 0), for the same purpose. */
    val startQw: Double
    val startQx: Double
    val startQy: Double
    val startQz: Double

    private val discPrng = HashPrng(HashPrng.seedForEpoch(epoch, salt = 2))
    private val discs = ArrayList<DiscSeed>()
    private var discSerial = 0
    val discSeeds: List<DiscSeed> get() = discs

    private val zCentre = config.rhoDefault - 1.0

    init {
        val prng = HashPrng(HashPrng.seedForEpoch(epoch, salt = 1))
        // Hashed initial condition: off the z axis (x = y = 0 is invariant), roughly attractor sized.
        val sx = if (prng.nextBoolean()) 1.0 else -1.0
        val sy = if (prng.nextBoolean()) 1.0 else -1.0
        val p = LorenzPoint(
            sx * (1.0 + 14.0 * prng.nextUnit()),
            sy * (1.0 + 14.0 * prng.nextUnit()),
            8.0 + 30.0 * prng.nextUnit(),
        )
        for (i in 0 until tuning.burnInSteps) p.step(config.sigma, config.rhoDefault, config.beta, tuning.burnInDt)
        cur.x = p.x; cur.y = p.y; cur.z = p.z
        val q = randomUnitQuat(prng)
        cur.qw = q.w; cur.qx = q.x; cur.qy = q.y; cur.qz = q.z
        startQw = q.w; startQx = q.x; startQy = q.y; startQz = q.z
        nxt.copyFrom(cur)
        stepNxt()

        val initial = config.spinDiscMin + discPrng.nextInt(discSpan() + 1)
        val lead = leadSteps.toDouble() / config.stepsPerSecond
        for (i in 0 until initial) discs.add(newDisc(epochStartUnix - lead))
    }

    private fun discSpan(): Int = if (config.spinDiscMax > config.spinDiscMin) config.spinDiscMax - config.spinDiscMin else 0

    private fun stepNxt() {
        nxt.step(config.sigma, config.rhoDefault, config.beta, config.lorenzDt, config.angularVelocityScale, zCentre)
    }

    fun timeOfStep(k: Int): Double = epochStartUnix + (k - leadSteps).toDouble() / config.stepsPerSecond

    /** Sequential only: [n] must be >= [step]. */
    fun advanceToStep(n: Int) {
        while (step < n) {
            val prevLobe = cur.x >= 0.0
            cur.copyFrom(nxt)
            step += 1
            if (step == windowStartStep) {
                windowQw = cur.qw; windowQx = cur.qx; windowQy = cur.qy; windowQz = cur.qz
                windowCaptured = true
            }
            if (step > leadSteps && (cur.x >= 0.0) != prevLobe) {
                lobeSwitches += 1
                onLobeSwitch(timeOfStep(step))
            }
            stepNxt()
        }
    }

    // ---- spin disc lifecycle (SPEC 3.4): one spawns or dies per world lobe switch ----

    private fun onLobeSwitch(t: Double) {
        val fade = config.spinDiscFadeSeconds
        // forget discs that finished fading out
        var i = discs.size - 1
        while (i >= 0) {
            if (t >= discs[i].dyingAt + fade) discs.removeAt(i)
            i -= 1
        }
        val alive = discs.count { it.dyingAt == Double.POSITIVE_INFINITY }
        val coin = discPrng.nextBoolean() // always drawn, so the stream does not depend on the branch
        val spawn = when {
            alive <= config.spinDiscMin -> true
            alive >= config.spinDiscMax -> false
            else -> coin
        }
        if (spawn) {
            // the list (alive + still fading) must never exceed the maximum
            while (discs.size >= config.spinDiscMax) {
                val k = discs.indexOfFirst { it.dyingAt != Double.POSITIVE_INFINITY }
                if (k < 0) return
                discs.removeAt(k)
            }
            discs.add(newDisc(t))
        } else {
            var pick = discPrng.nextInt(alive)
            for (d in discs) {
                if (d.dyingAt != Double.POSITIVE_INFINITY) continue
                if (pick == 0) {
                    d.dyingAt = t
                    break
                }
                pick -= 1
            }
        }
    }

    private fun newDisc(bornAt: Double): DiscSeed {
        val p = discPrng
        discSerial += 1
        val id = ((HashPrng.mix(epoch.toInt()) and 0x7ffff) shl 12) or (discSerial and 0xfff)
        val ring = p.nextInt(if (ringCount > 0) ringCount else 1)
        val latSign = if (p.nextBoolean()) 1 else -1
        val lonJitter = p.nextSigned()
        val theta = p.nextSigned()
        val orientation = randomUnitQuat(p)
        val period = (7 + p.nextInt(if (config.spinPeriodCount > 0) config.spinPeriodCount else 1)) * config.spinPeriodUnitSeconds
        val phase = p.nextUnit() * 2.0 * PI
        val triad = TriadKind.entries[p.nextInt(3)]
        val hue = p.nextInt(360)
        val jitterA = p.nextInt(41) - 20
        val jitterB = p.nextInt(41) - 20
        var disc = ORIGINAL_DISC
        var stroke = ORIGINAL_STROKE
        var wedge = ORIGINAL_WEDGE
        if (triad == TriadKind.WILD) {
            disc = hueToRgb(hue)
            stroke = hueToRgb(hue + 120 + jitterA)
            wedge = hueToRgb(hue + 240 + jitterB)
        }
        return DiscSeed(id, ring, latSign, lonJitter, theta, orientation, period, phase, bornAt, triad, disc, stroke, wedge)
    }

    companion object {
        /** The reference colours: fill="blue" stroke="red", wedges fill="yellow". */
        const val ORIGINAL_DISC: Int = 0x0000ff
        const val ORIGINAL_STROKE: Int = 0xff0000
        const val ORIGINAL_WEDGE: Int = 0xffff00

        fun leadStepsOf(config: BloomConfig, tuning: CoreTuning): Int {
            val v = tuning.epochBlendSeconds * config.stepsPerSecond
            return if (v > 0.0) (v + 0.5).toInt() else 0
        }

        /** Uniform random rotation: rejection sample the 4-ball, then normalise. Integer PRNG driven. */
        fun randomUnitQuat(prng: HashPrng): Quat {
            for (attempt in 0 until 64) {
                val w = prng.nextSigned()
                val x = prng.nextSigned()
                val y = prng.nextSigned()
                val z = prng.nextSigned()
                val n2 = w * w + x * x + y * y + z * z
                if (n2 > 0.01 && n2 <= 1.0) {
                    val inv = 1.0 / sqrt(n2)
                    return Quat(w * inv, x * inv, y * inv, z * inv)
                }
            }
            return Quat.IDENTITY
        }

        /** Fully saturated hue (degrees, any integer) to 0xRRGGBB, integer arithmetic only. */
        fun hueToRgb(hueDegrees: Int): Int {
            val h = ((hueDegrees % 360) + 360) % 360
            val sector = h / 60
            val rising = (h % 60) * 255 / 60
            val falling = 255 - rising
            val r: Int
            val g: Int
            val b: Int
            when (sector) {
                0 -> { r = 255; g = rising; b = 0 }
                1 -> { r = falling; g = 255; b = 0 }
                2 -> { r = 0; g = 255; b = rising }
                3 -> { r = 0; g = falling; b = 255 }
                4 -> { r = rising; g = 0; b = 255 }
                else -> { r = 255; g = 0; b = falling }
            }
            return (r shl 16) or (g shl 8) or b
        }
    }
}
