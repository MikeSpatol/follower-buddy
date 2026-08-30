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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads dialogs.json and reloads it when it changes, exactly as
 * {@link RuleLoader} does for phrases.json - same poll, same defaults-on-first-
 * run, same refusal to replace good content with nothing on a bad read.
 */
@Slf4j
@Singleton
public class DialogLoader
{
	public static final String FILE_NAME = "dialogs.json";

	/** The bundled trees as of the last merge, for the three-way. */
	public static final String BASE_FILE = "dialogs-seen.json";

	/** Handled by the node pass rather than as a field of the tree. */
	private static final java.util.Set<String> TREE_FIELDS =
		new java.util.HashSet<>(java.util.Arrays.asList("nodes", "id"));

	/** Lines have their own pass; the id is what we matched on. */
	private static final java.util.Set<String> NODE_FIELDS =
		new java.util.HashSet<>(java.util.Arrays.asList("says", "id"));
	private static final String BUNDLED_DEFAULT = "/com/follower/default-dialogs.json";

	private static final int SUPPORTED_VERSION = 1;

	private final Gson gson;

	private Map<String, DialogTree> trees = Collections.emptyMap();

	@Getter
	private String status = "not loaded";

	@Getter
	private List<String> errors = Collections.emptyList();

	private Path file;
	private Path baseFile;
	private long lastModified = -1L;
	private long lastSize = -1L;

	@Inject
	public DialogLoader(Gson gson)
	{
		this.gson = gson;
	}

	public void initialise(Path dataDir)
	{
		initialise(dataDir, false);
	}

