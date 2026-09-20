# bloom-core notes: constants and how to tune them

Pure Kotlin, `commonMain` only (no DOM, no three). Package `bar.verdantbloom.bloom.core`.

```
BloomCore.createWorld(config = BloomCore.DEFAULT_CONFIG): BloomWorld      // what the site calls
BloomCore.createWorld(config, tuning: CoreTuning): HopfLorenzWorld        // everything tunable
BloomCore.newCalendar: NewCalendar                                        // = NewCal
```

Build / test: `./gradlew :bloom-core:compileKotlinJs`, `./gradlew :bloom-core:jsNodeTest`
(33 tests, about 2 s; mocha timeout raised to 60 s in `build.gradle.kts`).

## Files

| file | what |
| --- | --- |
| `HashPrng.kt` | counter-based 32-bit integer hash PRNG (lowbias32). `seedForEpoch(H, salt)` |
| `Lorenz.kt` | `LorenzPoint` (3-dim RK4, visitor + burn-in), `LorenzQuat` (7-dim RK4: state + world quaternion) |
| `EpochTrack.kt` | one hour of the world from its hashed seed; lobe-switch detection; spin-disc lifecycle |
| `WorldTimeline.kt` | pure function t -> state/rotation: track choice, catch-up, sub-step lerp, hourly blend |
| `HopfFiber.kt` | fiber as great circle `A cos t + B sin t`, S3 rotation, projection, clamp, fade, anchor |
| `HopfLorenzWorld.kt` | the `BloomWorld`: canonical clock, display clock, visitor, ghost, rings, discs |
| `CoreTuning.kt` | every constant that is not in the frozen `BloomConfig` |
| `NewCal.kt` | The New Calendar port (MIT, (c) ralph7c2, see `NOTICE`) |

## The two rules that must survive tuning

1. **World = pure function of Unix time.** A track only ever steps forward one fixed RK4 step at a time
   from its hashed seed; the state at step k depends on k alone. Everything else (sub-step
   interpolation, the hourly blend factor, disc alphas) is computed from t. Test
   `oneJumpEqualsManySmallCalls` compares one jump, 1600 small calls and a forward-then-back jump
   bit for bit (states, quaternion, every sampled ring float, discs).
2. **Integrator uses only `+ - *`.** `Lorenz.kt` has no division and no `kotlin.math` call except one
   `sqrt` and one `/` in `LorenzQuat.normalise()` (quaternion normalisation; both are exactly rounded
   IEEE operations, so they are as portable as `+`). 1/6 and 1/2^24 are compile-time constants.
   Trig lives only in layout/sampling code (`HopfFiber`, disc placement), which is display, not state.

Changing any of these changes what *everyone* sees and breaks the golden tests (that is their job):
`sigma, beta, rhoDefault, stepsPerSecond, lorenzDt, epochSeconds, angularVelocityScale` (rotation
only), `CoreTuning.burnInSteps, burnInDt, epochBlendSeconds`, the PRNG, the seed salts (1 = initial
condition, 2 = discs), the order of PRNG draws in `EpochTrack`.

## Constants

### BloomConfig (frozen API class; bloom-core passes its own instance: `BloomCore.DEFAULT_CONFIG`)

| field | value | note |
| --- | --- | --- |
| `sigma, beta, rhoDefault` | 10, 8/3, 28 | world always runs at `rhoDefault` |
| `rhoMin, rhoMax` | 25, 45 | knob clamp. rho > 24.74 means both fixed points are unstable: never settles. Test sweeps 25..45 for 1000 Lorenz time units |
| `stepsPerSecond` | 10 | RK4 steps per real second |
| `lorenzDt` | 0.0025 | 0.025 Lorenz time units per real second. Measured: 50 lobe switches per hour (one per ~70 s) |
| `epochSeconds` | 3600 | re-seed period |
| `angularVelocityScale` | **0.08** (API default 0.004) | omega = scale * (x, y, z - (rho-1)) rad per Lorenz time unit. 0.08 is about 0.02 rad/s, a turn every 5 min. 0.004 looked frozen (one turn per 100 min) |
| `reducedMotionFactor` | 1/50 | |
| `shelfLatitudes / shelfLongitudeOffsets` | -0.35 / 0.35 / 1.05 rad; 0 / 0.5 / 1.0 | layout, from `PourList.rings(config)` |
| `fiberSegments` | 128 | default for `sampleRing` |
| `fadeStartRadius, maxRadius` | **5, 10** (API default 4, 8) | see "pole blow-up" |
| `switchEpsilon, maxPerturbation` | 1e-9, 1e-3 | |
| `spinDiscMin/Max` | 2, 5 | |
| `spinDiscRadius, spinPeriodUnitSeconds, spinPeriodCount, spinDiscFadeSeconds` | 0.128, 29.6, 23, 2.5 | |

