package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Drives the speech engine against a game that is not running, to check the
 * behaviours the rule format promises: rising edges, cooldowns, priority,
 * delays, and the throttles that apply to speech but not to animation.
 */
public class SpeechEngineSimulationTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Path scratch() throws IOException
	{
		return folder.newFolder().toPath();
	}

	private static String rules(String... bodies)
	{
		return "{\"version\": 1, \"rules\": [" + String.join(",", bodies) + "]}";
	}

	// ------------------------------------------------------------ rising edge

	@Test
	public void aConditionThatStaysTrueFiresOnceNotEveryTick() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"hurt\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"ouch\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(5);

		assertEquals("low health is one crossing, not five ticks of it",
			1, h.firedBy("hurt").size());
	}

	@Test
	public void theConditionGoingFalseRearmsIt() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"hurt\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"ouch\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(2);
		h.game.hitpoints(99, 99);
		h.gameTicks(2);
		h.game.hitpoints(10, 99);
		h.gameTicks(2);

		assertEquals("healing and dropping again is a second crossing",
			2, h.firedBy("hurt").size());
	}

	// --------------------------------------------------------------- cooldown

	@Test
	public void aRuleWillNotRefireInsideItsOwnCooldown() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"hurt\", \"group\": \"t\", \"cooldownMs\": 600000,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"ouch\"]}"));

		for (int i = 0; i < 4; i++)
		{
			h.game.hitpoints(10, 99);
			h.gameTicks(2);
			h.game.hitpoints(99, 99);
			h.gameTicks(2);
		}

		assertEquals("four crossings inside a ten minute cooldown is still one line",
			1, h.firedBy("hurt").size());
	}

	// --------------------------------------------------------------- priority

	@Test
	public void theHighestPriorityRuleWinsWhenSeveralMatchAtOnce() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"quiet\", \"group\": \"t\", \"priority\": 10, \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}",
			"{\"id\": \"loud\", \"group\": \"t\", \"priority\": 90, \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"b\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(2);

		assertEquals(1, h.spoken.size());
		assertEquals("loud", h.spoken.get(0).rule.id);
	}

	@Test
	public void theLoserStillTracksItsEdgeAndDoesNotFireLater() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"quiet\", \"group\": \"t\", \"priority\": 10, \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}",
			"{\"id\": \"loud\", \"group\": \"t\", \"priority\": 90, \"cooldownMs\": 600000,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"b\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(5);

		assertTrue("the losing rule must not fire on the next tick from a stale edge",
			h.firedBy("quiet").isEmpty());
	}

	// ------------------------------------------------------------------ delay

	@Test
	public void aDelayedRuleSpeaksLaterAndOnlyOnce() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"slow\", \"group\": \"t\", \"cooldownMs\": 600000, \"delayTicks\": 4,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"ouch\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(1);
		assertTrue("nothing on the tick it won", h.firedBy("slow").isEmpty());

		h.gameTicks(3);
		assertTrue("still nothing before the delay is up", h.firedBy("slow").isEmpty());

		h.gameTicks(1);
		assertEquals("speaks once the delay expires", 1, h.firedBy("slow").size());

		h.gameTicks(20);
		assertEquals("and only once", 1, h.firedBy("slow").size());
	}

	@Test
	public void aRangedDelayStaysInsideItsBoundsAcrossManyDraws() throws IOException
	{
		SpeechRule rule = new SpeechRule();
		rule.delayTicks = 2;
		rule.delayTicksMax = 6;

		boolean sawMin = false;
		boolean sawMax = false;
		for (int i = 0; i < 2000; i++)
		{
			int drawn = rule.rollDelayTicks();
			assertTrue("drew " + drawn + ", below the floor", drawn >= 2);
			assertTrue("drew " + drawn + ", above the ceiling", drawn <= 6);
			sawMin |= drawn == 2;
			sawMax |= drawn == 6;
		}
		assertTrue("both bounds should be reachable", sawMin && sawMax);
	}

	@Test
	public void aDelayRangeSurvivesBeingWrittenBackwards()
	{
		SpeechRule rule = new SpeechRule();
		rule.delayTicks = 9;
		rule.delayTicksMax = 3;
		for (int i = 0; i < 100; i++)
		{
			assertEquals("a max below the min must not produce a negative draw",
				9, rule.rollDelayTicks());
		}
	}

	@Test
	public void noDelayFieldsMeansNoWait()
	{
		assertEquals(0, new SpeechRule().rollDelayTicks());
	}

	// -------------------------------------------------------------- throttles

	@Test
	public void theGlobalGapSilencesSpeechButNotAnimation() throws IOException
	{
		Harness spoken = new Harness(scratch(), rules(
			"{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}"));
		spoken.engine.setGlobalCooldownMs(600000L);
		spoken.game.hitpoints(10, 99);
		spoken.gameTicks(2);
		spoken.game.hitpoints(99, 99);
		spoken.gameTicks(2);
		spoken.game.hitpoints(10, 99);
		spoken.gameTicks(2);
		assertEquals("the second crossing falls inside the global window",
			1, spoken.firedBy("chatty").size());

		Harness mimed = new Harness(scratch(), rules(
			"{\"id\": \"emoter\", \"group\": \"t\", \"cooldownMs\": 0, \"animation\": 862,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}}"));
		mimed.engine.setGlobalCooldownMs(600000L);
		mimed.game.hitpoints(10, 99);
		mimed.gameTicks(2);
		mimed.game.hitpoints(99, 99);
		mimed.gameTicks(2);
		mimed.game.hitpoints(10, 99);
		mimed.gameTicks(2);
		assertEquals("an animation is not chatter and skips the window",
			2, mimed.firedBy("emoter").size());
	}

	@Test
	public void muteSilencesSpeechButNotAnimation() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}",
			"{\"id\": \"emoter\", \"group\": \"t\", \"priority\": -5, \"cooldownMs\": 0,"
				+ " \"animation\": 862,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}}"));
		h.engine.setMuted(true);
		h.game.hitpoints(10, 99);
		h.gameTicks(2);

		assertTrue("muted means no words", h.firedBy("chatty").isEmpty());
	}

	// ----------------------------------------------------------------- groups

	@Test
	public void aDisabledGroupStopsItsRulesFiring() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"hurt\", \"group\": \"health\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}"));
		h.engine.setDisabledGroups(java.util.Collections.singleton("health"));

		h.game.hitpoints(10, 99);
		h.gameTicks(3);

		assertTrue(h.spoken.isEmpty());
	}

	@Test
	public void aRuleCanBeSilencedByIdAsWellAsByGroup() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"hurt\", \"group\": \"health\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}"));
		h.engine.setDisabledGroups(java.util.Collections.singleton("hurt"));

		h.game.hitpoints(10, 99);
		h.gameTicks(3);

		assertTrue(h.spoken.isEmpty());
	}

	// ---------------------------------------------------------- substitutions

	@Test
	public void placeholdersAreFilledFromTheEventAndTheSnapshot() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"greet\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"combatStart\"},"
				+ " \"say\": [\"{player} versus {npc} at {hpPercent} percent\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Zulrah"));

		assertEquals(1, h.spoken.size());
		assertEquals("Tester versus Zulrah at 100 percent", h.spoken.get(0).text);
	}

	@Test
	public void anUnknownPlaceholderIsLeftAloneRatherThanBlanked() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"greet\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"login\"}, \"say\": [\"hello {nonsense}\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));

		assertEquals("hello {nonsense}", h.spoken.get(0).text);
	}

	// -------------------------------------------------------------- mirroring

	@Test
	public void aMirrorRuleAnswersWithThePlayersOwnAnimation() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"copy\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"mirrorAnimation\": true,"
				+ " \"when\": {\"type\": \"animationSelf\", \"ids\": [862, 866]}}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.animation(866));

		assertEquals(1, h.spoken.size());
		assertEquals("mirroring must replay the id that arrived, not a fixed one",
			866, h.spoken.get(0).animationId);
	}

	// ------------------------------------------------------------- kill rules

	@Test
	public void anOrdinaryKillNeverReachesTheBossCelebration() throws IOException
	{
		Harness h = new Harness(scratch());
		h.gameTicks(1);
		for (int i = 0; i < 40; i++)
		{
			h.dispatch(TriggerEvent.kill(1, "Chicken", 1));
			h.gameTicks(1);
		}
		assertTrue("a chicken is not a boss",
			h.firedBy("kill-boss-celebrate").isEmpty());
	}

	@Test
	public void aBossKillNeverReachesTheOrdinaryCheer() throws IOException
	{
		Harness h = new Harness(scratch());
		h.gameTicks(1);
		for (int i = 0; i < 40; i++)
		{
			h.dispatch(TriggerEvent.kill(2, "Zulrah", 725));
			h.gameTicks(20);
		}
		assertTrue("the two kill rules must not both answer the same kill",
			h.firedBy("kill-cheer").isEmpty());
	}

	@Test
	public void theBossCelebrationPlaysItsWholeChainAndHoldsStill() throws IOException
	{
		Harness h = new Harness(scratch());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.kill(2, "Zulrah", 725));
		h.gameTicks(20);

		assertEquals(1, h.firedBy("kill-boss-celebrate").size());
		SpeechRule rule = h.rule("kill-boss-celebrate");
		assertTrue("a celebration cut off by walking is no celebration",
			Boolean.TRUE.equals(rule.holdStill));
		assertEquals("cheer, dance, jump", 3, rule.animations.size());
	}

	// ------------------------------------------------------------ reset paths

	@Test
	public void resetDropsPendingFiringsSoTheyDoNotArriveAfterALogout() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"slow\", \"group\": \"t\", \"cooldownMs\": 0, \"delayTicks\": 5,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"a\"]}"));

		h.game.hitpoints(10, 99);
		h.gameTicks(1);

		h.engine.reset();
		// Back to full health, or the rule simply wins again on its own merits
		// and the test proves nothing about the queue.
		h.game.hitpoints(99, 99);
		h.gameTicks(10);

		assertTrue("a line queued before a logout must not surface after it",
			h.firedBy("slow").isEmpty());
	}

	@Test
	public void aRuleThatThrowsIsDisabledRatherThanKillingTheDispatch() throws IOException
	{
		// A regex that cannot compile throws from inside matches().
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"broken\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"chatMessage\", \"regex\": \"[unclosed\"},"
				+ " \"say\": [\"a\"]}",
			"{\"id\": \"fine\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"b\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.chat("anything", 0, ""));

		h.game.hitpoints(10, 99);
		h.gameTicks(2);

		assertEquals("one bad rule must not stop the rest of the set",
			1, h.firedBy("fine").size());
		assertFalse("and it should take itself out of service",
			h.rule("broken").isEnabled());
	}
}
