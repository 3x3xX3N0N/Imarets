package bar.verdantbloom.bloom.api

/** The three shelves of the pour list (SPEC 3.6). A shelf is one latitude on S2 = one nested torus. */
enum class Shelf(val label: String) {
    WELL("WELL"),
    CALL("CALL"),
    TOP_SHELF("TOP SHELF"),
}

/**
 * Every tunable constant of the bloom in ONE object (SPEC 3.1 "Constants live in one config object").
 * All defaults are "(default)" in the SPEC sense: easy to change, nothing else hard-codes them.
 */
data class BloomConfig(
    // --- Lorenz clock (SPEC 3.2) ---
    val sigma: Double = 10.0,
    val beta: Double = 8.0 / 3.0,
    val rhoDefault: Double = 28.0,
    /** The rho knob is clamped to [rhoMin, rhoMax] so the system never settles. */
    val rhoMin: Double = 25.0,
    val rhoMax: Double = 45.0,
    /** Fixed RK4 steps per REAL second. World state at t = floor((t - epochStart) * stepsPerSecond) steps from the epoch seed. */
    val stepsPerSecond: Int = 10,
    /** Lorenz time per RK4 step. stepsPerSecond * lorenzDt = Lorenz time units per real second (0.025: a lobe switch roughly every 30-60 s). */
    val lorenzDt: Double = 0.0025,
    /** Seconds per hash epoch: the initial condition is re-seeded from H = floor(t / epochSeconds). */
    val epochSeconds: Int = 3600,
    /** Scale from Lorenz state to world angular velocity (radians per Lorenz time unit per unit of state). */
    val angularVelocityScale: Double = 0.004,
    /** prefers-reduced-motion: drift slows to this factor, never 0 (SPEC 3.2 "NEVER perfectly still"). */
    val reducedMotionFactor: Double = 1.0 / 50.0,

    // --- Hopf layout (SPEC 3.1) ---
    /** Latitude on S2 in radians for each shelf, in (-pi/2, pi/2). Must stay away from the south pole c = -1. */
    val shelfLatitudes: Map<Shelf, Double> = mapOf(
        Shelf.WELL to -0.35,
        Shelf.CALL to 0.35,
        Shelf.TOP_SHELF to 1.05,
    ),
    /** Longitude offset per shelf so rings of different shelves do not line up. */
    val shelfLongitudeOffsets: Map<Shelf, Double> = mapOf(
        Shelf.WELL to 0.0,
        Shelf.CALL to 0.5,
        Shelf.TOP_SHELF to 1.0,
    ),
    /** Points per sampled fiber (closed loop, first point NOT repeated). */
    val fiberSegments: Int = 128,
    /** Stereographic blow-up guard: projected points are clamped to this radius (bloom units). */
    val maxRadius: Double = 8.0,
    /** A ring starts fading when its farthest point passes this radius; alpha reaches 0 at [maxRadius]. */
    val fadeStartRadius: Double = 4.0,

    // --- visitor (SPEC 3.3) ---
    /** Epsilon injected by an inert-looking switch flip. */
    val switchEpsilon: Double = 1e-9,
    /** Upper bound of a single perturbation after scaling; keeps the visitor state on the attractor. */
    val maxPerturbation: Double = 1e-3,

    // --- spin discs (SPEC 3.4) ---
    val spinDiscMin: Int = 2,
    val spinDiscMax: Int = 5,
    /** Disc radius in bloom units (the original is r = 12.8 px at roughly 100 px per unit). */
    val spinDiscRadius: Double = 0.128,
    /** period = (7 + c) * spinPeriodUnitSeconds for c in 0 until spinPeriodCount. */
    val spinPeriodUnitSeconds: Double = 29.6,
    val spinPeriodCount: Int = 23,
    /** Seconds a disc takes to fade in / out. */
    val spinDiscFadeSeconds: Double = 2.5,
)
