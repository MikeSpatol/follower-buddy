// public:file agility,agility-all
package com.follower.follower;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer; // public:cut dev
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;

/**
 * Watches the player for agility obstacles and shortcuts, and records each
 * one for the follower to do after them.
 *
 * <p>Nothing here knows any obstacle. A crossing is recognised by how the
 * player moves, measured against how they move the rest of the time:
 *
 * <ul>
 * <li><b>An odd pose.</b> The player's pose leaves their own stand, walk,
 * run and turn set - the log balance, the ledge shimmy, the monkey bars.
 * The game does these by swapping the player's base animations, so the set
 * the pose is measured against is the one they had BEFORE the swap: the
 * normal set is only re-taken when the weapon changes, which is the one
 * thing that legitimately changes it.</li>
 * <li><b>A glide.</b> The fine position moving while the pose is not a
 * walking pose. Ordinary steps always show a walk or a run; the game's
 * forced movement (a jump, a squeeze, a vault) shows the stand, or an odd
 * pose. Counted only over consecutive frames, so the one frame where a walk
 * ends on the stand can never add up to one; a frame that only repeats the
 * last (more frames drawn than game cycles) neither counts nor breaks it.
 * A running jump counts at once: the server puts them further than their
 * walk goes, with a clip on, while the client still draws the walk.</li>
 * <li><b>An action with a hop.</b> An animation, then the player put down a
 * tile or more away or on another floor: a climb, a jump across a gap. A
 * ladder looks exactly the same, so this one needs agility XP, or damage (a
 * fall), to count.</li>
 * <li><b>Agility XP after something moved them.</b> Every course obstacle
 * pays XP as it completes. XP while an unconfirmed run has moved the player
 * confirms it; XP with nothing open, or for movement before what is open,
 * rebuilds the crossing from the last few seconds of the player (see
 * {@link #BUFFER_MS}), so an obstacle none of the above caught is late but
 * never skipped.</li>
 * </ul>
 *
 * <p>An animation alone - an emote, a swing, a skilling loop, eating on the
 * run - never counts: nothing moved, or it moved them on an ordinary walk
 * with no XP. Walking on normally ends a run that has not counted yet. A
 * fight or a conversation (the player interacting with an actor) is never a
 * crossing, and nor is anything while the player is transformed into an NPC
 * or aboard a boat; the plugin also stops watching in a cutscene. A jump
 * further than the follower's own snap distance is a teleport: whatever was
 * being watched is dropped and the follower's queue with it.
 *
 * <p>A crossing is handed to the follower the moment it is recognised, still
 * recording, so the follower can begin it a couple of ticks behind the
 * player. It ends once the player has been back to normal for a while:
 * {@link #SETTLE_MS} standing, or {@link #WALK_OFF_MS} walking on. It is cut
 * where the drawn player reaches the tile the server stepped them off to
 * as their own set came back, so the follower does not copy the walk away,
 * nor stop short of the end of a rope.
 */
public final class CrossingWatch
{
	/** Where the crossings go; {@link FollowerEntity} is the one that copies them. */
	public interface Listener
	{
		/** Something has begun at this tile that may be a crossing (a climb awaiting its XP): hold, do not snap. */
		void expectCrossing(WorldPoint start);

		/** A crossing, recognised: still recording unless {@link Crossing#isComplete}. */
		void queueCrossing(Crossing crossing);

		/** What was expected was not a crossing after all. */
		void cancelExpectedCrossing();

		/** The player is gone (a teleport): whatever is queued is let go. */
		void clearCrossings();
	}

	/**
	 * Fine units of gliding, past the first glide frame, before a glide
	 * counts: three eighths of a tile. A forced move covers tiles.
	 */
	static final long GLIDE_UNITS = 48;

	/** Back to standing normally this long and the crossing is over: two ticks, so a climb's pause before the hop is not its end. */
	static final long SETTLE_MS = 1200;

	/** Walking on normally this long and it is over: half a tick, so the next obstacle is a crossing of its own. */
	static final long WALK_OFF_MS = 300;

	/** How long a climb's hop waits for the XP drop (or the fall's damage) that says it was agility: two ticks. */
	static final long CONFIRM_WAIT_MS = 1200;

