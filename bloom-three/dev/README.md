# bloom-three dev harness (never shipped)

A way to LOOK at the renderer before :site wires it in, without adding an executable target to a library
module and without any hand-written JS.

How it works: `src/jsTest/.../DevHarnessTest.kt` is an ordinary test class. On node it returns at once (no
`document`). `index.html` loads the compiled TEST module straight from `build/js` as ES modules (an import map
points `three/webgpu` and `three/tsl` at `build/js/node_modules/three/build`). In a browser kotlin-test finds
no test framework and just runs every test once; the harness test sees `#bloom-three-dev` and starts a
`StubBloomWorld`, the renderer ladder (webgpu -> webgl) and a requestAnimationFrame loop. Because it lives in
jsTest it cannot reach the production bundle.

```
cd scratch/imarets
./gradlew :bloom-three:jsNodeTest        # compiles build/js/packages/imarets-bloom-three-test/kotlin/*.mjs
python bloom-three/dev/serve.py          # 127.0.0.1:8937 only; Ctrl+C when done
```

Open `http://127.0.0.1:8937/bloom-three/dev/index.html` with any of:

| query | effect |
| --- | --- |
| `renderer=webgl` (default) / `renderer=webgpu` | rung to start the ladder at |
| `theme=day` | NIGHTSHADE HERBARIUM fallback palette (default is ABSINTHE ABYSS) |
| `ghost=1` | ghost ring on |
| `louche=0.8` | louche at start |
| `distance=5` | camera distance |
| `still=1` | no auto orbit |

Mouse: drag = orbit, wheel = zoom, hover = pick + highlight, click = pick + ripple.
Keys: `b` bell, `g` ghost, `l` louche +0.25, `t` theme, `o` auto orbit.
`window.__bloomThreeDev` is the ThreeBloomRenderer (Kotlin-mangled method names).

The inline import map is the one thing the shipped page may never have (CSP); an import map cannot be an
external file, and this page is not part of any bundle.
