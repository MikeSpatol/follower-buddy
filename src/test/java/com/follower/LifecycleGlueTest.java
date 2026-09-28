package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Login, logout, a hop, a profile switch and a plugin toggle: what each of
 * them has to let go of, read from the plugin's own source.
 *
 * <p>These are the handlers the harness cannot run - they need a client, a
 * config manager and an event bus - and every fault here had one shape:
 * something belonging to a session or a scene outliving it. All of them
 * were found by reading, so the reading is kept, as a lint.
 */
public class LifecycleGlueTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static final String RESET = "speechEngine.reset();";

	private static String source() throws IOException
	{
		assertTrue("cannot read " + PLUGIN, Files.isRegularFile(PLUGIN));
		return new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/** The last method signature before a point in the source, to say where a fault is. */
	private static String methodAt(String source, int at)
	{
		Matcher signature = Pattern.compile("\n\t(?:public|protected|private)[^\n]*").matcher(source);
		String found = "?";
		while (signature.find() && signature.start() < at)
		{
			found = signature.group().trim();
		}
		return found;
	}

	@Test
	public void aMemoryLetGoOfIsNeverSavedOverTheStoredOne() throws IOException
	{
		// A logout resets the engine, which leaves a blank context behind, and
		// the profile the memory had been read from outlived it. Switching the
		// plugin off at the login screen then passed the profile check and
		// saved that blank over the real memory: a stranger again at the next
		// login. So every reset of the engine is followed by the memory being
		// read again, or by forgetting whose it was - unless the stored one
		// was erased first, by the Forget button, where blank is the truth.
		String source = source();
		String[] members = source.split("\n\t}\n");
		int resets = 0;
		int offset = 0;
		for (String member : members)
		{
			for (int at = member.indexOf(RESET); at >= 0; at = member.indexOf(RESET, at + 1))
			{
				resets++;
				String after = member.substring(at);
				assertTrue("a reset in " + methodAt(source, offset + at)
						+ " leaves a blank memory the next save would write over the stored one",
					after.contains("readCounters();") || after.contains("memoryProfileId = -1;")
						|| member.substring(0, at).contains(
							"unsetConfiguration(FollowerConfig.GROUP, \"counters\")"));
			}
			offset += member.length() + "\n\t}\n".length();
		}
		assertTrue("no engine resets found at all - the scan broke, not the plugin", resets > 0);
	}
}
