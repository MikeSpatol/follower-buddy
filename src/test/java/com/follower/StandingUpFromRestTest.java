package com.follower;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The rest lets go a tick after whatever ended it, and an errand with no
 * trip can start on the seated follower in that tick: the scroll and the
 * reading pose for field notes, the bend for a bootlace. Letting go used to
 * clear whatever pose was on and play the sit's stand-up over anything, so
 * the notes were written standing idle with the scroll out and the bend was
 * cut off a tick in.
 */
public class StandingUpFromRestTest
{
	/** The Rest pose setting's default, the game's own sit emote. */
	private static final int SIT = new FollowerConfig()
	{
	}.restAnimation();

	private boolean emoting;
	private final List<Integer> played = new ArrayList<>();

	private final FollowerEntity follower = new FollowerEntity(null, null)
	{
		@Override
		public boolean isEmotePlaying()
		{
			return emoting;
		}

		@Override
		public void playAnimation(int animationId)
		{
			played.add(animationId);
		}
	};

	@Test
	public void fromTheSitItStandsUp()
	{
		follower.setPoseOverride(SIT);
		FollowerPlugin.letGoOfTheSit(follower, SIT);
		assertEquals(0, follower.getPoseOverride());
		assertEquals("the sit's own stand-up", 1, played.size());
	}

	@Test
	public void fieldNotesStartedOnTheSitKeepTheirReadingPose()
	{
		follower.setPoseOverride(SIT);
		// The errand's first tick, before the rest has noticed it.
		follower.setPoseOverride(ErrandController.READ_SCROLL_POSE);
		FollowerPlugin.letGoOfTheSit(follower, SIT);
		assertEquals("still reading", ErrandController.READ_SCROLL_POSE, follower.getPoseOverride());
		assertTrue("and no stand-up over it", played.isEmpty());
	}

	@Test
	public void aBendStartedOnTheSitIsNotCutOff()
	{
		follower.setPoseOverride(SIT);
		emoting = true;
		FollowerPlugin.letGoOfTheSit(follower, SIT);
		assertTrue("the bend plays on", played.isEmpty());
		assertEquals("with the sit gone from under it", 0, follower.getPoseOverride());
	}
}
