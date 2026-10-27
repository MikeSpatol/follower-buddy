package com.follower;

import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import lombok.RequiredArgsConstructor;
import java.util.Map.Entry;

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
class Noticing
{
	/** What was noticed; {@code key}, the name in lower case, is what the rules match on. */
	@RequiredArgsConstructor
	enum Kind
	{
		/** A drop of the player's, worth having, that they walked away from. */
		VALUABLE(150),
		/** A bird's nest on the ground. */
		NEST(100),
		/** An impling within reach. */
		IMPLING(300),
		/** A crashed star. */
		STAR(1500),
		/** A signpost, a statue, a plaque the player stopped beside. */
		LANDMARK(400);

		final String key = name().toLowerCase(Locale.ROOT);
		final int cooldownTicks;
	}

	/** The walk over, and whether it is still under way. Implemented by the moments. */
	interface Looker
	{
		boolean goAndLook(WorldPoint at, int steps, boolean writes, int holdTicks);

		boolean isLooking();
	}

	/** The player is "walking away" from a drop once this far from it. */
	static final int WALKED_AWAY_TILES = 5;

	/** Nothing further off than this is on the player's screen to be seen. */
	static final int VISIBLE_TILES = 10;

	/** Ticks a drop lies before it counts as left, so looting is not interrupted. */
	static final int LEFT_AFTER_TICKS = 3;

	/** How long the player stands beside a landmark before it is read. */
	static final int LANDMARK_AFTER_IDLE_TICKS = 15,
		LANDMARK_WITHIN = 2;

	/** The scans that walk lists run this often. */
	static final int SCAN_EVERY_TICKS = 5;

	/**
	 * The things the scribe stops to read when the player stops beside one.
	 * Game objects only, since that is what the scan looks at: the game's
	 * "Noticeboard" and "Portrait" are wall decorations, and it has no
	 * sundial, so none of those are here.
	 */
	static final String[] LANDMARKS = {
		"Signpost", "Notice board", "Statue", "Painting", "Plaque", "Monument",
		"Memorial", "Map",
	};

	/** A drop lying on the ground that might be worth a look. */
	@RequiredArgsConstructor
	static class Drop
	{
		final int id;
		final String name;
		final long value;
		final int since;
		final boolean nest;
	}

	final Client client;
	final FollowerEntity follower;
	final FollowerConfig config;
	final Looker looker;
	final Consumer<TriggerEvent> events;
	/** Anything that owns the feet, or would make a walk wrong: a fight, thrall work. */
	final BooleanSupplier busy;
	final IntSupplier idleTicks;
	/** The landmarks within reach of the player, name and tile. */
	final Supplier<List<Entry<String, WorldPoint>>> landmarksNearby;

	final Map<WorldPoint, List<Drop>> drops = new HashMap<>();
	final Map<Kind, Integer> lastAt = new EnumMap<>(Kind.class);
	final Set<String> seen = new HashSet<>();
	final List<Entry<String, WorldPoint>> stars = new ArrayList<>();

	Noticing(Client client, FollowerEntity follower, FollowerConfig config, Looker looker,
		Consumer<TriggerEvent> events, BooleanSupplier busy, IntSupplier idleTicks,
		Supplier<List<Entry<String, WorldPoint>>> landmarksNearby)
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
	void itemSpawned(TileItem item, WorldPoint tile, String name, long value)
	{
		if (item == null || tile == null || name == null)
			return;
		boolean nest = name.toLowerCase(Locale.ROOT).startsWith("bird nest");
		boolean mine = item.getOwnership() == TileItem.OWNERSHIP_SELF
			|| item.getOwnership() == TileItem.OWNERSHIP_GROUP;
		// VALUABLE_GP: A drop is worth going back for from this much.
		if (!nest && !(mine && value >= 20_000 /* VALUABLE_GP */))
			return;
		drops.computeIfAbsent(tile, t -> new ArrayList<>())
			.add(new Drop(item.getId(), name, value, client.getTickCount(), nest));
	}

	/** A ground item went: picked up, or despawned. Either way, not there to look at. */
	void itemDespawned(TileItem item, WorldPoint tile)
	{
		if (item == null || tile == null)
			return;
		var here = drops.get(tile);
		if (here == null)
			return;
		here.removeIf(drop -> drop.id == item.getId());
		if (here.isEmpty())
			drops.remove(tile);
	}

	/** Scenery appeared, by name. The one the follower cares about is a star. */
	void objectSpawned(String name, WorldPoint tile)
	{
		if (name != null && tile != null && name.toLowerCase(Locale.ROOT).contains("crashed star"))
			stars.add(new java.util.AbstractMap.SimpleImmutableEntry<>(name, tile));
	}