### CoreTuning (bloom-core's own)

| field | value | note |
| --- | --- | --- |
| `burnInSteps, burnInDt` | 3000, 0.01 | 30 Lorenz time units of warm-up so an hour never starts with a transient spiralling in |
| `epochBlendSeconds` | 12 | hourly hand-over window; `* stepsPerSecond` must be an integer |
| `visitorScaleX/Y/Z` | 1/18, 1/24, 1/22 | visitor base = normalise(x sx, y sy, (z - (rho-1)) sz) |
| `southChartBelow` | -0.9 | Hopf chart switch for base points near c = -1 |
| `anchorBias` | 0.15 | label anchor, see below |
| `discLatitudeShift, discLongitudeJitter, discThetaSpread` | 0.35, 0.25, 1.2 | disc placement |
| `minPerturbation` | 1e-12 | "nothing is inert" floor |
| `visitorMaxCatchUpSteps` | 72000 | longer gaps reset the visitor to the world |
| `driftRelaxSeconds, driftMaxFrameSeconds` | 20, 60 | display clock |
| `scaleFor(kind)` | table in the file | raw magnitude -> epsilon |

## How it works, briefly

**Hour track.** `H = floor(t / 3600)`. Seed = `HashPrng.seedForEpoch(H, 1)` (folds the 64-bit hour,
works for negative hours). Initial point: x, y in +-[1, 15], z in [8, 38], then the burn-in; initial
quaternion: rejection-sampled uniform unit quaternion. Track step 0 sits `epochBlendSeconds` BEFORE the
top of the hour; step k is at `H*3600 + (k - 120)/10`. At exactly `H*3600` the output is purely track
H, so `#t=0` shows track 0 at its step 120 and needs one track only.

**Catch-up.** Worst case is loading in the last 12 s of an hour: 36 120 steps of track H plus up to 120
of track H+1 (and two burn-ins). Measured in `fullHourCatchUpIsUnderBudget` on this machine (node 24):
**3 - 5 ms best of five, 15 - 21 ms cold**, budget 100 ms. The test fails if it ever exceeds 100 ms.

**Smoothness.** Ten steps per second would judder at 60 fps, so outputs are interpolated between the
steps bracketing t (lerp for the state, normalised lerp for the quaternion). Still a pure function of t.

**Hourly hand-over.** In the last 12 s of hour H the output is `smoothstep` blend from track H to track
H+1 (which started its lead-in there), so the re-seed is a 12 s sweep of the whole bloom, never a snap.
The quaternion hemisphere for that blend is fixed from two fixed instants (old track at window start,
new track step 0). Consequence: at the top of the hour `worldRotation` can flip to its antipode
(`-q`). `q` and `-q` draw identical rings (the antipodal map sends every great circle to itself), ring
anchors and discs are built to be invariant under it; the only trace is that `sampleRing` vertex
indices shift by half a loop at that instant. Do not slerp between successive `worldRotation` values
without checking the dot product.

**Rotation in S3.** Left quaternion product `q * p`, p read as (w,x,y,z) = (x1,x2,x3,x4). An isometry,
so fibers stay great circles and stay linked once; test `anyTwoFibersLinkExactlyOnce` integrates the
Gauss linking integral for every checkable pair among the ten models + visitor at four instants
(tolerance 0.03, same handedness everywhere).

**Pole blow-up.** A ring's farthest projected point is at `R = sqrt((1+m)/(1-m))`, m = max x4 on the
circle = `sqrt(A4^2 + B4^2)`: closed form, no sampling. Points with R > `maxRadius` are pulled back
onto the sphere of that radius along their own direction; `ringAlpha` is 1 up to `fadeStartRadius` and
smoothsteps to 0 at `maxRadius`, so the clamped arc is never visible. For a tumbling fibration m^2 is
uniform on [0,1], so the fraction of time a ring is fading is `1 - ((F^2-1)/(F^2+1))^2` and gone
`1 - ((M^2-1)/(M^2+1))^2`: 22 % / 6 % with the API's 4 / 8, **15 % / 4 %** with 5 / 10. Raise both to
see rings more often (they then sweep closer past a camera at distance 9).

**Anchor.** `ringAnchor` is NOT a fixed theta (a fixed theta regularly flies off to the clamp sphere
while the ring is still visible). It is the ring's innermost point (smallest x4, always inside the
unit ball), biased by `anchorBias` towards the point with the largest x1 so it stays defined when the
ring is a perfect unit circle. Set `anchorBias` large (say 50) to get an almost fixed point instead.

