package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the plugin says in chat, and when. QA, 15 Oct (chat and menus). The
 * handlers need a client, so, as in {@link LifecycleGlueTest}, what can only
 * be read from the source is kept as a lint.
 */
public class StatusLinesTest
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

	/**
	 * "Wearing outfit profile 'Default follower'" was said on every start,
	 * plugin enable and profile switch, and was the first thing a new player
	 * ever heard from the plugin. The restore is quiet; a pick still answers.
	 */
	@Test
	public void restoringTheSessionsOutfitIsQuiet() throws IOException
	{
		String restore = method("private void restoreActiveProfile()");
		assertTrue("the restore loads its profile quietly", restore.contains("loadOutfitProfile(wanted, true)"));
		String load = method("private void loadOutfitProfile(String name, boolean quiet)");
		int said = load.indexOf("sendStatus(\"Wearing outfit profile");
		assertTrue("the profile line has moved - the lint has lost its place", said >= 0);
		assertTrue("the profile line is held back when quiet",
			load.substring(0, said).contains("if (!quiet)"));
		assertTrue("a profile picked in the panel still says so",
			method("private void loadOutfitProfile(String name)\n").contains("loadOutfitProfile(name, false)"));
	}

	/**
	 * With errands off, ::follower errand and the panel's Go now promised a
	 * run the controller would never make. The ask is refused before the
	 * promise, and a forced errand that cannot start is reported back.
	 */
	@Test
	public void anErrandIsPromisedOnlyWhenErrandsAreOn() throws IOException
	{
		String force = method("public void forceErrand(String key)");
		int off = force.indexOf("if (!config.errandsEnabled())");
		int queued = force.indexOf("errands.force(key)");
		int promised = force.indexOf("sendStatus(\"Will run the ");
		assertTrue("the lint has lost its place", queued >= 0 && promised > queued);
		assertTrue("errands off is answered before anything is queued", off >= 0 && off < queued);
		assertTrue("and that answer returns", force.substring(off, queued).contains("return;"));
		assertTrue("a forced errand that cannot start here is told",
			source().contains("errands.setOnForcedMiss("));
	}

	/**
	 * With ::follower chatwatch on, each echo was a CONSOLE line, posted as a
	 * chat message, and echoed again, endlessly; every status line also went
	 * to the rules as a chat event. Both stop at the top of the handler.
	 */
	@Test
	public void thePluginsOwnLinesAreNeitherEchoedNorHeardByTheRules() throws IOException
	{
		assertTrue("status lines are CONSOLE lines - the lint has lost its place",
			method("private void sendStatus(String message)").contains(".type(ChatMessageType.CONSOLE)"));
		String chat = method("public void onChatMessage(ChatMessage event)");
		int skip = chat.indexOf("if (event.getType() == ChatMessageType.CONSOLE)");
		int echo = chat.indexOf("if (watchChat)");
		int rules = chat.indexOf("speechEngine.dispatch(TriggerEvent.chat(");
		assertTrue("the lint has lost its place", echo >= 0 && rules >= 0);
		assertTrue("CONSOLE lines return before the echo and the rules",
			skip >= 0 && skip < echo && skip < rules
				&& chat.substring(skip, echo).contains("return;"));
	}

	/** where and status printed "at WorldPoint(x=3222, y=3218, plane=0)", or "at null". */
	@Test
	public void aTileIsPrintedAsNumbersAndNotAtAllBeforeItIsKnown()
	{
		assertEquals(" at (3222, 3218, 0)",
			FollowerPlugin.atTile(new net.runelite.api.coords.WorldPoint(3222, 3218, 0)));
		assertEquals("", FollowerPlugin.atTile(null));
	}

	/** status dumped the skilling state, raw item ids and all, to everyone. */
	@Test
	public void theSkillingDumpIsADevelopersOnly() throws IOException
	{
		String source = source();
		int status = source.indexOf("case \"status\":");
		assertTrue("the lint has lost its place", status >= 0);
		String body = source.substring(status, source.indexOf("break;", status));
		int dump = body.indexOf("sendStatus(\"Skilling: \"");
		assertTrue("the lint has lost its place", dump >= 0);
		assertTrue("the skilling line is said only in developer mode",
			body.substring(0, dump).contains("if (config.developerMode())"));
		assertFalse("no raw tile", body.contains("+ \" at \" + speechEngine"));
	}

	/**
	 * A phrases.json with a syntax error at startup left the follower silent
	 * and nothing said why: the files are read before there is a chat, and
	 * their problems were told only on a reload. They are told at the login.
	 */
	@Test
	public void whatTheFilesGotWrongAtStartupIsToldAtTheLogin() throws IOException
	{
		String source = source();
		int read = source.indexOf("ruleLoader.initialise(dataDir, true);");
		assertTrue("the lint has lost its place", read >= 0);
		assertTrue("each start owes the telling afresh",
			source.substring(read, source.indexOf("dialogLoader.initialise(dataDir, true);", read))
				.contains("loadErrorsReported = false;"));
		int login = source.indexOf("if (freshLogin)\n\t\t\t\t{");
		assertTrue("the lint has lost its place", login >= 0);
		assertTrue("the login tells them",
			source.substring(login, source.indexOf("TriggerEvent.Type.LOGIN", login)).contains("reportLoadErrors();"));
		String report = method("private void reportLoadErrors()");
		assertTrue("rules and dialogs both", report.contains("reportRuleErrors();")
			&& report.contains("dialogLoader.getErrors()"));
		assertTrue("once per start", report.contains("if (loadErrorsReported)"));
	}

	/**
	 * Every settings change and outfit edit rebuilds the follower, and one bad
	 * outfit string repeated its warning on each of them.
	 */
	@Test
	public void anOutfitWarningIsToldOnceUntilItChanges() throws IOException
	{
		String resolve = method("private Outfit resolveOutfit()");
		int said = resolve.indexOf("sendStatus(warning)");
		assertTrue("the lint has lost its place", said >= 0);
		assertTrue("told only when it differs from the last told",
			resolve.substring(0, said).contains("!warning.equals(lastOutfitWarning)"));
		assertTrue("and remembered", resolve.substring(said).contains("lastOutfitWarning = warning;"));

		String rebuild = method("private void rebuildFollower()");
		int failed = rebuild.indexOf("sendStatus(\"Couldn't build the follower model. \"");
		assertTrue("the lint has lost its place", failed >= 0);
		assertTrue("a failed build is told once",
			rebuild.substring(0, failed).contains("if (!buildFailureTold)"));
		assertTrue("until a build succeeds", rebuild.substring(failed).contains("buildFailureTold = false;"));
	}
}
