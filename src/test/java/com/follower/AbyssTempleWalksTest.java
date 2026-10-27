package com.follower;

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
 * Enter the Abyss and Temple of the Eye walked stage by stage on the shipped
 * rules, at Occasional (6 s) and Minimal (45 s). Stage values from Quest
 * Helper (BSD), master 06da90f: EnterTheAbyss.java and TempleOfTheEye.java
 * loadSteps; end states from the cache's quest table (4 and 130).
 */
public class AbyssTempleWalksTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long[] GAPS = {6000, 45000};

	private QuestSim sim(long gap) throws Exception
	{
		return new QuestSim(folder.newFolder().toPath(), gap);
	}

	/** The quest moments heard, in order. */
	private static List<String> moments(QuestSim s)
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

	// ------------------------------------------------------------ Enter the Abyss

	/** Enter the Abyss, the usual way: Aubury, Sedridor, Cromperty. */
	private QuestSim abyss(long gap, boolean runeMysteriesSeen) throws Exception
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
		s.at(3100, 3550, 0);
		s.npc("Mage of Zamorak", 3104, 3558, 0);
		s.walkTo(3103, 3556, 0, 1);
		s.ticks(6);
		s.talk("Mage of Zamorak", 8, () -> s.varp(492, 1));
		s.ticks(20);
		s.removeNpc("Mage of Zamorak");
		s.at(3212, 3424, 0);
		s.ticks(4);
		s.npc("Mage of Zamorak", 3259, 3386, 0);
		s.walkTo(3258, 3384, 0, 1);
		s.ticks(4);
		s.talk("Mage of Zamorak", 40, () ->
		{
			s.varp(492, 2);
			s.bag(5519);
		});
		s.ticks(20);
		s.at(3253, 3400, 0);
		s.ticks(4);
		s.at(2911, 4832, 0);
		s.varbit(2315, 1);
		s.ticks(20);
		s.at(3104, 9572, 0);
		s.ticks(10);
		s.at(2911, 4832, 0);
		s.varbit(2314, 1);
		s.ticks(20);
		s.at(2683, 3321, 0);
		s.ticks(10);
		s.at(2911, 4832, 0);
		s.varbit(2316, 1);
		s.bag(5518);
		s.ticks(30);
		s.at(3258, 3384, 0);
		s.ticks(10);
		s.dialogue("Mage of Zamorak");
		s.ticks(20);
		s.varp(492, 3);
		s.bag();
		s.ticks(20);
		s.varp(492, 4);
		// No scroll: the plugin hears of it as the page is written.
		s.miniquestComplete(Quest.ENTER_THE_ABYSS, "Enter the Abyss");
		s.ticks(2);
		s.dialogue(false);
		s.ticks(120);
		s.summary();
		return s;
	}

	@Test
	public void enterTheAbyssHasEachMomentOnceAndInOrder() throws Exception
	{
		for (long gap : GAPS)
		{
			for (boolean seen : new boolean[]{false, true})
			{
				QuestSim s = abyss(gap, seen);
				assertEquals(s.transcript(), Arrays.asList(
					"qm-enter-the-abyss-not-here",
					seen ? "qm-enter-the-abyss-the-orb-after-rune-mysteries" : "qm-enter-the-abyss-the-orb",
					"qm-enter-the-abyss-the-orb-full",
					"qm-enter-the-abyss-done"), moments(s));
				assertTrue(s.transcript(), s.lost.isEmpty());
			}
		}
	}

	@Test
	public void aMiniquestsEndIsSaidWithNoScrollAndItsPageIsNews() throws Exception
	{
		QuestSim s = abyss(6000, false);
		assertFalse("a miniquest has no scroll to answer", s.heardIds.contains("quest-complete"));
		assertTrue("its page is the witness", s.h.engine.getContext().questWitnessedOn("ENTER_THE_ABYSS") != null);
	}

	/** The first two readings pass in silence; the orb filling at the third is the moment. */
	@Test
	public void theOrbIsMarkedWhenItFillsNotBefore() throws Exception
	{
		QuestSim s = sim(6000);
		s.started(Quest.ENTER_THE_ABYSS);
		s.varp(492, 2);
		s.bag(5519);
		s.at(3253, 3400, 0);
		s.ticks(10);
		s.at(2911, 4832, 0);
		s.varbit(2315, 1);
		s.ticks(20);
		assertFalse("not at the first reading\n" + s.transcript(),
			s.heardIds.contains("qm-enter-the-abyss-the-orb-full"));
		s.at(3253, 3400, 0);
		s.ticks(10);
		s.at(2911, 4832, 0);
		s.bag(5518);
		s.ticks(10);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-enter-the-abyss-the-orb-full"));
	}

	// ---------------------------------------------------------- Temple of the Eye

	private QuestSim temple(long gap, boolean earlierQuestsSeen) throws Exception
	{
		QuestSim s = sim(gap);
		if (earlierQuestsSeen)
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
		s.at(3284, 3231, 0);
		s.ticks(4);
		s.talk("Wizard Persten", 40, () ->
		{
			s.varbit(13738, 10);
			s.bag(26903);
		});
		s.ticks(20);
		// Herbert, and the tea.
		s.at(3258, 3384, 0);
		s.ticks(6);
		s.talk("Mage of Zamorak", 30, () -> s.varbit(13738, 15));
		s.ticks(15);
		s.at(3270, 3409, 0);
		s.ticks(4);
		s.talk("Tea Seller", 8, () -> s.bag(26903, 26904, 1929));
		s.ticks(20);
		s.at(3258, 3384, 0);
		s.ticks(4);
		s.dialogue("Mage of Zamorak");
		s.ticks(10);
		s.varbit(13738, 20);
		s.ticks(10);
		s.varbit(13738, 25);
		s.at(3039, 4832, 0);
		s.ticks(1);
		s.dialogue(false);
		s.ticks(6);
		// The bath, and the energies after it.
		s.dialogue("Dark Mage");
		s.ticks(10);
		s.varbit(13738, 30);
		s.ticks(15);
		s.varbit(13738, 35);
		s.ticks(1);
		s.dialogue(false);
		s.ticks(60);
		s.varbit(13738, 40);
		s.talk("Dark Mage", 10, () -> s.varbit(13738, 45));
		s.ticks(10);
		// Persten again, and Sedridor on the incantation.
		s.at(3284, 3231, 0);
		s.ticks(4);
		s.talk("Wizard Persten", 40, () -> s.varbit(13738, 60));
		s.at(3104, 9572, 0);
		s.ticks(6);
		s.dialogue("Archmage Sedridor");
		s.ticks(20);
		s.varbit(13738, 65);
		s.ticks(20);
		s.varbit(13738, 70);
		s.ticks(1);
		s.dialogue(false);
		s.ticks(20);
		// Traiborn's puzzle, and the answer.
		s.at(3111, 3162, 1);
		s.ticks(4);
		s.talk("Wizard Traiborn", 20, () -> s.varbit(13738, 75));
		s.ticks(40);
		s.varbit(13738, 80);
		s.talk("Wizard Traiborn", 15, () -> s.varbit(13738, 85));
		s.ticks(20);
		// The incantation, and the temple.
		s.at(3104, 9572, 0);
		s.ticks(4);
		s.talk("Wizard Persten", 10, () -> s.varbit(13738, 90));
		s.scene(true);
		s.ticks(40);
		s.varbit(13738, 95);
		s.ticks(20);
		s.varbit(13738, 100);
		s.scene(false);
		s.ticks(60);
		s.talk("Wizard Persten", 10, () -> s.varbit(13738, 105));
		s.scene(true);
		s.ticks(30);
		s.varbit(13738, 110);
		s.ticks(40);
		s.varbit(13738, 115);
		s.scene(false);
		s.ticks(120);
		s.scene(true);
		s.ticks(30);
		s.varbit(13738, 120);
		s.ticks(30);
		s.varbit(13738, 125);
		s.ticks(20);
		s.varbit(13738, 130);
		s.scene(false);
		s.questComplete(Quest.TEMPLE_OF_THE_EYE, "Temple of the Eye");
		s.ticks(200);
		s.summary();
		return s;
	}

	@Test
	public void templeOfTheEyeHasEachMomentOnceAndInOrder() throws Exception
	{
		for (long gap : GAPS)
		{
			for (boolean seen : new boolean[]{false, true})
			{
				QuestSim s = temple(gap, seen);
				assertEquals(s.transcript(), Arrays.asList(
					seen ? "qm-temple-of-the-eye-herbert-after-enter-the-abyss" : "qm-temple-of-the-eye-herbert",
					"qm-temple-of-the-eye-strong-tea",
					"qm-temple-of-the-eye-the-bath",
					seen ? "qm-temple-of-the-eye-written-on-flesh-after-rune-mysteries"
						: "qm-temple-of-the-eye-written-on-flesh",
					"qm-temple-of-the-eye-thingummywut",
					"qm-temple-of-the-eye-persten"), moments(s));
				assertTrue(s.transcript(), s.heardIds.contains("quest-complete"));
			}
		}
	}

	/** The tea moment waits for the cup: Herbert asking for it is a moment of its own. */
	@Test
	public void theStrongTeaWaitsForTheCup() throws Exception
	{
		QuestSim s = sim(6000);
		s.started(Quest.TEMPLE_OF_THE_EYE);
		s.bag(26903);
		s.at(3258, 3384, 0);
		s.ticks(4);
		s.talk("Mage of Zamorak", 20, () -> s.varbit(13738, 15));
		s.ticks(30);
		assertFalse("not before the cup\n" + s.transcript(), s.heardIds.contains("qm-temple-of-the-eye-strong-tea"));
		s.bag(26903, 26904);
		s.ticks(10);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-temple-of-the-eye-strong-tea"));
	}

	/** Persten's moment is grave: no contractions, as the death lines have none. */
	@Test
	public void perstensMomentKeepsTheGraveRegister() throws Exception
	{
		QuestSim s = sim(6000);
		SpeechRule persten = s.h.rule("qm-temple-of-the-eye-persten");
		for (String line : persten.say)
		{
			assertFalse(line, line.matches(".*\\w'\\w.*"));
		}
		assertTrue("a sad moment lowers the mood", persten.mood != null && persten.mood < 0);
	}
}
