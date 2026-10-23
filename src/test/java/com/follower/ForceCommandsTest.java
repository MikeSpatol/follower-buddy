// public:file dev
package com.follower;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The force-paths are one table, and everything that names them reads it or
 * is held to it: the dispatch, the developer help and the diagnostics table
 * in docs/DEVELOPMENT.md. A force added to the table and not wired up, or
 * wired up and never written down, is a force-path nobody can find, which is
 * the failure the family exists to end.
 */
public class ForceCommandsTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");
	private static final Path DOCS = Paths.get("docs/DEVELOPMENT.md");

	private static String read(Path path) throws IOException
	{
		assertTrue("cannot read " + path, Files.isRegularFile(path));
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/** Each force's name, the first word of its usage. */
	private static List<String> names()
	{
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, String> entry : FollowerPlugin.FORCE_COMMANDS)
		{
			names.add(entry.getKey().split(" ")[0]);
		}
		assertFalse("no forces at all - the table broke, not the lint", names.isEmpty());
		return names;
	}

	@Test
	public void everyForceIsDispatchedAndEveryDispatchIsListed() throws IOException
	{
		String source = read(PLUGIN);
		int start = source.indexOf("String forceNow(String[] args)");
		assertTrue("the plugin has no forceNow - the lint has lost its place", start >= 0);
		String dispatch = source.substring(start, source.indexOf("\n\t}\n", start));
		Set<String> cases = new TreeSet<>();
		Matcher found = Pattern.compile("\n\t\t\tcase \"([a-z]+)\":").matcher(dispatch);
		while (found.find())
		{
			cases.add(found.group(1));
		}
		assertEquals("the force table and forceNow's cases", new TreeSet<>(names()), cases);
	}

	@Test
	public void forceIsADeveloperCommandAndInItsHelp() throws IOException, ReflectiveOperationException
	{
		Field gated = FollowerPlugin.class.getDeclaredField("DEVELOPER_COMMANDS");
		gated.setAccessible(true);
		assertTrue("gated like fire and loot", ((Set<?>) gated.get(null)).contains("force"));

		Field help = FollowerPlugin.class.getDeclaredField("DEVELOPER_COMMAND_HELP");
		help.setAccessible(true);
		String listed = null;
		for (Object entry : (List<?>) help.get(null))
		{
			Map.Entry<?, ?> command = (Map.Entry<?, ?>) entry;
			if (command.getKey().toString().startsWith("force "))
			{
				listed = command.getValue().toString();
			}
		}
		assertTrue("the panel's developer commands list force", listed != null);
		for (String name : names())
		{
			assertTrue("the panel's force line names '" + name + "': " + listed,
				Pattern.compile("\\b" + name + "\\b").matcher(listed).find());
		}

		String source = read(PLUGIN);
		int developer = source.indexOf("sendStatus(\"Developer: ");
		assertTrue("the chat help's developer line has moved", developer >= 0);
		assertTrue("the chat help's developer line lists force",
			source.substring(developer, source.indexOf(");", developer)).contains("force <what>"));
	}

	@Test
	public void everyForceHasARowInTheDiagnosticsTable() throws IOException
	{
		String docs = read(DOCS);
		int start = docs.indexOf("**Diagnostics**");
		assertTrue("DEVELOPMENT.md has no Diagnostics table - the lint has lost its place", start >= 0);
		// The table runs from its header to the first line that is not a row.
		StringBuilder table = new StringBuilder();
		boolean begun = false;
		for (String line : docs.substring(start).split("\n"))
		{
			if (line.startsWith("|"))
			{
				begun = true;
				table.append(line.replace("\\|", "|")).append('\n');
			}
			else if (begun)
			{
				break;
			}
		}
		List<String> usages = new ArrayList<>();
		for (Map.Entry<String, String> entry : FollowerPlugin.FORCE_COMMANDS)
		{
			usages.add(entry.getKey());
			assertTrue("no Diagnostics row for ::follower force " + entry.getKey(),
				table.toString().contains("| `::follower force " + entry.getKey() + "` **[dev]** |"));
		}

		// And the other way: a row for a force the table has lost is a
		// force-path the docs promise and the game refuses.
		Matcher row = Pattern.compile("\\| `::follower force ([^`]+)` \\*\\*\\[dev\\]\\*\\* \\|")
			.matcher(table);
		while (row.find())
		{
			assertTrue("the docs list a force the plugin does not have: " + row.group(1),
				usages.contains(row.group(1)));
		}
	}
}
