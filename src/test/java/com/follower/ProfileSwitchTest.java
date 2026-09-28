package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A RuneLite profile switch: the old profile's session goes, and the new
 * one's memory is read. What the switch does to the engine.
 */
public class ProfileSwitchTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String LOGIN_RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"greet\", \"group\": \"t\", \"cooldownMs\": 0, \"delayTicks\": 3,"
		+ " \"when\": {\"type\": \"login\"}, \"say\": [\"there you are\"]},"
		+ "{\"id\": \"update\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"gameUpdated\"}, \"say\": [\"the world moved\"]}]}";

	/** Stands in for the gear worn and the place stood in: true all along. */
	private static final String WORLD_RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"gear\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"always\"}, \"say\": [\"nice hat\"]}]}";

	private Harness harness(String rules) throws IOException
	{
		return new Harness(folder.newFolder().toPath(), rules);
	}

	/** The engine's half of the switch, in the plugin's order. */
	private static void switchProfile(Harness h, boolean loginComing)
	{
		h.engine.reset();
		if (FollowerPlugin.switchTakesTheBaseline(loginComing))
		{
			h.engine.primeEdgesOnNextTick();
		}
	}

	@Test
	public void aSwitchAtTheLoginScreenLeavesTheBaselineToTheLogin() throws IOException
	{
		// Primed at the login screen, the window had no ticks to run it
		// down there, and was still open when the login said hello: the
		// greeting was discarded with everything else, and so was the week's
		// update line, which is marked as told straight after.
		Harness h = harness(LOGIN_RULES);
		h.engine.reset();
		switchProfile(h, true);

		// The login, as the plugin does it: the hello, the news, then the
		// login's own baseline.
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.GAME_UPDATE));
		h.engine.primeEdgesOnNextTick();
		h.gameTicks(8);

		assertEquals("the login after the switch is greeted", 1, h.firedBy("greet").size());
		assertEquals("and told the game changed", 1, h.firedBy("update").size());
	}

	@Test
	public void aSwitchInTheWorldTakesTheBaselineItself() throws IOException
	{
		// No login is coming to do it. The reset clears every edge, and
		// without the window the gear worn all along rose as news.
		Harness h = harness(WORLD_RULES);
		h.gameTicks(3);
		assertEquals("the stand-in speaks once, when first seen", 1, h.firedBy("gear").size());
		h.clear();

		switchProfile(h, false);
		h.gameTicks(6);

		assertTrue("nothing that was true before the switch is news after it",
			h.firedBy("gear").isEmpty());
	}
}
