package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/**
 * What the follower notices that the player has not: the drop they walked
 * away from, the nest under the tree, an impling, a star in the ground, the
 * signpost they are standing next to. It goes and looks, and the rules get
 * one line about it.
 *
 * <p>Being useful without cheating. Naughty Dog's rule for Ellie's callouts
 * is the whole design: when she called out an enemy the player could not
 * see, it reflected badly on her, so a callout is only ever made about a
 * thing that is there for the player to see. Everything here is checked to
 * still be there, and close enough to be on screen, on the tick the follower
 * sets off - and if it is gone by the time the follower arrives, the look is
 * simply over.
 *
 * <p>Each kind keeps its own cooldown and each thing is noticed once, so a
 * pile of drops in a good hour is one remark, not a commentary. The walk
 * itself belongs to the moments, which own the feet for it.
 */
@Slf4j
public class Noticing
{
	/** What was noticed; {@code key} is what the rules match on. */
	public enum Kind
	{
		/** A drop of the player's, worth having, that they walked away from. */
		VALUABLE("valuable", 150),
		/** A bird's nest on the ground. */
		NEST("nest", 100),
		/** An impling within reach. */
		IMPLING("impling", 300),
		/** A crashed star. */
		STAR("star", 1500),
		/** A signpost, a statue, a plaque the player stopped beside. */
		LANDMARK("landmark", 400);

		public final String key;
		final int cooldownTicks;

		Kind(String key, int cooldownTicks)
		{
			this.key = key;
			this.cooldownTicks = cooldownTicks;
		}
	}

	/** The walk over, and whether it is still under way. Implemented by the moments. */
	public interface Looker
	{
		boolean goAndLook(WorldPoint at, int steps, boolean writes, int holdTicks);

		boolean isLooking();
	}

	/** A drop is worth going back for from this much. */
	public static final int VALUABLE_GP = 20_000;

	/** The player is "walking away" from a drop once this far from it. */
	static final int WALKED_AWAY_TILES = 5;

	/** Nothing further off than this is on the player's screen to be seen. */
	static final int VISIBLE_TILES = 10;

	/** Ticks a drop lies before it counts as left, so looting is not interrupted. */
	static final int LEFT_AFTER_TICKS = 3;

	/** How long the player stands beside a landmark before it is read. */
	static final int LANDMARK_AFTER_IDLE_TICKS = 15;
	public static final int LANDMARK_WITHIN = 2;

	/** The scans that walk lists run this often. */
	static final int SCAN_EVERY_TICKS = 5;

	/**
	 * The things the scribe stops to read when the player stops beside one.
	 * Game objects only, since that is what the scan looks at: the game's
	 * "Noticeboard" and "Portrait" are wall decorations, and it has no
	 * sundial, so none of those are here.
	 */
	public static final String[] LANDMARKS = {
		"Signpost", "Notice board", "Statue", "Painting", "Plaque", "Monument",
		"Memorial", "Map",
	};

	/** A drop lying on the ground that might be worth a look. */
	private static final class Drop
	{
		final int id;
		final String name;
		final long value;
		final int since;
		final boolean nest;

		Drop(int id, String name, long value, int since, boolean nest)
		{
			this.id = id;
			this.name = name;
			this.value = value;
			this.since = since;
			this.nest = nest;
		}
	}

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final Looker looker;
	private final Consumer<TriggerEvent> events;
	/** Anything that owns the feet, or would make a walk wrong: a fight, thrall work. */
	private final BooleanSupplier busy;
	private final IntSupplier idleTicks;
	/** The landmarks within reach of the player, name and tile. */
	private final Supplier<List<Map.Entry<String, WorldPoint>>> landmarksNearby;

	private final Map<WorldPoint, List<Drop>> drops = new HashMap<>();
	private final Map<Kind, Integer> lastAt = new EnumMap<>(Kind.class);
	private final Set<String> seen = new HashSet<>();
	private final List<Map.Entry<String, WorldPoint>> stars = new ArrayList<>();

	/** Only while ::follower force notice runs: no clocks, and what it found, for the chat line. */
	private boolean forcing;
	private String forced;

