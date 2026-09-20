# engine/attic - parked, NOT compiled

Nothing in this folder is part of any Gradle source set.

| file | why it is parked |
| --- | --- |
| `WebGPUInterface.kt` | `expect`-style interface from a never-finished commonMain. It imports `borg.trikeshed.lib.*`, a library that is not declared anywhere in this build (the old `src/js/settings.gradle.kts` pointed at `../../../Trikeshed`, outside the repo). |
| `WebGPUInterfaceWasm.kt` | Hand-rolled WebGPU bindings that declare WebGPU as an npm module (`@JsModule("webgpu")`). WebGPU is a browser API, not a package, so this can never link. Also depends on TrikeShed. |
| `agent_example.js` | Hand-written JS example for the agent API. SPEC section 0: Kotlin owns the engine; kept for reference only. |

WebGPU in this project comes from three.js instead: `THREE.WebGPURenderer` (see `three-externals`),
with `forceWebGL = true` for the webgl rung, so there is one rendering code path (SPEC section 4).

`engine/demo-resources/` holds the POC's original `index.html` / `style.css` (they reference a CDN import
map and are NOT shipped); `:site` has its own CSP-safe page.
