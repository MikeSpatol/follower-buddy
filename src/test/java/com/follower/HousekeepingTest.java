package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The bookkeeping nobody sees until it goes wrong.
 *
 * <p>Every case here is a fault that produces no error, no log line and no
 * visible symptom at the moment it happens - a follower that goes quiet for ten
 * seconds after a file save, a save that rewrites nine kilobytes of identical
 * JSON every minute for as long as the client is open. They are found by asking
 * rather than by noticing.
 */
public class HousekeepingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void reloadingTheRulesReleasesAHeldFloor()
	{
		// The exemption that lets the floor's holder speak through its own hush
		// is by identity, and a reload parses every rule afresh. Without the
		// release the holder becomes an object nothing points at, and the hush
		// it left behind silences the entire file until it times out - so
		// saving phrases.json mid-arrival muted the follower for ten seconds.
		Harness h = harnessWithAFloorRule();
		h.gameTicks(1);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse("the floor is taken", h.firedBy("important").isEmpty());

		h.dispatch(TriggerEvent.death());
		assertTrue("and held", h.firedBy("chatter").isEmpty());

		// A tick between the two deaths, or the second is not a rising edge:
		// playerDeath is only true while that dispatch is being processed, and
		// the first one already consumed the edge on its way to being hushed.
		h.gameTicks(1);
		h.engine.clearFloor();

		h.dispatch(TriggerEvent.death());
		assertFalse("releasing it lets everything else speak again",
			h.firedBy("chatter").isEmpty());
	}

	private Harness harnessWithAFloorRule() throws AssertionError
	{
		try
		{
			return new Harness(folder.newFolder().toPath(),
				"{\"version\": 1, \"rules\": ["
					+ "{\"id\": \"important\", \"group\": \"t\", \"cooldownMs\": 0,"
					+ " \"hushMs\": 60000, \"when\": {\"type\": \"examined\"},"
					+ " \"say\": [\"listen to me\"]},"
					+ "{\"id\": \"chatter\", \"group\": \"t\", \"cooldownMs\": 0,"
					+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"unrelated\"]}]}");
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void nothingCountedMeansNothingToSave() throws IOException
	{
		// The blob is nine kilobytes at the cap and the timer runs every hundred
		// ticks for as long as the client is open. Most of those minutes contain
		// no kill, no level and no death.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);
		h.engine.getContext().clearCountersDirty();

		h.gameTicks(300);
		assertFalse("a quiet stretch must not keep rewriting the same value",
			h.engine.getContext().isCountersDirty());

		h.engine.getContext().tally("kill:rat");
		assertTrue("but a kill has to reach the disk",
			h.engine.getContext().isCountersDirty());
	}

	@Test
	public void aSessionShorterThanTheRecordChangesNothing() throws IOException
	{
		// The longest-session record is filed every minute, which would have
		// marked the counters dirty every minute on its own and undone the
		// whole saving. It only counts when today actually beats the mark.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().noteRecord("session", 180);
		h.engine.getContext().clearCountersDirty();

		for (int minute = 1; minute <= 20; minute++)
		{
			h.engine.getContext().noteRecord("session", minute);
		}
		assertFalse("twenty minutes is not a record when the mark is three hours",
			h.engine.getContext().isCountersDirty());

		h.engine.getContext().noteRecord("session", 181);
		assertTrue("beating it is worth writing",
			h.engine.getContext().isCountersDirty());
	}

	@Test
	public void restoringFromDiskLeavesSomethingWorthWriting() throws IOException
	{
		// A restore MERGES with whatever the session already counted, so what is
		// in memory afterwards is not what is on disk. Skipping that save would
		// lose the merge on a crash.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().tally("kill:rat");
		h.engine.getContext().clearCountersDirty();

		java.util.Map<String, Integer> saved = new java.util.HashMap<>();
		saved.put("kill:rat", 40);
		h.engine.getContext().restoreCounters(saved, null);

		assertTrue("the merged total only exists in memory until it is written",
			h.engine.getContext().isCountersDirty());
	}
}
