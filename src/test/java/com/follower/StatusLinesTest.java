package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

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
}
