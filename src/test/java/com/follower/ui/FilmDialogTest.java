// public:file posing
package com.follower.ui;

import com.follower.follower.FilmSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The film window's pose list, its typed lines, and its one door to the
 * follower.
 *
 * <p>The list narrows to a search, a group and, unless Show all is ticked,
 * the named poses. A line the game's overhead font cannot draw is refused
 * before it is said, and a long one is said with a warning, since overhead
 * text never wraps. The window itself cannot be built headless, so the
 * threading it promises is read out of its source, as LifecycleGlueTest
 * reads the plugin's.
 */
public class FilmDialogTest
{
	@Test
	public void plainLinesAreSaid()
	{
		assertNull(FilmDialog.refusal("Well, that went well. Again?"));
		assertNull(FilmDialog.refusal("It's 3 o'clock - (roughly) 50% done!"));
	}

	@Test
	public void whatTheFontCannotDrawIsRefused()
	{
		for (String pasted : new String[]{"\u65e5\u672c", "An emoji \ud83d\ude00", "A <col=ff0000>red</col> line"})
		{
			assertNotNull(pasted, FilmDialog.refusal(pasted));
		}
		assertEquals("Type a line first.", FilmDialog.refusal(""));
		assertEquals("a character is named whole, not half an emoji",
			"The game can't show '\ud83d\ude00' overhead. Use plain letters and punctuation.",
			FilmDialog.refusal("An emoji \ud83d\ude00"));
	}

	@Test
	public void aPastedDashOrCurlyQuoteIsSaidPlainAsInTheConversationTab()
	{
		// QA2, 15 Oct: the Say boxes refused what the Conversation tab
		// quietly wrote plain. One check now serves both.
		for (String pasted : new String[]{"Wait \u2014 what?", "\u201cHello\u201d", "Don\u2019t", "caf\u00e9"})
		{
			assertNull(pasted, FilmDialog.refusal(pasted));
			assertNull(pasted, FilmDialog.undrawable(pasted));
		}
		assertEquals("Wait - \"what\"? Don't...", FilmDialog.asSaid("Wait \u2014 \u201cwhat\u201d? Don\u2019t\u2026"));
		assertEquals("\u65e5", FilmDialog.undrawable("ok \u65e5\u672c"));
		assertNull("a typed question mark is the player's own", FilmDialog.undrawable("Why?"));
	}

	@Test
	public void aLongLineIsSaidWithAWarning()
	{
		String fits = "x".repeat(FilmDialog.LONG_LINE);
		assertEquals("Said.", FilmDialog.done("Said.", fits));
		String longer = fits + "y";
		assertEquals("Said. At 81 characters it may run off the screen: overhead text never wraps.",
			FilmDialog.done("Said.", longer));
		assertNull("warned, not refused", FilmDialog.refusal(longer));
	}

	@Test
	public void theSearchFindsEveryWordOfANameOrAnId()
	{
		List<FilmSet.Pose> found = FilmDialog.filter(FilmSet.POSES, "  Book READ ", FilmDialog.ALL_GROUPS, false);
		assertTrue(ids(found).contains(1350));
		for (FilmSet.Pose pose : found)
		{
			String name = pose.name.toLowerCase();
			assertTrue(pose.name, name.contains("book") && name.contains("read"));
		}
		assertEquals(Collections.singletonList(4751),
			ids(FilmDialog.filter(FilmSet.POSES, "4751", FilmDialog.ALL_GROUPS, false)));
		assertTrue(FilmDialog.filter(FilmSet.POSES, "no pose is called this", FilmDialog.ALL_GROUPS, true).isEmpty());
	}

	@Test
	public void aGroupNarrowsTheListToItself()
	{
		List<FilmSet.Pose> capes = FilmDialog.filter(FilmSet.POSES, "", "Skill capes", false);
		assertTrue(ids(capes).containsAll(Arrays.asList(4959, 4961, 7121)));
		for (FilmSet.Pose pose : capes)
		{
			assertEquals(pose.name, "Skill capes", pose.group);
		}
		assertTrue("nothing to read in the capes", FilmDialog.filter(FilmSet.POSES, "book", "Skill capes", true).isEmpty());
	}

