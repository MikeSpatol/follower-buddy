package com.follower;

import java.util.*;
import java.util.Set;
import java.util.function.*;
import java.util.function.Consumer;
import net.runelite.api.*;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import lombok.RequiredArgsConstructor;

/**
 * A will of its own, aimed at the player.
 *
 * <p>The companions people love are the ones that are not quite puppets:
 * the Fable II dog that "you don't control, which makes him feel real",
 * Trico's beat of reluctance before it does what it is asked, Agro not
 * turning on a dime. And Ellie, who was given the right not to move out of
 * the player's way until the last second - "with a vocalization admonishing
 * the player for encroaching on her personal space" - which the studio
 * credits with making players respect her. None of it is refusal. A
 * companion that refuses is an escort quest; this is the beat before yes.
 *
 * <p>Two things live here. A Send with a misgiving in it - across the
 * Wilderness ditch, or to a spot beside something that could kill the
 * follower - is walked to its edge first, where the follower turns to look
 * at the player for a couple of ticks and says so, and then goes on if it
 * can. And a parked follower that the player walks straight at steps aside
 * at the last tick, not before, with a word about it.
 */
@RequiredArgsConstructor
class Will
{
	/** What the follower has misgivings about. */
	@RequiredArgsConstructor
	enum Misgiving
	{
		/** The other side of the ditch. */
		WILDERNESS("wilderness"),
		/** Something beside the spot that outclasses the player by a margin. */
		MONSTER("monster");

		final String key;
	}

	/** Ticks the follower stands at the edge, looking back, before going on. */
	static final int HESITATE_TICKS = 3;

	/** The ditch's own two rows; the edge is the row south of them. */
	static final int DITCH_SOUTH_Y = 3519;

	final Client client;
	final FollowerEntity follower;
	final Consumer<TriggerEvent> events;
	/** Whether the follower's park is its own, or the player's: a wander, a Stay. */
	final BooleanSupplier parkedPlainly;

	/** The Send under way with a misgiving: where it was asked to go, and the pause. */
	WorldPoint pending;
	Misgiving misgiving;
	int goOnAt;
	final Set<String> hesitatedAt = new HashSet<>();

	WorldPoint lastPlayerTile;
	int steppedAsideAt = -1000;

	// ------------------------------------------------------------- the send

	/**
	 * The player sent the follower somewhere. With no misgiving it simply
	 * goes; with one, it goes to the edge, turns to the player, and the
	 * rules hear of it.
	 *
	 * @return false when nothing near the target is reachable at all, as
	 * {@link FollowerEntity#stayAt} would say
	 */
	boolean send(WorldPoint target)
	{
		pending = null;
		misgiving = null;
		// A tile with a fire on it, or a booth or table in it that the map
		// calls open floor: the player means over there, not into that, so
		// the Send lands beside it rather than being refused.
		target = beside(target);
		var from = follower.getWorldLocation();
		var local = client.getLocalPlayer();
		var here = local == null ? null : local.getWorldLocation();
		if (target == null || from == null || here == null)
			return follower.stayAt(target);
		Misgiving about = null;
		WorldPoint edge = null;
		String thing = "",
			key = null;
		if (isWilderness(target) && !isWilderness(here) && !isWilderness(from))
		{
			about = Misgiving.WILDERNESS;
			edge = new WorldPoint(target.getX(), Math.min(target.getY(), DITCH_SOUTH_Y), target.getPlane());
			thing = "the Wilderness";
			key = "wilderness";
		}
		else
		{
			var monster = monsterBeside(target, local);
			// A monster with no name gives the line nothing to say: "Next to
			// the that?" So it simply goes.
			if (monster != null && monster.getName() != null)
			{
				about = Misgiving.MONSTER;
				// MONSTER_EDGE: The edge it stops at, short of a monster.
				edge = edgeShortOf(target, from, 3 /* MONSTER_EDGE */);
				thing = monster.getName();
				key = "monster:" + monster.getIndex();
			}
		}
		if (about == null || edge == null || hesitatedAt.contains(key)
			|| !follower.stayAt(edge))
			return follower.stayAt(target);
		hesitatedAt.add(key);
		pending = target;
		misgiving = about;
		follower.setStayFaceTile(here);
		goOnAt = client.getTickCount() + from.distanceTo(edge) + HESITATE_TICKS;
		events.accept(TriggerEvent.hesitated(about.key, thing));
		return true;
	}

	/**
	 * The tile itself when the follower would stand on it, and the nearest
	 * tile round it that it would when it would not. Unchanged when there
	 * is nowhere better: refusing outright is the caller's business.
	 */
	WorldPoint beside(WorldPoint target)
	{
		var from = follower.getWorldLocation();
		if (target == null || (follower.goodGround(target) && !follower.furnished(target)))
			return target;
		var best = target;
		int nearest = Integer.MAX_VALUE;
		for (var spot : SkillingController.ring(target, target, 1, target.getPlane()))
		{
			int away = from == null ? 0 : spot.distanceTo(from);
			if (follower.goodGround(spot) && !follower.furnished(spot) && away < nearest)
			{
				nearest = away;
				best = spot;
			}
		}
		return best;
	}