	/**
	 * The same wait on a course - agility XP in the last {@link #ON_COURSE_MS}
	 * - where a hop is far more likely an obstacle than a ladder: five ticks,
	 * so XP that comes a tick or two late still confirms it in place.
	 */
	static final long CONFIRM_WAIT_ON_COURSE_MS = 3000;

	/** Agility XP this recently means the player is on a course. A lap of the longest takes about a minute. */
	static final long ON_COURSE_MS = 60_000;

	/**
	 * The longest a crossing may run. Every obstacle and shortcut takes
	 * seconds; a recording past half a minute is something else wearing an
	 * odd pose - a carpet ride, swimming, carrying something in a minigame -
	 * and is dropped, with that pose taken as normal until the player's own
	 * set is back on them.
	 */
	static final long MAX_CROSSING_MS = 30_000;

	/** Frames kept for one crossing, a safety cap past MAX_CROSSING_MS: half a minute at three hundred a second. */
	static final int MAX_FRAMES = 9000;

	/** A hop further than this is a teleport: the follower's own snap distance. */
	static final int MAX_HOP_TILES = FollowerEntity.SNAP_DISTANCE;

	/**
	 * A whole tile in one frame, off a walking pose, with an animation on:
	 * a hop. A jump across a gap can put the player down one tile over with
	 * nothing between; ordinary steps never cover a tile in a frame.
	 */
	static final long HOP_UNITS = 128;

	/**
	 * How much of the player is remembered for rebuilding a crossing the
	 * signals missed when its XP arrives: ten seconds, longer than any one
	 * obstacle of a rooftop course, at most {@link #BUFFER_FRAMES} frames.
	 */
	static final long BUFFER_MS = 10_000;

	static final int BUFFER_FRAMES = 3000;

	/**
	 * XP rebuilds a crossing only from movement that ended this recently.
	 * An obstacle pays as it completes; XP from a lamp, a book or a quest
	 * comes long after any walk, and so rebuilds nothing.
	 */
	static final long REBUILD_RECENT_MS = 3000;

	/** One reading of the player, taken each frame. */
	public static final class Sample
	{
		final long atMs;
		final long fineX;
		final long fineY;
		final int plane;
		final int orientation;
		final int pose;
		final int poseFrame;
		final int action;
		final int actionFrame;
		/** Idle, walk, run, back, left, right, turn left, turn right: the base animations now. */
		final int[] base;
		final int weapon;
		final boolean interacting;
		/** The tile the server has them on, which leads the drawn one on a walk. */
		final WorldPoint server;

		public Sample(long atMs, long fineX, long fineY, int plane, int orientation,
			int pose, int poseFrame, int action, int actionFrame, int[] base, int weapon,
			boolean interacting)
		{
			this(atMs, fineX, fineY, plane, orientation, pose, poseFrame, action, actionFrame, base, weapon,
				interacting, new WorldPoint((int) (fineX >> 7), (int) (fineY >> 7), plane));
		}

		Sample(long atMs, long fineX, long fineY, int plane, int orientation,
			int pose, int poseFrame, int action, int actionFrame, int[] base, int weapon,
			boolean interacting, WorldPoint server)
		{
			this.server = server;
			this.atMs = atMs;
			this.fineX = fineX;
			this.fineY = fineY;
			this.plane = plane;
			this.orientation = orientation;
			this.pose = pose;
			this.poseFrame = poseFrame;
			this.action = action;
			this.actionFrame = actionFrame;
			this.base = base;
			this.weapon = weapon;
			this.interacting = interacting;
		}

