package com.follower.follower;

import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.SpotAnimRepository;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * The follower: a {@link RuneLiteObject} carrying a composed player model, moving
 * with the game's own follow mechanics.
 *
 * <p>Two layers, both taken verbatim from the preserved RS2 engine and client
 * sources rather than approximated:
 *
 * <p><b>Server rule</b> - every entity's {@code followX/followZ} is the tile it
 * last stepped FROM, and a follower re-paths to that tile whenever it finishes its
 * route. "One tile behind" is emergent from that single rule, not an offset.
 *
 * <p><b>Client rule</b> ({@code routeMove}) - the fine position glides along the
 * queued tiles at 4 units per 20ms cycle walking, 2 while turning, 6 with three or
 * more tiles queued, 8 with four or more, all doubled on run steps; it snaps if
 * the next tile is over two tiles away; yaw turns at 32/2048 per cycle toward the
 * 8-direction of travel; the run animation plays at speed >= 8. Movement steps each
 * axis independently, so diagonals are genuinely faster - that is authentic.
 *
 * <p>The fine position is anchored in WORLD units (tile x 128), converted to scene
 * coordinates only at render time, so a region rebuild cannot teleport the
 * follower. Client thread only.
 */
@Slf4j
@Singleton
public class FollowerEntity
{
	/** Local (fine) units per tile. */
	private static final int TILE = Perspective.LOCAL_TILE_SIZE;

	/** One client cycle: the clock every routeMove constant is expressed in. */
	private static final int CYCLE_MS = 20;

	/** JAU per cycle an entity turns toward its destination yaw. */
	private static final int TURN_SPEED = 32;

	/** Beyond this distance the player must have teleported; snap rather than walk. */
	private static final int SNAP_DISTANCE = 14;

	private final Client client;
	private final StanceLibrary stanceLibrary;

	private RuneLiteObject object;

	/** Animations the follower plays, so they can be opted into interpolation. */
	private final java.util.Set<Integer> animationIds =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The follower's rendered tile - where the fine position has reached. */
	private WorldPoint tile;

	/** Fine position in world units: worldTile * 128 + 0..127, tile centre at 64. */
	private long fineWX = -1;
	private long fineWY = -1;

	/**
	 * The server layer: steps computed but not yet released. The real server
	 * releases at most one step per tick walking, two running - that gate is what
	 * stops a follower ever outpacing the player. Without it the render queue
	 * held the whole remaining path, and the client's queue-depth catch-up speeds
	 * (meant for render backlog) turned into sprint-ahead speeds that carried the
	 * follower into, and past, the player on long runs.
	 */
	private final Deque<WorldPoint> serverPath = new ArrayDeque<>();

	/** The follower's server-side tile: where the released steps end. */
	private WorldPoint simTile;

	/** Wall-clock accumulator for the 600ms server tick. */
	private long tickCarryMs;

	/** Released steps still to glide through (head = next), with run flags. */
	private final Deque<WorldPoint> route = new ArrayDeque<>();
	private final Deque<Boolean> routeRun = new ArrayDeque<>();

	private int yaw;
	private int dstYaw;

	/** Speed the last movement cycle used; >= 8 means the run animation. */
	private int lastMoveSpeed;

	/** The player's followX/followZ - the tile they last stepped FROM. */
	private WorldPoint playerFollowTile;
	private WorldPoint lastSeenPlayerTile;
	private boolean playerRunning;

	private int activePose = -1;
	private boolean animatable;
	private boolean emotePlaying;
	private boolean needsReattach;
	private int controllerGeneration;
	private int poseOverride;
	private int wrapTrim = 1;

	/** Per-animation trim overrides, tuned by eye. */
	private final java.util.Map<Integer, Integer> wrapTrims = new java.util.HashMap<>();

	/** Per-animation trims derived by measuring the model. */
	private final java.util.Map<Integer, Integer> measuredTrims = new java.util.HashMap<>();

	/** The unposed model, needed to pose it at arbitrary frames for measurement. */
	@Getter
	private net.runelite.api.Model baseModel;
	private int[] spawnAnimationIds;
	private int weaponItemId = StanceLibrary.UNARMED;

	/** Units to raise the follower clear of the ground. */
	@lombok.Setter
	private int verticalOffset;

	@Getter
	private boolean spawned;

	/** Where the follower was last drawn, for the speech overlay to project against. */
	@Getter
	private LocalPoint lastRenderedLocation;

	/** The render z used at the last draw, needed to project the clickbox. */
	@Getter
	private int lastRenderedZ;

	private long lastFrameMs;

	@Inject
	public FollowerEntity(Client client, StanceLibrary stanceLibrary)
	{
		this.client = client;
		this.stanceLibrary = stanceLibrary;
	}

	// ------------------------------------------------------------------ lifecycle

	public void spawn(ComposedAppearance appearance, WorldPoint at)
	{
		despawn();

		if (appearance == null || appearance.getModel() == null)
		{
			return;
		}

		object = client.createRuneLiteObject();
		if (object == null)
		{
			log.warn("createRuneLiteObject returned null");
			return;
		}

		// Actor-style FACE-SORTED rendering. Player parts genuinely
		// interpenetrate - the back pokes through the cape, hair through the
		// hat - and the game always hides it by drawing actors with the
		// 12-class priority sort and NO depth testing, trusting the sort. A
		// RuneLiteObject's DEFAULT mode takes the cheaper depth-buffered route,
		// which faithfully shows the raw interpenetration; the model's priority
		// data - verified identical to the client's own, index for index -
		// only matters on the sorted path. SORTED_NO_DEPTH is that path: in the
		// GPU plugin it is the ONLY mode that reaches uploadSortedModel with
		// prioritySort=true (plain SORTED is not special-cased at all).
		object.setRenderMode(net.runelite.api.Renderable.RENDERMODE_SORTED_NO_DEPTH);

		animatable = appearance.isAnimatable();
		object.setModel(appearance.getModel());
		// Keep the unposed model: measuring wrap points needs to pose it at arbitrary
		// frames, and getModel() returns the already-animated one.
		baseModel = appearance.getModel();
		// Measured trims are NOT cleared here. The best wrap point comes from how the
		// animation's frames relate to one another, which barely changes with outfit -
		// so re-measuring on every gear change would be wasted work.
		object.setDrawFrontTilesFirst(true);

		tile = at;
		simTile = at;
		fineWX = at.getX() * 128L + 64;
		fineWY = at.getY() * 128L + 64;
		route.clear();
		routeRun.clear();
		serverPath.clear();
		playerFollowTile = null;
		lastSeenPlayerTile = null;
		moveCarryX = 0;
		moveCarryY = 0;
		turnCarry = 0;
		tickCarryMs = 0;
		lastFrameMs = System.currentTimeMillis();

		applyPose(stance().idle, true);
		render();

		object.setActive(true);
		spawned = true;

		if (spawnAnimationIds != null && spawnAnimationIds.length > 0)
		{
			playAnimations(spawnAnimationIds);
		}
	}

	/** Animation(s) played on spawn so the follower doesn't just pop in. */
	public void setSpawnAnimation(int[] animationIds)
	{
		spawnAnimationIds = animationIds;
	}

	public void despawn()
	{
		if (object != null)
		{
			object.setActive(false);
			object = null;
		}
		for (RuneLiteObject graphic : activeGraphics)
		{
			graphic.setActive(false);
		}
		activeGraphics.clear();
		stayTile = null;
		goalTile = null;
		route.clear();
		routeRun.clear();
		serverPath.clear();
		lastSeenPlayerTile = null;
		playerFollowTile = null;
		spawned = false;
		activePose = -1;
	}

	/** Swaps the model without disturbing position or trail. */
	public void setAppearance(ComposedAppearance appearance)
	{
		if (object == null || appearance == null || appearance.getModel() == null)
		{
			return;
		}
		animatable = appearance.isAnimatable();
		object.setModel(appearance.getModel());
		// Keep the unposed model: measuring wrap points needs to pose it at arbitrary
		// frames, and getModel() returns the already-animated one.
		baseModel = appearance.getModel();
		// Measured trims are NOT cleared here. The best wrap point comes from how the
		// animation's frames relate to one another, which barely changes with outfit -
		// so re-measuring on every gear change would be wasted work.
		if (!animatable)
		{
			object.setPoseAnimationController(null);
			object.setAnimationController(null);
			activePose = -1;
		}
		else
		{
			applyPose(activePose < 0 ? PlayerPose.IDLE : activePose, true);
		}
	}

	/**
	 * Best free tile behind the player, preferring directly behind and fanning out
	 * from there. Never returns the player's own tile.
	 *
	 * @param distance how many tiles back to aim for
	 */
	public WorldPoint restingTileBehind(Player local, int distance)
	{
		WorldPoint playerTile = local.getWorldLocation();
		if (playerTile == null)
		{
			return null;
		}

		// Orientation is JAU: 0 is south, increasing counter-clockwise. "Behind" is
		// simply the reverse of where they face.
		int jau = local.getCurrentOrientation();
		double radians = (jau / 2048.0) * 2.0 * Math.PI;
		double facingX = -Math.sin(radians);
		double facingY = -Math.cos(radians);

		// Walkable is not enough: a tile on the far side of a wall is "walkable"
		// but unreachable, and a follower spawned there walks THROUGH the wall to
		// reach you. Candidates must be genuinely step-reachable from the player.
		java.util.Set<WorldPoint> reachable = reachableFrom(playerTile, distance + 1);

		// Try directly behind first, then progressively wider angles either side, so
		// the follower ends up as close to "behind" as the terrain allows.
		int[] offsets = {0, 256, -256, 512, -512, 768, -768, 1024};
		for (int offset : offsets)
		{
			double a = radians + (offset / 2048.0) * 2.0 * Math.PI;
			double bx = -Math.sin(a);
			double by = -Math.cos(a);

			for (int d = distance; d >= 1; d--)
			{
				int x = playerTile.getX() - (int) Math.round(bx * d);
				int y = playerTile.getY() - (int) Math.round(by * d);
				WorldPoint candidate = new WorldPoint(x, y, playerTile.getPlane());

				if (!candidate.equals(playerTile) && reachable.contains(candidate))
				{
					return candidate;
				}
			}
		}

		return null;
	}

	/**
	 * Every tile reachable from {@code start} within {@code radius} legal steps -
	 * legal per {@link #canStep}, so walls block even where the ground beyond
	 * them is clear.
	 */
	private java.util.Set<WorldPoint> reachableFrom(WorldPoint start, int radius)
	{
		java.util.Set<WorldPoint> seen = new java.util.HashSet<>();
		Deque<WorldPoint> frontier = new ArrayDeque<>();
		seen.add(start);
		frontier.add(start);

		for (int depth = 0; depth < radius && !frontier.isEmpty(); depth++)
		{
			int size = frontier.size();
			for (int i = 0; i < size; i++)
			{
				WorldPoint at = frontier.poll();
				for (int dx = -1; dx <= 1; dx++)
				{
					for (int dy = -1; dy <= 1; dy++)
					{
						if (dx == 0 && dy == 0)
						{
							continue;
						}
						WorldPoint next = new WorldPoint(
							at.getX() + dx, at.getY() + dy, at.getPlane());
						if (!seen.contains(next) && canStep(at, dx, dy))
						{
							seen.add(next);
							frontier.add(next);
						}
					}
				}
			}
		}
		return seen;
	}

	/**
	 * Sends the follower to the open tile in front of the player, facing them on
	 * arrival. Following pauses until it arrives or the player moves.
	 *
	 * @return false when no legal, reachable tile exists in front - the caller
	 * should have the follower say so rather than shoving into a wall
	 */
	public boolean moveToFront(Player local)
	{
		WorldPoint playerTile = local.getWorldLocation();
		if (playerTile == null || !spawned)
		{
			return false;
		}

		int jau = local.getCurrentOrientation();
		double radians = (jau / 2048.0) * 2.0 * Math.PI;
		int dx = (int) Math.round(-Math.sin(radians));
		int dy = (int) Math.round(-Math.cos(radians));

		WorldPoint front = new WorldPoint(
			playerTile.getX() + dx, playerTile.getY() + dy, playerTile.getPlane());

		if (front.equals(tile))
		{
			facePlayer();
			return true;
		}
		if (!canStep(playerTile, dx, dy)
			|| !reachableFrom(playerTile, 3).contains(front))
		{
			return false;
		}

		// Face-me replaces a stay pose: the follower is being called over.
		stayTile = null;
		goalTile = front;
		serverPath.clear();
		return true;
	}

