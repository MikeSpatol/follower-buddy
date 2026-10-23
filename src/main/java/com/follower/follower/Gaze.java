// public:file company-extras,gaze
package com.follower.follower;

import com.follower.FollowerConfig;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * Where the follower looks when nothing else owns its head.
 *
 * <p>Joint attention is the cheapest sign of company there is, and the one
 * with the best evidence behind it: a robot that followed people's gaze was
 * rated more likeable and preferred three times out of four, without anybody
 * being able to say why. OSRS has no gaze to follow, but it has two things
 * that stand in for it - where the cursor rests, and where the camera is
 * turned - and the follower turns its head to both. Gaze only: the feet stay
 * with whatever owns them, and a real facing (a seat, a goal, an errand at
 * its business) outranks a glance, so none of this applies while the body is
 * spoken for.
 *
 * <p>The third source is the follower's own: standing about, it looks round
 * at what is nearby every so often and then back at the player, which is what
 * a companion that is present rather than parked looks like. How it looks
 * round depends on where it is. Somewhere it likes, the looks are slow and
 * long; somewhere it dislikes, quick and often and always at the nearest
 * thing that moves.
 *
 * <p>Nothing here is memoised as a refusal: a glance that cannot be aimed is
 * simply skipped, and nothing speaks. All three sources pace themselves, so
 * the head does not flick.
 */
@Slf4j
public class Gaze
{
	/** Ticks the cursor rests on a thing before the head turns to it. */
	static final int HOVER_TICKS = 2;
	static final int HOVER_GLANCE_MS = 1800;
	static final int HOVER_COOLDOWN_TICKS = 5;

	/** A camera swing this big, then two still ticks, is a look worth sharing. */
	static final int CAMERA_SWING = 200;
	static final int CAMERA_JITTER = 12;
	static final int CAMERA_SETTLE_TICKS = 2;
	static final int CAMERA_GLANCE_MS = 1500;
	static final int CAMERA_COOLDOWN_TICKS = 15;
	static final int CAMERA_LOOK_TILES = 4;

	/** The look round: after this much standing about, this often, for this long. */
	static final int IDLE_BEFORE_AROUND_TICKS = 10;
	static final int AROUND_MIN_TICKS = 33;
	static final int AROUND_MAX_TICKS = 66;
	static final int AROUND_MS = 1500;
	/** Somewhere it likes: slower, longer, taking it in. */
	static final int LIKED_MIN_TICKS = 50;
	static final int LIKED_MAX_TICKS = 83;
	static final int LIKED_MS = 3000;
	/** Somewhere it dislikes: quick, often, at whatever is nearest. */
	static final int DISLIKED_MIN_TICKS = 13;
	static final int DISLIKED_MAX_TICKS = 25;
	static final int DISLIKED_MS = 900;
	static final int AROUND_NEAR = 3;
	public static final int AROUND_FAR = 6;

	/** What the cursor is resting on: an actor, or a tile with something on it. */
	public static final class Hover
	{
		final String key;
		final Actor actor;
		final WorldPoint tile;

		public Hover(String key, Actor actor, WorldPoint tile)
		{
			this.key = key;
			this.actor = actor;
			this.tile = tile;
		}

		WorldPoint where()
		{
			return actor != null ? actor.getWorldLocation() : tile;
		}
	}

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final IntSupplier cameraYaw;
	private final IntSupplier idleTicks;
	/** Something else has the head or the feet: nothing here applies. */
	private final BooleanSupplier busy;
	/** "liked", "disliked" or "" for where the follower is standing. */
	private final Supplier<String> placeFeeling;
	/** Tiles of the things nearby worth a look, nearest first. */
	private final Supplier<List<WorldPoint>> nearby;
	private final Random random;

	private Hover hoverNow;
	private String hoverKey;
	private int hoverTicks;
	private String glancedKey;
	private int hoverCooldownUntil;

	private int lastYaw = Integer.MIN_VALUE;
	private int settledYaw = Integer.MIN_VALUE;
	private int stillTicks;
	private boolean swung;
	private int cameraCooldownUntil;

	private int aroundIn = -1;