		/**
		 * The player now, or null when there is nothing worth watching: no
		 * position yet, aboard a boat (its own world view, its own moves), or
		 * transformed into an NPC, whose walk is not theirs.
		 *
		 * <p>The fine position is the RENDERED one, taken in world units: the
		 * scene's base plus the local position, which survives a rebuild where
		 * the local position alone does not.
		 */
		public static Sample read(Player local, long nowMs)
		{
			if (local == null)
			{
				return null;
			}
			LocalPoint at = local.getLocalLocation();
			WorldPoint tile = local.getWorldLocation();
			WorldView view = local.getWorldView();
			PlayerComposition composition = local.getPlayerComposition();
			if (at == null || tile == null || view == null || !view.isTopLevel()
				|| composition == null || composition.getTransformedNpcId() != -1)
			{
				return null;
			}
			int[] base = {
				local.getIdlePoseAnimation(),
				local.getWalkAnimation(),
				local.getRunAnimation(),
				local.getWalkRotate180(),
				local.getWalkRotateLeft(),
				local.getWalkRotateRight(),
				local.getIdleRotateLeft(),
				local.getIdleRotateRight()};
			return new Sample(nowMs,
				view.getBaseX() * 128L + at.getX(), view.getBaseY() * 128L + at.getY(),
				tile.getPlane(), local.getOrientation(),
				local.getPoseAnimation(), local.getPoseAnimationFrame(),
				local.getAnimation(), local.getAnimationFrame(),
				base, composition.getEquipmentId(KitType.WEAPON),
				local.getInteracting() != null, tile);
		}

		Crossing.Frame frame()
		{
			return new Crossing.Frame(atMs, fineX, fineY, plane, orientation,
				pose, poseFrame, action, actionFrame);
		}
	}

	/** Damage that can come from a fall: a hit, not poison, venom, disease or a drain. */
	public static boolean isFallDamage(int hitsplatType)
	{
		return hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.DAMAGE_OTHER
			|| hitsplatType == HitsplatID.DAMAGE_MAX_ME;
	}

	/** What is being watched: the frames since something odd began. */
	private static final class Run
	{
		final List<Crossing.Frame> frames = new ArrayList<>();
		final long startMs;
		long glided;
		boolean lastGlided;
		boolean confirmed;
		/** Handed to the follower once confirmed, and grown frame by frame after. */
		Crossing crossing;
		// public:cut dev
		/** What confirmed it, for the trace. */
		String why;
		// public:end dev
		/** The listener was told to expect it. */
		boolean announced;
		/** When the player was put down somewhere new, or -1. */
		long hopMs = -1;
		/** Since when the player has been back to normal, and the frame it began, or -1. */
		long calmSinceMs = -1;
		int calmIndex = -1;
		boolean calmWalked;
		/** The server's tile on the first normal frame: the calm counts from the drawn player's reaching it. */
		WorldPoint steppedOffTo;

		Run(Sample first)
		{
			startMs = first.atMs;
			frames.add(first.frame());
		}

		WorldPoint start()
		{
			return frames.get(0).tile();
		}

		/** Whether the player is on another tile or floor than it began on. */
		boolean displaced()
		{
			return !frames.get(frames.size() - 1).tile().equals(start());
		}
	}

	/** One remembered frame, with what the watch made of it. */
	private static final class Seen
	{
		final Crossing.Frame frame;
		/** Their own stand or walk, with nothing playing. */
		final boolean calm;
		final boolean walking;
		/** Moved by something other than their own walk: a glide, a hop, an odd pose or an animation on the move. */
		final boolean unusual;
		final WorldPoint server;

		Seen(Crossing.Frame frame, boolean calm, boolean walking, boolean unusual, WorldPoint server)
		{
			this.server = server;
			this.frame = frame;
			this.calm = calm;
			this.walking = walking;
			this.unusual = unusual;
		}
	}

	private final Listener listener;

	/** The player's own set and the weapon it goes with; null until the first reading. */
	private int[] normal;
	private int normalWeapon;

	/**
	 * The player's own set while a long swapped one (swimming, a carpet)
	 * stands in for it, to go back to the moment it is on them again.
	 */
	private int[] ownSet;

	private Sample previous;
	private Run run;
	private long lastXpMs = Long.MIN_VALUE;
	private long lastDamageMs = Long.MIN_VALUE;

	/** After a run too long to be an obstacle: nothing new starts until the player looks normal. */
	private boolean cooling;

	/** The last few seconds of the player, oldest first. */
	private final Deque<Seen> recent = new ArrayDeque<>();

	/** Frames up to this time are in a crossing already handed over, or are past caring about. */
	private long coveredToMs = Long.MIN_VALUE;

	/** Agility XP has landed and the next frame looks for what it paid for. */
	private boolean xpToCheck;
	// public:cut dev

