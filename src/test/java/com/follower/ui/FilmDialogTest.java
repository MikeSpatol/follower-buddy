package com.follower.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The film window's typed lines, and its one door to the follower.
 *
 * <p>A line the game's overhead font cannot draw is refused before it is
 * said, and a long one is said with a warning, since overhead text never
 * wraps. The window itself cannot be built headless, so the threading it
 * promises is read out of its source, as LifecycleGlueTest reads the
 * plugin's.
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
		for (String pasted : new String[]{"Wait \u2014 what?", "\u201cHello\u201d", "Don\u2019t", "caf\u00e9",
			"A <col=ff0000>red</col> line"})
		{
			assertNotNull(pasted, FilmDialog.refusal(pasted));
		}
		assertEquals("Type a line first.", FilmDialog.refusal(""));
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
}
