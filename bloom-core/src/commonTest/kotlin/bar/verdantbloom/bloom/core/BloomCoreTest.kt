package bar.verdantbloom.bloom.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BloomCoreTest {
    @Test
    fun createsAWorldWithTenRings() {
        assertEquals(10, BloomCore.createWorld().rings.size)
    }
}
