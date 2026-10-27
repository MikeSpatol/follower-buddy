package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Which thread the plugin does things on, read from its source: the wiring
 * needs a panel, a client thread, an executor and a config manager, which
 * the harness cannot run (QA pass 4, threading audit).
 */
public class ThreadingGlueTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static String source() throws IOException
	{
		assertTrue("cannot read " + PLUGIN, Files.isRegularFile(PLUGIN));
		return PublicMarkers.strip(new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n"));
	}

	private static String method(String signature) throws IOException
	{
		String source = source();
		int start = source.indexOf(signature);
		assertTrue("the plugin has no " + signature + " - the lint has lost its place", start >= 0);
		return source.substring(start, source.indexOf("\n\t}\n", start));
	}

	@Test
	public void aProfileWornByCommandIsTheOneTheDropdownShows() throws IOException
	{
		// ::follower outfit <name> changed the profile edits save into, and
		// the dropdown went on showing the old one.
		assertTrue("loading a profile refreshes the dropdown",
			method("void loadOutfitProfile(String name, boolean quiet)")
				.contains("panel.setProfileNames(profileStore.names(), activeProfile);"));
	}

	@Test
	public void thePanelsProfileButtonsRunOnTheClientThread() throws IOException
	{
		// A pick in the panel ran on the event thread while the client thread
		// autosaved the outfit into the active profile: the profile left could
		// be written over with the one picked.
		String source = source();
		for (String callback : new String[]{"setOnProfileLoad(", "setOnProfileSave(", "setOnProfileDelete("})
		{
			int at = source.indexOf("panel." + callback);
			assertTrue("no panel." + callback + " - the lint has lost its place", at >= 0);
			String line = source.substring(at, source.indexOf('\n', at));
			assertTrue(line, line.contains("clientThread.invoke("));
		}
	}

}
