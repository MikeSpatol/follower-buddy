package com.follower;

import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * commands.tsv holds the everyday commands' help, which the help printer and
 * the side panel's Commands section both list, so a slip in it must fail
 * here. The list as the code built it before it moved there is kept below as
 * it stood: the file must give the same pairs in the same order.
 */
public class CommandsFileTest
{
	// public:cut errands
	private static final String LISTED_ERRAND_KEYS = listedErrandKeys();

	private static String listedErrandKeys()
	{
		try
		{
			Field field = FollowerPlugin.class.getDeclaredField("LISTED_ERRAND_KEYS");
			field.setAccessible(true);
			return (String) field.get(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	// public:end errands
	private static java.util.Map.Entry<String, String> command(String name, String effect)
	{
		return new java.util.AbstractMap.SimpleImmutableEntry<>(name, effect);
	}

	/** FollowerPlugin.EVERYDAY_COMMANDS before it moved, verbatim. */
	private static java.util.List<java.util.Map.Entry<String, String>> before()
	{
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			command("say <text>", "make the follower say it"),
			command("here", "place the follower at your feet"),
			command("fix", "re-attach it if it vanished"),
			command("rebuild", "rebuild its model from the outfit"),
			command("copy", "dress it in your gear (also a panel button)"),
			command("outfit <name>", "wear a saved profile (also in the panel)"), // public:cut outfit-profiles
			// public:cut posing-preview
			command("anim <id...>", "preview an animation on it"),
			command("gfx <id> [height]", "preview a graphic on it, that far up its body"),
			command("pose <id>", "hold a looping pose, 0 to release"),
			// public:end posing-preview
			command("posing", "the Advanced posing window: pose it, give it lines, or play out a scene"), // public:cut posing
			command("sketch", "pose for the chronicle's sketch of this place, as right-click Sketch"), // public:cut chronicle
			command("height <n>", "lift or lower it"),
			// public:cut errands
			// Spaced, so the list wraps between keys rather than mid-word.
			command("errand [" + LISTED_ERRAND_KEYS.replace("|", " | ") + "]", "send it on an errand now"),
			// public:end errands
			command("reload", "re-read the phrase and sound files"), // public:cut sharevoice
			// public:else command("reload", "re-read the phrase files"),
			command("status", "models, rules and spawn state"),
			command("where", "your region id, for Locations in the Messages window")));
	}

	@Test
	public void theFileListsWhatTheCodeListed()
	{
		assertEquals(before(), FollowerPlugin.EVERYDAY_COMMANDS);
	}
}
