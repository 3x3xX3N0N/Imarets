package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.NewCalendar

/**
 * Entry point of bloom-core. The site calls exactly [createWorld] and [newCalendar].
 */
object BloomCore {
    /**
     * bloom-core's tuned configuration. Differences from the bloom-api defaults:
     *  - angularVelocityScale 0.004 -> 0.08 (at 0.004 one turn of the bloom takes over an hour and the
     *    page looks frozen; 0.08 gives roughly 0.02 - 0.05 rad/s);
     *  - fadeStartRadius 4 -> 5, maxRadius 8 -> 10: with 4/8 a ring is fading 22 % of the time and gone
     *    6 %; with 5/10 that is 15 % and 4 % (see NOTES.md for the formula).
     * See NOTES.md before changing anything: this object defines what "the same bloom for everyone" means.
     */
    val DEFAULT_CONFIG: BloomConfig = BloomConfig(angularVelocityScale = 0.08, fadeStartRadius = 5.0, maxRadius = 10.0)

    fun createWorld(config: BloomConfig = DEFAULT_CONFIG): BloomWorld = HopfLorenzWorld(config)

    fun createWorld(config: BloomConfig, tuning: CoreTuning): HopfLorenzWorld = HopfLorenzWorld(config, tuning)

    /** The New Calendar (SPEC 3.2). */
    val newCalendar: NewCalendar get() = NewCal
}
