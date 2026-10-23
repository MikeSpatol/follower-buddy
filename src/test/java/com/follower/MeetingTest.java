// public:file chronicle
package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** How close a creature has to be to count as met, before the line of sight is asked. */
public class MeetingTest
{
	@Test
	public void closeAndOnTheSameFloor()
	{
		assertTrue(FollowerPlugin.closeEnoughToMeet(0, 0, 1));
		assertTrue(FollowerPlugin.closeEnoughToMeet(1, 1, FollowerPlugin.MEET_TILES));
	}

	@Test
	public void notFarOffNorOnAnotherFloor()
	{
		// The caged monk is loaded from well across the palace; the next
		// floor up is a floor away whatever the tiles say.
		assertFalse(FollowerPlugin.closeEnoughToMeet(0, 0, FollowerPlugin.MEET_TILES + 1));
		assertFalse(FollowerPlugin.closeEnoughToMeet(0, 1, 2));
	}
}
