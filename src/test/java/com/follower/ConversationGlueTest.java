package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where the film window's conversation meets the plugin, read from the
 * plugin's own source, as LifecycleGlueTest reads the rest: the handlers
 * need a client, a config manager and an event bus, which no harness has.
 *
 * <p>A chat line is said through ConversationPlayer.sayInChat alone, whose
 * calls ConversationPlayerTest records, so nothing it says can reach the
 * server; whatever ends the scene ends the conversation; it is stepped every
 * client tick; Esc stops it; and the follower's Talk-to waits while it plays.
 */
public class ConversationGlueTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static String source() throws IOException
	{
		assertTrue("cannot read " + PLUGIN, Files.isRegularFile(PLUGIN));
		return new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/** One method, from its signature to the brace that closes it at the given depth. */
	private static String method(String signature, String indent) throws IOException
	{
		String source = source();
		int start = source.indexOf(signature);
		assertTrue("the plugin has no " + signature + " - the lint has lost its place", start >= 0);
		return source.substring(start, source.indexOf("\n" + indent + "}\n", start));
	}

	@Test
	public void aChatLineIsSaidThroughTheOneLocalDoorOnly() throws IOException
	{
		String say = method("public void say(com.follower.speech.Conversation.Line line, long ms)", "\t\t\t");
		assertTrue(say.contains("com.follower.speech.ConversationPlayer.sayInChat(client, filmSet, followerName(),"));
		assertFalse("the client is touched only through sayInChat: " + say, say.contains("client."));
	}

	@Test
	public void whateverEndsTheSceneEndsTheConversationFirst() throws IOException
	{
		String end = method("private void endFilming()", "\t");
		int stop = end.indexOf("conversation.stop(null);");
		assertTrue("ending the scene stops it", stop >= 0);
		assertTrue("before the check, as it plays without the follower out",
			stop < end.indexOf("if (!filming)"));
	}

	@Test
	public void itIsSteppedEveryClientTick() throws IOException
	{
		String tick = method("public void onClientTick(net.runelite.api.events.ClientTick event)", "\t");
		assertTrue(tick.contains("conversation.update(System.currentTimeMillis());"));
	}

	@Test
	public void escInTheGameStopsItAndTheListenerGoesWithThePlugin() throws IOException
	{
		String source = source();
		assertTrue(source.contains("keyManager.registerKeyListener(escapeStopsTheConversation);"));
		assertTrue(source.contains("keyManager.unregisterKeyListener(escapeStopsTheConversation);"));
		String listener = source.substring(source.indexOf("escapeStopsTheConversation ="));
		listener = listener.substring(0, listener.indexOf("\n\t};\n"));
		assertTrue(listener.contains("VK_ESCAPE"));
		assertTrue("on the client thread", listener.contains("clientThread.invoke(() -> playing.stop(\"Stopped.\"));"));
	}

	@Test
	public void talkToWaitsWhileItPlays() throws IOException
	{
		String talk = method("private void startTalking()", "\t");
		int check = talk.indexOf("conversation.isPlaying()");
		assertTrue(check >= 0);
		assertTrue("before anything opens", check < talk.indexOf("dialog.startNextTick("));
	}
}
