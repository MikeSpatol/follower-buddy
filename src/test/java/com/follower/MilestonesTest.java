package com.follower;

import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The levels that mean something beyond being the next one, the game's own
 * kill counts, the favour owed, and the greeting that remembers what you
 * were doing. The research behind all four is the same finding: a response
 * only lands as a response when it is about the specific thing that just
 * happened, and "you levelled" is worth a fraction of "that is your third
 * ninety-nine, and it took eleven days".
 */
public class MilestonesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long MINUTE = 60_000L;
	private static final long DAY = 24 * 60 * MINUTE;

	private Harness bundled() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		// Past the polite era, so the familiar level line is the baseline.
		h.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay() - 30);
		h.gameTicks(2);
		return h;
	}

	private static String said(Harness h, String ruleId)
	{
		List<Harness.Spoken> fired = h.firedBy(ruleId);
		assertFalse(ruleId + " never fired", fired.isEmpty());
		return fired.get(0).text;
	}

	// ----------------------------------------------------------- the levels

	@Test
	public void ninetyTwoIsTheTrueHalfway() throws IOException
	{
		Harness h = bundled();
		h.dispatch(TriggerEvent.levelUp("Fishing", 92, 1501, 1500, 40, 40, 0, 3 * DAY, DAY));
		h.gameTicks(3);
		assertFalse("the halfway line outranks the plain level line",
			h.firedBy("level-halfway").isEmpty());
		assertTrue(h.firedBy("level-up").isEmpty());
	}

	@Test
	public void theFirstNinetyNineIsSaidOnceAndTheRestAreCounted() throws IOException
	{
		Harness h = bundled();
		h.dispatch(TriggerEvent.levelUp("Cooking", 99, 1600, 1599, 40, 40, 1, 20 * DAY, DAY));
		h.gameTicks(3);
		assertFalse("the first ninety-nine", h.firedBy("level-mastery-first").isEmpty());
		assertTrue(h.firedBy("level-mastery-again").isEmpty());

		h.clear();
		h.gameTicks(400);
		h.dispatch(TriggerEvent.levelUp("Fishing", 99, 1700, 1699, 40, 40, 3, 20 * DAY, DAY));
		h.gameTicks(3);
		assertTrue("the first is once ever", h.firedBy("level-mastery-first").isEmpty());
		String line = said(h, "level-mastery-again");
		assertTrue("the ordinal is said in words: " + line,
			line.contains("third") || line.contains("three at ninety-nine"));
		// QA, 15 Oct: "That's 22 at ninety-nine. Your twenty-second" - the
		// count is in words, like the ninety-nine beside it.
		assertFalse(line, line.matches(".*\\d.*"));
	}

	@Test
	public void theTotalAndTheBaseAreCrossingsNotStates() throws IOException
	{
		Harness h = bundled();
		// 1499 to 1500 crosses a hundred; 1500 to 1501 does not.
		h.dispatch(TriggerEvent.levelUp("Mining", 61, 1500, 1499, 40, 40, 0, DAY, DAY));
		h.gameTicks(3);
		assertTrue(said(h, "level-total").contains("1500"));
		h.clear();
		h.gameTicks(400);
		h.dispatch(TriggerEvent.levelUp("Mining", 62, 1501, 1500, 40, 40, 0, DAY, DAY));
		h.gameTicks(3);
		assertTrue("a total that crossed nothing", h.firedBy("level-total").isEmpty());

		// The base: the lowest skill reaching fifty, and only on that tick.
		Harness base = bundled();
		base.dispatch(TriggerEvent.levelUp("Runecraft", 50, 1200, 1199, 50, 49, 0, DAY, DAY));
		base.gameTicks(3);
		assertTrue(said(base, "level-base").contains("50"));
		base.clear();
		base.gameTicks(400);
		base.dispatch(TriggerEvent.levelUp("Runecraft", 51, 1201, 1200, 50, 50, 0, DAY, DAY));
		base.gameTicks(3);
		assertTrue("the base did not move", base.firedBy("level-base").isEmpty());
	}

	@Test
	public void theGapSinceTheLastLevelIsSaidInWords() throws IOException
	{
		Harness h = bundled();
		h.dispatch(TriggerEvent.levelUp("Agility", 71, 1501, 1500, 40, 40, 0, 11 * DAY, 2 * DAY));
		h.gameTicks(3);
		String line = said(h, "level-after-a-gap");
		assertTrue("eleven days, in words: " + line,
			line.toLowerCase(java.util.Locale.ROOT).contains("eleven days"));

		// No stamp to measure from is not a gap of any size.
		Harness first = bundled();
		first.dispatch(TriggerEvent.levelUp("Agility", 71, 1501, 1500, 40, 40, 0, -1, -1));
		first.gameTicks(3);
		assertTrue(first.firedBy("level-after-a-gap").isEmpty());
		assertTrue(first.firedBy("level-quick").isEmpty());
		assertFalse("the plain level line still lands", first.firedBy("level-up").isEmpty());
	}

	@Test
	public void theContextKeepsTheStampsAndMeasuresTheGap()
	{
		TriggerContext context =
			new TriggerContext(new FakeGame().client);
		assertEquals(-1, context.sinceAnyLevel(1000L));
		assertEquals(-1, context.noteLevel("Fishing", 70, 1000L));
		assertEquals(0, context.sinceAnyLevel(1000L));
		assertEquals(5000L, context.noteLevel("Fishing", 71, 6000L));
		assertEquals(-1, context.noteLevel("Mining", 50, 7000L));
		assertEquals("the latest of any skill", 1000L, context.sinceAnyLevel(8000L));
		assertEquals("what it was doing", "getting Mining to 50", context.getLastThing());
	}

	// ------------------------------------------------------ the kill counts

	@Test
	public void theGamesOwnCountIsParsedWhateverTheBossCallsIt()
	{
		assertEquals("Vorkath", FollowerPlugin.parseKillCount(
			"Your Vorkath kill count is: <col=ff0000>41</col>.").getKey());
		assertEquals(41, (int) FollowerPlugin.parseKillCount(
			"Your Vorkath kill count is: <col=ff0000>41</col>.").getValue());
		assertEquals("Chambers of Xeric", FollowerPlugin.parseKillCount(
			"Your completed Chambers of Xeric count is: 5.").getKey());
		assertEquals("Barrows", FollowerPlugin.parseKillCount(
			"Your Barrows chest count is: 12.").getKey());
		assertEquals("Theatre of Blood", FollowerPlugin.parseKillCount(
			"Your Theatre of Blood completion count is: 7.").getKey());
		assertEquals("Wintertodt", FollowerPlugin.parseKillCount(
			"Your subdued Wintertodt count is: 1,024.").getKey());
		assertEquals(1024, (int) FollowerPlugin.parseKillCount(
			"Your subdued Wintertodt count is: 1,024.").getValue());
		assertNull(FollowerPlugin.parseKillCount("Your Slayer task is complete."));
		assertNull(FollowerPlugin.parseKillCount("You have 41 kills."));
	}

	@Test
	public void aTracksNameComesWithoutTheGamesColourTag()
	{
		// Every music unlock in the transcripts was said tag and all:
		// "Another one for the catalogue: @mes_hl_red@Children of the Sun."
		assertEquals("Children of the Sun", FollowerPlugin.parseTailAfter(
			"You have unlocked a new music track: @mes_hl_red@Children of the Sun.",
			"You have unlocked a new music track:"));
		assertEquals("the collection log's own colour goes as before", "Bronze med helm",
			FollowerPlugin.parseTailAfter("New item added to your collection log: <col=ef1020>Bronze med helm</col>",
				"New item added to your collection log:"));
		assertNull(FollowerPlugin.parseTailAfter("You have unlocked a new music track:",
			"You have unlocked a new music track:"));
	}

	@Test
	public void aLapIsNotAKill()
	{
		// The agility courses count in the boss's own words - "Your Varrock
		// Rooftop lap count is: 25." - and the boss lines answered it with
		// "That makes 25 Varrock Rooftop lap."
		assertNull(FollowerPlugin.parseKillCount(
			"Your Varrock Rooftop lap count is: <col=ff0000>25</col>."));
		assertNull(FollowerPlugin.parseKillCount(
			"Your Draynor Village Rooftop lap count is: <col=ff0000>100</col>."));
		assertNull(FollowerPlugin.parseKillCount(
			"Your Gnome Stronghold Agility lap count is: 12."));
		assertNull(FollowerPlugin.parseKillCount(
			"Your Herbiboar harvest count is: 5."));
		assertEquals("Vorkath", FollowerPlugin.parseKillCount(
			"Your Vorkath kill count is: <col=ff0000>41</col>.").getKey());
	}

	@Test
	public void theCountsAPersonSaysOutLoud() throws IOException
	{
		Harness h = bundled();
		h.dispatch(TriggerEvent.killCount("Vorkath", 1, 0));
		h.gameTicks(8);
		assertFalse("the first", h.firedBy("kill-count-first").isEmpty());
		assertTrue(h.firedBy("kill-count-round").isEmpty());

		// Past a thousand the hundreds stop being worth saying: 1100 is quiet.
		for (int n : new int[]{2, 3, 4, 6, 11, 26, 51, 99, 150, 1100})
		{
			Harness quiet = bundled();
			quiet.dispatch(TriggerEvent.killCount("Vorkath", n, n - 1));
			quiet.gameTicks(8);
			assertTrue(n + " is not a count anybody says out loud",
				quiet.firedBy("kill-count-round").isEmpty() && quiet.firedBy("kill-count-round-long").isEmpty());
		}
		for (int n : new int[]{5, 10, 25, 50, 75, 100, 300, 900, 1500})
		{
			Harness round = bundled();
			round.dispatch(TriggerEvent.killCount("Vorkath", n, n - 1));
			round.gameTicks(8);
			// From the hundreds the long-history lines have a rule of their own (text QA5 #65).
			assertFalse(n + " is a round count", round.firedBy("kill-count-round").isEmpty()
				&& round.firedBy("kill-count-round-long").isEmpty());
			assertTrue(n + " is too soon for a long history", n >= 100 || round.firedBy("kill-count-round-long").isEmpty());
		}

		Harness thousand = bundled();
		thousand.dispatch(TriggerEvent.killCount("Zulrah", 1000, 999));
		thousand.gameTicks(8);
		assertFalse("the thousandth outranks the round line",
			thousand.firedBy("kill-count-thousand").isEmpty());
		assertTrue(thousand.firedBy("kill-count-round").isEmpty() && thousand.firedBy("kill-count-round-long").isEmpty());
	}

	@Test
	public void theOrdinalRidesTheEvent()
	{
		TriggerEvent event = TriggerEvent.killCount("Vorkath", 41, 40);
		assertEquals("forty-first", event.getPlaceholders().get("ordinal"));
		assertEquals("41", event.getPlaceholders().get("count"));
		assertEquals("40", event.getPlaceholders().get("lastCount"));
		assertEquals(Words.ordinal(41), event.getPlaceholders().get("ordinal"));
	}

	// ------------------------------------------------------- the favour owed

	@Test
	public void aFavourDoneIsOwedForHalfAnHour() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"probe\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": {\"type\": \"grateful\"}, \"say\": [\"owed\"]}]}");
		h.gameTicks(2);
		assertTrue("nothing owed yet", h.firedBy("probe").isEmpty());
		h.engine.getContext().noteFavour();
		h.gameTicks(1);
		assertFalse("grateful", h.firedBy("probe").isEmpty());
		h.clear();
		h.gameTicks(TriggerContext.FAVOUR_TICKS + 2);
		h.clear();
		h.gameTicks(1);
		assertTrue("the debt lapses", h.firedBy("probe").isEmpty());
	}

	@Test
	public void aWantKeptAndAWishGrantedAreTheFavours() throws IOException
	{
		// The debt above is set by hand. These are the two doors it is
		// meant to come through, and a door that stopped opening it would
		// leave the goodwill in the body with nothing to earn it.
		Harness kept = new Harness(folder.newFolder().toPath());
		kept.game.at(3000, 3000, 0);
		kept.gameTicks(2);
		kept.engine.getContext().setWant(12850, "Lumbridge", 20);
		assertFalse(kept.engine.getContext().isGrateful());
		kept.game.at(12850 >> 8 << 6, (12850 & 0xFF) << 6, 0);
		kept.gameTicks(3);
		assertTrue("taken where it wanted to go", kept.engine.getContext().isGrateful());
	}

	// ----------------------------------------------------------- last time

	@Test
	public void theGreetingRemembersWhatYouWereDoing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"probe\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": {\"type\": \"lastTime\"},"
				+ " \"say\": [\"last time it was {lastTime}\"]}]}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertTrue("nothing to remember", h.firedBy("probe").isEmpty());

		h.engine.getContext().restoreLastThing("the woodcutting");
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertEquals("last time it was the woodcutting", said(h, "probe"));

		// Login only: the memory is for hello, not for the afternoon.
		h.clear();
		h.gameTicks(3);
		assertTrue(h.firedBy("probe").isEmpty());
	}

	@Test
	public void theWorkAndTheBossWriteThemselvesDown() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setWork(true, "fishing", "Fishing spot", null, 10, 0, 100);
		h.gameTicks(1);
		assertEquals("the fishing", h.engine.getContext().getLastThing());

		// A session that named nothing carries last session's memory forward.
		TriggerContext quiet =
			new TriggerContext(new FakeGame().client);
		quiet.restoreLastThing("fighting Vorkath");
		assertEquals("fighting Vorkath", quiet.getLastTime());
		assertEquals("fighting Vorkath", quiet.getLastThing());
	}
}
