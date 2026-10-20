package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The goodbye is for a logout, not a hop. Behaviour QA5, 16-20 Oct: all
 * four farewells came as the logout panel opened on the way to the World
 * Switcher, about 20 s before the hop line, and none before a logout.
 */
public class FarewellWaitsTest
{
	private static int after(boolean... open)
	{
		int ticks = 0;
		boolean was = false;
		for (boolean now : open)
		{
			ticks = FollowerPlugin.ticksOnShow(ticks, now, now && !was);
			was = now;
		}
		return ticks;
	}

	@Test
	public void theGoodbyeWaitsATickWithThePanelStillUp()
	{
		assertEquals("not as it opens", 1, after(true));
		assertEquals("a tick on, still up", FollowerPlugin.FAREWELL_AFTER_TICKS, after(true, true));
	}

	@Test
	public void goingOnToTheSwitcherTakesTheGoodbyeWithIt()
	{
		// The switcher replaces the panel: down before the goodbye was due.
		assertEquals(0, after(true, false));
		assertEquals("opened again later, it starts over", 1, after(true, false, false, true));
	}

	@Test
	public void aPanelUpAsTheSessionStartsIsNoGoodbye()
	{
		int ticks = 0;
		for (int i = 0; i < 5; i++)
		{
			// Up all along: never opened in this session.
			ticks = FollowerPlugin.ticksOnShow(ticks, true, false);
		}
		assertEquals(0, ticks);
		assertTrue(FollowerPlugin.FAREWELL_AFTER_TICKS > 1);
	}
}
