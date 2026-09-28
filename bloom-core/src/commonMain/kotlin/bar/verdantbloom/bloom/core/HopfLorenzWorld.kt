package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.LorenzState
import bar.verdantbloom.bloom.api.ModelRing
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.PourList
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real bloom (SPEC 3.1 - 3.4).
 *
 * Two clocks:
 *  - CANONICAL time t (what advanceTo is given): [worldState], [lobeSwitchCount], disc births and deaths.
 *    Identical for everyone on Earth at the same instant.
 *  - DISPLAY time tau = t + offset: [worldRotation], the visitor and the ghost. offset is 0 (and the two
 *    clocks are literally the same object) until the visitor touches the drift-rate knob or reduced
 *    motion is on; then tau runs at rate = driftRate * (reducedMotion ? 1/50 : 1), which is never 0.
 *    The display is still the same pure function, just evaluated at the visitor's own tau.
 */
class HopfLorenzWorld(
    override val config: BloomConfig = BloomConfig(),
    val tuning: CoreTuning = CoreTuning(),
    override val rings: List<ModelRing> = PourList.rings(config),
) : BloomWorld {

    private val canonical = WorldTimeline(config, tuning, rings.size)
    private var displayLine: WorldTimeline? = null
    private val display: WorldTimeline get() = displayLine ?: canonical

    private var lastT = Double.NaN
    private var offset = 0.0

    // visitor
    private var diverged = false
    private val vCur = LorenzPoint(0.0, 0.0, 0.0)
    private val vNxt = LorenzPoint(0.0, 0.0, 0.0)
    private var vStep = 0.0
    private var vFrac = 0.0
    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0

    private val fibers = Array(RingIds.COUNT) { HopfFiber() }
    private val discFiber = HopfFiber()
    private val p4 = DoubleArray(4)
    private val p3 = DoubleArray(3)
    private var discCache: List<SpinDisc>? = null

    init {
        for (r in rings) {
            if (r.index in 0 until RingIds.VISITOR) {
                val b = r.base
                fibers[r.index].setBase(b.x, b.y, b.z, tuning.southChartBelow)
            }
        }
        fibers[RingIds.VISITOR].setBase(1.0, 0.0, 0.0, tuning.southChartBelow)
        fibers[RingIds.GHOST].setBase(1.0, 0.0, 0.0, tuning.southChartBelow)
        for (f in fibers) f.rotate(1.0, 0.0, 0.0, 0.0)
    }

    // ---------------------------------------------------------------- knobs

    override var rho: Double = config.rhoDefault
        set(value) {
            if (value.isNaN()) return
            val clamped = if (value < config.rhoMin) config.rhoMin else if (value > config.rhoMax) config.rhoMax else value
            if (clamped == field) return
            field = clamped
            if (started) {
                ensureDiverged()
                refreshVisitor()
            }
        }

    override var driftRate: Double = 1.0
        set(value) {
            if (value.isNaN()) return
            field = if (value < DRIFT_MIN) DRIFT_MIN else if (value > DRIFT_MAX) DRIFT_MAX else value
        }

    override var reducedMotion: Boolean = false

    override var ghostEnabled: Boolean = false

    /** Effective display clock rate. Never 0: at worst 0.02 / 50 = 0.0004. */
    val effectiveRate: Double get() = driftRate * (if (reducedMotion) config.reducedMotionFactor else 1.0)

    /** Seconds the display clock is ahead (+) or behind (-) canonical time. 0 = everyone sees this rotation. */
    val displayOffsetSeconds: Double get() = offset

    /** True once the visitor state has left the world trajectory (first perturbation or rho change). */
    val visitorDiverged: Boolean get() = diverged

    /** RK4 steps the last advanceTo ran on the canonical clock (catch-up cost). */
    val lastCatchUpSteps: Int get() = canonical.lastStepsRun

    private val started: Boolean get() = !lastT.isNaN()

    // ---------------------------------------------------------------- time

    override fun advanceTo(unixSeconds: Double) {
        if (unixSeconds.isNaN() || unixSeconds.isInfinite()) return
        val t = unixSeconds
        if (started) {
            val dtReal = t - lastT
            if (dtReal < -BACKWARD_TOLERANCE_SECONDS) {
                // a real jump back (new #t, test harness): fresh load semantics
                offset = 0.0
                diverged = false
            } else if (dtReal > 0.0) {
                val dd = if (dtReal > tuning.driftMaxFrameSeconds) tuning.driftMaxFrameSeconds else dtReal
                val rate = effectiveRate
                if (rate != 1.0) {
                    offset += (rate - 1.0) * dd
                } else if (offset != 0.0) {
                    // ease back to real time, keeping the display rate inside [DRIFT_MIN, DRIFT_MAX]
                    val k = if (tuning.driftRelaxSeconds > 0.0) dd / tuning.driftRelaxSeconds else 1.0
                    var move = offset * (if (k > 1.0) 1.0 else k)
                    val maxBack = (1.0 - DRIFT_MIN) * dd
                    val maxFwd = (DRIFT_MAX - 1.0) * dd
                    if (move > maxBack) move = maxBack
                    if (move < -maxFwd) move = -maxFwd
                    offset -= move
                    if (abs(offset) < OFFSET_SNAP_SECONDS) offset = 0.0
                }
            }
        }
        lastT = t

        canonical.advanceTo(t)
        if (offset == 0.0) {
            displayLine = null
        } else {
            val line = displayLine ?: WorldTimeline(config, tuning, rings.size).also { displayLine = it }
            line.advanceTo(t + offset)
        }

        // visitor: same number of steps as the display clock
        val gStep = display.globalStep
        vFrac = display.frac
        if (diverged) {
            val gap = gStep - vStep
            if (gap > tuning.visitorMaxCatchUpSteps || gap < -BACKWARD_TOLERANCE_SECONDS * config.stepsPerSecond) {
                diverged = false
            } else if (gap > 0.0) {
                var i = gap.toInt()
                while (i > 0) {
                    vCur.copyFrom(vNxt)
                    vNxt.step(config.sigma, rho, config.beta, config.lorenzDt)
                    i -= 1
                }
                vStep = gStep
            } else if (gap < 0.0) {
                vFrac = 0.0
            }
        }
        // a visitor with its own rho is never "the world", even right after a reset
        if (!diverged && rho != config.rhoDefault) ensureDiverged()
        val d = display
        val q = d.qw
        for (r in rings) if (r.index in 0 until RingIds.VISITOR) fibers[r.index].rotate(q, d.qx, d.qy, d.qz)
        setBaseFromState(fibers[RingIds.GHOST], d.x, d.y, d.z, config.rhoDefault)
        fibers[RingIds.GHOST].rotate(q, d.qx, d.qy, d.qz)
        refreshVisitor()
        discCache = null
    }

    override val unixSeconds: Double get() = if (started) lastT else 0.0

    override val worldState: LorenzState get() = LorenzState(canonical.x, canonical.y, canonical.z)

    override val visitorState: LorenzState get() = LorenzState(vx, vy, vz)

    override val worldRotation: Quat get() { val d = display; return Quat(d.qw, d.qx, d.qy, d.qz) }

    override val visitorBase: Vec3 get() = baseFromState(vx, vy, vz, rho)

    override val lobeSwitchCount: Int get() = canonical.lobeSwitchCount

    // ---------------------------------------------------------------- visitor

    private fun ensureDiverged() {
        if (diverged) return
        val d = display
        vCur.set(d.curX, d.curY, d.curZ)
        vNxt.set(d.nxtX, d.nxtY, d.nxtZ)
        vStep = d.globalStep
        vFrac = d.frac
        diverged = true
    }

    private fun refreshVisitor() {
        val d = display
        if (diverged) {
            vx = vCur.x + (vNxt.x - vCur.x) * vFrac
            vy = vCur.y + (vNxt.y - vCur.y) * vFrac
            vz = vCur.z + (vNxt.z - vCur.z) * vFrac
        } else {
            vx = d.x; vy = d.y; vz = d.z
        }
        val f = fibers[RingIds.VISITOR]
        setBaseFromState(f, vx, vy, vz, rho)
        f.rotate(d.qw, d.qx, d.qy, d.qz)
    }

    override fun perturb(kind: PerturbKind, magnitude: Double) {
        if (!started) return
        var eps = if (kind == PerturbKind.SWITCH || magnitude.isNaN() || magnitude.isInfinite()) {
            config.switchEpsilon
        } else {
            abs(magnitude) * tuning.scaleFor(kind)
        }
        if (eps < tuning.minPerturbation) eps = tuning.minPerturbation
        if (eps > config.maxPerturbation) eps = config.maxPerturbation
        if (magnitude < 0.0) eps = -eps
        ensureDiverged()
        val axes = tuning.axesFor(kind)
        if (axes and 1 != 0) { vCur.x += eps; vNxt.x += eps }
        if (axes and 2 != 0) { vCur.y += eps; vNxt.y += eps }
        if (axes and 4 != 0) { vCur.z += eps; vNxt.z += eps }
        refreshVisitor()
    }

    /** Visitor / ghost base point on S2 from a Lorenz state: the scaled direction from the attractor's centre. */
    fun baseFromState(x: Double, y: Double, z: Double, rhoValue: Double): Vec3 {
        val ux = x * tuning.visitorScaleX
        val uy = y * tuning.visitorScaleY
        val uz = (z - (rhoValue - 1.0)) * tuning.visitorScaleZ
        val n2 = ux * ux + uy * uy + uz * uz
        if (n2 < 1e-18) return Vec3.UNIT_Z
        val inv = 1.0 / sqrt(n2)
        return Vec3(ux * inv, uy * inv, uz * inv)
    }

    private fun setBaseFromState(f: HopfFiber, x: Double, y: Double, z: Double, rhoValue: Double) {
        val b = baseFromState(x, y, z, rhoValue)
        f.setBase(b.x, b.y, b.z, tuning.southChartBelow)
    }

    // ---------------------------------------------------------------- rings

    private fun ringEnabled(ringId: Int): Boolean = when {
        ringId < 0 || ringId >= RingIds.COUNT -> false
        ringId == RingIds.GHOST -> ghostEnabled
        ringId < RingIds.VISITOR -> ringId < rings.size
        else -> true
    }

    override fun sampleRing(ringId: Int, out: FloatArray, offset: Int, segments: Int): Int {
        if (!ringEnabled(ringId) || segments <= 0 || offset < 0 || out.size < offset + 3 * segments) return 0
        val f = fibers[ringId]
        val table = HopfFiber.table(segments)
        val ra = f.ra
        val rb = f.rb
        val maxR = config.maxRadius
        var o = offset
        for (i in 0 until segments) {
            val c = table[2 * i]
            val s = table[2 * i + 1]
            HopfFiber.projectInto(
                ra[0] * c + rb[0] * s, ra[1] * c + rb[1] * s, ra[2] * c + rb[2] * s, ra[3] * c + rb[3] * s, maxR, p3,
            )
            out[o] = p3[0].toFloat(); out[o + 1] = p3[1].toFloat(); out[o + 2] = p3[2].toFloat()
            o += 3
        }
        return segments
    }

    /**
     * Same ring in full precision and on S3, BEFORE projection and clamping: x1,x2,x3,x4 per point.
     * For tests and for anyone who wants their own projection. Returns points written.
     */
    fun sampleRingS3(ringId: Int, out: DoubleArray, segments: Int = config.fiberSegments): Int {
        if (ringId < 0 || ringId >= RingIds.COUNT || segments <= 0 || out.size < 4 * segments) return 0
        val f = fibers[ringId]
        val table = HopfFiber.table(segments)
        for (i in 0 until segments) {
            val c = table[2 * i]
            val s = table[2 * i + 1]
            for (k in 0 until 4) out[4 * i + k] = f.ra[k] * c + f.rb[k] * s
        }
        return segments
    }

    /** Radius of the farthest point of the projected ring before clamping (infinite through the pole). */
    fun ringFarRadius(ringId: Int): Double =
        if (ringId < 0 || ringId >= RingIds.COUNT) Double.POSITIVE_INFINITY else fibers[ringId].farRadius

    override fun ringAlpha(ringId: Int): Double =
        if (!ringEnabled(ringId)) 0.0 else fibers[ringId].alpha(config.fadeStartRadius, config.maxRadius)

    override fun ringAnchor(ringId: Int): Vec3 {
        if (ringId < 0 || ringId >= RingIds.COUNT) return Vec3.ZERO
        val f = fibers[ringId]
        f.pointS3(f.anchorTheta(tuning.anchorBias), p4)
        f.project(p4, config.maxRadius, p3)
        return Vec3(p3[0], p3[1], p3[2])
    }

    // ---------------------------------------------------------------- spin discs

    override val spinDiscs: List<SpinDisc>
        get() {
            discCache?.let { return it }
            val built = buildDiscs()
            discCache = built
            return built
        }

    private fun buildDiscs(): List<SpinDisc> {
        if (!started) return emptyList()
        val track = canonical.discTrack ?: return emptyList()
        val t = canonical.unixSeconds
        val fade = config.spinDiscFadeSeconds
        val handOver = canonical.discBlendAlpha
        val d = display
        val out = ArrayList<SpinDisc>()
        for (seed in track.discSeeds) {
            if (t >= seed.dyingAt + fade) continue
            val ringIndex = if (rings.isEmpty()) 0 else seed.nearRing % rings.size
            var alpha = seed.alphaAt(t, fade) * handOver
            var position = Vec3.ZERO
            if (rings.isNotEmpty()) {
                val ring = rings[ringIndex]
                var lat = ring.latitude + seed.latitudeSign * tuning.discLatitudeShift
                if (lat > MAX_DISC_LATITUDE) lat = MAX_DISC_LATITUDE
                if (lat < -MAX_DISC_LATITUDE) lat = -MAX_DISC_LATITUDE
                val lon = ring.longitude + seed.longitudeJitter * tuning.discLongitudeJitter
                discFiber.setBase(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat), tuning.southChartBelow)
                discFiber.rotate(d.qw, d.qx, d.qy, d.qz)
                val theta = fibers[ring.index].anchorTheta(tuning.anchorBias) + seed.thetaOffset * tuning.discThetaSpread
                discFiber.pointS3(theta, p4)
                discFiber.project(p4, config.maxRadius, p3)
                position = Vec3(p3[0], p3[1], p3[2])
                // same distance fade as the rings, on the disc's own radius
                val r = sqrt(p3[0] * p3[0] + p3[1] * p3[1] + p3[2] * p3[2])
                if (r > config.fadeStartRadius) {
                    val span = config.maxRadius - config.fadeStartRadius
                    val c = if (span <= 0.0) 1.0 else (r - config.fadeStartRadius) / span
                    val cc = if (c > 1.0) 1.0 else c
                    alpha *= 1.0 - cc * cc * (3.0 - 2.0 * cc)
                }
            }
            out.add(
                SpinDisc(
                    id = seed.id,
                    position = position,
                    orientation = seed.orientation,
                    radius = config.spinDiscRadius,
                    periodSeconds = seed.periodSeconds,
                    phase = seed.phase,
                    bornAtUnixSeconds = seed.bornAt,
                    alpha = alpha,
                    triad = seed.triad,
                    discColor = seed.discColor,
                    strokeColor = seed.strokeColor,
                    wedgeColor = seed.wedgeColor,
                    nearRing = ringIndex,
                ),
            )
        }
        return out
    }

    companion object {
        const val DRIFT_MIN: Double = 0.02
        const val DRIFT_MAX: Double = 4.0
        private const val BACKWARD_TOLERANCE_SECONDS: Double = 5.0
        private const val OFFSET_SNAP_SECONDS: Double = 0.005
        private const val MAX_DISC_LATITUDE: Double = 1.45
    }
}
