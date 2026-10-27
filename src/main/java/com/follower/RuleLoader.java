package com.follower;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import javax.inject.*;
import lombok.Getter;
import com.follower.TriggerEvent.Type;

/**
 * Loads phrases.json and reloads it when the file changes on disk, so you can edit
 * rules with the client running. The file plumbing - first-run defaults, the
 * change poll, the three-way merge of each update - is {@link MergingLoader}'s.
 */
@Singleton
class RuleLoader extends MergingLoader<RuleFile>
{
	/** Fields the three-way must not touch: lines have their own pass. */
	static final Set<String> SETTLED_BY_HAND = Set.of("say", "id");

	/** The bundled defaults as of the last merge, for the three-way. */
	static final String BASE_FILE = "defaults-seen.json";

	/**
	 * Beside the base: the bundled rules' checksum and the player's file as
	 * the last merge left them. When neither has changed the merge would do
	 * nothing, and it re-read three large files and rewrote the base, 70-100
	 * ms of every start on the Swing thread (perf audit, Oct 2026).
	 */
	static final String MERGE_STAMP = "defaults-seen.stamp";

	/** Read from default-phrases.json in the jar when it is missing. */
	static final String FILE_NAME = "phrases.json";

	/**
	 * The rules 1.4.0 shipped, a base for some files that have none; see
	 * {@link #unrecordedBase}.
	 */
	static final String UNRECORDED_BASE = "/com/follower/seen/1.4.0-phrases.json";

	@Getter
	List<SpeechRule> rules = Collections.emptyList();

	/**
	 * The NPC names the boss rules name, which is what the rest of the plugin
	 * means by "boss" - see {@link com.follower.speech.TriggerContext#isBoss}.
	 * Collected here because this is where the rules are already walked, and
	 * because the rule file is the only place the list should live.
	 */
	@Getter
	Set<String> bossNames = Collections.emptySet();

	/**
	 * Whether any loaded rule uses the event-driven varbitChanged condition.
	 * Varbit changes arrive in floods (thousands at login), and dispatching
	 * each through every rule is pure waste when nothing listens - the
	 * state-based varbitEquals rules evaluate on the tick heartbeat instead.
	 */
	@Getter
	boolean varbitEventRules;

	@Inject
	RuleLoader(Gson gson)
	{
		super(gson, RuleFile.class, FILE_NAME, BASE_FILE, "rules", "rules");
	}

