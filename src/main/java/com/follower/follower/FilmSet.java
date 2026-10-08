package com.follower.follower;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * The film window's hold on the follower: the pose it is told to strike,
 * the way it is told to face, and a line over the player's own head.
 *
 * <p>Only what it put on itself is ever let go of, so the end of a scene
 * never takes off a pose or a face that something else owns. Client thread
 * only: every call reaches the follower or the player.
 *
 * <p>A pose is dressed as the game dresses it: whatever the animation names
 * for each hand - a book, a quill and a scroll, a chair - is put there for as
 * long as the pose is on show, and a hand it names nothing for keeps what the
 * follower holds. The model is rebuilt before the pose starts, since a rebuild
 * starts a clip again from its first frame. A held pose gives way to the walk
 * while the follower moves, for a Send or a Follow me, and its props come off
 * for the walk and go back on where it stops.
 */
@Slf4j
public class FilmSet
{
	/** One row of the window's pose list. */
	public static final class Pose
	{
		public final int id;
		public final String name;
		/** The window's group, such as Emotes or Skilling. */
		public final String group;
		/** Held until let go, rather than played once. */
		public final boolean hold;
		/** What it shows in each hand: {@link #KEEP}, {@link #EMPTY}, or an item id. */
		public final int left;
		public final int right;
		/** A graphic played once as it starts, or 0. */
		public final int graphic;
		/** A named by hand, B named from the game's own names, C only under Show all. */
		public final char tier;

		Pose(int id, String name, String group, boolean hold, int left, int right, int graphic, char tier)
		{
			this.id = id;
			this.name = name;
			this.group = group;
			this.hold = hold;
			this.left = left;
			this.right = right;
			this.graphic = graphic;
			this.tier = tier;
		}

		/** Whether it puts something in a hand that the game draws. */
		public boolean holdsItem()
		{
			return left >= 0 || right >= 0;
		}
	}

	/** A hand the animation leaves alone, as the client's getters give it. */
	public static final int KEEP = -1;

	/** A hand the animation empties. */
	public static final int EMPTY = -512;

	/**
	 * Every animation that moves the player's skeleton, which is every one the
	 * follower can strike, in the window's order. Built from the game cache by
	 * tools/cache-dumper (runPoseCatalog), which also checks each against it.
	 */
	public static final List<Pose> POSES = Collections.unmodifiableList(load());

	/** The groups in the order the list first shows them. */
	public static final List<String> GROUPS = Collections.unmodifiableList(groups(POSES));

	private static final Map<Integer, Pose> BY_ID = byId(POSES);

	/** The catalog's row for an animation, or null when it has none. */
	public static Pose pose(int animationId)
	{
		return BY_ID.get(animationId);
	}

	private static List<Pose> load()
	{
		try (InputStream in = FilmSet.class.getResourceAsStream("/com/follower/poses.tsv"))
		{
			if (in != null)
			{
				return read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read the posing list", e);
		}
		return new ArrayList<>();
	}

	/**
	 * The catalog's lines: id, name, group, hold or once, left, right,
	 * graphic and tier, tab separated, after a header and # comments.
	 */
	static List<Pose> read(BufferedReader reader) throws IOException
	{
		List<Pose> out = new ArrayList<>();
		for (String line = reader.readLine(); line != null; line = reader.readLine())
		{
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("id\t"))
			{
				continue;
			}
			String[] cells = line.split("\t");
			out.add(new Pose(Integer.parseInt(cells[0]), cells[1], cells[2], cells[3].equals("hold"),
				hand(cells[4]), hand(cells[5]), Integer.parseInt(cells[6]), cells[7].charAt(0)));
		}
		return out;
	}

	private static int hand(String cell)
	{
		switch (cell)
		{
			case "keep":
				return KEEP;
			case "empty":
				return EMPTY;
			default:
				return Integer.parseInt(cell);
		}
	}

	private static List<String> groups(List<Pose> poses)
	{
		Set<String> groups = new LinkedHashSet<>();
		for (Pose pose : poses)
		{
			groups.add(pose.group);
		}
		return new ArrayList<>(groups);
	}

	private static Map<Integer, Pose> byId(List<Pose> poses)
	{
		Map<Integer, Pose> out = new HashMap<>();
		for (Pose pose : poses)
		{
			out.put(pose.id, pose);
		}
		return out;
	}

	/**
	 * How far off the tile it faces lies. Far enough that a Send of a few
	 * tiles hardly moves the angle before the facing is aimed again.
	 */
	static final int FACE_TILES = 64;

	private final FollowerEntity follower;

	/** Builds the follower's model again, dressed as {@link #hands()} says. */
	private final Runnable redress;

