package com.follower;

import com.google.gson.Gson;
import java.util.*;
import java.util.Map;
import javax.inject.*;
import lombok.*;
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
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class SpotAnimRepository
{
	/** One spotanim definition. Field names mirror the dumper's. */
	static class Entry extends Recolours
	{
		int m,
			a;
		Integer rx,
			ry,
			rot,
			am,
			co;

		int modelId()
		{
			return m;
		}

		int animationId()
		{
			return a;
		}

		int resizeX()
		{
			return rx == null ? 128 : rx;
		}

		int resizeY()
		{
			return ry == null ? 128 : ry;
		}

		int rotation()
		{
			return rot == null ? 0 : rot;
		}

		int ambient()
		{
			return am == null ? 0 : am;
		}

		int contrast()
		{
			return co == null ? 0 : co;
		}
	}

	final Gson gson;

	Map<String, Entry> spotanims = Collections.emptyMap();
	boolean liveRead;

	@Getter
	String status = "not loaded";

	/**
	 * Builds the catalogue from the client's own loaded cache - the no-dump
	 * path every Hub install uses. A dump file, when present, wins for the
	 * ids it has. No-op while the cache indexes are not available yet;
	 * callers retry.
	 */
	void loadFromClient(net.runelite.api.Client client)
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
			return;
		spotanims = live;
		liveRead = true;
			status = spotanims.size() + " spotanims (live cache)";
	}

	/** Whether the client's own cache has been read in yet, under a dump or not. */
	boolean hasReadTheLiveCache()
	{
		return liveRead;
	}

	/** The definition for a graphic id, or null if unknown or not loaded. */
	Entry get(int graphicId)
	{
		return spotanims.get(Integer.toString(graphicId));
	}

	boolean isLoaded()
	{
		return !spotanims.isEmpty();
	}

	/** Lets the catalogue go while the plugin is off; startUp and the next login load it again. */
	void unload()
	{
		spotanims = Collections.emptyMap();
		liveRead = false;
		status = "not loaded";
	}
}
