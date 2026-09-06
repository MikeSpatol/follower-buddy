package com.follower.follower;

import com.follower.FollowerConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * What the follower does in the seat while the player works: the small
 * things a person does when they are keeping somebody company rather than
 * watching them.
 *
 * <p>Driven by {@link SkillingController}, which owns the session and the
 * seat and tells this class when a seat is taken, when a drop lands and when
 * the bag fills. Everything here is silent, and every counter is jittered so
 * nothing lands on a metronome: a scroll that comes out on the eighth drop
 * every time is a mechanism, one that comes out somewhere around the eighth
 * is a habit.
 *
 * <p>The gaze is worked through the seat's face tile rather than a glance,
 * because a seated follower is face-locked on its anchor and a glance would
 * never show. Unseated, a glance is all there is. The feet are only ever
 * moved through {@link FollowerEntity#stayAt}, so the pathing's refusals
 * still hold and a walk the ground will not allow is simply not taken.
 */
@Slf4j
class KeepingCompany
{
	/** Drops between tallies; the count for a fast skill. */
	static final int TALLY_EVERY = 8;
	static final int TALLY_EVERY_FAST = 12;

	/** Drops closer together than this are a fast skill. */
	static final int FAST_DROP_GAP = 4;

	/** Four seconds with the scroll out. */
	static final int TALLY_TICKS = 7;

	/** A look at the work about every third drop; two, three or four. */
	static final int NOD_EVERY = 3;
	private static final int NOD_TICKS = 2;
	private static final int NOD_GLANCE_MS = 1200;

	/** The player still and silent this long is a stop, not a pause. */
	static final int STOP_AFTER_TICKS = 5;
	static final int STOP_WAIT_TICKS = 4;
	static final int STOP_COOLDOWN_TICKS = 200;

	/**
	 * Think: a hand to the chin. Proven on the follower by the idle fidget,
	 * and the nearest thing the player skeleton has to scratching its head.
	 */
	static final int SCRATCH_HEAD = 857;

	/** Five minutes in, one walk out and back on the far side of the seat. */
	static final int PACE_AFTER_TICKS = 500;
	static final int PACE_TILES = 3;

	/** Eight minutes in, the follower sits. */
	static final int SIT_AFTER_TICKS = 800;

	/** The sit emote's own stand-up, the plugin's rest already pairs the two. */
	static final int STAND_UP = 10053;
	static final int STAND_UP_TICKS = 5;

	/** An hour: a slow half circle behind the player. Two: the same, then sit. */
	static final int STRETCH_AFTER_TICKS = 6000;
	static final int STRETCH_AGAIN_TICKS = 12000;

	/** Half a bag is worth a note; a full one a shrug and a step bankward. */
	static final int NOTE_TICKS = 5;
	static final int SHRUG = 2113;
	static final int BANK_TILES = 15;
	private static final int SHRUG_TICKS = 3;
	private static final int PLAYER_LOOK_TICKS = 8;

	/** An angler's company looks at a different bit of water every 30-45 seconds. */
	static final int WATER_LOOK_TICKS = 62;
	private static final int WATER_LOOK_SPREAD = 12;
	private static final int WATER_GAZE_TICKS = 4;

	/** A tree worked this long falls with a crash; a rock worked this long was a rock. */
	static final int TIMBER_AFTER_TICKS = 30;
	static final int ROCK_AFTER_TICKS = 15;

	/** The unarmed flinch, proven on the follower by the spectator's ducks. */
	static final int FLINCH = 424;

	/** The "no" head shake, proven by the answered-no rule. */
	static final int HEAD_SHAKE = 856;

	/**
	 * The peer: AnimationID.LOOKING_INTO, a player bending to look at
	 * something at their feet. New to the follower, so it is only played
	 * once a player has been seen doing it.
	 */
	static final int NEST_LOOK = 832;

	/** Three minutes at one rock earns a read of the notes: a pose, new to the follower. */
	static final int GERALD_AFTER_TICKS = 300;
	static final int NOTE_POSE = 4549;

	/** Two depletions closer than this share the one look for the next. */
	static final int NEXT_LOOK_GAP_TICKS = 15;
	private static final int PLAYER_BEAT_TICKS = 3;
	private static final int NEXT_LOOK_TICKS = 4;
	private static final int EMPTY_LOOK_TICKS = 3;

	/** One tile to look at for so many ticks; the reactions are queues of these. */
	private static final class Look
	{
		final WorldPoint tile;
		final int ticks;

		Look(WorldPoint tile, int ticks)
		{
			this.tile = tile;
			this.ticks = ticks;
		}
	}

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final ErrandController.Hands hands;
	private final Borrow borrow;
	private final Random random;

	private int startTick;
	private WorldPoint seat;

	/** The seat's own facing: the middle of the target. */
	private WorldPoint target;
	private WorldPoint facing;

	private int lastTallyDrop;
	private int tallyJitter;
	/** Whether the scroll is out, and the ticks it has left. */
	private boolean scrollOut;
	private int scrollTicks;
	private int lastDropTick;
	private boolean fast;

	private int lastNodDrop;
	private int nodGap;
	private int lookTicks;

	private WorldPoint lastHere;
	private int stillTicks;
	private boolean stopped;
	private int stopWait;
	private int stopCooldownUntil;

	private int paceAt;
	private int sitAt;
	private int stretchAt;
	private int stretchAgainAt;
	private boolean paced;
	private boolean stretched;
	private boolean stretchedAgain;
	private boolean sitting;

	/** The legs of a walk still to be taken, and the pause before the next. */
	private final List<WorldPoint> legs = new ArrayList<>();
	private int legWait;
	private boolean walking;
	private boolean sitAfterWalk;

	private int playerLookTicks;
	private WorldPoint step;
	private int stepTicks;
	private WorldPoint seatMoved;

	/** The looks still to be taken, the one being taken, and its ticks left. */
	private final List<Look> looks = new ArrayList<>();
	private WorldPoint currentLook;
	private int currentLookTicks;

	private int targetSince;
	private int lastDepletionTick;
	private int waterLookAt;
	private boolean geraldDone;

	/** Ticks the note pose has left; the head shake follows it. */
	private int noteTicks;

	KeepingCompany(Client client, FollowerEntity follower, FollowerConfig config,
		ErrandController.Hands hands, Borrow borrow, Random random)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.hands = hands;
		this.borrow = borrow;
		this.random = random;
	}

	boolean isSitting()
	{
		return sitting;
	}

	/** A session began; every counter starts over, each with its own jitter. */
	void begin(int now)
	{
		startTick = now;
		seat = null;
		target = null;
		facing = null;
		lastTallyDrop = 0;
		tallyJitter = random.nextInt(3) - 1;
		scrollOut = false;
		scrollTicks = 0;
		lastDropTick = -1;
		fast = false;
		lastNodDrop = 0;
		nodGap = nextNodGap();
		lookTicks = 0;
		lastHere = null;
		stillTicks = 0;
		stopped = false;
		stopWait = 0;
		stopCooldownUntil = 0;
		paceAt = jitter(PACE_AFTER_TICKS, 50);
		sitAt = jitter(SIT_AFTER_TICKS, 60);
		stretchAt = jitter(STRETCH_AFTER_TICKS, 300);
		stretchAgainAt = jitter(STRETCH_AGAIN_TICKS, 300);
		paced = false;
		stretched = false;
		stretchedAgain = false;
		sitting = false;
		legs.clear();
		legWait = 0;
		walking = false;
		playerLookTicks = 0;
		step = null;
		stepTicks = 0;
		seatMoved = null;
		looks.clear();
		currentLook = null;
		currentLookTicks = 0;
		lastDepletionTick = -1;
		noteTicks = 0;
	}

	/** The controller took, or retook, the seat. A walk in progress is dropped. */
	void seated(WorldPoint seat, WorldPoint target)
	{
		this.seat = seat;
		this.target = target;
		this.facing = target;
		legs.clear();
		walking = false;
		legWait = 0;
	}

	void unseated()
	{
		seat = null;
		facing = null;
		legs.clear();
		walking = false;
	}

	/** Where the seat went of its own accord - the step toward the bank - once. */
	WorldPoint seatMoved()
	{
		WorldPoint moved = seatMoved;
		seatMoved = null;
		return moved;
	}

	// ----------------------------------------------------------------- drops

	/**
	 * A drop landed: the tally scroll comes out somewhere around every eighth
	 * one (twelfth, when they come fast), and about every third earns a look
	 * at the work. Never two looks in a row; the gap is two at the least.
	 */
	void drop(int drops, int now)
	{
		if (lastDropTick >= 0)
		{
			fast = now - lastDropTick < FAST_DROP_GAP;
		}
		lastDropTick = now;

		int every = fast ? TALLY_EVERY_FAST : TALLY_EVERY;
		if (drops - lastTallyDrop >= every + tallyJitter)
		{
			lastTallyDrop = drops;
			tallyJitter = random.nextInt(3) - 1;
			takeOutTheScroll(TALLY_TICKS);
		}

		if (drops - lastNodDrop >= nodGap)
		{
			lastNodDrop = drops;
			nodGap = nextNodGap();
			if (scrollOut || walking)
			{
				return;
			}
			if (seat != null)
			{
				lookTicks = NOD_TICKS;
			}
			else if (target != null)
			{
				follower.glanceAt(target, NOD_GLANCE_MS);
			}
		}
	}

	private int nextNodGap()
	{
		return NOD_EVERY - 1 + random.nextInt(3);
	}

	/**
	 * A target, the session's first or the next one over. The seat is not
	 * needed for the nod's glance, only for its aim; the per-target clocks
	 * (the notes, the water) start over, and looks about the old one go.
	 */
	void aimAt(WorldPoint target, int now)
	{
		this.target = target;
		targetSince = now;
		geraldDone = false;
		waterLookAt = now + jitter(WATER_LOOK_TICKS, WATER_LOOK_SPREAD);
		looks.clear();
		currentLook = null;
		currentLookTicks = 0;
	}

	// ------------------------------------------------------------------ tick

	/**
	 * Once a tick during a session, after the controller has settled that the
	 * session goes on and the seat is what it is.
	 *
	 * @param animating whether the player is mid-animation this tick
	 */
	void tick(int now, WorldPoint here, boolean animating, String activity)
	{
		lookOn();
		if (seat == null)
		{
			lastHere = here;
			stillTicks = 0;
			return;
		}
		if (walking)
		{
			walk();
			return;
		}

		if (scrollTicks > 0 && --scrollTicks == 0)
		{
			putScrollAway();
		}
		if (noteTicks > 0 && --noteTicks == 0)
		{
			// The pose first, by hand: the notes may have been read over a
			// tool, with no scroll for putScrollAway to take the pose with.
			follower.setPoseOverride(0);
			putScrollAway();
			emote(HEAD_SHAKE);
		}
		if (lookTicks > 0)
		{
			lookTicks--;
		}
		if (playerLookTicks > 0)
		{
			playerLookTicks--;
		}
		if (stepTicks > 0 && --stepTicks == 0 && step != null)
		{
			if (follower.stayAt(step))
			{
				seat = step;
				seatMoved = step;
				log.debug("A step toward the bank, to {}", step);
			}
			step = null;
		}

		watchForTheStop(now, here, animating);

		int worked = now - startTick;
		if (!paced && worked >= paceAt)
		{
			paced = true;
			pace();
		}
		else if (!stretched && worked >= stretchAt)
		{
			stretched = true;
			stretch(here, false);
		}
		else if (!stretchedAgain && worked >= stretchAgainAt)
		{
			stretchedAgain = true;
			stretch(here, true);
		}
		else if (!sitting && !stretched && worked >= sitAt)
		{
			sit();
		}

		if ("mining".equals(activity) && !geraldDone && now - targetSince >= GERALD_AFTER_TICKS)
		{
			readTheNotes();
		}
		else if ("fishing".equals(activity) && now >= waterLookAt)
		{
			waterLookAt = now + jitter(WATER_LOOK_TICKS, WATER_LOOK_SPREAD);
			look(waterTile(), WATER_GAZE_TICKS);
		}

		face(wantedFace(here));
	}

	/**
	 * What the seat looks at right now, in order of claim: a look being
	 * taken, the player while the scroll is out or the bag is full, the work
	 * for a nod, the player when sitting or when they have stopped, the work
	 * otherwise.
	 */
	private WorldPoint wantedFace(WorldPoint here)
	{
		if (currentLook != null)
		{
			return currentLook;
		}
		if (scrollOut || playerLookTicks > 0)
		{
			return here;
		}
		if (lookTicks > 0)
		{
			return target;
		}
		return sitting || stopped ? here : target;
	}

	private void face(WorldPoint tile)
	{
		if (tile != null && !tile.equals(facing))
		{
			follower.setStayFaceTile(tile);
			facing = tile;
		}
	}

	// ------------------------------------------------------------------ stop

	/**
	 * The repeat ends and the player stands still: after five ticks the
	 * follower turns to them, waits a moment, and if they are still at
	 * nothing, scratches its head. Then not again for a couple of minutes.
	 */
	private void watchForTheStop(int now, WorldPoint here, boolean animating)
	{
		boolean still = here.equals(lastHere);
		lastHere = here;
		if (animating || !still)
		{
			stillTicks = 0;
			stopped = false;
			stopWait = 0;
			return;
		}
		stillTicks++;
		if (!stopped && stillTicks >= STOP_AFTER_TICKS && now >= stopCooldownUntil)
		{
			stopped = true;
			stopWait = jitter(STOP_WAIT_TICKS, 2);
			log.debug("The player stopped; looking over");
		}
		if (stopped && stopWait > 0 && --stopWait == 0)
		{
			emote(SCRATCH_HEAD);
			stopCooldownUntil = now + jitter(STOP_COOLDOWN_TICKS, 30);
		}
	}

	// ----------------------------------------------------------------- walks

	/** Out three tiles on the far side of the seat from the work, and back. */
	private void pace()
	{
		int dx = Integer.signum(seat.getX() - target.getX());
		int dy = Integer.signum(seat.getY() - target.getY());
		if (dx == 0 && dy == 0)
		{
			return;
		}
		WorldPoint away = new WorldPoint(seat.getX() + PACE_TILES * dx,
			seat.getY() + PACE_TILES * dy, seat.getPlane());
		walk(Arrays.asList(away, seat), false);
	}

	/**
	 * A slow half circle behind the player: to the tile behind them, to the
	 * other flank, back behind, and home to the seat.
	 */
	private void stretch(WorldPoint here, boolean thenSit)
	{
		int dx = Integer.signum(target.getX() - here.getX());
		int dy = Integer.signum(target.getY() - here.getY());
		if (dx == 0 && dy == 0)
		{
			dy = 1;
		}
		WorldPoint behind = new WorldPoint(here.getX() - 2 * dx, here.getY() - 2 * dy,
			here.getPlane());
		WorldPoint flankA = new WorldPoint(here.getX() - 2 * dy, here.getY() + 2 * dx,
			here.getPlane());
		WorldPoint flankB = new WorldPoint(here.getX() + 2 * dy, here.getY() - 2 * dx,
			here.getPlane());
		WorldPoint other = flankA.distanceTo(seat) >= flankB.distanceTo(seat) ? flankA : flankB;
		walk(Arrays.asList(behind, other, behind, seat), thenSit);
	}

	private void walk(List<WorldPoint> route, boolean thenSit)
	{
		putScrollAway();
		legs.clear();
		legs.addAll(route);
		walking = true;
		sitAfterWalk = thenSit;
		stopped = false;
		lookTicks = 0;
		looks.clear();
		currentLook = null;
		currentLookTicks = 0;
		// Up first, and the walk waits for it.
		legWait = standUp() ? STAND_UP_TICKS : 0;
	}

	/** One leg at a time, with a pause at each; the last leg is the seat. */
	private void walk()
	{
		if (legWait > 0)
		{
			legWait--;
			return;
		}
		if (legs.isEmpty())
		{
			walking = false;
			face(target);
			if (sitAfterWalk)
			{
				sit();
			}
			return;
		}
		WorldPoint next = legs.remove(0);
		WorldPoint from = follower.getWorldLocation();
		if (!follower.stayAt(next))
		{
			// The ground refused a leg: straight home, and the controller's
			// retake covers a refused seat too.
			legs.clear();
			walking = false;
			if (follower.stayAt(seat))
			{
				face(target);
			}
			log.debug("The walk to {} was refused", next);
			return;
		}
		// Face whoever is there while stood at a waypoint, the work once home.
		follower.setStayFaceTile(legs.isEmpty() ? target : null);
		facing = legs.isEmpty() ? target : null;
		int distance = from == null ? PACE_TILES : from.distanceTo(next);
		legWait = distance + 3 + random.nextInt(3);
	}

	// ------------------------------------------------------------------- sit

	private void sit()
	{
		int pose = config.restAnimation();
		if (pose <= 0)
		{
			return;
		}
		putScrollAway();
		sitting = true;
		follower.setPoseOverride(pose);
		log.debug("Sitting in for the long haul");
	}

	/** @return whether there was a sit to stand from */
	private boolean standUp()
	{
		if (!sitting)
		{
			return false;
		}
		sitting = false;
		follower.setPoseOverride(0);
		follower.playAnimation(STAND_UP);
		return true;
	}

	/** A one-shot, stood up first when sitting; the pose sits it back down after. */
	private void emote(int animationId)
	{
		if (sitting)
		{
			follower.playAnimations(new int[]{STAND_UP, animationId});
		}
		else
		{
			follower.playAnimation(animationId);
		}
	}

	// ---------------------------------------------------------------- scroll

	/**
	 * The scroll, through the same hands as the errand's, for a few ticks.
	 * Skipped over a borrowed tool (they share the weapon slot), over a
	 * scroll already out for something else, while sitting, and off the seat.
	 */
	private void takeOutTheScroll(int ticks)
	{
		if (seat == null || sitting || walking || scrollOut
			|| borrow.isHolding() || hands.isHolding())
		{
			return;
		}
		hands.hold(ErrandController.SCROLL_PROP);
		if (!hands.isHolding())
		{
			return;
		}
		follower.setPoseOverride(ErrandController.READ_SCROLL_POSE);
		scrollOut = true;
		scrollTicks = ticks;
	}

	private void putScrollAway()
	{
		if (noteTicks > 0)
		{
			// The notes may be read without the scroll, over a held tool.
			noteTicks = 0;
			follower.setPoseOverride(0);
		}
		if (!scrollOut)
		{
			return;
		}
		scrollOut = false;
		scrollTicks = 0;
		follower.setPoseOverride(0);
		hands.release();
	}

	// ------------------------------------------------------------- gathering

	/**
	 * A look at a tile for so many ticks, queued after any in progress. On
	 * the seat it is the face; off it, a glance. Nothing to look at is no look.
	 */
	private void look(WorldPoint tile, int ticks)
	{
		if (tile != null)
		{
			looks.add(new Look(tile, ticks));
		}
	}

	/** The look in progress runs down and the next begins. */
	private void lookOn()
	{
		if (currentLookTicks > 0 && --currentLookTicks == 0)
		{
			currentLook = null;
		}
		if (currentLook == null && !looks.isEmpty())
		{
			Look next = looks.remove(0);
			currentLook = next.tile;
			currentLookTicks = next.ticks;
			if (seat == null)
			{
				follower.glanceAt(next.tile, next.ticks * 600);
			}
		}
	}

	/** A moved fishing spot turned up: a look at it before the player clicks. */
	void lookNext(WorldPoint at)
	{
		look(at, NEXT_LOOK_TICKS);
	}

	/**
	 * The target went. A tree worked long enough falls, and the follower
	 * flinches and steps clear of the stump before looking at the player; a
	 * rock worked long enough empties, and the follower steps back and looks
	 * at the empty rock, at the next full one, then at the player. Anything
	 * quicker is a look at the player and then at where the next one is -
	 * unless the last depletion was moments ago, when once was enough.
	 *
	 * @param worked ticks this target was worked
	 * @param next the nearest same-name target, or null when there is none
	 */
	void depleted(String activity, int worked, WorldPoint min, WorldPoint max, WorldPoint next,
		WorldPoint here, int now)
	{
		boolean recent = lastDepletionTick >= 0 && now - lastDepletionTick < NEXT_LOOK_GAP_TICKS;
		lastDepletionTick = now;
		if (walking)
		{
			return;
		}
		if ("woodcutting".equals(activity) && worked >= TIMBER_AFTER_TICKS)
		{
			emote(FLINCH);
			stepAwayFrom(min, max);
			look(here, PLAYER_BEAT_TICKS);
			look(next, NEXT_LOOK_TICKS);
			return;
		}
		if ("mining".equals(activity) && worked >= ROCK_AFTER_TICKS)
		{
			stepAwayFrom(min, max);
			look(target, EMPTY_LOOK_TICKS);
			look(next, NEXT_LOOK_TICKS);
			look(here, PLAYER_BEAT_TICKS);
			return;
		}
		if (recent)
		{
			return;
		}
		look(here, PLAYER_BEAT_TICKS);
		look(next, NEXT_LOOK_TICKS);
	}

	/** One tile further from the nearest point of a footprint, ground allowing. */
	private void stepAwayFrom(WorldPoint min, WorldPoint max)
	{
		if (seat == null)
		{
			return;
		}
		int nx = Math.max(min.getX(), Math.min(max.getX(), seat.getX()));
		int ny = Math.max(min.getY(), Math.min(max.getY(), seat.getY()));
		int dx = Integer.signum(seat.getX() - nx);
		int dy = Integer.signum(seat.getY() - ny);
		if (dx == 0 && dy == 0)
		{
			return;
		}
		WorldPoint away = new WorldPoint(seat.getX() + dx, seat.getY() + dy, seat.getPlane());
		if (follower.stayAt(away))
		{
			seat = away;
			seatMoved = away;
		}
	}

	/**
	 * A bird's nest at the player's feet: a look at it, and the peer when a
	 * player has been seen doing it - it is new to the follower's skeleton.
	 */
	void nest(WorldPoint tile, boolean peerProven)
	{
		look(tile, NEXT_LOOK_TICKS);
		if (peerProven && !walking)
		{
			emote(NEST_LOOK);
		}
	}

	/**
	 * A bit of water that is not the spot: out past it from the seat, a tile
	 * or two, a little to one side.
	 */
	private WorldPoint waterTile()
	{
		int dx = Integer.signum(target.getX() - seat.getX());
		int dy = Integer.signum(target.getY() - seat.getY());
		if (dx == 0 && dy == 0)
		{
			dy = 1;
		}
		int reach = 1 + random.nextInt(2);
		int side = random.nextInt(3) - 1;
		return new WorldPoint(target.getX() + dx * reach - dy * side,
			target.getY() + dy * reach + dx * side, target.getPlane());
	}

	/**
	 * Three minutes at the one rock: a turn to it, the notes read for four
	 * seconds - with the scroll when the hands are free, over the pickaxe
	 * otherwise - and one shake of the head. Once per rock.
	 */
	private void readTheNotes()
	{
		if (sitting || scrollOut || noteTicks > 0)
		{
			return;
		}
		geraldDone = true;
		// The turn is now, not next tick: the pose must not open on the player.
		looks.clear();
		currentLook = target;
		currentLookTicks = TALLY_TICKS + 1;
		if (!borrow.isHolding() && !hands.isHolding())
		{
			hands.hold(ErrandController.SCROLL_PROP);
			scrollOut = hands.isHolding();
		}
		follower.setPoseOverride(NOTE_POSE);
		noteTicks = TALLY_TICKS;
		log.debug("Reading the notes on this rock");
	}

	// ------------------------------------------------------------------- bag

	/** Fourteen slots left: a note. Not for a player dropping as they go. */
	void bagHalf(String lastOption)
	{
		if (!"Drop".equalsIgnoreCase(lastOption))
		{
			takeOutTheScroll(NOTE_TICKS);
		}
	}

	/**
	 * The last slot gone: up, a look at the player, a shrug, and one step
	 * toward the nearest bank booth if there is one close and the ground
	 * allows it. Not for a player dropping as they go.
	 */
	void bagFull(String lastOption, WorldPoint here)
	{
		if ("Drop".equalsIgnoreCase(lastOption) || seat == null || walking)
		{
			return;
		}
		putScrollAway();
		playerLookTicks = PLAYER_LOOK_TICKS;
		face(here);
		emote(SHRUG);
		sitting = false;
		follower.setPoseOverride(0);

		WorldPoint bank = SkillingController.nearestObject(client, here, BANK_TILES,
			name -> "Bank booth".equals(name) || "Bank chest".equals(name));
		if (bank != null)
		{
			step = new WorldPoint(seat.getX() + Integer.signum(bank.getX() - seat.getX()),
				seat.getY() + Integer.signum(bank.getY() - seat.getY()), seat.getPlane());
			stepTicks = SHRUG_TICKS;
		}
	}

	// ------------------------------------------------------------------- end

	/**
	 * The session is over: the scroll away, the walk dropped, the sit ended.
	 *
	 * @param natural a proper end rather than an abort, which earns the
	 * stand-up; an abort simply lets the pose go
	 * @return the stand-up to play before whatever the ending plays, or 0
	 */
	int end(boolean natural)
	{
		putScrollAway();
		legs.clear();
		walking = false;
		int leadIn = 0;
		if (sitting)
		{
			sitting = false;
			follower.setPoseOverride(0);
			leadIn = natural ? STAND_UP : 0;
		}
		seat = null;
		facing = null;
		step = null;
		stepTicks = 0;
		looks.clear();
		currentLook = null;
		currentLookTicks = 0;
		return leadIn;
	}

	private int jitter(int base, int spread)
	{
		return base + random.nextInt(2 * spread + 1) - spread;
	}
}
