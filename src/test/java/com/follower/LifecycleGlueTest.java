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

	/** The reset that forgets what was said as well: another follower's memory. */
	private static final String FORGET = "speechEngine.forget();";

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
			for (int at = firstReset(member, 0); at >= 0; at = firstReset(member, at + 1))
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

	private static int firstReset(String member, int from)
	{
		int reset = member.indexOf(RESET, from);
		int forget = member.indexOf(FORGET, from);
		return reset < 0 ? forget : forget < 0 ? reset : Math.min(reset, forget);
	}

	// public:cut company-extras
	@Test
	public void leavingASceneForgetsWhatWasNoticedInIt() throws IOException
	{
		// Noticing, the look round and the will each promise "Logout, hop" on
		// their reset, and only a plugin toggle ever called it. No despawn
		// comes for a scene that is thrown away, so a drop or a crashed star
		// outlived the world it was in, and after a hop the follower walked
		// to the empty ground and named it.
		String leaving = method("private void tearDownScene()");
		for (String reset : new String[]{
			"noticing.reset();", // public:cut noticing
			"gaze.reset();", // public:cut gaze
			"will.reset();", // public:cut will
		})
		{
			assertTrue("leaving a scene must call " + reset, leaving.contains(reset));
		}
	}

	// public:end company-extras
	// public:cut dev
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
	// public:end dev

	@Test
	public void aSceneGoesWithTheScene() throws IOException
	{
		// A real cutscene, and the quiet after it, as the forced one: a logout
		// or hop mid-scene must not leave the next world held or silent.
		for (String signature : new String[]{"private void tearDownScene()", "private void resetTransientState()"})
		{
			String leaving = method(signature);
			for (String let : new String[]{"sceneOn = false;", "quietUntilTick = 0;",
				"speechEngine.setSceneQuiet(false);", "speechEngine.setInCutscene(false);"})
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
		assertTrue("and of the scene itself, which can move a quest on",
			method("private void noticeTheScene()").contains("speechEngine.setInCutscene(inCutscene());"));
		// public:cut chronicle
		// And for a conversation as the rules read it, page flips ridden out:
		// on the raw box the news went out on a flip (QA, 15 Oct).
		assertTrue("the page news waits for the quiet",
			method("public void onGameTick(GameTick event)").contains(
				"nextPageNews(pageNews, client.getTickCount(), speechEngine.isQuiet(),\n"
					+ "\t\t\tinConversation() || sceneQuiet() || (first != null && speechEngine.isPageLineCooling(first.event)),\n"
					+ "\t\t\tpageNewsTicks(speechEngine.moodScaledGap()), lastRegionId);"));
		// public:end chronicle
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
	public void theFollowersOwnBoxHoldsTheQueueAndEveryKeptLineKeepsItsTime() throws IOException
	{
		// Behaviour QA, 16 Oct: with the follower's own Talk-to box open a
		// rule's line went overhead once the floor was free, and lines
		// queued meanwhile aged out in the box. Through an NPC conversation
		// only the first kept line had its clock restarted, so the second
		// was dropped as stale as the box closed (probe P6).
		assertTrue("queued while its own box is open", method("private void speak(String text,")
			.contains("if ((sketching != null && sketching.isPosing()) || dialog.isOpen())")); // public:cut chronicle
			// public:else .contains("if (dialog.isOpen())"));
		String drain = method("private void drainSpeechQueue()");
		int every = drain.indexOf("for (Utterance queued : speechQueue)");
		assertTrue("every kept line", every >= 0
			&& drain.indexOf("keptForTheBox(queued.rule, box)") > every);
		assertTrue("in either box", drain.contains("boolean box = dialog.isOpen() || inConversation();"));
		assertTrue("before the own box returns", every < drain.indexOf("if (dialog.isOpen())"));
		assertTrue("and before the stale sweep", every < drain.indexOf("SPEECH_STALE_MS"));
	}

	@Test
	public void noGestureIsPlayedInTheFollowersOwnBox() throws IOException
	{
		// Behaviour QA4, 16 Oct: once the player had read for 48 s the
		// follower yawned, shrugged and struck its distant pose while its
		// chathead was talking: 4.4 gestures a 90 s box.
		String speak = method("private void speak(String text,");
		int gate = speak.indexOf("|| dialog.isOpen()))");
		assertTrue("a silent rule stands down for the box", gate >= 0
			&& speak.lastIndexOf("text.isEmpty()", gate) >= 0);
		assertTrue("before it is played", gate < speak.indexOf("if (text.isEmpty())"));
	}

	@Test
	public void aLineKeptInTheQueueIsLetGoOnceItsMomentHasPassed() throws IOException
	{
		// Behaviour QA4, 16 Oct (probe Q2a): Hans walked past during a 44 s
		// Talk-to box and was 12 tiles gone 24 s before it closed; his
		// opener was said on the tick the box closed. Re-checked as the
		// engine re-checks a line it kept.
		String drain = method("private void drainSpeechQueue()");
		int check = drain.indexOf("!line.rule.when.standingHolds(speechEngine.getContext(), TriggerEvent.tick())");
		assertTrue("re-checked", check >= 0);
		assertTrue("before it is said", check < drain.indexOf("speakNow(line);"));
	}

	// public:cut moments
	@Test
	public void theMomentsAnswerToNoticesThings() throws IOException
	{
		// UI QA4 #37, the owner's call: the level clap, the pet's greeting,
		// the walk over to a good drop and the stranger watch stand down
		// with Notices things, which already sends the follower to drops.
		String source = source();
		int calls = 0;
		for (String call : new String[]{"moments.levelUp(", "moments.noteworthy(",
			"moments.npcSpawned(", "moments.noteChatMessage("})
		{
			for (int at = source.indexOf(call); at >= 0; at = source.indexOf(call, at + 1))
			{
				calls++;
				String guard = source.substring(source.lastIndexOf("if (", at), at);
				assertTrue(call + " in " + methodAt(source, at), guard.contains("config.noticesThings()"));
			}
		}
		assertEquals(5, calls);
	}
	// public:end moments

	// public:cut errands
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

	// public:end errands
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
		// public:cut errands
		assertTrue("errands", src.contains("errands.setSession(() -> skilling.isWorking() || sceneQuiet());")); // public:cut skilling
		// public:else assertTrue("errands", src.contains("errands.setSession(this::sceneQuiet);"));
		// public:end errands
		// public:cut company-extras
		assertEquals(""
			+ "the look round" // public:cut gaze
			+ " and " // public:cut gaze,noticing
			+ "the noticing", 0 // public:cut noticing
			// public:else , 0
			+ 1 // public:cut gaze
			+ 1, src.split( // public:cut noticing
			// public:else , src.split(
			"isInCombat\\(\\) \\|\\| sceneQuiet\\(\\)"
			+ " \\|\\| filming", -1).length - 1); // public:cut posing
			// public:else , -1).length - 1);
		// public:end company-extras
	}

	// public:cut company-extras,will
	@Test
	public void theWillStepsNothingAsideInAScene() throws IOException
	{
		// The scene's hold is a stay, and a plain one to the will: walked at
		// by a scripted walk it stepped into the cast or along the walk.
		assertTrue(method("protected void startUp()").contains(
			"&& minutesState == 0 && !emoteHold && !filming && !sceneHolds());")); // public:cut posing
			// public:else "&& minutesState == 0 && !emoteHold && !sceneHolds());"));
	}

	// public:end company-extras,will
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
			"pendingMime = 0;", "copyAtWork = false;", "emoteDisarmed = false;"}) // public:cut skilling
			// public:else "emoteDisarmed = false;"})
		{
			assertTrue("leaving a scene must let go of the copy: " + let, leaving.contains(let));
		}
		assertTrue("before the body goes", leaving.indexOf("mirroredPose = 0;") < leaving.indexOf("follower.despawn();"));
	}

	// public:cut chronicle
	@Test
	public void theBookClosesWithTheScene() throws IOException
	{
		// Left open at a logout with no tick to close it, the chronicle was
		// not drawn at the login screen but still took every press where it
		// had last been drawn, over the Existing User and Login buttons.
		assertTrue("leaving a scene must close the book, as the game closes its own windows",
			method("private void tearDownScene()").contains("chronicleWindow.close();"));
	}

	// public:end chronicle
	@Test
	public void aProfileSwitchTakesTheBaselineAgain() throws IOException
	{
		// A switch resets every rule's edge, so on the next tick the gear worn
		// and the place stood in rose as news: a gear line as if it had just
		// gone on. The same cure as a login and a reload. And a line the old
		// profile's follower queued would have opened its wish or want in the
		// new one's memory at delivery.
		String switched = method("public void onProfileChanged(ProfileChanged event)");
		int reset = switched.indexOf(FORGET);
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

	// public:cut memory-window
	@Test
	public void forgettingTakesTheBaselineAgain() throws IOException
	{
		// Forget resets the engine too, and on the next tick the gear worn
		// and the place stood in rose as news, as after a switch. The same
		// cure, and the same exception at the login screen, where the login
		// takes its own after the hello.
		String forget = method("private void forgetEverything()");
		int reset = forget.indexOf(FORGET);
		assertTrue("Forget resets the engine", reset >= 0);
		assertTrue("so the edges are primed after the reset clears them",
			forget.indexOf("speechEngine.primeEdgesOnNextTick();", reset) > reset);
		assertTrue("unless a login is coming to take its own",
			forget.contains("if (switchTakesTheBaseline(freshLogin))\n\t\t\t{\n"
				+ "\t\t\t\tspeechEngine.primeEdgesOnNextTick();"));
	}
	// public:end memory-window

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

	// public:cut outfit-profiles
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

	// public:end outfit-profiles
	// public:cut chronicle
	@Test
	public void aDisablePutsTheSketchingScrollAway() throws IOException
	{
		// The sketch sets the reading pose on the follower, which outlives a
		// toggle, and only the sketch's own reset takes it off. Switched off
		// with the scroll out, the follower came back reading nothing.
		assertTrue("shutting down must reset the sketch",
			method("protected void shutDown()").contains("sketchingNow.reset();"));
	}

	// public:end chronicle
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
			"skillingNow.reset();", // public:cut skilling
			"momentsNow.reset();", // public:cut moments
			"sketchingNow.reset();", // public:cut chronicle
			"errandsNow.reset();", // public:cut errands
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

	// public:cut posing
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

	// public:end posing
	// public:cut chronicle
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

	// public:end chronicle
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

	// public:cut chronicle
	@Test
	public void sketchIsOfferedOnlyWhenItWouldStart() throws IOException
	{
		assertTrue("the menu asks whether a sketch could start now", source()
			.contains("if (sketching != null && !filming && sketching.canSketchNow())")); // public:cut posing
			// public:else .contains("if (sketching != null && sketching.canSketchNow())"));
	}

	// public:end chronicle
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
		for (String letGo : new String[]{"public void release()", "public void takeOff()"}) // public:cut skilling
		// public:else for (String letGo : new String[]{"public void release()"})
		{
			int at = starting.indexOf(letGo);
			assertTrue("the hands have no " + letGo + " - the lint has lost its place", at >= 0);
			String body = starting.substring(at, starting.indexOf("\n\t\t\t\t}\n", at));
			int rebuild = body.indexOf("rebuildFollower();");
			assertTrue(letGo + " rebuilds only while the plugin is on",
				rebuild > 0 && body.lastIndexOf("if (started)", rebuild) > 0);
		}
	}

	// public:cut chronicle
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

	// public:cut memory-window
	@Test
	public void forgettingTakesOnlyTheDrawingsMadeUnderThisMemory() throws IOException
	{
		// The sketch folders are shared by every RuneLite profile and the
		// memory is one profile's (the user, 21 Oct): every book opened is
		// noted in the memory, and Forget takes the books noted there, read
		// before the memory is erased.
		String source = source();
		int opened = 0;
		for (int at = source.indexOf("sketchBook.openFor("); at >= 0; at = source.indexOf("sketchBook.openFor(", at + 1))
		{
			opened++;
			String account = source.substring(at + "sketchBook.openFor(".length(), source.indexOf(");", at));
			int noted = source.indexOf("noteSketchAccount(" + account + ");", at);
			assertTrue("the book opened for " + account + " is noted in the memory",
				noted > at && noted - at < 200);
		}
		assertTrue(opened >= 3);
		String forget = method("private void forgetEverything()");
		int listed = forget.indexOf("getSketchAccounts()");
		assertTrue("the list is read before the memory is erased",
			listed >= 0 && listed < forget.indexOf("unsetConfiguration(FollowerConfig.GROUP, \"counters\")")
				&& listed < forget.indexOf(FORGET));
		assertTrue("and Forget takes those", forget.contains("sketchBook.forgetAll(sketched);"));
	}
	// public:end memory-window

	// public:end chronicle
	@Test
	public void aDeathAMinigameGivesBackIsNoDeath() throws IOException
	{
		// The user, 20 Oct: a Castle Wars death is ignored - no grief, no
		// memory, no mark. Text QA5: it was mourned, filed, and brought up
		// 24 times. The death event and the game's death message both stay
		// out of the rules there.
		String died = method("public void onActorDeath(net.runelite.api.events.ActorDeath event)");
		int safe = died.indexOf("isSafeDeathHere()");
		assertTrue("the death waits on the place", safe >= 0 && safe < died.indexOf("TriggerEvent.death()")
			&& safe < died.indexOf("noteDeath("));
		String chat = method("public void onChatMessage(ChatMessage event)");
		assertTrue("and so does the game's word for it",
			chat.indexOf("isSafeDeathHere()") >= 0
				&& chat.indexOf("isSafeDeathHere()") < chat.indexOf("TriggerEvent.chat("));
	}

	@Test
	public void theSlowWorkIsOnThePluginsOwnWorker() throws IOException
	{
		// QA pass 5, #68: RuneLite's executor is one thread every plugin
		// shares. A 64 MB pack or a second-long sketch on it held up all else
		// queued there, a line's voice among them. Only the voice, a moment's
		// work, is left on it; the rest goes to the plugin's own worker, made
		// at the start and stopped at the stop.
		String source = source();
		assertEquals("only the voice on the shared executor", 1, source.split("executor[.]execute[(]", -1).length - 1); // public:cut sharevoice
		// public:else assertEquals("nothing on the shared executor", 0, source.split("executor[.]execute[(]", -1).length - 1);
		// public:cut sharevoice
		assertTrue(source.contains("executor.execute(() -> voices.play(text, volume));"));
		assertTrue(source.contains("worker.execute(() -> exportPack(target, parent));"));
		assertTrue(source.contains("worker.execute(() -> importPack(source, parent));"));
		// public:end sharevoice
		assertTrue(source.contains("sketchBook, drawManager, worker,")); // public:cut chronicle
		assertTrue(method("protected void startUp() throws Exception").contains("worker = new "));
		assertTrue(method("protected void shutDown() throws Exception").contains("worker.shutdown();"));
	}
}
