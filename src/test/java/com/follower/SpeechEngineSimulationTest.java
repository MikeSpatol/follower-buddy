package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechOutput;
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

	@Test
	public void aLineThatOpensOnANameOpensOnACapital() throws IOException
	{
		// A souvenir, an earned name, a stretch of time: the values are written
		// for the middle of a sentence ("a feather", "the local menace", "two
		// minutes"), so a sentence that opens on one opened in lower case. Only
		// an opening is touched: mid-sentence, and after an ellipsis, it stays.
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"lost\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"souvenirLost\"},"
				+ " \"say\": [\"{souvenir}, gone. Why? {souvenir} was the good one... {souvenir}, of all things.\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.souvenirLost("a feather"));

		assertEquals("A feather, gone. Why? A feather was the good one... a feather, of all things.",
			h.spoken.get(0).text);
	}

	@Test
	public void aPlayerNameOpeningALineKeepsItsOwnSpelling() throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"in\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"channelJoined\"}, \"say\": [\"{name}, present. Noted.\"]}"));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.channel(TriggerEvent.Type.CHANNEL_JOIN, "iron btw"));

		assertEquals("iron btw, present. Noted.", h.spoken.get(0).text);

		// Review: whoever spoke in chat is a player's name too.
		Harness chat = new Harness(scratch(), rules(
			"{\"id\": \"heard\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"chatMessage\", \"contains\": \"hello\"},"
				+ " \"say\": [\"{speaker} again. Noted.\"]}"));
		chat.gameTicks(1);
		chat.dispatch(TriggerEvent.chat("hello", 2, "iron btw"));

		assertEquals("iron btw again. Noted.", chat.spoken.get(0).text);
	}

	/** What a one-line rule says to one event: the template, filled. */
	private String says(String when, String line, TriggerEvent event) throws IOException
	{
		Harness h = new Harness(scratch(), rules(
			"{\"id\": \"t\", \"group\": \"t\", \"cooldownMs\": 0, \"when\": " + when
				+ ", \"say\": [\"" + line + "\"]}"));
		h.gameTicks(1);
		h.dispatch(event);
		assertEquals(1, h.spoken.size());
		return h.spoken.get(0).text;
	}

	@Test
	public void anArticleBeforeAPlaceholderAgreesWithWhatFillsIt() throws IOException
	{
		// Straight from the transcripts: the lines as shipped, the names as met.
		String impling = "{\"type\": \"noticed\", \"is\": \"impling\"}";
		assertEquals("There's an Eclectic impling. It won't wait.", says(impling,
			"There's a {thing}. It won't wait.", TriggerEvent.noticed("impling", "Eclectic impling")));
		assertEquals("An Essence impling, if you're quick.", says(impling,
			"A {thing}, if you're quick.", TriggerEvent.noticed("impling", "Essence impling")));
		assertEquals("I see an Earth impling. I'm not catching it for you.", says(impling,
			"I see a {thing}. I'm not catching it for you.", TriggerEvent.noticed("impling", "Earth impling")));
		assertEquals("A Young impling, if you're quick.", says(impling,
			"A {thing}, if you're quick.", TriggerEvent.noticed("impling", "Young impling")));

		String hit = "{\"type\": \"personalBest\", \"names\": [\"hit\"]}";
		assertEquals("An 80! Underline it. Twice.", says(hit,
			"A {value}! Underline it. Twice.", TriggerEvent.record("hit", 80, 62)));
	}

	@Test
	public void aPluralOrAHeapLosesTheArticleRatherThanWearingItWrong() throws IOException
	{
		String loot = "{\"type\": \"lootWorth\", \"minimum\": 1000000}";
		assertEquals("That's Coins. That's Coins!", says(loot,
			"That's a {item}. That's a {item}!", TriggerEvent.loot(1_500_000, "Coins")));
		assertEquals("Dragon claws! I need to sit down.", says(loot,
			"A {item}! I need to sit down.", TriggerEvent.loot(1_500_000, "Dragon claws")));

		String examine = "{\"type\": \"examinedThing\"}";
		assertEquals("I looked where you looked. I saw Soft clay.", says(examine,
			"I looked where you looked. I saw a {target}.", TriggerEvent.examinedThing("Soft clay")));
		String left = "{\"type\": \"noticed\", \"is\": \"valuable\"}";
		assertEquals("You're walking away from Coins.", says(left,
			"You're walking away from a {thing}.", TriggerEvent.noticed("valuable", "Coins")));
		assertEquals("You're walking away from an Abyssal whip.", says(left,
			"You're walking away from a {thing}.", TriggerEvent.noticed("valuable", "Abyssal whip")));
		// A name that only looks plural keeps what it had.
		assertEquals("Very much a Brutus.", says(examine,
			"Very much a {target}.", TriggerEvent.examinedThing("Brutus")));
	}

	@Test
	public void anArticleInFrontOfADescribingPlaceholderStays() throws IOException
	{
		// "A {skill} level": the article belongs to "level", so Hitpoints keeps
		// it, and only the sound of the skill picks a or an.
		String level = "{\"type\": \"levelUp\", \"names\": [\"*\"]}";
		assertEquals("A Hitpoints level, noted.", says(level,
			"A {skill} level, noted.", TriggerEvent.levelUp("Hitpoints", 50)));
		assertEquals("An Agility level, noted.", says(level,
			"A {skill} level, noted.", TriggerEvent.levelUp("Agility", 50)));
	}

	@Test
	public void aNameThatBringsItsOwnTheGivesItToTheWordBeforeIt() throws IOException
	{
		// "Careful with the The Leviathan." The name's own "The" goes to the
		// word already pointing at it, an "a" becoming that "the"; with nothing
		// pointing at it, the name stays as the game spells it.
		String kill = "{\"type\": \"npcKill\", \"minimum\": 1}";
		TriggerEvent leviathan = TriggerEvent.kill(12214, "The Leviathan", 723);
		assertEquals("Careful with the Leviathan.", says(kill, "Careful with the {npc}.", leviathan));
		assertEquals("The Leviathan has filed its complaint.", says(kill,
			"The {npc} has filed its complaint.", leviathan));
		assertEquals("That's the Leviathan. Take your time.", says(kill,
			"That's a {npc}. Take your time.", leviathan));
		assertEquals("The Leviathan. I'll stand well back.", says(kill,
			"A {npc}. I'll stand well back.", leviathan));
		assertEquals("That Leviathan never stood a chance.", says(kill,
			"That {npc} never stood a chance.", leviathan));
		assertEquals("I remember the first Leviathan.", says(kill,
			"I remember the first {npc}.", leviathan));
		assertEquals("You beat The Leviathan!", says(kill, "You beat {npc}!", leviathan));

		// Only a whole "The" is given up.
		assertEquals("Careful with the Thermonuclear smoke devil.", says(kill,
			"Careful with the {npc}.", TriggerEvent.kill(499, "Thermonuclear smoke devil", 301)));
		assertEquals("Careful with the dwarf.", says(kill,
			"Careful with the {npc}.", TriggerEvent.kill(290, "Dwarf", 7)));

		// Review: a player's name and someone's own words keep their "The".
		assertEquals("Is that The Old Nite? Pull up a chair.", says("{\"type\": \"channelJoined\"}",
			"Is that {name}? Pull up a chair.", TriggerEvent.channel(TriggerEvent.Type.CHANNEL_JOIN, "The Old Nite")));
		assertEquals("You said that the bank is shut?", says("{\"type\": \"chatMessage\", \"contains\": \"bank\"}",
			"You said that {message}?", TriggerEvent.chat("the bank is shut", 2, "iron btw")));
	}

	@Test
	public void aCreatureAfterAnArticleIsACommonNoun() throws IOException
	{
		// Play test, 7 Oct: "Careful with the Guard." The game capitalises
		// every name; in the middle of a sentence a guard is a guard.
		String examine = "{\"type\": \"examinedThing\"}";
		assertEquals("Careful with that guard. Actually, it's a guard. You'll be fine.", says(examine,
			"Careful with that {target}. Actually, it's a {target}. You'll be fine.",
			TriggerEvent.examinedCreature("Guard")));
		assertEquals("Guard. There. Now we both know.", says(examine,
			"{target}. There. Now we both know.", TriggerEvent.examinedCreature("Guard")));
		assertEquals("The monk of Zamorak. Noted, reluctantly.", says(examine,
			"The {target}. Noted, reluctantly.", TriggerEvent.examinedCreature("Monk of Zamorak")));
		// A thing examined is not a creature: an item's name can open on a god's.
		assertEquals("I saw a Bandos chestplate.", says(examine,
			"I saw a {target}.", TriggerEvent.examinedThing("Bandos chestplate")));

		String kill = "{\"type\": \"npcKill\", \"minimum\": 1}";
		assertEquals("That's a goblin down.", says(kill, "That's a {npc} down.",
			TriggerEvent.kill(3029, "Goblin", 2)));
		// Without anything pointing at it, it is the name.
		assertEquals("Goblin, down.", says(kill, "{npc}, down.", TriggerEvent.kill(3029, "Goblin", 2)));
		assertEquals("You beat Goblin!", says(kill, "You beat {npc}!", TriggerEvent.kill(3029, "Goblin", 2)));
		// Names in their own right keep their capitals.
		assertEquals("Careful with the Hill Giant.", says(kill, "Careful with the {npc}.",
			TriggerEvent.kill(2098, "Hill Giant", 28)));
		assertEquals("Careful with the Obor.", says(kill, "Careful with the {npc}.",
			TriggerEvent.kill(7416, "Obor", 106)));
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

	// ------------------------------------------- the two switches everything uses

	@Test
	public void theHighestPriorityRuleWinsAndTheRestStayQuiet() throws IOException
	{
		// Priority decides which of three hundred rules gets the moment, and
		// nothing checked that the engine honours it at all. Every rule-ordering
		// bug fixed this week - four of them - assumed a mechanism no test had
		// ever exercised.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"quiet\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"priority\": 10, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"the loser\"]},"
				+ "{\"id\": \"loud\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"priority\": 90, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"the winner\"]}]}");
		h.gameTicks(1);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));

		assertFalse("the higher priority rule has to win", h.firedBy("loud").isEmpty());
		assertTrue("and only one rule may speak per event",
			h.firedBy("quiet").isEmpty());
	}

	@Test
	public void theLoserSpeaksOnceTheWinnerIsOnCooldown() throws IOException
	{
		// The other half: losing is not being disabled. A rule under a busy one
		// still gets the moments the winner cannot take, which is the whole
		// reason a cooldown and a priority are different things.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"quiet\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"priority\": 10, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"the loser\"]},"
				+ "{\"id\": \"loud\", \"group\": \"t\", \"cooldownMs\": 600000,"
				+ " \"priority\": 90, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"the winner\"]}]}");
		h.gameTicks(1);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse(h.firedBy("loud").isEmpty());

		// The edge has to fall and rise again, or nothing fires a second time.
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));

		assertFalse("with the winner on cooldown the next rule down gets its turn",
			h.firedBy("quiet").isEmpty());
	}

	@Test
	public void aDisabledGroupSaysNothing() throws IOException
	{
		// Every group toggle in the settings runs through this. If it stopped
		// working, every "off" switch in the panel would silently do nothing -
		// and nothing would have noticed.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"chatty\", \"group\": \"idle\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"hello\"]}]}");
		h.gameTicks(1);

		h.engine.setDisabledGroups(
			new java.util.HashSet<>(java.util.Collections.singletonList("idle")));
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("a switched-off group must not speak", h.firedBy("chatty").isEmpty());

		h.gameTicks(1);
		h.engine.setDisabledGroups(java.util.Collections.emptySet());
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse("and must come back when switched on again",
			h.firedBy("chatty").isEmpty());
	}

	@Test
	public void asingleRuleCanBeSilencedByIdWithoutItsWholeGroup() throws IOException
	{
		// The disabled set matches rule ids too, which is how one line is muted
		// without losing the twenty others in its group.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"one\", \"group\": \"idle\", \"cooldownMs\": 0,"
				+ " \"priority\": 50, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"first\"]},"
				+ "{\"id\": \"two\", \"group\": \"idle\", \"cooldownMs\": 0,"
				+ " \"priority\": 40, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"second\"]}]}");
		h.gameTicks(1);

		h.engine.setDisabledGroups(
			new java.util.HashSet<>(java.util.Collections.singletonList("one")));
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));

		assertTrue("the silenced rule stays silent", h.firedBy("one").isEmpty());
		assertFalse("its group-mate carries on", h.firedBy("two").isEmpty());
	}

	/**
	 * A line of the follower's own that no rule wrote - the remark after a
	 * sketch - went straight to the overhead, past the mute and the output
	 * setting.
	 */
	@Test
	public void aLineNoRuleWroteKeepsTheMuteAndGoesWhereSpeechGoes() throws IOException
	{
		Harness h = new Harness(scratch(), "{\"version\": 1, \"rules\": []}");
		h.engine.setDefaultOutput(SpeechOutput.CHATBOX);
		h.engine.remark("Sketched. The light did most of the work.");
		assertEquals(1, h.spoken.size());
		assertEquals("where the settings put speech", SpeechOutput.CHATBOX, h.spoken.get(0).output);

		h.spoken.clear();
		h.engine.setMuted(true);
		h.engine.remark("Sketched. The light did most of the work.");
		assertTrue("and not at all under the mute", h.spoken.isEmpty());
	}

	@Test
	public void aChatMessageBeforeTheGameLoadsDisablesNothing() throws IOException
	{
		// At client start a plugin's chat message is dispatched before the
		// game has loaded its varbits. A condition that throws then is
		// disabled for the whole session - the two Wilderness rules were.
		Harness h = new Harness(scratch());
		h.game.starting();
		h.dispatch(TriggerEvent.chat("Plugin started.",
			net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));

		assertTrue(h.rule("enter-wilderness").isEnabled());
		assertTrue(h.rule("wilderness-crossing").isEnabled());
		for (SpeechRule rule : h.loader.getRules())
		{
			assertTrue("rule '" + rule.id + "' was disabled at client start", rule.isEnabled());
		}
	}
}
