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
 * A boss gets its own send-off, not the one every fight gets.
 *
 * <p>combat-end and kill-boss-celebrate are different EVENTS, so priority
 * never compares them and both used to fire. The celebration also carries a
 * deliberate 13 to 16 tick delay so the ward can wind down first, which meant
 * a boss was seen off with "Well fought" and then congratulated properly eight
 * seconds later, in that order. The generic line now stands aside.
 */
public class BossGetsItsOwnSendOffTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness fightWith(String name) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setBossNames(h.loader.getBossNames());
		h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_END, name));
		h.gameTicks(3);
		return h;
	}

	@Test
	public void anOrdinaryFightStillGetsTheGenericLine() throws IOException
	{
		assertEquals("the send-off every fight gets",
			1, fightWith("Guard").firedBy("combat-end").size());
	}

	@Test
	public void aBossDoesNotGetTheGenericLine() throws IOException
	{
		assertTrue("a boss has its own send-off and does not want talking over",
			fightWith("Brutus").firedBy("combat-end").isEmpty());
	}

	@Test
	public void theBossListIsWhatDecidesIt() throws IOException
	{
		// Named in a boss rule, so it counts however small it fights.
		assertTrue(fightWith("Vorkath").firedBy("combat-end").isEmpty());
		// Not named anywhere, so it is an ordinary fight.
		assertEquals(1, fightWith("Giant rat").firedBy("combat-end").size());
	}
}
