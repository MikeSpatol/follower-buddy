package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Follow me is the one record of follow versus stay. The right-click Stay and
 * Follow ops move the follower first and write the setting second, so the
 * config change that write raises must find nothing left to do - otherwise
 * every click would run its own op twice.
 */
public class FollowToggleTest
{
	@Test
	public void aWriteThatMatchesTheLiveStateDoesNothing()
	{
		assertFalse("Stay clicked: parked, then followEnabled=false arrives",
			FollowerPlugin.followToggleNeedsAction(false, true));
		assertFalse("Follow clicked: released, then followEnabled=true arrives",
			FollowerPlugin.followToggleNeedsAction(true, false));
	}

	@Test
	public void theSettingsToggleStillMovesTheFollower()
	{
		assertTrue("ticked off while following parks it",
			FollowerPlugin.followToggleNeedsAction(false, false));
		assertTrue("ticked on while parked releases it",
			FollowerPlugin.followToggleNeedsAction(true, true));
	}
}
