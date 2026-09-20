// The New Calendar - Kotlin port for verdantbloom.bar.
//
// Ported from newcal (kotlin/src/main/kotlin/com/ralphlandon/newcal/NewCal.kt).
// Copyright (c) ralph7c2. MIT License. See bloom-core/NOTICE for the full text and the list of changes.
package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.NewCalDate
import bar.verdantbloom.bloom.api.NewCalendar

/**
 * The New Calendar: 5 seasons x 73 days, 9-day planet weeks, day 37 is Mid Season (outside the week),
 * one Leap Day (29 February) outside every season. The year starts on 21 December.
 *
 * Differences from the upstream Kotlin file (all covered by tests):
 *  - day counting uses floor division, so instants before 1970 that are not on a day boundary land on
 *    the right day (upstream truncates towards zero);
 *  - the year roll-over loop uses "days >= length of year": upstream used "days > 365" and indexed past
 *    the season list on 21 December of every non-leap New Calendar year;
 *  - the result is the bloom-api [NewCalDate], which carries dayOfWeek and modifier as fields.
 */
object NewCal : NewCalendar {
    const val WINTER = "Winter"
    const val SPRING = "Spring"
    const val SUMMER = "Summer"
    const val AUTUMN = "Autumn"
    const val FALL = "Fall"
    const val LEAP_DAY_NAME = "Leap Day"
    const val MID_SEASON = "Mid Season"

    val SEASONS: List<String> = listOf(WINTER, SPRING, SUMMER, AUTUMN, FALL)
    val PLANETS: List<String> = listOf(
        "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto",
    )

    private const val SECONDS_PER_DAY = 86400L

    fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun yearLength(year: Int): Long = if (isLeapYear(year)) 366L else 365L

    override fun fromUnix(unixSeconds: Long): NewCalDate {
        // 1 Jan 1970 is day index 11 of New Calendar year 1970 (the year began on 21 Dec 1969).
        var days = unixSeconds.floorDiv(SECONDS_PER_DAY) + 11
        var year = 1970
        while (days < 0) {
            year--
            days += yearLength(year)
        }
        while (days >= yearLength(year)) {
            days -= yearLength(year)
            year++
        }
        if (isLeapYear(year)) {
            if (days == 70L) return of(year, LEAP_DAY_NAME, NewCalDate.LEAP_DAY)
            if (days > 70) days--
        }
        val season = (days / 73).toInt()
        val dayOfSeason = (days % 73).toInt()
        return of(year, SEASONS[season], dayOfSeason + 1)
    }

    /** Build a date with its derived strings filled in. */
    fun of(year: Int, season: String, day: Int): NewCalDate =
        NewCalDate(year, season, day, dayOfWeek(day), modifier(day))

    fun dayOfWeek(day: Int): String {
        if (day == NewCalDate.LEAP_DAY) return LEAP_DAY_NAME
        if (day == 37) return MID_SEASON
        val adjusted = if (day > 37) day - 1 else day
        return PLANETS[(adjusted - 1).mod(PLANETS.size)]
    }

    fun modifier(day: Int): String = when {
        day == NewCalDate.LEAP_DAY -> ""
        day > 37 -> "Late"
        day == 37 -> "Mid"
        else -> "Early"
    }

    fun seasonWithModifier(date: NewCalDate): String = "${date.modifier} ${date.season}"

    /** Same text as upstream Date.toString(). */
    override fun format(date: NewCalDate): String {
        if (date.isLeapDay) return "$LEAP_DAY_NAME, ${date.year}"
        val weekday = if (date.day != 37) "${date.dayOfWeek}, " else ""
        return "$weekday${date.day} ${seasonWithModifier(date)}, ${date.year}"
    }

    override fun formatTime(unixSeconds: Long): String {
        val s = unixSeconds.mod(SECONDS_PER_DAY)
        return two(s / 3600) + ":" + two((s % 3600) / 60) + ":" + two(s % 60)
    }

    private fun two(v: Long): String = if (v < 10) "0$v" else v.toString()
}
