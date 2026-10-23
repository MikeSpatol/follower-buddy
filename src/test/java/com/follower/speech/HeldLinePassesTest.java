package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A line kept waiting is said only if what it is about still stands.
 * Behaviour QA, 16 Oct: critical-hp met the gap behind low-hp and was said
 * 4-8 s later, after the player had eaten or teleported ("49 HP! Get out!"
 * at 70 percent), and a person seen during someone else's conversation, a
 * scene or its quiet was announced after they had gone.
 *
 * <p>When the line is let go, its health, prayer, poison or person is read
 * again. Passed, it goes unsaid and gives its cooldown back, so it rises
 * the next time it is true.
 */
public class HeldLinePassesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private final List<String> held = new ArrayList<>();

	private static final String LOW = "{\"id\": \"low\", \"group\": \"t\", \"occasion\": true, \"cooldownMs\": 15000,"
		+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 40}, \"say\": [\"Eat something.\"]}";

	private static final String CRITICAL = "{\"id\": \"critical\", \"group\": \"t\", \"occasion\": true,"
		+ " \"cooldownMs\": 8000, \"when\": {\"type\": \"healthBelow\", \"percent\": 20}, \"say\": [\"Get out!\"]}";

	private static final String HANS = "{\"id\": \"hans\", \"group\": \"t\", \"waits\": true, \"cooldownMs\": 3600000,"
		+ " \"when\": {\"type\": \"npcNearby\", \"within\": 5, \"names\": [\"Hans\"]}, \"say\": [\"There goes Hans.\"]}";

	private Harness harness(String... bodies) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + String.join(",", bodies) + "]}");
		return wire(h);
	}

	private Harness wire(Harness h)
	{
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		h.engine.setOnSuppressed((rule, why) -> held.add(rule.describe() + ":" + why));
		h.game.at(3222, 3218, 0);
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
	public void aWarningTheGapHeldIsLetGoOnceThePlayerHasEaten() throws IOException
	{
		// Probe P4: 37 percent, then 17, then a bite.
		Harness h = harness(LOW, CRITICAL);
		h.game.hitpoints(37, 100);
		ticks(h, 1);
		assertEquals(1, h.firedBy("low").size());

		h.game.hitpoints(17, 100);
		ticks(h, 2);
		h.game.hitpoints(60, 100);
		ticks(h, 20);
		assertTrue("not said at 60 percent: " + h.spoken, h.firedBy("critical").isEmpty());
		assertTrue(held.toString(), held.contains("critical:passed"));

		// Its cooldown given back: the next crossing, inside the old
		// cooldown's 8 s, is said.
		ms[0] += 1_000L;
		h.game.hitpoints(15, 100);
		ticks(h, 1);
		assertEquals("said on the next crossing", 1, h.firedBy("critical").size());
	}

	@Test
	public void aWarningStillTrueWhenTheGapEndsIsSaid() throws IOException
	{
		Harness h = harness(LOW, CRITICAL);
		h.game.hitpoints(37, 100);
		ticks(h, 1);
		h.game.hitpoints(17, 100);
		ticks(h, 2);
		assertTrue("waiting out the gap", h.firedBy("critical").isEmpty());
		ticks(h, 12);
		assertEquals("still at 17 percent, so said", 1, h.firedBy("critical").size());
	}

	@Test
	public void aPasserBySeenInSomeoneElsesConversationIsNotAnnouncedAfterHeHasGone() throws IOException
	{
		// Probe P2: Hans passes within 2 tiles during a 30 s conversation.
		Harness h = harness(HANS);
		NPC hans = h.game.spawnNpc(9001, "Hans", 0);
		h.game.moveNpc(hans, 3240, 3218, 0);
		h.engine.getContext().setDialogueOpen(true);
		ticks(h, 10);
		h.game.moveNpc(hans, 3224, 3218, 0);
		ticks(h, 4);
		h.game.moveNpc(hans, 3240, 3218, 0);
		ticks(h, 36);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 10);
		assertTrue("Hans is 18 tiles off: " + h.spoken, h.firedBy("hans").isEmpty());
		assertTrue(held.toString(), held.contains("hans:passed"));

		// And the next time he comes by, he is announced: the hour's
		// cooldown went back with the line.
		h.game.moveNpc(hans, 3224, 3218, 0);
		ticks(h, 2);
		assertEquals(1, h.firedBy("hans").size());
	}

	@Test
	public void aPersonStillThereAfterTheConversationIsAnnounced() throws IOException
	{
		Harness h = harness(HANS);
		NPC hans = h.game.spawnNpc(9001, "Hans", 0);
		h.engine.getContext().setDialogueOpen(true);
		h.game.moveNpc(hans, 3224, 3218, 0);
		ticks(h, 20);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 4);
		assertEquals(1, h.firedBy("hans").size());
	}

	@Test
	public void theShippedCriticalWarningIsLetGoAfterABite() throws IOException
	{
		// The shipped rules: low-hp at 37 percent, critical-hp a hit later,
		// then the player eats before the gap is out.
		Harness h = wire(new Harness(folder.newFolder().toPath()));
		ticks(h, 20);
		h.spoken.clear();
		h.game.hitpoints(26, 70);
		ticks(h, 1);
		assertEquals(h.spoken.toString(), 1, h.firedBy("low-hp").size());
		h.game.hitpoints(12, 70);
		ticks(h, 2);
		h.game.hitpoints(49, 70);
		ticks(h, 20);
		assertTrue("no 'Get out!' at 70 percent: " + h.spoken, h.firedBy("critical-hp").isEmpty());
		assertTrue(held.toString(), held.contains("critical-hp:passed"));
	}

	@Test
	public void aWarningAnsweredWhileItWaitedIsLetGo() throws IOException
	{
		// Probe P12: 60 damage to 14 percent just after another line, and
		// the player teleports 2 ticks later, still at 14. "Get out!" was
		// said after the teleport, and the advice was called ignored a
		// minute on. The advised act, played while it waited, answers it.
		Harness h = wire(new Harness(folder.newFolder().toPath()));
		ticks(h, 20);
		h.spoken.clear();
		h.engine.remark("That was a 60.");
		h.game.hitpoints(10, 70);
		ticks(h, 2);
		h.dispatch(TriggerEvent.animation(714));
		ticks(h, 20);
		assertTrue("not said after the teleport: " + h.spoken, h.firedBy("critical-hp").isEmpty());
		assertTrue(held.toString(), held.contains("critical-hp:passed"));
		assertTrue("no advice left open", !h.engine.getContext().isAdvising()); // public:cut side-games
	}
}
