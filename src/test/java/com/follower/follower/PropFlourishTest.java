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
		public void clearPoseOverride(int id)
		{
			if (poseOverride == id)
			{
				poseOverride = 0;
			}
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return new WorldPoint(3222, 3218, 0);
		}
	}

	private static final int QUILL = ErrandController.QUILL_PROP;
	private static final int SCROLL = ErrandController.SCROLL_PROP;
	private static final int WRITE = ErrandController.WRITE_SCROLL_POSE;

	private RecordingFollower follower;
	private PropFlourish flourish;
	private int held;
	private int heldLeft;

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
			public void hold(int leftItemId, int rightItemId)
			{
				heldLeft = leftItemId;
				held = rightItemId;
			}

			@Override
			public void release()
			{
				held = 0;
				heldLeft = 0;
			}
		});
	}

	@Test
	public void theScrollAndThePoseArriveTogether()
	{
		// On the same tick, and so the same rendered frame. The first version
		// held the pose back two "settle" ticks out of caution about a rebuild
		// delay the dump path does not have - and those ticks were exactly the
		// window in which the follower stood in its idle pose holding a
		// scroll, which the eye reads as wrong immediately.
		assertTrue(flourish.start(QUILL, SCROLL, WRITE, 5));
		assertTrue(flourish.isActive());
		assertTrue("it should stop to write", follower.staying);
		assertEquals("the scroll is out", SCROLL, held);
		assertEquals("and the quill with it", QUILL, heldLeft);
		assertEquals("and the pose is already playing", WRITE, follower.poseOverride);
	}

	@Test
	public void aFlourishWithNothingForTheOtherHandHoldsTheOneProp()
	{
		heldLeft = -1;
		assertTrue(flourish.start(0, 10485, 5354, 5));
		assertEquals("the one prop, through the one-handed hold", 10485, held);
		assertEquals("and the other hand never asked", -1, heldLeft);
	}

	@Test
	public void everythingGoesBackWhenTheMomentPasses()
	{
		flourish.start(QUILL, SCROLL, WRITE, 3);
		for (int i = 0; i < 10; i++)
		{
			flourish.tick();
		}

		assertFalse(flourish.isActive());
		assertEquals("the pose released", 0, follower.poseOverride);
		assertEquals("the scroll went away", 0, held);
		assertEquals("and the quill", 0, heldLeft);
		assertTrue("and the follower walks again", follower.following);
	}

	@Test
	public void anAbortPutsEverythingBackToo()
	{
		flourish.start(QUILL, SCROLL, WRITE, 20);
		flourish.tick();
		flourish.tick();
		assertEquals(WRITE, follower.poseOverride);

		flourish.abort();

		assertFalse(flourish.isActive());
		assertEquals(0, follower.poseOverride);
		assertEquals(0, held);
		assertEquals(0, heldLeft);
		assertTrue(follower.following);
	}

	/**
	 * QA, 16 Oct: the end of the gesture took off whatever pose was on, so
	 * a rest that sat it down meanwhile was stood up under it, and the
	 * rest's own stand-up never came.
	 */
	@Test
	public void aPoseSomethingElsePutOnMeanwhileStays()
	{
		// The rest's sit, the default Rest pose.
		final int sit = 10061;
		flourish.start(QUILL, SCROLL, WRITE, 3);
		follower.setPoseOverride(sit);
		for (int i = 0; i < 10; i++)
		{
			flourish.tick();
		}
		assertFalse(flourish.isActive());
		assertEquals("the sit is the rest's to take off", sit, follower.poseOverride);
		assertEquals("the scroll still went away", 0, held);
	}

	@Test
	public void aSecondGestureMidGestureIsDroppedNotQueued()
	{
		assertTrue(flourish.start(QUILL, SCROLL, WRITE, 10));
		assertFalse("a backlog of gestures stops reading as spontaneous",
			flourish.start(QUILL, SCROLL, WRITE, 10));
	}

	@Test
	public void nonsenseNeverStarts()
	{
		assertFalse(flourish.start(QUILL, 0, WRITE, 10));
		assertFalse(flourish.start(QUILL, SCROLL, 0, 10));
		assertFalse("and nothing was touched", follower.staying);
		assertEquals(0, held);
	}
}
