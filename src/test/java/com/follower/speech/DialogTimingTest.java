package com.follower.speech;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * WHEN a conversation delivers its answer.
 *
 * <p>Lives in the engine's own package because the seams it uses are: reaching
 * a node the real way needs a follower to face the player and a client to
 * compose a chathead from, and neither exists headlessly. The moment the answer
 * lands is worth pinning down on its own anyway - it is not a detail of
 * rendering, it is the difference between hearing the reply and missing it.
 */
public class DialogTimingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private DialogTree shippedTree() throws IOException
	{
		DialogLoader loader = new DialogLoader(new Gson());
		loader.initialise(folder.newFolder().toPath());
		DialogTree tree = loader.get("want-outing");
		assertNotNull("the shipped question tree", tree);
		return tree;
	}

	private static String answeringNode(DialogTree tree)
	{
		for (DialogTree.DialogNode node : tree.nodes)
		{
			if (node.answer != null)
			{
				return node.id;
			}
		}
		throw new AssertionError("the tree has no answering node");
	}

	@Test
	public void theAnswerIsDeliveredWhenTheBoxCloses() throws IOException
	{
		// The follower's reply to its own question goes OVERHEAD. Delivering
		// the answer the instant the branch is picked puts that line above a
		// dialog box the player is still reading, so the one thing they were
		// waiting to hear is the one thing they miss.
		DialogTree tree = shippedTree();
		List<String> answers = new ArrayList<>();
		Map<String, FollowerDialog.Node> script = FollowerDialog.build(tree, answers::add);

		FollowerDialog dialog = new FollowerDialog(null, null, null, null, null, null, null, null);
		dialog.openForTest(script);

		dialog.reachForTest(answeringNode(tree));
		assertTrue("picking the branch must not deliver it yet", answers.isEmpty());

		dialog.close();
		assertEquals("closing the box is what delivers it", 1, answers.size());

		dialog.close();
		assertEquals("and it must not arrive twice", 1, answers.size());
	}

	@Test
	public void closingEarlyStillCounts() throws IOException
	{
		// Walking off, or being interrupted, after picking an answer. The
		// player answered; whether they read the rest of it is their business,
		// and a follower that forgot the answer because the box was dismissed
		// would look like it had not been listening.
		DialogTree tree = shippedTree();
		List<String> answers = new ArrayList<>();
		FollowerDialog dialog = new FollowerDialog(null, null, null, null, null, null, null, null);
		dialog.openForTest(FollowerDialog.build(tree, answers::add));

		dialog.reachForTest(answeringNode(tree));
		dialog.close();
		assertEquals(1, answers.size());
	}

	@Test
	public void aFreshConversationInheritsNoAnswerFromTheLastOne() throws IOException
	{
		DialogTree tree = shippedTree();
		List<String> answers = new ArrayList<>();
		Map<String, FollowerDialog.Node> script = FollowerDialog.build(tree, answers::add);

		FollowerDialog dialog = new FollowerDialog(null, null, null, null, null, null, null, null);
		dialog.openForTest(script);
		dialog.reachForTest(answeringNode(tree));

		// Talked to again before the first one ended. A latch carried across
		// would answer a question that is no longer being asked.
		dialog.openForTest(script);
		dialog.close();
		assertTrue("a new conversation must not carry the old latch", answers.isEmpty());
	}

	@Test
	public void aConversationNobodyAnsweredDeliversNothing() throws IOException
	{
		DialogTree tree = shippedTree();
		List<String> answers = new ArrayList<>();
		FollowerDialog dialog = new FollowerDialog(null, null, null, null, null, null, null, null);
		dialog.openForTest(FollowerDialog.build(tree, answers::add));

		dialog.reachForTest(tree.startId());
		dialog.close();
		assertTrue("opening and shutting the box is not an answer", answers.isEmpty());
	}
}
