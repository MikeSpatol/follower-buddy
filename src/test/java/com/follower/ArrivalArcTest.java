package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The first twenty minutes, which decide whether there is a second session.
 *
 * <p>Almost everything the follower has to say about YOU is gated behind a
 * history it has not got yet, so a new player meets the encyclopaedia rather
 * than the character. Four one-time lines cover the gap, and they are the only
 * content in the file that a player can miss permanently by the plugin getting
 * it wrong.
 *
 * <p>Which is not hypothetical. The first draft of two of these rules used an
 * "all" shorthand the condition parser does not have; they loaded, evaluated
 * false forever, and would have shipped as four lines nobody ever heard. Hence
 * a test that fires each one for real rather than reading the file.
 */
public class ArrivalArcTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The bundled rules, with the follower's memory set to a given history. */
	private Harness known(int sessions, int daysAgo) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		TriggerContext context = h.engine.getContext();
		context.setSessionCount(sessions);
		if (daysAgo >= 0)
		{
			context.setMetOnDay(LocalDate.now().toEpochDay() - daysAgo);
		}
		h.gameTicks(1);
		return h;
	}

	/** Waits out the delayTicks the login lines carry, so the words come out. */
	private static void settle(Harness h)
	{
		h.gameTicks(10);
	}

	/**
	 * Runs the session clock forward a minute at a time, as the plugin's own
	 * timer does. Jumping it straight to the number a rule wants would step
	 * over every window on the way and prove nothing about the sequence.
	 */
	private static void minutesPass(Harness h, int upTo)
	{
		for (int minute = h.engine.getContext().getSessionMinutes() + 1; minute <= upTo; minute++)
		{
			h.engine.getContext().setSessionMinutes(minute);
			h.gameTicks(1);
		}
	}

	private static com.follower.speech.Condition sessionMinutesWindow(Harness h, String ruleId)
	{
		for (com.follower.speech.Condition part : h.rule(ruleId).when.conditions)
		{
			if ("sessionMinutes".equalsIgnoreCase(part.type))
			{
				return part;
			}
		}
		throw new AssertionError(ruleId + " no longer sits on the session clock");
	}

	// ----------------------------------------------------------- the first hello

	@Test
	public void theVeryFirstLoginIsNotTheEverydayGreeting() throws IOException
	{
		Harness h = known(1, 0);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);

		assertEquals("the first login has its own hello", 1, h.firedBy("first-meeting").size());
		assertTrue("and the everyday one stays out of its way",
			h.firedBy("login-greeting").isEmpty());
	}

	@Test
	public void theSecondLoginIsTheEverydayGreetingAgain() throws IOException
	{
		// Same day, so this is a relog rather than a return - the arc's last
		// beat is about coming back TOMORROW and must not fire here.
		Harness h = known(2, 0);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);

		assertTrue(h.firedBy("first-meeting").isEmpty());
		assertTrue(h.firedBy("first-return").isEmpty());
		assertEquals(1, h.firedBy("login-greeting").size());
	}

	@Test
	public void comingBackTheNextDayIsWorthSaying() throws IOException
	{
		Harness h = known(2, 1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);

		assertEquals("twice is how a habit starts", 1, h.firedBy("first-return").size());
		assertTrue(h.firedBy("login-greeting").isEmpty());
	}

	@Test
	public void aLongStandingPlayerGetsNoneOfIt() throws IOException
	{
		Harness h = known(400, 300);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);
		minutesPass(h, 90);

		for (String id : new String[]{"first-meeting", "first-page", "first-hour", "first-return"})
		{
			assertTrue(id + " is for a follower that has just met you",
				h.firedBy(id).isEmpty());
		}
	}

	// -------------------------------------------------------- inside the session

	@Test
	public void tenMinutesInItWritesSomethingDown() throws IOException
	{
		// Scheduled on purpose. Left to the gates that already exist, a new
		// player's first characterful moment waits on a tally, a record or a
		// place score - none of which they have.
		Harness h = known(1, 0);
		minutesPass(h, 9);
		assertTrue("not yet", h.firedBy("first-page").isEmpty());

		minutesPass(h, 10);
		assertEquals(1, h.firedBy("first-page").size());
	}

	@Test
	public void anHourInItAdmitsItHasNothingOnYou() throws IOException
	{
		Harness h = known(1, 0);
		minutesPass(h, 45);

		assertEquals(1, h.firedBy("first-hour").size());
	}

	@Test
	public void theTwoInSessionLinesCannotBothWinTheSameTick() throws IOException
	{
		// They sit on the same clock, and a rule that loses an evaluation pass
		// has still had its rising edge consumed by it - so an overlap is not a
		// line that arrives late, it is a line that never arrives at all. The
		// windows are made exclusive rather than trusted to arrive in order.
		Harness h = known(1, 0);
		assertTrue("the earlier beat must close before the later one opens",
			sessionMinutesWindow(h, "first-page").maximum != null);

		minutesPass(h, 60);
		assertEquals(1, h.firedBy("first-page").size());
		assertEquals(1, h.firedBy("first-hour").size());
	}

	@Test
	public void theFirstDaysLinesStopBeingTrueAfterTheFirstDays() throws IOException
	{
		Harness h = known(9, 30);
		minutesPass(h, 60);

		assertTrue(h.firedBy("first-page").isEmpty());
		assertTrue(h.firedBy("first-hour").isEmpty());
	}

	// ------------------------------------------------------------- and only once

	@Test
	public void noneOfItHappensTwice() throws IOException
	{
		Harness h = known(1, 0);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);
		minutesPass(h, 45);

		assertEquals(1, h.firedBy("first-meeting").size());
		assertEquals(1, h.firedBy("first-page").size());
		assertEquals(1, h.firedBy("first-hour").size());

		// A world hop, which resets every rule's edge state and would re-raise
		// each of these conditions from scratch.
		h.engine.resetForNewScene();
		h.clear();
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		settle(h);
		h.gameTicks(2);

		for (String id : new String[]{"first-meeting", "first-page", "first-hour"})
		{
			assertTrue(id + " said itself a second time", h.firedBy(id).isEmpty());
		}
	}

	@Test
	public void everyArrivalLineIsMarkedBothOnceAndAnOccasion() throws IOException
	{
		// The two go together here and nowhere else. Missing "once" turns the
		// introduction into a catchphrase; missing "occasion" lets the director
		// swallow the one hearing the player was ever going to get.
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"first-meeting", "first-page", "first-hour", "first-return"})
		{
			assertTrue(id + " must be said only once", h.rule(id).isOnce());
			assertTrue(id + " must survive a relax period", h.rule(id).isOccasion());
			assertFalse(id + " must actually say something", h.rule(id).say.isEmpty());
		}
	}
}
