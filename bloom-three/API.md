# bloom-three - API

three.js renderer for the Hopf bloom. Package `bar.verdantbloom.bloom.three`, depends on `:bloom-api` and
`:three-externals` only. Implements `bloom-api` `BloomRenderer` for the `webgpu` and `webgl` rungs with ONE
code path (`THREE.WebGPURenderer`, `forceWebGL = true` for the webgl rung). Developed against
`StubBloomWorld`; nothing here knows about Hopf or Lorenz, it draws whatever `BloomWorld.sampleRing` returns.

Status 2026-09-20: compiles, 40 node tests pass, and it was looked at in a real browser on BOTH backends
through the dev harness (section 7): WebGL2 and WebGPU draw the same picture, zero console errors or
warnings. It is NOT wired into `:site` yet (the site still runs the engine demo); that is the integrator's step.

## 1. Entry points

```kotlin
object BloomThree {
    fun create(kind: RendererKind): BloomRenderer?                                  // what :site calls. null only for CANVAS2D
    fun create(kind: RendererKind, options: BloomThreeOptions): ThreeBloomRenderer? // same, with knobs
    fun createSceneGraph(world, palette, options = BloomThreeOptions()): BloomSceneGraph   // embedded mode, section 6
}
```

`create` never touches the DOM or the GPU. Whether the rung can start is decided by `attach`, which resolves
`false` (never rejects) and removes everything it added when:
- kind is WEBGPU and `navigator.gpu` is missing (immediately, no canvas is created),
- kind is WEBGPU but three fell back to its WebGL2 backend (that is the NEXT rung's job, so this one says no),
- `WebGPURenderer.init()` rejects or the constructor throws (no WebGL2, no canvas, node),
- `dispose()` was called while init was pending, or `attach` is called a second time.

So the ladder is simply: `for (kind in RendererKind.ladderFrom(requested)) { r = create(kind) ?: continue; if (await r.attach(...)) break }`.

## 2. Frame contract (unchanged from bloom-api)

```kotlin
world.advanceTo(now)            // caller
renderer.view = BloomView(...)  // caller owns and tweens the camera
renderer.frame(now, dt)         // re-samples rings + discs, draws one frame
val a = renderer.cardAnchor(id) // then place DOM cards, CSS px relative to the host
```

- The renderer owns NO animation loop and adds NO event listeners. `frame` is synchronous.
- `attach` inserts the canvas as the FIRST child of the host (`position:absolute; left/top 0; width/height 100%`,
  class `vb-bloom-canvas`, `aria-hidden`). The host must be positioned; the renderer never styles the host.
- `resize(cssW, cssH, pixelRatio)`: call it on every host size change. `attach` does one initial
  `resize(host.clientWidth, host.clientHeight, min(devicePixelRatio, 2))` if you have not called it before.
- While the size is 0 x 0 (hidden pane / tab) `frame` returns at once: nothing is sampled, nothing is drawn.
- The canvas is opaque and cleared to `palette.ground`.
- CPU cost measured in the harness: about 1.6 ms per `frame` (12 rings x 128 segments, desktop).

## 3. How the rings are drawn (the technique, and why it works on both backends)

Plain 1 px lines are useless (`linewidth` is ignored everywhere) and `Line2`/`LineGeometry` cannot fade along
the thread, glow softly, or be updated without reallocating its interleaved buffer. So:

**Every ring is a camera-facing triangle ribbon built on the CPU** (`RibbonBuffers`, pure Kotlin).
Widths are given in CSS px and turned into bloom units per sample with the projector, so a thread keeps its
thickness at any zoom. To three it is one indexed `BufferGeometry` of ordinary triangles with four custom
attributes, shaded by `MeshBasicNodeMaterial` + TSL. No line primitives, no instancing, no `ShaderMaterial`:
nothing that differs between the WebGPU and the WebGL2 backend.

All 12 ring slots share ONE geometry, drawn three times (3 draw calls for all rings):

| pass | material | what |
| --- | --- | --- |
| casing | depth only, `colorWrite=false`, polygonOffset | thread + `casingPx` either side. Draws nothing, but a fiber BEHIND it fails the depth test there, so at a crossing the far fiber is interrupted: the over/under of the link reads like a knot diagram on any ground colour |
| glow | full ribbon width, bell falloff, depth tested, not written | additive at night; NORMAL blending by day (adding light to paper is invisible, so the day glow is an ink bleed in `palette.glow`) |
| thread | the solid core, anti-aliased in the shader from px distances, depth written | ring colour |

Buffers (`RibbonBuffers`), all Kotlin `FloatArray` = `Float32Array`, wrapped zero-copy, `DynamicDrawUsage`,
rewritten IN PLACE each frame, never reallocated (tested):

| attribute | size | content |
| --- | --- | --- |
| `position` | 3 | ribbon vertices |
| `vbShape` | 4 | across (-1/+1), arc 0..1, ribbon half width px, thread half width px |
| `vbColor` | 4 | LINEAR rgb + alpha |
| `vbTrim` | 2 | dashes per turn (0 = solid), glow boost |

Per ring `(segments + 1) * 2` vertices (the extra pair repeats sample 0 with arc = 1, so dashes have no seam)
and `segments * 6` indices. Defaults: 12 x 258 = 3096 vertices, 9216 indices (Uint32). `world.sampleRing`
writes straight into `buffers.centers`. The ghost is the LAST slot; when it is off it is left out of the
draw range.

Pole handling (SPEC 3.1), on top of whatever the world already clamps: non-finite samples are replaced by the
previous good one with alpha 0; every sample is clamped to `config.maxRadius`; alpha falls from 1 at
`config.fadeStartRadius` to 0 at `maxRadius`; a depth cue dims the far side (floor 0.42); threads closer than
0.3 units to the camera fade out. No NaN can reach the GPU (tested).

Two ribbon artefacts are handled explicitly: where a thread points straight at the camera the side vector
leans on its predecessor (`GRAZING_SIN`), and the glow is never wider than 0.85 x the local SCREEN radius of
curvature, so the hairpin ends of a ring seen edge-on taper instead of bursting into a star.

Known limit: offsets are perpendicular to the view RAY, so far off-axis a ribbon is up to `1/cos(angle)`
wider radially (about 15 % in the corner at fov 50). Invisible in practice.

## 4. Look

- Colours: `palette.ringColor(id, world.rings)` (shelf colour), nudged up to 28 % towards `palette.glow` by
  position within the shelf so the six WELL rings differ; the guest tap leans 40 % to `accent2`.
  `BloomSceneGraph.ringColorHex(id)` returns the colour actually used, for card accents.
- Visitor: thread 1.8 px half width (models 1.15), glow x1.35 wide and x1.7 strong, lighter at night.
  Ghost: 0.8 px, alpha x0.45, 48 dashes per turn, never pickable.
- `setLouche(0..1)` (default 0.35): glow half width 5 + 13 x louche px, glow gain, glow colour mix towards
  `palette.glow`, and above 0 a camera-facing milky veil behind the rings (gain ~ louche^2).
- `setHighlight(id)`: eased (about 0.25 s) thicker thread, wider / stronger glow, lighter at night, inkier by day.
- `ripple(0..1)`: decaying travelling wave along every ring (3 waves per turn, inner ring ids first, 60 ms
  apart, visible for about 4 s): radial displacement up to 4.5 %, plus width and brightness on the crests. A kick
  of 0 still rings faintly. Up to 4 ripples overlap. Under `world.reducedMotion` the displacement is x0.25.
- `setPalette`: recolours everything, switches glow blending for day / night, re-clears the canvas.
- Spin discs: pool of `spinDiscMax + 3` slots; each is fill + three 60-degree wedges centred on 0/120/240 +
  stroke annulus, NON-overlapping flat meshes (no z-fighting, visible from both sides), rotated by
  `phase + 2 pi (t - born) / period` (the modulo is taken before scaling, exact at Unix-time magnitudes).
  `TriadKind.THEME` uses palette accent / alert / warm; the other kinds use the colours in the `SpinDisc`.
  Discs fade with `disc.alpha`, towards `maxRadius`, and close to the camera. They write depth.

## 5. Picking, anchors, projection

- `pick(x, y, slopPx = 12)`: point-to-segment distance in CSS px against the projected centre lines of the
  last frame. Nearest ring within the slop wins; if two rings are within 3 px of each other the one in
  FRONT wins. Skips the ghost, hidden rings, samples with alpha < 0.06 and anything behind the camera.
  Pass 24 for touch.
- `cardAnchor(id)`: DEFAULT `AnchorMode.CAMERA_FACING` = a point ON the ring chosen by score (near the
  camera 1.0, close along the ring to `world.ringAnchor(id)` 0.6, on screen 0.8, not faded 0.5), with
  hysteresis 0.25 and a glide of at most 0.6 turns per second, so the card neither sits behind the bloom nor
  jumps. `AnchorMode.WORLD` = exactly `world.ringAnchor(id)` projected (the literal bloom-api wording;
  use it if cards must agree 1:1 with bloom-2d).
  `visible` = in front of the camera, within 200 px of the viewport, alpha > 0.02.
- INTEGRATOR: when flying the camera to a card, fly to the point the card is pinned to:
  `(renderer as? BloomAnchorSource)?.anchorPoint(id) ?: world.ringAnchor(id)`. (If you fly to
  `world.ringAnchor` instead, the camera-facing anchor converges to it anyway once the camera is near.)
- `project(point)`: same projection, `ringId = NONE`, `alpha = 1`.
- `Projector` is plain Kotlin and identical to the three camera (tested against `THREE.PerspectiveCamera`):
  camera at `target + orbit*(0,0,distance)`, up `orbit*(0,1,0)`, `pxPerUnit = h / (2 d tan(fov/2))`.

## 6. Embedded mode (a host that owns renderer, scene, camera and loop)

```kotlin
val bloom = BloomThree.createSceneGraph(world, palette)
hostScene.add(bloom.root)                 // any position / uniform scale
// per frame, BEFORE the host renders:
world.advanceTo(now)
bloom.updateFromCamera(hostCamera, cssWidth, cssHeight, now, dt)   // THREE.PerspectiveCamera
```

`BloomSceneGraph` has the same `setPalette / setLouche / ripple / setHighlight / pick / cardAnchor / project /
anchorPoint / dispose`. The host renderer must be a `WebGPURenderer` (node materials) with a depth buffer.
Tested on node with a scaled + translated group; NOT yet tried inside the engine's SpaceGraph scene.

## 7. Dev harness (not shipped)

`src/jsTest/.../DevHarnessTest.kt` + `dev/` - see `dev/README.md`. From `scratch/imarets`:

```
./gradlew :bloom-three:jsNodeTest          # also compiles the test module the page loads
python bloom-three/dev/serve.py            # 127.0.0.1:8937, Ctrl+C when done
http://127.0.0.1:8937/bloom-three/dev/index.html?renderer=webgl|webgpu&theme=day&ghost=1&louche=0.8&distance=5&still=1
```

## 8. Files

| file | what |
| --- | --- |
| `BloomThree.kt` | entry points |
| `ThreeBloomRenderer.kt` | `BloomRenderer`: canvas, WebGPURenderer, camera from `BloomView`, lifecycle |
| `BloomSceneGraph.kt` | all scene objects under one Group; styles, per-frame update, pick / anchors; `BloomThreeOptions` |
| `RibbonBuffers.kt` | PURE: buffer layout + sizing, ribbon building, pole clamp / fades (`RingStyle`) |
| `Projector.kt` `RingPicker.kt` `AnchorTracker.kt` `RippleField.kt` `DiscGeometry.kt` `ColorMath.kt` | PURE math, all unit tested |
| `RingLayer.kt` `VeilLayer.kt` `SpinDiscLayer.kt` | three objects + TSL materials |
| `TslExternals.kt` | `@JsModule("three/tsl") external object TSL` (three-externals is frozen). `three.tsl.js` imports only `three/webgpu`, so the bundle still holds one three |

## 9. Not done / not verified

- Not wired into `:site`; `:site:assemble` and `verifySingleThree` were NOT run with this module's
  `three/tsl` import (checked by reading `three.tsl.js`: its only import is `three/webgpu`).
- No automated GPU test. Visual checks were by eye in one desktop browser (Chromium, Windows): webgl and
  webgpu rungs, both themes, ghost, louche 0.35 / 0.8, bell ripple, highlight, pick at every card anchor.
  The Browser pane throttles requestAnimationFrame to a few fps, so MOTION (ripple travel, anchor glide,
  highlight easing) was seen only as stills; frame cost was measured by calling frame() 200 times (1.6 ms).
  Not tried: a phone, a HiDPI screen, Firefox / Safari, WebGL context loss, WebGPU device loss (three logs
  it; this module does not recover - the caller would have to dispose and attach a new renderer).
- Tuned against `StubBloomWorld` only. Real Hopf fibers near the pole have not been seen through it yet;
  widths, fades and the anchor weights are constants at the bottom of `BloomSceneGraph` / `RibbonBuffers`.
- `attach` resolving false for WEBGPU-without-adapter was exercised only on node (no `navigator.gpu`).