	/** Shows a graphic on the follower, by spotanim id. */
	private final IntConsumer graphic;

	/** The pose the film put on, or 0. */
	private int held;

	/** What the held pose shows in each hand, or null when it would not load. */
	private int[] heldHands;

	/** The pose being played once, until it ends, or 0. */
	private int playing;

	/** What the pose being played once shows in each hand. */
	private int[] playingHands;

	/** The last pose played once, for Again; 0 before the first. */
	private int lastOnce;

	/** Whether the follower was on the move at the last frame, so the held pose was not on show. */
	private boolean walking;

	/** The orientation the film turned it to, or -1 while it faces the player. */
	private int facing = -1;

	/** The player's last animation, for Copy my emote; -1 before the first. */
	private int lastPlayerAnimation = -1;

	/** A film that dresses nothing and shows no graphics, for a follower with no model to build. */
	public FilmSet(FollowerEntity follower)
	{
		this(follower, () ->
		{
		}, id ->
		{
		});
	}

	/**
	 * @param redress builds the follower's model again, dressed as
	 *                {@link #hands()} says
	 * @param graphic shows a graphic on the follower, by spotanim id
	 */
	public FilmSet(FollowerEntity follower, Runnable redress, IntConsumer graphic)
	{
		this.follower = follower;
		this.redress = redress;
		this.graphic = graphic;
	}

	/** Holds a looping pose until let go, over any one-shot still playing. */
	public void hold(int animationId)
	{
		held = animationId;
		heldHands = follower.handItems(animationId);
		playing = 0;
		playingHands = null;
		redress.run();
		follower.cancelEmote();
		follower.setPoseOverride(animationId);
		graphicFor(animationId);
	}

	/** Plays a pose once; a held pose comes back when it ends. */
	public void once(int animationId)
	{
		lastOnce = animationId;
		int[] hands = follower.handItems(animationId);
		if (hands == null)
		{
			// Nothing will play, so nothing is put in its hands either.
			return;
		}
		playing = animationId;
		playingHands = hands;
		redress.run();
		follower.playAnimation(animationId);
		if (!follower.isEmotePlaying())
		{
			// It never started - no model to play it on - so it would never
			// end and hand the props back either.
			onceOver();
			return;
		}
		graphicFor(animationId);
	}

	/** @return false when nothing has been played once yet */
	public boolean again()
	{
		if (lastOnce == 0)
		{
			return false;
		}
		once(lastOnce);
		return true;
	}

	/**
	 * The follower's one-shot has ended, or been cut short by a walk or a
	 * hold: the held pose's props come back, or its own gear.
	 */
	public void onceOver()
	{
		if (playing != 0)
		{
			playing = 0;
			playingHands = null;
			redress.run();
		}
	}

	/**
	 * Lets go of the film's held pose and stops its one-shot, and of nothing
	 * else. A death or a knockdown lies still on its last frame for a minute
	 * or more, and this is how it gets up.
	 */
	public void standNormally()
	{
		boolean posing = isPosing();
		if (held != 0)
		{
			follower.clearPoseOverride(held);
			held = 0;
			heldHands = null;
		}
		if (playing != 0)
		{
			playing = 0;
			playingHands = null;
			follower.cancelEmote();
		}
		if (posing)
		{
			redress.run();
		}
	}

	/**
	 * Whether a pose of the film's is on. It dresses the hands itself, so the
	 * emote disarm stands down for it.
	 */
	public boolean isPosing()
	{
		return held != 0 || playing != 0;
	}

	/**
	 * What the film's pose on show puts in each hand, left then right, each
	 * {@link #KEEP}, {@link #EMPTY} or an item id; null when no pose of the
	 * film's is on show - none is on, or the held one has given way to a walk -
	 * or its animation would not load.
	 */
	public int[] hands()
	{
		if (playing != 0)
		{
			return playingHands;
		}
		return held != 0 && !walking ? heldHands : null;
	}

	/**
	 * Each frame, after the follower's own. The follower shows a held pose
	 * only while it stands, so the pose's props come off when it sets off, or
	 * it would walk to a Send with the chair in its hand and the chair's
	 * stance, and go back on when it stops and strikes the pose again. A
	 * one-shot needs nothing here: a walk cuts it short.
	 */
	public void frame()
	{
		boolean moving = follower.isMoving();
		if (moving == walking)
		{
			return;
		}
		walking = moving;
		if (held != 0 && playing == 0)
		{
			redress.run();
		}
	}

	/** The pose's paired graphic, played once as it starts. */
	private void graphicFor(int animationId)
	{
		Pose pose = pose(animationId);
		if (pose != null && pose.graphic > 0)
		{
			graphic.accept(pose.graphic);
		}
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
