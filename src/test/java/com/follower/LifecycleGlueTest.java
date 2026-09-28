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

	/** One method, from its signature to the brace that closes it at the class's own depth. */
	private static String method(String signature) throws IOException
	{
		String source = source();
		int start = source.indexOf(signature);
		assertTrue("the plugin has no " + signature + " - the lint has lost its place", start >= 0);
		return source.substring(start, source.indexOf("\n\t}\n", start));
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

	@Test
	public void leavingASceneForgetsWhatWasNoticedInIt() throws IOException
	{
		// Noticing, the look round and the will each promise "Logout, hop" on
		// their reset, and only a plugin toggle ever called it. No despawn
		// comes for a scene that is thrown away, so a drop or a crashed star
		// outlived the world it was in, and after a hop the follower walked
		// to the empty ground and named it.
		String leaving = method("private void tearDownScene()");
		for (String reset : new String[]{"noticing.reset();", "gaze.reset();", "will.reset();"})
		{
			assertTrue("leaving a scene must call " + reset, leaving.contains(reset));
		}
	}

	@Test
	public void theBookClosesWithTheScene() throws IOException
	{
		// Left open at a logout with no tick to close it, the chronicle was
		// not drawn at the login screen but still took every press where it
		// had last been drawn, over the Existing User and Login buttons.
		assertTrue("leaving a scene must close the book, as the game closes its own windows",
			method("private void tearDownScene()").contains("chronicleWindow.close();"));
	}

	@Test
	public void aProfileSwitchTakesTheBaselineAgain() throws IOException
	{
		// A switch resets every rule's edge, so on the next tick the gear worn
		// and the place stood in rose as news: a gear line as if it had just
		// gone on. The same cure as a login and a reload. And a line the old
		// profile's follower queued would have opened its wish or want in the
		// new one's memory at delivery.
		String switched = method("public void onProfileChanged(ProfileChanged event)");
		int reset = switched.indexOf(RESET);
		assertTrue("the switch resets the engine", reset >= 0);
		assertTrue("so the edges are primed after the reset clears them",
			switched.indexOf("speechEngine.primeEdgesOnNextTick();", reset) > reset);
		assertTrue("and the old follower's queued lines go with it",
			switched.contains("speechQueue.clear();"));
	}

	@Test
	public void aDisablePutsTheSketchingScrollAway() throws IOException
	{
		// The sketch sets the reading pose on the follower, which outlives a
		// toggle, and only the sketch's own reset takes it off. Switched off
		// with the scroll out, the follower came back reading nothing.
		assertTrue("shutting down must reset the sketch",
			method("protected void shutDown()").contains("sketching.reset();"));
	}
}
