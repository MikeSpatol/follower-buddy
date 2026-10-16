package com.follower.follower;

import java.util.ArrayList;
import java.util.List;
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
 * ends on the stand can never add up to one.</li>
 * <li><b>An action with a hop.</b> An animation, then the player put down a
 * few tiles away or on another floor: a climb. A ladder looks exactly the
 * same, so this one needs agility XP, or damage (a fall), to count.</li>
 * </ul>
 *
 * <p>An animation alone - an emote, a swing, a skilling loop, eating on the
 * run - never counts: nothing moved. Walking on normally ends a run that has
 * not counted yet. A fight or a conversation (the player interacting with
 * an actor) is never a crossing, and nor is anything while the player is
 * transformed into an NPC or aboard a boat; the plugin also stops watching
 * in a cutscene. A jump further than the follower's own snap distance is a
 * teleport: whatever was being watched is dropped and the follower's queue
 * with it.
 *
 * <p>A crossing ends once the player has been back to normal for a while:
 * {@link #SETTLE_MS} standing, or {@link #WALK_OFF_MS} walking on. It is cut
 * at the first normal frame, so the follower does not copy the walk away.
 */
public final class CrossingWatch
{
	/** Where the crossings go; {@link FollowerEntity} is the one that copies them. */
	public interface Listener
	{
		/** A crossing has begun at this tile: stop following and go there. */
		void expectCrossing(WorldPoint start);

		/** The crossing expected last is over and recorded. */
		void queueCrossing(Crossing crossing);

		/** It was not a crossing after all. */
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
	 * The longest a crossing may run. Every obstacle and shortcut takes
	 * seconds; a recording past half a minute is something else wearing an
	 * odd pose - a carpet ride, swimming, carrying something in a minigame -
	 * and is dropped, with that pose taken as normal from then on.
	 */
	static final long MAX_CROSSING_MS = 30_000;

	/** Frames kept for one crossing: half a minute at a hundred frames a second. */
	static final int MAX_FRAMES = 3000;

	/** A hop further than this is a teleport: the follower's own snap distance. */
	static final int MAX_HOP_TILES = FollowerEntity.SNAP_DISTANCE;

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

		public Sample(long atMs, long fineX, long fineY, int plane, int orientation,
			int pose, int poseFrame, int action, int actionFrame, int[] base, int weapon,
			boolean interacting)
		{
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
				local.getInteracting() != null);
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
		/** The listener was told to expect it. */
		boolean announced;
		/** When the player was put down somewhere new, or -1. */
		long hopMs = -1;
		/** Since when the player has been back to normal, and the frame it began, or -1. */
		long calmSinceMs = -1;
		int calmIndex = -1;
		boolean calmWalked;

		Run(Sample first)
		{
			startMs = first.atMs;
			frames.add(first.frame());
		}

		WorldPoint start()
		{
			return frames.get(0).tile();
		}
	}

	private final Listener listener;

	/** The player's own set and the weapon it goes with; null until the first reading. */
	private int[] normal;
	private int normalWeapon;

	private Sample previous;
	private Run run;
	private long lastXpMs = Long.MIN_VALUE;
	private long lastDamageMs = Long.MIN_VALUE;

	/** After a run too long to be an obstacle: nothing new starts until the player looks normal. */
	private boolean cooling;

	public CrossingWatch(Listener listener)
	{
		this.listener = listener;
	}

	/** Agility XP landed: the hop being watched, if any, was an obstacle. */
	public void noteAgilityXp(long atMs)
	{
		lastXpMs = atMs;
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

	/**
	 * Stops watching: a logout, a hop, the setting turned off, a cutscene.
	 * Whatever was half-recorded is dropped. The player's normal set is
	 * kept, since it is the thing a half-way reading would get wrong.
	 */
	public void reset()
	{
		discard();
		previous = null;
		cooling = false;
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
		}

		Sample prev = previous;
		previous = s;
		if (prev == null)
		{
			return;
		}

		long dx = Math.abs(s.fineX - prev.fineX);
		long dy = Math.abs(s.fineY - prev.fineY);
		long moved = Math.max(dx, dy);
		if (moved > MAX_HOP_TILES * 128L)
		{
			// A teleport. Nothing of this is copied, and nothing queued
			// either: the follower catches up the way it always has.
			discard();
			listener.clearCrossings();
			return;
		}

		boolean planeChanged = s.plane != prev.plane;
		boolean walkingPose = isWalkingPose(s.pose);
		boolean oddPose = s.pose != -1 && !contains(normal, s.pose);
		// A walking pose with a big step is a slow frame of ordinary steps.
		boolean hop = planeChanged || (moved > 256 && !walkingPose);
		boolean walking = !planeChanged && moved > 0 && walkingPose;
		boolean glide = !planeChanged && moved > 0 && moved <= 256 && !walkingPose;
		boolean odd = oddPose || s.action != -1 || glide;

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
		run.frames.add(s.frame());

		if (s.interacting)
		{
			// A knock-back in a fight, a scripted step in a conversation.
			discard();
			return;
		}

		run.glided += glide && run.lastGlided ? moved : 0;
		run.lastGlided = glide;
		if (hop)
		{
			run.hopMs = s.atMs;
		}

		if (!run.confirmed)
		{
			if (run.glided >= GLIDE_UNITS
				|| (run.hopMs >= 0 && (lastXpMs >= run.startMs || lastDamageMs >= run.startMs)))
			{
				confirm();
			}
			else if (run.hopMs >= 0)
			{
				// Possibly a climb: the follower holds where it is rather than
				// snapping after the player while the XP is awaited.
				if (!run.announced)
				{
					run.announced = true;
					listener.expectCrossing(run.start());
				}
				if (s.atMs - run.hopMs > CONFIRM_WAIT_MS)
				{
					// No XP and no fall: a ladder, a trapdoor, stairs.
					discard();
					return;
				}
			}
			else if (walking)
			{
				// Walked on: whatever played, it was not an obstacle.
				discard();
				return;
			}
		}

		if (odd || hop)
		{
			run.calmSinceMs = -1;
			run.calmWalked = false;
		}
		else if (run.calmSinceMs < 0)
		{
			run.calmSinceMs = s.atMs;
			run.calmIndex = run.frames.size() - 1;
			run.calmWalked = walking;
		}
		else
		{
			run.calmWalked |= walking;
		}

		if (s.atMs - run.startMs > MAX_CROSSING_MS || run.frames.size() > MAX_FRAMES)
		{
			discard();
			cooling = true;
			if (s.weapon == normalWeapon)
			{
				// Whatever this is, it is how they move now.
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
				discard();
			}
		}
	}

	private void confirm()
	{
		run.confirmed = true;
		if (!run.announced)
		{
			run.announced = true;
			listener.expectCrossing(run.start());
		}
	}

	private void finish()
	{
		List<Crossing.Frame> kept = run.frames.subList(0, run.calmIndex + 1);
		Crossing crossing = new Crossing(new ArrayList<>(kept), normal,
			lastDamageMs >= run.startMs);
		run = null;
		listener.queueCrossing(crossing);
	}

	private void discard()
	{
		Run dropped = run;
		run = null;
		if (dropped != null && dropped.announced)
		{
			listener.cancelExpectedCrossing();
		}
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
}
