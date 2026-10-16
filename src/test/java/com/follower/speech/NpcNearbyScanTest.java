package com.follower.speech;

import com.follower.sim.FakeGame;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The npcNearby scan asks the cheap question first. Each of the shipped
 * npcNearby conditions scans the whole loaded scene once a tick, and its
 * predicate is a name match per pattern; asked of every NPC in a crowd that
 * was milliseconds a tick (perf audit, Oct 2026, #17). Distance rules out
 * almost all of them for nothing.
 */
public class NpcNearbyScanTest
{
	@Test
	public void onlyTheNpcsInRangeAreAskedAboutByName()
	{
		FakeGame game = new FakeGame().at(3222, 3218, 0);
		for (int i = 0; i < 50; i++)
		{
			game.moveNpc(game.spawnNpc(3000 + i, "Man", 2), 3222 + 30, 3218 + i % 10, 0);
		}
		game.moveNpc(game.spawnNpc(3100, "Goblin", 5), 3224, 3218, 0);
		TriggerContext context = new TriggerContext(game.client);
		context.refresh();

		int[] asked = {0};
		assertFalse(context.isNpcNearby(npc ->
		{
			asked[0]++;
			return false;
		}, 5));
		assertEquals("only the one in range", 1, asked[0]);

		// And the answer is the same as before.
		assertTrue(context.isNpcNearby(npc -> "Goblin".equals(npc.getName()), 5));
		assertFalse(context.isNpcNearby(npc -> "Man".equals(npc.getName()), 5));
		assertTrue(context.isNpcNearby(npc -> "Man".equals(npc.getName()), 30));
	}
}
