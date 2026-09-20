@file:Suppress("unused")

package bar.verdantbloom.bloom.three

/**
 * The few TSL (three shading language) builders bloom-three needs. three-externals is frozen, so these
 * live here, as CONTRACTS.md section 4 asks. "three/tsl" itself imports "three/webgpu", so this adds no
 * second copy of three to the bundle.
 *
 * Every builder returns a node object; nodes chain with methods (`.mul()`, `.sub()`, `.x`, ...), which
 * is why they are typed `dynamic`.
 */
@JsModule("three/tsl")
external object TSL {
    /** Geometry attribute by name; [type] is "float", "vec2", "vec3" or "vec4". Usable in the fragment stage (auto varying). */
    fun attribute(name: String, type: String = definedExternally): dynamic

    /** Uniform from a number, THREE.Color, THREE.Vector*. Update through `.value`. */
    fun uniform(value: dynamic, type: String = definedExternally): dynamic

    fun float(value: dynamic): dynamic
    fun vec3(x: dynamic, y: dynamic = definedExternally, z: dynamic = definedExternally): dynamic
    fun vec4(x: dynamic, y: dynamic = definedExternally, z: dynamic = definedExternally, w: dynamic = definedExternally): dynamic
    fun uv(index: Int = definedExternally): dynamic

    fun abs(x: dynamic): dynamic
    fun max(a: dynamic, b: dynamic): dynamic
    fun min(a: dynamic, b: dynamic): dynamic
    fun fract(x: dynamic): dynamic
    fun exp(x: dynamic): dynamic
    fun length(x: dynamic): dynamic

    /** GLSL order: step(edge, x). */
    fun step(edge: dynamic, x: dynamic): dynamic

    /** GLSL order: smoothstep(low, high, x). */
    fun smoothstep(low: dynamic, high: dynamic, x: dynamic): dynamic
    fun mix(a: dynamic, b: dynamic, t: dynamic): dynamic
}
