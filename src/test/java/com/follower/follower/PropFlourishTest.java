package com.follower.follower;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rule-sized writing gesture: stop, scroll out, pose, everything back.
 *
 * <p>Same lifecycle promises as the errand's scroll handling, tested the same
 * way: the prop leads the pose by the settle ticks, the cleanup runs whole
 * from both the finish and the abort, and a second request mid-gesture is
 * dropped rather than queued.
 */
public class PropFlourishTest
{
	/** Records what it was asked; does none of it. */
	private static final class RecordingFollower extends FollowerEntity
	{
		boolean staying;
		boolean following = true;
		int poseOverride;

		RecordingFollower()
		{
			super(null, null);
		}

		@Override
		public void stayHere()
		{
			staying = true;
			following = false;
		}

		@Override
		public void resumeFollowing()
		{
			staying = false;
			following = true;
		}

		@Override
		public void setPoseOverride(int id)
		{
			poseOverride = id;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return new WorldPoint(3222, 3218, 0);
		}
	}

	private RecordingFollower follower;
	private PropFlourish flourish;
	private int held;

	@Before
	public void setUp()
	{
		follower = new RecordingFollower();
		flourish = new PropFlourish(follower, new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				held = itemId;
			}

			@Override
			public void release()
			{
				held = 0;
			}
		});
	}

	@Test
	public void theScrollLeadsAndThePoseFollows()
	{
		assertTrue(flourish.start(10485, 5354, 5));
		assertTrue(flourish.isActive());
		assertTrue("it should stop to write", follower.staying);
		assertEquals("the scroll comes out immediately", 10485, held);
		assertEquals("but the pose waits for the rebuild", 0, follower.poseOverride);

		flourish.tick();
		flourish.tick();
		assertEquals("two ticks later the pose starts", 5354, follower.poseOverride);
	}

	@Test
	public void everythingGoesBackWhenTheMomentPasses()
	{
		flourish.start(10485, 5354, 3);
		for (int i = 0; i < 10; i++)
		{
			flourish.tick();
		}

		assertFalse(flourish.isActive());
		assertEquals("the pose released", 0, follower.poseOverride);
		assertEquals("the scroll went away", 0, held);
		assertTrue("and the follower walks again", follower.following);
	}

	@Test
	public void anAbortPutsEverythingBackToo()
	{
		flourish.start(10485, 5354, 20);
		flourish.tick();
		flourish.tick();
		assertEquals(5354, follower.poseOverride);

		flourish.abort();

		assertFalse(flourish.isActive());
		assertEquals(0, follower.poseOverride);
		assertEquals(0, held);
		assertTrue(follower.following);
	}

	@Test
	public void aSecondGestureMidGestureIsDroppedNotQueued()
	{
		assertTrue(flourish.start(10485, 5354, 10));
		assertFalse("a backlog of gestures stops reading as spontaneous",
			flourish.start(10485, 5354, 10));
	}

	@Test
	public void nonsenseNeverStarts()
	{
		assertFalse(flourish.start(0, 5354, 10));
		assertFalse(flourish.start(10485, 0, 10));
		assertFalse("and nothing was touched", follower.staying);
		assertEquals(0, held);
	}
}
