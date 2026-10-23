package com.follower.speech.before;

/*
 * The loader as it stood before MergingLoader (commit 310760e), kept verbatim as
 * LoaderDiffTest's reference: the same upgrade scenarios must leave the same
 * files, status, errors, rules, trees and log lines with either. Only the
 * package, the imports of the speech classes it shared, Lombok's @Slf4j and
 * @Getter written out (the tests are built without Lombok) and, in
 * DialogLoader, the dialogs-editor marker round getFile differ. Not used by
 * the plugin.
 */

import com.follower.speech.Condition;
import com.follower.speech.RuleFile;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
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

/**
 * Loads phrases.json and reloads it when the file changes on disk, so you can edit
 * rules with the client running. Change detection is a lastModified/size poll
 * driven from the game tick — a WatchService buys nothing here and brings its own
 * platform quirks.
 */
@Singleton
public class RuleLoader
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RuleLoader.class);

	/** Fields the three-way must not touch: lines have their own pass. */
	private static final Set<String> SETTLED_BY_HAND =
		new HashSet<>(java.util.Arrays.asList("say", "id"));

	/** The bundled defaults as of the last merge, for the three-way. */
	public static final String BASE_FILE = "defaults-seen.json";

	/**
	 * Beside the base: the bundled rules' checksum and the player's file as
	 * the last merge left them. When neither has changed the merge would do
	 * nothing, and it re-read three large files and rewrote the base, 70-100
	 * ms of every start on the Swing thread (perf audit, Oct 2026).
	 */
	static final String MERGE_STAMP = "defaults-seen.stamp";

	public static final String FILE_NAME = "phrases.json";
	private static final String BUNDLED_DEFAULT = "/com/follower/default-phrases.json";

	/**
	 * The rules 1.4.0 shipped, a base for some files that have none; see
	 * {@link #unrecordedBase}.
	 */
	static final String UNRECORDED_BASE = "/com/follower/seen/1.4.0-phrases.json";

	private final Gson gson;

	private List<SpeechRule> rules = Collections.emptyList();

	public List<SpeechRule> getRules()
	{
		return this.rules;
	}
	private java.util.Set<String> bossNames = Collections.emptySet();
	private Path baseFile;

	private String status = "not loaded";

	public String getStatus()
	{
		return this.status;
	}

	private List<String> errors = Collections.emptyList();

	public List<String> getErrors()
	{
		return this.errors;
	}

	/**
	 * Whether any loaded rule uses the event-driven varbitChanged condition.
	 * Varbit changes arrive in floods (thousands at login), and dispatching
	 * each through every rule is pure waste when nothing listens - the
	 * state-based varbitEquals rules evaluate on the tick heartbeat instead.
	 */
	private boolean varbitEventRules;

	public boolean isVarbitEventRules()
	{
		return this.varbitEventRules;
	}

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
		baseFile = dataDir.resolve(BASE_FILE);

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
	 * Folds bundled changes into the player's file, three ways.
	 *
	 * <p>The old merge only ever APPENDED rules the file had never seen, which
	 * fixed the tester whose file predated the mimic rules and left a bigger
	 * hole behind it: a change to an existing rule reached nobody. Retuning the
	 * examine echo from 30% every three minutes to 70% every thirty, and
	 * writing twenty lines for it, changed nothing at all for anybody who
	 * already had the plugin - the work shipped and stayed invisible.
	 *
	 * <p>It could not be fixed by simply copying more across, because two
	 * situations look identical from the user's file alone: a line the update
	 * ADDED, and a line the player DELETED. Both are lines the bundled file has
	 * and theirs does not. Copying blindly resurrects everything anybody has
	 * ever removed.
	 *
	 * <p>So the defaults are kept as they stood at the last merge, in
	 * {@value #BASE_FILE}, and the question becomes answerable: bundled against
	 * BASE says what the update did, and theirs against base says what the
	 * player did. Upstream's changes are taken where the player has not touched
	 * that field, and the player wins everywhere they have. A line missing from
	 * theirs but present in base was deleted on purpose and stays gone; one
	 * that is in bundled but not in base is genuinely new and arrives.
	 *
	 * <p>Rules the editor deleted are recorded in the file's "removed" list and
	 * stay deleted. The file as it stood is kept beside the merged one as
	 * phrases.json.bak, and new nickname hooks arrive as before, as do new
	 * place names - existing keys are the user's to keep.
	 */
	private void mergeNewDefaults()
	{
		try (InputStream in = RuleLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				return;
			}
			byte[] shipped = in.readAllBytes();
			Path stampFile = file.resolveSibling(MERGE_STAMP);
			if (Files.exists(stampFile) && mergeStamp(shipped).equals(
				new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8)))
			{
				return;
			}
			Gson pretty = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
			com.google.gson.JsonObject bundled = pretty.fromJson(
				new String(shipped, StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject ours = pretty.fromJson(
				new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			if (bundled == null || ours == null
				|| !bundled.has("rules") || !ours.has("rules"))
			{
				return;
			}

			// The record of what shipped last time, or for a file that never
			// merged, what can be told of where it came from. A rule missing
			// from it is judged by mergeRule without one.
			com.google.gson.JsonObject base = readBase(pretty);
			if (base == null)
			{
				base = unrecordedBase(pretty, ours);
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

			Map<String, com.google.gson.JsonObject> mine = byId(ours);
			Map<String, com.google.gson.JsonObject> was = byId(base);

			com.google.gson.JsonArray rules = ours.getAsJsonArray("rules");
			int added = 0;
			int updated = 0;
			for (com.google.gson.JsonElement element : bundled.getAsJsonArray("rules"))
			{
				String id = idOf(element);
				if (id == null || !element.isJsonObject())
				{
					continue;
				}
				if (!known.contains(id))
				{
					rules.add(element);
					added++;
					continue;
				}
				com.google.gson.JsonObject held = mine.get(id);
				// Absent but known means the editor deleted it; it stays deleted.
				if (held != null
					&& mergeRule(held, was.get(id), element.getAsJsonObject()) > 0)
				{
					updated++;
				}
			}

			// A rule the update dropped goes too, as a dropped line does,
			// unless the player has changed it: then it is theirs now.
			Set<String> stillShipped = byId(bundled).keySet();
			int dropped = 0;
			for (java.util.Iterator<com.google.gson.JsonElement> it = rules.iterator(); it.hasNext(); )
			{
				com.google.gson.JsonElement element = it.next();
				String id = idOf(element);
				if (id != null && !stillShipped.contains(id) && element.equals(was.get(id)))
				{
					it.remove();
					dropped++;
				}
			}

			int nicknamesAdded = 0;
			if (bundled.has("nicknames") && bundled.get("nicknames").isJsonObject())
			{
				com.google.gson.JsonObject theirs = bundled.getAsJsonObject("nicknames");
				com.google.gson.JsonObject held =
					ours.has("nicknames") && ours.get("nicknames").isJsonObject()
						? ours.getAsJsonObject("nicknames") : new com.google.gson.JsonObject();
				for (Map.Entry<String, com.google.gson.JsonElement> entry : theirs.entrySet())
				{
					if (!held.has(entry.getKey()) || widens(held.get(entry.getKey()), entry.getValue()))
					{
						held.add(entry.getKey(), entry.getValue());
						nicknamesAdded++;
					}
				}
				ours.add("nicknames", held);
			}

			int placesAdded = 0;
			if (bundled.has("places") && bundled.get("places").isJsonObject())
			{
				com.google.gson.JsonObject held =
					ours.has("places") && ours.get("places").isJsonObject()
						? ours.getAsJsonObject("places") : new com.google.gson.JsonObject();
				for (Map.Entry<String, com.google.gson.JsonElement> entry
					: bundled.getAsJsonObject("places").entrySet())
				{
					if (!held.has(entry.getKey()))
					{
						held.add(entry.getKey(), entry.getValue());
						placesAdded++;
					}
				}
				ours.add("places", held);
			}

			if (added > 0 || updated > 0 || dropped > 0 || nicknamesAdded > 0 || placesAdded > 0)
			{
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Files.write(file,
					(pretty.toJson(ours) + "\n").getBytes(StandardCharsets.UTF_8));
				// public:cut logs
				log.info("Merged {} new rule(s), {} changed rule(s), {} dropped rule(s), {} nickname(s) and {} place name(s) into {}",
					added, updated, dropped, nicknamesAdded, placesAdded, file);
				// public:end logs
			}

			// Written whether or not anything moved, and last, so a merge that
			// half-happened is retried rather than recorded as done. From here
			// on there is a base and the guessing above never runs again.
			if (writeBase(bundled, pretty))
			{
				Files.write(stampFile, mergeStamp(shipped).getBytes(StandardCharsets.UTF_8));
			}
		}
		catch (Exception e)
		{
			// A file that cannot be merged is one reload() will complain about
			// in its own words; do not block startup over it.
			log.warn("Could not merge new defaults into {}", file, e);
		}
	}

	/**
	 * Folds one bundled rule into the player's copy.
	 *
	 * @param base the rule as it was bundled at the last merge, or null when
	 * there is no record - see the bootstrap below
	 * @return how many changes were made, so the caller can count rules
	 */
	private static int mergeRule(com.google.gson.JsonObject held,
		com.google.gson.JsonObject base, com.google.gson.JsonObject bundled)
	{
		if (base == null)
		{
			return bootstrap(held, bundled);
		}

		return ThreeWay.fields(held, base, bundled, SETTLED_BY_HAND)
			+ ThreeWay.strings(held, "say", base, bundled);
	}

	/**
	 * A rule with no record of what shipped before: one the base does not
	 * hold, which for a file with no record is most of what
	 * {@link #unrecordedBase} leaves out.
	 *
	 * <p>Nothing can be deduced about deletions, so the only honest signal left
	 * is whether the player has written into the rule at all. If every line in
	 * their copy is one that ships, they have not - and taking the bundled rule
	 * is right, which is how a year of tuning finally reaches them. If they
	 * have added anything the rule is theirs and is left exactly alone this
	 * once; the base written afterwards makes every later merge exact.
	 *
	 * <p>{@code enabled} is the player's either way. Switching a rule off is a
	 * decision made in the editor and adopting over it would turn it back on.
	 */
	private static int bootstrap(com.google.gson.JsonObject held,
		com.google.gson.JsonObject bundled)
	{
		if (!ThreeWay.untouched(ThreeWay.strings(held, "say"),
			ThreeWay.strings(bundled, "say")))
		{
			return 0;
		}
		com.google.gson.JsonElement enabled = held.get("enabled");
		int before = ThreeWay.strings(held, "say").size();
		for (String key : new java.util.ArrayList<>(held.keySet()))
		{
			held.remove(key);
		}
		for (Map.Entry<String, com.google.gson.JsonElement> entry : bundled.entrySet())
		{
			held.add(entry.getKey(), entry.getValue());
		}
		if (enabled != null)
		{
			held.add("enabled", enabled);
		}
		return ThreeWay.strings(held, "say").size() == before && before > 0 ? 0 : 1;
	}

	private static Map<String, com.google.gson.JsonObject> byId(com.google.gson.JsonObject file)
	{
		return ThreeWay.byId(ThreeWay.array(file, "rules"));
	}

	/**
	 * A base for a file with no record, as far as its origin can be told.
	 *
	 * <p>Up to 1.4.0 a fresh install wrote its starter file without recording
	 * it, and the record only arrived with the next start's merge, which
	 * guessed; so a file started just once reaches this version with none, and
	 * guessing puts back the lines its player deleted. A file that holds every
	 * rule 1.4.0 shipped was written by 1.4.0 or 1.3.0, which shipped the same
	 * rules, and each of its rules that still matches 1.4.0 outside its lines
	 * and its switch is judged against 1.4.0: a deleted line stays deleted. The
	 * rest are left out, to mergeRule's guess, which brings an untouched rule up
	 * to date: 1.3.0's two rules that 1.4.0 retuned, and every rule of a file
	 * older than 1.3.0, which lacks rules 1.4.0 added. Null when nothing fits.
	 */
	private com.google.gson.JsonObject unrecordedBase(Gson pretty, com.google.gson.JsonObject ours)
	{
		com.google.gson.JsonObject shipped;
		try (InputStream in = RuleLoader.class.getResourceAsStream(UNRECORDED_BASE))
		{
			if (in == null)
			{
				return null;
			}
			shipped = pretty.fromJson(new java.io.InputStreamReader(in, StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
		}
		catch (Exception e)
		{
			log.warn("Could not read {}, merging without a base", UNRECORDED_BASE, e);
			return null;
		}

		Map<String, com.google.gson.JsonObject> mine = byId(ours);
		Set<String> known = new HashSet<>(mine.keySet());
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
		com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
		for (com.google.gson.JsonElement element : shipped.getAsJsonArray("rules"))
		{
			String id = idOf(element);
			if (id == null || !known.contains(id))
			{
				// Older than 1.3.0: the guess serves it as it always did.
				return null;
			}
			com.google.gson.JsonObject held = mine.get(id);
			if (held != null && sameOutsideLines(held, element.getAsJsonObject()))
			{
				kept.add(element);
			}
		}
		com.google.gson.JsonObject base = new com.google.gson.JsonObject();
		base.add("rules", kept);
		return base;
	}

	/** Whether two copies of a rule differ only in their lines and whether it is on. */
	private static boolean sameOutsideLines(com.google.gson.JsonObject a, com.google.gson.JsonObject b)
	{
		com.google.gson.JsonObject x = a.deepCopy();
		com.google.gson.JsonObject y = b.deepCopy();
		for (String key : new String[]{"say", "enabled"})
		{
			x.remove(key);
			y.remove(key);
		}
		return x.equals(y);
	}

	/** What a merge depends on: the bundled rules, the player's file, and that there is a base. */
	private String mergeStamp(byte[] shipped) throws IOException
	{
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(shipped);
		return crc.getValue() + " " + Files.size(file) + " " + Files.getLastModifiedTime(file)
			+ " " + Files.exists(baseFile);
	}

	/** The defaults as they stood at the last merge, or null if never written. */
	private com.google.gson.JsonObject readBase(Gson pretty)
	{
		if (baseFile == null || !Files.exists(baseFile))
		{
			return null;
		}
		try
		{
			return pretty.fromJson(
				new String(Files.readAllBytes(baseFile), StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
		}
		catch (Exception e)
		{
			// Unreadable is the same as absent: fall back to the bootstrap
			// rather than refusing to merge at all.
			log.warn("Could not read {}, merging without a base", baseFile, e);
			return null;
		}
	}

	private boolean writeBase(com.google.gson.JsonObject bundled, Gson pretty)
	{
		if (baseFile == null)
		{
			return false;
		}
		try
		{
			Files.write(baseFile,
				(pretty.toJson(bundled) + "\n").getBytes(StandardCharsets.UTF_8));
			return true;
		}
		catch (Exception e)
		{
			// Only costs a repeat of the bootstrap next start, which is
			// idempotent, so it is not worth failing the merge over.
			log.warn("Could not record the merged defaults in {}", baseFile, e);
			return false;
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
					problems.add("Duplicate rule id '" + rule.id + "' - the later one wins for cooldowns");
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
			filePlaces = parsed.places == null
				? Collections.<Integer, String>emptyMap() : parsed.places;
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
	 * Whether the bundled names for a tally only add to the player's: theirs
	 * is still one of ours, untouched, and ours now offers a choice. "the
	 * swot" becomes "the swot|the grinder" (18 Oct); a name the player wrote
	 * themselves is kept.
	 *
	 * <p>A build from before the choice reads the widened value whole, and
	 * says it so: "you're down as the swot|the grinder" (behaviour QA5, 20
	 * Oct, the Hub's build on the same phrases.json). Editing the value back
	 * to one name is the cure for a player who goes back to such a build.
	 */
	static boolean widens(com.google.gson.JsonElement held, com.google.gson.JsonElement bundled)
	{
		if (held == null || !held.isJsonPrimitive() || bundled == null || !bundled.isJsonPrimitive())
		{
			return false;
		}
		String theirs = held.getAsString().trim();
		String ours = bundled.getAsString();
		if (theirs.equals(ours.trim()))
		{
			return false;
		}
		for (String name : ours.split("\\|"))
		{
			if (name.trim().equals(theirs))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Names the follower may earn for you, keyed by the tally that earns each.
	 * Reloaded with the rules, so editing the file re-reads them too.
	 */
	private Map<String, String> nicknames = Collections.emptyMap();

	public Map<String, String> getNicknames()
	{
		return this.nicknames;
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

	/** The rules with an npcSpawn condition somewhere in them. */
	private List<SpeechRule> spawnRules = Collections.emptyList();

	/**
	 * Whether any rule's npcSpawn names this NPC. A wave of spawns is a pass
	 * over every rule for each NPC, 5-12 ms in one frame for a busy area
	 * (perf audit, Oct 2026); a spawn no rule names can make true nothing the
	 * next tick would not, for the reason {@link #listensFor} gives.
	 */
	public boolean answersSpawn(TriggerEvent spawn)
	{
		for (SpeechRule rule : spawnRules)
		{
			if (rule.when.answersSpawn(spawn))
			{
				return true;
			}
		}
		return false;
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

	/**
	 * Region id to place name, read off the Locations rules: the first
	 * sentence of each area rule's note, for every region it names. The
	 * Memory window shows these beside the bare ids so "Region 12850" reads
	 * as Lumbridge. Only rules with a note contribute; an id no rule names is
	 * simply absent.
	 */
	public Map<Integer, String> getRegionNames()
	{
		return regionNames;
	}

	private Map<Integer, String> regionNames = Collections.emptyMap();

	/** The file's own place names, for regions no area rule names: its "places". */
	private Map<Integer, String> filePlaces = Collections.emptyMap();

	private static void collectRegionNames(SpeechRule rule, Map<Integer, String> into)
	{
		if (rule.when == null || rule.when.regions == null
			|| rule.note == null || rule.note.trim().isEmpty())
		{
			return;
		}
		String type = rule.when.type == null ? "" : rule.when.type.toLowerCase(java.util.Locale.ROOT);
		if (!type.equals("inregion") && !type.equals("regionenter"))
		{
			return;
		}
		String name = rule.note.trim();
		int stop = name.indexOf(". ");
		if (stop > 0)
		{
			name = name.substring(0, stop);
		}
		else if (name.endsWith("."))
		{
			name = name.substring(0, name.length() - 1);
		}
		for (Integer region : rule.when.regions)
		{
			if (region != null)
			{
				into.putIfAbsent(region, name);
			}
		}
	}

	/** Each region an area rule's "all" stands in, by the rule's title up to its comma, where nothing names it yet. */
	private static void collectTitledRegionNames(SpeechRule rule, Map<Integer, String> into)
	{
		if (rule.when == null || rule.when.conditions == null || rule.title == null
			|| !"all".equalsIgnoreCase(rule.when.type))
		{
			return;
		}
		int comma = rule.title.indexOf(',');
		String name = (comma > 0 ? rule.title.substring(0, comma) : rule.title).trim();
		for (Condition part : rule.when.conditions)
		{
			if (part != null && part.regions != null && !name.isEmpty()
				&& ("inregion".equalsIgnoreCase(part.type) || "regionenter".equalsIgnoreCase(part.type)))
			{
				for (Integer region : part.regions)
				{
					if (region != null)
					{
						into.putIfAbsent(region, name);
					}
				}
			}
		}
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
		Map<Integer, String> places = new java.util.HashMap<>();
		for (SpeechRule rule : newRules)
		{
			if ("boss".equalsIgnoreCase(rule.group))
			{
				collectBossNames(rule.when, bosses);
			}
			else if ("area".equalsIgnoreCase(rule.group))
			{
				collectRegionNames(rule, places);
			}
		}
		// Then the places only a rule with more to it names, by its title:
		// "The Abyss, after Enter the Abyss". Behaviour QA5, 19 Oct: a death
		// in the Abyss had no place to be filed in.
		for (SpeechRule rule : newRules)
		{
			if ("area".equalsIgnoreCase(rule.group))
			{
				collectTitledRegionNames(rule, places);
			}
		}
		// And the places no rule stands in, named in the file itself: the
		// essence mine, the Wizards' Tower basement (speech QA5 #23).
		for (Map.Entry<Integer, String> place : filePlaces.entrySet())
		{
			if (place.getKey() != null && place.getValue() != null)
			{
				places.putIfAbsent(place.getKey(), place.getValue());
			}
		}
		bossNames = bosses;
		regionNames = places;

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
		List<SpeechRule> spawning = new ArrayList<>();
		for (SpeechRule rule : newRules)
		{
			if (rule.uses("npcSpawn"))
			{
				spawning.add(rule);
			}
		}
		spawnRules = spawning;

		status = summary + (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)");
		// public:cut logs
		log.info("Loaded rules: {}", status);
		log.debug("Flood events with listeners: {}", listened);
		// public:end logs
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
			log.info("Wrote starter rules to {}", file); // public:cut logs
		}
		catch (IOException e)
		{
			log.warn("Could not write default rules to {}", file, e);
			return;
		}
		// Recorded at once, so the first update merges against what was
		// written rather than against a guess.
		try (InputStream in = RuleLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in != null && baseFile != null)
			{
				Files.copy(in, baseFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			// Only costs the first update a merge against 1.4.0's defaults.
			log.warn("Could not record the starter rules in {}", baseFile, e);
		}
	}
}
