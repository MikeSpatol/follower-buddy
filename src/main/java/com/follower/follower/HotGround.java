package com.follower.follower;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

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
@Slf4j
public class HotGround
{
	/**
	 * What counts. Every one of these is walkable ground with something on it
	 * that a person steps round; {@code ::follower errandscan} prints what the
	 * scene actually calls things, which is the honest way to extend this.
	 */
	public static final List<String> NAMES = Arrays.asList(
		"fire", "campfire", "forester's campfire", "bonfire");

	/** A footprint wider than this is not a fire; nothing is read off it. */
	private static final int BIGGEST = 4;

	/** Ids resolved so far, so a scene load is one lookup per distinct object. */
	private static final int CACHE_CAP = 4000;

	private final Client client;
	private final Set<WorldPoint> tiles = new HashSet<>();
	private final Map<Integer, Boolean> known = new HashMap<>();

	public HotGround(Client client)
	{
		this.client = client;
	}

	/** Whether the name is one of the fires, however the game capitalises it. */
	public static boolean isFire(String name)
	{
		return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Scenery arrived. A fire's tiles go on the list; everything else is
	 * ignored, and the answer is remembered against the id so a scene load
	 * does not resolve the same object a hundred times.
	 */
	public void objectSpawned(int id, WorldPoint min, WorldPoint max)
	{
		if (!fireId(id))
		{
			return;
		}
		forEachTile(min, max, tiles::add);
	}

	/** And went: burnt out, or the scene rebuilt around it. */
	public void objectDespawned(int id, WorldPoint min, WorldPoint max)
	{
		if (!fireId(id))
		{
			return;
		}
		forEachTile(min, max, tiles::remove);
	}

	/**
	 * Whether the follower would walk round this tile rather than onto it.
	 * Null is cold: an unknown tile is not a fire.
	 */
	public boolean hot(WorldPoint at)
	{
		return at != null && !tiles.isEmpty() && tiles.contains(at);
	}

	/** How many tiles are alight, for {@code ::follower status}. */
	public int count()
	{
		return tiles.size();
	}

	// public:cut dev
	/**
	 * What the scene holds against what this does, for {@code ::follower
	 * fires}. The set is built from events, and a missed event or a tile
	 * worked out wrongly is invisible from the inside: the follower simply
	 * walks into something. This is the only way to see it, so it reads the
	 * scene the long way round on purpose.
	 *
	 * @param centre the player's tile
	 * @param within how far to look, in tiles
	 */
	public List<String> describe(WorldPoint centre, int within)
	{
		List<String> lines = new java.util.ArrayList<>();
		lines.add("HotGround holds " + tiles.size() + " tile(s), "
			+ known.size() + " id(s) resolved");
		net.runelite.api.Player local = client.getLocalPlayer();
		net.runelite.api.coords.LocalPoint lp = local == null ? null : local.getLocalLocation();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (centre == null || lp == null || view == null)
		{
			lines.add("no scene to read");
			return lines;
		}

		Set<WorldPoint> inTheScene = new HashSet<>();
		net.runelite.api.Scene scene = view.getScene();
		net.runelite.api.Tile[][][] grid = scene.getTiles();
		int plane = view.getPlane();
		for (int dx = -within; dx <= within; dx++)
		{
			for (int dy = -within; dy <= within; dy++)
			{
				int x = lp.getSceneX() + dx;
				int y = lp.getSceneY() + dy;
				if (x < 0 || y < 0 || x >= grid[plane].length || y >= grid[plane][x].length)
				{
					continue;
				}
				net.runelite.api.Tile tile = grid[plane][x][y];
				if (tile == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject object : tile.getGameObjects())
				{
					if (object == null)
					{
						continue;
					}
					String name = ErrandController.objectName(client, object.getId());
					if (!isFire(name))
					{
						continue;
					}
					// BOTH readings: the one the events are built from, and
					// the one every other part of the plugin uses. If these
					// ever disagree the set is keyed on the wrong tiles.
					net.runelite.api.Point min = object.getSceneMinLocation();
					WorldPoint fromScene = min == null ? null
						: WorldPoint.fromScene(view, min.getX(), min.getY(), object.getPlane());
					WorldPoint fromObject = object.getWorldLocation();
					inTheScene.add(fromScene == null ? fromObject : fromScene);
					lines.add(String.format("  %s id %d at %s (object says %s) - %s",
						name, object.getId(), fromScene, fromObject,
						hot(fromScene) ? "hot" : "NOT IN THE SET"));
				}
			}
		}
		if (inTheScene.isEmpty())
		{
			lines.add("  no fire within " + within + " tiles");
		}
		for (WorldPoint held : tiles)
		{
			if (held.distanceTo(centre) <= within && !inTheScene.contains(held))
			{
				lines.add("  STALE: " + held + " is held but the scene has no fire on it");
			}
		}
		return lines;
	}

	// public:end dev
	/** A hop, a logout, a shutdown: the scene is gone and so is what was in it. */
	public void clear()
	{
		tiles.clear();
	}

	private boolean fireId(int id)
	{
		Boolean answer = known.get(id);
		if (answer == null)
		{
			answer = isFire(ErrandController.objectName(client, id));
			if (known.size() >= CACHE_CAP)
			{
				// Bounded rather than clever: the ids in one scene number in
				// the hundreds, and a fresh start costs one lookup each.
				known.clear();
			}
			known.put(id, answer);
		}
		return answer;
	}

	private void forEachTile(WorldPoint min, WorldPoint max, java.util.function.Consumer<WorldPoint> each)
	{
		if (min == null)
		{
			return;
		}
		WorldPoint far = max == null ? min : max;
		if (far.getX() - min.getX() >= BIGGEST || far.getY() - min.getY() >= BIGGEST
			|| far.getPlane() != min.getPlane())
		{
			each.accept(min);
			return;
		}
		for (int x = min.getX(); x <= far.getX(); x++)
		{
			for (int y = min.getY(); y <= far.getY(); y++)
			{
				each.accept(new WorldPoint(x, y, min.getPlane()));
			}
		}
	}
}
