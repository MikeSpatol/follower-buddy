package com.follower;

import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shipped rules the fourth QA pass found wrong in their data.
 */
public class QaFourRuleDataTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private Harness shipped() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		h.game.at(3222, 3218, 0);
		ticks(h, 20);
		h.spoken.clear();
		return h;
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.game.cameraMoved();
			h.gameTick();
		}
	}

	/** Walks up to someone, stays half a minute, and walks off again. */
	private void visit(Harness h, String name)
	{
		h.game.moveNpc(h.game.spawnNpc(9001, name, 0), 3224, 3218, 0);
		ticks(h, 50);
		h.game.clearNpcs();
		ticks(h, 50);
	}

	/**
	 * Quests QA4 #14: "If he asks for flour, eggs and milk, act surprised."
	 * was said in Lumbridge every hour after the follower had seen Cook's
	 * Assistant done, and Father Aereck's "He'll want help with the restless
	 * sort." came back between the first and third hours after his callback.
	 * A person's plain line, about the quest, goes quiet once the follower
	 * has seen it done.
	 */
	@Test
	public void aPersonsPlainLineDoesNotOfferAQuestSeenDone() throws IOException
	{
		Harness h = shipped();
		visit(h, "Cook");
		visit(h, "Father Aereck");
		assertEquals("before the quest", 1, h.firedBy("quest-cook").size());
		assertEquals("before the quest", 1, h.firedBy("quest-father-aereck").size());

		h = shipped();
		long longAgo = java.time.LocalDate.now().toEpochDay() - 30;
		h.engine.getContext().setQuestWitness(q -> "COOKS_ASSISTANT".equals(q)
			|| "THE_RESTLESS_GHOST".equals(q) ? longAgo : null);
		for (int visit = 0; visit < 3; visit++)
		{
			visit(h, "Cook");
			visit(h, "Father Aereck");
			// Seventy-five minutes on, past the plain line's hour.
			ticks(h, 7500);
		}
		assertTrue("the cook, after Cook's Assistant", h.firedBy("quest-cook").isEmpty());
		assertTrue("Father Aereck, after The Restless Ghost", h.firedBy("quest-father-aereck").isEmpty());
		assertEquals("his callback instead", 1, h.firedBy("fun-father-aereck-after-the-restless-ghost").size());
	}
}
