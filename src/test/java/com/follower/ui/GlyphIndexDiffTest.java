package com.follower.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * GameFont finds a glyph through the JDK's windows-1252 table, where it once
 * had a switch of the 27 code points that table maps above Latin-1. The
 * switch is kept here as it stood, and the two agree on every char there is.
 */
public class GlyphIndexDiffTest
{
	/** GameFont.glyphIndex before it read the JDK's table, verbatim. */
	static int oldGlyphIndex(char c)
	{
		if (c == '\u00A0')
		{
			return ' ';
		}
		if ((c > 0 && c < 128) || (c >= 160 && c <= 255))
		{
			return c;
		}
		switch (c)
		{
			case '\u20AC': return 0x80;
			case '\u201A': return 0x82;
			case '\u0192': return 0x83;
			case '\u201E': return 0x84;
			case '\u2026': return 0x85;
			case '\u2020': return 0x86;
			case '\u2021': return 0x87;
			case '\u02C6': return 0x88;
			case '\u2030': return 0x89;
			case '\u0160': return 0x8A;
			case '\u2039': return 0x8B;
			case '\u0152': return 0x8C;
			case '\u017D': return 0x8E;
			case '\u2018': return 0x91;
			case '\u2019': return 0x92;
			case '\u201C': return 0x93;
			case '\u201D': return 0x94;
			case '\u2022': return 0x95;
			case '\u2013': return 0x96;
			case '\u2014': return 0x97;
			case '\u02DC': return 0x98;
			case '\u2122': return 0x99;
			case '\u0161': return 0x9A;
			case '\u203A': return 0x9B;
			case '\u0153': return 0x9C;
			case '\u017E': return 0x9E;
			case '\u0178': return 0x9F;
			default: return '?';
		}
	}

	@Test
	public void everyCharFindsTheGlyphTheSwitchFound()
	{
		int mapped = 0;
		for (int i = Character.MIN_VALUE; i <= Character.MAX_VALUE; i++)
		{
			char c = (char) i;
			assertEquals("U+" + Integer.toHexString(i), oldGlyphIndex(c), GameFont.glyphIndex(c));
			if (oldGlyphIndex(c) != '?')
			{
				mapped++;
			}
		}
		assertEquals("the chars with a glyph of their own", 249, mapped);
	}
}
