package com.follower;

import com.follower.speech.DialogLoader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The conversation trees have the same upgrade gap the rules had.
 *
 * <p>Appending only whole new trees meant a reworded node, an added choice or
 * a fixed dead end reached nobody who already had the plugin. The trees change
 * rarely, which is exactly why this would have gone unnoticed for a long time:
 * the bug only shows the day somebody finally edits one.
 *
 * <p>Trees merge at two levels, so these tests work at both - a tree's own
 * fields, and the nodes inside it.
 */
public class DialogMergeTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static JsonObject shipped()
	{
		try (InputStream in = DialogLoader.class
			.getResourceAsStream("/com/follower/default-dialogs.json"))
		{
			return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static JsonObject tree(JsonObject file, String id)
	{
		for (JsonElement e : file.getAsJsonArray("trees"))
		{
			if (id.equals(e.getAsJsonObject().get("id").getAsString()))
			{
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private static String firstTreeId(JsonObject file)
	{
		return file.getAsJsonArray("trees").get(0).getAsJsonObject().get("id").getAsString();
	}

	private static JsonObject node(JsonObject file, String treeId, String nodeId)
	{
		for (JsonElement e : tree(file, treeId).getAsJsonArray("nodes"))
		{
			if (nodeId.equals(e.getAsJsonObject().get("id").getAsString()))
			{
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private static String firstNodeId(JsonObject file, String treeId)
	{
		return tree(file, treeId).getAsJsonArray("nodes").get(0)
			.getAsJsonObject().get("id").getAsString();
	}

	private static List<String> says(JsonObject node)
	{
		List<String> out = new ArrayList<>();
		for (JsonElement e : node.getAsJsonArray("says"))
		{
			out.add(e.getAsString());
		}
		return out;
	}

	private static void setSays(JsonObject node, List<String> lines)
	{
		JsonArray says = new JsonArray();
		lines.forEach(says::add);
		node.add("says", says);
	}

	/**
	 * A previous version: the first node of the first tree short of its last
	 * line. Built from the shipped file so everything else is genuinely real.
	 */
	private static JsonObject previousVersion()
	{
		JsonObject old = shipped();
		String t = firstTreeId(old);
		JsonObject n = node(old, t, firstNodeId(old, t));
		List<String> fewer = new ArrayList<>(says(n));
		fewer.remove(fewer.size() - 1);
		setSays(n, fewer);
		return old;
	}

	private JsonObject merge(JsonObject theirs, JsonObject base) throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(DialogLoader.FILE_NAME),
			GSON.toJson(theirs).getBytes(StandardCharsets.UTF_8));
		if (base != null)
		{
			Files.write(dir.resolve(DialogLoader.BASE_FILE),
				GSON.toJson(base).getBytes(StandardCharsets.UTF_8));
		}
		new DialogLoader(GSON).initialise(dir, true);
		return GSON.fromJson(new String(
			Files.readAllBytes(dir.resolve(DialogLoader.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
	}

	@Test
	public void aRewordedNodeReachesAnExistingInstall() throws Exception
	{
		JsonObject merged = merge(previousVersion(), previousVersion());

		String t = firstTreeId(merged);
		assertEquals("the line the update added arrives",
			says(node(shipped(), t, firstNodeId(shipped(), t))),
			says(node(merged, t, firstNodeId(merged, t))));
	}

	@Test
	public void aLineThePlayerDeletedFromANodeStaysDeleted() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		String t = firstTreeId(theirs);
		JsonObject n = node(theirs, t, firstNodeId(theirs, t));
		List<String> without = new ArrayList<>(says(n));
		String removed = without.remove(0);
		setSays(n, without);

		JsonObject merged = merge(theirs, base);

		assertFalse("their deletion is a decision",
			says(node(merged, t, firstNodeId(merged, t))).contains(removed));
	}

	@Test
	public void aNodeThePlayerRewroteIsTheirs() throws Exception
	{
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		String t = firstTreeId(theirs);
		String id = firstNodeId(theirs, t);
		setSays(node(theirs, t, id), java.util.Arrays.asList("My own opening."));

		JsonObject merged = merge(theirs, base);

		assertTrue("what they wrote survives",
			says(node(merged, t, id)).contains("My own opening."));
	}

	@Test
	public void aNodeTheUpdateAddedArrives() throws Exception
	{
		// Base and theirs both lack a node the shipped file has.
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		String t = firstTreeId(theirs);
		String doomed = tree(shipped(), t).getAsJsonArray("nodes")
			.get(1).getAsJsonObject().get("id").getAsString();
		for (JsonObject file : new JsonObject[]{base, theirs})
		{
			JsonArray kept = new JsonArray();
			for (JsonElement e : tree(file, t).getAsJsonArray("nodes"))
			{
				if (!doomed.equals(e.getAsJsonObject().get("id").getAsString()))
				{
					kept.add(e);
				}
			}
			tree(file, t).add("nodes", kept);
		}

		JsonObject merged = merge(theirs, base);

		assertNotNull("a node the update added arrives", node(merged, t, doomed));
	}

	@Test
	public void aNodeThePlayerDeletedStaysDeleted() throws Exception
	{
		// The base HAS the node, so its absence from theirs is a deletion.
		JsonObject base = previousVersion();
		JsonObject theirs = previousVersion();
		String t = firstTreeId(theirs);
		String gone = tree(theirs, t).getAsJsonArray("nodes")
			.get(1).getAsJsonObject().get("id").getAsString();
		JsonArray kept = new JsonArray();
		for (JsonElement e : tree(theirs, t).getAsJsonArray("nodes"))
		{
			if (!gone.equals(e.getAsJsonObject().get("id").getAsString()))
			{
				kept.add(e);
			}
		}
		tree(theirs, t).add("nodes", kept);

		JsonObject merged = merge(theirs, base);

		assertNull("a node they removed is not put back", node(merged, t, gone));
	}

	@Test
	public void mergingTwiceChangesNothingTheSecondTime() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(DialogLoader.FILE_NAME),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve(DialogLoader.BASE_FILE),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));

		new DialogLoader(GSON).initialise(dir, true);
		String once = new String(Files.readAllBytes(dir.resolve(DialogLoader.FILE_NAME)),
			StandardCharsets.UTF_8);
		new DialogLoader(GSON).initialise(dir, true);
		String twice = new String(Files.readAllBytes(dir.resolve(DialogLoader.FILE_NAME)),
			StandardCharsets.UTF_8);

		assertEquals(once, twice);
	}

	@Test
	public void withNoBaseAnUntouchedTreeIsBroughtUpToDate() throws Exception
	{
		JsonObject merged = merge(previousVersion(), null);

		String t = firstTreeId(merged);
		assertEquals("a tree nobody edited is brought up to date",
			says(node(shipped(), t, firstNodeId(shipped(), t))),
			says(node(merged, t, firstNodeId(merged, t))));
	}

	@Test
	public void withNoBaseATreeThePlayerWroteIntoIsLeftAlone() throws Exception
	{
		JsonObject theirs = previousVersion();
		String t = firstTreeId(theirs);
		String id = firstNodeId(theirs, t);
		List<String> plus = new ArrayList<>(says(node(theirs, t, id)));
		plus.add("A line of my own.");
		setSays(node(theirs, t, id), plus);

		JsonObject merged = merge(theirs, null);

		assertEquals("without a base, their tree is not ours to touch",
			plus, says(node(merged, t, id)));
	}

	@Test
	public void theBaseIsRecordedSoTheGuessworkHappensOnlyOnce() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(DialogLoader.FILE_NAME),
			GSON.toJson(previousVersion()).getBytes(StandardCharsets.UTF_8));
		assertFalse(Files.exists(dir.resolve(DialogLoader.BASE_FILE)));

		new DialogLoader(GSON).initialise(dir, true);

		assertTrue(Files.exists(dir.resolve(DialogLoader.BASE_FILE)));
	}
}
