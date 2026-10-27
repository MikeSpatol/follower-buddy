package com.follower.speech;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JSON file that ships with defaults and that the player may edit:
 * phrases.json and dialogs.json. Written from the jar when it is missing,
 * folded three ways into the player's copy when an update ships new defaults,
 * and reloaded when it changes on disk. Change detection is a lastModified/size
 * poll driven from the game tick - a WatchService buys nothing here and brings
 * its own platform quirks.
 *
 * <p>Folding an update into a file somebody has been editing needs three
 * copies rather than two, because from the player's file alone a line the
 * update ADDED and a line the player DELETED look identical - both are lines
 * the bundled file has and theirs does not. Keep the defaults as they stood at
 * the last merge and the question becomes answerable: bundled against base is
 * what the update did, theirs against base is what the player did.
 *
 * <p>The rule throughout is that upstream wins where the player has not touched
 * something, and the player wins everywhere they have. This is one class
 * rather than a copy per loader because the two drifting apart on a question
 * this subtle would be a bug nobody would find by reading either one.
 *
 * @param <T> the file's root object, as Gson reads it
 */
@RequiredArgsConstructor
public abstract class MergingLoader<T>
{
	/** Under each loader's own name, as before the two shared this class. */
	final Logger log = LoggerFactory.getLogger(getClass());

	final Gson gson;
	private final Class<T> type;

	/** The player's file in the data directory; the jar's copy is default-{fileName}. */
	private final String fileName;

	/** The bundled defaults as of the last merge, for the three-way. */
	private final String baseName;

	/**
	 * The array the merge works on: a file without one is left alone. It is
	 * also what the client log calls the content when a read comes back empty.
	 */
	private final String listName;

	/** What the status line and the client log call the loaded content. */
	private final String noun;

	@Getter
	Path file;
	Path baseFile;
	private long lastModified = -1L;
	private long lastSize = -1L;

	/** Built at the start of each merge; the merge's reads and writes go through it. */
	private Gson pretty;

	@Getter
	String status = "not loaded";

	@Getter
	List<String> errors = Collections.emptyList();

	/** Takes a freshly parsed file: validates it and makes it current. */
	abstract void load(T parsed, List<String> problems);

	/** The file could not be read at all. */
	abstract void unreadable(List<String> problems);

	/** How many things are loaded now, for "keeping N previous ...". */
	abstract int size();

	/**
	 * Folds the bundled defaults into the player's copy, and {@link #save}s it
	 * when anything moved.
	 *
	 * @param base the defaults as they shipped at the last merge, or null
	 */
	abstract void merge(JsonObject bundled, JsonObject ours, JsonObject base) throws IOException;

	/** A base for a file that has no record of the last merge, or null. */
	abstract JsonObject unrecordedBase(JsonObject ours);

	/** Whether the last merge already saw exactly these defaults and this file. */
	boolean upToDate(byte[] shipped) throws IOException
	{
		return false;
	}

	/** Called once the merge, and its record, are written. */
	void merged(byte[] shipped) throws IOException
	{
	}

	// public:cut logs
	/** Logs the starter file just written. */
	abstract void wroteDefaults();

	// public:end logs
	/**
	 * Points the loader at the data directory, writing the bundled defaults if
	 * no file exists yet, then loads.
	 */
	public void initialise(Path dataDir)
	{
		initialise(dataDir, false);
	}

