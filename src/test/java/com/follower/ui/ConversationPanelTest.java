// public:file posing
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
		// QA2, 15 Oct: the row kept what was typed while the game showed '?'.
		assertEquals("4. Bob (box, Neutral): and ??? - \"so\"",
			ConversationPanel.row(3, new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.BOX,
				Conversation.Expression.NEUTRAL, "and 日本語 — “so”"), "Bob"));
	}

	@Test
	public void playSaysWhichLineTheGameWillShowOtherwise()
	{
		// QA2, 15 Oct: a 117-character chat line and a line of CJK played
		// with no word, though the Say boxes warn of the one and refuse the
		// other. The same check now speaks for both tabs.
		java.util.List<Conversation.Line> lines = new java.util.ArrayList<>();
		lines.add(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, "Hello — “there”."));
		lines.add(new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.BOX,
			Conversation.Expression.NEUTRAL, "x".repeat(200)));
		assertEquals("pasted punctuation is written plain, and a long box pages", null,
			ConversationPanel.lineNote(lines));

		lines.add(new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, "y".repeat(117)));
		assertEquals("Line 3 is 117 characters. A chat line in the game stops at 80, and overhead text"
			+ " never wraps.",
			ConversationPanel.lineNote(lines));

		lines.add(1, new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
			Conversation.Expression.NEUTRAL, "and 日本"));
		assertEquals("the first line it finds", "Line 2 has characters the game can't show. They will appear as ?.",
			ConversationPanel.lineNote(lines));
	}

	@Test
	public void theControlsAreOnOnlyWhereTheyDoSomething() throws Exception
	{
		// QA2, 15 Oct: Seconds stayed on under Click to continue, which never
		// reads it, and refused 601 there; Remove, Up, Down, Load and Delete
		// were on with nothing to work on; Tab typed a tab into Text; Enter
		// in Name did nothing.
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		com.follower.speech.ConversationStore store = new com.follower.speech.ConversationStore(new com.google.gson.Gson());
		store.load(Files.createTempDirectory("conv"));
		Conversation take = new Conversation();
		take.name = "Take one";
		take.lines.add(new Conversation.Line());
		assertTrue(store.save(take));
		java.util.List<String> said = new java.util.ArrayList<>();
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			ConversationPanel panel = new ConversationPanel((w, d) -> { }, store, () -> "Bob", said::add);
			javax.swing.JTextField seconds = null;
			javax.swing.JTextField name = null;
			javax.swing.JTextArea text = null;
			for (java.awt.Component c : all(panel))
			{
				if (c instanceof javax.swing.JTextField && ((javax.swing.JTextField) c).getColumns() == 4)
				{
					seconds = (javax.swing.JTextField) c;
				}
				else if (c instanceof javax.swing.JTextField && name == null)
				{
					name = (javax.swing.JTextField) c;
				}
				else if (c instanceof javax.swing.JTextArea)
				{
					text = (javax.swing.JTextArea) c;
				}
			}

			assertFalse("Click to continue reads no seconds", seconds.isEnabled());
			radio(panel, "Auto").setSelected(true);
			assertTrue(seconds.isEnabled());
			radio(panel, "Click to continue").setSelected(true);
			assertFalse(seconds.isEnabled());

			for (String off : new String[]{"Remove", "Up", "Down", "Load", "Delete"})
			{
				assertFalse(off + " with nothing to work on", button(panel, off).isEnabled());
			}
			press(panel, "Add");
			assertTrue("Remove with a line picked", button(panel, "Remove").isEnabled());
			// QA3: Up on the first line and Down on the last did nothing.
			assertFalse("Up with the only line picked", button(panel, "Up").isEnabled());
			assertFalse("Down with the only line picked", button(panel, "Down").isEnabled());
			press(panel, "Add");
			assertTrue("Up on the second of two", button(panel, "Up").isEnabled());
			assertFalse("Down on the last", button(panel, "Down").isEnabled());
			press(panel, "Up");
			assertFalse("Up on the first", button(panel, "Up").isEnabled());
			assertTrue("Down on the first of two", button(panel, "Down").isEnabled());
			pickSaved(panel, "Take one");
			assertTrue(button(panel, "Load").isEnabled());
			assertTrue(button(panel, "Delete").isEnabled());

			assertTrue("Tab leaves the Text box", text.getFocusTraversalKeys(
				java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS)
				.contains(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, 0)));

			seconds.setText("601");
			name.setText("Take two");
			name.postActionEvent();
			assertTrue("Enter in Name saves, and seconds Click never reads are no bar: " + said,
				store.get("Take two") != null);
			radio(panel, "Auto").setSelected(true);
			name.setText("Take three");
			name.postActionEvent();
			assertEquals("Auto does read them", null, store.get("Take three"));
		});
	}

	private static javax.swing.JRadioButton radio(ConversationPanel panel, String text)
	{
		for (java.awt.Component c : all(panel))
		{
			if (c instanceof javax.swing.JRadioButton && text.equals(((javax.swing.JRadioButton) c).getText()))
			{
				return (javax.swing.JRadioButton) c;
			}
		}
		throw new AssertionError("no " + text + " choice");
	}

	private static javax.swing.JButton button(ConversationPanel panel, String text)
	{
		for (java.awt.Component c : all(panel))
		{
			if (c instanceof javax.swing.JButton && text.equals(((javax.swing.JButton) c).getText()))
			{
				return (javax.swing.JButton) c;
			}
		}
		throw new AssertionError("no " + text + " button");
	}

	@Test
	public void playAndStopGoThroughTheWindowsDoor() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/ui/ConversationPanel.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
		assertTrue("Play hands the conversation over, with any note on how a line will show",
			code.contains("act.accept(c -> c.play(script), lineNote(script.lines));"));
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

	@Test
	public void deletingTheLoadedOneLeavesTheTabsLinesAsUnsaved() throws Exception
	{
		// QA3: the tab's lines were the only copy once the saved one was
		// deleted, and the next Load dropped them without asking.
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		com.follower.speech.ConversationStore store = new com.follower.speech.ConversationStore(new com.google.gson.Gson());
		store.load(Files.createTempDirectory("conv"));
		for (String name : new String[]{"Doomed", "Next"})
		{
			Conversation take = new Conversation();
			take.name = name;
			take.lines.add(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
				Conversation.Expression.NEUTRAL, name + " line."));
			assertTrue(store.save(take));
		}
		int[] asked = {0};
		java.util.List<String> deleteAsked = new java.util.ArrayList<>();
		ConversationPanel[] made = new ConversationPanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			made[0] = new ConversationPanel((w, d) -> { }, store, () -> "Bob", s -> { })
			{
				@Override
				int askToSave()
				{
					asked[0]++;
					return 2;
				}

				@Override
				boolean confirmDelete(String picked)
				{
					deleteAsked.add(picked);
					return true;
				}
			};
		});
		ConversationPanel panel = made[0];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			pickSaved(panel, "Doomed");
			press(panel, "Load");
			pickSaved(panel, "Doomed");
			press(panel, "Delete");
			assertEquals(java.util.Collections.singletonList("Doomed"), deleteAsked);
			assertEquals(null, store.get("Doomed"));

			pickSaved(panel, "Next");
			press(panel, "Load");
			assertEquals("asked before the only copy went", 1, asked[0]);
			assertEquals("and Cancel kept it", "Doomed line.", panel.script().lines.get(0).text);
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
