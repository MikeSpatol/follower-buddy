package com.follower;

import java.util.*;
import java.util.List;
import java.util.Random;
import java.util.function.*;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import lombok.RequiredArgsConstructor;
import static java.lang.Math.*;

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
@RequiredArgsConstructor
class Gaze
{
	static final int HOVER_GLANCE_MS = 1800,
		HOVER_COOLDOWN_TICKS = 5;

	static final int CAMERA_SETTLE_TICKS = 2,
		CAMERA_COOLDOWN_TICKS = 15,
		CAMERA_LOOK_TILES = 4;

	/** The look round: after this much standing about, this often, for this long. */
	static final int IDLE_BEFORE_AROUND_TICKS = 10,
		AROUND_MIN_TICKS = 33,
		AROUND_MAX_TICKS = 66,
		AROUND_MS = 1500,
		AROUND_NEAR = 3,
		AROUND_FAR = 6;

	/** What the cursor is resting on: an actor, or a tile with something on it. */
	@RequiredArgsConstructor
	static class Hover
	{
		final String key;
		final Actor actor;
		final WorldPoint tile;

		WorldPoint where()
		{
			return actor != null ? actor.getWorldLocation() : tile;
		}
	}

	final Client client;
	final FollowerEntity follower;
	final FollowerConfig config;
	final IntSupplier cameraYaw,
		idleTicks;
	/** Something else has the head or the feet: nothing here applies. */
	final BooleanSupplier busy;
	/** Tiles of the things nearby worth a look, nearest first. */
	final Supplier<List<WorldPoint>> nearby;
	final Random random;

	Hover hoverNow;
	String hoverKey;
	int hoverTicks;
	String glancedKey;
	int hoverCooldownUntil;

	int lastYaw = Integer.MIN_VALUE,
		settledYaw = Integer.MIN_VALUE,
		stillTicks;
	boolean swung;
	int cameraCooldownUntil;

	int aroundIn = -1;

	// ------------------------------------------------------------- the cursor

	/**
	 * What the cursor is resting on right now, read off the menu the client
	 * would open: the top entry is what a left click would do, and its
	 * target is the thing under the mouse. Nothing for bare ground, for
	 * interfaces, for the player themselves or for the follower's own
	 * injected entries.
	 */
	static Hover read(Client client, FollowerEntity follower)
	{
		if (client.isMenuOpen())
			return null;
		var entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
			return null;
		var top = entries[entries.length - 1];
		var type = top.getType();
		if (type == null || type == MenuAction.WALK || type == MenuAction.CANCEL
			|| type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_HIGH_PRIORITY
			|| type == MenuAction.RUNELITE_WIDGET)
			return null;
		var npc = top.getNpc();
		if (npc != null)
			return new Hover("npc:" + npc.getIndex(), npc, null);
		var player = top.getPlayer();
		if (player != null)
			return player == client.getLocalPlayer() ? null
				: new Hover("player:" + player.getName(), player, null);
		String name = type.name();
		if (name.startsWith("GAME_OBJECT") || name.startsWith("GROUND_ITEM")
			|| type == MenuAction.EXAMINE_OBJECT || type == MenuAction.EXAMINE_ITEM_GROUND
			|| type == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
			|| type == MenuAction.WIDGET_TARGET_ON_GROUND_ITEM)
		{
			var view = client.getTopLevelWorldView();
			if (view == null)
				return null;
			var tile = WorldPoint.fromScene(view, top.getParam0(), top.getParam1(),
				view.getPlane());
			return new Hover("tile:" + tile.getX() + "," + tile.getY() + ":" + top.getIdentifier(),
				null, tile);
		}
		return null;
	}

	/** Once per client tick: what the cursor is on, or null. */
	void noteHover(Hover hover)
	{
		hoverNow = hover;
	}

	// --------------------------------------------------------------- the tick

