package com.follower.appearance;

import com.google.gson.Gson;
// public:cut devdump
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
// public:end devdump
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
	// public:cut devdump
	public static final String FILE_NAME = "spotanims.json";

	// public:end devdump
	/** One spotanim definition. Field names mirror the dumper's. */
	public static class Entry extends Recolours
	{
		public int m;
		public int a;
		public Integer rx;
		public Integer ry;
		public Integer rot;
		public Integer am;
		public Integer co;

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

	// public:cut devdump
	private static class Dump
	{
		int version;
		String cacheRevision;
		Map<String, Entry> spotanims;
	}

	// public:end devdump
	private final Gson gson;

	private Map<String, Entry> spotanims = Collections.emptyMap();
	private boolean liveRead;

	@Getter
	private String status = "not loaded";

	@Inject
	public SpotAnimRepository(Gson gson)
	{
		this.gson = gson;
	}

	// public:cut devdump
	public void load(Path dataDir)
	{
		Path file = dataDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
		{
			// Not an error: the live-cache parse takes over once the client
			// has its indexes loaded.
			status = "no " + FILE_NAME + "; will parse the live cache";
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
			// public:cut logs
			log.info("Loaded {} spotanims from {} (cache {})",
				spotanims.size(), file, dump.cacheRevision);
			// public:end logs
		}
		catch (IOException | RuntimeException e)
		{
			status = "failed to read " + FILE_NAME;
			log.warn("Could not load {}", file, e);
		}
	}

	// public:end devdump
	/**
	 * Builds the catalogue from the client's own loaded cache - the no-dump
	 * path every Hub install uses. A dump file, when present, wins for the
	 * ids it has. No-op while the cache indexes are not available yet;
	 * callers retry.
	 */
	public void loadFromClient(net.runelite.api.Client client)
	{
		addLive(LiveCacheParser.parseSpotAnims(client));
	}

	/**
	 * The live cache's graphics, under a dump's: the game's graphics newer
	 * than the dump are there too. A dump from August hid 426 of them on
	 * the development client (qa5 #75).
	 */
	void addLive(Map<String, Entry> live)
	{
		if (live.isEmpty())
		{
			return;
		}
		// public:cut devdump
		int dumped = spotanims.size();
		live.putAll(spotanims);
		// public:end devdump
		spotanims = live;
		liveRead = true;
		// public:cut devdump
		if (dumped == 0)
		{
		// public:end devdump
			status = spotanims.size() + " spotanims (live cache)";
			log.info("Loaded {} spotanims from live cache", spotanims.size()); // public:cut logs
		// public:cut devdump
		}
		else
		{
			log.info("Added {} spotanims from the live cache to the dump's {}", spotanims.size() - dumped, dumped); // public:cut logs
		}
		// public:end devdump
	}

	/** Whether the client's own cache has been read in yet, under a dump or not. */
	public boolean hasReadTheLiveCache()
	{
		return liveRead;
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

	/** Lets the catalogue go while the plugin is off; startUp and the next login load it again. */
	public void unload()
	{
		spotanims = Collections.emptyMap();
		liveRead = false;
		status = "not loaded";
	}
}
