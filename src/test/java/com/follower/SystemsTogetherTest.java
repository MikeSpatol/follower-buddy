package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Where the recently-added systems touch each other.
 *
 * <p>The director, the shuffle bag, the corpus window, the one-time lines and
 * the delayed-speech queue were each built and tested on their own. Every one
 * of them intercepts the same moment - a rule has won and is about to speak -
 * and the order they run in is load-bearing in ways none of their own tests
 * can see.
 */
public class SystemsTogetherTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long GAP = 3_000L;

	/**
	 * Puts the engine's own director into a relax period, deterministically.
	 *
	 * <p>Sets the gap itself rather than trusting the caller: the harness
	 * deliberately runs unpaced, and a director with no gap has no windows at
	 * all - so without this the helper silently does nothing and every test
	 * built on it passes for the wrong reason.
	 */
	private static void makeItRest(Harness h)
	{
		h.engine.setGlobalCooldownMs(GAP);

		SpeechRule ordinary = new SpeechRule();
		ordinary.id = "filler";
		ordinary.group = "reactions";
		long now = System.currentTimeMillis();
		for (int i = 0; i < 4; i++)
		{
			h.engine.getDirector().noteSpoke(ordinary, now);
		}
		assertTrue("the director should be resting",
			h.engine.getDirector().isRelaxing(now));
	}

	// ------------------------------------------- suppression against the bag

	@Test
	public void aLineNobodyHeardDoesNotSpendItsTurnInTheBag() throws IOException
	{
		// The bag's promise is that all N come out before any repeats. If a
		// suppressed firing drew from it anyway, the player would hear N minus
		// however many were swallowed - and the more the director held back,
		// the less variety it would leave behind. The two systems would fight.
		SpeechRule rule = new SpeechRule();
		rule.id = "t";
		rule.group = "reactions";
		rule.say = java.util.Arrays.asList("a", "b", "c", "d");

		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"t\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"a\", \"b\", \"c\", \"d\"]}]}");
		h.engine.setGlobalCooldownMs(GAP);
		h.gameTicks(1);

		makeItRest(h);

		// Six firings that all get held.
		for (int i = 0; i < 6; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}
		assertTrue("everything should have been held", h.spoken.isEmpty());

		// Now let it talk, with no gap in the way, and the bag should still be
		// whole: four distinct lines before any comes round again.
		h.engine.getDirector().reset();
		h.engine.setGlobalCooldownMs(0L);
		Set<String> heard = new HashSet<>();
		for (int i = 0; i < 4; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}
		for (Harness.Spoken s : h.spoken)
		{
			heard.add(s.text);
		}
		assertEquals("suppression ate part of the bag", 4, heard.size());
	}

	// ------------------------------------------ the queue against the director

	@Test
	public void aDelayedOccasionSurvivesTheQuietAndOrdinaryChatterDoesNot()
		throws IOException
	{
		// A delayed line is judged when it LANDS, not when it won, so the
		// director gets a second look at something already decided. That is
		// correct - the world moved on in the meantime - but it means a line
		// with delayTicks is the one most likely to be swallowed, and every
		// arrival-arc line has a delay.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"slow-big\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"occasion\": true, \"delayTicks\": 3,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"this mattered\"]},"
				+ "{\"id\": \"slow-small\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"delayTicks\": 3,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"just chatter\"]}]}");
		h.engine.setGlobalCooldownMs(GAP);
		h.gameTicks(1);
		makeItRest(h);

		h.dispatch(TriggerEvent.death());
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		h.gameTicks(5);

		assertEquals("a delayed occasion has to land", 1, h.firedBy("slow-big").size());
		assertTrue("delayed chatter should be held like any other",
			h.firedBy("slow-small").isEmpty());
	}

	// ------------------------------------------ one-time lines against the rest

	@Test
	public void aOneTimeLineHeldByTheDirectorIsNotSpent() throws IOException
	{
		// The worst possible interaction in the whole set. A once-line has
		// exactly one chance; if the director eats it, the player never hears
		// it and never can. The arrival arc is all four of them.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"only-once\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"once\": true,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"we have not met\"]}]}");
		h.engine.setGlobalCooldownMs(GAP);
		h.gameTicks(1);
		makeItRest(h);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("held, as an ordinary line would be", h.firedBy("only-once").isEmpty());
		assertFalse("but it must NOT have been spent",
			h.engine.getContext().hasSaidOnce("only-once"));

		h.engine.getDirector().reset();
		h.engine.setGlobalCooldownMs(0L);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals("still owed, and still paid", 1, h.firedBy("only-once").size());
		assertTrue(h.engine.getContext().hasSaidOnce("only-once"));
	}

	@Test
	public void everyArrivalLineIsBuiltToSurviveTheDirector() throws IOException
	{
		// The shipped version of the test above. These four are the only lines
		// in the file a player can miss permanently, and three of the systems
		// added this week can each swallow a line on its way out.
		Harness h = new Harness(folder.newFolder().toPath());
		long now = System.currentTimeMillis();
		makeItRest(h);

		for (String id : new String[]{"first-meeting", "first-page", "first-hour", "first-return"})
		{
			SpeechRule rule = h.rule(id);
			assertNull(id + " would be swallowed by a relax period",
				h.engine.getDirector().blocks(rule, now));

			// And the settling damper, which is live for exactly the sessions
			// these lines are written for.
			h.engine.getDirector().setSessionCount(1);
			assertNull(id + " would be swallowed while the follower settles in",
				h.engine.getDirector().blocks(rule, now));
		}
	}

	@Test
	public void theSettlingDamperCannotReachTheArrivalArc() throws IOException
	{
		// Belt and braces on the above, from the other end: the damper only
		// knows about groups, and every arrival line is misc rather than area
		// or gear. If one were ever moved into a scenery group it would fall
		// under the damper during the exact sessions it exists for.
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"first-meeting", "first-page", "first-hour", "first-return"})
		{
			assertFalse(id + " must not live in a group the damper thins",
				com.follower.speech.SpeechDirector.isLore(h.rule(id)));
		}
	}

	// ----------------------------------------- the window against the occasion

	@Test
	public void theCorpusWindowNeverSilencesAnUrgentLine() throws IOException
	{
		// The window prefers something unheard and falls back to the draw. A
		// single-variant warning has nothing to fall back TO, which is the case
		// worth pinning: "TELEPORT. NOW." said twice beats it not being said.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"urgent\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"occasion\": true,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"MOVE\"]}]}");
		h.gameTicks(1);

		for (int i = 0; i < 8; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}
		assertEquals("a warning with one line must say it every time",
			8, h.firedBy("urgent").size());
	}

	// --------------------------------------------- reading time and the queue

	@Test
	public void aLongLineDoesNotStretchTheQuiet() throws IOException
	{
		// Reading time gates the speech QUEUE and the director runs on the wall
		// clock, so a twelve-second line and a thirty-second rest are measured
		// from the same instant rather than stacking. Worth pinning because the
		// obvious wrong implementation - resting from the moment the line
		// finishes - would have long lines quietly buy longer silences.
		Harness h = new Harness(folder.newFolder().toPath());
		long now = System.currentTimeMillis();
		makeItRest(h);
		long shortLine = h.engine.getDirector().relaxRemainingMs(now);

		h.engine.getDirector().reset();
		SpeechRule wordy = new SpeechRule();
		wordy.id = "wordy";
		wordy.group = "reactions";
		for (int i = 0; i < 4; i++)
		{
			h.engine.getDirector().noteSpoke(wordy, now);
		}
		long longLine = h.engine.getDirector().relaxRemainingMs(now);

		assertTrue("the rest is drawn from a range, not from the line",
			shortLine >= 30_000L && shortLine <= 45_000L);
		assertTrue(longLine >= 30_000L && longLine <= 45_000L);
	}

	// ------------------------------------------------ everything, at real size

	@Test
	public void theShippedRulesStillSpeakWithEverySystemLive() throws IOException
	{
		// The integration smoke test. All the machinery on, the real rule set,
		// a few minutes of ticks - the follower has to still be a follower.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setGlobalCooldownMs(GAP);
		h.engine.getContext().setSessionCount(1);
		h.gameTicks(2);

		List<String> said = new ArrayList<>();
		for (int i = 0; i < 400; i++)
		{
			h.gameTick();
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			if (i % 40 == 0)
			{
				h.dispatch(TriggerEvent.death());
			}
		}
		for (Harness.Spoken s : h.spoken)
		{
			if (!s.text.isEmpty())
			{
				said.add(s.text);
			}
		}

		assertFalse("the follower went completely silent", said.isEmpty());
		for (int i = 1; i < said.size(); i++)
		{
			assertFalse("said \"" + said.get(i) + "\" twice running",
				said.get(i).equals(said.get(i - 1)));
		}
	}
}