	public Gaze(Client client, FollowerEntity follower, FollowerConfig config,
		IntSupplier cameraYaw, IntSupplier idleTicks, BooleanSupplier busy,
		Supplier<String> placeFeeling, Supplier<List<WorldPoint>> nearby, Random random)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.cameraYaw = cameraYaw;
		this.idleTicks = idleTicks;
		this.busy = busy;
		this.placeFeeling = placeFeeling;
		this.nearby = nearby;
		this.random = random;
	}

	// ------------------------------------------------------------- the cursor

	/**
	 * What the cursor is resting on right now, read off the menu the client
	 * would open: the top entry is what a left click would do, and its
	 * target is the thing under the mouse. Nothing for bare ground, for
	 * interfaces, for the player themselves or for the follower's own
	 * injected entries.
	 */
	public static Hover read(Client client, FollowerEntity follower)
	{
		if (client.isMenuOpen())
		{
			return null;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return null;
		}
		MenuEntry top = entries[entries.length - 1];
		MenuAction type = top.getType();
		if (type == null || type == MenuAction.WALK || type == MenuAction.CANCEL
			|| type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_HIGH_PRIORITY
			|| type == MenuAction.RUNELITE_WIDGET)
		{
			return null;
		}
		NPC npc = top.getNpc();
		if (npc != null)
		{
			return new Hover("npc:" + npc.getIndex(), npc, null);
		}
		Player player = top.getPlayer();
		if (player != null)
		{
			return player == client.getLocalPlayer() ? null
				: new Hover("player:" + player.getName(), player, null);
		}
		String name = type.name();
		if (name.startsWith("GAME_OBJECT") || name.startsWith("GROUND_ITEM")
			|| type == MenuAction.EXAMINE_OBJECT || type == MenuAction.EXAMINE_ITEM_GROUND
			|| type == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
			|| type == MenuAction.WIDGET_TARGET_ON_GROUND_ITEM)
		{
			net.runelite.api.WorldView view = client.getTopLevelWorldView();
			if (view == null)
			{
				return null;
			}
			WorldPoint tile = WorldPoint.fromScene(view, top.getParam0(), top.getParam1(),
				view.getPlane());
			return new Hover("tile:" + tile.getX() + "," + tile.getY() + ":" + top.getIdentifier(),
				null, tile);
		}
		return null;
	}

	/** Once per client tick: what the cursor is on, or null. */
	public void noteHover(Hover hover)
	{
		hoverNow = hover;
	}

	// --------------------------------------------------------------- the tick

	/** Call once per game tick, on the client thread, after the wander. */
	public void tick()
	{
		int now = client.getTickCount();
		boolean allowed = config.paysAttention() && follower.isSpawned() && !busy.getAsBoolean();
		WorldPoint from = follower.getWorldLocation();
		if (!allowed || from == null)
		{
			hoverKey = null;
			hoverTicks = 0;
			aroundIn = -1;
			lastYaw = Integer.MIN_VALUE;
			return;
		}
		if (watchTheCursor(now, from) || watchTheCamera(now, from))
		{
			// A shared look pushes the look round back, so the two never stack.
			aroundIn = -1;
			return;
		}
		lookRound(now, from);
	}

	private boolean watchTheCursor(int now, WorldPoint from)
	{
		Hover hover = hoverNow;
		if (hover == null)
		{
			hoverKey = null;
			hoverTicks = 0;
			glancedKey = null;
			return false;
		}
		if (hover.key.equals(hoverKey))
		{
			hoverTicks++;
		}
		else
		{
			hoverKey = hover.key;
			hoverTicks = 1;
		}
		if (hoverTicks != HOVER_TICKS || hover.key.equals(glancedKey) || now < hoverCooldownUntil)
		{
			return false;
		}
		WorldPoint at = hover.where();
		if (at == null || at.getPlane() != from.getPlane() || at.equals(from))
		{
			return false;
		}
		glancedKey = hover.key;
		hoverCooldownUntil = now + HOVER_COOLDOWN_TICKS;
		follower.glanceAt(at, HOVER_GLANCE_MS);
		log.debug("Cursor on {}: a look", hover.key); // public:cut logs
		return true;
	}

	private boolean watchTheCamera(int now, WorldPoint from)
	{
		int yaw = cameraYaw.getAsInt() & 0x7ff;
		if (lastYaw == Integer.MIN_VALUE)
		{
			lastYaw = yaw;
			settledYaw = yaw;
			return false;
		}
		boolean moving = angle(yaw, lastYaw) > CAMERA_JITTER;
		lastYaw = yaw;
		if (moving)
		{
			stillTicks = 0;
			if (angle(yaw, settledYaw) > CAMERA_SWING)
			{
				swung = true;
			}
			return false;
		}
		if (++stillTicks < CAMERA_SETTLE_TICKS)
		{
			return false;
		}
		boolean look = swung && now >= cameraCooldownUntil && idleTicks.getAsInt() > 0;
		swung = false;
		settledYaw = yaw;
		if (!look)
		{
			return false;
		}
		cameraCooldownUntil = now + CAMERA_COOLDOWN_TICKS;
		follower.glanceAt(lookTile(from, yaw, CAMERA_LOOK_TILES), CAMERA_GLANCE_MS);
		log.debug("Camera settled at {}: a look that way", yaw); // public:cut logs
		return true;
	}

	/**
	 * A tile so many steps from {@code from} in the direction the camera is
	 * looking. Camera yaw 0 looks north; an actor's orientation 0 faces
	 * south, so the two differ by a half turn and share the rest.
	 */
	static WorldPoint lookTile(WorldPoint from, int cameraYaw, int tiles)
	{
		int orientation = (cameraYaw + 1024) & 0x7ff;
		double radians = (orientation / 2048.0) * 2.0 * Math.PI;
		int dx = (int) Math.round(-Math.sin(radians) * tiles);
		int dy = (int) Math.round(-Math.cos(radians) * tiles);
		return from.dx(dx).dy(dy);
	}

	/** The smaller angle between two yaws, in JAU. */
	static int angle(int a, int b)
	{
		int d = (a - b) & 0x7ff;
		return d > 1024 ? 2048 - d : d;
	}

	private void lookRound(int now, WorldPoint from)
	{
		if (idleTicks.getAsInt() < IDLE_BEFORE_AROUND_TICKS)
		{
			aroundIn = -1;
			return;
		}
		String feeling = placeFeeling.get();
		boolean disliked = "disliked".equals(feeling);
		boolean liked = "liked".equals(feeling);
		if (aroundIn < 0)
		{
			aroundIn = disliked ? between(DISLIKED_MIN_TICKS, DISLIKED_MAX_TICKS)
				: liked ? between(LIKED_MIN_TICKS, LIKED_MAX_TICKS)
				: between(AROUND_MIN_TICKS, AROUND_MAX_TICKS);
			return;
		}
		if (--aroundIn > 0)
		{
			return;
		}
		aroundIn = -1;
		lookRoundNow(from, feeling);
	}

	/** The look round itself, at something nearby or nothing in particular: where, or null. */
	private WorldPoint lookRoundNow(WorldPoint from, String feeling)
	{
		boolean disliked = "disliked".equals(feeling);
		boolean liked = "liked".equals(feeling);
		List<WorldPoint> things = nearby.get();
		WorldPoint at = null;
		if (things != null && !things.isEmpty())
		{
			// Somewhere it dislikes, the nearest thing that moves; anywhere
			// else, any of them.
			at = disliked ? things.get(0) : things.get(random.nextInt(things.size()));
		}
		if (at == null)
		{
			double heading = random.nextDouble() * Math.PI * 2;
			int distance = between(AROUND_NEAR, AROUND_FAR);
			at = from.dx((int) Math.round(Math.cos(heading) * distance))
				.dy((int) Math.round(Math.sin(heading) * distance));
		}
		if (at.equals(from))
		{
			return null;
		}
		follower.glanceAt(at, disliked ? DISLIKED_MS : liked ? LIKED_MS : AROUND_MS);
		log.debug("A look round at {} ({})", at, feeling.isEmpty() ? "nothing felt" : feeling); // public:cut logs
		return at;
	}

	// public:cut dev
	/**
	 * ::follower force look: a look now, without the standing about or the
	 * wait between looks. The look round by default, as the tick makes it;
	 * "camera" looks the way the camera is turned and "cursor" at what the
	 * mouse rests on, as a settled swing or a rest would. The gates stay:
	 * Looks where you look off, or anything else with the head or the feet.
	 *
	 * @param how null for the look round, or "camera" or "cursor"
	 * @return what it did, or why not, for the chat line
	 */
	public String forceLook(String how)
	{
		if (how != null && !"camera".equals(how) && !"cursor".equals(how))
		{
			return "Look how? camera, cursor, or nothing for the look round.";
		}
		if (!config.paysAttention())
		{
			return "Looks where you look is off in the settings.";
		}
		WorldPoint from = follower.getWorldLocation();
		if (!follower.isSpawned() || from == null || busy.getAsBoolean())
		{
			return "The follower is busy.";
		}
		if ("camera".equals(how))
		{
			follower.glanceAt(lookTile(from, cameraYaw.getAsInt() & 0x7ff, CAMERA_LOOK_TILES),
				CAMERA_GLANCE_MS);
			return "A look the way the camera is turned.";
		}
		if ("cursor".equals(how))
		{
			WorldPoint at = hoverNow == null ? null : hoverNow.where();
			if (at == null || at.getPlane() != from.getPlane() || at.equals(from))
			{
				return "The cursor is not resting on anything to look at.";
			}
			follower.glanceAt(at, HOVER_GLANCE_MS);
			return "A look at what the cursor is on.";
		}
		aroundIn = -1;
		WorldPoint at = lookRoundNow(from, placeFeeling.get());
		return at == null ? "Nowhere to look round at."
			: "A look round at " + at.getX() + ", " + at.getY() + ".";
	}

	// public:end dev
	private int between(int min, int max)
	{
		return min + random.nextInt(Math.max(1, max - min + 1));
	}

	/** Logout, hop: nothing carried over. */
	public void reset()
	{
		hoverNow = null;
		hoverKey = null;
		hoverTicks = 0;
		glancedKey = null;
		lastYaw = Integer.MIN_VALUE;
		settledYaw = Integer.MIN_VALUE;
		stillTicks = 0;
		swung = false;
		aroundIn = -1;
	}
}
