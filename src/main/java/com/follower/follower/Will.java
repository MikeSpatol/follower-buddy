// public:file company-extras,will
package com.follower.follower;

import com.follower.speech.TriggerEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

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
@Slf4j
public class Will
{
	/** What the follower has misgivings about. */
	public enum Misgiving
	{
		/** The other side of the ditch. */
		WILDERNESS("wilderness"),
		/** Something beside the spot that outclasses the player by a margin. */
		MONSTER("monster");

		public final String key;

		Misgiving(String key)
		{
			this.key = key;
		}
	}

	/** Ticks the follower stands at the edge, looking back, before going on. */
	static final int HESITATE_TICKS = 3;

	/** A monster is one this much above the player's combat level, this close to the spot. */
	static final int MONSTER_MARGIN = 20;
	static final int MONSTER_WITHIN = 2;

	/** The edge it stops at, short of a monster. */
	static final int MONSTER_EDGE = 3;

	/** The surface Wilderness: north of the ditch, between the walls. */
	static final int WILDERNESS_MIN_X = 2944;
	static final int WILDERNESS_MAX_X = 3392;
	static final int WILDERNESS_MIN_Y = 3520;
	static final int WILDERNESS_MAX_Y = 4000;

	/** The ditch's own two rows; the edge is the row south of them. */
	static final int DITCH_SOUTH_Y = 3519;

	/** Not two steps aside inside this many ticks. */
	static final int STEP_ASIDE_COOLDOWN_TICKS = 20;

	private final Client client;
	private final FollowerEntity follower;
	private final Consumer<TriggerEvent> events;
	/** Whether the follower's park is its own, or the player's: a wander, a Stay. */
	private final BooleanSupplier parkedPlainly;

	/** The Send under way with a misgiving: where it was asked to go, and the pause. */
	private WorldPoint pending;
	private Misgiving misgiving;
	private int goOnAt;
	private final Set<String> hesitatedAt = new HashSet<>();

	// public:cut dev
	/** Set by ::follower force hesitate: the next Send is wary of any NPC beside its spot. */
	private boolean wary;

	// public:end dev
	private WorldPoint lastPlayerTile;
	private int steppedAsideAt = -1000;

	public Will(Client client, FollowerEntity follower, Consumer<TriggerEvent> events,
		BooleanSupplier parkedPlainly)
	{
		this.client = client;
		this.follower = follower;
		this.events = events;
		this.parkedPlainly = parkedPlainly;
	}

	// ------------------------------------------------------------- the send

	/**
	 * The player sent the follower somewhere. With no misgiving it simply
	 * goes; with one, it goes to the edge, turns to the player, and the
	 * rules hear of it.
	 *
	 * @return false when nothing near the target is reachable at all, as
	 * {@link FollowerEntity#stayAt} would say
	 */
	public boolean send(WorldPoint target)
	{
		// public:cut dev
		boolean forced = wary;
		wary = false;
		// public:end dev
		pending = null;
		misgiving = null;
		// A tile with a fire on it, or a booth or table in it that the map
		// calls open floor: the player means over there, not into that, so
		// the Send lands beside it rather than being refused.
		target = beside(target);
		WorldPoint from = follower.getWorldLocation();
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (target == null || from == null || here == null)
		{
			return follower.stayAt(target);
		}
		Misgiving about = null;
		WorldPoint edge = null;
		String thing = "";
		String key = null;
		if (isWilderness(target) && !isWilderness(here) && !isWilderness(from))
		{
			about = Misgiving.WILDERNESS;
			edge = new WorldPoint(target.getX(), Math.min(target.getY(), DITCH_SOUTH_Y), target.getPlane());
			thing = "the Wilderness";
			key = "wilderness";
		}
		else
		{
			NPC monster = monsterBeside(target, local, forced); // public:cut dev
			// public:else NPC monster = monsterBeside(target, local);
			// A monster with no name gives the line nothing to say: "Next to
			// the that?" So it simply goes.
			if (monster != null && monster.getName() != null)
			{
				about = Misgiving.MONSTER;
				edge = edgeShortOf(target, from, MONSTER_EDGE);
				thing = monster.getName();
				key = "monster:" + monster.getIndex();
			}
		}
		if (about == null || edge == null || (hesitatedAt.contains(key) && !forced) // public:cut dev
		// public:else if (about == null || edge == null || hesitatedAt.contains(key)
			|| !follower.stayAt(edge))
		{
			return follower.stayAt(target);
		}
		hesitatedAt.add(key);
		pending = target;
		misgiving = about;
		follower.setStayFaceTile(here);
		goOnAt = client.getTickCount() + from.distanceTo(edge) + HESITATE_TICKS;
		events.accept(TriggerEvent.hesitated(about.key, thing));
		log.debug("Sent to {} with a misgiving ({}); stopping at {} first", target, about, edge); // public:cut logs
		return true;
	}

	/**
	 * The tile itself when the follower would stand on it, and the nearest
	 * tile round it that it would when it would not. Unchanged when there
	 * is nowhere better: refusing outright is the caller's business.
	 */
	private WorldPoint beside(WorldPoint target)
	{
		WorldPoint from = follower.getWorldLocation();
		if (target == null || (follower.goodGround(target) && !follower.furnished(target)))
		{
			return target;
		}
		WorldPoint best = target;
		int nearest = Integer.MAX_VALUE;
		for (WorldPoint spot : SkillingController.ring(target, target, 1, target.getPlane())) // public:cut skilling
		// public:else for (WorldPoint spot : FollowerEntity.ring(target, target, 1, target.getPlane()))
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
			&& tile.getX() >= WILDERNESS_MIN_X && tile.getX() < WILDERNESS_MAX_X
			&& tile.getY() >= WILDERNESS_MIN_Y && tile.getY() < WILDERNESS_MAX_Y;
	}