	@Test
	public void showAllAddsTheRestAndOnlyThat()
	{
		List<FilmSet.Pose> named = FilmDialog.filter(FilmSet.POSES, "", FilmDialog.ALL_GROUPS, false);
		for (FilmSet.Pose pose : named)
		{
			assertTrue(pose.name + " is only for Show all", pose.tier != 'C');
		}
		assertTrue(named.size() > 1000);
		assertEquals(FilmSet.POSES, FilmDialog.filter(FilmSet.POSES, "", FilmDialog.ALL_GROUPS, true));
		assertFalse("a jaguar's death is not offered first",
			ids(FilmDialog.filter(FilmSet.POSES, "jaguar death", FilmDialog.ALL_GROUPS, false)).contains(10849));
		assertTrue(ids(FilmDialog.filter(FilmSet.POSES, "jaguar death", FilmDialog.ALL_GROUPS, true)).contains(10849));
	}

	@Test
	public void aRowSaysWhetherItHoldsAndWhetherItHoldsSomething()
	{
		assertEquals("Read a book  [hold]  [prop]", FilmDialog.row(FilmSet.pose(1350)));
		assertEquals("Wave  [once]", FilmDialog.row(FilmSet.pose(863)));
		assertEquals("a snowball the game draws nothing for is no prop",
			"Throw a snowball  [once]", FilmDialog.row(FilmSet.pose(5063)));
	}

	private static List<Integer> ids(List<FilmSet.Pose> poses)
	{
		List<Integer> out = new ArrayList<>();
		for (FilmSet.Pose pose : poses)
		{
			out.add(pose.id);
		}
		return out;
	}

	@Test
	public void everyPressGoesOverToTheClientThread() throws IOException
	{
		// The follower and the player may only be touched on the client
		// thread, and the window's presses arrive on the Swing thread. The
		// crew is reached in one place, inside the hand-over, and what comes
		// back is shown on the Swing thread again.
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/ui/FilmDialog.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		int act = source.indexOf("private void act(Function<Crew, String> work, String done)");
		assertTrue("the window has no act - the lint has lost its place", act >= 0);
		String body = source.substring(act, source.indexOf("\n\t}\n", act));
		int over = body.indexOf("clientThread.accept(() ->");
		assertTrue("act hands the work to the client thread", over >= 0);
		assertTrue("the crew is called there", body.indexOf("work.apply(crew)") > over);
		assertTrue("and the answer comes back on the Swing thread",
			body.indexOf("SwingUtilities.invokeLater(") > body.indexOf("work.apply(crew)"));

		String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
		int uses = 0;
		for (int at = code.indexOf("crew"); at >= 0; at = code.indexOf("crew", at + 1))
		{
			uses++;
		}
		// The field, the parameter, both sides of the assignment and act's one call.
		assertEquals("the crew is reached somewhere other than act", 5, uses);
		assertTrue("closing the window cuts the scene through act",
			source.contains("public void windowClosing(java.awt.event.WindowEvent e)\n\t\t\t{\n\t\t\t\tact(c ->\n"
				+ "\t\t\t\t{\n\t\t\t\t\tc.cut();"));
	}

	@Test
	public void theWindowFitsAboveALaptopsTaskbar()
	{
		// 1366x768 with a 40px taskbar leaves 728.
		assertEquals(700, FilmDialog.heightOnScreen(700, new java.awt.Rectangle(0, 0, 1366, 768),
			new java.awt.Insets(0, 0, 40, 0)));
		assertEquals(560, FilmDialog.heightOnScreen(700, new java.awt.Rectangle(0, 0, 1024, 600),
			new java.awt.Insets(0, 0, 40, 0)));
	}

