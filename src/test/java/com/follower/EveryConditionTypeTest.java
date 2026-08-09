package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every condition type the rule format advertises, driven until it fires.
 *
 * <p>A condition that never returns true is invisible: the rule using it simply
 * stays quiet, and nothing is logged. The bundled rules only use 36 of the 44
 * types, so the remaining 8 are advertised in the README and documented in
 * {@code Condition} with nothing anywhere proving they work at all. Each case
 * below sets up the state that should make it fire, and then asserts it did -
 * and, where the condition takes a threshold, that it does NOT fire on the
 * other side of it.
 */
public class EveryConditionTypeTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Types checked by a case here, so the coverage assertion can be honest. */
	private final Set<String> exercised = new HashSet<>();

	private Harness harnessFor(String when) throws IOException
	{
		return new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"probe\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": " + when + ", \"say\": [\"fired\"]}]}");
	}

	private void note(String... types)
	{
		for (String type : types)
		{
			exercised.add(type.toLowerCase(java.util.Locale.ROOT));
		}
	}

	private static void assertFired(Harness h, String why)
	{
		assertFalse(why + " - the condition never became true", h.firedBy("probe").isEmpty());
	}

	private static void assertQuiet(Harness h, String why)
	{
		assertTrue(why + " - it fired when it should not have", h.firedBy("probe").isEmpty());
	}

	// ----------------------------------------------------------- combinators

	@Test
	public void combinators() throws IOException
	{
		note("all", "any", "none", "always");

		Harness all = harnessFor("{\"type\": \"all\", \"conditions\": ["
			+ "{\"type\": \"always\"}, {\"type\": \"healthBelow\", \"percent\": 50}]}");
		all.game.hitpoints(10, 99);
		all.gameTicks(2);
		assertFired(all, "all");

		Harness allFails = harnessFor("{\"type\": \"all\", \"conditions\": ["
			+ "{\"type\": \"always\"}, {\"type\": \"healthBelow\", \"percent\": 50}]}");
		allFails.gameTicks(2);
		assertQuiet(allFails, "all with one false member");

		Harness any = harnessFor("{\"type\": \"any\", \"conditions\": ["
			+ "{\"type\": \"healthBelow\", \"percent\": 1}, {\"type\": \"always\"}]}");
		any.gameTicks(2);
		assertFired(any, "any");

		Harness none = harnessFor("{\"type\": \"none\", \"conditions\": ["
			+ "{\"type\": \"healthBelow\", \"percent\": 1}]}");
		none.gameTicks(2);
		assertFired(none, "none");

		Harness noneFails = harnessFor("{\"type\": \"none\", \"conditions\": ["
			+ "{\"type\": \"always\"}]}");
		noneFails.gameTicks(2);
		assertQuiet(noneFails, "none over a true member");
	}

	@Test
	public void anEmptyCombinatorDoesNotThrow() throws IOException
	{
		// all over nothing is vacuously true, any over nothing is false. What
		// matters is that a half-written rule cannot take the dispatch down.
		Harness all = harnessFor("{\"type\": \"all\", \"conditions\": []}");
		all.gameTicks(2);
		assertFired(all, "an empty all is vacuously true");

		Harness any = harnessFor("{\"type\": \"any\", \"conditions\": []}");
		any.gameTicks(2);
		assertQuiet(any, "an empty any has nothing to be true");

		Harness missing = harnessFor("{\"type\": \"all\"}");
		missing.gameTicks(2);
		assertFired(missing, "a combinator with no conditions key at all");
	}

	@Test
	public void chance() throws IOException
	{
		note("chance");

		Harness never = harnessFor("{\"type\": \"chance\", \"percent\": 0}");
		never.gameTicks(50);
		assertQuiet(never, "zero percent");

		Harness always = harnessFor("{\"type\": \"chance\", \"percent\": 100}");
		always.gameTicks(2);
		assertFired(always, "a hundred percent");
	}

	// ----------------------------------------------------------------- state

	@Test
	public void healthAndPrayerThresholds() throws IOException
	{
		note("healthBelow", "healthAbove", "prayerBelow", "prayerAbove");

		Harness below = harnessFor("{\"type\": \"healthBelow\", \"percent\": 50}");
		below.game.hitpoints(10, 99);
		below.gameTicks(2);
		assertFired(below, "healthBelow");

		Harness above = harnessFor("{\"type\": \"healthAbove\", \"percent\": 50}");
		above.gameTicks(2);
		assertFired(above, "healthAbove");

		Harness prayerLow = harnessFor("{\"type\": \"prayerBelow\", \"percent\": 20}");
		prayerLow.game.prayer(5, 99);
		prayerLow.gameTicks(2);
		assertFired(prayerLow, "prayerBelow");

		Harness prayerHigh = harnessFor("{\"type\": \"prayerAbove\", \"percent\": 20}");
		prayerHigh.gameTicks(2);
		assertFired(prayerHigh, "prayerAbove");
	}

	@Test
	public void prayerBelowCanRequireItToBeDraining() throws IOException
	{
		Harness h = harnessFor("{\"type\": \"prayerBelow\", \"percent\": 50,"
			+ " \"requirePrayerActive\": true}");
		h.game.prayer(10, 99);
		h.gameTicks(3);
		assertQuiet(h, "low prayer that is not draining is not prayer being used");

		h.game.prayer(9, 99);
		h.gameTicks(1);
		assertFired(h, "prayer dropping is prayer active");
	}

	@Test
	public void poisonAndVenomAndSkull() throws IOException
	{
		note("poisoned", "venomed", "skulled");
		int poisonVarp = net.runelite.api.gameval.VarPlayerID.POISON;

		Harness poisoned = harnessFor("{\"type\": \"poisoned\"}");
		poisoned.game.varp(poisonVarp, 12);
		poisoned.gameTicks(2);
		assertFired(poisoned, "poisoned");

		Harness venomed = harnessFor("{\"type\": \"venomed\"}");
		venomed.game.varp(poisonVarp, 1_000_050);
		venomed.gameTicks(2);
		assertFired(venomed, "venomed");

		// Venom is the same varp pushed past a million, so it must not also
		// read as ordinary poison.
		Harness notPoison = harnessFor("{\"type\": \"poisoned\"}");
		notPoison.game.varp(poisonVarp, 1_000_050);
		notPoison.gameTicks(2);
		assertQuiet(notPoison, "venom read as poison");

		Harness skulled = harnessFor("{\"type\": \"skulled\"}");
		skulled.game.skulled(true);
		skulled.gameTicks(2);
		assertFired(skulled, "skulled");
	}

	@Test
	public void energyAndIdleBounds() throws IOException
	{
		note("energyBelow", "idle", "idleBelow");

		Harness energy = harnessFor("{\"type\": \"energyBelow\", \"percent\": 20}");
		energy.game.energy(1500);
		energy.gameTicks(2);
		assertFired(energy, "energyBelow");

		Harness idle = harnessFor("{\"type\": \"idle\", \"ticks\": 5}");
		idle.gameTicks(8);
		assertFired(idle, "idle");

		Harness idleBelow = harnessFor("{\"type\": \"all\", \"conditions\": ["
			+ "{\"type\": \"idle\", \"ticks\": 3},"
			+ "{\"type\": \"idleBelow\", \"ticks\": 6}]}");
		idleBelow.gameTicks(5);
		assertFired(idleBelow, "the idle window between two bounds");
	}

	@Test
	public void itemEquipped() throws IOException
	{
		note("itemEquipped");

		Harness h = harnessFor("{\"type\": \"itemEquipped\", \"ids\": [4151]}");
		h.gameTicks(2);
		assertQuiet(h, "nothing worn");

		h.game.wearing(4151);
		h.gameTicks(2);
		assertFired(h, "itemEquipped");
	}

	@Test
	public void varbitEquals() throws IOException
	{
		note("varbitEquals");

		Harness h = harnessFor("{\"type\": \"varbitEquals\", \"varbit\": 4200, \"value\": 3}");
		h.game.varbit(4200, 1);
		h.gameTicks(2);
		assertQuiet(h, "the wrong value");

		h.game.varbit(4200, 3);
		h.gameTicks(2);
		assertFired(h, "varbitEquals");
	}

	// -------------------------------------------------------------- location

	@Test
	public void inRegionAndInArea() throws IOException
	{
		note("inRegion", "inArea");

		WorldPoint lumbridge = new WorldPoint(3222, 3218, 0);
		Harness region = harnessFor("{\"type\": \"inRegion\", \"regions\": ["
			+ lumbridge.getRegionID() + "]}");
		region.gameTicks(2);
		assertFired(region, "inRegion");

		Harness area = harnessFor("{\"type\": \"inArea\", \"x1\": 3200, \"y1\": 3200,"
			+ " \"x2\": 3250, \"y2\": 3250, \"plane\": 0}");
		area.gameTicks(2);
		assertFired(area, "inArea");

		Harness wrongPlane = harnessFor("{\"type\": \"inArea\", \"x1\": 3200, \"y1\": 3200,"
			+ " \"x2\": 3250, \"y2\": 3250, \"plane\": 2}");
		wrongPlane.gameTicks(2);
		assertQuiet(wrongPlane, "the area on another floor");
	}

	@Test
	public void inAreaAcceptsItsCornersInEitherOrder() throws IOException
	{
		// Written back to front, which is easy to do by hand from two clicks.
		Harness h = harnessFor("{\"type\": \"inArea\", \"x1\": 3250, \"y1\": 3250,"
			+ " \"x2\": 3200, \"y2\": 3200}");
		h.gameTicks(2);
		assertFired(h, "an area given from the far corner");
	}

	@Test
	public void anyLoadedRegionLooksWiderThanTheTileYouStandOn() throws IOException
	{
		Harness h = harnessFor("{\"type\": \"inRegion\", \"regions\": [4919],"
			+ " \"anyLoadedRegion\": true}");
		h.gameTicks(2);
		assertQuiet(h, "not loaded");

		h.game.regions(12850, 4919);
		h.gameTicks(2);
		assertFired(h, "a region loaded but not stood in");
	}

	@Test
	public void regionEnterAndReturnVisit() throws IOException
	{
		note("regionEnter", "returnVisit");

		Harness enter = harnessFor("{\"type\": \"regionEnter\", \"regions\": [12850]}");
		enter.gameTicks(1);
		enter.dispatch(TriggerEvent.regionChange(12850, 12851));
		assertFired(enter, "regionEnter");

		Harness ret = harnessFor("{\"type\": \"returnVisit\", \"minimum\": 1}");
		ret.gameTicks(1);
		ret.dispatch(TriggerEvent.regionChange(12850, 12851));
		assertFired(ret, "returnVisit");
	}

	// ----------------------------------------------------------------- scene

	@Test
	public void npcSpawnDespawnAndNearby() throws IOException
	{
		note("npcSpawn", "npcDespawn", "npcNearby", "petNearby");

		Harness spawn = harnessFor("{\"type\": \"npcSpawn\", \"names\": [\"Goblin\"]}");
		spawn.gameTicks(1);
		spawn.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		assertFired(spawn, "npcSpawn");

		Harness despawn = harnessFor("{\"type\": \"npcDespawn\", \"ids\": [3029]}");
		despawn.gameTicks(1);
		despawn.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_DESPAWN, 3029, "Goblin"));
		assertFired(despawn, "npcDespawn");

		Harness nearby = harnessFor("{\"type\": \"npcNearby\", \"names\": [\"Goblin\"],"
			+ " \"within\": 5}");
		nearby.gameTicks(2);
		assertQuiet(nearby, "an empty scene");
		nearby.game.spawnNpc(3029, "Goblin", 5);
		nearby.gameTicks(2);
		assertFired(nearby, "npcNearby");

		Harness pet = harnessFor("{\"type\": \"petNearby\", \"within\": 5}");
		pet.game.spawnNpc(3029, "Goblin", 5, false);
		pet.gameTicks(2);
		assertQuiet(pet, "a goblin is not a pet");
		pet.game.spawnNpc(5591, "Trotters", 0, true);
		pet.gameTicks(2);
		assertFired(pet, "petNearby");
	}

	@Test
	public void npcNamesMatchWithWildcardsAndIgnoreCase() throws IOException
	{
		Harness h = harnessFor("{\"type\": \"npcSpawn\", \"names\": [\"*guard*\"]}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 1, "Palace Guard"));
		assertFired(h, "a wildcard on both sides");
	}

	@Test
	public void anNpcNameIsMatchedWholeNotAsASubstring() throws IOException
	{
		Harness h = harnessFor("{\"type\": \"npcSpawn\", \"names\": [\"Guard\"]}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 1, "Guard dog"));
		assertQuiet(h, "a bare name must not match a longer one");
	}

	// ----------------------------------------------------------------- events

	@Test
	public void oneShotEventConditions() throws IOException
	{
		note("login", "playerDeath", "levelUp", "damageTaken", "chatMessage",
			"animationSelf", "varbitChanged", "lootWorth", "npcKill",
			"combatStart", "combatEnd", "thrallStart", "thrallSwitch",
			"thrallEnd", "errandStart", "errandEnd");

		fires("{\"type\": \"login\"}", TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		fires("{\"type\": \"playerDeath\"}", TriggerEvent.death());
		fires("{\"type\": \"levelUp\", \"names\": [\"Attack\"]}",
			TriggerEvent.levelUp("Attack", 70));
		fires("{\"type\": \"damageTaken\", \"minimum\": 10}", TriggerEvent.damageTaken(30));
		fires("{\"type\": \"chatMessage\", \"contains\": \"hello\"}",
			TriggerEvent.chat("well hello there", 0, ""));
		fires("{\"type\": \"chatMessage\", \"regex\": \"h.llo\"}",
			TriggerEvent.chat("hallo", 0, ""));
		fires("{\"type\": \"animationSelf\", \"ids\": [862]}", TriggerEvent.animation(862));
		fires("{\"type\": \"varbitChanged\", \"varbit\": 100, \"value\": 2}",
			TriggerEvent.varbit(100, 2, 1));
		fires("{\"type\": \"lootWorth\", \"minimum\": 1000}",
			TriggerEvent.loot(50000, "Dragon bones"));
		fires("{\"type\": \"npcKill\", \"minimum\": 100}",
			TriggerEvent.kill(2, "Zulrah", 725));
		fires("{\"type\": \"combatStart\"}",
			TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Goblin"));
		fires("{\"type\": \"combatEnd\"}",
			TriggerEvent.combat(TriggerEvent.Type.COMBAT_END, "Goblin"));
		fires("{\"type\": \"thrallStart\"}",
			TriggerEvent.thrall(TriggerEvent.Type.THRALL_START, "melee"));
		fires("{\"type\": \"thrallSwitch\"}", TriggerEvent.thrallSwitch("melee", "magic"));
		fires("{\"type\": \"thrallEnd\"}",
			TriggerEvent.thrall(TriggerEvent.Type.THRALL_END, "melee"));
		fires("{\"type\": \"errandStart\"}",
			TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, "bank"));
		fires("{\"type\": \"errandEnd\"}",
			TriggerEvent.errand(TriggerEvent.Type.ERRAND_END, "bank"));
	}

	private void fires(String when, TriggerEvent event) throws IOException
	{
		Harness h = harnessFor(when);
		h.gameTicks(1);
		h.dispatch(event);
		assertFired(h, when);
	}

	@Test
	public void thresholdConditionsRejectTheWrongSideOfTheLine() throws IOException
	{
		quiet("{\"type\": \"damageTaken\", \"minimum\": 30}", TriggerEvent.damageTaken(5));
		quiet("{\"type\": \"lootWorth\", \"minimum\": 100000}",
			TriggerEvent.loot(500, "Bones"));
		quiet("{\"type\": \"npcKill\", \"minimum\": 100}", TriggerEvent.kill(1, "Rat", 1));
		quiet("{\"type\": \"npcKill\", \"maximum\": 99}", TriggerEvent.kill(2, "Zulrah", 725));
		quiet("{\"type\": \"levelUp\", \"names\": [\"Attack\"]}",
			TriggerEvent.levelUp("Cooking", 70));
		quiet("{\"type\": \"chatMessage\", \"contains\": \"hello\"}",
			TriggerEvent.chat("goodbye", 0, ""));
		quiet("{\"type\": \"animationSelf\", \"ids\": [862]}", TriggerEvent.animation(999));
		quiet("{\"type\": \"varbitChanged\", \"varbit\": 100, \"value\": 2}",
			TriggerEvent.varbit(100, 5, 1));
	}

	private void quiet(String when, TriggerEvent event) throws IOException
	{
		Harness h = harnessFor(when);
		h.gameTicks(1);
		h.dispatch(event);
		assertQuiet(h, when);
	}

	@Test
	public void anEventConditionIgnoresEveryOtherKindOfEvent() throws IOException
	{
		Harness h = harnessFor("{\"type\": \"playerDeath\"}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.animation(862));
		h.dispatch(TriggerEvent.chat("hello", 0, ""));
		h.dispatch(TriggerEvent.loot(9999999, "Twisted bow"));
		h.gameTicks(5);
		assertQuiet(h, "playerDeath answering something else");
	}

	// -------------------------------------------------------------- combat

	@Test
	public void combatAndBossFightAndDeathSpot() throws IOException
	{
		note("combat", "bossFight", "nearDeathSpot");

		Harness combat = harnessFor("{\"type\": \"combat\"}");
		NPC goblin = combat.game.spawnNpc(3029, "Goblin", 5);
		combat.game.fighting(goblin);
		combat.gameTicks(2);
		assertFired(combat, "combat");

		Harness boss = harnessFor("{\"type\": \"bossFight\"}");
		NPC zulrah = boss.game.spawnNpc(2042, "Zulrah", 725);
		boss.game.fighting(zulrah);
		boss.gameTicks(2);
		assertFired(boss, "bossFight");

		Harness notBoss = harnessFor("{\"type\": \"bossFight\"}");
		NPC rat = notBoss.game.spawnNpc(2854, "Giant rat", 3);
		notBoss.game.fighting(rat);
		notBoss.gameTicks(2);
		assertQuiet(notBoss, "a giant rat is not a boss");

		Harness spot = harnessFor("{\"type\": \"nearDeathSpot\", \"within\": 5}");
		spot.gameTicks(1);
		spot.engine.getContext().noteDeath(new WorldPoint(3222, 3218, 0));
		spot.gameTicks(250);
		assertFired(spot, "nearDeathSpot");
	}

	@Test
	public void mood() throws IOException
	{
		note("mood");

		Harness even = harnessFor("{\"type\": \"mood\", \"is\": \"even\"}");
		even.gameTicks(2);
		assertFired(even, "a session starts even");

		Harness low = harnessFor("{\"type\": \"mood\", \"is\": \"low\"}");
		low.gameTicks(2);
		assertQuiet(low, "even is not low");
		low.engine.getContext().adjustMood(-40);
		low.gameTicks(2);
		assertFired(low, "mood band");

		Harness range = harnessFor("{\"type\": \"mood\", \"minimum\": 60}");
		range.gameTicks(2);
		assertQuiet(range, "fifty is below sixty");
		range.engine.getContext().adjustMood(20);
		range.gameTicks(2);
		assertFired(range, "mood range");
	}

	@Test
	public void repeating() throws IOException
	{
		note("repeating");

		Harness h = harnessFor("{\"type\": \"repeating\", \"ticks\": 20}");
		h.gameTicks(30);
		assertQuiet(h, "standing about is not doing something over and over");

		h.game.animating(879);
		h.gameTicks(30);
		assertFired(h, "repeating");
	}

	@Test
	public void awayFor() throws IOException
	{
		note("awayFor");

		Harness h = harnessFor("{\"type\": \"awayFor\", \"minimum\": 60}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertQuiet(h, "a follower with no idea how long it was must not guess");

		h.engine.getContext().setMinutesAway(30);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertQuiet(h, "half an hour is not an absence");

		h.engine.getContext().setMinutesAway(600);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertFired(h, "awayFor");
	}

	@Test
	public void tallyAndRecordsAndSessions() throws IOException
	{
		note("tally", "personalBest", "sessionCount");

		// A lifetime count. State, so it needs no event of its own - which is
		// exactly why it has to stay quiet until the count is actually there.
		Harness counted = harnessFor("{\"type\": \"tally\", \"of\": \"kill:rat\", \"minimum\": 3}");
		counted.gameTicks(2);
		assertQuiet(counted, "nothing has been counted yet");
		counted.engine.getContext().tally("kill:rat");
		counted.engine.getContext().tally("kill:rat");
		counted.gameTicks(2);
		assertQuiet(counted, "two is not three");
		counted.engine.getContext().tally("kill:rat");
		counted.gameTicks(2);
		assertFired(counted, "tally");

		fires("{\"type\": \"personalBest\", \"names\": [\"hit\"]}",
			TriggerEvent.record("hit", 42, 30));
		quiet("{\"type\": \"personalBest\", \"names\": [\"session\"]}",
			TriggerEvent.record("hit", 42, 30));

		Harness sessions = harnessFor("{\"type\": \"sessionCount\", \"every\": 10}");
		sessions.gameTicks(1);
		sessions.engine.getContext().setSessionCount(7);
		sessions.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertQuiet(sessions, "seven is not a round number");
		sessions.engine.getContext().setSessionCount(10);
		sessions.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertFired(sessions, "sessionCount");
	}

	@Test
	public void tasteIsAboutWhereTheFollowerIsStanding() throws IOException
	{
		note("feelsAbout");

		Harness h = harnessFor("{\"type\": \"feelsAbout\", \"is\": \"liked\"}");
		h.gameTicks(2);
		assertQuiet(h, "a follower with no taste yet likes nowhere in particular");

		int here = new WorldPoint(3222, 3218, 0).getRegionID();
		h.engine.getContext().setTraits(
			new HashSet<>(java.util.Collections.singletonList(here)),
			new HashSet<>(java.util.Collections.singletonList(here + 1)));
		h.gameTicks(2);
		assertFired(h, "feelsAbout");

		// The other half: a place it dislikes is not one it likes.
		Harness sour = harnessFor("{\"type\": \"feelsAbout\", \"is\": \"disliked\"}");
		sour.engine.getContext().setTraits(
			new HashSet<>(java.util.Collections.singletonList(here + 1)),
			new HashSet<>(java.util.Collections.singletonList(here)));
		sour.gameTicks(2);
		assertFired(sour, "feelsAbout disliked");
	}

	@Test
	public void wantsAreAskedForFulfilledAndForgotten() throws IOException
	{
		note("wanting", "wantFulfilled", "wantExpired");

		// A rule that asks for somewhere, and one that notices the answer.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"probe\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"wantFulfilled\"}, \"say\": [\"fired\"]},"
				+ "{\"id\": \"asker\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"want\": {\"region\": 10553, \"label\": \"the guild\", \"minutes\": 5},"
				+ " \"when\": {\"type\": \"login\"}, \"say\": [\"can we go?\"]},"
				+ "{\"id\": \"gaveup\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"wantExpired\"}, \"say\": [\"never mind\"]}]}");

		h.gameTicks(1);
		assertTrue("nothing has been asked for yet", !h.engine.getContext().isWanting());
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertTrue("saying it is what opens the want", h.engine.getContext().isWanting());

		// A second ask must not quietly replace the first.
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertEquals("the follower asked for one thing", "the guild",
			h.engine.getContext().getWantLabel());

		h.gameTicks(2);
		assertQuiet(h, "we have not gone anywhere");

		// A region id is (x >> 6) << 8 | (y >> 6), so this tile IS region 10553.
		h.game.at(2624, 3648, 0);
		assertEquals("the test tile has to be in the wanted region",
			10553, new WorldPoint(2624, 3648, 0).getRegionID());
		h.gameTicks(2);
		assertFired(h, "wantFulfilled");
		assertTrue("a fulfilled want is closed", !h.engine.getContext().isWanting());

		// And the other ending: asked for, never done, quietly given up on.
		Harness lapsed = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"probe\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"wantExpired\"}, \"say\": [\"fired\"]},"
				+ "{\"id\": \"asker\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"want\": {\"region\": 10553, \"label\": \"the guild\", \"minutes\": 1},"
				+ " \"when\": {\"type\": \"login\"}, \"say\": [\"can we go?\"]}]}");
		lapsed.gameTicks(1);
		lapsed.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		lapsed.gameTicks(50);
		assertQuiet(lapsed, "a minute has not passed");
		lapsed.gameTicks(60);
		assertFired(lapsed, "wantExpired");

		Harness open = harnessFor("{\"type\": \"wanting\"}");
		open.gameTicks(2);
		assertQuiet(open, "nothing is wanted");
		open.engine.getContext().setWant(10553, "the guild", 5);
		open.gameTicks(1);
		assertFired(open, "wanting");
	}

	@Test
	public void inventoryRoomAndCrowdsAndDangerousNeighbours() throws IOException
	{
		note("inventoryFree", "playersNearby");

		// The point of this one is that it fires while there is still room.
		Harness bag = harnessFor("{\"type\": \"inventoryFree\", \"maximum\": 2}");
		bag.game.inventoryUsing(20);
		bag.gameTicks(2);
		assertQuiet(bag, "eight slots left is not nearly full");
		bag.game.inventoryUsing(26);
		bag.gameTicks(2);
		assertFired(bag, "inventoryFree");

		// No container yet must read as empty, not as full: a follower warning
		// about a bag it has not seen would fire on every login.
		Harness unloaded = harnessFor("{\"type\": \"inventoryFree\", \"maximum\": 2}");
		unloaded.game.noInventory();
		unloaded.gameTicks(2);
		assertQuiet(unloaded, "an inventory that has not loaded is not a full one");

		Harness crowd = harnessFor("{\"type\": \"playersNearby\", \"minimum\": 4, \"within\": 5}");
		crowd.gameTicks(2);
		assertQuiet(crowd, "nobody about");
		for (int i = 0; i < 3; i++)
		{
			crowd.game.spawnPlayer(1, i);
		}
		crowd.gameTicks(2);
		assertQuiet(crowd, "three is not a crowd");
		crowd.game.spawnPlayer(2, 2);
		crowd.gameTicks(2);
		assertFired(crowd, "playersNearby");

		// Someone standing well out of the way does not make it busy here.
		Harness distant = harnessFor("{\"type\": \"playersNearby\", \"minimum\": 1, \"within\": 3}");
		distant.game.spawnPlayer(40, 40);
		distant.gameTicks(2);
		assertQuiet(distant, "a player two streets away is not nearby");

		// npcNearby on combat level alone, naming nothing.
		Harness big = harnessFor("{\"type\": \"npcNearby\", \"minimum\": 200, \"within\": 6}");
		big.game.spawnNpc(1, "Rat", 1);
		big.gameTicks(2);
		assertQuiet(big, "a rat is not a threat");
		big.game.spawnNpc(2, "Something Enormous", 400);
		big.gameTicks(2);
		assertFired(big, "npcNearby by level with no name given");
	}

	@Test
	public void chatCanBeNarrowedToWhoSaidItAndHow() throws IOException
	{
		int publicChat = net.runelite.api.ChatMessageType.PUBLICCHAT.getType();

		Harness mine = harnessFor("{\"type\": \"chatMessage\", \"from\": \"player\","
			+ " \"contains\": \"hello\"}");
		mine.gameTicks(1);
		mine.someoneSays("Bystander", "hello");
		assertQuiet(mine, "a follower that answers the whole street is not answering you");
		mine.playerSays("hello");
		assertFired(mine, "the player's own line");

		Harness others = harnessFor("{\"type\": \"chatMessage\", \"from\": \"others\","
			+ " \"contains\": \"hello\"}");
		others.gameTicks(1);
		others.playerSays("hello");
		assertQuiet(others, "the player is not 'others'");
		others.someoneSays("Bystander", "hello");
		assertFired(others, "somebody else's line");

		// The chat TYPE, named exactly as ::follower chatwatch prints it.
		Harness typed = harnessFor("{\"type\": \"chatMessage\", \"is\": \"PUBLICCHAT\","
			+ " \"contains\": \"burnt\"}");
		typed.gameTicks(1);
		typed.dispatch(TriggerEvent.chat("You accidentally burnt the food.",
			net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
		assertQuiet(typed, "a game message is not somebody talking");
		typed.dispatch(TriggerEvent.chat("burnt again", publicChat, "Tester"));
		assertFired(typed, "the right chat type");

		Harness unknownType = harnessFor("{\"type\": \"chatMessage\", \"is\": \"NONSENSE\","
			+ " \"contains\": \"x\"}");
		unknownType.gameTicks(1);
		unknownType.playerSays("x");
		assertQuiet(unknownType, "a misspelt chat type must match nothing, not everything");
	}

	@Test
	public void answeredHovererAndExamined() throws IOException
	{
		note("answered", "hovered", "examined");

		Harness yes = harnessFor("{\"type\": \"answered\", \"is\": \"yes\"}");
		yes.gameTicks(1);
		yes.playerSays("yes");
		assertQuiet(yes, "nothing was asked, so nothing can be an answer");

		yes.engine.getContext().noteQuestion();
		yes.playerSays("broken");
		assertQuiet(yes, "'ok' inside 'broken' is not agreement");

		yes.engine.getContext().noteQuestion();
		yes.playerSays("yeah!");
		assertFired(yes, "answered yes");

		// The window shuts, so a later stray "yes" is not agreement to
		// something the player has long forgotten being asked.
		Harness stale = harnessFor("{\"type\": \"answered\", \"is\": \"yes\"}");
		stale.gameTicks(1);
		stale.engine.getContext().noteQuestion();
		stale.gameTicks(30);
		stale.playerSays("yes");
		assertQuiet(stale, "an answer twenty seconds late is an answer to nothing");

		Harness no = harnessFor("{\"type\": \"answered\", \"is\": \"no\"}");
		no.gameTicks(1);
		no.engine.getContext().noteQuestion();
		no.playerSays("nah");
		assertFired(no, "answered no");

		Harness hovered = harnessFor("{\"type\": \"hovered\", \"ticks\": 4}");
		hovered.gameTicks(3);
		assertQuiet(hovered, "nobody is looking");
		hovered.engine.getContext().setHoverTicks(6);
		hovered.gameTicks(1);
		assertFired(hovered, "hovered");

		fires("{\"type\": \"examined\"}",
			TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
	}

	@Test
	public void thievingEdges() throws IOException
	{
		note("thievingStart", "thievingEnd", "thieving");

		fires("{\"type\": \"thievingStart\"}",
			TriggerEvent.simple(TriggerEvent.Type.THIEVING_START));
		fires("{\"type\": \"thievingEnd\"}",
			TriggerEvent.simple(TriggerEvent.Type.THIEVING_END));

		quiet("{\"type\": \"thievingStart\"}",
			TriggerEvent.simple(TriggerEvent.Type.THIEVING_END));

		// The state, as opposed to the two edges.
		Harness during = harnessFor("{\"type\": \"thieving\"}");
		during.gameTicks(2);
		assertQuiet(during, "nobody is thieving");
		during.game.animating(881);
		during.gameTicks(2);
		assertFired(during, "thieving");
	}

	// ------------------------------------------------------------- coverage

	@Test
	public void everyTypeTheEngineSupportsIsExercisedSomewhere() throws IOException
	{
		// Run every case, then compare what they touched against the full list.
		combinators();
		chance();
		healthAndPrayerThresholds();
		poisonAndVenomAndSkull();
		energyAndIdleBounds();
		itemEquipped();
		varbitEquals();
		inRegionAndInArea();
		regionEnterAndReturnVisit();
		npcSpawnDespawnAndNearby();
		oneShotEventConditions();
		combatAndBossFightAndDeathSpot();
		mood();
		repeating();
		awayFor();
		tallyAndRecordsAndSessions();
		answeredHovererAndExamined();
		inventoryRoomAndCrowdsAndDangerousNeighbours();
		wantsAreAskedForFulfilledAndForgotten();
		tasteIsAboutWhereTheFollowerIsStanding();
		thievingEdges();

		List<String> missing = new ArrayList<>(new TreeSet<>(RuleSetIntegrityTest.KNOWN_TYPES));
		missing.removeAll(exercised);
		assertEquals("condition types with no test proving they ever fire: " + missing,
			0, missing.size());
	}
}
