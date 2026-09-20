# bloom-2d - the no-WebGL rung

Package `bar.verdantbloom.bloom.canvas2d`, depends on `:bloom-api` only (no three, no engine).
Implements `BloomRenderer` with Canvas2D and ships an optional self-contained 2D ZUI (camera, input,
card placement) so `?renderer=2d` is an interactive page, not a picture.

```
./gradlew :bloom-2d:compileKotlinJs
./gradlew :bloom-2d:jsNodeTest          # 64 tests, node only, no DOM / canvas needed
```

## 1. Entry point (signature unchanged)

```kotlin
Bloom2d.create(): BloomRenderer?            // Canvas2dRenderer, or null when there is no DOM / no Canvas2D at all
Bloom2d.createCanvas2d(): Canvas2dRenderer? // same object, concrete type (for flyTo / enableOwnCamera)
Bloom2d.isSupported(): Boolean
```

`create()` returning non-null only means "a browser with Canvas2D". Whether a context can really be had is
decided in `attach`, which resolves `false` (never rejects) after removing anything it added.

## 2. Contract behaviour (`Canvas2dRenderer : BloomRenderer`)

| member | what it does here |
| --- | --- |
| `kind` | `RendererKind.CANVAS2D` |
| `attach(host, world, palette)` | Disposes any previous attachment. Adds TWO canvases as the FIRST children of `host`, so DOM cards the site appends stay on top: `canvas.vb-bloom-2d-glow` (quarter resolution, underneath) and `canvas.vb-bloom-2d` (crisp lines). Both `position:absolute; left:0; top:0; width:100%; height:100%`, `aria-hidden="true"`, styled through style PROPERTIES only. `host` itself is never styled: give it `position: relative` (or fixed / absolute) and a size. Sizes itself from `host.clientWidth/Height` and `devicePixelRatio` capped at 2; call `resize` to override. |
| `resize(w, h, pixelRatio)` | Backing store = CSS size x ratio (ratio clamped to 0.5..4). `0 x 0` is legal: `frame` then draws nothing and anchors report `visible = false` (hidden pane, no GL-style warnings possible anyway). |
| `view` | Same numbers as the three rungs: camera at `target + orbit*(0,0,d)`, up `orbit*(0,1,0)`, `pxPerUnit = h / (2 d tan(fov/2))` at the target plane. True perspective (nearer = bigger), not orthographic. Assigning a value EQUAL to the current one is a no-op (so `r.view = r.view` never cancels a tween). |
| `frame(t, dt)` | Caller has done `world.advanceTo`. Samples 12 rings, applies the ripple, projects, depth-sorts, paints. `dt` also drives the ripple, the own camera (if enabled) and the quality governor. |
| `setPalette`, `setLouche(0..1)`, `setHighlight(ringId)` | Louche 0 switches the glow pass off completely (cheapest mode). Highlight: ring x1.7 width, others x0.7 alpha, its glow boosted. |
| `ripple(0..1)` | Spherical wave packet from the bloom centre (3.2 units/s, dies in 4.5 s, up to 4 overlapping). Points are pushed along their radius by up to 12 %; card anchors ride the same wave. |
| `pick(x, y, slopPx)` | CSS px relative to host. Rule: a DIRECT hit (within 4 px of a line) -> the ring NEAREST IN DEPTH among direct hits (what you see on top is what you get); otherwise the closest line within `slopPx`. Ghost never, disabled rings never, rings with alpha < 0.05 never, segments behind the camera never. Uses the arrays of the last `frame` (returns NONE before the first). 2816 point-segment tests, no allocation: fine on every pointermove. |
| `cardAnchor(ringId)` / `project(p)` | `x, y` CSS px relative to host; `pxPerUnit` at the anchor's OWN depth (drives semantic zoom); `depth` camera-space; `alpha` = `world.ringAlpha` x a depth fade (1.0 near .. 0.45 far); `visible` false when behind the camera, more than 160 px off screen, ring disabled, host 0 x 0, or not attached. |
| `dispose()` | Removes both canvases and any input listeners. Idempotent. |

