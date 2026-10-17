package com.follower;

import com.follower.sim.FakeGame;
import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A memory names the moment: what, who, where and roughly when. Play report,
 * 17 Oct: "I was rereading the entry on the way that went" gave the player
 * nothing to remember the moment by. The particulars are filed with every
 * incident, a form that cannot be filled falls to one that can, and the old
 * vague words are never said.
 *
 * <p>The forms here are the ones written to try the machinery; the shipped
 * rules keep their own words until new ones are approved.
 */
public class SpecificMemoryTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"memory-death\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"the-death\", \"as\": \"your death to the {foe}\","
		+ "  \"or\": [\"your death {where}\"], \"plain\": \"the time you died\"},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"Noted.\"]},"
		+ "{\"id\": \"memory-close-call\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"close-call\", \"as\": \"the {foe} leaving you on {hp} hitpoints\","
		+ "  \"or\": [\"your close call with the {foe}\", \"your close call {where}\"],"
		+ "  \"plain\": \"the time you nearly died\"},"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"healthBelow\", \"percent\": 8},"
		+ "  {\"type\": \"combat\"}]}, \"say\": [\"Close.\"]},"
		+ "{\"id\": \"memory-big-drop\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"big-drop\", \"as\": \"the {item} from the {foe}\","
		+ "  \"or\": [\"the {item} that dropped\", \"your big drop {where}\"], \"plain\": \"your big drop\"},"
		+ " \"when\": {\"type\": \"lootWorth\", \"minimum\": 1000000}, \"say\": [\"Rich.\"]},"
		+ "{\"id\": \"old-death\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"old-death\", \"as\": \"the way that went with the {foe}\","
		+ "  \"plain\": \"the way that went\"},"
		+ " \"when\": {\"type\": \"chatMessage\", \"contains\": \"old death\"}, \"say\": [\"Old.\"]},"
		+ "{\"id\": \"recall\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"remembers\"},"
		+ "  {\"type\": \"chatMessage\", \"contains\": \"recall\"}]},"
		+ " \"say\": [\"I keep thinking about {memory}{memoryWhere}, {memoryAgo}.\"]}"
		+ "]}";

	private static final long DAY = 24L * 60 * 60_000L;

	private static Map<Integer, String> names()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		names.put(12850, "Lumbridge town and castle");
		return names;
	}

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.engine.getContext().setRegionNames(names());
		return h;
	}

	@Test
	public void aDeathIsFiledWithTheFoeAndThePlace() throws IOException
	{
		Harness h = harness();
		h.game.at(3090, 3490, 0);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		h.dispatch(TriggerEvent.death());
		TriggerContext c = h.engine.getContext();
		assertEquals("the-death", c.getIncidentKey());
		assertEquals("your death to the Hill Giant", c.getIncidentPhrase());
		assertEquals("Hill Giant", c.getIncidentWho());
		assertEquals("Edgeville", c.getIncidentPlace());
		assertFalse(c.isIncidentNear());
		assertEquals(" in Edgeville", c.getMemoryWhere());
	}

	@Test
	public void aCloseCallKeepsTheHitpointsLeft() throws IOException
	{
		Harness h = harness();
		h.game.at(3090, 3490, 0).hitpoints(6, 99);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		TriggerContext c = h.engine.getContext();
		assertEquals("close-call", c.getIncidentKey());
		assertEquals("the Hill Giant leaving you on 6 hitpoints", c.getIncidentPhrase());
		assertEquals(6, c.getIncidentHp());
	}

	@Test
	public void aDropKeepsTheItemAndWhatDroppedIt() throws IOException
	{
		Harness h = harness();
		h.game.at(3090, 3490, 0);
		NPC demon = h.game.spawnNpc(415, "Abyssal demon", 124);
		h.game.fighting(demon);
		h.gameTicks(3);
		h.dispatch(TriggerEvent.loot(2_500_000, "Abyssal whip"));
		TriggerContext c = h.engine.getContext();
		assertEquals("big-drop", c.getIncidentKey());
		assertEquals("the Abyssal whip from the abyssal demon", c.getIncidentPhrase());
		assertEquals("Abyssal whip", c.getIncidentItem());
		assertEquals("Abyssal demon", c.getIncidentWho());
	}

	@Test
	public void withNoFoeTheDeathIsTheDeathThere() throws IOException
	{
		// "your death in Edgeville", and the line does not say the place twice.
		Harness h = harness();
		h.game.at(3090, 3490, 0);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		TriggerContext c = h.engine.getContext();
		assertEquals("your death in Edgeville", c.getIncidentPhrase());
		assertEquals("", c.getMemoryWhere());
		assertEquals("I keep thinking about your death in Edgeville, earlier today.",
			h.engine.fill("I keep thinking about {memory}{memoryWhere}, {memoryAgo}."));
	}

	@Test
	public void withNothingKnownItStillSaysWhatKindOfMomentItWas() throws IOException
	{
		Harness h = harness();
		h.game.at(1000, 1000, 0);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		assertEquals("the time you died", h.engine.getContext().getIncidentPhrase());
		assertEquals("", h.engine.getContext().getMemoryWhere());
	}

	@Test
	public void aPlaceWithNoNameBorrowsTheWildernessOrTheNameNextDoor() throws IOException
	{
		Harness h = harness();
		TriggerContext c = h.engine.getContext();

		h.game.at(3090, 3530, 0);
		h.gameTicks(1);
		assertEquals("the region north of Edgeville is near it", "Edgeville", c.getPlaceHere());
		assertTrue(c.isPlaceHereNear());
		assertEquals("near Edgeville", c.getWhereHere());
		h.dispatch(TriggerEvent.death());
		assertEquals("your death near Edgeville", c.getIncidentPhrase());

		h.game.varbit(5963, 1).at(3090, 3700, 0);
		h.gameTicks(1);
		assertEquals("in the Wilderness", c.getWhereHere());
		h.dispatch(TriggerEvent.death());
		assertEquals("your death in the Wilderness", c.getIncidentPhrase());
		assertFalse(c.isIncidentNear());

		h.game.varbit(5963, 0).at(1000, 1000, 0);
		h.gameTicks(1);
		assertEquals("nowhere with a name", "", c.getWhereHere());
	}

	@Test
	public void whenReadsAsTheCalendarSaysIt()
	{
		ZoneId zone = ZoneOffset.UTC;
		long now = LocalDateTime.of(2026, 10, 17, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		TriggerContext c = new TriggerContext(new FakeGame().client);
		assertEquals("nothing remembered", "", c.getMemoryAgo(now, zone));
		long lastNight = LocalDateTime.of(2026, 10, 16, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli();
		long[][] cases = {{now - 60_000L}, {lastNight}, {now - 2 * DAY}, {now - 6 * DAY}, {now - 7 * DAY},
			{now - 13 * DAY}, {now - 14 * DAY}, {0L}, {now + DAY}};
		String[] said = {"earlier today", "yesterday", "two days ago", "six days ago", "last week",
			"last week", "a while back", "a while back", "a while back"};
		for (int i = 0; i < cases.length; i++)
		{
			c.restoreIncident("the-death", "the time you died", 1, 0, cases[i][0], "");
			assertEquals("case " + i, said[i], c.getMemoryAgo(now, zone));
		}
	}

	@Test
	public void anOldVagueMemoryIsToldAgainFromWhatWasKept() throws IOException
	{
		// The developer's own save: "the way that went", with the giant kept.
		Harness h = harness();
		TriggerContext c = h.engine.getContext();
		c.restoreIncident("the-death", "the way that went", 3, 12342, System.currentTimeMillis() - 2 * DAY,
			"Hill Giant");
		assertTrue(c.isIncidentToRetell());
		h.gameTicks(1);
		assertEquals("your death to the Hill Giant", c.getIncidentPhrase());
		assertEquals("the count is kept", 3, c.getIncidentCount());
		assertFalse(c.isIncidentToRetell());
		assertTrue("and saved", c.isCountersDirty());

		// With no foe kept, the place does it.
		Harness placed = harness();
		placed.engine.getContext().restoreIncident("the-death", "the way that went", 1, 12342, 0L, "");
		placed.gameTicks(1);
		assertEquals("your death in Edgeville", placed.engine.getContext().getIncidentPhrase());

		// The old form that named the foe is told in the current words too.
		Harness named = harness();
		named.engine.getContext().restoreIncident("close-call", "how close that was with the Hill Giant", 1,
			0, 0L, "Hill Giant");
		named.gameTicks(1);
		assertEquals("your close call with the Hill Giant", named.engine.getContext().getIncidentPhrase());

		// A memory in its own words is left alone.
		Harness quest = harness();
		quest.engine.getContext().restoreIncident("quest-aubury-package", "the sealed package for Aubury", 1,
			0, 0L, "");
		assertFalse(quest.engine.getContext().isIncidentToRetell());
	}

	@Test
	public void aVagueMemoryWithNothingKeptIsNeverSaid() throws IOException
	{
		Harness h = harness();
		TriggerContext c = h.engine.getContext();
		c.restoreIncident("old-death", "the way that went", 2, 0, 0L, "");
		h.gameTicks(3);
		h.playerSays("recall");
		assertEquals("kept, to be replaced by the next", "the way that went", c.getIncidentPhrase());
		assertEquals(2, c.getIncidentCount());
		assertTrue("nothing specific to say", h.engine.memoryPhrases().isEmpty());
		assertTrue("so it is not brought up", h.firedBy("recall").isEmpty());

		// Filed vague today, by words not yet replaced: the same.
		Harness today = harness();
		today.game.at(1000, 1000, 0);
		today.gameTicks(1);
		today.playerSays("old death");
		today.gameTicks(3);
		today.playerSays("recall");
		assertEquals("the way that went", today.engine.getContext().getIncidentPhrase());
		assertTrue(today.firedBy("recall").isEmpty());

		// The next incident replaces it, and is said.
		h.game.at(3090, 3490, 0);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		h.gameTicks(2);
		h.playerSays("recall");
		assertEquals("your death in Edgeville", c.getIncidentPhrase());
		assertFalse(h.firedBy("recall").isEmpty());
		assertEquals("I keep thinking about your death in Edgeville, earlier today.",
			h.firedBy("recall").get(0).text);
	}

	@Test
	public void theParticularsSurviveARestartAndOldSavesStillLoad()
	{
		Gson gson = new Gson();
		TriggerContext before = new TriggerContext(new FakeGame().at(3090, 3530, 0).client);
		before.setRegionNames(names());
		before.refresh();
		before.noteIncident("big-drop", "the Abyssal whip from the abyssal demon", "Abyssal demon",
			"Abyssal whip", 40, 1_700_000_000_000L);
		String blob = FollowerPlugin.snapshotMemory(before, 0, gson);

		TriggerContext after = new TriggerContext(new FakeGame().client);
		FollowerPlugin.restoreMemory(after, blob, gson, 0);
		assertEquals("the Abyssal whip from the abyssal demon", after.getIncidentPhrase());
		assertEquals("Abyssal demon", after.getIncidentWho());
		assertEquals("Abyssal whip", after.getIncidentItem());
		assertEquals(40, after.getIncidentHp());
		assertEquals("the place, with no region names to read it from", "Edgeville", after.getIncidentPlace());
		assertTrue(after.isIncidentNear());
		assertEquals(" near Edgeville", after.getMemoryWhere());
		assertFalse(after.isIncidentToRetell());

		// A save from before the particulars: the place comes off the region.
		String old = "{\"incidentKey\":\"the-death\",\"incidentPhrase\":\"the way that went\","
			+ "\"incidentCount\":2,\"incidentWhere\":12342,\"incidentWhenMs\":1700000000000,"
			+ "\"incidentWho\":\"Hill Giant\"}";
		TriggerContext loaded = new TriggerContext(new FakeGame().client);
		loaded.setRegionNames(names());
		FollowerPlugin.restoreMemory(loaded, old, gson, 0);
		assertEquals("the way that went", loaded.getIncidentPhrase());
		assertEquals("Edgeville", loaded.getIncidentPlace());
		assertFalse(loaded.isIncidentNear());
		assertEquals("", loaded.getIncidentItem());
		assertEquals(-1, loaded.getIncidentHp());
		assertTrue("waiting to be told again", loaded.isIncidentToRetell());
	}
}
