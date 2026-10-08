package com.follower.follower;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * The film window's hold on the follower: the pose it is told to strike,
 * the way it is told to face, and a line over the player's own head.
 *
 * <p>Only what it put on itself is ever let go of, so the end of a scene
 * never takes off a pose or a face that something else owns. Client thread
 * only: every call reaches the follower or the player.
 */
public class FilmSet
{
	/** One row of the window's pose list. */
	public static final class Pose
	{
		public final String name;
		public final int id;
		/** Held until let go, rather than played once. */
		public final boolean hold;

		Pose(String name, int id, boolean hold)
		{
			this.name = name;
			this.id = id;
			this.hold = hold;
		}
	}

	/**
	 * The poses on offer, all the game's own emotes. The holds are the loops
	 * the mimic-emote-loops rule already plays on the follower, so each is
	 * known to sit on its skeleton. No entry clip is paired with a loop: the
	 * sit's loop sits down by itself, and the clip the sit emote is named
	 * after is in fact its stand-up.
	 */
	public static final List<Pose> POSES = Collections.unmodifiableList(Arrays.asList(
		new Pose("Sit", 10061, true),
		new Pose("Lean", 10062, true),
		new Pose("Read a scroll", 5354, true),
		new Pose("Read a book", 1350, true),
		new Pose("Peer", 13993, true),
		new Pose("Pray", 179, true),
		new Pose("Dance", 10048, true),
		new Pose("Clap", 3193, true),
		new Pose("Beckon", 2691, true),
		new Pose("Blow kiss", 3803, true),
		new Pose("Panic", 12050, true),
		new Pose("Yes", 189, true),
		new Pose("No", 190, true),
		new Pose("Think", 191, true),
		new Pose("Bow", 192, true),
		new Pose("Angry", 193, true),
		new Pose("Cry", 194, true),
		new Pose("Laugh", 195, true),
		new Pose("Cheer", 196, true),
		new Pose("Wave", 197, true),
		new Pose("Yes", 855, false),
		new Pose("No", 856, false),
		new Pose("Think", 857, false),
		new Pose("Bow", 858, false),
		new Pose("Angry", 859, false),
		new Pose("Cry", 860, false),
		new Pose("Laugh", 861, false),
		new Pose("Cheer", 862, false),
		new Pose("Wave", 863, false),
		new Pose("Beckon", 864, false),
		new Pose("Clap", 865, false),
		new Pose("Dance", 866, false),
		new Pose("Blow kiss", 1374, false),
		new Pose("Panic", 2105, false),
		new Pose("Jig", 2106, false),
		new Pose("Spin", 2107, false),
		new Pose("Headbang", 2108, false),
		new Pose("Jump for joy", 2109, false),
		new Pose("Raspberry", 2110, false),
		new Pose("Yawn", 2111, false),
		new Pose("Salute", 2112, false),
		new Pose("Shrug", 2113, false),
		new Pose("Slap head", 4275, false),
		new Pose("Idea", 4276, false),
		new Pose("Stamp", 4278, false),
		new Pose("Air guitar", 4751, false),
		new Pose("Flex", 8917, false)));

	/**
	 * How far off the tile it faces lies. Far enough that a Send of a few
	 * tiles hardly moves the angle before the facing is aimed again.
	 */
	static final int FACE_TILES = 64;

	private final FollowerEntity follower;

	/** The pose the film put on, or 0. */
	private int held;

	/** The last pose played once, for Again; 0 before the first. */
	private int lastOnce;

	/** The orientation the film turned it to, or -1 while it faces the player. */
	private int facing = -1;

	/** The player's last animation, for Copy my emote; -1 before the first. */
	private int lastPlayerAnimation = -1;

	public FilmSet(FollowerEntity follower)
	{
		this.follower = follower;
	}

	/** Holds a looping pose until let go, over any one-shot still playing. */
	public void hold(int animationId)
	{
		follower.cancelEmote();
		follower.setPoseOverride(animationId);
		held = animationId;
	}

	/** Plays a pose once; a held pose comes back when it ends. */
	public void once(int animationId)
	{
		follower.playAnimation(animationId);
		lastOnce = animationId;
	}

	/** @return false when nothing has been played once yet */
	public boolean again()
	{
		if (lastOnce == 0)
		{
			return false;
		}
		follower.playAnimation(lastOnce);
		return true;
	}

	/** Lets go of the film's held pose, and of nothing else. */
	public void standNormally()
	{
		if (held != 0)
		{
			follower.clearPoseOverride(held);
			held = 0;
		}
	}

	/** Whether a pose of the film's is held: the weapon goes away for it, as for a mirrored loop. */
	public boolean isHolding()
	{
		return held != 0;
	}

	/**
	 * Turns it to an actor orientation - 0 south, 512 west, 1024 north, 1536
	 * east - and keeps it there. A stay faces a tile, so this is a far tile
	 * that way. Gaze.lookTile adds a half turn to what it is given, so the
	 * half turn is taken off first.
	 */
	public void face(int orientation)
	{
		facing = orientation & 0x7ff;
		aim();
	}

	/**
	 * The orientation that faces the viewer. The camera's yaw is in 16384ths
	 * of a turn, not an orientation's 2048ths, and it turns the other way:
	 * 0 looks north, a quarter turn looks west. Looking north the viewer is
	 * to the south, which is orientation 0; looking west they are to the
	 * east, which is 1536, a quarter turn back the other way.
	 */
	public static int cameraFacing(int cameraYaw)
	{
		return (-(cameraYaw >> 3)) & 0x7ff;
	}

	/** Faces the player again, as any stay does. */
	public void faceMe()
	{
		facing = -1;
		follower.setStayFaceTile(null);
	}

	/** Aims the facing again from where the stay now holds it, after a Send has moved it. */
	public void reaim()
	{
		if (facing >= 0)
		{
			aim();
		}
	}

	private void aim()
	{
		WorldPoint from = follower.getStayTile() != null ? follower.getStayTile() : follower.getWorldLocation();
		if (from != null)
		{
			follower.setStayFaceTile(Gaze.lookTile(from, (facing - 1024) & 0x7ff, FACE_TILES));
		}
	}

	/**
	 * A line over the player's own head, where their own chat shows. Set on
	 * this client only: nothing is sent, nothing reaches the chatbox, and
	 * nobody else sees it.
	 */
	public void playerSays(Player player, String text, long ms)
	{
		player.setOverheadText(text);
		player.setOverheadCycle(overheadCycles(ms));
	}

	/**
	 * Milliseconds as the overhead countdown, taken as client cycles of 20 ms:
	 * the game's own chat stays up 150 of them, three seconds. The API does
	 * not say what the unit is, so the guess lives here and nowhere else.
	 */
	static int overheadCycles(long ms)
	{
		return (int) (ms / 20);
	}

	/** Every animation the player plays, so Copy my emote has the last one. */
	public void notePlayerAnimation(int animationId)
	{
		if (animationId != -1)
		{
			lastPlayerAnimation = animationId;
		}
	}

	/** The player's last animation, or -1 when none has been seen. */
	public int getLastPlayerAnimation()
	{
		return lastPlayerAnimation;
	}

	/** The scene is over: the film's pose and facing are let go of, and only those. */
	public void letGo()
	{
		standNormally();
		if (facing >= 0)
		{
			faceMe();
		}
	}
}