**Visitor.** Mirrors the display world exactly (same doubles) until the first `perturb` or a `rho`
change; then it owns a `LorenzPoint` stepped once per display step with the visitor's rho. `perturb`:
epsilon = |magnitude| * `scaleFor(kind)`, floored at 1e-12, clamped to `maxPerturbation`, sign of the
magnitude; `SWITCH` is always exactly `switchEpsilon`; `BELL` kicks all three axes, other kinds one
axis (`ordinal % 3`). NaN/infinite magnitudes count as a switch flip. Divergence is slow on purpose:
e-folding time is about 44 s of page time (Lyapunov 0.9 x 0.025), so 1e-9 needs roughly 15 min to reach
order 1, a bell ring (1e-3) about 5 min. To make it faster raise `lorenzDt` (world-changing) or
`maxPerturbation` and the `scaleFor` table (local only). A backwards time jump of more than 5 s, or a
gap of more than 2 hours of steps, resets the visitor to the world.
The visitor base passes close to c = -1 at every lobe switch (x, y near 0 with z below the centre), so
the visitor ring swoops through the south-chart region about once a minute; that is intended.

**Ghost.** Same base-point mapping applied to the unperturbed display world state with `rhoDefault`.
Coincides with the visitor ring bit for bit until the first perturbation. `sampleRing` returns 0 and
`ringAlpha` 0 while `ghostEnabled` is false.

**Drift rate / reduced motion.** The canonical clock t is never touched: `worldState`,
`lobeSwitchCount` and disc births/deaths are the same for everyone. The knobs run a DISPLAY clock
`tau = t + offset`, `d(tau)/dt = driftRate * (reducedMotion ? 1/50 : 1)`, minimum 0.02/50 = 0.0004, never
0. `worldRotation`, the visitor and the ghost are evaluated at tau (a second `WorldTimeline`, created
only while offset != 0; creating it costs one catch-up, ~5-20 ms, on the first knob touch). When the
rate is back at exactly 1 the offset decays with a 20 s time constant (display rate kept inside
[0.02, 4]) and snaps to 0 below 5 ms, after which the visitor is back on the shared rotation.
`HopfLorenzWorld.displayOffsetSeconds` and `effectiveRate` expose this.

**Spin discs.** Deterministic per hour, PRNG stream salt 2. 2..5 at the start of the hour; at each
world lobe switch (sign change of x between two steps after the top of the hour) a coin decides spawn
or death, forced to spawn at the minimum and to die at the maximum. A dying disc fades for
`spinDiscFadeSeconds` and stays in the list meanwhile; the list never exceeds the maximum. During the
hourly hand-over the old hour's discs fade to 0 at the middle of the window and the new hour's fade in.
Per disc from the PRNG: near-ring, side, jitter, theta offset, fixed R3 orientation, period
`(7 + c) * 29.6`, phase, triad (ORIGINAL 0x0000ff / 0xff0000 / 0xffff00 as on the spin page, THEME =
renderer substitutes palette colours, WILD = three saturated hues about 120 degrees apart).
Placement: the disc rides on a NEIGHBOUR fiber, base point shifted 0.35 rad in latitude (the middle of
the gap between shelves) plus longitude jitter, at the ring's anchor theta + offset. Neighbouring
fibers are Clifford parallel, so the disc follows its ring around and can never touch it (measured
minimum gap 0.09 bloom units over 3 h, disc radius 0.128; renderers may want to draw discs a bit
smaller or accept the occasional graze). `position` is recomputed every `advanceTo`; `id` is stable for
the disc's life.

## The New Calendar

`NewCal.fromUnix(0)` -> `Earth, 12 Early Winter, 1970`. Two upstream bugs fixed (floor division before
1970; 21 December of non-leap years indexed past the season list) and listed in `NOTICE`. The upstream
Kotlin tests were ported; two of them could not have passed upstream (leap day written as day 0 while
the code uses -1) and use `LEAP_DAY` here.

## Not done / caveats

- Nothing here has been LOOKED at: all speeds, radii and the hand-over were tuned by numbers, not by
  eye. Expect to revisit `angularVelocityScale`, `fadeStartRadius/maxRadius`, `anchorBias`.
- Bit-identical results were verified on node (V8) only. The design guarantees it for any IEEE-754
  engine, but no other browser engine was run.
- The root CLAUDE.md asks for TrikeShed Series/Join; that library is not in this build, plain arrays
  and lists are used.