	/** The last frame anything odd moved or posed the player, for the trace's "near agility". */
	private long lastOddMs = Long.MIN_VALUE;

	/** Where the trace's event lines go; nowhere unless the plugin says. */
	private Consumer<String> trace = line -> { };

	// public:end dev
	public CrossingWatch(Listener listener)
	{
		this.listener = listener;
	}

	// public:cut dev
	public void setTrace(Consumer<String> trace)
	{
		this.trace = trace == null ? line -> { } : trace;
	}

	// public:end dev
	/** Agility XP landed: the hop being watched, if any, was an obstacle, and anything missed is rebuilt. */
	public void noteAgilityXp(long atMs)
	{
		lastXpMs = atMs;
		xpToCheck = true;
	}

	/** The player was hurt: on a crossing, that is a fall. */
	public void noteDamage(long atMs)
	{
		lastDamageMs = atMs;
	}

	/** Whether a crossing is being watched or recorded right now. */
	public boolean isWatching()
	{
		return run != null;
	}

	/** Whether what is being watched has moved the player: a skilling loop or an emote has not. */
	public boolean isWatchingMovement()
	{
		return run != null && (run.displaced() || run.glided > 0 || run.hopMs >= 0);
	}

	// public:cut dev
	/** Whether anything odd moved or posed the player within this long before {@code nowMs}. */
	public boolean wasOddWithin(long nowMs, long windowMs)
	{
		return lastOddMs != Long.MIN_VALUE && nowMs - lastOddMs <= windowMs;
	}

	// public:end dev
	/**
	 * The follower was put beside the player: whatever the player did before
	 * now is behind it, and XP arriving for it must not send it back there.
	 */
	public void forgetMissed()
	{
		recent.clear();
		if (previous != null)
		{
			coveredToMs = previous.atMs;
		}
	}

	// public:cut dev
	/** The watch's state in a few words, for the trace. */
	public String describe(long nowMs)
	{
		if (cooling)
		{
			return "cooling";
		}
		if (run == null)
		{
			return "idle";
		}
		String state = run.confirmed ? "rec(" + run.why + ")"
			: run.hopMs >= 0 ? "await-xp" : "watch";
		return state + " " + seconds(nowMs - run.startMs) + " " + run.frames.size() + "f"
			+ (run.calmSinceMs >= 0 ? " calm" : "");
	}

	// public:end dev
	/**
	 * Stops watching: a logout, a hop, the setting turned off, a cutscene.
	 * Whatever was half-recorded is dropped. The player's normal set is
	 * kept, since it is the thing a half-way reading would get wrong.
	 */
	public void reset()
	{
		discard("watching stopped");
		previous = null;
		cooling = false;
		recent.clear();
		xpToCheck = false;
	}

