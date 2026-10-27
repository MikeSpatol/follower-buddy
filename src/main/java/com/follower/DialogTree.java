package com.follower;

import java.util.*;
import java.util.List;
import java.util.Map;

/**
 * A conversation, as data.
 *
 * <p>The follower's everyday talk script is bundled (talk.json) and never
 * changes, so it is no one's to edit. These are different: they are the conversations
 * the follower STARTS, they replace the everyday one while they are open, and
 * they are where the player's answer to a question actually comes from. That
 * makes them content rather than plumbing, and content belongs in a file
 * somebody can edit without a compiler.
 *
 * <p>Nodes are a list rather than a map so their order survives a round trip
 * through the file, which is what lets the editor show them as a table in the
 * order they were written rather than in whatever order a hash gives back.
 */
class DialogTree
{
	String id;

	/** Free text, ignored by the plugin. */
	String note;

	/** Which node the conversation opens on. Defaults to the first one. */
	String start;

	List<DialogNode> nodes;

	static class DialogNode
	{
		String id;

		/** Pages the FOLLOWER speaks, with its chathead. */
		List<String> says;

		/** Pages the PLAYER speaks, with theirs. */
		List<String> you;

		/** Where to go once the pages are done. Absent ends the conversation. */
		String next;

		List<DialogChoice> choices;

		/**
		 * Reaching this node answers the question the follower asked.
		 *
		 * <p>Any single plain word; {@code answered} rules match on it. This is
		 * the whole reason these trees exist: the answer used to be a word
		 * typed into public chat, which meant the follower was listening to the
		 * whole street and the player had to know the magic word. Picking an
		 * option cannot be misheard, cannot be ambiguous, and shows the player
		 * what their choices are - which is also why the vocabulary is not
		 * limited to yes and no. A menu offering "left" and "right" is exactly
		 * as unmistakable as one offering yes and no, and it can be a game.
		 */
		String answer;

		List<String> pages()
		{
			List<String> spoken = you != null && !you.isEmpty() ? you : says;
			return spoken == null ? new ArrayList<>() : spoken;
		}

		boolean isPlayerSpeaking()
		{
			return you != null && !you.isEmpty();
		}
	}

	static class DialogChoice
	{
		String label,
			next;
	}

	/**
	 * Every problem that would stop this tree working, as sentences.
	 *
	 * <p>A dialog fails silently in the worst possible way - the box opens and
	 * closes again, or a branch dead-ends on a node id with a typo in it - so
	 * the checks are done once at load and reported, rather than discovered by
	 * a player halfway through a conversation.
	 */
	List<String> problems()
	{
		List<String> found = new ArrayList<>();
		if (id == null || id.trim().isEmpty())
		{
			found.add("a tree with no id");
			return found;
		}
		if (nodes == null || nodes.isEmpty())
		{
			found.add(id + ": no nodes");
			return found;
		}

		Map<String, DialogNode> byId = new LinkedHashMap<>();
		for (var node : nodes)
		{
			if (node == null || node.id == null || node.id.trim().isEmpty())
			{
				found.add(id + ": a node with no id");
				continue;
			}
			if (byId.put(node.id, node) != null)
				found.add(id + ": two nodes called '" + node.id + "'");
		}

		String entry = startId();
		if (entry == null || !byId.containsKey(entry))
			found.add(id + ": starts at '" + entry + "', which is not a node");

		for (var node : byId.values())
		{
			if (node.next != null && !byId.containsKey(node.next))
				found.add(id + ": '" + node.id + "' continues to '" + node.next
					+ "', which is not a node");
			if (node.choices != null)
			{
				for (var choice : node.choices)
				{
					if (choice == null || choice.label == null || choice.label.trim().isEmpty())
					{
						found.add(id + ": '" + node.id + "' has a choice with no label");
						continue;
					}
					if (choice.next == null || !byId.containsKey(choice.next))
						found.add(id + ": '" + node.id + "' offers \"" + choice.label
							+ "\" leading to '" + choice.next + "', which is not a node");
				}
			}
			if (node.answer != null && !node.answer.matches("[a-zA-Z0-9-]+"))
				found.add(id + ": '" + node.id + "' answers '" + node.answer
					+ Resources.text("answerOneWord"));
			if (node.pages().isEmpty() && node.choices == null && node.next == null)
				found.add(id + ": '" + node.id + "' says nothing and goes nowhere");
		}

		// A tree the follower opens to ask something, that the player cannot
		// answer, is a tree that wastes the one moment it had.
		boolean answerable = false;
		for (var node : byId.values())
		{
			if (node.answer != null)
			{
				answerable = true;
				break;
			}
		}
		if (!answerable)
			found.add(id + Resources.text("noAnswers"));

		return found;
	}

	String startId()
	{
		if (start != null && !start.trim().isEmpty())
			return start;
		return nodes == null || nodes.isEmpty() || nodes.get(0) == null
			? null : nodes.get(0).id;
	}

	Map<String, DialogNode> byId()
	{
		Map<String, DialogNode> map = new LinkedHashMap<>();
		if (nodes != null)
		{
			for (var node : nodes)
			{
				if (node != null && node.id != null)
					map.put(node.id, node);
			}
		}
		return map;
	}
}
