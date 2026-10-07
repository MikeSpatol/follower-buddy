package com.follower.speech;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

	@Test
	public void theArticleGoesBySoundNotSpelling()
	{
		assertEquals("an", Words.article("Eclectic impling"));
		assertEquals("an", Words.article("Earth impling"));
		assertEquals("an", Words.article("Essence impling"));
		assertEquals("a", Words.article("Young impling"));
		assertEquals("a", Words.article("Dwarf"));
		assertEquals("an", Words.article("Agility"));
		assertEquals("an", Words.article("Uncut onyx"));
		assertEquals("a", Words.article("Unicorn"));
		assertEquals("a", Words.article("One-eyed rat"));
		assertEquals("an", Words.article("hour"));
		assertEquals("an", Words.article("8"));
		assertEquals("an", Words.article("80"));
		assertEquals("an", Words.article("11"));
		assertEquals("an", Words.article("18"));
		assertEquals("a", Words.article("31"));
		assertEquals("a", Words.article("110"));
	}

	@Test
	public void pluralsHeapsAndTheNamedTakeNoArticle()
	{
		assertTrue(Words.takesNoArticle("Coins"));
		assertTrue(Words.takesNoArticle("Dragon bones"));
		assertTrue(Words.takesNoArticle("Onyx bolts (e)"));
		assertTrue(Words.takesNoArticle("Soft clay"));
		assertTrue(Words.takesNoArticle("charcoal"));
		assertTrue(Words.takesNoArticle("Hanging meat"));
		assertTrue(Words.takesNoArticle("The Leviathan"));
		assertFalse(Words.takesNoArticle("Cerberus"));
		assertFalse(Words.takesNoArticle("Scurrius"));
		assertFalse(Words.takesNoArticle("Venenatis"));
		assertFalse(Words.takesNoArticle("Tempoross"));
		assertFalse(Words.takesNoArticle("Jar of souls"));
		assertFalse(Words.takesNoArticle("ball of wool"));
		assertFalse(Words.takesNoArticle("Clue scroll (elite)"));
		assertFalse(Words.takesNoArticle("Eclectic impling"));
		assertFalse(Words.takesNoArticle("80"));
		// Review: one of a kind despite the s, and glass had by the piece.
		assertFalse(Words.takesNoArticle("Cyclops"));
		assertFalse(Words.takesNoArticle("Nylocas Ischyros"));
		assertFalse(Words.takesNoArticle("Nylocas Vasilias"));
		assertFalse(Words.takesNoArticle("Lantern lens"));
		assertFalse(Words.takesNoArticle("Beer glass"));
		assertFalse(Words.takesNoArticle("Magnifying glass"));
	}

	@Test
	public void aPlainNameBecomesACommonNounAndANameInItsOwnRightDoesNot()
	{
		assertEquals("guard", Words.asCommonNoun("Guard"));
		assertEquals("monk of Zamorak", Words.asCommonNoun("Monk of Zamorak"));
		assertEquals("thermonuclear smoke devil", Words.asCommonNoun("Thermonuclear smoke devil"));
		assertEquals("goblin (level-2)", Words.asCommonNoun("Goblin (level-2)"));
		assertEquals("Hill Giant", Words.asCommonNoun("Hill Giant"));
		assertEquals("King Black Dragon", Words.asCommonNoun("King Black Dragon"));
		assertEquals("TzHaar-Ket", Words.asCommonNoun("TzHaar-Ket"));
		assertEquals("guard", Words.asCommonNoun("guard"));
	}
}
