package com.follower.speech;

import com.follower.sim.Harness;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * A running bit starts at its opener. Text QA, 15 Oct: the openers rolled
 * the same small chance a tick as the stages after them, so about half of
 * players heard the fence book's middle before "I've started reading a
 * history of fences", and four in ten "Go easy on Gerald" before "Every mine
 * has a Gerald". The opener now has no roll: it is true on the first tick it
 * can be, and outranks the stages on that tick.
 */
public class RunningBitOrderTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The shipped rules whose ids start with the prefix, and nothing else. */
	private static String shipped(String prefix) throws IOException
	{
		String all = new String(Files.readAllBytes(
			Paths.get("src/main/resources/com/follower/default-phrases.json")), StandardCharsets.UTF_8);
		JsonArray kept = new JsonArray();
		for (JsonElement rule : new JsonParser().parse(all).getAsJsonObject().getAsJsonArray("rules"))
		{
			if (rule.getAsJsonObject().get("id").getAsString().startsWith(prefix))
			{
				kept.add(rule);
			}
		}
		JsonObject file = new JsonObject();
		file.addProperty("version", 1);
		file.add("rules", kept);
		return file.toString();
	}

	@Test
	public void theFenceBookStartsAtTheStart() throws IOException
	{
		String rules = shipped("fun-fence-book-");
		for (int daysKnown : new int[]{5, 50})
		{
			for (int trial = 0; trial < 60; trial++)
			{
				Harness h = new Harness(folder.newFolder().toPath(), rules);
				h.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay() - daysKnown);
				h.gameTicks(700);
				assertFalse("something said, day " + daysKnown, h.spoken.isEmpty());
				assertEquals("day " + daysKnown, "fun-fence-book-start", h.spoken.get(0).rule.id);
			}
		}
	}
	// public:cut skilling

	@Test
	public void geraldIsIntroducedFirst() throws IOException
	{
		String rules = shipped("fun-gerald-");
		for (int trial = 0; trial < 60; trial++)
		{
			Harness h = new Harness(folder.newFolder().toPath(), rules);
			h.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay() - 5);
			h.engine.getContext().setWork(true, "mining", "Iron rocks", null, 0, 0, 0);
			h.gameTicks(400);
			assertFalse(h.spoken.isEmpty());
			assertEquals("fun-gerald-first", h.spoken.get(0).rule.id);
		}
	}
	// public:end skilling
}
