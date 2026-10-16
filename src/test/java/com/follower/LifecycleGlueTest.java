package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
	public void aForcedCutsceneGoesWithTheScene() throws IOException
	{
		// ::follower force cutscene on held the follower still and facing
		// where the player faced through every logout and hop after, with
		// nothing on screen to say why, until the plugin was toggled. A real
		// cutscene ends with the scene; the forced one has to as well.
		assertTrue("leaving a scene must let go of the forced cutscene",
			method("private void tearDownScene()").contains("cutsceneForced = false;"));
	}

	@Test
	public void aSceneGoesWithTheScene() throws IOException
	{
		// A real cutscene, and the quiet after it, as the forced one: a logout
		// or hop mid-scene must not leave the next world held or silent.
		for (String signature : new String[]{"private void tearDownScene()", "private void resetTransientState()"})
		{
			String leaving = method(signature);
			for (String let : new String[]{"sceneOn = false;", "quietUntilTick = 0;",
				"speechEngine.setSceneQuiet(false);"})
			{
				assertTrue(signature + " must let go of the scene: " + let, leaving.contains(let));
			}
		}
	}

	@Test
	public void theSceneIsReadBeforeTheLoadWait() throws IOException
	{
		// A scene that opens with an area load is a scene from its first
		// tick, and the hold is given from that one reading only.
		String tick = method("public void onGameTick(GameTick event)");
		int read = tick.indexOf("noticeTheScene();");
		assertTrue("the scene is read before the load wait",
			read >= 0 && read < tick.indexOf("if (spawnDelayTicks > 0)"));
		assertFalse("and the hold is set nowhere else in the tick", tick.contains("setWatchingWithThePlayer"));
	}

	@Test
	public void aSceneIsASilence() throws IOException
	{
		// Play test, 9 Oct: a line said over the opening of a cutscene. The
		// engine is told of the scene and its quiet, and a page written in
		// one waits for both to be over.
		assertTrue("the engine is told of the scene and the quiet after it",
			method("private void noticeTheScene()").contains("speechEngine.setSceneQuiet(sceneQuiet());"));
		// And for a conversation as the rules read it, page flips ridden out:
		// on the raw box the news went out on a flip (QA, 15 Oct).
		assertTrue("the page news waits for the quiet",
			method("public void onGameTick(GameTick event)").contains(
				"nextPageNews(pageNews, client.getTickCount(), speechEngine.isQuiet(),\n"
					+ "\t\t\tinConversation() || sceneQuiet());"));
		assertTrue(method("private boolean inConversation()")
			.contains("return npcDialogOpen() || dialogGoneTicks <= 2;"));
	}

	@Test
	public void aLineThatWaitsIsKeptInTheQueueThroughAConversation() throws IOException
	{
		// QA, 15 Oct: a line the engine held for a conversation can still sit
		// in the queue behind a long one, and the player start talking to
		// someone meanwhile. Kept, not said into the box and dropped.
		com.follower.speech.SpeechRule waits = new com.follower.speech.SpeechRule();
		waits.waits = Boolean.TRUE;
		com.follower.speech.SpeechRule occasion = new com.follower.speech.SpeechRule();
		occasion.occasion = Boolean.TRUE;
		assertTrue(FollowerPlugin.keptForTheBox(waits, true));
		assertTrue(FollowerPlugin.keptForTheBox(occasion, true));
		assertFalse("chatter is dropped there as ever",
			FollowerPlugin.keptForTheBox(new com.follower.speech.SpeechRule(), true));
		assertFalse(FollowerPlugin.keptForTheBox(waits, false));
		assertFalse(FollowerPlugin.keptForTheBox(null, true));

		assertTrue("not said into the box when the floor is free", method("private void speak(String text,")
			.contains("if (now < speakingUntilMs || keptForTheBox(rule, inConversation()))"));
		String drain = method("private void drainSpeechQueue()");
		int kept = drain.indexOf("keptForTheBox(next.rule, inConversation())");
		assertTrue("nor drained into it", kept >= 0);
		assertTrue("its staleness counted from the box closing", kept < drain.indexOf("SPEECH_STALE_MS"));
	}

	@Test
	public void theHiddenErrandIsNeverListed() throws IOException
	{
		// Text sweep, 14 Oct: the fire errand that walks into the fire is a
		// joke the panel's dropdown keeps hidden; the help and the typo reply
		// listed it.
		for (java.util.Map.Entry<String, String> command : FollowerPlugin.EVERYDAY_COMMANDS)
		{
			assertFalse(command.getKey(), command.getKey().contains("firedeath"));
		}
		assertTrue("the typo reply lists the same keys", source()
			.contains("+ LISTED_ERRAND_KEYS.replace(\"|\", \", \"));"));
	}

	@Test
	public void theErrandHelpWrapsBetweenKeys()
	{
		// QA, 15 Oct: one unbroken token, so the panel's Commands list split
		// it mid-word ("docu" / "ment").
		String errand = null;
		for (java.util.Map.Entry<String, String> command : FollowerPlugin.EVERYDAY_COMMANDS)
		{
			if (command.getKey().startsWith("errand "))
			{
				errand = command.getKey();
			}
		}
		assertTrue("the help has no errand line", errand != null);
		assertTrue(errand, errand.contains("bank | altar"));
		assertFalse(errand, errand.matches(".*\\w\\|.*|.*\\|\\w.*"));
	}

	@Test
	public void thePanelSaysNothingOnceTheItemsAreIn() throws IOException
	{
		// QA, 15 Oct: the item and kit counts and the cache date sat under the
		// profiles for every player, cut off at the sidebar's edge.
		String sync = method("private void syncPanel()");
		assertTrue(sync, sync.contains("panel.setStatus(modelRepository.isLoaded()\n\t\t\t? \"\"\n"));
	}

	@Test
	public void theQuietAfterASceneStartsNothing() throws IOException
	{
		// Play test, 13 Oct: hold the activities through the quiet too. No
		// errand, look round or noticing of its own until it is over.
		String src = source();
		assertTrue("errands", src.contains("errands.setSession(() -> skilling.isWorking() || sceneQuiet());"));
		assertEquals("the look round and the noticing", 2, src.split(
			"isInCombat\\(\\) \\|\\| sceneQuiet\\(\\) \\|\\| filming", -1).length - 1);
	}

	@Test
	public void theWillStepsNothingAsideInAScene() throws IOException
	{
		// The scene's hold is a stay, and a plain one to the will: walked at
		// by a scripted walk it stepped into the cast or along the walk.
		assertTrue(method("protected void startUp()").contains(
			"&& minutesState == 0 && !emoteHold && !filming && !sceneHolds());"));
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
	public void aHeldCopyGoesWithTheScene() throws IOException
	{
		// A copied sit or dance loop is a pose override the body keeps, and
		// despawn and spawn leave it on. Carried over a hop, the next body sat
		// at every stop beside a standing player, its weapon put away.
		String leaving = method("private void tearDownScene()");
		for (String let : new String[]{"follower.clearPoseOverride(mirroredPose);", "mirroredPose = 0;",
			"pendingMime = 0;", "copyAtWork = false;", "emoteDisarmed = false;"})
		{
			assertTrue("leaving a scene must let go of the copy: " + let, leaving.contains(let));
		}
		assertTrue("before the body goes", leaving.indexOf("mirroredPose = 0;") < leaving.indexOf("follower.despawn();"));
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
			method("protected void shutDown()").contains("sketchingNow.reset();"));
	}

	@Test
	public void aDisableLetsGoOfTheFollowerOnTheClientThread() throws IOException
	{
		// Play test, 29 Sep: RuneLite stops a plugin on the Swing thread, and
		// putting a scroll or a tool away rebuilds the follower, which reads
		// the client. Only the client thread may, and the client's check
		// throws on every build. Switched off mid-sketch, the stop threw
		// there: the reading pose stayed on for good, and the despawn, the
		// saves and the sidebar button's removal never ran. A borrowed tool,
		// the notes, the minutes or a thrall did the same. So everything that
		// lets go of the follower goes over in the stop's one invoke, with
		// this session's controllers, not a quick re-enable's new ones.
		String stopping = method("protected void shutDown() throws Exception");
		int over = stopping.indexOf("clientThread.invoke(");
		assertTrue("the stop hands its follower work to the client thread", over >= 0);
		String swing = stopping.substring(0, over);
		String client = stopping.substring(over, stopping.indexOf("\n\t\t});", over));
		for (String call : new String[]{"resetThrallQuietly();", "dialog.close();", "flourishNow.abort();",
			"skillingNow.reset();", "momentsNow.reset();", "sketchingNow.reset();", "errandsNow.reset();",
			"endMinutes(false);", "follower.setPoseOverride(0);", "follower.despawn();"})
		{
			assertTrue("the stop must call " + call + " on the client thread", client.contains(call));
		}
		// The code alone, and the engine's own reset, which is only fields.
		String left = swing.replaceAll("//[^\n]*", "").replace("speechEngine.reset(", "");
		for (String call : new String[]{"resetThrallQuietly\\(", "dialog\\.close\\(", "\\.abort\\(",
			"\\.reset\\(", "endMinutes\\(", "(?<![.\\w])follower\\."})
		{
			assertFalse("the stop reaches the follower from the Swing thread: " + call,
				Pattern.compile(call).matcher(left).find());
		}
	}

	@Test
	public void aSceneBeingFilmedEndsWithTheScene() throws IOException
	{
		// The film's park and pose belong to the scene; its window does not,
		// and the next press in it starts the scene again in the new one.
		String leaving = method("private void tearDownScene()");
		int ended = leaving.indexOf("endFilming();");
		assertTrue("leaving a scene must end the film", ended >= 0);
		assertTrue("while the follower is still there to let go of",
			ended < leaving.indexOf("follower.despawn();"));
	}

	@Test
	public void aSceneEndedByLeavingTheWorldIsToldToTheWindow() throws IOException
	{
		// After a hop the window still said the scene was on, while the
		// follower chatted and wandered again. A scene under way is told
		// to the window before it is ended, and only one under way.
		String leaving = method("private void tearDownScene()");
		int ended = leaving.indexOf("endFilming();");
		int told = leaving.indexOf("tellTheFilm(");
		assertTrue("leaving a scene must tell the film window it ended", told >= 0 && told < ended);
		assertTrue("only when a scene was being filmed",
			leaving.lastIndexOf("if (filming)", told) > leaving.lastIndexOf(";", told));
	}

	@Test
	public void aDisableClosesTheFilmWindowAndForgetsTheScene() throws IOException
	{
		// Shut on the Swing thread, where it was opened. Disposing is not
		// closing, so no cut reaches the client thread after the stop: the
		// stop's own block takes every pose off, and the flags go with the
		// transient state.
		String stopping = method("protected void shutDown() throws Exception");
		int over = stopping.indexOf("clientThread.invoke(");
		int disposed = stopping.indexOf("filmDialog.dispose();");
		assertTrue("the stop must dispose the film window on the Swing thread", disposed >= 0 && disposed < over);
		String reset = method("private void resetTransientState()");
		assertTrue(reset.contains("filming = false;"));
		assertTrue(reset.contains("filmParked = false;"));
		String opening = method("private void openFilmDialog()");
		assertTrue("the window is built on the Swing thread and handed the client thread",
			opening.contains("javax.swing.SwingUtilities.invokeLater(") && opening.contains("clientThread::invoke"));
	}

	@Test
	public void aCreatureIsMetInSightOrByDealingWithItNotByLoading() throws IOException
	{
		// Play test, 7 Oct: a spawn is anything loaded within about 15 tiles,
		// through walls and floors, and writing pages there wrote the caged
		// Monk of Zamorak's on the way to King Roald, unseen.
		assertFalse("a spawn must not write a page",
			method("public void onNpcSpawned(NpcSpawned event)").contains("noteNpc("));
		assertTrue("seen close, in clear sight, each tick",
			method("public void onGameTick(GameTick event)").contains("noteCreaturesInSight(local);"));
		assertTrue("talked to, fought or set upon",
			method("public void onInteractingChanged(").contains("chronicleProgress.noteNpc("));
		assertTrue("examined", method("public void onMenuOptionClicked(MenuOptionClicked event)")
			.contains("EXAMINE_NPC"));
		assertTrue("and in sight means a clear line", method("private void noteCreaturesInSight(Player me)")
			.contains("hasLineOfSightTo(view, mine)"));
	}

	@Test
	public void theShiftHoverTooltipIsAddedEveryFrame() throws IOException
	{
		// Play test, 8 Oct: added once a client tick, the tooltip was missing
		// from every frame past fifty a second and flickered.
		assertTrue("the frame callback adds it",
			method("public void onBeforeRender(BeforeRender event)").contains("shiftHoverTooltip();"));
		assertFalse("and not once a client tick",
			method("public void onClientTick(").contains("tooltipManager"));
	}

	@Test
	public void sketchIsOfferedOnlyWhenItWouldStart() throws IOException
	{
		assertTrue("the menu asks whether a sketch could start now", source()
			.contains("if (sketching != null && !filming && sketching.canSketchNow())"));
	}

	@Test
	public void aShiftClickTalksOnTheClientThread() throws IOException
	{
		// Play test, 29 Sep: the mouse is heard on the Swing thread, and the
		// Talk-to box reads the bag for a wished item, which only the client
		// thread may. While a wish was open a shift-click threw and the box
		// never opened; the right-click Talk-to, on the client thread, was fine.
		String source = source();
		int at = source.indexOf("shiftClickAdapter =");
		assertTrue("the plugin has no shift-click adapter - the lint has lost its place", at >= 0);
		String adapter = source.substring(at, source.indexOf("\n\t};\n", at));
		int over = adapter.indexOf("clientThread.invoke(");
		assertTrue("a shift-click must open the box on the client thread", over >= 0
			&& adapter.indexOf("startTalking();", over) > over);
		assertFalse("and only there", adapter.substring(0, over).contains("startTalking("));
		// Asked again there: the box opens on the next tick, so a second
		// click queued before it would otherwise start the talk over.
		String there = adapter.substring(over);
		assertTrue("and only while no talk is open and the plugin is still on",
			there.indexOf("!dialog.isOpen()") >= 0 && there.indexOf("started") >= 0
				&& there.indexOf("!dialog.isOpen()") < there.indexOf("startTalking();"));
	}

	@Test
	public void aStopLetsGoOfThePropsWithoutARebuild() throws IOException
	{
		// Review, 29 Sep: the stop's queued block puts the scroll or the tool
		// away after the Swing side has unloaded the model data, and the
		// rebuild that followed failed and told the chat the install had no
		// equipment models. After a stop the block despawns anyway.
		String starting = method("protected void startUp() throws Exception");
		for (String letGo : new String[]{"public void release()", "public void takeOff()"})
		{
			int at = starting.indexOf(letGo);
			assertTrue("the hands have no " + letGo + " - the lint has lost its place", at >= 0);
			String body = starting.substring(at, starting.indexOf("\n\t\t\t\t}\n", at));
			int rebuild = body.indexOf("rebuildFollower();");
			assertTrue(letGo + " rebuilds only while the plugin is on",
				rebuild > 0 && body.lastIndexOf("if (started)", rebuild) > 0);
		}
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
