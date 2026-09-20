package bar.verdantbloom.bloom.api

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny deterministic stand-in for bloom-core: ten tilted plain circles that precess with time, a visitor
 * circle, an optional ghost circle and three fixed spin discs. NOT Hopf, NOT Lorenz - just enough for
 * bloom-three / bloom-2d / site to be developed and tested before bloom-core lands.
 * Same t in => same floats out.
 */
class StubBloomWorld(
    override val config: BloomConfig = BloomConfig(),
    override val rings: List<ModelRing> = PourList.rings(config),
) : BloomWorld {

    override var unixSeconds: Double = 0.0
        private set

    private var kick: Double = 0.0

    override fun advanceTo(unixSeconds: Double) {
        this.unixSeconds = unixSeconds
    }

    private fun phase(): Double {
        val speed = (if (reducedMotion) config.reducedMotionFactor else 1.0) * driftRate
        return (unixSeconds % 3600.0) * 0.02 * speed
    }

    override val worldState: LorenzState
        get() = LorenzState(10.0 * sin(phase()), 10.0 * cos(phase()), 25.0)

    override val visitorState: LorenzState
        get() = LorenzState(10.0 * sin(phase() + kick), 10.0 * cos(phase() + kick), 25.0 + kick)

    override val worldRotation: Quat
        get() = Quat(cos(phase() / 2), 0.0, 0.0, sin(phase() / 2))

    override val visitorBase: Vec3
        get() = Vec3(cos(phase() + kick), sin(phase() + kick), 0.0)

    override var rho: Double = config.rhoDefault
        set(value) {
            field = value.coerceIn(config.rhoMin, config.rhoMax)
        }

    override var driftRate: Double = 1.0
        set(value) {
            field = value.coerceIn(0.02, 4.0)
        }

    override var reducedMotion: Boolean = false
    override var ghostEnabled: Boolean = false

    override fun perturb(kind: PerturbKind, magnitude: Double) {
        kick += config.switchEpsilon * (1 + kind.ordinal) + magnitude.coerceIn(-1.0, 1.0) * config.maxPerturbation
    }

    private fun radius(ringId: Int): Double = when (ringId) {
        RingIds.VISITOR -> 2.6
        RingIds.GHOST -> 2.8
        else -> 0.8 + 0.16 * ringId
    }

    private fun tilt(ringId: Int): Double = when (ringId) {
        RingIds.VISITOR -> phase() + kick * 50.0
        RingIds.GHOST -> phase()
        else -> ringId * (PI / 10.0) + phase()
    }

    private fun enabled(ringId: Int): Boolean =
        (ringId in rings.indices) || ringId == RingIds.VISITOR || (ringId == RingIds.GHOST && ghostEnabled)

    private fun point(ringId: Int, theta: Double): Vec3 {
        val r = radius(ringId)
        val a = tilt(ringId)
        val x = r * cos(theta)
        val y = r * sin(theta)
        // tilt about X, then spin about Z by half the tilt
        val y2 = y * cos(a)
        val z2 = y * sin(a)
        val s = a / 2
        return Vec3(x * cos(s) - y2 * sin(s), x * sin(s) + y2 * cos(s), z2)
    }

    override fun sampleRing(ringId: Int, out: FloatArray, offset: Int, segments: Int): Int {
        if (!enabled(ringId)) return 0
        var o = offset
        for (i in 0 until segments) {
            val p = point(ringId, 2.0 * PI * i / segments)
            out[o++] = p.x.toFloat()
            out[o++] = p.y.toFloat()
            out[o++] = p.z.toFloat()
        }
        return segments
    }

    override fun ringAlpha(ringId: Int): Double = when {
        !enabled(ringId) -> 0.0
        ringId == RingIds.GHOST -> 0.25
        else -> 1.0
    }

    override fun ringAnchor(ringId: Int): Vec3 = point(ringId, 0.0)

    override val spinDiscs: List<SpinDisc>
        get() = List(3) { i ->
            val p = point(i * 3, 1.0)
            SpinDisc(
                id = i,
                position = Vec3(p.x * 1.15, p.y * 1.15, p.z * 1.15 + 0.2),
                orientation = Quat.IDENTITY,
                radius = config.spinDiscRadius,
                periodSeconds = (7 + i * 5) * config.spinPeriodUnitSeconds,
                phase = i.toDouble(),
                bornAtUnixSeconds = 0.0,
                alpha = 1.0,
                triad = TriadKind.entries[i % 3],
                discColor = 0x2040ff,
                strokeColor = 0xff2020,
                wedgeColor = 0xffe020,
                nearRing = i * 3,
            )
        }

    override val lobeSwitchCount: Int
        get() = (phase() / PI).toInt()
}
