// public:file dev
package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * A chat line that points at a developer command is for developers. QA,
 * 15 Oct: the colour harvest after a look change told every player "run
 * '::follower palette'", a command refused to them as a developer's, about a
 * harvest they never asked for. The harvest goes on quietly for everyone;
 * the line is said only in developer mode. Read from the source, as in
 * {@link LifecycleGlueTest}: the handler needs a client.
 */
public class DeveloperOnlyChatTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static String method(String signature) throws IOException
	{
		assertTrue("cannot read " + PLUGIN, Files.isRegularFile(PLUGIN));
		String source = new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n");
		int start = source.indexOf(signature);
		assertTrue("the plugin has no " + signature + " - the lint has lost its place", start >= 0);
		return source.substring(start, source.indexOf("\n\t}\n", start));
	}

	@Test
	public void theHarvestBanksForEveryoneAndSaysSoOnlyToADeveloper() throws IOException
	{
		String harvest = method("private void autoHarvest(Player local)");
		int banked = harvest.indexOf("paletteHarvest.record(");
		int said = harvest.indexOf("sendStatus(\"Banked colour set");
		assertTrue("the harvest still banks", banked >= 0);
		assertTrue("the harvest's chat line has moved - the lint has lost its place", said > banked);
		assertTrue("banking is not held to developer mode",
			!harvest.substring(0, banked).contains("developerMode()"));
		String guard = harvest.substring(banked, said);
		assertTrue("the banked line is said only in developer mode",
			guard.contains("config.developerMode()") && guard.contains("if ("));
	}
}
