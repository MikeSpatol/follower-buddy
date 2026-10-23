// public:file skilling
package com.follower.follower;

import com.follower.FollowerConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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

	/**
	 * The sit emote's own stand-up, the plugin's rest already pairs the two:
	 * its second half only, a second long, so two ticks covers it.
	 */
	static final int STAND_UP = FollowerEntity.STAND_UP;
	static final int STAND_UP_TICKS = 2;

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
	 * The look at a nest at the player's feet: HUMAN_PICKUPFLOOR, the bend to
	 * the floor the errands and the pet look already use. It was 832,
	 * HUMAN_PICKUPTABLE, a reach forward at table height into the air in
	 * front of the follower; the LOOKING_INTO it was named for is no animation
	 * the game has (QA, 15 Oct). Played once a player has been seen doing it.
	 */
	static final int NEST_LOOK = 827;

	/**
	 * Working alongside: a stretch at the work between scroll breaks, counted
	 * in ticks actually spent working - about a minute and a quarter - and
	 * the break itself, about nine seconds. Mostly the work, now and then
	 * the notes.
	 */
	static final int WORK_STRETCH_TICKS = 120;
	static final int WORK_STRETCH_SPREAD = 40;
	static final int WORK_BREAK_TICKS = 15;
	static final int WORK_BREAK_SPREAD = 5;

	/** Two depletions closer than this share the one look for the next. */
	static final int NEXT_LOOK_GAP_TICKS = 15;
	private static final int PLAYER_BEAT_TICKS = 3;
	private static final int NEXT_LOOK_TICKS = 4;
	private static final int EMPTY_LOOK_TICKS = 3;

	/** HUMAN_FIRECOOKING: the errand's warming of the hands, replayed at a fire. */
	static final int WARM_HANDS = 897;
	private static final int WARM_EVERY_TICKS = 4;

	/** The anvil study puts the scroll down for a shrug about this often. */
	static final int STUDY_SHRUG_EVERY = 50;
	private static final int STUDY_RESUME_TICKS = 3;

	/**
	 * The peer over the shoulder: GENERIC_LOOK_AND_SEARCH, played once when
	 * the follower stands. It was HUMAN_DISTANT_IDLE held as a pose, which
	 * moves nothing more than 16 units and read as the follower doing nothing
	 * (QA, 15 Oct). This one starts and ends on the standing pose, 3.4 ticks.
	 */
	static final int PEER = 6840;
	static final int PEER_TICKS = 6;

	/** Three to five minutes between looks over the shoulder. */
	static final int SHOULDER_AFTER_TICKS = 400;
	private static final int SHOULDER_SPREAD = 100;

	/** Every seventh potion, two seconds facing away from the fumes. */
	static final int FUMES_EVERY = 7;
	private static final int FUMES_TICKS = 3;

	/** The kneel, proven on the follower by the altar errand. */
	static final int KNEEL = 645;

	/** A guard stirring earns a glance at the player, not more than this often. */
	static final int GUARD_GLANCE_GAP_TICKS = 30;
	private static final int GUARD_GLANCE_TICKS = 2;

	/** EMOTE_YES, proven by the mirror rules: the foreman's nod. */
	static final int NOD = 855;

	/** EMOTE_STAMPFEET, proven by the cold-stamp-feet rule; every thirty seconds. */
	static final int STAMP_FEET = 4278;
	static final int STAMP_EVERY_TICKS = 50;
	private static final int STAMP_SPREAD = 10;

	/** The compost: a step back and two seconds facing the other way. */
	private static final int COMPOST_TICKS = 3;

	/**
	 * How a seat is kept, shaped to the work. The controller reads it off the
	 * session ({@link SkillingController#modeFor}); the geometry and the
	 * habits both follow from it.
	 */
	enum Mode
	{
		/** The flank seat and the universal habits, nothing more. */
		NONE,
		/** Across the fire from the cook, hands to the flames. */
		FIRESIDE,
		/** One tile along the range's side. */
		RANGE,
		/** The anvil's far side, the notes out for the whole session. */
		ANVIL,
		/** Beside a wheel or a loom, sat down at once. */
		WHEEL,
		/** Work with nothing to click: a look over the shoulder now and then. */
		STANDING,
		/** The runecraft altar: one kneel, facing it. */
		ALTAR,
		/** The stall watch: off between the player and the guard, facing the guard. */
		STALL,
		/**
		 * A farming patch: kept like a gatherer's seat - not a step taken,
		 * working the patch alongside the player when there is room at it,
		 * reading otherwise - and the notes at the end of the visit.
		 */
		PATCH,
		/** The foreman: three tiles off the hotspot, notes out, a nod per hammer. */
		FOREMAN,
		/** The brazier in the cold: three tiles off, stamping the feet. */
		BRAZIER,
		/**
		 * Mining, woodcutting, fishing, hunting: not a step taken. Working
		 * alongside the player when the seat is a work tile - the work pose
		 * most of the time, the scroll now and then - and parked with the
		 * scroll out when it is only a seat.
		 */
		GATHERING,
	}

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

	/** Whether a borrowed tool was put down to free the hand for the scroll. */
	private boolean toolAside;
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

	private int lastDepletionTick;
	private int waterLookAt;

	/** The footprint the follower works, or null when the seat is only a seat. */
	private WorldPoint workMin;
	private WorldPoint workMax;

	/** The work pose it holds now, or 0. */
	private int working;

	/** Its work went - felled, emptied, moved on - until it is chosen again. */
	private boolean workGone;

	/** Whether it was last turned to its work, and the tick it was. */
	private boolean facingTheWork;
	private int facedAt;

	/** Ticks worked since the last break, and the stretch's length (0: not drawn yet). */
	private int workTicks;
	private int stretch;

	private Mode mode = Mode.NONE;
	private String activity = "";

	/** The guard the stall watch faces, or null. */
	private NPC guard;
	private int guardGlanceAt;

	private int warmAt;

	/** The anvil study: ticks until the scroll goes down, and until it comes back. */
	private int studyTicks;
	private int studyResume;

	private int shoulderAt;

	/** Ticks the peer over the shoulder has left; the seat is retaken after. */
	private int peerTicks;

	private int fumesDrops;
	private boolean wasAnimating;
	private int stampAt;

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

	/**
	 * A session began. The controller makes a fresh company for every
	 * session, so everything else starts over as it was made; here the
	 * counters each draw their own jitter.
	 */
	void begin(int now)
	{
		startTick = now;
		tallyJitter = random.nextInt(3) - 1;
		lastDropTick = -1;
		nodGap = nextNodGap();
		paceAt = jitter(PACE_AFTER_TICKS, 50);
		sitAt = jitter(SIT_AFTER_TICKS, 60);
		stretchAt = jitter(STRETCH_AFTER_TICKS, 300);
		stretchAgainAt = jitter(STRETCH_AGAIN_TICKS, 300);
		lastDepletionTick = -1;
	}

	/**
	 * The controller took, or retook, the seat. A walk in progress is
	 * dropped, and the seat's own opening plays: the notes come out at an
	 * anvil or a hotspot, the follower sits at a wheel, kneels at an altar.
	 */
	void seated(WorldPoint seat, WorldPoint target)
	{
		letGoOfTheWork();
		workMin = null;
		workMax = null;
		workGone = false;
		facingTheWork = false;
		this.seat = seat;
		this.target = target;
		this.facing = target;
		legs.clear();
		walking = false;
		legWait = 0;
		switch (mode)
		{
			case ANVIL:
			case FOREMAN:
				study();
				break;
			case WHEEL:
				sit();
				break;
			case ALTAR:
				gesture(KNEEL);
				break;
			default:
				break;
		}
	}

	void unseated()
	{
		// Not reading on the move: the parked scroll goes with the seat, and
		// the swing with the work.
		putScrollAway();
		letGoOfTheWork();
		workMin = null;
		workMax = null;
		seat = null;
		facing = null;
		legs.clear();
		walking = false;
	}

	/**
	 * The seat is a work tile: the follower works its own tree, rock or spot
	 * from it, alongside the player - the player's own when there was room,
	 * the next one along when there was not - and faces its middle.
	 */
	void seatedAtWork(WorldPoint seat, WorldPoint target, WorldPoint min, WorldPoint max, int now)
	{
		seated(seat, target);
		workMin = min;
		workMax = max;
		faceTheWork(now);
	}

	boolean isAtWork()
	{
		return workMin != null;
	}

	/**
	 * Its own work went: the swing stops, and a look at where it was - unless
	 * the depletion that took it has had its looks this tick already.
	 */
	void workGone(int now)
	{
		if (workMin == null || workGone)
		{
			return;
		}
		workGone = true;
		letGoOfTheWork();
		if (lastDepletionTick != now)
		{
			look(workMin, EMPTY_LOOK_TICKS);
		}
	}

	/**
	 * A moment has the feet: a gatherer's scroll and swing both stop, and
	 * come back when the seat is handed back.
	 */
	void excuse()
	{
		if (!parked())
		{
			return;
		}
		putScrollAway();
		letGoOfTheWork();
		facingTheWork = false;
	}

	/** The stall watch's guard; the seat faces it and watches it stir. */
	void watch(NPC guard)
	{
		this.guard = guard;
	}

	/** Whether the follower is off the seat on purpose, peering over the shoulder. */
	boolean isAway()
	{
		return peerTicks > 0;
	}

	/** Where the seat looks when nothing else claims it: the guard, or the work. */
	private WorldPoint watched()
	{
		WorldPoint at = mode == Mode.STALL && guard != null ? guard.getWorldLocation() : null;
		return at != null ? at : target;
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
			// The patch inspector keeps its notes for the end.
			if (mode != Mode.PATCH)
			{
				takeOutTheScroll(TALLY_TICKS);
			}
		}

		if ("herblore".equals(activity) && ++fumesDrops % FUMES_EVERY == 0)
		{
			look(awayTile(), FUMES_TICKS);
		}

		if (workMin != null)
		{
			// Working alongside: eyes on its own work, not a look at theirs.
			return;
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
	 * A target, the session's first or the next one over, and the shape of
	 * the work at it. The seat is not needed for the nod's glance, only for
	 * its aim; the per-target clocks (the notes, the water, the look over
	 * the shoulder) start over, and looks about the old one go.
	 */
	void aimAt(WorldPoint target, String activity, Mode mode, int now)
	{
		this.target = target;
		this.activity = activity == null ? "" : activity;
		this.mode = mode == null ? Mode.NONE : mode;
		waterLookAt = now + jitter(WATER_LOOK_TICKS, WATER_LOOK_SPREAD);
		shoulderAt = now + jitter(SHOULDER_AFTER_TICKS, SHOULDER_SPREAD);
		stampAt = now + jitter(STAMP_EVERY_TICKS, STAMP_SPREAD);
		guardGlanceAt = 0;
		fumesDrops = 0;
		forgetLooks();
	}

	/** The notes out for the whole session, put down for a shrug now and then. */
	private void study()
	{
		takeOutTheScroll(0);
		studyTicks = jitter(STUDY_SHRUG_EVERY, 5);
	}

	private void forgetLooks()
	{
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
	/**
	 * @param workPose the work to hold when working alongside, or 0
	 * @param bareHanded whether that work is done with nothing in the hand
	 */
	void tick(int now, WorldPoint here, boolean animating, String activity, int workPose,
		boolean bareHanded)
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
				log.debug("A step toward the bank, to {}", step); // public:cut logs
			}
			step = null;
		}

		watchForTheStop(now, here, animating);

		int worked = now - startTick;
		// Not while the follower is off the seat peering over the shoulder:
		// a walk begun from the player's front tile wins over the peer's
		// return, and the seat is then wherever the walk ended. The walks are
		// not lost, only late; their flags stay down until the peer is over.
		boolean onTheSeat = peerTicks == 0;
		if (parked())
		{
			// Play report: walking between the tile at the player's side and
			// the one behind them "looks off". Gathering takes no step: at a
			// work tile it works alongside, and on a plain seat it reads.
			if (workMin != null)
			{
				keepAtTheWork(now, workPose, bareHanded);
			}
			else
			{
				parkTheScroll(0);
			}
		}
		else if (onTheSeat && !paced && worked >= paceAt)
		{
			paced = true;
			pace();
		}
		else if (onTheSeat && !stretched && worked >= stretchAt)
		{
			stretched = true;
			stretch(here, false);
		}
		else if (onTheSeat && !stretchedAgain && worked >= stretchAgainAt)
		{
			stretchedAgain = true;
			stretch(here, true);
		}
		else if (!sitting && !stretched && worked >= sitAt)
		{
			sit();
		}
		else if (sitting && follower.getPoseOverride() == 0
			&& seat.equals(follower.getWorldLocation()))
		{
			// Something else put its pose over the sit - a copy of the work
			// made from the seat, an emote loop copied - and let go of it:
			// back down, rather than stood while it still counts as sitting.
			sit();
		}

		if ("fishing".equals(activity) && workMin == null && now >= waterLookAt)
		{
			waterLookAt = now + jitter(WATER_LOOK_TICKS, WATER_LOOK_SPREAD);
			look(waterTile(), WATER_GAZE_TICKS);
		}

		keepTheSeatItsWay(now, here, animating);
		if (peerTicks > 0)
		{
			// Off the seat, in front of the player: the seat's facing is theirs.
			return;
		}
		WorldPoint wanted = wantedFace(here);
		if (workMin != null && (wanted == null || wanted.equals(target)))
		{
			// "The work", at a work tile, is its own: the middle of it.
			if (!facingTheWork)
			{
				faceTheWork(now);
			}
		}
		else
		{
			face(wanted);
		}
	}

	/**
	 * The habits of the particular seat, once a tick. Each is a small thing
	 * that says the follower knows what this work is: hands to a fire, a
	 * shrug over the notes at the anvil, a look over the shoulder at a
	 * fletcher, a glance at the mark when the guard stirs, a nod per hammer,
	 * feet stamped in the cold.
	 */
	private void keepTheSeatItsWay(int now, WorldPoint here, boolean animating)
	{
		switch (mode)
		{
			case FIRESIDE:
				if (animating && !sitting && !scrollOut && now >= warmAt)
				{
					warmAt = now + WARM_EVERY_TICKS;
					follower.playAnimation(WARM_HANDS);
				}
				break;
			case ANVIL:
				if (scrollOut && --studyTicks <= 0)
				{
					putScrollAway();
					gesture(SHRUG);
					studyResume = STUDY_RESUME_TICKS;
				}
				else if (!scrollOut && studyResume > 0 && --studyResume == 0)
				{
					study();
				}
				break;
			case STANDING:
				overTheShoulder(now);
				break;
			case STALL:
				if (guard != null && now >= guardGlanceAt
					&& guard.getPoseAnimation() != guard.getIdlePoseAnimation())
				{
					guardGlanceAt = now + GUARD_GLANCE_GAP_TICKS;
					lookNow(here, GUARD_GLANCE_TICKS);
				}
				break;
			case FOREMAN:
				if (wasAnimating && !animating)
				{
					gesture(NOD);
				}
				break;
			case BRAZIER:
				if (now >= stampAt && !sitting)
				{
					stampAt = now + jitter(STAMP_EVERY_TICKS, STAMP_SPREAD);
					gesture(STAMP_FEET);
				}
				break;
			default:
				break;
		}
		wasAnimating = animating;
	}

	/**
	 * Every few minutes at work with nothing to click, a look over the
	 * shoulder: to the tile in front of the player, the peer held for a few
	 * ticks, and back to the seat. The front tile the pathing refuses is no
	 * look, and the next is a few minutes off again.
	 */
	private void overTheShoulder(int now)
	{
		if (peerTicks > 0)
		{
			if (--peerTicks == 0)
			{
				if (follower.stayAt(seat))
				{
					follower.setStayFaceTile(target);
					facing = target;
				}
			}
			return;
		}
		if (now < shoulderAt)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		// Walking is only seen here on the tick a walk begins; a look then
		// would go to the front tile and the walk set off from there with the
		// peer held.
		if (sitting || scrollOut || walking || local == null)
		{
			// Busy with something; the next tick, not the next five minutes.
			return;
		}
		shoulderAt = now + jitter(SHOULDER_AFTER_TICKS, SHOULDER_SPREAD);
		if (!follower.moveToFront(local))
		{
			return;
		}
		peerTicks = PEER_TICKS;
		facing = null;
		// On arrival at the front tile: on the way, moving would drop it.
		follower.playWhenStill(PEER, PEER_TICKS);
		log.debug("A look over the shoulder"); // public:cut logs
	}

	/**
	 * What the seat looks at right now, in order of claim: a look being
	 * taken, the player while the scroll is out (the anvil and the hotspot
	 * excepted: the notes are about the work) or the bag is full, the work
	 * for a nod, the player when sitting or when they have stopped, the work
	 * otherwise - or the guard, on the stall watch.
	 */
	private WorldPoint wantedFace(WorldPoint here)
	{
		if (currentLook != null)
		{
			return currentLook;
		}
		boolean studying = mode == Mode.ANVIL || mode == Mode.FOREMAN || parked();
		if ((scrollOut && !studying) || playerLookTicks > 0)
		{
			return here;
		}
		if (lookTicks > 0)
		{
			return target;
		}
		return sitting || stopped ? here : watched();
	}

	/** One tile past the seat, away from the work: where a turned back looks. */
	private WorldPoint awayTile()
	{
		WorldPoint base = seat != null ? seat : follower.getWorldLocation();
		if (base == null || target == null)
		{
			return null;
		}
		int dx = Integer.signum(base.getX() - target.getX());
		int dy = Integer.signum(base.getY() - target.getY());
		if (dx == 0 && dy == 0)
		{
			dy = -1;
		}
		return new WorldPoint(base.getX() + dx, base.getY() + dy, base.getPlane());
	}

	/** The compost went on: two seconds facing the other way, where it stands. */
	void compost()
	{
		if (walking)
		{
			return;
		}
		look(awayTile(), COMPOST_TICKS);
	}

	/** The same for a chain - the level-up's send-off - stood up first when sitting. */
	void gesture(int[] animationIds)
	{
		if (!sitting)
		{
			follower.playAnimations(animationIds);
			return;
		}
		int[] chain = new int[animationIds.length + 1];
		chain[0] = STAND_UP;
		System.arraycopy(animationIds, 0, chain, 1, animationIds.length);
		follower.playAnimations(chain);
	}

	/**
	 * A look at a tile for the controller's own reactions: the face on the
	 * seat, or a glance straight away off it - the queue only runs during a
	 * session, and the out-of-runes think happens outside one.
	 */
	void lookAt(WorldPoint tile, int ticks)
	{
		if (seat == null && tile != null)
		{
			follower.glanceAt(tile, ticks * 600);
		}
		else
		{
			look(tile, ticks);
		}
	}

	/**
	 * The patch inspector's notes, after the session has ended: the scroll
	 * out over free hands while the controller keeps the feet a moment.
	 *
	 * @return the ticks to keep the feet, or 0 when there was no note to take
	 */
	int endNote()
	{
		return borrow.isHolding() || hands.isHolding() || !holdTheScroll(0) ? 0 : NOTE_TICKS;
	}

	private void face(WorldPoint tile)
	{
		if (tile != null && !tile.equals(facing))
		{
			follower.setStayFaceTile(tile);
			facing = tile;
			facingTheWork = false;
		}
	}

	/** Turned to the middle of its own work, exactly, as the player is to theirs. */
	private void faceTheWork(int now)
	{
		follower.setStayFaceArea(workMin, workMax);
		facing = null;
		facingTheWork = true;
		facedAt = now;
	}

	/**
	 * At work: the work pose held most of the time, a break with the scroll
	 * now and then. The swing waits until the follower stands on its tile,
	 * turned to the work, a tick after the turn began; it stops for a look,
	 * for the player stopping, and for the work going. Somebody else's pose
	 * over it - a stranger's peer, a held emote copied - is waited out rather
	 * than fought.
	 */
	private void keepAtTheWork(int now, int pose, boolean bareHanded)
	{
		if (scrollOut && scrollTicks > 0)
		{
			// On a break; puts the reading pose back if it was let go of.
			parkTheScroll(0);
			return;
		}
		if (scrollOut)
		{
			// A plain seat's scroll, carried onto a work tile: the work now.
			// Left out, it was read at the vein for the rest of the session.
			putScrollAway();
		}
		boolean ready = pose > 0 && !workGone && !stopped && !sitting && currentLook == null
			&& playerLookTicks == 0 && (bareHanded || borrow.isHolding() && !toolAside)
			&& seat.equals(follower.getWorldLocation()) && follower.isSettled()
			&& follower.isTurned() && facingTheWork && now > facedAt;
		if (!ready)
		{
			letGoOfTheWork();
			return;
		}
		int held = follower.getPoseOverride();
		if (held != 0 && held != working)
		{
			return;
		}
		if (held != pose)
		{
			follower.setPoseOverride(pose);
			working = pose;
		}
		if (stretch == 0)
		{
			stretch = jitter(WORK_STRETCH_TICKS, WORK_STRETCH_SPREAD);
		}
		if (++workTicks >= stretch)
		{
			letGoOfTheWork();
			workTicks = 0;
			stretch = 0;
			parkTheScroll(jitter(WORK_BREAK_TICKS, WORK_BREAK_SPREAD));
		}
	}

	/** A gatherer's seat or a patch's: kept without a step, working or reading. */
	private boolean parked()
	{
		return mode == Mode.GATHERING || mode == Mode.PATCH;
	}

	/** The swing stops; only its own pose is cleared. */
	private void letGoOfTheWork()
	{
		if (working != 0)
		{
			follower.clearPoseOverride(working);
			working = 0;
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
			log.debug("The player stopped; looking over"); // public:cut logs
		}
		if (stopped && stopWait > 0 && --stopWait == 0)
		{
			gesture(SCRATCH_HEAD);
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
		forgetLooks();
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
			log.debug("The walk to {} was refused", next); // public:cut logs
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
		log.debug("Sitting in for the long haul"); // public:cut logs
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

	/**
	 * A one-shot, the controller's own reactions among them, stood up first
	 * when sitting; the pose sits it back down after.
	 */
	void gesture(int animationId)
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
	 * The scroll, through the same hands as the errand's, for a few ticks -
	 * or until put away, for none. Skipped over a borrowed tool (they share
	 * the weapon slot), over a scroll already out for something else, while
	 * sitting, off the seat, and over the player's shoulder: a tally due on
	 * the tick of a look took the scroll out in front of the player and read
	 * it for the whole look, which was never seen.
	 */
	private void takeOutTheScroll(int ticks)
	{
		if (seat == null || sitting || walking || scrollOut || peerTicks > 0
			|| borrow.isHolding() || hands.isHolding())
		{
			return;
		}
		holdTheScroll(ticks);
	}

	/**
	 * The scroll and quill into the errand's hands and the writing pose on,
	 * out for so many ticks, or until put away for none.
	 *
	 * @return false when the hands decline them
	 */
	private boolean holdTheScroll(int ticks)
	{
		hands.hold(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP);
		if (!hands.isHolding())
		{
			return false;
		}
		follower.setPoseOverride(ErrandController.WRITE_SCROLL_POSE);
		scrollOut = true;
		scrollTicks = ticks;
		return true;
	}

	/**
	 * The gatherer's company, parked: the scroll out and left out, over a
	 * borrowed tool set down for it. Put away to join in - the copy swings
	 * the tool - and out again once back on the seat.
	 */
	private void parkTheScroll(int ticks)
	{
		if (seat == null || sitting || walking || peerTicks > 0 || playerLookTicks > 0)
		{
			return;
		}
		if (scrollOut)
		{
			// Something else let go of the pose over it - a held emote
			// copied and done - and left the scroll in the hand:
			// back to reading it, rather than holding it at the side.
			if (follower.getPoseOverride() == 0)
			{
				follower.setPoseOverride(ErrandController.WRITE_SCROLL_POSE);
			}
			return;
		}
		if (borrow.isHolding() && !toolAside)
		{
			toolAside = borrow.setAside();
		}
		// Something else in the hand is not the scroll's to take.
		if (!hands.isHolding())
		{
			holdTheScroll(ticks);
		}
	}

	void putScrollAway()
	{
		if (scrollOut)
		{
			scrollOut = false;
			scrollTicks = 0;
			follower.setPoseOverride(0);
			hands.release();
		}
		if (toolAside)
		{
			// Whatever the hands were freed for has finished with them.
			toolAside = false;
			borrow.takeBack();
		}
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

	/**
	 * A look that starts this tick when nothing is being looked at, rather
	 * than the next: a glance at a guard stirring is late a tick later.
	 */
	private void lookNow(WorldPoint tile, int ticks)
	{
		if (tile == null || currentLook != null)
		{
			look(tile, ticks);
			return;
		}
		currentLook = tile;
		currentLookTicks = ticks;
		if (seat == null)
		{
			follower.glanceAt(tile, ticks * 600);
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
	 * flinches before looking at the player; a rock worked long enough
	 * empties, and the follower looks at the empty rock, at the next full
	 * one, then at the player. No step clear: a gatherer's company stays
	 * parked where it is. Anything
	 * quicker is a look at the player and then at where the next one is -
	 * unless the last depletion was moments ago, when once was enough.
	 *
	 * @param worked ticks this target was worked
	 * @param next the nearest same-name target, or null when there is none
	 */
	void depleted(String activity, int worked, WorldPoint next, WorldPoint here, int now)
	{
		boolean recent = lastDepletionTick >= 0 && now - lastDepletionTick < NEXT_LOOK_GAP_TICKS;
		lastDepletionTick = now;
		if (walking)
		{
			return;
		}
		if ("woodcutting".equals(activity) && worked >= TIMBER_AFTER_TICKS)
		{
			gesture(FLINCH);
			look(here, PLAYER_BEAT_TICKS);
			look(next, NEXT_LOOK_TICKS);
			return;
		}
		if ("mining".equals(activity) && worked >= ROCK_AFTER_TICKS)
		{
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

	/**
	 * A bird's nest at the player's feet: a look at it, and the bend to it
	 * when a player has been seen doing it.
	 */
	void nest(WorldPoint tile, boolean peerProven)
	{
		look(tile, NEXT_LOOK_TICKS);
		if (peerProven && !walking)
		{
			gesture(NEST_LOOK);
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
	 * allows it - not from a gathering seat, which stays parked. Not for a
	 * player dropping as they go.
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
		gesture(SHRUG);
		sitting = false;
		follower.setPoseOverride(0);

		WorldPoint bank = SkillingController.nearestObject(client, here, BANK_TILES,
			SkillingController.BANK_NAMES);
		if (bank != null && !parked())
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
		letGoOfTheWork();
		workMin = null;
		workMax = null;
		legs.clear();
		walking = false;
		peerTicks = 0;
		guard = null;
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
		forgetLooks();
		return leadIn;
	}

	private int jitter(int base, int spread)
	{
		return base + random.nextInt(2 * spread + 1) - spread;
	}
}
