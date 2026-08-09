package com.follower;

import com.follower.speech.FollowerDialog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The everyday Talk-to conversation.
 *
 * <p>It is the only speech in the plugin that does not live in phrases.json,
 * and until now it was the only speech nothing checked. Every failure the rule
 * file is guarded against applies here too, and worse: a branch pointing at a
 * node that does not exist shuts the box in the player's face mid-sentence,
 * and a character the game has no glyph for renders as a different character
 * entirely rather than as nothing.
 */
public class TalkScriptTest
{
	private static Map<String, FollowerDialog.Node> script()
	{
		return FollowerPlugin.talkScript();
	}

	/** Every node id something points at, via {@code then} or a choice. */
	private static Set<String> targets(Map<String, FollowerDialog.Node> script)
	{
		Set<String> found = new TreeSet<>();
		for (FollowerDialog.Node node : script.values())
		{
			found.addAll(node.getTargets());
		}
		return found;
	}

	@Test
	public void everyBranchLeadsToANodeThatExists()
	{
		Map<String, FollowerDialog.Node> script = script();
		Set<String> missing = new TreeSet<>(targets(script));
		missing.removeAll(script.keySet());
		assertTrue("branches pointing at nodes that do not exist, which shut the"
			+ " box mid-conversation: " + missing, missing.isEmpty());
	}

	@Test
	public void everyNodeCanBeReached()
	{
		// An unreachable node is dialogue nobody will ever read. Usually it
		// means a branch was repointed and its old destination left behind.
		Map<String, FollowerDialog.Node> script = script();
		Set<String> reachable = new HashSet<>(targets(script));
		reachable.add("start");

		Set<String> orphans = new TreeSet<>(script.keySet());
		orphans.removeAll(reachable);
		assertTrue("nodes nothing leads to: " + orphans, orphans.isEmpty());
	}

	@Test
	public void everyLineHasAGlyphInBothGameFonts() throws IOException
	{
		// Same check the rule file gets, and for the same reason: GameFont
		// indexes with charAt(i) & 0xFF, so anything above U+00FF is truncated
		// and drawn as whatever lives at that index. The line does not come out
		// blank, it comes out wrong, and nothing reports it.
		JsonObject fonts = bundled("/com/follower/fonts.json");
		JsonObject overhead = fontById(fonts, 496);
		JsonObject dialog = fontById(fonts, 497);

		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, FollowerDialog.Node> entry : script().entrySet())
		{
			for (String line : entry.getValue().getLines())
			{
				for (char c : line.toCharArray())
				{
					if (c == ' ')
					{
						continue;
					}
					if (!hasGlyph(overhead, c) || !hasGlyph(dialog, c))
					{
						problems.add(String.format("%s: '%c' (U+%04X) in %s",
							entry.getKey(), c, (int) c, line));
					}
				}
			}
		}
		assertTrue("lines the game cannot draw:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void nothingRunsOnTooLongToRead()
	{
		// The dialog body is a 380px column of three lines. A page much past
		// this is either cut off or crushes the whole conversation into a wall,
		// and a follower that monologues stops sounding like an NPC.
		List<String> tooLong = new ArrayList<>();
		for (Map.Entry<String, FollowerDialog.Node> entry : script().entrySet())
		{
			for (String line : entry.getValue().getLines())
			{
				if (line.length() > 110)
				{
					tooLong.add(entry.getKey() + " (" + line.length() + "): " + line);
				}
			}
		}
		assertTrue("pages too long for the box:\n  "
			+ String.join("\n  ", tooLong), tooLong.isEmpty());
	}

	@Test
	public void theConversationCanAlwaysBeEndedFromAnywhere()
	{
		// The property that actually matters is not that every menu has a
		// "back" on it - the joke loop escapes two hops away, through the
		// groan - but that no node strands the player. From anywhere, some
		// sequence of clicks has to reach an ending.
		Map<String, FollowerDialog.Node> script = script();
		List<String> trapped = new ArrayList<>();
		for (String from : script.keySet())
		{
			if (!canEnd(script, from))
			{
				trapped.add(from);
			}
		}
		assertTrue("nodes the player cannot get out of: " + trapped, trapped.isEmpty());
	}

	/** Whether any path from here reaches a node that closes the box. */
	private static boolean canEnd(Map<String, FollowerDialog.Node> script, String from)
	{
		Set<String> seen = new HashSet<>();
		java.util.Deque<String> queue = new java.util.ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty())
		{
			String id = queue.poll();
			if (!seen.add(id))
			{
				continue;
			}
			FollowerDialog.Node node = script.get(id);
			if (node == null)
			{
				continue;
			}
			List<String> next = node.getTargets();
			if (next.isEmpty())
			{
				// Nothing to continue to: the pages run out and the box shuts.
				return true;
			}
			queue.addAll(next);
		}
		return false;
	}

	@Test
	public void theFollowerTalksAboutWhatItCanActuallyDo()
	{
		// The script drifted a whole feature set behind once already: it still
		// described a follower that only walked, stayed and danced, long after
		// it had a memory, a mood, tastes and things it wanted. Nothing catches
		// that automatically - this at least catches it going backwards.
		StringBuilder all = new StringBuilder();
		for (FollowerDialog.Node node : script().values())
		{
			for (String line : node.getLines())
			{
				all.append(line).append(' ');
			}
		}
		String text = all.toString().toLowerCase(java.util.Locale.ROOT);

		assertTrue("nothing about counting or remembering", text.contains("count"));
		assertTrue("nothing about having moods", text.contains("mood"));
		assertTrue("nothing about places it likes", text.contains("places i like"));
		assertTrue("nothing about asking to be taken somewhere",
			text.contains("take me somewhere"));
		assertTrue("nothing about giving you room while thieving",
			text.contains("pockets"));
		assertFalse("the old Whitman line is not how an NPC talks",
			text.contains("contain multitudes"));
	}

	// ------------------------------------------------------------- plumbing

	private static JsonObject bundled(String resource) throws IOException
	{
		try (InputStream in = TalkScriptTest.class.getResourceAsStream(resource))
		{
			assertTrue("missing bundled resource " + resource, in != null);
			return new JsonParser().parse(
				new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static JsonObject fontById(JsonObject fonts, int id)
	{
		for (com.google.gson.JsonElement element : fonts.getAsJsonArray("fonts"))
		{
			JsonObject font = element.getAsJsonObject();
			if (font.get("id").getAsInt() == id)
			{
				return font;
			}
		}
		throw new AssertionError("no font " + id + " in the bundled dump");
	}

	private static boolean hasGlyph(JsonObject font, char c)
	{
		// Anything past Latin-1 would be truncated to a different character.
		if (c > 0xFF)
		{
			return false;
		}
		// Exactly what GameFont.drawTop does to choose a glyph.
		JsonObject glyph = font.getAsJsonArray("glyphs").get(c & 0xFF).getAsJsonObject();
		com.google.gson.JsonElement mask = glyph.get("mask");
		return glyph.get("w").getAsInt() > 0
			&& mask != null && !mask.isJsonNull() && !mask.getAsString().isEmpty();
	}
}