	void objectDespawned(String name, WorldPoint tile)
	{
		if (tile != null)
			stars.removeIf(star -> star.getValue().equals(tile));
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread, after the moments. */
	void tick()
	{
		if (!config.noticesThings())
		{
			drops.clear();
			stars.clear();
			return;
		}
		int now = client.getTickCount();
		var local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation(),
			from = follower.getWorldLocation();
		if (here == null || from == null || !follower.isSpawned() || looker.isLooking()
			|| busy.getAsBoolean())
			return;
		if (noticeADrop(now, here, from)
			|| (now % SCAN_EVERY_TICKS == 0
				&& (noticeAnImpling(now, local, here, from) || noticeAStar(now, from)
					|| noticeALandmark(now, here, from))))
			return;
	}

	boolean off(Kind kind, int now)
	{
		var last = lastAt.get(kind);
		return last == null || now - last >= kind.cooldownTicks;
	}

	boolean look(Kind kind, String what, WorldPoint at, WorldPoint from, int now,
		int steps, boolean writes, int holdTicks)
	{
		String key = kind.key + ":" + at.getX() + "," + at.getY() + ":" + what;
		// Another floor is infinitely far, as distanceTo has it.
		if (seen.contains(key) || at.distanceTo(from) > VISIBLE_TILES)
			return false;
		if (!looker.goAndLook(at, steps, writes, holdTicks))
			return false;
		seen.add(key);
		lastAt.put(kind, now);
		events.accept(TriggerEvent.noticed(kind.key, what));
		return true;
	}

	/** The player's own drop, worth having, that they have walked away from. */
	boolean noticeADrop(int now, WorldPoint here, WorldPoint from)
	{
		var it = drops.entrySet().iterator();
		while (it.hasNext())
		{
			var entry = it.next();
			var tile = entry.getKey();
			// Another floor is infinitely far, as distanceTo has it.
			if (tile.distanceTo(here) > 40)
			{
				// Out of the picture; forgotten with the rest of the scene.
				it.remove();
				continue;
			}
			if (tile.distanceTo(here) < WALKED_AWAY_TILES)
				continue;
			for (var drop : entry.getValue())
			{
				if (now - drop.since < LEFT_AFTER_TICKS)
					continue;
				var kind = drop.nest ? Kind.NEST : Kind.VALUABLE;
				if (off(kind, now) && look(kind, drop.name, tile, from, now, 1, false, 4))
					return true;
			}
		}
		return false;
	}

	/** An impling near enough to be seen, that the player is not already on. */
	boolean noticeAnImpling(int now, Player local, WorldPoint here, WorldPoint from)
	{
		if (!off(Kind.IMPLING, now))
			return false;
		var view = client.getTopLevelWorldView();
		if (view == null)
			return false;
		for (var npc : view.npcs())
		{
			String name = npc == null ? null : npc.getName();
			var at = npc == null ? null : npc.getWorldLocation();
			if (name == null || at == null || !name.toLowerCase(Locale.ROOT).contains("impling")
				|| local.getInteracting() == npc || at.distanceTo(here) > VISIBLE_TILES)
				continue;
			if (look(Kind.IMPLING, name, at, from, now, 2, false, 3))
				return true;
		}
		return false;
	}

	/** A crashed star: walked over to and written up, since it is a date worth having. */
	boolean noticeAStar(int now, WorldPoint from)
	{
		if (!off(Kind.STAR, now))
			return false;
		for (var star : stars)
		{
			if (look(Kind.STAR, star.getKey(), star.getValue(), from, now, 2, true, 8))
				return true;
		}
		return false;
	}

	/**
	 * The player has stood beside a signpost, a statue, a plaque for a while:
	 * the scribe goes and reads it, notes out. Elizabeth's points of
	 * interest, driven by where the player stops rather than by a roll.
	 */
	boolean noticeALandmark(int now, WorldPoint here, WorldPoint from)
	{
		if (!off(Kind.LANDMARK, now)
			|| idleTicks.getAsInt() < LANDMARK_AFTER_IDLE_TICKS)
			return false;
		var found = landmarksNearby.get();
		if (found == null)
			return false;
		for (var landmark : found)
		{
			var at = landmark.getValue();
			if (at != null && at.distanceTo(here) <= LANDMARK_WITHIN
				&& look(Kind.LANDMARK, landmark.getKey(), at, from, now, 1, true, 8))
				return true;
		}
		return false;
	}

	/** Whether the name is one of the landmarks the scribe reads. */
	static boolean isLandmark(String name)
	{
		return name != null && java.util.Arrays.stream(LANDMARKS).anyMatch(name::equalsIgnoreCase);
	}

	/** Logout, hop: the scene is gone, and so is everything noticed in it. */
	void reset()
	{
		drops.clear();
		stars.clear();
		seen.clear();
		lastAt.clear();
	}
}
