package com.follower.follower;

import com.follower.sim.FakeGame;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The follower must never walk through a wall.
 *
 * <p>Everything else about it is an illusion held up by obeying the same rules
 * as everything else in the world. A model sliding through stone does not look
 * like a bug in the pathing, it looks like the whole thing is fake - which it
 * is, and the point is that you should not be able to tell.
 *
 * <p>The greedy stepper used to end with a raw step "so missing collision data
 * never freezes the follower". But no data already reads as open, so that
 * branch only ever fired when collision was PRESENT and said no. Indoors, where
 * the player is often two tiles away through a partition, it fired constantly.
 */
public class WallPathingTest
{
	private FollowerEntity followerOn(FakeGame game)
	{
		return new FollowerEntity(game.client, null);
	}

	@Test
	public void aWallStopsTheGreedyStepper()
	{
		// Standing at (10,10) wanting (12,10), with a wall on the east edge of
		// (10,10). There is no legal step east, so there is no step at all.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertFalse("the wall has to be readable at all",
			follower.canStep(from, 1, 0));
		assertNull("the follower must not step through it",
			follower.stepToward(from, new WorldPoint(12, 10, 0), false));
	}

	@Test
	public void itStepsRoundTheWallWhenThereIsAWayRound()
	{
		// The same wall, but the target is up and to the right, so the stepper
		// has a legal diagonal-turned-cardinal to take instead of giving up.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		WorldPoint step = follower.stepToward(
			new WorldPoint(10, 10, 0), new WorldPoint(12, 14, 0), false);
		assertNotNull("blocked one way is not blocked every way", step);
		assertTrue("and whatever it picked has to be legal",
			follower.canStep(new WorldPoint(10, 10, 0),
				step.getX() - 10, step.getY() - 10));
	}

	@Test
	public void retracingThePlayersOwnPathMayStepAnyway()
	{
		// The one place the raw step survives. These tiles were walked by the
		// player a moment ago, so a collision reading that disagrees is the
		// reading that is wrong, and refusing would leave the follow tile
		// unknown - which is worse than trusting the footprints.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		assertNotNull("the player's own path is always walkable",
			follower.stepToward(new WorldPoint(10, 10, 0),
				new WorldPoint(12, 10, 0), true));
	}

	@Test
	public void aFullyBlockedTileIsNeverSteppedOnto()
	{
		FakeGame game = new FakeGame().withCollision();
		game.blockTile(11, 10);
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertFalse(follower.canStep(from, 1, 0));
		assertNull("a solid tile is not a step",
			follower.stepToward(from, new WorldPoint(11, 10, 0), false));
	}

	@Test
	public void noCollisionDataStillReadsAsOpen()
	{
		// An instance edge, or a scene that has not loaded. Refusing here would
		// freeze the follower for a reason that is not a wall - which is what
		// the raw fallback was originally defending against, and it is already
		// handled without it.
		FakeGame game = new FakeGame();
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertTrue("no data means no obstacle", follower.canStep(from, 1, 0));
		assertNotNull("so the follower still moves",
			follower.stepToward(from, new WorldPoint(12, 10, 0), false));
	}

	@Test
	public void aWallIsSeenFromBothSides()
	{
		// The game stores a wall on one tile's edge; the tile on the other side
		// carries the opposite bit for the same edge. Checking only the near
		// side would let the follower through half of every wall in the game.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(11, 10, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		FollowerEntity follower = followerOn(game);

		assertFalse("approaching from the west",
			follower.canStep(new WorldPoint(10, 10, 0), 1, 0));
		assertFalse("and from the east",
			follower.canStep(new WorldPoint(11, 10, 0), -1, 0));
	}

	@Test
	public void aDiagonalCannotSlipPastACornerPost()
	{
		// Cutting a corner diagonally past two walls that meet is the classic
		// way through a building, and it is exactly what the game forbids.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		FollowerEntity follower = followerOn(game);

		assertFalse("the corner is shut",
			follower.canStep(new WorldPoint(10, 10, 0), 1, 1));
	}

	// ------------------------------------------------------------- teleports

	/**
	 * Walking obeys the walls; snapping used to ignore them entirely.
	 *
	 * <p>Every teleport, plane change and scene recovery put the follower one
	 * step west of the player by arithmetic alone, and arithmetic does not
	 * know about stone. Play report: the follower teleporting onto tiles it
	 * could not stand on.
	 */
	@Test
	public void aSnapNeverLandsSomewhereItCouldNotWalkTo()
	{
		FakeGame game = new FakeGame().withCollision();
		// The tile a snap wants - one west of the player - is solid.
		game.wallAt(9, 10, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		FollowerEntity follower = followerOn(game);

		WorldPoint player = new WorldPoint(10, 10, 0);
		WorldPoint wanted = new WorldPoint(9, 10, 0);
		WorldPoint landing = follower.landingTile(wanted, player);

		assertFalse("it landed inside the scenery it was told to land in",
			wanted.equals(landing));
		// Not necessarily adjacent - going round the blocked tile can cost two
		// steps - but it must be somewhere connected to the player rather than
		// a hole on the far side of a wall.
		assertNotNull("whatever it chose has to be walkable ground",
			follower.stepToward(landing, player, false));
	}

	@Test
	public void aPlayerBoxedInGetsTheirOwnTileShared()
	{
		// Nowhere legal at all: standing on top of the player for a moment is
		// a far smaller lie than standing inside a wall, and their tile is
		// standable by definition because they are standing on it.
		FakeGame game = new FakeGame().withCollision();
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				game.wallAt(10 + dx, 10 + dy, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
			}
		}
		FollowerEntity follower = followerOn(game);

		WorldPoint player = new WorldPoint(10, 10, 0);
		assertEquals("with nowhere else to go it must share the player's tile",
			player,
			follower.landingTile(new WorldPoint(9, 10, 0), player));
	}
}
