package com.follower;

import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * Being away from the keys trails off as standing still does. Behaviour
 * QA5, 20 Oct: gone-away was 11 of the day's 66 lines, every twenty minutes
 * for as long as the player was away; its condition is "unattended", which
 * the trail-off did not count.
 */
public class GoneAwayTrailsOffTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	@Test
	public void theFifthAwayLineWaitsLongerThanTheFourth() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"gone-away\", \"group\": \"t\", \"cooldownMs\": 0, \"when\": {\"type\": \"all\","
			+ " \"conditions\": [{\"type\": \"unattended\", \"ticks\": 0}, {\"type\": \"chatMessage\", \"contains\": \"tick\"}]},"
			+ " \"say\": [\"You've gone, haven't you.\", \"Still gone.\"]}]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(45_000L);
		h.gameTicks(1);
		for (int i = 0; i < 5; i++)
		{
			h.someoneSays("Clock", "tick");
			h.gameTicks(1);
			ms[0] += 20 * 60_000L;
		}
		assertEquals("four, then the fifth waits its trail", 4, h.firedBy("gone-away").size());
		ms[0] += 20 * 60_000L;
		h.someoneSays("Clock", "tick");
		assertEquals("and comes, later", 5, h.firedBy("gone-away").size());
	}
}
