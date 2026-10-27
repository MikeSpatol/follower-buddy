package com.follower;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The running bits tell their stages in order, whenever the player met the
 * follower.
 *
 * <p>The stages used to be keyed on the days since the first meeting. A
 * follower already past a late stage's day when the bit arrived heard the
 * opener and the ending in the same session: "I've started reading a history
 * of fences" and "Finished the fence book" minutes apart (QA pass 2, #29).
 * They now count from the day the opener was said.
 */
public class RunningBitOrderTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final List<String> FENCE = Arrays.asList(
		"fun-fence-book-start", "fun-fence-book-early", "fun-fence-book-middle",
		"fun-fence-book-finished", "fun-fence-book-sequel");
	private static final List<String> GULL = Arrays.asList(
		"fun-gull-rival-first", "fun-gull-rival-early", "fun-gull-rival-middle",
		"fun-gull-rival-late");
	private static final List<String> HANS = Arrays.asList(
		"fun-hans-race-first", "fun-hans-race-early", "fun-hans-race-middle",
		"fun-hans-race-late");
	private static final List<String> GERALD = Arrays.asList(
		"fun-gerald-first", "fun-gerald-early", "fun-gerald-later");

	/** Just these shipped rules, as the bundled file has them. */
	private Harness harnessWith(List<String> ids) throws IOException
	{
		JsonArray rules;
		try (InputStream in = getClass().getResourceAsStream("/com/follower/default-phrases.json"))
		{
			rules = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonArray("rules");
		}
		Set<String> wanted = new HashSet<>(ids);
		JsonArray kept = new JsonArray();
		for (JsonElement rule : rules)
		{
			if (wanted.contains(rule.getAsJsonObject().get("id").getAsString()))
			{
				kept.add(rule);
			}
		}
		assertEquals("every rule of the bit is still shipped", ids.size(), kept.size());
		JsonObject file = new JsonObject();
		file.addProperty("version", 1);
		file.add("rules", kept);
		return new Harness(folder.newFolder().toPath(), file.toString());
	}

	private static long daysAgo(int days)
	{
		return LocalDate.now().toEpochDay() - days;
	}

	private static Set<String> heard(Harness h, List<String> ids)
	{
		Set<String> out = new HashSet<>();
		for (String id : ids)
		{
			if (!h.firedBy(id).isEmpty())
			{
				out.add(id);
			}
		}
		return out;
	}

	@Test
	public void aFollowerFiftyDaysInOpensTheFenceBookAndDoesNotFinishIt() throws IOException
	{
		int opened = 0;
		for (int trial = 0; trial < 40; trial++)
		{
			Harness h = harnessWith(FENCE);
			h.engine.getContext().setMetOnDay(daysAgo(50));
			h.gameTicks(700);
			Set<String> said = heard(h, FENCE);
			if (said.contains("fun-fence-book-start"))
			{
				opened++;
			}
			assertEquals("trial " + trial + ": only the opener, on the day it opens: " + said,
				Collections.emptySet(), minus(said, "fun-fence-book-start"));
		}
		assertTrue("the opener itself still comes (" + opened + " of 40)", opened >= 30);
	}

	@Test
	public void theFenceStagesCountFromTheOpenersDay() throws IOException
	{
		int[][] stages = {{5, 1}, {20, 2}, {50, 3}, {120, 4}};
		for (int[] stage : stages)
		{
			// Each stage rolls a small chance in its idle window, so one
			// session can pass without it: a few sessions, and never the
			// wrong stage in any of them.
			boolean heardIt = false;
			for (int session = 0; session < 5 && !heardIt; session++)
			{
				Harness h = harnessWith(FENCE);
				// Met long ago; the opener said some days back.
				h.engine.getContext().setMetOnDay(daysAgo(400));
				h.engine.getContext().restoreSpokenOnce(
					Collections.singletonList("fun-fence-book-start"),
					Collections.singletonMap("fun-fence-book-start", daysAgo(stage[0])));
				h.gameTicks(700);
				Set<String> said = heard(h, FENCE);
				assertTrue(stage[0] + " days after the opener, only its stage: " + said,
					minus(said, FENCE.get(stage[1])).isEmpty());
				heardIt = said.contains(FENCE.get(stage[1]));
			}
			assertTrue(stage[0] + " days after the opener, its stage is heard", heardIt);
		}

		Harness unopened = harnessWith(Arrays.asList(FENCE.get(1), FENCE.get(2), FENCE.get(3), FENCE.get(4)));
		unopened.engine.getContext().setMetOnDay(daysAgo(400));
		unopened.gameTicks(700);
		assertTrue("no stage before the opener has been heard", unopened.spoken.isEmpty());
	}

	@Test
	public void aFollowerNinetyDaysInMeetsItsGullRivalBeforeMakingPeace() throws IOException
	{
		Harness h = harnessWith(GULL);
		h.engine.getContext().setMetOnDay(daysAgo(120));
		for (int sighting = 0; sighting < 4; sighting++)
		{
			h.game.spawnNpc(1338, "Seagull", 2);
			h.gameTicks(5);
			h.game.clearNpcs();
			h.gameTicks(5);
		}
		assertEquals("the rivalry opens, and nothing later follows it the same day",
			Collections.singleton("fun-gull-rival-first"), heard(h, GULL));
	}

	@Test
	public void aFollowerAHundredDaysInStartsTheRaceWithHansBeforeCallingItADraw() throws IOException
	{
		Harness h = harnessWith(HANS);
		h.engine.getContext().setMetOnDay(daysAgo(150));
		for (int sighting = 0; sighting < 4; sighting++)
		{
			h.game.spawnNpc(3077, "Hans", 0);
			h.gameTicks(5);
			h.game.clearNpcs();
			h.gameTicks(5);
		}
		// QA pass 3, #22: the first stage, from day 0, rose the moment the
		// opener was heard and followed it six seconds later.
		assertEquals("the race starts, and no stage follows it the same day",
			Collections.singleton("fun-hans-race-first"), heard(h, HANS));
	}

	@Test
	public void aFollowerSixtyDaysInNamesGeraldAndLeavesItThere() throws IOException
	{
		// QA pass 3, #23: keyed on the days since meeting, a follower known a
		// month heard "Every mine has a Gerald" and then "Gerald's looking
		// well" in the same mining session.
		for (int trial = 0; trial < 10; trial++)
		{
			Harness h = harnessWith(GERALD);
			h.engine.getContext().setMetOnDay(daysAgo(60));
			h.engine.getContext().setWork(true, "mining", "Iron rocks", null, 0, 0, 0);
			h.gameTicks(600);
			assertEquals("trial " + trial, Collections.singleton("fun-gerald-first"), heard(h, GERALD));
		}
	}

	@Test
	public void theGeraldStagesCountFromTheOpenersDay() throws IOException
	{
		for (int[] stage : new int[][]{{5, 1}, {40, 2}})
		{
			// A chance roll in its window, as the fence stages: a few
			// sessions, and never the wrong stage in any of them.
			boolean heardIt = false;
			for (int session = 0; session < 5 && !heardIt; session++)
			{
				Harness h = harnessWith(GERALD);
				h.engine.getContext().setMetOnDay(daysAgo(400));
				h.engine.getContext().restoreSpokenOnce(
					Collections.singletonList("fun-gerald-first"),
					Collections.singletonMap("fun-gerald-first", daysAgo(stage[0])));
				h.engine.getContext().setWork(true, "mining", "Iron rocks", null, 0, 0, 0);
				h.gameTicks(1500);
				Set<String> said = heard(h, GERALD);
				assertTrue(stage[0] + " days after the opener, only its stage: " + said,
					minus(said, GERALD.get(stage[1])).isEmpty());
				heardIt = said.contains(GERALD.get(stage[1]));
			}
			assertTrue(stage[0] + " days after the opener, its stage is heard", heardIt);
		}
	}

	@Test
	public void aOneTimeLineIsDatedAndTheDateSurvivesARestart()
	{
		Gson gson = new Gson();
		TriggerContext before = new TriggerContext(new FakeGame().client);
		before.noteSaidOnce("fun-fence-book-start");
		assertEquals("said today", 0, before.daysSinceSaid("fun-fence-book-start"));
		assertEquals("never said", -1, before.daysSinceSaid("fun-gull-rival-first"));
		before.restoreSpokenOnce(Collections.singletonList("fun-hans-race-first"),
			Collections.singletonMap("fun-hans-race-first", daysAgo(30)));

		TriggerContext after = new TriggerContext(new FakeGame().client);
		FollowerPlugin.restoreMemory(after, FollowerPlugin.snapshotMemory(before, 0, gson), gson, 0);
		assertEquals(0, after.daysSinceSaid("fun-fence-book-start"));
		assertEquals(30, after.daysSinceSaid("fun-hans-race-first"));
	}

	@Test
	public void aOneTimeLineFromAnOlderSaveIsDatedTheDayItIsRead()
	{
		// Saves from before the dates were kept have the ids alone. Dated
		// from the first meeting, a bit opened long ago would jump straight to
		// its end; dated today, it starts again from its first stage.
		Gson gson = new Gson();
		String older = "{\"sessions\":40,\"metOnDay\":" + daysAgo(66)
			+ ",\"spokenOnce\":[\"fun-fence-book-start\",\"first-meeting\"],\"deathPlane\":-1}";
		TriggerContext after = new TriggerContext(new FakeGame().client);
		FollowerPlugin.restoreMemory(after, older, gson, 0);
		assertTrue(after.hasSaidOnce("fun-fence-book-start"));
		assertEquals(0, after.daysSinceSaid("fun-fence-book-start"));
		assertTrue("and the dates are written back", after.isCountersDirty());
	}

	@Test
	public void everyStageCountsFromAShippedOneTimeRule() throws IOException
	{
		// A daysSinceSaid naming a rule that is gone, or one that is not
		// "once", is never true and fails silently.
		Harness h = new Harness(folder.newFolder().toPath());
		Set<String> once = new HashSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.isOnce())
			{
				once.add(rule.id);
			}
		}
		List<String> bad = new java.util.ArrayList<>();
		List<String> sameDay = new java.util.ArrayList<>();
		int found = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			for (Condition c : flatten(rule.when))
			{
				if ("daysSinceSaid".equalsIgnoreCase(c.type))
				{
					found++;
					if (!once.contains(c.rule))
					{
						bad.add(rule.id + " -> " + c.rule);
					}
					// From day 0 a stage rises the moment its opener is said.
					if (c.minimum == null || c.minimum < 1)
					{
						sameDay.add(rule.id);
					}
				}
			}
		}
		assertTrue("the running bits use it", found >= 11);
		assertTrue("counting from no once rule: " + bad, bad.isEmpty());
		assertTrue("a stage that can share its opener's day: " + sameDay, sameDay.isEmpty());
	}

	private static List<Condition> flatten(Condition c)
	{
		List<Condition> out = new java.util.ArrayList<>();
		if (c != null)
		{
			out.add(c);
			if (c.conditions != null)
			{
				for (Condition child : c.conditions)
				{
					out.addAll(flatten(child));
				}
			}
		}
		return out;
	}

	private static Set<String> minus(Set<String> all, String gone)
	{
		Set<String> out = new HashSet<>(all);
		out.remove(gone);
		return out;
	}
}
