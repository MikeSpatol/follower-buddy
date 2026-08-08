package com.follower;

import com.follower.appearance.Outfit;
import com.follower.appearance.OutfitParser;
import com.follower.appearance.OutfitProfileStore;
import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechRule;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What survives the editor writing the rules file back out.
 *
 * <p>The phrase editors show one group at a time but rewrite the WHOLE file:
 * they re-read it, mutate the rules they own, and serialise everything back.
 * That means every rule in every other group, and every field the editor has
 * never heard of, passes through Gson twice on a save it had nothing to do
 * with. Anything lost there is silent data loss in the file that holds the
 * follower's entire personality.
 */
public class PhraseFileFidelityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The same Gson the editor builds for writing. */
	private static Gson editorGson()
	{
		return new Gson().newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	}

	private static JsonObject bundledRules() throws IOException
	{
		try (InputStream in = PhraseFileFidelityTest.class
			.getResourceAsStream("/com/follower/default-phrases.json"))
		{
			assertNotNull(in);
			return new JsonParser()
				.parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	public void theWholeRuleFileSurvivesTheEditorsSerialiser() throws IOException
	{
		JsonObject original = bundledRules();
		JsonObject rewritten = new JsonParser()
			.parse(editorGson().toJson(original)).getAsJsonObject();

		assertEquals("a save would have changed rules it never touched",
			original, rewritten);
	}

	@Test
	public void aRewriteSurvivesBeingLoadedBackAsRules() throws IOException
	{
		// Semantic check on top of the structural one: the rules the plugin
		// ends up running after a save must be the rules it ran before.
		Path before = folder.newFolder().toPath();
		RuleLoader first = new RuleLoader(new Gson());
		first.initialise(before);

		Path after = folder.newFolder().toPath();
		Files.write(after.resolve(RuleLoader.FILE_NAME),
			editorGson().toJson(bundledRules()).getBytes(StandardCharsets.UTF_8));
		RuleLoader second = new RuleLoader(new Gson());
		second.initialise(after);

		assertTrue("the rewritten file failed to load: " + second.getErrors(),
			second.getErrors().isEmpty());
		assertEquals(first.getRules().size(), second.getRules().size());

		for (int i = 0; i < first.getRules().size(); i++)
		{
			SpeechRule a = first.getRules().get(i);
			SpeechRule b = second.getRules().get(i);
			assertEquals(a.id, b.id);
			assertEquals("group changed on " + a.id, a.group, b.group);
			assertEquals("priority changed on " + a.id, a.priority, b.priority);
			assertEquals("cooldown changed on " + a.id, a.cooldownMs, b.cooldownMs);
			assertEquals("say lines changed on " + a.id, a.say, b.say);
			assertEquals("animation changed on " + a.id, a.animation, b.animation);
			assertEquals("chain changed on " + a.id, a.animations, b.animations);
			assertEquals("delay changed on " + a.id, a.delayTicks, b.delayTicks);
			assertEquals("delay range changed on " + a.id, a.delayTicksMax, b.delayTicksMax);
			assertEquals("holdStill changed on " + a.id, a.holdStill, b.holdStill);
			assertEquals("mirroring changed on " + a.id, a.mirrorAnimation, b.mirrorAnimation);
			assertEquals("vanish changed on " + a.id, a.vanishAfter, b.vanishAfter);
			assertEquals("sync changed on " + a.id, a.syncToPlayer, b.syncToPlayer);
		}
	}

	@Test
	public void fieldsTheEditorHasNeverHeardOfAreCarriedThrough() throws IOException
	{
		JsonObject file = new JsonParser().parse(
			"{\"version\": 1, \"rules\": [{\"id\": \"exotic\", \"group\": \"boss\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"],"
				+ " \"somethingFromTheFuture\": {\"nested\": [1, 2, 3]},"
				+ " \"holdStill\": true, \"delayTicksMax\": 9}]}").getAsJsonObject();

		JsonObject rewritten = new JsonParser()
			.parse(editorGson().toJson(file)).getAsJsonObject();
		JsonObject rule = rewritten.getAsJsonArray("rules").get(0).getAsJsonObject();

		assertTrue("an unknown field was dropped", rule.has("somethingFromTheFuture"));
		assertEquals(file, rewritten);
	}

	@Test
	public void editingOneGroupCannotDisturbAnother() throws IOException
	{
		// The editor's contract in miniature: re-read, change only the rules it
		// owns, keep the rest verbatim. Modelled here because the real save
		// needs a window, and this is the part that can lose a user's data.
		JsonObject file = bundledRules();
		JsonArray rules = file.getAsJsonArray("rules");

		List<JsonElement> untouched = new ArrayList<>();
		JsonArray kept = new JsonArray();
		int edited = 0;
		for (JsonElement element : rules)
		{
			JsonObject rule = element.getAsJsonObject();
			if ("gear".equals(rule.get("group").getAsString()))
			{
				JsonArray say = new JsonArray();
				say.add("edited line");
				rule.add("say", say);
				edited++;
			}
			else
			{
				untouched.add(new JsonParser().parse(rule.toString()));
			}
			kept.add(rule);
		}
		file.add("rules", kept);

		assertTrue("the fixture needs some gear rules to edit", edited > 0);

		JsonObject rewritten = new JsonParser()
			.parse(editorGson().toJson(file)).getAsJsonObject();

		int index = 0;
		for (JsonElement element : rewritten.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			if (!"gear".equals(rule.get("group").getAsString()))
			{
				assertEquals("a rule outside the edited group changed",
					untouched.get(index), rule);
				index++;
			}
		}
		assertEquals(untouched.size(), index);
		assertEquals("no rule may go missing on a save",
			rules.size(), rewritten.getAsJsonArray("rules").size());
	}

	@Test
	public void unicodeAndEscapesSurviveASave()
	{
		// disableHtmlEscaping is on, so quotes and angle brackets must come back
		// as themselves rather than as escapes that then render literally.
		String[] awkward = {
			"He said \"hello\"", "a < b > c", "back\\slash", "tab\there",
			"new\nline", "apostrophe's", "100% & more", "emoji free ok",
		};
		for (String line : awkward)
		{
			JsonObject file = new JsonObject();
			JsonArray say = new JsonArray();
			say.add(line);
			JsonObject rule = new JsonObject();
			rule.addProperty("id", "x");
			rule.add("say", say);
			JsonArray rules = new JsonArray();
			rules.add(rule);
			file.add("rules", rules);

			JsonObject back = new JsonParser()
				.parse(editorGson().toJson(file)).getAsJsonObject();
			assertEquals("lost on save: " + line, line, back.getAsJsonArray("rules")
				.get(0).getAsJsonObject().getAsJsonArray("say").get(0).getAsString());
		}
	}

	// ------------------------------------------------------- outfit profiles

	@Test
	public void outfitProfilesSurviveASaveAndReload() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		OutfitProfileStore first = new OutfitProfileStore(new Gson());
		first.load(dir);

		Outfit outfit = new Outfit();
		outfit.setItem(net.runelite.api.kit.KitType.WEAPON, 4151);
		outfit.setKit(net.runelite.api.kit.KitType.HAIR, 3);
		outfit.setGender(1);
		outfit.setColors(new int[]{1, 2, 3, 4, 5});
		first.put("My costume", outfit.toString());

		OutfitProfileStore second = new OutfitProfileStore(new Gson());
		second.load(dir);

		String stored = second.get("My costume");
		assertNotNull("the profile did not survive the round trip", stored);
		assertEquals("the outfit changed on the way through",
			outfit, OutfitParser.parse(stored));
	}

	@Test
	public void theSeededStyleProfilesAreAlwaysThere() throws IOException
	{
		OutfitProfileStore store = new OutfitProfileStore(new Gson());
		store.load(folder.newFolder().toPath());

		for (String name : new String[]{"Melee", "Ranged", "Magic"})
		{
			assertNotNull("the thrall config points at '" + name + "' by default,"
				+ " so it has to exist", store.get(name));
		}
	}

	@Test
	public void deletingAProfileSticksAcrossAReload() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		OutfitProfileStore first = new OutfitProfileStore(new Gson());
		first.load(dir);
		first.put("Temporary", "weapon=item:4151");
		assertTrue(first.remove("Temporary"));

		OutfitProfileStore second = new OutfitProfileStore(new Gson());
		second.load(dir);
		assertEquals("a deleted profile came back", null, second.get("Temporary"));
	}

	@Test
	public void aCorruptProfileFileDoesNotStopTheStoreLoading() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve("outfit-profiles.json"),
			"{ not json".getBytes(StandardCharsets.UTF_8));

		OutfitProfileStore store = new OutfitProfileStore(new Gson());
		store.load(dir);

		assertNotNull("the seeded profiles should still be there", store.get("Melee"));
	}
}
