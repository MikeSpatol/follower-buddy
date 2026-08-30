package com.follower;

import com.follower.speech.RuleLoader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An update has to reach the people who already have the plugin.
 *
 * <p>The old merge only appended rules the file had never seen, so a change to
 * an existing rule reached nobody: retuning the examine echo and writing twenty
 * lines for it changed nothing whatsoever for anyone who already had the
 * plugin. The work shipped and stayed invisible, and the only way to see it was
 * to delete your file.
 *
 * <p>Copying more across is not the fix, because two things look identical from
 * the user's file alone - a line the update ADDED, and a line the player
 * DELETED. Both are lines the bundled file has and theirs does not. So the
 * defaults are kept as they were at the last merge and the question becomes
 * answerable.
 *
 * <p>These tests are built against the REAL bundled rules rather than a toy
 * pair, with the base reconstructed as the previous version genuinely was, so
 * what they prove is what a player's next start actually does.
 */
public class ThreeWayMergeTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static JsonObject shipped()
	{
		try (InputStream in = RuleLoader.class
			.getResourceAsStream("/com/follower/default-phrases.json"))
		{
			return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static JsonObject rule(JsonObject file, String id)
	{
		for (JsonElement e : file.getAsJsonArray("rules"))
		{
			if (id.equals(e.getAsJsonObject().get("id").getAsString()))
			{
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private static List<String> say(JsonObject file, String id)
	{
		List<String> out = new ArrayList<>();
		for (JsonElement line : rule(file, id).getAsJsonArray("say"))
		{
			out.add(line.getAsString());
		}
		return out;
	}

	private static void setSay(JsonObject file, String id, List<String> lines)
	{
		JsonArray say = new JsonArray();
		lines.forEach(say::add);
		rule(file, id).add("say", say);
	}

	private static int chance(JsonObject file, String id)
	{
		for (JsonElement c : rule(file, id).getAsJsonObject("when").getAsJsonArray("conditions"))
		{
			if ("chance".equals(c.getAsJsonObject().get("type").getAsString()))
			{
				return c.getAsJsonObject().get("percent").getAsInt();
			}
		}
		return -1;
	}

	private static void setChance(JsonObject file, String id, int percent)
	{
		for (JsonElement c : rule(file, id).getAsJsonObject("when").getAsJsonArray("conditions"))
		{
			if ("chance".equals(c.getAsJsonObject().get("type").getAsString()))
			{
				c.getAsJsonObject().addProperty("percent", percent);
			}
		}
	}

	/**
	 * The examine echo as it shipped before today: four lines, thirty percent,
	 * once every three minutes. Built from the current file so the rest of it
	 * is exactly what a previous version really held.
	 */
	private static JsonObject previousVersion()
	{
		JsonObject old = shipped();
		setSay(old, "examine-echo", say(old, "examine-echo").subList(0, 4));
		setChance(old, "examine-echo", 30);
		rule(old, "examine-echo").addProperty("cooldownMs", 180000);
		return old;
	}

	/** Runs the merge the way startup does, and hands back the merged file. */
	private JsonObject merge(JsonObject theirs, JsonObject base) throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			GSON.toJson(theirs).getBytes(StandardCharsets.UTF_8));
		if (base != null)
		{
			Files.write(dir.resolve(RuleLoader.BASE_FILE),
				GSON.toJson(base).getBytes(StandardCharsets.UTF_8));
		}
		new RuleLoader(GSON).initialise(dir, true);
		return GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
	}

	@Test
	public void anUpdatedRuleReachesSomebodyWhoAlreadyHasThePlugin() throws Exception
	{
		JsonObject merged = merge(previousVersion(), previousVersion());

		assertEquals("the twenty new lines arrive", 24, say(merged, "examine-echo").size());
		assertEquals("and the retune with them", 70, chance(merged, "examine-echo"));
		assertEquals(30000L,
			rule(merged, "examine-echo").get("cooldownMs").getAsLong());
	}

	@Test
	public void aLineThePlayerDeletedStaysDeleted() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		List<String> without = new ArrayList<>(say(theirs, "examine-echo"));
		String removed = without.remove(0);
		setSay(theirs, "examine-echo", without);

		JsonObject merged = merge(theirs, base);

		assertFalse("a deletion is a decision, not a stale copy",
			say(merged, "examine-echo").contains(removed));
		assertEquals("while the new lines still arrive", 23,
			say(merged, "examine-echo").size());
	}

	@Test
	public void thePlayersOwnLinesSurviveAndTheNewOnesJoinThem() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		List<String> plus = new ArrayList<>(say(theirs, "examine-echo"));
		plus.add("A line of my own.");
		setSay(theirs, "examine-echo", plus);

		JsonObject merged = merge(theirs, base);

		assertTrue("their line is still there",
			say(merged, "examine-echo").contains("A line of my own."));
		assertEquals("theirs plus the twenty", 25, say(merged, "examine-echo").size());
	}

	@Test
	public void aValueThePlayerTunedIsTheirsToKeep() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		rule(theirs, "examine-echo").addProperty("cooldownMs", 600000);
		setChance(theirs, "examine-echo", 5);

		JsonObject merged = merge(theirs, base);

		assertEquals("their cooldown stands", 600000L,
			rule(merged, "examine-echo").get("cooldownMs").getAsLong());
		assertEquals("and their chance", 5, chance(merged, "examine-echo"));
		assertEquals("but the lines are not a setting, and still arrive",
			24, say(merged, "examine-echo").size());
	}

	@Test
	public void aRuleSwitchedOffStaysOff() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		rule(theirs, "examine-echo").addProperty("enabled", false);

		JsonObject merged = merge(theirs, base);

		assertFalse("silencing a rule is a decision",
			rule(merged, "examine-echo").get("enabled").getAsBoolean());
	}

	@Test
	public void aLineDroppedUpstreamGoes() throws Exception
	{
		// Base holds a line the current bundled file does not: an update
		// removed it, and the player never touched it.
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		for (JsonObject file : new JsonObject[]{base, theirs})
		{
			List<String> plus = new ArrayList<>(say(file, "examine-echo"));
			plus.add("A line the next version drops.");
			setSay(file, "examine-echo", plus);
		}

		JsonObject merged = merge(theirs, base);

		assertFalse("what an update removes, goes",
			say(merged, "examine-echo").contains("A line the next version drops."));
	}

	@Test
	public void mergingTwiceChangesNothingTheSecondTime() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve(RuleLoader.BASE_FILE),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));

		new RuleLoader(GSON).initialise(dir, true);
		String once = new String(Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)),
			StandardCharsets.UTF_8);
		new RuleLoader(GSON).initialise(dir, true);
		String twice = new String(Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)),
			StandardCharsets.UTF_8);

		assertEquals("a merge with nothing to do must do nothing", once, twice);
	}

	// ------------------------------------------------------- the first run

	@Test
	public void withNoBaseAnUntouchedRuleIsBroughtUpToDate() throws Exception
	{
		JsonObject merged = merge(previousVersion(), null);

		assertEquals("a year of tuning finally lands", 24,
			say(merged, "examine-echo").size());
		assertEquals(70, chance(merged, "examine-echo"));
	}

	@Test
	public void withNoBaseARuleThePlayerWroteIntoIsLeftAlone() throws Exception
	{
		JsonObject theirs = previousVersion();
		List<String> plus = new ArrayList<>(say(theirs, "examine-echo"));
		plus.add("A line of my own.");
		setSay(theirs, "examine-echo", plus);

		JsonObject merged = merge(theirs, null);

		assertEquals("without a base, their rule is not ours to touch",
			5, say(merged, "examine-echo").size());
		assertEquals(30, chance(merged, "examine-echo"));
	}

	@Test
	public void withNoBaseARuleSwitchedOffStaysOff() throws Exception
	{
		JsonObject theirs = previousVersion();
		rule(theirs, "examine-echo").addProperty("enabled", false);

		JsonObject merged = merge(theirs, null);

		assertFalse(rule(merged, "examine-echo").get("enabled").getAsBoolean());
		assertEquals("brought up to date in every other respect", 24,
			say(merged, "examine-echo").size());
	}

	@Test
	public void theBaseIsRecordedSoTheGuessworkHappensOnlyOnce() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));
		assertFalse(Files.exists(dir.resolve(RuleLoader.BASE_FILE)));

		new RuleLoader(GSON).initialise(dir, true);

		assertTrue("the defaults are recorded on the way out",
			Files.exists(dir.resolve(RuleLoader.BASE_FILE)));
		JsonObject base = GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.BASE_FILE)), StandardCharsets.UTF_8),
			JsonObject.class);
		assertEquals("and they are the ones that just shipped",
			24, say(base, "examine-echo").size());

		// Now a deletion must stick, which it could not have before.
		JsonObject theirs = GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
		List<String> without = new ArrayList<>(say(theirs, "examine-echo"));
		String removed = without.remove(0);
		setSay(theirs, "examine-echo", without);
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			GSON.toJson(theirs).getBytes(StandardCharsets.UTF_8));

		new RuleLoader(GSON).initialise(dir, true);

		JsonObject after = GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
		assertFalse("with a base, the deletion holds",
			say(after, "examine-echo").contains(removed));
	}

	@Test
	public void aWhollyNewRuleStillArrivesTheWayItAlwaysDid() throws Exception
	{
		JsonObject theirs = previousVersion();
		JsonArray kept = new JsonArray();
		for (JsonElement e : theirs.getAsJsonArray("rules"))
		{
			if (!"boss-brutus".equals(e.getAsJsonObject().get("id").getAsString()))
			{
				kept.add(e);
			}
		}
		theirs.add("rules", kept);

		JsonObject merged = merge(theirs, previousVersion());

		assertTrue("a new rule is still appended",
			rule(merged, "boss-brutus") != null);
	}
}
