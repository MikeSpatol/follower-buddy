package com.follower.ui;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The game's bitmap fonts, dumped from the cache by tools/cache-dumper. Keyed
 * by the id widgets report from {@code getFontId()} - the glyph sprite
 * archive's id - so a sniffed dialog's font maps straight to the exact glyphs
 * it renders with.
 */
@Slf4j
@Singleton
public class GameFontRepository
{
	public static final String FILE_NAME = "fonts.json";

	private static class GlyphEntry
	{
		int w;
		int h;
		int ox;
		int oy;
		String mask;
	}

	private static class FontEntry
	{
		int id;
		String name;
		int ascent;
		int[] advances;
		List<GlyphEntry> glyphs;
	}

	private static class Dump
	{
		int version;
		String cacheRevision;
		List<FontEntry> fonts;
	}

	private final Gson gson;
	private final Map<Integer, GameFont> fonts = new HashMap<>();
	private final Map<String, GameFont> fontsByName = new HashMap<>();

	@Getter
	private String status = "not loaded";

	@Inject
	public GameFontRepository(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Loads from {@code <dataDir>/fonts.json} when present (the development
	 * override), else from the bundled copy of the same dump. Fonts are
	 * bundled rather than parsed live because pairing font metrics to glyph
	 * sprites requires archive name hashes the runtime API does not expose -
	 * and the game's bitmap fonts have not changed in decades.
	 */
	public void load(Path dataDir)
	{
		Path file = dataDir.resolve(FILE_NAME);
		if (Files.isRegularFile(file))
		{
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
			{
				if (read(reader, file.toString()))
				{
					return;
				}
			}
			catch (IOException | RuntimeException e)
			{
				status = "failed to read " + FILE_NAME;
				log.warn("Could not load {}", file, e);
			}
		}

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

	private boolean read(Reader reader, String source)
	{
		Dump dump = gson.fromJson(reader, Dump.class);
		if (dump == null || dump.fonts == null)
		{
			status = FILE_NAME + " is empty (" + source + ")";
			return false;
		}

		for (FontEntry entry : dump.fonts)
		{
			GameFont.Glyph[] glyphs = new GameFont.Glyph[256];
			for (int i = 0; i < entry.glyphs.size() && i < 256; i++)
			{
				GlyphEntry g = entry.glyphs.get(i);
				glyphs[i] = new GameFont.Glyph(g.w, g.h, g.ox, g.oy,
					g.mask == null ? new byte[0] : Base64.getDecoder().decode(g.mask));
			}
			GameFont font = new GameFont(entry.ascent, entry.advances, glyphs);
			fonts.put(entry.id, font);
			if (entry.name != null)
			{
				fontsByName.put(entry.name, font);
			}
		}
		status = fonts.size() + " fonts (cache " + dump.cacheRevision + ")";
		log.info("Loaded {} game fonts from {} (cache {})",
			fonts.size(), source, dump.cacheRevision);
		return true;
	}

	/** The font for a widget font id, or null if unknown or not loaded. */
	public GameFont get(int fontId)
	{
		return fonts.get(fontId);
	}

	/** The font by cache archive name (p12_full, b12_full, ...), or null. */
	public GameFont getByName(String name)
	{
		return fontsByName.get(name);
	}

	public boolean isLoaded()
	{
		return !fonts.isEmpty();
	}
}
