package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hours of the shipped rules with every system live at its real setting.
 *
 * <p>{@link EngineSoakTest} explores random rule sets for engine invariants.
 * This one does the opposite: the REAL four hundred rules, real chattiness,
 * real director, real bags, driven long enough that the windows actually open
 * and close. The clock is fake, which is the only reason that is possible -
 * a relax period is thirty to forty-five seconds and a test cannot spend them.
 *
 * <p>What it is looking for is the failure no unit test can produce: something
 * that only goes wrong on the four hundredth firing, or when three windows
 * happen to line up.
 */
public class LongSoakTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** One game tick, in milliseconds. */
	private static final long TICK_MS = 600L;

	private static final long GAP = 3_000L;

	/** A session long enough for every window in the engine to cycle many times. */
	private static final int TICKS = 20_000;      // 20,000 * 0.6s = about 3h20m

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	private Harness soakHarness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(clock::get);
		h.engine.setGlobalCooldownMs(GAP);
		return h;
	}

	/** Drives ticks and a spread of realistic events, advancing the fake clock. */
	private void play(Harness h, int ticks, Random random)
	{
		for (int i = 0; i < ticks; i++)
		{
			clock.addAndGet(TICK_MS);
			h.gameTick();

			int roll = random.nextInt(100);
			if (roll < 4)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			}
			else if (roll < 7)
			{
				h.dispatch(TriggerEvent.death());
			}
			else if (roll < 12)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));
			}
			else if (roll < 17)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_END));
			}
			else if (roll < 20)
			{
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LEVEL_UP));
			}
		}
	}

	// --------------------------------------------------------------- the soak

	@Test
	public void hoursOfPlayLeaveEverySystemIntact() throws IOException
	{
		Harness h = soakHarness();
		h.gameTicks(3);
		h.clear();

		play(h, TICKS, new Random(20260810L));

		List<Harness.Spoken> spoken = new ArrayList<>(h.spoken);
		List<String> lines = new ArrayList<>();
		for (Harness.Spoken s : spoken)
		{
			if (!s.text.isEmpty())
			{
				lines.add(s.text);
			}
		}

		assertFalse("three hours and the follower never said anything", lines.isEmpty());

		// 1. Never the same line twice running, whatever combination of gap,
		//    rest, bag refill and window happened to line up.
		for (int i = 1; i < lines.size(); i++)
		{
			assertFalse("said \"" + lines.get(i) + "\" twice running at line " + i,
				lines.get(i).equals(lines.get(i - 1)));
		}

		// 2. Every rule that spoke stayed inside its own bag: no line came round
		//    again before that rule had spent its other variants.
		Map<String, List<String>> byRule = new HashMap<>();
		for (Harness.Spoken s : spoken)
		{
			if (s.rule != null && !s.text.isEmpty())
			{
				byRule.computeIfAbsent(s.rule.id, k -> new ArrayList<>()).add(s.text);
			}
		}
		for (Map.Entry<String, List<String>> entry : byRule.entrySet())
		{
			SpeechRule rule = h.rule(entry.getKey());
			int variants = rule.say == null ? 1 : rule.say.size();
			if (variants < 2)
			{
				continue;
			}
			List<String> said = entry.getValue();

			// Never the same line twice in a row FOR THIS RULE. The global check
			// above cannot see this: two firings of one rule are usually far
			// apart in the overall stream, with other rules in between.
			for (int i = 1; i < said.size(); i++)
			{
				assertFalse(entry.getKey() + " said \"" + said.get(i) + "\" twice running",
					said.get(i).equals(said.get(i - 1)));
			}

			// The real promise of a bag is EVEN USE, not minimum spacing. A line
			// drawn late in one bag may legitimately come early in the next, so
			// two occurrences can be close - but across many bags every variant
			// has to come out the same number of times, give or take the partly
			// spent bag at the end. That is the property that catches a bag
			// which quietly stopped dealing part of its range.
			boolean templated = false;
			for (String variant : rule.say)
			{
				templated |= variant.indexOf('{') >= 0;
			}
			if (templated || said.size() < variants * 3)
			{
				continue;
			}

			Map<String, Integer> counts = new HashMap<>();
			for (String line : said)
			{
				counts.merge(line, 1, Integer::sum);
			}
			assertEquals(entry.getKey() + " only ever used " + counts.size()
				+ " of its " + variants + " lines across " + said.size() + " firings",
				variants, counts.size());

			int most = 0;
			int fewest = Integer.MAX_VALUE;
			for (int n : counts.values())
			{
				most = Math.max(most, n);
				fewest = Math.min(fewest, n);
			}
			assertTrue(entry.getKey() + " used one line " + most + " times and another"
				+ " only " + fewest + " across " + said.size() + " firings, which is"
				+ " not a bag dealing evenly", most - fewest <= 1);
		}

		// 3. A one-time line is one time, across three hours and every window.
		Map<String, Integer> onceCounts = new HashMap<>();
		for (Harness.Spoken s : spoken)
		{
			if (s.rule != null && s.rule.isOnce())
			{
				onceCounts.merge(s.rule.id, 1, Integer::sum);
			}
		}
		for (Map.Entry<String, Integer> entry : onceCounts.entrySet())
		{
			assertEquals(entry.getKey() + " said itself more than once",
				1, entry.getValue().intValue());
		}

		// 4. Memory stays bounded. The window is a fixed twelve and the spent
		//    set can only hold as many ids as there are one-time rules.
		assertTrue("the spent-line set grew past the rules that can fill it",
			h.engine.getContext().getSpokenOnce().size() <= 8);

		// 5. The follower is still capable of speech at the end - the failure
		//    where some window latches shut and everything afterwards is silent.
		h.clear();
		play(h, 600, new Random(7L));
		assertFalse("the follower went permanently quiet by the end", h.spoken.isEmpty());
	}

	@Test
	public void theDirectorRestsWithoutEverStopping() throws IOException
	{
		// The shape the whole model is for: bursts with real quiet between them,
		// rather than a flat stream or a follower that talks itself into silence.
		Harness h = soakHarness();
		h.gameTicks(3);
		h.clear();

		List<Long> spokenAt = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId) ->
		{
			if (!text.isEmpty())
			{
				spokenAt.add(clock.get());
			}
		});

		play(h, TICKS, new Random(99L));

		assertTrue("far too little came out of three hours: " + spokenAt.size(),
			spokenAt.size() > 20);

		long span = clock.get() - 1_700_000_000_000L;
		double perHour = spokenAt.size() * 3_600_000.0 / span;
		assertTrue("the follower talked at " + Math.round(perHour) + " lines an hour,"
			+ " which is not a companion, it is a radio", perHour < 400);

		// There must be real silences in there. A model that never actually
		// rests would show a tight cluster of gaps around the speech floor.
		int longQuiets = 0;
		for (int i = 1; i < spokenAt.size(); i++)
		{
			if (spokenAt.get(i) - spokenAt.get(i - 1) >= 25_000L)
			{
				longQuiets++;
			}
		}
		assertTrue("three hours produced " + spokenAt.size() + " lines at "
			+ Math.round(perHour) + " an hour with only " + longQuiets + " silences"
			+ " past 25 seconds; the rest period is not taking hold",
			longQuiets > 5);
	}

	@Test
	public void anOccasionIsNeverTheThingThatGetsHeldBack() throws IOException
	{
		// The promise the whole occasion flag exists to keep, checked against
		// the shipped rules over hours rather than against a hand-made pair.
		Harness h = soakHarness();
		h.gameTicks(3);

		Set<String> heldOccasions = new HashSet<>();
		h.engine.setOnSuppressed((rule, why) ->
		{
			if (rule.isOccasion() && ("relax".equals(why) || "settling".equals(why)))
			{
				heldOccasions.add(rule.id + " by " + why);
			}
		});

		h.engine.getContext().setSessionCount(1);       // settling damper live too
		play(h, TICKS, new Random(4242L));

		assertTrue("the director held back lines it must never hold: " + heldOccasions,
			heldOccasions.isEmpty());
	}
}
