package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * Lines that come free together are said most important first. Quests QA,
 * 16 Oct: waiting lines were let go in the order they had won, so Prince
 * Itzla's line (priority 35) went ahead of the bag moment (63) it was held
 * with, and at Minimal later moments overtook earlier ones. And a line held
 * through a conversation kept its "beat after the box" until it next came
 * free, so it still went first after a gap had held it since.
 */
public class PendingOrderTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private static final String PERSON = "{\"id\": \"person\", \"group\": \"t\", \"priority\": 35, \"waits\": true,"
		+ " \"cooldownMs\": 0, \"when\": {\"type\": \"levelUp\", \"names\": [\"Attack\"]}, \"say\": [\"Prince Itzla.\"]}";

	private static final String MOMENT = "{\"id\": \"moment\", \"group\": \"t\", \"priority\": 63, \"occasion\": true,"
		+ " \"cooldownMs\": 0, \"when\": {\"type\": \"levelUp\", \"names\": [\"Strength\"]}, \"say\": [\"The bag.\"]}";

	private static final String CHATTY = "{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"Defence\"]}, \"say\": [\"Nice.\"]}";

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + PERSON + "," + MOMENT + "," + CHATTY + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
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
	public void twoHeldThroughOneBoxAreSaidMostImportantFirst() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().setDialogueOpen(true);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 5);
		h.dispatch(TriggerEvent.levelUp("Strength", 30));
		ticks(h, 5);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 30);
		assertEquals(2, h.spoken.size());
		assertEquals("the moment first", "moment", h.spoken.get(0).rule.id);
		assertEquals("person", h.spoken.get(1).rule.id);
	}

	private static final String ARRIVAL = "{\"id\": \"arrival\", \"group\": \"t\", \"priority\": 63,"
		+ " \"occasion\": true, \"cooldownMs\": 0, \"when\": {\"type\": \"levelUp\", \"names\": [\"Magic\"]},"
		+ " \"say\": [\"The tower.\"]}";

	@Test
	public void theBeatAfterTheBoxIsOnlyForTheTickItGoes() throws IOException
	{
		// The Rune Mysteries replay at 45 s: the tower's arrival, held
		// through Sedridor's talk, then held for the gap after a line said
		// as the box went, was said after the package moment that rose in
		// that gap. Level in priority, the earlier moment goes first.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + ARRIVAL + "," + MOMENT + "," + CHATTY + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		h.gameTicks(1);
		h.engine.getContext().setDialogueOpen(true);
		h.dispatch(TriggerEvent.levelUp("Magic", 30));
		ticks(h, 5);
		h.engine.getContext().setDialogueOpen(false);
		h.dispatch(TriggerEvent.levelUp("Defence", 30));
		assertEquals("chatty", h.spoken.get(0).rule.id);
		ticks(h, 3);
		h.dispatch(TriggerEvent.levelUp("Strength", 30));
		ticks(h, 30);
		assertEquals(3, h.spoken.size());
		assertEquals("arrival", h.spoken.get(1).rule.id);
		assertEquals("moment", h.spoken.get(2).rule.id);
	}

	private static final String SCROLL = "{\"id\": \"scroll\", \"group\": \"t\", \"priority\": 85,"
		+ " \"occasion\": true, \"cooldownMs\": 0, \"when\": {\"type\": \"questComplete\"},"
		+ " \"say\": [\"Done and witnessed.\"]}";

	@Test
	public void aMomentHeldThroughTheHandInGoesBeforeTheScrollsLine() throws IOException
	{
		// Quests QA4, 16 Oct: the moment won before the hand-in and was held
		// through it; the scroll's line, higher in priority, won in the
		// talk and went first, so the moment came after it as an
		// afterthought, or in another place. Occasions go in the order they
		// won, and the person's line after them both.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + PERSON + "," + MOMENT + "," + SCROLL + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		h.gameTicks(1);
		h.engine.getContext().setDialogueOpen(true);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 2);
		h.dispatch(TriggerEvent.levelUp("Strength", 30));
		ticks(h, 5);
		h.dispatch(TriggerEvent.questComplete("Witch's House"));
		ticks(h, 2);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 40);
		assertEquals(3, h.spoken.size());
		assertEquals("the moment, as it won first", "moment", h.spoken.get(0).rule.id);
		assertEquals("scroll", h.spoken.get(1).rule.id);
		assertEquals("person", h.spoken.get(2).rule.id);
	}
}
