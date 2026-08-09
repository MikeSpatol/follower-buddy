package com.follower.speech;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A conversation, as data.
 *
 * <p>The follower's everyday talk script is written in Java, which is fine for
 * something that never changes. These are different: they are the conversations
 * the follower STARTS, they replace the everyday one while they are open, and
 * they are where the player's answer to a question actually comes from. That
 * makes them content rather than plumbing, and content belongs in a file
 * somebody can edit without a compiler.
 *
 * <p>Nodes are a list rather than a map so their order survives a round trip
 * through the file, which is what lets the editor show them as a table in the
 * order they were written rather than in whatever order a hash gives back.
 */
public class DialogTree
{
	public String id;

	/** Free text, ignored by the plugin. */
	public String note;

	/** Which node the conversation opens on. Defaults to the first one. */
	public String start;

	public List<DialogNode> nodes;

	public static class DialogNode
	{
		public String id;

		/** Pages the FOLLOWER speaks, with its chathead. */
		public List<String> says;

		/** Pages the PLAYER speaks, with theirs. */
		public List<String> you;

		/** Where to go once the pages are done. Absent ends the conversation. */
		public String next;

		public List<DialogChoice> choices;

		/**
		 * Reaching this node answers the question the follower asked.
		 *
		 * <p>"yes" or "no". This is the whole reason these trees exist: the
		 * answer used to be a word typed into public chat, which meant the
		 * follower was listening to the whole street and the player had to know
		 * the magic word. Picking an option cannot be misheard, cannot be
		 * ambiguous, and shows the player what their choices are.
		 */
		public String answer;

		public List<String> pages()
		{
			List<String> spoken = you != null && !you.isEmpty() ? you : says;
			return spoken == null ? new ArrayList<>() : spoken;
		}

		public boolean isPlayerSpeaking()
		{
			return you != null && !you.isEmpty();
		}
	}

	public static class DialogChoice
	{
		public String label;
		public String next;
	}

	/**
	 * Every problem that would stop this tree working, as sentences.
	 *
	 * <p>A dialog fails silently in the worst possible way - the box opens and
	 * closes again, or a branch dead-ends on a node id with a typo in it - so
	 * the checks are done once at load and reported, rather than discovered by
	 * a player halfway through a conversation.
	 */
	public List<String> problems()
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
		for (DialogNode node : nodes)
		{
			if (node == null || node.id == null || node.id.trim().isEmpty())
			{
				found.add(id + ": a node with no id");
				continue;
			}
			if (byId.put(node.id, node) != null)
			{
				found.add(id + ": two nodes called '" + node.id + "'");
			}
		}

		String entry = startId();
		if (entry == null || !byId.containsKey(entry))
		{
			found.add(id + ": starts at '" + entry + "', which is not a node");
		}

		for (DialogNode node : byId.values())
		{
			if (node.next != null && !byId.containsKey(node.next))
			{
				found.add(id + ": '" + node.id + "' continues to '" + node.next
					+ "', which is not a node");
			}
			if (node.choices != null)
			{
				for (DialogChoice choice : node.choices)
				{
					if (choice == null || choice.label == null || choice.label.trim().isEmpty())
					{
						found.add(id + ": '" + node.id + "' has a choice with no label");
						continue;
					}
					if (choice.next == null || !byId.containsKey(choice.next))
					{
						found.add(id + ": '" + node.id + "' offers \"" + choice.label
							+ "\" leading to '" + choice.next + "', which is not a node");
					}
				}
			}
			if (node.answer != null
				&& !"yes".equalsIgnoreCase(node.answer) && !"no".equalsIgnoreCase(node.answer))
			{
				found.add(id + ": '" + node.id + "' answers '" + node.answer
					+ "'; only yes and no mean anything");
			}
			if (node.pages().isEmpty() && node.choices == null && node.next == null)
			{
				found.add(id + ": '" + node.id + "' says nothing and goes nowhere");
			}
		}

		// A tree the follower opens to ask something, that the player cannot
		// answer, is a tree that wastes the one moment it had.
		boolean answerable = false;
		for (DialogNode node : byId.values())
		{
			if (node.answer != null)
			{
				answerable = true;
				break;
			}
		}
		if (!answerable)
		{
			found.add(id + ": no node answers anything, so nothing can come of it");
		}

		return found;
	}

	public String startId()
	{
		if (start != null && !start.trim().isEmpty())
		{
			return start;
		}
		return nodes == null || nodes.isEmpty() || nodes.get(0) == null
			? null : nodes.get(0).id;
	}

	public Map<String, DialogNode> byId()
	{
		Map<String, DialogNode> map = new LinkedHashMap<>();
		if (nodes != null)
		{
			for (DialogNode node : nodes)
			{
				if (node != null && node.id != null)
				{
					map.put(node.id, node);
				}
			}
		}
		return map;
	}
}