	@Test
	public void bothTabsFitInTheWindowAsItOpens() throws Exception
	{
		// QA2, 15 Oct: it opened 760 tall, and the status line went under a
		// laptop's taskbar. Built, never shown.
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		java.nio.file.Path dir = Files.createTempDirectory("film");
		com.follower.speech.ConversationStore store = new com.follower.speech.ConversationStore(new com.google.gson.Gson());
		store.load(dir);
		FilmDialog.Crew none = (FilmDialog.Crew) java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{FilmDialog.Crew.class},
			(p, m, a) -> m.getReturnType() == int.class ? -1 : null);
		// Measured in the client's own look, put back afterwards.
		javax.swing.LookAndFeel before = javax.swing.UIManager.getLookAndFeel();
		FilmDialog[] made = new FilmDialog[1];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			net.runelite.client.ui.laf.RuneLiteLAF.setup();
			made[0] = new FilmDialog(none, Runnable::run, store, () -> "Bob");
		});
		FilmDialog film = made[0];
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			film.validate();
			assertTrue("opens " + film.getHeight() + " tall", film.getHeight() <= 700);
			java.awt.Dimension wants = film.getContentPane().getPreferredSize();
			assertTrue("its content wants " + wants.height + " and gets " + film.getContentPane().getHeight(),
				wants.height <= film.getContentPane().getHeight());
			film.dispose();
			try
			{
				javax.swing.UIManager.setLookAndFeel(before);
			}
			catch (javax.swing.UnsupportedLookAndFeelException e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	@Test
	public void itCannotBeDraggedSmallerThanItsHintAndThePoseListStopsShortOfItsButtons() throws Exception
	{
		// QA2, 15 Oct: dragged to 560 wide the hint was cut ("right-click
		// the g"), and the pose list ran into Again with no gap, so its last
		// row looked cut by the buttons.
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		com.follower.speech.ConversationStore store = new com.follower.speech.ConversationStore(new com.google.gson.Gson());
		store.load(Files.createTempDirectory("film"));
		FilmDialog.Crew none = (FilmDialog.Crew) java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{FilmDialog.Crew.class},
			(p, m, a) -> m.getReturnType() == int.class ? -1 : null);
		javax.swing.LookAndFeel before = javax.swing.UIManager.getLookAndFeel();
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			net.runelite.client.ui.laf.RuneLiteLAF.setup();
			FilmDialog film = new FilmDialog(none, Runnable::run, store, () -> "Bob");
			try
			{
				film.setSize(560, 600);
				film.validate();
				assertTrue("held at " + film.getWidth() + " wide", film.getWidth() >= 620);
				for (java.awt.Component c : all(film.getContentPane()))
				{
					if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getText().startsWith("<html>"))
					{
						assertTrue("the hint wants " + c.getPreferredSize().width + " and has " + c.getWidth(),
							c.getPreferredSize().width <= c.getWidth());
					}
				}

				javax.swing.JButton again = null;
				javax.swing.JScrollPane poses = null;
				for (java.awt.Component c : all(film.getContentPane()))
				{
					if (c instanceof javax.swing.JButton && "Again".equals(((javax.swing.JButton) c).getText()))
					{
						again = (javax.swing.JButton) c;
					}
					if (c instanceof javax.swing.JScrollPane && poses == null
						&& ((javax.swing.JScrollPane) c).getViewport().getView() instanceof javax.swing.JList)
					{
						poses = (javax.swing.JScrollPane) c;
					}
				}
				int listBottom = javax.swing.SwingUtilities.convertPoint(poses.getParent(), 0,
					poses.getY() + poses.getHeight(), film.getContentPane()).y;
				int buttonsTop = javax.swing.SwingUtilities.convertPoint(again.getParent(), 0, again.getY(),
					film.getContentPane()).y;
				assertTrue("the list ends at " + listBottom + " and Again starts at " + buttonsTop,
					buttonsTop - listBottom >= 4);
			}
			finally
			{
				film.dispose();
				try
				{
					javax.swing.UIManager.setLookAndFeel(before);
				}
				catch (javax.swing.UnsupportedLookAndFeelException e)
				{
					throw new IllegalStateException(e);
				}
			}
		});
	}

	private static List<java.awt.Component> all(java.awt.Container root)
	{
		List<java.awt.Component> out = new ArrayList<>();
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