	/** A one-off destination that suspends following until reached. */
	private WorldPoint goalTile;

	/**
	 * A posed destination that PERSISTS: unlike {@link #goalTile}, the player
	 * walking away does not release it - only an explicit Follow, a forced
	 * relocation (plane change, leash snap, scene recovery), or a despawn does.
	 */
	private WorldPoint stayTile;

	/**
	 * While posed the player may roam this far before the follower snaps back
	 * to them (releasing the pose). Wider than the follow snap distance - the
	 * point of posing is walking away from the follower - but well inside the
	 * loaded scene, so the object never strands off the edge.
	 */
	private static final int STAY_LEASH = 40;

	public boolean isStaying()
	{
		return stayTile != null;
	}

	/**
	 * True when the follower has fully stopped: no queued route, and its fine
	 * position rests at its tile's centre. An emote started before this is
	 * cancelled by the very next movement frame.
	 */
	public boolean isSettled()
	{
		return route.isEmpty() && serverPath.isEmpty() && tile != null
			&& fineWX == tile.getX() * 128L + 64
			&& fineWY == tile.getY() * 128L + 64;
	}

	/** The model the object is rendering RIGHT NOW (post-animation), for diagnostics. */
	public net.runelite.api.Model getRenderModel()
	{
		return object == null ? null : object.getModel();
	}

	/** Releases a stay pose; following resumes on its own. */
	public void resumeFollowing()
	{
		stayTile = null;
		stayFaceTile = null;
	}

	/**
	 * While holding a stay pose, face this tile instead of the player - an
	 * errand faces its business: the booth, the altar, the cat. Cleared with
	 * the pose.
	 */
	private WorldPoint stayFaceTile;

	public void setStayFaceTile(WorldPoint tile)
	{
		stayFaceTile = tile;
	}

	/** The stay pose's idle facing: the face tile when set, the player otherwise. */
	private void faceStayAnchor()
	{
		if (stayFaceTile != null && tile != null)
		{
			int dx = tile.getX() - stayFaceTile.getX();
			int dy = tile.getY() - stayFaceTile.getY();
			if (dx != 0 || dy != 0)
			{
				dstYaw = (int) Math.round(Math.atan2(dx, dy) * 325.949) & 0x7ff;
			}
			return;
		}
		facePlayer();
	}

	/** Until when the follower stays invisible (the comedy death's empty stage). */
	private long hideUntilMs;

	/** Armed by the comedy death: at the emote's final frame, go dark this long. */
	private int hideAfterEmoteMs;

	/**
	 * Arms a frame-accurate disappearance: the instant the current emote
	 * finishes, the follower goes invisible for the given time - no standing
	 * back up first. The caller stages the return.
	 */
	public void hideAfterEmote(int ms)
	{
		hideAfterEmoteMs = ms;
	}

	/** Snaps the follower to the player's side, ending any pose - the errand's way home. */
	public void teleportToPlayer()
	{
		// Arriving home ends any staged disappearance, however long it had left.
		hideUntilMs = 0;
		Player local = client.getLocalPlayer();
		if (spawned && local != null && local.getWorldLocation() != null)
		{
			snapBeside(local.getWorldLocation());
		}
	}

	// -------------------------------------------------------------- thrall mode

	/** The possessed thrall NPC; non-null suspends ALL follow logic. */
	private net.runelite.api.NPC thrallNpc;
	private WorldPoint lastThrallTile;

	/**
	 * Enters thrall mode: the follower stops being a follower and becomes the
	 * given NPC - position, plane and facing are copied from it every frame
	 * until {@link #releaseNpcSlave()}. Movement poses still come from the
	 * follower's own stance for its equipped weapon, so it walks like a player
	 * in the thrall's footsteps rather than gliding.
	 */
	public void slaveToNpc(net.runelite.api.NPC npc)
	{
		thrallNpc = npc;
		lastThrallTile = null;
		stayTile = null;
		goalTile = null;
		route.clear();
		routeRun.clear();
		serverPath.clear();
		cancelEmote();
	}

	/** Leaves thrall mode and reappears beside the player, following as before. */
	public void releaseNpcSlave()
	{
		thrallNpc = null;
		lastThrallTile = null;
		clearThrallCircle();
		Player local = client.getLocalPlayer();
		if (local != null && local.getWorldLocation() != null)
		{
			snapBeside(local.getWorldLocation());
		}
	}

	/**
	 * Ends the possession but HOLDS the follower where it stands - the exit
	 * flourish plays out at the thrall's last spot before the snap home.
	 */
	public void endNpcSlaveHolding()
	{
		thrallNpc = null;
		lastThrallTile = null;
		clearThrallCircle();
		if (spawned && tile != null)
		{
			stayHere();
		}
	}

	/** The thrall's summoning circle: a looping ground graphic riding the follower. */
	private RuneLiteObject thrallCircle;

	/**
	 * A circle COMPONENT lies within this of the ground plane; the body
	 * component reaches far above it. Component-level, so it need not be
	 * razor thin - a foot is disqualified by the leg it is welded to.
	 */
	private static final float CIRCLE_HEIGHT_LIMIT = 12f;

	/**
	 * The authentic summoning circle, carved out of the thrall NPC's own model:
	 * the circle is baked into the same mesh as the body (measured - each
	 * thrall composition holds exactly one model id), so every face reaching
	 * meaningfully above the ground plane is made fully transparent, leaving
	 * the genuine style-coloured ring.
	 */
	public void setThrallCircleFromNpcModel(int modelId)
	{
		clearThrallCircle();
		if (object == null || modelId < 0)
		{
			return;
		}
		ModelData data = client.loadModelData(modelId);
		if (data == null)
		{
			return;
		}
		data = data.cloneVertices().cloneColors().cloneTransparencies(true);
		byte[] alpha = data.getFaceTransparencies();
		if (alpha == null)
		{
			return;
		}
		// Deterministic classification, no per-face guessing: faces that share
		// vertices form connected components - each foot is welded to its own
		// 3D geometry, the circle is its own component lying flat on the
		// ground. A component is kept only when it is BOTH flat and grounded
		// as a WHOLE, so a flat sole can never survive on its own: it is
		// carried out with the leg it is attached to.
		float[] ys = data.getVerticesY();
		int[] f1 = data.getFaceIndices1();
		int[] f2 = data.getFaceIndices2();
		int[] f3 = data.getFaceIndices3();
		int faceCount = data.getFaceCount();
		int vertexCount = data.getVerticesCount();

		int[] parent = new int[vertexCount];
		for (int i = 0; i < vertexCount; i++)
		{
			parent[i] = i;
		}
		for (int i = 0; i < faceCount; i++)
		{
			union(parent, f1[i], f2[i]);
			union(parent, f2[i], f3[i]);
		}

		// Vertical extent of every component, over its vertices.
		float[] minY = new float[vertexCount];
		float[] maxY = new float[vertexCount];
		java.util.Arrays.fill(minY, Float.MAX_VALUE);
		java.util.Arrays.fill(maxY, -Float.MAX_VALUE);
		for (int i = 0; i < vertexCount; i++)
		{
			int root = find(parent, i);
			minY[root] = Math.min(minY[root], ys[i]);
			maxY[root] = Math.max(maxY[root], ys[i]);
		}

		for (int i = 0; i < faceCount; i++)
		{
			int root = find(parent, f1[i]);
			// Up is negative Y: grounded means the component's highest point
			// stays near the ground plane; flat means it has no real height.
			boolean grounded = minY[root] > -CIRCLE_HEIGHT_LIMIT;
			boolean flat = maxY[root] - minY[root] <= CIRCLE_FLATNESS;
			if (!grounded || !flat)
			{
				alpha[i] = (byte) 0xFF;
			}
		}

		Model lit = data.light(64, 850, -30, -50, -30);
		RuneLiteObject graphic = client.createRuneLiteObject();
		graphic.setRenderMode(net.runelite.api.Renderable.RENDERMODE_SORTED_NO_DEPTH);
		graphic.setModel(lit);
		if (lastRenderedLocation != null && tile != null)
		{
			graphic.setLocation(lastRenderedLocation, tile.getPlane());
			graphic.setZ(lastRenderedZ);
		}
		graphic.setActive(true);
		thrallCircle = graphic;

		// Same-tile RuneLiteObjects draw in attach order: bouncing the
		// follower back into the scene AFTER the circle puts the follower's
		// model on top, feet over ring - the layering the real thrall shows.
		needsReattach = true;
	}

	/** A circle COMPONENT has essentially no vertical extent; a foot's does. */
	private static final float CIRCLE_FLATNESS = 6f;

	private static int find(int[] parent, int i)
	{
		while (parent[i] != i)
		{
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b)
	{
		parent[find(parent, a)] = find(parent, b);
	}

	public void clearThrallCircle()
	{
		if (thrallCircle != null)
		{
			thrallCircle.setActive(false);
			thrallCircle = null;
		}
	}

	public boolean isNpcSlaved()
	{
		return thrallNpc != null;
	}

	/**
	 * The per-frame body of thrall mode. The REAL thrall is hidden by a draw
	 * veto, and a vetoed draw also skips the client's per-frame movement
	 * interpolation - a hidden NPC's fine position freezes between server
	 * ticks. Its SERVER tile still updates, so those tiles feed the follower's
	 * own stepper, which recreates the smooth walk the thrall would have
	 * shown. The big-jump guard doubles as the thrall catch-up teleport.
	 */
	private void updateThrallFrame(long deltaMs)
	{
		net.runelite.api.NPC npc = thrallNpc;
		WorldPoint wp = npc == null ? null : npc.getWorldLocation();
		if (wp == null)
		{
			// Mid chunk-load the NPC blinks out for a beat; hold hidden rather
			// than glitch to a stale spot. Despawn proper is the plugin's call.
			if (object.isActive())
			{
				object.setActive(false);
			}
			return;
		}

		// First frame of a possession, a plane change or a genuine catch-up
		// teleport: place directly rather than gliding across the map.
		if (tile == null || lastThrallTile == null
			|| wp.getPlane() != tile.getPlane() || tile.distanceTo(wp) > 10)
		{
			route.clear();
			routeRun.clear();
			tile = wp;
			fineWX = wp.getX() * 128L + 64;
			fineWY = wp.getY() * 128L + 64;
		}
		else if (!wp.equals(lastThrallTile))
		{
			// A new server tile from the thrall becomes a waypoint. The run
			// flag is not cosmetic: a thrall keeps pace with the player, so
			// when you run it covers two tiles a tick. Walking that gap takes
			// two ticks and the follower falls permanently behind - which is
			// exactly the "thrall walks too slowly" symptom. Two tiles in one
			// tick IS running, so mark it and let the stepper move at run
			// speed.
			boolean covered = lastThrallTile.distanceTo(wp) >= 2;
			route.addLast(wp);
			routeRun.addLast(covered);
		}
		lastThrallTile = wp;

		moveContinuous(deltaMs / (double) CYCLE_MS);

		boolean moving = !route.isEmpty()
			|| fineWX != tile.getX() * 128L + 64 || fineWY != tile.getY() * 128L + 64;

		// A fighting entity keeps FACING its target even while its feet move -
		// the client's entityFace. Applied after moveContinuous so it overrides
		// the travel heading that was just set.
		boolean facingTarget = faceThrallTarget(false);

		StanceLibrary.Stance stance = stance();
		int pose;
		if (!moving)
		{
			pose = stance.idle;
		}
		else if (facingTarget)
		{
			// Stepping while locked on the target: directional poses (strafe,
			// back-pedal), exactly as the client picks them for a face target.
			pose = movePose(stance);
		}
		else
		{
			pose = lastMoveSpeed >= 8 ? stance.run : stance.walk;
		}
		applyPose(pose, false);
		wrapBeforeFinalFrame();
		render();

		// The summoning circle rides the follower's rendered feet.
		if (thrallCircle != null && lastRenderedLocation != null)
		{
			thrallCircle.setLocation(lastRenderedLocation, tile.getPlane());
			thrallCircle.setZ(lastRenderedZ);
		}
	}

	/**
	 * Points the follower at the thrall's combat target. Smooth turns the yaw
	 * toward it normally; {@code snap} sets it instantly - the moment an
	 * attack fires, the swing must already face the enemy.
	 *
	 * @return true when there was a target to face
	 */
	public boolean faceThrallTarget(boolean snap)
	{
		if (thrallNpc == null || tile == null)
		{
			return false;
		}
		net.runelite.api.Actor target = thrallTarget();
		WorldPoint targetTile = target == null ? null : target.getWorldLocation();
		if (targetTile == null)
		{
			return false;
		}
		// SELF minus TARGET, matching facePlayer: the reversed delta bakes in
		// the game's 0-is-south yaw convention. Target-minus-self faces the
		// exact opposite way.
		int dx = tile.getX() - targetTile.getX();
		int dy = tile.getY() - targetTile.getY();
		if (dx == 0 && dy == 0)
		{
			return false;
		}
		dstYaw = (int) Math.round(Math.atan2(dx, dy) * 325.949) & 0x7ff;
		if (snap)
		{
			yaw = dstYaw;
		}
		return true;
	}

	/** Holds the follower where it stands until released. */
	public void stayHere()
	{
		if (!spawned || tile == null)
		{
			return;
		}
		stayTile = tile;
		goalTile = null;
		serverPath.clear();
	}

	/**
	 * Sends the follower to a tile and holds it there until released. Routed by
	 * the client's own BFS pathfinder, with its closest-approach behaviour: a
	 * blocked destination (inside a fenced yard) walks the follower as close
	 * as collision allows, exactly as Walk here does for the player.
	 *
	 * @return false only when nothing near the target is reachable at all, or
	 * the send would immediately trip the stay leash
	 */
	public boolean stayAt(WorldPoint target)
	{
		if (!spawned || tile == null || target == null
			|| target.getPlane() != tile.getPlane())
		{
			return false;
		}

		// A destination near the leash edge would snap-and-release the moment
		// the follower set off; refuse it as out of range instead.
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null
			|| target.distanceTo(local.getWorldLocation()) > STAY_LEASH - 2)
		{
			return false;
		}

		WorldPoint from = simTile != null ? simTile : tile;
		java.util.List<WorldPoint> path = findPath(from, target, true, playerTileToAvoid());
		if (path == null)
		{
			return false;
		}

		// Closest-approach may legitimately stop short of the clicked tile.
		stayTile = path.isEmpty() ? from : path.get(path.size() - 1);
		goalTile = null;
		serverPath.clear();
		serverPath.addAll(path);
		return true;
	}

