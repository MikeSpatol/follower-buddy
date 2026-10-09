package com.follower.follower;

import com.follower.sim.FakeGame;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A follower left waiting comes back to its post after a relog, rather than
 * being drawn back beside the player as every other arrival is.
 *
 * <p>Review, 29 Sep: the spawn put every tile through the two-step landing
 * search, so a post further than two steps from the player was swapped for a
 * tile beside them, and the next tick saved that over the post. The post is
 * taken as given only where the follower could stand and walk to it from the
 * player within its leash; anywhere else it lands beside them as before.
 */
public class PostArrivalTest
{
	private static final WorldPoint PLAYER = new WorldPoint(10, 10, 0);
	private static final WorldPoint POST = new WorldPoint(20, 14, 0);

	private static FollowerEntity followerOn(FakeGame game)
	{
		return new FollowerEntity(game.client, null);
	}

	@Test
	public void aPostItCouldWalkToIsWhereItArrives()
	{
		FollowerEntity follower = followerOn(new FakeGame().withCollision());

		assertEquals(POST, follower.arrivalTile(POST, PLAYER, true));
		assertNotEquals("an ordinary arrival still lands beside the player",
			POST, follower.arrivalTile(POST, PLAYER, false));
	}

	@Test
	public void aPostItCannotStandOnIsNot()
	{
		FakeGame game = new FakeGame().withCollision().blockTile(POST.getX(), POST.getY());

		assertTrue("beside the player instead",
			followerOn(game).arrivalTile(POST, PLAYER, true).distanceTo(PLAYER) <= 2);
	}

	@Test
	public void aPostOnFireIsNot()
	{
		FollowerEntity follower = followerOn(new FakeGame().withCollision());
		follower.setHotGround(POST::equals);

		assertTrue(follower.arrivalTile(POST, PLAYER, true).distanceTo(PLAYER) <= 2);
	}

	/**
	 * A bank booth the map calls open floor: a post kept there from before
	 * the feet refused one would put it back inside at every login.
	 */
	@Test
	public void aPostInABoothIsNot()
	{
		FakeGame game = new FakeGame().withCollision().furniture(POST.getX(), POST.getY(), 0, 10, 0, 224);

		assertTrue(followerOn(game).arrivalTile(POST, PLAYER, true).distanceTo(PLAYER) <= 2);
	}

	@Test
	public void aPostWalledOffFromThePlayerIsNot()
	{
		FakeGame game = new FakeGame().withCollision();
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					game.blockTile(POST.getX() + dx, POST.getY() + dy);
				}
			}
		}

		assertTrue("standable, but nothing walks there",
			followerOn(game).arrivalTile(POST, PLAYER, true).distanceTo(PLAYER) <= 2);
	}

	@Test
	public void aPostPastItsLeashIsNot()
	{
		FollowerEntity follower = followerOn(new FakeGame().withCollision());
		WorldPoint edge = new WorldPoint(PLAYER.getX() + FollowerEntity.STAY_LEASH - 2, PLAYER.getY(), 0);
		WorldPoint past = new WorldPoint(edge.getX() + 1, edge.getY(), 0);

		assertEquals("at the edge of its reach it still comes back", edge, follower.arrivalTile(edge, PLAYER, true));
		assertTrue(follower.arrivalTile(past, PLAYER, true).distanceTo(PLAYER) <= 2);
	}
}
