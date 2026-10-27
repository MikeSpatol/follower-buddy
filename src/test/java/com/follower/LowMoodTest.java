package com.follower;

import java.io.IOException;
import java.util.HashSet;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The low-mood arc: what a bad day changes, and the way back up.
 *
 * <p>Three visible pieces. The Talk-to box sharpens "How have you been?" into
 * "You all right?" and asking it IS the comfort (that half lives in
 * {@link TalkScriptTest}). The social offers go quiet at the bottom band, so
 * a low day has friction the player can feel without anything functional
 * being withheld. And when the rolled temperament and the earned record
 * disagree about a place, the follower concedes the facts while keeping the
 * feeling, which is the one moment the temperament is visible as one.
 */
public class LowMoodTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void beingAskedAfterLandsAsALift() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().adjustMood(-40);
		assertEquals("a genuinely bad day", "low",
			h.engine.getContext().getMoodBand());
		int before = h.engine.getContext().getMood();

		// What the allright-a node latches: the box closes, the rules hear it.
		h.answers("comforted");
		h.gameTicks(10);

		assertEquals("one soft coda after the box closes",
			1, h.firedBy("comforted").size());
		assertEquals("and being asked after is worth something",
			before + 6, h.engine.getContext().getMood());
	}

	@Test
	public void theOffersHoldTheirTongueOnTheWorstDay() throws IOException
	{
		// ask-outing wants idle 60..400 with a 3% roll per evaluation; over a
		// whole idle stretch it is near-certain to fire if its guard is gone.
		// With the guard present this is deterministic silence, so the test
		// can never flake in the failing direction - it is the behavioural
		// half of the structural lint in RuleSetIntegrityTest.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().adjustMood(-40);

		h.gameTicks(420);
		assertTrue("no outing is proposed from the bottom of the band",
			h.firedBy("ask-outing").isEmpty());
		assertTrue("no game either", h.firedBy("offer-game").isEmpty());
	}

}
