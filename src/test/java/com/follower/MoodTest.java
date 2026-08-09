package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower's mood: one number that gives the lines a state to be said from.
 *
 * <p>Nothing in the engine decides what moves it. Rules carry a nudge and the
 * engine applies it when they fire, which means the mood inherits every trigger
 * the rule format already understands - and a new influence is an edit to
 * phrases.json rather than to any of this.
 */
public class MoodTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static String rules(String... bodies)
	{
		return "{\"version\": 1, \"rules\": [" + String.join(",", bodies) + "]}";
	}

	// ---------------------------------------------------------------- the value

	@Test
	public void aSessionStartsEven() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		assertEquals(50, h.engine.getContext().getMood());
		assertEquals("even", h.engine.getContext().getMoodBand());
	}

	@Test
	public void theBandsCoverTheWholeRangeWithNoGaps()
	{
		TriggerContext context = new TriggerContext(new com.follower.sim.FakeGame().client);
		List<String> seen = new ArrayList<>();
		for (int value = 0; value <= 100; value++)
		{
			context.adjustMood(value - context.getMood());
			String band = context.getMoodBand();
			assertTrue("mood " + value + " is in no band at all",
				TriggerContext.moodBands().contains(band));
			if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(band))
			{
				seen.add(band);
			}
		}
		assertEquals("the bands should run low to high in order",
			new ArrayList<>(TriggerContext.moodBands()), seen);
	}

	@Test
	public void moodCannotLeaveItsRange()
	{
		TriggerContext context = new TriggerContext(new com.follower.sim.FakeGame().client);

		context.adjustMood(10000);
		assertEquals("no amount of good news goes past the top", 100, context.getMood());

		context.adjustMood(-10000);
		assertEquals("nor past the bottom", 0, context.getMood());

		assertEquals("and a nudge of nothing changes nothing",
			0, context.adjustMood(0));
	}

	@Test
	public void moodDriftsBackTowardEvenSoNothingIsPermanent() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().adjustMood(45);
		assertEquals(95, h.engine.getContext().getMood());

		h.gameTicks(600);
		int after = h.engine.getContext().getMood();

		assertTrue("a good stretch should fade, not stick: still at " + after,
			after < 95);
		assertTrue("but not overshoot past even: " + after, after >= 50);
	}

	@Test
	public void aFlatMoodClimbsBackUpTheSameWay() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().adjustMood(-45);
		assertEquals(5, h.engine.getContext().getMood());

		h.gameTicks(600);
		int after = h.engine.getContext().getMood();

		assertTrue("a bad run has to be recoverable by carrying on: " + after,
			after > 5);
		assertTrue(after <= 50);
	}

	@Test
	public void anEvenMoodDoesNotDrift() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2000);
		assertEquals(50, h.engine.getContext().getMood());
	}

	// --------------------------------------------------------------- the nudge

	@Test
	public void aRuleMovesTheMoodWhenItFires() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"good\", \"group\": \"t\", \"cooldownMs\": 0, \"mood\": 15,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());

		assertEquals(65, h.engine.getContext().getMood());
	}

	@Test
	public void aRuleHeldBackByItsCooldownDoesNotMoveTheMood() throws IOException
	{
		// The state and the words have to stay one story: a rule that said
		// nothing must not have quietly changed how the follower feels.
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"good\", \"group\": \"t\", \"cooldownMs\": 600000, \"mood\": 15,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}"));

		h.gameTicks(1);
		for (int i = 0; i < 10; i++)
		{
			h.dispatch(TriggerEvent.death());
			h.gameTick();
		}

		assertEquals("ten deaths, one line, one nudge", 65, h.engine.getContext().getMood());
	}

	@Test
	public void aRuleInADisabledGroupDoesNotMoveTheMood() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"good\", \"group\": \"off\", \"cooldownMs\": 0, \"mood\": 15,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}"));
		h.engine.setDisabledGroups(java.util.Collections.singleton("off"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());

		assertEquals("a silenced rule must not move the mood either",
			50, h.engine.getContext().getMood());
	}

	// ---------------------------------------------------------------- the gate

	@Test
	public void aRuleCanRequireABand() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"gloomy\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"mood\", \"is\": \"low\"}, \"say\": [\"a\"]}"));

		h.gameTicks(2);
		assertTrue("even is not low", h.firedBy("gloomy").isEmpty());

		h.engine.getContext().adjustMood(-40);
		h.gameTicks(2);
		assertFalse("ten out of a hundred is low", h.firedBy("gloomy").isEmpty());
	}

	@Test
	public void aRuleCanRequireARange() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"narrow\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"mood\", \"minimum\": 70, \"maximum\": 75},"
				+ " \"say\": [\"a\"]}"));

		h.engine.getContext().adjustMood(20);
		h.gameTicks(2);
		assertFalse("seventy is inside seventy to seventy-five",
			h.firedBy("narrow").isEmpty());

		h.clear();
		h.engine.getContext().adjustMood(20);
		h.gameTicks(2);
		assertTrue("ninety is outside it", h.firedBy("narrow").isEmpty());
	}

	// ------------------------------------------------------------ the rule set

	@Test
	public void theShippedNudgesAreSignedTheWayTheEventReads() throws IOException
	{
		// A boss kill must not lower the mood and a death must not raise it.
		// Getting a sign backwards would be invisible except as a follower that
		// cheers up when you die.
		Harness h = new Harness(folder.newFolder().toPath());
		java.util.Map<String, Boolean> shouldBePositive = new java.util.HashMap<>();
		shouldBePositive.put("kill-boss-celebrate", true);
		shouldBePositive.put("loot-big", true);
		shouldBePositive.put("loot-nice", true);
		shouldBePositive.put("level-up", true);
		shouldBePositive.put("kill-cheer", true);
		shouldBePositive.put("death-moment", false);
		shouldBePositive.put("death", false);

		for (java.util.Map.Entry<String, Boolean> entry : shouldBePositive.entrySet())
		{
			SpeechRule rule = h.rule(entry.getKey());
			assertTrue(entry.getKey() + " carries no nudge at all", rule.mood != null);
			assertEquals(entry.getKey() + " has the wrong sign",
				entry.getValue(), rule.mood > 0);
		}
	}

	@Test
	public void everyMoodBandNamedByARuleExists() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> unknown = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			collectBands(rule, rule.when, unknown);
		}
		assertTrue("rules naming a mood band that does not exist, so they can"
			+ " never fire: " + unknown, unknown.isEmpty());
	}

	private void collectBands(SpeechRule rule, com.follower.speech.Condition condition,
		List<String> unknown)
	{
		if (condition == null)
		{
			return;
		}
		if ("mood".equalsIgnoreCase(condition.type) && condition.is != null
			&& !TriggerContext.moodBands().contains(condition.is))
		{
			unknown.add(rule.id + ": \"" + condition.is + "\"");
		}
		if (condition.conditions != null)
		{
			for (com.follower.speech.Condition child : condition.conditions)
			{
				collectBands(rule, child, unknown);
			}
		}
	}

	// ------------------------------------------------------------- a session

	@Test
	public void aGoodRunLiftsItAndABadOneDoesNot() throws IOException
	{
		// End to end through the shipped rules: the mood should follow the
		// shape of the session without anything else being told about it.
		Harness good = new Harness(folder.newFolder().toPath());
		good.gameTicks(1);
		for (int i = 0; i < 6; i++)
		{
			good.dispatch(TriggerEvent.kill(2, "Zulrah", 725));
			good.gameTicks(40);
		}
		assertTrue("six boss kills should have cheered it up, mood is "
			+ good.engine.getContext().getMood(),
			good.engine.getContext().getMood() > 55);

		Harness bad = new Harness(folder.newFolder().toPath());
		bad.gameTicks(1);
		for (int i = 0; i < 4; i++)
		{
			bad.dispatch(TriggerEvent.death());
			bad.gameTicks(120);
		}
		assertTrue("four deaths should have flattened it, mood is "
			+ bad.engine.getContext().getMood(),
			bad.engine.getContext().getMood() < 45);
	}

	@Test
	public void theLowLinesOnlyTurnUpWhenThingsAreActuallyBad() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(200);
		assertTrue("the gloomy lines must not appear in an ordinary session",
			h.firedBy("mood-low-idle").isEmpty());

		h.engine.getContext().adjustMood(-40);
		h.gameTicks(200);
		assertFalse("and they should appear once it is",
			h.firedBy("mood-low-idle").isEmpty());
	}
}
