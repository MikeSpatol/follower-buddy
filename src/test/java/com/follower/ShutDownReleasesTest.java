package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RuneLite keeps a plugin it has switched off, and everything the plugin
 * holds with it. The catalogues and the Chronicle's portrait models are let
 * go at shutDown and built again at the next startUp and login (perf audit,
 * Oct 2026, #64).
 */
public class ShutDownReleasesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void shutDownLetsThemGo() throws IOException
	{
		String plugin = PublicMarkers.strip(new String(Files.readAllBytes(Paths.get("src/main/java/com/follower/FollowerPlugin.java")),
			StandardCharsets.UTF_8).replace("\r\n", "\n"));
		int at = plugin.indexOf("protected void shutDown()");
		assertTrue("no shutDown - the lint has lost its place", at >= 0);
		String body = plugin.substring(at, plugin.indexOf("\n\t}\n", at));
		assertTrue(body.contains("modelRepository.unload();"));
		assertTrue(body.contains("spotAnimRepository.unload();"));
	}
}
