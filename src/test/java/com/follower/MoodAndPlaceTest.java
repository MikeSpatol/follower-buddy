package com.follower;

import java.io.IOException;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two things a mood does beyond choosing words, and the guard that keeps
 * the follower's opinion of a place about the place.
 *
 * <p>Both were found by a mutation sweep rather than by review: the sweep said
 * they were covered, and they were not - the run before each had left a compile
 * error behind, so the "failure" that looked like a catch was the build, not a
 * test. Hence these, which fail for the reason they claim to.
 */
public class MoodAndPlaceTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aBadDayMakesTheFollowerSayLessRatherThanSaySadderThings()
	{
		long base = 3_000L;

		// The point of the feature: the gap is not the same in every mood.
		assertTrue("a low mood has to leave more room than an even one",
			SpeechEngine.gapForMood(base, "low") > SpeechEngine.gapForMood(base, "even"));
		assertTrue("a high mood has to leave less",
			SpeechEngine.gapForMood(base, "high") < SpeechEngine.gapForMood(base, "even"));

		// And it has to be monotonic across the bands, or the follower gets
		// chattier on the way DOWN through one of them.
		long low = SpeechEngine.gapForMood(base, "low");
		long down = SpeechEngine.gapForMood(base, "down");
		long even = SpeechEngine.gapForMood(base, "even");
		long good = SpeechEngine.gapForMood(base, "good");
		long high = SpeechEngine.gapForMood(base, "high");
		assertTrue("low > down > even > good > high",
			low > down && down > even && even > good && good > high);

		// An unknown band is the ordinary gap, not zero: a follower that talked
		// without pause because a band was renamed would be a bad way to find
		// out about the rename.
		assertEquals("an unrecognised band falls back to the plain gap",
			base, SpeechEngine.gapForMood(base, "nonsense"));
		assertEquals(base, SpeechEngine.gapForMood(base, null));
	}


	@Test
	public void aPlaceDoesNotRemindYouOfSomethingYouJustDid() throws IOException
	{
		// Found in a transcript: the follower set a personal best, filed the
		// spot, and five seconds later told the player "this is where you hit
		// harder than you ever had" - to somebody who had not moved. The value
		// of a place memory is entirely in the waiting.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"recall\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"happenedHere\"},"
				+ " \"say\": [\"this is where {here}\"]}]}");

		h.gameTicks(1);
		h.engine.getContext().notePlaceMemory("you hit a 73");
		h.gameTicks(5);
		assertTrue("standing where it happened is not being reminded of it",
			h.firedBy("recall").isEmpty());

		// Leave, come back, twenty minutes on: a minute on is still too fresh.
		h.game.at(2624, 3648, 0);
		h.gameTicks(TriggerContext.MEMORY_MIN_AGE_TICKS);
		h.game.at(3222, 3218, 0);
		h.gameTicks(2);
		assertFalse("coming back is what makes it worth saying",
			h.firedBy("recall").isEmpty());
	}

}
