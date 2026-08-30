package com.follower.speech;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads phrases.json and reloads it when the file changes on disk, so you can edit
 * rules with the client running. Change detection is a lastModified/size poll
 * driven from the game tick — a WatchService buys nothing here and brings its own
 * platform quirks.
 */
@Slf4j
@Singleton
public class RuleLoader
{
	public static final String FILE_NAME = "phrases.json";
	private static final String BUNDLED_DEFAULT = "/com/follower/default-phrases.json";

	private final Gson gson;

	@Getter
	private List<SpeechRule> rules = Collections.emptyList();
	private java.util.Set<String> bossNames = Collections.emptySet();

	@Getter
	private String status = "not loaded";

	@Getter
	private List<String> errors = Collections.emptyList();

	/**
	 * Whether any loaded rule uses the event-driven varbitChanged condition.
	 * Varbit changes arrive in floods (thousands at login), and dispatching
	 * each through every rule is pure waste when nothing listens - the
	 * state-based varbitEquals rules evaluate on the tick heartbeat instead.
	 */
	@Getter
	private boolean varbitEventRules;

	private Path file;
	private long lastModified = -1L;
	private long lastSize = -1L;

	@Inject
	public RuleLoader(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Points the loader at the data directory, writing the bundled default rules if
	 * no file exists yet, then loads.
	 */
	public void initialise(Path dataDir)
	{
		initialise(dataDir, false);
	}

	/**
	 * @param mergeDefaults whether to fold newly bundled rules into an
	 * existing file first. The plugin passes true; the test harnesses supply
	 * exact files and pass false through the plain overload.
	 */
	public void initialise(Path dataDir, boolean mergeDefaults)
	{
		file = dataDir.resolve(FILE_NAME);

		if (!Files.exists(file))
		{
			writeDefaults();
		}
		else if (mergeDefaults)
		{
			mergeNewDefaults();
		}

		reload();
	}

	/**
	 * Appends bundled rules the user's file has never seen.
	 *
	 * <p>The starter file is only written when no file exists, so before this
	 * every rule an update added silently never reached an existing install:
	 * the Hub updated the code, the file stayed at whatever release first
	 * wrote it, and the new features looked broken rather than missing. Play
	 * report: emote mirroring "not working" for a tester whose file predated
	 * the mimic rules entirely.
	 *
	 * <p>Only ABSENT ids are appended, so every edit to an existing rule -
	 * rewording, silencing, extra lines - survives untouched. Rules the
	 * editor deleted are recorded in the file's "removed" list and stay
	 * deleted. The file as it stood is kept beside the merged one as
	 * phrases.json.bak, and new nickname hooks arrive the same way (existing
	 * keys are the user's to keep).
	 */
	private void mergeNewDefaults()
	{
		try (InputStream in = RuleLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				return;
			}
			Gson pretty = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
			com.google.gson.JsonObject bundled = pretty.fromJson(
				new java.io.InputStreamReader(in, StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject ours = pretty.fromJson(
				new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			if (bundled == null || ours == null
				|| !bundled.has("rules") || !ours.has("rules"))
			{
				return;
			}

			Set<String> known = new HashSet<>();
			for (com.google.gson.JsonElement element : ours.getAsJsonArray("rules"))
			{
				String id = idOf(element);
				if (id != null)
				{
					known.add(id);
				}
			}
			if (ours.has("removed") && ours.get("removed").isJsonArray())
			{
				for (com.google.gson.JsonElement element : ours.getAsJsonArray("removed"))
				{
					if (element.isJsonPrimitive())
					{
						known.add(element.getAsString());
					}
				}
			}

			com.google.gson.JsonArray rules = ours.getAsJsonArray("rules");
			int added = 0;
			for (com.google.gson.JsonElement element : bundled.getAsJsonArray("rules"))
			{
				String id = idOf(element);
				if (id != null && !known.contains(id))
				{
					rules.add(element);
					added++;
				}
			}

			int nicknamesAdded = 0;
			if (bundled.has("nicknames") && bundled.get("nicknames").isJsonObject())
			{
				com.google.gson.JsonObject theirs = bundled.getAsJsonObject("nicknames");
				com.google.gson.JsonObject mine =
					ours.has("nicknames") && ours.get("nicknames").isJsonObject()
						? ours.getAsJsonObject("nicknames") : new com.google.gson.JsonObject();
				for (Map.Entry<String, com.google.gson.JsonElement> entry : theirs.entrySet())
				{
					if (!mine.has(entry.getKey()))
					{
						mine.add(entry.getKey(), entry.getValue());
						nicknamesAdded++;
					}
				}
				ours.add("nicknames", mine);
			}

			if (added == 0 && nicknamesAdded == 0)
			{
				return;
			}

			Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.write(file,
				(pretty.toJson(ours) + "\n").getBytes(StandardCharsets.UTF_8));
			log.info("Merged {} new default rule(s) and {} nickname(s) into {}",
				added, nicknamesAdded, file);
		}
		catch (Exception e)
		{
			// A file that cannot be merged is one reload() will complain about
			// in its own words; do not block startup over it.
			log.warn("Could not merge new defaults into {}", file, e);
		}
	}

	private static String idOf(com.google.gson.JsonElement element)
	{
		if (!element.isJsonObject())
		{
			return null;
		}
		com.google.gson.JsonElement id = element.getAsJsonObject().get("id");
		return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
	}

	public Path getFile()
	{
		return file;
	}

	/** Reloads if the file changed since last check. Returns true if rules were replaced. */
	public boolean reloadIfChanged()
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}

		try
		{
			long modified = Files.getLastModifiedTime(file).toMillis();
			long size = Files.size(file);
			if (modified == lastModified && size == lastSize)
			{
				return false;
			}
		}
		catch (IOException e)
		{
			return false;
		}

		reload();
		return true;
	}

	public void reload()
	{
		if (file == null)
		{
			return;
		}

		List<String> problems = new ArrayList<>();

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			RuleFile parsed = gson.fromJson(reader, RuleFile.class);

			if (parsed == null)
			{
				// An empty read is almost never an empty file on purpose. It is
				// what a poll sees in the instant between an editor truncating
				// the file and writing it back, and it is what a failed save
				// leaves behind. Treating it as "no rules" replaced the whole
				// personality with silence, which is exactly what the syntax
				// error path below is careful NOT to do. Keep what we had, say
				// so, and do not record this read as current - the next poll
				// then picks up the finished file.
				problems.add("File is empty; keeping " + rules.size() + " previous rules");
				errors = problems;
				status = "empty file, keeping " + rules.size() + " previous rules";
				log.warn("phrases.json read as empty; keeping the previous rules");
				return;
			}

			lastModified = Files.getLastModifiedTime(file).toMillis();
			lastSize = Files.size(file);

			if (parsed.version != RuleFile.SUPPORTED_VERSION)
			{
				problems.add("version is " + parsed.version + ", plugin expects " + RuleFile.SUPPORTED_VERSION);
			}

			List<SpeechRule> valid = new ArrayList<>();
			Set<String> seenIds = new HashSet<>();
			int index = 0;

			for (SpeechRule rule : parsed.rules == null ? Collections.<SpeechRule>emptyList() : parsed.rules)
			{
				index++;
				if (rule == null)
				{
					continue;
				}

				if (rule.id == null || rule.id.trim().isEmpty())
				{
					rule.id = "rule-" + index;
				}
				if (!seenIds.add(rule.id))
				{
					problems.add("Duplicate rule id '" + rule.id + "' — later one wins for cooldowns");
				}
				if (!rule.isValid())
				{
					problems.add("Rule '" + rule.id + "' needs a 'when' block and at least one of"
						+ " a non-empty 'say' list, an 'animation', or 'mirrorAnimation'");
					continue;
				}
				if (rule.group == null || rule.group.trim().isEmpty())
				{
					rule.group = "misc";
				}

				rule.reset();
				valid.add(rule);
			}

			valid.sort((a, b) -> Integer.compare(b.priority, a.priority));
			nicknames = parsed.nicknames == null
				? Collections.<String, String>emptyMap() : parsed.nicknames;
			apply(valid, problems, valid.size() + " rules");
		}
		catch (IOException e)
		{
			problems.add("Could not read " + file + ": " + e.getMessage());
			apply(Collections.emptyList(), problems, "read error");
		}
		catch (JsonParseException e)
		{
			// Keep the previous rules on a syntax error; losing your follower's whole
			// personality over a trailing comma is worse than running stale rules.
			problems.add("JSON error: " + e.getMessage());
			errors = problems;
			status = "syntax error, keeping " + rules.size() + " previous rules";
			log.warn("phrases.json failed to parse", e);
		}
	}

