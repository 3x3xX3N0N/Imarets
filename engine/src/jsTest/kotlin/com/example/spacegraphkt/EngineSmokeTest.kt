package com.example.spacegraphkt

import bar.verdantbloom.three.THREE
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.external.VecTween
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Node-side smoke tests (no DOM): the GSAP replacement and the data/three bridge. */
class EngineSmokeTest {
    @Test
    fun tweenEasesOutAndLandsExactly() {
        val v = THREE.Vector3(0.0, 0.0, 0.0)
        VecTween.to(v, 10.0, -20.0, 30.0, 1.0)
        assertTrue(VecTween.isTweening(v))
        val start = kotlin.js.Date.now()
        VecTween.update(start + 500.0)
        assertTrue(v.x > 5.0 && v.x < 10.0, "power3.out is past the midpoint at t=0.5, got ${v.x}")
        VecTween.update(start + 5000.0)
        assertEquals(10.0, v.x)
        assertEquals(-20.0, v.y)
        assertEquals(30.0, v.z)
        assertFalse(VecTween.isTweening(v))
    }

    @Test
    fun killStopsATween() {
        val v = THREE.Vector3(1.0, 1.0, 1.0)
        VecTween.to(v, 9.0, 9.0, 9.0, 1.0)
        VecTween.killTweensOf(v)
        VecTween.update(kotlin.js.Date.now() + 5000.0)
        assertEquals(1.0, v.x)
    }

    @Test
    fun vector3dRoundTrip() {
        val t = Vector3D(1.0, 2.0, 3.0).toThreeVector()
        assertEquals(Vector3D(1.0, 2.0, 3.0), Vector3D.fromThreeVector(t))
    }
}
