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
		"prayerabove", "inventoryhas", "inregion", "regionenter", "inarea", "chatmessage",
		"varbitequals", "varbitchanged", "questvar", "dialogueopen", "questdone",
		"animationself", "levelup",
		"damagetaken", "itemequipped", "poisoned", "venomed", "skulled",
		"energybelow", "idle", "idlebelow", "playerdeath", "neardeathspot",
		"lootworth", "returnvisit", "combat", "bossfight", "login",
		"thrallstart", "thrallswitch", "thrallend", "errandstart", "errandend",
		"combatstart", "combatend", "npckill", "mood", "repeating", "awayfor",
		"thievingstart", "thievingend", "thieving",
		"tally", "personalbest", "sessioncount",
		"answered", "hovered", "examined",
		"inventoryfree", "playersnearby",
		"wanting", "wantfulfilled", "wantexpired",
		"feelsabout", // public:cut likes
		"wishing",
		"wishitemheld",
		"rolledfeeling", // public:cut likes
		"boundary", "staying", "inwilderness",
		"remembers", "memoryhere", "memoryfoenearby", "carrying", "souvenirlost",
		"betting", "betwon", "betlost",
		"timeofday", "sessionminutes", "asking",
		"placescore", // public:cut likes
		"happenedhere",
		"heeded", "ignored", "advising",
		"daysknown", "anniversary", "outgrew",
		"nicknamed", // public:cut nicknames
		"challenging", "challengemet", "challengefailed",
		"unattended", "underfoot",
		"milestone", "levelgap", "killcount", "grateful", "lasttime", "farewell",
		"noticed", // public:cut company-extras,noticing
		"hesitated", "steppedaside", // public:cut company-extras,will
		"crossed", // public:cut agility,agility-all
		"addressed",
		"nicknameearned", // public:cut nicknames
		"dateis", "worldhop", "gameupdated", "notesedited",
		"musicunlock", "collectionadd", "questcomplete", "geoffer",
		"channeljoined", "channelleft", "npcshout", "musictrack",
		"examinedthing", "attendedagain", "minutestaken",
		"pagewritten", // public:cut chronicle
		"working", "xpdrop", "workended", "targetdepleted", "bagfull", "baghalf", // public:cut skilling
		// public:else "xpdrop",
		"dayssincesaid", "questwitnessed", "grace")); // public:cut agility-all,marks
		// public:else "dayssincesaid", "questwitnessed"));

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

	/**
	 * Restored with an approved line but no approved title or about yet.
	 * Kept exact, so a rule that gets its words comes off this list.
	 */
	private static final List<String> AWAITING_TITLE_AND_ABOUT = java.util.Collections.emptyList();

	/**
	 * QA 2, 15 Oct: the quest moments and fun lines came in with titles made
	 * from their ids ('Qm priest in peril the kings verdict ...') and, under
	 * them, the authors' notes: varp numbers, sources, quest steps. The
	 * window shows only an about, so each of them carries a title and one.
	 */
	@Test
	public void everyQuestMomentAndFunLineHasATitleAndAnAbout() throws IOException
	{
		JsonArray raw = bundled("/com/follower/default-phrases.json")
			.getAsJsonArray("rules");
		Set<String> missing = new TreeSet<>();
		int checked = 0;
		for (JsonElement element : raw)
		{
			JsonObject rule = element.getAsJsonObject();
			String id = rule.get("id").getAsString();
			if (!id.startsWith("qm-") && !id.startsWith("fun-"))
			{
				continue;
			}
			checked++;
			for (String field : new String[]{"title", "about"})
			{
				if (!rule.has(field) || rule.get(field).getAsString().trim().isEmpty())
				{
					missing.add(id);
				}
			}
		}
		assertTrue("the qm- and fun- rules were found", checked > 90);
		assertEquals("qm- and fun- rules with no title or no about",
			new TreeSet<>(AWAITING_TITLE_AND_ABOUT), missing);
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

	@Test
	public void theReactionsSwitchCoversTheDeathsDropsAndLevelUpsItNames() throws IOException
	{
		// The setting reads "Deaths, drops, thieving, level-ups and the chat".
		// QA2, 15 Oct: the level-ups, the death line and the pet drop were in
		// misc, so switching Reactions off left them talking. Reminiscing
		// files its own memories of deaths and drops under its own switch.
		Set<String> named = new HashSet<>(Arrays.asList("death", "pet-drop", "level-up-new", "level-up",
			"level-halfway", "level-round", "level-mastery-first", "level-mastery-again", "level-total",
			"level-base", "level-after-a-gap", "level-quick", "levels-many"));
		Set<String> triggers = new HashSet<>(Arrays.asList(
			"levelUp", "levelGap", "playerDeath", "nearDeathSpot", "lootWorth"));
		Set<String> elsewhere = new TreeSet<>();
		for (JsonElement e : bundled("/com/follower/default-phrases.json").getAsJsonArray("rules"))
		{
			JsonObject rule = e.getAsJsonObject();
			String id = rule.get("id").getAsString();
			String group = rule.has("group") ? rule.get("group").getAsString() : "misc";
			boolean covered = named.contains(id)
				|| (!"memory".equals(group) && triggerTypes(rule.getAsJsonObject("when")).stream().anyMatch(triggers::contains));
			if (covered && !"reactions".equals(group))
			{
				elsewhere.add(id + " (" + group + ")");
			}
		}
		assertTrue("said on a death, drop or level-up but not under Reactions: " + elsewhere, elsewhere.isEmpty());
	}

	private static Set<String> triggerTypes(JsonObject when)
	{
		Set<String> out = new HashSet<>();
		if (when == null)
		{
			return out;
		}
		if (when.has("type"))
		{
			out.add(when.get("type").getAsString());
		}
		if (when.has("conditions"))
		{
			for (JsonElement c : when.getAsJsonArray("conditions"))
			{
				out.addAll(triggerTypes(c.getAsJsonObject()));
			}
		}
		return out;
	}

	// ----------------------------------------------------------------- glyphs

	/**
	 * The game draws with bitmap fonts of exactly 256 glyphs, and
	 * {@code GameFont} picks one as the client does: cp1252, with a '?' for a
	 * character that has no byte there.
	 *
	 * <p>So a character above U+00FF either becomes a question mark or lands
	 * on a glyph not every font has: an em-dash, U+2014, is glyph 0x97, which
	 * 496 holds and 497 does not. The line does not come out blank, it comes
	 * out as a different character, and nothing anywhere reports it.
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
		// Anything past Latin-1 is a '?' or a glyph not every font has.
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

	/**
	 * Nothing so long that reading it becomes the activity.
	 *
	 * <p>The display time now scales with the line, so an over-long line is no
	 * longer unreadable - it is SLOW, and it holds the queue behind it for as
	 * long as it is up. Five seconds at a comfortable reading speed is about
	 * the point where an overhead remark stops being a remark.
	 *
	 * <p>Sits next to the glyph check because it is the same kind of rule: a
	 * property of the text that nothing at runtime will ever complain about.
	 */
	@Test
	public void nothingTakesLongerToReadThanItIsWorth() throws IOException
	{
		final int cps = 17;
		final double maxSeconds = 5.0;
		int limit = (int) (cps * maxSeconds);

		Harness h = new Harness(folder.newFolder().toPath());
		List<String> slow = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String line : rule.say)
			{
				if (line.length() > limit)
				{
					slow.add(String.format("%s: %d chars, %.1fs to read - %s",
						rule.id, line.length(), line.length() / (double) cps, line));
				}
			}
		}
		assertTrue("lines that take longer to read than they are worth:\n  "
			+ String.join("\n  ", slow), slow.isEmpty());
	}

	/**
	 * {@code 've} may help a verb. It may not be one.
	 *
	 * <p>"I've seen" is fine: the contraction is an auxiliary and the participle
	 * does the work. "I've no complaints" is not, because there is no
	 * participle - {@code 've} is carrying the whole of "have" on its own, and
	 * that only survives in a register two generations older than this
	 * follower's. The test is simply what comes next: a determiner or a bare
	 * pronoun means nothing is coming to help.
	 *
	 * <p>Six of these had accumulated - four rules, an area line, and a node in
	 * the Talk-to tree - and none was caught by reading. The character document
	 * says to contract by default, so each one looked like obedience.
	 *
	 * <p>Scans both bundled files as raw text rather than walking two different
	 * object models. The defect is a property of the words, and the half of it
	 * that got missed last time was the half that lived in the other file.
	 */
	@Test
	public void aContractionMayHelpAVerbButMayNotBeOne() throws IOException
	{
		String determiners = "a|an|the|no|some|any|my|your|his|her|its|our|their"
			+ "|this|that|these|those|one|two|three|four|five|several|enough"
			+ "|plenty|nothing|something|anything|everything|none|both|half";
		java.util.regex.Pattern bare = java.util.regex.Pattern.compile(
			"\\b\\w+'ve\\s+(?:" + determiners + ")\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

		List<String> wrong = new ArrayList<>();
		for (String resource : new String[]{
			"/com/follower/default-phrases.json",
			"/com/follower/default-dialogs.json"})
		{
			java.util.regex.Matcher m = bare.matcher(bundledText(resource));
			while (m.find())
			{
				wrong.add(resource + ": \"" + m.group() + "\"");
			}
		}
		assertTrue("'ve used as a verb rather than as an auxiliary. Let the"
			+ " participle back in (\"I've got no...\") or drop the pronoun and"
			+ " let it be a fragment (\"No complaints.\"):\n  "
			+ String.join("\n  ", wrong), wrong.isEmpty());
	}

	/**
	 * Every authored flourish is playable as written.
	 *
	 * <p>The prop is equipment and the pose is a loop held as an override;
	 * neither is checked at runtime beyond a warning log, so a typo here is a
	 * gesture that silently never happens. The item must be one the wearable
	 * dump knows, the pose must be present, and the hold must be long enough
	 * to actually render and short enough not to plant the follower for half
	 * a minute. A prop rule must not also carry an animation - the flourish
	 * owns the follower's body for its duration.
	 */
	@Test
	public void everyFlourishIsPlayableAsWritten() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> broken = unplayableFlourishes(h.loader.getRules());
		assertTrue("flourishes that cannot play as written:\n  "
			+ String.join("\n  ", broken), broken.isEmpty());
	}

	/**
	 * Only a flourish that needs the Stay is refused on it. One kept off the
	 * Stay - staying under a none - is exactly the one that plays, and the
	 * check once flagged it too, since it asked only whether staying was
	 * named anywhere in the tree.
	 */
	@Test
	public void aFlourishKeptOffTheStayIsPlayable() throws IOException
	{
		String prop = "\"prop\": {\"item\": 10485, \"pose\": 5354, \"ticks\": 10},"
			+ " \"group\": \"t\", \"say\": [\"Notes.\"],";
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"off-the-post\", " + prop
				+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"chance\","
				+ " \"percent\": 2}, {\"type\": \"none\", \"conditions\": [{\"type\": \"staying\"}]}]}},"
				+ "{\"id\": \"either-way\", " + prop
				+ " \"when\": {\"type\": \"any\", \"conditions\": [{\"type\": \"staying\"},"
				+ " {\"type\": \"idle\"}]}},"
				+ "{\"id\": \"at-the-post\", " + prop
				+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"staying\"},"
				+ " {\"type\": \"chance\", \"percent\": 2}]}}]}");
		assertEquals(3, h.loader.getRules().size());
		assertEquals(java.util.Collections.singletonList(
			"at-the-post: the flourish is refused on a Stay, so the prop never shows"),
			unplayableFlourishes(h.loader.getRules()));
	}

	private static List<String> unplayableFlourishes(List<SpeechRule> rules)
	{
		List<String> broken = new ArrayList<>();
		for (SpeechRule rule : rules)
		{
			if (rule.prop == null)
			{
				continue;
			}
			if (rule.prop.item == null || rule.prop.item <= 0)
			{
				broken.add(rule.id + ": no prop item");
			}
			if (rule.prop.pose == null || rule.prop.pose <= 0)
			{
				broken.add(rule.id + ": no prop pose");
			}
			// Under three ticks the gesture barely renders; over twenty-five
			// the follower is planted for a quarter of a minute.
			int ticks = rule.prop.ticks == null ? 8 : rule.prop.ticks;
			if (ticks < 3 || ticks > 25)
			{
				broken.add(rule.id + ": hold of " + ticks + " ticks is outside 3..25");
			}
			if (rule.hasAnimationAction())
			{
				broken.add(rule.id + ": carries both a prop and an animation");
			}
			if (!rule.hasSpeech())
			{
				broken.add(rule.id + ": a silent flourish is a follower stopping"
					+ " for no visible reason");
			}
			// The flourish plants the follower and lets it go at the end, which
			// would end a Stay, so it is refused on one: the prop never shows.
			if (requiresType(rule.when, "staying"))
			{
				broken.add(rule.id + ": the flourish is refused on a Stay, so the prop never shows");
			}
		}
		return broken;
	}

	/**
	 * Whether a when-block can only match with this type true: the type
	 * itself, any branch of an all, or every branch of an any. Nothing under
	 * a none, which asks for the opposite.
	 */
	private static boolean requiresType(Condition condition, String type)
	{
		if (condition == null)
		{
			return false;
		}
		if (type.equalsIgnoreCase(condition.type))
		{
			return true;
		}
		if (condition.conditions == null || condition.conditions.isEmpty())
		{
			return false;
		}
		if ("all".equalsIgnoreCase(condition.type))
		{
			for (Condition child : condition.conditions)
			{
				if (requiresType(child, type))
				{
					return true;
				}
			}
			return false;
		}
		if ("any".equalsIgnoreCase(condition.type))
		{
			for (Condition child : condition.conditions)
			{
				if (!requiresType(child, type))
				{
					return false;
				}
			}
			return true;
		}
		return false;
	}

	// public:cut errands
	/**
	 * Every study target the controller can find has its lines, both ends.
	 *
	 * <p>The target list lives in code and the lines live in phrases.json, and
	 * nothing at runtime notices when they drift: a target without rules is a
	 * follower that walks up to a fountain, writes solemnly in its scroll, and
	 * says nothing at all - which reads as a bug precisely because everything
	 * else about the moment worked.
	 */
	@Test
	public void everyStudyTargetHasItsLines() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> missing = new ArrayList<>();
		for (String target : com.follower.follower.ErrandController.STUDY_TARGETS)
		{
			String key = com.follower.follower.ErrandController.siteKey("study", target);
			boolean start = false;
			boolean end = false;
			for (SpeechRule rule : h.loader.getRules())
			{
				if (rule.when == null || rule.when.names == null
					|| !rule.when.names.contains(key))
				{
					continue;
				}
				start |= rule.when.usesType("errandStart");
				end |= rule.when.usesType("errandEnd");
			}
			if (!start)
			{
				missing.add(key + " has no start lines");
			}
			if (!end)
			{
				missing.add(key + " has no end lines");
			}
		}
		assertTrue("study targets the rules do not know:\n  "
			+ String.join("\n  ", missing), missing.isEmpty());
	}

	// public:end errands
	/**
	 * The voice eras (R16) must tile the calendar: on every day known there
	 * is exactly one greeting and one level-up voice - no gap where the
	 * follower falls silent, no overlap where two hellos race each other.
	 */
	@Test
	public void theVoiceErasTileTheCalendar() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		String[][] families = {
			{"login-greeting-new", "login-greeting-settling", "login-greeting"},
			{"level-up-new", "level-up"},
		};
		for (String[] family : families)
		{
			for (int day = 0; day <= 40; day++)
			{
				List<String> holders = new ArrayList<>();
				for (String id : family)
				{
					Condition window = daysKnownWindow(h, id);
					int minimum = window.minimum == null ? 1 : window.minimum;
					if (day >= minimum
						&& (window.maximum == null || day <= window.maximum))
					{
						holders.add(id);
					}
				}
				assertEquals("day " + day + " of " + family[family.length - 1]
					+ "'s family belongs to " + holders, 1, holders.size());
			}
		}
	}

	private static Condition daysKnownWindow(Harness h, String ruleId)
	{
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!ruleId.equals(rule.id))
			{
				continue;
			}
			for (Condition part : rule.when.conditions)
			{
				if ("daysKnown".equalsIgnoreCase(part.type))
				{
					return part;
				}
			}
		}
		throw new AssertionError(ruleId + " no longer carries a daysKnown era gate");
	}

	/**
	 * R7: a callout at somebody the player cannot see is a bad callout, and
	 * a bad callout is worse than silence. Every npcNearby in the shipped
	 * set confirms line of sight, because every rule that uses it names or
	 * gestures at the body in question - and a future one will too.
	 */
	@Test
	public void everyNearbyCalloutConfirmsSight() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> unsighted = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			collectUnsightedNearby(rule.when, rule.id, unsighted);
		}
		assertTrue("rules pointing at NPCs without confirming they are visible: "
			+ unsighted, unsighted.isEmpty());
	}

	private static void collectUnsightedNearby(Condition condition, String ruleId,
		List<String> into)
	{
		if (condition == null)
		{
			return;
		}
		if ("npcNearby".equalsIgnoreCase(condition.type)
			&& !Boolean.TRUE.equals(condition.visible))
		{
			into.add(ruleId);
		}
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				collectUnsightedNearby(child, ruleId, into);
			}
		}
	}

	// public:cut errands
	/** The explore list is held to the same bargain as the study list above. */
	@Test
	public void everyExploreTargetHasItsLines() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> missing = new ArrayList<>();
		for (String target : com.follower.follower.ErrandController.EXPLORE_TARGETS)
		{
			String key = com.follower.follower.ErrandController.siteKey("explore", target);
			boolean start = false;
			boolean end = false;
			for (SpeechRule rule : h.loader.getRules())
			{
				if (rule.when == null || rule.when.names == null
					|| !rule.when.names.contains(key))
				{
					continue;
				}
				start |= rule.when.usesType("errandStart");
				end |= rule.when.usesType("errandEnd");
			}
			if (!start)
			{
				missing.add(key + " has no start lines");
			}
			if (!end)
			{
				missing.add(key + " has no end lines");
			}
		}
		assertTrue("explore targets the rules do not know:\n  "
			+ String.join("\n  ", missing), missing.isEmpty());
	}

	// public:end errands
	private static String bundledText(String resource) throws IOException
	{
		try (InputStream in = RuleSetIntegrityTest.class.getResourceAsStream(resource))
		{
			assertTrue("missing bundled resource " + resource, in != null);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int read; (read = in.read(buffer)) != -1; )
			{
				out.write(buffer, 0, read);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

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
		Set<String> levelExtras = new HashSet<>(Arrays.asList("skill", "level",
			"total", "base", "nines", "ninesOrdinal"));
		byType.put("levelup", levelExtras);
		byType.put("milestone", levelExtras);
		// The gaps are empty strings until there is a stamp to measure from,
		// so levelGap is the guard that makes them printable.
		Set<String> gapExtras = new HashSet<>(levelExtras);
		gapExtras.addAll(Arrays.asList("sinceLast", "sinceAny"));
		byType.put("levelgap", gapExtras);
		byType.put("killcount", new HashSet<>(Arrays.asList("boss", "count", "ordinal", "lastCount")));
		byType.put("lasttime", new HashSet<>(Arrays.asList("lastTime")));
		// public:cut company-extras
		byType.put("noticed", new HashSet<>(Arrays.asList("kind", "thing"))); // public:cut noticing
		byType.put("hesitated", new HashSet<>(Arrays.asList("misgiving", "thing"))); // public:cut will
		// public:end company-extras
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
		byType.put("worldhop", new HashSet<>(Arrays.asList("world")));
		byType.put("musicunlock", new HashSet<>(Arrays.asList("track")));
		byType.put("musictrack", new HashSet<>(Arrays.asList("track")));
		byType.put("examinedthing", new HashSet<>(Arrays.asList("target")));
		byType.put("collectionadd", new HashSet<>(Arrays.asList("item")));
		byType.put("questcomplete", new HashSet<>(Arrays.asList("quest")));
		byType.put("pagewritten", new HashSet<>(Arrays.asList("page")));
		byType.put("geoffer", new HashSet<>(Arrays.asList("item", "value")));
		byType.put("channeljoined", new HashSet<>(Arrays.asList("name")));
		byType.put("channelleft", new HashSet<>(Arrays.asList("name")));
		byType.put("npcshout", new HashSet<>(Arrays.asList("npc", "message")));

		// These two come off the state snapshot rather than the event, so they
		// are readable from any trigger - but they are EMPTY unless there is
		// something to read, and an empty one leaves a hole in the sentence
		// ("I keep thinking about ."). Listing them as conditional rather than
		// ambient is what makes the guard compulsory.
		Set<String> memoryExtras = new HashSet<>(Arrays.asList("memory", "memoryWhere", "memoryWhen", "memoryAgo"));
		byType.put("remembers", memoryExtras);
		byType.put("memoryhere", memoryExtras);
		Set<String> foeExtras = new HashSet<>(memoryExtras);
		foeExtras.add("memoryWho");
		byType.put("memoryfoenearby", foeExtras);
		byType.put("carrying", new HashSet<>(Arrays.asList("souvenir")));
		byType.put("wishing", new HashSet<>(Arrays.asList("wish")));
		byType.put("souvenirlost", new HashSet<>(Arrays.asList("souvenir")));
		byType.put("happenedhere", new HashSet<>(Arrays.asList("here")));
		byType.put("nicknamed", new HashSet<>(Arrays.asList("nickname")));
		byType.put("nicknameearned", new HashSet<>(Arrays.asList("candidate")));
		byType.put("answered", new HashSet<>(Arrays.asList("answer", "candidate")));
		byType.put("heeded", new HashSet<>(Arrays.asList("advice")));
		byType.put("ignored", new HashSet<>(Arrays.asList("advice")));
		byType.put("challengemet", new HashSet<>(Arrays.asList("challenge")));
		byType.put("challengefailed", new HashSet<>(Arrays.asList("challenge")));
		byType.put("challenging", new HashSet<>(Arrays.asList("challenge", "left")));

		// {days} reads a date the follower has had since its first login, so
		// unlike the others it is genuinely always there - but it is zero
		// until that date exists, and "0 days" is not a sentence. daysKnown
		// is the guard that makes it a number worth printing.
		byType.put("daysknown", new HashSet<>(Arrays.asList("days")));
		byType.put("anniversary", new HashSet<>(Arrays.asList("days")));

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

	/**
	 * Every animation the follower can be made to play from a rule moves the
	 * player's skeleton. poses.tsv lists every animation that does, so an id
	 * missing from it is a creature's clip, which bends the follower's human
	 * model out of shape for its length. A mirror rule plays the id that
	 * triggered it, so its trigger list counts too: mimic-emotes once carried
	 * the penguin, Easter egg, party graphic and cow pet emotes (QA, 15 Oct).
	 */
	@Test
	public void everyAnimationARuleCanPlayIsOnThePlayerSkeleton() throws IOException
	{
		Set<Integer> playerSkeleton = new HashSet<>();
		try (InputStream in = RuleSetIntegrityTest.class.getResourceAsStream("/com/follower/poses.tsv");
			java.io.BufferedReader reader = new java.io.BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("id\t"))
				{
					playerSkeleton.add(Integer.parseInt(line.split("\t")[0]));
				}
			}
		}
		assertTrue("poses.tsv looks too short to be every player animation",
			playerSkeleton.size() > 3000);

		Harness h = new Harness(folder.newFolder().toPath());
		Set<String> problems = new TreeSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			List<Integer> played = new ArrayList<>();
			if (rule.animation != null)
			{
				played.add(rule.animation);
			}
			if (rule.animations != null)
			{
				played.addAll(rule.animations);
			}
			if (rule.prop != null && rule.prop.pose != null)
			{
				played.add(rule.prop.pose);
			}
			if ((Boolean.TRUE.equals(rule.mirrorAnimation) || Boolean.TRUE.equals(rule.mirrorPose))
				&& rule.when != null)
			{
				collectAnimationSelfIds(rule.when, played);
			}
			for (Integer id : played)
			{
				if (id != null && id > 0 && !playerSkeleton.contains(id))
				{
					problems.add(rule.id + " " + id);
				}
			}
		}
		assertTrue("animations off the player skeleton: " + problems, problems.isEmpty());
	}

	private static void collectAnimationSelfIds(Condition condition, List<Integer> out)
	{
		if ("animationSelf".equalsIgnoreCase(condition.type) && condition.ids != null)
		{
			out.addAll(condition.ids);
		}
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				if (child != null)
				{
					collectAnimationSelfIds(child, out);
				}
			}
		}
	}

	/**
	 * A souvenir is picked up, so the follower bends for it, and before the
	 * rest sit at 500 idle ticks: in the 500-620 window it said it had picked
	 * something up as it sat down, and its body did nothing (QA, 15 Oct).
	 */
	// public:cut souvenirs-wishes
	@Test
	public void aSouvenirIsBentForBeforeTheRestSit() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		int souvenirs = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.pickUp == null || !rule.id.startsWith("souvenir-"))
			{
				continue;
			}
			souvenirs++;
			assertEquals(rule.id + " bends to the floor for it", Integer.valueOf(827), rule.animation);
			assertEquals(rule.id + " stands still to", Boolean.TRUE, rule.holdStill);
			assertTrue(rule.id + " fires on the rest", idleBelow(rule.when) <= 500);
		}
		assertTrue(souvenirs >= 5);
	}
	// public:end souvenirs-wishes

	/** The tightest idleBelow a condition tree's top-level all puts on it, or MAX_VALUE. */
	private static int idleBelow(Condition condition)
	{
		int below = Integer.MAX_VALUE;
		if ("idleBelow".equalsIgnoreCase(condition.type) && condition.ticks != null)
		{
			below = condition.ticks;
		}
		if ("all".equalsIgnoreCase(condition.type) && condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				below = Math.min(below, idleBelow(child));
			}
		}
		return below;
	}

	@Test
	public void theStampAndTheAnniversaryWaveAreNotPlayedFromTheRest() throws IOException
	{
		// Both can fire past the rest's 500 idle ticks, and a gesture from
		// the sit popped the follower up with no stand-up (QA, 15 Oct).
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"cold-stamp-feet", "anniversary"})
		{
			assertTrue(id + " fires on the rest", idleBelow(h.rule(id).when) <= 500);
		}
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

	/**
	 * A tally condition with a maximum and no minimum still has one: the
	 * engine defaults it to 1. QA, 15 Oct: grudge-noted asked for "want:kept"
	 * at most 0 that way, which no count can be, so it never said a word.
	 */
	@Test
	public void noTallyAsksForACountItCannotHave() throws IOException
	{
		JsonArray raw = bundled("/com/follower/default-phrases.json").getAsJsonArray("rules");
		List<String> problems = new ArrayList<>();
		for (JsonElement element : raw)
		{
			JsonObject rule = element.getAsJsonObject();
			for (JsonObject tally : conditionsOfType(rule.get("when"), "tally"))
			{
				int minimum = tally.has("minimum") ? tally.get("minimum").getAsInt() : 1;
				if (tally.has("maximum") && tally.get("maximum").getAsInt() < minimum)
				{
					problems.add(rule.get("id").getAsString() + " (" + tally + ")");
				}
			}
		}
		assertTrue("tally ranges no count can fall in: " + problems, problems.isEmpty());
	}

	/**
	 * A levelUp with no names, contains or regex matches no level at all:
	 * the skill is matched as text, and with nothing to match it against the
	 * answer is no. QA, 15 Oct: levels-many was dead that way.
	 */
	@Test
	public void everyLevelUpNamesTheSkillsItWants() throws IOException
	{
		JsonArray raw = bundled("/com/follower/default-phrases.json").getAsJsonArray("rules");
		List<String> problems = new ArrayList<>();
		for (JsonElement element : raw)
		{
			JsonObject rule = element.getAsJsonObject();
			for (JsonObject level : conditionsOfType(rule.get("when"), "levelUp"))
			{
				if (!level.has("names") && !level.has("contains") && !level.has("regex"))
				{
					problems.add(rule.get("id").getAsString());
				}
			}
		}
		assertTrue("levelUp conditions that can never match: " + problems, problems.isEmpty());
	}

	/** Every condition of one type in a when-block, however nested. */
	private static List<JsonObject> conditionsOfType(JsonElement when, String type)
	{
		List<JsonObject> found = new ArrayList<>();
		if (when == null || !when.isJsonObject())
		{
			return found;
		}
		JsonObject block = when.getAsJsonObject();
		if (block.has("type") && type.equalsIgnoreCase(block.get("type").getAsString()))
		{
			found.add(block);
		}
		if (block.has("conditions") && block.get("conditions").isJsonArray())
		{
			for (JsonElement child : block.getAsJsonArray("conditions"))
			{
				found.addAll(conditionsOfType(child, type));
			}
		}
		return found;
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
	public void theLowMoodRefusalsStayInPlace() throws IOException
	{
		// The social offers go quiet at a genuinely low band - that friction
		// IS the low-mood arc, and it would vanish silently if an edit to
		// these rules dropped the mood guard. Any mood condition anywhere in
		// the tree counts; the arc only needs the band consulted.
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> unguarded = new ArrayList<>();
		for (String id : new String[]{"offer-game", "offer-challenge", "ask-outing"})
		{
			for (SpeechRule rule : h.loader.getRules())
			{
				if (id.equals(rule.id) && !consultsType(rule.when, "mood"))
				{
					unguarded.add(id);
				}
			}
		}
		assertTrue("social offers that no longer consult the mood band, so they"
			+ " would cheerfully fire on the follower's worst day: " + unguarded,
			unguarded.isEmpty());
	}

	/**
	 * The grind guard (R24): anything that OPENS a demanding interaction - a
	 * question, a want, a wish, a challenge - must consult the repeating
	 * condition, so none of them lands while the player is mid-task in the
	 * activity they deliberately entered for calm. Field-driven rather than
	 * id-driven, so a future opener cannot arrive without its guard.
	 */
	@Test
	public void theGrindGuardStaysInPlace() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> unguarded = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			boolean opener = rule.asks != null || rule.want != null
				|| rule.wish != null // public:cut souvenirs-wishes
				|| rule.challenge != null; // public:cut side-games
				// public:else ;
			if (opener && !consultsType(rule.when, "repeating"))
			{
				unguarded.add(rule.id);
			}
		}
		assertTrue("openers that would interrupt a deliberate grind: " + unguarded,
			unguarded.isEmpty());
	}

	private static boolean consultsType(Condition condition, String type)
	{
		if (condition == null)
		{
			return false;
		}
		if (type.equalsIgnoreCase(condition.type))
		{
			return true;
		}
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				if (consultsType(child, type))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The line book (docs/lines.md) is generated from the rule file by
	 * tools/lines.py and is the surface every line is reviewed on. A book
	 * that drifts from the rules reviews the wrong words, so the build holds
	 * it current: the check runs the generator in --check mode and fails
	 * when a regeneration is due. Skipped where python is not on the path
	 * (a CI box that lacks it must not turn the whole suite red).
	 */
	@Test
	public void theLineBookIsCurrent() throws IOException, InterruptedException
	{
		java.nio.file.Path tool = java.nio.file.Paths.get("tools", "lines.py");
		if (!java.nio.file.Files.exists(tool))
		{
			return;
		}
		Process check;
		try
		{
			check = new ProcessBuilder("python", tool.toString(), "--check")
				.redirectErrorStream(true).start();
		}
		catch (IOException noPython)
		{
			return;
		}
		String output = new String(check.getInputStream().readAllBytes(),
			StandardCharsets.UTF_8);
		int code = check.waitFor();
		assertTrue("docs/lines.md is stale - run: python tools/lines.py\n" + output,
			code == 0);
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

	// ---------------------------------------------------------------- tallies

	/**
	 * Every tally a rule wagers on, is nicknamed after, or gates itself on has
	 * to be a counter some code path actually increments.
	 *
	 * <p>The kill challenge and the "local menace" nickname both named a
	 * lifetime key nothing ever wrote. The wager failed on its deadline however
	 * well the player did, and the name could never be earned. Nothing could
	 * see it: the key is perfectly legal, it is simply always zero. The written
	 * set is read back out of the source rather than listed here, because a
	 * list is a thing that drifts out of date silently - which is the failure
	 * this is here to end, not to repeat.
	 */
	@Test
	public void everyTallyTheRulesNameIsOneTheCodeWrites() throws IOException
	{
		Set<String> written = new TreeSet<>();
		Set<String> prefixes = new TreeSet<>();
		for (String source : new String[]{
			"src/main/java/com/follower/FollowerPlugin.java",
			"src/main/java/com/follower/speech/SpeechEngine.java"})
		{
			java.nio.file.Path path = java.nio.file.Paths.get(source);
			assertTrue("cannot read " + source, java.nio.file.Files.isRegularFile(path));
			collectTalliedKeys(PublicMarkers.strip(new String(
				java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8)),
				written, prefixes);
		}
		assertFalse("no tally calls found at all - the scan broke, not the rules",
			written.isEmpty() && prefixes.isEmpty());

		JsonObject file = bundled("/com/follower/default-phrases.json");
		List<String> problems = new ArrayList<>();

		if (file.has("nicknames"))
		{
			for (java.util.Map.Entry<String, JsonElement> nickname
				: file.getAsJsonObject("nicknames").entrySet())
			{
				if (!isWritten(nickname.getKey(), written, prefixes))
				{
					problems.add("the nickname '" + nickname.getValue().getAsString()
						+ "' is earned on tally '" + nickname.getKey()
						+ "', which nothing writes");
				}
			}
		}

		for (JsonElement element : file.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			String id = rule.has("id") ? rule.get("id").getAsString() : "?";
			if (rule.has("challenge") && rule.get("challenge").isJsonObject())
			{
				JsonObject challenge = rule.getAsJsonObject("challenge");
				if (challenge.has("tally")
					&& !isWritten(challenge.get("tally").getAsString(), written, prefixes))
				{
					problems.add(id + " wagers on tally '"
						+ challenge.get("tally").getAsString() + "', which nothing writes");
				}
			}
			for (String of : tallyKeysIn(rule.get("when")))
			{
				if (!isWritten(of, written, prefixes))
				{
					problems.add(id + " gates on tally '" + of + "', which nothing writes");
				}
			}
		}

		assertTrue("tallies named by rules that no code path increments:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	/** The {@code of} of every tally condition in a when-block, however nested. */
	private static List<String> tallyKeysIn(JsonElement when)
	{
		List<String> keys = new ArrayList<>();
		if (when == null || !when.isJsonObject())
		{
			return keys;
		}
		JsonObject block = when.getAsJsonObject();
		if (block.has("type")
			&& "tally".equalsIgnoreCase(block.get("type").getAsString())
			&& block.has("of"))
		{
			keys.add(block.get("of").getAsString());
		}
		if (block.has("conditions") && block.get("conditions").isJsonArray())
		{
			for (JsonElement child : block.getAsJsonArray("conditions"))
			{
				keys.addAll(tallyKeysIn(child));
			}
		}
		return keys;
	}

	/**
	 * Every string literal handed to a tally call in the given source, split
	 * by what it actually produces.
	 *
	 * <p>The split is the whole point. A literal that IS the argument writes
	 * that key exactly; a literal glued to something else ({@code "kill:" +
	 * name}, {@code "kills" + TODAY}) only ever writes keys LONGER than
	 * itself, and reading it as a whole key is precisely the mistake that let
	 * a challenge wager on a counter that was never written.
	 */
	private static void collectTalliedKeys(String source,
		Set<String> exact, Set<String> prefixes)
	{
		// Case matters: this must not match getTally(), which only READS.
		java.util.regex.Matcher call = java.util.regex.Pattern
			.compile("tallyBoth\\(|tally\\(").matcher(source);
		while (call.find())
		{
			int open = call.end() - 1;
			int depth = 0;
			int end = open;
			while (end < source.length())
			{
				char c = source.charAt(end);
				if (c == '(')
				{
					depth++;
				}
				else if (c == ')' && --depth == 0)
				{
					break;
				}
				end++;
			}

			String argument = source.substring(open, Math.min(end + 1, source.length()));
			java.util.regex.Matcher literal = java.util.regex.Pattern
				.compile("\"([^\"]+)\"").matcher(argument);
			while (literal.find())
			{
				int after = literal.end();
				while (after < argument.length()
					&& Character.isWhitespace(argument.charAt(after)))
				{
					after++;
				}
				boolean glued = after < argument.length() && argument.charAt(after) == '+';
				(glued ? prefixes : exact).add(literal.group(1));
			}
		}
	}

	/**
	 * Whether a key is one the code writes: exactly, as today's half of a
	 * lifetime pair, or as something longer than a prefix the code builds on.
	 */
	private static boolean isWritten(String key, Set<String> exact, Set<String> prefixes)
	{
		if (exact.contains(key))
		{
			return true;
		}
		String base = key.endsWith(":today")
			? key.substring(0, key.length() - ":today".length()) : key;
		if (exact.contains(base))
		{
			return true;
		}
		for (String prefix : prefixes)
		{
			// Strictly longer: a prefix is only ever half of the key it makes.
			if (key.length() > prefix.length() && key.startsWith(prefix))
			{
				return true;
			}
		}
		return false;
	}
}
