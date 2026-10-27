package com.follower;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The follow distance setting: the follow tile taken further back along the
 * player's own trail, so the follower still walks the tiles the player
 * walked and simply stops further off when they stop.
 */
public class FollowDistanceTest
{
	private static WorldPoint at(int x, int y)
	{
		return new WorldPoint(x, y, 0);
	}

	@Test
	public void distanceOneIsTheTileBehindWhateverTheTrail()
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		for (int y = 0; y < 6; y++)
		{
			follower.recordTrail(at(10, y));
		}
		assertEquals(at(10, 4), follower.behindBy(1, at(10, 4)));
	}

	@Test
	public void aWiderDistanceFollowsTheTrailFurtherBack()
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		for (int y = 0; y < 6; y++)
		{
			follower.recordTrail(at(10, y));
		}
		assertEquals("two steps back", at(10, 3), follower.behindBy(2, at(10, 4)));
		assertEquals("three steps back", at(10, 2), follower.behindBy(3, at(10, 4)));
	}

	@Test
	public void theTrailFollowsCornersNotStraightLines()
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		follower.recordTrail(at(10, 0));
		follower.recordTrail(at(10, 1));
		follower.recordTrail(at(10, 2));
		follower.recordTrail(at(11, 2));
		follower.recordTrail(at(12, 2));
		assertEquals("round the corner, not through it", at(10, 1), follower.behindBy(3, at(11, 2)));
	}

	@Test
	public void tooShortATrailFallsBackToTheTileBehind()
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		follower.recordTrail(at(10, 0));
		follower.recordTrail(at(10, 1));
		assertEquals(at(10, 0), follower.behindBy(3, at(10, 0)));
	}

	@Test
	public void aRepeatedTileAndAPlaneChangeDoNotPadTheTrail()
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		follower.recordTrail(at(10, 0));
		follower.recordTrail(at(10, 0));
		follower.recordTrail(at(10, 1));
		follower.recordTrail(at(10, 2));
		follower.recordTrail(at(10, 3));
		assertEquals("the repeat counts once", at(10, 0), follower.behindBy(3, at(10, 2)));
		follower.recordTrail(new WorldPoint(10, 4, 1));
		assertEquals("a plane change starts the trail again", at(99, 99),
			follower.behindBy(2, at(99, 99)));
	}

	@Test
	public void theDistanceIsClampedToWhatTheModelSupports() throws Exception
	{
		FollowerEntity follower = new FollowerEntity(null, null);
		follower.setFollowDistance(9);
		for (int y = 0; y < 8; y++)
		{
			follower.recordTrail(at(10, y));
		}
		assertEquals(at(10, 6 - FollowerEntity.MAX_FOLLOW_DISTANCE + 1), follower.behindBy(followDistance(follower), at(10, 6)));
	}

	/** The distance as the setting left it, which is what the follow step walks by. */
	private static int followDistance(FollowerEntity follower) throws Exception
	{
		java.lang.reflect.Field field = FollowerEntity.class.getDeclaredField("followDistance");
		field.setAccessible(true);
		return field.getInt(follower);
	}
}