	/** One frame of the player, or null when there is nothing to read. */
	public void sample(Sample s)
	{
		if (s == null)
		{
			reset();
			return;
		}
		if (normal == null || (s.weapon != normalWeapon && run == null))
		{
			normal = s.base.clone();
			normalWeapon = s.weapon;
			ownSet = null;
		}
		else if (ownSet != null && java.util.Arrays.equals(s.base, ownSet))
		{
			// Out of the water: their own walk is not a glide.
			normal = ownSet;
			ownSet = null;
		}

		Sample prev = previous;
		previous = s;
		if (prev == null)
		{
			remember(new Seen(s.frame(), true, false, false, s.server));
			return;
		}

		long dx = Math.abs(s.fineX - prev.fineX);
		long dy = Math.abs(s.fineY - prev.fineY);
		long moved = Math.max(dx, dy);
		if (moved > MAX_HOP_TILES * 128L)
		{
			// A teleport. Nothing of this is copied, and nothing queued
			// either: the follower catches up the way it always has.
			discard("teleport");
			recent.clear();
			coveredToMs = s.atMs;
			xpToCheck = false;
			trace.accept("watch: teleport of " + (moved / 128) + " tiles; queue let go"); // public:cut dev
			listener.clearCrossings();
			return;
		}

		boolean planeChanged = s.plane != prev.plane;
		boolean walkingPose = isWalkingPose(s.pose);
		boolean oddPose = s.pose != -1 && !contains(normal, s.pose);
		boolean animating = s.action != -1 || prev.action != -1;
		// A running jump: the server puts them further than their walk or run
		// goes, with a clip on, while the client still draws the walk. Moved
		// by the game, as a glide is, though it looks like walking.
		boolean leapt = !planeChanged && s.action != -1
			&& Math.max(Math.abs(s.server.getX() - prev.server.getX()), Math.abs(s.server.getY() - prev.server.getY()))
			> (s.pose == normal[1] ? 1 : 2);
		// A walking pose with a big step is a slow frame of ordinary steps;
		// a tile in one frame off a walking pose, with a clip on, is a hop.
		boolean hop = planeChanged || (moved > 256 && !walkingPose)
			|| (moved >= HOP_UNITS && !walkingPose && (animating || oddPose));
		boolean walking = !planeChanged && !leapt && moved > 0 && walkingPose;
		boolean glide = !planeChanged && moved > 0 && moved <= 256 && !walkingPose;
		boolean odd = oddPose || s.action != -1 || glide;
		boolean unusual = (moved > 0 || planeChanged)
			&& (planeChanged || !walkingPose || oddPose || s.action != -1);
		remember(new Seen(s.frame(), !odd && !hop, walking, unusual, s.server));
		// public:cut dev
		if (unusual || oddPose)
		{
			lastOddMs = s.atMs;
		}
		// public:end dev

		if (xpToCheck)
		{
			// Before this frame's run is handled, so anything rebuilt is
			// queued ahead of a crossing this frame confirms.
			xpToCheck = false;
			rebuildMissed(s.atMs);
		}

		if (cooling)
		{
			if (oddPose || s.action != -1)
			{
				return;
			}
			cooling = false;
		}

		if (run == null)
		{
			if (!odd || walking || s.interacting)
			{
				return;
			}
			// Begun from where they stood a frame ago: a glide has already left it.
			run = new Run(prev);
		}
		Crossing.Frame frame = s.frame();
		run.frames.add(frame);
		if (run.crossing != null)
		{
			run.crossing.append(frame);
		}

		if (s.interacting)
		{
			// A knock-back in a fight, a scripted step in a conversation.
			discard("interacting");
			return;
		}

		// A frame that repeats the last (more frames than game cycles) is
		// neither a glide nor a break in one, unless it shows a walk.
		if (moved > 0 || planeChanged || walkingPose)
		{
			run.glided += glide && run.lastGlided ? moved : 0;
			run.lastGlided = glide;
		}
		if (hop)
		{
			run.hopMs = s.atMs;
		}

		if (!run.confirmed)
		{
			boolean xp = lastXpMs >= run.startMs;
			boolean hurt = lastDamageMs >= run.startMs;
			if (run.glided >= GLIDE_UNITS || leapt)
			{
				confirm(leapt ? "leap" : "glide"); // public:cut dev
				// public:else confirm();
			}
			else if (run.hopMs >= 0 && (xp || hurt))
			{
				confirm(xp ? "hop+xp" : "hop+fall"); // public:cut dev
				// public:else confirm();
			}
			else if (xp && run.displaced())
			{
				confirm("moved+xp"); // public:cut dev
				// public:else confirm();
			}
			else if (run.hopMs >= 0)
			{
				// Possibly a climb: the follower holds where it is rather than
				// snapping after the player while the XP is awaited.
				if (!run.announced)
				{
					run.announced = true;
					// public:cut dev
					trace.accept("watch: hop from " + describe(run.start()) + " to " + describe(frame.tile())
						+ "; holding for xp");
					// public:end dev
					listener.expectCrossing(run.start());
				}
				long wait = onCourse(s.atMs) ? CONFIRM_WAIT_ON_COURSE_MS : CONFIRM_WAIT_MS;
				if (s.atMs - run.hopMs > wait)
				{
					// No XP and no fall: a ladder, a trapdoor, stairs.
					discard("no xp " + seconds(wait) + " after the hop"); // public:cut dev
					// public:else discard("no xp after the hop");
					return;
				}
			}
			else if (walking)
			{
				// Walked on: whatever played, it was not an obstacle.
				discard("walked on");
				return;
			}
		}

		if (odd || hop)
		{
			run.calmSinceMs = -1;
			run.calmWalked = false;
			run.steppedOffTo = null;
		}
		else if (run.calmSinceMs < 0)
		{
			// Back to normal as the server steps them off, with the drawn
			// player still a tile or two along the rope: calm from where it
			// put them down, once they are drawn there.
			if (run.steppedOffTo == null)
			{
				run.steppedOffTo = s.server;
			}
			if (frame.tile().equals(run.steppedOffTo))
			{
				run.calmSinceMs = s.atMs;
				run.calmIndex = run.frames.size() - 1;
				run.calmWalked = walking;
			}
		}
		else
		{
			run.calmWalked |= walking;
		}

		if (s.atMs - run.startMs > MAX_CROSSING_MS || run.frames.size() > MAX_FRAMES)
		{
			discard("ran past " + seconds(MAX_CROSSING_MS) + ": not an obstacle"); // public:cut dev
			// public:else discard("ran past");
			cooling = true;
			if (s.weapon == normalWeapon)
			{
				// Whatever this is, it is how they move now, until their own
				// set is back.
				if (ownSet == null)
				{
					ownSet = normal;
				}
				normal = s.base.clone();
			}
			return;
		}

		if (run.calmSinceMs >= 0
			&& s.atMs - run.calmSinceMs >= (run.calmWalked ? WALK_OFF_MS : SETTLE_MS))
		{
			if (run.confirmed)
			{
				finish();
			}
			else if (run.hopMs < 0)
			{
				// Stood still again with nothing moved: an emote, a swing.
				discard("nothing moved");
			}
		}
	}

