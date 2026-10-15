package com.follower.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The item picker shows an item's id beside its name when another match
 * shares the name. QA, 15 Oct: "Dragon axe (or)" came up twice, as two
 * identical rows.
 */
public class ItemPickerTest
{
	@Test
	public void onlyNamesThatRepeatAreMarked()
	{
		assertEquals(new HashSet<>(Arrays.asList("Dragon axe (or)", "Dragon 2h sword")),
			FollowerPanel.repeated(Arrays.asList("Dragon axe (or)", "Dragon dagger",
				"Dragon 2h sword", "Dragon axe (or)", "Dragon 2h sword", "Dragon 2h sword")));
		assertTrue(FollowerPanel.repeated(Arrays.asList("Dragon dagger", "Dragon axe")).isEmpty());
		assertTrue(FollowerPanel.repeated(Collections.emptyList()).isEmpty());
	}
}
