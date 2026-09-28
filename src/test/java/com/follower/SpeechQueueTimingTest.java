package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

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
	public void aGuardWaitsAsLongAsALineCanStillLand() throws Exception
	{
		// A rule reading a state a queued line will set waits for that line,
		// and stops waiting once the line is too old to land. Shorter than the
		// queue's own wait, a line could land after the guard let go, and the
		// dare would open over the advice after all.
		Field field = com.follower.speech.SpeechEngine.class.getDeclaredField("LONGEST_WAIT_MS");
		field.setAccessible(true);
		long waited = ((Number) field.get(null)).longValue();
		long stale = constant("SPEECH_STALE_MS");

		assertTrue("the engine stops waiting on a line after " + waited + "ms, but the"
			+ " queue can still say it at " + stale + "ms", waited > stale);
	}

	@Test
	public void aFirstWaitingInTheQueueCannotBeHandedOverAgain() throws Exception
	{
		// A first is spent when it lands, not when the engine hands it over,
		// so a copy still waiting in the queue has not spent it yet. Only the
		// rule's cooldown stops it winning a second time meanwhile, so the
		// cooldown has to outlast the longest a line can wait.
		long stale = constant("SPEECH_STALE_MS");
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.isOnce() && rule.cooldownMs <= stale)
			{
				problems.add(rule.id + " cools down in " + rule.cooldownMs + "ms");
			}
		}
		assertTrue("a first could be queued twice and said twice, since a queued"
			+ " line may wait " + stale + "ms: " + problems, problems.isEmpty());
		assertTrue("no firsts found at all - the scan broke, not the rules",
			h.loader.getRules().stream().anyMatch(SpeechRule::isOnce));
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
