package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechDirector;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How much the follower says across a stretch of time, as opposed to how close
 * together two lines may fall.
 *
 * <p>The gap was always a floor and never a budget: at three seconds apart, a
 * follower with plenty to react to talks for as long as things keep happening,
 * and every line lands in the same flat stream as the one before. The director
 * makes it possible for the follower to have said enough for now.
 *
 * <p>Everything here takes the clock as an argument rather than reading it, so
 * these tests walk minutes without spending any.
 */
public class SpeechDirectorTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long GAP = 3_000L;

	private static SpeechDirector paced()
	{
		SpeechDirector director = new SpeechDirector();
		director.setBaseGapMs(GAP);
		return director;
	}

	private static SpeechRule ordinary()
	{
		SpeechRule rule = new SpeechRule();
		rule.id = "ordinary";
		rule.group = "reactions";
		return rule;
	}

	private static SpeechRule occasion()
	{
		SpeechRule rule = ordinary();
		rule.id = "occasion";
		rule.occasion = Boolean.TRUE;
		return rule;
	}

	private static SpeechRule lore()
	{
		SpeechRule rule = ordinary();
		rule.id = "lore";
		rule.group = "area";
		return rule;
	}

	// -------------------------------------------------------------- the peak

	@Test
	public void aBurstBuysSomeQuiet()
	{
		SpeechDirector director = paced();
		long now = 10_000L;

		director.noteSpoke(ordinary(), now);
		assertNull("one line is not a burst", director.blocks(ordinary(), now));
		director.noteSpoke(ordinary(), now += GAP);
		assertNull("two is still a conversation", director.blocks(ordinary(), now));

		director.noteSpoke(ordinary(), now += GAP);
		assertEquals("three in ten seconds is enough for now",
			"relax", director.blocks(ordinary(), now));
	}

	@Test
	public void theQuietEndsOnItsOwn()
	{
		SpeechDirector director = paced();
		long now = 10_000L;
		for (int i = 0; i < 3; i++)
		{
			director.noteSpoke(ordinary(), now += GAP);
		}
		assertTrue(director.isRelaxing(now));

		// The longest a peak can buy, and a second past it.
		assertNull("the follower does not sulk forever",
			director.blocks(ordinary(), now + 46_000L));
	}

	@Test
	public void theQuietIsNeverTheSameLengthTwice()
	{
		// A constant is a metronome. A player who notices the follower goes
		// quiet for exactly thirty seconds has found the machinery, and hears a
		// timer from then on rather than someone choosing not to speak.
		Set<Long> lengths = new HashSet<>();
		for (int attempt = 0; attempt < 200; attempt++)
		{
			SpeechDirector director = paced();
			long now = 10_000L;
			for (int i = 0; i < 3; i++)
			{
				director.noteSpoke(ordinary(), now += GAP);
			}
			long remaining = director.relaxRemainingMs(now);
			assertTrue("a peak bought " + remaining + "ms, which is outside the range",
				remaining >= 30_000L && remaining <= 45_000L);
			lengths.add(remaining);
		}
		assertTrue("every peak bought exactly the same silence", lengths.size() > 1);
	}

	@Test
	public void talkingSlowlyNeverPeaks()
	{
		// The whole point of a decay: a follower saying one thing every couple
		// of minutes is not bursting, however long you leave it running.
		SpeechDirector director = paced();
		long now = 10_000L;
		for (int i = 0; i < 50; i++)
		{
			now += 120_000L;
			director.noteSpoke(ordinary(), now);
			assertNull("a line every two minutes should never trip the peak",
				director.blocks(ordinary(), now));
		}
	}

	@Test
	public void theDecayScalesWithTheChattinessSetting()
	{
		// Expressed in gaps rather than seconds, so a player who asked for quiet
		// is not then told to be quieter. Three lines at one gap apart burst at
		// any setting; three lines spread over five gaps burst at none.
		for (long gap : new long[]{1_500L, 3_000L, 6_000L, 12_000L})
		{
			SpeechDirector tight = new SpeechDirector();
			tight.setBaseGapMs(gap);
			SpeechDirector loose = new SpeechDirector();
			loose.setBaseGapMs(gap);

			long now = 10_000L;
			for (int i = 0; i < 3; i++)
			{
				tight.noteSpoke(ordinary(), now + i * gap);
				loose.noteSpoke(ordinary(), now + i * gap * 6);
			}
			assertNotNull("gap " + gap + ": back to back should peak",
				tight.blocks(ordinary(), now + 3 * gap));
			assertNull("gap " + gap + ": well spread should not",
				loose.blocks(ordinary(), now + 18 * gap));
		}
	}

	// ----------------------------------------------------------- the occasion

	@Test
	public void anOccasionIsWhatTheQuietWasFor()
	{
		SpeechDirector director = paced();
		long now = 10_000L;
		for (int i = 0; i < 3; i++)
		{
			director.noteSpoke(ordinary(), now += GAP);
		}

		assertEquals("relax", director.blocks(ordinary(), now));
		assertNull("an occasion is exactly what the follower stopped talking for",
			director.blocks(occasion(), now));
	}

	@Test
	public void anOccasionCostsNothing()
	{
		// Letting the one line worth saying build towards the next silence
		// would have the follower punish itself for saying it.
		SpeechDirector director = paced();
		long now = 10_000L;
		for (int i = 0; i < 10; i++)
		{
			director.noteSpoke(occasion(), now += GAP);
		}
		assertFalse("occasions should not spend the budget", director.isRelaxing(now));
		assertEquals(0.0, director.getIntensity(), 0.0001);
	}

	// ---------------------------------------------------------- settling in

	@Test
	public void aNewFollowerThinsOutTheScenery()
	{
		SpeechDirector director = paced();
		director.setSessionCount(1);
		assertTrue(director.isSettlingIn());

		long now = 10_000L;
		director.noteSpoke(lore(), now);

		assertEquals("a second area line straight after the first",
			"settling", director.blocks(lore(), now + GAP));
		assertNull("but the follower can still react to what just happened",
			director.blocks(ordinary(), now + GAP));
		assertNull("and the scenery comes back round soon enough",
			director.blocks(lore(), now + GAP * 4));
	}

	@Test
	public void anOccasionIsExemptFromTheSettlingDamperToo()
	{
		// The bug this exists for. The relax check exempted occasions and the
		// settling check did not, which handed the damper the power to swallow
		// enter-wilderness - group "area", so it counts as scenery, and an
		// occasion precisely because it must reach a player who has never seen
		// the place. A follower is only settling in because the player is new,
		// so the two conditions peak at exactly the same moment.
		SpeechDirector director = paced();
		director.setSessionCount(1);

		SpeechRule warning = lore();
		warning.id = "enter-wilderness";
		warning.occasion = Boolean.TRUE;

		long now = 10_000L;
		director.noteSpoke(lore(), now);

		assertEquals("ordinary scenery is thinned, as intended",
			"settling", director.blocks(lore(), now + GAP));
		assertNull("but a warning is not scenery, whatever group it lives in",
			director.blocks(warning, now + GAP));
	}

	@Test
	public void aFollowerThatKnowsYouTalksAboutTheSceneryFreely()
	{
		SpeechDirector director = paced();
		director.setSessionCount(3);
		assertFalse(director.isSettlingIn());

		long now = 10_000L;
		director.noteSpoke(lore(), now);
		assertNull("past the first couple of sessions the damper is gone",
			director.blocks(lore(), now + 1L));
	}

	@Test
	public void anUnansweredSessionCountIsNotEvidenceOfAnything()
	{
		// Zero means nobody has said. Defaulting the other way would have every
		// fresh engine start out quiet about the scenery until something got
		// round to correcting it - including, at one point, the test harness.
		SpeechDirector director = paced();
		assertFalse(director.isSettlingIn());
		director.noteSpoke(lore(), 10_000L);
		assertNull(director.blocks(lore(), 10_001L));
	}

	@Test
	public void noGapMeansNoPacingAtAll()
	{
		// Falls out of the model rather than being bolted on: every window here
		// is a multiple of the gap, so a gap of nothing makes all of them
		// nothing. The chattiness setting has no such level; the harness does.
		SpeechDirector director = new SpeechDirector();
		director.setBaseGapMs(0L);
		director.setSessionCount(1);
		assertFalse(director.isPacing());

		for (int i = 0; i < 20; i++)
		{
			director.noteSpoke(ordinary(), 10_000L);
			director.noteSpoke(lore(), 10_000L);
		}
		assertNull(director.blocks(ordinary(), 10_000L));
		assertNull(director.blocks(lore(), 10_000L));
	}

	// ------------------------------------------------------- through the engine

	@Test
	public void theEngineHonoursTheDirectorAndSaysSo() throws IOException
	{
		// The peak arithmetic is settled above, on a clock this test can move.
		// What is worth checking here is the wiring: that the reason reaches
		// the transcript, that ordinary chatter is held, and that an occasion
		// is not.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"chatter\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"again\"]},"
				+ "{\"id\": \"big\", \"group\": \"t\", \"cooldownMs\": 0, \"occasion\": true,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"that mattered\"]}]}");
		h.engine.setGlobalCooldownMs(GAP);

		java.util.List<String> reasons = new java.util.ArrayList<>();
		h.engine.setOnSuppressed((rule, why) -> reasons.add(rule.id + ":" + why));

		long now = System.currentTimeMillis();
		for (int i = 0; i < 3; i++)
		{
			h.engine.getDirector().noteSpoke(ordinary(), now);
		}
		assertTrue("the burst should have bought some quiet",
			h.engine.getDirector().isRelaxing(now));

		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("ordinary chatter is held, and the transcript is told why",
			reasons.contains("chatter:relax"));
		assertTrue("and it stayed held", h.firedBy("chatter").isEmpty());

		h.dispatch(TriggerEvent.death());
		assertEquals("the occasion is what the quiet was being kept for",
			1, h.firedBy("big").size());
	}
}
