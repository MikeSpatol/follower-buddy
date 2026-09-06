package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Keeps the follower company while the player works: notices a work session
 * beginning from the click, the animation and the XP, takes a seat off the
 * player's flank facing the target, borrows the tool, and lets everything go
 * when the session ends.
 *
 * <p>This is about the body and position, not words: nothing here speaks.
 * What it knows is handed to the rules through {@link TriggerContext} and a
 * few events, so a line can be written against it if one is ever wanted.
 *
 * <p>Cheap by construction. Nothing scans the scene per tick: the one scan
 * (the target's same-name neighbours, for the seat) runs once per seat, and
 * object and NPC events are dropped unless they touch the target's tile.
 */
@Slf4j
public class SkillingController
{
	/** The option words that start work, matched whole after tag stripping. */
	private static final String[] WORK_OPTIONS = {
		"Chop down", "Mine", "Fish", "Net", "Harpoon", "Cage", "Bait", "Lure",
		"Cook", "Smith", "Smelt", "Craft", "Spin", "Weave", "Pray-at", "Craft-rune",
		"Rake", "Plant", "Harvest", "Check-health", "Pick", "Steal-from", "Pickpocket",
		"Build", "Light", "Burn", "Bury", "Lay", "Set-trap", "Catch", "Use",
	};

	/** Ticks the player has to be at it before the seat is taken. */
	static final int SEAT_AFTER_TICKS = 6;

	/** A session with no animation and no drop for this long is over. */
	static final int QUIET_END_TICKS = 25;

	/** Walking this far from the working spot ends it. */
	static final int WALK_OFF_TILES = 3;

	/** How long a click waits for proof that it turned into work. */
	static final int PENDING_TICKS = 50;

	/** A session this long earns the return beat; two minutes. */
	static final int BEAT_AFTER_TICKS = 200;

	/** Ticks the seat is kept after the beat so walking does not cut it. */
	private static final int RELEASE_HOLD_TICKS = 3;

	/** How often an unseated session looks again for somewhere to stand. */
	private static final int SEAT_RETRY_TICKS = 10;

	/** Object and NPC events this soon after a load are the scene shuffling. */
	private static final int LOAD_SETTLE_TICKS = 2;

	/** Same-name neighbours this close are the player's likely next click. */
	private static final int NEIGHBOUR_TILES = 2;

	/** A moved fishing spot turns up about this far from the old one. */
	private static final int NEW_SPOT_TILES = 8;

	/** Free slots at which the bag counts as half full. */
	static final int HALF_BAG_FREE = 14;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final Consumer<TriggerEvent> dispatch;
	private final BooleanSupplier inCombat;

	/** True when thrall work or the spectator owns the feet; both beat the seat. */
	private final BooleanSupplier feetTaken;
	private final BooleanSupplier bankOpen;
	private final Borrow borrow;

	/** Every counter's jitter comes from here; a test may seed it. */
	final Random random = new Random();

	/** What the follower does in the seat; see {@link KeepingCompany}. */
	private final KeepingCompany company;

	/** The last menu option the player clicked, tags off, work or not. */
	private String lastOption = "";

	/** The bag edge seen this tick, handed to the company after the session checks. */
	private TriggerEvent.Type bagEdge;

	private WorkSession session;

	/** A work click waiting for its proof, and the tick it was made. */
	private WorkSession pending;
	private int pendingTick;

	private WorldPoint seat;

	/** The tick from which the seat may be (re)taken; retries space out from it. */
	private int seatDueTick;

	/** Set by a shift to a neighbouring target: the seat is chosen again. */
	private boolean reseat;

	private int releaseTicks;

	/** A tool the hands declined this session, so it is not asked for on every drop. */
	private int refusedTool;
	private int ticksSinceLoading = 1000;

	private int lastFreeSlots = -1;
	private boolean halfArmed = true;

	public SkillingController(Client client, FollowerEntity follower, FollowerConfig config,
		ErrandController.Hands hands, Consumer<TriggerEvent> dispatch,
		BooleanSupplier inCombat, BooleanSupplier feetTaken, BooleanSupplier bankOpen)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.dispatch = dispatch;
		this.inCombat = inCombat;
		this.feetTaken = feetTaken;
		this.bankOpen = bankOpen;
		this.borrow = new Borrow(follower, hands);
		this.company = new KeepingCompany(client, follower, config, hands, borrow, random);
	}

	public boolean isWorking()
	{
		return session != null;
	}

	public WorkSession getSession()
	{
		return session;
	}

	public Borrow getBorrow()
	{
		return borrow;
	}

	/** The seat tile while one is held, or null. */
	public WorldPoint getSeat()
	{
		return seat;
	}

	/**
	 * Whether the company owns the follower's feet: seated, or holding still
	 * through the return beat. Registered beside the errand and spectate
	 * owners so a seat never reads as a Stay the player asked for.
	 */
	public boolean holdsTheFeet()
	{
		return seat != null || releaseTicks > 0;
	}

	/** Whether the follower is sitting in for the long haul. */
	public boolean isSitting()
	{
		return company.isSitting();
	}

	// ---------------------------------------------------------------- inputs

	/** Any menu option the player clicked, tags off; the bag reads it. */
	public void noteMenuOption(String plain)
	{
		lastOption = plain == null ? "" : plain;
	}

	/** Whether a menu option is one of the words that start work. */
	public static boolean isWorkOption(String option)
	{
		String plain = plainOption(option);
		for (String word : WORK_OPTIONS)
		{
			if (word.equalsIgnoreCase(plain))
			{
				return true;
			}
		}
		return false;
	}

	/** The option with the game's colour tags off it. */
	public static String plainOption(String option)
	{
		return option == null ? "" : option.replaceAll("<[^>]*>", "").trim();
	}

	/**
	 * The skill an option word points at, as a lowercased skill name, or
	 * empty for a word that could be anything ("Use"). The first XP drop has
	 * the last word either way.
	 */
	public static String activityFor(String option)
	{
		switch (plainOption(option).toLowerCase(Locale.ROOT))
		{
			case "chop down":
				return "woodcutting";
			case "mine":
				return "mining";
			case "fish":
			case "net":
			case "harpoon":
			case "cage":
			case "bait":
			case "lure":
				return "fishing";
			case "cook":
				return "cooking";
			case "smith":
			case "smelt":
				return "smithing";
			case "craft":
			case "spin":
			case "weave":
				return "crafting";
			case "pray-at":
			case "bury":
				return "prayer";
			case "craft-rune":
				return "runecraft";
			case "rake":
			case "plant":
			case "harvest":
			case "check-health":
			case "pick":
				return "farming";
			case "steal-from":
			case "pickpocket":
				return "thieving";
			case "build":
				return "construction";
			case "light":
			case "burn":
				return "firemaking";
			case "lay":
			case "set-trap":
			case "catch":
				return "hunter";
			default:
				return "";
		}
	}

	/**
	 * The player clicked a work option on something.
	 *
	 * <p>A click alone opens nothing; it waits for the animation or the drop
	 * that proves it turned into work. A click on a same-name neighbour of the
	 * current target during a session moves the session over rather than
	 * ending it: that is the player shifting to the next tree, and the seat
	 * follows once they are at it.
	 *
	 * @param min the footprint's south-west tile, which is the target tile
	 * @param max the footprint's far corner, or null for a single tile
	 * @param npc the interacted NPC, or null for scenery
	 */
	public void noteClick(String option, String targetName, int targetId,
		WorldPoint min, WorldPoint max, NPC npc)
	{
		if (!config.keepsCompany() || !isWorkOption(option) || min == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			return;
		}
		String plain = plainOption(option);
		String name = targetName == null ? "" : targetName;
		int now = client.getTickCount();

		if (session != null && name.equals(session.targetName)
			&& min.getPlane() == session.targetTile.getPlane()
			&& min.distanceTo(session.targetTile) <= WALK_OFF_TILES)
		{
			session.targetId = targetId;
			session.targetTile = min;
			session.targetMax = max == null ? min : max;
			session.targetNpc = npc;
			session.depleted = false;
			session.newSpot = null;
			session.workSpot = here;
			session.lastEvidenceTick = now;
			// The old seat may now be on the line to the new target, or on
			// it. Chosen again from scratch once the player is at the work.
			reseat = true;
			seatDueTick = now + SEAT_AFTER_TICKS;
			company.aimAt(faceTile());
			log.debug("Work moved to {} at {}", name, min);
			return;
		}

		pending = new WorkSession(plain, activityFor(plain), name, targetId, min, max, npc,
			here, now);
		pendingTick = now;
	}

	/** The local player's animation changed; anything but -1 is work. */
	public void noteAnimation(int animationId)
	{
		if (animationId == -1)
		{
			return;
		}
		// A click parked during a session is the player moving on to
		// something else; the swing that follows is its proof, and the old
		// session ends inside startPending.
		if (pending != null)
		{
			pending.lastAnimation = animationId;
			startPending();
			return;
		}
		if (session != null)
		{
			session.lastAnimation = animationId;
			session.lastEvidenceTick = client.getTickCount();
		}
	}

	/**
	 * An XP drop landed. The skill is the truth about what the work is, so
	 * it overrides the guess the option word made.
	 *
	 * @return the drops this session including this one, or 0 outside one
	 */
	public int noteXpDrop(Skill skill)
	{
		int now = client.getTickCount();
		if (pending != null)
		{
			startPending();
		}
		if (session == null)
		{
			return 0;
		}
		if (skill != null)
		{
			session.activity = skill.getName().toLowerCase(Locale.ROOT);
		}
		session.xpDrops++;
		session.lastDropTick = now;
		session.lastEvidenceTick = now;
		if (now - session.startTick >= SEAT_AFTER_TICKS)
		{
			// The drop may be what named the activity, and so the tool.
			maybeBorrow();
		}
		company.drop(session.xpDrops, now);
		return session.xpDrops;
	}

	/**
	 * A scenery object appeared. Only the target's tile matters: a different
	 * id there is the stump, the empty rock, the used patch.
	 */
	public void objectSpawned(int id, WorldPoint min, WorldPoint max)
	{
		if (watchingObjects() && id != session.targetId && covers(min, max, session.targetTile))
		{
			depleted();
		}
	}

	/** A scenery object went. The target going is the depletion. */
	public void objectDespawned(int id, WorldPoint min, WorldPoint max)
	{
		if (watchingObjects() && id == session.targetId && covers(min, max, session.targetTile))
		{
			depleted();
		}
	}

	private boolean watchingObjects()
	{
		return session != null && session.targetNpc == null
			&& ticksSinceLoading > LOAD_SETTLE_TICKS;
	}

	/** The interacted NPC going - a fishing spot moving - is its depletion. */
	public void npcDespawned(NPC npc)
	{
		if (session == null || npc == null || npc != session.targetNpc
			|| ticksSinceLoading <= LOAD_SETTLE_TICKS)
		{
			return;
		}
		session.targetNpc = null;
		depleted();
	}

	/** A same-name NPC turning up near a gone target is where it moved to. */
	public void npcSpawned(NPC npc)
	{
		if (session == null || npc == null || session.targetNpc != null || !session.depleted
			|| ticksSinceLoading <= LOAD_SETTLE_TICKS)
		{
			return;
		}
		WorldPoint at = npc.getWorldLocation();
		if (at != null && session.targetName.equals(npc.getName())
			&& at.getPlane() == session.targetTile.getPlane()
			&& at.distanceTo(session.targetTile) <= NEW_SPOT_TILES)
		{
			session.newSpot = at;
			log.debug("The spot moved to {}", at);
		}
	}

	/** A scene load began: what despawns around it is shuffling, not change. */
	public void sceneLoading()
	{
		ticksSinceLoading = 0;
	}

	/** Logout, hop, shutdown: everything let go of, nothing said. */
	public void reset()
	{
		if (session != null)
		{
			end(true, false, false);
		}
		pending = null;
		releaseTicks = 0;
		lastFreeSlots = -1;
		halfArmed = true;
		ticksSinceLoading = 1000;
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread. */
	public void tick()
	{
		if (ticksSinceLoading < 1000)
		{
			ticksSinceLoading++;
		}
		watchBag();

		if (!config.keepsCompany())
		{
			if (session != null)
			{
				end(true, false, true);
			}
			pending = null;
			return;
		}

		if (releaseTicks > 0 && --releaseTicks == 0
			&& follower.isStaying() && !feetTaken.getAsBoolean())
		{
			// Only when the seat is still ours: a thrall or the spectator that
			// took the feet during the bow must not be released by us.
			follower.resumeFollowing();
		}

		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			if (session != null)
			{
				end(true, false, true);
			}
			return;
		}

		int now = client.getTickCount();

		// A parked click, with or without a session running: forgotten when
		// it goes stale, promoted by an animation. During a session that is
		// the player moving on, and startPending closes the old one.
		if (pending != null)
		{
			if (now - pendingTick > PENDING_TICKS)
			{
				pending = null;
			}
			else if (local.getAnimation() != -1)
			{
				pending.lastAnimation = local.getAnimation();
				startPending();
			}
		}
		if (session == null)
		{
			return;
		}

		// The abort paths: something else owns the follower, or the world
		// changed under the session. Silent, tool straight back.
		if (feetTaken.getAsBoolean() || !follower.isSpawned() || inCombat.getAsBoolean()
			|| here.getPlane() != session.workSpot.getPlane())
		{
			end(true, false, true);
			return;
		}

		if (local.getAnimation() != -1)
		{
			session.lastAnimation = local.getAnimation();
			session.lastEvidenceTick = now;
		}

		// The natural ends: walked off, gone quiet, gone to the bank.
		if (here.distanceTo(session.workSpot) > WALK_OFF_TILES
			|| now - session.lastEvidenceTick > QUIET_END_TICKS
			|| bankOpen.getAsBoolean())
		{
			end(false, now - session.startTick >= BEAT_AFTER_TICKS, true);
			return;
		}

		if (seat == null || reseat)
		{
			// Not before the player has been at it, and not every tick when
			// the ground keeps refusing.
			if (now >= seatDueTick)
			{
				reseat = false;
				seatDueTick = now + SEAT_RETRY_TICKS;
				takeSeat(here);
				maybeBorrow();
			}
		}
		else if (!follower.isStaying())
		{
			// Un-seated by somebody else's release - a held emote ending, a
			// leash snap - and the session is still on: take the seat again.
			if (follower.stayAt(seat))
			{
				follower.setStayFaceTile(faceTile());
				company.seated(seat, faceTile());
			}
			else
			{
				seat = null;
				company.unseated();
			}
		}

		// The company, last: the session goes on and the seat is settled.
		if (bagEdge == TriggerEvent.Type.BAG_HALF)
		{
			company.bagHalf(lastOption);
		}
		else if (bagEdge == TriggerEvent.Type.BAG_FULL)
		{
			company.bagFull(lastOption, here);
		}
		bagEdge = null;
		company.tick(now, here, local.getAnimation() != -1);
		WorldPoint moved = company.seatMoved();
		if (moved != null)
		{
			seat = moved;
		}
	}

	private void startPending()
	{
		if (pending == null || inCombat.getAsBoolean() || feetTaken.getAsBoolean()
			|| !follower.isSpawned())
		{
			return;
		}
		int now = client.getTickCount();
		if (session != null)
		{
			end(false, now - session.startTick >= BEAT_AFTER_TICKS, true);
		}
		session = new WorkSession(pending.option, pending.activity, pending.targetName,
			pending.targetId, pending.targetTile, pending.targetMax, pending.targetNpc,
			pending.workSpot, now);
		session.lastAnimation = pending.lastAnimation;
		pending = null;
		seat = null;
		reseat = false;
		refusedTool = 0;
		seatDueTick = now + SEAT_AFTER_TICKS;
		company.begin(now);
		company.aimAt(faceTile());
		log.debug("Work session: {} on {} at {}", session.option, session.targetName,
			session.targetTile);
	}

	/**
	 * Ends the session, however it ended.
	 *
	 * @param silent an abort: nothing played, the tool straight back
	 * @param withBeat a long session's send-off, when not silent
	 * @param announce whether the rules hear of it; a logout has nobody to tell
	 */
	private void end(boolean silent, boolean withBeat, boolean announce)
	{
		WorkSession done = session;
		session = null;
		reseat = false;
		bagEdge = null;
		// A sitting follower stands first, on a proper end; an abort simply
		// lets the pose go.
		int standUp = company.end(!silent);
		boolean beat = !silent && withBeat && borrow.isHolding();
		if (beat)
		{
			// The bow is to the player, not to the tree.
			Player local = client.getLocalPlayer();
			if (seat != null && local != null && local.getWorldLocation() != null)
			{
				follower.setStayFaceTile(local.getWorldLocation());
			}
			borrow.giveBack(true, standUp);
		}
		else
		{
			borrow.abort();
			if (standUp > 0)
			{
				follower.playAnimation(standUp);
			}
		}
		if (seat != null)
		{
			seat = null;
			if (beat || standUp > 0)
			{
				// Movement would cut the bow off; the feet stay a few ticks,
				// and a few more when the stand-up comes first.
				releaseTicks = RELEASE_HOLD_TICKS
					+ (standUp > 0 ? KeepingCompany.STAND_UP_TICKS : 0);
			}
			else if (follower.isStaying() && !feetTaken.getAsBoolean())
			{
				// Only when the seat is still ours: a thrall or the spectator
				// that just took the feet must not be released by us.
				follower.resumeFollowing();
			}
		}
		if (announce)
		{
			dispatch.accept(TriggerEvent.workEnd(done.activity, done.targetName));
		}
		log.debug("Work session over: {} on {}{}", done.option, done.targetName,
			silent ? " (aborted)" : "");
	}

	private void depleted()
	{
		if (session.depleted)
		{
			return;
		}
		session.depleted = true;
		dispatch.accept(TriggerEvent.targetDepleted(session.targetName));
		log.debug("Target depleted: {} at {}", session.targetName, session.targetTile);
	}

	// ------------------------------------------------------------------ seat

	/**
	 * Takes the seat: two tiles off the player's flank, never on the line to
	 * the target, never on its footprint or a same-name neighbour's, facing
	 * the target. A tile the pathing refuses is skipped; when every candidate
	 * is refused the follower simply keeps following, and tries again later.
	 */
	private void takeSeat(WorldPoint player)
	{
		if ("thieving".equals(session.activity))
		{
			// The thieving session already keeps the follower off at a
			// distance, and a seat at the mark's elbow is the opposite of
			// what that was tuned for. Its own watch comes later.
			return;
		}
		List<WorldPoint> reserved = reservedTiles();
		for (WorldPoint candidate : seatCandidates(player, session.targetTile,
			session.targetMax, reserved))
		{
			if (follower.stayAt(candidate))
			{
				seat = candidate;
				follower.setStayFaceTile(faceTile());
				company.seated(seat, faceTile());
				log.debug("Seated at {} facing {}", seat, session.targetTile);
				return;
			}
		}
		seat = null;
		company.unseated();
		log.debug("No seat by {} for {}; following instead", player, session.targetName);
	}

	/** The middle of the footprint, so a big tree is looked at, not past. */
	private WorldPoint faceTile()
	{
		WorldPoint min = session.targetTile;
		WorldPoint max = session.targetMax;
		return new WorldPoint((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2,
			min.getPlane());
	}

	/**
	 * The tiles a seat may take, in order of preference, from the pure
	 * geometry: the flank first, then a step back or forward off it, then
	 * behind the player. Never the footprint, never a reserved tile, never
	 * the player's own tile, never on the straight line to the target.
	 *
	 * @param min the target footprint's south-west tile
	 * @param max its far corner
	 * @param reserved tiles of the same-name neighbours the player may click next
	 */
	static List<WorldPoint> seatCandidates(WorldPoint player, WorldPoint min, WorldPoint max,
		Collection<WorldPoint> reserved)
	{
		// The direction to the nearest point of the footprint. A player
		// standing on it (a fire, a patch) has no direction; north will do.
		int nx = Math.max(min.getX(), Math.min(max.getX(), player.getX()));
		int ny = Math.max(min.getY(), Math.min(max.getY(), player.getY()));
		int dx = Integer.signum(nx - player.getX());
		int dy = Integer.signum(ny - player.getY());
		if (dx == 0 && dy == 0)
		{
			dy = 1;
		}
		int px = -dy;
		int py = dx;

		int[][] offsets = {
			{2 * px, 2 * py}, {-2 * px, -2 * py},
			{2 * px - dx, 2 * py - dy}, {-2 * px - dx, -2 * py - dy},
			{2 * px + dx, 2 * py + dy}, {-2 * px + dx, -2 * py + dy},
			{-2 * dx, -2 * dy}, {-2 * dx + px, -2 * dy + py}, {-2 * dx - px, -2 * dy - py},
		};

		Set<WorldPoint> avoid = new HashSet<>(reserved);
		List<WorldPoint> candidates = new ArrayList<>();
		for (int[] offset : offsets)
		{
			WorldPoint c = new WorldPoint(player.getX() + offset[0], player.getY() + offset[1],
				player.getPlane());
			if (candidates.contains(c) || avoid.contains(c) || covers(min, max, c)
				|| c.equals(player) || onTheLine(player, nx, ny, c))
			{
				continue;
			}
			candidates.add(c);
		}
		return candidates;
	}

	/** Whether the tile sits on the straight line from the player to the target. */
	private static boolean onTheLine(WorldPoint player, int tx, int ty, WorldPoint c)
	{
		int ax = tx - player.getX();
		int ay = ty - player.getY();
		int bx = c.getX() - player.getX();
		int by = c.getY() - player.getY();
		boolean collinear = ax * by - ay * bx == 0;
		boolean between = bx * ax + by * ay >= 0
			&& bx * bx + by * by <= ax * ax + ay * ay;
		return collinear && between;
	}

	private static boolean covers(WorldPoint min, WorldPoint max, WorldPoint tile)
	{
		if (min == null || tile == null)
		{
			return false;
		}
		WorldPoint far = max == null ? min : max;
		return tile.getPlane() == min.getPlane()
			&& tile.getX() >= min.getX() && tile.getX() <= far.getX()
			&& tile.getY() >= min.getY() && tile.getY() <= far.getY();
	}

	/**
	 * The footprints of the target's same-name neighbours within two tiles:
	 * the next tree, the next rock, the next spot. One bounded look at the
	 * scene per seat, not per tick.
	 */
	private List<WorldPoint> reservedTiles()
	{
		List<WorldPoint> reserved = new ArrayList<>();
		WorldPoint min = session.targetTile;
		WorldPoint max = session.targetMax;
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return reserved;
		}
		if (session.targetNpc != null)
		{
			for (NPC npc : view.npcs())
			{
				WorldPoint at = npc == null ? null : npc.getWorldLocation();
				if (at == null || npc == session.targetNpc
					|| !session.targetName.equals(npc.getName())
					|| at.getPlane() != min.getPlane()
					|| at.distanceTo(min) > NEIGHBOUR_TILES)
				{
					continue;
				}
				int size = npc.getComposition() == null ? 1
					: Math.max(1, npc.getComposition().getSize());
				addFootprint(reserved, at, new WorldPoint(at.getX() + size - 1,
					at.getY() + size - 1, at.getPlane()));
			}
			return reserved;
		}

		Tile[][][] tiles = view.getScene() == null ? null : view.getScene().getTiles();
		if (tiles == null)
		{
			return reserved;
		}
		int plane = view.getPlane();
		for (int x = min.getX() - NEIGHBOUR_TILES; x <= max.getX() + NEIGHBOUR_TILES; x++)
		{
			for (int y = min.getY() - NEIGHBOUR_TILES; y <= max.getY() + NEIGHBOUR_TILES; y++)
			{
				int sx = x - view.getBaseX();
				int sy = y - view.getBaseY();
				if (plane < 0 || plane >= tiles.length || sx < 0 || sy < 0
					|| sx >= tiles[plane].length || sy >= tiles[plane][sx].length)
				{
					continue;
				}
				Tile tile = tiles[plane][sx][sy];
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object == null)
					{
						continue;
					}
					WorldPoint[] footprint = footprintOf(view, object);
					if (object.getId() == session.targetId
						&& covers(footprint[0], footprint[1], min))
					{
						continue;
					}
					String name = ErrandController.objectName(client, object.getId());
					if (session.targetName.equals(name))
					{
						addFootprint(reserved, footprint[0], footprint[1]);
					}
				}
			}
		}
		return reserved;
	}

	/** An object's tiles from its scene corners, or its own tile when it has none. */
	private static WorldPoint[] footprintOf(WorldView view, GameObject object)
	{
		net.runelite.api.Point lo = object.getSceneMinLocation();
		net.runelite.api.Point hi = object.getSceneMaxLocation();
		if (lo != null && hi != null)
		{
			return new WorldPoint[]{
				WorldPoint.fromScene(view, lo.getX(), lo.getY(), object.getPlane()),
				WorldPoint.fromScene(view, hi.getX(), hi.getY(), object.getPlane()),
			};
		}
		WorldPoint at = object.getWorldLocation();
		return new WorldPoint[]{at, at};
	}

	private static void addFootprint(List<WorldPoint> into, WorldPoint min, WorldPoint max)
	{
		if (min == null)
		{
			return;
		}
		WorldPoint far = max == null ? min : max;
		for (int x = min.getX(); x <= far.getX(); x++)
		{
			for (int y = min.getY(); y <= far.getY(); y++)
			{
				into.add(new WorldPoint(x, y, min.getPlane()));
			}
		}
	}

	// ---------------------------------------------------------------- borrow

	/**
	 * Borrows the session's tool once the work is settled - at seat time, and
	 * again on a drop, since the drop may be what named the activity.
	 */
	private void maybeBorrow()
	{
		if (session == null || borrow.isHolding() || !config.borrowsTools())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		int tool = Borrow.toolFor(session.activity, session.lastAnimation,
			Borrow.wornItems(local.getPlayerComposition()));
		if (tool > 0 && tool != refusedTool && !borrow.start(tool))
		{
			refusedTool = tool;
		}
	}

	// ------------------------------------------------------------------- bag

	/**
	 * The two bag edges, as events: full when the last slot goes, half when
	 * fourteen are left - once per fill, re-armed when the bag empties past it.
	 */
	private void watchBag()
	{
		bagEdge = null;
		int free = freeSlots();
		if (free < 0)
		{
			return;
		}
		TriggerEvent.Type edge = bagEdge(lastFreeSlots, free, halfArmed);
		if (edge == TriggerEvent.Type.BAG_HALF)
		{
			halfArmed = false;
		}
		if (free > HALF_BAG_FREE)
		{
			halfArmed = true;
		}
		lastFreeSlots = free;
		// Cleared at the top and set here, so an edge seen outside a session
		// never waits for the next one.
		bagEdge = edge;
		if (edge != null)
		{
			dispatch.accept(TriggerEvent.simple(edge));
		}
	}

	/**
	 * The nearest scene object by name within a radius of a tile, or null.
	 * One bounded look, taken on an edge rather than on a tick.
	 */
	static WorldPoint nearestObject(Client client, WorldPoint centre, int radius,
		Predicate<String> nameMatch)
	{
		WorldView view = client.getTopLevelWorldView();
		Tile[][][] tiles = view == null || view.getScene() == null ? null
			: view.getScene().getTiles();
		if (tiles == null || centre == null)
		{
			return null;
		}
		int plane = view.getPlane();
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++)
		{
			for (int y = centre.getY() - radius; y <= centre.getY() + radius; y++)
			{
				int sx = x - view.getBaseX();
				int sy = y - view.getBaseY();
				if (plane < 0 || plane >= tiles.length || sx < 0 || sy < 0
					|| sx >= tiles[plane].length || sy >= tiles[plane][sx].length
					|| tiles[plane][sx][sy] == null)
				{
					continue;
				}
				for (GameObject object : tiles[plane][sx][sy].getGameObjects())
				{
					if (object == null || object.getWorldLocation() == null
						|| !nameMatch.test(ErrandController.objectName(client, object.getId())))
					{
						continue;
					}
					int distance = object.getWorldLocation().distanceTo(centre);
					if (distance < bestDistance)
					{
						bestDistance = distance;
						best = object.getWorldLocation();
					}
				}
			}
		}
		return best;
	}

	/** The edge between two readings, or null; pure so it can be tested. */
	static TriggerEvent.Type bagEdge(int previousFree, int free, boolean halfArmed)
	{
		if (previousFree < 0)
		{
			return null;
		}
		if (free == 0 && previousFree > 0)
		{
			return TriggerEvent.Type.BAG_FULL;
		}
		if (halfArmed && free <= HALF_BAG_FREE && previousFree > HALF_BAG_FREE)
		{
			return TriggerEvent.Type.BAG_HALF;
		}
		return null;
	}

	/** Empty inventory slots, or -1 while the container has not loaded. */
	private int freeSlots()
	{
		net.runelite.api.ItemContainer inventory =
			client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		if (inventory == null)
		{
			return -1;
		}
		int used = 0;
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				used++;
			}
		}
		return Math.max(0, 28 - used);
	}

	// --------------------------------------------------------------- context

	/** Hands the rules what the company knows, once a tick. */
	public void describeTo(TriggerContext context)
	{
		if (session == null)
		{
			context.setWork(false, "", "", null, 0, 0, Integer.MAX_VALUE);
			return;
		}
		int now = client.getTickCount();
		context.setWork(true, session.activity, session.targetName, session.targetTile,
			now - session.startTick, session.xpDrops,
			session.lastDropTick < 0 ? Integer.MAX_VALUE : now - session.lastDropTick);
	}

	/** The option words, for the docs and the tests. */
	public static List<String> workOptions()
	{
		return Arrays.asList(WORK_OPTIONS);
	}
}
