package com.follower;

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
}
