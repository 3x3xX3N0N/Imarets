# engine - public API (SpaceGraph ZUI port)

Module `:engine`, packages `com.example.spacegraphkt.*`. Kotlin/JS (IR, ES modules), depends on
`:three-externals` only. No GSAP, no CDN, one copy of three (`three/webgpu`, see CONTRACTS section 4).

```
core/   SpaceGraph, CameraController, UIManager, HtmlNodeElement, NoteNode, ShapeNode, Edge, ForceLayout,
        RendererFactory (GpuRung, GpuInitResult), Extensions (FrameListener, PickHook, PickEvent, PickPhase)
zui/    PURE Kotlin, no DOM, no three - unit tested on node:
        Tween (Easing, Tween, TweenGroup), HashRoute (Route, HashState, HashRouter), Tour (Tour, TourStop),
        Lod (LodLevel, LodThresholds, ViewMath, PinchTracker, TapSlop)
        DOM glue: ZuiNavigator.kt (HashRouterBinding, ZuiNavigator)
external/ VecTween (binds zui.Tween to THREE.Vector3), generateId
main/   runEngineDemo
```

Build / test: `./gradlew :engine:compileKotlinJs`, `./gradlew :engine:jsNodeTest` (38 tests, results in
`engine/build/test-results/jsNodeTest/`).

## 1. Creating a graph

```kotlin
val graph = SpaceGraph(
    containerElement = host,                       // positioned element; the engine adds a canvas + a CSS3D layer
    options = SpaceGraphOptions(
        readOnly = true,                           // landing page mode, see section 2
        layoutEnabled = false,                     // the host positions nodes (cards hung on bloom rings)
        defaultLighting = false,
        lod = LodThresholds(520.0, 1100.0),
    ),
    forceWebGL = kind != RendererKind.WEBGPU,      // true = webgl rung
)
graph.ready.then { result -> if (!result.ok) { graph.dispose(); /* next rung */ } }
```

`SpaceGraphOptions` (all defaulted): `uiElements`, `layoutSettings`, `backgroundColor`, `backgroundAlpha`,
`readOnly = false`, `draggableNodes = !readOnly`, `focusOnTap = readOnly`, `layoutEnabled = true`,
`lod = LodThresholds()`, `defaultLighting = true`, `maxPixelRatio = 2.0`.

The engine sets `touch-action: none` on the container (and clears it on dispose). Give the container a size in
CSS; the engine follows it with a ResizeObserver. Nothing is drawn on the GPU while the container is 0x0.

`dispose()` is idempotent, removes every listener the engine added, removes its canvas / CSS3D layer, and puts
adopted elements back where they were.

## 2. readOnly mode

With `readOnly = true`:

