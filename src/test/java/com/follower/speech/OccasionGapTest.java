package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An occasion waits at most eight seconds for the gap after another line.
 * Quests QA, 16 Oct: at Minimal (45 s, the user's own setting) every quest
 * moment waited the full gap behind any line, the sketch prompt included,
 * and landed a stage late, out of order or after the reward scroll. The
 * user's call: the moments break through; ordinary lines keep the gap.
 */
public class OccasionGapTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private final List<String> held = new ArrayList<>();

	private static final String CHATTY = "{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"*\"]}, \"say\": [\"Nice.\"]}";

	private static final String QUEST = "{\"id\": \"quest\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"occasion\": true, \"when\": {\"type\": \"questComplete\"}, \"say\": [\"Done and witnessed.\"]}";

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + CHATTY + "," + QUEST + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(45_000L);
		h.engine.setOnSuppressed((rule, why) -> held.add(rule.describe() + ":" + why));
		h.gameTicks(1);
		return h;
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	@Test
	public void atMinimalAnOccasionWaitsEightSecondsNotTheGap() throws IOException
	{
		Harness h = harness();
		// The sketch prompt: a remark of the follower's own.
		h.engine.remark("Hold still a moment.");
		ticks(h, 1);
		h.dispatch(TriggerEvent.questComplete("Rune Mysteries"));
		ticks(h, 12);
		assertTrue("not inside eight seconds", h.firedBy("quest").isEmpty());
		ticks(h, 2);
		assertEquals("said once eight seconds are out", 1, h.firedBy("quest").size());
	}

	@Test
	public void ordinaryLinesStillKeepTheWholeGap() throws IOException
	{
		Harness h = harness();
		h.dispatch(TriggerEvent.questComplete("Rune Mysteries"));
		assertEquals(1, h.firedBy("quest").size());
		ticks(h, 20);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		assertTrue("12 s after a line, at Minimal", h.firedBy("chatty").isEmpty());
		assertEquals("chatty:gap", held.get(0));
		ticks(h, 60);
		h.dispatch(TriggerEvent.levelUp("Attack", 31));
		assertEquals("48 s after it", 1, h.firedBy("chatty").size());
	}
}