Renderers add no navigation listeners: true here. Nothing below runs unless the host opts in.

## 3. Optional 2D ZUI (opt-in, not part of bloom-api)

The site's input layer may drive `view` for every rung and ignore all of this. If it wants the ready-made one:

```kotlin
val r = Bloom2d.createCanvas2d() ?: return
r.attach(host, world, palette).then { ok -> if (ok) {
    r.enableOwnCamera(host, onRingTap = { ring -> history.push(ring); r.flyTo(ring) })   // binds pointer + wheel to host
    r.camera.homeDistance = OrbitCamera.distanceToFit(3.0, host.clientWidth.toDouble(), host.clientHeight.toDouble())
} }
// per frame: world.advanceTo(now); r.frame(now, dt)   <- frame() advances the camera and adopts its view; read r.view if needed
```

- `enableOwnCamera(inputElement = host, onRingTap, onHoverRing): OrbitCamera` - `frame()` then runs the camera.
  `inputElement = null` = no listeners, feed the camera yourself. Default tap = `flyTo(ring)`, default hover = `setHighlight`.
- `flyTo(ringId, seconds = 0.9, distance = camera.focusDistance /* 2.4 */)` - power3 in-out tween of target, log-distance
  and orbit (the camera ends on the ring's side of the bloom, shortest arc, no surprise roll), then FOLLOWS the
  drifting anchor with zero lag. `seconds = 0` jumps: pass that when reduced motion is on.
- `flyHome(seconds)`, `disableOwnCamera()`, `ownInput` (the `Zui2dInput`, e.g. `ownInput?.onActivity = { px_s -> world.perturb(POINTER, px_s) }`).
- "Last writer wins": assigning a different `view` while the own camera is on makes the camera adopt it and drops tween / follow.

`OrbitCamera` (pure maths over `BloomView`, no DOM, usable for the three rungs too):
`rotateBy(dx, dy, dt)` trackball (full-height drag = half a turn), `panBy`, `zoomAt(factor, cx, cy)` keeps the point
under the cursor fixed, `wheel(deltaY, deltaMode, cx, cy)`, `pinch(prevSpan, span, prevCx, prevCy, cx, cy, twist)`
zoom + pan + roll, `grab()` / `release()` with inertia (decays in 0.32 s, no fling if the pointer rested > 90 ms),
`flyTo(anchor: () -> Vec3, ...)`, `flyHome`, `jumpTo(view)`, `update(dt): Boolean`, `view` (re-allocated only when changed),
`isFlying`, `isFollowing`, `isAnimating`, `minDistance` 0.6, `maxDistance` 40,
`OrbitCamera.distanceToFit(radius, w, h, fov, margin)`.

**Portrait gotcha:** the BloomView scale is defined by the viewport HEIGHT. At 375 x 812 the default distance 9
lets a radius-2.6 ring spill over both sides. Use `distanceToFit` for the overview distance (the demo does).

`Zui2dInput(element, camera, onTap, onHover, onActivity)`: one pointer = rotate, shift / middle / right drag = pan,
wheel = zoom at cursor (preventDefault, non-passive), two pointers = pinch + twist, third pointer ignored.
Tap = moved < 5 px (mouse) / 12 px (touch) within 600 ms, never after a multi-touch; reports element-relative px and
the slop to hand to `pick` (12 / 24). Sets `touch-action: none` on the element while attached and restores it on `detach()`.

`Cards2d` (works with ANY rung's `CardAnchor`): `place(card, anchor, offsetX, offsetY)` sets `style.transform`
(`translate3d(x,y,0) scale(s) translate(-50%,-50%)`, expects `position:absolute; left:0; top:0; transform-origin:0 0`),
`opacity`, `zIndex` (nearer on top), toggles `hidden`, and writes `data-detail="code|title|full"` only when it changes.
Thresholds: title from 95 px/unit, full from 210, 8 % hysteresis; scale = pxPerUnit / 260 clamped to 0.4..1.
No style attribute strings anywhere (CSP).

`Bloom2dDemo.start(host, world = StubBloomWorld(), palette, onReady)`: reference wiring + the harness used for the
browser check (rAF loop, resize, labels via Cards2d, own camera). Not referenced by :site, so DCE drops it.
Its `Handle` has JS-named methods: `stop, ring, flyTo, home, louche, highlight, ghost, pin, bench, diagnostics`.

## 4. How it draws

1. **Chunks.** Each ring (default 256 samples; `renderer.segments` to change) is cut into 32 chunks of 8 segments.
   Chunks and spin discs are the items of a painter's algorithm, sorted far to near by mean camera depth with an
   insertion sort over a PERSISTENT order array (nearly sorted every frame, so about linear).
2. **Gaps at crossings.** Per chunk: erase a wider "casing" (`destination-out`, butt caps, 3 px + 0.6 x width per side),
   then stroke the line (round caps). The nearer ring therefore interrupts the farther one - the knot-diagram
   convention - and the single linking of each pair of fibers can be read off. The canvas stays TRANSPARENT (the
   page ground shows through), which is why gaps are cut rather than painted in a ground colour. A coarse screen
   grid (24 px cells of ring bit masks) marks chunks that come near ANOTHER ring; only those get a casing.
   That saves little at the overview (the bloom crosses itself everywhere) and a lot when zoomed in.
3. **Depth cue.** Nearer = wider (x0.6..2.4) and more opaque (x0.3..1).
4. **Visitor ring**: wider, its own colour, plus a bead travelling round it every 16 s. **Ghost ring**: thin, dashed,
   cuts no gaps, takes no part in picking, only when the world returns samples for it.
5. **Spin discs**: true affine image of the tilted disc (`setTransform` from the disc orientation and the view), geometry
   from the original page: circle r, stroke 0.25/12.8 of r (min 0.75 px), three 60 degree wedges at 0/120/240 that are
   TRIANGLES (straight chord, as `generateWedgeString` in the original, not the arc variant). `TriadKind.THEME` takes
   palette accent / alert / warm, the others use the colours carried in `SpinDisc`. Discs are depth-sorted with the
   chunks, so rings pass in front of and behind them and cut gaps into them.
6. **Louche** = the glow canvas underneath: quarter resolution, every 4th sample, upscaled by the browser's
   compositor (free, and the upscale is the blur). STANDARD: two translucent strokes per ring + a radial haze at the
   bloom centre. RICH: `shadowBlur` inside the small canvas. LEAN: one stroke, refreshed every other frame.
   If the second canvas cannot be made the glow is drawn into the main canvas with `destination-over`.
   The glow layer deliberately contains nothing line-shaped: it shows through the gaps, and a tight stroke there
   bridged them (seen in the browser, fixed).

Quality (`renderer.governor`, `QualityGovernor`): starts STANDARD; RICH after 150 consecutive frames under 19 ms, at
most once; one level down after 40 frames over 26 ms; LEAN = 128 samples, 16 chunks, backing store x0.75 when
pixelRatio > 1; LEAN recovers after 600 smooth frames. Intervals over 250 ms are ignored. `governor.pinned = Quality2d.X`
switches adaptation off. `renderer.diagnostics()` gives one line for a HUD.

## 5. Measured (this machine, Chromium pane, 1280 x 720 @1, StubBloomWorld, 11 rings x 256)

- GPU-backed canvas, real rAF: 13-16 ms frame interval (display rate) at STANDARD.
- Forced SOFTWARE canvas (`willReadFrequently`) with a 1 px read-back per frame to force completion, mean of 150 frames:
  no glow 7.5-8.6 ms, LEAN 6.4-7.2 ms, STANDARD 7.5-8.9 ms, RICH 7.7-9.7 ms; zoomed in (long wide lines) STANDARD 12.1 ms,
  LEAN 10 ms. Before the glow moved into its own low-resolution canvas STANDARD was 23 ms and RICH 95 ms.
- Allocation (node 24, `HotPathAllocationTest`, 3000 frames): about 0.3 KB/frame after subtracting the meter's own
  cost - a few dozen V8 heap numbers from double arguments of per-ring helper calls. Per-sample and per-chunk paths: zero.
  It was 18.7 KB/frame until two V8 boxing traps were removed; see the comment in `DepthSort.sortFarToNear`. Rule of thumb
  for this module: never write `var d = array[i]; if (cond) d = CONSTANT` or `x = if (ok) value else Infinity` in a hot
  loop; use two stores or `min` / `max`.
- NOT in the hot path and allocating by design: `cardAnchor` / `project` return a `CardAnchor` (API), the own camera makes
  a few `Quat` / `Vec3` / one `BloomView` per frame WHILE it moves, `Cards2d.place` builds a transform string.

## 6. Tests (`src/jsTest`, all on node)

`ProjectionTest` (contract scale, axes, near plane, orbit, matrix vs quaternion on random views, denormalised orbit,
array vs point projection, quaternion helpers), `DepthSortAndSceneTest` (stable far-to-near sort, persistent order,
chunk depth, **a Hopf link alternates over / under at its screen crossings** at 64 and 256 samples, casing flags are set
at every crossing and nowhere near a lonely ring, NaN samples are culled, disc affine frame, ripple is radial and ends),
`PickTest` (slop, direct hit prefers nearest depth, near miss prefers closest line, faded / ghost / behind-camera never),
`OrbitCameraTest` (drag directions, half turn per height, pan 1:1, zoom keeps the cursor point, wheel modes and clamps,
pinch + twist, inertia decays and stops, flyTo monotonic then follows a moving anchor, reduced-motion jump, flyHome,
interrupted flight has no jump, distanceToFit in portrait and landscape), `InputAndHelpersTest` (synthetic pointer
events: tap vs drag vs shift-pan vs pinch vs third finger, wheel, touch-action restore; Cards2d; Css; governor; spin disc
geometry), `PainterAndRendererTest` (recording fake context: casing/line pairing, state left clean, glow pass per
quality, glow layer, ghost dashed, highlight, discs; fake host: attach false without a context, 375 x 667 @3 end to end
incl. anchors, pick, 0 x 0, flyTo, view hand-over, dispose), `HotPathAllocationTest`.
Async tests are really awaited (checked by mutation).

Checked by hand in the Browser pane with the demo harness (not automated): draws at 1280 x 720 and 375 x 812 @2 in both
palettes, zero console errors, wheel zoom at cursor, click -> fly to ring -> card centred and `data-detail="full"`,
real `PointerEvent`s of type touch for pinch and one-finger rotate, ripple / ghost / highlight, 0 x 0 hidden pane.

## 7. Not done / caveats

- Developed against `StubBloomWorld` and a two-ring Hopf link test world only. Not yet run against the real bloom-core
  world (near-pole blow-up, rings with alpha fading to 0, 8-unit radii). Nothing in the code depends on the stub.
- No real phone was used: touch was tested with synthetic PointerEvents headlessly and in the desktop Chromium pane.
- The pane throttles rAF when hidden, so real-rAF numbers above are from short visible moments; the software-canvas
  numbers are the dependable ones. In that throttled pane the governor drops to LEAN (its 26-250 ms samples look slow);
  that is an artefact of the pane, a truly hidden tab gets no frames at all.
- Chunk-level sorting: two rings that cross on screen with less depth separation than a chunk is deep could be ordered
  wrongly for a frame. Linked fibers are well separated where they cross (test asserts > 0.2 units), so this has not
  been seen; raise `Scene2d.TARGET_CHUNKS_PER_RING` if it ever is.
- The painter draws no text: labels and cards are DOM, by design.
- `Bloom2dDemo` and its harness page are developer tools; the harness HTML lives outside the repo (scratchpad).
