package com.follower;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.*;

/**
 * Loads dialogs.json and reloads it when it changes, exactly as
 * {@link RuleLoader} does for phrases.json - same poll, same defaults-on-first-
 * run, same refusal to replace good content with nothing on a bad read; the
 * plumbing is {@link MergingLoader}'s.
 */
@Singleton
class DialogLoader extends MergingLoader<DialogLoader.DialogFile>
{
	/** Read from default-dialogs.json in the jar when it is missing. */
	static final String FILE_NAME = "dialogs.json";

	/** The bundled trees as of the last merge, for the three-way. */
	static final String BASE_FILE = "dialogs-seen.json";

	/** Handled by the node pass rather than as a field of the tree. */
	static final Set<String> TREE_FIELDS = Set.of("nodes", "id");

	/** Lines have their own pass; the id is what we matched on. */
	static final Set<String> NODE_FIELDS = Set.of("says", "id");

	/** The trees 1.4.0 shipped, the base for a file that has none; see RuleLoader. */
	static final String UNRECORDED_BASE = "/com/follower/seen/1.4.0-dialogs.json";

	static final int SUPPORTED_VERSION = 1;

	Map<String, DialogTree> trees = Collections.emptyMap();

	@Inject
	DialogLoader(Gson gson)
	{
		super(gson, DialogFile.class, FILE_NAME, BASE_FILE, "trees", "conversations");
	}

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
		var mine = byId(array(ours, "trees"));
		var was = byId(array(base, "trees"));
		var trees = ours.getAsJsonArray("trees");
		int added = 0,
			updated = 0;
		for (var element : bundled.getAsJsonArray("trees"))
		{
			String id = idOf(element);
			if (id == null)
				continue;
			var held = mine.get(id);
			if (held == null)
			{
				trees.add(element);
				added++;
			}
			else if (mergeTree(held, was.get(id), element.getAsJsonObject()) > 0)
				updated++;
		}

		if (added + updated > 0)
			save(ours);
	}

	/** For a file that never merged, the trees 1.4.0 wrote; null only if even that cannot be read. */
	@Override
	JsonObject unrecordedBase(JsonObject ours)
	{
		return Files.exists(baseFile) ? null : resource(UNRECORDED_BASE);
	}

	/** One bundled tree, folded into the player's copy of it. */
	static int mergeTree(JsonObject held, JsonObject base, JsonObject bundled)
	{
		if (base == null)
			return bootstrap(held, bundled);

		int changes = fields(held, base, bundled, TREE_FIELDS);

		JsonArray nodes = array(held, "nodes"),
			shipped = array(bundled, "nodes");
		if (nodes == null || shipped == null)
			return changes;
		var wasNodes = byId(array(base, "nodes"));
		var shippedIds = byId(shipped).keySet();

		// Rebuilt rather than edited in place, because a node can leave.
		var kept = new JsonArray();
		Map<String, JsonObject> keptById = new HashMap<>();
		for (var element : nodes)
		{
			if (!element.isJsonObject())
				continue;
			var node = element.getAsJsonObject();
			String id = idOf(element);
			var before = wasNodes.get(id);
			if (before != null && !shippedIds.contains(id) && before.equals(node))
			{
				// The update removed it and the player never touched it.
				changes++;
				continue;
			}
			kept.add(node);
			if (id != null)
				keptById.putIfAbsent(id, node);
		}

		for (var element : shipped)
		{
			String id = idOf(element);
			if (id == null)
				continue;
			JsonObject now = element.getAsJsonObject(),
				node = keptById.get(id);
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
			var before = wasNodes.getOrDefault(id, node);
			changes += fields(node, before, now, NODE_FIELDS) + strings(node, "says", before, now);
		}

		if (changes > 0)
			held.add("nodes", kept);
		return changes;
	}

	/**
	 * The first run, with no record of what shipped before. As with the rules,
	 * the only honest question left is whether the player has written into
	 * this tree at all: a tree holding nothing but shipped lines has not been,
	 * and can be brought up to date without taking anything from anybody.
	 */
	static int bootstrap(JsonObject held, JsonObject bundled)
	{
		if (!untouched(allSays(held), allSays(bundled)) || held.equals(bundled))
			return 0;
		replace(held, bundled);
		return 1;
	}

	/** Every line the tree can speak, for the bootstrap's one question. */
	static List<String> allSays(JsonObject tree)
	{
		List<String> out = new ArrayList<>();
		var nodes = array(tree, "nodes");
		if (nodes != null)
		{
			for (var element : nodes)
			{
				if (element.isJsonObject())
					out.addAll(strings(element.getAsJsonObject(), "says"));
			}
		}
		return out;
	}

	DialogTree get(String id)
	{
		return id == null ? null : trees.get(id);
	}

	/** Every tree, in file order. */
	List<DialogTree> getTrees()
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
			problems.add("version is " + parsed.version
				+ ", plugin expects " + SUPPORTED_VERSION);

		Map<String, DialogTree> valid = new LinkedHashMap<>();
		for (var tree : parsed.trees == null
			? Collections.<DialogTree>emptyList() : parsed.trees)
		{
			if (tree == null)
				continue;
			var found = tree.problems();
			problems.addAll(found);
			// A broken tree is left out rather than half-loaded: a
			// conversation that dead-ends mid-branch is worse than one
			// that never opens, because the player is already in it.
			if (found.isEmpty() && valid.put(tree.id, tree) != null)
				problems.add("Duplicate tree id '" + tree.id + "'");
		}

		trees = valid;
		loaded(problems, valid.size() + " conversations");
	}

	static class DialogFile
	{
		int version;
		List<DialogTree> trees;
	}
}
