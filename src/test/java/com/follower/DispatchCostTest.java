package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * What one pass over the whole rule set costs.
 *
 * <p>Every rule is evaluated against every event, and the events are not rare:
 * a tick heartbeat, plus one per NPC entering or leaving the scene, which on a
 * chunk load is hundreds at once. This is not a benchmark to tune against - the
 * numbers move with the machine - but a tripwire for a change that makes the
 * pass pathologically slow, and a place to read the real figure from.
 */
public class DispatchCostTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aFullRulePassIsCheapEnoughToRunHundredsOfTimesATick() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		int rules = h.loader.getRules().size();

		// A busy scene: enough NPCs that the nearby scans have real work.
		for (int i = 0; i < 60; i++)
		{
			h.game.spawnNpc(3029 + i, "Goblin " + i, 5);
		}
		h.gameTicks(5);

		// Warm up, so the timing is of the code and not of the JIT.
		for (int i = 0; i < 200; i++)
		{
			h.engine.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		}
		h.clear();

		int passes = 2000;
		long start = System.nanoTime();
		for (int i = 0; i < passes; i++)
		{
			h.engine.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		}
		long spawnNanos = (System.nanoTime() - start) / passes;

		start = System.nanoTime();
		for (int i = 0; i < passes; i++)
		{
			h.engine.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_DESPAWN, 3029, "Goblin"));
		}
		long despawnNanos = (System.nanoTime() - start) / passes;

		start = System.nanoTime();
		for (int i = 0; i < passes; i++)
		{
			h.engine.dispatch(TriggerEvent.tick());
		}
		long tickNanos = (System.nanoTime() - start) / passes;

		System.out.printf("%n  rule pass over %d rules:%n"
				+ "    npc spawn   %6d ns%n"
				+ "    npc despawn %6d ns%n"
				+ "    tick        %6d ns%n%n",
			rules, spawnNanos, despawnNanos, tickNanos);

		// A game tick is 600ms and a chunk load can raise a few hundred spawns
		// inside one. Anything approaching a millisecond a pass would be felt.
		assertTrue("a single rule pass took " + spawnNanos + "ns, which would stall"
			+ " the client on a busy scene", spawnNanos < 1_000_000L);
	}
}
