package com.follower;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.Map;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

/**
 * The game's bitmap fonts, dumped from the cache by tools/cache-dumper. Keyed
 * by the id widgets report from {@code getFontId()} - the glyph sprite
 * archive's id - so a sniffed dialog's font maps straight to the exact glyphs
 * it renders with.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GameFontRepository
{
	static final String FILE_NAME = "fonts.json";

	static class GlyphEntry
	{
		int w,
			h,
			ox,
			oy;
		String mask;
	}

	static class FontEntry
	{
		int id;
		String name;
		int ascent;
		int[] advances;
		List<GlyphEntry> glyphs;
	}

	static class Dump
	{
		int version;
		String cacheRevision;
		List<FontEntry> fonts;
	}

	final Gson gson;
	final Map<Integer, GameFont> fonts = new HashMap<>();
	final Map<String, GameFont> fontsByName = new HashMap<>();

	@Getter
	String status = "not loaded";

	/**
	 * Loads from {@code <dataDir>/fonts.json} when present (the development
	 * override), else from the bundled copy of the same dump. Fonts are
	 * bundled rather than parsed live because pairing font metrics to glyph
	 * sprites requires archive name hashes the runtime API does not expose -
	 * and the game's bitmap fonts have not changed in decades.
	 *
	 * <p>The BUNDLED dump carries only the two fonts the plugin draws with:
	 * 497 (q8_full) for the dialog box and 496 (b12_full) for the overhead
	 * line. The dumper still writes all 21, which is what you want on disk for
	 * identifying a sniffed widget's font, but shipping the other 19 cost
	 * around 60KB of the jar and 5,000 base64 glyph decodes at startup to hold
	 * glyphs nothing ever asked for. Add an id to the trim if something starts
	 * drawing with it - {@link #get} and {@link #getByName} return null for a
	 * font that is not there, and both callers fall back to a TTF, so the
	 * failure is quiet rather than loud.
	 */
	void load()
	{
		try (java.io.InputStream in = GameFontRepository.class
			.getResourceAsStream("/com/follower/" + FILE_NAME))
		{
			if (in == null)
			{
				status = "no " + FILE_NAME + " bundled or on disk";
				return;
			}
			read(new java.io.BufferedReader(
				new java.io.InputStreamReader(in, StandardCharsets.UTF_8)), "bundled resource");
		}
		catch (IOException | RuntimeException e)
		{
			status = "failed to read bundled " + FILE_NAME;
			log.warn("Could not load bundled font dump", e);
		}
	}

	boolean read(Reader reader, String source)
	{
		Dump dump = gson.fromJson(reader, Dump.class);
		if (dump == null || dump.fonts == null)
		{
			status = FILE_NAME + " is empty (" + source + ")";
			return false;
		}

		for (var entry : dump.fonts)
		{
			var glyphs = new GameFont.Glyph[256];
			for (int i = 0; i < entry.glyphs.size() && i < 256; i++)
			{
				var g = entry.glyphs.get(i);
				glyphs[i] = new GameFont.Glyph(g.w, g.h, g.ox, g.oy,
					g.mask == null ? new byte[0] : Base64.getDecoder().decode(g.mask));
			}
			var font = new GameFont(entry.ascent, entry.advances, glyphs);
			fonts.put(entry.id, font);
			if (entry.name != null)
				fontsByName.put(entry.name, font);
		}
		status = fonts.size() + " fonts (cache " + dump.cacheRevision + ")";
		return true;
	}

	/** The font for a widget font id, or null if unknown or not loaded. */
	GameFont get(int fontId)
	{
		return fonts.get(fontId);
	}

	/** The font by cache archive name (p12_full, b12_full, ...), or null. */
	GameFont getByName(String name)
	{
		return fontsByName.get(name);
	}

	boolean isLoaded()
	{
		return !fonts.isEmpty();
	}
}
