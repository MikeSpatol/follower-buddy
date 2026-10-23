package com.follower.speech;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Loads dialogs.json and reloads it when it changes, exactly as
 * {@link RuleLoader} does for phrases.json - same poll, same defaults-on-first-
 * run, same refusal to replace good content with nothing on a bad read; the
 * plumbing is {@link MergingLoader}'s.
 */
@Singleton
public class DialogLoader extends MergingLoader<DialogLoader.DialogFile>
{
	/** Read from default-dialogs.json in the jar when it is missing. */
	public static final String FILE_NAME = "dialogs.json";

	/** The bundled trees as of the last merge, for the three-way. */
	public static final String BASE_FILE = "dialogs-seen.json";

	/** Handled by the node pass rather than as a field of the tree. */
	private static final Set<String> TREE_FIELDS = Set.of("nodes", "id");

	/** Lines have their own pass; the id is what we matched on. */
	private static final Set<String> NODE_FIELDS = Set.of("says", "id");

	/** The trees 1.4.0 shipped, the base for a file that has none; see RuleLoader. */
	static final String UNRECORDED_BASE = "/com/follower/seen/1.4.0-dialogs.json";

	private static final int SUPPORTED_VERSION = 1;

	private Map<String, DialogTree> trees = Collections.emptyMap();

	@Inject
	public DialogLoader(Gson gson)
	{
		super(gson, DialogFile.class, FILE_NAME, BASE_FILE, "trees", "conversations", "dialogs");
	}

	// public:cut logs
	@Override
	void wroteDefaults()
	{
		log.debug("Wrote default dialogs to {}", file);
	}

	// public:end logs
	/**
	 * Folds bundled conversation changes into the player's file, three ways.
	 *
	 * <p>The same upgrade gap {@code phrases.json} had, and fixed the same way -
	 * see {@link MergingLoader}. Appending only whole new trees meant a reworded
	 * node, an added choice or a fixed dead end reached nobody who already had
	 * the plugin.
	 *
	 * <p>Trees merge at two levels because that is how they are built: the tree
	 * carries its own fields and a list of nodes, and each node carries its own
	 * fields and its lines. A node the update added arrives, a node it removed
	 * goes unless the player had already changed it, and a node they have
	 * rewritten is theirs.
	 */
	@Override
	void merge(JsonObject bundled, JsonObject ours, JsonObject base) throws IOException
	{
		Map<String, JsonObject> mine = byId(array(ours, "trees"));
		Map<String, JsonObject> was = byId(array(base, "trees"));
		JsonArray trees = ours.getAsJsonArray("trees");
		int added = 0;
		int updated = 0;
		for (JsonElement element : bundled.getAsJsonArray("trees"))
		{
			String id = idOf(element);
			if (id == null)
			{
				continue;
			}
			JsonObject held = mine.get(id);
			if (held == null)
			{
				trees.add(element);
				added++;
			}
			else if (mergeTree(held, was.get(id), element.getAsJsonObject()) > 0)
			{
				updated++;
			}
		}

		if (added + updated > 0)
		{
			save(ours);
			// public:cut logs
			log.info("Merged {} new tree(s) and {} changed tree(s) into {}",
				added, updated, file);
			// public:end logs
		}
	}

	/** For a file that never merged, the trees 1.4.0 wrote; null only if even that cannot be read. */
	@Override
	JsonObject unrecordedBase(JsonObject ours)
	{
		return Files.exists(baseFile) ? null : resource(UNRECORDED_BASE);
	}

	/** One bundled tree, folded into the player's copy of it. */
	private static int mergeTree(JsonObject held, JsonObject base, JsonObject bundled)
	{
		if (base == null)
		{
			return bootstrap(held, bundled);
		}

		int changes = fields(held, base, bundled, TREE_FIELDS);

		JsonArray nodes = array(held, "nodes");
		JsonArray shipped = array(bundled, "nodes");
		if (nodes == null || shipped == null)
		{
			return changes;
		}
		Map<String, JsonObject> wasNodes = byId(array(base, "nodes"));
		Set<String> shippedIds = byId(shipped).keySet();

		// Rebuilt rather than edited in place, because a node can leave.
		JsonArray kept = new JsonArray();
		Map<String, JsonObject> keptById = new HashMap<>();
		for (JsonElement element : nodes)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject node = element.getAsJsonObject();
			String id = idOf(element);
			JsonObject before = wasNodes.get(id);
			if (before != null && !shippedIds.contains(id) && before.equals(node))
			{
				// The update removed it and the player never touched it.
				changes++;
				continue;
			}
			kept.add(node);
			if (id != null)
			{
				keptById.putIfAbsent(id, node);
			}
		}

		for (JsonElement element : shipped)
		{
			String id = idOf(element);
			if (id == null)
			{
				continue;
			}
			JsonObject now = element.getAsJsonObject();
			JsonObject node = keptById.get(id);
			if (node == null)
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
			// A node the base never had is one the player wrote; comparing it
			// with itself leaves it alone, which is the intent.
			JsonObject before = wasNodes.getOrDefault(id, node);
			changes += fields(node, before, now, NODE_FIELDS) + strings(node, "says", before, now);
		}

		if (changes > 0)
		{
			held.add("nodes", kept);
		}
		return changes;
	}

	/**
	 * The first run, with no record of what shipped before. As with the rules,
	 * the only honest question left is whether the player has written into
	 * this tree at all: a tree holding nothing but shipped lines has not been,
	 * and can be brought up to date without taking anything from anybody.
	 */
	private static int bootstrap(JsonObject held, JsonObject bundled)
	{
		if (!untouched(allSays(held), allSays(bundled)) || held.equals(bundled))
		{
			return 0;
		}
		replace(held, bundled);
		return 1;
	}

	/** Every line the tree can speak, for the bootstrap's one question. */
	private static List<String> allSays(JsonObject tree)
	{
		List<String> out = new ArrayList<>();
		JsonArray nodes = array(tree, "nodes");
		if (nodes != null)
		{
			for (JsonElement element : nodes)
			{
				if (element.isJsonObject())
				{
					out.addAll(strings(element.getAsJsonObject(), "says"));
				}
			}
		}
		return out;
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

	@Override
	int size()
	{
		return trees.size();
	}

	@Override
	void unreadable(List<String> problems)
	{
		errors = problems;
		status = "read error";
	}

	@Override
	void load(DialogFile parsed, List<String> problems)
	{
		if (parsed.version != SUPPORTED_VERSION)
		{
			problems.add("version is " + parsed.version
				+ ", plugin expects " + SUPPORTED_VERSION);
		}

		Map<String, DialogTree> valid = new LinkedHashMap<>();
		for (DialogTree tree : parsed.trees == null
			? Collections.<DialogTree>emptyList() : parsed.trees)
		{
			if (tree == null)
			{
				continue;
			}
			List<String> found = tree.problems();
			problems.addAll(found);
			// A broken tree is left out rather than half-loaded: a
			// conversation that dead-ends mid-branch is worse than one
			// that never opens, because the player is already in it.
			if (found.isEmpty() && valid.put(tree.id, tree) != null)
			{
				problems.add("Duplicate tree id '" + tree.id + "'");
			}
		}

		trees = valid;
		loaded(problems, valid.size() + " conversations");
	}

	static final class DialogFile
	{
		int version;
		List<DialogTree> trees;
	}
}