	private boolean onCourse(long nowMs)
	{
		return lastXpMs != Long.MIN_VALUE && nowMs - lastXpMs <= ON_COURSE_MS;
	}

	private void confirm(String why) // public:cut dev
	// public:else private void confirm()
	{
		run.confirmed = true;
		run.why = why; // public:cut dev
		run.announced = true;
		run.crossing = Crossing.live(run.frames, normal);
		trace.accept("watch: crossing recognised (" + why + ") from " + describe(run.start())); // public:cut dev
		listener.queueCrossing(run.crossing);
	}

	private void finish()
	{
		Run done = run;
		run = null;
		done.crossing.end(done.calmIndex + 1, lastDamageMs >= done.startMs);
		coveredToMs = done.frames.get(done.calmIndex).atMs;
		// public:cut dev
		trace.accept("watch: crossing ended at " + describe(done.crossing.getEnd()) + ", "
			+ seconds(done.crossing.getDurationMs()) + (done.crossing.isFell() ? ", fell" : ""));
		// public:end dev
	}

	private void discard(String why)
	{
		Run dropped = run;
		run = null;
		if (dropped == null)
		{
			return;
		}
		if (dropped.crossing != null)
		{
			// Already handed over. Ended where it got to; and if it was not an
			// obstacle at all, one the follower has not begun is skipped.
			if (why.startsWith("ran past") || why.equals("interacting"))
			{
				dropped.crossing.drop();
			}
			else
			{
				dropped.crossing.cut();
			}
			coveredToMs = dropped.frames.get(dropped.frames.size() - 1).atMs;
			// public:cut dev
			trace.accept("watch: crossing " + (dropped.crossing.isDropped() ? "dropped" : "cut")
				+ " (" + why + ") at " + describe(dropped.crossing.getEnd()));
			// public:end dev
		}
		else if (dropped.announced)
		{
			trace.accept("watch: let go of the hop (" + why + ")"); // public:cut dev
			listener.cancelExpectedCrossing();
		}
	}

	private void remember(Seen seen)
	{
		recent.addLast(seen);
		long oldest = seen.frame.atMs - BUFFER_MS;
		while (recent.size() > BUFFER_FRAMES || recent.peekFirst().frame.atMs < oldest)
		{
			recent.removeFirst();
		}
	}

