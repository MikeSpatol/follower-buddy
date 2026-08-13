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
