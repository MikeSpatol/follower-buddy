package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * How often a memory comes back, and how soon. Text QA5, 18 Oct: recall-idle
 * named the Castle Wars death eighteen times in one day. Quests QA5: a quest
 * moment brought up seconds after it happened, "Persten going into the rift,
 * earlier today" fifteen seconds after Persten went.
 */
public class MemoryRecallPacingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Nine in the morning, so a few hours on is still the same day. */
	private final long[] ms = {LocalDate.of(2026, 10, 18).atTime(9, 0)
		.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()};

	private static final String IDLE_RECALL = "{\"id\": \"recall-idle\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"levelUp\", \"names\": [\"Attack\"]}, {\"type\": \"idle\", \"ticks\": 0}]},"
		+ " \"say\": [\"My mind goes back to {memory}.\"]}";

	private static final String HERE_RECALL = "{\"id\": \"place-remembers\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"happenedHere\"}, \"say\": [\"This is where {here}.\"]}";

	private Harness harness(String rules) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": [" + rules + "]}");
		h.engine.setClock(() -> ms[0]);
		h.gameTicks(1);
		return h;
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	private void level(Harness h)
	{
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 1);
	}

	@Test
	public void aMemoryIsNotBroughtUpUntilItIsTwentyMinutesOld() throws IOException
	{
		Harness h = harness(IDLE_RECALL);
		h.engine.getContext().noteIncident("rift", "Persten going into the rift", "", ms[0]);
		ticks(h, 25);
		level(h);
		assertEquals("not fifteen seconds on", 0, h.firedBy("recall-idle").size());

		ticks(h, TriggerContext.MEMORY_MIN_AGE_TICKS);
		level(h);
		assertEquals("twenty minutes on", 1, h.firedBy("recall-idle").size());
	}

	@Test
	public void oneMemoryIsBroughtUpUnpromptedOnceADay() throws IOException
	{
		Harness h = harness(IDLE_RECALL);
		h.engine.getContext().noteIncident("death", "your death near Castle Wars", "", ms[0]);
		ticks(h, TriggerContext.MEMORY_MIN_AGE_TICKS);
		level(h);
		assertEquals(1, h.firedBy("recall-idle").size());

		ms[0] += 3 * 3_600_000L;
		level(h);
		assertEquals("not again the same day", 1, h.firedBy("recall-idle").size());

		ms[0] += 24 * 3_600_000L;
		level(h);
		assertEquals("the next day", 2, h.firedBy("recall-idle").size());
	}

	@Test
	public void aNewMemoryIsNewThatDay() throws IOException
	{
		Harness h = harness(IDLE_RECALL);
		h.engine.getContext().noteIncident("death", "your death near Castle Wars", "", ms[0]);
		ticks(h, TriggerContext.MEMORY_MIN_AGE_TICKS);
		level(h);
		h.engine.getContext().noteIncident("dog", "the dog in the mausoleum", "", ms[0]);
		ticks(h, TriggerContext.MEMORY_MIN_AGE_TICKS);
		level(h);
		assertEquals(2, h.firedBy("recall-idle").size());
		assertEquals("My mind goes back to the dog in the mausoleum.", h.firedBy("recall-idle").get(1).text);
	}

	@Test
	public void theDayIsKeptWithTheMemory()
	{
		TriggerContext context = new TriggerContext(null);
		context.noteIncident("death", "your death near Castle Wars", "", ms[0]);
		context.noteIncidentRecalled(ms[0]);
		TriggerContext restored = new TriggerContext(null);
		restored.restoreIncident("death", "your death near Castle Wars", 1, 9520, ms[0], "");
		restored.restoreIncidentRecalled(context.getIncidentRecalledMs());
		assertEquals(true, restored.wasIncidentRecalledToday(ms[0] + 3_600_000L));
	}

	@Test
	public void aPlaceIsNotRememberedUntilItsMemoryIsTwentyMinutesOld() throws IOException
	{
		// Real play, 19 Oct: a record hit at sea, then a minute later and
		// back over the same border, "This is where you hit harder than you
		// ever had."
		Harness h = harness(HERE_RECALL);
		h.game.at(3222, 3218, 0);
		ticks(h, 1);
		h.engine.getContext().notePlaceMemory("you hit a 15");
		h.game.at(3222, 3290, 0);
		ticks(h, 2);
		h.game.at(3222, 3218, 0);
		ticks(h, 100);
		assertEquals("not a minute on", 0, h.firedBy("place-remembers").size());

		h.game.at(3222, 3290, 0);
		ticks(h, TriggerContext.MEMORY_MIN_AGE_TICKS);
		h.game.at(3222, 3218, 0);
		ticks(h, 2);
		assertEquals("back twenty minutes on", 1, h.firedBy("place-remembers").size());
	}

	@Test
	public void theIdleRecallIsTwoHoursApart() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		assertEquals(7_200_000L, h.rule("recall-idle").cooldownMs);
	}
}