	/**
	 * Agility XP arrived: if the player was moved oddly since the last
	 * crossing handed over, and before whatever is being watched now, that
	 * movement was an obstacle the signals missed. It is rebuilt from the
	 * remembered frames and queued like any other, late but not skipped.
	 *
	 * <p>It starts on the last frame of the player's own stand or walk before
	 * the odd movement, and ends on the first after it - or now, if they
	 * are still at it - as a crossing seen live would.
	 */
	private void rebuildMissed(long nowMs)
	{
		// Odd frames that run straight into what is being watched lead into
		// it, a clip begun on the last step towards a climb: not a crossing
		// of their own.
		long until = run == null ? Long.MAX_VALUE : run.startMs;
		for (java.util.Iterator<Seen> back = recent.descendingIterator(); run != null && back.hasNext(); )
		{
			Seen seen = back.next();
			if (seen.frame.atMs >= run.startMs)
			{
				continue;
			}
			if (!seen.unusual)
			{
				break;
			}
			until = seen.frame.atMs;
		}
		List<Seen> window = new ArrayList<>();
		for (Seen seen : recent)
		{
			long at = seen.frame.atMs;
			if (at > coveredToMs && at < until)
			{
				window.add(seen);
			}
		}
		int last = -1;
		for (int i = window.size() - 1; i >= 0; i--)
		{
			if (window.get(i).unusual)
			{
				last = i;
				break;
			}
		}
		if (last < 0 || nowMs - window.get(last).frame.atMs > REBUILD_RECENT_MS)
		{
			trace.accept("watch: xp, nothing missed"); // public:cut dev
			return;
		}

		// Back to the last stretch of their own stand or walk long enough to
		// have ended a crossing: the odd movement began after it.
		int first = last;
		boolean walked = false;
		for (int i = last - 1; i >= 0; i--)
		{
			Seen seen = window.get(i);
			if (!seen.calm)
			{
				first = i;
				walked = false;
				continue;
			}
			walked |= seen.walking;
			long stretch = window.get(first).frame.atMs - seen.frame.atMs;
			if (stretch >= (walked ? WALK_OFF_MS : SETTLE_MS))
			{
				break;
			}
		}
		int start = Math.max(0, first - 1);
		// As a crossing seen live ends: where the drawn player reaches the
		// tile the server stepped them off to.
		int end = window.size() - 1;
		WorldPoint steppedOffTo = null;
		for (int i = last + 1; i < window.size(); i++)
		{
			Seen seen = window.get(i);
			if (!seen.calm)
			{
				steppedOffTo = null;
				continue;
			}
			if (steppedOffTo == null)
			{
				steppedOffTo = seen.server;
			}
			if (seen.frame.tile().equals(steppedOffTo))
			{
				end = i;
				break;
			}
		}

		List<Crossing.Frame> frames = new ArrayList<>();
		for (int i = start; i <= end; i++)
		{
			frames.add(window.get(i).frame);
		}
		Crossing rebuilt = new Crossing(frames, normal,
			lastDamageMs >= frames.get(0).atMs);
		if (rebuilt.getStart().equals(rebuilt.getEnd()))
		{
			trace.accept("watch: xp, odd movement at " + describe(rebuilt.getStart()) + " went nowhere"); // public:cut dev
			return;
		}
		coveredToMs = window.get(end).frame.atMs;
		// public:cut dev
		trace.accept("watch: crossing rebuilt from xp, " + describe(rebuilt.getStart()) + " to "
			+ describe(rebuilt.getEnd()) + ", " + seconds(rebuilt.getDurationMs())
			+ ", " + frames.size() + " frames");
		// public:end dev
		listener.queueCrossing(rebuilt);
	}

	/** Walk, run, back-pedal and the side-steps of the player's own set. */
	private boolean isWalkingPose(int pose)
	{
		for (int i = 1; i <= 5 && i < normal.length; i++)
		{
			if (normal[i] == pose)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean contains(int[] set, int value)
	{
		for (int each : set)
		{
			if (each == value)
			{
				return true;
			}
		}
		return false;
	}
	// public:cut dev

	public static String describe(WorldPoint p)
	{
		return p == null ? "-" : p.getX() + "," + p.getY() + "," + p.getPlane();
	}

	static String seconds(long ms)
	{
		return String.format("%.1fs", ms / 1000.0);
	}
	// public:end dev
}
