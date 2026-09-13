package com.follower.speech;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Numbers and stretches of time in words: the difference between a counter
 * with a face and somebody keeping count.
 */
public class WordsTest
{
	@Test
	public void numbersInWordsUpToWhereWordsStopReadingNaturally()
	{
		assertEquals("one", Words.number(1));
		assertEquals("eleven", Words.number(11));
		assertEquals("twenty", Words.number(20));
		assertEquals("forty-one", Words.number(41));
		assertEquals("ninety-nine", Words.number(99));
		assertEquals("100", Words.number(100));
		assertEquals("1500", Words.number(1500));
	}

	@Test
	public void ordinalsTheWayTheyAreSaid()
	{
		assertEquals("first", Words.ordinal(1));
		assertEquals("third", Words.ordinal(3));
		assertEquals("twelfth", Words.ordinal(12));
		assertEquals("twentieth", Words.ordinal(20));
		assertEquals("forty-first", Words.ordinal(41));
		assertEquals("fifty-second", Words.ordinal(52));
		assertEquals("ninety-ninth", Words.ordinal(99));
		assertEquals("hundredth", Words.ordinal(100));
		assertEquals("101st", Words.ordinal(101));
		assertEquals("111th", Words.ordinal(111));
		assertEquals("112th", Words.ordinal(112));
		assertEquals("122nd", Words.ordinal(122));
		assertEquals("203rd", Words.ordinal(203));
		assertEquals("thousandth", Words.ordinal(1000));
		assertEquals("1001st", Words.ordinal(1001));
	}

	@Test
	public void durationsPickOneUnitAndStopThere()
	{
		long minute = 60_000L;
		assertEquals("a minute", Words.duration(0));
		assertEquals("a minute", Words.duration(90_000L));
		assertEquals("forty minutes", Words.duration(40 * minute));
		assertEquals("an hour", Words.duration(75 * minute));
		assertEquals("three hours", Words.duration(3 * 60 * minute + 12 * minute));
		assertEquals("a day", Words.duration(40 * 60 * minute));
		assertEquals("eleven days", Words.duration(11 * 24 * 60 * minute + 4 * 60 * minute));
		assertEquals("three weeks", Words.duration(23 * 24 * 60 * minute));
		assertEquals("six weeks", Words.duration(45 * 24 * 60 * minute));
		assertEquals("a month", Words.duration(58 * 24 * 60 * minute));
		assertEquals("four months", Words.duration(130 * 24 * 60 * minute));
		assertEquals("a year", Words.duration(400L * 24 * 60 * minute));
		assertEquals("two years", Words.duration(800L * 24 * 60 * minute));
	}
}
