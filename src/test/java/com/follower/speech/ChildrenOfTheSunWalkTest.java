package com.follower.speech;

import com.follower.sim.QuestSim;
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
 * Children of the Sun walked end to end on the shipped rules. Play test,
 * 15 Oct: the user heard nothing through the whole quest. QA, 15 Oct: the
 * quest's people were met by talking to them, and the moments held over a
 * scene were released into the conversation the scene ran on into, so their
 * words went to the dialogue hush. A runner lost the overheard moment,
 * Sergeant Tobyn and Prince Itzla; a walker lost Itzla.
 *
 * <p>Varbit 9632 walked through 0-2-4-6-8-10-12-14-16-18-20-22-24 as Quest
 * Helper's ChildrenOfTheSun.java (BSD, steps.put 0..22) and the wiki
 * transcript have it, at Quest Helper's WorldPoints: the delegation's scene,
 * the guard, the window's scene, Tobyn, the four, the escort box to the roof,
 * the Prince and the interrogation's scene. A walker pauses between steps; a
 * runner runs two tiles a tick and clicks straight on.
 */
public class ChildrenOfTheSunWalkTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int VB = 9632;

	/**
	 * @param runner   runs and clicks straight on, rather than pausing
	 * @param roofLag  ticks between the escort box closing and the Prince's conversation opening
	 */
	private QuestSim walk(boolean runner, int roofLag, long gap, boolean priorQuests) throws Exception
	{
		QuestSim s = new QuestSim(folder.newFolder().toPath(), gap);
		if (priorQuests)
		{
			// Witnessed: the callbacks remember them (QA pass 2, #31).
			s.witnessed(Quest.RUNE_MYSTERIES);
			s.witnessed(Quest.PRIEST_IN_PERIL);
		}
		s.started(Quest.CHILDREN_OF_THE_SUN);
		int speed = runner ? 2 : 1;

		// Into the square from the south road, and up to Alina.
		s.at(3212, 3400, 0);
		s.ticks(5);
		s.npc("Alina", 3225, 3426, 0);
		s.npc("Noah", 3226, 3427, 0);
		s.npc("Sergeant Tobyn", 3211, 3437, 0);
		s.walkTo(3224, 3425, 0, speed);
		s.dialogue(true);
		s.ticks(20);
		s.varbit(VB, 2);
		// The delegation's scene.
		s.scene(true);
		s.npc("King Roald", 3212, 3428, 0);
		s.npc("Prince Itzla Arkan", 3208, 3430, 0);
		s.ticks(10);
		s.varbit(VB, 4);
		s.ticks(40);
		s.varbit(VB, 6);
		s.removeNpc("King Roald");
		s.removeNpc("Prince Itzla Arkan");
		s.scene(false);
		s.tick();
		s.dialogue(false);

		// Tail the guard at walking pace, then the bandits' door.
		s.walkTo(3233, 3428, 0, 1);
		s.walkTo(3240, 3417, 0, 1);
		s.walkTo(3242, 3403, 0, 1);
		s.walkTo(3236, 3392, 0, 1);
		s.walkTo(3247, 3397, 0, 1);
		s.varbit(VB, 8);
		if (!runner)
		{
			s.ticks(8);
		}
		s.walkTo(3258, 3400, 0, speed);
		// The window's scene.
		s.dialogue(true);
		s.scene(true);
		s.ticks(45);
		s.varbit(VB, 10);
		s.scene(false);
		s.tick();
		s.dialogue(false);

		// Back to Sergeant Tobyn: a runner is there inside the quiet after the scene.
		if (!runner)
		{
			s.ticks(20);
		}
		s.walkTo(3212, 3436, 0, speed);
		s.talk(25, () -> s.varbit(VB, 12));

		// Mark the four.
		int[][] guards = {{3208, 3422}, {3221, 3430}, {3246, 3429}, {3237, 3427}};
		for (int[] g : guards)
		{
			s.walkTo(g[0], g[1] + 1, 0, speed);
			s.chat("You mark the guard.");
			s.ticks(2);
		}
		s.varbit(VB, 14);
		s.walkTo(3212, 3436, 0, speed);
		s.dialogue(true);
		s.ticks(15);
		s.chat("Sergeant Tobyn signals to his guards and the bandits are quickly arrested.");
		s.ticks(10);
		s.varbit(VB, 16);
		// The escort box: up on the palace roof, with the Prince there.
		s.removeNpc("Sergeant Tobyn");
		s.at(3205, 3471, 2);
		s.npc("Sergeant Tobyn", 3202, 3473, 2);
		s.npc("Prince Itzla Arkan", 3203, 3470, 2);
		s.ticks(3);
		s.dialogue(false);
		s.ticks(roofLag);
		// The Prince, and the interrogation's scene.
		s.dialogue(true);
		s.ticks(10);
		s.scene(true);
		s.varbit(VB, 18);
		s.ticks(60);
		s.varbit(VB, 20);
		s.ticks(40);
		s.varbit(VB, 22);
		s.ticks(20);
		s.removeNpc("Prince Itzla Arkan");
		s.scene(false);
		s.ticks(1);
		s.varbit(VB, 24);
		s.questComplete(Quest.CHILDREN_OF_THE_SUN, "Children of the Sun");
		s.tick();
		s.dialogue(false);
		s.ticks(60);
		s.summary();
		return s;
	}

	private static final List<String> PEOPLE_AND_MOMENTS = Arrays.asList(
		"quest-itzla", "quest-sergeant-tobyn", "qm-children-of-the-sun-overheard");

	private static void hearsEveryone(QuestSim s, String roof)
	{
		for (String id : PEOPLE_AND_MOMENTS)
		{
			assertTrue(id + " heard\n" + s.transcript(), s.heardIds.contains(id));
		}
		assertTrue(roof + " heard\n" + s.transcript(), s.heardIds.contains(roof));
		assertTrue("nothing dropped\n" + s.transcript(), s.lost.isEmpty());
		assertEquals("the quest's end too", 1, s.heardIds.stream().filter("quest-complete"::equals).count());
	}

	@Test
	public void aWalkerHearsThePeopleAndTheMoments() throws Exception
	{
		hearsEveryone(walk(false, 12, 6000, false), "qm-children-of-the-sun-the-roof");
	}

	@Test
	public void aRunnerHearsThemToo() throws Exception
	{
		// Tobyn's briefing is reached inside the quiet after the window's
		// scene: the overheard moment and his line are said after it.
		hearsEveryone(walk(true, 3, 6000, false), "qm-children-of-the-sun-the-roof");
	}

	@Test
	public void andWithTheCallbacks() throws Exception
	{
		hearsEveryone(walk(true, 3, 3000, true), "qm-children-of-the-sun-the-roof-after-priest-in-peril");
		hearsEveryone(walk(false, 12, 6000, true), "qm-children-of-the-sun-the-roof-after-priest-in-peril");
	}

	@Test
	public void theRoofIsSaidBeforeTheQuestioningAndItzlaWaitsForIt() throws Exception
	{
		// Both come up as the escort box closes. The moment is about now,
		// so it goes first; the Prince's line keeps, and is said after.
		QuestSim s = walk(true, 3, 6000, false);
		List<String> heard = s.heardIds;
		assertTrue(s.transcript(),
			heard.indexOf("qm-children-of-the-sun-the-roof") < heard.indexOf("quest-itzla"));
		assertTrue(s.transcript(), heard.indexOf("quest-itzla") > heard.indexOf("quest-complete"));
	}

	@Test
	public void aChainClickerLosesTheRoofCleanly() throws Exception
	{
		// The Prince clicked a tick after the escort box: the box never
		// really closes, so the roof moment never comes up, and it is not
		// said after the questioning either. Everyone else still is.
		QuestSim s = walk(true, 1, 6000, false);
		assertFalse(s.transcript(), s.heardIds.contains("qm-children-of-the-sun-the-roof"));
		for (String id : PEOPLE_AND_MOMENTS)
		{
			assertTrue(id + " heard\n" + s.transcript(), s.heardIds.contains(id));
		}
		assertTrue(s.transcript(), s.lost.isEmpty());
	}
}
