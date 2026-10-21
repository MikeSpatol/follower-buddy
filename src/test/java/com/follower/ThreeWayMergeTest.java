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

	/**
	 * A rule 1.4.0 shipped exactly as it ships now, with a cooldown, for the
	 * tests that need one nobody has touched. Found rather than named: two
	 * named ones were reworded later and broke these tests each time.
	 */
	private static String unchangedSince140()
	{
		JsonObject now = shipped();
		for (JsonElement e : freshFrom140().getAsJsonArray("rules"))
		{
			JsonObject was = e.getAsJsonObject();
			String id = was.get("id").getAsString();
			if (was.equals(rule(now, id)) && was.has("cooldownMs") && was.has("say")
				&& (!was.has("enabled") || was.get("enabled").getAsBoolean()))
			{
				return id;
			}
		}
		throw new AssertionError("no rule is the same as in 1.4.0");
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

	/**
	 * The rules exactly as 1.4.0 wrote them on a fresh install. Up to 1.4.0 a
	 * fresh install recorded nothing, so its first update had no base.
	 */
	private static JsonObject freshFrom140()
	{
		try (InputStream in = RuleLoader.class
			.getResourceAsStream("/com/follower/seen/1.4.0-phrases.json"))
		{
			return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * Play test, 28 Sep: a fresh 1.4.0 install with one line deleted from the
	 * axe rule and one harpoon line rewritten. The upgrade had no base and put
	 * the deleted axe line back, because a rule holding only shipped lines
	 * looked untouched.
	 */
	@Test
	public void aFreshInstallFrom140KeepsTheLinesItDeletedAndTheOnesItWrote() throws Exception
	{
		JsonObject theirs = freshFrom140();
		List<String> axe = new ArrayList<>(say(theirs, "gear-axe"));
		String deleted = axe.remove(1);
		setSay(theirs, "gear-axe", axe);
		List<String> harpoon = new ArrayList<>(say(theirs, "gear-harpoon"));
		String rewritten = harpoon.set(1, "TEST!");
		setSay(theirs, "gear-harpoon", harpoon);

		JsonObject merged = merge(theirs, null);

		assertFalse("the deleted line stays deleted", say(merged, "gear-axe").contains(deleted));
		assertTrue(say(merged, "gear-harpoon").contains("TEST!"));
		assertFalse("and the line it replaced stays gone", say(merged, "gear-harpoon").contains(rewritten));
	}

	@Test
	public void aFreshInstallFrom140ThatNobodyEditedBecomesTheNewDefaults() throws Exception
	{
		JsonObject merged = merge(freshFrom140(), null);

		for (JsonElement e : shipped().getAsJsonArray("rules"))
		{
			String id = e.getAsJsonObject().get("id").getAsString();
			// New lines join the end of the old ones; a line is picked at
			// random, so only which lines there are matters.
			assertEquals("every rule's lines as it ships now: " + id,
				String.valueOf(lines(e.getAsJsonObject())), String.valueOf(lines(rule(merged, id))));
			JsonObject want = e.getAsJsonObject().deepCopy();
			JsonObject got = rule(merged, id).deepCopy();
			want.remove("say");
			got.remove("say");
			assertEquals("and everything else: " + id, want, got);
		}
	}

	/** A rule's lines as a sorted set, or null for a rule that says nothing of its own. */
	private static java.util.TreeSet<String> lines(JsonObject rule)
	{
		if (!rule.has("say") || rule.getAsJsonArray("say").size() == 0)
		{
			return null;
		}
		java.util.TreeSet<String> out = new java.util.TreeSet<>();
		rule.getAsJsonArray("say").forEach(line -> out.add(line.getAsString()));
		return out;
	}

	@Test
	public void aRuleSwitchedOffOnAFreshInstallFrom140StaysOff() throws Exception
	{
		String id = unchangedSince140();
		JsonObject theirs = freshFrom140();
		rule(theirs, id).addProperty("enabled", false);

		JsonObject merged = merge(theirs, null);

		assertFalse(rule(merged, id).get("enabled").getAsBoolean());
		assertEquals("brought up to date in every other respect",
			say(shipped(), id), say(merged, id));
	}

	/**
	 * A rule 1.4.0 never shipped has no record to be judged against, so it is
	 * judged by its lines, as every rule once was: one the player wrote into
	 * is theirs.
	 */
	@Test
	public void aRuleTheRecordNeverHadIsLeftAloneOnceWrittenInto() throws Exception
	{
		JsonObject theirs = freshFrom140();
		JsonObject newer = null;
		for (JsonElement e : shipped().getAsJsonArray("rules"))
		{
			if (rule(theirs, e.getAsJsonObject().get("id").getAsString()) == null)
			{
				newer = e.getAsJsonObject().deepCopy();
				break;
			}
		}
		assertTrue("the current defaults have rules 1.4.0 did not", newer != null);
		String id = newer.get("id").getAsString();
		theirs.getAsJsonArray("rules").add(newer);
		List<String> plus = new ArrayList<>(say(theirs, id));
		plus.add("A line of my own.");
		setSay(theirs, id, plus);

		JsonObject merged = merge(theirs, null);

		assertEquals(plus, say(merged, id));
	}

	/**
	 * Review, 28 Sep: a file older than 1.4.0 must not be judged against 1.4.0,
	 * or everything 1.3.0 and 1.4.0 added to its untouched rules looks like
	 * the player deleted it, and never arrives. A 1.2.0 file lacks rules 1.4.0
	 * added, so it is still brought up to date by its lines, as before.
	 */
	@Test
	public void aFileOlderThan130IsStillBroughtUpToDate() throws Exception
	{
		// As 1.2.0 was: without a rule 1.3.0 added, and with fewer lines in a
		// rule 1.3.0 wrote more for without retuning it (eight such rules).
		JsonObject theirs = freshFrom140();
		JsonArray older = new JsonArray();
		for (JsonElement e : theirs.getAsJsonArray("rules"))
		{
			if (!"boss-brutus".equals(e.getAsJsonObject().get("id").getAsString()))
			{
				older.add(e);
			}
		}
		theirs.add("rules", older);
		setSay(theirs, "gear-axe", say(theirs, "gear-axe").subList(0, 1));

		JsonObject merged = merge(theirs, null);

		assertEquals("the lines written since arrive",
			say(shipped(), "gear-axe"), say(merged, "gear-axe"));
	}

	/**
	 * 1.3.0 shipped the same rules as 1.4.0, and 1.4.0 retuned two of them. A
	 * 1.3.0 copy of such a rule no longer matches 1.4.0 outside its lines, so
	 * it is brought up to date by its lines rather than judged as tuned.
	 */
	@Test
	public void aRuleRetunedSince130StillArrives() throws Exception
	{
		String id = unchangedSince140();
		JsonObject theirs = freshFrom140();
		rule(theirs, id).addProperty("cooldownMs", 123456);

		JsonObject merged = merge(theirs, null);

		assertEquals(rule(shipped(), id).get("cooldownMs"),
			rule(merged, id).get("cooldownMs"));
	}

	/** From this version on a fresh install records what it wrote, so there is never a first run to guess on. */
	@Test
	public void aFreshInstallRecordsWhatItWrote() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		new RuleLoader(GSON).initialise(dir, true);

		assertEquals(shipped(), GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.BASE_FILE)), StandardCharsets.UTF_8),
			JsonObject.class));

		JsonObject theirs = GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
		List<String> axe = new ArrayList<>(say(theirs, "gear-axe"));
		String deleted = axe.remove(1);
		setSay(theirs, "gear-axe", axe);
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			GSON.toJson(theirs).getBytes(StandardCharsets.UTF_8));

		new RuleLoader(GSON).initialise(dir, true);

		JsonObject after = GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(RuleLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
		assertFalse(say(after, "gear-axe").contains(deleted));
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

	/**
	 * Upgrade test, 21 Oct: a 1.4.1 file kept wilderness-crossing, a rule 1.5.0
	 * dropped (its lines went to enter-wilderness). It could never be heard, but
	 * it sat in the Messages window. A rule an update drops goes, as a dropped
	 * line does, whether the base was recorded or has to be told from 1.4.0.
	 */
	@Test
	public void aRuleDroppedUpstreamGoes() throws Exception
	{
		assertTrue("1.4.0 had it", rule(freshFrom140(), "wilderness-crossing") != null);
		assertTrue("1.5.0 does not", rule(shipped(), "wilderness-crossing") == null);

		assertTrue("gone with no record of the base",
			rule(merge(freshFrom140(), null), "wilderness-crossing") == null);
		assertTrue("and gone with one",
			rule(merge(freshFrom140(), freshFrom140()), "wilderness-crossing") == null);
	}

	/** One the player changed is theirs now, and so is one they wrote. */
	@Test
	public void aDroppedRuleThePlayerChangedOrWroteStays() throws Exception
	{
		JsonObject theirs = freshFrom140();
		List<String> plus = new ArrayList<>(say(theirs, "wilderness-crossing"));
		plus.add("A line of my own.");
		setSay(theirs, "wilderness-crossing", plus);
		JsonObject mine = rule(theirs, "wilderness-crossing").deepCopy();
		mine.addProperty("id", "my-own-rule");
		theirs.getAsJsonArray("rules").add(mine);

		JsonObject merged = merge(theirs, freshFrom140());

		assertTrue("the changed one stays", rule(merged, "wilderness-crossing") != null);
		assertTrue("and their own rule", rule(merged, "my-own-rule") != null);
	}
}