	/**
	 * @param mergeDefaults whether to fold newly bundled defaults into an
	 * existing file first. The plugin passes true; the test harnesses supply
	 * exact files and pass false through the plain overload.
	 */
	public void initialise(Path dataDir, boolean mergeDefaults)
	{
		file = dataDir.resolve(fileName);
		baseFile = dataDir.resolve(baseName);
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

	private InputStream bundled()
	{
		return MergingLoader.class.getResourceAsStream("/com/follower/default-" + fileName);
	}

	private JsonObject json(byte[] bytes)
	{
		return pretty.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
	}

	private void mergeNewDefaults()
	{
		try (InputStream in = bundled())
		{
			if (in == null)
			{
				return;
			}
			byte[] shipped = in.readAllBytes();
			if (upToDate(shipped))
			{
				return;
			}
			pretty = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
			JsonObject bundled = json(shipped);
			JsonObject ours = json(Files.readAllBytes(file));
			if (bundled == null || ours == null || !bundled.has(listName) || !ours.has(listName))
			{
				return;
			}

			// The record of what shipped last time, or for a file that never
			// merged, what can be told of where it came from.
			JsonObject base = readBase();
			if (base == null)
			{
				base = unrecordedBase(ours);
			}
			merge(bundled, ours, base);

			// Written whether or not anything moved, and last, so a merge that
			// half-happened is retried rather than recorded as done. From here
			// on there is a base and the guessing above never runs again.
			if (writeBase(bundled))
			{
				merged(shipped);
			}
		}
		catch (Exception e)
		{
			// A file that cannot be merged is one reload() will complain about
			// in its own words; do not block startup over it.
			log.warn("Could not merge new defaults into {}", file, e);
		}
	}

	/** Writes the merged file, with the file as it stood kept beside it. */
	void save(JsonObject ours) throws IOException
	{
		Files.copy(file, file.resolveSibling(fileName + ".bak"), StandardCopyOption.REPLACE_EXISTING);
		Files.write(file, (pretty.toJson(ours) + "\n").getBytes(StandardCharsets.UTF_8));
	}

	/** A bundled file, or null when it is missing or unreadable. */
	JsonObject resource(String path)
	{
		try (InputStream in = MergingLoader.class.getResourceAsStream(path))
		{
			return in == null ? null : pretty.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
		}
		catch (Exception e)
		{
			log.warn("Could not read {}, merging without a base", path, e);
			return null;
		}
	}

	/** The defaults as they stood at the last merge, or null if never written. */
	private JsonObject readBase()
	{
		if (!Files.exists(baseFile))
		{
			return null;
		}
		try
		{
			return json(Files.readAllBytes(baseFile));
		}
		catch (Exception e)
		{
			// Unreadable is the same as absent: fall back to the bootstrap
			// rather than refusing to merge at all.
			log.warn("Could not read {}, merging without a base", baseFile, e);
			return null;
		}
	}

	private boolean writeBase(JsonObject bundled)
	{
		try
		{
			Files.write(baseFile, (pretty.toJson(bundled) + "\n").getBytes(StandardCharsets.UTF_8));
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

	private void writeDefaults()
	{
		try (InputStream in = bundled())
		{
			if (in == null)
			{
				// The jar lacks its own defaults: a broken build.
				log.warn("Bundled /com/follower/default-{} is missing", fileName);
				return;
			}
			Files.createDirectories(file.getParent());
			Files.copy(in, file);
			wroteDefaults(); // public:cut logs
		}
		catch (IOException e)
		{
			log.warn("Could not write default {} to {}", noun, file, e);
			return;
		}
		// Recorded at once, so the first update merges against what was
		// written rather than against a guess.
		try (InputStream in = bundled())
		{
			if (in != null)
			{
				Files.copy(in, baseFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			// Only costs the first update a merge against 1.4.0's defaults.
			log.warn("Could not record the starter {} in {}", noun, baseFile, e);
		}
	}

	/** Reloads if the file changed since last check. Returns true if it was re-read. */
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
			T parsed = gson.fromJson(reader, type);

			if (parsed == null)
			{
				// An empty read is almost never an empty file on purpose. It is
				// what a poll sees in the instant between an editor truncating
				// the file and writing it back, and it is what a failed save
				// leaves behind. Treating it as "nothing" replaced the whole
				// personality with silence, which is exactly what the syntax
				// error path below is careful NOT to do. Keep what we had, say
				// so, and do not record this read as current - the next poll
				// then picks up the finished file.
				problems.add("File is empty; keeping " + size() + " previous " + noun);
				errors = problems;
				status = "empty file, keeping " + size() + " previous " + noun;
				log.warn("{} read as empty; keeping the previous {}", fileName, listName);
				return;
			}

			lastModified = Files.getLastModifiedTime(file).toMillis();
			lastSize = Files.size(file);
			load(parsed, problems);
		}
		catch (IOException e)
		{
			problems.add("Could not read " + file + ": " + e.getMessage());
			unreadable(problems);
		}
		catch (JsonParseException e)
		{
			// Keep what loaded last time on a syntax error; losing your
			// follower's whole personality over a trailing comma is worse
			// than running stale content.
			problems.add("JSON error: " + e.getMessage());
			errors = problems;
			status = "syntax error, keeping " + size() + " previous " + noun;
			log.warn("{} failed to parse", fileName, e);
		}
	}

	/** Makes what was loaded current, with a status line that counts its problems. */
	void loaded(List<String> problems, String summary)
	{
		errors = problems;
		status = summary + (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)");
		// At INFO: when a rule never fires or a conversation does not open,
		// the first thing worth knowing is whether it loaded at all.
		log.info("Loaded {}: {}", noun, status); // public:cut logs
	}

	/** Makes {@code held} a copy of {@code with}, in place: it is the object the file holds. */
	static void replace(JsonObject held, JsonObject with)
	{
		for (String key : new ArrayList<>(held.keySet()))
		{
			held.remove(key);
		}
		for (Map.Entry<String, JsonElement> entry : with.entrySet())
		{
			held.add(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Every field except the named ones. A field the update did not change is
	 * left alone; one the player changed is theirs; the rest take the update's
	 * value, or go if the update dropped them.
	 *
	 * @return how many fields moved
	 */
	static int fields(JsonObject held, JsonObject base, JsonObject bundled, Set<String> except)
	{
		int changes = 0;
		Set<String> keys = new LinkedHashSet<>(bundled.keySet());
		keys.addAll(base.keySet());
		for (String key : keys)
		{
			JsonElement before = base.get(key);
			JsonElement now = bundled.get(key);
			// Skipped when the update has nothing to offer here, or when the
			// player moved it: a tuned cooldown or a reworded note is a
			// decision, not a stale copy.
			if (except.contains(key) || Objects.equals(before, now) || !Objects.equals(held.get(key), before))
			{
				continue;
			}
			if (now == null)
			{
				held.remove(key);
			}
			else
			{
				held.add(key, now);
			}
			changes++;
		}
		return changes;
	}

	/**
	 * An array of strings, merged as a set with a history. What the update
	 * added arrives; what it removed goes, unless the player had already
	 * removed it; everything else - their own lines, their own rewordings - is
	 * left exactly where it is. A list the player never touched takes the
	 * update's whole, in the update's order: a dialog node's lines are said in
	 * order, and a reworded first line appended at the end had the follower
	 * say "Lead on." before its answer (dialogue check, 14 Oct).
	 *
	 * @return more than 0 when anything moved
	 */
	static int strings(JsonObject held, String key, JsonObject base, JsonObject bundled)
	{
		if (!held.has(key) || !held.get(key).isJsonArray())
		{
			return 0;
		}
		List<String> shipped = strings(bundled, key);
		List<String> mine = strings(held, key);
		List<String> kept = shipped;
		int changes = 1;
		if (base == null || !mine.equals(strings(base, key)) || mine.equals(shipped))
		{
			Set<String> was = new HashSet<>(strings(base, key));
			kept = new ArrayList<>();
			changes = 0;
			for (String line : mine)
			{
				if (was.contains(line) && !shipped.contains(line))
				{
					changes++;
					continue;
				}
				kept.add(line);
			}
			for (String line : shipped)
			{
				if (!was.contains(line) && !kept.contains(line))
				{
					kept.add(line);
					changes++;
				}
			}
		}
		if (changes > 0)
		{
			JsonArray out = new JsonArray();
			kept.forEach(out::add);
			held.add(key, out);
		}
		return changes;
	}

	/** The strings under a key, or empty. */
	static List<String> strings(JsonObject object, String key)
	{
		List<String> out = new ArrayList<>();
		JsonArray array = array(object, key);
		if (array != null)
		{
			for (JsonElement element : array)
			{
				if (element.isJsonPrimitive())
				{
					out.add(element.getAsString());
				}
			}
		}
		return out;
	}

	/**
	 * Whether the player has written anything of their own here.
	 *
	 * <p>The only question the first run can honestly ask. With no base there
	 * is no way to tell a deletion from an addition, but a file holding
	 * nothing but shipped lines has plainly not been written into, and can be
	 * brought up to date without taking anything away from anybody.
	 */
	static boolean untouched(List<String> theirs, List<String> shipped)
	{
		return new HashSet<>(shipped).containsAll(theirs);
	}

	/** The objects of an array, by their {@code id}. */
	static Map<String, JsonObject> byId(JsonArray array)
	{
		Map<String, JsonObject> map = new HashMap<>();
		if (array != null)
		{
			for (JsonElement element : array)
			{
				String id = idOf(element);
				if (id != null)
				{
					map.put(id, element.getAsJsonObject());
				}
			}
		}
		return map;
	}

	static JsonArray array(JsonObject object, String key)
	{
		return object != null && object.has(key) && object.get(key).isJsonArray()
			? object.getAsJsonArray(key) : null;
	}

	static String idOf(JsonElement element)
	{
		if (element == null || !element.isJsonObject())
		{
			return null;
		}
		JsonElement id = element.getAsJsonObject().get("id");
		return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
	}
}
