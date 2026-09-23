package com.follower.chronicle;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A button press in the game lands on the next tick, and a second press
 * before then is ignored. The chronicle's tabs, rows and close button go
 * through this, so it has to behave exactly like that.
 */
public class TickGateTest
{
	@Test
	public void nothingHappensUntilTheTick()
	{
		TickGate gate = new TickGate();
		List<String> ran = new ArrayList<>();
		assertTrue(gate.offer(() -> ran.add("tab")));
		assertTrue("waiting on the server", gate.busy());
		assertTrue("not yet", ran.isEmpty());

		gate.tick();
		assertEquals("on the tick", 1, ran.size());
		assertFalse("and the gate is free again", gate.busy());
	}

	@Test
	public void aSecondPressWhileOneIsWaitingIsIgnored()
	{
		TickGate gate = new TickGate();
		List<String> ran = new ArrayList<>();
		assertTrue(gate.offer(() -> ran.add("first")));
		assertFalse("the game ignores a click while one is in flight", gate.offer(() -> ran.add("second")));
		gate.tick();
		assertEquals("only the first happened", List.of("first"), ran);
	}

	@Test
	public void theTickRunsItOnce()
	{
		TickGate gate = new TickGate();
		List<String> ran = new ArrayList<>();
		gate.offer(() -> ran.add("x"));
		gate.tick();
		gate.tick();
		gate.tick();
		assertEquals(1, ran.size());
	}

	@Test
	public void clearingDropsItWithoutRunning()
	{
		TickGate gate = new TickGate();
		List<String> ran = new ArrayList<>();
		gate.offer(() -> ran.add("x"));
		gate.clear();
		gate.tick();
		assertTrue(ran.isEmpty());
		assertFalse(gate.busy());
	}

	@Test
	public void anEmptyTickIsNothing()
	{
		TickGate gate = new TickGate();
		gate.tick();
		assertFalse(gate.busy());
		assertFalse("nothing to take", gate.offer(null));
	}
}
