package com.follower;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A callback about a quest's person stands aside the plain line about them.
 * QA, 15 Oct (probe P2): passing the Duke a few times in ten minutes gave
 * both callbacks and then "Duke Horacio himself. Lovely man.", as if the two
 * had never met. The callbacks keep three hours apart and the plain line one,
 * and nothing tied them together. Text QA pass 3, #70: and the two callbacks
 * themselves came about two minutes apart.
 */
public class CallbackStandsAsideTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private static String duke(String id, int priority, long cooldownMs)
	{
		return "{\"id\": \"" + id + "\", \"group\": \"t\", \"priority\": " + priority
			+ ", \"cooldownMs\": " + cooldownMs + ", \"waits\": true,"
			+ " \"when\": {\"type\": \"npcNearby\", \"within\": 5, \"names\": [\"Duke Horacio\"]},"
			+ " \"say\": [\"" + id + ".\"]}";
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	/** Walks up to the Duke, stays a minute, and walks off again. */
	private void passTheDuke(Harness h)
	{
		h.game.cameraMoved();
		h.game.spawnNpc(815, "Duke Horacio", 0);
		ticks(h, 100);
		h.game.clearNpcs();
		ticks(h, 200);
	}

	@Test
	public void aCallbackStandsThePlainLineAsideForItsWhile() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ duke("fun-duke-after-rune-mysteries", 37, 10_800_000L) + ","
			+ duke("fun-duke-after-cooks-assistant", 36, 10_800_000L) + ","
			+ duke("quest-duke", 35, 3_600_000L) + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		ticks(h, 1);

		passTheDuke(h);
		passTheDuke(h);
		passTheDuke(h);
		assertEquals("first meeting", 1, h.firedBy("fun-duke-after-rune-mysteries").size());
		assertTrue("not a second callback on the next pass", h.firedBy("fun-duke-after-cooks-assistant").isEmpty());
		assertTrue("not as if they had never met", h.firedBy("quest-duke").isEmpty());

		// An hour on, the callbacks are still cooling and the plain line has its turn.
		ticks(h, 6000);
		passTheDuke(h);
		assertEquals("an hour on", 1, h.firedBy("quest-duke").size());
	}

	@Test
	public void aPlainLineHeldForABoxStandsAsideForTheCallback() throws IOException
	{
		// Behaviour QA, 16 Oct (probe P8): the Duke walks up with a box open.
		// The plain line rises alone, the callback being gated off by the
		// box, and is held for it; the callback rises as it closes and wins.
		// Both were said, 6 s apart. Both wait 2 ticks, as shipped: the plain
		// line gets its beat after the box before the callback's delay is
		// out, so it stands aside at the callback's win, not its saying.
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"fun-duke-after-cooks-assistant\", \"group\": \"t\", \"priority\": 36, \"delayTicks\": 2,"
			+ " \"cooldownMs\": 10800000, \"waits\": true, \"when\": {\"type\": \"all\", \"conditions\": ["
			+ "{\"type\": \"npcNearby\", \"within\": 5, \"names\": [\"Duke Horacio\"]},"
			+ " {\"type\": \"none\", \"conditions\": [{\"type\": \"dialogueOpen\"}]}]},"
			+ " \"say\": [\"The Duke looks well.\"]},"
			+ duke("quest-duke", 35, 3_600_000L).replace("\"waits\": true,", "\"waits\": true, \"delayTicks\": 2,")
			+ "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		ticks(h, 1);
		h.engine.getContext().setDialogueOpen(true);
		h.game.cameraMoved();
		h.game.spawnNpc(815, "Duke Horacio", 0);
		ticks(h, 20);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 30);
		assertEquals("the callback", 1, h.firedBy("fun-duke-after-cooks-assistant").size());
		assertTrue("and not the plain line too", h.firedBy("quest-duke").isEmpty());
	}

	/** The link is by name, so every shipped callback about a person has to find its plain line. */
	@Test
	public void everyShippedCallbackAboutAPersonFindsItsPlainLine() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> lost = new ArrayList<>();
		int callbacks = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.plainLineId() != null && rule.when.usesType("npcNearby"))
			{
				callbacks++;
				if (h.loader.getRules().stream().noneMatch(r -> rule.plainLineId().equals(r.id)))
				{
					lost.add(rule.id + " -> " + rule.plainLineId());
				}
			}
		}
		assertTrue("no callbacks about people found - the scan broke", callbacks >= 9);
		assertTrue("callbacks with no plain line to stand aside: " + lost, lost.isEmpty());
	}
}
