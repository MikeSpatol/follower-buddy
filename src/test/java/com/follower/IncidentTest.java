package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The incident, with its particulars. "How close that was" is a hint; "how
 * close that was with the Hill Giant, 6 hitpoints left, in Edgeville, three
 * days ago" is a memory two people share, and the point of keeping one.
 */
public class IncidentTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static Map<Integer, String> names()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		names.put(12853, "Varrock: west bank, centre and palace");
		return names;
	}

	private static String said(Harness h, String ruleId)
	{
		List<Harness.Spoken> fired = h.firedBy(ruleId);
		assertFalse(ruleId + " never fired", fired.isEmpty());
		return fired.get(0).text;
	}

	@Test
	public void theCloseCallIsFiledWithWhoAndHowClose() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setRegionNames(names());
		h.game.regions(12342).at(3090, 3490, 0).hitpoints(6, 99);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		TriggerContext context = h.engine.getContext();
		assertTrue("the close call was filed", context.hasIncident());
		assertEquals("close-call", context.getIncidentKey());
		assertEquals("the Hill Giant leaving you on 6 hitpoints",
			context.getIncidentPhrase());
		assertEquals(6, context.getIncidentHp());
		assertEquals("Hill Giant", context.getIncidentWho());
		assertEquals(12342, context.getIncidentWhere());
		assertEquals(" in Edgeville", context.getMemoryWhere());
	}

	private static final String QUEST_MEMORY = "{\"version\": 1, \"rules\": [{\"id\": \"quest-memory\","
		+ " \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"the-quest\", \"as\": \"the sheep that wouldn't be sheared\"},"
		+ " \"when\": {\"type\": \"questComplete\"}, \"say\": [\"Filed.\"]}]}";

	/** A memory filed some ticks after a ram fight ended. */
	private String whoAfter(int ticksSinceTheFight) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), QUEST_MEMORY);
		h.game.regions(12342).at(3090, 3490, 0);
		NPC ram = h.game.spawnNpc(2800, "Ram", 2);
		h.game.fighting(ram);
		h.gameTicks(3);
		h.game.fighting(null);
		h.gameTicks(ticksSinceTheFight);
		h.dispatch(TriggerEvent.questComplete("Sheep Shearer"));
		assertEquals("the-quest", h.engine.getContext().getIncidentKey());
		return h.engine.getContext().getIncidentWho();
	}

	@Test
	public void aMemoryIsAboutTheFightOnlyWhileItIsGoingOn() throws IOException
	{
		// QA, 15 Oct: the combat target is never cleared, so a quest finished
		// long after a ram fight was filed with the Ram as its "who", and the
		// next Ram in sight brought up a quest it had nothing to do with.
		assertEquals("a fight just over is the memory's", "Ram", whoAfter(2));
		assertEquals("one long over is not", "", whoAfter(60));
	}

	@Test
	public void aDeathLongAfterAFightIsNotPutDownToIt() throws IOException
	{
		// The same stale target in the shipped death memory's own words:
		// "your death to the {foe}".
		for (int since : new int[]{2, 60})
		{
			Harness h = new Harness(folder.newFolder().toPath());
			h.game.regions(12342).at(3090, 3490, 0);
			NPC ram = h.game.spawnNpc(2800, "Ram", 2);
			h.game.fighting(ram);
			h.gameTicks(3);
			h.game.fighting(null);
			h.gameTicks(since);
			h.dispatch(TriggerEvent.death());
			TriggerContext context = h.engine.getContext();
			assertEquals("the-death", context.getIncidentKey());
			if (since == 2)
			{
				assertEquals("your death to the ram", context.getIncidentPhrase().toLowerCase());
				assertEquals("Ram", context.getIncidentWho());
			}
			else
			{
				assertEquals("the time you died", context.getIncidentPhrase());
				assertEquals("", context.getIncidentWho());
			}
		}
	}

	@Test
	public void whatIsAlreadyTrueAtLoginIsNotFiled() throws IOException
	{
		// Logging back in mid-fight at six hitpoints - after a disconnect,
		// say - is not a close call the follower saw. Just after login the
		// engine primes its edges so standing state reads as already true,
		// and a memory filed then would be about nothing that happened.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.regions(12342).at(3090, 3490, 0).hitpoints(6, 99);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.engine.primeEdgesOnNextTick();
		h.gameTicks(3);
		assertFalse(h.engine.getContext().hasIncident());
	}

	@Test
	public void aPlaceholderWithNothingToSayFilesThePlainForm() throws IOException
	{
		// A death with no combat target and no named place on record: "your
		// death to the " would be a hole, and so would "your death ", so the
		// plain form is filed instead.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		TriggerContext context = h.engine.getContext();
		assertEquals("the-death", context.getIncidentKey());
		assertEquals("the time you died", context.getIncidentPhrase());
		assertEquals("", context.getIncidentWho());
	}

	@Test
	public void thatsThreeIsSaidOnTheThirdDeathOnly() throws IOException
	{
		// Dialogue check, 14 Oct: "That's three" was said from the fourth
		// death on - the death is filed after its line is chosen, so the
		// count read was one behind. It has the third to itself now.
		//
		// QA, 15 Oct: and it was never heard. It waited 10-16 ticks, and the
		// game's "Oh dear, you are dead!" a few ticks after the death let the
		// chat death line speak first, so the count was lost in the gap. Run
		// the way the game runs it: the default gap on a game clock, and the
		// message five ticks behind the death.
		Harness h = new Harness(folder.newFolder().toPath());
		long[] ms = {1_000_000L};
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(com.follower.speech.Chattiness.OCCASIONAL.getGapMs());
		h.gameTicks(1);
		for (int death = 1; death <= 4; death++)
		{
			int deathLinesBefore = h.firedBy("death").size();
			h.dispatch(TriggerEvent.death());
			for (int tick = 0; tick < 400; tick++)
			{
				ms[0] += 600;
				h.gameTick();
				if (tick == 4)
				{
					h.dispatch(TriggerEvent.chat("Oh dear, you are dead!",
						net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
				}
			}
			if (death == 2)
			{
				assertTrue("nothing to count yet", h.firedBy("recall-death-third").isEmpty());
			}
			if (death == 3)
			{
				assertEquals("said on the third", 1, h.firedBy("recall-death-third").size());
			}
			if (death >= 3)
			{
				assertEquals("and the chat death line does not talk over the count on death " + death,
					deathLinesBefore, h.firedBy("death").size());
			}
		}
		assertEquals("and not again on the fourth", 1, h.firedBy("recall-death-third").size());
		assertEquals("which has the other lines", 1, h.firedBy("recall-death-again").size());
	}

	@Test
	public void theCountBeatsTheLifetimeTally() throws IOException
	{
		// QA, 15 Oct: past ten lifetime deaths, deaths-many (same priority,
		// earlier in the file) took every pass from recall-death-again.
		// A game clock, so the cooldowns run out between deaths as they would.
		Harness h = new Harness(folder.newFolder().toPath());
		long[] ms = {1_000_000L};
		h.engine.setClock(() -> ms[0]);
		h.engine.getContext().restoreCounters(Collections.singletonMap("deaths", 12), null);
		h.gameTicks(1);
		for (int death = 1; death <= 4; death++)
		{
			h.engine.getContext().tallyBoth("deaths");
			h.dispatch(TriggerEvent.death());
			for (int tick = 0; tick < 400; tick++)
			{
				ms[0] += 600;
				h.gameTick();
			}
		}
		assertEquals(1, h.firedBy("recall-death-third").size());
		assertEquals(1, h.firedBy("recall-death-again").size());
	}

	@Test
	public void aDropIsRememberedByName() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.loot(2_000_000, "Abyssal whip"));
		assertEquals("the Abyssal whip that dropped", h.engine.getContext().getIncidentPhrase());
	}

	@Test
	public void turningReminiscingOffQuietensTheTalkNotTheMemory() throws IOException
	{
		// The setting promises it "does not stop it remembering", but the
		// memory rules sit in its group and silencing the group stopped them
		// filing. Silenced by group or by id, the memory is still kept.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setDisabledGroups(Collections.singleton("memory"));
		h.gameTicks(1);
		h.dispatch(TriggerEvent.loot(2_000_000, "Abyssal whip"));
		assertEquals("the Abyssal whip that dropped", h.engine.getContext().getIncidentPhrase());

		Harness byId = new Harness(folder.newFolder().toPath());
		byId.engine.setDisabledGroups(Collections.singleton("memory-death"));
		byId.gameTicks(1);
		byId.dispatch(TriggerEvent.death());
		assertEquals("the time you died", byId.engine.getContext().getIncidentPhrase());
		assertTrue("and silenced means silent", byId.firedBy("memory-death").isEmpty());
	}

	@Test
	public void whenAndWhereReadAsSomebodyWouldSayThem()
	{
		TriggerContext context = new TriggerContext(new com.follower.sim.FakeGame().client);
		assertEquals("", context.getMemoryWhen(5_000L));
		context.setRegionNames(names());
		long now = 10_000_000_000L;
		context.noteIncident("the-boss", "the General Graardor going down", "General Graardor", now - 30_000L);
		assertEquals("just now", context.getMemoryWhen(now));
		context.noteIncident("the-boss", "the General Graardor going down", "General Graardor",
			now - 3L * 24 * 60 * 60_000L);
		assertEquals("three days ago", context.getMemoryWhen(now));
		assertEquals("a repeat keeps the count", 2, context.getIncidentCount());
		context.restoreIncident("the-boss", "the size of that thing", 1);
		assertEquals("an old record with no date", "a while ago", context.getMemoryWhen(now));
		assertEquals("Varrock", context.placeName(12853));
		assertEquals("", context.placeName(999));
	}

	@Test
	public void aPlaceIsSaidAsItsNameNotItsDescription() throws IOException
	{
		// QA, 15 Oct: "in The God Wars Dungeon", "in Lumbridge town and
		// castle". The Locations notes describe; a line wants the name.
		com.follower.speech.RuleLoader rules = new com.follower.speech.RuleLoader(new com.google.gson.Gson());
		rules.initialise(folder.newFolder().toPath());
		TriggerContext context = new TriggerContext(new com.follower.sim.FakeGame().client);
		context.setRegionNames(rules.getRegionNames());
		Map<String, String> said = new java.util.TreeMap<>();
		for (Map.Entry<Integer, String> place : rules.getRegionNames().entrySet())
		{
			context.restoreIncident("the-boss", "the size of that thing", 1, place.getKey(), 0L, "");
			said.put(place.getValue(), context.getMemoryWhere());
		}
		assertEquals(" in the God Wars Dungeon", said.get("The God Wars Dungeon"));
		assertEquals(" in Lumbridge", said.get("Lumbridge town and castle"));
		assertEquals(" in Seers' Village", said.get("Seers' Village and Camelot castle"));
		assertEquals(" in East Ardougne", said.get("East Ardougne and its market"));
		assertEquals(" in Castle Wars", said.get("Castle Wars lobby area"));
		assertEquals(" in the Grand Exchange", said.get("The Grand Exchange plaza"));
		assertEquals(" in the Barrows", said.get("The Barrows mounds"));
		for (String where : said.values())
		{
			assertFalse("no capital The mid-sentence: " + where, where.contains(" The "));
			assertFalse("no description: " + where, where.contains(" and "));
		}
	}

	/**
	 * QA, 15 Oct (probe P3): a memory is filed where it happens, so the place
	 * recall could say "Here again, and I've not forgotten the orb breaking"
	 * twelve seconds after the orb broke, to somebody who never left.
	 */
	@Test
	public void aMemoryIsNotHereUntilYouComeBackToIt() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.regions(12342).at(3090, 3490, 0);
		h.gameTicks(1);
		h.engine.getContext().noteIncident("close-call",
			"how close that was with the Hill Giant", "Hill Giant", System.currentTimeMillis());
		h.gameTicks(200);
		assertTrue("not on the visit it happened", h.firedBy("recall-here").isEmpty());
		assertFalse(h.engine.getContext().isAtTheIncident());

		h.game.regions(12850).at(3222, 3218, 0);
		h.gameTicks(2);
		h.game.regions(12342).at(3090, 3490, 0);
		h.gameTicks(1);
		assertTrue("back again", h.engine.getContext().isAtTheIncident());
	}

	@Test
	public void theRecallLinesSayTheParticulars() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setRegionNames(names());
		h.game.regions(12342).at(3090, 3490, 0);
		h.gameTicks(1);
		h.engine.getContext().noteIncident("close-call",
			"how close that was with the Hill Giant", "Hill Giant",
			System.currentTimeMillis() - 3L * 24 * 60 * 60_000L);
		// Away and back: the return is the rising edge the place recall fires on.
		h.game.regions(12850).at(3222, 3218, 0);
		h.gameTicks(2);
		h.game.regions(12342).at(3090, 3490, 0);
		// Both recalls are a coin toss each tick after a short delay, and the
		// chance draws from a random a test cannot seed, so they are given
		// long enough that missing every toss is not a thing that happens.
		// Twelve and eight ticks missed about one run in sixteen.
		h.gameTicks(40);
		String line = said(h, "recall-here");
		assertTrue("the memory in full: " + line, line.contains("Hill Giant"));

		// The giant back in sight.
		Harness foe = new Harness(folder.newFolder().toPath());
		foe.engine.getContext().setRegionNames(names());
		foe.gameTicks(1);
		foe.engine.getContext().noteIncident("close-call",
			"how close that was with the Hill Giant", "Hill Giant",
			System.currentTimeMillis() - 3L * 24 * 60 * 60_000L);
		NPC giant = foe.game.spawnNpc(2098, "Hill Giant", 28);
		foe.game.moveNpc(giant, foe.game.player.getWorldLocation().getX() + 4,
			foe.game.player.getWorldLocation().getY(), 0);
		foe.gameTicks(40);
		String seen = said(foe, "recall-foe");
		assertTrue("names the thing or the memory: " + seen,
			seen.contains("Hill Giant"));
		assertFalse("no placeholder printed literally: " + seen, seen.contains("{"));
	}

	@Test
	public void everyRememberHasAPlainFormForWhenTheParticularsAreMissing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (com.follower.speech.SpeechRule rule : h.loader.getRules())
		{
			if (rule.remember == null || rule.remember.as == null || rule.remember.as.indexOf('{') < 0)
			{
				continue;
			}
			assertTrue(rule.id + " fills its memory in from placeholders and needs a plain form",
				rule.remember.plain != null && !rule.remember.plain.isEmpty());
		}
		assertEquals(Collections.emptyList(), h.firedBy("nothing"));
	}
}
