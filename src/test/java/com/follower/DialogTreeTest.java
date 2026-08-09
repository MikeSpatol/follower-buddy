package com.follower;

import com.follower.speech.DialogLoader;
import com.follower.speech.DialogTree;
import com.follower.speech.FollowerDialog;
import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechRule;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The conversations the follower opens, which are data rather than code.
 *
 * <p>A dialog fails in the worst way available: the box opens and shuts again,
 * or a branch dead-ends on a node id with a typo in it, and the player is
 * already inside it when that happens. Nothing about that shows up as an error
 * at runtime, so every structural check is made once at load and again here.
 */
public class DialogTreeTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private DialogLoader loaded() throws IOException
	{
		DialogLoader loader = new DialogLoader(new Gson());
		loader.initialise(folder.newFolder().toPath());
		return loader;
	}

	private DialogLoader loadedFrom(String json) throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(DialogLoader.FILE_NAME), json.getBytes(StandardCharsets.UTF_8));
		DialogLoader loader = new DialogLoader(new Gson());
		loader.initialise(dir);
		return loader;
	}

	@Test
	public void theShippedTreesLoadWithoutComplaint() throws IOException
	{
		DialogLoader loader = loaded();
		assertTrue("the trees the plugin ships must load clean: " + loader.getErrors(),
			loader.getErrors().isEmpty());
		assertFalse("and there has to be at least one", loader.getTrees().isEmpty());
	}

	@Test
	public void everyBranchLeadsSomewhereReal() throws IOException
	{
		// The failure this exists for: a branch pointing at a node id that does
		// not exist. The conversation simply stops, mid-sentence, with the
		// player looking at a closed box wondering what they did wrong.
		DialogLoader loader = loaded();
		for (DialogTree tree : loader.getTrees())
		{
			assertEquals(tree.id + " has structural problems", 0, tree.problems().size());
		}
	}

	@Test
	public void aTreeWithADeadBranchIsRefusedRatherThanHalfLoaded() throws IOException
	{
		// Everything else about this tree is fine - it answers, it starts
		// somewhere real - so the ONLY thing wrong is the branch pointing at a
		// node called "nowhere". Anything else here and the test would pass on
		// the strength of a different check.
		DialogLoader loader = loadedFrom("{\"version\": 1, \"trees\": [{"
			+ "\"id\": \"broken\", \"start\": \"a\", \"nodes\": ["
			+ "{\"id\": \"a\", \"says\": [\"hello\"], \"choices\": ["
			+ "{\"label\": \"fine\", \"next\": \"b\"},"
			+ "{\"label\": \"onwards\", \"next\": \"nowhere\"}]},"
			+ "{\"id\": \"b\", \"says\": [\"right\"], \"answer\": \"yes\"}]}]}");

		assertNull("a tree that can dead-end must not be offered at all",
			loader.get("broken"));
		assertTrue("and the reason has to name the branch: " + loader.getErrors(),
			loader.getErrors().toString().contains("nowhere"));
	}

	@Test
	public void aTreeNobodyCanAnswerIsRefused() throws IOException
	{
		// These trees exist to collect an answer. One with no answer in it is a
		// conversation that wastes the single moment the follower had.
		DialogLoader loader = loadedFrom("{\"version\": 1, \"trees\": [{"
			+ "\"id\": \"pointless\", \"start\": \"a\", \"nodes\": ["
			+ "{\"id\": \"a\", \"says\": [\"nice weather\"]}]}]}");

		assertNull(loader.get("pointless"));
	}

	@Test
	public void aBadReadKeepsTheTreesItAlreadyHad() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		DialogLoader loader = new DialogLoader(new Gson());
		loader.initialise(dir);
		int before = loader.getTrees().size();
		assertTrue("start from something", before > 0);

		// What a poll sees between an editor truncating the file and writing it
		// back. Losing every conversation over that would be absurd.
		Files.write(dir.resolve(DialogLoader.FILE_NAME), new byte[0]);
		loader.reload();
		assertEquals("an empty read must not wipe the trees",
			before, loader.getTrees().size());

		Files.write(dir.resolve(DialogLoader.FILE_NAME),
			"{ not json at all".getBytes(StandardCharsets.UTF_8));
		loader.reload();
		assertEquals("nor must a syntax error", before, loader.getTrees().size());
		assertFalse(loader.getErrors().isEmpty());
	}

	@Test
	public void buildingATreeCarriesTheChoicesAndTheAnswerAcross() throws IOException
	{
		DialogLoader loader = loaded();
		DialogTree tree = loader.get("want-outing");
		assertNotNull("the shipped question tree", tree);

		List<String> answers = new ArrayList<>();
		Map<String, FollowerDialog.Node> script =
			FollowerDialog.build(tree, answers::add);

		assertTrue("every node has to survive the build",
			script.keySet().containsAll(tree.byId().keySet()));
		assertNotNull("and it has to start somewhere", script.get(tree.startId()));

		// Both answers have to be reachable, or the player has been offered a
		// question they can only answer one way.
		Set<String> reachable = new HashSet<>();
		for (DialogTree.DialogNode node : tree.nodes)
		{
			if (node.answer != null)
			{
				reachable.add(node.answer.toLowerCase(java.util.Locale.ROOT));
			}
		}
		assertTrue("yes has to be sayable", reachable.contains("yes"));
		assertTrue("and so does no", reachable.contains("no"));
	}

	@Test
	public void whatTheEditorWritesIsWhatTheLoaderReads() throws IOException
	{
		// The editor rebuilds the whole file from its own model and writes it
		// back. If that model has drifted from the loader's - a renamed field,
		// a list where an object was - the save silently produces a file that
		// no longer loads, and the first anyone knows is an empty Talk-to.
		DialogLoader first = loaded();
		int before = first.getTrees().size();

		Gson pretty = new Gson().newBuilder().setPrettyPrinting()
			.disableHtmlEscaping().create();
		EditorShape shape = new EditorShape();
		shape.version = 1;
		shape.trees = first.getTrees();

		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(DialogLoader.FILE_NAME),
			pretty.toJson(shape).getBytes(StandardCharsets.UTF_8));

		DialogLoader second = new DialogLoader(new Gson());
		second.initialise(dir);
		assertTrue("a round trip through the editor's shape must load clean: "
			+ second.getErrors(), second.getErrors().isEmpty());
		assertEquals("and keep every tree", before, second.getTrees().size());

		DialogTree tree = second.get("want-outing");
		assertNotNull("including the one that answers the question", tree);
		boolean playerSpeaks = false;
		for (DialogTree.DialogNode node : tree.nodes)
		{
			playerSpeaks |= node.isPlayerSpeaking();
		}
		assertTrue("and who speaks each line has to survive it", playerSpeaks);
	}

	/** The shape {@code DialogsDialog} serialises; kept in step by the test above. */
	private static final class EditorShape
	{
		int version;
		List<DialogTree> trees;
	}

	@Test
	public void everyAskedTreeIsOneThatExists() throws IOException
	{
		// A rule naming a tree that is not there falls back to the everyday
		// script, so the follower asks a question and then has nothing to say
		// about it when you come over - which looks exactly like a bug.
		DialogLoader dialogs = loaded();
		RuleLoader rules = new RuleLoader(new Gson());
		rules.initialise(folder.newFolder().toPath());

		int asking = 0;
		for (SpeechRule rule : rules.getRules())
		{
			if (rule.asks == null || rule.asks.isEmpty())
			{
				continue;
			}
			asking++;
			assertNotNull(rule.id + " asks via '" + rule.asks
				+ "', which is not a tree in dialogs.json", dialogs.get(rule.asks));
		}
		assertTrue("something has to be asking, or none of this runs", asking > 0);
	}
}
