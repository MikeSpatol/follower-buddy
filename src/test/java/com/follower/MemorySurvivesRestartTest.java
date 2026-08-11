package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.google.gson.Gson;
import java.util.Arrays;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Everything the follower remembers, written out and read back.
 *
 * <p>Nothing verified this before, and the cost of a field written but never
 * read is completely silent: the follower forgets one thing forever and the
 * only symptom is behaviour that quietly resets every restart. The one-time
 * lines make it worse than it used to be - if the spent set does not survive,
 * the follower introduces itself as a stranger every single session, which is
 * the exact opposite of the thing it was added to do.
 *
 * <p>The saved shape stays private to the plugin. The seam here is a string in
 * and a string out, which is what actually crosses the disk.
 */
public class MemorySurvivesRestartTest
{
	private final Gson gson = new Gson();

	private TriggerContext fresh()
	{
		return new TriggerContext(new FakeGame().client);
	}

	/** A follower with something of every kind it can remember. */
	private TriggerContext lived()
	{
		TriggerContext context = fresh();
		context.tally("kill:goblin");
		context.tally("kill:goblin");
		context.tally("advice:taken");
		context.noteRecord("hit", 42);
		context.noteRecord("session", 238);
		context.setSessionCount(36);
		context.noteIncident("chicken-death", "that chicken");
		context.notePlaceFeeling(10);
		context.notePlaceMemory("the drop");
		context.noteSaidOnce("first-meeting");
		context.noteSaidOnce("first-page");
		context.setMetOnDay(20_400L);
		context.setMetWearingValue(1_234);
		context.restoreDeathSpot(new WorldPoint(3200, 3200, 1));
		return context;
	}

	@Test
	public void everythingWrittenComesBack()
	{
		TriggerContext before = lived();
		String blob = FollowerPlugin.snapshotMemory(before, 1_234, gson);

		TriggerContext after = fresh();
		int metWearing = FollowerPlugin.restoreMemory(after, blob, gson, 0);

		assertEquals("tallies", 2, after.getTally("kill:goblin"));
		assertEquals("tallies", 1, after.getTally("advice:taken"));
		assertEquals("records", 42, after.getRecord("hit"));
		assertEquals("records", 238, after.getRecord("session"));
		assertEquals("sessions", 36, after.getSessionCount());
		assertEquals("the incident", "that chicken", after.getIncidentPhrase());
		assertEquals("the first meeting", 20_400L, after.getMetOnDay());
		assertEquals("what you were wearing when it met you", 1_234, metWearing);
		assertEquals("where you died", new WorldPoint(3200, 3200, 1),
			after.getDeathLocation());

		// The one that would be silent if it broke.
		assertTrue("a spent introduction has to survive a restart",
			after.hasSaidOnce("first-meeting"));
		assertTrue(after.hasSaidOnce("first-page"));
		assertFalse("and nothing else should come back spent",
			after.hasSaidOnce("first-hour"));
	}

	@Test
	public void aSecondRoundTripChangesNothing()
	{
		// Save, load, save. The two blobs have to agree, or something is being
		// dropped or duplicated on every restart rather than only on the first.
		TriggerContext before = lived();
		String first = FollowerPlugin.snapshotMemory(before, 1_234, gson);

		TriggerContext middle = fresh();
		int metWearing = FollowerPlugin.restoreMemory(middle, first, gson, 0);
		String second = FollowerPlugin.snapshotMemory(middle, metWearing, gson);

		assertEquals("the follower's memory is not stable across a restart",
			first, second);
	}

	@Test
	public void aFollowerThatHasDoneNothingSavesAndLoadsCleanly()
	{
		String blob = FollowerPlugin.snapshotMemory(fresh(), 0, gson);
		TriggerContext after = fresh();
		assertEquals(0, FollowerPlugin.restoreMemory(after, blob, gson, 0));
		assertEquals(0, after.getSessionCount());
		assertTrue(after.getSpokenOnce().isEmpty());
	}

	@Test
	public void nothingIsLostToAMissingFieldFromAnOlderVersion()
	{
		// Every user upgrading arrives with a blob written by the previous
		// build, which has no spokenOnce in it at all. That must read as "has
		// said nothing yet" rather than throwing and costing them everything
		// else in the same blob.
		String older = "{\"tallies\":{\"kill:rat\":3},\"records\":{\"hit\":9},"
			+ "\"sessions\":12,\"metOnDay\":20400,\"deathPlane\":-1}";

		TriggerContext after = fresh();
		FollowerPlugin.restoreMemory(after, older, gson, 0);

		assertEquals("the rest of an older blob still has to load", 3,
			after.getTally("kill:rat"));
		assertEquals(12, after.getSessionCount());
		assertTrue("a missing spent-set reads as nothing said yet",
			after.getSpokenOnce().isEmpty());
		assertFalse(after.hasSaidOnce("first-meeting"));
	}

	@Test
	public void ruinedMemoryCostsTheMemoryAndNotTheSession()
	{
		// A corrupt value is sad but survivable. Refusing to start over it
		// would not be.
		TriggerContext after = fresh();
		int metWearing = FollowerPlugin.restoreMemory(after, "{not json at all",
			gson, 77);

		assertEquals("a corrupt blob must fall back rather than throw", 77, metWearing);
		assertEquals(0, after.getSessionCount());
	}

	@Test
	public void anEmptyOrAbsentBlobIsNotAnError()
	{
		assertEquals(5, FollowerPlugin.restoreMemory(fresh(), null, gson, 5));
		assertEquals(5, FollowerPlugin.restoreMemory(fresh(), "", gson, 5));
		assertEquals(5, FollowerPlugin.restoreMemory(fresh(), "null", gson, 5));
	}

	@Test
	public void theSpentSetSurvivesTheOrderItWasWrittenIn()
	{
		// It is a Set in memory and a List on disk, which is exactly the shape
		// where an ordering assumption can creep in unnoticed.
		TriggerContext before = fresh();
		for (String id : Arrays.asList("first-return", "first-meeting", "first-hour"))
		{
			before.noteSaidOnce(id);
		}
		String blob = FollowerPlugin.snapshotMemory(before, 0, gson);

		TriggerContext after = fresh();
		FollowerPlugin.restoreMemory(after, blob, gson, 0);
		assertEquals(3, after.getSpokenOnce().size());
		for (String id : Arrays.asList("first-return", "first-meeting", "first-hour"))
		{
			assertTrue(id + " was lost across the restart", after.hasSaidOnce(id));
		}
	}
}
