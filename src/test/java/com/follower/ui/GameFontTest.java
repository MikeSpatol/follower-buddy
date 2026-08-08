package com.follower.ui;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The glyph renderer every line the follower says goes through.
 *
 * <p>Its one sharp edge is how a character becomes a glyph: {@code charAt(i) &
 * 0xFF}. Nothing is rejected and nothing is mapped, so a character above
 * U+00FF is silently drawn as a DIFFERENT character, and the text on screen is
 * wrong in a way no log will ever mention. That is pinned here so the rule
 * cannot quietly change, and so the phrase-file check has something to agree
 * with.
 */
public class GameFontTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private GameFont overhead;
	private GameFont dialog;

	@Before
	public void setUp() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		Path empty = folder.newFolder().toPath();
		fonts.load(empty);
		overhead = fonts.getByName("b12_full");
		dialog = fonts.get(497);
		assertNotNull(overhead);
		assertNotNull(dialog);
	}

	private static Graphics2D canvas()
	{
		return new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB).createGraphics();
	}

	// ------------------------------------------------------------- measuring

	@Test
	public void anEmptyStringHasNoWidth()
	{
		assertEquals(0, overhead.stringWidth(""));
	}

	@Test
	public void aNullStringIsNotACrash()
	{
		assertEquals(0, overhead.stringWidth(null));
		overhead.drawTop(canvas(), null, 0, 0, 0xFFFFFF, false);
	}

	@Test
	public void widthGrowsWithTheText()
	{
		int one = overhead.stringWidth("a");
		int two = overhead.stringWidth("aa");
		assertTrue("a single character should measure something", one > 0);
		assertEquals("measuring should be the sum of its advances", one * 2, two);
	}

	@Test
	public void aRealSentenceMeasuresSensibly()
	{
		int width = overhead.stringWidth("The quick brown fox jumps over the lazy dog");
		assertTrue("suspiciously narrow: " + width, width > 100);
		assertTrue("suspiciously wide: " + width, width < 1000);
	}

	@Test
	public void bothFontsHaveARealLineHeight()
	{
		assertTrue(overhead.getLineHeight() > 0);
		assertTrue(dialog.getLineHeight() > 0);
		assertTrue(overhead.getAscent() > 0);
	}

	// -------------------------------------------------------------- drawing

	@Test
	public void drawingActuallyPutsPixelsDown()
	{
		BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		overhead.drawTop(graphics, "Hello", 10, 10, 0xFF0000, false);

		boolean any = false;
		for (int x = 0; x < image.getWidth() && !any; x++)
		{
			for (int y = 0; y < image.getHeight(); y++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					any = true;
					break;
				}
			}
		}
		assertTrue("nothing was drawn at all", any);
	}

	@Test
	public void everyDrawEntryPointSurvivesOrdinaryText()
	{
		Graphics2D graphics = canvas();
		overhead.drawTop(graphics, "Hello", 10, 10, 0xFFFFFF, false);
		overhead.drawTop(graphics, "Hello", 10, 10, 0xFFFFFF, true);
		overhead.drawBaseline(graphics, "Hello", 10, 40, 0xFFFFFF, false);
		overhead.drawCenteredBaseline(graphics, "Hello", 200, 40, 0xFFFFFF, true);
		overhead.drawCenteredOverhead(graphics, "Hello", 200, 60, 0x00FF00);
	}

	@Test
	public void drawingOffTheEdgeOfTheCanvasIsHarmless()
	{
		Graphics2D graphics = canvas();
		overhead.drawTop(graphics, "Hello", -500, -500, 0xFFFFFF, false);
		overhead.drawTop(graphics, "Hello", 100000, 100000, 0xFFFFFF, false);
	}

	// ------------------------------------------------------ the sharp edge

	@Test
	public void everyLatin1CharacterCanBeMeasuredAndDrawn()
	{
		Graphics2D graphics = canvas();
		for (int c = 0; c <= 0xFF; c++)
		{
			String text = String.valueOf((char) c);
			assertTrue("negative width for char " + c, overhead.stringWidth(text) >= 0);
			overhead.drawTop(graphics, text, 10, 10, 0xFFFFFF, false);
			dialog.drawTop(graphics, text, 10, 10, 0xFFFFFF, false);
		}
	}

	/**
	 * The behaviour that makes the phrase-file check necessary. An em-dash is
	 * U+2014; the low byte is 0x14, so it is drawn as whatever sits there - not
	 * as the em-dash glyph the font really holds at 0x97, and not as nothing.
	 */
	@Test
	public void aCharacterPastLatin1IsTruncatedNotRejected()
	{
		assertEquals("an em-dash is measured as the character at its low byte",
			overhead.stringWidth(String.valueOf((char) 0x14)),
			overhead.stringWidth("—"));

		assertEquals("and as a different width from the glyph cp1252 would pick",
			overhead.stringWidth(String.valueOf((char) 0x97)),
			overhead.stringWidth(""));
	}

	@Test
	public void charactersWayPastLatin1StillDoNotThrow()
	{
		Graphics2D graphics = canvas();
		String[] awkward = {
			"—", "’", "…", "€", "�",
			"中文", "😀",
		};
		for (String text : awkward)
		{
			assertTrue(overhead.stringWidth(text) >= 0);
			overhead.drawTop(graphics, text, 10, 10, 0xFFFFFF, false);
			overhead.drawCenteredOverhead(graphics, text, 200, 60, 0xFFFFFF);
		}
	}

	@Test
	public void aVeryLongLineDoesNotOverflowItsMeasurement()
	{
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			text.append('W');
		}
		int width = overhead.stringWidth(text.toString());
		assertTrue("width overflowed to a negative: " + width, width > 0);
	}
}
