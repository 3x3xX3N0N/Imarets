package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.PerturbKind

/**
 * bloom-core constants that are NOT in the frozen [BloomConfig]. Everything here is a default and is
 * meant to be changed by passing a different instance to [BloomCore.createWorld]. See NOTES.md.
 *
 * Changing ANY value that feeds the world track (burn-in, blend lead-in) changes what everyone on
 * Earth sees, and invalidates the golden values in the tests.
 */
data class CoreTuning(
    /** Warm-up RK4 steps run on the hashed initial condition so every hour starts ON the attractor. */
    val burnInSteps: Int = 3000,
    /** Lorenz dt of the warm-up steps (3000 * 0.01 = 30 Lorenz time units). */
    val burnInDt: Double = 0.01,
    /**
     * Seconds BEFORE the top of the hour at which the next hour's track starts. In that window the
     * output is a smoothstep blend old hour -> new hour, so the hourly re-seed is a 12 s sweep instead
     * of a snap, and at the top of the hour the new track is already fully in charge.
     * blendSeconds * stepsPerSecond should be an integer.
     */
    val epochBlendSeconds: Double = 12.0,

    /** Visitor / ghost base point = normalise(x * sx, y * sy, (z - (rho - 1)) * sz). */
    val visitorScaleX: Double = 1.0 / 18.0,
    val visitorScaleY: Double = 1.0 / 24.0,
    val visitorScaleZ: Double = 1.0 / 22.0,

    /** Below this c (base point z) the fiber switches to the south chart of the Hopf parametrisation. */
    val southChartBelow: Double = -0.9,

    /**
     * Label anchor direction = (innermost point of the ring) + anchorBias * (point with the largest x1).
     * 0 = always the innermost point (whirls when the ring is a perfect unit circle).
     */
    val anchorBias: Double = 0.15,

    /** Spin disc fibers sit this far (radians on S2, in latitude) from their ring's base point. */
    val discLatitudeShift: Double = 0.35,
    /** Plus/minus longitude jitter of the disc fiber (radians). */
    val discLongitudeJitter: Double = 0.25,
    /** Disc sits at anchorTheta + [-spread, spread] along its fiber, i.e. near the visible inner part. */
    val discThetaSpread: Double = 1.2,

    /** A perturbation is never smaller than this (a zero-magnitude input still counts: nothing is inert). */
    val minPerturbation: Double = 1e-12,
    /** If the visitor falls more than this many steps behind (long sleep), it is reset to the world. */
    val visitorMaxCatchUpSteps: Int = 72000,
    /** With drift rate back at 1 the display clock eases back to real time with this time constant (s). */
    val driftRelaxSeconds: Double = 20.0,
    /** Real-time gaps longer than this (hidden tab) do not accumulate drift offset. */
    val driftMaxFrameSeconds: Double = 60.0,
) {
    /** Raw magnitude -> epsilon scale per input kind. SWITCH ignores magnitude (always config.switchEpsilon). */
    fun scaleFor(kind: PerturbKind): Double = when (kind) {
        PerturbKind.POINTER -> 2e-8      // px/s, 0..5000
        PerturbKind.SCROLL -> 1e-7       // px
        PerturbKind.WHEEL -> 1e-7        // wheel delta, ~100 per notch
        PerturbKind.KEY_TIMING -> 1e-8   // ms between key events
        PerturbKind.TOUCH -> 1e-7        // contact radius px
        PerturbKind.RESIZE -> 1e-7       // px of size change
        PerturbKind.VISIBILITY -> 1e-6   // seconds hidden
        PerturbKind.GAMEPAD -> 1e-5      // axis -1..1
        PerturbKind.PING_JITTER -> 1e-7  // ms
        PerturbKind.BATTERY -> 1e-6      // level 0..1
        PerturbKind.NETWORK -> 1e-7      // downlink mbps or rtt ms
        PerturbKind.TILT -> 1e-6         // degrees
        PerturbKind.SWITCH -> 0.0        // fixed epsilon
        PerturbKind.KNOB -> 1e-5         // knob travel 0..1
        PerturbKind.BELL -> 1e-6         // reply latency ms: 1 s = 1e-3 = the clamp
    }

    /** Which state axes a kind nudges: bit 0 = x, bit 1 = y, bit 2 = z. */
    fun axesFor(kind: PerturbKind): Int = when (kind) {
        PerturbKind.BELL -> 7
        else -> 1 shl (kind.ordinal % 3)
    }
}
