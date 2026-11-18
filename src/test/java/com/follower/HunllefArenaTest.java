package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether the follower is outside the Hunllef's arena, read from its walk to
 * you. Play report: a follower shut out when the barrier closed ran round the
 * Corrupted Gauntlet's maze, through rooms never opened, for the whole fight.
 * Any walk inside one room is short; a longer one, or none, means it is out.
 */
public class HunllefArenaTest
{
	@Test
	public void noWalkAtAllIsOutside()
	{
		assertTrue(FollowerPlugin.outsideHunllefArena(-1));
	}

	@Test
	public void aWalkOutThroughTheMazeIsOutside()
	{
		assertTrue(FollowerPlugin.outsideHunllefArena(17));
	}

	@Test
	public void aWalkAcrossTheRoomIsInside()
	{
		assertFalse("already beside you", FollowerPlugin.outsideHunllefArena(0));
		assertFalse(FollowerPlugin.outsideHunllefArena(1));
		assertFalse("corner to corner of one room", FollowerPlugin.outsideHunllefArena(16));
	}
}
