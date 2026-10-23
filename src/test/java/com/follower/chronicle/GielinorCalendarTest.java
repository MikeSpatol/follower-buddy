// public:file chronicle
package com.follower.chronicle;

import java.time.LocalDate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Real dates in the world's own calendar, at every month's edges, the way
 * the OSRS wiki's "Calendar" table lays the ten months over the year.
 */
public class GielinorCalendarTest
{
	private static String on(int year, int month, int day)
	{
		return GielinorCalendar.date(LocalDate.of(year, month, day));
	}

	@Test
	public void everyMonthStartsWhereTheTableSays()
	{
		assertEquals("the 1st of Rintra", on(2026, 1, 1));
		assertEquals("the 1st of Moevyng", on(2026, 2, 9));
		assertEquals("the 1st of Bennath", on(2026, 3, 19));
		assertEquals("the 1st of Raktuber", on(2026, 4, 20));
		assertEquals("the 1st of Pentember", on(2026, 5, 24));
		assertEquals("the 1st of Fentuary", on(2026, 7, 1));
		assertEquals("the 1st of Septober", on(2026, 8, 1));
		assertEquals("the 1st of Ire of Phyrrys", on(2026, 9, 8));
		assertEquals("the 1st of Novtumber", on(2026, 10, 18));
		assertEquals("the 1st of Wintumber", on(2026, 11, 26));
	}

	@Test
	public void andEndsOnTheDayBeforeTheNext()
	{
		assertEquals("Rintra has 39 days", "the 39th of Rintra", on(2026, 2, 8));
		assertEquals("Moevyng 38", "the 38th of Moevyng", on(2026, 3, 18));
		assertEquals("Bennath 32", "the 32nd of Bennath", on(2026, 4, 19));
		assertEquals("Raktuber 34", "the 34th of Raktuber", on(2026, 5, 23));
		assertEquals("Pentember 38", "the 38th of Pentember", on(2026, 6, 30));
		assertEquals("Fentuary 31", "the 31st of Fentuary", on(2026, 7, 31));
		assertEquals("Septober 38", "the 38th of Septober", on(2026, 9, 7));
		assertEquals("Ire of Phyrrys 40", "the 40th of Ire of Phyrrys", on(2026, 10, 17));
		assertEquals("Novtumber 39", "the 39th of Novtumber", on(2026, 11, 25));
		assertEquals("Wintumber 36", "the 36th of Wintumber", on(2026, 12, 31));
	}

	@Test
	public void theLeapDayIsMoevyngDayAndStandsOutsideTheCount()
	{
		assertEquals("the 20th of Moevyng", on(2028, 2, 28));
		assertEquals("Moevyng Day", on(2028, 2, 29));
		assertEquals("the day after is numbered as in any year", "the 21st of Moevyng", on(2028, 3, 1));
		assertEquals("the 38th of Moevyng", on(2028, 3, 18));
		assertEquals("the 1st of Bennath", on(2028, 3, 19));
	}

	@Test
	public void theDaysAreCountedAsABookWouldCountThem()
	{
		assertEquals("the 17th of Ire of Phyrrys", on(2026, 9, 24));
		assertEquals("the 11th of Rintra", on(2026, 1, 11));
		assertEquals("the 12th of Rintra", on(2026, 1, 12));
		assertEquals("the 13th of Rintra", on(2026, 1, 13));
		assertEquals("the 21st of Rintra", on(2026, 1, 21));
		assertEquals("the 22nd of Rintra", on(2026, 1, 22));
		assertEquals("the 23rd of Rintra", on(2026, 1, 23));
	}
}
