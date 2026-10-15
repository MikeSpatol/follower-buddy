package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What survives of a typed follower name.
 *
 * <p>The chatbox validator refuses bad characters as they are typed, but
 * pasted text does not go through it, and everything downstream - the menu
 * target, the dialog header, the chat echo - trusts the stored name to hold
 * no tags and no surprises. This is the one gate.
 */
public class NamePromptTest
{
	@Test
	public void ordinaryNamesPassThrough()
	{
		assertEquals("Pip", FollowerPlugin.cleanFollowerName("Pip"));
		assertEquals("Old Bob", FollowerPlugin.cleanFollowerName("Old Bob"));
		assertEquals("D'arcy-Jones", FollowerPlugin.cleanFollowerName("D'arcy-Jones"));
	}

	@Test
	public void whitespaceIsTidiedNotTrusted()
	{
		assertEquals("Pip", FollowerPlugin.cleanFollowerName("  Pip  "));
		assertEquals("Old Bob", FollowerPlugin.cleanFollowerName("Old    Bob"));
	}

	@Test
	public void tagsAndMarkupCannotSurvive()
	{
		// The menu target wraps the name in colour tags; a name carrying its
		// own would break out of them. Whatever comes in, no angle bracket,
		// equals sign or slash comes out.
		String cleaned = FollowerPlugin.cleanFollowerName("Bob<col=ff0000>");
		assertEquals("Bobcolff0000", cleaned);
		assertEquals("img1Pip", FollowerPlugin.cleanFollowerName("<img=1/>Pip"));
	}

	@Test
	public void onlyLettersTheGameCanDrawAreKept()
	{
		// QA, 15 Oct: the game's fonts stop at Latin-1, and a Cyrillic name
		// drew as blanks over the follower's head. Accented Latin is fine.
		assertEquals("Zo\u00eb", FollowerPlugin.cleanFollowerName("Zo\u00eb"));
		assertEquals("Pip", FollowerPlugin.cleanFollowerName("Pip\u0414\u043c\u03b1\u4e00"));
		assertEquals("", FollowerPlugin.cleanFollowerName("\u0414\u043c\u0438\u0442\u0440\u0438\u0439"));
		assertTrue(FollowerPlugin.nameChar('\u00e9'));
		assertFalse(FollowerPlugin.nameChar('\u0414'));
	}

	@Test
	public void lengthIsCappedAtTwenty()
	{
		assertEquals(20,
			FollowerPlugin.cleanFollowerName("Abcdefghijklmnopqrstuvwxyz").length());
	}

	@Test
	public void nothingUsableMeansEmptyWhichMeansSkip()
	{
		assertEquals("", FollowerPlugin.cleanFollowerName(null));
		assertEquals("", FollowerPlugin.cleanFollowerName("   "));
		assertEquals("", FollowerPlugin.cleanFollowerName("<<<>>>"));
	}
}
