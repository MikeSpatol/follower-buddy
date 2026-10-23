// public:file chronicle
package com.follower.chronicle;

import java.time.LocalDate;
import java.time.MonthDay;

/**
 * The world's own calendar, for dating the chronicle's pages: ten months laid
 * over the real year, as Reldo gives them in Postbag from the Hedge issue 21
 * (the OSRS wiki's "Calendar" page has the table). Rintra starts on the 1st of
 * January and Wintumber ends on the 31st of December, so a real date is always
 * a Gielinor one. The leap day is Moevyng Day, added "in the middle of
 * Moevyng every four years": the real 29th of February, which the month's
 * days are not counted through. The year is 169 of the Fifth Age, where the
 * world has stood since.
 */
public final class GielinorCalendar
{
	/** The months, and the real day each begins on. */
	static final String[] MONTHS = {
		"Rintra", "Moevyng", "Bennath", "Raktuber", "Pentember",
		"Fentuary", "Septober", "Ire of Phyrrys", "Novtumber", "Wintumber",
	};
	private static final MonthDay[] STARTS = {
		MonthDay.of(1, 1), MonthDay.of(2, 9), MonthDay.of(3, 19), MonthDay.of(4, 20), MonthDay.of(5, 24),
		MonthDay.of(7, 1), MonthDay.of(8, 1), MonthDay.of(9, 8), MonthDay.of(10, 18), MonthDay.of(11, 26),
	};

	private GielinorCalendar()
	{
	}

	/** "the 17th of Ire of Phyrrys", or "Moevyng Day". */
	public static String date(LocalDate day)
	{
		if (day.getMonthValue() == 2 && day.getDayOfMonth() == 29)
		{
			return "Moevyng Day";
		}
		MonthDay today = MonthDay.from(day);
		int month = 0;
		for (int i = 0; i < STARTS.length; i++)
		{
			if (!today.isBefore(STARTS[i]))
			{
				month = i;
			}
		}
		LocalDate start = STARTS[month].atYear(day.getYear());
		int dayOfMonth = (int) (day.toEpochDay() - start.toEpochDay()) + 1;
		// Moevyng Day stands outside the count, so the days after it in a
		// leap year are numbered as in any other year.
		if (month == 1 && day.isLeapYear() && day.getMonthValue() == 3)
		{
			dayOfMonth--;
		}
		return "the " + ordinal(dayOfMonth) + " of " + MONTHS[month];
	}

	static String ordinal(int n)
	{
		int tens = n % 100;
		if (tens >= 11 && tens <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}
}
