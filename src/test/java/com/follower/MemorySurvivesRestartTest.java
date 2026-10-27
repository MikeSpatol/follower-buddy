package com.follower;

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
		context.noteKillCountName("TzTok-Jad");
		context.setSessionCount(36);
		context.noteIncident("chicken-death", "that chicken", "Chicken", 1_700_000_000_000L);
		context.notePlaceMemory("the drop");
		context.noteSaidOnce("first-meeting");
		context.noteSaidOnce("first-page");
		context.noteLineSaid("A line said twice.");
		context.noteLineSaid("A line said twice.");
		context.setMetOnDay(20_400L);
		context.setMetWearingValue(1_234);
		context.restoreDeathSpot(new WorldPoint(3200, 3200, 1));
		context.noteLevel("Fishing", 70, 1_700_000_000_000L);
		context.noteDoing("fighting Vorkath");
		return context;
	}

	@Test
	public void theIncidentKeepsWhereItHappened()
	{
		// Where is what the standing-there recall and the Memory window's
		// "in Edgeville" both read. The fixture above files its incident
		// before the context has seen a region, so it is asked here of one
		// filed somewhere.
		TriggerContext before = new TriggerContext(new FakeGame().at(3090, 3490, 0).client);
		before.refresh();
		before.noteIncident("close-call", "how close that was", "Hill Giant", 1_700_000_000_000L);
		assertEquals("filed in Edgeville", 12342, before.getIncidentWhere());

		TriggerContext after = fresh();
		FollowerPlugin.restoreMemory(after, FollowerPlugin.snapshotMemory(before, 0, gson), gson, 0);
		assertEquals("and still there after a restart", 12342, after.getIncidentWhere());
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
		assertEquals("a boss's name as the game writes it", "TzTok-Jad",
			after.getKillCountNames().get("tztok-jad"));
		assertEquals("sessions", 36, after.getSessionCount());
		assertEquals("the incident", "that chicken", after.getIncidentPhrase());
		assertEquals("who it was with", "Chicken", after.getIncidentWho());
		assertEquals("when", 1_700_000_000_000L, after.getIncidentWhenMs());
		assertEquals("the first meeting", 20_400L, after.getMetOnDay());
		assertEquals("what you were wearing when it met you", 1_234, metWearing);
		assertEquals("where you died", new WorldPoint(3200, 3200, 1),
			after.getDeathLocation());
		assertEquals("when Fishing last went up", Long.valueOf(1_700_000_000_000L),
			after.getLevelStamps().get("fishing"));
		assertEquals("what you were doing last time", "fighting Vorkath",
			after.getLastTime());

		// The one that would be silent if it broke.
		assertTrue("a spent introduction has to survive a restart",
			after.hasSaidOnce("first-meeting"));
		assertTrue(after.hasSaidOnce("first-page"));
		assertFalse("and nothing else should come back spent",
			after.hasSaidOnce("first-hour"));

		// Line wear resetting every restart would make retirement (R17) a
		// per-session mechanism, which is to say no mechanism at all.
		assertEquals("how worn a line is has to survive too",
			2, after.lineWear("A line said twice."));
		assertEquals("and unheard lines stay unheard",
			0, after.lineWear("A line never said."));
	}

	@Test
	public void theWearLedgerStaysASensibleSize()
	{
		// The blob this rides in is written every few minutes for as long as
		// the client is open; an unbounded map would grow it forever. The cap
		// drops the least-worn entries - the ones farthest from ever standing
		// a line aside.
		TriggerContext context = fresh();
		context.noteLineSaid("the favourite");
		context.noteLineSaid("the favourite");
		context.noteLineSaid("the favourite");
		for (int i = 0; i < 700; i++)
		{
			context.noteLineSaid("filler line " + i);
		}

		assertTrue("the ledger must stay capped, was " + context.getLineWear().size(),
			context.getLineWear().size() <= 500);
		assertEquals("and the most-worn entries are the ones kept",
			3, context.lineWear("the favourite"));
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
	public void aBlobWithANicknameAndPlaceOpinionsLoadsInEveryBuild()
	{
		// What the full build (and 1.4.1) writes. The public build may leave
		// the nickname and the place opinions out; the blob still has to load
		// there, the rest of it with it.
		String full = "{\"tallies\":{\"kill:rat\":3},\"sessions\":12,\"deathPlane\":-1,"
			+ "\"placeScores\":{\"12850\":45},\"placeMemories\":{\"12850\":\"the drop\"},"
			+ "\"nickname\":\"the swot\",\"refusedNicknames\":[\"the gravedigger\"],"
			+ "\"nicknameAskedMs\":1700000000000,\"nicknameAsksIgnored\":2}";

		TriggerContext after = fresh();
		FollowerPlugin.restoreMemory(after, full, gson, 0);

		assertEquals(3, after.getTally("kill:rat"));
		assertEquals(12, after.getSessionCount());
		assertEquals("the drop", after.getPlaceMemories().get(12850));
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
	@Test
	public void memoryIsOnlyEverWrittenToTheProfileItCameFrom()
	{
		// Issue #1: a RuneLite profile switch announces its changed keys with
		// the new profile already current, and every write in that window
		// filed the old profile's state under the new one. Unread state is
		// never written either: there is nothing to write.
		assertTrue(FollowerPlugin.belongsToProfile(7L, 7L));
		assertFalse("another profile is current", FollowerPlugin.belongsToProfile(7L, 8L));
		assertFalse("nothing read yet", FollowerPlugin.belongsToProfile(-1L, -1L));
		assertFalse(FollowerPlugin.belongsToProfile(-1L, 3L));
	}


	@Test
	public void theRestFromRecallingSurvives()
	{
		// Text QA5: the nickname asked again at every login, and one memory
		// brought up at every one; both rests are kept with the memory.
		TriggerContext before = lived();
		before.noteIncidentRecalled(1_700_000_100_000L);
		TriggerContext after = fresh();
		FollowerPlugin.restoreMemory(after, FollowerPlugin.snapshotMemory(before, 0, gson), gson, 0);
		assertEquals(1_700_000_100_000L, after.getIncidentRecalledMs());
	}
}
