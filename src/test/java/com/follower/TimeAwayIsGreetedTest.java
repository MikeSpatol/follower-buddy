package com.follower;

import java.io.IOException;
import java.time.LocalDate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The hello after an absence. The away lines and last-time all sit on the
 * login event, and one event has one winner: while the everyday greeting
 * matched every login at a higher priority they lost every pass and never
 * said a word.
 */
public class TimeAwayIsGreetedTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A login after minutesAway, daysAgo days in, past the arrival arc and off the milestones. */
	private Harness login(int daysAgo, long minutesAway, String lastTime) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setSessionCount(20);
		h.engine.getContext().setMetOnDay(LocalDate.now().toEpochDay() - daysAgo);
		h.engine.getContext().setMinutesAway(minutesAway);
		h.engine.getContext().restoreLastThing(lastTime);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(12);      // the slowest of them rides ten delay ticks
		return h;
	}

	private static int hellos(Harness h)
	{
		int n = 0;
		for (String id : new String[]{"login-greeting", "away-a-while", "last-time",
			"away-a-day", "away-a-long-time"})
		{
			n += h.firedBy(id).size();
		}
		return n;
	}

	@Test
	public void aFewHoursAwayIsSaidInsteadOfTheHello() throws IOException
	{
		Harness h = login(10, 300, "");
		assertEquals(1, h.firedBy("away-a-while").size());
		assertTrue(h.firedBy("login-greeting").isEmpty());
	}

	@Test
	public void aDayAwayAndAWeekAwayHaveTheirOwn() throws IOException
	{
		assertEquals(1, login(10, 2000, "").firedBy("away-a-day").size());
		assertEquals(1, login(10, 20000, "").firedBy("away-a-long-time").size());
	}

	@Test
	public void lastTimeGetsItsShare() throws IOException
	{
		// The rule rolls a chance, so give it a fair number of logins.
		boolean heard = false;
		for (int i = 0; i < 30 && !heard; i++)
		{
			heard = !login(10, 300, "the woodcutting").firedBy("last-time").isEmpty();
		}
		assertTrue("last time is remembered some of the time", heard);
	}

	/**
	 * QA, 15 Oct: at 71, last-time lost every absence of a day or more to
	 * away-a-day (74) and away-a-long-time (78). It sits over them now, and
	 * its own chance roll is what leaves them their share.
	 */
	@Test
	public void lastTimeIsAlsoRememberedAfterDaysAway() throws IOException
	{
		for (long away : new long[]{2000, 20000})
		{
			boolean heard = false;
			boolean plain = false;
			for (int i = 0; i < 40 && !(heard && plain); i++)
			{
				Harness h = login(10, away, "the woodcutting");
				heard |= !h.firedBy("last-time").isEmpty();
				plain |= !h.firedBy(away < 10080 ? "away-a-day" : "away-a-long-time").isEmpty();
			}
			assertTrue("away " + away + ": last time is remembered some of the time", heard);
			assertTrue("away " + away + ": and the away line keeps a share", plain);
		}
	}

	/**
	 * QA, 15 Oct (MikeS: yes): an hour to three away belonged to the everyday
	 * hello, which outranks last-time, so last-time never had them. With
	 * something to remember, last-time has every one of them now.
	 */
	@Test
	public void anHourOrTwoAwayIsRememberedEveryTime() throws IOException
	{
		for (int i = 0; i < 10; i++)
		{
			Harness h = login(10, 90, "the woodcutting");
			assertEquals("last time, every time", 1, h.firedBy("last-time").size());
			assertTrue("instead of the hello", h.firedBy("login-greeting").isEmpty());
		}
		assertEquals("with nothing to remember, the hello",
			1, login(10, 90, "").firedBy("login-greeting").size());
	}

	@Test
	public void aShortBreakOrNoRecordStillGetsTheHello() throws IOException
	{
		assertEquals(1, login(10, 30, "").firedBy("login-greeting").size());
		assertEquals(1, login(10, -1, "").firedBy("login-greeting").size());
	}

	@Test
	public void theFirstWeekKeepsItsOwnVoice() throws IOException
	{
		assertEquals(1, login(1, 300, "").firedBy("login-greeting-new").size());
		assertEquals(1, login(4, 300, "").firedBy("login-greeting-settling").size());
	}

	@Test
	public void oneHelloNotTwo() throws IOException
	{
		for (long away : new long[]{-1, 30, 90, 300, 2000, 20000})
		{
			assertEquals("away " + away, 1, hellos(login(10, away, "the woodcutting")));
		}
	}
	/**
	 * The login as the plugin raises it: the prime armed first, and LOGIN
	 * held until it has taken its baseline. QA, 15 Oct: raised before the
	 * prime, logging in poisoned or in the Wilderness had that standing state
	 * rise on the login pass and take it from the greeting.
	 */
	private Harness loginAsThePluginDoes(java.util.function.Consumer<Harness> standing) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setSessionCount(20);
		h.engine.getContext().setMetOnDay(LocalDate.now().toEpochDay() - 40);
		h.engine.getContext().setMinutesAway(30);
		standing.accept(h);
		h.engine.primeEdgesOnNextTick();
		h.engine.dispatchAfterPriming(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(20);
		return h;
	}

	@Test
	public void standingStateDoesNotTakeTheHello() throws IOException
	{
		Harness wildy = loginAsThePluginDoes(h -> h.game.varbit(5963, 1));
		assertEquals(1, wildy.firedBy("login-greeting").size());
		assertTrue(wildy.firedBy("enter-wilderness").isEmpty());

		Harness poisoned = loginAsThePluginDoes(
			h -> h.game.varp(net.runelite.api.gameval.VarPlayerID.POISON, 5));
		assertEquals(1, poisoned.firedBy("login-greeting").size());
		assertTrue(poisoned.firedBy("status-poisoned").isEmpty());
	}

	/** The plugin arms the prime before it raises LOGIN, and raises it through the wait. */
	@Test
	public void thePluginRaisesLoginAfterThePrime() throws IOException
	{
		String source = PublicMarkers.strip(new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/com/follower/FollowerPlugin.java")),
			java.nio.charset.StandardCharsets.UTF_8));
		String login = "TriggerEvent.simple(LOGIN)";
		int at = source.indexOf(login);
		assertTrue("the plugin raises LOGIN", at >= 0);
		assertEquals("once", at, source.lastIndexOf(login));
		String before = source.substring(Math.max(0, at - 1500), at);
		assertTrue("through dispatchAfterPriming",
			before.trim().endsWith("speechEngine.dispatchAfterPriming("));
		assertTrue("after the prime is armed", before.contains("speechEngine.primeEdgesOnNextTick();"));
	}
}
