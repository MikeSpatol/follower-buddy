package com.follower.speech;

import com.follower.sim.QuestSim;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Below Ice Mountain walked stage by stage on the shipped rules, at
 * Occasional (6 s) and Minimal (45 s), by a walker and a runner. Stage
 * values from Quest Helper (BSD), master 06da90f: BelowIceMountain.java
 * loadSteps, with each recruit's own varbit (Marley 12064, Checkal 12065,
 * Burntof 12066, 40 when recruited); the end state from the cache's quest
 * table (varbit 12063 = 120).
 */
public class BelowIceMountainWalksTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long[] GAPS = {6000, 45000};

	/** How the player moves: tiles a tick, ticks before talking, and how long they stand about. */
	static final class Style
	{
		final String name;
		final int speed;
		final int lag;
		final double idle;

		Style(String name, int speed, int lag, double idle)
		{
			this.name = name;
			this.speed = speed;
			this.lag = lag;
			this.idle = idle;
		}
	}

	static final Style WALKER = new Style("walker", 1, 10, 1.0);
	static final Style RUNNER = new Style("runner", 2, 3, 0.3);
	static final Style[] STYLES = {WALKER, RUNNER};

	private static final int MAIN = 12063;
	private static final int MARLEY = 12064;
	private static final int CHECKAL = 12065;
	private static final int BURNTOF = 12066;

	/** The quest moments heard, in order. */
	static List<String> moments(QuestSim s)
	{
		List<String> out = new ArrayList<>();
		for (String id : s.heardIds)
		{
			if (id.startsWith("qm-"))
			{
				out.add(id);
			}
		}
		return out;
	}

	private static void pause(QuestSim s, Style p, int ticks)
	{
		s.ticks(Math.max(1, (int) Math.round(ticks * p.idle)));
	}

	private static void checkal(QuestSim s, Style p, int[] recruited)
	{
		s.walkTo(3080, 3420, 0, p.speed);
		s.ticks(3);
		s.npc("Checkal", 3087, 3415, 0);
		s.walkTo(3086, 3416, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Checkal", 14, () -> s.varbit(CHECKAL, 5));
		pause(s, p, 6);
		// Atlas in the Long Hall, and his workout.
		s.npc("Atlas", 3076, 3440, 0);
		s.walkTo(3076, 3438, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Atlas", 10, () -> s.varbit(CHECKAL, 10));
		s.scene(true);
		s.ticks(70);
		s.varbit(CHECKAL, 15);
		s.scene(false);
		s.talk("Atlas", 4, null);
		pause(s, p, 6);
		// Back to Checkal to flex.
		s.walkTo(3086, 3416, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Checkal", 6, () -> s.varbit(CHECKAL, 20));
		s.ticks(4);
		s.talk("Checkal", 6, () ->
		{
			s.varbit(CHECKAL, 40);
			s.varbit(MAIN, 10 + 5 * ++recruited[0]);
		});
		s.removeNpc("Checkal");
		s.removeNpc("Atlas");
		pause(s, p, 6);
	}

	private static void marley(QuestSim s, Style p, int[] recruited)
	{
		s.walkTo(3094, 3480, 0, p.speed);
		s.ticks(3);
		s.npc("Marley", 3088, 3470, 0);
		s.walkTo(3089, 3471, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Marley", 25, () -> s.varbit(MARLEY, 5));
		s.removeNpc("Marley");
		pause(s, p, 6);
		// The Blue Moon Inn's cook, and the recipe.
		s.walkTo(3220, 3400, 0, p.speed);
		s.ticks(4);
		s.npc("Cook", 3230, 3401, 0);
		s.walkTo(3229, 3401, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Cook", 25, () -> s.varbit(MARLEY, 10));
		s.removeNpc("Cook");
		pause(s, p, 10);
		s.bag(2142, 2309, 946);
		s.ticks(3);
		s.bag(946, 25631);
		pause(s, p, 10);
		// Back to Marley with it.
		s.walkTo(3094, 3480, 0, p.speed);
		s.ticks(3);
		s.npc("Marley", 3088, 3470, 0);
		s.walkTo(3089, 3471, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Marley", 12, () ->
		{
			s.varbit(MARLEY, 35);
			s.bag(946);
		});
		s.talk("Marley", 6, () ->
		{
			s.varbit(MARLEY, 40);
			s.varbit(MAIN, 10 + 5 * ++recruited[0]);
		});
		s.removeNpc("Marley");
		pause(s, p, 6);
	}

	private static void burntof(QuestSim s, Style p, int[] recruited)
	{
		s.walkTo(2960, 3378, 0, p.speed);
		s.ticks(3);
		s.npc("Burntof", 2956, 3367, 0);
		s.walkTo(2957, 3368, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Burntof", 6, () -> s.varbit(BURNTOF, 5));
		s.npc("Emily", 2954, 3370, 0);
		s.talk("Emily", 6, () -> s.bag(1905));
		s.removeNpc("Emily");
		s.ticks(p.lag);
		s.talk("Burntof", 20, () ->
		{
			s.varbit(BURNTOF, 10);
			s.bag();
		});
		// The best of three, upstairs.
		s.scene(true);
		s.ticks(50);
		s.varbit(BURNTOF, 40);
		s.varbit(MAIN, 10 + 5 * ++recruited[0]);
		s.scene(false);
		s.removeNpc("Burntof");
		pause(s, p, 6);
	}

	/**
	 * The whole quest, the recruits in the order given (c, m, b), with
	 * Priest in Peril seen done or only done before the follower came.
	 */
	static QuestSim walk(Path dir, long gap, Style p, boolean priestSeen, String order)
		throws java.io.IOException
	{
		QuestSim s = new QuestSim(dir, gap);
		s.note("### Below Ice Mountain, " + p.name + ", order " + order
			+ (priestSeen ? ", Priest in Peril witnessed" : ", Priest in Peril done before") + " | gap " + gap);
		if (priestSeen)
		{
			s.witnessed(Quest.PRIEST_IN_PERIL);
		}
		else
		{
			s.done(Quest.PRIEST_IN_PERIL);
		}
		s.started(Quest.BELOW_ICE_MOUNTAIN);
		// Willow, on the path south of Ice Mountain.
		s.at(3005, 3428, 0);
		s.ticks(3);
		s.npc("Willow", 3002, 3436, 0);
		s.walkTo(3003, 3434, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Willow", 40, () -> s.varbit(MAIN, 10));
		s.removeNpc("Willow");
		pause(s, p, 6);
		int[] recruited = {0};
		for (char c : order.toCharArray())
		{
			if (c == 'c')
			{
				checkal(s, p, recruited);
			}
			else if (c == 'm')
			{
				marley(s, p, recruited);
			}
			else
			{
				burntof(s, p, recruited);
			}
		}
		// The door on Ice Mountain's west side, the crew waiting at it.
		s.walkTo(2992, 3480, 0, p.speed);
		s.ticks(3);
		s.npc("Willow", 2996, 3494, 0);
		s.npc("Checkal", 2994, 3492, 0);
		s.npc("Marley", 2997, 3491, 0);
		s.npc("Burntof", 2998, 3493, 0);
		s.walkTo(2996, 3492, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Willow", 10, null);
		// The door blown, the reveal, the guardian: one long scene.
		s.scene(true);
		s.ticks(60);
		s.varbit(MAIN, 30);
		s.at(2952, 5768, 0);
		s.npc("Willow", 2952, 5772, 0);
		s.npc("Checkal", 2951, 5772, 0);
		s.npc("Marley", 2953, 5772, 0);
		s.npc("Burntof", 2954, 5771, 0);
		s.ticks(60);
		s.varbit(MAIN, 35);
		s.removeNpc("Willow");
		s.removeNpc("Checkal");
		s.removeNpc("Marley");
		s.removeNpc("Burntof");
		s.npc("Ancient Guardian", 2952, 5771, 0);
		s.scene(false);
		// The fight, from its edge.
		s.ticks(p == WALKER ? 80 : 40);
		s.varbit(MAIN, 40);
		s.removeNpc("Ancient Guardian");
		s.ticks(4);
		// Willow's bag, the doors, and Ramarno.
		s.scene(true);
		s.npc("Ramarno", 2951, 5779, 0);
		s.ticks(30);
		s.varbit(MAIN, 120);
		s.scene(false);
		s.questComplete(Quest.BELOW_ICE_MOUNTAIN, "Below Ice Mountain", "Ruins of Camdozaal");
		s.ticks(2);
		s.talk("Ramarno", 40, null);
		s.removeNpc("Ramarno");
		s.ticks(20);
		// He goes on to his workshop by the forge, and we follow.
		s.walkTo(2958, 5805, 0, p.speed);
		s.npc("Ramarno", 2959, 5809, 0);
		s.walkTo(2959, 5807, 0, p.speed);
		s.ticks(130);
		s.summary();
		return s;
	}

	private static List<String> expected(boolean priestSeen)
	{
		return Arrays.asList(
			"qm-below-ice-mountain-the-crew",
			"qm-below-ice-mountain-the-workout",
			"qm-below-ice-mountain-steak-sandwich",
			"qm-below-ice-mountain-rock-paper-scissors",
			priestSeen ? "qm-below-ice-mountain-grave-robbers-after-priest-in-peril"
				: "qm-below-ice-mountain-grave-robbers");
	}

	@Test
	public void belowIceMountainHasEachMomentOnceAndInOrder() throws Exception
	{
		for (long gap : GAPS)
		{
			for (Style p : STYLES)
			{
				for (boolean seen : new boolean[]{false, true})
				{
					QuestSim s = walk(folder.newFolder().toPath(), gap, p, seen, "cmb");
					assertEquals(s.transcript(), expected(seen), moments(s));
					assertTrue(s.transcript(), s.lost.isEmpty());
					assertTrue(s.transcript(), s.heardIds.contains("quest-complete"));
					assertTrue(s.transcript(), s.pagesSaid.contains("Below Ice Mountain"));
				}
			}
		}
	}

	/** The recruits are Willow's in any order; each one's moment follows its own recruit. */
	@Test
	public void theRecruitsCanComeInAnyOrder() throws Exception
	{
		for (long gap : GAPS)
		{
			QuestSim s = walk(folder.newFolder().toPath(), gap, RUNNER, false, "bmc");
			assertEquals(s.transcript(), Arrays.asList(
				"qm-below-ice-mountain-the-crew",
				"qm-below-ice-mountain-rock-paper-scissors",
				"qm-below-ice-mountain-steak-sandwich",
				"qm-below-ice-mountain-the-workout",
				"qm-below-ice-mountain-grave-robbers"), moments(s));
		}
	}

	/**
	 * Nothing said before the reveal knows what Willow is, or what is
	 * behind the door: no grave robbers, no tomb, no Camdozaal, Ramarno or
	 * Imcando.
	 */
	@Test
	public void nothingSaidBeforeTheRevealGivesTheGameAway() throws Exception
	{
		for (long gap : GAPS)
		{
			for (Style p : STYLES)
			{
				QuestSim s = walk(folder.newFolder().toPath(), gap, p, false, "cmb");
				int reveal = s.heardIds.indexOf("qm-below-ice-mountain-grave-robbers");
				assertTrue(s.transcript(), reveal > 0);
				for (String line : s.heard.subList(0, reveal))
				{
					assertFalse(line, line.matches("(?i).*\\b(graves?|robbers?|robbing|tombs?|camdozaal|ramarno|imcando|thief|thieves)\\b.*"));
				}
			}
		}
	}

	/**
	 * The ruins' arrival line waits the day: the quest ends inside them,
	 * and a line about coming back is no line for the first visit.
	 */
	@Test
	public void theRuinsWaitADayToBeWelcomedBack() throws Exception
	{
		QuestSim s = walk(folder.newFolder().toPath(), 6000, WALKER, false, "cmb");
		s.at(2995, 3480, 0);
		s.ticks(10);
		for (int i = 0; i < 20; i++)
		{
			s.at(2952, 5768, 0);
			s.ticks(4);
			s.at(2995, 3480, 0);
			s.ticks(4);
		}
		assertFalse(s.transcript(), s.heardIds.contains("fun-camdozaal-after-below-ice-mountain"));
	}

	/**
	 * Thurgo is the last of the Imcando smiths until the follower has met
	 * Ramarno: once it has seen the quest done, the plain line stands aside
	 * for the callback.
	 */
	@Test
	public void thurgoIsNoLongerTheLastOnceRamarnoIsMet() throws Exception
	{
		QuestSim before = new QuestSim(folder.newFolder().toPath(), 6000);
		before.done(Quest.BELOW_ICE_MOUNTAIN);
		before.at(3000, 3145, 0);
		before.npc("Thurgo", 3000, 3144, 0);
		before.ticks(20);
		assertTrue(before.transcript(), before.heardIds.contains("quest-thurgo"));
		assertFalse(before.transcript(), before.heardIds.contains("fun-thurgo-after-below-ice-mountain"));

		QuestSim after = new QuestSim(folder.newFolder().toPath(), 6000);
		after.witnessed(Quest.BELOW_ICE_MOUNTAIN);
		after.at(3000, 3145, 0);
		after.npc("Thurgo", 3000, 3144, 0);
		after.ticks(20);
		assertTrue(after.transcript(), after.heardIds.contains("fun-thurgo-after-below-ice-mountain"));
		assertFalse(after.transcript(), after.heardIds.contains("quest-thurgo"));
	}

	/**
	 * The quest sends you to the Blue Moon Inn's cook, who is named Cook too:
	 * the Lumbridge cook's lines stay in Lumbridge (the user, 20 Oct).
	 */
	@Test
	public void theBlueMoonCookIsNotTheLumbridgeCook() throws Exception
	{
		QuestSim varrock = new QuestSim(folder.newFolder().toPath(), 6000);
		varrock.at(3230, 3400, 0);
		varrock.npc("Cook", 3231, 3400, 0);
		varrock.ticks(20);
		assertFalse(varrock.transcript(), varrock.heardIds.contains("quest-cook"));

		QuestSim lumbridge = new QuestSim(folder.newFolder().toPath(), 6000);
		lumbridge.at(3208, 3214, 0);
		lumbridge.npc("Cook", 3209, 3214, 0);
		lumbridge.ticks(20);
		assertTrue(lumbridge.transcript(), lumbridge.heardIds.contains("quest-cook"));

		QuestSim after = new QuestSim(folder.newFolder().toPath(), 6000);
		after.witnessed(Quest.COOKS_ASSISTANT);
		after.at(3230, 3400, 0);
		after.npc("Cook", 3231, 3400, 0);
		after.ticks(20);
		assertFalse(after.transcript(), after.heardIds.contains("fun-cook-after-cooks-assistant"));
	}

	/** Checkal and Marley went to Nardah with Willow; each has a line there once the quest is seen done. */
	@Test
	public void theGangInNardah() throws Exception
	{
		for (String who : new String[]{"Checkal", "Marley"})
		{
			QuestSim s = new QuestSim(folder.newFolder().toPath(), 6000);
			s.witnessed(Quest.BELOW_ICE_MOUNTAIN);
			s.at(3446, 2903, 0);
			s.npc(who, 3447, 2903, 0);
			s.ticks(20);
			assertTrue(s.transcript(), s.heardIds.contains("fun-" + who.toLowerCase() + "-after-below-ice-mountain"));
		}
	}

	/**
	 * Barronite in the ruins, once the quest is seen done: a line now and
	 * then, and none for any other rock. Only that line, and its chance a
	 * certainty, past the gap after the arrival line: the test is of what it
	 * is said for. Left to its 2%, its first rise could land in another
	 * line's gap, be held, and spend its three-hour cooldown (agility QA5's
	 * baseline run, 20 Oct).
	 */
	@Test
	public void barroniteIsMinedForTheForge() throws Exception
	{
		QuestSim seen = onlyTheBarroniteLine();
		seen.h.engine.getContext().setWork(true, "mining", "Barronite rocks", null, 20, 3, 1);
		seen.ticks(5);
		assertTrue(seen.transcript(), seen.heardIds.contains("fun-camdozaal-mining-after-below-ice-mountain"));

		QuestSim copper = onlyTheBarroniteLine();
		copper.h.engine.getContext().setWork(true, "mining", "Copper rocks", null, 20, 3, 1);
		copper.ticks(600);
		assertFalse(copper.transcript(), copper.heardIds.contains("fun-camdozaal-mining-after-below-ice-mountain"));
	}

	private QuestSim onlyTheBarroniteLine() throws Exception
	{
		QuestSim s = new QuestSim(folder.newFolder().toPath(), 6000);
		for (SpeechRule rule : s.h.loader.getRules())
		{
			if (!rule.id.equals("fun-camdozaal-mining-after-below-ice-mountain"))
			{
				rule.enabled = Boolean.FALSE;
				continue;
			}
			for (Condition condition : rule.when.conditions)
			{
				if ("chance".equals(condition.type))
				{
					assertEquals("the shipped chance", Integer.valueOf(2), condition.percent);
					condition.percent = 100;
				}
			}
		}
		s.witnessed(Quest.BELOW_ICE_MOUNTAIN);
		s.at(2924, 5814, 0);
		s.ticks(20);
		return s;
	}
}
