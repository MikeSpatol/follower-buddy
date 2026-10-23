package com.follower.speech;

import com.follower.sim.QuestSim;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Quest moments on the shipped rules, at the steps QA found them lost
 * (15 Oct). Stage values from Quest Helper (BSD): PriestInPeril.java
 * steps.put 4-8, RuneMysteries.java steps.put 3-5.
 */
public class QuestStepWalksTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private QuestSim sim() throws Exception
	{
		return new QuestSim(folder.newFolder().toPath(), 6000);
	}

	/**
	 * The current Priest in Peril has Drezel met first (302 goes 4 to 5) and
	 * the hooded monk killed for the key after, as the wiki's quick guide
	 * has it; Quest Helper's killMonk is a step at both 4 and 5.
	 */
	@Test
	public void theMonksKeyIsMarkedWhenDrezelIsMetFirst() throws Exception
	{
		QuestSim s = sim();
		s.started(Quest.PRIEST_IN_PERIL);
		s.varp(302, 4);
		s.at(3416, 3490, 2);
		s.ticks(4);
		s.talk(40, () -> s.varp(302, 5));
		s.ticks(6);
		s.at(3410, 3488, 0);
		s.ticks(3);
		s.event(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Monk of Zamorak"), "fight hooded monk"); // public:cut spectate
		s.ticks(20);
		s.event(TriggerEvent.kill(528, "Monk of Zamorak", 30), "kill hooded monk");
		s.ticks(2);
		s.bag(1925, 2944);
		s.ticks(20);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-priest-in-peril-the-monk-falls"));
	}

	/** The water poured, and Drezel clicked at once: the coffin's line is said after him. */
	@Test
	public void theCoffinIsMarkedWhenDrezelIsClickedAtOnce() throws Exception
	{
		QuestSim s = sim();
		s.started(Quest.PRIEST_IN_PERIL);
		s.varp(302, 6);
		s.at(3416, 3490, 2);
		s.ticks(10);
		s.varp(302, 7);
		s.ticks(1);
		s.talk(15, () -> s.varp(302, 8));
		s.ticks(20);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-priest-in-peril-the-coffin-sealed"));
		assertTrue(s.transcript(), s.lost.isEmpty());
	}

	/**
	 * Rune Mysteries, with the mugger outside only as Aubury's conversation
	 * ends: the notes are in hand, and the mugger's line is about the
	 * package. It took the pass and the notes were never mentioned.
	 */
	@Test
	public void theNotesAreMarkedWithTheMuggerOutside() throws Exception
	{
		QuestSim s = sim();
		s.started(Quest.RUNE_MYSTERIES);
		s.varp(63, 3);
		s.at(3240, 3400, 0);
		s.ticks(4);
		s.walkTo(3252, 3401, 0, 2);
		s.npc("Aubury", 3253, 3402, 0);
		s.ticks(1);
		s.talk(30, () -> s.varp(63, 4));
		s.talk(15, () ->
		{
			s.varp(63, 5);
			s.varbit(13725, 1);
		});
		s.npc("Mugger", 3255, 3397, 0);
		s.ticks(10);
		s.walkTo(3256, 3398, 0, 1);
		s.ticks(10);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-rune-mysteries-notes-in-hand"));
		assertFalse(s.transcript(), s.heardIds.contains("qm-rune-mysteries-mugger-street"));
	}

	/** On the way in with the package, the mugger is still marked. */
	@Test
	public void theMuggerIsMarkedOnThePackagesWayIn() throws Exception
	{
		QuestSim s = sim();
		s.started(Quest.RUNE_MYSTERIES);
		s.varp(63, 3);
		s.at(3240, 3400, 0);
		s.ticks(4);
		s.npc("Mugger", 3255, 3397, 0);
		s.walkTo(3253, 3399, 0, 1);
		s.ticks(10);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-rune-mysteries-mugger-street"));
	}

	/** The line a rule was said with, from the transcript. */
	private static String saidLine(QuestSim s, String id)
	{
		for (String line : s.transcript().split("\n"))
		{
			if (line.contains("SAYS [" + id + "]"))
			{
				return line;
			}
		}
		return "";
	}

	/**
	 * Flour left in the mill's bin from before the quest: the moment waits
	 * for the mill, rather than going off in the castle kitchen as the cook
	 * starts the quest (QA pass 2). Quest Helper's CooksAssistant.java (BSD)
	 * millSecond and millThird zones, 3162-3171 by 3302-3311.
	 */
	@Test
	public void flourLeftInTheBinIsMarkedAtTheMillNotInTheKitchen() throws Exception
	{
		QuestSim s = sim();
		s.varbit(4920, 3);
		s.at(3208, 3214, 0);
		s.ticks(4);
		s.started(Quest.COOKS_ASSISTANT);
		s.talk(10, () -> s.varp(29, 1));
		s.ticks(20);
		assertFalse("not in the kitchen\n" + s.transcript(),
			s.heardIds.contains("qm-cooks-assistant-flour-in-bin"));
		s.at(3166, 3306, 0);
		s.ticks(10);
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-cooks-assistant-flour-in-bin"));

		// And the usual way: the lever pulled on the top floor.
		QuestSim lever = sim();
		lever.started(Quest.COOKS_ASSISTANT);
		lever.varp(29, 1);
		lever.at(3166, 3306, 2);
		lever.ticks(20);
		lever.varbit(4920, 1);
		lever.ticks(10);
		lever.summary();
		assertTrue(lever.transcript(), lever.heardIds.contains("qm-cooks-assistant-flour-in-bin"));
	}

	/**
	 * Children of the Sun at 16 but on the ground, at the palace gate: the
	 * roof moment waits for the roof, Quest Helper's castleF2 zone
	 * (ChildrenOfTheSun.java, BSD). It had all of region 12854 at any
	 * plane (QA pass 2). The callback winces rather than looks pleased.
	 */
	@Test
	public void theRoofMomentWaitsForTheRoof() throws Exception
	{
		for (boolean priestInPeril : new boolean[]{false, true})
		{
			QuestSim s = sim();
			if (priestInPeril)
			{
				s.witnessed(Quest.PRIEST_IN_PERIL);
			}
			String id = priestInPeril
				? "qm-children-of-the-sun-the-roof-after-priest-in-peril" : "qm-children-of-the-sun-the-roof";
			s.started(Quest.CHILDREN_OF_THE_SUN);
			s.varbit(9632, 16);
			s.at(3212, 3466, 0);
			s.ticks(15);
			assertFalse("not at the gate\n" + s.transcript(), s.heardIds.contains(id));
			s.at(3205, 3471, 2);
			s.ticks(10);
			s.summary();
			assertTrue(s.transcript(), s.heardIds.contains(id));
			if (priestInPeril)
			{
				assertTrue(saidLine(s, id), saidLine(s, id).contains("anim=13994"));
				assertFalse(saidLine(s, id), saidLine(s, id).contains("mood="));
			}
		}
	}

	/**
	 * X Marks the Spot dug through stages 3-6 as Quest Helper's
	 * XMarksTheSpot.java (BSD) has them. Four moments in about two minutes
	 * was the densest stretch of any quest (QA pass 2): the orb, a short
	 * walk after hole one, is left out. The casket is uneasy, not pleased.
	 */
	@Test
	public void xMarksTheSpotKeepsItsHolesAndUneasyCasket() throws Exception
	{
		QuestSim s = sim();
		s.started(Quest.X_MARKS_THE_SPOT);
		s.at(3230, 3210, 0);
		for (int stage = 3; stage <= 6; stage++)
		{
			s.varbit(8063, stage);
			s.ticks(30);
		}
		s.summary();
		assertTrue(s.transcript(), s.heardIds.contains("qm-x-marks-the-spot-first-hole"));
		assertFalse(s.transcript(), s.heardIds.contains("qm-x-marks-the-spot-the-orb"));
		assertTrue(s.transcript(), s.heardIds.contains("qm-x-marks-the-spot-the-cipher"));
		String casket = saidLine(s, "qm-x-marks-the-spot-the-casket");
		assertTrue(s.transcript(), casket.contains("anim=13994"));
		assertFalse(casket, casket.contains("mood="));
	}
}
