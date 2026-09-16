package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The incident, with its particulars. "How close that was" is a hint; "how
 * close that was with the Hill Giant, 6 hitpoints left, in Edgeville, three
 * days ago" is a memory two people share, and the point of keeping one.
 */
public class IncidentTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static Map<Integer, String> names()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		names.put(12853, "Varrock: west bank, centre and palace");
		return names;
	}

	private static String said(Harness h, String ruleId)
	{
		List<Harness.Spoken> fired = h.firedBy(ruleId);
		assertFalse(ruleId + " never fired", fired.isEmpty());
		return fired.get(0).text;
	}

	@Test
	public void theCloseCallIsFiledWithWhoAndHowClose() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setRegionNames(names());
		h.game.regions(12342).at(3090, 3490, 0).hitpoints(6, 99);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		TriggerContext context = h.engine.getContext();
		assertTrue("the close call was filed", context.hasIncident());
		assertEquals("close-call", context.getIncidentKey());
		assertEquals("how close that was with the Hill Giant, 6 hitpoints left",
			context.getIncidentPhrase());
		assertEquals("Hill Giant", context.getIncidentWho());
		assertEquals(12342, context.getIncidentWhere());
		assertEquals(" in Edgeville", context.getMemoryWhere());
	}

	@Test
	public void aPlaceholderWithNothingToSayFilesThePlainForm() throws IOException
	{
		// A death with no combat target on record: "the way that went with
		// the " would be a hole, so the plain form is filed instead.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.death());
		TriggerContext context = h.engine.getContext();
		assertEquals("the-death", context.getIncidentKey());
		assertEquals("the way that went", context.getIncidentPhrase());
		assertEquals("", context.getIncidentWho());
	}

	@Test
	public void aDropIsRememberedByName() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.loot(2_000_000, "Abyssal whip"));
		assertEquals("the Abyssal whip that dropped", h.engine.getContext().getIncidentPhrase());
	}

	@Test
	public void whenAndWhereReadAsSomebodyWouldSayThem()
	{
		TriggerContext context = new TriggerContext(new com.follower.sim.FakeGame().client);
		assertEquals("", context.getMemoryWhen(5_000L));
		context.setRegionNames(names());
		long now = 10_000_000_000L;
		context.noteIncident("the-boss", "the General Graardor going down", "General Graardor", now - 30_000L);
		assertEquals("just now", context.getMemoryWhen(now));
		context.noteIncident("the-boss", "the General Graardor going down", "General Graardor",
			now - 3L * 24 * 60 * 60_000L);
		assertEquals("three days ago", context.getMemoryWhen(now));
		assertEquals("a repeat keeps the count", 2, context.getIncidentCount());
		context.restoreIncident("the-boss", "the size of that thing", 1);
		assertEquals("an old record with no date", "a while ago", context.getMemoryWhen(now));
		assertEquals("Varrock", context.placeName(12853));
		assertEquals("", context.placeName(999));
	}

	@Test
	public void theRecallLinesSayTheParticulars() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setRegionNames(names());
		h.game.regions(12342).at(3090, 3490, 0);
		h.gameTicks(1);
		h.engine.getContext().noteIncident("close-call",
			"how close that was with the Hill Giant, 6 hitpoints left", "Hill Giant",
			System.currentTimeMillis() - 3L * 24 * 60 * 60_000L);
		// Away and back: the return is the rising edge the place recall fires on.
		h.game.regions(12850).at(3222, 3218, 0);
		h.gameTicks(2);
		h.game.regions(12342).at(3090, 3490, 0);
		h.gameTicks(12);
		String line = said(h, "recall-here");
		assertTrue("the memory in full: " + line, line.contains("Hill Giant"));

		// The giant back in sight.
		Harness foe = new Harness(folder.newFolder().toPath());
		foe.engine.getContext().setRegionNames(names());
		foe.gameTicks(1);
		foe.engine.getContext().noteIncident("close-call",
			"how close that was with the Hill Giant, 6 hitpoints left", "Hill Giant",
			System.currentTimeMillis() - 3L * 24 * 60 * 60_000L);
		NPC giant = foe.game.spawnNpc(2098, "Hill Giant", 28);
		foe.game.moveNpc(giant, foe.game.player.getWorldLocation().getX() + 4,
			foe.game.player.getWorldLocation().getY(), 0);
		foe.gameTicks(8);
		String seen = said(foe, "recall-foe");
		assertTrue("names the thing or the memory: " + seen,
			seen.contains("Hill Giant"));
		assertFalse("no placeholder printed literally: " + seen, seen.contains("{"));
	}

	@Test
	public void everyRememberHasAPlainFormForWhenTheParticularsAreMissing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (com.follower.speech.SpeechRule rule : h.loader.getRules())
		{
			if (rule.remember == null || rule.remember.as == null || rule.remember.as.indexOf('{') < 0)
			{
				continue;
			}
			assertTrue(rule.id + " fills its memory in from placeholders and needs a plain form",
				rule.remember.plain != null && !rule.remember.plain.isEmpty());
		}
		assertEquals(Collections.emptyList(), h.firedBy("nothing"));
	}
}
