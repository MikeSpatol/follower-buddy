package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where a death is given back. The user, 20 Oct: Castle Wars and the like are ignored. */
public class SafeDeathTest
{
	private static boolean safeAt(int x, int y, int plane)
	{
		TriggerContext ctx = new TriggerContext(new FakeGame().at(x, y, plane).client);
		ctx.refresh();
		return ctx.isSafeDeathHere();
	}

	@Test
	public void castleWarsIsSafe()
	{
		// Text QA5, 17 Oct 14:54: the death in r9520, mourned and recalled.
		assertTrue("the arena", safeAt(2400, 3100, 0));
		assertTrue("the tunnels under it", safeAt(2400, 9500, 0));
	}

	@Test
	public void lastManStandingIsSafe()
	{
		assertTrue(safeAt(3420, 5790, 0));
	}

	@Test
	public void theRestOfTheWorldIsNot()
	{
		assertFalse("Lumbridge", safeAt(3222, 3218, 0));
		assertFalse("the Castle Wars lobby, where nobody dies", safeAt(2440, 3090, 0));
		assertFalse("the Abyss", safeAt(3040, 4830, 0));
	}
}
