package com.follower.speech;

import com.follower.sim.QuestSim;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The quest walker itself, as quests QA4 (#33) found it: a direct line
 * queued behind another threw, and a logout lost every witness.
 */
public class QuestSimTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aDirectLineQueuedBehindAnotherIsNoted() throws Exception
	{
		QuestSim s = new QuestSim(folder.newFolder().toPath(), 0L);
		s.h.engine.force("idle-chatter");
		// The sketch prompt, said straight through the engine with no rule.
		s.h.engine.say("Hold still a moment.", SpeechOutput.OVERHEAD);
		s.ticks(20);
		assertTrue(s.transcript(), s.transcript().contains("queued: (direct)"));
		assertTrue(s.transcript(), s.heard.contains("Hold still a moment."));
	}

	@Test
	public void theWitnessOutlastsALogout() throws Exception
	{
		// The plugin hands the book to the engine, not to one context; the
		// walker does the same, so a logout's reset keeps it.
		QuestSim s = new QuestSim(folder.newFolder().toPath(), 6000L);
		s.witnessed(Quest.COOKS_ASSISTANT);
		s.h.engine.reset();
		assertNotNull(s.h.engine.getContext().questWitnessedOn("COOKS_ASSISTANT"));

		QuestSim t = new QuestSim(folder.newFolder().toPath(), 6000L);
		t.questComplete(Quest.RUNE_MYSTERIES, "Rune Mysteries");
		t.h.engine.reset();
		assertNotNull(t.h.engine.getContext().questWitnessedOn("RUNE_MYSTERIES"));
	}

	@Test
	public void aPageIsCountedSaidOnlyWhenALineSaysIt() throws Exception
	{
		// pagesSaid counted the news handed to the rules, said or not, which
		// hid Priest in Peril's two unmentioned pages.
		QuestSim s = new QuestSim(folder.newFolder().toPath(), 6000L);
		s.ticks(20);
		s.pageWritten("no-such-kind", "A page no rule mentions");
		s.ticks(150);
		assertTrue(s.transcript(), s.pagesSaid.isEmpty());
		assertTrue(s.transcript(), s.pagesDropped.contains("A page no rule mentions"));
	}

	@Test
	public void theScrollWritesEveryPageTheQuestOpens() throws Exception
	{
		QuestSim s = new QuestSim(folder.newFolder().toPath(), 6000L);
		s.questComplete(Quest.PRIEST_IN_PERIL, "Priest in Peril", "Seven Priestly Warriors", "Morytania");
		for (String page : new String[]{"Priest in Peril", "Seven Priestly Warriors", "Morytania"})
		{
			assertTrue(s.transcript(), s.transcript().contains("chronicle page written: " + page));
		}
	}
}
