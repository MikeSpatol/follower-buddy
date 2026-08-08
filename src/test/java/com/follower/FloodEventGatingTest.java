package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NPCs entering and leaving the scene arrive in floods - a chunk load raises one
 * event per NPC in the new scene - and each one costs a pass over every rule.
 * The loader works out which of those floods anything is actually listening to,
 * so the plugin can skip the rest.
 */
public class FloodEventGatingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void theShippedRulesListenForSpawnsButNotDespawns() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());

		assertTrue("quest NPC reactions trigger on npcSpawn",
			h.loader.listensFor(TriggerEvent.Type.NPC_SPAWN));
		assertFalse("no shipped rule reacts to an NPC leaving, so every despawn"
				+ " on a chunk load is a rule pass for nothing",
			h.loader.listensFor(TriggerEvent.Type.NPC_DESPAWN));
		assertFalse("nor to raw varbit changes, which flood at login",
			h.loader.listensFor(TriggerEvent.Type.VARBIT));
	}

	@Test
	public void addingARuleThatListensTurnsTheFloodBackOn() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"gone\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"npcDespawn\", \"names\": [\"Goblin\"]},"
				+ " \"say\": [\"bye\"]}]}");

		assertTrue("a user rule must be able to switch its own flood on",
			h.loader.listensFor(TriggerEvent.Type.NPC_DESPAWN));
	}

	@Test
	public void aListenerNestedInsideACombinatorStillCounts() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"gone\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"all\", \"conditions\": ["
				+ "   {\"type\": \"healthBelow\", \"percent\": 50},"
				+ "   {\"type\": \"npcDespawn\", \"names\": [\"Goblin\"]}]},"
				+ " \"say\": [\"bye\"]}]}");

		assertTrue("the search has to walk into all/any/none blocks",
			h.loader.listensFor(TriggerEvent.Type.NPC_DESPAWN));
	}

	@Test
	public void everyOtherEventTypeIsAlwaysDelivered() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (TriggerEvent.Type type : TriggerEvent.Type.values())
		{
			if (type == TriggerEvent.Type.NPC_SPAWN
				|| type == TriggerEvent.Type.NPC_DESPAWN
				|| type == TriggerEvent.Type.VARBIT)
			{
				continue;
			}
			assertTrue("only the flood events may be gated; " + type
				+ " must always reach the rules", h.loader.listensFor(type));
		}
	}

	@Test
	public void gatingDoesNotStopAStateRuleFiringOnItsOwnTick() throws IOException
	{
		// The point of the gate is that nothing is lost: state only changes when
		// the snapshot refreshes, and the tick heartbeat always gets through.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"hurt\", \"group\": \"t\","
				+ " \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"ouch\"]}]}");

		assertFalse(h.loader.listensFor(TriggerEvent.Type.NPC_DESPAWN));

		h.game.hitpoints(10, 99);
		h.gameTicks(2);

		assertTrue("the tick still delivers it", !h.firedBy("hurt").isEmpty());
	}
}
