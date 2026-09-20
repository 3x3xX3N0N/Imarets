// ONE copy of three (SPEC 4).
// Kotlin externals import "three/webgpu" (build/three.webgpu.js). The addons shipped with three
// (CSS3DRenderer, Line2, ...) import bare "three" (build/three.module.js). Both builds share three.core.js
// but each carries its own renderer code. Point bare "three" at the webgpu build so every import
// resolves to the same module instance. `three$` = exact match only, so "three/webgpu", "three/tsl"
// and "three/addons/..." are untouched.
config.resolve = config.resolve || {};
config.resolve.alias = Object.assign({}, config.resolve.alias, {
    'three$': require.resolve('three/webgpu'),
});
