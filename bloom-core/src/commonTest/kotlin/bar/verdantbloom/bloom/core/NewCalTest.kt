// Ported from newcal (kotlin/src/test/kotlin/com/ralphlandon/newcal/NewCalTest.kt).
// Copyright (c) ralph7c2. MIT License. See bloom-core/NOTICE.
//
// Changes: java.time replaced by a pure Kotlin civil-date helper; the two upstream cases that used day 0
// for the leap day use LEAP_DAY (-1), which is what the code (and the Go original) defines; extra cases
// for Unix 0 formatting, floor division before 1970, and 21 December of a non-leap year.
package bar.verdantbloom.bloom.core

import bar.verdantbloom.bloom.api.NewCalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewCalTest {
    /** Days from civil (proleptic Gregorian, Howard Hinnant's algorithm) to Unix seconds at 00:00 UTC. */
    private fun toUnix(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097 + doe - 719468) * 86400L
    }

    private fun date(year: Int, season: String, day: Int): NewCalDate = NewCal.of(year, season, day)

    @Test
    fun civilHelperIsSane() {
        assertEquals(0L, toUnix(1970, 1, 1))
        assertEquals(5097600L, toUnix(1970, 3, 1))
        assertEquals(951782400L, toUnix(2000, 2, 29))
    }

    @Test
    fun testFromUnix() {
        // Tests from the Go implementation
        assertEquals(date(1968, "Autumn", 73), NewCal.fromUnix(toUnix(1968, 10, 8)))
        assertEquals(date(1968, "Fall", 1), NewCal.fromUnix(toUnix(1968, 10, 9)))
        assertEquals(date(1968, "Fall", 73), NewCal.fromUnix(toUnix(1968, 12, 20)))
        assertEquals(date(1969, "Winter", 1), NewCal.fromUnix(toUnix(1968, 12, 21)))
        assertEquals(date(1969, "Fall", 73), NewCal.fromUnix(toUnix(1969, 12, 20)))
        assertEquals(date(1970, "Winter", 1), NewCal.fromUnix(toUnix(1969, 12, 21)))
        assertEquals(date(1970, "Winter", 11), NewCal.fromUnix(-86400))
        assertEquals(date(1970, "Winter", 11), NewCal.fromUnix(toUnix(1969, 12, 31)))
        assertEquals(date(1970, "Winter", 12), NewCal.fromUnix(0))
        assertEquals(date(1970, "Winter", 12), NewCal.fromUnix(toUnix(1970, 1, 1)))
        assertEquals(date(1970, "Winter", 13), NewCal.fromUnix(86400))
        assertEquals(date(1970, "Winter", 13), NewCal.fromUnix(toUnix(1970, 1, 2)))
        assertEquals(date(1970, "Winter", 71), NewCal.fromUnix(5097600))
        assertEquals(date(1970, "Winter", 71), NewCal.fromUnix(toUnix(1970, 3, 1)))
        assertEquals(date(1970, "Winter", 73), NewCal.fromUnix(toUnix(1970, 3, 3)))
        assertEquals(date(1970, "Spring", 1), NewCal.fromUnix(toUnix(1970, 3, 4)))
        assertEquals(date(1971, "Winter", 71), NewCal.fromUnix(36633600))
        assertEquals(date(1971, "Winter", 71), NewCal.fromUnix(toUnix(1971, 3, 1)))
        assertEquals(date(1972, "Leap Day", NewCalDate.LEAP_DAY), NewCal.fromUnix(36633600 + 365 * 86400L))
        assertEquals(date(1972, "Leap Day", NewCalDate.LEAP_DAY), NewCal.fromUnix(toUnix(1972, 2, 29)))
        assertEquals(date(1972, "Winter", 71), NewCal.fromUnix(36633600 + 366 * 86400L))
        assertEquals(date(1987, "Spring", 73), NewCal.fromUnix(toUnix(1987, 5, 15)))
        assertEquals(date(1999, "Summer", 1), NewCal.fromUnix(toUnix(1999, 5, 16)))
        assertEquals(date(2999, "Summer", 73), NewCal.fromUnix(toUnix(2999, 7, 27)))
        assertEquals(date(3147, "Autumn", 1), NewCal.fromUnix(toUnix(3147, 7, 28)))
        assertEquals(date(3147, "Autumn", 73), NewCal.fromUnix(toUnix(3147, 10, 8)))
        assertEquals(date(1, "Autumn", 73), NewCal.fromUnix(toUnix(1, 10, 8)))
        assertEquals(date(0, "Autumn", 73), NewCal.fromUnix(toUnix(0, 10, 8)))
    }

    @Test
    fun testDayOfWeek() {
        val tests = listOf(
            NewCalDate.LEAP_DAY to "Leap Day",
            1 to "Mercury", 2 to "Venus", 3 to "Earth", 4 to "Mars", 5 to "Jupiter", 6 to "Saturn",
            7 to "Uranus", 8 to "Neptune", 9 to "Pluto", 10 to "Mercury", 11 to "Venus", 29 to "Venus",
            36 to "Pluto", 37 to "Mid Season", 38 to "Mercury", 73 to "Pluto",
        )
        for ((day, expected) in tests) assertEquals(expected, date(2000, "Winter", day).dayOfWeek, "For day $day")
    }

    @Test
    fun testMonthWithModifier() {
        assertEquals("Early Winter", NewCal.seasonWithModifier(date(2000, "Winter", 1)))
        assertEquals("Early Winter", NewCal.seasonWithModifier(date(2000, "Winter", 36)))
        assertEquals("Mid Winter", NewCal.seasonWithModifier(date(2000, "Winter", 37)))
        assertEquals("Late Winter", NewCal.seasonWithModifier(date(2000, "Winter", 38)))
        assertEquals("Late Winter", NewCal.seasonWithModifier(date(2000, "Winter", 73)))
    }

    @Test
    fun testIsLeapDay() {
        assertTrue(date(1972, "Leap Day", -1).isLeapDay)
        assertFalse(date(2000, "Winter", 1).isLeapDay)
        assertTrue(date(2000, "Winter", 37).isMidSeason)
    }

    @Test
    fun testToString() {
        assertEquals("Mercury, 1 Early Winter, 1970", NewCal.format(date(1970, "Winter", 1)))
        assertEquals("37 Mid Winter, 1970", NewCal.format(date(1970, "Winter", 37)))
        assertEquals("Mercury, 38 Late Winter, 1970", NewCal.format(date(1970, "Winter", 38)))
        assertEquals("Leap Day, 1972", NewCal.format(date(1972, "Leap Day", -1)))
    }

    @Test
    fun testIsLeapYear() {
        assertTrue(NewCal.isLeapYear(2000))
        assertTrue(NewCal.isLeapYear(2004))
        assertTrue(NewCal.isLeapYear(1972))
        assertFalse(NewCal.isLeapYear(1900))
        assertFalse(NewCal.isLeapYear(2001))
        assertFalse(NewCal.isLeapYear(1970))
    }

    // ---- verdantbloom additions ----

    @Test
    fun unixZeroIsTheOriginBloom() {
        val cal = BloomCore.newCalendar
        assertEquals("Earth, 12 Early Winter, 1970", cal.format(cal.fromUnix(0)))
        assertEquals("00:00:00", cal.formatTime(0))
        assertEquals("23:59:59", cal.formatTime(-1))
        assertEquals("01:46:40", cal.formatTime(1_000_000_000L))
    }

    @Test
    fun instantsBeforeTheEpochUseFloorDivision() {
        // 31 Dec 1969 23:59:59 is still Winter 11 (upstream truncation would say Winter 12)
        assertEquals(date(1970, "Winter", 11), NewCal.fromUnix(-1))
        assertEquals(date(1970, "Winter", 11), NewCal.fromUnix(-86400))
        assertEquals(date(1970, "Winter", 10), NewCal.fromUnix(-86401))
    }

    @Test
    fun december21StartsTheNextYearEveryYear() {
        // upstream indexes past the season list here for non-leap New Calendar years
        assertEquals(date(1970, "Fall", 73), NewCal.fromUnix(toUnix(1970, 12, 20)))
        assertEquals(date(1971, "Winter", 1), NewCal.fromUnix(toUnix(1970, 12, 21)))
        assertEquals(date(2027, "Winter", 1), NewCal.fromUnix(toUnix(2026, 12, 21)))
        assertEquals(date(2025, "Winter", 1), NewCal.fromUnix(toUnix(2024, 12, 21)))
        assertEquals(date(2024, "Fall", 73), NewCal.fromUnix(toUnix(2024, 12, 20)))
    }

    @Test
    fun everyDayOfTwoCenturiesIsWellFormedAndSequential() {
        var unix = toUnix(1899, 12, 21)
        val end = toUnix(2101, 1, 1)
        var prev = NewCal.fromUnix(unix - 86400)
        var leapDays = 0
        while (unix < end) {
            val d = NewCal.fromUnix(unix)
            if (d.isLeapDay) {
                leapDays++
                assertEquals("Winter", prev.season); assertEquals(70, prev.day)
            } else {
                assertTrue(d.day in 1..73 && d.season in NewCal.SEASONS, "$d")
                if (prev.isLeapDay) {
                    assertEquals(71, d.day)
                } else if (d.day == 1 && d.season == "Winter") {
                    assertEquals("Fall", prev.season); assertEquals(73, prev.day); assertEquals(prev.year + 1, d.year)
                } else if (d.day == 1) {
                    assertEquals(73, prev.day)
                } else {
                    assertEquals(prev.day + 1, d.day); assertEquals(prev.season, d.season)
                }
            }
            prev = d
            unix += 86400
        }
        assertEquals(49, leapDays) // 1900..2100: 51 multiples of 4 minus 1900 and 2100
    }

    @Test
    fun todayReadsRight() {
        // 20 Sep 2026: year began 21 Dec 2025, day index 273 -> Autumn (3 * 73 = 219), day 55
        assertEquals("Pluto, 55 Late Autumn, 2026", NewCal.format(NewCal.fromUnix(toUnix(2026, 9, 20))))
    }
}
