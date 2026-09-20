package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.three.THREE
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloomThreeTest {
    @Test
    fun placeholderAndThreeLink() {
        assertNull(BloomThree.create(RendererKind.WEBGL))
        assertTrue(THREE.REVISION.isNotEmpty())
    }
}
