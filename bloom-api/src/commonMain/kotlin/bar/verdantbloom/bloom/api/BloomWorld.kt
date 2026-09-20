package bar.verdantbloom.bloom.api

/** Where a perturbation came from (SPEC 3.3). The kind only selects which state axis / scale is nudged. */
enum class PerturbKind {
    POINTER, SCROLL, WHEEL, KEY_TIMING, TOUCH, RESIZE, VISIBILITY, GAMEPAD,
    PING_JITTER, BATTERY, NETWORK, TILT, SWITCH, KNOB, BELL,
}

/** Colour scheme of a spin disc (SPEC 3.4). */
enum class TriadKind {
    /** The original: blue disc / red stroke / yellow wedges (colours carried in the SpinDisc). */
    ORIGINAL,

    /** Renderer substitutes Palette.accent (disc) / Palette.alert (stroke) / Palette.warm (wedges). */
    THEME,

    /** Wild random triad (colours carried in the SpinDisc). */
    WILD,
}

/**
 * A spin disc: three 60-degree wedges at 0/120/240 degrees, rotating forever.
 * Rotation angle at time t (radians) = phase + 2*pi * (t - bornAtUnixSeconds) / periodSeconds.
 * Colours are 0xRRGGBB. For TriadKind.THEME the renderer ignores the three colour fields.
 */
data class SpinDisc(
    val id: Int,
    val position: Vec3,
    /** Orientation of the disc plane in R3 (disc lies in local XY, normal = local +Z). */
    val orientation: Quat,
    val radius: Double,
    val periodSeconds: Double,
    val phase: Double,
    val bornAtUnixSeconds: Double,
    /** 0..1 fade for spawn / death; 1 when fully present. */
    val alpha: Double,
    val triad: TriadKind,
    val discColor: Int,
    val strokeColor: Int,
    val wedgeColor: Int,
    /** Model ring the disc sits near (never ON it). */
    val nearRing: Int,
)

/**
 * The bloom as a pure function of Unix time (world) plus the visitor's perturbed copy.
 * Implemented by bloom-core and by [StubBloomWorld].
 * Not thread safe; call from the animation frame only.
 */
interface BloomWorld {
    val config: BloomConfig

    /** The ten model rings, list index == ring id. */
    val rings: List<ModelRing>

    /**
     * Advance to absolute Unix time. Monotonic calls integrate incrementally; a backwards jump or an
     * epoch (hour) boundary re-seeds from the epoch hash, so ANY call order yields the same world state
     * for the same t. The visitor state advances by the same steps.
     */
    fun advanceTo(unixSeconds: Double)

    /** Time of the last [advanceTo]. */
    val unixSeconds: Double

    /** Deterministic world Lorenz state (identical for everyone on Earth at the same second). */
    val worldState: LorenzState

    /** The visitor's own copy: equal to [worldState] at first advanceTo, diverges through [perturb]. */
    val visitorState: LorenzState

    /** World unit quaternion: S3 rotation applied to every fiber before projection. */
    val worldRotation: Quat

    /** Visitor ring base point on S2, derived from [visitorState]. */
    val visitorBase: Vec3

    /** rho knob, clamped to config.rhoMin..rhoMax on set. Affects the VISITOR copy only; the world stays canonical. */
    var rho: Double

    /** Drift-rate knob: multiplier on the visible rotation speed, 1.0 = canonical. Clamped to 0.02..4. */
    var driftRate: Double

    /** prefers-reduced-motion or the HUD switch: drift slows to config.reducedMotionFactor, never stops. */
    var reducedMotion: Boolean

    /** Hidden ghost ring on/off (default off). */
    var ghostEnabled: Boolean

    /** Inject a perturbation into the visitor state. [magnitude] is raw (px/s, ms, ...); the world scales and clamps it. */
    fun perturb(kind: PerturbKind, magnitude: Double)

    /**
     * Sample ring [ringId] (0..9 model, RingIds.VISITOR, RingIds.GHOST) as a closed polyline into [out]
     * from [offset]: x0,y0,z0,x1,y1,z1,... [segments] points, first point NOT repeated.
     * Points are already rotated by [worldRotation], projected to R3 and radius-clamped.
     * [out] needs offset + 3 * segments floats.
     * @return number of points written (0 when the ring is disabled, e.g. ghost off).
     */
    fun sampleRing(ringId: Int, out: FloatArray, offset: Int = 0, segments: Int = config.fiberSegments): Int

    /** 0..1 opacity of a ring after the near-pole fade (0 when disabled). */
    fun ringAlpha(ringId: Int): Double

    /** Bloom-space point where ring [ringId]'s label / card hangs (a fixed theta on the fiber, clamped). */
    fun ringAnchor(ringId: Int): Vec3

    /** Live spin discs, config.spinDiscMin..spinDiscMax. One spawns or dies on each world lobe switch. */
    val spinDiscs: List<SpinDisc>

    /** Number of world lobe switches since the top of the epoch (pure function of time). */
    val lobeSwitchCount: Int
}
