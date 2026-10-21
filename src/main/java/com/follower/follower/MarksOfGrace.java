package com.follower.follower;

import com.follower.speech.TriggerEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.runelite.api.Perspective;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;

/**
 * Marks of grace on a rooftop course, as the follower sees them: one turning
 * up on the player's roof, one the player walked past and left, one picked
 * up. Each is an event for the rules, and each mark gets at most one line,
 * whichever comes first.
 *
 * <p>"On a course" is the agility watch's own test: agility XP in the last
 * minute, or a crossing under way. A mark anywhere else is somebody's bank
 * trip, not a lap.
 *
 * <p>Every mark in the scene is kept, whether it arrived fresh or with a
 * chunk load, keyed by its tile. A mark already lying on a roof is spotted
 * the first tick the player is on that roof's floor and on a course, which
 * is how one found on arrival is seen without its own spawn.
 *
 * <p>Left behind: the player came within {@link #PASSED_WITHIN} tiles of it,
 * then moved on - a crossing, a change of floor, or agility XP for an
 * obstacle the watch did not see - and, once they are off the obstacle, the
 * mark is still there and they are not beside it. Judged on landing rather
 * than at the start of the next obstacle, so a mark on the roof ahead, seen
 * across a gap, is not left behind by jumping to it. A player who walks past
 * and turns back for it never moved on.
 */
public final class MarksOfGrace
{
	/** The mark of grace, from the game's own item list. */
	public static final int MARK = ItemID.GRACE;

	/** Further off than this, on the same floor, and it is not on screen to be seen. */
	static final int VISIBLE_TILES = 10;

	/** This close and the player has walked past it. */
	static final int PASSED_WITHIN = 3;

	/**
	 * Further than this and the mark is out of the scene: forgotten. A whole
	 * scene, since no despawn comes for a scene thrown away, and a lap runs
	 * further from its own marks than half of one.
	 */
	static final int FORGET_TILES = Perspective.SCENE_SIZE;

	/** Ticks a mark that went stays the one picked up, for an inventory that updates a tick late. */
	static final int PICKUP_TICKS = 2;

	private static final class Mark
	{
		final WorldPoint at;
		boolean spotted;
		boolean near;
		boolean left;
		/** A line about it was heard: it gets no other. */
		boolean voiced;

		Mark(WorldPoint at)
		{
			this.at = at;
		}
	}

	private final Consumer<TriggerEvent> events;
	private final Runnable collected;
	private final Map<WorldPoint, Mark> marks = new HashMap<>();
	/** Where marks were spoken of on this course: a relog brings the same mark back, already said. */
	private final Set<WorldPoint> said = new HashSet<>();

	/** The mark that last went from the ground, and ticks left to match it to the bag. */
	private Mark gone;
	private int goneTicks;

	private int lastPlane = -1;
	private int lastHeld = -1;
	private long seenXpMs;
	/** The player has moved on since the last landing: a crossing, a floor, an obstacle's XP. */
	private boolean movedOn;

	/**
	 * @param events where the rules hear of it
	 * @param collected told of every mark picked up on a course, said or not, for the tally
	 */
	public MarksOfGrace(Consumer<TriggerEvent> events, Runnable collected)
	{
		this.events = events;
		this.collected = collected;
	}

	/** A ground item appeared, fresh or with a chunk load. Only a mark on a roof is kept: one on the ground was dropped. */
	public void spawned(int itemId, WorldPoint at)
	{
		if (itemId == MARK && at != null && at.getPlane() > 0)
		{
			// The game takes a mark away and puts it straight back on its
			// tile now and then (seen every half minute or so on Varrock's
			// roofs, 17 Oct). It is the same mark, with what the follower
			// already made of it, not a new one to spot again.
			if (gone != null && gone.at.equals(at) && !marks.containsKey(at))
			{
				marks.put(at, gone);
				gone = null;
				return;
			}
			Mark mark = marks.computeIfAbsent(at, Mark::new);
			if (said.contains(at))
			{
				mark.spotted = true;
				mark.voiced = true;
			}
		}
	}

	/** A ground item went: picked up, or despawned. */
	public void despawned(int itemId, WorldPoint at)
	{
		if (itemId != MARK || at == null)
		{
			return;
		}
		Mark mark = marks.remove(at);
		if (mark != null)
		{
			gone = mark;
			goneTicks = PICKUP_TICKS;
		}
	}

	/**
	 * Once a game tick, after the ground items and the inventory have
	 * changed for it.
	 *
	 * @param player the player's tile
	 * @param crossing the agility watch has a crossing under way
	 * @param lastAgilityXpMs when agility XP last landed, 0 for never
	 * @param held marks in the inventory, or -1 before it has loaded
	 * @param watching the follower is out and with the player, not parked or put away
	 */
	public void tick(long nowMs, WorldPoint player, boolean crossing, long lastAgilityXpMs, int held,
		boolean watching)
	{
		if (player == null)
		{
			return;
		}
		boolean course = crossing
			|| (lastAgilityXpMs > 0 && nowMs - lastAgilityXpMs <= CrossingWatch.ON_COURSE_MS);
		if (!course)
		{
			said.clear();
		}
		boolean onCourse = watching && course;
		boolean xp = lastAgilityXpMs != seenXpMs;
		seenXpMs = lastAgilityXpMs;
		boolean newFloor = lastPlane >= 0 && player.getPlane() != lastPlane;
		lastPlane = player.getPlane();

		// Into the bag: the mark that just went, and one more held.
		if (gone != null && held > lastHeld && lastHeld >= 0)
		{
			// Spotted on a course, it is that course's mark however long they
			// stood on the roof before taking it.
			if (onCourse || (watching && gone.spotted))
			{
				collected.run();
				if (!gone.voiced)
				{
					raise("collected", gone);
				}
			}
			gone = null;
		}
		else if (gone != null && --goneTicks <= 0)
		{
			gone = null;
		}
		if (held >= 0)
		{
			lastHeld = held;
		}

		movedOn |= crossing || newFloor || xp;
		boolean landed = movedOn && !crossing;
		if (landed)
		{
			movedOn = false;
		}

		Iterator<Mark> it = marks.values().iterator();
		while (it.hasNext())
		{
			Mark mark = it.next();
			boolean samePlane = mark.at.getPlane() == player.getPlane();
			int distance = mark.at.distanceTo2D(player);
			if (distance > FORGET_TILES)
			{
				it.remove();
				continue;
			}
			if (landed && mark.near && !mark.left && onCourse && (!samePlane || distance > PASSED_WITHIN))
			{
				mark.left = true;
				if (!mark.voiced)
				{
					raise("left", mark);
				}
			}
			if (!mark.spotted && onCourse && samePlane && distance <= VISIBLE_TILES)
			{
				mark.spotted = true;
				raise("spotted", mark);
			}
			if (watching && !crossing && samePlane && distance <= PASSED_WITHIN)
			{
				mark.near = true;
			}
		}
	}

	private void raise(String stage, Mark mark)
	{
		events.accept(TriggerEvent.grace(stage, () ->
		{
			mark.voiced = true;
			said.add(mark.at);
		}));
	}

	/** Logout, hop: the scene is gone, and every mark in it. What was said stays while the course does. */
	public void reset()
	{
		marks.clear();
		gone = null;
		lastPlane = -1;
		lastHeld = -1;
		movedOn = false;
	}
}
