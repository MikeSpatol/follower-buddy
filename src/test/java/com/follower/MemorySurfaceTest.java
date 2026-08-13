package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.follower.ui.MemoryDialog;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The memory surface (R20): what the window claims has to match what the
 * follower actually holds, in both directions - a fresh follower must not be
 * dressed up with history, and a lived-in one must not have its memory
 * understated. The window itself is Swing; the claims are a pure function.
 */
public class MemorySurfaceTest
{
	private static String flat(List<String[]> rows)
	{
		StringBuilder out = new StringBuilder();
		for (String[] row : rows)
		{
			out.append(row[0]);
			if (row[1] != null)
			{
				out.append(": ").append(row[1]);
			}
			out.append('\n');
		}
		return out.toString();
	}

	@Test
	public void aLivedInMemoryShowsItsContents()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.setMetOnDay(LocalDate.now().toEpochDay() - 31);
		context.setSessionCount(36);
		context.tally("kill:goblin");
		context.tally("kill:goblin");
		context.noteRecord("hit", 42);
		context.noteIncident("chicken-death", "that chicken");
		context.notePlaceFeeling(45);
		context.notePlaceMemory("the drop");
		context.noteSaidOnce("first-meeting");
		context.noteLineSaid("a line");
		context.setTraits(java.util.Collections.singleton(12850),
			java.util.Collections.singleton(10553));
		context.setWish("feather", 5, java.util.Collections.singletonList(314));

		String surface = flat(MemoryDialog.summarise(context));

		assertTrue(surface.contains("(31 days ago)"));
		assertTrue(surface.contains("Sessions together: 36"));
		assertTrue(surface.contains("kill:goblin: 2"));
		assertTrue(surface.contains("hit: 42"));
		assertTrue(surface.contains("that chicken (x1)"));
		assertTrue("the place, its feeling and its memory: " + surface,
			surface.contains("+45 - the drop"));
		assertTrue(surface.contains("region 12850"));
		assertTrue(surface.contains("region 10553"));
		assertTrue(surface.contains("Hoping for: feather"));
		assertTrue(surface.contains("Firsts already said: 1"));
		assertTrue(surface.contains("Lines wear-tracked: 1"));
	}

	@Test
	public void aStrangerHasNothingToShow()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		String surface = flat(MemoryDialog.summarise(context));

		assertTrue(surface.contains("First met: not recorded yet"));
		assertTrue(surface.contains("Sessions together: 0"));
		assertTrue(surface.contains("Nothing counted yet."));
		assertTrue(surface.contains("No records held yet."));
		assertTrue(surface.contains("Nothing has stuck yet."));
		assertTrue(surface.contains("Opinions held: 0"));
		assertTrue(surface.contains("nowhere in particular"));
		assertTrue(surface.contains("Nothing in hand."));
		assertTrue(surface.contains("Firsts already said: 0"));

		// The other direction of honesty: no leftover-looking claims.
		assertFalse(surface.contains("x1"));
		assertFalse(surface.contains("Hoping for"));
	}
}