	/** True if a tile is free of walls and floor blockers. */
	private boolean isWalkable(WorldPoint tile)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, tile);
		if (localPoint == null)
		{
			return false;
		}

		CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
		if (maps == null || tile.getPlane() >= maps.length || maps[tile.getPlane()] == null)
		{
			// No collision data (instances sometimes); assume open rather than refuse.
			return true;
		}

		int[][] flags = maps[tile.getPlane()].getFlags();
		int sx = localPoint.getSceneX();
		int sy = localPoint.getSceneY();
		if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length)
		{
			return false;
		}

		return (flags[sx][sy] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	/** Drops queued route steps. */
	public void clearTrail()
	{
		route.clear();
		routeRun.clear();
		serverPath.clear();
	}

	/**
	 * Forces the object to be re-inserted into the scene on the next placement.
	 *
	 * <p>A RuneLiteObject belongs to the scene it was added to. When the scene is
	 * rebuilt - region load, teleport, entering an instance - the object is dropped
	 * from it, but its own active flag stays true. Nothing then re-adds it: the
	 * follower is gone from the world while still reporting itself as active, which
	 * is why a plain "is it active?" check could never detect this.
	 */
	public void markNeedsReattach()
	{
		needsReattach = true;
	}

	public WorldPoint getWorldLocation()
	{
		return tile;
	}

	// ------------------------------------------------------------------- movement

	/**
	 * Watches the player's tile per frame and maintains their followX/followZ.
	 *
	 * <p>Per frame rather than per GameTick deliberately: GameTick does not fire
	 * while a chunk loads, yet the player keeps moving through it - a tick-fed
	 * watcher starves exactly when it is needed most.
	 *
	 * <p>followX/followZ is the tile the player last stepped FROM: on a one-tile
	 * move that is their previous tile; on a two-tile (running) move it is the
	 * intermediate tile, reconstructed by stepping one square toward the
	 * destination. That reconstruction can differ from the server's true path
	 * around obstacles, but only for the single tile between two adjacent ones.
	 */
	private void watchPlayer(Player local)
	{
		WorldPoint current = local.getWorldLocation();
		if (current == null || current.equals(lastSeenPlayerTile))
		{
			return;
		}

		int distance = lastSeenPlayerTile == null ? Integer.MAX_VALUE
			: lastSeenPlayerTile.distanceTo(current);

		if (lastSeenPlayerTile == null
			|| lastSeenPlayerTile.getPlane() != current.getPlane()
			|| distance > 4)
		{
			// First sighting, or a genuinely teleport-scale jump: there is no
			// meaningful "tile stepped from" to follow. The player's CURRENT
			// tile stands in - closest-approach parks the follower beside it.
			// A null here left the follower frozen at the departure spot until
			// the player took a step (FTRACE 2026-08-04: sim=9 held for 15s
			// after a 9-tile teleport).
			if (debugEnabled)
			{
				log.info("FTRACE obs JUMP from {} to {}", lastSeenPlayerTile, current);
			}
			lastSeenPlayerTile = current;
			playerFollowTile = current;
			return;
		}

		// 3-4 tiles in one observation is not a teleport - it is TWO server ticks
		// of running sampled by one slow frame. Treating it as a jump skipped both
		// the path extension and the releases for that span, and mid-run there is
		// no headroom to recover a skipped release: every laggy frame permanently
		// widened the gap.
		int ticksObserved = distance > 2 ? 2 : 1;
		playerRunning = distance >= 2;

		// The player walking off cancels a posed goal - following resumes, the
		// same way any real interaction breaks when you move away. A STAY pose
		// deliberately survives this: that is its whole difference from a goal.
		goalTile = null;

		// followX/followZ: one step behind the player's current tile along the path.
		WorldPoint behind = lastSeenPlayerTile;
		for (int i = 0; i < distance - 1; i++)
		{
			behind = stepToward(behind, current);
		}
		playerFollowTile = behind;
		lastSeenPlayerTile = current;

		// Extend the path IMMEDIATELY rather than waiting for it to empty, so a
		// walking follower's queue never runs dry at a tile boundary - waiting
		// caused a visible stutter-step with the pose flapping walk/idle. While
		// staying, the follow tile must not be pathed at all - the stay path is
		// the only target (the releases below still drain it).
		//
		// (A per-observation smart RE-PATH replacing the queue - the server's
		// own model for player followers - was tried 2026-08-04 and reverted:
		// the trace numbers were fine but the follow FEEL was off. The greedy
		// append below is the behaviour the followtrace sessions verified.)
		if (stayTile == null)
		{
			if (serverPath.isEmpty() && simTile != null
				&& simTile.distanceTo(playerFollowTile) > 3)
			{
				// Re-acquiring from afar - a just-released pose, possibly with
				// walls in between. The greedy stepper's blocked-tile fallback
				// takes the RAW step (so missing collision data never freezes
				// the follower), which from here means walking THROUGH the
				// wall; the BFS routes around it. Steady-state following never
				// enters this branch - its gap is one or two tiles.
				extendPathBfs(playerFollowTile, null);
			}
			else
			{
				extendPathTo(playerFollowTile);
			}
		}

		// Release in the same breath, phase-locked to the player's own steps - one
		// release per server tick the observation covered. An independent release
		// clock drifted against the observations, and each phase slip lost a tile
		// forever; the server has no such hazard because everything moves in the
		// same tick.
		tickCarryMs = 0;
		if (debugEnabled)
		{
			log.info("FTRACE obs dist={} ticks={} run={} follow={} path={}",
				distance, ticksObserved, playerRunning, playerFollowTile, serverPath.size());
		}
		for (int i = 0; i < ticksObserved; i++)
		{
			releaseSteps();
		}
	}

	/**
	 * The client's own Walk-here pathfinder, ported structurally verbatim from
	 * its {@code tryMove}: a breadth-first flood over the loaded scene that
	 * records, per tile, the direction it was first reached from, then walks
	 * backwards from the destination to recover the route. Because it explores
	 * everything, "around the U" falls out naturally - the greedy stepper's
	 * dead-end jig cannot happen on a BFS route.
	 *
	 * <p>Fidelity notes: neighbours expand in the client's exact order - W, E,
	 * S, N, SW, SE, NW, NE - which is the tie-breaker deciding WHICH of several
	 * equal-length routes gets taken, so the follower picks the same corners a
	 * real player would. Edge legality reuses {@link #canStep}, the same rules
	 * the movement executor was verified against in game. With
	 * {@code tryNearest}, an unreachable destination falls back to the client's
	 * closest-approach scan: one ring around the target, keeping the tile with
	 * the lowest flood cost under 100 - which is why clicking inside a fenced
	 * yard walks you up against the fence.
	 *
	 * @return every tile of the route after {@code src} in walking order; empty
	 * if already there; null if nothing reachable
	 */
	private java.util.List<WorldPoint> findPath(WorldPoint src, WorldPoint dest, boolean tryNearest)
	{
		return findPath(src, dest, tryNearest, null);
	}

	/**
	 * @param avoid a tile to route around, or null. Posed movement passes the
	 *              PLAYER'S tile: the pathfinder itself walks through players
	 *              (they don't collide), but a follower cutting straight
	 *              through you reads as a glitch - and walking AROUND you is
	 *              the lateral movement that shows the side-step poses while
	 *              face-locked. An avoided destination resolves by the
	 *              closest-approach fallback, landing beside it.
	 */
	private java.util.List<WorldPoint> findPath(WorldPoint src, WorldPoint dest,
		boolean tryNearest, WorldPoint avoid)
	{
		LocalPoint srcLocal = LocalPoint.fromWorld(client, src);
		LocalPoint destLocal = LocalPoint.fromWorld(client, dest);
		if (srcLocal == null || destLocal == null)
		{
			return null;
		}

		final int size = Perspective.SCENE_SIZE;
		int[] dirMap = new int[size * size];
		int[] distMap = new int[size * size];
		java.util.Arrays.fill(distMap, 99999999);

		int baseX = src.getX() - srcLocal.getSceneX();
		int baseY = src.getY() - srcLocal.getSceneY();
		int plane = src.getPlane();

		int srcX = srcLocal.getSceneX();
		int srcZ = srcLocal.getSceneY();
		int dx = destLocal.getSceneX();
		int dz = destLocal.getSceneY();

		dirMap[srcX * size + srcZ] = 99;
		distMap[srcX * size + srcZ] = 0;

		// Each tile enqueues at most once, so a flat queue needs no ring wrap.
		int[] queueX = new int[size * size];
		int[] queueZ = new int[size * size];
		int steps = 0;
		int length = 0;
		queueX[steps] = srcX;
		queueZ[steps++] = srcZ;

		// The client's neighbour order, and the direction code each records:
		// W=2 E=8 S=1 N=4 SW=3 SE=9 NW=6 NE=12 (bits point BACK toward the
		// source: NORTH=1, EAST=2, SOUTH=4, WEST=8).
		final int[] stepX = {-1, 1, 0, 0, -1, 1, -1, 1};
		final int[] stepZ = {0, 0, -1, 1, -1, -1, 1, 1};
		final int[] dirCode = {2, 8, 1, 4, 3, 9, 6, 12};

		boolean arrived = false;
		int x = srcX;
		int z = srcZ;
		while (length != steps)
		{
			x = queueX[length];
			z = queueZ[length];
			length++;

			if (x == dx && z == dz)
			{
				arrived = true;
				break;
			}

			int nextCost = distMap[x * size + z] + 1;
			WorldPoint here = new WorldPoint(baseX + x, baseY + z, plane);
			for (int d = 0; d < 8; d++)
			{
				int nx = x + stepX[d];
				int nz = z + stepZ[d];
				if (nx < 0 || nz < 0 || nx >= size || nz >= size
					|| dirMap[nx * size + nz] != 0
					|| (avoid != null && baseX + nx == avoid.getX() && baseY + nz == avoid.getY())
					|| !canStep(here, stepX[d], stepZ[d]))
				{
					continue;
				}
				queueX[steps] = nx;
				queueZ[steps++] = nz;
				dirMap[nx * size + nz] = dirCode[d];
				distMap[nx * size + nz] = nextCost;
			}
		}

		if (!arrived)
		{
			if (tryNearest)
			{
				int min = 100;
				for (int px = dx - 1; px <= dx + 1; px++)
				{
					for (int pz = dz - 1; pz <= dz + 1; pz++)
					{
						if (px >= 0 && pz >= 0 && px < size && pz < size
							&& distMap[px * size + pz] < min)
						{
							min = distMap[px * size + pz];
							x = px;
							z = pz;
							arrived = true;
						}
					}
				}
			}
			if (!arrived)
			{
				return null;
			}
		}

		// Backtrack EVERY tile rather than the client's turn-compressed
		// waypoints - the route feeds serverPath directly, which steps tile by
		// tile.
		java.util.List<WorldPoint> reversed = new java.util.ArrayList<>();
		while (x != srcX || z != srcZ)
		{
			reversed.add(new WorldPoint(baseX + x, baseY + z, plane));
			int dir = dirMap[x * size + z];
			if ((dir & 2) != 0)
			{
				x++;
			}
			else if ((dir & 8) != 0)
			{
				x--;
			}
			if ((dir & 1) != 0)
			{
				z++;
			}
			else if ((dir & 4) != 0)
			{
				z--;
			}
		}
		Collections.reverse(reversed);
		return reversed;
	}

	/**
	 * Appends the BFS route to {@code target}. Posed destinations pass the
	 * player's tile as {@code avoid} (the follower walks around you); follow
	 * re-pathing passes null (a real follower walks through you).
	 */
	private void extendPathBfs(WorldPoint target, WorldPoint avoid)
	{
		if (target == null || simTile == null || target.getPlane() != simTile.getPlane())
		{
			return;
		}
		java.util.List<WorldPoint> path = findPath(simTile, target, true, avoid);
		if (path != null)
		{
			serverPath.addAll(path);
		}
	}

	/** The player's tile, for BFS routes to walk around rather than through. */
	private WorldPoint playerTileToAvoid()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	/** Appends the naive path from the server path's tail to {@code target}. */
	private void extendPathTo(WorldPoint target)
	{
		if (target == null || simTile == null || target.getPlane() != simTile.getPlane()
			|| serverPath.size() > SNAP_DISTANCE)
		{
			return;
		}

		WorldPoint tail = serverPath.isEmpty() ? simTile : serverPath.peekLast();
		// Twice the snap distance: collision detours around corners legitimately
		// take more steps than the straight-line distance.
		for (int guard = 0; guard < SNAP_DISTANCE * 2 && !tail.equals(target); guard++)
		{
			tail = stepToward(tail, target);
			if (tail == null)
			{
				return;
			}
			serverPath.addLast(tail);
		}
	}

	/**
	 * One movement tick: release at most one step walking, two running, into the
	 * render route.
	 *
	 * <p>Two steps are taken when the player is running, or when the follower has
	 * fallen behind - a run-enabled follower closes on a walking target at two
	 * tiles a tick until caught up, exactly as the real follow op does. A single
	 * released step renders as a walk, as a single step does in game.
	 */
	private void releaseSteps()
	{
		// Posed movement - Face-me's walk-around, a Send - is a deliberate WALK:
		// one step a tick, never the run pair, whatever the run orb says.
		boolean posed = goalTile != null || stayTile != null;
		boolean two = !posed && serverPath.size() >= 2 && (playerRunning || serverPath.size() > 2);
		int steps = two ? 2 : 1;
		for (int i = 0; i < steps && !serverPath.isEmpty(); i++)
		{
			WorldPoint next = serverPath.pollFirst();
			route.addLast(next);
			routeRun.addLast(two);
			simTile = next;
		}
		if (debugEnabled)
		{
			log.info("FTRACE rel steps={} run={} sim={} path={} route={}",
				steps, two, simTile, serverPath.size(), route.size());
		}
	}

	/**
	 * True when the mouse is over the follower's on-screen clickbox.
	 *
	 * <p>The game has no idea this object exists, so it can never have a native
	 * clickbox - this projects one from the model, orientation and drawn position.
	 * {@code Perspective.getClickbox} is marked internal (it backs
	 * {@code TileObject#getClickbox()}, which a RuneLiteObject can never provide),
	 * so treat any future signature change there as expected breakage here.
	 */
	public boolean isUnderMouse(net.runelite.api.Point canvasPoint)
	{
		if (!spawned || object == null || baseModel == null
			|| lastRenderedLocation == null || canvasPoint == null)
		{
			return false;
		}

		java.awt.Shape clickbox = Perspective.getClickbox(client,
			client.getTopLevelWorldView(), baseModel, yaw,
			lastRenderedLocation.getX(), lastRenderedLocation.getY(), lastRenderedZ);
		return clickbox != null && clickbox.contains(canvasPoint.getX(), canvasPoint.getY());
	}

	// ------------------------------------------------------------------ debugging

	/** Live follow diagnostics: overlay lines plus an FTRACE log stream. */
	@Getter
	@lombok.Setter
	private boolean debugEnabled;

	/** State lines for the debug overlay, built here where the state lives. */
	public java.util.List<String> debugLines()
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		Player local = client.getLocalPlayer();
		WorldPoint playerTile = local == null ? null : local.getWorldLocation();

		int gapLogical = playerTile == null || simTile == null ? -1
			: simTile.distanceTo(playerTile);
		double gapRender = -1;
		if (local != null && local.getLocalLocation() != null && lastRenderedLocation != null)
		{
			long dx = local.getLocalLocation().getX() - lastRenderedLocation.getX();
			long dy = local.getLocalLocation().getY() - lastRenderedLocation.getY();
			gapRender = Math.sqrt((double) (dx * dx + dy * dy)) / TILE;
		}

		out.add(String.format("gap  sim=%d  render=%.2f", gapLogical, gapRender));
		out.add("player " + describe(playerTile) + "  follow " + describe(playerFollowTile));
		out.add("sim " + describe(simTile) + "  render " + describe(tile));
		out.add("path=" + serverPath.size() + "  route=" + route.size()
			+ "  speed=" + lastMoveSpeed + (playerRunning ? "  RUN" : "  walk"));
		return out;
	}

	private static String describe(WorldPoint p)
	{
		return p == null ? "-" : p.getX() + "," + p.getY();
	}

	/** Per-frame update: follow, move, orient, animate, position. */
	public void updateFrame()
	{
		if (!spawned || object == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		long deltaMs = Math.min(now - lastFrameMs, 600);
		lastFrameMs = now;

		// Mid-teleport: the follower is gone until the player lands (their tile
		// moves from where it was at the cast's end), then reappears at their
		// side - a pet coming through the teleport with you. The timeout covers
		// a cancelled landing that produced no movement.
		if (vanished)
		{
			if (object.isActive())
			{
				object.setActive(false);
			}
			WorldPoint where = local.getWorldLocation();
			boolean landed = where != null && vanishPlayerTile != null
				&& !where.equals(vanishPlayerTile);
			if (landed || now - vanishedAtMs > 8000)
			{
				vanished = false;
				lastSeenPlayerTile = null;
				if (where != null)
				{
					snapBeside(where);
				}
			}
			return;
		}

		// Briefly gone (the comedy death): nothing renders, nothing moves.
		if (System.currentTimeMillis() < hideUntilMs)
		{
			if (object.isActive())
			{
				object.setActive(false);
			}
			return;
		}

		// Thrall mode: the follower IS the thrall. Everything below - follow
		// pathing, stay poses, leashes - is someone else's life for now.
		if (thrallNpc != null)
		{
			updateThrallFrame(deltaMs);
			return;
		}

		watchPlayer(local);
		WorldPoint playerTile = local.getWorldLocation();
		if (tile == null || playerTile == null)
		{
			return;
		}

		// Teleport rule: a plane change or a jump too far to walk snaps the
		// follower to the player's side, like a pet reappearing after a ladder.
		// A stay pose widens the distance to its leash - roaming away from a
		// posed follower is the point - but a snap of any kind releases it.
		int leash = stayTile != null ? STAY_LEASH : SNAP_DISTANCE;
		if (playerTile.getPlane() != tile.getPlane()
			|| tile.distanceTo(playerTile) > leash)
		{
			stayTile = null;
			if (!snapBeside(playerTile))
			{
				recoverPosition(local, playerTile);
			}
			return;
		}

		// Watchdog: if anything at all has left the object hidden while the player
		// is on screen, put it back. A deliberate hide (an NPC sharing the
		// tile) is not a fault and must not trigger a recovery snap.
		if (!object.isActive() && !hiddenForNpcOverlap)
		{
			recoverPosition(local, playerTile);
		}

		// A stay pose owns movement outright: path to the spot, then hold it -
		// facing the player attentively - until explicitly released. The player
		// walking away does NOT end it; watchPlayer skips the follow pathing
		// while it is set.
		if (stayTile != null)
		{
			if (tile.equals(stayTile) && route.isEmpty() && serverPath.isEmpty())
			{
				faceStayAnchor();
			}
			else if (serverPath.isEmpty() && route.isEmpty())
			{
				extendPathBfs(stayTile, playerTileToAvoid());
			}
		}
		// A posed goal suspends following: the goal's path is the only target
		// until the follower arrives (then it faces the player) or the player
		// moves (watchPlayer clears the goal).
		else if (goalTile != null)
		{
			if (tile.equals(goalTile) && route.isEmpty() && serverPath.isEmpty())
			{
				// Arrived: HOLD here, facing the player, until they move. Clearing
				// the goal on arrival let the follow rule immediately walk it back
				// behind them, so the pose lasted a fraction of a second.
				facePlayer();
			}
			else if (serverPath.isEmpty() && route.isEmpty())
			{
				extendPathBfs(goalTile, playerTileToAvoid());
			}
		}
		// Fallback for the cases the eager extension can't see - after a spawn,
		// snap or released pose, the player may already be standing still with
		// the follower away from their follow tile. BFS-routed: the follower
		// may be an arbitrary distance away with walls in between, and the
		// greedy stepper's raw-step fallback would walk it through them.
		else if (serverPath.isEmpty() && route.isEmpty() && playerFollowTile != null
			&& simTile != null
			&& playerFollowTile.getPlane() == simTile.getPlane()
			&& !simTile.equals(playerFollowTile))
		{
			// After a jump the follow tile IS the player's tile; avoid it so
			// closest-approach parks the follower beside them, not on them.
			extendPathBfs(playerFollowTile,
				playerFollowTile.equals(playerTile) ? playerFollowTile : null);
		}

		// Idle drain: releases are normally phase-locked to observed player steps,
		// which stop when the player stops - this timer finishes the follower's
		// remaining approach afterwards, one tick's worth at a time.
		if (!serverPath.isEmpty())
		{
			tickCarryMs += deltaMs;
			while (tickCarryMs >= 600)
			{
				tickCarryMs -= 600;
				releaseSteps();
			}
		}
		else
		{
			tickCarryMs = 0;
		}

		// The client's movement budget for this frame, in 20ms cycles. Consumed
		// CONTINUOUSLY rather than in whole cycles: the real client renders at a
		// locked 20ms per frame so whole cycles are smooth there, but against a
		// variable frame rate they beat (move, move, skip...) - a micro-stutter.
		// The speeds and rules are unchanged; only the granularity is finer.
		moveContinuous(deltaMs / (double) CYCLE_MS);

		boolean moving = !route.isEmpty()
			|| fineWX != tile.getX() * 128L + 64 || fineWY != tile.getY() * 128L + 64;

		// Movement always wins over an emote - a dancing follower gliding along
		// behind you looks broken, and the real game drops emotes on movement too.
		if (moving)
		{
			cancelEmote();
		}

		// Idle facing: the follow op interacts with its target, and an
		// interacting entity keeps facing it while standing - turning in place
		// with the turn pose as the player circles around. A stay pose with a
		// face tile (an errand at its business) faces that instead.
		if (!moving && faceLocked())
		{
			faceStayAnchor();
		}

		StanceLibrary.Stance stance = stance();
		int pose;
		if (poseOverride > 0)
		{
			pose = poseOverride;
		}
		else if (moving)
		{
			// Directional poses belong to the FACE-LOCKED states only (Face-me's
			// walk-around); plain following walks and runs facing its travel,
			// with no side-step flicker on sharp turns while the yaw catches up.
			pose = faceLocked() ? movePose(stance)
				: (lastMoveSpeed >= 8 ? stance.run : stance.walk);
		}
		else if (yaw != dstYaw && faceLocked())
		{
			// The client's entityFace: an idle entity still turning toward its
			// facing plays the turn animation (falling back to walk when the
			// stance has none - its own -1 fallback).
			int remaining = (dstYaw - yaw) & 0x7ff;
			int turn = remaining > 1024 ? stance.turnRight : stance.turnLeft;
			// 0 = field missing from a stance set by hand or learned before
			// these fields existed. The standard turn only suits a weapon on
			// the standard stance; anything else holds its own idle and lets
			// the yaw come round, rather than turning empty-handed.
			pose = turn > 0 ? turn
				: (stance.walk == PlayerPose.WALK ? PlayerPose.IDLE_TURN : stance.idle);
		}
		else
		{
			pose = stance.idle;
		}
		applyPose(pose, false);

		wrapBeforeFinalFrame();

		if (!render())
		{
			// A chunk load is a seamless, sub-second rebuild that the player keeps
			// running through. Hold position and let the scene settle; only recover
			// outright while genuinely logged in and unresolvable.
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				recoverPosition(local, playerTile);
			}
		}

		if (debugEnabled && now - lastDebugMs >= 1000)
		{
			lastDebugMs = now;
			log.info("FTRACE gap {}", debugLines().get(0).trim());
		}
	}

	private long lastDebugMs;

	/** Sub-unit remainders so fractional-cycle movement never loses distance. */
	private double moveCarryX;
	private double moveCarryY;
	private double turnCarry;

	/** The 8-direction heading of the current route step, for pose selection. */
	private int travelYaw;

	/**
	 * Whether the follower is face-locked on the player: facing holds on them
	 * while the body back- and side-steps along the route, and an idle follower
	 * turns in place to track them. POSED states only - Face-me's walk-around
	 * and a held pose's attentive stand. Plain following deliberately does NOT
	 * lock: a real follower walks facing its direction of travel, and locking
	 * it showed back-pedalling mid-follow whenever the route momentarily ran
	 * away from the player (reversals, corner detours).
	 */
	private boolean faceLocked()
	{
		// A thrall fighting something is face-locked on it, exactly like any
		// interacting entity. This matters for SPEED as much as facing: the
		// client's turn slowdown applies only when there is no face target,
		// so without this a thrall in combat crawled at half pace while its
		// yaw chased the enemy.
		if (thrallNpc != null)
		{
			return thrallTarget() != null;
		}
		if (goalTile != null)
		{
			return true;
		}
		return stayTile != null
			&& tile != null && tile.equals(stayTile) && route.isEmpty() && serverPath.isEmpty();
	}

	/**
	 * What the possessed thrall is fighting: its own interaction, or failing
	 * that the player's - ranged and magic thralls do not always hold a lock
	 * of their own, but they only ever attack the player's target.
	 */
	private net.runelite.api.Actor thrallTarget()
	{
		if (thrallNpc == null)
		{
			return null;
		}
		net.runelite.api.Actor target = thrallNpc.getInteracting();
		if (target == null)
		{
			Player local = client.getLocalPlayer();
			target = local == null ? null : local.getInteracting();
		}
		return target != null && target.getWorldLocation() != null ? target : null;
	}

	/**
	 * Where the follower looks while its feet move: the thrall's target in
	 * thrall mode, the stay anchor or player otherwise. Falls through to the
	 * travel heading when there is nothing to face, so it never moonwalks.
	 */
	private void faceAnchor()
	{
		if (thrallNpc != null)
		{
			if (!faceThrallTarget(false))
			{
				dstYaw = travelYaw;
			}
			return;
		}
		faceStayAnchor();
	}

	/**
	 * The client's routeMove pose bands, verbatim: the signed difference between
	 * the travel heading and the CURRENT yaw picks forward walk within a
	 * quarter-turn, the side-steps out to three-quarters either way, and the
	 * back-pedal beyond. Any missing pose falls back to walk (the client's -1
	 * fallback), and the run animation only ever replaces the FORWARD walk -
	 * strafing at run speed authentically shows the walking side-step.
	 */
	private int movePose(StanceLibrary.Stance stance)
	{
		int delta = (travelYaw - yaw) & 0x7ff;
		if (delta > 1024)
		{
			delta -= 2048;
		}

		// A 0 means the field is MISSING - a stance that was set by hand or
		// learned before the directional fields existed - not that the weapon
		// lacks the pose: every real player stance has directional animations.
		int pose;
		if (delta >= -256 && delta <= 256)
		{
			pose = stance.walk;
		}
		else if (delta >= 256 && delta < 768)
		{
			pose = stance.walkRight > 0 ? stance.walkRight
				: directionalFallback(stance, PlayerPose.SIDESTEP_RIGHT);
		}
		else if (delta >= -768 && delta <= -256)
		{
			pose = stance.walkLeft > 0 ? stance.walkLeft
				: directionalFallback(stance, PlayerPose.SIDESTEP_LEFT);
		}
		else
		{
			pose = stance.walkBack > 0 ? stance.walkBack
				: directionalFallback(stance, PlayerPose.TURN_180);
		}

		if (lastMoveSpeed >= 8 && pose == stance.walk && stance.run > 0)
		{
			pose = stance.run;
		}

		if (debugEnabled && pose != stance.walk && pose != stance.run && pose != activePose)
		{
			log.info("FTRACE pose directional {} (travel={} yaw={} delta={})",
				pose, travelYaw, yaw, delta);
		}
		return pose;
	}

	/**
	 * The side-step or back-pedal to use when a stance does not carry its own.
	 *
	 * <p>Which fallback is right depends entirely on the stance, and the
	 * observed library says so plainly. Of the 26 weapons using the default
	 * walk, ALL 26 also use the default side-steps, so the standard set is
	 * exactly right there. Of the 41 with a weapon-specific walk, only 5 use
	 * the standard side-step and NOT ONE uses the standard back-pedal - so
	 * borrowing it is wrong roughly nine times in ten, and it looks it: an
	 * empty-handed strafe while carrying a bulwark.
	 *
	 * <p>Those stances fall back to their own walk instead. It is the client's
	 * own behaviour for a pose that resolves to -1, and it keeps the weapon in
	 * hand, which is the part a player actually notices.
	 */
	private static int directionalFallback(StanceLibrary.Stance stance, int standardPose)
	{
		return stance.walk == PlayerPose.WALK ? standardPose : stance.walk;
	}

	/**
	 * The client's routeMove with its constants verbatim - speed 4 walking, 2
	 * while still turning, 6 with three or more tiles queued, 8 with four or
	 * more, doubled on run steps; snap when the next tile is over two tiles away;
	 * axis-independent stepping (diagonals genuinely faster); yaw turned by 32
	 * per cycle toward the 8-direction of travel - consumed continuously over a
	 * fractional number of cycles.
	 */
	private void moveContinuous(double cycles)
	{
		turnContinuous(cycles);

		// A tile transition mid-frame re-evaluates speed for the remainder, so a
		// bounded loop; guard against pathological budgets all the same.
		for (int guard = 0; guard < 8 && cycles > 0; guard++)
		{
			if (route.isEmpty())
			{
				lastMoveSpeed = 0;
				moveCarryX = 0;
				moveCarryY = 0;
				return;
			}

			WorldPoint next = route.peekFirst();
			long dstX = next.getX() * 128L + 64;
			long dstY = next.getY() * 128L + 64;

			if (Math.abs(dstX - fineWX) > 256 || Math.abs(dstY - fineWY) > 256)
			{
				fineWX = dstX;
				fineWY = dstY;
				tile = next;
				route.pollFirst();
				routeRun.pollFirst();
				continue;
			}

			// The heading of travel this step. With a face lock this is NOT the
			// facing: an interacting entity keeps facing its target while its
			// body back- or side-steps along the route - the client's routeMove
			// picks the pose from (travel - yaw) while entityFace holds the yaw
			// on the target. Without a lock, facing follows travel as before.
			travelYaw = eightDirYaw(fineWX, fineWY, dstX, dstY);
			if (faceLocked())
			{
				faceAnchor();
			}
			else
			{
				dstYaw = travelYaw;
			}

			int speed = 4;
			// The client's turn slowdown applies only WITHOUT a face target
			// (routeMove checks faceEntity === -1): a strafing entity moves at
			// full speed.
			if (yaw != dstYaw && !faceLocked())
			{
				speed = 2;
			}
			// The client's catch-up tiers (6 at depth 3, 8 at depth 4) are for
			// recovering render backlog after a stall. Our releases are capped, so
			// depth 3 here is ordinary observation jitter, not backlog - and the
			// FTRACE data showed the tiers letting the render sprint ahead of its
			// enqueue phase, visually collapsing the running gap onto the player
			// about once a second. Burst only for genuine backlog. (The verbatim
			// tiers were re-tried 2026-08-04 - tighter average gap, 1.24 vs a
			// slow drift to 3 on very long runs - but the speed surging read
			// wrong in game and the user preferred this feel; the drift only
			// shows on runs longer than ~15 seconds straight.)
			if (route.size() > 6)
			{
				speed = 8;
			}
			Boolean run = routeRun.peekFirst();
			if (run != null && run)
			{
				speed <<= 1;
			}
			lastMoveSpeed = speed;

			// Units each axis may cover this pass; axis-independent like the client.
			double budget = speed * cycles;
			double wantX = Math.abs(dstX - fineWX) + (fineWX == dstX ? 0 : -moveCarryX);
			double wantY = Math.abs(dstY - fineWY) + (fineWY == dstY ? 0 : -moveCarryY);
			double limiting = Math.max(wantX, wantY);

			if (budget >= limiting)
			{
				// Reaches the tile this pass; spend the fraction it costs and loop
				// for the remainder at the next tile's speed.
				fineWX = dstX;
				fineWY = dstY;
				moveCarryX = 0;
				moveCarryY = 0;
				tile = next;
				route.pollFirst();
				routeRun.pollFirst();
				cycles -= limiting / speed;
			}
			else
			{
				stepAxis(budget, dstX, true);
				stepAxis(budget, dstY, false);
				return;
			}
		}
	}

	/** Advances one axis by up to {@code budget} units, carrying sub-unit remainder. */
	private void stepAxis(double budget, long dst, boolean isX)
	{
		long fine = isX ? fineWX : fineWY;
		if (fine == dst)
		{
			return;
		}

		double carry = (isX ? moveCarryX : moveCarryY) + budget;
		long whole = (long) carry;
		carry -= whole;

		if (fine < dst)
		{
			fine = Math.min(fine + whole, dst);
		}
		else
		{
			fine = Math.max(fine - whole, dst);
		}

		if (isX)
		{
			fineWX = fine;
			moveCarryX = fine == dst ? 0 : carry;
		}
		else
		{
			fineWY = fine;
			moveCarryY = fine == dst ? 0 : carry;
		}
	}

	/** The client's 8-direction yaw for a movement step (0 south, 1024 north). */
	private static int eightDirYaw(long x, long y, long dstX, long dstY)
	{
		if (x < dstX)
		{
			return y < dstY ? 1280 : y > dstY ? 1792 : 1536;
		}
		if (x > dstX)
		{
			return y < dstY ? 768 : y > dstY ? 256 : 512;
		}
		return y < dstY ? 1024 : 0;
	}

	/** Turns yaw toward dstYaw at TURN_SPEED per cycle, fractionally accumulated. */
	private void turnContinuous(double cycles)
	{
		int remaining = (dstYaw - yaw) & 0x7ff;
		if (remaining == 0)
		{
			turnCarry = 0;
			return;
		}

		turnCarry += TURN_SPEED * cycles;
		int step = (int) turnCarry;
		if (step == 0)
		{
			return;
		}
		turnCarry -= step;

		if (remaining < step || remaining > 2048 - step)
		{
			yaw = dstYaw;
			turnCarry = 0;
		}
		else if (remaining > 1024)
		{
			yaw = (yaw - step) & 0x7ff;
		}
		else
		{
			yaw = (yaw + step) & 0x7ff;
		}
	}

	/**
	 * The next tile one step from {@code origin} towards {@code target},
	 * respecting collision the way the game's own movement does.
	 *
	 * <p>The corner rule is the important part: a diagonal step is legal only when
	 * the target AND both flanking cardinal tiles are open with no walls crossing
	 * any edge - which is why a player goes AROUND a fence corner while a raw
	 * diagonal cuts through it. When the diagonal is illegal, the cardinal with
	 * the greater remaining distance is tried first, then the other; if nothing
	 * is legal (boxed in, or no collision data), fall back to the raw step so the
	 * follower keeps making progress rather than freezing.
	 */
	private WorldPoint stepToward(WorldPoint origin, WorldPoint target)
	{
		if (origin == null || target == null || origin.getPlane() != target.getPlane())
		{
			return null;
		}

		int remX = target.getX() - origin.getX();
		int remY = target.getY() - origin.getY();
		int dx = Integer.signum(remX);
		int dy = Integer.signum(remY);
		if (dx == 0 && dy == 0)
		{
			return null;
		}

		if (dx != 0 && dy != 0)
		{
			if (canStep(origin, dx, dy))
			{
				return new WorldPoint(origin.getX() + dx, origin.getY() + dy, origin.getPlane());
			}

			// The corner is closed; go around it, longer axis first.
			int firstDx = Math.abs(remX) >= Math.abs(remY) ? dx : 0;
			int firstDy = firstDx == 0 ? dy : 0;
			if (canStep(origin, firstDx, firstDy))
			{
				return new WorldPoint(origin.getX() + firstDx, origin.getY() + firstDy, origin.getPlane());
			}
			if (canStep(origin, dx - firstDx, dy - firstDy))
			{
				return new WorldPoint(origin.getX() + dx - firstDx, origin.getY() + dy - firstDy, origin.getPlane());
			}
		}
		else if (canStep(origin, dx, dy))
		{
			return new WorldPoint(origin.getX() + dx, origin.getY() + dy, origin.getPlane());
		}

		// Boxed in or blind: raw step, the pre-collision behaviour.
		return new WorldPoint(origin.getX() + dx, origin.getY() + dy, origin.getPlane());
	}

	/** True if one step from {@code from} by (dx, dy) is legal under collision. */
	private boolean canStep(WorldPoint from, int dx, int dy)
	{
		if (dx == 0 && dy == 0)
		{
			return false;
		}

		if (dx != 0 && dy != 0)
		{
			// Diagonal: the corner wall bit on the source tile, the target itself,
			// and BOTH cardinal legs - from each end - must all be clear.
			Integer fromFlags = flagAt(from);
			if (fromFlags != null && (fromFlags & diagonalWallBit(dx, dy)) != 0)
			{
				return false;
			}
			WorldPoint xSide = new WorldPoint(from.getX() + dx, from.getY(), from.getPlane());
			WorldPoint ySide = new WorldPoint(from.getX(), from.getY() + dy, from.getPlane());
			return canStep(from, dx, 0) && canStep(from, 0, dy)
				&& canStep(xSide, 0, dy) && canStep(ySide, dx, 0);
		}

		Integer fromFlags = flagAt(from);
		WorldPoint to = new WorldPoint(from.getX() + dx, from.getY() + dy, from.getPlane());
		Integer toFlags = flagAt(to);
		if (fromFlags == null || toFlags == null)
		{
			// No collision data (instance edge, off-scene): assume open.
			return true;
		}

		if ((toFlags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}

		// Walls live on tile edges: the source's facing bit and the target's
		// opposite bit both describe the same edge.
		return (fromFlags & cardinalWallBit(dx, dy)) == 0
			&& (toFlags & cardinalWallBit(-dx, -dy)) == 0;
	}

	private static int cardinalWallBit(int dx, int dy)
	{
		if (dx > 0)
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		if (dx < 0)
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		return dy > 0
			? CollisionDataFlag.BLOCK_MOVEMENT_NORTH
			: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
	}

	private static int diagonalWallBit(int dx, int dy)
	{
		if (dx > 0)
		{
			return dy > 0
				? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		}
		return dy > 0
			? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST
			: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
	}

	/** True while the follower is hidden because an NPC shares its tile. */
	private boolean hiddenForNpcOverlap;

	/**
	 * Whether a real NPC occupies this tile. The possessed thrall is excluded:
	 * in thrall mode the follower deliberately stands exactly where it is, and
	 * it is hidden from the renderer anyway.
	 *
	 * <p>Multi-tile NPCs are compared on their south-west tile, as the client
	 * itself positions them; a giant's outer tiles do not count as occupied.
	 */
	private boolean npcOnTile(WorldPoint where)
	{
		if (where == null)
		{
			return false;
		}
		for (net.runelite.api.NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc == thrallNpc)
			{
				continue;
			}
			WorldPoint at = npc.getWorldLocation();
			if (at != null && at.equals(where))
			{
				return true;
			}
		}
		return false;
	}

	/** Collision flags for a tile, or null when no data covers it. */
	private Integer flagAt(WorldPoint tile)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, tile);
		if (localPoint == null)
		{
			return null;
		}

		CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
		if (maps == null || tile.getPlane() >= maps.length || maps[tile.getPlane()] == null)
		{
			return null;
		}

		int[][] flags = maps[tile.getPlane()].getFlags();
		int sx = localPoint.getSceneX();
		int sy = localPoint.getSceneY();
		if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length)
		{
			return null;
		}
		return flags[sx][sy];
	}

	/** Re-places the follower beside the player after a teleport or plane change. */
	private boolean snapBeside(WorldPoint playerTile)
	{
		WorldPoint beside = new WorldPoint(
			playerTile.getX() - 1, playerTile.getY(), playerTile.getPlane());

		// A forced relocation ends any pose - the tile it was holding is gone.
		stayTile = null;
		goalTile = null;
		route.clear();
		routeRun.clear();
		serverPath.clear();
		tile = beside;
		simTile = beside;
		fineWX = beside.getX() * 128L + 64;
		fineWY = beside.getY() * 128L + 64;
		lastMoveSpeed = 0;
		applyPose(stance().idle, false);
		return render();
	}

	/**
	 * Brings the follower back when its coordinates no longer resolve into the
	 * loaded scene. Falls back through: re-anchor beside the player in world
	 * space, then pin directly to the player's local coordinates - the last step
	 * always works while the player is being rendered.
	 */
	private void recoverPosition(Player local, WorldPoint playerTile)
	{
		if (playerTile != null)
		{
			if (snapBeside(playerTile))
			{
				return;
			}
		}

		LocalPoint playerLocal = local.getLocalLocation();
		if (playerLocal == null)
		{
			object.setActive(false);
			lastRenderedLocation = null;
			return;
		}

		route.clear();
		routeRun.clear();

		LocalPoint beside = new LocalPoint(
			playerLocal.getX() - TILE, playerLocal.getY(), client.getTopLevelWorldView());

		lastRenderedLocation = beside;
		object.setLocation(beside, client.getTopLevelWorldView().getPlane());
		object.setOrientation(yaw);
		if (!object.isActive())
		{
			object.setActive(true);
		}
	}

	/**
	 * Draws the follower at its fine world position, converted to scene
	 * coordinates here and nowhere else.
	 *
	 * @return false if the position could not be resolved into the loaded scene,
	 * in which case the caller must recover.
	 */
	private boolean render()
	{
		if (tile == null || fineWX < 0)
		{
			return false;
		}

		WorldPoint baseTile = new WorldPoint(
			(int) (fineWX >> 7), (int) (fineWY >> 7), tile.getPlane());
		LocalPoint base = LocalPoint.fromWorld(client, baseTile);
		if (base == null)
		{
			lastRenderedLocation = null;
			return false;
		}

		// A real NPC standing on the follower's tile: give way and go
		// invisible for as long as they share it. Two player-sized models on
		// one tile interpenetrate badly, and the follower is the guest here.
		// Position keeps updating underneath, so it reappears in the right
		// place the moment the tile clears.
		// Only once STOPPED on the shared tile: walking through an NPC is a
		// moment, and blinking out mid-stride draws more attention than the
		// overlap it hides.
		hiddenForNpcOverlap = isSettled() && npcOnTile(tile);
		if (hiddenForNpcOverlap)
		{
			if (object.isActive())
			{
				object.setActive(false);
			}
			return true;
		}

		// Re-insert into the rebuilt scene. Toggling off first is what actually
		// re-adds it; setActive(true) on an already-"active" object does nothing.
		if (needsReattach)
		{
			object.setActive(false);
			needsReattach = false;
		}
		if (!object.isActive())
		{
			object.setActive(true);
		}

		int lx = base.getX() + (int) (fineWX & 127) - 64;
		int ly = base.getY() + (int) (fineWY & 127) - 64;

		// Some poses move the body off the tile the object is placed on. The
		// prayer kneel leans far enough forward to leave the follower standing
		// on the edge of its own tile, which looks wrong beside a player
		// kneeling at an altar. Measured rather than assumed: standing reads a
		// centroid of z=-11 and the kneel reads z=-39, so the pose itself is
		// worth 28 units, near a quarter of a tile.
		//
		// Measured ONCE per pose and held. Reading it every frame made the
		// follower creep about: a centroid shifts slightly from frame to frame,
		// and feeding that straight back into the position turned a fixed
		// correction into constant motion.
		if (RECENTRED_POSES.contains(activePose))
		{
			if (recentrePose != activePose)
			{
				int[] centre = modelCentre();
				if (centre != null)
				{
					recentreForward = centre[1] - STANDING_CENTRE_Z;
					recentrePose = activePose;
				}
			}
		}
		else
		{
			recentrePose = -1;
			recentreForward = 0;
		}

		// The logical position stays the tile: facing, distance and the
		// graphics all reason about where the follower IS, not where its model
		// happened to be drawn.
		lastRenderedLocation = new LocalPoint(lx, ly, client.getTopLevelWorldView());

		int drawX = lx;
		int drawY = ly;
		if (recentreForward != 0)
		{
			// Model space is rotated by the object's orientation at draw time,
			// so the correction has to be rotated the same way. yaw is
			// atan2(dx, dy) in JAU, making (sin, cos) the axis it faces along.
			double angle = yaw * Math.PI / 1024.0;
			drawX -= (int) Math.round(recentreForward * Math.sin(angle));
			drawY -= (int) Math.round(recentreForward * Math.cos(angle));
		}
		object.setLocation(recentreForward == 0
			? lastRenderedLocation
			: new LocalPoint(drawX, drawY, client.getTopLevelWorldView()), tile.getPlane());

		// Height from the interpolated position: getTileHeight interpolates WITHIN
		// a tile, which is how a real player glides over slopes. Z is inverted, so
		// subtracting raises the model clear of the ground.
		int height = Perspective.getTileHeight(client, lastRenderedLocation, tile.getPlane());
		lastRenderedZ = height - verticalOffset;
		object.setZ(lastRenderedZ);

		object.setOrientation(yaw);

		// Attached graphics ride the follower on EVERY axis - fine position,
		// height and yaw - the way the client composites an actor's spotanim
		// into the actor's own model before drawing it at the actor's rotation.
		// A graphic parked where it spawned would sit askew the moment the
		// follower turned or stepped.
		for (RuneLiteObject graphic : activeGraphics)
		{
			graphic.setLocation(lastRenderedLocation, tile.getPlane());
			graphic.setZ(lastRenderedZ);
			graphic.setOrientation(yaw);
		}
		return true;
	}

	// ------------------------------------------------------------------ animation

	/*
	 * Pose animations are read live from the local player rather than hardcoded.
	 * A player's stand/walk/run set comes from the equipped weapon's definition,
	 * which the API doesn't expose per item id - but Actor exposes the resolved
	 * values, so mirroring the player guarantees the follower moves exactly like
	 * one. Side effect: the follower copies YOUR stance, not the stance implied by
	 * the gear it is wearing.
	 */

	private StanceLibrary.Stance stance()
	{
		return stanceLibrary.forWeapon(weaponItemId);
	}

	/**
	 * Forces a specific looping pose, overriding the stance library.
	 *
	 * <p>Diagnostic: if the pause tracks the cycle length of whatever animation is
	 * forced, it is animation-linked. If its period stays the same regardless, it is
	 * driven by something else entirely and the animation is a red herring.
	 */
	public void setPoseOverride(int animationId)
	{
		poseOverride = animationId;
		activePose = -1;
	}

	/**
	 * Whether a one-shot emote is still running. Lets a caller wait for one
	 * animation to finish before starting what follows it, which is how a
	 * summon leads into a channelled pose.
	 */
	public boolean isEmotePlaying()
	{
		return emotePlaying;
	}

	/** The weapon the follower is holding, for animation lookups. */
	public int getWeaponItemId()
	{
		return weaponItemId;
	}

	/** Tells the follower which weapon it is holding, so it picks that weapon's stances. */
	public void setWeapon(int itemId)
	{
		if (weaponItemId != itemId)
		{
			weaponItemId = itemId;
			activePose = -1; // force the pose to be re-applied from the new stance set
		}
	}

	private void applyPose(int animationId, boolean force)
	{
		// While a one-shot emote is playing it owns the main animation slot; the
		// pose resumes when the emote's onFinished fires.
		if (object == null || !animatable || emotePlaying)
		{
			return;
		}
		if (!force && activePose == animationId)
		{
			return;
		}

		Animation animation = client.loadAnimation(animationId);
		if (animation == null)
		{
			return;
		}

		// The pose is the object's MAIN animation. RuneLiteObject.tick() advances ONLY
		// the main controller - the pose slot is never auto-advanced, it exists to be
		// blended on top - so a controller in the pose slot alone can never animate.
		//
		// WrapLerpController interpolates across the loop boundary, which the client
		// itself never does; the trim workaround below stays available as a fallback.
		AnimationController controller = wrapLerp
			? new WrapLerpController(client, animation)
			: new AnimationController(client, animation);
		controller.setOnFinished(FollowerEntity::loopSafely);
		object.setAnimationController(controller);

		// Measure this animation's best wrap point the first time it is used. Still
		// done with the lerp active: if its safety guard trips mid-session, the trim
		// fallback needs a measured value ready rather than a blind default.
		if (animation.getFrameLengths() != null
			&& !measuredTrims.containsKey(animationId)
			&& !wrapTrims.containsKey(animationId))
		{
			measuredTrims.put(animationId,
				measureBestTrim(animation, animation.getFrameLengths().length));
		}

		controllerGeneration++;
		animationIds.add(animationId);
		activePose = animationId;
	}

	/**
	 * Fallback for {@link WrapLerpController}: wraps the pose early so the final
	 * frame is never displayed.
	 *
	 * <p>With animation smoothing on, the client interpolates every frame toward the
	 * NEXT one - except the last. RSSequenceDefinition sets {@code nextFrame = -1}
	 * past the end, so the final frame is held statically for its full duration and
	 * then jumps. That hold is the skip. Verified in the client source: the actor
	 * path has the SAME behaviour - real players hide it only because Jagex authors
	 * loops with the last pose close to the first.
	 *
	 * <p>The proper fix is WrapLerpController, which interpolates across the
	 * boundary. This trim remains for A/B comparison and as a safety net; skipping
	 * the final frame costs one frame of the cycle.
	 */
	private void wrapBeforeFinalFrame()
	{
		// The lerp controller renders the final frame correctly - trimming it too
		// would cut the very segment being interpolated. But if its safety guard has
		// switched it off, the trim must take back over or the raw hold shows.
		if (wrapLerp && object != null)
		{
			AnimationController active = object.getAnimationController();
			if (active instanceof WrapLerpController && !((WrapLerpController) active).isDisabled())
			{
				return;
			}
		}

		// Never touch a one-shot. Skipping the final frame of an emote means it never
		// finishes, onFinished never fires, and the follower is stuck replaying it
		// forever with the pose logic locked out behind emotePlaying.
		if (wrapTrim <= 0 || object == null || emotePlaying)
		{
			return;
		}

		AnimationController controller = object.getAnimationController();
		if (controller == null)
		{
			return;
		}

		Animation animation = controller.getAnimation();
		if (animation == null || animation.getFrameLengths() == null)
		{
			return;
		}

		// Only worth doing when smoothing is actually on. Without interpolation there
		// is no dead-end frame to avoid, so trimming one would cost a frame of the
		// cycle for nothing.
		if (!isSmoothingActive(animation))
		{
			return;
		}

		int frames = animation.getFrameLengths().length;
		int trim = trimFor(animation.getId(), frames);

		if (frames > trim + 1 && controller.getFrame() >= frames - trim)
		{
			controller.setFrame(0);
		}
	}

	/**
	 * Frames to trim from this particular animation.
	 *
	 * <p>A single figure cannot serve every pose: an idle may run to a dozen frames
	 * while a walk cycle is far shorter, so trimming the same count takes a much
	 * bigger bite out of the walk and visibly truncates it. Values are per animation,
	 * and anything not tuned falls back to the default capped at a fraction of the
	 * cycle, so a short animation is never gutted.
	 */
	private int trimFor(int animationId, int frames)
	{
		// A hand-tuned value always wins - measurement is a good default, not a veto.
		Integer tuned = wrapTrims.get(animationId);
		if (tuned != null)
		{
			return Math.min(tuned, Math.max(1, frames / 3));
		}

		Integer measured = measuredTrims.get(animationId);
		if (measured != null)
		{
			return measured;
		}

		return Math.min(wrapTrim, Math.max(1, frames / 6));
	}

	/**
	 * Works out how many frames to trim by measuring the model itself.
	 *
	 * <p>Trimming avoids the interpolation dead-end on the final frame, but costs a
	 * visible jump: the pose skips from wherever it stopped to frame 0. The size of
	 * that jump depends entirely on the animation - which is why one hand-tuned
	 * number suited the idle and truncated the walk.
	 *
	 * <p>So measure it. Pose the model at each candidate wrap point, compare its
	 * vertices against frame 0, and keep whichever lands closest. That is the wrap
	 * with the smallest discontinuity, derived per animation instead of guessed.
	 *
	 * <p>Runs once per animation and is cached; the cost is a handful of transforms.
	 */
	private int measureBestTrim(Animation animation, int frames)
	{
		if (baseModel == null || frames < 4)
		{
			return 1;
		}

		float[] atStart = poseVertices(animation, 0);
		if (atStart == null)
		{
			return 1;
		}

		int bestTrim = 1;
		double bestDistance = Double.MAX_VALUE;
		int maxTrim = Math.max(1, Math.min(4, frames / 3));

		for (int trim = 1; trim <= maxTrim; trim++)
		{
			float[] atWrap = poseVertices(animation, frames - trim);
			if (atWrap == null || atWrap.length != atStart.length)
			{
				continue;
			}

			double distance = 0;
			for (int i = 0; i < atStart.length; i++)
			{
				double d = atStart[i] - atWrap[i];
				distance += d * d;
			}

			if (distance < bestDistance)
			{
				bestDistance = distance;
				bestTrim = trim;
			}
		}

		log.debug("Animation {}: {} frames, measured trim {}", animation.getId(), frames, bestTrim);
		return bestTrim;
	}

	/**
	 * Vertex positions of the base model posed at one frame.
	 *
	 * <p>Copied immediately: applyTransformations returns a shared model that any
	 * later call invalidates, so two poses cannot be held at once.
	 */
	private float[] poseVertices(Animation animation, int frame)
	{
		net.runelite.api.Model posed = client.applyTransformations(baseModel, animation, frame, null, 0);
		if (posed == null || posed.getVerticesX() == null)
		{
			return null;
		}

		int count = posed.getVerticesCount();
		float[] out = new float[count * 3];
		float[] x = posed.getVerticesX();
		float[] y = posed.getVerticesY();
		float[] z = posed.getVerticesZ();

		for (int i = 0; i < count; i++)
		{
			out[i * 3] = x[i];
			out[i * 3 + 1] = y[i];
			out[i * 3 + 2] = z[i];
		}
		return out;
	}

	/** Interpolate across the loop boundary instead of trimming frames off it. */
	private boolean wrapLerp = true;

	public boolean isWrapLerp()
	{
		return wrapLerp;
	}

	/** Switches wrap handling and re-applies the current pose so it takes effect now. */
	public void setWrapLerp(boolean enabled)
	{
		if (wrapLerp == enabled)
		{
			return;
		}
		wrapLerp = enabled;
		if (activePose > 0)
		{
			applyPose(activePose, true);
		}
	}

	/** Sets the trim for one animation, so each pose can be tuned separately. */
	public void setWrapTrim(int animationId, int frames)
	{
		if (animationId <= 0)
		{
			return;
		}
		wrapTrims.put(animationId, Math.max(0, frames));
	}

	public java.util.Map<Integer, Integer> getWrapTrims()
	{
		return new java.util.LinkedHashMap<>(wrapTrims);
	}

	public java.util.Map<Integer, Integer> getMeasuredTrims()
	{
		return new java.util.LinkedHashMap<>(measuredTrims);
	}

	/** Drops manual overrides so measurement takes over again. */
	public void clearWrapTrims()
	{
		wrapTrims.clear();
		measuredTrims.clear();
	}

	/**
	 * Restores saved trims. Measured values are kept rather than recomputed - the
	 * measurement is deterministic, so reloading them just skips the work.
	 */
	public void restoreTrims(java.util.Map<Integer, Integer> manual,
		java.util.Map<Integer, Integer> measured)
	{
		if (manual != null)
		{
			wrapTrims.putAll(manual);
		}
		if (measured != null)
		{
			measuredTrims.putAll(measured);
		}
	}

	/**
	 * True when the client will interpolate this animation - i.e. the Animation
	 * Smoothing plugin is on and has not blocklisted it.
	 */
	public boolean isSmoothingActive(Animation animation)
	{
		java.util.function.IntPredicate filter = client.getAnimationInterpolationFilter();
		return animation != null && filter != null && filter.test(animation.getId());
	}

	public boolean isSmoothingActive()
	{
		return isSmoothingActive(getPoseAnimation());
	}

	/** Fallback trim for animations that haven't been tuned individually. */
	public void setDefaultWrapTrim(int frames)
	{
		wrapTrim = Math.max(0, frames);
	}

	public int getWrapTrim()
	{
		return wrapTrim;
	}

	/**
	 * Loops an animation without letting it kill itself.
	 *
	 * <p>The default handler steps back by the animation's frameStep, and if that
	 * still leaves the frame out of range the controller drops its animation and the
	 * model freezes. Falling back to frame 0 keeps a cycle with an unusable frameStep
	 * playing instead of silently stopping.
	 */
	private static void loopSafely(AnimationController controller)
	{
		controller.loop();

		Animation animation = controller.getAnimation();
		if (animation == null)
		{
			return;
		}

		int frames = animation.getFrameLengths() == null ? 0 : animation.getFrameLengths().length;
		if (controller.getFrame() < 0 || controller.getFrame() >= frames)
		{
			controller.setFrame(0);
		}
	}

	/**
	 * Poses corrected back onto their tile, and the standing reading they are
	 * corrected against.
	 *
	 * <p>Deliberately a short list rather than every animation. A walk cycle's
	 * centroid swings with the stride, so recentring it would fight the
	 * movement; these are held poses where the body simply sits somewhere the
	 * tile is not. Both were measured with {@code ::follower centre}: standing
	 * (pose 808) reads z=-11, the prayer kneel reads z=-39.
	 */
	private static final java.util.Set<Integer> RECENTRED_POSES =
		new java.util.HashSet<>(java.util.Arrays.asList(179, 645));

	private static final int STANDING_CENTRE_Z = -11;

	/** The pose the held correction was measured for, or -1 when none. */
	private int recentrePose = -1;

	/** The held correction, in model units along the follower's facing. */
	private int recentreForward;

	/**
	 * Where the animated model's mass actually sits relative to the tile it is
	 * placed on, in model units (128 to a tile).
	 *
	 * <p>An animation is free to move the body away from the origin - kneeling
	 * leans forward, and the prayer animation leans far enough to put the
	 * follower on the edge of its tile. The object is positioned by its tile,
	 * not by where the model ended up, so measuring the difference is the only
	 * way to know what to correct by.
	 *
	 * @return {x, z} mean vertex position, or null when there is no model
	 */
	public int[] modelCentre()
	{
		net.runelite.api.Model model = object == null ? null : object.getModel();
		if (model == null || model.getVerticesX() == null
			|| model.getVerticesX().length == 0)
		{
			return null;
		}
		float[] xs = model.getVerticesX();
		float[] zs = model.getVerticesZ();
		double sumX = 0;
		double sumZ = 0;
		for (int i = 0; i < xs.length; i++)
		{
			sumX += xs[i];
			sumZ += zs[i];
		}
		return new int[]{(int) Math.round(sumX / xs.length), (int) Math.round(sumZ / zs.length)};
	}

	/** True if the follower is currently using this animation. */
	public boolean usesAnimation(int animationId)
	{
		return animationIds.contains(animationId);
	}

	public int getActivePose()
	{
		return activePose;
	}

	/**
	 * Increments every time a new AnimationController is installed.
	 *
	 * <p>A restart and a clean wrap both look like "frame 11 then frame 0" in a frame
	 * trace, so the frame index alone cannot tell them apart. This can.
	 */
	public int getControllerGeneration()
	{
		return controllerGeneration;
	}

	/** True when the model currently handed to the renderer is the untransformed base. */
	public boolean isRenderingBaseModel()
	{
		if (object == null)
		{
			return false;
		}
		AnimationController controller = object.getAnimationController();
		return controller == null || controller.getAnimation() == null;
	}

	/** The pose controller's current frame, or -1 if nothing is playing. */
	public int getPoseFrame()
	{
		if (object == null)
		{
			return -1;
		}
		AnimationController controller = object.getAnimationController();
		return controller == null ? -1 : controller.getFrame();
	}

	/** The animation currently driving the follower, for diagnostics. */
	public Animation getPoseAnimation()
	{
		if (object == null)
		{
			return null;
		}
		AnimationController controller = object.getAnimationController();
		return controller == null ? null : controller.getAnimation();
	}

	/** Plays a one-shot animation, then resumes the walk/idle pose. */
	public void playAnimation(int animationId)
	{
		playAnimations(new int[]{animationId});
	}

	/**
	 * Plays animations back to back, then resumes the pose. Some sequences are
	 * authored as separate clips - a landing and the get-up that follows it, say -
	 * and playing only the first leaves the model stuck in its final frame.
	 */
	public void playAnimations(int[] animationIds)
	{
		playAnimations(animationIds, null);
	}

	/**
	 * As {@link #playAnimations(int[])}, with an optional spotanim paired to each
	 * stage - the home teleport is five clips, each opening with its own piece of
	 * the rune circle. A null entry skips that stage's graphic.
	 */
	public void playAnimations(int[] animationIds, SpotAnimRepository.Entry[] graphics)
	{
		if (object == null || !animatable || animationIds == null || animationIds.length == 0)
		{
			return;
		}
		playChain(animationIds, graphics, 0, emoteGeneration);
	}

	private void playChain(int[] ids, SpotAnimRepository.Entry[] graphics, int index, int generation)
	{
		// A cancelled emote's chain must not resurrect itself: the finished handler
		// for the clip that was interrupted still fires, and without this check it
		// would queue the next clip on top of the locomotion pose.
		if (generation != emoteGeneration)
		{
			return;
		}

		if (index >= ids.length)
		{
			emotePlaying = false;
			activePose = -1; // force the next frame to re-apply the locomotion pose
			consumeVanish();
			return;
		}

		Animation animation = client.loadAnimation(ids[index]);
		if (animation == null)
		{
			log.debug("Animation {} not in cache, skipping", ids[index]);
			playChain(ids, graphics, index + 1, generation);
			return;
		}

		AnimationController controller = new AnimationController(client, animation);
		controller.setOnFinished(finished -> playChain(ids, graphics, index + 1, generation));
		object.setAnimationController(controller);
		animationIds.add(ids[index]);
		emotePlaying = true;

		if (graphics != null && index < graphics.length && graphics[index] != null)
		{
			playSpotAnim(graphics[index]);
		}
	}

	/** Bumped to orphan an in-flight emote chain. */
	private int emoteGeneration;

	// ---- teleport vanish: disappear with the cast, reappear at the landing ----

	/** Armed by a teleport-mirror rule; consumed when the mirrored cast ends. */
	private boolean vanishAfterEmoteArmed;

	/** While true the follower is gone mid-teleport, awaiting the player's landing. */
	private boolean vanished;
	private long vanishedAtMs;
	private WorldPoint vanishPlayerTile;

	/**
	 * Arms the teleport exit: when the current emote or slaved chain finishes,
	 * the follower vanishes - as the player does at the end of their cast - and
	 * reappears beside them once they land. Without this the follower stood at
	 * the departure spot after its cast, then walked over (FTRACE 2026-08-04:
	 * a 9-tile teleport held sim=9 indefinitely).
	 */
	public void vanishAfterEmote()
	{
		vanishAfterEmoteArmed = true;
	}

	/** Called as a mirrored cast ends: go invisible and wait for the landing. */
	private void consumeVanish()
	{
		// The comedy death's exit shares the emote-end moment: the corpse
		// disappears on the death animation's last frame, not a beat after.
		if (hideAfterEmoteMs > 0)
		{
			hideUntilMs = System.currentTimeMillis() + hideAfterEmoteMs;
			hideAfterEmoteMs = 0;
			if (object != null)
			{
				object.setActive(false);
			}
		}

		if (!vanishAfterEmoteArmed)
		{
			return;
		}
		vanishAfterEmoteArmed = false;
		vanished = true;
		vanishedAtMs = System.currentTimeMillis();
		Player local = client.getLocalPlayer();
		vanishPlayerTile = local == null ? null : local.getWorldLocation();
		if (object != null)
		{
			object.setActive(false);
		}
	}

	// ---- slaved chain: stages advanced by the PLAYER's animation changes ----

	private int[] slavedChain;
	private SpotAnimRepository.Entry[] slavedGraphics;
	private int slavedIndex;

	/** Tick of the last slaved stage change, for the plugin's watchdog. */
	@Getter
	private int slavedAdvanceTick;

	/**
	 * Plays a stage chain SLAVED to the player's own sequence: each stage starts
	 * when the player's animation steps to ITS next stage, not when the
	 * follower's clip runs out. Teleport clips carry trailing hold frames longer
	 * than the server's stage schedule - the server cuts each short by setting
	 * the next - so chaining on clip end shows a one-to-two second freeze
	 * between stages that the real sequence never has. Slaving also paces the
	 * DEFAULT sequence correctly under a cosmetic override whose stages run on
	 * a different schedule, and ends the moment the player's sequence ends.
	 */
	public void startSlavedChain(int[] ids, SpotAnimRepository.Entry[] graphics)
	{
		if (object == null || !animatable || ids == null || ids.length == 0)
		{
			return;
		}
		cancelEmote();
		slavedChain = ids;
		slavedGraphics = graphics;
		slavedIndex = -1;
		advanceSlavedChain();
	}

	public boolean isSlavedChainActive()
	{
		return slavedChain != null;
	}

	/** Steps to the next stage; called when the player's animation changes. */
	public void advanceSlavedChain()
	{
		if (slavedChain == null)
		{
			return;
		}

		slavedIndex++;
		slavedAdvanceTick = client.getTickCount();
		if (slavedIndex >= slavedChain.length)
		{
			endSlavedChain();
			return;
		}

		Animation animation = client.loadAnimation(slavedChain[slavedIndex]);
		if (animation == null)
		{
			log.debug("Slaved chain animation {} not in cache, skipping", slavedChain[slavedIndex]);
			advanceSlavedChain();
			return;
		}

		// No onFinished chaining: if the clip outlasts the player's stage, the
		// player's next change cuts it; if it runs out first, the model holds
		// its final frame until the next stage - the same look the server's
		// schedule produces on a real player.
		AnimationController controller = new AnimationController(client, animation);
		object.setAnimationController(controller);
		animationIds.add(slavedChain[slavedIndex]);
		emotePlaying = true;

		if (slavedGraphics != null && slavedIndex < slavedGraphics.length
			&& slavedGraphics[slavedIndex] != null)
		{
			playSpotAnim(slavedGraphics[slavedIndex]);
		}
	}

	/** Ends the chain and hands the animation slot back to locomotion. */
	public void endSlavedChain()
	{
		slavedChain = null;
		slavedGraphics = null;
		// Before cancelEmote: a cancel DISARMS the vanish (an interrupted
		// teleport keeps the follower), while a completed chain consumes it.
		consumeVanish();
		cancelEmote();
	}

	/** Transient graphic objects currently showing, so despawn can clear them. */
	private final java.util.List<RuneLiteObject> activeGraphics = new java.util.ArrayList<>();

	/**
	 * Shows a spotanim - a "graphic": teleport swirl, spell impact, the home
	 * teleport's rune circle - at the follower's feet, the way the game shows one
	 * on a player. A transient RuneLiteObject carries the spotanim's model,
	 * recoloured, scaled, rotated and lit per its cache definition (the client
	 * lights spotanims with the actor constants shifted by the definition's
	 * ambient and contrast), and despawns itself when its one-shot animation
	 * ends.
	 */
	public void playSpotAnim(SpotAnimRepository.Entry fx)
	{
		playSpotAnim(fx, 0);
	}

	/**
	 * @param height how far above the ground the graphic sits, in unscaled model
	 *               units - the server sends one with every cast (a body-height
	 *               teleport swirl is not a feet-height rune circle), and the
	 *               client raises the spotanim model by it BEFORE resizing, so
	 *               the offset scales with the graphic. Same order here.
	 */
	public void playSpotAnim(SpotAnimRepository.Entry fx, int height)
	{
		if (fx == null || object == null || lastRenderedLocation == null)
		{
			return;
		}

		ModelData data = client.loadModelData(fx.modelId());
		if (data == null)
		{
			return;
		}

		Animation animation = client.loadAnimation(fx.animationId());
		if (animation == null)
		{
			// A frozen first frame with no animation to end it would linger forever.
			return;
		}

		// The client's own pipeline order: base model (rotation, colours), then
		// the height translate, then resize, then lighting.
		data = data.cloneColors();
		if (fx.cf != null && fx.cr != null)
		{
			for (int i = 0; i < Math.min(fx.cf.length, fx.cr.length); i++)
			{
				data.recolor(fx.cf[i], fx.cr[i]);
			}
		}
		if (fx.tf != null && fx.tr != null)
		{
			data = data.cloneTextures();
			for (int i = 0; i < Math.min(fx.tf.length, fx.tr.length); i++)
			{
				data.retexture(fx.tf[i], fx.tr[i]);
			}
		}
		for (int i = 0; i < fx.rotation(); i++)
		{
			data = data.cloneVertices().rotateY90Ccw();
		}
		if (height != 0)
		{
			data = data.cloneVertices().translate(0, -height, 0);
		}
		if (fx.resizeX() != 128 || fx.resizeY() != 128)
		{
			data = data.cloneVertices()
				.scale(fx.resizeX(), fx.resizeY(), fx.resizeX());
		}

		Model lit = data.light(64 + fx.ambient(), 850 + fx.contrast(), -30, -50, -30);

		RuneLiteObject graphic = client.createRuneLiteObject();
		// Same sorted mode as the follower: in the real client a spotanim is
		// composited INTO the actor model and drawn through the sorted path.
		graphic.setRenderMode(net.runelite.api.Renderable.RENDERMODE_SORTED_NO_DEPTH);
		graphic.setModel(lit);
		graphic.setLocation(lastRenderedLocation,
			tile != null ? tile.getPlane() : client.getTopLevelWorldView().getPlane());
		// Aligned with the follower from the very first frame; render() keeps it
		// aligned every frame after.
		graphic.setZ(lastRenderedZ);
		graphic.setOrientation(yaw);

		AnimationController controller = new AnimationController(client, animation);
		controller.setOnFinished(finished ->
		{
			graphic.setActive(false);
			activeGraphics.remove(graphic);
		});
		graphic.setAnimationController(controller);
		graphic.setActive(true);
		activeGraphics.add(graphic);
	}

	/**
	 * Drops any one-shot emote and hands the animation slot back to locomotion.
	 *
	 * <p>Called the moment the follower starts moving: an emote owns the main
	 * animation slot until it finishes, so without this the follower slides along
	 * behind you mid-dance instead of walking.
	 */
	public void cancelEmote()
	{
		// A slaved chain dies with its emote, or a later player animation change
		// would resurrect a stage on a follower that has moved on. An armed
		// teleport vanish dies too - an interrupted cast keeps the follower.
		slavedChain = null;
		slavedGraphics = null;
		vanishAfterEmoteArmed = false;
		// An interrupted death keeps the follower visible - same rule as the
		// interrupted teleport above.
		hideAfterEmoteMs = 0;

		if (!emotePlaying)
		{
			return;
		}
		emoteGeneration++;
		emotePlaying = false;
		activePose = -1;

		// A cancelled teleport should not leave its rune circle spinning at the
		// follower's feet as it walks off.
		for (RuneLiteObject graphic : activeGraphics)
		{
			graphic.setActive(false);
		}
		activeGraphics.clear();
	}

	/**
	 * Turns to look at the player. Sets the destination yaw only - the authentic
	 * turn rate then eases into it, the same as any other facing change.
	 */
	public void facePlayer()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getLocalLocation() == null || lastRenderedLocation == null)
		{
			return;
		}

		long dx = lastRenderedLocation.getX() - local.getLocalLocation().getX();
		long dy = lastRenderedLocation.getY() - local.getLocalLocation().getY();
		if (dx == 0 && dy == 0)
		{
			return;
		}
		dstYaw = (int) Math.round(Math.atan2(dx, dy) * 325.949) & 0x7ff;
	}
}
