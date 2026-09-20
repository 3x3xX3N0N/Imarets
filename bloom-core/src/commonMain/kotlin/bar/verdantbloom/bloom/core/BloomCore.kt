package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.StubBloomWorld

/**
 * Entry point of bloom-core. PLACEHOLDER from bringup: returns the stub world.
 * The bloom-core agent replaces the body with the real Hopf/Lorenz world; keep the signature,
 * :site calls exactly this.
 */
object BloomCore {
    fun createWorld(config: BloomConfig = BloomConfig()): BloomWorld = StubBloomWorld(config)
}
