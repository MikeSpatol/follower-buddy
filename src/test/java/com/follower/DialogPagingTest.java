package com.follower;

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

	@Test
	public void textWithNoBreakWrapsAsItAlwaysDid()
	{
		assertArrayEquals(new String[]{"one two", "three four", "five"}, wrap("one two three four five"));
		assertArrayEquals(new String[0], wrap(""));
	}

	@Test
	public void aWordTooWideForTheColumnIsCutWhereItStopsFitting()
	{
		// QA2, 15 Oct: left whole, a pasted link ran over the chathead and
		// past both edges of the box.
		assertArrayEquals(new String[]{"a", "unbreakabl", "ething b"}, wrap("a unbreakablething b"));
		assertArrayEquals(new String[]{"aaaaaaaaaa", "aaaaaaaaaa", "aaaaa"}, wrap("a".repeat(25)));
		for (String line : wrap("see https://example.com/a/very/long/link/with/no/spaces ok"))
		{
			assertEquals(line, true, line.length() * 6 <= 60);
		}
	}

	@Test
	public void aTypedBreakStartsANewLineAndABlankOneIsKept()
	{
		assertArrayEquals(new String[]{"Hello.", "Bye."}, wrap("Hello.\nBye."));
		assertArrayEquals(new String[]{"Hello.", "", "Bye."}, wrap("Hello.\n\nBye."));
		assertArrayEquals(new String[]{"one two", "three", "four"}, wrap("one two three\nfour"));
	}
}
