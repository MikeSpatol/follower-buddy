package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hours of the shipped rules with every system live at its real setting.
 *
 * <p>{@link EngineSoakTest} explores random rule sets for engine invariants.
 * This one does the opposite: the REAL four hundred rules, real chattiness,
 * real director, real bags, driven long enough that the windows actually open
 * and close. The clock is fake, which is the only reason that is possible -
 * a relax period is thirty to forty-five seconds and a test cannot spend them.
 *
 * <p>What it is looking for is the failure no unit test can produce: something
 * that only goes wrong on the four hundredth firing, or when three windows
 * happen to line up.
 */
public class LongSoakTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** One game tick, in milliseconds. */
	private static final long TICK_MS = 600L;

	/** The shipped chattiness, Occasional: what a player who never opens the settings hears. */
	private static final long GAP = new FollowerConfig()
	{
	}.chattiness().getGapMs();

	/** A session long enough for every window in the engine to cycle many times. */
	private static final int TICKS = 20_000;      // 20,000 * 0.6s = about 3h20m

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	/** Where the harness stands the player, and a region to walk off to. */
	private static final WorldPoint HOME = new WorldPoint(3222, 3218, 0);
	private static final WorldPoint AWAY = new WorldPoint(3300, 3300, 0);

	private Harness soakHarness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(clock::get);
		h.engine.setGlobalCooldownMs(GAP);
		return h;
	}

	/** What the driver levels up in, for the level lines to name. */
	private static final String[] SKILLS = {"Attack", "Cooking", "Mining", "Woodcutting"};

	/** Drives ticks and a spread of realistic events, advancing the fake clock. */
	private void play(Harness h, int ticks, long seed)
	{
		Random random = new Random(seed);
		// The skill and level of each level-up, drawn apart so the stream of
		// events is the one every seed has always played.
		Random levels = new Random(seed + 1);
		for (int i = 0; i < ticks; i++)
		{
			clock.addAndGet(TICK_MS);
			h.gameTick();

			int roll = random.nextInt(100);
			if (roll < 4)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			}
			else if (roll < 7)
			{
				h.dispatch(TriggerEvent.death());
			}
			else if (roll < 12)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));
			}
			else if (roll < 17)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_END));
			}
			else if (roll < 20)
			{
				h.dispatch(TriggerEvent.levelUp(SKILLS[levels.nextInt(SKILLS.length)],
					2 + levels.nextInt(98)));
			}
		}
	}

	// --------------------------------------------------------------- the soak

	@Test
	public void hoursOfPlayLeaveEverySystemIntact() throws IOException
	{
		Harness h = soakHarness();
		h.gameTicks(3);
		h.clear();

		play(h, TICKS, 20260810L);

		List<Harness.Spoken> spoken = new ArrayList<>(h.spoken);
		List<String> lines = new ArrayList<>();
		for (Harness.Spoken s : spoken)
		{
			if (!s.text.isEmpty())
			{
				lines.add(s.text);
			}
		}

		assertFalse("three hours and the follower never said anything", lines.isEmpty());

		// 1. Never the same line twice running, whatever combination of gap,
		//    rest, bag refill and window happened to line up.
		for (int i = 1; i < lines.size(); i++)
		{
			assertFalse("said \"" + lines.get(i) + "\" twice running at line " + i,
				lines.get(i).equals(lines.get(i - 1)));
		}

		// 2. Every rule that spoke stayed inside its own bag: no line came round
		//    again before that rule had spent its other variants.
		assertBagsDealEvenly(h, spoken);

		// 3. A one-time line is one time, across three hours and every window.
		assertOnceIsOnce(spoken);

		// 4. Memory stays bounded. The window is a fixed twelve and the spent
		//    set can only hold as many ids as there are one-time rules.
		assertTrue("the spent-line set grew past the rules that can fill it",
			h.engine.getContext().getSpokenOnce().size() <= 8);

		// 5. The level-ups were answered, the plain line and a milestone both.
		assertLevelsSpoke(rulesOf(spoken));

		// 6. The follower is still capable of speech at the end - the failure
		//    where some window latches shut and everything afterwards is silent.
		h.clear();
		play(h, 600, 7L);
		assertFalse("the follower went permanently quiet by the end", h.spoken.isEmpty());
	}

	private static void assertBagsDealEvenly(Harness h, List<Harness.Spoken> spoken)
	{
		Map<String, List<String>> byRule = new HashMap<>();
		for (Harness.Spoken s : spoken)
		{
			if (s.rule != null && !s.text.isEmpty())
			{
				byRule.computeIfAbsent(s.rule.id, k -> new ArrayList<>()).add(s.text);
			}
		}
		for (Map.Entry<String, List<String>> entry : byRule.entrySet())
		{
			SpeechRule rule = h.rule(entry.getKey());
			int variants = rule.say == null ? 1 : rule.say.size();
			if (variants < 2)
			{
				continue;
			}
			List<String> said = entry.getValue();

			// Never the same line twice in a row FOR THIS RULE. The global check
			// cannot see this: two firings of one rule are usually far apart in
			// the overall stream, with other rules in between.
			for (int i = 1; i < said.size(); i++)
			{
				assertFalse(entry.getKey() + " said \"" + said.get(i) + "\" twice running",
					said.get(i).equals(said.get(i - 1)));
			}

			// The real promise of a bag is EVEN USE, not minimum spacing. A line
			// drawn late in one bag may legitimately come early in the next, so
			// two occurrences can be close - but across many bags every variant
			// has to come out the same number of times, give or take the partly
			// spent bag at the end. That is the property that catches a bag
			// which quietly stopped dealing part of its range.
			boolean templated = false;
			for (String variant : rule.say)
			{
				templated |= variant.indexOf('{') >= 0;
			}
			if (templated || said.size() < variants * 3)
			{
				continue;
			}

			Map<String, Integer> counts = new HashMap<>();
			for (String line : said)
			{
				counts.merge(line, 1, Integer::sum);
			}
			assertEquals(entry.getKey() + " only ever used " + counts.size()
				+ " of its " + variants + " lines across " + said.size() + " firings",
				variants, counts.size());

			int most = 0;
			int fewest = Integer.MAX_VALUE;
			for (int n : counts.values())
			{
				most = Math.max(most, n);
				fewest = Math.min(fewest, n);
			}
			assertTrue(entry.getKey() + " used one line " + most + " times and another"
				+ " only " + fewest + " across " + said.size() + " firings, which is"
				+ " not a bag dealing evenly", most - fewest <= 1);
		}
	}

	/**
	 * A level-up line and a level milestone both came out. The driver once
	 * raised every level-up at level nought, which no level rule answers since
	 * levelUp asks for level one at least, and three soaks went on passing
	 * without a level line in them.
	 */
	private static void assertLevelsSpoke(List<SpeechRule> said)
	{
		boolean levelUp = false;
		boolean milestone = false;
		for (SpeechRule rule : said)
		{
			levelUp |= rule != null && rule.when.usesType("levelUp");
			milestone |= rule != null && rule.when.usesType("milestone");
		}
		assertTrue("hours of level-ups and not one level-up line", levelUp);
		assertTrue("hours of level-ups and not one level milestone", milestone);
	}

	private static List<SpeechRule> rulesOf(List<Harness.Spoken> spoken)
	{
		List<SpeechRule> rules = new ArrayList<>();
		for (Harness.Spoken s : spoken)
		{
			rules.add(s.rule);
		}
		return rules;
	}

	private static void assertOnceIsOnce(List<Harness.Spoken> spoken)
	{
		Map<String, Integer> onceCounts = new HashMap<>();
		for (Harness.Spoken s : spoken)
		{
			if (s.rule != null && s.rule.isOnce())
			{
				onceCounts.merge(s.rule.id, 1, Integer::sum);
			}
		}
		for (Map.Entry<String, Integer> entry : onceCounts.entrySet())
		{
			assertEquals(entry.getKey() + " said itself more than once",
				1, entry.getValue().intValue());
		}
	}

	@Test
	public void theNewSystemsSurviveHoursTogether() throws IOException
	{
		// Everything the last two days added, live at once for simulated
		// hours: stays with reunions, wilderness stints, boundaries from
		// every ending, era-gated greetings, wear accruing on every line,
		// the context idle pools. What this is for is the failure that only
		// shows when three of them line up - and the silent one, where a new
		// condition throws once and its rules are disabled forever.
		//
		// Since then the milestones, the companion's reactions and the
		// skilling company have joined it: kill counts, things noticed and
		// sends hesitated over, a parked follower walked into, emotes done
		// at it, pages written, walks away from an old incident and back,
		// work sessions with their drops, and the greeting that remembers
		// last time.
		Harness h = soakHarness();
		TriggerContext ctx = h.engine.getContext();
		ctx.setNicknames(h.loader.getNicknames());
		h.game.spawnNpc(2098, "Hill Giant", 28);
		h.gameTicks(3);
		h.clear();

		Random random = new Random(20260812L);
		boolean staying = false;
		boolean away = false;
		boolean working = false;
		int total = 1450;
		int masteries = 0;
		int killCount = 0;
		for (int i = 0; i < TICKS; i++)
		{
			clock.addAndGet(TICK_MS);
			h.gameTick();

			int roll = random.nextInt(1000);
			if (roll < 30)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));
			}
			else if (roll < 60)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_END));
			}
			else if (roll < 75)
			{
				// With the ledger the milestones read: the total climbing one
				// at a time and the lowest skill with it, a ninety-nine now
				// and then, and a gap that is unknown, quick or over a week.
				int level = 30 + random.nextInt(70);
				masteries += level == 99 ? 1 : 0;
				total++;
				long gap = new long[]{-1L, 5 * 60_000L, 8 * 24 * 3_600_000L}[random.nextInt(3)];
				ctx.tallyBoth("levels");
				h.dispatch(TriggerEvent.levelUp("Cooking", level, total, total - 1,
					total / 30, (total - 1) / 30, masteries, gap, gap));
			}
			else if (roll < 90)
			{
				h.dispatch(TriggerEvent.regionChange(
					12850 + random.nextInt(4), 12850));
			}
			else if (roll < 100)
			{
				h.engine.getContext().noteBoundary("bank");
			}
			else if (roll < 110)
			{
				staying = !staying;
				h.engine.getContext().setFollowerStaying(staying);
			}
			else if (roll < 120 && staying)
			{
				h.engine.getContext().noteBoundary("reunion");
			}
			else if (roll < 140)
			{
				h.game.varbit(5963, random.nextBoolean() ? 1 : 0);
			}
			else if (roll < 160)
			{
				h.game.animating(roll % 2 == 0 ? 879 : -1);
			}
			else if (roll < 170)
			{
				// Where and how often, as the plugin notes a death.
				ctx.noteDeath(h.game.player.getWorldLocation());
				ctx.tallyBoth("deaths");
				h.dispatch(TriggerEvent.death());
			}
			else if (roll < 175)
			{
				killCount++;
				h.dispatch(TriggerEvent.killCount("Vorkath", killCount, killCount - 1));
			}
			else if (roll < 180)
			{
				String kind = new String[]{"valuable", "nest", "impling", "star", "landmark"}
					[random.nextInt(5)];
				h.dispatch(TriggerEvent.noticed(kind, "a " + kind));
			}
			else if (roll < 183)
			{
				h.dispatch(random.nextBoolean()
					? TriggerEvent.hesitated("monster", "Hill Giant")
					: TriggerEvent.hesitated("wilderness", "the Wilderness"));
			}
			else if (roll < 188)
			{
				// Only a parked follower can be walked into.
				if (staying)
				{
					h.dispatch(TriggerEvent.simple(TriggerEvent.Type.STEPPED_ASIDE));
				}
			}
			else if (roll < 193)
			{
				h.dispatch(TriggerEvent.pageWritten(
					new String[]{"boss", "enemy", "location", "faction", "world", "quest"}
						[random.nextInt(6)], "Giant Mole"));
			}
			else if (roll < 195)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.FAREWELL));
			}
			else if (roll < 200)
			{
				// The follower a tile to the south, which the player faces, or
				// a few tiles off where an emote is not done at it.
				WorldPoint here = h.game.player.getWorldLocation();
				ctx.setFollowerLocation(random.nextBoolean()
					? new WorldPoint(here.getX(), here.getY() - 1, here.getPlane())
					: new WorldPoint(here.getX() + 4, here.getY(), here.getPlane()));
			}
			else if (roll < 205)
			{
				h.dispatch(TriggerEvent.animation(855 + random.nextInt(8)));
			}
			else if (roll < 208)
			{
				// A walk away from home and back, where the incidents are filed
				// and the Hill Giant stands.
				WorldPoint from = away ? AWAY : HOME;
				away = !away;
				WorldPoint to = away ? AWAY : HOME;
				h.game.at(to.getX(), to.getY(), to.getPlane());
				h.dispatch(TriggerEvent.regionChange(to.getRegionID(), from.getRegionID()));
			}
			else if (roll < 210)
			{
				working = !working;
				String activity = random.nextBoolean() ? "cooking" : "fletching";
				ctx.setWork(working, activity, "Range", null, 10, 0, Integer.MAX_VALUE);
			}
			else if (roll < 230)
			{
				// What a work session raises, while one is on.
				if (working)
				{
					int work = random.nextInt(10);
					if (work < 3)
					{
						h.dispatch(TriggerEvent.chat("You accidentally burn the shrimps.",
							net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
					}
					else if (work < 6)
					{
						h.dispatch(TriggerEvent.animation(1248));
					}
					else if (work < 8)
					{
						h.dispatch(TriggerEvent.xpDrop("Cooking", 1 + random.nextInt(20)));
					}
					else
					{
						h.dispatch(new TriggerEvent[]{
							TriggerEvent.workEnd("cooking", "Range"),
							TriggerEvent.targetDepleted("Range"),
							TriggerEvent.simple(TriggerEvent.Type.BAG_FULL),
							TriggerEvent.simple(TriggerEvent.Type.BAG_HALF)}[random.nextInt(4)]);
					}
				}
			}
			else if (roll == 230)
			{
				// A login after a long absence, remembering what the two of
				// them were doing.
				ctx.restoreLastThing("the mining");
				ctx.setMinutesAway(600);
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
			}
			else if (roll == 231)
			{
				// Filed as the death memory files it, with the foe it was with.
				ctx.noteIncident("the-death", "the way that went with the Hill Giant",
					"Hill Giant", clock.get());
			}
			else if (roll == 232)
			{
				// The player saying yes to the name on offer.
				ctx.acceptNickname(ctx.getEarnedNickname());
			}
			else if (roll == 233 && random.nextInt(10) == 0)
			{
				// A favour done: rare, since the debt lasts half an hour.
				ctx.noteFavour();
			}
		}

		assertFalse("hours of everything at once and nothing was said",
			h.spoken.isEmpty());

		// The silent failure: a condition that throws disables its rule for
		// good, and the only symptom is a line nobody ever hears again.
		for (SpeechRule rule : h.loader.getRules())
		{
			assertTrue("rule '" + rule.id + "' was disabled by a thrown condition",
				rule.isEnabled());
		}

		assertTrue("the wear ledger stayed bounded",
			h.engine.getContext().getLineWear().size() <= 500);

		// The bags and the one-time lines, held to the same promises as the
		// plain soak's, over everything this one drives.
		List<Harness.Spoken> spoken = new ArrayList<>(h.spoken);
		assertBagsDealEvenly(h, spoken);
		assertOnceIsOnce(spoken);

		// Never the same line twice running, whatever lined up.
		String previous = null;
		for (Harness.Spoken s : h.spoken)
		{
			if (!s.text.isEmpty())
			{
				assertFalse("said \"" + s.text + "\" twice running",
					s.text.equals(previous));
				previous = s.text;
			}
		}

		// And still capable of speech at the end.
		h.engine.getContext().setFollowerStaying(false);
		h.clear();
		play(h, 600, 7L);
		assertFalse("the follower went permanently quiet by the end",
			h.spoken.isEmpty());
	}

	@Test
	public void theDirectorRestsWithoutEverStopping() throws IOException
	{
		// The shape the whole model is for: bursts with real quiet between them,
		// rather than a flat stream or a follower that talks itself into silence.
		Harness h = soakHarness();
		h.gameTicks(3);
		h.clear();

		List<Long> spokenAt = new ArrayList<>();
		List<SpeechRule> said = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) ->
		{
			onSaid.run();
			if (!text.isEmpty())
			{
				spokenAt.add(clock.get());
				said.add(rule);
			}
		});

		play(h, TICKS, 99L);

		assertTrue("far too little came out of three hours: " + spokenAt.size(),
			spokenAt.size() > 20);

		long span = clock.get() - 1_700_000_000_000L;
		double perHour = spokenAt.size() * 3_600_000.0 / span;
		assertTrue("the follower talked at " + Math.round(perHour) + " lines an hour,"
			+ " which is not a companion, it is a radio", perHour < 400);

		// There must be real silences in there. A model that never actually
		// rests would show a tight cluster of gaps around the speech floor.
		int longQuiets = 0;
		for (int i = 1; i < spokenAt.size(); i++)
		{
			if (spokenAt.get(i) - spokenAt.get(i - 1) >= 25_000L)
			{
				longQuiets++;
			}
		}
		assertTrue("three hours produced " + spokenAt.size() + " lines at "
			+ Math.round(perHour) + " an hour with only " + longQuiets + " silences"
			+ " past 25 seconds; the rest period is not taking hold",
			longQuiets > 5);

		// And the level-ups were answered in among it.
		assertLevelsSpoke(said);
	}

	@Test
	public void anOccasionIsNeverTheThingThatGetsHeldBack() throws IOException
	{
		// The promise the whole occasion flag exists to keep, checked against
		// the shipped rules over hours rather than against a hand-made pair.
		Harness h = soakHarness();
		h.gameTicks(3);

		Set<String> heldOccasions = new HashSet<>();
		h.engine.setOnSuppressed((rule, why) ->
		{
			if (rule.isOccasion() && ("relax".equals(why) || "settling".equals(why)))
			{
				heldOccasions.add(rule.id + " by " + why);
			}
		});

		h.engine.getContext().setSessionCount(1);       // settling damper live too
		play(h, TICKS, 4242L);

		assertTrue("the director held back lines it must never hold: " + heldOccasions,
			heldOccasions.isEmpty());
		assertLevelsSpoke(rulesOf(h.spoken));
	}

	// ------------------------------------------------- scenes, boxes, chairs

	/** Who the driver brings into view: quest people, whose lines wait, and a passer-by. */
	private static final String[] PEOPLE = {"Hans", "Alina", "Sergeant Tobyn", "Prince Itzla Arkan",
		"Duke Horacio", "Cook", "Man"};

	/** Ticks a session of the scene soak plays: two hours. */
	private static final int SCENE_SOAK_TICKS = 12_000;

	@Test
	public void scenesConversationsAndAbsencesKeepTheirPromises() throws Exception
	{
		// QA, 15 Oct: the soaks above never drove what came in with the
		// cutscenes and the quest people - the quiet after a scene, lines
		// that wait their turn through it, through a conversation and an
		// empty chair, and two quest people met on one tick. Six seeds of two
		// hours each, the scenes, boxes and absences at random among the rest.
		java.lang.reflect.Field pendingField =
			com.follower.speech.SpeechEngine.class.getDeclaredField("pending");
		pendingField.setAccessible(true);
		List<String> problems = new ArrayList<>();
		int waitsHeard = 0;
		int occasionsHeard = 0;

		for (long seed = 1; seed <= 6; seed++)
		{
			final long run = seed;
			Harness h = soakHarness();
			TriggerContext ctx = h.engine.getContext();
			boolean[] quiet = {false};
			List<SpeechRule> said = new ArrayList<>();
			h.engine.setSink((text, output, rule, animationId, onSaid) ->
			{
				onSaid.run();
				if (text.isEmpty() || rule == null)
				{
					return;
				}
				said.add(rule);
				if (quiet[0])
				{
					problems.add(run + ": " + rule.id + " said in a scene or the quiet after it");
				}
				if (rule.waitsItsTurn() && ctx.isDialogueOpen())
				{
					problems.add(run + ": " + rule.id + " said into a conversation");
				}
				if (Boolean.TRUE.equals(rule.waits) && ctx.getUnattendedTicks() >= 300
					&& !rule.when.usesType("unattended"))
				{
					problems.add(run + ": " + rule.id + " said to the empty chair");
				}
			});
			h.gameTicks(3);

			Random r = new Random(run);
			int sceneLeft = 0;
			int quietLeft = 0;
			int boxLeft = 0;
			int boxTail = 0;
			int awayLeft = 0;
			int quest = 0;
			int maxPending = 0;
			for (int i = 0; i < SCENE_SOAK_TICKS; i++)
			{
				clock.addAndGet(TICK_MS);

				// A cutscene, then the quiet the plugin keeps after one.
				if (sceneLeft == 0 && quietLeft == 0 && r.nextInt(400) == 0)
				{
					sceneLeft = 20 + r.nextInt(80);
				}
				if (sceneLeft > 0 && --sceneLeft == 0)
				{
					quietLeft = 25;
				}
				else if (quietLeft > 0)
				{
					quietLeft--;
				}
				quiet[0] = sceneLeft > 0 || quietLeft > 0;
				h.engine.setSceneQuiet(quiet[0]);
				h.engine.setInCutscene(sceneLeft > 0);

				// A conversation, read as the plugin reads it: up, and for two
				// ticks after the box goes. A scene may run straight into one.
				if (boxLeft == 0 && boxTail == 0 && r.nextInt(80) == 0)
				{
					boxLeft = 5 + r.nextInt(35);
				}
				if (boxLeft > 0 && --boxLeft == 0)
				{
					boxTail = 2;
				}
				else if (boxTail > 0)
				{
					boxTail--;
				}
				ctx.setDialogueOpen(boxLeft > 0 || boxTail > 0);

				// Away from the keys now and then, long enough for the damper.
				if (awayLeft == 0 && r.nextInt(3000) == 0)
				{
					awayLeft = 350 + r.nextInt(400);
				}
				boolean away = awayLeft > 0;
				if (away)
				{
					awayLeft--;
				}
				else if (r.nextInt(5) == 0)
				{
					h.game.cameraMoved();
				}

				// A quest moving on, as Children of the Sun and Priest in Peril do.
				if (r.nextInt(150) == 0)
				{
					quest = Math.min(22, quest + 2);
					h.game.varbit(9632, quest);
					h.game.varp(302, Math.min(60, quest / 2));
				}

				h.gameTick();

				// Somebody comes into view, now and then two at once.
				if (r.nextInt(40) == 0)
				{
					h.game.clearNpcs();
					for (int n = r.nextInt(4) == 0 ? 2 : 1; n > 0; n--)
					{
						String who = PEOPLE[r.nextInt(PEOPLE.length)];
						h.game.spawnNpc(4000 + who.length(), who, 0);
						h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 4000 + who.length(), who));
					}
				}

				int roll = r.nextInt(100);
				if (away)
				{
					// Nothing of the player's own while they are away.
				}
				else if (roll < 3)
				{
					h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));
				}
				else if (roll < 6)
				{
					h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_END));
				}
				else if (roll < 8)
				{
					h.dispatch(TriggerEvent.levelUp(SKILLS[r.nextInt(SKILLS.length)], 2 + r.nextInt(97)));
				}
				else if (roll < 9)
				{
					h.dispatch(TriggerEvent.questComplete("Cook's Assistant"));
				}
				maxPending = Math.max(maxPending, ((List<?>) pendingField.get(h.engine)).size());
			}

			Map<String, Integer> once = new HashMap<>();
			for (SpeechRule rule : said)
			{
				waitsHeard += Boolean.TRUE.equals(rule.waits) ? 1 : 0;
				occasionsHeard += rule.isOccasion() ? 1 : 0;
				if (rule.isOnce())
				{
					once.merge(rule.onceKey(), 1, Integer::sum);
				}
			}
			for (Map.Entry<String, Integer> e : once.entrySet())
			{
				if (e.getValue() > 1)
				{
					problems.add(run + ": the first " + e.getKey() + " said " + e.getValue() + " times");
				}
			}
			if (maxPending > 8)
			{
				problems.add(run + ": " + maxPending + " lines waiting at once");
			}

			// And still capable of speech once it is all over.
			h.engine.setSceneQuiet(false);
			quiet[0] = false;
			ctx.setDialogueOpen(false);
			int before = said.size();
			play(h, 600, 7L);
			if (said.size() == before)
			{
				problems.add(run + ": the follower went permanently quiet");
			}
		}

		assertTrue(String.join("\n", problems), problems.isEmpty());
		assertTrue("no line that waits was ever heard: the path was not driven", waitsHeard > 0);
		assertTrue("no occasion was ever heard: the path was not driven", occasionsHeard > 0);
	}
}
