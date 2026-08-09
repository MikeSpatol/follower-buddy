package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Noticing repetition: how many, and how long.
 *
 * <p>Both are the same idea from the player's side - the follower has been
 * paying attention - and they are the cheapest way to make it seem so. The
 * count is kept where the kill is raised so the tally and the event can never
 * disagree; the duration is measured off the animation, which means it covers
 * every skill at once without knowing what any of them are.
 */
public class CountingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static String rules(String... bodies)
	{
		return "{\"version\": 1, \"rules\": [" + String.join(",", bodies) + "]}";
	}

	// ---------------------------------------------------------------- tallies

	@Test
	public void aTallyCountsEachThingSeparately() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		assertEquals(1, h.engine.getContext().tally("kill:goblin"));
		assertEquals(2, h.engine.getContext().tally("kill:goblin"));
		assertEquals("a different thing has its own count",
			1, h.engine.getContext().tally("kill:rat"));
		assertEquals(2, h.engine.getContext().getTally("kill:goblin"));
		assertEquals("something never counted is zero, not an error",
			0, h.engine.getContext().getTally("kill:dragon"));
	}

	@Test
	public void everyFiresOnTheMultiplesAndNotBetween() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"milestone\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"npcKill\", \"every\": 5},"
				+ " \"say\": [\"{count}\"]}"));

		h.gameTicks(1);
		for (int count = 1; count <= 20; count++)
		{
			h.dispatch(TriggerEvent.kill(1, "Goblin", 5, count));
			h.gameTick();
		}

		assertEquals("fifth, tenth, fifteenth, twentieth", 4, h.firedBy("milestone").size());
		assertEquals("5", h.firedBy("milestone").get(0).text);
		assertEquals("20", h.firedBy("milestone").get(3).text);
	}

	@Test
	public void aKillWithNoCountNeverTripsAMilestone() throws IOException
	{
		// count 0 is "nobody counted", which must not read as a multiple of
		// everything.
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"milestone\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"npcKill\", \"every\": 5}, \"say\": [\"a\"]}"));

		h.gameTicks(1);
		for (int i = 0; i < 10; i++)
		{
			h.dispatch(TriggerEvent.kill(1, "Goblin", 5));
			h.gameTick();
		}

		assertTrue(h.firedBy("milestone").isEmpty());
	}

	@Test
	public void aKillRuleWithNoEveryStillFiresOnEveryKill() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"each\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"npcKill\"}, \"say\": [\"a\"]}"));

		h.gameTicks(1);
		for (int count = 1; count <= 5; count++)
		{
			h.dispatch(TriggerEvent.kill(1, "Goblin", 5, count));
			h.gameTick();
		}

		assertEquals("adding every must not have changed the plain case",
			5, h.firedBy("each").size());
	}

	@Test
	public void theCountPlaceholderCarriesTheNumber() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"tally\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"npcKill\"},"
				+ " \"say\": [\"that is {count} of {npc}\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.kill(1, "Goblin", 5, 42));

		assertEquals("that is 42 of Goblin", h.spoken.get(0).text);
	}

	// -------------------------------------------------------------- repeating

	@Test
	public void doingTheSameThingClimbsTheCounter() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.animating(879);
		h.gameTicks(30);

		assertTrue("thirty ticks of one animation should have been noticed",
			h.engine.getContext().getRepeatingTicks() >= 25);
		assertEquals(879, h.engine.getContext().getRepeatingAnimation());
	}

	@Test
	public void aBriefGapDoesNotResetIt() throws IOException
	{
		// Most gathering drops to no animation for a tick between swings, and
		// treating that as stopping would hold the count at zero forever.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.animating(879);
		h.gameTicks(20);
		int before = h.engine.getContext().getRepeatingTicks();

		h.game.animating(-1);
		h.gameTicks(2);
		h.game.animating(879);
		h.gameTicks(5);

		assertTrue("a two tick pause is a swing, not a stop: was " + before
				+ ", now " + h.engine.getContext().getRepeatingTicks(),
			h.engine.getContext().getRepeatingTicks() > before);
	}

	@Test
	public void actuallyStoppingResetsIt() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.animating(879);
		h.gameTicks(30);

		h.game.animating(-1);
		h.gameTicks(20);

		assertEquals("standing about is not doing something",
			0, h.engine.getContext().getRepeatingTicks());
	}

	@Test
	public void switchingActivityStartsTheCountAgain() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.animating(879);
		h.gameTicks(30);

		h.game.animating(624);
		h.gameTicks(3);

		assertEquals(624, h.engine.getContext().getRepeatingAnimation());
		assertTrue("a different activity is a new count, not a continuation",
			h.engine.getContext().getRepeatingTicks() <= 3);
	}

	@Test
	public void aRuleCanWaitForAWhileOfTheSameThing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"noticed\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"repeating\", \"ticks\": 50},"
				+ " \"say\": [\"still at it\"]}"));

		h.game.animating(879);
		h.gameTicks(20);
		assertTrue("twenty ticks is not yet a while", h.firedBy("noticed").isEmpty());

		h.gameTicks(40);
		assertFalse("sixty is", h.firedBy("noticed").isEmpty());
	}

	@Test
	public void aRepeatingRuleCanNameTheAnimationsItCaresAbout() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), rules(
			"{\"id\": \"chopping\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"repeating\", \"ticks\": 20, \"ids\": [879]},"
				+ " \"say\": [\"timber\"]}"));

		h.game.animating(624);
		h.gameTicks(40);
		assertTrue("a different activity must not match", h.firedBy("chopping").isEmpty());

		h.game.animating(879);
		h.gameTicks(40);
		assertFalse(h.firedBy("chopping").isEmpty());
	}

	// ------------------------------------------------------------ the rule set

	@Test
	public void theShippedMilestonesRiseInOrder() throws IOException
	{
		// The hundredth must outrank the twenty-fifth, or the round number
		// loses to the smaller one when both come up on the same kill.
		Harness h = new Harness(folder.newFolder().toPath());

		assertTrue("the hundred milestone must win when they coincide",
			h.rule("kill-tally-hundred").priority > h.rule("kill-tally").priority);
		assertEquals(25, h.rule("kill-tally").when.every.intValue());
		assertEquals(100, h.rule("kill-tally-hundred").when.every.intValue());
		assertTrue("the longer wait must outrank the shorter one",
			h.rule("repeating-a-long-while").priority
				> h.rule("repeating-a-while").priority);
	}
}
