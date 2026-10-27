package com.follower;

import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The hello is heard. Behaviour QA5, 16-20 Oct: the greeting was held by
 * the gap in 11 of 26 real logins, behind crowded-here, place-remembers or
 * the sketch invite, all said in the first few seconds.
 */
public class LoginGreetingHeardTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	@Test
	public void theHelloHoldsTheFloorUntilItIsSaid() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"greet\", \"group\": \"t\", \"cooldownMs\": 60000, \"delayTicks\": 8, \"hushMs\": 8000,"
			+ " \"when\": {\"type\": \"login\"}, \"say\": [\"Welcome back.\"]},"
			+ "{\"id\": \"crowd\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"when\": {\"type\": \"idle\", \"ticks\": 3}, \"say\": [\"Busy here, isn't it.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(45_000L);
		h.game.at(3164, 3487, 0);

		// The login as the plugin raises it: the baseline first, then the hello.
		h.engine.primeEdgesOnNextTick();
		h.engine.dispatchAfterPriming(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		ticks(h, 4);
		h.engine.remark("This place wants a sketch.");
		ticks(h, 12);

		assertEquals("the hello is said", 1, h.firedBy("greet").size());
		assertTrue("and nothing went before it", h.firedBy("crowd").isEmpty());
		assertEquals(1, h.spoken.size());
	}

	@Test
	public void everyLoginHelloHoldsTheFloorForItsDelay() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"login-greeting", "login-greeting-new", "login-greeting-settling",
			"away-a-while", "away-a-day", "away-a-long-time", "last-time"})
		{
			SpeechRule rule = h.rule(id);
			assertNotNull(id, rule.hushMs);
			int longest = rule.delayTicksMax != null ? rule.delayTicksMax : rule.delayTicks;
			assertTrue(id + " holds the floor until its line is due", rule.hushMs >= longest * 600);
		}
	}
}
