package com.follower.ui;

import com.follower.speech.Conversation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The film window's Conversation tab: how a line reads in its list, and
 * that Play and Stop reach the game only through the window's one door to
 * the client thread. The tab cannot be built headless, so the door is read
 * out of its source, as FilmDialogTest reads the window's.
 */
public class ConversationPanelTest
{
	@Test
	public void aRowSaysWhoHowWithWhatFaceAndWhat()
	{
		assertEquals("1. You (box, Happy): Morning!",
			ConversationPanel.row(0, new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
				Conversation.Expression.HAPPY, "Morning!"), "Bob"));
		assertEquals("2. Bob (chat): Two lines here",
			ConversationPanel.row(1, new Conversation.Line(Conversation.Speaker.FOLLOWER,
				Conversation.Display.CHAT, Conversation.Expression.SAD, "Two lines\n  here"), "Bob"));
		assertEquals("3. You (box, Neutral): ...",
			ConversationPanel.row(2, new Conversation.Line(), "Bob"));
	}

	@Test
	public void playAndStopGoThroughTheWindowsDoor() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/ui/ConversationPanel.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
		assertTrue("Play hands the conversation over", code.contains("act.accept(c -> c.play(script), null);"));
		assertTrue("Stop does too", code.contains("act.accept(c -> c.stopConversation(), null)"));
		assertFalse("the tab never reaches the game itself", code.contains("net.runelite.api"));
		assertFalse(code.contains("clientThread"));

		String window = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/ui/FilmDialog.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		assertTrue("the tab is given the window's door",
			window.contains("new ConversationPanel(this::act, store, followerName, status::setText)"));
		assertTrue("and Esc in the window stops it through the same door",
			window.contains("registerKeyboardAction(e -> act(c -> c.stopConversation(), null)"));
	}
}