	@Override
	boolean upToDate(byte[] shipped) throws IOException
	{
		var stampFile = file.resolveSibling(MERGE_STAMP);
		return Files.exists(stampFile) && mergeStamp(shipped).equals(
			new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8));
	}

	@Override
	void merged(byte[] shipped) throws IOException
	{
		Files.write(file.resolveSibling(MERGE_STAMP), mergeStamp(shipped).getBytes(StandardCharsets.UTF_8));
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
	@Override
	void merge(JsonObject bundled, JsonObject ours, JsonObject base) throws IOException
	{
		// A rule missing from the base is judged by mergeRule without one.
		var mine = byId(array(ours, "rules"));
		var was = byId(array(base, "rules"));
		Set<String> known = new HashSet<>(mine.keySet());
		known.addAll(strings(ours, "removed"));

		var rules = ours.getAsJsonArray("rules");
		int added = 0,
			updated = 0;
		for (var element : bundled.getAsJsonArray("rules"))
		{
			String id = idOf(element);
			if (id == null)
				continue;
			var held = mine.get(id);
			if (!known.contains(id))
			{
				rules.add(element);
				added++;
			}
			// Absent but known means the editor deleted it; it stays deleted.
			else if (held != null && mergeRule(held, was.get(id), element.getAsJsonObject()) > 0)
				updated++;
		}

		// A rule the update dropped goes too, as a dropped line does,
		// unless the player has changed it: then it is theirs now.
		var stillShipped = byId(array(bundled, "rules")).keySet();
		int dropped = 0;
		for (Iterator<JsonElement> it = rules.iterator(); it.hasNext(); )
		{
			var element = it.next();
			String id = idOf(element);
			if (id != null && !stillShipped.contains(id) && element.equals(was.get(id)))
			{
				it.remove();
				dropped++;
			}
		}

		int placesAdded = addMissing(bundled, ours, "places");
		if (added + updated + dropped + placesAdded > 0)
			save(ours);
	}

	/**
	 * The entries of a bundled map the player's lacks.
	 *
	 * @return how many arrived
	 */
	static int addMissing(JsonObject bundled, JsonObject ours, String key)
	{
		if (!bundled.has(key) || !bundled.get(key).isJsonObject())
			return 0;
		var held = ours.has(key) && ours.get(key).isJsonObject() ? ours.getAsJsonObject(key) : new JsonObject();
		int added = 0;
		for (var entry : bundled.getAsJsonObject(key).entrySet())
		{
			if (!held.has(entry.getKey()))
			{
				held.add(entry.getKey(), entry.getValue());
				added++;
			}
		}
		ours.add(key, held);
		return added;
	}

	/**
	 * Folds one bundled rule into the player's copy.
	 *
	 * @param base the rule as it was bundled at the last merge, or null when
	 * there is no record - see the bootstrap below
	 * @return more than 0 when anything changed, so the caller can count rules
	 */
	static int mergeRule(JsonObject held, JsonObject base, JsonObject bundled)
	{
		if (base == null)
			return bootstrap(held, bundled);
		return fields(held, base, bundled, SETTLED_BY_HAND) + strings(held, "say", base, bundled);
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
	static int bootstrap(JsonObject held, JsonObject bundled)
	{
		if (!untouched(strings(held, "say"), strings(bundled, "say")))
			return 0;
		var enabled = held.get("enabled");
		int before = strings(held, "say").size();
		replace(held, bundled);
		if (enabled != null)
			held.add("enabled", enabled);
		return strings(held, "say").size() == before && before > 0 ? 0 : 1;
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
	@Override
	JsonObject unrecordedBase(JsonObject ours)
	{
		var shipped = resource(UNRECORDED_BASE);
		if (shipped == null)
			return null;
		var mine = byId(array(ours, "rules"));
		Set<String> known = new HashSet<>(mine.keySet());
		known.addAll(strings(ours, "removed"));
		var kept = new JsonArray();
		for (var element : shipped.getAsJsonArray("rules"))
		{
			String id = idOf(element);
			if (id == null || !known.contains(id))
				// Older than 1.3.0: the guess serves it as it always did.
				return null;
			var held = mine.get(id);
			if (held != null && sameOutsideLines(held, element.getAsJsonObject()))
				kept.add(element);
		}
		var base = new JsonObject();
		base.add("rules", kept);
		return base;
	}

	/** Whether two copies of a rule differ only in their lines and whether it is on. */
	static boolean sameOutsideLines(JsonObject a, JsonObject b)
	{
		JsonObject x = a.deepCopy(),
			y = b.deepCopy();
		for (String key : new String[]{"say", "enabled"})
		{
			x.remove(key);
			y.remove(key);
		}
		return x.equals(y);
	}

	/** What a merge depends on: the bundled rules, the player's file, and that there is a base. */
	String mergeStamp(byte[] shipped) throws IOException
	{
		var crc = new CRC32();
		crc.update(shipped);
		return crc.getValue() + " " + Files.size(file) + " " + Files.getLastModifiedTime(file)
			+ " " + Files.exists(baseFile);
	}

	@Override
	int size()
	{
		return rules.size();
	}

	@Override
	void unreadable(List<String> problems)
	{
		apply(Collections.emptyList(), problems, "read error");
	}

	@Override
	void load(RuleFile parsed, List<String> problems)
	{
		if (parsed.version != RuleFile.SUPPORTED_VERSION)
			problems.add("version is " + parsed.version + ", plugin expects " + RuleFile.SUPPORTED_VERSION);

		List<SpeechRule> valid = new ArrayList<>();
		Set<String> seenIds = new HashSet<>();
		int index = 0;

		for (var rule : parsed.rules == null ? Collections.<SpeechRule>emptyList() : parsed.rules)
		{
			index++;
			if (rule == null)
				continue;

			if (rule.id == null || rule.id.trim().isEmpty())
				rule.id = "rule-" + index;
			if (!seenIds.add(rule.id))
				problems.add("Duplicate rule id '" + rule.id + "' - the later one wins for cooldowns");
			if (!rule.isValid())
			{
				problems.add("Rule '" + rule.id + Resources.text("ruleIncomplete"));
				continue;
			}
			if (rule.group == null || rule.group.trim().isEmpty())
				rule.group = "misc";

			rule.reset();
			valid.add(rule);
		}

		valid.sort((a, b) -> Integer.compare(b.priority, a.priority));
		filePlaces = parsed.places == null
			? Collections.<Integer, String>emptyMap() : parsed.places;
		apply(valid, problems, valid.size() + " rules");
	}

	/**
	 * Which condition type answers which event. Only the types that are gated
	 * on a specific event appear: a state condition (healthBelow, inRegion) can
	 * be true during ANY dispatch and so is never listed.
	 */
	static final Map<Type, String> EVENT_CONDITIONS = new EnumMap<>(Map.of(
		Type.NPC_SPAWN, "npcSpawn",
		Type.NPC_DESPAWN, "npcDespawn",
		Type.VARBIT, "varbitChanged",
		// Busy scenes fire NPC overheads constantly; same economics as spawns.
		Type.NPC_SHOUT, "npcShout"));

	final Set<Type> listened = EnumSet.noneOf(Type.class);

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
	boolean listensFor(Type type)
	{
		return !EVENT_CONDITIONS.containsKey(type) || listened.contains(type);
	}

	/** The rules with an npcSpawn condition somewhere in them. */
	List<SpeechRule> spawnRules = Collections.emptyList();

	/**
	 * Whether any rule's npcSpawn names this NPC. A wave of spawns is a pass
	 * over every rule for each NPC, 5-12 ms in one frame for a busy area
	 * (perf audit, Oct 2026); a spawn no rule names can make true nothing the
	 * next tick would not, for the reason {@link #listensFor} gives.
	 */
	boolean answersSpawn(TriggerEvent spawn)
	{
		for (var rule : spawnRules)
		{
			if (rule.when.answersSpawn(spawn))
				return true;
		}
		return false;
	}

	/**
	 * Region id to place name, read off the Locations rules: the first
	 * sentence of each area rule's note, for every region it names. The
	 * Memory window shows these beside the bare ids so "Region 12850" reads
	 * as Lumbridge. Only rules with a note contribute; an id no rule names is
	 * simply absent.
	 */
	@Getter
	Map<Integer, String> regionNames = Collections.emptyMap();

	/** The file's own place names, for regions no area rule names: its "places". */
	Map<Integer, String> filePlaces = Collections.emptyMap();

	static void collectRegionNames(SpeechRule rule, Map<Integer, String> into)
	{
		if (rule.when == null || rule.when.regions == null
			|| rule.note == null || rule.note.trim().isEmpty())
			return;
		String type = rule.when.type == null ? "" : rule.when.type.toLowerCase(Locale.ROOT);
		if (!type.equals("inregion") && !type.equals("regionenter"))
			return;
		String name = rule.note.trim();
		int stop = name.indexOf(". ");
		if (stop > 0)
			name = name.substring(0, stop);
		else if (name.endsWith("."))
			name = name.substring(0, name.length() - 1);
		for (var region : rule.when.regions)
		{
			if (region != null)
				into.putIfAbsent(region, name);
		}
	}

	/** Each region an area rule's "all" stands in, by the rule's title up to its comma, where nothing names it yet. */
	static void collectTitledRegionNames(SpeechRule rule, Map<Integer, String> into)
	{
		if (rule.when == null || rule.when.conditions == null || rule.title == null
			|| !"all".equalsIgnoreCase(rule.when.type))
			return;
		int comma = rule.title.indexOf(',');
		String name = (comma > 0 ? rule.title.substring(0, comma) : rule.title).trim();
		for (var part : rule.when.conditions)
		{
			if (part != null && part.regions != null && !name.isEmpty()
				&& ("inregion".equalsIgnoreCase(part.type) || "regionenter".equalsIgnoreCase(part.type)))
			{
				for (var region : part.regions)
				{
					if (region != null)
						into.putIfAbsent(region, name);
				}
			}
		}
	}

	void collectBossNames(Condition condition, Set<String> into)
	{
		if (condition == null)
			return;
		if (condition.type != null
			&& condition.type.toLowerCase(Locale.ROOT).startsWith("npc")
			&& condition.names != null)
			into.addAll(condition.names);
		if (condition.conditions != null)
		{
			for (var sub : condition.conditions)
				collectBossNames(sub, into);
		}
	}

	void apply(List<SpeechRule> newRules, List<String> problems, String summary)
	{
		rules = newRules;

		Set<String> bosses = new HashSet<>();
		Map<Integer, String> places = new HashMap<>();
		listened.clear();
		List<SpeechRule> spawning = new ArrayList<>();
		for (var rule : newRules)
		{
			if ("boss".equalsIgnoreCase(rule.group))
				collectBossNames(rule.when, bosses);
			else if ("area".equalsIgnoreCase(rule.group))
				collectRegionNames(rule, places);
			for (var entry : EVENT_CONDITIONS.entrySet())
			{
				if (rule.uses(entry.getValue()))
					listened.add(entry.getKey());
			}
			if (rule.uses("npcSpawn"))
				spawning.add(rule);
		}
		// Then the places only a rule with more to it names, by its title:
		// "The Abyss, after Enter the Abyss". Behaviour QA5, 19 Oct: a death
		// in the Abyss had no place to be filed in.
		for (var rule : newRules)
		{
			if ("area".equalsIgnoreCase(rule.group))
				collectTitledRegionNames(rule, places);
		}
		// And the places no rule stands in, named in the file itself: the
		// essence mine, the Wizards' Tower basement (speech QA5 #23).
		for (var place : filePlaces.entrySet())
		{
			if (place.getKey() != null && place.getValue() != null)
				places.putIfAbsent(place.getKey(), place.getValue());
		}
		bossNames = bosses;
		regionNames = places;
		varbitEventRules = listened.contains(Type.VARBIT);
		spawnRules = spawning;
		loaded(problems, summary);
	}
}
