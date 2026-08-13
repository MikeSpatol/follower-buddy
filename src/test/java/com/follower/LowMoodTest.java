package com.follower;

import com.follower.sim.Harness;
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

	@Test
	public void theGrudgeConcedesTheFactsAndKeepsItself() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		// Rolled dislike for where we are standing, against an earned record
		// good enough that feelsAbout has flipped to liked. The defence fires
		// on the disagreement itself.
		int here = new WorldPoint(3222, 3218, 0).getRegionID();
		h.engine.getContext().setTraits(
			new HashSet<>(java.util.Collections.singletonList(here + 1)),
			new HashSet<>(java.util.Collections.singletonList(here)));
		h.engine.getContext().notePlaceFeeling(30);
		h.engine.getContext().notePlaceFeeling(30);
		assertTrue("the ledger has won the argument feelsAbout can see",
			h.engine.getContext().feelsAbout("liked"));

		h.gameTicks(12);
		assertEquals("so the follower concedes it, once",
			1, h.firedBy("defended-dislike").size());
	}

	@Test
	public void loyaltyArguesWithTheRecordTheSameWay() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		int here = new WorldPoint(3222, 3218, 0).getRegionID();
		h.engine.getContext().setTraits(
			new HashSet<>(java.util.Collections.singletonList(here)),
			new HashSet<>(java.util.Collections.singletonList(here + 1)));
		h.engine.getContext().notePlaceFeeling(-30);
		h.engine.getContext().notePlaceFeeling(-30);
		assertTrue("nothing but trouble here, says the record",
			h.engine.getContext().feelsAbout("disliked"));

		h.gameTicks(12);
		assertEquals("and the fondness stands its ground, once",
			1, h.firedBy("defended-like").size());
	}
}
