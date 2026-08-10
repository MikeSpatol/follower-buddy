package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.Condition;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Checks the rules the plugin ships against the code that has to run them.
 *
 * <p>The rule file is data, and data drifts away from its reader silently: a
 * mistyped condition type never fires, and a character the game has no glyph
 * for renders as a hole in the sentence. Neither shows up as an error at
 * runtime, so they are checked here instead.
 */
public class RuleSetIntegrityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/**
	 * Every case label in {@link Condition#matches}. Kept here rather than in
	 * the class itself so that adding a case without adding it here is a test
	 * failure rather than a silent gap.
	 */
	static final Set<String> KNOWN_TYPES = new HashSet<>(Arrays.asList(
		"all", "any", "none", "always", "chance", "npcspawn", "npcdespawn",
		"npcnearby", "petnearby", "healthbelow", "healthabove", "prayerbelow",
		"prayerabove", "inregion", "regionenter", "inarea", "chatmessage",
		"varbitequals", "varbitchanged", "animationself", "levelup",
		"damagetaken", "itemequipped", "poisoned", "venomed", "skulled",
		"energybelow", "idle", "idlebelow", "playerdeath", "neardeathspot",
		"lootworth", "returnvisit", "combat", "bossfight", "login",
		"thrallstart", "thrallswitch", "thrallend", "errandstart", "errandend",
		"combatstart", "combatend", "npckill", "mood", "repeating", "awayfor",
		"thievingstart", "thievingend", "thieving",
		"tally", "personalbest", "sessioncount",
		"answered", "hovered", "examined",
		"inventoryfree", "playersnearby",
		"wanting", "wantfulfilled", "wantexpired", "feelsabout",
		"remembers", "carrying", "souvenirlost",
		"betting", "betwon", "betlost",
		"timeofday", "sessionminutes", "asking"));

	private static JsonObject bundled(String resource) throws IOException
	{
		try (InputStream in = RuleSetIntegrityTest.class.getResourceAsStream(resource))
		{
			assertTrue("missing bundled resource " + resource, in != null);
			// The instance form, not the statics: RuneLite pins a Gson older
			// than the release that added JsonParser.parseReader.
			return new JsonParser().parse(
				new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	// -------------------------------------------------------------- structure

	@Test
	public void everyBundledRuleSurvivesLoading() throws IOException
	{
		JsonArray raw = bundled("/com/follower/default-phrases.json")
			.getAsJsonArray("rules");
		Harness h = new Harness(folder.newFolder().toPath());

		assertEquals("the loader dropped rules the file declares",
			raw.size(), h.loader.getRules().size());
		assertTrue("the shipped rules must load without complaint: "
			+ h.loader.getErrors(), h.loader.getErrors().isEmpty());
	}

	@Test
	public void ruleIdsAreUnique() throws IOException
	{
		JsonArray raw = bundled("/com/follower/default-phrases.json")
			.getAsJsonArray("rules");
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		for (JsonElement element : raw)
		{
			String id = element.getAsJsonObject().get("id").getAsString();
			if (!seen.add(id))
			{
				duplicates.add(id);
			}
		}
		assertTrue("duplicate rule ids: " + duplicates, duplicates.isEmpty());
	}

	@Test
	public void everyConditionTypeIsOneTheEngineHandles() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Set<String> unknown = new TreeSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			collectUnknown(rule.when, unknown);
		}
		assertTrue("condition types no rule evaluator knows about, so these rules"
			+ " can never fire: " + unknown, unknown.isEmpty());
	}

	private void collectUnknown(Condition condition, Set<String> into)
	{
		if (condition == null)
		{
			return;
		}
		if (condition.type == null
			|| !KNOWN_TYPES.contains(condition.type.toLowerCase(java.util.Locale.ROOT)))
		{
			into.add(String.valueOf(condition.type));
		}
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				collectUnknown(child, into);
			}
		}
	}

	@Test
	public void everyGroupIsOneTheSettingsCanSwitchOff() throws IOException
	{
		// Groups the config exposes a toggle for, plus the ones deliberately
		// always on. A group outside this list has no off switch in settings.
		Set<String> switchable = new HashSet<>(Arrays.asList(
			"boss", "health", "area", "gear", "quest", "combat", "mimic",
			"errand", "idle", "reactions", "thrall", "misc",
			"memory", "souvenir", "bet", "clock"));

		Harness h = new Harness(folder.newFolder().toPath());
		Set<String> orphans = new TreeSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!switchable.contains(rule.group))
			{
				orphans.add(rule.group);
			}
		}
		assertTrue("rule groups with no home in the settings: " + orphans,
			orphans.isEmpty());
	}

	// ----------------------------------------------------------------- glyphs

	/**
	 * The game draws with bitmap fonts of exactly 256 glyphs, and
	 * {@code GameFont} picks one with {@code text.charAt(i) & 0xFF}.
	 *
	 * <p>That mask is the whole rule, and it is stricter than it looks. There is
	 * no charset mapping: a character above U+00FF is not rejected, it is
	 * TRUNCATED to its low byte and drawn as whatever glyph happens to live
	 * there. An em-dash, U+2014, reaches glyph 0x14 - not the em-dash the font
	 * really does hold at 0x97, which is where cp1252 would have put it. So the
	 * line does not come out blank, it comes out as a different character
	 * entirely, and nothing anywhere reports it.
	 *
	 * <p>Hence both halves of the check: the character has to be inside Latin-1
	 * at all, and the glyph at that index has to have pixels. Measured for both
	 * fonts the plugin draws with, since they do not agree - 496 has a glyph at
	 * 0x97 and 497 does not.
	 */
	@Test
	public void everySpokenCharacterHasAGlyphInBothGameFonts() throws IOException
	{
		JsonObject fonts = bundled("/com/follower/fonts.json");
		JsonObject overhead = fontById(fonts, 496);
		JsonObject dialog = fontById(fonts, 497);

		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String line : rule.say)
			{
				for (char c : line.toCharArray())
				{
					if (c == ' ')
					{
						continue;
					}
					boolean over = hasGlyph(overhead, c);
					boolean dlg = hasGlyph(dialog, c);
					if (!over || !dlg)
					{
						problems.add(String.format(
							"%s: '%c' (U+%04X) blank in %s - %s",
							rule.id, c, (int) c,
							!over && !dlg ? "both fonts" : (over ? "the dialog font" : "the overhead font"),
							line));
					}
				}
			}
		}
		assertTrue("lines containing characters the game cannot draw:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	private static JsonObject fontById(JsonObject dump, int id)
	{
		for (JsonElement element : dump.getAsJsonArray("fonts"))
		{
			JsonObject font = element.getAsJsonObject();
			if (font.get("id").getAsInt() == id)
			{
				return font;
			}
		}
		throw new AssertionError("bundled fonts.json has no font " + id
			+ "; the plugin needs it at runtime");
	}

	private static boolean hasGlyph(JsonObject font, char c)
	{
		// Anything past Latin-1 would be truncated to a different character.
		if (c > 0xFF)
		{
			return false;
		}
		// Exactly what GameFont.drawTop does to choose a glyph.
		JsonObject glyph = font.getAsJsonArray("glyphs").get(c & 0xFF).getAsJsonObject();
		JsonElement mask = glyph.get("mask");
		return glyph.get("w").getAsInt() > 0
			&& mask != null && !mask.isJsonNull() && !mask.getAsString().isEmpty();
	}

	// ----------------------------------------------------------- placeholders

	/**
	 * A placeholder only resolves if the event that triggered the rule carries
	 * it. {npc} in a rule that fires on login would print literally.
	 */
	@Test
	public void everyPlaceholderCanBeSuppliedByTheTriggerThatFiresIt() throws IOException
	{
		// Always available from the state snapshot, whatever fired the rule.
		Set<String> ambient = new HashSet<>(Arrays.asList(
			"hp", "maxHp", "hpPercent", "prayer", "maxPrayer", "prayerPercent",
			"player", "region"));

		// Which conditions bring which extra placeholders with them. Taken from
		// the placeholders.put calls in TriggerEvent, factory by factory.
		java.util.Map<String, Set<String>> byType = new java.util.HashMap<>();
		byType.put("npcspawn", new HashSet<>(Arrays.asList("npc", "npcId")));
		byType.put("npcdespawn", new HashSet<>(Arrays.asList("npc", "npcId")));
		byType.put("npckill", new HashSet<>(Arrays.asList("npc", "npcId", "level", "count")));
		byType.put("combatstart", new HashSet<>(Arrays.asList("npc")));
		byType.put("combatend", new HashSet<>(Arrays.asList("npc")));
		byType.put("lootworth", new HashSet<>(Arrays.asList("item", "value")));
		byType.put("levelup", new HashSet<>(Arrays.asList("skill", "level")));
		byType.put("chatmessage", new HashSet<>(Arrays.asList("message")));
		byType.put("varbitchanged", new HashSet<>(Arrays.asList("value")));
		byType.put("thrallstart", new HashSet<>(Arrays.asList("style")));
		byType.put("thrallend", new HashSet<>(Arrays.asList("style")));
		byType.put("thrallswitch", new HashSet<>(Arrays.asList("style", "from")));
		byType.put("errandstart", new HashSet<>(Arrays.asList("errand")));
		byType.put("errandend", new HashSet<>(Arrays.asList("errand")));
		byType.put("damagetaken", new HashSet<>(Arrays.asList("damage")));
		byType.put("personalbest", new HashSet<>(Arrays.asList("record", "value", "previous")));
		byType.put("wantfulfilled", new HashSet<>(Arrays.asList("want")));
		byType.put("wantexpired", new HashSet<>(Arrays.asList("want")));

		// These two come off the state snapshot rather than the event, so they
		// are readable from any trigger - but they are EMPTY unless there is
		// something to read, and an empty one leaves a hole in the sentence
		// ("I keep thinking about ."). Listing them as conditional rather than
		// ambient is what makes the guard compulsory.
		byType.put("remembers", new HashSet<>(Arrays.asList("memory")));
		byType.put("carrying", new HashSet<>(Arrays.asList("souvenir")));
		byType.put("souvenirlost", new HashSet<>(Arrays.asList("souvenir")));

		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			Set<String> available = new HashSet<>(ambient);
			for (java.util.Map.Entry<String, Set<String>> entry : byType.entrySet())
			{
				if (rule.when != null && rule.when.usesType(entry.getKey()))
				{
					available.addAll(entry.getValue());
				}
			}
			for (String line : rule.say)
			{
				for (String key : placeholdersIn(line))
				{
					if (!available.contains(key))
					{
						problems.add(rule.id + ": {" + key + "} is never supplied by its trigger - " + line);
					}
				}
			}
		}
		assertTrue("placeholders that would print literally:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	private static List<String> placeholdersIn(String line)
	{
		List<String> keys = new ArrayList<>();
		int i = 0;
		while (i < line.length())
		{
			int open = line.indexOf('{', i);
			if (open < 0)
			{
				break;
			}
			int close = line.indexOf('}', open);
			if (close < 0)
			{
				break;
			}
			keys.add(line.substring(open + 1, close));
			i = close + 1;
		}
		return keys;
	}

	// -------------------------------------------------------------- coherence

	@Test
	public void aRuleThatMirrorsAnimationsAlsoTriggersOnOne() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (Boolean.TRUE.equals(rule.mirrorAnimation)
				&& (rule.when == null
					|| (!rule.when.usesType("animationSelf")
						&& !rule.when.usesType("repeating"))))
			{
				problems.add(rule.id);
			}
		}
		assertTrue("mirrorAnimation needs a trigger that says WHICH animation to"
			+ " copy - the event's own id, or the one the repeating condition is"
			+ " watching. These rules would mirror nothing: " + problems,
			problems.isEmpty());
	}

	@Test
	public void aRuleThatMirrorsAPoseAlsoTriggersOnAnAnimation() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (Boolean.TRUE.equals(rule.mirrorPose)
				&& (rule.when == null
					|| (!rule.when.usesType("animationSelf")
						&& !rule.when.usesType("repeating"))))
			{
				problems.add(rule.id);
			}
		}
		assertTrue("mirrorPose takes the id from the animation that triggered it,"
			+ " or from the one repeating is watching, so it needs one of those"
			+ " two triggers: " + problems, problems.isEmpty());
	}

	@Test
	public void theTwoMimicRulesNeverClaimTheSameAnimation() throws IOException
	{
		// One plays a one-shot and the other holds a pose. An id in both would
		// have them fighting over the same animation.
		Harness h = new Harness(folder.newFolder().toPath());
		Set<Integer> oneShots = new HashSet<>(h.rule("mimic-emotes").when.ids);
		Set<Integer> loops = new HashSet<>(h.rule("mimic-emote-loops").when.ids);

		Set<Integer> both = new HashSet<>(oneShots);
		both.retainAll(loops);

		assertTrue("animations claimed by both mimic rules: " + both, both.isEmpty());
		assertTrue("the one-shot list looks too short to be the whole set",
			oneShots.size() > 50);
		assertTrue("the loop list looks too short to be the whole set",
			loops.size() > 20);
	}

	@Test
	public void theCrabDanceIsMirroredBothWays() throws IOException
	{
		// It was missing entirely: HUMAN_EMOTE_CRABDANCE does not start with
		// EMOTE_, so the prefix match that built the first list never saw it.
		Harness h = new Harness(folder.newFolder().toPath());

		assertTrue("the crab dance entry clip is not mirrored",
			h.rule("mimic-emotes").when.ids.contains(10051));
		assertTrue("and its loop is not held",
			h.rule("mimic-emote-loops").when.ids.contains(10052));
	}

	@Test
	public void noRuleAsksToHoldStillWithoutAnimatingAnything() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (Boolean.TRUE.equals(rule.holdStill) && !rule.hasAnimationAction())
			{
				problems.add(rule.id);
			}
		}
		assertTrue("holdStill plants the follower for the length of an animation;"
			+ " without one it would never be released: " + problems, problems.isEmpty());
	}

	@Test
	public void aDelayRangeIsNeverWrittenBackwards() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.delayTicksMax != null
				&& (rule.delayTicks == null || rule.delayTicksMax < rule.delayTicks))
			{
				problems.add(rule.id + " (" + rule.delayTicks + ".." + rule.delayTicksMax + ")");
			}
		}
		assertTrue("a max below the min collapses the range: " + problems,
			problems.isEmpty());
	}

	@Test
	public void theBundledAndTheStarterFileAgree() throws IOException
	{
		// The starter file written on first run must be the bundled one, or a
		// user's first experience differs from the shipped default.
		Harness h = new Harness(folder.newFolder().toPath());
		JsonObject shipped = bundled("/com/follower/default-phrases.json");
		JsonElement written = new JsonParser().parse(new String(
			java.nio.file.Files.readAllBytes(h.loader.getFile()), StandardCharsets.UTF_8));
		assertEquals(shipped, written);
	}

	@Test
	public void noRuleIsLeftDisabledInTheShippedSet() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> off = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!rule.isEnabled())
			{
				off.add(rule.id);
			}
		}
		assertTrue("rules shipped switched off, which reads as them being broken: "
			+ off, off.isEmpty());
	}

	@Test
	public void animationOnlyRulesAreDeliberateNotMissingText() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> silent = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!rule.hasSpeech() && !rule.hasAnimationAction())
			{
				silent.add(rule.id);
			}
		}
		assertTrue("rules that neither speak nor move: " + silent, silent.isEmpty());
	}

	@Test
	public void theRuleFileParsesWithAPlainGson() throws IOException
	{
		// Guards against anything in the file that only a lenient reader accepts.
		try (InputStream in = getClass().getResourceAsStream("/com/follower/default-phrases.json"))
		{
			Object parsed = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), Object.class);
			assertFalse(parsed == null);
		}
	}
}
