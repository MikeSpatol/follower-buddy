package com.follower;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.util.*;
import java.util.Map;
import lombok.*;

/**
 * One of the game's own bitmap fonts, rendered by blitting the cache's actual
 * glyph masks - the same pixels the client puts on screen. A TTF through a
 * system rasterizer never matches exactly (hinting, baselines and advances all
 * differ); these are the real glyphs with the real per-character advances and
 * bearings, dumped by tools/cache-dumper into fonts.json.
 *
 * <p>Draw semantics ported from the client's font renderer ({@code PixFont}):
 * glyphs blit at {@code (x + offsetX, top + offsetY)}, the pen advances by the
 * font's per-character advance, and a shadowed draw plots a black copy at
 * {@code (+1, +1)} first. Overhead text is the one exception - the client
 * draws its black copy at {@code (x, y + 1)}, straight down - so both shadow
 * styles are offered. A character finds its glyph the way the client's
 * does, through {@link #glyphIndex} (the dumped archives all carry 256
 * glyphs and 256 advances).
 */
class GameFont
{
	@RequiredArgsConstructor
	static class Glyph
	{
		final int width,
			height,
			offsetX,
			offsetY;
		final byte[] mask;
	}

	@Getter
	final int ascent;
	final int[] advances;
	final Glyph[] glyphs;

	/** The client's PixFont.height: the tallest glyph mask, its line-stack step. */
	@Getter
	final int lineHeight;

	/** Tinted glyph images, cached per (character, colour). */
	final Map<Long, BufferedImage> tinted = new HashMap<>();

	GameFont(int ascent, int[] advances, Glyph[] glyphs)
	{
		this.ascent = ascent;
		this.advances = advances;
		this.glyphs = glyphs;

		int tallest = 0;
		for (var glyph : glyphs)
		{
			if (glyph != null && glyph.height > tallest)
				tallest = glyph.height;
		}
		this.lineHeight = tallest;
	}

	/** The client's text encoding, the JDK's own table of it. */
	static final Charset CP1252 = Charset.forName("windows-1252");

	/**
	 * The glyph a character is drawn with, as the client picks it (its font
	 * renderer's char width and draws, injected-client 1.13.1): a no-break
	 * space is a space, and everything else goes through cp1252, so a curly
	 * quote or a dash finds the glyph the font holds for it and a character
	 * the font has nothing for is a '?'. QA, 15 Oct: display names arrive
	 * with no-break spaces, which have no width in any of the game fonts, so
	 * "Sir Zeebos" was drawn "SirZeebos".
	 */
	static int glyphIndex(char c)
	{
		if (c == '\u00A0')
			return ' ';
		if ((c > 0 && c < 128) || (c >= 160 && c <= 255))
			return c;
		// What the encoding cannot map, a C1 control or a lone surrogate, it
		// writes as '?', as the client does; and NUL, which it would pass
		// through as 0, is one too.
		return c == 0 ? '?' : String.valueOf(c).getBytes(CP1252)[0] & 0xFF;
	}

	/** The exact pixel width the client would measure for this string. */
	int stringWidth(String text)
	{
		if (text == null)
			return 0;
		int width = 0;
		for (int i = 0; i < text.length(); i++)
			width += advances[glyphIndex(text.charAt(i))];
		return width;
	}

	/** Draws with {@code y} as the TOP of the glyph cell. */
	void drawTop(Graphics2D g, String text, int x, int y, int rgb, boolean shadowed)
	{
		if (text == null)
			return;
		draw(g, text, x, y, rgb, shadowed);
	}

	void draw(Graphics2D g, String text, int x, int y, int rgb, boolean shadowed)
	{
		int pen = x;
		for (int i = 0; i < text.length(); i++)
		{
			int c = glyphIndex(text.charAt(i));
			var glyph = glyphs[c];
			if (glyph != null && glyph.width > 0 && glyph.height > 0)
			{
				if (shadowed)
					g.drawImage(tint(c, 0x000000),
						pen + glyph.offsetX + 1, y + glyph.offsetY + 1, null);
				g.drawImage(tint(c, rgb), pen + glyph.offsetX, y + glyph.offsetY, null);
			}
			pen += advances[c];
		}
	}

	/**
	 * Overhead style, verbatim from the client's chat-above-head draw: the
	 * black copy sits at {@code (x, y + 1)} - straight below, not diagonal -
	 * with the coloured text on top, both centered. {@code y} is the baseline.
	 */
	void drawCenteredOverhead(Graphics2D g, String text, int x, int y, int rgb)
	{
		int left = x - stringWidth(text) / 2;
		draw(g, text, left, y + 1 - ascent, 0x000000, false);
		draw(g, text, left, y - ascent, rgb, false);
	}

	BufferedImage tint(int character, int rgb)
	{
		long key = ((long) character << 32) | (rgb & 0xFFFFFFFFL);
		var cached = tinted.get(key);
		if (cached != null)
			return cached;

		var glyph = glyphs[character];
		var image = new BufferedImage(glyph.width, glyph.height,
			BufferedImage.TYPE_INT_ARGB);
		int argb = 0xFF000000 | rgb;
		for (int i = 0; i < glyph.mask.length; i++)
		{
			if (glyph.mask[i] != 0)
				image.setRGB(i % glyph.width, i / glyph.width, argb);
		}
		tinted.put(key, image);
		return image;
	}
}
