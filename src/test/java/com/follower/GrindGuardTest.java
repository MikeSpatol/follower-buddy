package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R24's guard, measured rather than believed.
 *
 * <p>What it actually does: repeating needs a hundred ticks of sustained
 * animation to count, and resets within four quiet ticks of stopping. So
 * the guard bites during a LIVE grind - the combat stretch is the case that
 * matters, since the idle gates already keep the idle-triggered openers out
 * of anything animated - and releases almost immediately after, so a wish
 * or a question lands in the calm after the work rather than never.
 */
public class GrindGuardTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A probe with the exact guard shape the openers carry, triggered on demand. */
	private Harness guarded() throws IOException
	{
		return new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"probe\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"all\", \"conditions\": ["
				+ "{\"type\": \"none\", \"conditions\": [ {\"type\": \"repeating\"} ]},"
				+ "{\"type\": \"answered\", \"is\": \"yes\"}]},"
				+ " \"say\": [\"opened\"]}]}");
	}

	@Test
	public void theGuardBitesMidGrindAndReleasesAfter() throws IOException
	{
		Harness h = guarded();
		h.gameTicks(1);

		// A hundred and twenty ticks of the same swing: a real grind.
		h.game.animating(879);
		h.gameTicks(120);
		h.answers("yes");
		h.gameTicks(2);
		assertTrue("nothing demanding opens mid-grind", h.spoken.isEmpty());

		// The grind ends; the guard lets go within a few quiet ticks, so the
		// opener lands in the calm after the work rather than never.
		h.game.animating(-1);
		h.gameTicks(6);
		h.answers("yes");
		h.gameTicks(2);
		assertEquals("the calm after the grind is fair game",
			1, h.firedBy("probe").size());
	}

	@Test
	public void aShortTaskNeverTripsTheGuard() throws IOException
	{
		// Under a hundred ticks of activity is a task, not a grind; the
		// guard must not punish somebody who chopped three logs.
		Harness h = guarded();
		h.gameTicks(1);

		h.game.animating(879);
		h.gameTicks(40);
		h.answers("yes");
		h.gameTicks(2);
		assertEquals("forty ticks of work is not a grind",
			1, h.firedBy("probe").size());
	}
}
