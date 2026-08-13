package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
