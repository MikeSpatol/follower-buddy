package com.follower.follower;

import java.util.ArrayList;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The side panel lists errands by display name and forces them by key, so
 * the two views of the enum must line up exactly.
 */
public class ErrandNamesTest
{
	@Test
	public void everyKeyHasANameInTheSameOrder()
	{
		Map<String, String> names = ErrandController.displayNames();
		assertEquals(ErrandController.keys(), new ArrayList<>(names.keySet()));
		for (Map.Entry<String, String> entry : names.entrySet())
		{
			assertFalse("blank name for " + entry.getKey(), entry.getValue().trim().isEmpty());
		}
	}
}
