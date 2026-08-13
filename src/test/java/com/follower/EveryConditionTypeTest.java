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

		// And the half that was missing: it has to stay quiet ELSEWHERE.
		// Fifty-eight area rules hang off this one condition, so a version
		// that matched everywhere would have the follower announcing Lumbridge
		// from Varrock - and every test here passed against exactly that.
		Harness elsewhere = harnessFor("{\"type\": \"inRegion\", \"regions\": ["
			+ lumbridge.getRegionID() + "]}");
		elsewhere.game.at(2624, 3648, 0);
		elsewhere.gameTicks(3);
		assertQuiet(elsewhere, "a region rule fired somewhere else entirely");

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

		// The name was checked and the RADIUS never was. Without it the
		// follower announces a boss from the other side of the map, which is
		// what "within" exists to stop.
		Harness far = harnessFor("{\"type\": \"npcNearby\", \"names\": [\"Goblin\"],"
			+ " \"within\": 5}");
		NPC distant = far.game.spawnNpc(3029, "Goblin", 5);
		far.game.moveNpc(distant, 3222 + 40, 3218 + 40, 0);
		far.gameTicks(3);
		assertQuiet(far, "a goblin forty tiles away is not nearby");

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
	public void aWishOpensBySayingAndClosesByGiving() throws IOException
	{
		note("wishing");

		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"wisher\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"wish\": {\"what\": \"feather\", \"minutes\": 5},"
				+ " \"when\": {\"type\": \"login\"}, \"say\": [\"a feather, if you see one\"]},"
				+ "{\"id\": \"probe\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"all\", \"conditions\": ["
				+ "{\"type\": \"wishing\", \"is\": \"feather\"},"
				+ "{\"type\": \"answered\", \"is\": \"gift\"}]},"
				+ " \"say\": [\"fired\"]}]}");
		h.gameTicks(1);

		h.answers("gift");
		assertQuiet(h, "no wish is open yet, so the gift lands on nothing");

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(1);
		assertTrue("saying the wish opens it", h.engine.getContext().isWishing());

		h.answers("gift");
		assertFired(h, "wishing while the gift arrives");
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

		// The answer is a branch picked in the conversation the follower
		// opened, so nothing typed into chat can be one.
		Harness yes = harnessFor("{\"type\": \"answered\", \"is\": \"yes\"}");
		yes.gameTicks(1);
		yes.playerSays("yes");
		assertQuiet(yes, "a word typed in chat is no longer an answer");

		yes.answers("no");
		assertQuiet(yes, "no is not yes");

		yes.answers("yes");
		assertFired(yes, "answered yes");

		Harness no = harnessFor("{\"type\": \"answered\", \"is\": \"no\"}");
		no.gameTicks(1);
		no.answers("no");
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

	@Test
	public void theIncidentItKeepsBringingUp() throws IOException
	{
		note("remembers");

		Harness any = harnessFor("{\"type\": \"remembers\"}");
		any.gameTicks(2);
		assertQuiet(any, "nothing has happened worth remembering");
		any.engine.getContext().noteIncident("chicken-death", "that chicken");
		any.gameTicks(1);
		assertFired(any, "remembers");

		// Named: it is the RIGHT incident, not merely some incident. Without
		// this the follower brings up the chicken after drowning in the sea.
		Harness named = harnessFor(
			"{\"type\": \"remembers\", \"is\": \"chicken-death\"}");
		named.engine.getContext().noteIncident("drowned", "the swim back");
		named.gameTicks(2);
		assertQuiet(named, "a different incident is on its mind");
		named.engine.getContext().noteIncident("chicken-death", "that chicken");
		named.gameTicks(1);
		assertFired(named, "remembers named");

		// And the count, which is what makes "again" honest.
		Harness twice = harnessFor(
			"{\"type\": \"remembers\", \"is\": \"chicken-death\", \"minimum\": 2}");
		twice.engine.getContext().noteIncident("chicken-death", "that chicken");
		twice.gameTicks(2);
		assertQuiet(twice, "it has only happened once");
		twice.engine.getContext().noteIncident("chicken-death", "that chicken");
		twice.gameTicks(1);
		assertFired(twice, "remembers twice");
	}

	@Test
	public void whatItIsCarryingAndWhenItLosesIt() throws IOException
	{
		note("carrying", "souvenirLost");

		Harness h = harnessFor("{\"type\": \"carrying\"}");
		h.gameTicks(2);
		assertQuiet(h, "its hands are empty");
		h.engine.getContext().pickUp("a nice flat rock", 1);
		h.gameTicks(1);
		assertFired(h, "carrying");

		// One at a time: a second pick-up while holding something is refused,
		// or the follower ends up describing a rock it swapped out silently.
		h.engine.getContext().pickUp("a shinier rock", 5);
		assertEquals("it can only carry one thing", "a nice flat rock",
			h.engine.getContext().getSouvenir());

		// And the losing of it, a minute later.
		Harness gone = harnessFor("{\"type\": \"souvenirLost\"}");
		gone.engine.getContext().pickUp("a nice flat rock", 1);
		gone.gameTicks(50);
		assertQuiet(gone, "it is still holding the thing");
		gone.gameTicks(60);
		assertFired(gone, "souvenirLost");
	}

	@Test
	public void theBetIsPlacedAndSettledBothWays() throws IOException
	{
		note("betting", "betWon", "betLost");

		Harness open = harnessFor("{\"type\": \"betting\"}");
		open.gameTicks(2);
		assertQuiet(open, "no prediction is outstanding");
		open.engine.getContext().placeBet(false, 50_000, 5);
		open.gameTicks(1);
		assertFired(open, "betting");

		// Betting the drop comes in UNDER 50k, and it does.
		Harness won = harnessFor("{\"type\": \"betWon\"}");
		won.engine.getContext().placeBet(false, 50_000, 5);
		won.gameTicks(1);
		won.engine.getContext().settleBet(1_200);
		won.gameTicks(2);
		assertFired(won, "betWon");

		// Same prediction, a drop that beats it. The half that has to work for
		// the other half to mean anything.
		Harness lost = harnessFor("{\"type\": \"betLost\"}");
		lost.engine.getContext().placeBet(false, 50_000, 5);
		lost.gameTicks(1);
		lost.engine.getContext().settleBet(2_000_000);
		lost.gameTicks(2);
		assertFired(lost, "betLost");

		// A bet nobody ever collected on is not a win.
		Harness lapsed = harnessFor("{\"type\": \"betWon\"}");
		lapsed.engine.getContext().placeBet(false, 50_000, 1);
		lapsed.gameTicks(120);
		assertQuiet(lapsed, "an uncollected bet cannot be right by default");
	}

	@Test
	public void theHourAndHowLongWeHaveBeenAtIt() throws IOException
	{
		note("timeOfDay", "sessionMinutes");

		// The hour comes off the player's own clock, so the test cannot pick
		// it - it can only ask about the hour it actually is, and about the
		// window that excludes it.
		int hour = java.time.LocalTime.now().getHour();

		Harness now = harnessFor(
			"{\"type\": \"timeOfDay\", \"minimum\": " + hour
				+ ", \"maximum\": " + hour + "}");
		now.gameTicks(2);
		assertFired(now, "timeOfDay covering the current hour");

		int elsewhere = (hour + 6) % 24;
		Harness other = harnessFor(
			"{\"type\": \"timeOfDay\", \"minimum\": " + elsewhere
				+ ", \"maximum\": " + elsewhere + "}");
		other.gameTicks(2);
		assertQuiet(other, "a window six hours away is not now");

		// The wrap past midnight, which is the whole reason the comparison is
		// not a plain range check: 23-to-5 has to mean five hours, not none.
		Harness wrapped = harnessFor(
			"{\"type\": \"timeOfDay\", \"minimum\": " + ((hour + 23) % 24)
				+ ", \"maximum\": " + ((hour + 1) % 24) + "}");
		wrapped.gameTicks(2);
		assertFired(wrapped, "a window straddling midnight still contains now");

		Harness been = harnessFor("{\"type\": \"sessionMinutes\", \"minimum\": 90}");
		been.engine.getContext().setSessionMinutes(89);
		been.gameTicks(2);
		assertQuiet(been, "eighty-nine minutes is not ninety");
		been.engine.getContext().setSessionMinutes(90);
		been.gameTicks(1);
		assertFired(been, "sessionMinutes");
	}

	@Test
	public void aQuestionAlreadyOnTheTable() throws IOException
	{
		note("asking");

		Harness h = harnessFor("{\"type\": \"asking\"}");
		h.gameTicks(2);
		assertQuiet(h, "nothing has been asked");
		h.engine.getContext().noteQuestion("want-outing");
		h.gameTicks(1);
		assertFired(h, "asking");

		// Named, which is how one question can be told from another.
		Harness named = harnessFor("{\"type\": \"asking\", \"is\": \"game-hands\"}");
		named.engine.getContext().noteQuestion("want-outing");
		named.gameTicks(2);
		assertQuiet(named, "a different question is open");
		named.engine.getContext().noteQuestion("game-hands");
		named.gameTicks(1);
		assertFired(named, "asking named");

		// Answering closes it, which is what the guard on every asking rule
		// depends on: once answered, a new question may be opened.
		Harness closed = harnessFor("{\"type\": \"asking\"}");
		closed.engine.getContext().noteQuestion("want-outing");
		closed.engine.getContext().noteAnswered();
		closed.gameTicks(2);
		assertQuiet(closed, "an answered question is no longer on the table");
	}

	@Test
	public void tasteIsEarnedAndOutranksTheRoll() throws IOException
	{
		note("placeScore", "happenedHere");

		Harness h = harnessFor("{\"type\": \"placeScore\", \"maximum\": -80}");
		h.gameTicks(2);
		assertQuiet(h, "nothing has happened here yet");

		// Five deaths' worth of misery, in the units the rules already use.
		for (int i = 0; i < 5; i++)
		{
			h.engine.getContext().notePlaceFeeling(-25);
		}
		h.gameTicks(1);
		assertFired(h, "placeScore");

		// And the point of it: experience beats the shuffle. A place the roll
		// called a favourite is not a favourite once it has taken you twice.
		Harness sour = harnessFor("{\"type\": \"feelsAbout\", \"is\": \"liked\"}");
		int here = new WorldPoint(3222, 3218, 0).getRegionID();
		sour.engine.getContext().setTraits(
			new HashSet<>(java.util.Collections.singletonList(here)),
			new HashSet<>());
		sour.gameTicks(2);
		assertFired(sour, "the rolled taste still answers where nothing has happened");

		sour.clear();
		for (int i = 0; i < 3; i++)
		{
			sour.engine.getContext().notePlaceFeeling(-25);
		}
		sour.gameTicks(2);
		assertQuiet(sour, "a place it has learned to dislike is not liked");

		// The place's own memory, which waits here rather than following you.
		Harness held = harnessFor("{\"type\": \"happenedHere\"}");
		held.gameTicks(2);
		assertQuiet(held, "this place holds nothing yet");
		held.engine.getContext().notePlaceMemory("you got that drop");
		held.gameTicks(2);
		assertQuiet(held, "a thing that just happened is not something a place reminds you of");

		// It belongs to the PLACE: somewhere else, it is not on offer.
		held.game.at(3300, 3300, 0);
		held.gameTicks(2);
		assertQuiet(held, "a different region holds a different nothing");

		// And coming back is what makes it worth saying.
		held.game.at(3222, 3218, 0);
		held.gameTicks(2);
		assertFired(held, "happenedHere, once you have been away and returned");
	}

	@Test
	public void adviceIsTakenOrIsNot() throws IOException
	{
		note("heeded", "ignored", "advising");

		Harness open = harnessFor("{\"type\": \"advising\"}");
		open.gameTicks(2);
		assertQuiet(open, "no advice is outstanding");
		open.engine.getContext().adviseOn("food",
			new HashSet<>(java.util.Arrays.asList(829)), false, 1);
		open.gameTicks(1);
		assertFired(open, "advising");

		// Doing the thing inside the window settles it.
		Harness took = harnessFor("{\"type\": \"heeded\", \"is\": \"food\"}");
		took.engine.getContext().adviseOn("food",
			new HashSet<>(java.util.Arrays.asList(829)), false, 1);
		took.gameTicks(2);
		assertQuiet(took, "nothing has been eaten");
		took.dispatch(TriggerEvent.animation(829));
		took.gameTicks(2);
		assertFired(took, "heeded");

		// A DIFFERENT animation is not the thing that was asked for.
		Harness wrong = harnessFor("{\"type\": \"heeded\"}");
		wrong.engine.getContext().adviseOn("food",
			new HashSet<>(java.util.Arrays.asList(829)), false, 1);
		wrong.dispatch(TriggerEvent.animation(714));
		wrong.gameTicks(3);
		assertQuiet(wrong, "teleporting is not eating");

		// And the window shutting on nothing.
		Harness lapsed = harnessFor("{\"type\": \"ignored\"}");
		lapsed.engine.getContext().adviseOn("food",
			new HashSet<>(java.util.Arrays.asList(829)), false, 1);
		lapsed.gameTicks(50);
		assertQuiet(lapsed, "the minute is not up");
		lapsed.gameTicks(60);
		assertFired(lapsed, "ignored");
	}

	@Test
	public void adviceAboutTheBagIsSettledByMakingRoom() throws IOException
	{
		// The one piece of advice with no animation to watch for. Room is made
		// by banking, dropping, or eating the thing that was in the way, and
		// the follower should not care which.
		Harness h = harnessFor("{\"type\": \"heeded\", \"is\": \"the bag\"}");
		h.game.inventoryUsing(27);
		h.gameTicks(1);
		h.engine.getContext().adviseOn("the bag", null, true, 3);
		h.gameTicks(2);
		assertQuiet(h, "the bag is as full as it was");

		h.game.inventoryUsing(22);
		h.gameTicks(2);
		assertQuiet(h, "emptying it instantly is not taking advice");

		// It settles once enough time has passed that the player can be said
		// to have acted on it rather than to have been about to anyway.
		h.gameTicks(30);
		assertFired(h, "heeded via making room");
	}

	@Test
	public void makingRoomAtOnceIsNotTakingAdvice() throws IOException
	{
		// Found in a transcript rather than by review. Three hours of Guardians
		// of the Rift produced fifty-six lines - a fifth of everything said -
		// of the follower warning about the bag and thanking the player four
		// seconds later, because emptying the bag IS the activity. A bag
		// getting emptier is ambient in a way that eating is not, so the room
		// kind needs a beat before it counts.
		Harness h = harnessFor("{\"type\": \"heeded\"}");
		h.game.inventoryUsing(27);
		h.gameTicks(1);
		h.engine.getContext().adviseOn("the bag", null, true, 3);

		h.game.inventoryUsing(10);
		h.gameTicks(4);
		assertQuiet(h, "four seconds is not a response, it is a coincidence");
	}

	@Test
	public void eatingWhenToldToEatStillCountsImmediately() throws IOException
	{
		// The other half: the delay must NOT apply to an animation. Eating
		// promptly when told to eat is exactly the thing worth acknowledging,
		// and it is attributable in a way that a slot freeing up is not.
		Harness h = harnessFor("{\"type\": \"heeded\", \"is\": \"food\"}");
		h.gameTicks(1);
		h.engine.getContext().adviseOn("food",
			new HashSet<>(java.util.Arrays.asList(829)), false, 1);
		h.dispatch(TriggerEvent.animation(829));
		h.gameTicks(2);
		assertFired(h, "prompt is the point, for food");
	}

	@Test
	public void howLongWeHaveKnownEachOther() throws IOException
	{
		note("daysKnown", "anniversary");

		Harness h = harnessFor("{\"type\": \"daysKnown\", \"minimum\": 365}");
		h.gameTicks(2);
		assertQuiet(h, "no first meeting has been recorded");

		h.engine.getContext().setMetOnDay(
			java.time.LocalDate.now().toEpochDay() - 400);
		h.gameTicks(1);
		assertFired(h, "daysKnown");

		Harness young = harnessFor("{\"type\": \"daysKnown\", \"minimum\": 365}");
		young.engine.getContext().setMetOnDay(
			java.time.LocalDate.now().toEpochDay() - 100);
		young.gameTicks(2);
		assertQuiet(young, "a hundred days is not a year");

		// The same day of the year, come round again.
		Harness today = harnessFor("{\"type\": \"anniversary\"}");
		today.engine.getContext().setMetOnDay(
			java.time.LocalDate.now().minusYears(2).toEpochDay());
		today.gameTicks(2);
		assertFired(today, "anniversary");

		// The first meeting is not an anniversary of anything.
		Harness first = harnessFor("{\"type\": \"anniversary\"}");
		first.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay());
		first.gameTicks(2);
		assertQuiet(first, "today is not the anniversary of today");

		// Nor is a date that merely shares the year.
		Harness other = harnessFor("{\"type\": \"anniversary\"}");
		other.engine.getContext().setMetOnDay(
			java.time.LocalDate.now().minusYears(2).plusDays(3).toEpochDay());
		other.gameTicks(2);
		assertQuiet(other, "three days out is not the day");
	}

	@Test
	public void theNameYouEarnAndTheGearYouOutgrew() throws IOException
	{
		note("nicknamed", "outgrew");

		Harness h = harnessFor("{\"type\": \"nicknamed\"}");
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		names.put("deaths", "the gravedigger");
		names.put("kills", "the local menace");
		h.engine.getContext().setNicknames(names);
		h.gameTicks(2);
		assertQuiet(h, "nothing has been done often enough to earn a name");

		for (int i = 0; i < 30; i++)
		{
			h.engine.getContext().tally("deaths");
		}
		h.gameTicks(1);
		assertFired(h, "nicknamed");
		assertEquals("the biggest tally names you",
			"the gravedigger", h.engine.getContext().getNickname());

		// And it MOVES. A name earned by dying is not kept once you mostly kill.
		for (int i = 0; i < 60; i++)
		{
			h.engine.getContext().tally("kills");
		}
		assertEquals("the name follows what you actually do",
			"the local menace", h.engine.getContext().getNickname());

		Harness rich = harnessFor("{\"type\": \"outgrew\", \"minimum\": 10}");
		rich.engine.getContext().setWornValue(500_000);
		rich.gameTicks(2);
		assertQuiet(rich, "there is nothing to compare against yet");

		rich.engine.getContext().setMetWearingValue(1_000);
		rich.gameTicks(1);
		assertFired(rich, "outgrew");

		// A follower that has only ever known you rich has no story to tell.
		Harness always = harnessFor("{\"type\": \"outgrew\", \"minimum\": 10}");
		always.engine.getContext().setWornValue(500_000);
		always.engine.getContext().setMetWearingValue(400_000);
		always.gameTicks(2);
		assertQuiet(always, "you were already well dressed");
	}

	@Test
	public void theChallengeIsMetOrIsNot() throws IOException
	{
		note("challenging", "challengeMet", "challengeFailed");

		Harness open = harnessFor("{\"type\": \"challenging\"}");
		open.gameTicks(2);
		assertQuiet(open, "nothing has been wagered");
		open.engine.getContext().setChallenge("ten kills", "kills", 10, 5);
		open.gameTicks(1);
		assertFired(open, "challenging");

		Harness won = harnessFor("{\"type\": \"challengeMet\"}");
		won.engine.getContext().setChallenge("ten kills", "kills", 3, 5);
		won.gameTicks(2);
		assertQuiet(won, "nothing has been killed");
		for (int i = 0; i < 3; i++)
		{
			won.engine.getContext().tally("kills");
		}
		won.gameTicks(2);
		assertFired(won, "challengeMet");

		// Counted from where the wager STARTED, not from zero: a challenge set
		// after four hundred kills must not settle itself on the spot.
		Harness late = harnessFor("{\"type\": \"challengeMet\"}");
		for (int i = 0; i < 400; i++)
		{
			late.engine.getContext().tally("kills");
		}
		late.engine.getContext().setChallenge("ten kills", "kills", 10, 5);
		late.gameTicks(3);
		assertQuiet(late, "a wager is measured from when it was made");

		Harness lost = harnessFor("{\"type\": \"challengeFailed\"}");
		lost.engine.getContext().setChallenge("ten kills", "kills", 10, 1);
		lost.gameTicks(50);
		assertQuiet(lost, "there is time left");
		lost.gameTicks(60);
		assertFired(lost, "challengeFailed");
	}

	@Test
	public void underfootAndGoneFromTheKeyboard() throws IOException
	{
		note("underfoot", "unattended");

		Harness h = harnessFor("{\"type\": \"underfoot\"}");
		h.gameTicks(2);
		assertQuiet(h, "nobody has clicked the follower's tile");
		h.engine.getContext().noteUnderfoot();
		h.gameTicks(2);
		assertFired(h, "underfoot");

		// A moment, not a state: it does not keep being true afterwards.
		h.clear();
		h.gameTicks(4);
		assertQuiet(h, "the tile was walked to a second later");

		// Gone is not the same as standing still on purpose. The camera is the
		// tell, and the fake game holds it still unless a test moves it.
		Harness away = harnessFor("{\"type\": \"unattended\", \"ticks\": 30}");
		away.gameTicks(10);
		assertQuiet(away, "ten ticks is not gone");
		away.gameTicks(30);
		assertFired(away, "unattended");

		Harness present = harnessFor("{\"type\": \"unattended\", \"ticks\": 30}");
		for (int i = 0; i < 60; i++)
		{
			present.game.cameraMoved();
			present.gameTick();
		}
		assertQuiet(present, "somebody who keeps moving the camera is there");
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
		aWishOpensBySayingAndClosesByGiving();
		tasteIsAboutWhereTheFollowerIsStanding();
		thievingEdges();
		theIncidentItKeepsBringingUp();
		whatItIsCarryingAndWhenItLosesIt();
		theBetIsPlacedAndSettledBothWays();
		theHourAndHowLongWeHaveBeenAtIt();
		aQuestionAlreadyOnTheTable();
		tasteIsEarnedAndOutranksTheRoll();
		adviceIsTakenOrIsNot();
		howLongWeHaveKnownEachOther();
		theNameYouEarnAndTheGearYouOutgrew();
		theChallengeIsMetOrIsNot();
		underfootAndGoneFromTheKeyboard();

		List<String> missing = new ArrayList<>(new TreeSet<>(RuleSetIntegrityTest.KNOWN_TYPES));
		missing.removeAll(exercised);
		assertEquals("condition types with no test proving they ever fire: " + missing,
			0, missing.size());
	}
}