	/**
	 * @param mergeDefaults whether to fold newly bundled trees into an
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
	 * Folds bundled conversation changes into the player's file, three ways.
	 *
	 * <p>The same upgrade gap {@code phrases.json} had, and fixed the same way -
	 * see {@link ThreeWay}. Appending only whole new trees meant a reworded
	 * node, an added choice or a fixed dead end reached nobody who already had
	 * the plugin.
	 *
	 * <p>Trees merge at two levels because that is how they are built: the tree
	 * carries its own fields and a list of nodes, and each node carries its own
	 * fields and its lines. A node the update added arrives, a node it removed
	 * goes unless the player had already changed it, and a node they have
	 * rewritten is theirs.
	 */
	private void mergeNewDefaults()
	{
		try (InputStream in = DialogLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				return;
			}
			Gson pretty = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
			com.google.gson.JsonObject bundled = pretty.fromJson(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject ours = pretty.fromJson(
				new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			if (bundled == null || ours == null
				|| !bundled.has("trees") || !ours.has("trees"))
			{
				return;
			}

			com.google.gson.JsonObject base = readBase(pretty);
			java.util.Map<String, com.google.gson.JsonObject> mine =
				ThreeWay.byId(ThreeWay.array(ours, "trees"));
			java.util.Map<String, com.google.gson.JsonObject> was =
				ThreeWay.byId(ThreeWay.array(base, "trees"));

			com.google.gson.JsonArray trees = ours.getAsJsonArray("trees");
			int added = 0;
			int updated = 0;
			for (com.google.gson.JsonElement element : bundled.getAsJsonArray("trees"))
			{
				String id = ThreeWay.idOf(element);
				if (id == null)
				{
					continue;
				}
				com.google.gson.JsonObject held = mine.get(id);
				if (held == null)
				{
					trees.add(element);
					added++;
					continue;
				}
				if (mergeTree(held, was.get(id), element.getAsJsonObject()) > 0)
				{
					updated++;
				}
			}

			if (added > 0 || updated > 0)
			{
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Files.write(file, (pretty.toJson(ours) + "\n")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				log.info("Merged {} new tree(s) and {} changed tree(s) into {}",
					added, updated, file);
			}

			// Last, and whether or not anything moved, so a merge that only
			// half-happened is retried rather than recorded as done.
			writeBase(bundled, pretty);
		}
		catch (Exception e)
		{
			log.warn("Could not merge new defaults into {}", file, e);
		}
	}

	/** One bundled tree, folded into the player's copy of it. */
	private static int mergeTree(com.google.gson.JsonObject held,
		com.google.gson.JsonObject base, com.google.gson.JsonObject bundled)
	{
		if (base == null)
		{
			return bootstrap(held, bundled);
		}

		int changes = ThreeWay.fields(held, base, bundled, TREE_FIELDS);

		com.google.gson.JsonArray nodes = ThreeWay.array(held, "nodes");
		com.google.gson.JsonArray shipped = ThreeWay.array(bundled, "nodes");
		if (nodes == null || shipped == null)
		{
			return changes;
		}
		java.util.Map<String, com.google.gson.JsonObject> wasNodes =
			ThreeWay.byId(ThreeWay.array(base, "nodes"));

		java.util.Set<String> shippedIds = new java.util.HashSet<>();
		for (com.google.gson.JsonElement element : shipped)
		{
			String id = ThreeWay.idOf(element);
			if (id != null)
			{
				shippedIds.add(id);
			}
		}

		// Rebuilt rather than edited in place, because a node can leave.
		java.util.List<com.google.gson.JsonObject> kept = new java.util.ArrayList<>();
		java.util.Set<String> keptIds = new java.util.HashSet<>();
		for (com.google.gson.JsonElement element : nodes)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			com.google.gson.JsonObject node = element.getAsJsonObject();
			String id = ThreeWay.idOf(element);
			com.google.gson.JsonObject before = id == null ? null : wasNodes.get(id);
			if (id != null && before != null && !shippedIds.contains(id)
				&& before.equals(node))
			{
				// The update removed it and the player never touched it.
				changes++;
				continue;
			}
			kept.add(node);
			if (id != null)
			{
				keptIds.add(id);
			}
		}

		for (com.google.gson.JsonElement element : shipped)
		{
			String id = ThreeWay.idOf(element);
			if (id == null)
			{
				continue;
			}
			com.google.gson.JsonObject now = element.getAsJsonObject();
			if (!keptIds.contains(id))
			{
				// Absent here: new upstream unless the player deleted it, and
				// the base is what tells the two apart.
				if (!wasNodes.containsKey(id))
				{
					kept.add(now);
					changes++;
				}
				continue;
			}
			com.google.gson.JsonObject node = null;
			for (com.google.gson.JsonObject candidate : kept)
			{
				if (id.equals(ThreeWay.idOf(candidate)))
				{
					node = candidate;
					break;
				}
			}
			// A node the base never had is one the player wrote; comparing it
			// with itself leaves it alone, which is the intent.
			com.google.gson.JsonObject before = wasNodes.getOrDefault(id, node);
			changes += ThreeWay.fields(node, before, now, NODE_FIELDS);
			changes += ThreeWay.strings(node, "says", before, now);
		}

		if (changes > 0)
		{
			com.google.gson.JsonArray out = new com.google.gson.JsonArray();
			for (com.google.gson.JsonObject node : kept)
			{
				out.add(node);
			}
			held.add("nodes", out);
		}
		return changes;
	}

	/**
	 * The first run, with no record of what shipped before. As with the rules,
	 * the only honest question left is whether the player has written into
	 * this tree at all: a tree holding nothing but shipped lines has not been,
	 * and can be brought up to date without taking anything from anybody.
	 */
	private static int bootstrap(com.google.gson.JsonObject held,
		com.google.gson.JsonObject bundled)
	{
		if (!ThreeWay.untouched(allSays(held), allSays(bundled)))
		{
			return 0;
		}
		if (held.equals(bundled))
		{
			return 0;
		}
		for (String key : new java.util.ArrayList<>(held.keySet()))
		{
			held.remove(key);
		}
		for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : bundled.entrySet())
		{
			held.add(entry.getKey(), entry.getValue());
		}
		return 1;
	}

