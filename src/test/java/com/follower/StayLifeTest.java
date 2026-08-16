package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R25: Stay mode has a life. Taking the post is acknowledged, the reunion is
 * noticed, and none of it leaks into the stays the follower's own machinery
 * takes - the plugin only marks a PLAYER-commanded Stay, which is what the
 * staying flag means here.
 */
public class StayLifeTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	private Harness parked() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(clock::get);
		h.gameTicks(2);
		h.clear();
		return h;
	}

	private void ticksPass(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			clock.addAndGet(600L);
			h.gameTick();
		}
	}

	@Test
	public void thePostIsTakenAndTheReunionNoticed() throws IOException
	{
		Harness h = parked();

		h.engine.getContext().setFollowerStaying(true);
		ticksPass(h, 5);
		assertEquals("taking the post is worth a word",
			1, h.firedBy("stay-start").size());

		// The player wanders off and comes back; the plugin marks the return
		// as a reunion boundary, and the parked follower notices.
		ticksPass(h, 100);
		h.engine.getContext().noteBoundary("reunion");
		ticksPass(h, 5);
		assertEquals("the absence ending is noticed",
			1, h.firedBy("stay-reunion").size());
	}

	@Test
	public void onlyThePlayersOwnStayCounts()
	{
		// Every exclusion is a system that parks the follower through the
		// same stayAt. The wander is the one the first version forgot, and
		// the real-play transcript showed the cost: "this spot is mine now"
		// thirty times in an afternoon of the follower's own drifting.
		assertTrue(FollowerPlugin.stayIsPlayerCommanded(
			true, false, false, false, false, false));
		assertTrue("wandering is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, true, false, false, false, false));
		assertTrue("an errand is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, true, false, false));
		assertTrue("spectating is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, true, false));
		assertTrue("thrall work is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, true, false, false, false));
		// The second play report: a teleport plants the follower for its
		// cast, and that plant read as a Stay command.
		assertTrue("planting for an emote is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, true));
		assertTrue("and following at heel is nothing at all",
			!FollowerPlugin.stayIsPlayerCommanded(false, false, false, false, false, false));
	}

	@Test
	public void theWanderGateOpensLateAndClosesForTheRest()
	{
		// The window the play reports set: not before the stop has proven to
		// be a stay (48 seconds - "they start to wander too soon"), and not
		// once the rest has taken over. Thieving overrides it entirely,
		// because there the follower being underfoot IS the problem.
		assertTrue("a settled stop earns the drift",
			FollowerPlugin.wanderAllowed(true, false, true, false, 80));
		assertTrue("but not before it has proven to be a stay",
			!FollowerPlugin.wanderAllowed(true, false, true, false, 79));
		assertTrue("and not once the rest has taken over",
			!FollowerPlugin.wanderAllowed(true, false, true, false, 500));
		assertTrue("thieving wanders without waiting to be invited",
			FollowerPlugin.wanderAllowed(true, false, true, true, 0));
		assertTrue("busy always wins",
			!FollowerPlugin.wanderAllowed(true, true, true, true, 200));
		assertTrue("no follower, no drift",
			!FollowerPlugin.wanderAllowed(true, false, false, false, 200));
		assertTrue("and the setting is the law",
			!FollowerPlugin.wanderAllowed(false, false, true, false, 200));
	}

	@Test
	public void aReunionMeansNothingToAFollowerAtHeel() throws IOException
	{
		// The boundary alone must not be enough: stay-reunion requires the
		// follower to actually be ON a stay, or a stray reunion mark from a
		// cleared stay would have it greeting nobody.
		Harness h = parked();
		h.engine.getContext().noteBoundary("reunion");
		ticksPass(h, 5);
		assertTrue("no post, no reunion", h.firedBy("stay-reunion").isEmpty());
	}
}
