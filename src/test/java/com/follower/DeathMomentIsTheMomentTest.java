package com.follower;

import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The line for the moment of death is said at the moment. Text QA5, 19 Oct:
 * critical-hp at 13:55:05 in the Abyss, and "I hate this. I hate watching
 * this." at 13:55:13 - in Lumbridge, after the respawn, the occasion having
 * waited out the gap behind the warning.
 */
public class DeathMomentIsTheMomentTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	@Test
	public void itTakesTheFloorRatherThanWaitingTheGap() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"critical-hp\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"Attack\"]}, \"say\": [\"Eat something!\"]},"
			+ "{\"id\": \"death-moment\", \"group\": \"t\", \"occasion\": true, \"priority\": 90, \"cooldownMs\": 60000,"
			+ " \"hushMs\": 2000, \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"Please get up.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(45_000L);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ms[0] += 1_200L;
		h.dispatch(TriggerEvent.death());
		assertEquals("said as it happens, not 8 s on", 1, h.firedBy("death-moment").size());
	}

	@Test
	public void theShippedLineTakesTheFloor() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		assertNotNull(h.rule("death-moment").hushMs);
	}
}
