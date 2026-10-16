package com.follower.speech;

import com.follower.sim.QuestSim;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * Quest moments on the shipped rules, at the steps QA found them lost
 * (15 Oct). Stage values from Quest Helper (BSD): PriestInPeril.java
 * steps.put 6-8.
 */
public class QuestStepWalksTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private QuestSim sim() throws Exception
	{
		return new QuestSim(folder.newFolder().toPath(), 6000);
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
}
