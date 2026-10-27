package com.follower;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Quests QA5, 20 Oct: the quest findings, walked on QuestSim at Occasional
 * (6 s) and Minimal (45 s) with the people where the game has them. The
 * walks are QA5's (QaNew5Test), stage values from Quest Helper (BSD),
 * master 06da90f.
 */
public class QaFiveQuestsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long[] GAPS = {6000, 45000};

	private static final BelowIceMountainWalksTest.Style WALKER = BelowIceMountainWalksTest.WALKER;
	private static final BelowIceMountainWalksTest.Style RUNNER = BelowIceMountainWalksTest.RUNNER;
	private static final BelowIceMountainWalksTest.Style CHAIN =
		new BelowIceMountainWalksTest.Style("chain-clicker", 2, 1, 0.0);
	private static final BelowIceMountainWalksTest.Style[] STYLES = {WALKER, RUNNER, CHAIN};

	private QuestSim sim(long gap) throws Exception
	{
		return new QuestSim(folder.newFolder().toPath(), gap);
	}

	private static void pause(QuestSim s, BelowIceMountainWalksTest.Style p, int ticks)
	{
		s.ticks(Math.max(1, (int) Math.round(ticks * p.idle)));
	}

	private static final Pattern SAYS = Pattern.compile("^t\\d+\\s+([\\d.]+)s\\s+SAYS \\[");

	/** When each heard line was said, in seconds, read off the transcript. */
	private static List<Double> heardAt(QuestSim s)
	{
		List<Double> at = new ArrayList<>();
		for (String line : s.transcript().split("\\R"))
		{
			Matcher m = SAYS.matcher(line);
			if (m.find())
			{
				at.add(Double.parseDouble(m.group(1)));
			}
		}
		return at;
	}

	// ------------------------------------------------------------ Enter the Abyss

	/** The mine on its map region, or an instance copy of it, as it is in play. */
	private QuestSim abyss(BelowIceMountainWalksTest.Style p, long gap, boolean runeMysteriesSeen, boolean instanced)
		throws Exception
	{
		QuestSim s = sim(gap);
		if (runeMysteriesSeen)
		{
			s.witnessed(Quest.RUNE_MYSTERIES);
		}
		else
		{
			s.done(Quest.RUNE_MYSTERIES);
		}
		s.started(Quest.ENTER_THE_ABYSS);
		int mineTicks = p == WALKER ? 20 : p == RUNNER ? 10 : 6;
		int mx = instanced ? 11456 + 30 : 2911;
		int my = instanced ? 5056 + 30 : 4832;
		s.at(3094, 3520, 0);
		s.ticks(3);
		s.npc("Mage of Zamorak", 3104, 3558, 0);
		s.walkTo(3103, 3556, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Mage of Zamorak", 8, () -> s.varp(492, 1));
		pause(s, p, 10);
		s.removeNpc("Mage of Zamorak");
		// Varrock: the chaos temple, Aubury 18 tiles north.
		s.at(3240, 3420, 0);
		s.ticks(3);
		s.npc("Aubury", 3253, 3401, 0);
		s.npc("Mage of Zamorak", 3259, 3385, 0);
		s.walkTo(3258, 3384, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Mage of Zamorak", 40, () ->
		{
			s.varp(492, 2);
			s.bag(5519);
		});
		pause(s, p, 4);
		s.walkTo(3253, 3399, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Aubury", 6, null);
		s.at(mx, my, 0);
		s.varbit(2315, 1);
		s.ticks(mineTicks);
		s.at(3104, 9565, 0);
		s.npc("Archmage Sedridor", 3104, 9571, 0);
		s.walkTo(3104, 9569, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Archmage Sedridor", 6, null);
		s.at(mx, my, 0);
		s.varbit(2314, 1);
		s.ticks(mineTicks);
		s.at(2662, 3305, 0);
		s.npc("Wizard Cromperty", 2684, 3323, 0);
		s.walkTo(2683, 3321, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Wizard Cromperty", 6, null);
		s.at(mx, my, 0);
		s.varbit(2316, 1);
		s.bag(5518);
		s.ticks(mineTicks);
		s.at(3253, 3399, 0);
		s.ticks(3);
		s.walkTo(3258, 3384, 0, p.speed);
		s.ticks(p.lag);
		s.dialogue("Mage of Zamorak");
		s.ticks(20);
		s.varp(492, 3);
		s.bag();
		s.ticks(20);
		s.varp(492, 4);
		s.miniquestComplete(Quest.ENTER_THE_ABYSS, "Enter the Abyss");
		s.ticks(2);
		s.dialogue(false);
		s.ticks(150);
		s.summary();
		return s;
	}

	/**
	 * QA5 #14: the essence mine is an instance, which reports its own
	 * region, so a moment kept to 11595 was never said (19 Oct, three trips).
	 * The full orb at varp 492 = 2 is only true from the third teleport.
	 */
	@Test
	public void theOrbFillsInTheInstancedMine() throws Exception
	{
		for (long gap : GAPS)
		{
			for (BelowIceMountainWalksTest.Style p : STYLES)
			{
				QuestSim s = abyss(p, gap, false, true);
				assertTrue(p.name + "\n" + s.transcript(), s.heardIds.contains("qm-enter-the-abyss-the-orb-full"));
				assertTrue(s.transcript(), s.lost.isEmpty());
			}
		}
	}

	/**
	 * QA5 #57: at Minimal the Wilderness start was four lines in 28 s, the
	 * mage's own introduction after the moment about him. On the errand,
	 * varp 492 1-3, the moments carry him; met before he is spoken to, he is
	 * introduced then.
	 */
	@Test
	public void theMageIsNotIntroducedOverTheMomentAboutHim() throws Exception
	{
		for (long gap : GAPS)
		{
			for (BelowIceMountainWalksTest.Style p : STYLES)
			{
				QuestSim s = abyss(p, gap, false, false);
				String walk = s.transcript();
				int from = walk.indexOf("varp 492 = 1");
				int to = walk.indexOf("varp 492 = 4");
				assertTrue(walk, from > 0 && to > from
					&& walk.substring(from, to).contains("SAYS [qm-enter-the-abyss-not-here]"));
				assertFalse(p.name + ", gap " + gap + "\n" + walk,
					walk.substring(from, to).contains("SAYS [quest-mage-of-zamorak]"));
			}
		}
	}

	/** The same, spoken to the moment he is in view: the introduction, kept through his box, is let go. */
	@Test
	public void theMageSpokenToAtOnceIsNotIntroducedAfterTheMoment() throws Exception
	{
		for (long gap : GAPS)
		{
			QuestSim s = sim(gap);
			s.done(Quest.RUNE_MYSTERIES);
			s.started(Quest.ENTER_THE_ABYSS);
			s.at(3094, 3520, 0);
			s.ticks(20);
			s.at(3103, 3556, 0);
			s.npc("Mage of Zamorak", 3104, 3558, 0);
			s.talk("Mage of Zamorak", 8, () -> s.varp(492, 1));
			s.ticks(80);
			s.summary();
			assertTrue(s.transcript(), s.heardIds.contains("qm-enter-the-abyss-not-here"));
			assertFalse("gap " + gap + "\n" + s.transcript(), s.heardIds.contains("quest-mage-of-zamorak"));
		}
	}

	/**
	 * QA5 #52, the real 19 Oct run at Minimal: the orb moment's Rune
	 * Mysteries callback, then Aubury's own 9 s later, his shop being on the
	 * way. On the orb's errand the plain line covers him.
	 */
	@Test
	public void auburysCallbackStandsAsideOnTheOrbsErrand() throws Exception
	{
		for (long gap : GAPS)
		{
			QuestSim s = sim(gap);
			s.witnessed(Quest.RUNE_MYSTERIES);
			s.started(Quest.ENTER_THE_ABYSS);
			s.varp(492, 1);
			s.at(3240, 3420, 0);
			s.ticks(3);
			s.npc("Aubury", 3253, 3401, 0);
			s.npc("Mage of Zamorak", 3259, 3385, 0);
			s.walkTo(3258, 3384, 0, 2);
			s.ticks(3);
			s.talk("Mage of Zamorak", 40, () ->
			{
				s.varp(492, 2);
				s.bag(5519);
			});
			s.ticks(2);
			s.walkTo(3253, 3399, 0, 2);
			s.ticks(30);
			s.summary();
			assertTrue(s.transcript(), s.heardIds.contains("qm-enter-the-abyss-the-orb-after-rune-mysteries"));
			assertFalse(s.transcript(), s.heardIds.contains("fun-aubury-after-rune-mysteries"));
		}
	}

	/**
	 * QA5 #15: the Ourania Cave's Mage of Zamorak (7425) shares the name, and
	 * was given the Varrock mage's lines, Herbert's tea included.
	 */
	@Test
	public void theOuraniaMageIsNotTheQuestMage() throws Exception
	{
		for (String seen : new String[]{"none", "abyss", "temple"})
		{
			QuestSim s = sim(6000);
			if (!seen.equals("none"))
			{
				s.witnessed(Quest.ENTER_THE_ABYSS);
			}
			if (seen.equals("temple"))
			{
				s.witnessed(Quest.TEMPLE_OF_THE_EYE);
			}
			s.at(3014, 5620, 0);
			s.ticks(5);
			s.npc("Mage of Zamorak", 3015, 5622, 0);
			s.ticks(40);
			for (String id : s.heardIds)
			{
				assertFalse(seen + "\n" + s.transcript(), id.contains("mage-of-zamorak"));
			}
		}
		QuestSim varrock = sim(6000);
		varrock.at(3255, 3383, 0);
		varrock.ticks(5);
		varrock.npc("Mage of Zamorak", 3259, 3385, 0);
		varrock.ticks(40);
		assertTrue("still the quest mage in Varrock\n" + varrock.transcript(),
			varrock.heardIds.contains("quest-mage-of-zamorak"));
	}

	// ---------------------------------------------------------- Temple of the Eye

	private QuestSim temple(BelowIceMountainWalksTest.Style p, long gap, boolean seen) throws Exception
	{
		QuestSim s = sim(gap);
		if (seen)
		{
			s.witnessed(Quest.RUNE_MYSTERIES);
			s.witnessed(Quest.ENTER_THE_ABYSS);
		}
		else
		{
			s.done(Quest.RUNE_MYSTERIES);
			s.done(Quest.ENTER_THE_ABYSS);
		}
		s.started(Quest.TEMPLE_OF_THE_EYE);
		s.at(3270, 3220, 0);
		s.ticks(3);
		s.npc("Wizard Persten", 3284, 3232, 0);
		s.walkTo(3284, 3230, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Wizard Persten", 40, () ->
		{
			s.varbit(13738, 10);
			s.bag(26903);
		});
		s.removeNpc("Wizard Persten");
		pause(s, p, 10);
		// Herbert, and the tea.
		s.at(3240, 3420, 0);
		s.ticks(3);
		s.npc("Mage of Zamorak", 3259, 3385, 0);
		s.walkTo(3258, 3384, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Mage of Zamorak", 30, () -> s.varbit(13738, 15));
		pause(s, p, 6);
		s.npc("Tea seller", 3271, 3410, 0);
		s.walkTo(3270, 3409, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Tea seller", 8, () -> s.bag(26903, 26904, 1929));
		pause(s, p, 6);
		s.walkTo(3258, 3384, 0, p.speed);
		s.ticks(p.lag);
		s.dialogue("Mage of Zamorak");
		s.ticks(10);
		s.varbit(13738, 20);
		s.ticks(10);
		s.varbit(13738, 25);
		s.removeNpc("Mage of Zamorak");
		s.at(3039, 4832, 0);
		s.npc("Dark Mage", 3039, 4834, 0);
		s.ticks(1);
		s.dialogue(false);
		s.ticks(4 + p.lag);
		s.dialogue("Dark Mage");
		s.ticks(10);
		s.varbit(13738, 30);
		s.ticks(15);
		s.varbit(13738, 35);
		s.ticks(1);
		s.dialogue(false);
		// Touching the rune energies in order: a puzzle, never quick.
		s.ticks(p == WALKER ? 60 : 25);
		pause(s, p, 30);
		s.varbit(13738, 40);
		s.talk("Dark Mage", 10, () -> s.varbit(13738, 45));
		s.removeNpc("Dark Mage");
		pause(s, p, 10);
		// Persten again, and Sedridor on the incantation.
		s.at(3270, 3220, 0);
		s.npc("Wizard Persten", 3284, 3232, 0);
		s.walkTo(3284, 3230, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Wizard Persten", 40, () -> s.varbit(13738, 60));
		s.removeNpc("Wizard Persten");
		s.at(3104, 9565, 0);
		s.npc("Archmage Sedridor", 3104, 9571, 0);
		s.walkTo(3104, 9569, 0, p.speed);
		s.ticks(p.lag);
		s.dialogue("Archmage Sedridor");
		s.ticks(20);
		s.varbit(13738, 65);
		s.ticks(20);
		s.varbit(13738, 70);
		s.ticks(1);
		s.dialogue(false);
		pause(s, p, 10);
		// Basement ladder, then the stairs to the first floor.
		s.ticks(p == WALKER ? 25 : 12);
		// Traiborn's puzzle, and the answer.
		s.at(3106, 3160, 1);
		s.npc("Wizard Traiborn", 3111, 3162, 1);
		s.npc("Apprentice Felix", 3108, 3164, 1);
		s.walkTo(3110, 3162, 1, p.speed);
		s.ticks(p.lag);
		s.talk("Wizard Traiborn", 20, () -> s.varbit(13738, 75));
		s.ticks(p.lag);
		s.talk("Apprentice Felix", 8, null);
		s.ticks(p.lag);
		s.talk(8, null);
		s.ticks(p.lag);
		s.talk(8, null);
		s.varbit(13738, 80);
		s.ticks(p.lag);
		s.talk("Wizard Traiborn", 15, () -> s.varbit(13738, 85));
		pause(s, p, 10);
		// Stairs down and the basement ladder.
		s.ticks(p == WALKER ? 25 : 12);
		s.removeNpc("Wizard Traiborn");
		s.removeNpc("Apprentice Felix");
		// The incantation, and the temple.
		s.at(3104, 9569, 0);
		s.npc("Wizard Persten", 3105, 9572, 0);
		s.ticks(p.lag);
		s.talk("Wizard Persten", 10, () -> s.varbit(13738, 90));
		s.scene(true);
		s.ticks(40);
		s.varbit(13738, 95);
		s.ticks(20);
		s.varbit(13738, 100);
		s.at(2398, 5650, 0);
		s.removeNpc("Wizard Persten");
		s.npc("Apprentice Felix", 2400, 5652, 0);
		s.scene(false);
		pause(s, p, 40);
		s.npc("Wizard Persten", 2397, 5652, 0);
		s.talk("Wizard Persten", 10, () -> s.varbit(13738, 105));
		s.scene(true);
		s.ticks(30);
		s.varbit(13738, 110);
		s.ticks(40);
		s.varbit(13738, 115);
		s.removeNpc("Wizard Persten");
		s.npc("The Great Guardian", 2400, 5655, 0);
		s.scene(false);
		s.ticks(p == CHAIN ? 60 : 120);
		s.scene(true);
		s.ticks(30);
		s.varbit(13738, 120);
		s.ticks(30);
		s.varbit(13738, 125);
		s.scene(false);
		s.removeNpc("The Great Guardian");
		s.removeNpc("Apprentice Felix");
		s.at(3104, 9565, 0);
		s.npc("Archmage Sedridor", 3104, 9571, 0);
		s.walkTo(3104, 9569, 0, p.speed);
		s.ticks(p.lag);
		s.talk("Archmage Sedridor", 20, () -> s.varbit(13738, 130));
		s.questComplete(Quest.TEMPLE_OF_THE_EYE, "Temple of the Eye");
		s.ticks(200);
		s.summary();
		return s;
	}

	/**
	 * Every Temple of the Eye walk, at both gaps, by a walker, a runner and
	 * a chain-clicker, with the earlier quests seen or not.
	 *
	 * <ul>
	 * <li>QA5 #54: at Minimal a fast player lost the thingummywut. Traiborn's
	 * intro, kept through the puzzle's boxes, took the floor from it at the
	 * hand-in, and it was let go as Persten's box opened.</li>
	 * <li>QA5 #52: the mage's Enter the Abyss callback, then the Herbert
	 * moment's 23-30 s later, both about the orb's errand.</li>
	 * <li>QA5 #53: a light line seconds after the grave Persten moment,
	 * Felix's intro most often.</li>
	 * </ul>
	 */
	@Test
	public void templeOfTheEyeWalks() throws Exception
	{
		for (long gap : GAPS)
		{
			for (BelowIceMountainWalksTest.Style p : STYLES)
			{
				for (boolean seen : new boolean[]{false, true})
				{
					QuestSim s = temple(p, gap, seen);
					String why = p.name + ", gap " + gap + (seen ? ", seen" : "") + "\n" + s.transcript();
					assertTrue(why, s.heardIds.contains("qm-temple-of-the-eye-thingummywut"));
					assertFalse(why, s.heardIds.contains("fun-mage-of-zamorak-after-enter-the-abyss"));
					int persten = s.heardIds.indexOf("qm-temple-of-the-eye-persten");
					assertTrue(why, persten > 0 && persten + 1 < s.heardIds.size());
					List<Double> at = heardAt(s);
					assertTrue(why, at.get(persten + 1) - at.get(persten) >= 45.0);
				}
			}
		}
	}

	/**
	 * QA5 #60: the plain lines that the quests answer stand aside once the
	 * follower has seen them done, as the Cook's and Thurgo's do: Traiborn's
	 * "Nobody knows" after the thingummywut is eleven, Burntof's drink with
	 * him sober in Nardah.
	 */
	@Test
	public void traibornAndBurntofStandAsideOnceTheirQuestIsSeen() throws Exception
	{
		String[][] cases = {
			{"Wizard Traiborn", "TEMPLE_OF_THE_EYE", "quest-traiborn", "3110", "3162", "1"},
			{"Burntof", "BELOW_ICE_MOUNTAIN", "quest-burntof", "3446", "2903", "0"},
		};
		for (String[] c : cases)
		{
			for (boolean seen : new boolean[]{false, true})
			{
				QuestSim s = sim(6000);
				if (seen)
				{
					s.witnessed(Quest.valueOf(c[1]));
				}
				int x = Integer.parseInt(c[3]);
				int y = Integer.parseInt(c[4]);
				int plane = Integer.parseInt(c[5]);
				s.at(x, y, plane);
				s.ticks(5);
				s.npc(c[0], x + 1, y, plane);
				s.ticks(20);
				assertEquals(c[0] + (seen ? ", seen" : "") + "\n" + s.transcript(),
					!seen, s.heardIds.contains(c[2]));
			}
		}
	}

	// ---------------------------------------------------------- Below Ice Mountain

	/**
	 * QA5 #55: the guardian's line was kept to Camdozaal's map regions, which
	 * an instanced fight would not report. Kept to the quest's stage instead.
	 */
	@Test
	public void theAncientGuardianIsKnownByTheQuestStageNotTheRegion() throws Exception
	{
		for (boolean instanced : new boolean[]{false, true})
		{
			QuestSim s = sim(6000);
			s.started(Quest.BELOW_ICE_MOUNTAIN);
			s.varbit(12063, 35);
			int x = instanced ? 11456 + 30 : 2952;
			int y = instanced ? 5056 + 30 : 5772;
			s.at(x, y, 0);
			s.ticks(5);
			s.npc("Ancient Guardian", x + 2, y, 0);
			s.ticks(20);
			assertTrue((instanced ? "instanced" : "the ruins") + "\n" + s.transcript(),
				s.heardIds.contains("quest-ancient-guardian"));
		}
		// Desert Treasure II's guardian, in the Ancient Vault, long after.
		QuestSim vault = sim(6000);
		vault.done(Quest.BELOW_ICE_MOUNTAIN);
		vault.varbit(12063, 120);
		vault.at(3360, 6400, 0);
		vault.ticks(5);
		vault.npc("Ancient Guardian", 3362, 6400, 0);
		vault.ticks(20);
		assertFalse(vault.transcript(), vault.heardIds.contains("quest-ancient-guardian"));
	}

	// ---------------------------------------------------------- the scroll, and the places

	/**
	 * QA5 #41: parked on Stay, the follower did not see the quest finish, and
	 * the book files it as not seen; it said "{quest}, done and witnessed."
	 * from its post all the same. QA5 #46: it asks how it went instead.
	 */
	@Test
	public void aParkedFollowerClaimsNoSightingAtTheScroll() throws Exception
	{
		for (int tally : new int[]{0, 9})
		{
			QuestSim s = sim(6000);
			for (int i = 0; i < tally; i++)
			{
				s.h.engine.getContext().tally("quests");
			}
			s.started(Quest.COOKS_ASSISTANT);
			s.h.engine.getContext().setFollowerStaying(true);
			s.ticks(10);
			s.questComplete(Quest.COOKS_ASSISTANT, "Cook's Assistant");
			s.ticks(60);
			assertFalse(s.transcript(), s.heardIds.contains("quest-complete"));
			assertFalse(s.transcript(), s.heardIds.contains("quest-milestone-10"));
			assertTrue(s.transcript(), s.heard.contains("Cook's Assistant, done. Tell me how it went."));
		}
		QuestSim there = sim(6000);
		there.started(Quest.COOKS_ASSISTANT);
		there.ticks(10);
		there.questComplete(Quest.COOKS_ASSISTANT, "Cook's Assistant");
		there.ticks(60);
		assertTrue(there.transcript(), there.heardIds.contains("quest-complete"));
		assertFalse(there.transcript(), there.heardIds.contains("quest-complete-parked"));
	}

	/**
	 * QA5 #17: the Varrock callback to Children of the Sun was said six times
	 * on 18 Oct, its bag line four. Arriving every five minutes for six hours,
	 * it is heard at most once in three hours.
	 */
	@Test
	public void aLongDayInVarrockHearsTheCallbackOnceOrTwice() throws Exception
	{
		QuestSim s = sim(6000);
		s.witnessed(Quest.CHILDREN_OF_THE_SUN);
		for (int visit = 0; visit < 72; visit++)
		{
			s.at(3290, 3420, 0);
			s.ticks(250);
			s.at(3212, 3424, 0);
			s.ticks(250);
		}
		int said = java.util.Collections.frequency(s.heardIds, "fun-varrock-after-children-of-the-sun");
		assertTrue(said + "\n" + s.transcript(), said >= 1 && said <= 2);
	}

	/**
	 * Text QA5 #65: "5. Hard to believe there was ever a first one." at the
	 * fifth kill. The lines about a long history wait for the hundreds.
	 */
	@Test
	public void theLongHistoryLinesWaitForTheHundreds() throws Exception
	{
		Harness h = new Harness(folder.newFolder().toPath());
		java.util.Set<String> early = new java.util.HashSet<>();
		java.util.Set<String> late = new java.util.HashSet<>();
		long[] clock = {1_800_000_000_000L};
		h.engine.setClock(() -> clock[0]);
		for (int round = 0; round < 60; round++)
		{
			for (int count : new int[]{5, 10, 25, 50, 75, 100, 200, 500})
			{
				h.clear();
				clock[0] += 3_600_000L;
				h.dispatch(TriggerEvent.killCount("Vorkath", count, count - 1));
				h.gameTicks(8);
				for (Harness.Spoken spoken : h.spoken)
				{
					if (spoken.rule != null && spoken.rule.id.startsWith("kill-count-round"))
					{
						(count < 100 ? early : late).add(spoken.rule.id + ": " + spoken.text);
					}
				}
			}
		}
		assertFalse("the round line was heard early", early.isEmpty());
		for (String line : early)
		{
			assertFalse(line, line.contains("first one") || line.contains("from memory"));
		}
		assertTrue("from the hundreds, " + late, late.stream().anyMatch(line -> line.contains("first one")));
		assertTrue("and the round line's own, " + late,
			late.stream().anyMatch(line -> line.startsWith("kill-count-round: ")));
	}
}
