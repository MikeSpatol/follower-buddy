package com.follower;

import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * One remembered thing brought up at a time. Text QA4, 16 Oct: "It's quiet.
 * My mind goes back to the guard with the very large bag." and, 16 s later,
 * "The notes on the guard with the very large bag start here." Each rule
 * had its own cooldown and nothing kept them apart.
 */
public class OneMemoryAtATimeTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private static String rule(String id, String skill, long cooldownMs, String line)
	{
		return "{\"id\": \"" + id + "\", \"group\": \"t\", \"cooldownMs\": " + cooldownMs + ","
			+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"" + skill + "\"]}, \"say\": [\"" + line + "\"]}";
	}

	@Test
	public void aMemoryBroughtUpRestsTheOthersTenMinutes() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ rule("recall-idle", "Attack", 900_000L, "My mind goes back to {memory}.") + ","
			+ rule("recall-here", "Strength", 1_800_000L, "The notes on {memory} start here.") + ","
			+ rule("chatty", "Defence", 0L, "Nice.") + "]}");
		h.engine.setClock(() -> ms[0]);
		h.gameTicks(1);
		// From an earlier session: a memory filed just now is too fresh to bring up.
		h.engine.getContext().restoreIncident("bag", "the guard with the very large bag", 1, 0, 0L, "");

		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		assertEquals(1, h.firedBy("recall-idle").size());
		ms[0] += 16_000L;
		h.dispatch(TriggerEvent.levelUp("Strength", 30));
		h.dispatch(TriggerEvent.levelUp("Defence", 30));
		assertEquals("not the same memory 16 s on", 0, h.firedBy("recall-here").size());
		assertEquals("other lines are not held", 1, h.firedBy("chatty").size());

		ms[0] += 600_000L;
		h.dispatch(TriggerEvent.levelUp("Strength", 31));
		assertEquals("ten minutes on", 1, h.firedBy("recall-here").size());
	}

	@Test
	public void whatAPlaceHoldsIsAMemoryToo() throws IOException
	{
		// Text QA5, 17 Oct: arriving where the player died, "This is where I
		// wrote down the time you died, yesterday." and, 6 s later, "Same
		// spot. This is where it went wrong for you." The spot of the last
		// death counts as well.
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ rule("recall-here", "Attack", 1_800_000L, "The notes on {memory} start here.") + ","
			+ rule("place-remembers", "Strength", 1_200_000L, "Same spot. This is where {here}.") + ","
			+ "{\"id\": \"death-spot\", \"group\": \"t\", \"cooldownMs\": 600000, \"when\": {\"type\": \"all\","
			+ " \"conditions\": [{\"type\": \"levelUp\", \"names\": [\"Defence\"]}, {\"type\": \"nearDeathSpot\"}]},"
			+ " \"say\": [\"This is where it happened.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.game.at(3222, 3218, 0);
		h.gameTicks(1);
		h.engine.getContext().restoreIncident("the-death", "your death to the Hill Giant", 1, 0, 0L, "");
		h.engine.getContext().restorePlaces(
			java.util.Collections.singletonMap(h.engine.getContext().getRegionId(), "you died to the Hill Giant"));
		h.engine.getContext().restoreDeathSpot(new net.runelite.api.coords.WorldPoint(3222, 3218, 0));

		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		assertEquals(1, h.firedBy("recall-here").size());
		ms[0] += 6_000L;
		h.gameTicks(1);
		h.dispatch(TriggerEvent.levelUp("Strength", 30));
		h.dispatch(TriggerEvent.levelUp("Defence", 30));
		assertEquals("not the place's memory 6 s on", 0, h.firedBy("place-remembers").size());
		assertEquals("nor the death spot", 0, h.firedBy("death-spot").size());

		ms[0] += 600_000L;
		h.gameTicks(1);
		h.dispatch(TriggerEvent.levelUp("Strength", 31));
		assertEquals("ten minutes on", 1, h.firedBy("place-remembers").size());
	}
}