	/** Whether the tile is on the surface Wilderness's side of the ditch. */
	static boolean isWilderness(WorldPoint tile)
	{
		return tile != null && tile.getPlane() == 0
			// WILDERNESS_MIN_X: The surface Wilderness: north of the ditch, between the walls.
			&& tile.getX() >= 2944 /* WILDERNESS_MIN_X */ && tile.getX() < 3392 /* WILDERNESS_MAX_X */
			&& tile.getY() >= 3520 /* WILDERNESS_MIN_Y */ && tile.getY() < 4000 /* WILDERNESS_MAX_Y */;
	}

	/**
	 * An NPC beside the target that outclasses the player, or null; with
	 * {@code anyLevel}, any NPC beside it, as the force-path asks.
	 */
	NPC monsterBeside(WorldPoint target, Player local)
	{
		var view = client.getTopLevelWorldView();
		if (view == null || local == null)
			return null;
		int level = local.getCombatLevel();
		for (var npc : view.npcs())
		{
			var at = npc == null ? null : npc.getWorldLocation();
			// Another floor is infinitely far, as distanceTo has it.
			if (at == null || npc == local.getInteracting()
				// MONSTER_MARGIN: A monster is one this much above the player's combat level, this close to the spot.
				|| npc.getCombatLevel() < level + 20 /* MONSTER_MARGIN */
				|| at.distanceTo(target) > 2 /* MONSTER_WITHIN */)
				continue;
			return npc;
		}
		return null;
	}

	/**
	 * A tile so many steps short of the target, on the follower's side: the
	 * ring at that distance, nearest the follower, straightest first.
	 */
	static WorldPoint edgeShortOf(WorldPoint target, WorldPoint from, int steps)
	{
		if (from.distanceTo(target) <= steps)
			return null;
		WorldPoint best = null;
		long bestScore = Long.MAX_VALUE;
		for (var spot : SkillingController.ring(target, target, steps, target.getPlane()))
		{
			long ddx = spot.getX() - from.getX(),
				ddy = spot.getY() - from.getY(),
				score = spot.distanceTo(from) * 1_000_000L + ddx * ddx + ddy * ddy;
			if (score < bestScore)
			{
				bestScore = score;
				best = spot;
			}
		}
		return best;
	}

	/** Whether a Send is paused at its edge. */
	boolean isHesitating()
	{
		return pending != null;
	}

	/**
	 * Where the Send just given ends: the spot itself past a monster, which
	 * the pause at the edge only delays, and the edge at the ditch, which it
	 * does not go on from.
	 */
	WorldPoint endsAt()
	{
		return pending != null && misgiving == Misgiving.MONSTER ? pending : follower.getStayTile();
	}

	// ------------------------------------------------------------- the tick

	/** Call once per game tick, on the client thread. */
	void tick()
	{
		int now = client.getTickCount();
		var here = FollowerEntity.playerTile(client);
		if (pending != null)
		{
			if (!follower.isStaying())
			{
				// Released by something else - a Follow, a leash snap.
				pending = null;
				misgiving = null;
			}
			else if (now >= goOnAt)
			{
				var target = pending;
				var about = misgiving;
				pending = null;
				misgiving = null;
				follower.setStayFaceTile(null);
				if (about == Misgiving.MONSTER)
					// The beat is over; it goes, not hurrying.
					follower.stayAt(target);
				// Across the ditch there is no going on: it waits at the edge.
			}
		}
		stepAsideIfWalkedAt(now, here);
		lastPlayerTile = here;
	}

	/**
	 * The player walking straight at a parked follower. Not preemptively:
	 * only when their next step would land on it, the same as a friend who
	 * was standing there first. One tile to the side, and a word.
	 */
	void stepAsideIfWalkedAt(int now, WorldPoint here)
	{
		var tile = follower.getWorldLocation();
		if (here == null || lastPlayerTile == null || tile == null || !follower.isStaying()
			|| !parkedPlainly.getAsBoolean() || !follower.isSettled()
			|| here.getPlane() != tile.getPlane() || here.equals(lastPlayerTile)
			// STEP_ASIDE_COOLDOWN_TICKS: Not two steps aside inside this many ticks.
			|| now - steppedAsideAt < 20 /* STEP_ASIDE_COOLDOWN_TICKS */)
			return;
		int vx = Integer.signum(here.getX() - lastPlayerTile.getX()),
			vy = Integer.signum(here.getY() - lastPlayerTile.getY());
		boolean running = lastPlayerTile.distanceTo(here) >= 2;
		WorldPoint next = here.dx(vx).dy(vy),
			after = here.dx(2 * vx).dy(2 * vy);
		if (!tile.equals(next) && !(running && tile.equals(after)))
			return;
		// The two tiles to either side of the line they are walking, then
		// the one behind the follower along it.
		WorldPoint[] aside = {
			tile.dx(-vy).dy(vx),
			tile.dx(vy).dy(-vx),
			tile.dx(vx).dy(vy),
		};
		for (var spot : aside)
		{
			if (follower.standable(spot) && follower.canStep(tile, spot.getX() - tile.getX(),
				spot.getY() - tile.getY()) && follower.stayAt(spot))
			{
				steppedAsideAt = now;
				events.accept(TriggerEvent.simple(TriggerEvent.Type.STEPPED_ASIDE));
				return;
			}
		}
	}

	/** Logout, hop: the scene and the Send with it. */
	void reset()
	{
		pending = null;
		misgiving = null;
		hesitatedAt.clear();
		lastPlayerTile = null;
		steppedAsideAt = -1000;
	}
}
