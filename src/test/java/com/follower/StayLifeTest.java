package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import com.follower.follower.SpectateController; // public:cut spectate,spectate-keep-fight-lines,spectate-shield
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
			true, false, false, false, false, false, false, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else true, false, false, false, false, false, false));
		assertTrue("wandering is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, true, false, false, false, false, false, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, true, false, false, false, false, false));
		// public:cut errands
		assertTrue("an errand is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, true, false, false, false, false)); // public:cut spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, false, true, false, false, false));
		// public:end errands
		// public:cut spectate,spectate-keep-fight-lines
		assertTrue("spectating is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, true, false, false, false)); // public:cut errands
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, false, true, false, false, false));
		// public:end spectate,spectate-keep-fight-lines
		assertTrue("thrall work is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, true, false, false, false, false, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, true, false, false, false, false));
		// The second play report: a teleport plants the follower for its
		// cast, and that plant read as a Stay command.
		assertTrue("planting for an emote is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, true, false, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, true, false, false));
		assertTrue("minuting a meeting is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, false, true, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, true, false));
		// The seat the skilling company takes by the tree parks it the same way.
		assertTrue("keeping company at the tree is not a post",
			!FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, false, false, true)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, false, false, true));
		assertTrue("and following at heel is nothing at all",
			!FollowerPlugin.stayIsPlayerCommanded(false, false, false, false, false, false, false, false)); // public:cut errands,spectate,spectate-keep-fight-lines
			// public:else !FollowerPlugin.stayIsPlayerCommanded(false, false, false, false, false, false, false));
	}

	// public:cut spectate,spectate-keep-fight-lines,spectate-shield
	/**
	 * The gap between a fight ending and the follower walking back.
	 *
	 * <p>stop() clears the spectating flag straight away, but the follower is
	 * held where it stands for a few more ticks so the closing cast can play
	 * out. Asking only whether it is spectating reports false through that
	 * window while the follower is still parked, which reads as a Stay the
	 * player asked for - and the follower announced taking the post every time
	 * it finished watching a fight.
	 */
	@Test
	public void theWindDownAfterAFightIsNotACommandedStay()
	{
		assertTrue("during the fight",
			SpectateController.spectateOwnsTheFeet(true, 0));
		assertTrue("and while it finishes the spell before walking back",
			SpectateController.spectateOwnsTheFeet(false, 4));
		assertFalse("only once it has actually been released",
			SpectateController.spectateOwnsTheFeet(false, 0));

		assertFalse("so the post is not announced on the way back",
			FollowerPlugin.stayIsPlayerCommanded(true, false, false, false, // public:cut errands
			// public:else FollowerPlugin.stayIsPlayerCommanded(true, false, false,
				SpectateController.spectateOwnsTheFeet(false, 4), false, false, false));
	}

	// public:end spectate,spectate-keep-fight-lines,spectate-shield
	@Test
	public void theWanderGateOpensLateAndClosesForTheRest()
	{
		// The window the play reports set: not before the stop has proven to
		// be a stay (48 seconds - "they start to wander too soon"), and not
		// once the rest has taken over. Thieving overrides it entirely,
		// because there the follower being underfoot IS the problem.
		assertTrue("a settled stop earns the drift",
			FollowerPlugin.wanderAllowed(true, false, true, false, 80, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("but not before it has proven to be a stay",
			!FollowerPlugin.wanderAllowed(true, false, true, false, 79, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("and not once the rest has taken over",
			!FollowerPlugin.wanderAllowed(true, false, true, false, 500, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("thieving wanders without waiting to be invited",
			FollowerPlugin.wanderAllowed(true, false, true, true, 0, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("busy always wins",
			!FollowerPlugin.wanderAllowed(true, true, true, true, 200, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("no follower, no drift",
			!FollowerPlugin.wanderAllowed(true, false, false, false, 200, FollowerPlugin.WANDER_AFTER_TICKS));
		assertTrue("and the setting is the law",
			!FollowerPlugin.wanderAllowed(false, false, true, false, 200, FollowerPlugin.WANDER_AFTER_TICKS));

		// Wander after, set to its shortest: three seconds is five ticks.
		assertTrue("a shorter wait opens the window sooner",
			FollowerPlugin.wanderAllowed(true, false, true, false, 5, 5));
		assertTrue(!FollowerPlugin.wanderAllowed(true, false, true, false, 4, 5));
		assertTrue("and never on the tick the player stops",
			!FollowerPlugin.wanderAllowed(true, false, true, false, 0, 0));
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