	public Noticing(Client client, FollowerEntity follower, FollowerConfig config, Looker looker,
		Consumer<TriggerEvent> events, BooleanSupplier busy, IntSupplier idleTicks,
		Supplier<List<Map.Entry<String, WorldPoint>>> landmarksNearby)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.looker = looker;
		this.events = events;
		this.busy = busy;
		this.idleTicks = idleTicks;
		this.landmarksNearby = landmarksNearby;
	}

	// ---------------------------------------------------------------- inputs

	/**
	 * A ground item appeared. Kept only when it is the player's own and
	 * worth having, or a nest; everything else is somebody else's business.
	 *
	 * @param value the stack's worth in gp, already priced by the caller
	 */
	public void itemSpawned(TileItem item, WorldPoint tile, String name, long value)
	{
		if (item == null || tile == null || name == null)
		{
			return;
		}
		boolean nest = name.toLowerCase(Locale.ROOT).startsWith("bird nest");
		boolean mine = item.getOwnership() == TileItem.OWNERSHIP_SELF
			|| item.getOwnership() == TileItem.OWNERSHIP_GROUP;
		if (!nest && !(mine && value >= VALUABLE_GP))
		{
			return;
		}
		drops.computeIfAbsent(tile, t -> new ArrayList<>())
			.add(new Drop(item.getId(), name, value, client.getTickCount(), nest));
	}

	/** A ground item went: picked up, or despawned. Either way, not there to look at. */
	public void itemDespawned(TileItem item, WorldPoint tile)
	{
		if (item == null || tile == null)
		{
			return;
		}
		List<Drop> here = drops.get(tile);
		if (here == null)
		{
			return;
		}
		here.removeIf(drop -> drop.id == item.getId());
		if (here.isEmpty())
		{
			drops.remove(tile);
		}
	}

	/** Scenery appeared, by name. The one the follower cares about is a star. */
	public void objectSpawned(String name, WorldPoint tile)
	{
		if (name != null && tile != null && name.toLowerCase(Locale.ROOT).contains("crashed star"))
		{
			stars.add(new java.util.AbstractMap.SimpleImmutableEntry<>(name, tile));
		}
	}

	public void objectDespawned(String name, WorldPoint tile)
	{
		if (tile != null)
		{
			stars.removeIf(star -> star.getValue().equals(tile));
		}
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread, after the moments. */
	public void tick()
	{
		if (!config.noticesThings())
		{
			drops.clear();
			stars.clear();
			return;
		}
		int now = client.getTickCount();
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		WorldPoint from = follower.getWorldLocation();
		if (here == null || from == null || !follower.isSpawned() || looker.isLooking()
			|| busy.getAsBoolean())
		{
			return;
		}
		if (noticeADrop(now, here, from)
			|| (now % SCAN_EVERY_TICKS == 0
				&& (noticeAnImpling(now, local, here, from) || noticeAStar(now, from)
					|| noticeALandmark(now, here, from))))
		{
			return;
		}
	}

	/**
	 * ::follower force notice: the best thing in view noticed now, walked
	 * to, looked at and named, as the tick would - but with no kind's clock,
	 * no seen list, and no waiting for a drop to lie a few ticks or for the
	 * player to have stood by a landmark a while. The gates stay: Notices
	 * things off, the follower busy or already off looking, a drop the
	 * player has not stepped off from, since its line says they have, and
	 * whatever the walk itself refuses, a park the player gave among them.
	 *
	 * @return what it noticed, or why nothing, for the chat line
	 */
	public String forceNotice()
	{
		if (!config.noticesThings())
		{
			return "Notices things, or Works beside you when skilling, is off in the settings.";
		}
		int now = client.getTickCount();
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		WorldPoint from = follower.getWorldLocation();
		if (here == null || from == null || !follower.isSpawned() || looker.isLooking()
			|| busy.getAsBoolean())
		{
			return "The follower is busy.";
		}
		forcing = true;
		forced = null;
		try
		{
			// In the tick's order, with the scan's cadence dropped as well.
			if (noticeADrop(now, here, from) || noticeAnImpling(now, local, here, from)
				|| noticeAStar(now, from) || noticeALandmark(now, here, from))
			{
				return forced;
			}
			// Nothing gone to; a refusal found on the way is the better answer.
			return forced == null ? "Nothing to notice in view." : forced;
		}
		finally
		{
			forcing = false;
			forced = null;
		}
	}

	private boolean off(Kind kind, int now)
	{
		Integer last = lastAt.get(kind);
		return forcing || last == null || now - last >= kind.cooldownTicks;
	}

	private boolean look(Kind kind, String what, WorldPoint at, WorldPoint from, int now,
		int steps, boolean writes, int holdTicks)
	{
		String key = kind.key + ":" + at.getX() + "," + at.getY() + ":" + what;
		if ((seen.contains(key) && !forcing) || at.getPlane() != from.getPlane()
			|| at.distanceTo(from) > VISIBLE_TILES)
		{
			return false;
		}
		if (!looker.goAndLook(at, steps, writes, holdTicks))
		{
			if (forcing)
			{
				forced = "Saw the " + what + " (" + kind.key + ") but could not go and look.";
			}
			return false;
		}
		seen.add(key);
		lastAt.put(kind, now);
		events.accept(TriggerEvent.noticed(kind.key, what));
		log.debug("Noticed a {}: {} at {}", kind.key, what, at);
		if (forcing)
		{
			forced = "Went to look at the " + what + " (" + kind.key + ").";
		}
		return true;
	}

	/** The player's own drop, worth having, that they have walked away from. */
	private boolean noticeADrop(int now, WorldPoint here, WorldPoint from)
	{
		Iterator<Map.Entry<WorldPoint, List<Drop>>> it = drops.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<WorldPoint, List<Drop>> entry = it.next();
			WorldPoint tile = entry.getKey();
			if (tile.getPlane() != here.getPlane() || tile.distanceTo(here) > 40)
			{
				// Out of the picture; forgotten with the rest of the scene.
				it.remove();
				continue;
			}
			// Kept when forcing: the lines are about a drop walked away from,
			// and said to a player standing on it they are wrong.
			if (tile.distanceTo(here) < WALKED_AWAY_TILES)
			{
				if (forcing)
				{
					forced = "A drop is within " + WALKED_AWAY_TILES + " tiles of you, and its line is"
						+ " for one you walked away from: step off first.";
				}
				continue;
			}
			for (Drop drop : entry.getValue())
			{
				if (now - drop.since < LEFT_AFTER_TICKS && !forcing)
				{
					continue;
				}
				Kind kind = drop.nest ? Kind.NEST : Kind.VALUABLE;
				if (off(kind, now) && look(kind, drop.name, tile, from, now, 1, false, 4))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** An impling near enough to be seen, that the player is not already on. */
	private boolean noticeAnImpling(int now, Player local, WorldPoint here, WorldPoint from)
	{
		if (!off(Kind.IMPLING, now))
		{
			return false;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return false;
		}
		for (NPC npc : view.npcs())
		{
			String name = npc == null ? null : npc.getName();
			WorldPoint at = npc == null ? null : npc.getWorldLocation();
			if (name == null || at == null || !name.toLowerCase(Locale.ROOT).contains("impling")
				|| local.getInteracting() == npc || at.distanceTo(here) > VISIBLE_TILES)
			{
				continue;
			}
			if (look(Kind.IMPLING, name, at, from, now, 2, false, 3))
			{
				return true;
			}
		}
		return false;
	}

	/** A crashed star: walked over to and written up, since it is a date worth having. */
	private boolean noticeAStar(int now, WorldPoint from)
	{
		if (!off(Kind.STAR, now))
		{
			return false;
		}
		for (Map.Entry<String, WorldPoint> star : stars)
		{
			if (look(Kind.STAR, star.getKey(), star.getValue(), from, now, 2, true, 8))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The player has stood beside a signpost, a statue, a plaque for a while:
	 * the scribe goes and reads it, notes out. Elizabeth's points of
	 * interest, driven by where the player stops rather than by a roll.
	 */
	private boolean noticeALandmark(int now, WorldPoint here, WorldPoint from)
	{
		if (!off(Kind.LANDMARK, now)
			|| (idleTicks.getAsInt() < LANDMARK_AFTER_IDLE_TICKS && !forcing))
		{
			return false;
		}
		List<Map.Entry<String, WorldPoint>> found = landmarksNearby.get();
		if (found == null)
		{
			return false;
		}
		for (Map.Entry<String, WorldPoint> landmark : found)
		{
			WorldPoint at = landmark.getValue();
			if (at != null && at.distanceTo(here) <= LANDMARK_WITHIN
				&& look(Kind.LANDMARK, landmark.getKey(), at, from, now, 1, true, 8))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether the name is one of the landmarks the scribe reads. */
	public static boolean isLandmark(String name)
	{
		if (name == null)
		{
			return false;
		}
		for (String landmark : LANDMARKS)
		{
			if (landmark.equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
	}

	/** Logout, hop: the scene is gone, and so is everything noticed in it. */
	public void reset()
	{
		drops.clear();
		stars.clear();
		seen.clear();
		lastAt.clear();
	}
}
