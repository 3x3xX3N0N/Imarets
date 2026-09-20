# engine harness

Runs the engine alone in a browser, under a strict CSP (`script-src 'self'; style-src 'self'`), without :site.

```
cd scratch/imarets
./gradlew :engine:jsBrowserProductionWebpack -PengineHarness
cp engine/build/kotlin-webpack/js/productionExecutable/engine.js* engine/harness/     # engine.js is git-ignored here
cd engine/harness && python -m http.server 8942 --bind 127.0.0.1                      # stop it afterwards
```

Open `http://127.0.0.1:8942/index.html` (`?mode=edit` for the editor, `?renderer=webgpu` for the WebGPU rung,
`#pour/dark` for a deep link). `window.sgHarness` has `graph`, `tour`, `navigator`, `flyTo(id)`, `back()`,
`reset()`, `lods()`, `target()`, `history()`, `picks`, `frames()`, `dispose()`. Set `window.sgClaimTaps = true`
to let the demo pick hook take taps on empty space.

Without `-PengineHarness` nothing here is compiled and `:engine` stays a plain library.
