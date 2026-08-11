package com.follower;

import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The queue has to outlast the line it is queued behind.
 *
 * <p>Two constants that had nothing to do with each other were made to collide
 * by the reading-time change. A line may hold the overhead for up to
 * MAX_SPEECH_MS plus a breath; a queued line is discarded once it has waited
 * SPEECH_STALE_MS. Both were twelve seconds, which meant a line queued behind a
 * maximum-length one aged out fractionally before the floor it was waiting for
 * ever freed - so it was never spoken at all.
 *
 * <p>That was reachable from the settings panel alone. The minimum display time
 * went up to fifteen seconds, reading time clamps to twelve, and every line
 * therefore held the floor for the full twelve: with the minimum turned up, the
 * speech queue stopped delivering anything, ever. Two rules firing at once is
 * the entire case the queue exists for.
 *
 * <p>Read by reflection rather than by making the constants visible. They are
 * private implementation detail and the relationship between them is the only
 * thing worth asserting from outside.
 */
public class SpeechQueueTimingTest
{
	private static long constant(String name) throws Exception
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return ((Number) field.get(null)).longValue();
	}

	@Test
	public void aQueuedLineOutlastsTheLongestPossibleLineInFrontOfIt() throws Exception
	{
		long longestHold = constant("MAX_SPEECH_MS") + constant("SPEECH_GAP_MS");
		long stale = constant("SPEECH_STALE_MS");

		assertTrue("a line may hold the overhead for " + longestHold + "ms but the"
			+ " queue throws work away after " + stale + "ms, so anything queued"
			+ " behind the longest line is dropped before it can ever be said",
			stale > longestHold);
	}

	@Test
	public void theMinimumDisplayTimeCannotBeSetAboveTheMaximum() throws Exception
	{
		// The setting used to offer fifteen seconds while the code clamped at
		// twelve, so the top three seconds of the slider did nothing at all.
		net.runelite.client.config.Range range = FollowerConfig.class
			.getMethod("speechDurationMs")
			.getAnnotation(net.runelite.client.config.Range.class);

		assertTrue("the config has no range at all", range != null);
		assertTrue("the box offers " + range.max() + "ms but nothing is ever shown"
			+ " for longer than " + constant("MAX_SPEECH_MS") + "ms",
			range.max() <= constant("MAX_SPEECH_MS"));
	}
}