	/** Every line the tree can speak, for the bootstrap's one question. */
	private static java.util.List<String> allSays(com.google.gson.JsonObject tree)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		com.google.gson.JsonArray nodes = ThreeWay.array(tree, "nodes");
		if (nodes == null)
		{
			return out;
		}
		for (com.google.gson.JsonElement element : nodes)
		{
			if (element.isJsonObject())
			{
				out.addAll(ThreeWay.strings(element.getAsJsonObject(), "says"));
			}
		}
		return out;
	}

	/** The trees as they shipped at the last merge, or null if never written. */
	private com.google.gson.JsonObject readBase(Gson pretty)
	{
		if (baseFile == null || !Files.exists(baseFile))
		{
			return null;
		}
		try
		{
			return pretty.fromJson(new String(Files.readAllBytes(baseFile),
				java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
		}
		catch (Exception e)
		{
			log.warn("Could not read {}, merging without a base", baseFile, e);
			return null;
		}
	}

	private void writeBase(com.google.gson.JsonObject bundled, Gson pretty)
	{
		if (baseFile == null)
		{
			return;
		}
		try
		{
			Files.write(baseFile, (pretty.toJson(bundled) + "\n")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn("Could not record the merged defaults in {}", baseFile, e);
		}
	}

	private static String treeIdOf(com.google.gson.JsonElement element)
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

	public DialogTree get(String id)
	{
		return id == null ? null : trees.get(id);
	}

	/** Every tree, in file order. */
	public List<DialogTree> getTrees()
	{
		return new ArrayList<>(trees.values());
	}

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
			DialogFile parsed = gson.fromJson(reader, DialogFile.class);

			if (parsed == null)
			{
				// The same reasoning as phrases.json: an empty read is almost
				// always a poll landing between an editor truncating the file
				// and writing it back. Keep what we had and try again.
				errors = Collections.singletonList(
					"File is empty; keeping " + trees.size() + " previous trees");
				status = "empty file, keeping " + trees.size() + " previous trees";
				log.warn("dialogs.json read as empty; keeping the previous trees");
				return;
			}

			lastModified = Files.getLastModifiedTime(file).toMillis();
			lastSize = Files.size(file);

			if (parsed.version != SUPPORTED_VERSION)
			{
				problems.add("version is " + parsed.version
					+ ", plugin expects " + SUPPORTED_VERSION);
			}

			Map<String, DialogTree> valid = new LinkedHashMap<>();
			Set<String> seen = new HashSet<>();
			for (DialogTree tree : parsed.trees == null
				? Collections.<DialogTree>emptyList() : parsed.trees)
			{
				if (tree == null)
				{
					continue;
				}
				List<String> found = tree.problems();
				problems.addAll(found);
				if (!found.isEmpty())
				{
					// A broken tree is left out rather than half-loaded: a
					// conversation that dead-ends mid-branch is worse than one
					// that never opens, because the player is already in it.
					continue;
				}
				if (!seen.add(tree.id))
				{
					problems.add("Duplicate tree id '" + tree.id + "'");
				}
				valid.put(tree.id, tree);
			}

			trees = valid;
			errors = problems;
			status = valid.size() + " trees"
				+ (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)");
			// At INFO like the rule loader: when a conversation does not open,
			// the first thing worth knowing is whether it loaded at all.
			log.info("Loaded dialogs: {}", status);
		}
		catch (IOException e)
		{
			problems.add("Could not read " + file + ": " + e.getMessage());
			errors = problems;
			status = "read error";
		}
		catch (JsonParseException e)
		{
			// Keep the previous trees on a syntax error, as with phrases.json.
			problems.add("JSON error: " + e.getMessage());
			errors = problems;
			status = "syntax error, keeping " + trees.size() + " previous trees";
			log.warn("dialogs.json failed to parse", e);
		}
	}

	private void writeDefaults()
	{
		try (InputStream in = DialogLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				log.warn("Bundled {} is missing", BUNDLED_DEFAULT);
				return;
			}
			Files.createDirectories(file.getParent());
			Files.copy(in, file);
			log.debug("Wrote default dialogs to {}", file);
		}
		catch (IOException e)
		{
			log.warn("Could not write default dialogs to {}", file, e);
		}
	}

	public void restoreDefaults()
	{
		try
		{
			Files.deleteIfExists(file);
		}
		catch (IOException e)
		{
			log.warn("Could not remove {}", file, e);
		}
		writeDefaults();
		reload();
	}

	private static final class DialogFile
	{
		int version;
		List<DialogTree> trees;
	}
}
