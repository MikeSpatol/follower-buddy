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
		return new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	private static String method(String signature) throws IOException
	{
		String source = source();
		int start = source.indexOf(signature);
		assertTrue("the plugin has no " + signature + " - the lint has lost its place", start >= 0);
		return source.substring(start, source.indexOf("\n\t}\n", start));
	}

	// public:cut outfit-profiles
	@Test
	public void aProfileWornByCommandIsTheOneTheDropdownShows() throws IOException
	{
		// ::follower outfit <name> changed the profile edits save into, and
		// the dropdown went on showing the old one.
		assertTrue("loading a profile refreshes the dropdown",
			method("private void loadOutfitProfile(String name, boolean quiet)")
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

	// public:end outfit-profiles
	// public:cut sharevoice
	@Test
	public void aPackIsReadAndWrittenOffTheEventThread() throws IOException
	{
		// Up to 64 MB of sounds unzipped, and every sound zipped, with the
		// event thread, and the game's input, waiting.
		String share = method("private void shareLines(java.awt.Component parent)");
		assertTrue(share, share.contains("worker.execute(() -> exportPack("));
		assertTrue(share, share.contains("worker.execute(() -> importPack("));
		for (String off : new String[]{"private void exportPack(", "private void importPack("})
		{
			String work = method(off);
			assertTrue("a dialog shown off the event thread in " + off,
				!work.contains("JOptionPane.show"));
		}
	}

	@Test
	public void aVoicedLineIsPlayedOffTheClientThread() throws IOException
	{
		// Opening the file and the audio line, on the client thread, for
		// every line with a sound.
		String speak = method("private void speakNow(");
		int at = speak.indexOf("voices.play(");
		assertTrue("no voices.play in speakNow - the lint has lost its place", at >= 0);
		assertTrue(speak.substring(Math.max(0, at - 60), at),
			speak.substring(Math.max(0, at - 60), at).contains("executor.execute("));
	}
	// public:end sharevoice
}
