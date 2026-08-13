package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Drawing without replacement, because a player does not hear "random" - they
 * hear the second time.
 *
 * <p>Uniform selection with an anti-immediate-repeat guard sounds correct and
 * is not. Thirty variants start repeating after about eight draws, which means
 * three quarters of the writing in the largest rule in the file was being paid
 * for and not heard. A bag gives all thirty before any of them comes round
 * again.
 */
public class ShuffleBagTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static SpeechRule of(String... lines)
	{
		SpeechRule rule = new SpeechRule();
		rule.id = "t";
		rule.say = Arrays.asList(lines);
		return rule;
	}

	private static SpeechRule ofSize(int n)
	{
		String[] lines = new String[n];
		for (int i = 0; i < n; i++)
		{
			lines[i] = "line " + i;
		}
		return of(lines);
	}

	// ------------------------------------------------------------- line wear

	@Test
	public void aWornLineStandsAsideWhileAnythingFresherExists()
	{
		// R17: the player does not hear "well written", they hear the fortieth
		// telling. Past the threshold a line waits its siblings out.
		SpeechRule rule = of("worn out", "fresh one", "fresh two");
		java.util.function.ToIntFunction<String> wear =
			line -> line.equals("worn out") ? SpeechRule.TIRED_AFTER : 0;

		for (int i = 0; i < 30; i++)
		{
			assertFalse("the worn line came back with fresher ones available",
				"worn out".equals(rule.pickPhrase(
					java.util.Collections.emptySet(), wear)));
		}
	}

	@Test
	public void freshLinesStillArriveAsFullCycles()
	{
		// The bag's guarantee survives the retirement: a cycle is still a
		// cycle, just of what remains.
		SpeechRule rule = of("worn out", "fresh one", "fresh two");
		java.util.function.ToIntFunction<String> wear =
			line -> line.equals("worn out") ? 99 : 0;

		for (int cycle = 0; cycle < 10; cycle++)
		{
			Set<String> drawn = new HashSet<>();
			drawn.add(rule.pickPhrase(java.util.Collections.emptySet(), wear));
			drawn.add(rule.pickPhrase(java.util.Collections.emptySet(), wear));
			assertEquals("cycle " + cycle + " should hold both fresh lines",
				new HashSet<>(Arrays.asList("fresh one", "fresh two")), drawn);
		}
	}

	@Test
	public void wearNeverStarvesALineOverTheLongRun()
	{
		// The property that matters across months: retirement redistributes,
		// it never silences. Start with one line nearly worn out and the
		// rest fresh, accrue wear the way real play does (one per say), and
		// after everything has crossed the threshold the bag must go back to
		// dealing all four evenly.
		SpeechRule rule = of("old favourite", "new one", "new two", "new three");
		java.util.Map<String, Integer> wear = new java.util.HashMap<>();
		wear.put("old favourite", SpeechRule.TIRED_AFTER - 1);

		java.util.Map<String, Integer> lastForty = new java.util.HashMap<>();
		for (int draw = 0; draw < 200; draw++)
		{
			String said = rule.pickPhrase(java.util.Collections.emptySet(),
				line -> wear.getOrDefault(line, 0));
			wear.merge(said, 1, Integer::sum);
			if (draw >= 160)
			{
				lastForty.merge(said, 1, Integer::sum);
			}
		}

		assertEquals("every line is still in service at the end",
			4, lastForty.size());
		int most = 0;
		int fewest = Integer.MAX_VALUE;
		for (int n : lastForty.values())
		{
			most = Math.max(most, n);
			fewest = Math.min(fewest, n);
		}
		assertTrue("the last forty draws dealt " + most + " of one line and "
			+ fewest + " of another; retirement must settle back to even",
			most - fewest <= 3);
	}

	@Test
	public void whenEveryLineIsWornTheyAllComeBack()
	{
		// Wear is relative, not absolute: retiring every line would retire
		// the rule, and a familiar line beats silence.
		SpeechRule rule = ofSize(4);
		java.util.function.ToIntFunction<String> wear = line -> 999;

		Set<String> drawn = new HashSet<>();
		for (int i = 0; i < 4; i++)
		{
			drawn.add(rule.pickPhrase(java.util.Collections.emptySet(), wear));
		}
		assertEquals("all four should serve when all four are equally worn",
			4, drawn.size());
	}

	// ------------------------------------------------------------- the bag

	@Test
	public void everyLineIsHeardBeforeAnyIsHeardTwice()
	{
		for (int size : new int[]{2, 3, 5, 12, 15, 30})
		{
			SpeechRule rule = ofSize(size);
			Set<String> drawn = new HashSet<>();
			for (int i = 0; i < size; i++)
			{
				assertTrue("size " + size + ": line repeated after " + i + " draws",
					drawn.add(rule.pickPhrase()));
			}
			assertEquals("the bag should have held every line", size, drawn.size());
		}
	}

	@Test
	public void theBagRefillsAndKeepsGoing()
	{
		SpeechRule rule = ofSize(6);
		for (int cycle = 0; cycle < 20; cycle++)
		{
			Set<String> drawn = new HashSet<>();
			for (int i = 0; i < 6; i++)
			{
				drawn.add(rule.pickPhrase());
			}
			assertEquals("cycle " + cycle + " did not cover the whole set", 6, drawn.size());
		}
	}

	@Test
	public void theSeamBetweenTwoBagsIsNotARepeat()
	{
		// The one weakness a bag has: the last line of one and the first of the
		// next are adjacent draws, and nothing about shuffling stops them being
		// the same line. Left alone it happens once per bag - MORE often than
		// the uniform draw this replaced, and at a predictable interval.
		for (int attempt = 0; attempt < 500; attempt++)
		{
			SpeechRule rule = ofSize(4);
			String previous = null;
			for (int i = 0; i < 40; i++)
			{
				String line = rule.pickPhrase();
				assertFalse("said \"" + line + "\" twice running", line.equals(previous));
				previous = line;
			}
		}
	}

	@Test
	public void theOrderIsNotTheSameEveryBag()
	{
		// A bag that always deals in the same order is a rota, which is worse
		// than random rather than better - it is learnable.
		Set<String> orders = new HashSet<>();
		for (int attempt = 0; attempt < 100; attempt++)
		{
			SpeechRule rule = ofSize(5);
			StringBuilder order = new StringBuilder();
			for (int i = 0; i < 5; i++)
			{
				order.append(rule.pickPhrase()).append('|');
			}
			orders.add(order.toString());
		}
		assertTrue("every bag dealt in the same order", orders.size() > 10);
	}

	@Test
	public void theEasyCasesStillWork()
	{
		assertEquals("", new SpeechRule().pickPhrase());
		assertEquals("only", of("only").pickPhrase());
		assertEquals("only", of("only").pickPhrase());
	}

	// ------------------------------------------------- the corpus-wide window

	@Test
	public void theUnheardLineComesFirst()
	{
		// Not "comes every time" - the bag still deals all three. What the
		// window buys is ORDER: whichever the shuffle put first, the one the
		// player has not heard lately is the one that comes out now.
		Set<String> recent = new HashSet<>(Arrays.asList("alpha", "gamma"));
		for (int attempt = 0; attempt < 200; attempt++)
		{
			SpeechRule rule = of("alpha", "beta", "gamma");
			assertEquals("the unheard line should be brought forward",
				"beta", rule.pickPhrase(recent));
		}
	}

	@Test
	public void skippingReordersTheBagWithoutDamagingIt()
	{
		// Skipping reorders the bag, and reordering is the operation most able
		// to lose or duplicate an entry by accident. Twelve lines with four in
		// the window means several swaps happen inside one bag, which is where
		// an off-by-one in the shuffling would show.
		//
		// Two corrections are baked into the shape of this. It first used three
		// lines and one skip, which is too small for a reordering fault to be
		// visible at all. And the window was written as "l1", "l4" against
		// lines that ofSize calls "line 1", "line 4" - so it matched nothing,
		// no skip ever ran, and the test exercised none of the code it names.
		Set<String> recent = new HashSet<>(
			Arrays.asList("line 1", "line 4", "line 7", "line 9"));
		for (int attempt = 0; attempt < 300; attempt++)
		{
			SpeechRule rule = ofSize(12);
			List<String> drawn = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				drawn.add(rule.pickPhrase(recent));
			}
			assertEquals("the bag lost or duplicated a line: " + drawn,
				12, new HashSet<>(drawn).size());
		}
	}

	@Test
	public void aFullWindowNeverProducesSilence()
	{
		// The failure that matters. If every line is in the window the follower
		// must still speak: a repeated line beats no line, and the alternative
		// is a rule that has quietly stopped working.
		SpeechRule rule = of("alpha", "beta");
		Set<String> recent = new HashSet<>(Arrays.asList("alpha", "beta"));

		for (int i = 0; i < 50; i++)
		{
			String line = rule.pickPhrase(recent);
			assertTrue("said something that is not in the rule: " + line,
				recent.contains(line));
		}
	}

	@Test
	public void skippingDoesNotCostTheSkippedLineItsTurn()
	{
		// A line passed over has to go back in the bag rather than be dropped,
		// or the window would quietly shrink every rule it touches.
		SpeechRule rule = of("alpha", "beta", "gamma", "delta");
		Set<String> recent = new HashSet<>(java.util.Collections.singletonList("alpha"));

		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 4; i++)
		{
			seen.add(rule.pickPhrase(recent));
		}
		assertEquals("all four should still come out inside one bag", 4, seen.size());
	}

	// ------------------------------------------------------ through the engine

	@Test
	public void theWindowCarriesTheBagsGuaranteeAcrossARefill()
	{
		// What the corpus window is actually for, once you notice that
		// noTwoRulesShareASentence already forbids two rules owning the same
		// line: the bag's promise stops dead at the refill, and without help
		// the first draw of a new bag can be something said two lines ago.
		//
		// With a window of twelve and a rule of fifteen, exactly three lines
		// have aged out by the time the bag turns over - and those three are
		// the ones that should come next.
		for (int attempt = 0; attempt < 100; attempt++)
		{
			SpeechRule rule = ofSize(15);
			List<String> order = new ArrayList<>();
			for (int i = 0; i < 15; i++)
			{
				order.add(rule.pickPhrase());
			}

			Set<String> window = new HashSet<>(order.subList(3, 15));
			Set<String> aged = new HashSet<>(order.subList(0, 3));

			for (int i = 0; i < 3; i++)
			{
				String next = rule.pickPhrase(window);
				assertTrue("the new bag opened with \"" + next + "\", which was"
					+ " said inside the window", aged.remove(next));
			}
		}
	}

	@Test
	public void theEngineActuallyFeedsTheWindow() throws IOException
	{
		// The first version of this only asserted no back-to-back repeats,
		// which the bag alone already guarantees - so a mutation that stopped
		// the engine feeding the window entirely went unnoticed. Nothing tested
		// the wiring, only the thing on the far end of it.
		//
		// Thirteen variants against a window of twelve makes it deterministic.
		// After a full bag, exactly one line has aged out of the window: the
		// one said first. It is therefore the only line the new bag may open
		// with, and any other answer means the window is empty.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"many\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"v0\", \"v1\", \"v2\", \"v3\", \"v4\", \"v5\", \"v6\","
				+ " \"v7\", \"v8\", \"v9\", \"v10\", \"v11\", \"v12\"]}]}");
		h.gameTicks(1);

		List<String> said = new ArrayList<>();
		for (int i = 0; i < 14; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}
		for (Harness.Spoken s : h.spoken)
		{
			said.add(s.text);
		}

		assertEquals("all fourteen firings should have spoken", 14, said.size());
		assertEquals("the first thirteen are one whole bag",
			13, new HashSet<>(said.subList(0, 13)).size());
		assertEquals("the fourteenth line should be the only one old enough to"
			+ " have left the window, which means the engine is feeding it",
			said.get(0), said.get(13));
	}

	@Test
	public void theWindowIsKeyedOnTheTemplateAndNotTheRenderedText()
		throws IOException
	{
		// "{count}. Keeping track" is one line however many numbers it has
		// carried. Keying on the finished string would let it through every
		// time the count changed - which is every time.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"counted\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"that is {hp} of them\", \"and another\"]}]}");
		h.gameTicks(1);

		for (int i = 0; i < 6; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}

		List<String> said = new ArrayList<>();
		for (Harness.Spoken s : h.spoken)
		{
			said.add(s.text);
		}
		assertTrue("nothing came out at all", said.size() >= 4);
		for (int i = 1; i < said.size(); i++)
		{
			assertFalse("said \"" + said.get(i) + "\" twice running",
				said.get(i).equals(said.get(i - 1)));
		}
	}

	@Test
	public void aWorldHopDoesNotDealAFreshBag() throws IOException
	{
		// resetForNewScene clears edges so rules may fire again. If it cleared
		// bags too, a player who hops worlds regularly would be handed the
		// front of a fresh shuffle every time and hear the same few lines for
		// it - the exact failure the bag exists to remove.
		SpeechRule rule = ofSize(6);
		Set<String> first = new HashSet<>();
		for (int i = 0; i < 3; i++)
		{
			first.add(rule.pickPhrase());
		}

		rule.resetEdges();

		for (int i = 0; i < 3; i++)
		{
			assertTrue("a hop mid-bag must not re-deal what was already said",
				first.add(rule.pickPhrase()));
		}
		assertEquals(6, first.size());
	}
}
