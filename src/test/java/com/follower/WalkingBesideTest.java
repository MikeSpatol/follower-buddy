package com.follower;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * On a long run over open ground the follower draws level and walks at
 * the player's shoulder; the moment the ground narrows it drops back
 * behind. Only the choice of follow tile is tested here - the path model
 * under it is the one the trace sessions verified and is left alone.
 */
public class WalkingBesideTest
{
	/** Every tile is ground except the ones blocked by name. */
	private static final class OpenGroundFollower extends FollowerEntity
	{
		final Set<WorldPoint> blocked = new HashSet<>();

		OpenGroundFollower()
		{
			super(null, null);
		}

		@Override
		boolean standable(WorldPoint at)
		{
			return at != null && !blocked.contains(at);
		}

		@Override
		boolean canStep(WorldPoint from, int dx, int dy)
		{
			return (dx != 0 || dy != 0)
				&& !blocked.contains(new WorldPoint(from.getX() + dx, from.getY() + dy, from.getPlane()));
		}

		@Override
		public boolean furnished(WorldPoint at)
		{
			return false;
		}
	}

	private OpenGroundFollower follower;
	private long now = 10_000;

	@Before
	public void setUp()
	{
		follower = new OpenGroundFollower();
		follower.setWalksBeside(true);
	}

	/** One step north per observation, and what the follower was told to follow to. */
	private WorldPoint stepNorth(int y)
	{
		WorldPoint previous = new WorldPoint(3200, y - 1, 0);
		WorldPoint current = new WorldPoint(3200, y, 0);
		now += 600;
		return follower.followTileFor(previous, current, previous, 1, now);
	}

	@Test
	public void itTrailsForTheFirstStepsAndThenDrawsLevel()
	{
		for (int i = 1; i < FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			assertEquals("behind, step " + i, new WorldPoint(3200, 3400 + i - 1, 0), stepNorth(3400 + i));
			assertFalse(follower.isWalkingBeside());
		}
		WorldPoint at = stepNorth(3400 + FollowerEntity.BESIDE_AFTER_STEPS);
		assertTrue(follower.isWalkingBeside());
		assertEquals("level with them", 3400 + FollowerEntity.BESIDE_AFTER_STEPS, at.getY());
		assertEquals("one to the side", 1, Math.abs(at.getX() - 3200));
	}

