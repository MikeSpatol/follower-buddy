package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * The ordinary cheer stays a cheer. Behaviour QA, 15 Oct: in a slayer task
 * it was the line heard most, about 80 an hour and 45% of everything said
 * in combat, and the rests it caused cost the kill, loot and bet lines.
 * Now about one in every minute and a half to two minutes of fighting.
 */
public class CombatCheerPacingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void halfAnHourOfHillGiantsHasAFewCheersNotForty() throws IOException
	{
		long[] ms = {1_700_000_000_000L};
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6_000L);
		h.game.at(3117, 9845, 0);
		h.game.hitpoints(70, 70);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.moveNpc(giant, 3119, 9845, 0);
		h.game.fighting(giant);
		for (int tick = 0; tick < 3000; tick++)
		{
			ms[0] += 600;
			if (tick % 4 == 0)
			{
				h.game.cameraMoved();
			}
			h.gameTick();
		}
		int cheers = h.firedBy("combat-cheer").size();
		assertTrue("cheers in 30 minutes: " + cheers, cheers >= 5 && cheers <= 20);
	}
}