	/**
	 * Names the follower may earn for you, keyed by the tally that earns each.
	 * Reloaded with the rules, so editing the file re-reads them too.
	 */
	@lombok.Getter
	private Map<String, String> nicknames = Collections.emptyMap();

	/**
	 * Which condition type answers which event. Only the types that are gated
	 * on a specific event appear: a state condition (healthBelow, inRegion) can
	 * be true during ANY dispatch and so is never listed.
	 */
	private static final Map<TriggerEvent.Type, String[]> EVENT_CONDITIONS;

	static
	{
		EVENT_CONDITIONS = new EnumMap<>(TriggerEvent.Type.class);
		EVENT_CONDITIONS.put(TriggerEvent.Type.NPC_SPAWN, new String[]{"npcSpawn"});
		EVENT_CONDITIONS.put(TriggerEvent.Type.NPC_DESPAWN, new String[]{"npcDespawn"});
		EVENT_CONDITIONS.put(TriggerEvent.Type.VARBIT, new String[]{"varbitChanged"});
		// Busy scenes fire NPC overheads constantly; same economics as spawns.
		EVENT_CONDITIONS.put(TriggerEvent.Type.NPC_SHOUT, new String[]{"npcShout"});
	}

	private final Set<TriggerEvent.Type> listened = EnumSet.noneOf(TriggerEvent.Type.class);