| gone | still works |
| --- | --- |
| context menus (the browser's own menu is not suppressed) | pan (drag), wheel zoom, pinch zoom, two-finger pan |
| Delete / Backspace, Enter-to-edit, +/- resize, Space (the page keeps these keys) | click / tap selects a node (`.selected`, `aria-current`) |
| link drawing, edge menu | `focusOnTap`: click / tap flies to the node |
| contenteditable, node control buttons, resize handles (removed from the DOM) | Esc = view history back, then deselect |
| node dragging (`draggableNodes`) - dragging on a card pans instead | `flyTo`, `back`, `reset`, deep links, tour |
| editor chrome: `#sg-context-menu`, `#sg-confirm-dialog`, `#sg-status-indicator` are never added to the document; `showStatus` is a no-op | links, buttons and form fields INSIDE a card work natively |

Inside a card these elements are left to the browser (no pan, no tap handling):
`a[href], button, input, select, textarea, label, summary, [data-sg-interactive]`. An element with
`data-sg-scroll` that actually overflows keeps its own wheel scrolling.

`AgentAPI` is not restricted by `readOnly` (it is a programmatic API, not visitor input). Do not expose it on
`window` in production; `runEngineDemo` does, the site must not.

## 3. Camera, tween, view history

`graph.cameraController : CameraController` (driven from the graph's single frame loop).

```kotlin
graph.flyTo(node | nodeId, duration = 0.6, select = true, onArrive = null): Boolean   // pushes view history
graph.back(duration = 0.6): Boolean          // what Esc does; false when the history is empty
graph.reset(duration = 0.7)                  // overview, history cleared, nothing selected
graph.centerView(target = null, duration)    // centroid of all nodes
graph.fitDistanceFor(node, padding = 1.25)   // width-limited in portrait (375 px), height-limited on desktop
graph.autoZoom(node)                         // toggle: fly to node / back

camera.moveTo(x, y, z, duration, lookAt, onArrive)     // eased (ease-out cubic) move of the camera TARGETS
camera.flyTo(lookAt, distance, nodeId, duration, pushHistory, onArrive)
camera.back() / popState() / pushState() / resetView() / reset() / snap()
camera.canGoBack, camera.viewHistory (max 20), camera.currentTargetNodeId
camera.addTargetListener { nodeId: String? -> }        // fires on focus, back, reset, and null when the visitor pans / zooms away
camera.panByPixels(dx, dy); camera.dollyBy(scale, clientX, clientY)   // what touch gestures call
camera.easing = Easing.OUT_CUBIC; camera.dampingFactor = 0.12 (per 1/60 s, frame-rate independent)
camera.reducedMotion = true                            // programmatic moves cut instead of flying
```

Tween (pure, `zui/Tween.kt`): `Easing.{LINEAR, OUT_QUAD, OUT_CUBIC, OUT_QUART, IN_OUT_CUBIC}`;
`Tween(from, to, startMs, durationMs, easing).sample(nowMs, out): Boolean` is a pure function of `nowMs` and
lands EXACTLY on the end value; `TweenGroup<K>` = keyed set with overwrite / kill / onDone, keys compared by
identity (THREE.Vector3 has a value `equals`, which must not merge two vectors' tweens).

## 4. Semantic zoom (LOD)

Every frame each `HtmlNodeElement` measures its camera distance and sets `data-lod="far" | "mid" | "near"` on
its element (written only when it changes). CSS does the rest:

```css
.node-html[data-lod="far"]  .card-body  { display: none; }   /* order code only */
.node-html[data-lod="near"] .card-short { display: none; }   /* full card */
```

`LodThresholds(nearDistance = 520, midDistance = 1100, hysteresis = 0.08)`: `<= near` NEAR, `<= mid` MID, else
FAR; with a previous level a boundary only counts once crossed by the hysteresis fraction (no flicker).
Defaults: overview camera (700) = MID, a focused card (roughly 200-450) = NEAR.
Per graph: `graph.lodThresholds`; per node: `node.lodThresholds`. Read: `node.lod`, `node.cameraDistance`.
Observe: `graph.addLodListener { node, level, previous -> }`. Force: `node.applyLod(level)`.

## 5. Hash router and tour

Pure (`zui/HashRoute.kt`):

```kotlin
sealed class Route { Home; Pour(id); Tab; PourList; Sign }      // "", #pour/<id>, #tab, #list, #sign
data class HashState(route, t: String?, extras: List<Pair<String,String>>)   // tSeconds: Double?
HashRouter.parse(hash): HashState      // never throws; bad route / id / t are dropped
HashRouter.emit(state): String         // canonical: "#<route>&t=<t>&<extras>", "" when empty
HashRouter.navigate(currentHash, route): String   // keeps t and extras
```

`t=<unix>` coexists with every route in any order on input (`#t=0`, `#pour/mini&t=86400`, `#t=5&list`), and is
emitted after the route, which is the syntax bloom-api `TimeOverride.parse` reads. `t` is kept as its original
text, so re-emitting is lossless. Ids are limited to `[A-Za-z0-9._-]{1,64}`.

DOM (`zui/ZuiNavigator.kt`): `HashRouterBinding(onChange).start() / navigate(route, replace) / state / dispose()`
uses `history.pushState`, so the browser Back button walks the same trail as Esc; `onChange` fires for
Back / Forward / hand-edited hashes / `<a href="#pour/mini">`, not for its own `navigate`.

Tour (pure, `zui/Tour.kt`) - what a DOM tour bar drives:

```kotlin
val tour = Tour(listOf(TourStop(id = "card-mini", title = "W01 MINI", route = Route.Pour("mini")), ...), wrap = true)
tour.next(); tour.prev(); tour.goTo(index | id); tour.leave()      // notify listeners on real moves
tour.current; tour.index (-1 = overview); tour.hasNext; tour.hasPrev; tour.positionLabel  // "3 / 10"
tour.sync(id)                       // follow without notifying (visitor got there another way)
tour.addListener { stop, index -> }        // NAVIGATION: the tour itself moved (not on sync) -> fly the camera
tour.addChangeListener { stop, index -> }  // DISPLAY: every change incl. sync -> repaint the tour bar
```

`ZuiNavigator(graph, tour).start()` wires the three together (tour stops are the node <-> route map):
tour move -> fly + URL; click on a node -> tour + URL follow; deep link / Back / Forward -> fly + tour follow;
Esc to the overview -> route leaves the URL (`t=` stays). `navigator.home()`, `navigator.dispose()`.
Tour bar buttons only need `tour.prev()`, `tour.next()` and an `addChangeListener` that writes `stop.title` with `textContent`.

## 6. Renderer factory (SPEC section 4)

```kotlin
enum class GpuRung { WEBGPU, WEBGL }   // GpuRung.fromQuery("webgpu")
RendererFactory.webGpuAvailable(): Boolean                       // navigator.gpu present
RendererFactory.create(canvas, rung, antialias, alpha): THREE.WebGPURenderer?   // null if three throws
RendererFactory.init(renderer, rung): Promise<GpuInitResult>     // NEVER rejects; 10 s timeout
class GpuInitResult(ok, requested, backend: "webgpu"|"webgl2"|"none", fellBack, reason, error)
```

Always `THREE.WebGPURenderer`; the webgl rung is `forceWebGL = true`. `SpaceGraph` uses the factory itself:
`graph.ready: Promise<GpuInitResult>`, `graph.gpuInit`, `graph.gpuReady`, `graph.gpuFailed`, `graph.rung`.
`ok = false` is the clean fallback signal (dispose, start the next rung). `fellBack = true` means WEBGPU was
asked for and three started its WebGL2 backend instead: still one working code path. HTML nodes stay on
`CSS3DRenderer` (`graph.cssRenderer`, `graph.cssScene`) and work even when the GPU rung is dead.

## 7. Extension points for bloom-three

```kotlin
graph.scene: THREE.Scene            // add ring / disc objects here; engine units = CSS px at scale 1
graph.cssScene: THREE.Scene         // CSS3D objects
graph.camera: THREE.PerspectiveCamera   (also `_camera`)
graph.gpuRenderer: THREE.WebGPURenderer?   graph.gpuCanvas
graph.addFrameListener { dtSeconds, nowMs -> }   // after the camera moved, before nodes update and the draw; returns a remover
graph.renderOverride = { renderer, scene, camera -> postProcessing.render() }   // replaces the plain render call
graph.addPickHook { e: PickEvent -> Boolean }    // asked BEFORE the engine's node / edge picking; returns a remover
```

`PickEvent(phase, x, y, pointerType, slopPx, pointerId, nodeUnderPointer, original)`; `x`/`y` are CSS px relative
to the container = the frame of bloom-api `BloomRenderer.pick(x, y, slopPx)`; `slopPx` is 12 (mouse) / 24 (touch).

| phase | return true means |
| --- | --- |
| `TAP` | "mine": the engine does not select / fly / deselect. The usual ring picker: `nodeUnderPointer == null && renderer.pick(e.x, e.y, e.slopPx) != RingIds.NONE` then `graph.flyTo(cardNodeId)` |
| `DOWN` | claim the whole gesture: the engine ignores this pointer (no pan, no drag) and forwards `MOVE`, `UP`, `CANCEL` to that hook only |
| `HOVER` | something of yours is under the mouse: the engine skips its edge hover highlight (use it for `setHighlight`) |

Adopting EXISTING DOM (progressive enhancement):

```kotlin
val node = graph.adoptElement(element, Vector3D(x, y, z), id = element.id, width = null, height = null, billboard = true)
node.setPosition(x, y, z)           // move it every frame from a frame listener if it hangs on a ring anchor
graph.removeNode(node.id)           // or graph.dispose(): the element returns to its original parent, position,
                                    // inline style, classes and attributes (HtmlNodeElement.release())
```

The element is not cloned: CSS3DRenderer moves it into its layer. It gets classes `node-html node-adopted`,
`data-node-id`, `data-lod`, `pointer-events: auto`; its width is pinned to the measured width, its height stays
`auto` unless given. `HtmlNodeElement.adopt(...)` is the same without adding to a graph.

## 8. Touch

Pointer events only. One finger: pan (a tap within 12 px selects / flies). Two fingers: pinch zoom about the
midpoint + pan by the midpoint (`PinchTracker`), never a tap afterwards. `pointercancel` is handled. Wheel and
touch are only intercepted ON the container; nothing at window level is `preventDefault`ed, so the rest of the
page scrolls normally. Works at 375 px: `fitDistanceFor` is width-limited in portrait.

## 9. CSP

No `innerHTML` / markup strings, no inline handlers, no `setAttribute("style")`, no `eval`. Elements are built
with `createElement` + `textContent`; styles go through CSSOM properties (`release()` restores an adopted
element's inline style through `style.cssText`, which is CSSOM too). `NodeData.content` is TEXT.

## 10. Browser harness

`engine/harness/` + `src/harness/kotlin` run the engine alone under a strict CSP. Opt-in only:
`./gradlew :engine:jsBrowserProductionWebpack -PengineHarness` (see `engine/harness/README.md`). Without the
property `:engine` is a plain library and none of it is compiled or bundled into `:site`.

## 11. Verified / not verified (honest list)

Verified by the engine agent in the desktop app's browser pane, 2026-09-20, through the harness, with
`Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'` and no console errors:
webgl rung (`backend=webgl2`) and webgpu rung (`backend=webgpu`) both start; read-only mode has no editor
chrome / contenteditable / controls; tour next -> fly + `#pour/<id>` + selection + `data-lod="near"`;
deep link `#pour/dark&t=0` on load (t survives every navigation); Esc -> back -> overview; Space not taken in
read-only; synthetic touch tap -> select + fly; synthetic two-finger pinch zooms (680 -> 299); drag on a card
pans; wheel is prevented over the graph and not over the document; pick hook sees DOWN / TAP and a claimed TAP
leaves the selection alone; `<a href="#pour/mini">` inside an adopted card navigates, browser Back returns;
`dispose()` puts the adopted card back (parent, sibling order, inline style, classes, attributes) and is
idempotent; 375 px wide layout: a focused card is 300 px wide.

NOT verified: a real touch screen (touch was synthetic PointerEvents), real-device pinch feel, Safari / Firefox,
the WebGPU-unavailable path (`fellBack` / `ok = false` were not provoked), context menus / link drawing /
resize handles in edit mode (they compile and the chrome appears, nothing more), AgentAPI, edge labels.
Node tests cover only pure logic (tween, router, tour, LOD, view maths, pinch maths): 38 tests.

Known behaviour gaps:
- After a pinch, the finger that stays down does not resume panning until all fingers are lifted.
- `ZuiNavigator` maps routes through tour stops only; `#list` / `#sign` need a stop or the site's own handling.
- `touch-action: none` on the container means a graph that fills the phone screen cannot be scrolled past by
  dragging ON it; give the page scrollable space outside the container or a smaller container.
- `VecTween` is a process-wide singleton: fine for one graph, two graphs share one tween clock.
- ForceLayout still logs to the console (info level) in editor mode.
