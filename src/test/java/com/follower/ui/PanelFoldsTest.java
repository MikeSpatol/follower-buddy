package com.follower.ui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The side panel remembers which sections are open as a comma list in one
 * hidden setting. The list has to survive a hand edit, an empty value and
 * the default, so the reader is forgiving about spaces and case.
 */
public class PanelFoldsTest
{
	@Test
	public void theDefaultOpensTalkingAndOutfit()
	{
		assertEquals(new LinkedHashSet<>(Arrays.asList("talking", "outfit")),
			FollowerPanel.parseFolds("talking,outfit"));
	}

	@Test
	public void spacesCaseAndStraysAreForgiven()
	{
		assertEquals(new LinkedHashSet<>(Arrays.asList("memory", "commands")),
			FollowerPanel.parseFolds(" Memory , COMMANDS,, "));
	}

	@Test
	public void nothingStoredMeansEverythingFolded()
	{
		assertTrue(FollowerPanel.parseFolds(null).isEmpty());
		assertTrue(FollowerPanel.parseFolds("").isEmpty());
	}
}
