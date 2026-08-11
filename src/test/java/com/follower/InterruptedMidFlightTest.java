package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every new system, interrupted while it is holding something.
 *
 * <p>All the interesting bugs in this plugin have been of one shape: a piece of
 * state outliving the thing that owned it. A held floor surviving the rule that
 * took it. An edge surviving the reload that replaced the rule. A want
 * surviving the world hop that made it unreachable. Each of the systems added
 * this week holds state across time, so each of them can fail the same way.
 */
public class InterruptedMidFlightTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long GAP = 3_000L;
	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	private Harness harness(String rules) throws IOException
	{
		Harness h = rules == null
			? new Harness(folder.newFolder().toPath())
			: new Harness(folder.newFolder().toPath(), rules);
		h.engine.setClock(clock::get);
		h.engine.setGlobalCooldownMs(GAP);
		return h;
	}

	private void rest(Harness h)
	{
		SpeechRule filler = new SpeechRule();
		filler.id = "filler";
		filler.group = "reactions";
		for (int i = 0; i < 4; i++)
		{
			h.engine.getDirector().noteSpoke(filler, clock.get());
		}
		assertTrue(h.engine.getDirector().isRelaxing(clock.get()));
	}

	// ------------------------------------------------- the mute, mid-everything

	@Test
	public void unmutingDoesNotLeaveTheFollowerOwingASilence() throws IOException
	{
		// The mute is checked after the floor has already been taken, so a
		// muted rule that wins can hold the floor for a line nobody heard. It
		// costs nothing while the mute is on - everything is silent anyway -
		// but the residue must not outlive it.
		Harness h = harness("{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"big\", \"group\": \"t\", \"cooldownMs\": 0, \"hushMs\": 8000,"
			+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"listen\"]},"
			+ "{\"id\": \"small\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"unrelated\"]}]}");
		h.gameTicks(1);

		h.engine.setMuted(true);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("muted, so nothing came out", h.spoken.isEmpty());

		h.engine.setMuted(false);
		clock.addAndGet(9_000L);            // past any floor the muted win took
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		assertFalse("un-muting has to give the follower its voice back",
			h.firedBy("small").isEmpty());
	}

	// ------------------------------------------------ the reload, mid-anything

	@Test
	public void aReloadMidRestDoesNotStrandTheFollower() throws IOException
	{
		// A reload replaces every rule object. The director survives it, being
		// engine state rather than rule state - so a reload during a rest must
		// not extend it, and the floor must be released, or editing a phrase
		// silences the file until a timer nobody can see runs out.
		Harness h = harness(null);
		h.gameTicks(1);
		rest(h);

		long restingUntil = clock.get() + h.engine.getDirector().relaxRemainingMs(clock.get());
		h.engine.clearFloor();
		h.engine.primeEdgesOnNextTick();

		clock.set(restingUntil + 1_000L);
		assertFalse("the rest outlived its own window",
			h.engine.getDirector().isRelaxing(clock.get()));
	}

	@Test
	public void aReloadDoesNotUnsayAOneTimeLine() throws IOException
	{
		// Already covered for the file-watch path; this is the same claim for
		// the object identity itself, which is what actually changes.
		String rules = "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"hello\", \"group\": \"t\", \"cooldownMs\": 0, \"once\": true,"
			+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"first time\"]}]}";
		Harness h = harness(rules);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals(1, h.firedBy("hello").size());

		Path file = h.getScratch().resolve(RuleLoader.FILE_NAME);
		Files.write(file, rules.replace("first time", "first time, honest")
			.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime
			.fromMillis(System.currentTimeMillis() + 5_000L));
		assertTrue(h.loader.reloadIfChanged());

		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals("a reload must not hand back a spent first",
			1, h.firedBy("hello").size());
	}

	// ------------------------------------------------- the world hop, mid-bag

	@Test
	public void aWorldHopKeepsTheBagAndTheWindowAndReleasesTheEdges()
		throws IOException
	{
		// Two things had to change for this to test anything at all.
		//
		// It ran once, and a re-dealt six-line bag avoids three already-said
		// lines about one time in twenty, so a single attempt was a coin toss a
		// mutation sweep walked straight past.
		//
		// And more interestingly, the corpus window was covering for the bag.
		// recentLineSet survives a scene change, so it still held the three
		// lines just said, and pickPhrase prefers what has not been heard - a
		// freshly dealt bag produced the same three remaining lines a preserved
		// one would have. The systems defend each other, which is good, and
		// makes each of them individually untestable, which is not. The
		// flusher rule below moves the window on so the bag is the only thing
		// left deciding.
		for (int attempt = 0; attempt < 20; attempt++)
		{
			Harness h = harness("{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"t\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\"]},"
				+ "{\"id\": \"flush\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"playerDeath\"},"
				+ " \"say\": [\"f1\", \"f2\", \"f3\", \"f4\", \"f5\", \"f6\","
				+ " \"f7\", \"f8\", \"f9\", \"f10\", \"f11\", \"f12\"]}]}");
			h.engine.setGlobalCooldownMs(0L);
			h.gameTicks(1);

			java.util.Set<String> before = new java.util.HashSet<>();
			for (int i = 0; i < 3; i++)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
				h.gameTicks(1);
			}
			for (Harness.Spoken s : h.spoken)
			{
				before.add(s.text);
			}
			assertEquals(3, before.size());

			// Twelve other lines, which is exactly the window, so nothing the
			// rule under test has said is still remembered corpus-wide.
			for (int i = 0; i < 12; i++)
			{
				h.dispatch(TriggerEvent.death());
				h.gameTicks(1);
			}

			h.engine.resetForNewScene();
			h.clear();
			h.gameTicks(1);

			for (int i = 0; i < 3; i++)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
				h.gameTicks(1);
			}
			for (Harness.Spoken s : h.spoken)
			{
				if (s.rule != null && "t".equals(s.rule.id))
				{
					assertTrue("the hop re-dealt \"" + s.text + "\", which this bag"
						+ " had already spent", before.add(s.text));
				}
			}
			assertEquals("all six should still have come out across the hop",
				6, before.size());
		}
	}

	// ------------------------------------ the settings, changed mid-everything

	@Test
	public void turningTheChattinessDownMidRestDoesNotStretchIt() throws IOException
	{
		// The rest is an absolute deadline and the decay is a rate. Changing the
		// gap changes the rate, and must not retroactively move a deadline
		// already set - a player switching to Quiet mid-rest should not be
		// punished with four times the silence they asked for.
		Harness h = harness(null);
		h.gameTicks(1);
		rest(h);
		long remaining = h.engine.getDirector().relaxRemainingMs(clock.get());

		h.engine.setGlobalCooldownMs(12_000L);      // Quiet
		assertEquals("the rest already running must not move",
			remaining, h.engine.getDirector().relaxRemainingMs(clock.get()));
	}

	@Test
	public void aPluginToggleClearsEverythingTheFollowerWasHolding() throws IOException
	{
		// reset() is the "start over" path. Everything with a deadline has to go
		// with it, or switching the plugin off and on again - the first thing
		// anybody tries - leaves the follower serving out a sentence.
		Harness h = harness(null);
		h.gameTicks(1);
		rest(h);

		h.engine.reset();

		assertFalse("a toggle must not leave the follower resting",
			h.engine.getDirector().isRelaxing(clock.get()));
		assertEquals("and must not leave a stale gap", 0L, h.engine.getLastSpokeMs());

		SpeechRule any = new SpeechRule();
		any.id = "x";
		any.group = "reactions";
		assertNull("nothing should be held after a toggle",
			h.engine.getDirector().blocks(any, clock.get()));
	}

	// ------------------------------------------------- the delay, interrupted

	@Test
	public void aDelayedLineDoesNotSurviveAToggle() throws IOException
	{
		// A pending firing holds a rule and an event across ticks. If the queue
		// outlived a reset, the follower would speak about something from
		// before it was switched off.
		Harness h = harness("{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"slow\", \"group\": \"t\", \"cooldownMs\": 0, \"delayTicks\": 5,"
			+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"eventually\"]}]}");
		h.gameTicks(1);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		h.gameTicks(2);
		assertTrue("still pending", h.spoken.isEmpty());

		h.engine.reset();
		h.gameTicks(10);
		assertTrue("a line queued before the reset came out after it",
			h.spoken.isEmpty());
	}
}
