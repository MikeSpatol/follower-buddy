package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.Condition;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Randomly generated rule sets, driven by randomly generated events.
 *
 * <p>The hand-written tests check the cases somebody thought of. This one
 * explores the combinations nobody did - a rule that is delayed AND on cooldown
 * AND in a disabled group, fired by an event that arrives while another firing
 * is still pending - and asserts the promises that must hold whatever the
 * combination.
 *
 * <p>Seeded, so a failure is reproducible rather than a story about something
 * that happened once.
 */
public class EngineSoakTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final Gson GSON = new Gson();

	private static final String[] CONDITIONS = {
		"{\"type\": \"always\"}",
		"{\"type\": \"chance\", \"percent\": 50}",
		"{\"type\": \"healthBelow\", \"percent\": 50}",
		"{\"type\": \"healthAbove\", \"percent\": 20}",
		"{\"type\": \"idle\", \"ticks\": 3}",
		"{\"type\": \"energyBelow\", \"percent\": 50}",
		"{\"type\": \"combat\"}",
		"{\"type\": \"login\"}",
		"{\"type\": \"playerDeath\"}",
		"{\"type\": \"npcSpawn\", \"names\": [\"Goblin\"]}",
		"{\"type\": \"npcNearby\", \"names\": [\"Goblin\"], \"within\": 5}",
		"{\"type\": \"animationSelf\", \"ids\": [862]}",
		"{\"type\": \"lootWorth\", \"minimum\": 100}",
		"{\"type\": \"npcKill\", \"minimum\": 1}",
		"{\"type\": \"combatStart\"}",
		"{\"type\": \"damageTaken\", \"minimum\": 1}",
		"{\"type\": \"inRegion\", \"regions\": [12850]}",
		"{\"type\": \"itemEquipped\", \"ids\": [4151]}",
		// The 2026-08-12 batch: state conditions the driver flips at random,
		// so combinators wrapping them open and close mid-flight.
		"{\"type\": \"boundary\"}",
		"{\"type\": \"boundary\", \"is\": \"bank\"}",
		"{\"type\": \"staying\"}",
		"{\"type\": \"inWilderness\"}",
		"{\"type\": \"rolledFeeling\", \"is\": \"disliked\"}", // public:cut likes
		"{\"type\": \"daysKnown\", \"minimum\": 0, \"maximum\": 3}",
		"{\"type\": \"daysSinceSaid\", \"rule\": \"probe\", \"maximum\": 3}",
		"{\"type\": \"none\", \"conditions\": [{\"type\": \"repeating\"}]}",
		// The rest of the vocabulary, older than the pool's last look. The
		// scene, the body and the bag first: state the driver moves.
		"{\"type\": \"npcDespawn\", \"names\": [\"Goblin\"]}",
		"{\"type\": \"petNearby\", \"within\": 5}",
		"{\"type\": \"prayerBelow\", \"percent\": 30}",
		"{\"type\": \"prayerAbove\", \"percent\": 30}",
		"{\"type\": \"inArea\", \"x1\": 3200, \"y1\": 3200, \"x2\": 3250, \"y2\": 3250, \"plane\": 0}",
		"{\"type\": \"regionEnter\", \"regions\": [12850]}",
		"{\"type\": \"returnVisit\", \"minimum\": 1}",
		"{\"type\": \"varbitEquals\", \"varbit\": 5963, \"value\": 1}",
		"{\"type\": \"varbitChanged\", \"varbit\": 5963, \"value\": 1}",
		"{\"type\": \"questVar\", \"varp\": 179, \"minimum\": 1, \"maximum\": 20}",
		"{\"type\": \"dialogueOpen\"}",
		"{\"type\": \"questDone\", \"quest\": \"SHEEP_SHEARER\"}",
		"{\"type\": \"questWitnessed\", \"quest\": \"SHEEP_SHEARER\"}",
		"{\"type\": \"inventoryHas\", \"ids\": [2944]}",
		"{\"type\": \"poisoned\"}",
		"{\"type\": \"venomed\"}",
		"{\"type\": \"skulled\"}",
		"{\"type\": \"idleBelow\", \"ticks\": 20}",
		"{\"type\": \"nearDeathSpot\", \"within\": 5}",
		"{\"type\": \"bossFight\"}",
		"{\"type\": \"thieving\"}",
		"{\"type\": \"inventoryFree\", \"maximum\": 2}",
		"{\"type\": \"playersNearby\", \"minimum\": 2, \"within\": 5}",
		"{\"type\": \"hovered\", \"ticks\": 4}",
		"{\"type\": \"unattended\", \"ticks\": 30}",
		"{\"type\": \"mood\", \"minimum\": 60}",
		// Events that arrive and are gone.
		"{\"type\": \"chatMessage\", \"contains\": \"hello\", \"every\": 2}",
		"{\"type\": \"levelUp\", \"names\": [\"Cooking\"]}",
		"{\"type\": \"combatEnd\"}",
		"{\"type\": \"thrallStart\"}",
		"{\"type\": \"thrallSwitch\"}",
		"{\"type\": \"thrallEnd\"}",
		// public:cut errands
		"{\"type\": \"errandStart\", \"names\": [\"bank\"]}",
		"{\"type\": \"errandEnd\"}",
		// public:end errands
		"{\"type\": \"thievingStart\"}",
		"{\"type\": \"thievingEnd\"}",
		"{\"type\": \"personalBest\"}",
		"{\"type\": \"sessionCount\", \"every\": 2}",
		"{\"type\": \"awayFor\", \"minimum\": 60}",
		"{\"type\": \"answered\", \"is\": \"yes\"}",
		"{\"type\": \"examined\"}",
		"{\"type\": \"worldHop\"}",
		"{\"type\": \"gameUpdated\"}",
		"{\"type\": \"notesEdited\"}",
		"{\"type\": \"musicUnlock\"}",
		"{\"type\": \"musicTrack\", \"names\": [\"Sea Shanty*\"]}",
		"{\"type\": \"collectionAdd\"}",
		"{\"type\": \"questComplete\"}",
		"{\"type\": \"geOffer\", \"is\": \"sold\"}",
		"{\"type\": \"channelJoined\"}",
		"{\"type\": \"channelLeft\"}",
		"{\"type\": \"npcShout\", \"names\": [\"Town crier\"]}",
		"{\"type\": \"examinedThing\"}",
		"{\"type\": \"attendedAgain\"}",
		"{\"type\": \"minutesTaken\"}",
		// The follower's own ledger, opened by the driver and closed by the
		// engine: the want lapses, the souvenir drops, the bet settles.
		"{\"type\": \"tally\", \"of\": \"kills\", \"minimum\": 26}",
		"{\"type\": \"wanting\"}",
		"{\"type\": \"wantFulfilled\"}",
		"{\"type\": \"wantExpired\"}",
		"{\"type\": \"wishing\"}",
		"{\"type\": \"wishItemHeld\"}",
		"{\"type\": \"feelsAbout\", \"is\": \"liked\"}", // public:cut likes
		"{\"type\": \"remembers\"}",
		"{\"type\": \"carrying\"}",
		"{\"type\": \"souvenirLost\"}",
		"{\"type\": \"betting\"}",
		"{\"type\": \"betWon\"}",
		"{\"type\": \"betLost\"}",
		"{\"type\": \"asking\"}",
		"{\"type\": \"placeScore\", \"maximum\": -50}", // public:cut likes
		"{\"type\": \"happenedHere\"}",
		"{\"type\": \"advising\"}",
		"{\"type\": \"heeded\"}",
		"{\"type\": \"ignored\"}",
		"{\"type\": \"challenging\"}",
		"{\"type\": \"challengeMet\"}",
		"{\"type\": \"challengeFailed\"}",
		"{\"type\": \"underfoot\"}",
		"{\"type\": \"anniversary\"}",
		"{\"type\": \"outgrew\", \"minimum\": 10}",
		"{\"type\": \"nicknamed\"}", // public:cut nicknames
		"{\"type\": \"sessionMinutes\", \"minimum\": 60}",
		// The player's own wall clock and calendar. Nothing a driver can
		// flip, so these are constants for the length of a run.
		"{\"type\": \"timeOfDay\", \"minimum\": 0, \"maximum\": 23}",
		"{\"type\": \"dateIs\", \"month\": 1, \"day\": 1, \"untilMonth\": 12, \"untilDay\": 31}",
		// Everything since 2026-08-12: the milestones, the companion's
		// reactions and the skilling company.
		"{\"type\": \"milestone\", \"is\": \"round\"}",
		"{\"type\": \"milestone\", \"is\": \"total\", \"every\": 100}",
		"{\"type\": \"milestone\", \"is\": \"mastery\"}",
		"{\"type\": \"levelGap\", \"minimum\": 10080}",
		"{\"type\": \"levelGap\", \"maximum\": 10}",
		"{\"type\": \"killCount\", \"is\": \"round\"}",
		"{\"type\": \"grateful\"}",
		"{\"type\": \"lastTime\"}",
		"{\"type\": \"farewell\"}",
		// public:cut company-extras
		"{\"type\": \"noticed\", \"is\": \"impling\"}", // public:cut noticing
		// public:cut will
		"{\"type\": \"hesitated\", \"is\": \"monster\"}",
		"{\"type\": \"steppedAside\"}",
		// public:end will
		// public:end company-extras
		"{\"type\": \"crossed\", \"is\": \"fell\"}",
		"{\"type\": \"grace\", \"is\": \"left\"}",
		"{\"type\": \"addressed\"}",
		"{\"type\": \"nicknameEarned\"}", // public:cut nicknames
		"{\"type\": \"memoryHere\"}",
		"{\"type\": \"memoryFoeNearby\", \"within\": 12}",
		"{\"type\": \"pageWritten\", \"is\": \"boss\"}", // public:cut chronicle
		"{\"type\": \"working\", \"is\": \"woodcutting\"}", // public:cut skilling
		"{\"type\": \"xpDrop\", \"every\": 2}",
		// public:cut skilling
		"{\"type\": \"workEnded\"}",
		"{\"type\": \"targetDepleted\"}",
		"{\"type\": \"bagFull\"}",
		"{\"type\": \"bagHalf\"}",
		// public:end skilling
	};

	/** Types that are the same for the whole of a run, whatever the driver does. */
	private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
		"always", "timeofday", "dateis"));

	private static String condition(Random random, int depth)
	{
		if (depth > 0 && random.nextInt(4) == 0)
		{
			String combinator = new String[]{"all", "any", "none"}[random.nextInt(3)];
			int count = 1 + random.nextInt(3);
			StringBuilder children = new StringBuilder();
			for (int i = 0; i < count; i++)
			{
				if (i > 0)
				{
					children.append(',');
				}
				children.append(condition(random, depth - 1));
			}
			return "{\"type\": \"" + combinator + "\", \"conditions\": [" + children + "]}";
		}
		return CONDITIONS[random.nextInt(CONDITIONS.length)];
	}

	private static String rules(Random random, int count)
	{
		StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
		for (int i = 0; i < count; i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append("{\"id\": \"r").append(i).append('"')
				.append(", \"group\": \"g").append(random.nextInt(4)).append('"')
				.append(", \"priority\": ").append(random.nextInt(100))
				.append(", \"cooldownMs\": ").append(random.nextInt(3) * 5000);
			if (random.nextInt(3) == 0)
			{
				int min = random.nextInt(4);
				out.append(", \"delayTicks\": ").append(min);
				if (random.nextBoolean())
				{
					out.append(", \"delayTicksMax\": ").append(min + random.nextInt(4));
				}
			}
			if (random.nextInt(5) == 0)
			{
				out.append(", \"animation\": 862");
			}
			int waiting = random.nextInt(6);
			if (waiting == 0)
			{
				out.append(", \"occasion\": true");
			}
			else if (waiting == 1)
			{
				out.append(", \"waits\": true");
			}
			out.append(", \"when\": ").append(condition(random, 2))
				.append(", \"say\": [\"line ").append(i).append("\"]}");
		}
		// And every pool entry as a rule of its own, in a group that is always
		// switched off: evaluated against every event like the rest, so a
		// condition that throws is caught whether or not the dice put it in
		// a rule this seed, and never able to speak over the rules under test.
		for (int i = 0; i < CONDITIONS.length; i++)
		{
			out.append(",{\"id\": \"probe").append(i).append("\", \"group\": \"probe\"")
				.append(", \"cooldownMs\": 0, \"when\": ").append(CONDITIONS[i])
				.append(", \"say\": [\"probe ").append(i).append("\"]}");
		}
		return out.append("]}").toString();
	}

	/**
	 * A pool entry, noting whether it ever came true after being false: the
	 * difference between an entry the driver reaches and one that only sits
	 * in the pool, never exercising the half of its code that could throw.
	 */
	private static final class Probe extends Condition
	{
		private final Condition entry;
		private Boolean last;
		private boolean rose;

		Probe(Condition entry)
		{
			this.entry = entry;
		}

		@Override
		public boolean matches(TriggerContext ctx, TriggerEvent event)
		{
			boolean now = entry.matches(ctx, event);
			rose |= now && Boolean.FALSE.equals(last);
			last = now;
			return now;
		}
	}

	// --------------------------------------------------------------- the world

	/** The two places the driver walks between: Lumbridge, and north-east of it. */
	private static final WorldPoint HOME = new WorldPoint(3222, 3218, 0);
	private static final WorldPoint AWAY = new WorldPoint(3300, 3300, 0);

	private NPC goblin;
	private NPC boss;
	private boolean away;
	private int killCount;

	/**
	 * What the driver plays with: a goblin to fight and a boss to fear, both
	 * left behind at home with a pet; a temperament that likes home and not
	 * the other place; the nicknames, with both tallies a little short of
	 * earning one; and a first-meeting outfit to have outgrown.
	 */
	private void stage(Harness h)
	{
		goblin = h.game.spawnNpc(3029, "Goblin", 5);
		boss = h.game.spawnNpc(2042, "Zulrah", 725);
		h.game.spawnNpc(5591, "Trotters", 0, true);
		away = false;
		killCount = 0;

		TriggerContext ctx = h.engine.getContext();
		// public:cut likes
		ctx.setTraits(new HashSet<>(Collections.singletonList(HOME.getRegionID())),
			new HashSet<>(Collections.singletonList(AWAY.getRegionID())));
		// public:end likes
		// public:cut nicknames
		Map<String, String> nicknames = new HashMap<>();
		nicknames.put("deaths", "the gravedigger");
		nicknames.put("kills", "the local menace");
		ctx.setNicknames(nicknames);
		// public:end nicknames
		for (int i = 0; i < 24; i++)
		{
			ctx.tallyBoth("deaths");
			ctx.tallyBoth("kills");
		}
		ctx.setMetWearingValue(1_000);
	}

	private void randomEvent(Harness h, Random random)
	{
		TriggerContext ctx = h.engine.getContext();
		switch (random.nextInt(76))
		{
			case 0:
				h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
				break;
			case 1:
				h.dispatch(TriggerEvent.animation(862));
				break;
			case 2:
			{
				// The plugin settles an open bet on the loot it was about.
				int worth = random.nextInt(200000);
				ctx.settleBet(worth); // public:cut side-games
				h.dispatch(TriggerEvent.loot(worth, "Bones"));
				break;
			}
			case 3:
				ctx.tallyBoth("kills");
				h.dispatch(TriggerEvent.kill(1, "Goblin", random.nextInt(800)));
				break;
			case 4:
				// Where and how often, as the plugin notes a death.
				ctx.noteDeath(h.game.player.getWorldLocation());
				ctx.tallyBoth("deaths");
				h.dispatch(TriggerEvent.death());
				break;
			case 5:
				h.dispatch(TriggerEvent.damageTaken(1 + random.nextInt(40)));
				break;
			case 6:
				h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Goblin")); // public:cut spectate
				// public:else h.dispatch(TriggerEvent.simple(TriggerEvent.Type.THIEVING_START));
				break;
			case 7:
				// With what a login brings: the count, the time away, and now
				// and then what the two of them were doing last time.
				ctx.setSessionCount(1 + random.nextInt(4));
				ctx.setMinutesAway(new long[]{-1L, 30L, 600L}[random.nextInt(3)]);
				if (random.nextBoolean())
				{
					ctx.restoreLastThing("the mining");
				}
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
				break;
			case 8:
				h.game.hitpoints(1 + random.nextInt(99), 99);
				break;
			case 9:
				h.game.energy(random.nextInt(10001));
				break;
			case 10:
				h.engine.getContext().noteBoundary(
					new String[]{"combat", "bank", "reunion"}[random.nextInt(3)]);
				break;
			case 11:
				h.engine.getContext().setFollowerStaying(random.nextBoolean());
				h.engine.getContext().setDialogueOpen(random.nextBoolean());
				h.engine.setSceneQuiet(random.nextInt(4) == 0);
				break;
			case 12:
			{
				// The ditch, as the varbit and as the event the change raises.
				int wilderness = random.nextInt(2);
				h.game.varbit(5963, wilderness);
				h.dispatch(TriggerEvent.varbit(5963, wilderness, 1 - wilderness));
				// A quest's progress, before, during and after.
				h.game.varp(179, random.nextInt(22));
				h.game.quest(net.runelite.api.Quest.SHEEP_SHEARER, random.nextBoolean()
					? net.runelite.api.QuestState.FINISHED : net.runelite.api.QuestState.IN_PROGRESS);
				// And an item in the bag, or not.
				if (random.nextBoolean())
				{
					h.game.inventoryContaining(2944);
				}
				else
				{
					h.game.inventoryContaining(1511);
				}
				break;
			}
			case 13:
				h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_DESPAWN, 3029, "Goblin"));
				break;
			case 14:
			{
				// A walk between the two places, raised as the plugin raises it.
				// Everything tied to a place - the area, the incident, the death
				// spot, the taste, the NPCs left at home - opens and closes.
				WorldPoint from = away ? AWAY : HOME;
				away = !away;
				WorldPoint to = away ? AWAY : HOME;
				h.game.at(to.getX(), to.getY(), to.getPlane());
				h.dispatch(TriggerEvent.regionChange(to.getRegionID(), from.getRegionID()));
				break;
			}
			case 15:
				h.playerSays("hello there");
				break;
			case 16:
			{
				// A level with the ledger the milestones read: sometimes round,
				// the total sometimes crossing a hundred, now and then a
				// ninety-nine, and a gap that is unknown, quick or over a week.
				int level = new int[]{50, 70, 91, 92, 99}[random.nextInt(5)];
				int total = 1495 + random.nextInt(10);
				long gap = new long[]{-1L, 5 * 60_000L, 8 * 24 * 3_600_000L}[random.nextInt(3)];
				h.dispatch(TriggerEvent.levelUp("Cooking", level, total, total - 1,
					50, 49 + random.nextInt(2), level == 99 ? 1 + random.nextInt(2) : 0, gap, gap));
				break;
			}
			case 17:
				h.game.prayer(random.nextInt(100), 99);
				break;
			case 18:
				h.game.varp(net.runelite.api.gameval.VarPlayerID.POISON,
					new int[]{0, 12, 1_000_050}[random.nextInt(3)]);
				break;
			case 19:
				h.game.skulled(random.nextBoolean());
				break;
			case 20:
				h.game.fighting(new NPC[]{null, goblin, boss}[random.nextInt(3)]);
				break;
			case 21:
				h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_END, "Goblin")); // public:cut spectate
				// public:else h.dispatch(TriggerEvent.simple(TriggerEvent.Type.THIEVING_END));
				break;
			case 22:
				h.dispatch(new TriggerEvent[]{
					TriggerEvent.thrall(TriggerEvent.Type.THRALL_START, "melee"),
					TriggerEvent.thrallSwitch("melee", "magic"),
					TriggerEvent.thrall(TriggerEvent.Type.THRALL_END, "magic")}[random.nextInt(3)]);
				break;
			// public:cut errands
			case 23:
				h.dispatch(TriggerEvent.errand(random.nextBoolean()
					? TriggerEvent.Type.ERRAND_START : TriggerEvent.Type.ERRAND_END, "bank"));
				break;
			// public:end errands
			case 24:
				h.dispatch(TriggerEvent.simple(random.nextBoolean()
					? TriggerEvent.Type.THIEVING_START : TriggerEvent.Type.THIEVING_END));
				break;
			case 25:
				// A pickpocket; the default case below puts it down again.
				h.game.animating(881);
				break;
			case 26:
				ctx.adjustMood(random.nextBoolean() ? 15 : -15);
				break;
			case 27:
				h.dispatch(TriggerEvent.record("hit", 42, 30));
				break;
			case 28:
				ctx.noteQuestion("want-outing");
				break;
			case 29:
				h.answers(random.nextBoolean() ? "yes" : "no", "want-outing");
				break;
			case 30:
				ctx.setHoverTicks(random.nextInt(9));
				break;
			case 31:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
				break;
			case 32:
				if (random.nextBoolean())
				{
					h.game.inventoryUsing(20 + random.nextInt(9));
				}
				else
				{
					h.game.inventoryContaining(314);
				}
				break;
			case 33:
				// A pair of passers-by, or nobody.
				if (random.nextBoolean())
				{
					h.game.spawnPlayer(1, 0);
					h.game.spawnPlayer(0, 2);
				}
				else
				{
					h.game.clearPlayers();
				}
				break;
			case 34:
				// Wanting the other place: walking there keeps it, a minute
				// without lets it lapse, and the engine announces either.
				ctx.setWant((away ? HOME : AWAY).getRegionID(), "the walk", 1);
				break;
			case 35:
				ctx.setWish("a feather", 1, Collections.singletonList(314)); // public:cut souvenirs-wishes
				break;
			case 36:
				ctx.noteIncident("the-death", "the way that went", "Goblin",
					System.currentTimeMillis());
				break;
			case 37:
				ctx.pickUp("a nice flat rock", 1); // public:cut souvenirs-wishes
				break;
			case 38:
				ctx.placeBet(random.nextBoolean(), 50_000, 1); // public:cut side-games
				break;
			case 39:
				ctx.adviseOn("food", new HashSet<>(Collections.singletonList(829)), false, 1); // public:cut side-games
				break;
			case 40:
				// Eating, which is what the food advice was asking for.
				h.dispatch(TriggerEvent.animation(829));
				break;
			case 41:
				ctx.setChallenge("a kill", "kills", 1, 1); // public:cut side-games
				break;
			case 42:
				ctx.noteUnderfoot();
				break;
			case 43:
				h.game.cameraMoved();
				break;
			case 44:
				ctx.notePlaceFeeling(random.nextBoolean() ? 25 : -25); // public:cut likes
				// public:else random.nextBoolean();
				if (random.nextBoolean())
				{
					// From an earlier session: one filed now is too fresh to come up.
					ctx.restorePlaces(new java.util.HashMap<>(ctx.getPlaceScores()), // public:cut likes
					// public:else ctx.restorePlaces(
						Collections.singletonMap(ctx.getRegionId(), "the Armadyl chestplate dropped"));
				}
				break;
			case 45:
			{
				long today = LocalDate.now().toEpochDay();
				ctx.setMetOnDay(new long[]{0L, today - 1, today - 400,
					LocalDate.now().minusYears(2).toEpochDay()}[random.nextInt(4)]);
				// And a one-time line said, for daysSinceSaid to count from.
				ctx.noteSaidOnce("probe");
				break;
			}
			case 46:
				ctx.setWornValue(random.nextBoolean() ? 500_000 : 2_000);
				break;
			case 47:
				// The player saying yes to the name on offer.
				ctx.acceptNickname(ctx.getEarnedNickname()); // public:cut nicknames
				break;
			case 48:
				ctx.noteFavour();
				break;
			case 49:
			{
				// The follower a tile to the south, which the player faces, or
				// a few tiles off where nothing done is done at it.
				WorldPoint here = h.game.player.getWorldLocation();
				ctx.setFollowerLocation(random.nextBoolean()
					? new WorldPoint(here.getX(), here.getY() - 1, here.getPlane())
					: new WorldPoint(here.getX() + 4, here.getY(), here.getPlane()));
				break;
			}
			case 50:
				// public:cut skilling
				ctx.setWork(random.nextBoolean(), "woodcutting", "Yew tree", null, 10, 0,
					Integer.MAX_VALUE);
				// public:end skilling
				break;
			case 51:
				h.dispatch(TriggerEvent.xpDrop("Woodcutting", 1 + random.nextInt(8)));
				break;
			case 52:
				h.dispatch(TriggerEvent.workEnd("woodcutting", "Yew tree")); // public:cut skilling
				break;
			case 53:
				h.dispatch(TriggerEvent.targetDepleted("Yew tree")); // public:cut skilling
				break;
			case 54:
				// public:cut skilling
				h.dispatch(TriggerEvent.simple(random.nextBoolean()
					? TriggerEvent.Type.BAG_FULL : TriggerEvent.Type.BAG_HALF));
				// public:end skilling
				break;
			case 55:
				killCount++;
				h.dispatch(TriggerEvent.killCount("Vorkath", killCount, killCount - 1));
				break;
			case 56:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.FAREWELL));
				break;
			// public:cut company-extras
			// public:cut noticing
			case 57:
				h.dispatch(random.nextBoolean()
					? TriggerEvent.noticed("impling", "Dragon impling")
					: TriggerEvent.noticed("nest", "Bird nest"));
				break;
			// public:end noticing
			// public:cut will
			case 58:
				h.dispatch(random.nextBoolean()
					? TriggerEvent.hesitated("monster", "Hill Giant")
					: TriggerEvent.hesitated("wilderness", "the Wilderness"));
				break;
			// public:end will
			// public:end company-extras
			case 59:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.STEPPED_ASIDE)); // public:cut company-extras,will
				// public:cut agility-all
				h.dispatch(TriggerEvent.crossed(true)); // public:cut agility
				h.dispatch(TriggerEvent.grace("left", null)); // public:cut marks
				// public:end agility-all
				break;
			// public:cut chronicle
			case 60:
				h.dispatch(TriggerEvent.pageWritten(
					new String[]{"boss", "enemy", "location", "quest"}[random.nextInt(4)],
					"Giant Mole"));
				break;
			// public:end chronicle
			case 61:
				ctx.setSessionMinutes(random.nextInt(120));
				break;
			case 62:
				if (random.nextBoolean())
				{
					h.game.wearing(4151);
				}
				else
				{
					h.game.wearing();
				}
				break;
			// The wider world: things that happen and are gone, with no state
			// behind them for anything to read.
			case 63:
				h.dispatch(TriggerEvent.worldHop(302));
				break;
			case 64:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.GAME_UPDATE));
				break;
			case 65:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.NOTES_EDITED));
				break;
			case 66:
				h.dispatch(TriggerEvent.musicUnlock("Sea Shanty 2"));
				break;
			case 67:
				h.dispatch(TriggerEvent.trackChange(random.nextBoolean() ? "Sea Shanty 2" : "Harmony"));
				break;
			case 68:
				h.dispatch(TriggerEvent.collectionAdd("Bandos chestplate"));
				break;
			case 69:
				h.dispatch(TriggerEvent.questComplete("The Restless Ghost"));
				break;
			case 70:
				h.dispatch(TriggerEvent.geOffer(random.nextBoolean(), "Yew logs", 40_000));
				break;
			case 71:
				h.dispatch(TriggerEvent.channel(random.nextBoolean()
					? TriggerEvent.Type.CHANNEL_JOIN : TriggerEvent.Type.CHANNEL_LEAVE, "Bob"));
				break;
			case 72:
				h.dispatch(TriggerEvent.npcShout("Town crier", "Hear ye! Hear ye!"));
				break;
			case 73:
				h.dispatch(TriggerEvent.examinedThing("Scarecrow"));
				break;
			case 74:
				h.dispatch(TriggerEvent.simple(random.nextBoolean()
					? TriggerEvent.Type.ATTENDED_AGAIN : TriggerEvent.Type.MINUTES_TAKEN));
				break;
			default:
				h.game.animating(random.nextBoolean() ? 879 : -1);
				break;
		}
	}

	@Test
	public void everyConditionTypeIsInThePool()
	{
		// The fuzzer was three features behind once before anybody looked. The
		// combinators are written by condition() itself; every other type has
		// to be drawable, or no random rule set will ever contain it.
		Set<String> pooled = new TreeSet<>(Arrays.asList("all", "any", "none"));
		for (String entry : CONDITIONS)
		{
			collectTypes(GSON.fromJson(entry, Condition.class), pooled);
		}
		Set<String> missing = new TreeSet<>(RuleSetIntegrityTest.KNOWN_TYPES);
		missing.removeAll(pooled);
		assertTrue("condition types the fuzzer never draws: " + missing, missing.isEmpty());
	}

	private static void collectTypes(Condition condition, Set<String> into)
	{
		into.add(condition.type.toLowerCase(java.util.Locale.ROOT));
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				collectTypes(child, into);
			}
		}
	}

	@Test
	public void randomRuleSetsUnderRandomEventsKeepTheirPromises() throws IOException
	{
		boolean[] rose = new boolean[CONDITIONS.length];

		for (int seed = 0; seed < 40; seed++)
		{
			Random random = new Random(seed);
			Harness h = new Harness(folder.newFolder().toPath(), rules(random, 25));
			// The book, as the plugin wires it: here a quest done is a quest seen.
			h.engine.getContext().setQuestWitness(quest -> h.engine.getContext().isQuestDone(quest)
				? LocalDate.now().toEpochDay() : null);

			Set<String> disabled = new HashSet<>();
			disabled.add("probe");
			if (random.nextBoolean())
			{
				disabled.add("g" + random.nextInt(4));
			}
			h.engine.setDisabledGroups(disabled);
			stage(h);

			// Each rule's condition as written, for the failure message, and
			// the probes put in place over their pool entries.
			Map<String, String> written = new HashMap<>();
			Probe[] probes = new Probe[CONDITIONS.length];
			for (SpeechRule rule : h.loader.getRules())
			{
				written.put(rule.id, GSON.toJson(rule.when));
				if (rule.id.startsWith("probe"))
				{
					int index = Integer.parseInt(rule.id.substring("probe".length()));
					probes[index] = new Probe(rule.when);
					rule.when = probes[index];
				}
			}

			Map<String, Long> lastSpokeAt = new HashMap<>();
			int before = 0;

			// A fake clock, a game tick a step, so a cooldown runs out and comes
			// round again inside the run. On the real one three hundred steps
			// take less than a second, and no rule with a cooldown ever got to
			// speak twice for the check below to measure.
			AtomicLong clock = new AtomicLong(1_700_000_000_000L);
			h.engine.setClock(clock::get);

			for (int step = 0; step < 300; step++)
			{
				clock.addAndGet(600L);
				if (random.nextBoolean())
				{
					randomEvent(h, random);
				}
				h.gameTick();

				// Nothing from a group that was switched off.
				for (int i = before; i < h.spoken.size(); i++)
				{
					Harness.Spoken spoken = h.spoken.get(i);
					SpeechRule rule = spoken.rule;
					assertTrue("seed " + seed + ": '" + rule.id + "' spoke from the"
							+ " disabled group '" + rule.group + "'",
						!disabled.contains(rule.group));

					// Never twice inside its own cooldown. The engine charges it
					// when a line is said, and at the win too for one delayed or
					// held back, so the next line waits a full cooldown after the
					// last. The harness has the global gap off; it could only
					// spread them further. The clock moves only between steps, so
					// this reads the "now" each line was charged at. A line held
					// through a scene or a conversation can outlast its cooldown,
					// and a rule with one waiting is not queued a second time.
					Long previous = lastSpokeAt.get(rule.id);
					long now = clock.get();
					if (previous != null && rule.cooldownMs > 0)
					{
						assertTrue("seed " + seed + ": '" + rule.id + "' spoke twice"
								+ " inside its " + rule.cooldownMs + "ms cooldown, "
								+ (now - previous) + "ms apart",
							now - previous >= rule.cooldownMs);
					}
					lastSpokeAt.put(rule.id, now);
				}
				before = h.spoken.size();
			}

			// The silent failure: a condition that throws disables its rule for
			// good, and the only symptom is a line nobody ever hears again.
			for (SpeechRule rule : h.loader.getRules())
			{
				assertTrue("seed " + seed + ": '" + rule.id + "' was disabled by a"
						+ " condition that threw: " + written.get(rule.id),
					rule.isEnabled());
			}

			for (int i = 0; i < probes.length; i++)
			{
				rose[i] |= probes[i].rose;
			}

			// Still alive and answering after all that.
			h.clear();
			h.game.hitpoints(1, 99);
			h.gameTicks(5);
		}

		// And the driver reaches the whole pool: every entry came true after
		// being false somewhere in the run. One that never does has an event
		// nobody raises or a state nobody flips, and whatever it would throw
		// on its true side, nothing above would ever see.
		List<String> unreached = new ArrayList<>();
		for (int i = 0; i < CONDITIONS.length; i++)
		{
			String type = GSON.fromJson(CONDITIONS[i], Condition.class).type;
			if (!rose[i] && !CONSTANTS.contains(type.toLowerCase(java.util.Locale.ROOT)))
			{
				unreached.add(CONDITIONS[i]);
			}
		}
		assertTrue("pool entries the driver never made true: " + unreached, unreached.isEmpty());
	}

	@Test
	public void aDelayedFiringNeverArrivesEarlierThanItsFloor() throws IOException
	{
		// Ten ticks of delay, checked exactly: the queue must not leak a firing
		// forward when other events are arriving in between.
		for (int seed = 0; seed < 20; seed++)
		{
			Random random = new Random(seed);
			Harness h = new Harness(folder.newFolder().toPath(),
				"{\"version\": 1, \"rules\": [{\"id\": \"slow\", \"group\": \"t\","
					+ " \"cooldownMs\": 0, \"delayTicks\": 10,"
					+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}]}");
			stage(h);

			h.gameTicks(1);
			h.dispatch(TriggerEvent.death());

			for (int tick = 1; tick <= 9; tick++)
			{
				if (random.nextBoolean())
				{
					randomEvent(h, random);
				}
				h.gameTick();
				assertTrue("seed " + seed + ": spoke on tick " + tick + " of a 10 tick wait",
					h.firedBy("slow").isEmpty());
			}
			// A line due inside a conversation is held back, not handed over
			// to be hushed (QA3 #10); the box the random events opened goes.
			h.engine.getContext().setDialogueOpen(false);
			h.gameTick();
			assertEquals("seed " + seed + ": did not speak when the wait was up",
				1, h.firedBy("slow").size());
		}
	}

	@Test
	public void atMostOneRuleSpeaksPerDispatch() throws IOException
	{
		// Twenty rules that are all true at once, all off cooldown. The engine
		// picks a single winner per pass; anything else is two lines at once.
		StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
		for (int i = 0; i < 20; i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append("{\"id\": \"r").append(i).append("\", \"group\": \"t\","
				+ " \"priority\": ").append(i).append(", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}");
		}
		Harness h = new Harness(folder.newFolder().toPath(), out.append("]}").toString());

		h.gameTick();
		int afterFirst = h.spoken.size();
		assertTrue("twenty true rules produced " + afterFirst + " lines in one pass",
			afterFirst <= 1);

		List<String> speakers = new ArrayList<>();
		for (int tick = 0; tick < 20; tick++)
		{
			int before = h.spoken.size();
			h.gameTick();
			assertTrue("more than one line in a single tick",
				h.spoken.size() - before <= 1);
			for (int i = before; i < h.spoken.size(); i++)
			{
				speakers.add(h.spoken.get(i).rule.id);
			}
		}

		// "always" never falls, so after the first pass no rule has a rising
		// edge left and nothing more should be said at all.
		assertTrue("a permanently true rule kept talking: " + speakers,
			speakers.isEmpty());
	}
}
