package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R16: the voice grows familiar with time known.
 *
 * <p>The recurring touchpoints - the login hello and the level-up - carry
 * era-gated siblings: formal on days nought to two, thawing through the
 * first week, and the familiar register from day seven. The formality is in
 * the grammar rather than announced, so what these tests can hold is the
 * gating: the right era answers, and only that one.
 */
public class VoiceGrowsFamiliarTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The bundled rules, met daysAgo days ago, past the arrival arc. */
	private Harness known(int daysAgo) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		// Enough sessions that the one-time arrival arc stays out of the way;
		// the eras run on days known, not on session count.
		h.engine.getContext().setSessionCount(5);
		h.engine.getContext().setMetOnDay(LocalDate.now().toEpochDay() - daysAgo);
		h.gameTicks(1);
		return h;
	}

	private void expectGreeting(int daysAgo, String expected) throws IOException
	{
		Harness h = known(daysAgo);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(12);      // the hello rides eight delay ticks

		for (String era : new String[]{
			"login-greeting-new", "login-greeting-settling", "login-greeting"})
		{
			if (era.equals(expected))
			{
				assertEquals(daysAgo + " days in, the hello should be " + era,
					1, h.firedBy(era).size());
			}
			else
			{
				assertTrue(daysAgo + " days in, " + era + " is not this era's voice",
					h.firedBy(era).isEmpty());
			}
		}
	}

	@Test
	public void aBrandNewFollowerIsPolite() throws IOException
	{
		expectGreeting(0, "login-greeting-new");
	}

	@Test
	public void theThawSetsInMidWeek() throws IOException
	{
		expectGreeting(4, "login-greeting-settling");
	}

	@Test
	public void aWeekInTheHelloIsFamiliar() throws IOException
	{
		expectGreeting(10, "login-greeting");
	}

	@Test
	public void congratulationsGrowFamiliarTheSameWay() throws IOException
	{
		Harness young = known(0);
		young.dispatch(TriggerEvent.levelUp("Cooking", 34));
		young.gameTicks(3);
		assertEquals("day nought congratulates formally",
			1, young.firedBy("level-up-new").size());
		assertTrue(young.firedBy("level-up").isEmpty());

		Harness old = known(10);
		old.dispatch(TriggerEvent.levelUp("Cooking", 34));
		old.gameTicks(3);
		assertEquals("ten days in it talks like itself",
			1, old.firedBy("level-up").size());
		assertTrue(old.firedBy("level-up-new").isEmpty());
	}
}
