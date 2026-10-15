package com.follower.speech;

import java.util.function.ToIntFunction;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * A long line in a dialogue box goes onto more pages, four lines to a page,
 * as the game's own boxes hold; a line break typed into it starts a new line;
 * and text with no break wraps exactly as it always did.
 */
public class DialogPagingTest
{
	/** Every character six pixels wide, so a 60-pixel column holds ten. */
	private static final ToIntFunction<String> SIX = String::length;

	private static String[] wrap(String text)
	{
		return FollowerDialog.wrap(text, s -> SIX.applyAsInt(s) * 6, 60);
	}

	private static String[] pages(String text)
	{
		return FollowerDialog.pages(text, s -> SIX.applyAsInt(s) * 6, 60);
	}

	@Test
	public void textWithNoBreakWrapsAsItAlwaysDid()
	{
		assertArrayEquals(new String[]{"one two", "three four", "five"}, wrap("one two three four five"));
		assertArrayEquals("a word too long for the column is left to overflow",
			new String[]{"a", "unbreakablething", "b"}, wrap("a unbreakablething b"));
		assertArrayEquals(new String[0], wrap(""));
	}

	@Test
	public void aTypedBreakStartsANewLineAndABlankOneIsKept()
	{
		assertArrayEquals(new String[]{"Hello.", "Bye."}, wrap("Hello.\nBye."));
		assertArrayEquals(new String[]{"Hello.", "", "Bye."}, wrap("Hello.\n\nBye."));
		assertArrayEquals(new String[]{"one two", "three", "four"}, wrap("one two three\nfour"));
	}

	@Test
	public void aLongLineGoesOntoPagesOfFourLines()
	{
		String nine = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp qq rr ss tt uu vv ww xx yy zz";
		String[] lines = wrap(nine);
		assertEquals(9, lines.length);
		String[] pages = pages(nine);
		assertEquals(3, pages.length);
		assertEquals(4, wrap(pages[0]).length);
		assertEquals(4, wrap(pages[1]).length);
		assertEquals(1, wrap(pages[2]).length);
		// A page wraps to the same lines again when it is drawn.
		StringBuilder again = new StringBuilder();
		for (String page : pages)
		{
			for (String line : wrap(page))
			{
				again.append(line).append('|');
			}
		}
		assertEquals(String.join("|", lines) + "|", again.toString());
	}

	@Test
	public void aShortLineIsOnePageAndNothingIsOneEmptyPage()
	{
		assertArrayEquals(new String[]{"Hi."}, pages("Hi."));
		assertArrayEquals(new String[]{"one two\nthree four"}, pages("one two three four"));
		assertArrayEquals(new String[]{""}, pages(""));
	}

	@Test
	public void aBoxHoldsFourLinesAsTheGamesDoes()
	{
		assertEquals(4, FollowerDialog.LINES_PER_PAGE);
	}
}
