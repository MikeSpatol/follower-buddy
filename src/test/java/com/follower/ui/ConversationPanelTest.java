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

	@Test
	public void loadAsksBeforeThrowingAwayUnsavedLines() throws Exception
	{
		// QA2, 15 Oct: Load replaced four lines, one never saved, with the
		// saved three and said nothing. Messages and Conversations both ask.
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		com.follower.speech.ConversationStore store = new com.follower.speech.ConversationStore(new com.google.gson.Gson());
		store.load(Files.createTempDirectory("conv"));
		Conversation take = new Conversation();
		take.name = "Take one";
		take.lines.add(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
			Conversation.Expression.NEUTRAL, "Morning."));
		assertTrue(store.save(take));

		int[] answer = {2};
		int[] asked = {0};
		java.util.List<String> said = new java.util.ArrayList<>();
		ConversationPanel[] made = new ConversationPanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			made[0] = new ConversationPanel((w, d) -> { }, store, () -> "Bob", said::add)
			{
				@Override
				int askToSave()
				{
					asked[0]++;
					return answer[0];
				}
			};
		});
		ConversationPanel panel = made[0];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			pickSaved(panel, "Take one");
			press(panel, "Load");
			assertEquals("nothing written yet, so nothing to ask", 0, asked[0]);
			assertEquals(1, panel.script().lines.size());

			press(panel, "Add");
			assertEquals(2, panel.script().lines.size());
			pickSaved(panel, "Take one");
			press(panel, "Load");
			assertEquals("asked before the new line went", 1, asked[0]);
			assertEquals("Cancel keeps it", 2, panel.script().lines.size());

			answer[0] = 1;
			press(panel, "Load");
			assertEquals("Discard loads", 1, panel.script().lines.size());

			press(panel, "Add");
			for (java.awt.Component c : all(panel))
			{
				if (c instanceof javax.swing.JTextArea)
				{
					((javax.swing.JTextArea) c).setText("Lovely day.");
				}
			}
			answer[0] = 0;
			pickSaved(panel, "Take one");
			press(panel, "Load");
			assertEquals("Save keeps it in the store first", 2, store.get("Take one").lines.size());
			assertEquals(2, panel.script().lines.size());

			press(panel, "Load");
			assertEquals("and once saved there is nothing to ask", 3, asked[0]);
		});
	}

	private static void pickSaved(ConversationPanel panel, String name)
	{
		for (java.awt.Component c : all(panel))
		{
			if (c instanceof javax.swing.JComboBox && ((javax.swing.JComboBox<?>) c).getItemCount() > 0
				&& ConversationPanel.NO_SAVED.equals(((javax.swing.JComboBox<?>) c).getItemAt(0)))
			{
				((javax.swing.JComboBox<?>) c).setSelectedItem(name);
			}
		}
	}

	private static void press(ConversationPanel panel, String text)
	{
		for (java.awt.Component c : all(panel))
		{
			if (c instanceof javax.swing.JButton && text.equals(((javax.swing.JButton) c).getText()))
			{
				((javax.swing.JButton) c).doClick(0);
				return;
			}
		}
		throw new AssertionError("no " + text + " button");
	}

	private static java.util.List<java.awt.Component> all(java.awt.Container root)
	{
		java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
		for (java.awt.Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof java.awt.Container)
			{
				out.addAll(all((java.awt.Container) c));
			}
		}
		return out;
	}
}
