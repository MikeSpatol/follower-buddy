package com.follower.follower;

import com.follower.Resources;
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
public class FollowerEntity implements CrossingWatch.Listener // public:cut agility,agility-all
// public:else public class FollowerEntity
{
	// public:cut dev
	/** Local (fine) units per tile. */
	private static final int TILE = Perspective.LOCAL_TILE_SIZE;

	// public:end dev
	/** One client cycle: the clock every routeMove constant is expressed in. */
	private static final int CYCLE_MS = 20;

	/** JAU per cycle an entity turns toward its destination yaw. */
	private static final int TURN_SPEED = 32;

	/** Beyond this distance the player must have teleported; snap rather than walk. */
	static final int SNAP_DISTANCE = 14;

	private final Client client;
	private final StanceLibrary stanceLibrary;

	private RuneLiteObject object;

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

	/** Catching up on a backlog: on past BURST_FROM tiles waiting, off at BURST_UNTIL. */
	private boolean burst;
	static final int BURST_FROM = 6;
	static final int BURST_UNTIL = 2;

	/** The player's followX/followZ - the tile they last stepped FROM. */
	private WorldPoint playerFollowTile;
	private WorldPoint lastSeenPlayerTile;
	private boolean playerRunning;

	// ----------------------------------------------------- follow distance

	/**
	 * How many steps behind the player the follower walks and stops. One is
	 * the game's own pet distance and the path model this was tuned on; two
	 * and three follow the same model with the follow tile taken further
	 * back along the player's own trail, so the follower still walks the
	 * tiles the player walked and simply stops further off when they stop.
	 * Play report: "awkwardly standing one tile behind me when we stop".
	 */
	static final int MAX_FOLLOW_DISTANCE = 3;
	private static final int TRAIL_KEPT = 8;
	private int followDistance = 1;
	private final Deque<WorldPoint> playerTrail = new ArrayDeque<>();

	public void setFollowDistance(int tiles)
	{
		followDistance = Math.max(1, Math.min(MAX_FOLLOW_DISTANCE, tiles));
	}

	/** The player stepped onto this tile; the trail remembers the last few. */
	void recordTrail(WorldPoint stepped)
	{
		WorldPoint last = playerTrail.peekLast();
		if (stepped == null || stepped.equals(last))
		{
			return;
		}
		if (last != null && last.getPlane() != stepped.getPlane())
		{
			playerTrail.clear();
		}
		playerTrail.addLast(stepped);
		while (playerTrail.size() > TRAIL_KEPT)
		{
			playerTrail.pollFirst();
		}
	}

	void clearTrailOfPlayer()
	{
		playerTrail.clear();
	}

	/**
	 * The tile so many of the player's steps back along their trail, the
	 * last entry being where they stand now; {@code behind} when the trail
	 * is too short to say, which is also the answer at distance one.
	 */
	WorldPoint behindBy(int steps, WorldPoint behind)
	{
		if (steps <= 1 || playerTrail.size() <= steps)
		{
			return behind;
		}
		return new java.util.ArrayList<>(playerTrail).get(playerTrail.size() - 1 - steps);
	}

	// ----------------------------------------------------- walking beside

	/**
	 * On a long run over open ground the follower draws level and walks at
	 * the player's shoulder instead of trailing a tile behind. Pets trail;
	 * friends walk alongside. The follow tile is the only thing that
	 * changes - the path model, the releases and the speeds are untouched -
	 * and it changes only after this many continuous steps, only while the
	 * flank tile is open ground the player could step to, and never while a
	 * stay pose or a goal has the feet. Two blocked flanks in a row drop it
	 * back behind, where it stays for a few steps before trying again, so a
	 * hedge-lined path does not have it hopping in and out.
	 */
	static final int BESIDE_AFTER_STEPS = 10;
	static final int BESIDE_RETRY_STEPS = 8;
	static final int BESIDE_MISSES = 2;
	/** A pause in the player's steps longer than this ends the run. */
	static final long BESIDE_PAUSE_MS = 1500;

	/** Set by the plugin: the setting is on and nothing else has the follower. */
	private boolean besideAllowed;
	private int stepStreak;
	private int besideMisses;
	private boolean beside;
	private int besideSide = 1;
	private long lastStepMs;

	public void setWalksBeside(boolean allowed)
	{
		besideAllowed = allowed;
		if (!allowed)
		{
			beside = false;
			stepStreak = 0;
		}
	}

	/** Whether the follower is currently walking at the player's shoulder. */
	public boolean isWalkingBeside()
	{
		return beside;
	}

	/**
	 * Where to follow to this observation: the tile behind, or the flank
	 * once the run has gone on long enough and the flank is open. Never a
	 * tile that is alight - the tile the player last stepped from is the
	 * one they just lit, for the whole of a firemaking line - nor one that
	 * furniture fills, since wherever the player stops, the follow tile is
	 * where the follower comes to rest.
	 *
	 * @param previous the player's tile before this step
	 * @param current their tile now
	 * @param behind the trailing follow tile already worked out
	 * @param steps how many server steps this observation covered
	 */
	WorldPoint followTileFor(WorldPoint previous, WorldPoint current, WorldPoint behind,
		int steps, long nowMs)
	{
		if (lastStepMs > 0 && nowMs - lastStepMs > BESIDE_PAUSE_MS)
		{
			// They stopped for a moment: the run starts again from nothing.
			stepStreak = 0;
			beside = false;
		}
		lastStepMs = nowMs;
		stepStreak += Math.max(1, steps);
		if (!besideAllowed || stayTile != null || goalTile != null)
		{
			beside = false;
			return coolFollowTile(behind, true);
		}
		if (!beside)
		{
			if (stepStreak < BESIDE_AFTER_STEPS)
			{
				return coolFollowTile(behind, true);
			}
			beside = true;
			besideMisses = 0;
			besideSide = sideOf(previous, current, tile);
		}
		WorldPoint flank = flankTile(previous, current, besideSide);
		// A booth the map leaves open is open ground to the map, and a player
		// walking along the row and stopping at it would leave the follower
		// standing in the next. Asked last, being the one that reads the scene.
		if (flank != null && goodGround(flank)
			&& canStep(current, flank.getX() - current.getX(), flank.getY() - current.getY())
			&& !furnished(flank))
		{
			besideMisses = 0;
			return flank;
		}
		if (++besideMisses >= BESIDE_MISSES)
		{
			// Hedged in: back behind, and a few steps before it tries again.
			beside = false;
			stepStreak = BESIDE_AFTER_STEPS - BESIDE_RETRY_STEPS;
		}
		return coolFollowTile(behind, true);
	}

	/**
	 * The follow tile, or the nearest tile beside it that is not alight.
	 *
	 * <p>The follow tile is the tile the player last stepped from, and on
	 * a firemaking line that tile is on fire - every single time, for the
	 * whole session. Left as it was, the follower was given a destination
	 * it would never walk onto and the pathing quietly settled for
	 * whatever it could reach, which made arriving, being stranded and
	 * being settled all mean something other than what they say.
	 *
	 * <p>Never the player's own tile: that is the one tile every route is
	 * told to walk around, so choosing it is choosing nowhere.
	 *
	 * <p>Nor, where it is chosen, a tile furniture fills: a booth the map
	 * calls open floor, which the player may have stepped out of. The tile
	 * beside it then, out of the furniture too, or the tile itself where
	 * there is none, in a field of crops, rather than nowhere to go.
	 *
	 * @param chosen whether this is the step the tile is chosen on, which
	 * also asks after furniture. The frame asks again only about fire, which
	 * goes up after the tile was chosen; furniture was there already, and
	 * asking every frame would read the scene every frame.
	 */
	private WorldPoint coolFollowTile(WorldPoint wanted, boolean chosen)
	{
		if (wanted == null || (!hot(wanted) && !(chosen && furnished(wanted))))
		{
			return wanted;
		}
		WorldPoint player = playerTile(client);
		WorldPoint from = simTile != null ? simTile : tile;
		WorldPoint best = null;
		int nearest = Integer.MAX_VALUE;
		for (int[] step : AROUND)
		{
			WorldPoint beside = wanted.dx(step[0]).dy(step[1]);
			if (beside.equals(player) || !goodGround(beside)
				|| (chosen && furnished(beside)))
			{
				continue;
			}
			int away = from == null ? 0 : beside.distanceTo(from);
			// Coordinates break ties so the choice cannot jitter between
			// frames on the iteration order.
			if (best == null || away < nearest
				|| (away == nearest && comesFirst(beside, best)))
			{
				nearest = away;
				best = beside;
			}
		}
		return best == null ? wanted : best;
	}

	/**
	 * The tile at the player's shoulder: one step to the side of the line
	 * they are walking, on the given side (1 left, -1 right of travel).
	 */
	static WorldPoint flankTile(WorldPoint previous, WorldPoint current, int side)
	{
		if (previous == null || current == null)
		{
			return null;
		}
		int vx = Integer.signum(current.getX() - previous.getX());
		int vy = Integer.signum(current.getY() - previous.getY());
		if (vx == 0 && vy == 0)
		{
			return null;
		}
		return current.dx(-vy * side).dy(vx * side);
	}

	/** Which side of the line of travel the follower is already on; left when it is on the line. */
	static int sideOf(WorldPoint previous, WorldPoint current, WorldPoint follower)
	{
		if (previous == null || current == null || follower == null)
		{
			return 1;
		}
		int vx = Integer.signum(current.getX() - previous.getX());
		int vy = Integer.signum(current.getY() - previous.getY());
		int cross = vx * (follower.getY() - current.getY()) - vy * (follower.getX() - current.getX());
		return cross < 0 ? -1 : 1;
	}

	private int activePose = -1;
	private boolean animatable;
	private boolean emotePlaying;
	private boolean needsReattach;
	private int controllerGeneration; // public:cut dev
	private int poseOverride;
	private int wrapTrim = 1; // public:cut dev

	/** Whether the last frame had it on the move, which a forced pose gives way to. */
	private boolean onTheMove;

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

	/**
	 * Whether the body is in the scene now. Spawned is not enough: it goes
	 * out of sight while an NPC stands on its tile, through a teleport and
	 * the comedy death, and its overhead line must go with it.
	 */
	public boolean isShown()
	{
		return spawned && object != null && object.isActive();
	}

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

	/**
	 * @param arrival the spotanim to swirl in with, or null for none, and how
	 * far to lift it. Passed rather than held, because there are three ways to
	 * spawn and a field only one of them set is a field two of them forget.
	 */
	public void spawn(ComposedAppearance appearance, WorldPoint at,
		SpotAnimRepository.Entry arrival, int arrivalHeight)
	{
		spawn(appearance, at, arrival, arrivalHeight, false);
	}

	/**
	 * @param post whether {@code at} is a tile the follower was left waiting
	 * on, which it comes back to rather than landing beside the player; see
	 * {@link #arrivalTile}
	 */
	public void spawn(ComposedAppearance appearance, WorldPoint at,
		SpotAnimRepository.Entry arrival, int arrivalHeight, boolean post)
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

		animatable = true;
		object.setModel(appearance.getModel());
		// Keep the unposed model: measuring wrap points needs to pose it at arbitrary
		// frames, and getModel() returns the already-animated one.
		baseModel = appearance.getModel();
		// Measured trims are NOT cleared here. The best wrap point comes from how the
		// animation's frames relate to one another, which barely changes with outfit -
		// so re-measuring on every gear change would be wasted work.
		object.setDrawFrontTilesFirst(true);

		// Arriving is a snap like any other: whatever the caller worked out,
		// it does not get to put the follower inside a wall.
		WorldPoint anchor = playerTile(client);
		if (anchor != null)
		{
			at = arrivalTile(at, anchor, post);
		}

		putAt(at);
		playerFollowTile = null;
		lastSeenPlayerTile = null;
		moveCarryX = 0;
		moveCarryY = 0;
		turnCarry = 0;
		tickCarryMs = 0;
		lastFrameMs = System.currentTimeMillis();

		applyPose(stance().idle, true);
		render();
		spawned = true;

		// A new body starts clear of whatever the last one was in the middle
		// of. An emote cut off by a logout, a hop or a hide stayed "playing",
		// which refused this body its idle until its first step, and a
		// teleport's vanish or the comedy death's hide would take it straight
		// back out of sight. Here and not in despawn, so whatever waited on
		// that emote is let go with a follower out to rebuild.
		cancelEmote();
		vanished = false;
		hideUntilMs = 0;
		waitingEmote = -1;

		if (spawnAnimationIds != null && spawnAnimationIds.length > 0)
		{
			// Paired through the chain rather than played after it, so the
			// swirl begins in the same statement that starts the clip. Calling
			// it afterwards left it to whatever the caller did next.
			playAnimations(spawnAnimationIds,
				arrival == null ? null : new SpotAnimRepository.Entry[]{arrival},
				arrivalHeight);
		}

		// Attached LAST, after its own arrival graphic, for the reason the
		// thrall circle already documents: same-tile RuneLiteObjects draw in
		// attach order. Activating the follower first put it in the scene a
		// frame before the swirl that was supposed to bring it, so the effect
		// arrived visibly behind the animation it is exactly the length of -
		// 715 and 7165 are both twelve frames and 1.56s, authored to run
		// together. Nothing else spawns an object and animates it on the same
		// tick, which is why nothing else showed this.
		object.setActive(true);
	}

	/**
	 * Animation(s) played on spawn so the follower doesn't just pop in, and the
	 * spotanim that goes with each.
	 *
	 * <p>The animation alone was a teleport arrival with nothing arriving: the
	 * follower performed the landing and the air around it stayed empty, which
	 * reads as the effect having failed rather than as a choice. A real
	 * spellbook teleport is an animation AND a graphic, and the paired chain
	 * this hands to {@link #playAnimations(int[], SpotAnimRepository.Entry[])}
	 * was built for exactly that - it just had nobody passing it graphics.
	 *
	 * <p>The graphic is NOT held here. It is played by the caller the tick the
	 * spawn happens, resolved fresh from the repository - the same way every
	 * other teleport in the plugin does it, and for the reason this one
	 * originally did not work: an Entry cached on a field is only as good as
	 * the one path that set it, and there are three ways to spawn.
	 */
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
		showGraphics(false);
		activeGraphics.clear();
		pendingGraphics.clear();
		dropPose();
		releaseAfterCast = false;
		clearTrail();
		lastSeenPlayerTile = null;
		playerFollowTile = null;
		dropCrossings(); // public:cut agility,agility-all
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
		animatable = true;
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
		else if (playing == null || activePose >= 0) // public:cut agility,agility-all
		// public:else else
		{
			// public:cut agility,agility-all
			// Not under a crossing's clip, which keeps running on the new
			// model: a stand put up here would show for a frame.
			// public:end agility,agility-all
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

		// Walkable is not enough: a tile on the far side of a wall is "walkable"
		// but unreachable, and a follower spawned there walks THROUGH the wall to
		// reach you. Candidates must be genuinely step-reachable from the player.
		java.util.Set<WorldPoint> reachable = reachableFrom(playerTile, distance + 1);

		// Try directly behind first, then progressively wider angles either side, so
		// the follower ends up as close to "behind" as the terrain allows.
		int[] offsets = {0, 256, -256, 512, -512, 768, -768, 1024};
		WorldPoint furnishedOnly = null;
		for (int offset : offsets)
		{
			double a = radians + (offset / 2048.0) * 2.0 * Math.PI;
			double bx = -Math.sin(a);
			double by = -Math.cos(a);

			for (int d = distance; d >= 1; d--)
			{
				WorldPoint candidate = playerTile.dx(-(int) Math.round(bx * d)).dy(-(int) Math.round(by * d));

				if (!candidate.equals(playerTile) && reachable.contains(candidate))
				{
					// Nor inside furniture the map leaves open; see furnished.
					if (!furnished(candidate))
					{
						return candidate;
					}
					// Unless that is all there is, as landingTile allows: null
					// here spawns it on the player's own tile.
					if (furnishedOnly == null)
					{
						furnishedOnly = candidate;
					}
				}
			}
		}

		return furnishedOnly;
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
				for (int[] step : AROUND)
				{
					WorldPoint next = at.dx(step[0]).dy(step[1]);
					if (!seen.contains(next) && canStep(at, step[0], step[1]))
					{
						seen.add(next);
						frontier.add(next);
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
	 * @return false when no legal, reachable tile exists in front, or
	 * furniture fills it - the caller should have the follower say so rather
	 * than shoving into a wall
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

		WorldPoint front = playerTile.dx(dx).dy(dy);

		if (front.equals(tile))
		{
			facePlayer();
			return true;
		}
		// Nor into furniture the map leaves open. In front of a player at a
		// bank is the booth they face, so a look over the shoulder while
		// they work at it, or the welfare check while they stand there,
		// would walk straight in.
		if (!canStep(playerTile, dx, dy)
			|| !reachableFrom(playerTile, 3).contains(front)
			|| furnished(front))
		{
			return false;
		}

		// Face-me replaces a stay pose: the follower is being called over.
		// The pose's face goes with it, or it would arrive facing whatever
		// the stay was looking at.
		dropPose();
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
	public static final int STAY_LEASH = 40;

	public boolean isStaying()
	{
		return stayTile != null;
	}

	/** Where the stay pose holds it, or null: closest approach may stop short of the ask. */
	public WorldPoint getStayTile()
	{
		return stayTile;
	}

	// public:cut skilling
	/** Whether it has finished turning to the way it means to face. */
	public boolean isTurned()
	{
		return yaw == dstYaw;
	}

	// public:end skilling
	/**
	 * True when the follower has fully stopped: no queued route, and its fine
	 * position rests at its tile's centre. An emote started before this is
	 * cancelled by the very next movement frame.
	 */
	public boolean isSettled()
	{
		return route.isEmpty() && serverPath.isEmpty() && tile != null && !offCentre();
	}

	/** Whether the fine position is off its tile's centre: mid-step. */
	private boolean offCentre()
	{
		return fineWX != tile.getX() * 128L + 64 || fineWY != tile.getY() * 128L + 64;
	}

	/** The fine position onto this tile's centre. */
	private void centreOn(WorldPoint at)
	{
		fineWX = at.getX() * 128L + 64;
		fineWY = at.getY() * 128L + 64;
	}

	/** Put down on a tile outright, with nothing queued. */
	private void putAt(WorldPoint at)
	{
		clearTrail();
		tile = at;
		simTile = at;
		centreOn(at);
	}

	// public:cut dev
	/** The model the object is rendering RIGHT NOW (post-animation), for diagnostics. */
	public net.runelite.api.Model getRenderModel()
	{
		return object == null ? null : object.getModel();
	}

	// public:end dev
	/**
	 * A release asked for mid-cast, carried out when the cast ends: a teleport's
	 * hold is only a stay, and anything let go of mid-cast (a flourish running
	 * out, a work session or an errand ending) would walk the follower off its
	 * cast and leave it behind at the departure point.
	 */
	private boolean releaseAfterCast;

	/** Releases a stay pose; following resumes on its own. */
	public void resumeFollowing()
	{
		if (slavedChain != null || vanishAfterEmoteArmed)
		{
			releaseAfterCast = true;
			return;
		}
		releaseAfterCast = false;
		stayTile = null;
		stayFaceTile = null;
		stayFaceMax = null; // public:cut skilling
		insistedTile = null; // public:cut errands
	}

	/** The tick the scene was last kept, so the frame loop acts once a tick: NPC tiles move on ticks. */
	private int sceneTick = -1;
	private WorldPoint scenePlayer;
	private int scenePlayerStill;
	/** Let go to walk over to the player after the scene moved them; held again once there. */
	private boolean catchingUp;

	/**
	 * A cutscene: where it stands is its mark. It gives way to an actor that
	 * comes onto it, and when the scene has put the player down more than
	 * three tiles off it walks over once, after they stop, rather than
	 * trailing a scripted walk through the cast.
	 */
	private void keepToTheScene(WorldPoint player)
	{
		int now = client.getTickCount();
		if (now == sceneTick)
		{
			return;
		}
		sceneTick = now;
		scenePlayerStill = player.equals(scenePlayer) ? scenePlayerStill + 1 : 0;
		scenePlayer = player;

		if (stayTile == null)
		{
			// Held where it stands. A step under way ends on its tile; a walk
			// over to the player is finished first.
			if (!catchingUp || isSettled())
			{
				catchingUp = false;
				stayHere();
			}
			return;
		}
		if (!isSettled())
		{
			return;
		}
		java.util.Map<WorldPoint, net.runelite.api.NPC> taken = npcFootprints();
		if (taken.containsKey(tile) || tile.equals(player))
		{
			giveWay(taken, player);
		}
		else if (scenePlayerStill >= 2 && tile.distanceTo(player) > 3)
		{
			resumeFollowing();
			catchingUp = true;
		}
	}

	/**
	 * One tile across the way of the actor on the follower's tile: never where
	 * it is heading, never back into it, never onto anybody. Boxed in, it keeps
	 * the tile, and the render hide covers an actor that stops there.
	 */
	private void giveWay(java.util.Map<WorldPoint, net.runelite.api.NPC> taken, WorldPoint player)
	{
		net.runelite.api.NPC actor = taken.get(tile);
		int fx = 0;
		int fy = 0;
		if (actor != null)
		{
			// The way it faces is the way it is walking (as standOverTheShoulder reads it).
			double radians = actor.getOrientation() / 2048.0 * 2.0 * Math.PI;
			fx = (int) Math.round(-Math.sin(radians));
			fy = (int) Math.round(-Math.cos(radians));
		}
		int[][] steps = {{-fy, fx}, {fy, -fx},
			{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
		for (int[] step : steps)
		{
			if ((step[0] == 0 && step[1] == 0) || (step[0] == fx && step[1] == fy)
				|| (step[0] == -fx && step[1] == -fy && actor != null))
			{
				continue;
			}
			WorldPoint off = tile.dx(step[0]).dy(step[1]);
			if (!off.equals(player) && !taken.containsKey(off) && goodGround(off)
				&& canStep(tile, step[0], step[1]) && stayAt(off))
			{
				return;
			}
		}
	}

	/**
	 * While holding a stay pose, face this tile instead of the player - an
	 * errand faces its business: the booth, the altar, the cat. Cleared with
	 * the pose.
	 */
	private WorldPoint stayFaceTile;

	// public:cut skilling
	/** The far corner when the face is a footprint rather than a tile; null for a tile. */
	private WorldPoint stayFaceMax;

	// public:end skilling
	/** Lets go of a stay, the way it faced, and a goal. */
	private void dropPose()
	{
		stayTile = null;
		stayFaceTile = null;
		stayFaceMax = null; // public:cut skilling
		goalTile = null;
	}

	public void setStayFaceTile(WorldPoint tile)
	{
		stayFaceTile = tile;
		stayFaceMax = null; // public:cut skilling
	}

	// public:cut skilling
	/**
	 * Faces the exact middle of a footprint, as the player does when they
	 * work it. The middle of a two-by-two tree is the corner its four tiles
	 * share, not any one tile's centre: facing its south-west tile instead
	 * swung the axe up to 27 degrees wide of the trunk.
	 */
	public void setStayFaceArea(WorldPoint min, WorldPoint max)
	{
		stayFaceTile = min;
		stayFaceMax = max;
	}

	// public:end skilling
	/**
	 * The stay pose's idle facing: the face tile when set, the player
	 * otherwise. Counting you in for a sketch it is you, even from a drift
	 * over to something that it would otherwise go on looking at.
	 */
	private void faceStayAnchor()
	{
		if (stayFaceTile != null && tile != null && !facingThePlayer) // public:cut chronicle
		// public:else if (stayFaceTile != null && tile != null)
		{
			// In half tiles, so the middle of an even footprint is exact.
			WorldPoint far = stayFaceMax == null ? stayFaceTile : stayFaceMax; // public:cut skilling
			// public:else WorldPoint far = stayFaceTile;
			int dx = 2 * tile.getX() - stayFaceTile.getX() - far.getX();
			int dy = 2 * tile.getY() - stayFaceTile.getY() - far.getY();
			if (dx != 0 || dy != 0)
			{
				dstYaw = yawOf(dx, dy);
			}
			return;
		}
		facePlayer();
	}

	/**
	 * Where a held follower looks. A cutscene: the same way the player is
	 * turned, animating or not. Play test, 15 Oct (Children of the Sun): the
	 * hold faced the player on its own, ahead of the frame's turn, so the
	 * scene's look never got as far as the yaw.
	 */
	private void faceTheHold(Player local)
	{
		if (watchingWithThePlayer)
		{
			dstYaw = local.getOrientation() & 0x7ff;
			return;
		}
		faceStayAnchor();
	}

	/** Until when the follower stays invisible (the comedy death's empty stage). */
	private long hideUntilMs;

	/** Armed by the comedy death: at the emote's final frame, go dark this long. */
	private int hideAfterEmoteMs;

	// public:cut chronicle
	/** Out of the frames drawn while set: the sketch's frame, which the scribe draws and is not in. */
	@lombok.Setter
	private boolean outOfPicture;

	/** The body is out for the sketch's frame, not lost: it comes back where it stood. */
	private boolean pictureHidden;

	// public:end chronicle
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
		strandedTicks = 0;
		if (spawned)
		{
			snapHome("sent home"); // public:cut dev
			// public:else snapHome();
		}
	}

	/** Snaps beside the player, when there is a player to snap to; false when not. */
	private boolean snapHome(String why) // public:cut dev
	// public:else private boolean snapHome()
	{
		WorldPoint player = playerTile(client);
		if (player != null)
		{
			snapBeside(player, why); // public:cut dev
			// public:else snapBeside(player);
		}
		return player != null;
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
		strandedTicks = 0;
		thrallNpc = npc;
		lastThrallTile = null;
		dropCrossings(); // public:cut agility,agility-all
		dropPose();
		clearTrail();
		cancelEmote();
	}

	/** Leaves thrall mode and reappears beside the player, following as before. */
	public void releaseNpcSlave()
	{
		thrallNpc = null;
		lastThrallTile = null;
		clearThrallCircle();
		snapHome("thrall released"); // public:cut dev
		// public:else snapHome();
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
		if (!spawned || tile == null)
		{
			return;
		}

		// Whatever the possession left behind belongs to the thrall, not to
		// the follower. Its waypoints are the NPC's raw server tiles, which
		// were never collision-checked because they never needed to be - a
		// thrall walks where a thrall may - and walking them now, as itself,
		// is how the follower ends up somewhere it cannot stand.
		route.clear();
		routeRun.clear();

		// The tile underfoot is the thrall's for the same reason: behind
		// counters, over water, inside scenery. Holding the spot is right
		// when the spot is a real one. When it is not, the possession ended
		// somewhere the follower has no business standing, so it goes home.
		if (!standable(tile) && snapHome("thrall left it somewhere unstandable")) // public:cut dev
		// public:else if (!standable(tile) && snapHome())
		{
			return;
		}

		// Possession moved `tile` every tick and never touched simTile, which
		// is what the pathfinder plans from - left stale, the next route would
		// be worked out from wherever the follower was when it was possessed.
		simTile = tile;
		stayHere();
	}

	/**
	 * Whether the follower is standing where a player could not: on scenery,
	 * a bank booth, a deck facility that arrived after it landed.
	 *
	 * <p>Play test, 7 Oct: it was found standing in a bank booth, twice. A
	 * placement made while a new area is still loading reads a map that does
	 * not describe it yet, so the tile beside the player can read open when it
	 * is a booth, and nothing on land ever looked again. Every step of its own
	 * is checked as it is planned, so this only ever catches a placement. Not
	 * from a map still loading, and never off the player's own tile, which is
	 * where a boxed-in follower is put on purpose.
	 */
	boolean blockedUnderfoot(WorldPoint standing, Player local)
	{
		return standing != null && local != null
			&& loggedIn()
			&& !standing.equals(local.getWorldLocation())
			&& !standable(standing);
	}

	/**
	 * Tiles the map calls walkable that the follower walks round rather
	 * than onto: a lit fire, chiefly. Supplied by the plugin from the
	 * objects the client announces; nothing is hot until something says
	 * so, which is why the tests and the thrall path are untouched.
	 */
	private java.util.function.Predicate<WorldPoint> hotGround = at -> false;

	public void setHotGround(java.util.function.Predicate<WorldPoint> hot)
	{
		hotGround = hot == null ? at -> false : hot;
	}

	// public:cut errands
	/**
	 * The one tile the follower has been told to walk onto anyway. The
	 * fire gag walks INTO the flames on purpose and is the only thing in
	 * the plugin that does, so it is the only exception the feet keep.
	 */
	private WorldPoint insistedTile;

	// public:end errands
	// public:cut dev,logs
	/** Whether the last frame found the follower standing in a fire. */
	private boolean inAFire;

	// public:end dev,logs
	// public:cut errands
	/**
	 * Walk onto it even so. Goes through {@link #stayAt} rather than round
	 * it, so there is still one way to give the follower a spot.
	 */
	public boolean insistOn(WorldPoint target)
	{
		insistedTile = target;
		boolean went = stayAt(target);
		if (!went)
		{
			insistedTile = null;
		}
		return went;
	}

	// public:end errands
	/** Whether the follower walks round this tile rather than onto it. */
	boolean hot(WorldPoint at)
	{
		return at != null && !at.equals(insistedTile) && hotGround.test(at); // public:cut errands
		// public:else return at != null && hotGround.test(at);
	}

	/**
	 * Whether the follower would CHOOSE to stand here: the map allows it,
	 * and it is not a tile a person steps round. Every spot the follower is
	 * given should be asked about this rather than about collision alone.
	 */
	public boolean goodGround(WorldPoint at)
	{
		return standable(at) && !hot(at);
	}

	// public:cut skilling
	/**
	 * Whether a wall or fence runs along this tile's edge on the given side:
	 * a tree behind a fence is square on to the tile across it, and still
	 * not worked from there.
	 */
	boolean walledToward(WorldPoint at, int dx, int dy)
	{
		Integer flags = flagAt(at);
		return flags != null && (flags & cardinalWallBit(dx, dy)) != 0;
	}

	// public:end skilling
	/** Whether the follower could stand on this tile at all. */
	boolean standable(WorldPoint at)
	{
		Integer flags = flagAt(at);
		// No data - an instance edge, an unloaded scene - reads as open, the
		// same reading canStep takes, so this never freezes it out of a place
		// the game simply has not described.
		return flags == null || (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	/**
	 * Furniture is a model that passes through someone standing on its tile
	 * between knee and chest, in model units above the floor. Measured from
	 * the cache: a booth runs 0 to 224, counters 103 to 184 tall, a chair
	 * 144, and a cupboard on the wall starts at 128. Daisies (42), marks on
	 * the floor (0 to 18) and anything hung overhead miss the band.
	 */
	private static final int KNEE_HEIGHT = 64;
	private static final int CHEST_HEIGHT = 128;

	/**
	 * Whether the object is a crop: anything with a Pick option, as it is now
	 * if it changes with a varbit. Review, 8 Oct: a Send into a wheat field
	 * was refused as if the field were a table.
	 */
	private boolean isCrop(int objectId)
	{
		net.runelite.api.ObjectComposition composition = client.getObjectDefinition(objectId);
		if (composition != null && composition.getImpostorIds() != null)
		{
			composition = composition.getImpostor();
		}
		String[] actions = composition == null ? null : composition.getActions();
		return actions != null && java.util.Arrays.stream(actions).anyMatch("Pick"::equalsIgnoreCase);
	}

	/**
	 * Whether furniture fills this tile though the map may call it open.
	 *
	 * <p>Play test, 8 Oct: logging in at the top-floor bank of Lumbridge
	 * castle put the follower inside a bank booth. Two booths in that row
	 * are made to ignore collision and the floor under them is not blocked,
	 * so their tiles read 0 - by the map a player could stand in them - and
	 * the placement took one. Nothing the client says about an object tells
	 * whether it was meant to be solid, so this looks at what is there: an
	 * object placed as a centrepiece (location type 10 or 11, as booths,
	 * counters, tables and chairs are) whose model crosses the band above.
	 * Walls, doors, wall decorations, roofs and ground objects are other
	 * types and never count.
	 *
	 * <p>Crops with a Pick option - wheat, flax, potatoes - are walked into
	 * by everyone and are left out ({@link #isCrop}); other tall plants
	 * still count. This is asked only where the follower is put or chooses
	 * to stop, never by the route search.
	 */
	public boolean furnished(WorldPoint at)
	{
		net.runelite.api.WorldView view = at == null ? null : client.findWorldViewFromWorldPoint(at);
		if (view == null || view.getScene() == null)
		{
			return false;
		}
		LocalPoint local = LocalPoint.fromWorld(view, at);
		net.runelite.api.Tile[][][] tiles = view.getScene().getTiles();
		if (local == null || tiles == null || at.getPlane() >= tiles.length)
		{
			return false;
		}
		net.runelite.api.Tile[][] plane = tiles[at.getPlane()];
		int x = local.getSceneX();
		int y = local.getSceneY();
		if (x < 0 || y < 0 || x >= plane.length || y >= plane[x].length)
		{
			return false;
		}
		// The tile has to be the one asked about. Aboard a boat the scene
		// may not be laid out like the collision map, and a misread must
		// find no furniture rather than furniture on the wrong tile.
		net.runelite.api.Tile tile = plane[x][y];
		if (tile == null || !at.equals(tile.getWorldLocation()))
		{
			return false;
		}
		for (net.runelite.api.GameObject object : tile.getGameObjects())
		{
			if (object == null)
			{
				continue;
			}
			int type = object.getConfig() & 0x1F;
			net.runelite.api.Renderable renderable = object.getRenderable();
			// Players and NPCs are put in the same arrays as they are drawn.
			if ((type != 10 && type != 11) || renderable == null
				|| renderable instanceof net.runelite.api.Actor || isCrop(object.getId()))
			{
				continue;
			}
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			float[] ys = model == null ? null : model.getVerticesY();
			int count = ys == null ? 0 : Math.min(model.getVerticesCount(), ys.length);
			float top = Float.NEGATIVE_INFINITY;
			float bottom = Float.POSITIVE_INFINITY;
			for (int i = 0; i < count; i++)
			{
				// Y runs negative upward.
				top = Math.max(top, -ys[i]);
				bottom = Math.min(bottom, -ys[i]);
			}
			if (bottom <= CHEST_HEIGHT && top >= KNEE_HEIGHT)
			{
				return true;
			}
		}
		return false;
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
			centreOn(wp);
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

		boolean moving = !route.isEmpty() || offCentre();

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
			pose = gait(stance);
		}
		showPose(pose);

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
		if (targetTile == null || !faceToward(targetTile))
		{
			return false;
		}
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
		releaseAfterCast = false;
		goalTile = null;
		serverPath.clear();
		// public:cut agility,agility-all
		// Halfway along a log is not a place to stay: see finishCrossing.
		stayGivenMidCrossing = playing != null ? tile : null;
		// public:end agility,agility-all
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
	/**
	 * Every spot the follower is given comes through here - the seat, the
	 * errands, the wander, the moments, a Send - so this is where a fire, and
	 * furniture the map calls open floor, are refused once, for all of them.
	 * The callers that offer a list of candidates move on to the next, which
	 * is what each already does when the ground refuses a tile.
	 */
	public boolean stayAt(WorldPoint target)
	{
		// public:cut errands
		// The exception lives exactly as long as the destination it was
		// granted for; any other destination ends it.
		if (insistedTile != null && !insistedTile.equals(target))
		{
			insistedTile = null;
		}
		// public:end errands
		java.util.List<WorldPoint> path = stayRoute(target);
		if (path == null)
		{
			return false;
		}
		WorldPoint from = simTile != null ? simTile : tile;

		// Closest-approach may legitimately stop short of the clicked tile.
		// What it must never settle for is the fire the follower is already
		// standing in: an unreachable destination comes back as an empty
		// path, whose "as near as I could get" is the tile underfoot. That
		// was returned as SUCCESS, so the escape from a fire anchored the
		// stay ON the fire, and the next frame did it again. Refusing sends
		// the caller to its next candidate, which is what they all do.
		WorldPoint settled = path.isEmpty() ? from : path.get(path.size() - 1);
		if (!restsOn(settled))
		{
			return false;
		}
		stayTile = settled;
		goalTile = null;
		serverPath.clear();
		serverPath.addAll(path);
		return true;
	}

	// public:cut skilling
	/**
	 * Whether a stay here would end ON the tile rather than beside it, asked
	 * without setting off. Closest approach calls stopping beside a rock, or
	 * at the water's edge, arriving; a caller choosing among tiles needs to
	 * know that before it commits the walk, or every tile it turns down is
	 * still walked to.
	 */
	public boolean arrivesAt(WorldPoint target)
	{
		return stepsTo(target) >= 0;
	}

	/**
	 * The steps a stay here would take, when it ends ON the tile: 0 for the
	 * tile underfoot, -1 when it would stop beside it or is refused. Asked
	 * without setting off, like {@link #arrivesAt}, so a caller weighing
	 * several tiles can take the one that is least walk.
	 */
	public int stepsTo(WorldPoint target)
	{
		java.util.List<WorldPoint> path = stayRoute(target);
		if (path == null)
		{
			return -1;
		}
		WorldPoint from = simTile != null ? simTile : tile;
		WorldPoint settled = path.isEmpty() ? from : path.get(path.size() - 1);
		return settled.equals(target) && restsOn(settled) ? path.size() : -1;
	}

	// public:end skilling
	/**
	 * Whether a stay may end on this tile: not in a fire, and not inside
	 * furniture the map calls open floor.
	 *
	 * <p>Play test, 8 Oct: with the placement kept out of the Lumbridge
	 * booth, the follower wandered into it. The wander, its drift over to
	 * the banker, the seats, the errands and the moments all end in a stay,
	 * and the booth reads open, so any of them could aim at it, or settle
	 * on it by closest approach to a blocked tile behind the counter. Asked
	 * of where the stay ends, which covers both, and once a stay: the walk
	 * there may still pass through.
	// public:cut errands
	 *
	 * <p>The tile the follower was told to walk onto anyway is let off the
	 * furniture as it is the fire. A fire is an object placed the way
	 * furniture is and may well read as one, and the gag walks into it.
	// public:end errands
	 */
	private boolean restsOn(WorldPoint settled)
	{
		return !hot(settled) && (settled.equals(insistedTile) || !furnished(settled)); // public:cut errands
		// public:else return !hot(settled) && !furnished(settled);
	}

	/** The walk a stay would take to a tile, or null where it is refused outright. */
	private java.util.List<WorldPoint> stayRoute(WorldPoint target)
	{
		if (!spawned || tile == null || target == null
			|| target.getPlane() != tile.getPlane() || hot(target))
		{
			return null;
		}

		// A destination near the leash edge would snap-and-release the moment
		// the follower set off; refuse it as out of range instead.
		WorldPoint player = playerTile(client);
		if (player == null || target.distanceTo(player) > STAY_LEASH - 2)
		{
			return null;
		}

		WorldPoint from = simTile != null ? simTile : tile;
		return findPath(from, target, true, player);
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

	// public:cut agility,agility-all
	// -------------------------------------------------------------------- agility

	/*
	 * The player's agility, done after them: every obstacle and shortcut they
	 * take, in order, however far behind that puts it, falls included.
	 * CrossingWatch records each one off the player and hands it over while
	 * it is still recording; this walks to the tile it began on and plays it
	 * back START_LAG_MS behind the player - the same fine positions, floors,
	 * facing and animations at the same moments - then the next, then follows
	 * again.
	 *
	 * While any of it is going on, following, the teleport snap and the
	 * blocked-tile hop are all off: a follower a log behind is not lost, and
	 * one a floor below on a rooftop course is not stranded. For a while after
	 * (AGILITY_PATIENCE_MS from the last crossing or XP drop) they stay off
	 * too, the follower waiting where it is rather than snapping: on a course,
	 * an obstacle the watch missed is about to be rebuilt from its XP. Two
	 * limits stand in for the snap. More than MAX_BEHIND_TILES behind - far
	 * enough that the next scene rebuild could leave it off the loaded map -
	 * or more than MAX_QUEUED_CROSSINGS waiting, and the queue is let go: the
	 * crossing under way is finished, and the ordinary catch-up takes it from
	 * there. A real teleport (the watch's) ends the patience at once.
	 */

	/**
	 * Crossings waiting before the queue is let go: more than the longest
	 * course has obstacles, so a follower this far behind has lost a whole lap.
	 */
	static final int MAX_QUEUED_CROSSINGS = 16;

	/**
	 * Unable to reach where a crossing begins for this long, and it is put
	 * there: eight ticks, longer than the watch waits for a late XP drop
	 * (five), so a missed climb between here and there is rebuilt and
	 * queued first, and walked rather than jumped.
	 */
	static final long STUCK_APPROACH_MS = 4800;

	/**
	 * How far behind the player a crossing starts: two ticks, measured from
	 * when the player began it (the recording's own length), not from when
	 * the follower got to the start.
	 */
	static final long START_LAG_MS = 1200;

	/** The first part of a crossing eases from where the follower stood onto the recording. */
	static final long EASE_MS = 300;

	/** Playback speed while further behind the player than START_LAG_MS, outside the player's own clips. */
	static final double CATCH_UP = 1.25;

	/**
	 * After the last crossing, or agility XP, this long the follower waits
	 * for the player rather than snapping to them. A rooftop obstacle takes
	 * a few seconds and pays as it ends; a ladder off the course is snapped
	 * after, as before, once this has run out.
	 */
	static final long AGILITY_PATIENCE_MS = 8000;

	/**
	 * Behind by more than this and the queue is let go. The loaded scene is
	 * 104 tiles and is rebuilt round the player when they come within 16 of
	 * its edge, leaving about 48 behind them: past 64 the follower is about
	 * to fall off the map whatever it does. The old 40 (the stay leash) was
	 * within a long course's lap; Draynor's whole course fits in 24.
	 */
	static final int MAX_BEHIND_TILES = 64;

	private final Deque<Crossing> crossings = new ArrayDeque<>();

	/** Where the crossing the player is on now began, until it is queued. */
	private WorldPoint awaitedCrossing;

	/** The crossing being played back, and how far into it. */
	private Crossing playing;
	private long playedMs;
	/** Where the follower stood as it began: the first EASE_MS go from here onto the recording. */
	private long easeFromX;
	private long easeFromY;
	/** A crossing the player has begun is waiting: the walk to its start is a run. */
	private boolean hurrying;
	private AnimationController crossingController;
	private int crossingAnimation = -1;
	/** The recorded frame of the clip last shown, to tell it begun again. */
	private int crossingActionFrame = -1;

	/** The walk to where the next crossing begins: aimed at, planned to, and how long stuck. */
	private WorldPoint approachTarget;
	private boolean approachWaiting;
	private WorldPoint approachPlanned;
	private long approachStillMs;

	/** Where a stay was given while a crossing played, to be moved to where it ends. */
	private WorldPoint stayGivenMidCrossing;

	/** Whether the last frame was agility's, so the hand-back to following happens once. */
	private boolean inAgility;

	/** Told as each crossing is done, for the words. */
	@lombok.Setter
	private java.util.function.Consumer<Crossing> onCrossed;

	/**
	 * What the clip a crossing shows now puts in each hand, as handItems
	 * gives it, or null: a climb, a squeeze or a swing empties the hands,
	 * the pole vault holds the pole. The plugin dresses it as it dresses an
	 * emote's, and is told to rebuild when it changes.
	 */
	@Getter
	private int[] crossingHands;
	@lombok.Setter
	private Runnable onCrossingHands;

	/** Left of the wait after agility before a snap may fetch the follower (AGILITY_PATIENCE_MS). */
	private long patienceMs;
	/** XP, a crossing queued or one finished since the last hop was expected. */
	private boolean patienceRenewed;

	// public:end agility,agility-all
	// public:cut dev
	/** The agility trace's event lines; nowhere unless the plugin says. */
	private java.util.function.Consumer<String> agilityTrace = line -> { };

	// public:end dev
	/** Told when a snap lets the agility go, so XP for what was left behind rebuilds nothing. */
	@lombok.Setter
	private Runnable onAgilitySnap;

	// public:cut dev
	public void setAgilityTrace(java.util.function.Consumer<String> trace)
	{
		agilityTrace = trace == null ? line -> { } : trace;
	}

	// public:end dev
	// public:cut agility,agility-all
	/** The clock the watch stamps its recordings with: how far behind the player the follower is. */
	private java.util.function.LongSupplier agilityClock = System::currentTimeMillis;

	/** For a harness driving the watch on a clock of its own. */
	void setAgilityClock(java.util.function.LongSupplier clock)
	{
		agilityClock = clock;
	}

	/** Whether agility has the follower: one being played, queued, or under way on the player. */
	public boolean isDoingAgility()
	{
		return playing != null || awaitedCrossing != null || !crossings.isEmpty();
	}

	/** Whether the follower is waiting on the player's agility rather than snapping to them. */
	public boolean isHoldingForAgility()
	{
		return patienceMs > 0;
	}

	/** Agility XP: whatever the player is on is about to be queued, so no snap for a while. */
	public void holdForAgility()
	{
		if (spawned && tile != null && thrallNpc == null && stayTile == null)
		{
			patienceMs = AGILITY_PATIENCE_MS;
			patienceRenewed = true;
		}
	}

	// public:cut dev
	/** What agility has the follower doing, in a few words, for the trace. */
	public String describeAgility()
	{
		String hold = patienceMs > 0 ? " hold " + CrossingWatch.seconds(patienceMs) : "";
		if (playing != null)
		{
			return "play " + CrossingWatch.seconds(Math.min(playedMs, playing.getDurationMs())) + "/"
				+ CrossingWatch.seconds(playing.getDurationMs()) + (playing.isComplete() ? "" : " live") + hold;
		}
		Crossing next = crossings.peekFirst();
		if (next != null)
		{
			return "to " + CrossingWatch.describe(next.getStart())
				+ (next.isComplete() || next.getDurationMs() >= START_LAG_MS ? " ready"
				: " lag " + CrossingWatch.seconds(START_LAG_MS - next.getDurationMs()))
				+ (approachStillMs > 0 ? " stuck " + CrossingWatch.seconds(approachStillMs) : "") + hold;
		}
		if (awaitedCrossing != null)
		{
			return "await " + CrossingWatch.describe(awaitedCrossing) + hold;
		}
		return "follow" + hold;
	}

	// public:end dev
	public boolean isPlayingCrossing()
	{
		return playing != null;
	}

	public int getCrossingsQueued()
	{
		return crossings.size();
	}

	@Override
	public void expectCrossing(WorldPoint start)
	{
		if (!spawned || tile == null || thrallNpc != null || start == null)
		{
			return;
		}
		takeFeetForAgility();
		awaitedCrossing = start;
		// Long enough for its XP on a course, and no longer: a ladder is let
		// go and snapped after much as before.
		patienceMs = Math.max(patienceMs, CrossingWatch.CONFIRM_WAIT_ON_COURSE_MS + 600);
		patienceRenewed = false;
	}

	/**
	 * Queued in the order the player began them: a crossing rebuilt from
	 * late XP goes ahead of one recognised since. One that began before the
	 * crossing already being played is too late to copy in order, and is let
	 * go rather than walked back for.
	 */
	@Override
	public void queueCrossing(Crossing crossing)
	{
		if (!spawned || tile == null || thrallNpc != null || crossing == null)
		{
			return;
		}
		if (crossings.size() >= MAX_QUEUED_CROSSINGS)
		{
			log.debug("{} crossings behind; letting the queue go", crossings.size()); // public:cut logs
			agilityTrace.accept("follower: cap, " + crossings.size() + " crossings queued; queue let go"); // public:cut dev
			clearCrossings();
			return;
		}
		if (playing != null && crossing.getStartedAtMs() < playing.getStartedAtMs())
		{
			// public:cut dev
			agilityTrace.accept("follower: crossing from " + CrossingWatch.describe(crossing.getStart())
				+ " began before the one playing; too late to copy");
			// public:end dev
			return;
		}
		takeFeetForAgility();
		java.util.List<Crossing> ordered = new java.util.ArrayList<>(crossings);
		int at = ordered.size();
		while (at > 0 && ordered.get(at - 1).getStartedAtMs() > crossing.getStartedAtMs())
		{
			at--;
		}
		ordered.add(at, crossing);
		crossings.clear();
		crossings.addAll(ordered);
		awaitedCrossing = null;
		patienceMs = AGILITY_PATIENCE_MS;
		patienceRenewed = true;
		// public:cut dev
		agilityTrace.accept("follower: queued crossing from " + CrossingWatch.describe(crossing.getStart())
			+ (crossing.isComplete() ? "" : " (live)") + ", " + crossings.size() + " waiting");
		// public:end dev
	}

	@Override
	public void cancelExpectedCrossing()
	{
		awaitedCrossing = null;
		if (!patienceRenewed)
		{
			// Only the hop held it: no agility after all.
			patienceMs = 0;
		}
	}

	/** Lets go of what is waiting, and of the wait. One under way is finished: nobody stops halfway along a log. */
	@Override
	public void clearCrossings()
	{
		crossings.clear();
		awaitedCrossing = null;
		patienceMs = 0;
	}

	/** All of it, now: a snap, a despawn, thrall work. */
	private void dropCrossings()
	{
		clearCrossings();
		playing = null;
		crossingController = null;
		crossingAnimation = -1;
		setCrossingHands(null);
	}

	/**
	 * Agility taking the feet from whatever of the follower's own had them -
	 * a moment's walk, a drift. Never the player's park: the plugin does not
	 * watch for crossings while there is one.
	 */
	private void takeFeetForAgility()
	{
		if (isDoingAgility())
		{
			return;
		}
		dropPose();
		insistedTile = null; // public:cut errands
		releaseAfterCast = false;
		serverPath.clear();
		approachTarget = null;
		approachPlanned = null;
		approachStillMs = 0;
	}

	/**
	 * The frame while agility has the follower. False once it is over, after
	 * handing back to following, so the rest of the frame follows as usual.
	 */
	private boolean agilityFrame(Player local, long deltaMs)
	{
		if (!isDoingAgility())
		{
			if (inAgility)
			{
				// Picked up from wherever the player is now, as after any jump:
				// beside them if they are near, the snap if they are not.
				inAgility = false;
				lastSeenPlayerTile = null;
				playerFollowTile = null;
				clearTrailOfPlayer();
				serverPath.clear();
				approachTarget = null;
				approachPlanned = null;
				hurrying = false;
			}
			return false;
		}
		inAgility = true;

		WorldPoint playerTile = local.getWorldLocation();
		if (playerTile != null && tile.distanceTo2D(playerTile) > MAX_BEHIND_TILES
			&& (awaitedCrossing != null || !crossings.isEmpty()))
		{
			log.debug("{} tiles behind on agility; letting the queue go", tile.distanceTo2D(playerTile)); // public:cut logs
			agilityTrace.accept("follower: cap, " + tile.distanceTo2D(playerTile) + " tiles behind; queue let go"); // public:cut dev
			clearCrossings();
			if (!isDoingAgility())
			{
				return agilityFrame(local, deltaMs);
			}
		}
		if (playing == null && !crossings.isEmpty() && crossings.peekFirst().isDropped())
		{
			// Let go by the watch as not an obstacle before it was begun.
			Crossing skipped = crossings.pollFirst(); // public:cut dev
			// public:else crossings.pollFirst();
			// public:cut dev
			agilityTrace.accept("follower: skipped crossing from " + CrossingWatch.describe(skipped.getStart())
				+ ", dropped before it began");
			// public:end dev
			return agilityFrame(local, deltaMs);
		}

		if (playing != null)
		{
			playCrossing(deltaMs);
		}
		else
		{
			approachCrossing(local, deltaMs);
		}

		if (!render())
		{
			// Off the loaded scene: nothing here can be shown. The old catch-up.
			recoverPosition(local, playerTile, "off the loaded scene during agility"); // public:cut dev
			// public:else recoverPosition(local, playerTile);
		}
		return true;
	}

	/** Walking to where the next crossing begins, or waiting there for the one the player is on. */
	private void approachCrossing(Player local, long deltaMs)
	{
		Crossing next = crossings.peekFirst();
		WorldPoint target = next != null ? next.getStart() : awaitedCrossing;
		// A cutscene: the queue waits where it stands until the scene is over.
		boolean held = watchingWithThePlayer;
		// While the player is still on it, up beside where it began rather
		// than onto them: a climb starts on the very tile they stand on, and
		// a crossing handed over as it begins may still have them on it.
		boolean waiting = next == null || target.equals(local.getWorldLocation());
		// Not before the player is START_LAG_MS into it.
		boolean ready = next != null && (next.isComplete() || next.getDurationMs() >= START_LAG_MS);
		// The player has begun it: run there, as they would after someone.
		hurrying = next != null;

		if (!target.equals(approachTarget) || waiting != approachWaiting)
		{
			approachTarget = target;
			approachWaiting = waiting;
			approachPlanned = null;
			approachStillMs = 0;
		}
		if (held)
		{
			serverPath.clear();
			approachPlanned = null;
		}
		else if (waiting && approachPlanned == null && route.isEmpty() && tile.distanceTo(target) == 1)
		{
			// Already beside it: stay, one step from it for when they move off.
			serverPath.clear();
			approachPlanned = tile;
		}
		else if (approachPlanned == null
			|| (serverPath.isEmpty() && route.isEmpty() && !tile.equals(approachPlanned))
			|| (!serverPath.isEmpty() && !serverPath.peekLast().equals(approachPlanned)))
		{
			// Planned once, and again only if something else took the walk.
			planApproach(target, waiting ? playerTile(client) : null);
			// The first step at once rather than on the next tick's release:
			// the start is usually the tile the player has just stepped off,
			// and the crossing begins START_LAG_MS after they did.
			tickCarryMs = 600;
		}
		boolean moving = walkFrame(deltaMs);

		// On the start, or beside it once the player is off it: the first
		// moments of the crossing ease it on, which a rope's first tile,
		// where the game puts the player and nobody can walk, needs.
		boolean there = !moving && ((serverPath.isEmpty() && tile.equals(target))
			|| (!waiting && tile.distanceTo(target) == 1));
		if (!moving && serverPath.isEmpty() && !there && !held && !waiting)
		{
			approachStillMs += deltaMs;
			if (approachStillMs >= STUCK_APPROACH_MS)
			{
				// Walled off from it, or it is on a floor the follower cannot
				// walk to: put on the spot, as a snap would put it beside you.
				log.debug("Cannot reach the crossing at {} from {}; placing it there", target, tile); // public:cut logs
				// public:cut dev
				agilityTrace.accept("follower: placed on the crossing start " + CrossingWatch.describe(target)
					+ " from " + CrossingWatch.describe(tile) + ", unreachable for "
					+ CrossingWatch.seconds(approachStillMs));
				// public:end dev
				placeAt(target);
				there = true;
			}
		}
		else
		{
			approachStillMs = 0;
		}

		// Facing the way the player set off, so it does not start sideways.
		int setOff = next == null ? -1 : next.getFrames().get(0).getOrientation() & 0x7ff;
		if (there && next != null && !held && ready && Math.abs(((setOff - yaw + 1024) & 0x7ff) - 1024) <= 256)
		{
			startCrossing(next);
			return;
		}

		if (!moving)
		{
			if (held)
			{
				faceTheHold(local);
			}
			else if (there && next != null)
			{
				dstYaw = setOff;
			}
			else
			{
				// Watching them do it.
				facePlayer();
			}
		}
		walkPose(moving);
	}

	/**
	 * The frame while the player is up on another floor just after agility:
	 * the step under way finished, then standing watching them, as beside a
	 * crossing's start, until the crossing is queued or the patience runs out.
	 */
	private void waitForAgility(Player local, long deltaMs)
	{
		serverPath.clear();
		boolean moving = walkFrame(deltaMs);
		if (!moving)
		{
			facePlayer();
		}
		walkPose(moving);
		if (!render())
		{
			recoverPosition(local, local.getWorldLocation(), "off the loaded scene while waiting on agility"); // public:cut dev
			// public:else recoverPosition(local, local.getWorldLocation());
		}
	}

	/** Agility's plain locomotion: stand, walk or run. */
	private void walkPose(boolean moving)
	{
		StanceLibrary.Stance stance = stance();
		applyPose(!moving ? stance.idle : gait(stance), false);
		wrapBeforeFinalFrame();
		passEmoteMilestones(false);
	}

	private void planApproach(WorldPoint target, WorldPoint avoid)
	{
		// Steps already released that run on past where it begins (the
		// player's trail, after the game carried them on from it) stop
		// there: nobody walks past an obstacle's start to turn back to it.
		// Not while the player still stands on it: that is walked up beside.
		if (avoid == null && !route.isEmpty() && (tile.equals(target) || route.contains(target)))
		{
			while (!route.isEmpty() && !route.peekLast().equals(target))
			{
				route.pollLast();
				routeRun.pollLast();
			}
			if (route.isEmpty())
			{
				route.addLast(target);
				routeRun.addLast(false);
			}
			simTile = target;
		}
		WorldPoint from = simTile != null ? simTile : tile;
		java.util.List<WorldPoint> path = target.getPlane() == from.getPlane()
			? findPath(from, target, true, avoid) : null;
		serverPath.clear();
		if (path != null)
		{
			serverPath.addAll(path);
		}
		approachPlanned = path == null || path.isEmpty() ? from : path.get(path.size() - 1);
	}

	private void placeAt(WorldPoint at)
	{
		putAt(at);
		approachPlanned = at;
	}

	private void startCrossing(Crossing crossing)
	{
		crossings.pollFirst();
		playing = crossing;
		playedMs = 0;
		clearTrail();
		crossingController = null;
		crossingAnimation = -1;
		easeFromX = fineWX;
		easeFromY = fineWY;
		hurrying = false;
		// public:cut dev
		agilityTrace.accept("follower: began crossing from " + CrossingWatch.describe(crossing.getStart())
			+ ", " + CrossingWatch.seconds(agilityClock.getAsLong() - crossing.getStartedAtMs())
			+ " behind the player" + (crossing.isComplete() ? " (recording done)" : ""));
		// public:end dev
		playCrossing(0);
	}

	/** One frame of the crossing: where the player was at this moment of it, and what they were doing. */
	private void playCrossing(long deltaMs)
	{
		// The crossing has the body, as walking does: no emote over it.
		if (emotePlaying)
		{
			cancelEmote();
		}
		waitingEmote = -1;

		Crossing crossing = playing;
		// Behind the player by more than the two ticks: a little quicker
		// through whatever is not a clip of its own, until it is back to them.
		boolean behind = agilityClock.getAsLong() - crossing.getStartedAtMs() - playedMs > START_LAG_MS;
		playedMs += behind && crossing.frameAt(playedMs).action == -1 ? Math.round(deltaMs * CATCH_UP) : deltaMs;
		if (!crossing.isComplete() && playedMs > crossing.getDurationMs())
		{
			// Caught up with the recording (a stalled frame on the player's
			// side): hold on its newest frame rather than run on past it.
			playedMs = crossing.getDurationMs();
		}
		long at = Math.min(playedMs, crossing.getDurationMs());
		Crossing.Frame frame = crossing.frameAt(at);
		long[] fine = crossing.fineAt(at);
		if (at < EASE_MS)
		{
			// From where it stood, beside the start or a little off it, onto
			// the player's path: no pop.
			double k = at / (double) EASE_MS;
			fine[0] = easeFromX + Math.round((fine[0] - easeFromX) * k);
			fine[1] = easeFromY + Math.round((fine[1] - easeFromY) * k);
		}
		fineWX = fine[0];
		fineWY = fine[1];
		tile = new WorldPoint((int) (fineWX >> 7), (int) (fineWY >> 7), frame.plane);
		simTile = tile;
		lastMoveSpeed = 0;
		burst = false;
		onTheMove = true;
		dstYaw = frame.orientation & 0x7ff;
		turnContinuous(deltaMs / (double) CYCLE_MS);
		showCrossingFrame(crossing, frame);

		if (crossing.isComplete() && playedMs >= crossing.getDurationMs())
		{
			finishCrossing();
		}
	}

	private void finishCrossing()
	{
		Crossing done = playing;
		playing = null;
		patienceMs = AGILITY_PATIENCE_MS;
		patienceRenewed = true;
		// public:cut dev
		agilityTrace.accept("follower: finished crossing at " + CrossingWatch.describe(tile)
			+ (done.isFell() ? ", fell" : "") + ", " + crossings.size() + " waiting");
		// public:end dev
		crossingController = null;
		crossingAnimation = -1;
		setCrossingHands(null);
		// The locomotion pose goes back on with the next frame's.
		activePose = -1;
		clearTrail();
		if (offCentre())
		{
			// Ended mid-step, as the player walked off: the rest of the step.
			route.addLast(tile);
			routeRun.addLast(false);
		}
		simTile = tile;
		approachPlanned = null;
		approachTarget = null;
		if (stayTile != null && stayTile.equals(stayGivenMidCrossing))
		{
			// A Stay given mid-crossing holds where the crossing put it down.
			stayTile = tile;
		}
		stayGivenMidCrossing = null;
		if (onCrossed != null)
		{
			onCrossed.accept(done);
		}
	}

	/**
	 * The animation the player showed at this frame, on the follower: the
	 * action when one played, the pose otherwise. A pose from the player's
	 * own stand-walk-run set is shown as the follower's own, so it walks
	 * between the odd parts like itself; anything else - the balance, the
	 * shimmy, the hang - is the player's, frame for frame. Each starts on
	 * the frame the player's was on, so a clip begun before the first
	 * sample still lines up.
	 */
	private void showCrossingFrame(Crossing crossing, Crossing.Frame frame)
	{
		if (object == null || !animatable)
		{
			return;
		}
		boolean action = frame.action != -1;
		int normalIndex = action ? -1 : crossing.normalIndex(frame.pose);
		int wanted = action ? frame.action : normalIndex >= 0 ? ownPose(normalIndex) : frame.pose;
		int startFrame = action ? frame.actionFrame : normalIndex >= 0 ? 0 : frame.poseFrame;
		// The same clip begun again (its frame gone back, with nothing
		// between): a second jump, not the first one held.
		boolean again = action && frame.actionFrame < crossingActionFrame;
		crossingActionFrame = action ? frame.actionFrame : -1;
		if (wanted == crossingAnimation && crossingController != null
			&& object.getAnimationController() == crossingController && !again)
		{
			return;
		}

		Animation animation = wanted > 0 ? client.loadAnimation(wanted) : null;
		if (animation == null)
		{
			// Not in the cache: the follower's own stand rather than nothing.
			wanted = stance().idle;
			startFrame = 0;
			action = false;
			animation = client.loadAnimation(wanted);
			if (animation == null)
			{
				return;
			}
		}
		int[] hands = action ? handItems(wanted) : null;
		setCrossingHands(hands != null && (hands[0] != FilmSet.KEEP || hands[1] != FilmSet.KEEP) ? hands : null); // public:cut posing
		// public:else setCrossingHands(hands != null && (hands[0] != -1 || hands[1] != -1) ? hands : null);
		AnimationController controller = new AnimationController(client, animation);
		int frames = frameCount(animation);
		if (startFrame > 0 && startFrame < frames)
		{
			controller.setFrame(startFrame);
		}
		// A one-shot holds its last frame until the recording moves on, the
		// way the player's did until the next thing replaced it; a loop loops.
		controller.setOnFinished(action && animation.getFrameStep() < 0
			? FollowerEntity::holdLastFrame : FollowerEntity::loopSafely);
		object.setAnimationController(controller);
		crossingController = controller;
		crossingAnimation = wanted;
		controllerGeneration++; // public:cut dev
		activePose = action ? -1 : wanted;
	}

	private void setCrossingHands(int[] hands)
	{
		if (java.util.Arrays.equals(hands, crossingHands))
		{
			return;
		}
		crossingHands = hands;
		if (onCrossingHands != null)
		{
			onCrossingHands.run();
		}
	}

	/** The follower's own version of the player's normal pose at this index, in stance order. */
	private int ownPose(int index)
	{
		StanceLibrary.Stance stance = stance();
		int[] own = {stance.idle, stance.walk, stance.run, stance.walkBack,
			stance.walkLeft, stance.walkRight, stance.turnLeft, stance.turnRight};
		int pose = index < own.length ? own[index] : 0;
		if (pose > 0)
		{
			return pose;
		}
		// Missing from a stance saved before the directional fields existed.
		return index >= 1 && index <= 5 ? stance.walk : stance.idle;
	}

	private static void holdLastFrame(AnimationController controller)
	{
		controller.setFrame(Math.max(0, frameCount(controller.getAnimation()) - 1));
	}

	// public:end agility,agility-all
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
			// public:cut dev
			if (debugEnabled)
			{
				log.info("FTRACE obs JUMP from {} to {}", lastSeenPlayerTile, current); // public:cut logs
			}
			// public:end dev
			// The player landed while the copy of their cast still plays.
			if (lastSeenPlayerTile != null && vanishAfterEmoteArmed)
			{
				castLanded = true;
			}
			lastSeenPlayerTile = current;
			playerFollowTile = current;
			clearTrailOfPlayer();
			recordTrail(current);
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
		recordTrail(lastSeenPlayerTile);
		for (int i = 0; i < distance - 1; i++)
		{
			behind = stepToward(behind, current, true);
			recordTrail(behind);
		}
		recordTrail(current);
		// Further back along the same trail at a wider follow distance, or
		// the shoulder, on a long run over open ground.
		playerFollowTile = followTileFor(lastSeenPlayerTile, current,
			behindBy(followDistance, behind), ticksObserved, System.currentTimeMillis());
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
		if (stayTile == null && playerFollowTile != null)
		{
			if (serverPath.isEmpty() && simTile != null
				&& simTile.distanceTo(playerFollowTile) > 3)
			{
				// Re-acquiring from afar - a just-released pose, possibly with
				// walls in between. Steady-state following never enters this
				// branch; its gap is one or two tiles.
				extendPathBfs(playerFollowTile, null);
			}
			else
			{
				extendPathTo(playerFollowTile);
			}

			// The greedy stepper walks only legal tiles now, so a wall between
			// the follower and the player leaves the queue empty where it used
			// to produce a step straight through. Round it with the BFS, which
			// finds the doorway.
			//
			// This is the common case indoors and it is worth the search: two
			// tiles apart through a partition is a six-tile walk, and nothing
			// about the straight-line gap says so.
			if (serverPath.isEmpty() && simTile != null
				&& !simTile.equals(playerFollowTile))
			{
				extendPathBfs(playerFollowTile, null);

				// Still nothing. The BFS covers the whole loaded scene and
				// settles for the closest reachable tile, so an empty answer
				// means there is genuinely no way round - a sealed room, a
				// locked door, a floor the follower cannot reach. Count the
				// ticks; the plugin teleports it once the count says stuck
				// rather than merely slow.
				strandedTicks = serverPath.isEmpty() ? strandedTicks + 1 : 0;
			}
			else
			{
				strandedTicks = 0;
			}
		}

		// Release in the same breath, phase-locked to the player's own steps - one
		// release per server tick the observation covered. An independent release
		// clock drifted against the observations, and each phase slip lost a tile
		// forever; the server has no such hazard because everything moves in the
		// same tick.
		tickCarryMs = 0;
		// public:cut dev
		if (debugEnabled)
		{
			// public:cut logs
			log.info("FTRACE obs dist={} ticks={} run={} follow={} path={}",
				distance, ticksObserved, playerRunning, playerFollowTile, serverPath.size());
			// public:end logs
		}
		// public:end dev
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
	 * @param avoid a tile to route around, or null. Posed movement passes the
	 *              PLAYER'S tile: the pathfinder itself walks through players
	 *              (they don't collide), but a follower cutting straight
	 *              through you reads as a glitch - and walking AROUND you is
	 *              the lateral movement that shows the side-step poses while
	 *              face-locked. An avoided destination resolves by the
	 *              closest-approach fallback, landing beside it.
	 * @return every tile of the route after {@code src} in walking order; empty
	 * if already there; null if nothing reachable
	 */
	private java.util.List<WorldPoint> findPath(WorldPoint src, WorldPoint dest,
		boolean tryNearest, WorldPoint avoid)
	{
		LocalPoint srcLocal = sceneOf(src);
		LocalPoint destLocal = sceneOf(dest);
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

		// The client's neighbour order: W E S N SW SE NW NE. A reached tile
		// records which of these steps reached it (index + 1, 0 = unvisited),
		// where the client records the direction bits back toward the source
		// (W=2 E=8 S=1 N=4 SW=3 SE=9 NW=6 NE=12); both backtrack the same route.
		final int[] stepX = {-1, 1, 0, 0, -1, 1, -1, 1};
		final int[] stepZ = {0, 0, -1, 1, -1, -1, 1, 1};

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
				dirMap[nx * size + nz] = d + 1;
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
			// Back along the step that first reached this tile.
			int d = dirMap[x * size + z] - 1;
			x -= stepX[d];
			z -= stepZ[d];
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

	/**
	 * Where the player stands, or null while there is no player: the tile
	 * BFS routes walk around rather than through, and the controllers' "here".
	 */
	public static WorldPoint playerTile(Client client)
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
			tail = stepToward(tail, target, false);
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
		boolean two = !posed && (hurrying || serverPath.size() >= 2 && (playerRunning || serverPath.size() > 2)); // public:cut agility,agility-all
		// public:else boolean two = !posed && serverPath.size() >= 2 && (playerRunning || serverPath.size() > 2);
		int steps = two ? 2 : 1;
		for (int i = 0; i < steps && !serverPath.isEmpty(); i++)
		{
			WorldPoint next = serverPath.peekFirst();
			// Or blocked since it was planned: a walk worked out on a map
			// still loading must not pass through a booth.
			if (hot(next) || !standable(next))
			{
				// Queued while the tile was open and set alight since. Steps
				// are checked as they are PLANNED and never again, and on a
				// line of fires the tile being walked to is, by construction,
				// the tile the player is about to light. Drop what is queued;
				// the next observation paths again, knowing about the fire.
				serverPath.clear();
				break;
			}
			serverPath.pollFirst();
			route.addLast(next);
			routeRun.addLast(two);
			simTile = next;
		}
		// public:cut dev
		if (debugEnabled)
		{
			// public:cut logs
			log.info("FTRACE rel steps={} run={} sim={} path={} route={}",
				steps, two, simTile, serverPath.size(), route.size());
			// public:end logs
		}
		// public:end dev
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
		// Out of sight (an NPC or a player on its tile, a vanish): nothing to click.
		if (!isShown() || baseModel == null
			|| lastRenderedLocation == null || canvasPoint == null)
		{
			return false;
		}

		net.runelite.api.WorldView view = client.getWorldView(lastRenderedLocation.getWorldView());
		if (!isInsideBoundingBox(view, canvasPoint))
		{
			return false;
		}
		java.awt.Shape clickbox = Perspective.getClickbox(client, view, baseModel, yaw,
			lastRenderedLocation.getX(), lastRenderedLocation.getY(), lastRenderedZ);
		return clickbox != null && clickbox.contains(canvasPoint.getX(), canvasPoint.getY());
	}

	/**
	 * Whether the point is inside the screen rectangle round the model's
	 * bounding box, projected the way {@code getClickbox} projects it first:
	 * the clickbox is clipped to that box's outline, so a point outside it
	 * cannot be on the follower. The full clickbox, every face projected,
	 * merged and sorted, was built fifty times a second wherever the mouse
	 * was, and was most of the plugin's CPU (perf audit, Oct 2026).
	 */
	private boolean isInsideBoundingBox(net.runelite.api.WorldView view, net.runelite.api.Point point)
	{
		net.runelite.api.AABB box = baseModel.getAABB(yaw);
		float[] xs = new float[8];
		float[] ys = new float[8];
		float[] zs = new float[8];
		for (int i = 0; i < 8; i++)
		{
			// The same corners, in the same order, as Perspective's own.
			xs[i] = box.getCenterX() + ((i & 1) == 0 ? -box.getExtremeX() : box.getExtremeX());
			ys[i] = box.getCenterZ() + ((i & 2) == 0 ? -box.getExtremeZ() : box.getExtremeZ());
			zs[i] = box.getCenterY() + ((i & 4) == 0 ? -box.getExtremeY() : box.getExtremeY());
		}
		int[] canvasX = new int[8];
		int[] canvasY = new int[8];
		Perspective.modelToCanvas(client, view, 8, lastRenderedLocation.getX(),
			lastRenderedLocation.getY(), lastRenderedZ, 0, xs, ys, zs, canvasX, canvasY);
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < 8; i++)
		{
			// A corner behind the camera is left out, as the outline leaves it out.
			if (canvasX[i] != Integer.MIN_VALUE)
			{
				minX = Math.min(minX, canvasX[i]);
				maxX = Math.max(maxX, canvasX[i]);
				minY = Math.min(minY, canvasY[i]);
				maxY = Math.max(maxY, canvasY[i]);
			}
		}
		return point.getX() >= minX && point.getX() <= maxX
			&& point.getY() >= minY && point.getY() <= maxY;
	}

	// public:cut dev
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
		// public:cut agility,agility-all
		if (isDoingAgility())
		{
			out.add("agility queued=" + crossings.size() + (playing != null ? "  playing" : "")
				+ (awaitedCrossing != null ? "  awaiting " + describe(awaitedCrossing) : ""));
		}
		// public:end agility,agility-all
		return out;
	}

	private static String describe(WorldPoint p)
	{
		return p == null ? "-" : p.getX() + "," + p.getY();
	}

	// public:end dev
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
		patienceMs = Math.max(0, patienceMs - deltaMs); // public:cut agility,agility-all

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
			// Not while the area it landed in is still loading: the map then
			// is not the new one's, and a booth beside the player reads open.
			if (loggedIn() && (landed || now - vanishedAtMs > 8000))
			{
				vanished = false;
				lastSeenPlayerTile = null;
				if (where != null)
				{
					snapped();
					snapBeside(where, "teleport landing"); // public:cut dev
					// public:else snapBeside(where);
				}
			}
			return;
		}

		// Briefly gone (the comedy death, or a sketch's frame): nothing
		// renders, nothing moves.
		if (outOfPicture || System.currentTimeMillis() < hideUntilMs) // public:cut chronicle
		// public:else if (System.currentTimeMillis() < hideUntilMs)
		{
			if (object.isActive())
			{
				object.setActive(false);
			}
			pictureHidden = outOfPicture; // public:cut chronicle
			return;
		}
		// public:cut chronicle
		if (pictureHidden)
		{
			// Put back here, before the watchdog below can take the hide
			// for a fault and snap it beside the player, ending any Stay.
			pictureHidden = false;
			object.setActive(true);
		}
		// public:end chronicle

		// Thrall mode: the follower IS the thrall. Everything below - follow
		// pathing, stay poses, leashes - is someone else's life for now.
		if (thrallNpc != null)
		{
			updateThrallFrame(deltaMs);
			return;
		}

		// public:cut agility,agility-all
		// Agility: the follower is doing the player's obstacles after them.
		// Ahead of the follow pathing, the teleport rule and the blocked-tile
		// hop, none of which apply to somebody halfway along a log.
		if (agilityFrame(local, deltaMs))
		{
			return;
		}

		// public:end agility,agility-all
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
		boolean otherFloor = playerTile.getPlane() != tile.getPlane();
		boolean away = otherFloor || tile.distanceTo(playerTile) > leash;
		// public:cut agility,agility-all
		if (away && !castLanded && patienceMs > 0 && stayTile == null
			&& tile.distanceTo2D(playerTile) <= MAX_BEHIND_TILES)
		{
			// Just after agility: the player is on an obstacle the watch has
			// not queued yet, or one its XP is about to rebuild. Wait for it
			// rather than snap; on the same floor, walk after them as usual.
			if (otherFloor)
			{
				waitForAgility(local, deltaMs);
				return;
			}
		}
		// public:end agility,agility-all
		else if (castLanded || away) // public:cut agility,agility-all
		// public:else if (castLanded || away)
		{
			if (!loggedIn())
			{
				// Snapped from real data once the area has loaded.
				return;
			}
			if (castLanded)
			{
				// A teleport clip whose hold outlasts the cast (the Lunar
				// one's, HUMAN_TELEPORT_OTHER_IMPACT, runs 41 seconds) never
				// ends in time to vanish: the follower lands with the player.
				castLanded = false;
				if (vanishAfterEmoteArmed)
				{
					cancelEmote();
				}
			}
			stayTile = null;
			snapped();
			// public:cut dev
			String why = castLanded ? "cast landed" : otherFloor ? "player on another floor"
				: "player " + tile.distanceTo(playerTile) + " tiles away";
			// public:end dev
			if (!snapBeside(playerTile, why)) // public:cut dev
			// public:else if (!snapBeside(playerTile))
			{
				recoverPosition(local, playerTile, why); // public:cut dev
				// public:else recoverPosition(local, playerTile);
			}
			return;
		}

		// A settled follower whose own tile reads blocked hops to the spot
		// beside the player: a booth it was placed in while the area loaded,
		// public:cut agility,agility-all
		// or a deck facility that arrived after the boarding snap. Not just
		// after agility: a crossing can end where the map says nobody stands.
		// public:end agility,agility-all
		if (isSettled() && blockedUnderfoot(tile, local))
		{
			// public:cut agility,agility-all
			if (patienceMs <= 0)
			{
			// public:end agility,agility-all
				// public:cut dev
				snapBeside(playerTile, "blocked underfoot"); // public:cut agility,agility-all
			// public:else snapBeside(playerTile, "blocked underfoot");
				// public:end dev
				// public:else snapBeside(playerTile);
				return; // public:cut agility,agility-all
			// public:else return;
			// public:cut agility,agility-all
			}
			// Off a crossing that ends where nobody stands, a rope's last
			// tile: a step off it now, rather than the agility patience
			// waited out on the rope and then a hop.
			stepOffTheFire();
			// public:end agility,agility-all
		}

		// The follow tile is worked out when the player steps, and the tile
		// they stepped from is lit AFTER they stop on the next one - so by
		// the time anything acts on it, it is on fire and was not when it
		// was chosen. Asked again here, where it is used.
		playerFollowTile = coolFollowTile(playerFollowTile, false);

		// A fire lit under its feet - which is most of a firemaking line,
		// since the follower stands on the tile the player just left. One
		// step off, not a snap: somebody whose ground catches moves aside,
		// they do not appear elsewhere. Whatever owns the feet then chooses
		// properly at its own pace - the seat is re-taken a tick later.
		// public:cut dev,logs
		if (hot(tile))
		{
			if (!inAFire)
			{
				inAFire = true;
				// Once per entry, not per frame. Standing in a fire is not
				// supposed to happen at all, so when it does the whole state
				// of the feet is worth having without asking anyone to catch
				// it with a command.
				for (String line : describeFeet())
				{
					log.info("FIRES walked in: {}", line); // public:cut logs
				}
			}
			if (isSettled())
			{
				stepOffTheFire();
			}
		}
		else
		{
			inAFire = false;
		}
		// public:end dev,logs
		// public:else if (hot(tile) && isSettled())
		// public:else {
			// public:else stepOffTheFire();
		// public:else }

		// Out of sight a long while under somebody standing on its tile (a
		// player gone idle at a bank booth): a step aside, as off a fire.
		hiddenForOverlapMs = hiddenForNpcOverlap ? hiddenForOverlapMs + deltaMs : 0;
		if (hiddenForOverlapMs >= LONG_HIDE_MS && isSettled())
		{
			hiddenForOverlapMs = 0;
			stepOffTheFire();
		}

		// Watchdog: if anything at all has left the object hidden while the player
		// is on screen, put it back. A deliberate hide (an NPC sharing the
		// tile) is not a fault and must not trigger a recovery snap.
		if (!object.isActive() && !hiddenForNpcOverlap)
		{
			recoverPosition(local, playerTile, "watchdog: hidden"); // public:cut dev
			// public:else recoverPosition(local, playerTile);
		}

		// A cutscene keeps the follower as an extra (keepToTheScene).
		if (watchingWithThePlayer && loggedIn())
		{
			keepToTheScene(playerTile);
		}

		// A stay pose owns movement outright: path to the spot, then hold it -
		// facing the player attentively - until explicitly released. The player
		// walking away does NOT end it; watchPlayer skips the follow pathing
		// while it is set.
		// A posed goal suspends following: the goal's path is the only target
		// until the follower arrives (then it faces the player) or the player
		// moves (watchPlayer clears the goal).
		WorldPoint posed = stayTile != null ? stayTile : goalTile;
		if (posed != null)
		{
			if (route.isEmpty() && serverPath.isEmpty())
			{
				if (!tile.equals(posed))
				{
					extendPathBfs(posed, playerTile(client));
				}
				else if (stayTile != null)
				{
					faceTheHold(local);
				}
				else
				{
					// Arrived: HOLD here, facing the player, until they move. Clearing
					// the goal on arrival let the follow rule immediately walk it back
					// behind them, so the pose lasted a fraction of a second.
					facePlayer();
				}
			}
		}
		// Fallback for the cases the eager extension can't see - after a spawn,
		// snap or released pose, the player may already be standing still with
		// the follower away from their follow tile. BFS-routed: the follower
		// may be an arbitrary distance away with walls in between, and the
		// greedy stepper's raw-step fallback would walk it through them.
		// (extendPathBfs itself declines a follow tile on another floor.)
		else if (serverPath.isEmpty() && route.isEmpty() && playerFollowTile != null
			&& !playerFollowTile.equals(simTile))
		{
			// After a jump the follow tile IS the player's tile; avoid it so
			// closest-approach parks the follower beside them, not on them.
			extendPathBfs(playerFollowTile,
				playerFollowTile.equals(playerTile) ? playerFollowTile : null);
		}

		boolean moving = walkFrame(deltaMs);
		noteAStall(moving, playerTile, deltaMs); // public:cut dev

		// Idle facing: the follow op interacts with its target, and an
		// interacting entity keeps facing it while standing - turning in place
		// with the turn pose as the player circles around. A stay pose with a
		// face tile (an errand at its business) faces that instead. A
		// cutscene comes first: its hold is a stay, and would face the player.
		if (!moving && (watchingWithThePlayer || faceLocked()))
		{
			faceTheHold(local);
		}
		else if (!moving && !glance())
		{
			watchTheWork(local);
		}

		StanceLibrary.Stance stance = stance();
		int pose;

		// A forced pose only holds while STANDING. Movement wins over it for the
		// same reason it wins over an emote: a follower running back to you
		// still kneeling in prayer looks broken, and that is exactly what a
		// spectated fight ending used to produce.
		//
		// The override is kept rather than cleared, so a momentary shuffle does
		// not end a spell that is still being held. Whoever set it decides when
		// it is over - the spectate controller does that on its own count of
		// unsettled ticks.
		if (poseOverride > 0 && !moving)
		{
			pose = poseOverride;
		}
		else if (moving)
		{
			// Directional poses belong to the FACE-LOCKED states only (Face-me's
			// walk-around); plain following walks and runs facing its travel,
			// with no side-step flicker on sharp turns while the yaw catches up.
			pose = faceLocked() ? movePose(stance) : gait(stance);
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
		if (!showPose(pose))
		{
			// A chunk load is a seamless, sub-second rebuild that the player keeps
			// running through. Hold position and let the scene settle; only recover
			// outright while genuinely logged in and unresolvable.
			recoverPosition(local, playerTile, "off the loaded scene"); // public:cut dev
			// public:else recoverPosition(local, playerTile);
		}
		// public:cut dev

		if (debugEnabled && now - lastDebugMs >= 1000)
		{
			lastDebugMs = now;
			log.info("FTRACE gap {}", debugLines().get(0).trim()); // public:cut logs
		}
		// public:end dev
	}

	/**
	 * The frame's walking. Idle drain first: releases are normally
	 * phase-locked to observed player steps, which stop when the player
	 * stops - this timer finishes the follower's remaining approach
	 * afterwards, one tick's worth at a time.
	 *
	 * <p>Then the client's movement budget for this frame, in 20ms cycles.
	 * Consumed CONTINUOUSLY rather than in whole cycles: the real client
	 * renders at a locked 20ms per frame so whole cycles are smooth there,
	 * but against a variable frame rate they beat (move, move, skip...) - a
	 * micro-stutter. The speeds and rules are unchanged; only the
	 * granularity is finer.
	 *
	 * <p>Movement always wins over an emote - a dancing follower gliding
	 * along behind you looks broken, and the real game drops emotes on
	 * movement too.
	 *
	 * @return whether it is on the move
	 */
	private boolean walkFrame(long deltaMs)
	{
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
		moveContinuous(deltaMs / (double) CYCLE_MS);
		onTheMove = !route.isEmpty() || offCentre();
		if (onTheMove)
		{
			cancelEmote();
		}
		return onTheMove;
	}

	/** Walk or run by the last movement cycle's speed. */
	private int gait(StanceLibrary.Stance stance)
	{
		return lastMoveSpeed >= 8 ? stance.run : stance.walk;
	}

	/**
	 * The frame's pose on, kept on its loop, the emote's waiters told how far
	 * it has got, what waited for it to stand played, and the frame drawn.
	 *
	 * @return false when the draw could not place it, as {@link #render}
	 */
	private boolean showPose(int pose)
	{
		applyPose(pose, false);
		wrapBeforeFinalFrame();
		holdTheWriting();
		passEmoteMilestones(false);
		playWhatWaitedForStill();
		return render();
	}

	// public:cut dev
	private long lastDebugMs;

	// public:end dev
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

		// public:cut dev
		if (debugEnabled && pose != stance.walk && pose != stance.run && pose != activePose)
		{
			// public:cut logs
			log.info("FTRACE pose directional {} (travel={} yaw={} delta={})",
				pose, travelYaw, yaw, delta);
			// public:end logs
		}
		// public:end dev
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
	static int directionalFallback(StanceLibrary.Stance stance, int standardPose)
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
				burst = false;
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
			//
			// Once begun, the burst runs until the follower has properly caught
			// up, then settles back to a walk. Play test, 18 Oct: on and off at
			// the one threshold, a long walk flicked into the run pose for a
			// split second and straight back, over and over.
			if (route.size() > BURST_FROM)
			{
				burst = true;
			}
			else if (route.size() <= BURST_UNTIL)
			{
				burst = false;
			}
			if (burst)
			{
				speed = 8;
			}
			Boolean run = routeRun.peekFirst();
			if (run != null && run)
			{
				// A run's catch-up at half as fast again: at twice a run, a
				// long straight run read as a lunge every fourteen seconds.
				speed = burst ? 12 : speed << 1;
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
	/**
	 * One greedy step from {@code origin} toward {@code target}, or null when
	 * every legal direction is closed.
	 *
	 * @param allowRaw whether a boxed-in step may be taken anyway. Only true
	 * when RETRACING the player's own path: those tiles were walked a moment
	 * ago, so a collision reading that disagrees is the reading that is wrong,
	 * and refusing would leave the follow tile unknown.
	 *
	 * <p>False everywhere the follower moves under its own steam. It used to be
	 * unconditional, on the reasoning that missing collision data should never
	 * freeze the follower - but {@link #canStep} already answers "no data" with
	 * yes, so the raw step only ever fired when collision was present and said
	 * no. That is not blindness, it is a wall, and the follower walked through
	 * it. Inside a building it happened constantly: the player is two tiles
	 * away through a partition, the greedy stepper finds every direction shut,
	 * and takes the step regardless.
	 */
	WorldPoint stepToward(WorldPoint origin, WorldPoint target, boolean allowRaw)
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

		if (canStep(origin, dx, dy))
		{
			return origin.dx(dx).dy(dy);
		}
		if (dx != 0 && dy != 0)
		{
			// The corner is closed; go around it, longer axis first.
			int firstDx = Math.abs(remX) >= Math.abs(remY) ? dx : 0;
			int firstDy = firstDx == 0 ? dy : 0;
			if (canStep(origin, firstDx, firstDy))
			{
				return origin.dx(firstDx).dy(firstDy);
			}
			if (canStep(origin, dx - firstDx, dy - firstDy))
			{
				return origin.dx(dx - firstDx).dy(dy - firstDy);
			}
		}

		// Boxed in. Only the player's own retraced path may step anyway.
		return allowRaw
			? origin.dx(dx).dy(dy)
			: null;
	}

	/** True if one step from {@code from} by (dx, dy) is legal under collision. */
	boolean canStep(WorldPoint from, int dx, int dy)
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
			WorldPoint xSide = from.dx(dx);
			WorldPoint ySide = from.dy(dy);
			return canStep(from, dx, 0) && canStep(from, 0, dy)
				&& canStep(xSide, 0, dy) && canStep(ySide, dx, 0);
		}

		Integer fromFlags = flagAt(from);
		WorldPoint to = from.dx(dx).dy(dy);
		if (hot(to))
		{
			// A fire is walkable ground and nobody walks on it. Refused here
			// rather than at the destination, so the BFS routes AROUND one
			// instead of merely declining to stop on it - and so the greedy
			// stepper does too. Stepping OFF a fire is unaffected: only the
			// tile being stepped onto is asked about.
			return false;
		}
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

	/**
	 * Consecutive movement ticks with no route to the player at all.
	 *
	 * <p>Zero the moment a single step is found, so this only climbs while the
	 * follower is genuinely walled off rather than merely behind. The plugin
	 * reads it and teleports once it is high enough to mean stuck.
	 */
	@lombok.Getter
	private int strandedTicks;

	/** Called when the follower is placed somewhere by force; it is no longer stuck. */
	public void clearStranded()
	{
		strandedTicks = 0;
	}

	/**
	 * True while the follower has somewhere of its own to be - a Send, or the
	 * walk-around behind Face-me. Being away from the player is the point of
	 * both, so neither counts as stuck.
	 */
	public boolean isPosed()
	{
		return goalTile != null || stayTile != null;
	}

	/** True while the follower is hidden because an NPC or another player shares its tile. */
	private boolean hiddenForNpcOverlap;

	/** How long it has been hidden so, and how long before it steps aside instead. */
	private long hiddenForOverlapMs;
	static final long LONG_HIDE_MS = 20_000;

	/**
	 * How tall an NPC has to be, against the follower's own height, before
	 * sharing a tile with it is worth hiding for.
	 *
	 * <p>Tile size was tried first and cannot make this distinction: a rat and
	 * a woman are both one tile, yet one is ankle height and the other stands
	 * eye to eye. Height is the property that actually decides whether the
	 * follower disappears inside something or merely overlaps it.
	 *
	 * <p>Measured against the follower rather than a fixed number, so it holds
	 * whatever the follower is wearing and whichever way the models are scaled.
	 */
	private static final double OVERLAP_HEIGHT_FRACTION = 0.55;

	/** Model height by NPC id. A rat is always rat-shaped; measure it once. */
	private final java.util.Map<Integer, Integer> npcHeights = new java.util.HashMap<>();

	/**
	 * A model's height in model units, from its own vertices. Y runs negative
	 * upward, so the peak is the most negative vertex and the height is its
	 * distance from the ground plane.
	 */
	private static int modelHeight(net.runelite.api.Model model)
	{
		if (model == null || model.getVerticesY() == null || model.getVerticesCount() == 0)
		{
			return 0;
		}
		float[] ys = model.getVerticesY();
		float highest = 0;
		for (int i = 0; i < model.getVerticesCount() && i < ys.length; i++)
		{
			if (ys[i] < highest)
			{
				highest = ys[i];
			}
		}
		return Math.round(-highest);
	}

	/**
	 * Whether an NPC tall enough to swallow the follower occupies this tile.
	 *
	 * <p>Hiding exists because a follower standing inside something reads as a
	 * rendering fault. That is true of anything roughly person-sized and up; it
	 * is not true of a rat, where the two simply overlap the way two players on
	 * one tile do, and vanishing is the more jarring of the two.
	 *
	 * <p>Both parties have to be still for it: the follower is checked by its
	 * caller, and the NPC here. Two things settled on one tile look wrong for
	 * as long as they stay there, which is worth hiding. One passing through is
	 * over in a moment, and blinking out to cover it is the louder event.
	 *
	 * <p>The possessed thrall is excluded: in thrall mode the follower stands
	 * exactly where it is deliberately, and it is hidden from the renderer
	 * anyway.
	 *
	 * <p>Multi-tile NPCs are compared on their south-west tile, as the client
	 * itself positions them; a giant's outer tiles do not count as occupied.
	 */
	private boolean npcOnTile(WorldPoint where)
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (where == null || view == null)
		{
			return false;
		}
		int own = modelHeight(object == null ? null : object.getModel());
		for (net.runelite.api.NPC npc : view.npcs())
		{
			// Only once the NPC has STOPPED there. One walking through is a
			// moment, and blinking out as it crosses draws far more attention
			// than the overlap would have. An NPC on the move is playing a
			// walk pose; a standing one is on its idle pose, including while
			// it attacks, since that lives in the animation slot instead.
			if (npc == null || npc == thrallNpc || !where.equals(npc.getWorldLocation())
				|| !standing(npc) || !drawn.test(npc))
			{
				continue;
			}

			// Only the NPCs actually on the tile are measured, so the vertex
			// walk happens for at most a handful of models, and only until
			// each type has been seen once.
			Integer height = npcHeights.get(npc.getId());
			if (height == null)
			{
				height = modelHeight(npc.getModel());
				if (height == 0)
				{
					// Not rendered yet. Say no rather than caching a zero that
					// would make this NPC permanently short.
					continue;
				}
				npcHeights.put(npc.getId(), height);
			}
			if (own <= 0 || height >= own * OVERLAP_HEIGHT_FRACTION)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Another player standing still on the follower's tile. Play test, 16 Oct:
	 * two player-sized models on one tile overlap as badly as an NPC's does,
	 * so the follower gives way the same way. Not the local player, whom the
	 * follower keeps off by itself, and only once they have stopped there.
	 */
	private boolean playerOnTile(WorldPoint where)
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (where == null || view == null)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		for (Player other : view.players())
		{
			if (other == null || other == local || !where.equals(other.getWorldLocation()))
			{
				continue;
			}
			if (standing(other) && drawn.test(other))
			{
				return true;
			}
		}
		return false;
	}

	/** Stopped: on its idle pose, or turning on the spot, which plays the idle's turn. */
	private static boolean standing(net.runelite.api.Actor actor)
	{
		int pose = actor.getPoseAnimation();
		return pose == actor.getIdlePoseAnimation()
			|| pose == actor.getIdleRotateLeft() || pose == actor.getIdleRotateRight();
	}

	/**
	 * Whether the client will draw this actor: another plugin (Entity Hider)
	 * can hide players and NPCs, and the follower does not give way to
	 * someone nobody can see. Everyone, until the plugin says.
	 */
	private java.util.function.Predicate<net.runelite.api.Actor> drawn = actor -> true;

	public void setDrawn(java.util.function.Predicate<net.runelite.api.Actor> drawn)
	{
		this.drawn = drawn == null ? actor -> true : drawn;
	}

	// public:cut dev
	/** How long the follower has stood still well out of a follow's reach of the player. */
	private long stalledMs;
	static final long STALL_NOTE_MS = 2000;

	/** Whose stay holds the follower, in a word or two, for that line. */
	private java.util.function.Supplier<String> stayOwner = () -> "unknown";

	public void setStayOwner(java.util.function.Supplier<String> owner)
	{
		stayOwner = owner == null ? () -> "unknown" : owner;
	}

	/**
	 * QA5: the follower sometimes stood still 5 to 24 tiles from the player
	 * until the leash snapped it over, and nothing on record said why. Two
	 * seconds of that, once, and the trace says what held it.
	 */
	private void noteAStall(boolean moving, WorldPoint playerTile, long deltaMs)
	{
		int gap = tile.getPlane() == playerTile.getPlane() ? tile.distanceTo(playerTile) : 0;
		stalledMs = !moving && gap > followDistance + 3 ? stalledMs + deltaMs : 0;
		if (stalledMs < STALL_NOTE_MS || stalledMs - deltaMs >= STALL_NOTE_MS)
		{
			return;
		}
		String why;
		if (stayTile != null)
		{
			why = "a stay at " + CrossingWatch.describe(stayTile) + " (" + stayOwner.get() + ")"; // public:cut agility,agility-all
			// public:else why = "a stay at " + describe(stayTile) + " (" + stayOwner.get() + ")";
		}
		else if (goalTile != null)
		{
			why = "a goal at " + CrossingWatch.describe(goalTile); // public:cut agility,agility-all
			// public:else why = "a goal at " + describe(goalTile);
		}
		else if (playerFollowTile == null || simTile == null)
		{
			why = "no follow tile";
		}
		else
		{
			java.util.List<WorldPoint> path = playerFollowTile.getPlane() != simTile.getPlane() ? null
				: findPath(simTile, playerFollowTile, true, playerFollowTile.equals(playerTile) ? playerTile : null);
			why = (path == null ? "no route to " : path.isEmpty() ? "as near as it gets to "
				: "a route of " + path.size() + " not walked to ") + CrossingWatch.describe(playerFollowTile); // public:cut agility,agility-all
				// public:else : "a route of " + path.size() + " not walked to ") + describe(playerFollowTile);
		}
		agilityTrace.accept("follower: still " + gap + " tiles from the player at "
			+ CrossingWatch.describe(playerTile) + " for " + CrossingWatch.seconds(stalledMs) + ": " + why); // public:cut agility,agility-all
			// public:else + describe(playerTile) + " for " + String.format("%.1fs", stalledMs / 1000.0) + ": " + why);
	}

	// public:end dev
	/**
	 * Every tile an NPC stands on, by its whole footprint (a giant's four, not
	 * only the south-west one it is placed by), with the NPC on it. The
	 * possessed thrall is not counted, as in npcOnTile.
	 */
	java.util.Map<WorldPoint, net.runelite.api.NPC> npcFootprints()
	{
		java.util.Map<WorldPoint, net.runelite.api.NPC> taken = new java.util.HashMap<>();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return taken;
		}
		for (net.runelite.api.NPC npc : view.npcs())
		{
			WorldPoint at = npc == null || npc == thrallNpc ? null : npc.getWorldLocation();
			if (at == null)
			{
				continue;
			}
			int size = npc.getComposition() == null ? 1 : Math.max(1, npc.getComposition().getSize());
			for (int dx = 0; dx < size; dx++)
			{
				for (int dy = 0; dy < size; dy++)
				{
					taken.put(at.dx(dx).dy(dy), npc);
				}
			}
		}
		return taken;
	}

	/** The top-level plane, or 0 while there is no world view to ask. */
	private int plane()
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		return view == null ? 0 : view.getPlane();
	}

	/** Collision flags for a tile, or null when no data covers it. */
	private Integer flagAt(WorldPoint tile)
	{
		// The view the tile is IN, not the top-level one. Aboard a boat the
		// player stands in the boat's own sub-world with its own collision
		// map, and reading the sea's map at the boat's scene coordinates was
		// how the follower came to walk the water beside the hull.
		net.runelite.api.WorldView view = client.findWorldViewFromWorldPoint(tile);

		// And aboard, the boat is the whole world: a tile that resolves to any
		// other view is the sea, and the sea's map reading open, or missing,
		// must not make it somewhere to stand.
		Player local = client.getLocalPlayer();
		net.runelite.api.WorldView mine = local == null ? null : local.getWorldView();
		if (mine != null && !mine.isTopLevel() && (view == null || view.getId() != mine.getId()))
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		}
		if (view == null)
		{
			return null;
		}
		// By x and y: the tile's own floor is read below, whichever the player is on.
		LocalPoint localPoint = LocalPoint.fromWorld(view, tile.getX(), tile.getY());
		if (localPoint == null)
		{
			return null;
		}
		return sceneFlags(view, tile.getPlane(), localPoint.getSceneX(), localPoint.getSceneY());
	}

	/**
	 * Collision flags at a scene coordinate of one view, or null when no data
	 * covers it.
	 *
	 * <p>The client pads a sub-world's map: it builds it (sizeX + 6) by
	 * (sizeY + 6) with both insets at -1, so scene (x, y) lives at [x + 1][y + 1]
	 * aboard a boat and at [x][y] on the top level, whose map is not padded.
	 * Read unshifted, a deck's facilities came out open and its walkway
	 * blocked, which is exactly where the follower then stood and walked.
	 */
	public static Integer sceneFlags(net.runelite.api.WorldView view, int plane, int sceneX, int sceneY)
	{
		CollisionData[] maps = view.getCollisionMaps();
		if (maps == null || plane >= maps.length || maps[plane] == null)
		{
			return null;
		}
		int[][] flags = maps[plane].getFlags();
		int inset = flags.length == view.getSizeX() + 6 ? 1 : 0;
		int sx = sceneX + inset;
		int sy = sceneY + inset;
		if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length)
		{
			return null;
		}
		return flags[sx][sy];
	}

	/** The eight steps round a tile, cardinals first: the order a step aside tries them. */
	private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};

	/** How far around the player a snap may look for somewhere legal to land. */
	private static final int LANDING_SEARCH_RADIUS = 2;

	/**
	 * Where an arrival puts the follower. An ordinary one goes through
	 * {@link #landingTile}, which draws it back beside the player. A post it
	 * was left waiting on is taken as given where it could stand and walk to
	 * it from the player within its stay leash, since coming back to it is the
	 * point; anywhere else it lands beside the player like any arrival.
	 *
	 * <p>Nor in furniture the map calls open floor: a post kept from before
	 * the feet refused to stop in a booth would put it back in one at every
	 * login.
	 */
	WorldPoint arrivalTile(WorldPoint wanted, WorldPoint anchor, boolean post)
	{
		if (post && wanted != null && anchor != null && wanted.getPlane() == anchor.getPlane()
			&& wanted.distanceTo(anchor) <= STAY_LEASH - 2 && goodGround(wanted) && !furnished(wanted))
		{
			java.util.List<WorldPoint> path = findPath(anchor, wanted, false, null);
			if (path != null && (path.isEmpty() ? anchor.equals(wanted)
				: path.get(path.size() - 1).equals(wanted)))
			{
				return wanted;
			}
		}
		return landingTile(wanted, anchor);
	}

	/**
	 * Where a snap actually puts the follower.
	 *
	 * <p>Every teleport, plane change and scene recovery used to drop it on a
	 * tile picked by arithmetic alone - one step west of the player - and
	 * arithmetic does not know about walls. Play report: the follower
	 * teleporting onto tiles it could not stand on.
	 *
	 * <p>So the wanted tile is taken only if the follower could actually WALK
	 * there from the player, which is stricter than merely standable and rules
	 * out landing through a wall into a closet. Failing that, the nearest tile
	 * that qualifies. Failing even that - a player boxed into a single tile -
	 * the player's own, which is standable by definition because they are
	 * standing on it: sharing a tile for a moment is a far smaller lie than
	 * standing inside the scenery.
	 */
	WorldPoint landingTile(WorldPoint wanted, WorldPoint anchor)
	{
		if (anchor == null)
		{
			return wanted;
		}

		java.util.Set<WorldPoint> reachable = reachableFrom(anchor, LANDING_SEARCH_RADIUS);
		// Nor inside furniture the map leaves open, or inside an NPC, unless
		// that is all there is: a player standing in a flowerbed still gets
		// the follower beside them in it, not on top of them.
		java.util.Set<WorldPoint> clear = new java.util.HashSet<>(reachable);
		java.util.Map<WorldPoint, net.runelite.api.NPC> taken = npcFootprints();
		clear.removeIf(at -> !at.equals(anchor) && (furnished(at) || taken.containsKey(at)));
		if (clear.size() > 1)
		{
			reachable = clear;
		}
		if (wanted != null && reachable.contains(wanted))
		{
			return wanted;
		}

		WorldPoint target = wanted == null ? anchor : wanted;
		WorldPoint best = anchor;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint candidate : reachable)
		{
			if (candidate.equals(anchor))
			{
				// The last resort, not a preference: only if nothing else fits.
				continue;
			}
			int distance = candidate.distanceTo(target);
			// Coordinates break ties so the choice cannot jitter between
			// frames on the iteration order of a hash set.
			if (distance < bestDistance
				|| (distance == bestDistance && comesFirst(candidate, best)))
			{
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean comesFirst(WorldPoint candidate, WorldPoint against)
	{
		return candidate.getX() != against.getX()
			? candidate.getX() < against.getX()
			: candidate.getY() < against.getY();
	}

	/**
	 * Out of the flames by the shortest honest route: one step to open
	 * ground, cardinals first because a straight step reads as moving
	 * aside where a diagonal reads as drifting. A held stay keeps its
	 * post, one tile over; a following follower takes it as a goal, which
	 * the player's next step clears on its own.
	 *
	 * <p>Never onto the player. Lighting a line of fires puts them on the
	 * tile beside the one they just lit, so their tile is the second thing
	 * tried - and it is also the one tile every route is told to walk
	 * AROUND, so the escape could not be walked at all. Play report: the
	 * follower leaves the first fire and stands in every fire after it.
	 *
	 * <p>And a refused escape tries the next way out rather than none at
	 * all: one geometric candidate the feet would not take used to end the
	 * attempt, leaving the follower exactly where it was.
	 */
	private void stepOffTheFire()
	{
		WorldPoint player = playerTile(client);
		boolean staying = stayTile != null;
		for (int[] step : AROUND)
		{
			WorldPoint off = tile.dx(step[0]).dy(step[1]);
			if (off.equals(player) || !goodGround(off)
				|| !canStep(tile, step[0], step[1]))
			{
				continue;
			}
			if (staying)
			{
				if (!stayAt(off))
				{
					continue;
				}
			}
			else
			{
				goalTile = off;
				serverPath.clear();
			}
			log.debug("Standing in a fire at {}; stepping to {}", tile, off); // public:cut logs
			return;
		}
	}

	// public:cut dev
	/**
	 * Where the feet are and what owns them, for {@code ::follower fires}.
	 * Every one of these is a route onto a tile, so a fire showing up in any
	 * of them is the answer.
	 */
	public java.util.List<String> describeFeet()
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add("Follower at " + tile + (hot(tile) ? " - STANDING IN A FIRE" : "")
			+ " sim " + simTile + (isSettled() ? " settled" : " walking"));
		lines.add("  stay " + stayTile + hotness(stayTile)
			+ " | goal " + goalTile + hotness(goalTile)
			+ " | follow " + playerFollowTile + hotness(playerFollowTile) // public:cut errands
			// public:else + " | follow " + playerFollowTile + hotness(playerFollowTile));
			+ " | insisted " + insistedTile); // public:cut errands
		lines.add("  path " + serverPath.size() + " route " + route.size()
			+ " beside " + beside + " stranded " + strandedTicks);
		StringBuilder queued = new StringBuilder();
		for (WorldPoint step : serverPath)
		{
			queued.append(step).append(hotness(step)).append(' ');
		}
		lines.add("  queued: " + (queued.length() == 0 ? "(nothing)" : queued.toString()));
		return lines;
	}

	private String hotness(WorldPoint at)
	{
		return at != null && hot(at) ? " [FIRE]" : "";
	}

	// public:end dev
	/**
	 * Told when a teleport, a plane change or a jump past the leash puts the
	 * follower back beside the player, before it is shown there: whatever had
	 * it elsewhere is over. Behaviour QA, 15 Oct: an errand's scroll and the
	 * minutes' writing pose could be drawn beside the landed player until
	 * the next game tick noticed they had gone.
	 */
	@lombok.Setter
	private Runnable onSnapped;

	private void snapped()
	{
		if (onSnapped != null)
		{
			onSnapped.run();
		}
	}

	/** Re-places the follower beside the player after a teleport or plane change. */
	private boolean snapBeside(WorldPoint playerTile, String why) // public:cut dev
	// public:else private boolean snapBeside(WorldPoint playerTile)
	{
		WorldPoint beside = landingTile(playerTile.dx(-1), playerTile);

		// public:cut dev
		agilityTrace.accept("follower: snap (" + why + ") from " + CrossingWatch.describe(tile) // public:cut agility,agility-all
		// public:else agilityTrace.accept("follower: snap (" + why + ") from " + describe(tile)
			+ " to " + CrossingWatch.describe(beside) // public:cut agility,agility-all
			// public:else + " to " + describe(beside));
			// public:cut agility,agility-all
			+ (isDoingAgility() ? ", letting go of " + (crossings.size() + (playing != null ? 1 : 0))
			+ " crossings" : ""));
			// public:end agility,agility-all
		// public:end dev
		// A forced relocation ends any pose - the tile it was holding is gone,
		// public:cut agility,agility-all
		// and the way the pose faced with it. And any agility it was copying.
		dropCrossings();
		if (onAgilitySnap != null)
		{
			onAgilitySnap.run();
		}
		// public:end agility,agility-all
		dropPose();
		putAt(beside);
		lastMoveSpeed = 0;
		burst = false;
		applyPose(stance().idle, false);
		return render();
	}

	/**
	 * Brings the follower back when its coordinates no longer resolve into the
	 * loaded scene. Falls back through: re-anchor beside the player in world
	 * space, then pin directly to the player's local coordinates - the last step
	 * always works while the player is being rendered.
	 */
	private void recoverPosition(Player local, WorldPoint playerTile, String why) // public:cut dev
	// public:else private void recoverPosition(Player local, WorldPoint playerTile)
	{
		// Only while genuinely logged in: a chunk load is a seamless rebuild
		// the scene settles from by itself.
		if (!loggedIn() || playerTile != null && snapBeside(playerTile, why)) // public:cut dev
		// public:else if (!loggedIn() || playerTile != null && snapBeside(playerTile))
		{
			return;
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
		agilityTrace.accept("follower: pinned to the player's scene position (" + why + ")"); // public:cut dev

		// Last ditch, and still not licence to stand in a wall: pick the
		// landing tile in world space and come back to local, falling back to
		// the player's own spot if that cannot be resolved.
		LocalPoint beside = null;
		if (playerTile != null)
		{
			WorldPoint landing = landingTile(playerTile.dx(-1), playerTile);
			beside = LocalPoint.fromWorld(client, landing);
		}
		if (beside == null)
		{
			beside = playerLocal;
		}

		lastRenderedLocation = beside;
		object.setLocation(beside, client.getWorldView(beside.getWorldView()).getPlane());
		object.setOrientation(yaw);
		if (!object.isActive())
		{
			object.setActive(true);
		}
	}

	private boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	/**
	 * Where a world tile is in the scene, on whichever floor it is. The
	 * client's own conversion answers null for a tile off the player's
	 * floor, which was right while the follower only ever stood where they
	 * did; a floor behind them on a rooftop course it still has to be drawn
	 * and routed on its own floor.
	 */
	private LocalPoint sceneOf(WorldPoint at)
	{
		net.runelite.api.WorldView view = client.findWorldViewFromWorldPoint(at);
		return view == null ? null : LocalPoint.fromWorld(view, at.getX(), at.getY());
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
		LocalPoint base = sceneOf(baseTile);
		if (base == null)
		{
			lastRenderedLocation = null;
			return false;
		}
		// Drawn in the view the tile resolved to: a point tagged with the
		// top-level view while the player is aboard a boat is placed, and
		// given its height, in the sea outside the hull.
		net.runelite.api.WorldView drawView = client.getWorldView(base.getWorldView());

		// A real NPC standing on the follower's tile: give way and go
		// invisible for as long as they share it. Two player-sized models on
		// one tile interpenetrate badly, and the follower is the guest here.
		// Position keeps updating underneath, so it reappears in the right
		// place the moment the tile clears.
		// Only once STOPPED on the shared tile: walking through an NPC is a
		// moment, and blinking out mid-stride draws more attention than the
		// overlap it hides.
		hiddenForNpcOverlap = isSettled() && (npcOnTile(tile) || playerOnTile(tile));
		if (hiddenForNpcOverlap)
		{
			if (object.isActive())
			{
				object.setActive(false);
				// Whatever it is wearing goes with it, and comes back with it.
				showGraphics(false);
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
			showGraphics(true);
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
		// What is actually on screen: an emote owns the animation slot without
		// touching activePose, so a kneel or a stand played as an emote has to
		// be read from the emote itself or it goes uncorrected.
		int shownAnimation = emotePlaying ? emoteAnimation : activePose;

		if (RECENTRED_POSES.contains(shownAnimation))
		{
			int[] centre = recentreFrozen ? null : modelCentre();
			if (centre != null)
			{
				// A running mean, then frozen. The two obvious approaches both
				// fail: a single reading when the pose begins measures a
				// follower still standing, because HUMAN_PRAY_LOOP is upright
				// at frame 0 and only kneels by frame 5; and following the
				// centroid live slides the model around all through the loop,
				// because the body genuinely shifts as it breathes.
				//
				// The mean settles within a second and each further sample
				// moves it less, so the model eases into place and then stops.
				// Freezing outright after that makes it exactly still, which is
				// the point - the model is meant to sit on its tile, not hunt
				// for it.
				recentreSum += centre[1] - STANDING_CENTRE_Z;
				recentreSamples++;
				recentreForward = (int) (recentreSum / recentreSamples) + recentreBias; // public:cut dev
				// public:else recentreForward = (int) (recentreSum / recentreSamples);
				recentreFrozen = recentreSamples >= RECENTRE_FREEZE_SAMPLES;
			}
		}
		else
		{
			recentreForward = 0;
			recentreSum = 0;
			recentreSamples = 0;
			recentreFrozen = false;
		}

		// The logical position stays the tile: facing, distance and the
		// graphics all reason about where the follower IS, not where its model
		// happened to be drawn.
		lastRenderedLocation = new LocalPoint(lx, ly, drawView);

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
			: new LocalPoint(drawX, drawY, drawView), tile.getPlane());

		// Height from the interpolated position: getTileHeight interpolates WITHIN
		// a tile, which is how a real player glides over slopes. Z is inverted, so
		// subtracting raises the model clear of the ground.
		int height = Perspective.getTileHeight(client, lastRenderedLocation, tile.getPlane());
		lastRenderedZ = height - verticalOffset;
		object.setZ(lastRenderedZ);

		object.setOrientation(yaw);

		// A graphic still downloading goes on the frame it lands.
		placePendingGraphics();

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
		// The writing let go of with the scroll up: it is lowered first, the
		// pose's own way down, before whatever comes next.
		boolean putAway = poseOverride == ErrandController.WRITE_SCROLL_POSE
			&& animationId != poseOverride && writingShown();
		poseOverride = animationId;
		activePose = -1;
		if (putAway)
		{
			playAnimation(WRITE_PUT_AWAY);
		}
	}

	/** The forced pose held now, or 0. */
	public int getPoseOverride()
	{
		return poseOverride;
	}

	/**
	 * Whether it was on the move at the last frame. A forced pose only shows
	 * while it stands, so this is also whether the walk has it instead.
	 */
	public boolean isMoving()
	{
		return onTheMove;
	}

	/** Lets go of a forced pose, unless something else has put its own on since. */
	public void clearPoseOverride(int animationId)
	{
		if (poseOverride == animationId)
		{
			setPoseOverride(0);
		}
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

	/** Notified the moment an emote starts or ends. */
	@lombok.Setter
	private java.util.function.Consumer<Boolean> onEmoteStateChanged;

	/** Notified as each clip of an emote goes on, with the clip's id, so its hands can be dressed. */
	@lombok.Setter
	private java.util.function.IntConsumer onEmoteClip;

	private void emoteClipOn(int clip)
	{
		if (onEmoteClip != null)
		{
			onEmoteClip.accept(clip);
		}
	}

	/**
	 * The one place emotePlaying changes, so anything that needs to react does
	 * so AS it happens rather than on the next tick. Half a second of a wave
	 * still holding a sword is most of a short emote.
	 */
	private void setEmotePlaying(boolean playing)
	{
		if (emotePlaying == playing)
		{
			return;
		}
		emotePlaying = playing;
		if (!playing)
		{
			puttingAway = false;
		}
		if (onEmoteStateChanged != null)
		{
			onEmoteStateChanged.accept(playing);
		}
		if (!playing)
		{
			passEmoteMilestones(true);
		}
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
		// public:cut dev
		AnimationController controller = wrapLerp
			? new WrapLerpController(client, animation)
			: new AnimationController(client, animation);
		// public:end dev
		// public:else AnimationController controller = new WrapLerpController(client, animation);
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

		controllerGeneration++; // public:cut dev
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
		// Never touch a one-shot. Skipping the final frame of an emote means it never
		// finishes, onFinished never fires, and the follower is stuck replaying it
		// forever with the pose logic locked out behind emotePlaying.
		if (wrapTrim <= 0 || object == null || emotePlaying) // public:cut dev
		// public:else if (object == null || emotePlaying)
		{
			return;
		}
		AnimationController controller = object.getAnimationController();
		Animation animation = controller == null ? null : controller.getAnimation();
		// The lerp controller renders the final frame correctly - trimming it too
		// would cut the very segment being interpolated. But if its safety guard has
		// switched it off, the trim must take back over or the raw hold shows. And
		// only worth doing when smoothing is actually on: without interpolation
		// there is no dead-end frame to avoid, so trimming one would cost a frame
		// of the cycle for nothing.
		if (wrapLerp && controller instanceof WrapLerpController && !((WrapLerpController) controller).isDisabled() // public:cut dev
		// public:else if (controller instanceof WrapLerpController && !((WrapLerpController) controller).isDisabled()
			|| animation == null || animation.getFrameLengths() == null || !isSmoothingActive(animation))
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

		return Math.min(wrapTrim, Math.max(1, frames / 6)); // public:cut dev
		// public:else return 1;
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

		log.debug("Animation {}: {} frames, measured trim {}", animation.getId(), frames, bestTrim); // public:cut logs
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
		return WrapLerpController.snapshot(client.applyTransformations(baseModel, animation, frame, null, 0));
	}

	// public:cut dev
	/** Interpolate across the loop boundary instead of trimming frames off it. */
	private boolean wrapLerp = true;

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

	// public:end dev
	public java.util.Map<Integer, Integer> getWrapTrims()
	{
		return new java.util.LinkedHashMap<>(wrapTrims);
	}

	public java.util.Map<Integer, Integer> getMeasuredTrims()
	{
		return new java.util.LinkedHashMap<>(measuredTrims);
	}

	// public:cut dev
	/** Drops manual overrides so measurement takes over again. */
	public void clearWrapTrims()
	{
		wrapTrims.clear();
		measuredTrims.clear();
	}

	// public:end dev
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

	// public:cut dev
	/** Fallback trim for animations that haven't been tuned individually. */
	public void setDefaultWrapTrim(int frames)
	{
		wrapTrim = Math.max(0, frames);
	}

	// public:end dev
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

		int frames = frameCount(animation);
		if (controller.getFrame() < 0 || controller.getFrame() >= frames)
		{
			controller.setFrame(0);
		}
	}

	/** An animation's frame count; 0 for none. */
	private static int frameCount(Animation animation)
	{
		int[] lengths = animation == null ? null : animation.getFrameLengths();
		return lengths == null ? 0 : lengths.length;
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
	private static final java.util.Set<Integer> RECENTRED_POSES = Resources.ids("recentredPoses");

	/**
	 * How much of the model's own offset to leave alone.
	 *
	 * <p>Was the standing reading of -11, on the reasoning that a model's
	 * natural asymmetry is not the animation's doing and should be left as it
	 * is. Correcting only the difference left the follower visibly short of the
	 * middle though, and the question being answered is simply "is it on its
	 * tile" - which means putting the model's mass on the tile centre, all of
	 * it, not the part of it the animation moved.
	 *
	 * <p>Past zero and into a positive bias by eye. The centroid is an average
	 * over every vertex, so a kneeling figure is weighted by wherever the bulk
	 * of the mesh sits rather than by where the body reads as being, and the
	 * two do not have to agree. Judged in game rather than derived: the
	 * measurement gets within about a tenth of a tile and the rest is looking.
	 *
	 * <p>Settled at 13 over three passes - the delta alone fell short, zero
	 * fell short, and eight was still a few units shy - and then at 22 once
	 * there was something to measure against. Every earlier pass judged a bare
	 * model against a tile whose centre has to be imagined; the spectating ward
	 * draws a circle on that centre, so the kneel is suddenly sitting inside a
	 * ring that either fits it or does not. It did not. The model read forward
	 * of the ring by about the width of a foot, which is exactly the sort of
	 * error an imagined centre hides and a drawn one cannot.
	 *
	 * <p>Still an eye judgement rather than a derivation, and still adjustable
	 * live with {@code ::follower centre <n>} - negative pulls further back.
	 */
	private static final int STANDING_CENTRE_Z = 22;

	// public:cut dev
	/** Live nudge from ::follower centre, for dialling the last few units in. */
	private int recentreBias;

	public void setRecentreBias(int units)
	{
		recentreBias = units;
		// Re-measure from scratch so the change is visible immediately rather
		// than after the current pose ends.
		recentreSum = 0;
		recentreSamples = 0;
		recentreFrozen = false;
	}

	public int getRecentreBias()
	{
		return recentreBias;
	}

	// public:end dev
	/**
	 * Frames averaged before the correction is fixed for good. About two
	 * seconds at a normal frame rate, comfortably more than one cycle of the
	 * prayer loop, so the mean covers the whole range of the pose rather than
	 * whichever part of it happened to be showing.
	 */
	private static final int RECENTRE_FREEZE_SAMPLES = 100;

	/** The correction currently applied, in model units along the facing. */
	private int recentreForward;

	private long recentreSum;
	private int recentreSamples;
	private boolean recentreFrozen;

	/** The clip an emote is showing right now, since activePose is not it. */
	private int emoteAnimation = -1;

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

	// public:cut dev
	/**
	 * The follower's own model height and that of every NPC within a few
	 * tiles, for {@code ::follower heights}. The overlap rule is a comparison
	 * between these two numbers, so seeing them is how the threshold gets
	 * checked rather than trusted.
	 */
	public java.util.List<String> describeHeights()
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		int own = modelHeight(object == null ? null : object.getModel());
		lines.add("follower height " + own + ", hides at "
			+ Math.round(own * OVERLAP_HEIGHT_FRACTION) + " and above");
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (tile == null || view == null)
		{
			return lines;
		}
		for (net.runelite.api.NPC npc : view.npcs())
		{
			if (npc == null || npc.getWorldLocation() == null
				|| npc.getWorldLocation().distanceTo(tile) > 3)
			{
				continue;
			}
			int height = modelHeight(npc.getModel());
			lines.add(String.format("  %-24s height %-5d %s",
				npc.getName(), height,
				own > 0 && height >= own * OVERLAP_HEIGHT_FRACTION ? "HIDES" : "shows through"));
		}
		return lines;
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

	// public:end dev
	/**
	 * What an animation puts in each hand, as the client gives it: left then
	 * right, each -1 to keep what that hand holds, -512 to empty it, or an
	 * item id. Null when the animation does not load.
	 */
	public int[] handItems(int animationId)
	{
		Animation animation = client.loadAnimation(animationId);
		return animation == null ? null
			: new int[]{animation.getLeftHandItem(), animation.getRightHandItem()};
	}

	/**
	 * What an emote's clip puts in the hands, as {@link #handItems}, when it
	 * names a thing for either hand; null when it names nothing, and the
	 * hands stay as the emote disarm leaves them.
	 */
	public int[] emoteHandItems(int animationId)
	{
		int[] hands = handItems(animationId);
		return hands != null && (hands[0] > 0 || hands[1] > 0) ? hands : null;
	}

	/** Plays a one-shot animation, then resumes the walk/idle pose. */
	public void playAnimation(int animationId)
	{
		playAnimations(new int[]{animationId});
	}

	/** A one-shot waiting for the follower to stand, and the tick it gives up; or -1. */
	private int waitingEmote = -1;
	private int waitingEmoteUntil;

	/**
	 * Plays a one-shot now if the follower is standing, or as soon as it
	 * stands within so many ticks, and not at all after that. Moving drops an
	 * emote on its first frame, so one asked for on the move was simply lost.
	 */
	public void playWhenStill(int animationId, int ticks)
	{
		if (!onTheMove)
		{
			waitingEmote = -1;
			playAnimation(animationId);
			return;
		}
		waitingEmote = animationId;
		waitingEmoteUntil = client.getTickCount() + ticks;
	}

	/** Plays what {@link #playWhenStill} kept, once it stands. Every frame. */
	private void playWhatWaitedForStill()
	{
		if (waitingEmote < 0 || onTheMove || emotePlaying)
		{
			if (waitingEmote >= 0 && client.getTickCount() > waitingEmoteUntil)
			{
				waitingEmote = -1;
			}
			return;
		}
		int animationId = waitingEmote;
		waitingEmote = -1;
		if (client.getTickCount() <= waitingEmoteUntil)
		{
			playAnimation(animationId);
		}
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
		playAnimations(animationIds, graphics, 0);
	}

	/**
	 * @param graphicHeight how far up the model to lift each stage's spotanim.
	 * Zero for a rune circle on the floor; body height for a teleport swirl.
	 */
	public void playAnimations(int[] animationIds, SpotAnimRepository.Entry[] graphics,
		int graphicHeight)
	{
		if (object == null || !animatable || animationIds == null || animationIds.length == 0)
		{
			return;
		}
		// The one-shot this takes over from is as good as over for whatever
		// was waiting on it.
		passEmoteMilestones(true);
		fetchGraphics(graphics);
		playChain(animationIds, graphics, graphicHeight, 0, emoteGeneration);
	}

	private void playChain(int[] ids, SpotAnimRepository.Entry[] graphics, int graphicHeight,
		int index, int generation)
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
			setEmotePlaying(false);
			activePose = -1; // force the next frame to re-apply the locomotion pose
			consumeVanish();
			return;
		}

		int clip = clipOf(ids[index]);
		Animation animation = client.loadAnimation(clip);
		if (animation == null)
		{
			log.debug("Animation {} not in cache, skipping", clip); // public:cut logs
			playChain(ids, graphics, graphicHeight, index + 1, generation);
			return;
		}

		AnimationController controller = EASED_STANCES.contains(clip)
			? new EaseInOutController(client, animation, stance().idle)
			: new AnimationController(client, animation);
		int firstFrame = firstFrameOf(ids[index]);
		if (firstFrame > 0)
		{
			controller.setFrame(firstFrame);
		}
		controller.setOnFinished(finished -> playChain(ids, graphics, graphicHeight, index + 1, generation));
		object.setAnimationController(controller);
		puttingAway = ids[index] == WRITE_PUT_AWAY;
		setEmotePlaying(true);
		// Which clip is on screen right now: activePose is not updated while an
		// emote owns the slot, so anything that needs to know what is being
		// drawn - the tile recentring - has to read it from here.
		emoteAnimation = clip;
		emoteClipOn(clip);

		if (graphics != null && index < graphics.length && graphics[index] != null)
		{
			playSpotAnim(graphics[index], graphicHeight);
		}
	}

	/** The Sit emote, which the stand-up is the second half of. */
	static final int SIT_EMOTE = 10053;

	/** Where the Sit emote's stand-up half begins: the seated pose, about to rise. */
	static final int SIT_EMOTE_STAND_UP_FRAME = 17;

	/**
	 * The stand-up from a sit, as an id of its own: the Sit emote from frame
	 * 17. The emote starts standing, sits down, holds and stands; played
	 * whole from a sit, the follower popped up to full height, sat back down
	 * and stood again, 2.8 seconds of up-down-up (QA, 15 Oct). Frames 17-25
	 * rise from exactly the seated pose in 50 client cycles, a second. Kept
	 * apart from 10053 itself, which stays whole where it is the emote: the
	 * follower copying a player's Sit. Above any id the cache has.
	 */
	public static final int STAND_UP = 0x10000 + SIT_EMOTE;

	/**
	 * The writing pose's own lowering, as an id of its own: 13302 from frame
	 * 17, the scroll and quill coming down to the waist (frames 18-21, 32
	 * client cycles). Played when the writing is let go of, so the scroll is
	 * put away rather than switched off mid-air (QA, 15 Oct).
	 */
	public static final int WRITE_PUT_AWAY = 0x10000 + ErrandController.WRITE_SCROLL_POSE;

	/** Where 13302's lowering begins. */
	static final int WRITE_PUT_AWAY_FRAME = 17;

	/** The one-shot prayer kneel, whose last frames are the rise from it. */
	static final int PRAY = 645;

	/**
	 * Up off the knees from the held prayer, HUMAN_PRAY_LOOP: the one-shot
	 * kneel, 645, from frame 8, which is the loop's kneel exactly, through
	 * its rise. HUMAN_GETUP starts 97-103 units from that kneel, a getting
	 * up from the floor rather than from the knees (cache, 15 Oct).
	 */
	public static final int PRAY_STAND_UP = 0x10000 + PRAY;
	static final int PRAY_STAND_UP_FRAME = 8;

	/** The clip an id plays: the halves are their emotes, anything else itself. */
	static int clipOf(int id)
	{
		return id == STAND_UP ? SIT_EMOTE
			: id == WRITE_PUT_AWAY ? ErrandController.WRITE_SCROLL_POSE
			: id == PRAY_STAND_UP ? PRAY : id;
	}

	/** The frame an id's clip starts on. */
	static int firstFrameOf(int id)
	{
		return id == STAND_UP ? SIT_EMOTE_STAND_UP_FRAME
			: id == WRITE_PUT_AWAY ? WRITE_PUT_AWAY_FRAME
			: id == PRAY_STAND_UP ? PRAY_STAND_UP_FRAME : 0;
	}

	/**
	 * Writing with a quill held as a pose. 13302 is authored to play once,
	 * with its own lift (frames 0-3) and lowering (18-21) built in; looped
	 * whole, the follower lowered the scroll to its waist and lifted it again
	 * every 2.9 seconds of a long hold (QA, 15 Oct). Frame 16 is frame 9
	 * exactly, so on reaching 16 it goes back to 9 and writes on with no seam:
	 * the lift once on the way in, then frames 9-15 for as long as it is held.
	 */
	static final int WRITE_LOOP_END = 16;
	static final int WRITE_LOOP_START = 9;

	/** The first frame with the scroll up and writing, past the lift. */
	static final int WRITE_RAISED_FRAME = 4;

	/** Keeps a held writing pose on its writing frames. Every frame, after the pose. */
	private void holdTheWriting()
	{
		if (object == null || emotePlaying || activePose != ErrandController.WRITE_SCROLL_POSE)
		{
			return;
		}
		AnimationController controller = object.getAnimationController();
		if (controller != null && controller.getFrame() >= WRITE_LOOP_END)
		{
			controller.setFrame(WRITE_LOOP_START);
		}
	}

	/** Whether the writing pose is on screen now, with the scroll up. */
	private boolean writingShown()
	{
		if (object == null || emotePlaying || onTheMove
			|| activePose != ErrandController.WRITE_SCROLL_POSE)
		{
			return false;
		}
		AnimationController controller = object.getAnimationController();
		return controller != null && controller.getFrame() >= WRITE_RAISED_FRAME
			&& controller.getFrame() < WRITE_PUT_AWAY_FRAME;
	}

	/** Set while the writing's lowering plays. */
	private boolean puttingAway;

	/**
	 * Whether the scroll is on its way down: whoever lets go of the props
	 * waits for the end of it, or they vanish at the top of the lowering.
	 */
	public boolean isPuttingAway()
	{
		return emotePlaying && puttingAway;
	}

	/**
	 * The HUMAN_*_IDLE mood clips. They are standing stances, not emotes: the
	 * first and last frames sit 63-104 units from the normal standing pose,
	 * so played as a one-shot the body jumped into the mood and jumped back
	 * out about five seconds later (QA, 15 Oct). Eased in from the idle and
	 * back out to it instead.
	 */
	static final java.util.Set<Integer> EASED_STANCES = Resources.ids("easedStances");

	/** What waits on the one-shot playing now: how far through it, and what then. */
	private static final class Milestone
	{
		final double fraction;
		final Runnable then;

		Milestone(double fraction, Runnable then)
		{
			this.fraction = fraction;
			this.then = then;
		}
	}

	private final java.util.List<Milestone> emoteMilestones = new java.util.ArrayList<>();

	/**
	 * Runs {@code then} once the one-shot playing now is {@code fraction} of
	 * the way through its frames, or once it ends or gives way to another,
	 * whichever is first; at once when nothing is playing.
	 */
	public void atEmoteFraction(double fraction, Runnable then)
	{
		if (!emotePlaying)
		{
			then.run();
			return;
		}
		emoteMilestones.add(new Milestone(fraction, then));
	}

	/** Runs whatever the one-shot has got far enough for; all of it when it is over. */
	private void passEmoteMilestones(boolean over)
	{
		if (emoteMilestones.isEmpty())
		{
			return;
		}
		double through = 1;
		if (!over)
		{
			AnimationController controller = object == null ? null : object.getAnimationController();
			Animation animation = controller == null ? null : controller.getAnimation();
			int frames = frameCount(animation);
			through = frames == 0 ? 1 : controller.getFrame() / (double) frames;
		}
		java.util.List<Runnable> due = new java.util.ArrayList<>();
		for (java.util.Iterator<Milestone> it = emoteMilestones.iterator(); it.hasNext(); )
		{
			Milestone milestone = it.next();
			if (through >= milestone.fraction)
			{
				due.add(milestone.then);
				it.remove();
			}
		}
		due.forEach(Runnable::run);
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

	/** Set when the player is seen to land with the vanish still armed. */
	private boolean castLanded;

	/**
	 * Arms the teleport exit: when the current emote or slaved chain finishes,
	 * the follower vanishes - as the player does at the end of their cast - and
	 * reappears beside them once they land. Without this the follower stood at
	 * the departure spot after its cast, then walked over (FTRACE 2026-08-04:
	 * a 9-tile teleport held sim=9 indefinitely).
	 */
	public void vanishAfterEmote()
	{
		// Only over a cast that is playing: one that never started (no model
		// to play it on) would hold the vanish, and every release waiting on
		// the cast, until some later emote.
		if (emotePlaying)
		{
			vanishAfterEmoteArmed = true;
		}
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
		vanishPlayerTile = playerTile(client);
		if (object != null)
		{
			object.setActive(false);
		}
		releaseHeldOverTheCast();
	}

	/** Carries out a release that waited on the cast. */
	private void releaseHeldOverTheCast()
	{
		if (releaseAfterCast && slavedChain == null && !vanishAfterEmoteArmed)
		{
			resumeFollowing();
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
		fetchGraphics(graphics);
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
			// A cosmetic with more stages than ours (Leagues 6 has eight):
			// stay on the last one until the player's sequence ends, which
			// is when slavedPlayerStopped takes the follower with them.
			slavedIndex = slavedChain.length - 1;
			return;
		}

		Animation animation = client.loadAnimation(slavedChain[slavedIndex]);
		if (animation == null)
		{
			log.debug("Slaved chain animation {} not in cache, skipping", slavedChain[slavedIndex]); // public:cut logs
			advanceSlavedChain();
			return;
		}

		// No onFinished chaining: if the clip outlasts the player's stage, the
		// player's next change cuts it; if it runs out first, the model holds
		// its final frame until the next stage - the same look the server's
		// schedule produces on a real player. Held here: left to the default,
		// a finished clip starts again from its first frame.
		AnimationController controller = new AnimationController(client, animation);
		controller.setOnFinished(finished -> finished.setFrame(animation.isMayaAnim()
			? animation.getDuration() - 1 : animation.getFrameLengths().length - 1));
		object.setAnimationController(controller);
		setEmotePlaying(true);
		emoteClipOn(slavedChain[slavedIndex]);

		if (slavedGraphics != null && slavedIndex < slavedGraphics.length
			&& slavedGraphics[slavedIndex] != null)
		{
			// One graphic at a time, as on the player: the game keeps an actor's
			// spotanim in one slot, and the next stage's graphic takes the last
			// one's place. Left to run out instead, the fourth stage's (803, the
			// book and its glow, opaque to its last frame and 50 client ticks
			// longer than the stage's own clip) stayed in the hands of a body
			// already fading out under the fifth's, whose flash (804) has no book.
			if (stageGraphic != null)
			{
				stageGraphic.setActive(false);
				activeGraphics.remove(stageGraphic);
			}
			// Nor does the last stage's graphic come on late over this one's.
			pendingGraphics.removeIf(pending -> pending.stage);
			showSpotAnim(slavedGraphics[slavedIndex], 0, true);
		}
	}

	/** The graphic the slaved chain's last stage put on, which the next one's replaces. */
	private RuneLiteObject stageGraphic;

	/**
	 * The player's sequence stopped. Only a cast that reached its last stage
	 * leaves with it; one cut short (a click away, a hit) keeps the follower,
	 * as a mirrored one-shot cast cut by movement does.
	 */
	public void slavedPlayerStopped()
	{
		if (slavedChain != null && slavedIndex >= slavedChain.length - 1)
		{
			endSlavedChain();
		}
		else
		{
			cancelEmote();
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
	 * How far up the model a teleport's graphic sits.
	 *
	 * <p>A spotanim is drawn at the feet unless it is lifted, and a teleport
	 * wraps the body rather than pooling on the floor. Judged by eye on the
	 * arrival and then found to be missing from the departures, which is what
	 * naming it here is for: the number was written twice, in two files, and
	 * the two places that needed it most had a bare zero instead.
	 */
	public static final int TELEPORT_GRAPHIC_HEIGHT = 92;

	/**
	 * @param height how far above the ground the graphic sits, in unscaled model
	 *               units - the server sends one with every cast (a body-height
	 *               teleport swirl is not a feet-height rune circle), and the
	 *               client raises the spotanim model by it BEFORE resizing, so
	 *               the offset scales with the graphic. Same order here.
	 */
	public void playSpotAnim(SpotAnimRepository.Entry fx, int height)
	{
		showSpotAnim(fx, height, false);
	}

	/**
	 * A graphic the client was still downloading when it was due. A model or
	 * animation it has never drawn is fetched from the game's servers, and the
	 * first ask starts the download and gets nothing: on a fresh install the
	 * first home teleport went without its circle and book (public QA, 27 Oct).
	 */
	private static final class PendingGraphic
	{
		final SpotAnimRepository.Entry fx;
		final int height;
		/** The slaved chain's stage graphic, which the next stage's replaces. */
		final boolean stage;
		final long dueMs = System.currentTimeMillis();

		PendingGraphic(SpotAnimRepository.Entry fx, int height, boolean stage)
		{
			this.fx = fx;
			this.height = height;
			this.stage = stage;
		}
	}

	private final java.util.List<PendingGraphic> pendingGraphics = new java.util.ArrayList<>();

	/**
	 * How long a graphic still downloading is asked for, at most. One that
	 * lands goes on at the frame it would have reached, so its own length is
	 * what usually ends the wait.
	 */
	static final long GRAPHIC_WAIT_MS = 5000;

	/** As {@link #playSpotAnim(SpotAnimRepository.Entry, int)}; a stage's is the chain's to replace. */
	private void showSpotAnim(SpotAnimRepository.Entry fx, int height, boolean stage)
	{
		if (fx == null || object == null || lastRenderedLocation == null)
		{
			return;
		}
		PendingGraphic graphic = new PendingGraphic(fx, height, stage);
		if (!placeGraphic(graphic))
		{
			pendingGraphics.add(graphic);
		}
	}

	/** Puts on the graphics that have landed since they were due. Every frame. */
	void placePendingGraphics()
	{
		pendingGraphics.removeIf(this::placeGraphic);
	}

	/**
	 * Asks for a cast's graphics up front, so one the client has never drawn
	 * downloads while the stages before it play.
	 */
	private void fetchGraphics(SpotAnimRepository.Entry[] graphics)
	{
		if (graphics == null)
		{
			return;
		}
		for (SpotAnimRepository.Entry fx : graphics)
		{
			if (fx != null)
			{
				client.loadModelData(fx.modelId());
				client.loadAnimation(fx.animationId());
			}
		}
	}

	/**
	 * The frame of {@code animation} reached this many client cycles in, or -1
	 * once it would be over.
	 */
	static int frameAt(Animation animation, int cycles)
	{
		int[] lengths = animation.getFrameLengths();
		if (lengths == null)
		{
			return 0;
		}
		for (int frame = 0; frame < lengths.length; frame++)
		{
			cycles -= lengths[frame];
			if (cycles < 0)
			{
				return frame;
			}
		}
		return -1;
	}

	/** Shows the graphic if its model and animation are in; true once it is done with. */
	private boolean placeGraphic(PendingGraphic pending)
	{
		SpotAnimRepository.Entry fx = pending.fx;
		int height = pending.height;
		ModelData data = client.loadModelData(fx.modelId());
		// A frozen first frame with no animation to end it would linger forever.
		Animation animation = client.loadAnimation(fx.animationId());
		long late = System.currentTimeMillis() - pending.dueMs;
		if (data == null || animation == null)
		{
			return late > GRAPHIC_WAIT_MS;
		}
		// In step with the cast it came with: a client cycle is 20ms.
		int frame = frameAt(animation, (int) (late / 20));
		if (frame < 0)
		{
			return true;
		}

		// The client's own pipeline order: base model (rotation, colours), then
		// the height translate, then resize, then lighting.
		data = fx.recolour(data.cloneColors());
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
		graphic.setLocation(lastRenderedLocation, tile != null ? tile.getPlane() : plane());
		// Aligned with the follower from the very first frame; render() keeps it
		// aligned every frame after.
		graphic.setZ(lastRenderedZ);
		graphic.setOrientation(yaw);

		AnimationController controller = new AnimationController(client, animation);
		if (frame > 0)
		{
			controller.setFrame(frame);
		}
		controller.setOnFinished(finished ->
		{
			graphic.setActive(false);
			activeGraphics.remove(graphic);
		});
		graphic.setAnimationController(controller);
		graphic.setActive(true);
		activeGraphics.add(graphic);
		if (pending.stage)
		{
			stageGraphic = graphic;
		}
		return true;
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
		releaseHeldOverTheCast();
		// An interrupted death keeps the follower visible - same rule as the
		// interrupted teleport above.
		hideAfterEmoteMs = 0;

		if (!emotePlaying)
		{
			return;
		}
		emoteGeneration++;
		setEmotePlaying(false);
		activePose = -1;

		// A cancelled teleport should not leave its rune circle spinning at the
		// follower's feet as it walks off.
		showGraphics(false);
		activeGraphics.clear();
		pendingGraphics.clear();
	}

	/** Shows or hides every graphic the follower is wearing. */
	private void showGraphics(boolean shown)
	{
		for (RuneLiteObject graphic : activeGraphics)
		{
			graphic.setActive(shown);
		}
	}

	/**
	 * Turns to look at the player. Sets the destination yaw only - the authentic
	 * turn rate then eases into it, the same as any other facing change.
	 */
	/**
	 * While the player is working, look where they are looking.
	 *
	 * <p>A companion that stares at your face while you chop a tree is watching
	 * YOU; one that looks at the tree is watching what you are doing, and the
	 * difference costs a yaw.
	 *
	 * <p>The player's orientation is used rather than their target, because
	 * there usually is no target to read: {@code getInteracting} only reports
	 * actors, so mining a rock or chopping a tree exposes nothing at all. Which
	 * way they are turned is always known and is the same information - they
	 * are facing the thing.
	 *
	 * <p>Only while they are animating. Standing about is not work, and the
	 * follower goes back to facing them, which is the attentive default.
	 */
	/**
	 * True while a cutscene keeps the follower: it holds its tile, gives way to
	 * the cast and looks where the player looks.
	 */
	private boolean watchingWithThePlayer;

	public void setWatchingWithThePlayer(boolean watching)
	{
		watchingWithThePlayer = watching;
		if (!watching)
		{
			// The next scene starts from where it finds the follower.
			catchingUp = false;
			scenePlayer = null;
		}
	}

	// public:cut chronicle
	/** True while it counts you in for a sketch: your emote is the pose, not work to watch. */
	private boolean facingThePlayer;

	public void setFacingThePlayer(boolean facing)
	{
		facingThePlayer = facing;
	}

	// public:end chronicle
	private void watchTheWork(Player local)
	{
		if (local.getAnimation() == -1 || facingThePlayer) // public:cut chronicle
		// public:else if (local.getAnimation() == -1)
		{
			facePlayer();
			return;
		}
		dstYaw = local.getOrientation() & 0x7ff;
	}

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
		dstYaw = yawOf(dx, dy);
	}

	/**
	 * The yaw that faces along a delta taken SELF minus TARGET: the reversed
	 * delta bakes in the game's 0-is-south yaw convention. Target-minus-self
	 * faces the exact opposite way.
	 */
	private static int yawOf(double dx, double dy)
	{
		return (int) Math.round(Math.atan2(dx, dy) * 325.949) & 0x7ff;
	}

	/** Turns toward a tile; false when standing on it, with no way to face. */
	private boolean faceToward(WorldPoint at)
	{
		int dx = tile.getX() - at.getX();
		int dy = tile.getY() - at.getY();
		if (dx == 0 && dy == 0)
		{
			return false;
		}
		dstYaw = yawOf(dx, dy);
		return true;
	}

	/** Where and until when the follower's gaze is borrowed by a glance. */
	private WorldPoint glanceTile;
	private long glanceUntilMs;

	/**
	 * Turns the gaze onto a tile for a moment - co-attention, the cheapest
	 * sign of presence there is. Gaze only: the feet stay with whatever owns
	 * them, and a real facing (a pose's anchor, a goal, the thrall's target)
	 * outranks it, which is why it only applies in the plain idle case.
	 */
	public void glanceAt(WorldPoint at, int ms)
	{
		glanceTile = at;
		glanceUntilMs = System.currentTimeMillis() + ms;
	}

	/** Applies a live glance's facing; false once it lapses or cannot aim. */
	private boolean glance()
	{
		if (glanceTile == null)
		{
			return false;
		}
		if (System.currentTimeMillis() >= glanceUntilMs)
		{
			glanceTile = null;
			return false;
		}
		return tile != null && faceToward(glanceTile);
	}
} // public:cut skilling
// public:else
	// public:else /** The tiles exactly so far from a footprint, nearest corner first. */
	// public:else public static java.util.List<WorldPoint> ring(WorldPoint min, WorldPoint max, int radius, int plane)
	// public:else {
		// public:else WorldPoint far = max == null ? min : max;
		// public:else java.util.List<WorldPoint> ring = new java.util.ArrayList<>();
		// public:else for (int x = min.getX() - radius; x <= far.getX() + radius; x++)
		// public:else {
			// public:else for (int y = min.getY() - radius; y <= far.getY() + radius; y++)
			// public:else {
				// public:else int cx = Math.max(min.getX(), Math.min(far.getX(), x));
				// public:else int cy = Math.max(min.getY(), Math.min(far.getY(), y));
				// public:else if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) == radius)
				// public:else {
					// public:else ring.add(new WorldPoint(x, y, plane));
				// public:else }
			// public:else }
		// public:else }
		// public:else return ring;
	// public:else }
// public:else }
