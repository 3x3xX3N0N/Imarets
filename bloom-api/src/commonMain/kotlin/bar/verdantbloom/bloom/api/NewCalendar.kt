package bar.verdantbloom.bloom.api

/**
 * A date in The New Calendar: 5 seasons x 73 days, 9-day planet weeks, mid-season day 37, leap day.
 * Shape follows NewCal.kt (MIT, (c) ralph7c2); the port itself lives in bloom-core.
 *
 * @property season "Winter" | "Spring" | "Summer" | "Autumn" | "Fall" | "Leap Day"
 * @property day 1..73, or [LEAP_DAY] (-1)
 * @property dayOfWeek planet name, "Mid Season" or "Leap Day"
 * @property modifier "Early" | "Mid" | "Late", or "" on the leap day
 */
data class NewCalDate(
    val year: Int,
    val season: String,
    val day: Int,
    val dayOfWeek: String,
    val modifier: String,
) {
    val isLeapDay: Boolean get() = day == LEAP_DAY
    val isMidSeason: Boolean get() = day == 37

    companion object {
        const val LEAP_DAY: Int = -1
    }
}

interface NewCalendar {
    /** Unix seconds (UTC) to New Calendar date. Negative values (before 1970) must work. */
    fun fromUnix(unixSeconds: Long): NewCalDate

    /** Human string identical to NewCal.kt `Date.toString()`: "<dayOfWeek>, <day> <modifier> <season>, <year>". */
    fun format(date: NewCalDate): String

    /** Clock face "HH:MM:SS" (UTC) for the same instant. */
    fun formatTime(unixSeconds: Long): String
}
