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
		file = dataDir.resolve(FILE_NAME);

		if (!Files.exists(file))
		{
			writeDefaults();
		}

		reload();
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
			lastModified = Files.getLastModifiedTime(file).toMillis();
			lastSize = Files.size(file);

			if (parsed == null)
			{
				problems.add("File is empty");
				apply(Collections.emptyList(), problems, "empty file");
				return;
			}

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

	private void apply(List<SpeechRule> newRules, List<String> problems, String summary)
	{
		rules = newRules;
		errors = problems;

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