	/** Call once per game tick, on the client thread, after the wander. */
	void tick()
	{
		int now = client.getTickCount();
		boolean allowed = config.paysAttention() && follower.isSpawned() && !busy.getAsBoolean();
		var from = follower.getWorldLocation();
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

	boolean watchTheCursor(int now, WorldPoint from)
	{
		var hover = hoverNow;
		if (hover == null)
		{
			hoverKey = null;
			hoverTicks = 0;
			glancedKey = null;
			return false;
		}
		if (hover.key.equals(hoverKey))
			hoverTicks++;
		else
		{
			hoverKey = hover.key;
			hoverTicks = 1;
		}
		// HOVER_TICKS: Ticks the cursor rests on a thing before the head turns to it.
		if (hoverTicks != 2 /* HOVER_TICKS */ || hover.key.equals(glancedKey) || now < hoverCooldownUntil)
			return false;
		var at = hover.where();
		if (at == null || at.getPlane() != from.getPlane() || at.equals(from))
			return false;
		glancedKey = hover.key;
		hoverCooldownUntil = now + HOVER_COOLDOWN_TICKS;
		follower.glanceAt(at, HOVER_GLANCE_MS);
		return true;
	}

	boolean watchTheCamera(int now, WorldPoint from)
	{
		int yaw = cameraYaw.getAsInt() & 0x7ff;
		if (lastYaw == Integer.MIN_VALUE)
		{
			lastYaw = yaw;
			settledYaw = yaw;
			return false;
		}
		boolean moving = angle(yaw, lastYaw) > 12 /* CAMERA_JITTER */;
		lastYaw = yaw;
		if (moving)
		{
			stillTicks = 0;
			// CAMERA_SWING: A camera swing this big, then two still ticks, is a look worth sharing.
			if (angle(yaw, settledYaw) > 200 /* CAMERA_SWING */)
				swung = true;
			return false;
		}
		if (++stillTicks < CAMERA_SETTLE_TICKS)
			return false;
		boolean look = swung && now >= cameraCooldownUntil && idleTicks.getAsInt() > 0;
		swung = false;
		settledYaw = yaw;
		if (!look)
			return false;
		cameraCooldownUntil = now + CAMERA_COOLDOWN_TICKS;
		follower.glanceAt(lookTile(from, yaw, CAMERA_LOOK_TILES), 1500 /* CAMERA_GLANCE_MS */);
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
		double radians = (orientation / 2048.0) * 2.0 * PI;
		int dx = (int) round(-sin(radians) * tiles),
			dy = (int) round(-cos(radians) * tiles);
		return from.dx(dx).dy(dy);
	}

	/** The smaller angle between two yaws, in JAU. */
	static int angle(int a, int b)
	{
		int d = (a - b) & 0x7ff;
		return d > 1024 ? 2048 - d : d;
	}

	void lookRound(int now, WorldPoint from)
	{
		if (idleTicks.getAsInt() < IDLE_BEFORE_AROUND_TICKS)
		{
			aroundIn = -1;
			return;
		}
		if (aroundIn < 0)
		{
			aroundIn = between(AROUND_MIN_TICKS, AROUND_MAX_TICKS);
			return;
		}
		if (--aroundIn > 0)
			return;
		aroundIn = -1;
		lookRoundNow(from);
	}

	/** The look round itself, at something nearby or nothing in particular: where, or null. */
	WorldPoint lookRoundNow(WorldPoint from)
	{
		var things = nearby.get();
		WorldPoint at = null;
		if (things != null && !things.isEmpty())
			at = things.get(random.nextInt(things.size()));
		if (at == null)
		{
			double heading = random.nextDouble() * PI * 2;
			int distance = between(AROUND_NEAR, AROUND_FAR);
			at = from.dx((int) round(cos(heading) * distance))
				.dy((int) round(sin(heading) * distance));
		}
		if (at.equals(from))
			return null;
		follower.glanceAt(at, AROUND_MS);
		return at;
	}

	int between(int min, int max)
	{
		return min + random.nextInt(max(1, max - min + 1));
	}

	/** Logout, hop: nothing carried over. */
	void reset()
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