	/**
	 * Whether any loaded rule can answer this kind of event.
	 *
	 * <p>Only meaningful for the FLOOD events - NPCs entering and leaving the
	 * scene, and varbits at login - which arrive in the hundreds within a single
	 * tick and each cost a pass over every rule. Everything else is rare enough
	 * that asking is not worth it.
	 *
	 * <p>Skipping a pass costs nothing a state rule needed: state only changes
	 * when the snapshot refreshes, once a tick, and the tick heartbeat always
	 * gets its own dispatch. It does mean a {@code chance} roll no longer
	 * happens per despawn, which is a fix rather than a loss - how talkative the
	 * follower is should not depend on how many NPCs happen to be wandering out
	 * of view.
	 */
	public boolean listensFor(TriggerEvent.Type type)
	{
		return !EVENT_CONDITIONS.containsKey(type) || listened.contains(type);
	}

	/**
	 * The NPC names the boss rules name, which is what the rest of the plugin
	 * means by "boss" - see {@link com.follower.speech.TriggerContext#isBoss}.
	 * Collected here because this is where the rules are already walked, and
	 * because the rule file is the only place the list should live.
	 */
	public java.util.Set<String> getBossNames()
	{
		return bossNames;
	}

	private void collectBossNames(Condition condition, java.util.Set<String> into)
	{
		if (condition == null)
		{
			return;
		}
		if (condition.type != null
			&& condition.type.toLowerCase(java.util.Locale.ROOT).startsWith("npc")
			&& condition.names != null)
		{
			into.addAll(condition.names);
		}
		if (condition.conditions != null)
		{
			for (Condition sub : condition.conditions)
			{
				collectBossNames(sub, into);
			}
		}
	}

	private void apply(List<SpeechRule> newRules, List<String> problems, String summary)
	{
		rules = newRules;
		errors = problems;

		java.util.Set<String> bosses = new java.util.HashSet<>();
		for (SpeechRule rule : newRules)
		{
			if ("boss".equalsIgnoreCase(rule.group))
			{
				collectBossNames(rule.when, bosses);
			}
		}
		bossNames = bosses;

		listened.clear();
		for (Map.Entry<TriggerEvent.Type, String[]> entry : EVENT_CONDITIONS.entrySet())
		{
			for (SpeechRule rule : newRules)
			{
				if (rule.when == null)
				{
					continue;
				}
				for (String conditionType : entry.getValue())
				{
					if (rule.when.usesType(conditionType))
					{
						listened.add(entry.getKey());
						break;
					}
				}
			}
		}
		varbitEventRules = listened.contains(TriggerEvent.Type.VARBIT);

		status = summary + (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)");
		log.info("Loaded rules: {}", status);
		log.debug("Flood events with listeners: {}", listened);
	}

	private void writeDefaults()
	{
		try (InputStream in = RuleLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				log.warn("Bundled default rules missing from jar");
				return;
			}
			Files.createDirectories(file.getParent());
			Files.copy(in, file);
			log.info("Wrote starter rules to {}", file);
		}
		catch (IOException e)
		{
			log.warn("Could not write default rules to {}", file, e);
		}
	}
}