	/** The entity's own state, which the stubs here have no other way to set. */
	private void set(String name, Object value)
	{
		try
		{
			Field field = FollowerEntity.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(follower, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(name, e);
		}
	}

	@Test
	public void itDrawsLevelOnWhicheverSideItIsAlreadyOn()
	{
		// Walking north with the follower a tile east of the line: the right
		// shoulder. Crossing behind the player to reach the left one is the
		// hop the side choice is there to spare it.
		set("tile", new WorldPoint(3201, 3400, 0));
		WorldPoint at = null;
		for (int i = 1; i <= FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			at = stepNorth(3400 + i);
		}
		assertTrue(follower.isWalkingBeside());
		assertEquals("the east shoulder", 3201, at.getX());
	}

	@Test
	public void aStayOrAGoalKeepsItBehindHoweverLongTheRun()
	{
		// Only the follow tile changes, and only while nothing else has the
		// feet: a stay or a goal owns them, and the shoulder must not pull
		// against either.
		set("stayTile", new WorldPoint(3200, 3390, 0));
		for (int i = 1; i <= 3 * FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			assertEquals("a stay, step " + i, 3200, stepNorth(3400 + i).getX());
		}
		assertFalse(follower.isWalkingBeside());

		set("stayTile", null);
		set("goalTile", new WorldPoint(3205, 3460, 0));
		for (int i = 1; i <= 3 * FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			assertEquals("a goal, step " + i, 3200, stepNorth(3430 + i).getX());
		}
		assertFalse(follower.isWalkingBeside());

		// Let go, and the run was long enough all along.
		set("goalTile", null);
		assertEquals(1, Math.abs(stepNorth(3461).getX() - 3200));
		assertTrue(follower.isWalkingBeside());
	}

	@Test
	public void aPauseInTheRunStartsTheCountAgain()
	{
		for (int i = 1; i <= FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			stepNorth(3400 + i);
		}
		assertTrue(follower.isWalkingBeside());
		now += FollowerEntity.BESIDE_PAUSE_MS + 1;
		WorldPoint at = stepNorth(3400 + FollowerEntity.BESIDE_AFTER_STEPS + 1);
		assertFalse("they stopped: back behind", follower.isWalkingBeside());
		assertEquals(3200, at.getX());
	}

	@Test
	public void aHedgeDropsItBackBehindAndItWaitsBeforeTryingAgain()
	{
		for (int i = 1; i <= FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			stepNorth(3400 + i);
		}
		assertTrue(follower.isWalkingBeside());
		int y = 3400 + FollowerEntity.BESIDE_AFTER_STEPS;
		// A hedge along both flanks for a stretch.
		for (int i = 1; i <= 6; i++)
		{
			follower.blocked.add(new WorldPoint(3199, y + i, 0));
			follower.blocked.add(new WorldPoint(3201, y + i, 0));
		}
		WorldPoint first = stepNorth(y + 1);
		assertEquals("one miss is forgiven", 3200, first.getX());
		assertTrue(follower.isWalkingBeside());
		stepNorth(y + 2);
		assertFalse("two in a row and it drops back", follower.isWalkingBeside());

		int retried = 0;
		for (int i = 3; i <= 6 + FollowerEntity.BESIDE_RETRY_STEPS; i++)
		{
			WorldPoint at = stepNorth(y + i);
			if (at.getX() != 3200)
			{
				retried = i;
				break;
			}
		}
		assertTrue("it tries again once the ground opens, after a few steps: " + retried,
			retried > 6 && retried <= 6 + FollowerEntity.BESIDE_RETRY_STEPS);
	}

	@Test
	public void switchedOffItTrailsHoweverLongTheRun()
	{
		follower.setWalksBeside(false);
		for (int i = 1; i <= 3 * FollowerEntity.BESIDE_AFTER_STEPS; i++)
		{
			assertEquals(3200, stepNorth(3400 + i).getX());
		}
		assertFalse(follower.isWalkingBeside());
	}

	@Test
	public void theFlankIsOneStepToTheSideOfTheLineOfTravel()
	{
		WorldPoint previous = new WorldPoint(10, 10, 0);
		assertEquals("north, left is west", new WorldPoint(9, 11, 0),
			FollowerEntity.flankTile(previous, new WorldPoint(10, 11, 0), 1));
		assertEquals("north, right is east", new WorldPoint(11, 11, 0),
			FollowerEntity.flankTile(previous, new WorldPoint(10, 11, 0), -1));
		assertEquals("east, left is north", new WorldPoint(11, 11, 0),
			FollowerEntity.flankTile(previous, new WorldPoint(11, 10, 0), 1));
		assertEquals("a diagonal run has a diagonal flank", new WorldPoint(10, 12, 0),
			FollowerEntity.flankTile(previous, new WorldPoint(11, 11, 0), 1));
		assertNull(FollowerEntity.flankTile(previous, previous, 1));

		assertEquals("already on the right", -1,
			FollowerEntity.sideOf(previous, new WorldPoint(10, 11, 0), new WorldPoint(11, 9, 0)));
		assertEquals("already on the left", 1,
			FollowerEntity.sideOf(previous, new WorldPoint(10, 11, 0), new WorldPoint(9, 9, 0)));
		assertEquals("on the line: left", 1,
			FollowerEntity.sideOf(previous, new WorldPoint(10, 11, 0), new WorldPoint(10, 9, 0)));
	}
}
