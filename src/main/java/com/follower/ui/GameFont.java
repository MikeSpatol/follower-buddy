package com.follower.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

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
 * styles are offered. Glyphs are indexed DIRECTLY by character code (the
 * dumped archives all carry 256 glyphs and 256 advances).
 */
public final class GameFont
{
	public static class Glyph
	{
		public final int width;
		public final int height;
		public final int offsetX;
		public final int offsetY;
		public final byte[] mask;

		Glyph(int width, int height, int offsetX, int offsetY, byte[] mask)
		{
			this.width = width;
			this.height = height;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.mask = mask;
		}
	}

	private final int ascent;
	private final int[] advances;
	private final Glyph[] glyphs;

	/** The client's PixFont.height: the tallest glyph mask, its line-stack step. */
	private final int lineHeight;

	/** Tinted glyph images, cached per (character, colour). */
	private final Map<Long, BufferedImage> tinted = new HashMap<>();

	public GameFont(int ascent, int[] advances, Glyph[] glyphs)
	{
		this.ascent = ascent;
		this.advances = advances;
		this.glyphs = glyphs;

		int tallest = 0;
		for (Glyph glyph : glyphs)
		{
			if (glyph != null && glyph.height > tallest)
			{
				tallest = glyph.height;
			}
		}
		this.lineHeight = tallest;
	}

	public int getAscent()
	{
		return ascent;
	}

	public int getLineHeight()
	{
		return lineHeight;
	}

	/** The exact pixel width the client would measure for this string. */
	public int stringWidth(String text)
	{
		if (text == null)
		{
			return 0;
		}
		int width = 0;
		for (int i = 0; i < text.length(); i++)
		{
			width += advances[text.charAt(i) & 0xFF];
		}
		return width;
	}

	/**
	 * Draws with {@code y} as the BASELINE, the widget text convention: a text
	 * line's baseline sits at its top plus the font's ascent.
	 */
	public void drawBaseline(Graphics2D g, String text, int x, int y, int rgb, boolean shadowed)
	{
		drawTop(g, text, x, y - ascent, rgb, shadowed);
	}

	/** Draws with {@code y} as the TOP of the glyph cell. */
	public void drawTop(Graphics2D g, String text, int x, int y, int rgb, boolean shadowed)
	{
		if (text == null)
		{
			return;
		}
		int pen = x;
		for (int i = 0; i < text.length(); i++)
		{
			int c = text.charAt(i) & 0xFF;
			Glyph glyph = glyphs[c];
			if (glyph != null && glyph.width > 0 && glyph.height > 0)
			{
				if (shadowed)
				{
					g.drawImage(tint(c, 0x000000),
						pen + glyph.offsetX + 1, y + glyph.offsetY + 1, null);
				}
				g.drawImage(tint(c, rgb), pen + glyph.offsetX, y + glyph.offsetY, null);
			}
			pen += advances[c];
		}
	}

	/** Centered on {@code x}, baseline at {@code y}. */
	public void drawCenteredBaseline(Graphics2D g, String text, int x, int y, int rgb, boolean shadowed)
	{
		drawBaseline(g, text, x - stringWidth(text) / 2, y, rgb, shadowed);
	}

	/**
	 * Overhead style, verbatim from the client's chat-above-head draw: the
	 * black copy sits at {@code (x, y + 1)} - straight below, not diagonal -
	 * with the coloured text on top, both centered. {@code y} is the baseline.
	 */
	public void drawCenteredOverhead(Graphics2D g, String text, int x, int y, int rgb)
	{
		int left = x - stringWidth(text) / 2;
		drawTopNoShadow(g, text, left, y + 1 - ascent, 0x000000);
		drawTopNoShadow(g, text, left, y - ascent, rgb);
	}

	private void drawTopNoShadow(Graphics2D g, String text, int x, int y, int rgb)
	{
		int pen = x;
		for (int i = 0; i < text.length(); i++)
		{
			int c = text.charAt(i) & 0xFF;
			Glyph glyph = glyphs[c];
			if (glyph != null && glyph.width > 0 && glyph.height > 0)
			{
				g.drawImage(tint(c, rgb), pen + glyph.offsetX, y + glyph.offsetY, null);
			}
			pen += advances[c];
		}
	}

	private BufferedImage tint(int character, int rgb)
	{
		long key = ((long) character << 32) | (rgb & 0xFFFFFFFFL);
		BufferedImage cached = tinted.get(key);
		if (cached != null)
		{
			return cached;
		}

		Glyph glyph = glyphs[character];
		BufferedImage image = new BufferedImage(glyph.width, glyph.height,
			BufferedImage.TYPE_INT_ARGB);
		int argb = 0xFF000000 | rgb;
		for (int i = 0; i < glyph.mask.length; i++)
		{
			if (glyph.mask[i] != 0)
			{
				image.setRGB(i % glyph.width, i / glyph.width, argb);
			}
		}
		tinted.put(key, image);
		return image;
	}
}
