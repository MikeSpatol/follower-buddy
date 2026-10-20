package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * A logout ends the session, not what was said in it. Text QA5, 17 Oct:
 * the fence book (two hours' cooldown) was said at 14:21 and again at 14:33,
 * either side of a relog; 18 Oct, the nickname was asked at 15:39 and 15:50
 * the same way.
 */
public class CooldownsAcrossLogoutTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"book\", \"group\": \"t\", \"cooldownMs\": 7200000,"
				+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"Thieving\"]}, \"say\": [\"The fence book.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.loggedInAs(1L);
		h.gameTicks(1);
		return h;
	}

	private void sayIt(Harness h)
	{
		h.dispatch(TriggerEvent.levelUp("Thieving", 50));
		h.gameTicks(1);
	}

	@Test
	public void aCooldownHoldsThroughARelog() throws IOException
	{
		Harness h = harness();
		sayIt(h);
		ms[0] += 12 * 60_000L;
		h.engine.reset();
		h.engine.loggedInAs(1L);
		sayIt(h);
		assertEquals("not again twelve minutes on, across the relog", 1, h.firedBy("book").size());

		ms[0] += 2 * 3_600_000L;
		sayIt(h);
		assertEquals("its own while still runs out", 2, h.firedBy("book").size());
	}

	@Test
	public void anotherAccountStartsOver() throws IOException
	{
		Harness h = harness();
		sayIt(h);
		h.engine.reset();
		h.engine.loggedInAs(2L);
		sayIt(h);
		assertEquals(2, h.firedBy("book").size());
	}

	@Test
	public void forgettingStartsOver() throws IOException
	{
		// A profile switch, or Forget everything: another follower's memory.
		Harness h = harness();
		sayIt(h);
		h.engine.forget();
		sayIt(h);
		assertEquals(2, h.firedBy("book").size());
	}
}
