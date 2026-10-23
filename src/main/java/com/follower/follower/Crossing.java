// public:file agility,agility-all
package com.follower.follower;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * One agility obstacle or shortcut as the player took it: where they were,
 * which way they faced and what they were doing, frame by frame, from the
 * first odd step to the moment they were walking normally again.
 *
 * <p>Positions are fine WORLD units (tile x 128, the centre of a tile at 64),
 * the same space the follower's own fine position lives in, so a recording
 * made before a scene rebuild plays back the same after it. Times are
 * milliseconds from the first frame.
 *
 * <p>Nothing here knows which obstacle it was. That is the point: the
 * follower plays back what it saw, not what a table says the obstacle does.
 *
 * <p>A recording is handed over while it is still being made, so the
 * follower can set off a couple of ticks after the player rather than
 * waiting for them to finish: {@link CrossingWatch} appends a frame at a
 * time, then {@link #end ends} it, or {@link #cut cuts} it short. The watch
 * writes and the follower reads on the client thread alone (BeforeRender,
 * StatChanged, HitsplatApplied and GameTick are all posted there), so there
 * is no lock: frames are only ever appended, and the one trim, at the end,
 * removes frames the reader clamps itself away from.
 */
public final class Crossing
{
	/** One sample of the player. */
	public static final class Frame
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

		public Frame(long atMs, long fineX, long fineY, int plane, int orientation,
			int pose, int poseFrame, int action, int actionFrame)
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
		}

		public WorldPoint tile()
		{
			return new WorldPoint((int) (fineX >> 7), (int) (fineY >> 7), plane);
		}

		public int getPose()
		{
			return pose;
		}

		public int getAction()
		{
			return action;
		}

		public int getPlane()
		{
			return plane;
		}

		public int getOrientation()
		{
			return orientation;
		}
	}

	private final List<Frame> frames;
	private final List<Frame> readOnly;
	/** The recording clock's time at the first frame, which every frame is rebased against. */
	private final long zero;
	private boolean fell;
	/** No more frames are coming: ended, cut or dropped. */
	private boolean complete;
	/** Let go as not an obstacle at all: skipped if the follower has not begun it. */
	private boolean dropped;

	/**
	 * The player's own stand, walk, run and turns while they were not
	 * crossing anything, in {@link StanceLibrary.Stance} order: idle, walk,
	 * run, back, left, right, turn left, turn right. A frame showing one of
	 * these is played with the follower's own version of it, so it stands
	 * and walks like itself between the odd parts.
	 */
	private final int[] normal;

	/** A finished recording. */
	public Crossing(List<Frame> frames, int[] normal, boolean fell)
	{
		this(frames, normal);
		this.fell = fell;
		complete = true;
	}

	private Crossing(List<Frame> frames, int[] normal)
	{
		if (frames == null || frames.isEmpty())
		{
			throw new IllegalArgumentException("a crossing has at least one frame");
		}
		// Rebased so the first frame is time zero whatever clock recorded it.
		zero = frames.get(0).atMs;
		this.frames = new ArrayList<>(frames.size());
		for (Frame f : frames)
		{
			append(f);
		}
		readOnly = Collections.unmodifiableList(this.frames);
		this.normal = normal == null ? new int[0] : normal.clone();
	}

	/** A recording still being made: the watch appends to it and then ends it. */
	static Crossing live(List<Frame> frames, int[] normal)
	{
		return new Crossing(frames, normal);
	}

	/** The next frame, on the recording clock. */
	void append(Frame f)
	{
		frames.add(new Frame(f.atMs - zero, f.fineX, f.fineY, f.plane, f.orientation,
			f.pose, f.poseFrame, f.action, f.actionFrame));
	}

	/** Over: only the first {@code keep} frames were the crossing, the rest the walk away. */
	void end(int keep, boolean fell)
	{
		while (frames.size() > Math.max(1, keep))
		{
			frames.remove(frames.size() - 1);
		}
		this.fell = fell;
		complete = true;
	}

	/** Ended early, where it got to: played that far and no further. */
	void cut()
	{
		complete = true;
	}

	/** Not an obstacle after all: what has been played ends, and an unbegun one is skipped. */
	void drop()
	{
		complete = true;
		dropped = true;
	}

	public boolean isComplete()
	{
		return complete;
	}

	public boolean isDropped()
	{
		return dropped;
	}

	/** When it began on the recording clock, which orders crossings made at different times. */
	public long getStartedAtMs()
	{
		return zero;
	}

	public List<Frame> getFrames()
	{
		return readOnly;
	}

	/** Where the player stood as it began: the tile the follower walks to first. */
	public WorldPoint getStart()
	{
		return frames.get(0).tile();
	}

	/** Where they stood when it was over, fall or no fall. */
	public WorldPoint getEnd()
	{
		return frames.get(frames.size() - 1).tile();
	}

	/** As long as it is so far: the whole of it once {@link #isComplete complete}. */
	public long getDurationMs()
	{
		return frames.get(frames.size() - 1).atMs;
	}

	/** Whether they were hurt on the way: the fall is copied along with the rest. */
	public boolean isFell()
	{
		return fell;
	}

	/** Which of the normal poses this is (an index into the stance order), or -1 for an odd one. */
	int normalIndex(int pose)
	{
		for (int i = 0; i < normal.length; i++)
		{
			if (normal[i] == pose)
			{
				return i;
			}
		}
		return -1;
	}

	/** The frame showing at {@code t} ms in: the last one at or before it. */
	public Frame frameAt(long t)
	{
		return frames.get(indexAt(t));
	}

	int indexAt(long t)
	{
		int lo = 0;
		int hi = frames.size() - 1;
		while (lo < hi)
		{
			int mid = (lo + hi + 1) >>> 1;
			if (frames.get(mid).atMs <= t)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		return lo;
	}

	/**
	 * The fine position at {@code t} ms in, {x, y}: between two frames on the
	 * same plane and within two tiles of each other it glides, as the player
	 * did between samples; across a plane change or a bigger hop it holds the
	 * earlier frame and moves on the later one, as they did.
	 */
	public long[] fineAt(long t)
	{
		int i = indexAt(t);
		Frame a = frames.get(i);
		if (i + 1 >= frames.size() || t <= a.atMs)
		{
			return new long[]{a.fineX, a.fineY};
		}
		Frame b = frames.get(i + 1);
		if (b.plane != a.plane || Math.abs(b.fineX - a.fineX) > 256 || Math.abs(b.fineY - a.fineY) > 256
			|| b.atMs <= a.atMs)
		{
			return new long[]{a.fineX, a.fineY};
		}
		double k = (t - a.atMs) / (double) (b.atMs - a.atMs);
		return new long[]{
			a.fineX + Math.round((b.fineX - a.fineX) * k),
			a.fineY + Math.round((b.fineY - a.fineY) * k)};
	}
}