	/**
	 * An NPC beside the target that outclasses the player, or null; with
	 * {@code anyLevel}, any NPC beside it, as the force-path asks.
	 */
	private NPC monsterBeside(WorldPoint target, Player local, boolean anyLevel) // public:cut dev
	// public:else private NPC monsterBeside(WorldPoint target, Player local)
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null || local == null)
		{
			return null;
		}
		int level = local.getCombatLevel();
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc == null ? null : npc.getWorldLocation();
			// Another floor is infinitely far, as distanceTo has it.
			if (at == null || npc == local.getInteracting()
				|| (npc.getCombatLevel() < level + MONSTER_MARGIN && !anyLevel) // public:cut dev
				// public:else || npc.getCombatLevel() < level + MONSTER_MARGIN
				|| at.distanceTo(target) > MONSTER_WITHIN)
			{
				continue;
			}
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
		{
			return null;
		}
		WorldPoint best = null;
		long bestScore = Long.MAX_VALUE;
		for (WorldPoint spot : SkillingController.ring(target, target, steps, target.getPlane())) // public:cut skilling
		// public:else for (WorldPoint spot : FollowerEntity.ring(target, target, steps, target.getPlane()))
		{
			long ddx = spot.getX() - from.getX();
			long ddy = spot.getY() - from.getY();
			long score = spot.distanceTo(from) * 1_000_000L + ddx * ddx + ddy * ddy;
			if (score < bestScore)
			{
				bestScore = score;
				best = spot;
			}
		}
		return best;
	}

	// public:cut dev
	/**
	 * ::follower force hesitate: the next Send hesitates over any NPC beside
	 * its spot as it would over one that outclassed the player, even one it
	 * has hesitated over before. The edge, the beat and the line are the
	 * real ones; only the level margin and the once-each list are waived.
	 * Spent by that Send, whether or not an NPC was there, and dropped with
	 * the scene.
	 */
	public void forceHesitate()
	{
		wary = true;
	}

	// public:end dev
	/** Whether a Send is paused at its edge. */
	public boolean isHesitating()
	{
		return pending != null;
	}

	/**
	 * Where the Send just given ends: the spot itself past a monster, which
	 * the pause at the edge only delays, and the edge at the ditch, which it
	 * does not go on from.
	 */
	public WorldPoint endsAt()
	{
		return pending != null && misgiving == Misgiving.MONSTER ? pending : follower.getStayTile();
	}

	// ------------------------------------------------------------- the tick

	/** Call once per game tick, on the client thread. */
	public void tick()
	{
		int now = client.getTickCount();
		WorldPoint here = FollowerEntity.playerTile(client);
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
				WorldPoint target = pending;
				Misgiving about = misgiving;
				pending = null;
				misgiving = null;
				follower.setStayFaceTile(null);
				if (about == Misgiving.MONSTER)
				{
					// The beat is over; it goes, not hurrying.
					follower.stayAt(target);
				}
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
	private void stepAsideIfWalkedAt(int now, WorldPoint here)
	{
		WorldPoint tile = follower.getWorldLocation();
		if (here == null || lastPlayerTile == null || tile == null || !follower.isStaying()
			|| !parkedPlainly.getAsBoolean() || !follower.isSettled()
			|| here.getPlane() != tile.getPlane() || here.equals(lastPlayerTile)
			|| now - steppedAsideAt < STEP_ASIDE_COOLDOWN_TICKS)
		{
			return;
		}
		int vx = Integer.signum(here.getX() - lastPlayerTile.getX());
		int vy = Integer.signum(here.getY() - lastPlayerTile.getY());
		boolean running = lastPlayerTile.distanceTo(here) >= 2;
		WorldPoint next = here.dx(vx).dy(vy);
		WorldPoint after = here.dx(2 * vx).dy(2 * vy);
		if (!tile.equals(next) && !(running && tile.equals(after)))
		{
			return;
		}
		// The two tiles to either side of the line they are walking, then
		// the one behind the follower along it.
		WorldPoint[] aside = {
			tile.dx(-vy).dy(vx),
			tile.dx(vy).dy(-vx),
			tile.dx(vx).dy(vy),
		};
		for (WorldPoint spot : aside)
		{
			if (follower.standable(spot) && follower.canStep(tile, spot.getX() - tile.getX(),
				spot.getY() - tile.getY()) && follower.stayAt(spot))
			{
				steppedAsideAt = now;
				events.accept(TriggerEvent.simple(TriggerEvent.Type.STEPPED_ASIDE));
				log.debug("Walked at: a step aside to {}", spot); // public:cut logs
				return;
			}
		}
	}

	/** Logout, hop: the scene and the Send with it. */
	public void reset()
	{
		pending = null;
		misgiving = null;
		hesitatedAt.clear();
		wary = false; // public:cut dev
		lastPlayerTile = null;
		steppedAsideAt = -1000;
	}
}
