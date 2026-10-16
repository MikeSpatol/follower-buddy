package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.Condition;
import com.follower.speech.SpeechEngine;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A draft rule file - the quest moments, before their text is approved and
 * they move into default-phrases.json - loaded through the real loader into
 * a harness of its own. The shipped file is not touched.
 *
 * <p>Point {@code FOLLOWER_DRAFT_RULES} at the draft ({@code {"rules": [...]}})
 * to run it; without it the test is skipped.
 */
public class QuestMomentDraftTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private JsonObject draft;

	@Before
	public void readDraft() throws IOException
	{
		String path = System.getenv("FOLLOWER_DRAFT_RULES");
		Assume.assumeTrue("FOLLOWER_DRAFT_RULES not set", path != null && !path.isEmpty());
		Path file = Paths.get(path);
		Assume.assumeTrue("no draft at " + file, Files.exists(file));
		draft = new JsonParser().parse(
			new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
		draft.addProperty("version", 1);
	}

	private Harness load(String json) throws IOException
	{
		return new Harness(folder.newFolder().toPath(), json);
	}

	private static void walk(Condition condition, Consumer<Condition> visit)
	{
		if (condition == null)
		{
			return;
		}
		visit.accept(condition);
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				walk(child, visit);
			}
		}
	}

	@Test
	public void everyRuleParsesAndEveryConditionIsKnown() throws IOException
	{
		Harness h = load(draft.toString());
		int written = draft.getAsJsonArray("rules").size();
		assertTrue("loader problems: " + h.loader.getErrors(), h.loader.getErrors().isEmpty());
		assertEquals("every rule loaded", written, h.loader.getRules().size());

		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			walk(rule.when, condition ->
			{
				String type = condition.type == null ? "" : condition.type.toLowerCase(Locale.ROOT);
				if (!RuleSetIntegrityTest.KNOWN_TYPES.contains(type))
				{
					problems.add(rule.id + ": unknown type " + condition.type);
				}
				if (type.equals("questvar") && (condition.varbit == null) == (condition.varp == null))
				{
					problems.add(rule.id + ": questVar needs exactly one of varbit or varp");
				}
				if (type.equals("questvar") && condition.minimum == null && condition.maximum == null)
				{
					problems.add(rule.id + ": questVar with no range");
				}
			});
			if (!rule.id.startsWith("qm-") || !"quest".equals(rule.group) || !rule.isOnce()
				|| !rule.isOccasion() || !"overhead".equals(rule.output) || !rule.hasSpeech())
			{
				problems.add(rule.id + ": not shaped as a quest moment");
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void noCopyCanBeQueuedTwice() throws Exception
	{
		// A first is spent when it lands. Until then only the cooldown stops a
		// second copy: behind one in the plugin's queue, or behind one the
		// engine holds through a cutscene.
		Field field = FollowerPlugin.class.getDeclaredField("SPEECH_STALE_MS");
		field.setAccessible(true);
		long stale = ((Number) field.get(null)).longValue();
		Field wait = SpeechEngine.class.getDeclaredField("SCENE_WAIT_TICKS");
		wait.setAccessible(true);
		long scene = ((Number) wait.get(null)).longValue() * 600L;

		Harness h = load(draft.toString());
		List<String> short_ = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.cooldownMs <= Math.max(stale, scene))
			{
				short_.add(rule.id + " cools down in " + rule.cooldownMs + "ms");
			}
		}
		assertTrue(String.join("\n", short_), short_.isEmpty());
	}

	/**
	 * Every moment that rests on quest progress alone, driven into its range
	 * on its own: the values set, the dialogue box shut, and the line said.
	 */
	@Test
	public void everyProgressOnlyMomentFires() throws IOException
	{
		Harness all = load(draft.toString());
		List<String> silent = new ArrayList<>();
		int tried = 0;
		for (SpeechRule rule : all.loader.getRules())
		{
			boolean progressOnly = true;
			for (Condition c : rule.when.conditions)
			{
				String type = c.type.toLowerCase(Locale.ROOT);
				progressOnly &= type.equals("questvar") || type.equals("none");
			}
			if (!progressOnly)
			{
				continue;
			}
			tried++;
			JsonObject one = new JsonObject();
			one.addProperty("version", 1);
			com.google.gson.JsonArray only = new com.google.gson.JsonArray();
			for (com.google.gson.JsonElement r : draft.getAsJsonArray("rules"))
			{
				if (rule.id.equals(r.getAsJsonObject().get("id").getAsString()))
				{
					only.add(r);
				}
			}
			one.add("rules", only);

			Harness h = load(one.toString());
			h.gameTicks(1);
			for (Condition c : rule.when.conditions)
			{
				if ("questvar".equalsIgnoreCase(c.type))
				{
					int value = c.minimum != null ? c.minimum : c.maximum;
					if (c.varbit != null)
					{
						h.game.varbit(c.varbit, value);
					}
					else
					{
						h.game.varp(c.varp, value);
					}
				}
			}
			h.gameTicks(5);
			if (h.firedBy(rule.id).isEmpty())
			{
				silent.add(rule.id);
			}
			else if (rule.remember != null
				&& !rule.remember.as.equals(h.engine.getContext().getIncidentPhrase()))
			{
				silent.add(rule.id + " (said, but filed '"
					+ h.engine.getContext().getIncidentPhrase() + "')");
			}
		}
		assertTrue("no progress-only moments found - the scan broke", tried > 0);
		assertTrue("never said: " + silent, silent.isEmpty());
	}

	/**
	 * The gestures a moment may play, by RuneLite gameval AnimationID name:
	 * player animations only, since the follower is a player model - the
	 * emotes, and the mood stances the shipped idle rules already play.
	 */
	private static final List<String> GESTURES = Arrays.asList(
		"EMOTE_YES", "EMOTE_NO", "EMOTE_THINK", "EMOTE_BOW", "EMOTE_CRY", "EMOTE_LAUGH",
		"EMOTE_CHEER", "EMOTE_WAVE", "EMOTE_BECKON", "EMOTE_CLAP", "EMOTE_PANIC",
		"EMOTE_JUMP_WITH_JOY", "EMOTE_YAWN", "EMOTE_SHRUG", "EMOTE_SLAP_HEAD", "EMOTE_LIGHTBULB",
		"HUMAN_DISTANT_IDLE", "HUMAN_CONCERNED_IDLE", "HUMAN_PLEASED_IDLE",
		"HUMAN_DISGRUNTLED_IDLE", "HUMAN_DETERMINED_IDLE", "HUMAN_SMUG_IDLE",
		"HUMAN_CONFUSED_IDLE");

	@Test
	public void everyGestureIsOneTheFollowerCanPlay() throws Exception
	{
		// Read from the jar the plugin compiles against, so a renumbered or
		// misremembered id fails here rather than as a wrong pose in game.
		Map<Integer, String> playable = new HashMap<>();
		for (String name : GESTURES)
		{
			playable.put(net.runelite.api.gameval.AnimationID.class.getField(name).getInt(null), name);
		}
		Harness h = load(draft.toString());
		List<String> wrong = new ArrayList<>();
		int gestures = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.animations != null || rule.prop != null || rule.mirrorAnimation != null
				|| rule.mirrorPose != null)
			{
				wrong.add(rule.id + ": a moment plays one plain gesture, nothing else");
			}
			if (rule.animation == null)
			{
				continue;
			}
			gestures++;
			if (!playable.containsKey(rule.animation))
			{
				wrong.add(rule.id + ": animation " + rule.animation
					+ " is not a gesture the follower plays");
			}
		}
		assertTrue("no gestures found - the scan broke", gestures > 0);
		assertTrue(String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void everyMemoryIsWellFormed() throws IOException
	{
		// Shaped like the shipped memory rules: a key a recall can name, and
		// a phrase that reads inside "I keep thinking about {memory}".
		Harness h = load(draft.toString());
		List<String> wrong = new ArrayList<>();
		Map<String, String> phraseByKey = new HashMap<>();
		Set<String> phrases = new HashSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			SpeechRule.Incident memory = rule.remember;
			if (memory == null)
			{
				continue;
			}
			if (memory.key == null || !memory.key.matches("quest-[a-z0-9]+(-[a-z0-9]+)*"))
			{
				wrong.add(rule.id + ": key '" + memory.key + "' is not quest-<words>");
			}
			if (memory.as == null || memory.as.isEmpty() || !memory.as.equals(memory.plain))
			{
				wrong.add(rule.id + ": with no placeholder, plain files the same words as as");
				continue;
			}
			if (!memory.as.matches("[a-z][ -~]*[A-Za-z]") || memory.as.contains("{")
				|| memory.as.length() > 45)
			{
				wrong.add(rule.id + ": '" + memory.as + "' doesn't read mid-sentence"
					+ " (lower-case start, no closing stop, no placeholder, 45 at most)");
			}
			// A callback files its base's memory; anything else is a new one.
			String other = phraseByKey.putIfAbsent(memory.key, memory.as);
			if (other != null && !other.equals(memory.as))
			{
				wrong.add(rule.id + ": key " + memory.key + " already files '" + other + "'");
			}
			if (other == null && !phrases.add(memory.as))
			{
				wrong.add(rule.id + ": '" + memory.as + "' is filed under two keys");
			}
		}
		assertTrue("no memories found - the scan broke", !phraseByKey.isEmpty());
		assertTrue(String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void everyCallbackNamesARealQuestAndOutranksItsBase() throws IOException
	{
		Harness h = load(draft.toString());
		Map<String, SpeechRule> byId = new HashMap<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			byId.put(rule.id, rule);
		}
		List<String> wrong = new ArrayList<>();
		int callbacks = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			List<String> quests = new ArrayList<>();
			walk(rule.when, condition ->
			{
				if ("questdone".equalsIgnoreCase(condition.type))
				{
					quests.add(condition.quest);
				}
			});
			for (String quest : quests)
			{
				if (quest == null || !Arrays.stream(Quest.values()).anyMatch(q -> q.name().equals(quest)))
				{
					wrong.add(rule.id + ": '" + quest + "' is no Quest constant");
				}
			}

			// A callback takes questDone straight in its "all"; its base, the
			// same id before "-after-", takes the same quest inside a "none".
			String direct = null;
			for (Condition c : rule.when.conditions)
			{
				if ("questdone".equalsIgnoreCase(c.type))
				{
					direct = c.quest;
				}
			}
			if (direct == null)
			{
				continue;
			}
			callbacks++;
			int cut = rule.id.indexOf("-after-");
			SpeechRule base = cut < 0 ? null : byId.get(rule.id.substring(0, cut));
			if (base == null)
			{
				wrong.add(rule.id + ": no base moment");
				continue;
			}
			if (rule.priority != 64 || base.priority != 63)
			{
				wrong.add(rule.id + ": a callback is priority 64 over a base at 63");
			}
			boolean standsAside = false;
			for (Condition c : base.when.conditions)
			{
				standsAside |= "none".equalsIgnoreCase(c.type) && c.conditions.size() == 1
					&& "questdone".equalsIgnoreCase(c.conditions.get(0).type)
					&& direct.equals(c.conditions.get(0).quest);
			}
			if (!standsAside)
			{
				wrong.add(base.id + ": doesn't stand aside when " + direct + " is done");
			}
		}
		assertTrue("no callbacks found - the scan broke", callbacks > 0);
		assertTrue(String.join("\n", wrong), wrong.isEmpty());
	}

	/**
	 * X Marks the Spot's orb, dug up behind the castle kitchen: the cook's
	 * back door, and a callback to his cake if Cook's Assistant is done.
	 * Done, only the callback is said; not done, only the base line.
	 */
	@Test
	public void aCallbackAndItsBaseNeverBothSpeak() throws IOException
	{
		String base = "qm-x-marks-the-spot-the-orb";
		String callback = base + "-after-cooks-assistant";
		for (boolean cakeBaked : new boolean[]{false, true})
		{
			Harness h = load(draft.toString());
			assertNotNull(h.rule(callback));
			h.game.quest(Quest.COOKS_ASSISTANT,
				cakeBaked ? QuestState.FINISHED : QuestState.IN_PROGRESS);
			// The hole before, said and out of the way first.
			h.game.varbit(8063, 3);
			h.gameTicks(6);
			h.clear();
			h.game.varbit(8063, 4);
			h.gameTicks(5);
			// The step falling and rising again must not let the other in.
			h.game.varbit(8063, 3);
			h.gameTicks(2);
			h.game.varbit(8063, 4);
			h.gameTicks(5);

			String said = cakeBaked ? callback : base;
			String quiet = cakeBaked ? base : callback;
			assertEquals(said + " is said", 1, h.firedBy(said).size());
			assertEquals(quiet + " is not", 0, h.firedBy(quiet).size());
			assertEquals("and nothing else", 1, h.spoken.size());
			assertEquals("with its gesture", (int) h.rule(said).animation,
				h.spoken.get(0).animationId);
		}
	}

	@Test
	public void theThingEscapingFiresEndToEnd() throws IOException
	{
		Harness h = load(draft.toString());
		h.game.at(3201, 3268, 0);
		h.gameTicks(2);
		// Started, nothing handed in yet: no band of its own to fire.
		h.game.varp(179, 1);
		h.gameTicks(2);
		h.dispatch(TriggerEvent.chat("The... whatever it is... manages to get away from you!",
			ChatMessageType.GAMEMESSAGE.getType(), ""));
		h.gameTicks(4);
		assertEquals(1, h.firedBy("qm-sheep-shearer-thing-escaped").size());
		assertEquals("and nothing else said with it", 1, h.spoken.size());
	}
}
