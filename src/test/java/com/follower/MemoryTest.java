package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The things the follower is supposed to remember between one session and the
 * next.
 *
 * <p>A count that resets on logout is a scoreboard for the current game. The
 * whole value of "your fiftieth" is that the fifty were since you met, so the
 * parts that make that true - the merge on restore, the records that only move
 * up, the first-ever value that seeds silently - are what is checked here.
 */
public class MemoryTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void restoringCountersAddsToWhatIsAlreadyCounted() throws IOException
	{
		// The restore happens a few ticks into a login, and a kill can land
		// first. Overwriting would throw that kill away.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().tally("kill:rat");

		Map<String, Integer> saved = new HashMap<>();
		saved.put("kill:rat", 40);
		h.engine.getContext().restoreCounters(saved, null);

		assertEquals("a restore must add to the session, not replace it",
			41, h.engine.getContext().getTally("kill:rat"));
	}

	@Test
	public void restoringRecordsKeepsTheBetterOfTheTwo() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().noteRecord("hit", 60);

		Map<String, Integer> saved = new HashMap<>();
		saved.put("hit", 42);
		h.engine.getContext().restoreCounters(null, saved);

		assertEquals("a stale saved record must not undo a better live one",
			60, h.engine.getContext().getRecord("hit"));
	}

	@Test
	public void theFirstValueEverSeenIsNotAPersonalBest() throws IOException
	{
		// Otherwise every new install announces a record on its first hit,
		// which makes the feature look broken to every new user at once.
		TriggerContext context = new Harness(folder.newFolder().toPath())
			.engine.getContext();

		assertFalse("the first measurement is the only measurement",
			context.noteRecord("hit", 30));
		assertEquals("it is still stored, though", 30, context.getRecord("hit"));
		assertFalse("and beaten is beaten, not equalled", context.noteRecord("hit", 30));
		assertTrue("beating it is the news", context.noteRecord("hit", 31));
	}

	// ------------------------------------------------------- shipped rules

	@Test
	public void theSessionMilestoneLandsOnTheExactNumber() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		// These carry a delayTicks, so the line lands some ticks after the win.
		h.engine.getContext().setSessionCount(99);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(20);
		assertTrue("ninety-nine is not a hundred", h.firedBy("session-100").isEmpty());

		h.engine.getContext().setSessionCount(100);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(20);
		assertFalse("the hundredth session", h.firedBy("session-100").isEmpty());

		h.clear();
		h.engine.getContext().setSessionCount(101);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.gameTicks(20);
		assertTrue("and never again after", h.firedBy("session-100").isEmpty());
	}

	@Test
	public void theRecordLineNamesBothNumbers() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.record("hit", 71, 54));

		List<Harness.Spoken> said = h.firedBy("record-hit");
		assertFalse("a new biggest hit is worth saying", said.isEmpty());
		String line = said.get(0).text;
		assertTrue("the new mark has to appear: " + line, line.contains("71"));
		assertFalse("and nothing may print as a literal placeholder: " + line,
			line.contains("{"));
	}

	@Test
	public void dyingOftenReadsDifferentlyFromDyingOnce() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		h.dispatch(TriggerEvent.death());
		h.gameTicks(10);
		assertTrue("the first death is not a pattern", h.firedBy("deaths-many").isEmpty());

		for (int i = 0; i < 12; i++)
		{
			h.engine.getContext().tally("deaths");
		}
		h.gameTicks(200);
		h.clear();
		h.dispatch(TriggerEvent.death());
		h.gameTicks(10);
		assertFalse("a dozen deaths is a pattern", h.firedBy("deaths-many").isEmpty());
	}
}
