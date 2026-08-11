package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechDirector;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * The one promise the director makes: an occasion always gets through.
 *
 * <p>That promise was broken within hours of being written, and in a way no
 * amount of reading would have caught. The relax check exempted occasions; the
 * settling check, added at the same time and four lines below it, did not - so
 * enter-wilderness, which is group "area" and therefore counts as scenery,
 * could be swallowed for a player who was new. A follower is only settling in
 * because the player is new, so the two conditions peak together.
 *
 * <p>The fix was to state the exemption once at the top of the method. The
 * guard against it happening again cannot be another reading of that method,
 * because the failure was one damper forgetting what the one above it knew.
 * This asserts the PROPERTY over every shipped occasion and every state the
 * director can be in, so a third damper added later has to keep the promise
 * without anyone remembering to ask it to.
 */
public class OccasionGuaranteeTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long GAP = 3_000L;
	private static final long NOW = 1_700_000_000_000L;

	/** Every state the director can be in, with a name for the failure message. */
	private static List<Object[]> everyDirectorState()
	{
		List<Object[]> states = new ArrayList<>();

		SpeechRule filler = new SpeechRule();
		filler.id = "filler";
		filler.group = "area";           // counts as lore, so it arms both dampers

		for (int sessions : new int[]{1, 2, 3, 40})
		{
			for (boolean resting : new boolean[]{false, true})
			{
				SpeechDirector director = new SpeechDirector();
				director.setBaseGapMs(GAP);
				director.setSessionCount(sessions);
				director.noteSpoke(filler, NOW);     // arms the settling damper
				if (resting)
				{
					for (int i = 0; i < 4; i++)
					{
						director.noteSpoke(filler, NOW);
					}
				}
				states.add(new Object[]{
					"session " + sessions + (resting ? ", resting" : ", listening"),
					director});
			}
		}
		return states;
	}

	@Test
	public void noOccasionCanBeHeldByAnyDamperInAnyState() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());

		List<String> broken = new ArrayList<>();
		int checked = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!rule.isOccasion())
			{
				continue;
			}
			checked++;
			for (Object[] state : everyDirectorState())
			{
				String held = ((SpeechDirector) state[1]).blocks(rule, NOW);
				if (held != null)
				{
					broken.add(rule.id + " held by \"" + held + "\" (" + state[0] + ")");
				}
			}
		}

		assertTrue("the shipped set should have plenty of occasions", checked > 50);
		assertTrue("an occasion is a line the follower must never be too busy to"
			+ " say, and these were held:\n  " + String.join("\n  ", broken),
			broken.isEmpty());
	}

	@Test
	public void theGuaranteeHoldsForAGroupTheDamperThins() throws IOException
	{
		// The specific shape of the original bug, kept as its own case because
		// it is the only shipped occasion living in a scenery group and would
		// be easy to move without noticing what it was protecting.
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule wilderness = h.rule("enter-wilderness");

		assertTrue("enter-wilderness has to stay an occasion; it is the whole"
			+ " reason the flag exists", wilderness.isOccasion());
		assertTrue("and it is still in a group the settling damper thins, which"
			+ " is exactly why the guarantee has to be unconditional",
			SpeechDirector.isLore(wilderness));

		for (Object[] state : everyDirectorState())
		{
			assertTrue("enter-wilderness held (" + state[0] + ")",
				((SpeechDirector) state[1]).blocks(wilderness, NOW) == null);
		}
	}

	@Test
	public void everyHealthWarningIsAnOccasion() throws IOException
	{
		// The content half of the same promise. A warning the player never
		// hears is worse than one they hear twice, so anything that tells them
		// to eat, cure, pray or run has to be exempt.
		Harness h = new Harness(folder.newFolder().toPath());

		// Deliberately not the whole group: "Ouch. {damage}." and "Out of
		// breath already?" are remarks about what happened, not instructions
		// about what to do, and holding those back costs nobody anything.
		List<String> mustCarry = java.util.Arrays.asList(
			"critical-hp", "low-hp", "low-prayer", "status-poisoned",
			"status-venomed", "status-skulled", "boss-while-low",
			"enter-wilderness");

		List<String> missing = new ArrayList<>();
		for (String id : mustCarry)
		{
			if (!h.rule(id).isOccasion())
			{
				missing.add(id);
			}
		}
		assertTrue("these tell the player to do something about a danger and"
			+ " must never be held: " + missing, missing.isEmpty());
	}

	@Test
	public void everyBossIdentificationIsAnOccasion() throws IOException
	{
		// Walking into a boss is the clearest case in the file: the advice is
		// only any use before the fight starts, and a player who missed it
		// because the follower was resting has been failed by the exact feature
		// meant to make them listen.
		Harness h = new Harness(folder.newFolder().toPath());

		List<String> missing = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if ("boss".equals(rule.group) && rule.priority >= 90 && !rule.isOccasion())
			{
				missing.add(rule.id);
			}
		}
		assertTrue("boss advice that could be held back: " + missing, missing.isEmpty());
	}
}
