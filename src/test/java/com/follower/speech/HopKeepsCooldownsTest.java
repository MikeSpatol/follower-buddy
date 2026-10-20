package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * A world hop forgets the edges, not what was said. Behaviour QA, 16 Oct
 * (probe P1): Callisto's intro, an hour's cooldown, was said again on the
 * next approach a minute after a hop, and crowded-here (45 minutes) again
 * after every GE hop.
 */
public class HopKeepsCooldownsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private Harness harness(long cooldownMs) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"boss\", \"group\": \"t\", \"occasion\": true,"
				+ " \"cooldownMs\": " + cooldownMs + ", \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"Bears don't get this big on their own.\"]}]}");
		h.engine.setClock(() -> ms[0]);
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
	public void aLineSaidBeforeAHopKeepsItsCooldownAfterIt() throws IOException
	{
		Harness h = harness(3_600_000L);
		h.game.hitpoints(10, 99);
		ticks(h, 1);
		assertEquals(1, h.firedBy("boss").size());
		h.game.hitpoints(99, 99);
		ticks(h, 60);

		h.engine.resetForNewScene();
		h.game.hitpoints(10, 99);
		ticks(h, 5);
		assertEquals("not again a minute later", 1, h.firedBy("boss").size());
	}

	@Test
	public void theEdgeIsStillForgotten() throws IOException
	{
		// What is true in the new world is news there: a condition true
		// through the hop rises again.
		Harness h = harness(0L);
		h.game.hitpoints(10, 99);
		ticks(h, 1);
		h.engine.resetForNewScene();
		ticks(h, 1);
		assertEquals(2, h.firedBy("boss").size());
	}

	@Test
	public void aLineWaitingAtTheHopIsNotLostForItsCooldown() throws IOException
	{
		// Behaviour QA4, 16 Oct (probe Q3): Tobyn's line was waiting its gap
		// when the player hopped, and was gone for the hour with him still
		// beside the player. Its cooldown is given back, so it rises again.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"tobyn\", \"group\": \"quest\", \"waits\": true,"
				+ " \"cooldownMs\": 3600000, \"when\": {\"type\": \"npcNearby\", \"within\": 5,"
				+ " \"names\": [\"Sergeant Tobyn\"]}, \"say\": [\"Sergeant Tobyn.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(45_000L);
		h.game.at(3222, 3218, 0);
		h.gameTicks(1);
		h.engine.remark("Hold still a moment.");
		ticks(h, 1);
		h.game.moveNpc(h.game.spawnNpc(12703, "Sergeant Tobyn", 0), 3224, 3218, 0);
		ticks(h, 5);
		assertEquals("waiting its gap", 0, h.firedBy("tobyn").size());

		// The hop as the plugin has it: the scene goes, and the new one's
		// baseline is taken (behaviour QA5: without the prime this passed
		// while the plugin never said the line).
		h.engine.resetForNewScene();
		h.engine.primeEdgesOnNextTick();
		ticks(h, 5);
		assertEquals("said after the hop, him still there", 1, h.firedBy("tobyn").size());
	}
}
