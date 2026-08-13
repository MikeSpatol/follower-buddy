package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import java.util.Collections;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R23: one like and one grudge the record can never argue away.
 *
 * <p>The earned override exists because experience should normally outrank
 * the roll - but a follower the ledger can always argue around has no
 * opinions of its own, and over-adaptation reads as inauthentic. The two
 * core places hold whatever happens there; everywhere else the override
 * keeps working exactly as before, which the control half of each test
 * pins down.
 */
public class UnshakableTasteTest
{
	private static final int HERE = new WorldPoint(3222, 3218, 0).getRegionID();

	private TriggerContext standingHere()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.refresh();
		return context;
	}

	@Test
	public void theCoreFondnessSurvivesAnyAmountOfBadLuck()
	{
		TriggerContext context = standingHere();
		context.setTraits(Collections.singleton(HERE), Collections.emptySet());
		context.setCoreTastes(HERE, 0);

		context.notePlaceFeeling(-30);
		context.notePlaceFeeling(-30);
		context.notePlaceFeeling(-30);

		assertTrue("the core fondness holds whatever the ledger says",
			context.feelsAbout("liked"));
		assertFalse(context.feelsAbout("disliked"));
	}

	@Test
	public void theCoreGrudgeSurvivesAnyAmountOfGoodFortune()
	{
		TriggerContext context = standingHere();
		context.setTraits(Collections.emptySet(), Collections.singleton(HERE));
		context.setCoreTastes(0, HERE);

		context.notePlaceFeeling(30);
		context.notePlaceFeeling(30);
		context.notePlaceFeeling(30);

		assertTrue("the core grudge holds whatever the ledger says",
			context.feelsAbout("disliked"));
		assertFalse(context.feelsAbout("liked"));
	}

	@Test
	public void theEarnedVerdictNeverContradictsTheCore() throws java.io.IOException
	{
		// Round-1 finding from the deep-testing pass: with the raw score at
		// -80 in a core-liked region, place-earned-dislike said "I hate it
		// here" in the one place the follower cannot be argued out of
		// liking, while defended-like said the opposite moments later. The
		// earned verdicts now defer to feelsAbout, which the core owns.
		com.follower.sim.Harness h = new com.follower.sim.Harness(
			folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().setTraits(Collections.singleton(HERE),
			Collections.emptySet());
		h.engine.getContext().setCoreTastes(HERE, 0);
		for (int i = 0; i < 4; i++)
		{
			h.engine.getContext().notePlaceFeeling(-30);
		}

		// The 8% chance flicker re-arms the rule constantly; three hundred
		// ticks would be more than enough for it to fire if it were allowed.
		h.gameTicks(300);
		assertTrue("the record cannot put hate in a core-fond mouth",
			h.firedBy("place-earned-dislike").isEmpty());
		assertFalse("the defence is what speaks for the disagreement",
			h.firedBy("defended-like").isEmpty());
	}

	@org.junit.Rule
	public final org.junit.rules.TemporaryFolder folder =
		new org.junit.rules.TemporaryFolder();

	@Test
	public void everywhereElseExperienceStillOutranksTheRoll()
	{
		// The control: a rolled fondness that is NOT core concedes to the
		// record, exactly as it did before cores existed.
		TriggerContext context = standingHere();
		context.setTraits(Collections.singleton(HERE), Collections.emptySet());
		context.setCoreTastes(0, 0);

		context.notePlaceFeeling(-30);
		context.notePlaceFeeling(-30);
		context.notePlaceFeeling(-30);

		assertTrue("without a core, the override still wins",
			context.feelsAbout("disliked"));
		assertFalse(context.feelsAbout("liked"));
	}
}
