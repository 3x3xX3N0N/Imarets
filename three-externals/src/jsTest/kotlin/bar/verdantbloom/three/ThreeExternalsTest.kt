package bar.verdantbloom.three

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs on node: proves "three/webgpu" resolves and the externals match the real classes (no GPU needed). */
class ThreeExternalsTest {
    @Test
    fun coreMathComesFromTheWebgpuBuild() {
        assertTrue(THREE.REVISION.isNotEmpty())
        val v = THREE.Vector3(1.0, 2.0, 2.0)
        assertEquals(3.0, v.length())
        val q = THREE.Quaternion().setFromAxisAngle(THREE.Vector3(0.0, 0.0, 1.0), kotlin.math.PI / 2)
        val r = THREE.Vector3(1.0, 0.0, 0.0).applyQuaternion(q)
        assertTrue(abs(r.y - 1.0) < 1e-12)
    }

    @Test
    fun floatArrayIsAZeroCopyBufferAttribute() {
        val floats = FloatArray(9) { it.toFloat() }
        val g = THREE.BufferGeometry()
        g.setAttribute("position", THREE.BufferAttribute(floats.asFloat32Array(), 3))
        assertEquals(3, g.getAttribute("position").count)
        floats[3] = 42f
        assertEquals(42.0, g.getAttribute("position").getX(1))
        val loop = THREE.LineLoop(g, THREE.LineBasicMaterial(jsObject { color = 0x3dffc0 }))
        assertTrue(loop.isObject3D)
    }

    @Test
    fun webgpuRendererClassIsExported() {
        assertEquals("function", jsTypeOf(THREE.asDynamic().WebGPURenderer))
        assertEquals("undefined", jsTypeOf(THREE.asDynamic().WebGLRenderer))
    }
}
