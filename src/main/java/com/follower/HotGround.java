package com.follower;

import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import lombok.RequiredArgsConstructor;

/**
 * The tiles the map calls walkable that nobody walks on: a lit fire, chiefly.
 *
 * <p>Collision is the only thing the follower's feet have ever consulted, and
 * a fire has none - a player walks straight over one, and so, until now, did
 * the follower. Play report: light a campfire, cook on it, and the follower
 * stands IN the fire while it copies the cooking. The map is not wrong; it
 * simply does not describe the one thing a person would take account of.
 *
 * <p>Kept as a set of tiles rather than a scan, because the client already
 * announces every object that comes and goes - including one event per object
 * when a scene loads - so the set maintains itself and an answer costs a hash
 * lookup. That matters: the pathfinder asks per tile, thousands of times per
 * route.
 *
 * <p>Matched by NAME rather than by id, for the reason the borrowed tools
 * learned the hard way: a table of ids cannot keep up with the game, and the
 * names here are the ones the plugin already looks for when it sends the
 * follower to warm its hands.
 */
@RequiredArgsConstructor
class HotGround
{
	/**
	 * What counts. Every one of these is walkable ground with something on it
	 * that a person steps round; {@code ::follower errandscan} prints what the
	 * scene actually calls things, which is the honest way to extend this.
	 */
	static final List<String> NAMES = Arrays.asList(
		"fire", "campfire", "forester's campfire", "bonfire");

	/** A footprint wider than this is not a fire; nothing is read off it. */
	static final int BIGGEST = 4;

	final Client client;
	final Set<WorldPoint> tiles = new HashSet<>();
	final Map<Integer, Boolean> known = new HashMap<>();

	/** Whether the name is one of the fires, however the game capitalises it. */
	static boolean isFire(String name)
	{
		return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Scenery arrived. A fire's tiles go on the list; everything else is
	 * ignored, and the answer is remembered against the id so a scene load
	 * does not resolve the same object a hundred times.
	 */
	void objectSpawned(int id, WorldPoint min, WorldPoint max)
	{
		if (!fireId(id))
			return;
		forEachTile(min, max, tiles::add);
	}

	/** And went: burnt out, or the scene rebuilt around it. */
	void objectDespawned(int id, WorldPoint min, WorldPoint max)
	{
		if (!fireId(id))
			return;
		forEachTile(min, max, tiles::remove);
	}

	/**
	 * Whether the follower would walk round this tile rather than onto it.
	 * Null is cold: an unknown tile is not a fire.
	 */
	boolean hot(WorldPoint at)
	{
		return at != null && !tiles.isEmpty() && tiles.contains(at);
	}

	/** How many tiles are alight, for {@code ::follower status}. */
	int count()
	{
		return tiles.size();
	}

	/** A hop, a logout, a shutdown: the scene is gone and so is what was in it. */
	void clear()
	{
		tiles.clear();
	}

	boolean fireId(int id)
	{
		var answer = known.get(id);
		if (answer == null)
		{
			answer = isFire(ErrandController.objectName(client, id));
			// CACHE_CAP: Ids resolved so far, so a scene load is one lookup per distinct object.
			if (known.size() >= 4000 /* CACHE_CAP */)
				// Bounded rather than clever: the ids in one scene number in
				// the hundreds, and a fresh start costs one lookup each.
				known.clear();
			known.put(id, answer);
		}
		return answer;
	}

	void forEachTile(WorldPoint min, WorldPoint max, java.util.function.Consumer<WorldPoint> each)
	{
		if (min == null)
			return;
		var far = max == null ? min : max;
		if (far.getX() - min.getX() >= BIGGEST || far.getY() - min.getY() >= BIGGEST
			|| far.getPlane() != min.getPlane())
		{
			each.accept(min);
			return;
		}
		for (int x = min.getX(); x <= far.getX(); x++)
		{
			for (int y = min.getY(); y <= far.getY(); y++)
				each.accept(new WorldPoint(x, y, min.getPlane()));
		}
	}
}
