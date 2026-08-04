package com.follower.appearance;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The spotanim ("graphic") catalogue, dumped from the cache by
 * tools/cache-dumper. A spotanim is how the game shows teleport swirls, spell
 * impacts and the home teleport's rune circle: a model, the animation that
 * plays it once, scaling, rotation, recolours and lighting tweaks.
 *
 * <p>Dumped rather than read live because the client API exposes ids only -
 * {@code Actor.getGraphic()} says WHICH graphic is on the player, but nothing
 * maps that id to a model the follower could wear.
 */
@Slf4j
@Singleton
public class SpotAnimRepository
{
	public static final String FILE_NAME = "spotanims.json";

	/** One spotanim definition. Field names mirror the dumper's. */
	public static class Entry
	{
		public int m;
		public int a;
		public Integer rx;
		public Integer ry;
		public Integer rot;
		public Integer am;
		public Integer co;
		public short[] cf;
		public short[] cr;
		public short[] tf;
		public short[] tr;

		public int modelId()
		{
			return m;
		}

		public int animationId()
		{
			return a;
		}

		public int resizeX()
		{
			return rx == null ? 128 : rx;
		}

		public int resizeY()
		{
			return ry == null ? 128 : ry;
		}

		public int rotation()
		{
			return rot == null ? 0 : rot;
		}

		public int ambient()
		{
			return am == null ? 0 : am;
		}

		public int contrast()
		{
			return co == null ? 0 : co;
		}
	}

	private static class Dump
	{
		int version;
		String cacheRevision;
		Map<String, Entry> spotanims;
	}

	private final Gson gson;

	private Map<String, Entry> spotanims = Collections.emptyMap();

	@Getter
	private String status = "not loaded";

	@Inject
	public SpotAnimRepository(Gson gson)
	{
		this.gson = gson;
	}

	public void load(Path dataDir)
	{
		Path file = dataDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
		{
			status = "no " + FILE_NAME + " - re-run tools/cache-dumper to enable graphic mirroring";
			log.info("SpotAnim dump not found at {}; graphic mirroring disabled", file);
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Dump dump = gson.fromJson(reader, Dump.class);
			if (dump == null || dump.spotanims == null)
			{
				status = FILE_NAME + " is empty";
				return;
			}
			spotanims = dump.spotanims;
			status = spotanims.size() + " spotanims (cache " + dump.cacheRevision + ")";
			log.info("Loaded {} spotanims from {} (cache {})",
				spotanims.size(), file, dump.cacheRevision);
		}
		catch (IOException | RuntimeException e)
		{
			status = "failed to read " + FILE_NAME;
			log.warn("Could not load {}", file, e);
		}
	}

	/** The definition for a graphic id, or null if unknown or not loaded. */
	public Entry get(int graphicId)
	{
		return spotanims.get(Integer.toString(graphicId));
	}

	public boolean isLoaded()
	{
		return !spotanims.isEmpty();
	}
}
