package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
	public void aParkWaitingForTheFeetGoesWithTheScene() throws IOException
	{
		// A Send given while thrall work had the feet waits to be walked to
		// once they are let go. A hop or a logout ended the thrall work and
		// left the Send waiting, so with Follow me on the next spawn walked
		// off to a tile from the last world or the last session, or, too far
		// to walk, was parked beside the player straight after the login.
		String leaving = method("private void tearDownScene()");
		for (String let : new String[]{"parkedByPlayer = false;", "parkWhenFree = false;",
			"sentWhenFree = null;"})
		{
			assertTrue("leaving a scene must let go of the park: " + let, leaving.contains(let));
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
		// But not at the login screen, where no tick ran the window down and
		// it swallowed the next login's hello and the week's update line.
		// ProfileSwitchTest holds the decision; this holds the glue to it.
		assertTrue("unless a login is coming to take its own",
			switched.contains("if (switchTakesTheBaseline(freshLogin))\n\t\t\t{\n"
				+ "\t\t\t\tspeechEngine.primeEdgesOnNextTick();"));
		assertTrue("and the old follower's queued lines go with it",
			switched.contains("speechQueue.clear();"));
	}

	@Test
	public void forgettingTakesTheBaselineAgain() throws IOException
	{
		// Forget resets the engine too, and on the next tick the gear worn
		// and the place stood in rose as news, as after a switch. The same
		// cure, and the same exception at the login screen, where the login
		// takes its own after the hello.
		String forget = method("private void forgetEverything()");
		int reset = forget.indexOf(RESET);
		assertTrue("Forget resets the engine", reset >= 0);
		assertTrue("so the edges are primed after the reset clears them",
			forget.indexOf("speechEngine.primeEdgesOnNextTick();", reset) > reset);
		assertTrue("unless a login is coming to take its own",
			forget.contains("if (switchTakesTheBaseline(freshLogin))\n\t\t\t{\n"
				+ "\t\t\t\tspeechEngine.primeEdgesOnNextTick();"));
	}

	@Test
	public void anEnableInTheWorldTakesTheBaseline() throws IOException
	{
		// The rules start an enable with every edge cleared, and no login is
		// coming to prime them, so the first tick took the gear worn all
		// along and the place stood in as news. Primed before the restore is
		// queued: a tick can run before that, and a line it left waiting out
		// its delay would not be stopped by a window opened after it.
		String start = method("protected void startUp() throws Exception");
		int read = start.indexOf("readCounters();");
		int inWorld = start.lastIndexOf("if (client.getGameState() == GameState.LOGGED_IN)", read);
		assertTrue("the plugin no longer restores the memory in the world on an enable",
			read >= 0 && inWorld >= 0);
		int prime = start.indexOf("speechEngine.primeEdgesOnNextTick();", inWorld);
		assertTrue("an enable in the world must take the baseline", prime > inWorld && prime < read);
		assertTrue("before the restore is queued", prime < start.indexOf("clientThread.invoke(", inWorld));
	}

	@Test
	public void aLoginDoesNotReadAgainWhatASwitchAlreadyRead() throws IOException
	{
		// A switch at the login screen reads the new profile's memory, and
		// the login read it again on top: every tally and every line's wear
		// doubled, and was saved that way. ProfileSwitchTest holds the
		// decision; this holds the glue to it.
		String login = method("public void onGameStateChanged(GameStateChanged event)");
		int read = login.indexOf("readCounters();");
		assertTrue("the login reads the memory", read >= 0);
		assertTrue("and in one place only", read == login.lastIndexOf("readCounters();"));
		assertTrue("unless it already holds this profile's",
			login.contains("if (loginReadsTheMemory(memoryProfileId, currentProfileId()))\n"
				+ "\t\t\t\t\t{\n\t\t\t\t\t\treadCounters();"));
	}

	@Test
	public void aStopIsKnownToWorkStillQueued() throws IOException
	{
		// A switch to a profile with the plugin off stops it on the Swing
		// thread while the switch's own work waits on the client thread. The
		// work ran after the stop, read that profile's memory into the engine,
		// and the next start read its own on top of it. ProfileSwitchTest
		// holds the work to the flag; this holds the flag to the lifecycle.
		String starting = method("protected void startUp() throws Exception");
		assertTrue("the start sets the flag once everything is up",
			starting.trim().endsWith("started = true;"));
		String stopping = method("protected void shutDown() throws Exception");
		assertTrue("the stop clears it before anything else",
			stopping.replaceAll("\\s*//[^\n]*", "").startsWith(
				"protected void shutDown() throws Exception\n\t{\n\t\tstarted = false;"));
	}

	@Test
	public void theConversationClosesWithTheScene() throws IOException
	{
		// Left open at a logout, the Talk-to box was not drawn at the login
		// screen but took the presses in the chatbox, came back at the next
		// login, and at the first step gave the last session's comfort, gift
		// or bluff to the one just read. Closing delivers what the box had
		// reached, so where the close sits decides where that lands: in the
		// scene's own session, with what it says cleared with the scene.
		String leaving = method("private void tearDownScene()");
		int close = leaving.indexOf("dialog.close();");
		assertTrue("leaving a scene must close the conversation", close >= 0);
		assertTrue("before the bubble and the queue are cleared of the answer's reply",
			close < leaving.indexOf("overlay.clear();") && close < leaving.indexOf("speechQueue.clear();"));
		String source = source();
		String loggedOut = source.substring(source.indexOf("case LOGIN_SCREEN:"),
			source.indexOf("case HOPPING:"));
		assertTrue("and at a logout before the engine lets the session go",
			loggedOut.indexOf("tearDownScene();") >= 0
				&& loggedOut.indexOf("tearDownScene();") < loggedOut.indexOf(RESET));
	}

	@Test
	public void aLogoutLetsTheMemoryGoButNotTheOutfit() throws IOException
	{
		// Forgetting whose memory it was at a logout shut the outfit's
		// auto-save as well, since it asked the same question. An edit made
		// at the login screen was never filed under its named outfit, and
		// picking another outfit and back after the login lost it. The
		// outfit is still worn after a logout, and still that profile's.
		String filing = method("private void autoSaveActiveProfile()");
		assertTrue("the outfit is filed on the profile it was read with",
			filing.contains("belongsToProfile(outfitProfileId, currentProfileId())"));
		assertFalse("not on the memory's, which a logout lets go of",
			filing.contains("onMemoryProfile()"));
		assertFalse("and nothing lets the outfit's go",
			source().contains("\toutfitProfileId = -1;"));
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

	@Test
	public void theSketchBookOpensWhenTheAccountsProfileArrives() throws IOException
	{
		// An account's first login makes its profile after the login itself,
		// so the book opened at the login had no folder, and every sketch
		// drawn that session was gone at the logout. A logout posts no
		// profile, and must not reopen the book, or a sketch still being
		// drawn would lose the folder it was begun for.
		assertTrue("the plugin must hear the profile arrive", source().contains(
			"@Subscribe\n\tpublic void onRuneScapeProfileChanged(RuneScapeProfileChanged event)"));
		String arrived = method("public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)");
		assertTrue("the book opens for the new profile",
			arrived.contains("sketchBook.openFor(event.getNewProfile());"));
		assertTrue("but not for a logout's empty one",
			arrived.contains("event.getNewProfile() != null"));
	}
}
