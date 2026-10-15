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
 * <p>Its one sharp edge is how a character becomes a glyph. It follows the
 * client: a no-break space is a space, and the rest goes through cp1252,
 * with a '?' for what has no byte there. That is pinned here so the rule
 * cannot quietly change.
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
	 * Past Latin-1 a character goes through cp1252, as the client's font
	 * renderer sends it: an em-dash, U+2014, is the glyph the font holds at
	 * 0x97, a curly apostrophe the one at 0x92, and a character with no
	 * cp1252 byte is drawn as a '?'. It used to be cut to its low byte, so an
	 * em-dash was drawn as glyph 0x14. The phrase-file check still keeps them
	 * out of the shipped lines: not every font has a glyph there.
	 */
	@Test
	public void aCharacterPastLatin1IsMappedAsTheClientMapsIt()
	{
		assertEquals("an em-dash is the cp1252 em-dash", 0x97, GameFont.glyphIndex('\u2014'));
		assertEquals("a curly apostrophe is the cp1252 one", 0x92, GameFont.glyphIndex('\u2019'));
		assertEquals("the euro sign", 0x80, GameFont.glyphIndex('\u20AC'));
		assertEquals("Latin-1 is itself", 0xE9, GameFont.glyphIndex('\u00E9'));
		assertEquals("a C1 control has no glyph of its own", '?', GameFont.glyphIndex((char) 0x97));
		assertEquals("and what cp1252 has no byte for is a question mark",
			overhead.stringWidth("?"),
			overhead.stringWidth("\u4E2D"));
	}

	/**
	 * QA, 15 Oct: display names arrive with no-break spaces, and no game font
	 * has a width at 0xA0, so "Sir Zeebos" was measured and drawn as
	 * "SirZeebos" over the follower and in the Talk-to name header. The client
	 * turns one into a plain space first; so does GameFont.
	 */
	@Test
	public void aNoBreakSpaceIsAPlainSpace()
	{
		for (GameFont font : new GameFont[]{overhead, dialog})
		{
			assertEquals(font.stringWidth("Sir Zeebos"), font.stringWidth("Sir\u00A0Zeebos"));
			assertTrue(font.stringWidth("Sir\u00A0Zeebos") > font.stringWidth("SirZeebos"));
		}
	}

	@Test
	public void everyDrawPutsTheSamePixelsForANoBreakSpaceAsForASpace()
	{
		for (int style = 0; style < 4; style++)
		{
			BufferedImage plain = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB);
			BufferedImage nbsp = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB);
			draw(style, plain.createGraphics(), "Sir Zeebos");
			draw(style, nbsp.createGraphics(), "Sir\u00A0Zeebos");
			for (int x = 0; x < plain.getWidth(); x++)
			{
				for (int y = 0; y < plain.getHeight(); y++)
				{
					assertEquals("draw style " + style + " differs at " + x + "," + y,
						plain.getRGB(x, y), nbsp.getRGB(x, y));
				}
			}
		}
	}

	private void draw(int style, Graphics2D g, String text)
	{
		switch (style)
		{
			case 0:
				overhead.drawTop(g, text, 5, 5, 0xFFFFFF, false);
				break;
			case 1:
				overhead.drawTop(g, text, 5, 5, 0xFFFFFF, true);
				break;
			case 2:
				overhead.drawCenteredBaseline(g, text, 100, 25, 0xFFFFFF, true);
				break;
			default:
				overhead.drawCenteredOverhead(g, text, 100, 25, 0xFFFF00);
				break;
		}
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
