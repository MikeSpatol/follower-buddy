// public:file skilling
package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.Resources;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer; // public:cut errands
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
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
	/**
	 * The skilling company's tables - the work options, the tools, the
	 * outfits and the like - read once from skilling.properties: each a key
	 * and its values, comma separated.
	 */
	private static final Properties DATA = Resources.properties("skilling.properties");

	/** The option words that start work, lowercased, and the skill each points at. */
	private static final Map<String, String> WORK = new HashMap<>();

	static
	{
		for (String key : DATA.stringPropertyNames())
		{
			if (key.startsWith("option."))
			{
				for (String word : values(key))
				{
					WORK.put(word.toLowerCase(Locale.ROOT), key.substring("option.".length()));
				}
			}
		}
	}

	/**
	 * The work the game lets a player do from one side only: every object
	 * with a work option whose cache definition carries an access mask. The
	 * client drops that mask when it loads the definition, so it cannot be
	 * read at run time; skilling.properties has it, read off the cache.
	 * Everything else is worked from any side a fence does not close.
	 */
	private static final Set<Integer> ONE_SIDED = idSet("oneSided");

	/** Ticks the player has to be at it before the seat is taken. */
	static final int SEAT_AFTER_TICKS = 6;

	/** While a favour is owed the seat is taken in half the time. */
	static final int GRATEFUL_SEAT_AFTER_TICKS = SEAT_AFTER_TICKS / 2;

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

	/** How far off the player the follower looks for something of its own to work. */
	static final int ALONGSIDE_TILES = 6;

	/** Work tiles asked of the pathing, best first, before it settles for a seat. */
	static final int ALONGSIDE_PATH_ASKS = 6;

	/** Steps beyond the straight line it will walk to reach one. */
	static final int ALONGSIDE_DETOUR_STEPS = 4;

	/** Its own work gone, a look at where it was before it chooses again. */
	static final int WORK_GONE_TICKS = 3;

	/** A moved fishing spot turns up about this far from the old one. */
	private static final int NEW_SPOT_TILES = 8;

	/** Free slots at which the bag counts as half full. */
	static final int HALF_BAG_FREE = 14;

	/** A bank this close to the work makes a walk-off a bank trip, maybe. */
	static final int BANK_TRIP_TILES = 40;

	/** The seat is kept this long for the bank to open; the walk there. */
	static final int BANK_WALK_TICKS = 30;

	/** Three minutes: the most a bank trip holds the seat once the bank opened. */
	static final int BANK_TRIP_CAP_TICKS = 300;

	/** Back from the bank, a gatherer's seat waits this long for the first swing. */
	static final int BANK_BACK_TICKS = 10;

	/** How far the next same-name target is looked for after a depletion. */
	private static final int NEXT_TARGET_TILES = 10;

	/**
	 * Which scenery counts as the bank, for the trip and the bag's step. In
	 * any case: the game capitalises a few of its own, "Bank Booth" at Burgh
	 * de Rott and "Bank Chest" on Fossil Island among them.
	 */
	static final Predicate<String> BANK_NAMES =
		name -> "Bank booth".equalsIgnoreCase(name) || "Bank chest".equalsIgnoreCase(name);

	/** The stall watch stands this far off the player, toward the guard. */
	static final int STALL_WATCH_TILES = 5;
	private static final int GUARD_TILES = 15;

	/** Who counts as the guard a stall is watched against, in one place. */
	static final String[] GUARD_NAMES = {"Guard", "Market Guard", "Knight of Ardougne", "Paladin"};

	/** SpotanimID.STUNNED_THIEVING: the stars over a failed pickpocket. */
	static final int STUN_GRAPHIC = 245;

	/** A stun earns a head shake one time in three, and not again for twenty seconds. */
	static final int STUN_COOLDOWN_TICKS = 33;
	private static final int STUN_ONE_IN = 3;

	/** The stun's graphic and its message inside this many ticks are the one stun. */
	private static final int STUN_SAME_TICKS = 2;

	/** HUMAN_LAYTRAP, the player's own; the trap object follows it onto their tile. */
	static final int LAY_TRAP = 5208;
	private static final int LAY_SETTLE_TICKS = 3;

	/** The trap watcher stands two tiles off the trap, for two minutes at most. */
	static final int TRAP_WATCH_TILES = 2;
	static final int TRAP_WATCH_TICKS = 200;

	/** A trap run is over once the player is this far from its first trap. */
	static final int TRAP_RUN_TILES = 10;
	private static final int TRAP_LOOK_TICKS = 3;

	/** A fire lit within this of where the player just stood is theirs. */
	static final int FIRE_LINE_TILES = 3;
	static final int FIRE_LINE_EVERY = 4;

	/** A fire this long after the last starts the count over. */
	private static final int FIRE_LINE_LAPSE_TICKS = 100;

	/** Three seconds at the fire once there. */
	static final int FIRE_WARM_TICKS = 5;

	/** How long the walk to it is given before the visit is written off. */
	static final int FIRE_ARRIVAL_PATIENCE = 10;
	private static final int FIRE_GAG_ONE_IN = 50; // public:cut errands

	/** The cold-stamp-feet rule's regions: the Wintertodt camp and the snows. */
	static final Set<Integer> COLD_REGIONS = idSet("coldRegions");

	/**
	 * How long a borrowed tool stays in the hand once a session ends. One
	 * tree is not the work; the walk to the next one is part of it, and
	 * handing an axe back twice a minute to take it straight out again
	 * reads as a fault rather than as company.
	 */
	static final int TOOL_GRACE_TICKS = 50;

	/** The look at the player that goes with the send-off, off the seat. */
	private static final int BEAT_GLANCE_MS = 1800;

	/**
	 * How much longer than the grace a send-off will wait for the player to
	 * stand still. Movement cancels an emote, and a player who has just
	 * walked away from a tree is usually still walking, so a bow played the
	 * instant the grace lapsed was a bow nobody saw.
	 */
	static final int BEAT_WAIT_TICKS = 100;

	/** EMOTE_CLAP, proven by the mirror rules: the task ledger's applause. */
	static final int CLAP = 865;
	private static final int CLAP_TICKS = 3;

	/** Ticks the ledger's scroll is out after the clap. */
	static final int LEDGER_TICKS = 8;
	static final int LEDGER_COOLDOWN_TICKS = 20;

	/** Out of runes: a think, and not again for half a minute. */
	static final int RUNES_COOLDOWN_TICKS = 50;

	/**
	 * The game's own words, as the smallest certain fragment of each, matched
	 * without case. The thieving stun ("You have been stunned!"), an empty
	 * rune pouch ("You do not have enough ... to cast this spell."), the
	 * slayer task ending ("... return to a Slayer master.") and the next one
	 * being set ("Your new task is to kill ..." in the master's dialog, or
	 * "... you're now assigned to kill ..." for a boss task).
	 */
	static final String STUNNED_MESSAGE = "stunned";
	static final String NO_RUNES_MESSAGE = "do not have enough";
	static final String TASK_DONE_MESSAGE = "return to a slayer master";
	static final String[] NEW_TASK_MESSAGES = {"new task is to kill", "now assigned to"};

	/** The skills worked with nothing in the world to click; a drop opens them. */
	private static final Set<Skill> STANDING_SKILLS = new HashSet<>(Arrays.asList(
		Skill.FLETCHING, Skill.HERBLORE, Skill.CRAFTING));

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final Consumer<TriggerEvent> dispatch;
	private final BooleanSupplier inCombat;

	/** True when thrall work or the spectator owns the feet; both beat the seat. */
	private final BooleanSupplier feetTaken;
	private final BooleanSupplier bankOpen;
	private final Borrow borrow;
	private final ErrandController.Hands hands;

	/** Every counter's jitter comes from here; a test may seed it. */
	final Random random = new Random();

	/** What the follower does in the seat, made afresh for each session; see {@link KeepingCompany}. */
	private KeepingCompany company;

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
	private int refusedHat;

	/** Ticks a borrowed tool is kept between sessions, and the work it is for. */
	private int graceTicks;
	private String graceActivity;

	/** Ticks already spent waiting for a still moment to bow in. */
	private int beatWait;

	/**
	 * When the current run of one kind of work began. The send-off is for
	 * the whole stint, so an hour of chopping earns its bow even though no
	 * single tree lasted two minutes.
	 */
	private int stintStart;
	private int ticksSinceLoading = 1000;

	/**
	 * The seat excused for a moment of the follower's own - a level, the
	 * pet - by {@link Moments}: not retaken, and the company's habits paused,
	 * until the moment hands the feet back.
	 */
	private boolean excused;

	private int lastFreeSlots = -1;
	private boolean halfArmed = true;

	/**
	 * The seat kept through a bank trip after the session ended: from the
	 * walk-off, and confirmed when the bank opens. Until then the tick it
	 * lapses is the walk's; after, the cap's.
	 */
	private boolean bankTrip;
	private boolean bankTripConfirmed;
	private int bankTripUntil;

	/** Whether the player has been away from the seat, so that coming back counts. */
	private boolean bankTripAway;

	/** Whether the seat kept for the trip is a gatherer's, carried into the next session. */
	private boolean bankTripGathering;

	/** Whether a player has been seen playing the nest look, so it is safe to. */
	private boolean nestLookProven;

	/** The player's tile at the last tick; a fire lit near it is theirs. */
	private WorldPoint previousHere;

	/**
	 * The trap watch, outside any session: the tick the lay animation
	 * played, the trap that followed it onto the player's tile, and the
	 * seat two tiles off it.
	 */
	private int layTick = -1000;
	/** Where the player stood to lay it: the trap turns up there, after they step off. */
	private WorldPoint layTile;
	private WorldPoint trapTile;
	private int trapId;
	private WorldPoint trapSeat;
	private int trapUntil;
	private int trapLookTicks;

	/** The trap watcher's notes: out now and then over a run, and when next. */
	private boolean trapReading;
	private int trapReadAt;
	private int trapReadUntil;

	/** The fire line: fires counted, and the visit to every fourth. */
	private int fires;
	private int lastFireTick = -1000;
	private WorldPoint fireSeat;
	private int fireWarmAt;
	private int fireLeaveAt;

	/** The task ledger's scroll, and the tick the clap hands over to it. */
	private final PropFlourish ledger;
	private int ledgerAt;
	private int ledgerCooldownUntil;
	private int runesCooldownUntil;
	private int stunSeenTick = -1000;
	private int stunCooldownUntil;

	/** Ticks the feet are kept after a session for the patch inspector's notes. */
	private boolean endNoteOut;

	// public:cut errands
	/**
	 * A way to start an errand by key, silently or not: the prayer at the
	 * altar borrows the altar errand, the fire line the too-close gag. Null
	 * when nothing offered one.
	 */
	private BiConsumer<String, Boolean> errands;

	// public:end errands
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
		this.hands = hands;
		this.company = new KeepingCompany(client, follower, config, hands, borrow, random);
		this.ledger = new PropFlourish(follower, hands);
	}

	// public:cut errands
	/**
	 * Offers the errands: (key, silent), as {@link ErrandController#force}.
	 * Without one the prayer kneel and the fire gag simply do not happen.
	 */
	public void setErrands(BiConsumer<String, Boolean> force)
	{
		this.errands = force;
	}

	// public:end errands
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
	/**
	 * One line of what the company is doing, for {@code ::follower status}:
	 * enough to tell a session that never opened from one that opened on
	 * the wrong thing, which is the difference between the follower doing
	 * nothing and the follower doing nothing yet.
	 */
	/** An item's name off the live cache, or null when it cannot be read. */
	private String itemName(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		try
		{
			net.runelite.api.ItemComposition item = client.getItemDefinition(itemId);
			return item == null ? null : item.getName();
		}
		catch (RuntimeException notInTheCache)
		{
			return null;
		}
	}

	/** Where the player stands, or null while there is no player. */
	private WorldPoint playerTile()
	{
		return FollowerEntity.playerTile(client);
	}

	/** Whether the follower is somebody else's just now, fighting, or not out at all. */
	private boolean elsewhere()
	{
		return feetTaken.getAsBoolean() || inCombat.getAsBoolean() || !follower.isSpawned();
	}

	/** Hands the feet back, when they are still the company's to give. */
	private void letGo()
	{
		if (follower.isStaying() && !feetTaken.getAsBoolean())
		{
			follower.resumeFollowing();
		}
	}

	// public:cut dev
	public String describe()
	{
		if (!config.keepsCompany())
		{
			return "off in the settings";
		}
		if (session == null)
		{
			String waiting = pending == null
				? "no work" : "waiting on a swing at " + pending.targetName;
			return graceTicks > 0
				? waiting + ", still holding " + borrow.heldItem()
					+ " for " + graceTicks + " more ticks"
				: waiting;
		}
		int worked = client.getTickCount() - session.startTick;
		return (session.activity == null ? "work" : session.activity)
			+ " on " + session.targetName
			+ ", " + worked + " ticks, " + session.xpDrops + " drops"
			+ ", seat " + (seat == null ? "none yet" : seat.getX() + "," + seat.getY())
			+ ", holding " + (borrow.isHolding() ? String.valueOf(borrow.heldItem()) : "nothing")
			+ (borrow.hasHat() ? ", wearing " + borrow.hatWorn() : "")
			+ describeHand();
	}

	/** What the player's own hand reports, which is what a borrow reads. */
	private String describeHand()
	{
		Player local = client.getLocalPlayer();
		if (local == null || session == null)
		{
			return "";
		}
		int hand = Borrow.wornWeapon(local.getPlayerComposition());
		Set<Integer> worn = Borrow.wornItems(local.getPlayerComposition());
		return " | your hand " + (hand == 0 ? "empty" : hand + " " + itemName(hand))
			+ ", would borrow "
			+ Borrow.toolFor(session.activity, session.lastAnimation, worn, hand, itemName(hand));
	}

	// public:end dev
	/**
	 * Whether a mirrored pose may play from where the follower stands. During
	 * a session the answer is only from the seat: a cast copied while the
	 * follower is still trailing the player lands on dry ground, which is the
	 * fishing-the-grass the mimic was seen doing. With no session on, the
	 * mimic keeps its old freedom.
	 */
	public boolean mirrorsFromHere()
	{
		return session == null || seat != null;
	}

	public boolean holdsTheFeet()
	{
		return seat != null || releaseTicks > 0 || trapSeat != null || fireSeat != null
			|| joinTile != null || ledger.isActive();
	}

	// public:cut errands
	/**
	 * Whether the errands must stand aside: the feet are held, or a session
	 * is on - except a prayer at an altar, which is the one session that
	 * borrows an errand rather than a seat. This is the busy gate an errand
	 * under way must also pass; the errands' own session gate keeps every
	 * unforced start out of the prayer session too.
	 */
	// public:end errands
	/**
	 * Excuses the seat for a moment, or hands it back. Excused, the seat is
	 * kept in mind but not retaken - the moment has the feet - and the
	 * company's habits wait; handed back, the next tick retakes it.
	 */
	public void setExcused(boolean excused)
	{
		if (excused && !this.excused && session != null)
		{
			// The moment has the feet: a gatherer's scroll and swing stop too.
			company.excuse();
		}
		this.excused = excused;
	}

	/**
	 * A one-shot of somebody else's - the level-up's clap - played the
	 * seat's way: a look at the player first, and up off the sit before it.
	 * Off the seat it is a glance and the emote, which is all it would be.
	 */
	public void gesture(int[] animationIds, WorldPoint player)
	{
		company.lookAt(player, CLAP_TICKS);
		company.gesture(animationIds);
	}

	/** Whether the follower owes the player one just now; null means never. */
	private java.util.function.BooleanSupplier grateful;

	public void setGrateful(java.util.function.BooleanSupplier grateful)
	{
		this.grateful = grateful;
	}

	/** Ticks into the work before the seat is taken: fewer while a favour is owed. */
	int seatAfter()
	{
		return grateful != null && grateful.getAsBoolean() ? GRATEFUL_SEAT_AFTER_TICKS : SEAT_AFTER_TICKS;
	}

	// public:cut errands
	public boolean blocksErrands()
	{
		// A tool kept between trees is still in the hand, and every errand
		// lets go of the hand when it ends.
		return holdsTheFeet() || borrow.isHolding()
			|| (session != null && !"prayer".equals(session.activity));
	}

	// public:end errands
	/** Whether the follower is sitting in for the long haul. */
	public boolean isSitting()
	{
		return company.isSitting();
	}

	// ---------------------------------------------------------------- inputs

	/** Any menu option the player clicked, tags off; the bag reads it. */
	public void noteMenuOption(String plain)
	{
		noteMenuOption(plain, "");
	}

	/**
	 * The same with the option's target, tags off. Compost goes on a patch
	 * as "Use" with the compost named in the target, and the follower turns
	 * away from it where it stands.
	 */
	public void noteMenuOption(String plain, String target)
	{
		lastOption = plain == null ? "" : plain;
		if (session != null && "farming".equals(session.activity)
			&& "Use".equalsIgnoreCase(lastOption) && target != null
			&& target.toLowerCase(Locale.ROOT).contains("compost"))
		{
			company.compost();
		}
	}

	/** Whether a menu option is one of the words that start work. */
	public static boolean isWorkOption(String option)
	{
		return WORK.keySet().stream().anyMatch(plainOption(option)::equalsIgnoreCase);
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
		return WORK.getOrDefault(plainOption(option).toLowerCase(Locale.ROOT), "");
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
		WorldPoint here = playerTile();
		if (here == null)
		{
			return;
		}
		String plain = plainOption(option);
		String name = targetName == null ? "" : targetName;
		int now = client.getTickCount();

		// The same patch whatever its crop is called now and whatever is done to
		// it; anything else is the same work only by the same option - a net
		// at a spot where the player harpooned is new work, with its own tool.
		boolean samePatch = session != null && targetId == session.targetId
			&& min.equals(session.targetTile);
		if (session != null && !session.standing
			&& (name.equals(session.targetName) && plain.equalsIgnoreCase(session.option)
				|| samePatch)
			&& min.getPlane() == session.targetTile.getPlane()
			&& (min.distanceTo(session.targetTile) <= WALK_OFF_TILES
				|| (session.depleted && "fishing".equals(session.activity)
					&& min.distanceTo(session.targetTile) <= NEW_SPOT_TILES)))
		{
			boolean moved = !min.equals(session.targetTile);
			session.targetId = targetId;
			session.targetName = name;
			session.targetTile = min;
			session.targetMax = max == null ? min : max;
			session.targetNpc = npc;
			session.depleted = false;
			session.newSpot = null;
			session.targetSinceTick = now;
			session.lastEvidenceTick = now;
			// The player may have a walk ahead of them - a moved spot is
			// yards off - so where they work from is read at the next swing,
			// and the old seat, which may now be on the line to the new
			// target or on it, is chosen again from scratch once they are at it.
			session.settling = true;
			reseat = true;
			seatDueTick = now + seatAfter();
			if (moved)
			{
				// Not for the same target: the compost's turn away, queued a
				// moment ago by the same click, would be wiped with the looks.
				company.aimAt(faceTile(), session.activity, modeFor(here), now);
			}
			log.debug("Work moved to {} at {}", name, min); // public:cut logs
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
		if (animationId == LAY_TRAP)
		{
			// The trap object turns up on the player's tile a moment later.
			layTick = client.getTickCount();
			layTile = playerTile();
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
	 * Any player's animation, the local one included. The nest look is
	 * played on the follower only once a player skeleton has been seen
	 * playing it; until then the nest gets a look and no more.
	 */
	public void notePlayerAnimation(int animationId)
	{
		if (animationId == KeepingCompany.NEST_LOOK)
		{
			nestLookProven = true;
		}
	}

	/**
	 * A ground item appeared. A bird's nest landing at the feet of a player
	 * who is chopping is worth a look down, and the peer once that is known
	 * to be safe on the follower.
	 */
	public void itemSpawned(String name, WorldPoint tile)
	{
		if (session == null || !"woodcutting".equals(session.activity) || name == null
			|| tile == null || !name.toLowerCase(Locale.ROOT).startsWith("bird nest"))
		{
			return;
		}
		if (tile.equals(playerTile()))
		{
			company.nest(tile, nestLookProven);
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
		if (skill == Skill.HUNTER && trapSeat != null)
		{
			// A catch: the run is still on.
			trapUntil = now + TRAP_WATCH_TICKS;
		}
		if (pending != null)
		{
			startPending();
		}
		if (session == null && skill != null && STANDING_SKILLS.contains(skill))
		{
			startStanding(skill, now);
		}
		if (session == null)
		{
			return 0;
		}
		// The FIRST drop names the work, and only the first. A cook at a
		// fire lights the next one between batches, and firemaking experience
		// landing mid-session renamed the work, re-shaped the seat and moved
		// the follower - then the next fish renamed it back and moved it
		// again. Play report: "moves erratically to try and cook with the
		// player". One naming was always the intent; the code simply did not
		// say so.
		if (skill != null && session.xpDrops == 0)
		{
			String named = skill.getName().toLowerCase(Locale.ROOT);
			if (!named.equals(session.activity))
			{
				// A "Use" click said nothing; the drop names the work, and the
				// seat may be the wrong shape for it: across a fire, not on
				// its flank. Chosen again, once the player is at it.
				session.activity = named;
				company.aimAt(faceTile(), named, modeFor(playerTile()), now);
				if (seat != null)
				{
					reseat = true;
					seatDueTick = now;
				}
			}
		}
		session.xpDrops++;
		session.lastDropTick = now;
		session.lastEvidenceTick = now;
		if (now - session.startTick >= seatAfter())
		{
			// The drop may be what named the activity, and so the tool.
			maybeBorrow();
		}
		if (joinTile == null)
		{
			// Not the tally scroll into the hand mid-swing.
			company.drop(session.xpDrops, now);
		}
		return session.xpDrops;
	}

	/**
	 * A scenery object appeared. During a session only the target's tile
	 * matters: a different id there is the stump, the empty rock, the used
	 * patch. Outside one, the trap the player just laid and the fire they
	 * just lit are the two things worth noticing.
	 */
	public void objectSpawned(int id, WorldPoint min, WorldPoint max)
	{
		if (ticksSinceLoading <= LOAD_SETTLE_TICKS || min == null || !config.keepsCompany())
		{
			return;
		}
		if (watchingObjects() && id != session.targetId && covers(min, max, session.targetTile))
		{
			depleted();
			return;
		}
		if (trapTile != null)
		{
			if (!covers(min, max, trapTile))
			{
				// The next trap of the run: it goes on, the follower where it is.
				maybeTheTrap(id, min, max);
			}
			else if (id != trapId)
			{
				// The trap sprang, or fell: a glance at the player.
				trapId = id;
				WorldPoint here = playerTile();
				if (trapSeat != null && here != null)
				{
					follower.setStayFaceTile(here);
					trapLookTicks = TRAP_LOOK_TICKS;
				}
			}
			return;
		}
		if (session != null)
		{
			return;
		}
		if (!maybeTheTrap(id, min, max))
		{
			maybeTheFire(id, min, max);
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
		// Standing work has no target in the world; nothing can deplete it.
		return session != null && session.targetNpc == null && !session.standing
			&& ticksSinceLoading > LOAD_SETTLE_TICKS;
	}

	/** The interacted NPC going - a fishing spot moving - is its depletion. */
	public void npcDespawned(NPC npc)
	{
		if (alongside != null && npc != null && npc == alongside.npc
			&& ticksSinceLoading > LOAD_SETTLE_TICKS)
		{
			// The spot it was fishing moved on.
			loseTheWork(client.getTickCount());
		}
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
			boolean first = session.newSpot == null;
			session.newSpot = at;
			if (first)
			{
				// Before the player clicks it, if the spawn came after the
				// despawn; the depletion's own look covers the other order.
				company.lookNext(at);
			}
			log.debug("The spot moved to {}", at); // public:cut logs
		}
	}

	/** The nearest same-name spot other than the target, or null. */
	private WorldPoint nearestSpot()
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		WorldPoint best = null;
		for (NPC npc : sameNameNpcs(view, session.targetTile, NEW_SPOT_TILES))
		{
			WorldPoint at = npc.getWorldLocation();
			// The one on the target's own tile is the target, or what is
			// left of it in the list this tick.
			if (!at.equals(session.targetTile) && (best == null
				|| at.distanceTo(session.targetTile) < best.distanceTo(session.targetTile)))
			{
				best = at;
			}
		}
		return best;
	}

	/**
	 * The NPCs named as the target, other than the target itself, on a tile's
	 * plane and within so many tiles of it.
	 */
	private List<NPC> sameNameNpcs(WorldView view, WorldPoint near, int reach)
	{
		List<NPC> found = new ArrayList<>();
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc == null ? null : npc.getWorldLocation();
			if (at != null && npc != session.targetNpc && session.targetName.equals(npc.getName())
				&& at.getPlane() == near.getPlane() && at.distanceTo(near) <= reach)
			{
				found.add(npc);
			}
		}
		return found;
	}

	/** The far corner of an NPC's footprint, from its south-west tile. */
	private static WorldPoint farCorner(NPC npc, WorldPoint at)
	{
		int size = npc.getComposition() == null ? 1 : Math.max(1, npc.getComposition().getSize());
		return at.dx(size - 1).dy(size - 1);
	}

	/** A scene load began: what despawns around it is shuffling, not change. */
	public void sceneLoading()
	{
		ticksSinceLoading = 0;
	}

	/**
	 * The session, the kept seat, the trap, the fire and the kept tool, all
	 * let go of silently.
	 *
	 * @param announce whether the rules hear the session end
	 * @param notes whether the notes, the ledger and a parked click go too
	 */
	private void dropEverything(boolean announce, boolean notes)
	{
		if (session != null)
		{
			end(true, false, announce);
		}
		dropTheBankTrip(true);
		dropTheTrap(true);
		dropTheFireLine(true);
		dropTheGrace(false);
		if (notes)
		{
			letGoOfTheNotes();
			ledger.abort();
			ledgerAt = 0;
			pending = null;
		}
	}

	/** Logout, hop, shutdown: everything let go of, nothing said. */
	public void reset()
	{
		dropEverything(false, true);
		releaseTicks = 0;
		excused = false;
		alongside = null;
		lastFreeSlots = -1;
		halfArmed = true;
		ticksSinceLoading = 1000;
		previousHere = null;
		layTick = -1000;
		layTile = null;
	}

	// ---------------------------------------------------------------- words

	/**
	 * A game message. The company reads four: the thieving stun, the empty
	 * rune pouch, the slayer task ending and the next one being set. Each is
	 * a small silent reaction with its own cooldown; nothing is said back.
	 */
	public void noteChatMessage(String message, ChatMessageType type)
	{
		if (!config.keepsCompany() || message == null)
		{
			return;
		}
		String plain = message.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		int now = client.getTickCount();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.DIALOG)
		{
			return;
		}
		if (session != null && "thieving".equals(session.activity) && plain.contains(STUNNED_MESSAGE))
		{
			stunned(now);
		}
		else if (plain.contains(NO_RUNES_MESSAGE))
		{
			outOfRunes(now);
		}
		else if (plain.contains(TASK_DONE_MESSAGE)
			|| Arrays.stream(NEW_TASK_MESSAGES).anyMatch(plain::contains))
		{
			ledgerBeat(now, plain.contains(TASK_DONE_MESSAGE));
		}
	}

	/** A graphic on the local player. The thieving stun is the one that matters. */
	public void noteGraphic(int graphicId)
	{
		if (graphicId == STUN_GRAPHIC && session != null && "thieving".equals(session.activity)
			&& config.keepsCompany())
		{
			stunned(client.getTickCount());
		}
	}

	/**
	 * Stunned: a shake of the head, one time in three, and not again for
	 * twenty seconds. The graphic and the message of the one stun arrive
	 * together, and count once.
	 */
	private void stunned(int now)
	{
		if (now - stunSeenTick <= STUN_SAME_TICKS)
		{
			return;
		}
		stunSeenTick = now;
		if (now < stunCooldownUntil || random.nextInt(STUN_ONE_IN) != 0)
		{
			return;
		}
		stunCooldownUntil = now + STUN_COOLDOWN_TICKS;
		company.gesture(KeepingCompany.HEAD_SHAKE);
		log.debug("Stunned; a shake of the head"); // public:cut logs
	}

	/** Out of runes: a look at the player and a think. */
	private void outOfRunes(int now)
	{
		if (now < runesCooldownUntil)
		{
			return;
		}
		runesCooldownUntil = now + RUNES_COOLDOWN_TICKS;
		company.lookAt(playerTile(), CLAP_TICKS);
		company.gesture(KeepingCompany.SCRATCH_HEAD);
	}

	/**
	 * The task ledger. A task done: applause, then the scroll for the entry.
	 * The scroll goes through its own flourish, which holds the feet, so it
	 * is skipped while something else has them. A new task: a shrug -
	 * whatever it is, it is a lot of them.
	 */
	private void ledgerBeat(int now, boolean done)
	{
		if (now < ledgerCooldownUntil)
		{
			return;
		}
		ledgerCooldownUntil = now + LEDGER_COOLDOWN_TICKS;
		company.gesture(done ? CLAP : KeepingCompany.SHRUG);
		if (done && !holdsTheFeet() && !feetTaken.getAsBoolean())
		{
			ledgerAt = now + CLAP_TICKS;
		}
	}

	// public:cut posing
	/**
	 * A scene starts being filmed: the ledger's scroll goes away now, and an
	 * entry still due is not made. Left to run out, its end takes off the
	 * pose the film has put on since and hands the follower back to following.
	 */
	public void putTheLedgerAway()
	{
		ledger.abort();
		ledgerAt = 0;
	}

	// public:end posing
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
			dropEverything(true, true);
			return;
		}

		if (releaseTicks > 0 && --releaseTicks == 0)
		{
			letGoOfTheNotes();
			// Only when the seat is still ours: a thrall or the spectator
			// that took the feet during the bow must not be released by us.
			letGo();
		}

		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			dropEverything(true, false);
			previousHere = null;
			return;
		}

		int now = client.getTickCount();
		if (graceTicks > 0)
		{
			watchTheGrace();
		}
		watchTheGroundUnderfoot(now);
		if (bankTrip)
		{
			watchTheBankTrip(now, here);
		}
		watchTheTrap(now, here);
		watchTheFireLine(now, here);
		watchTheJoin(now);
		ledger.tick();
		if (ledgerAt > 0 && now >= ledgerAt)
		{
			ledgerAt = 0;
			if (!holdsTheFeet() && !feetTaken.getAsBoolean())
			{
				ledger.start(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
					ErrandController.WRITE_SCROLL_POSE, LEDGER_TICKS);
			}
		}
		previousHere = here;

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
		// Stood at the trap or the fire when the work began: that is over.
		dropTheTrap(true);
		dropTheFireLine(true);

		// The abort paths: something else owns the follower, or the world
		// changed under the session. Silent, tool straight back.
		if (elsewhere() || here.getPlane() != session.workSpot.getPlane())
		{
			end(true, false, true);
			return;
		}

		if (local.getAnimation() != -1)
		{
			session.lastAnimation = local.getAnimation();
			session.lastEvidenceTick = now;
			noteTheWorkPose(local.getAnimation(), now);
			if (session.settling)
			{
				// The first swing at the moved target: this is where they
				// work from now, and if it took a walk to get there the seat
				// waits its six ticks from here, as it did at the start.
				session.settling = false;
				if (!here.equals(session.workSpot))
				{
					seatDueTick = now + seatAfter();
				}
				session.workSpot = here;
			}
		}

		// The natural ends: walked off, gone quiet, gone to the bank. A walk
		// off toward a bank, or the bank opening, keeps the seat a while.
		boolean walkedOff = !session.settling
			&& here.distanceTo(session.workSpot) > WALK_OFF_TILES;
		boolean banking = bankOpen.getAsBoolean();
		if (walkedOff || banking || now - session.lastEvidenceTick > QUIET_END_TICKS)
		{
			if (seat != null && (walkedOff || banking)
				&& nearestObject(client, session.workSpot, BANK_TRIP_TILES, BANK_NAMES) != null)
			{
				holdForTheBankTrip(now, here, banking);
			}
			end(false, now - session.startTick >= BEAT_AFTER_TICKS, true);
			return;
		}

		if (excused)
		{
			// Off on a moment of its own: the seat waits, and so do the habits.
			syncTheSeat();
			return;
		}
		if (seat == null || reseat)
		{
			// Not before the player has been at it, and not every tick when
			// the ground keeps refusing.
			if (now >= seatDueTick && !session.settling)
			{
				reseat = false;
				seatDueTick = now + SEAT_RETRY_TICKS;
				if (gathering())
				{
					// Whether it can work alongside is whether it has the tool.
					maybeBorrow();
				}
				takeSeat(here);
				maybeBorrow();
			}
		}
		else if (!follower.isStaying() && !company.isAway())
		{
			// Un-seated by somebody else's release - a held emote ending, a
			// leash snap - and the session is still on: take the seat again.
			// Not while the company is off it on purpose, peering.
			if (follower.stayAt(seat))
			{
				seatTheCompany(now);
			}
			else
			{
				seat = null;
				company.unseated();
			}
		}

		// Working alongside: the player coming to work from its tile moves it
		// over, and its own tree, rock or spot going is a look and a choice.
		Alongside at = work();
		if (at != null && farmingPatch())
		{
			// The rake to rake, the dibber to plant, bare hands to pick.
			borrow.swap(Borrow.farmingTool(session.workPose));
		}
		if (at != null && here.equals(seat) && local.getAnimation() != -1 && !session.settling)
		{
			// Now, not when the retarget that brought them here would have
			// re-seated it: the two stood on one tile for six ticks.
			reseat = true;
			seatDueTick = Math.min(seatDueTick, now);
		}
		else if (at != null && !reseat && !stillLive(at))
		{
			loseTheWork(now);
		}

		// The company, last: the session goes on and the seat is settled.
		if (joinTile != null)
		{
			// Over at the work copying it: a look round, a pace, the notes
			// or a stretch would turn the swing away from it or walk off
			// mid-swing.
			bagEdge = null;
			return;
		}
		if (bagEdge == TriggerEvent.Type.BAG_HALF)
		{
			company.bagHalf(lastOption);
		}
		else if (bagEdge == TriggerEvent.Type.BAG_FULL)
		{
			company.bagFull(lastOption, here);
		}
		bagEdge = null;
		company.tick(now, here, local.getAnimation() != -1, session.activity, session.workPose,
			farmingPatch() && Borrow.farmingTool(session.workPose) == 0);
		syncTheSeat();
	}

	/** The company moved the seat of its own accord; the controller keeps up. */
	private void syncTheSeat()
	{
		WorldPoint moved = company.seatMoved();
		if (moved != null)
		{
			seat = moved;
		}
	}

	private void startPending()
	{
		if (pending == null || elsewhere())
		{
			return;
		}
		int now = client.getTickCount();
		// Where they work is where they stand at the first swing, not where
		// they clicked from: a click from across the clearing, as every one
		// back from the bank is, read as having walked off on the swing.
		WorldPoint at = playerTile();
		if (at == null)
		{
			at = pending.workSpot;
		}
		WorkSession opened = new WorkSession(pending.option, pending.activity,
			pending.targetName, pending.targetId, pending.targetTile, pending.targetMax,
			pending.targetNpc, at, now);
		opened.lastAnimation = pending.lastAnimation;
		pending = null;
		open(opened, now);
		// public:cut errands
		// A prayer at an altar takes no seat: the follower goes and kneels
		// beside the player through the altar errand, with its lines skipped.
		if ("Pray-at".equalsIgnoreCase(session.option) && errands != null)
		{
			errands.accept("altar", true);
		}
		// public:end errands
	}

	/**
	 * Work with nothing clicked - a knife on logs, a pestle on a herb - opens
	 * on its first drop. The target is the tile in front of the player, for
	 * the seat to stand off; nothing in the world can deplete it.
	 */
	private void startStanding(Skill skill, int now)
	{
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null || !config.keepsCompany() || elsewhere())
		{
			return;
		}
		double radians = (local.getCurrentOrientation() / 2048.0) * 2.0 * Math.PI;
		WorldPoint front = here.dx((int) Math.round(-Math.sin(radians)))
			.dy((int) Math.round(-Math.cos(radians)));
		WorkSession opened = new WorkSession("", skill.getName().toLowerCase(Locale.ROOT), "", 0,
			front, front, null, here, now);
		opened.standing = true;
		open(opened, now);
	}

	/** The session begins: whatever else had the feet lets go. */
	private void open(WorkSession opened, int now)
	{
		if (session != null)
		{
			end(false, now - session.startTick >= BEAT_AFTER_TICKS, true);
		}
		// A seat kept for a bank trip, a trap watched, a fire visited: let go,
		// the new work seats itself - except a gatherer's seat that is still a
		// seat for the new work, which is carried over and re-seated where it
		// is, without a step.
		WorldPoint carried = bankTrip ? seat : null;
		dropTheTrap(true);
		dropTheFireLine(true);
		letGoOfTheNotes();
		session = opened;
		boolean workCarried = carried != null && alongside != null && alongside.from.equals(carried)
			&& !carried.equals(opened.workSpot) && stillLive(alongside);
		boolean carry = carried != null && modeFor(opened.workSpot) == KeepingCompany.Mode.GATHERING
			&& carried.equals(follower.getStayTile())
			&& (workCarried || stillASeat(opened.workSpot, opened.targetTile, opened.targetMax,
				reservedTiles(), carried));
		if (carry)
		{
			bankTrip = false;
			bankTripConfirmed = false;
			if (!workCarried)
			{
				alongside = null;
			}
		}
		else
		{
			dropTheBankTrip(true);
		}
		if (graceTicks > 0)
		{
			if (graceActivity != null && opened.activity != null
				&& !graceActivity.equals(opened.activity))
			{
				// Different work: the old tool goes back before the new one.
				dropTheGrace(true);
			}
			else
			{
				// The next tree along: the tool simply stays in the hand.
				graceTicks = 0;
				graceActivity = null;
			}
		}
		seat = carry ? carried : null;
		reseat = carry;
		refusedTool = 0;
		refusedHat = 0;
		seatDueTick = now + seatAfter();
		company = new KeepingCompany(client, follower, config, hands, borrow, random);
		company.begin(now);
		company.aimAt(faceTile(), session.activity, modeFor(session.workSpot), now);
		// public:cut logs
		log.debug("Work session: {} on {} at {}", session.option, session.targetName,
			session.targetTile);
		// public:end logs
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
		dropTheJoin();
		WorkSession done = session;
		session = null;
		reseat = false;
		bagEdge = null;
		// A sitting follower stands first, on a proper end; an abort simply
		// lets the pose go.
		int standUp = company.end(!silent);
		// Walking to the next tree is not the end of the work, so the tool
		// stays in the hand while the player repositions and the send-off
		// waits to see whether they start again. Only an abort - a fight, a
		// plane change, somebody else taking the follower - puts it straight
		// back, because then there is nobody to hand it to.
		// The patch inspector writes its notes at the end, and the scroll and
		// a tool share the one hand, so that end really is the end.
		boolean notes = !silent && "farming".equals(done.activity)
			&& seat != null && !bankTrip && follower.isStaying();
		boolean carry = !silent && !notes && borrow.isBorrowing();
		if (carry)
		{
			graceTicks = TOOL_GRACE_TICKS;
			graceActivity = done.activity;
			beatWait = 0;
		}
		boolean beat = !silent && !carry && withBeat && borrow.isBorrowing();
		if (beat)
		{
			// The bow is to the player, not to the tree.
			WorldPoint here = playerTile();
			if (seat != null && here != null)
			{
				follower.setStayFaceTile(here);
			}
			borrow.giveBack(true, standUp);
		}
		else
		{
			if (!carry)
			{
				borrow.abort();
			}
			if (standUp > 0)
			{
				follower.playAnimation(standUp);
			}
		}
		if (seat != null && !bankTrip)
		{
			seat = null;
			alongside = null;
			// The patch inspector's notes, at the end and only then.
			int note = notes ? company.endNote() : 0;
			endNoteOut = note > 0;
			if (beat || standUp > 0 || note > 0)
			{
				// Movement would cut the bow off; the feet stay a few ticks,
				// and a few more when the stand-up comes first.
				releaseTicks = RELEASE_HOLD_TICKS + note
					+ (standUp > 0 ? KeepingCompany.STAND_UP_TICKS : 0);
			}
			else
			{
				// Only when the seat is still ours: a thrall or the spectator
				// that just took the feet must not be released by us.
				letGo();
			}
		}
		if (announce)
		{
			dispatch.accept(TriggerEvent.workEnd(done.activity, done.targetName));
		}
		// public:cut logs
		log.debug("Work session over: {} on {}{}", done.option, done.targetName,
			silent ? " (aborted)" : "");
		// public:end logs
	}

	/**
	 * The kept tool, between sessions. It goes back when the player turns
	 * out to have finished rather than moved along, and straight back if
	 * anything else claims the follower first.
	 */
	private void watchTheGrace()
	{
		if (elsewhere())
		{
			dropTheGrace(false);
			return;
		}
		if (bankTrip)
		{
			// A trip to the bank is the most obvious not-finished there is:
			// the seat is being kept, and so is what is in the hand.
			return;
		}
		if (--graceTicks > 0)
		{
			return;
		}
		// A send-off that is owed waits for somewhere to land: a player still
		// on the move would cancel the bow and be handed the tool back with
		// nothing to see. It does not wait for ever.
		if (owedTheSendOff() && !follower.isSettled() && beatWait < BEAT_WAIT_TICKS)
		{
			beatWait++;
			graceTicks = 1;
			return;
		}
		dropTheGrace(true);
	}

	/**
	 * Ground that has turned into scenery under a parked follower. Lighting
	 * a line of fires is the way to see it: the follower is given a tile, a
	 * fire goes up on it, and nothing moves it off, because a stay persists
	 * by design and the follower is not the one who lit it. Only the spots
	 * the company chose are second-guessed here; a follower standing
	 * somewhere odd of its own accord is usually there on purpose.
	 */
	private void watchTheGroundUnderfoot(int now)
	{
		if (fireSeat != null && !follower.goodGround(fireSeat))
		{
			dropTheFireLine(true);
		}
		if (trapSeat != null && !follower.goodGround(trapSeat))
		{
			dropTheTrap(true);
		}
		if (seat == null || follower.goodGround(seat))
		{
			return;
		}
		// Chosen again from scratch rather than nudged one tile: whatever went
		// up there - a wall, a fire - may well have taken the neighbours too.
		seat = null;
		reseat = false;
		seatDueTick = now + 1;
		// Off it now, in case there is nowhere better to be given.
		letGo();
		log.debug("The seat is not ground any more; choosing again"); // public:cut logs
	}

	/** Whether a kept tool has earned its bow on the way back. */
	private boolean owedTheSendOff()
	{
		return borrow.isBorrowing()
			&& client.getTickCount() - stintStart >= BEAT_AFTER_TICKS;
	}

	/**
	 * Gives a kept tool back.
	 *
	 * @param earned whether the send-off is allowed: the work really
	 * finished, rather than being interrupted. It still only plays for a
	 * stint long enough to deserve it, and only when the follower is
	 * standing still enough for it to be seen.
	 */
	private void dropTheGrace(boolean earned)
	{
		graceTicks = 0;
		graceActivity = null;
		if (!borrow.isBorrowing())
		{
			return;
		}
		WorldPoint here = playerTile();
		if (earned && owedTheSendOff() && follower.isSettled() && here != null)
		{
			follower.glanceAt(here, BEAT_GLANCE_MS);
			borrow.giveBack(true);
		}
		else
		{
			borrow.abort();
		}
	}

	private void depleted()
	{
		if (session.depleted)
		{
			return;
		}
		session.depleted = true;
		dispatch.accept(TriggerEvent.targetDepleted(session.targetName));
		log.debug("Target depleted: {} at {}", session.targetName, session.targetTile); // public:cut logs

		// Where the next one is: the nearest of the same name that is not
		// this one. A moved spot may not have turned up yet; npcSpawned
		// hands it over when it does.
		WorldPoint next;
		if ("fishing".equals(session.activity))
		{
			next = nearestSpot();
		}
		else
		{
			next = nearestObject(client, session.targetTile, session.targetTile, NEXT_TARGET_TILES,
				session.targetName::equals, session.targetTile, session.targetMax);
		}
		WorldPoint here = playerTile();
		Alongside at = work();
		if (at != null && !at.min.equals(session.targetTile))
		{
			// Working its own, next along: it keeps at it.
			return;
		}
		company.depleted(session.activity, client.getTickCount() - session.targetSinceTick,
			next, here, client.getTickCount());
	}

	/** The notes taken at a session's end, put away once the feet are let go. */
	private void letGoOfTheNotes()
	{
		if (endNoteOut)
		{
			endNoteOut = false;
			company.putScrollAway();
		}
	}

	// ------------------------------------------------------------------ trap

	/**
	 * An object on the player's tile a moment after the lay animation is the
	 * trap. The follower stands two tiles off it, facing it, and stays while
	 * the player goes about their other traps - the first laid, only.
	 */
	private boolean maybeTheTrap(int id, WorldPoint min, WorldPoint max)
	{
		int now = client.getTickCount();
		WorldPoint here = playerTile();
		WorldPoint laidAt = layTile != null ? layTile : here;
		if (now - layTick > LAY_SETTLE_TICKS || here == null || laidAt == null
			|| !covers(min, max, laidAt))
		{
			return false;
		}
		layTick = -1000;
		if (trapSeat != null)
		{
			// Another trap of the run: the run goes on. Only a trap laid where
			// the follower stands moves it, and then once, still by the first.
			trapUntil = now + TRAP_WATCH_TICKS;
			if (covers(min, max, trapSeat))
			{
				// Not reading on the move, and not across the traps: the nearest.
				putTheTrapNotesAway();
				WorldPoint from = follower.getWorldLocation();
				List<WorldPoint> ring = ring(trapTile, trapTile, TRAP_WATCH_TILES, here.getPlane());
				if (from != null)
				{
					ring.sort(nearestTo(from));
				}
				for (WorldPoint candidate : ring)
				{
					if (!candidate.equals(here) && !covers(min, max, candidate)
						&& follower.goodGround(candidate) && follower.arrivesAt(candidate)
						&& follower.stayAt(candidate))
					{
						trapSeat = candidate;
						follower.setStayFaceTile(trapTile);
						log.debug("A trap underfoot; over to {}", candidate); // public:cut logs
						break;
					}
				}
			}
			return true;
		}
		if (bankTrip || fireSeat != null || elsewhere())
		{
			return true;
		}
		WorldPoint from = follower.getWorldLocation();
		List<WorldPoint> ring = ring(min, max, TRAP_WATCH_TILES, here.getPlane());
		ring.removeIf(here::equals);
		if (from != null)
		{
			ring.sort(stepsFrom(from));
		}
		for (WorldPoint candidate : ring)
		{
			if (follower.goodGround(candidate) && follower.stayAt(candidate))
			{
				trapTile = min;
				trapId = id;
				trapSeat = candidate;
				trapUntil = now + TRAP_WATCH_TICKS;
				trapLookTicks = 0;
				trapReading = false;
				trapReadAt = now + jitter(KeepingCompany.WORK_STRETCH_TICKS,
					KeepingCompany.WORK_STRETCH_SPREAD);
				follower.setStayFaceTile(min);
				log.debug("Watching the trap at {} from {}", min, candidate); // public:cut logs
				return true;
			}
		}
		return true;
	}

	/**
	 * Every tick the trap run is watched, from the one seat by its first
	 * trap: the player more than ten tiles off it, two minutes with no trap
	 * laid and no catch, a fight, or the leash snapping (the stay is simply
	 * gone) end it. Play request: park it, not a walk from trap to trap. The
	 * glance at the player when the trap changes runs down here, back to the
	 * trap, and now and then the notes come out.
	 */
	private void watchTheTrap(int now, WorldPoint here)
	{
		if (trapSeat == null)
		{
			return;
		}
		if (!follower.isStaying() || !follower.isSpawned() || feetTaken.getAsBoolean())
		{
			dropTheTrap(false);
			return;
		}
		if (inCombat.getAsBoolean() || here.getPlane() != trapTile.getPlane())
		{
			dropTheTrap(true);
			return;
		}
		if (now >= trapUntil || here.distanceTo(trapTile) > TRAP_RUN_TILES)
		{
			dropTheTrap(true);
			return;
		}
		if (trapLookTicks > 0 && --trapLookTicks == 0)
		{
			follower.setStayFaceTile(trapTile);
		}
		readAtTheTraps(now);
	}

	/** Mostly watching the traps; now and then the notes, where it stands. */
	private void readAtTheTraps(int now)
	{
		if (excused)
		{
			// A moment has the follower: not reading through it.
			putTheTrapNotesAway();
			return;
		}
		if (trapReading)
		{
			if (now >= trapReadUntil)
			{
				putTheTrapNotesAway();
				trapReadAt = now + jitter(KeepingCompany.WORK_STRETCH_TICKS,
					KeepingCompany.WORK_STRETCH_SPREAD);
			}
			return;
		}
		if (now < trapReadAt || trapLookTicks > 0 || hands.isHolding() || !follower.isSettled()
			|| !trapSeat.equals(follower.getWorldLocation()) || follower.getPoseOverride() != 0)
		{
			return;
		}
		hands.hold(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP);
		if (hands.isHolding())
		{
			follower.setPoseOverride(ErrandController.WRITE_SCROLL_POSE);
			trapReading = true;
			trapReadUntil = now + jitter(KeepingCompany.WORK_BREAK_TICKS,
				KeepingCompany.WORK_BREAK_SPREAD);
		}
	}

	private void putTheTrapNotesAway()
	{
		if (!trapReading)
		{
			return;
		}
		trapReading = false;
		follower.clearPoseOverride(ErrandController.WRITE_SCROLL_POSE);
		hands.release();
	}

	private int jitter(int base, int spread)
	{
		return base + random.nextInt(2 * spread + 1) - spread;
	}

	/** @param release whether to hand the feet back, when they are still ours */
	private void dropTheTrap(boolean release)
	{
		if (trapSeat == null)
		{
			return;
		}
		putTheTrapNotesAway();
		trapSeat = null;
		trapTile = null;
		trapLookTicks = 0;
		if (release)
		{
			letGo();
		}
	}

	// ------------------------------------------------------------ joining in

	/** Where the follower went so it could copy the work from arm's reach. */
	private WorldPoint joinTile;

	/** The tick the walk over is given until before the copy is given up. */
	private int joinBy;

	/** How long the walk over to copy the work is given. */
	static final int JOIN_ARRIVAL_PATIENCE = 10;

	/**
	 * The follower wants to copy what the player is doing. Whether it may
	 * start now, and a walk over so that it can when it may not.
	 *
	 * <p>The seat is placed against the PLAYER - two tiles off their flank
	 * and off the line to the work - so a copy made from the seat is an axe
	 * swung at open air two tiles from the trunk. Play report: "sometimes
	 * the follower will chop a tree but not be up against the tree". The
	 * copying is done from arm's reach of the work or not at all, and the
	 * way to get there is the same short trip it already makes to warm its
	 * hands at a fire.
	 *
	 * <p>With no session there is nothing to stand against, so there is no
	 * copy: a swing with no tree in front of it is the very thing this is
	 * here to stop.
	 *
	 * @return true to copy now, false to wait for {@link #joinedIn()}
	 */
	public boolean joinIn()
	{
		// A stump or an emptied rock is not the work any more; and a moment
		// that has excused the seat has the feet, and the facing, for now.
		if (session == null || !mirrorsFromHere() || session.depleted || excused
			|| work() != null)
		{
			// Working alongside already: the copy is its own, not a walk over.
			return false;
		}
		WorldPoint at = follower.getWorldLocation();
		if (joinTile == null && at != null && at.equals(follower.getStayTile())
			&& workableFrom(at))
		{
			// The seat is square on to the work already, as an anvil's is:
			// the copy is made from it, once it has turned to the work. Not
			// straight away - the new facing is only taken up on the next
			// frame - so the copy waits a tick for joinedIn like any other.
			joinTile = at;
			joinBy = client.getTickCount() + JOIN_ARRIVAL_PATIENCE;
			faceTheWork();
			putTheParkedScrollAway();
			return false;
		}
		startTheJoin();
		return false;
	}

	/**
	 * Whether the copy may be made from this tile. Standing work - a knife
	 * on logs - has nothing in the world to stand against, so from the seat
	 * and nowhere else; everything else from a tile the work could be done
	 * from.
	 */
	private boolean workableFrom(WorldPoint at)
	{
		return session.standing ? at.equals(seat) : againstTheWork(at);
	}

	/** Whether the follower is on its way over to copy the work. */
	public boolean joiningIn()
	{
		return joinTile != null;
	}

	/**
	 * Whether it has got there, so the copying may start: stopped dead on
	 * the tile it was sent to, not wherever closest approach left it, and
	 * turned to the work.
	 */
	public boolean joinedIn()
	{
		return stillJoined() && follower.isSettled() && follower.isTurned();
	}

	/**
	 * Whether the follower is still where the copy is made from. Anything
	 * that has moved it since - a moment, a nudge, the session ending - and
	 * the copy is being made from somewhere else.
	 */
	public boolean stillJoined()
	{
		return joinTile != null && session != null
			&& joinTile.equals(follower.getWorldLocation())
			&& joinTile.equals(follower.getStayTile());
	}

	/**
	 * The copy is over, or was never going to happen: back to the seat, which
	 * is still on the books and is retaken on the next tick.
	 */
	public void dropTheJoin()
	{
		if (joinTile == null)
		{
			return;
		}
		boolean ours = joinTile.equals(follower.getStayTile());
		joinTile = null;
		joinBy = 0;
		if (!ours || feetTaken.getAsBoolean() || excused)
		{
			// Somebody else moved it on: their feet now, not ours to let go.
			return;
		}
		// Straight back to the seat. Let go instead, the follower set off
		// after the player for the tick before the seat was retaken.
		if (seat != null && follower.stayAt(seat))
		{
			follower.setStayFaceTile(faceTile());
		}
		else
		{
			follower.resumeFollowing();
		}
	}

	/**
	 * Off to the work. Refused quietly when anything else has the feet: the
	 * copying is a flourish, and a flourish does not interrupt an errand, a
	 * trap, a fire visit or a bank trip.
	 */
	private void startTheJoin()
	{
		if (joinTile != null || seat == null || bankTrip || fireSeat != null
			|| trapSeat != null || elsewhere() || session.targetTile == null || session.standing)
		{
			return;
		}
		WorldPoint player = playerTile();
		WorldPoint from = follower.getWorldLocation();
		// Square on, as the player works it: a corner is not within reach
		// of anything in the game, so a swing from one misses the trunk.
		List<WorldPoint> beside = ring(session.targetTile, session.targetMax, 1,
			session.targetTile.getPlane());
		beside.removeIf(c -> c.equals(player) || !againstTheWork(c));
		if (from != null)
		{
			beside.sort(nearestTo(from));
		}
		for (WorldPoint candidate : beside)
		{
			// Closest approach stops short - water, a wall - and a copy from
			// short of the tile is a copy from the wrong one. Asked before
			// the walk, so a tile turned down is not walked to.
			if (!follower.goodGround(candidate) || !follower.arrivesAt(candidate)
				|| !follower.stayAt(candidate))
			{
				continue;
			}
			joinTile = candidate;
			faceTheWork();
			putTheParkedScrollAway();
			int walk = from == null ? JOIN_ARRIVAL_PATIENCE : from.distanceTo(candidate);
			joinBy = client.getTickCount() + walk + JOIN_ARRIVAL_PATIENCE;
			log.debug("Going over to join in at {} by {}", candidate, session.targetName); // public:cut logs
			return;
		}
	}

	/**
	 * A gatherer's parked scroll down and the tool back in the hand: the copy
	 * swings the tool. Only a gatherer's - the anvil's notes are the seat's
	 * for the whole session, and put away they did not come back.
	 */
	private void putTheParkedScrollAway()
	{
		if (modeFor(session.workSpot) == KeepingCompany.Mode.GATHERING)
		{
			company.putScrollAway();
		}
	}

	/** Faces the middle of the work's footprint, exactly, as the player does. */
	private void faceTheWork()
	{
		follower.setStayFaceArea(session.targetTile, session.targetMax);
	}

	/** Gave up on the walk over, or something took the feet while it walked. */
	private void watchTheJoin(int now)
	{
		if (joinTile == null)
		{
			return;
		}
		if (joinedIn())
		{
			// Got there: the walk's patience is spent, and the copy runs on.
			joinBy = 0;
		}
		if (session == null || elsewhere() || excused || session.depleted
			|| !joinTile.equals(follower.getStayTile())
			|| !follower.goodGround(joinTile) || !workableFrom(joinTile)
			|| (joinBy > 0 && now > joinBy && !joinedIn()))
		{
			dropTheJoin();
		}
	}

	/**
	 * Whether the work could be done from this tile: square on to the
	 * footprint, with no fence along the edge between. A range worked from
	 * one side only (see {@link #ONE_SIDED}) is worked from the player's
	 * side, the side that is open, as a second cook would stand.
	 */
	private boolean againstTheWork(WorldPoint at)
	{
		if (at == null || session == null || session.targetTile == null)
		{
			return false;
		}
		WorldPoint min = session.targetTile;
		WorldPoint max = session.targetMax;
		if (at.getPlane() != min.getPlane() || !squareOn(min, max, at) || fenced(min, max, at))
		{
			return false;
		}
		boolean xIn = at.getX() >= min.getX() && at.getX() <= max.getX();
		WorldPoint player = session.workSpot;
		return !ONE_SIDED.contains(session.targetId)
			|| (at.getX() == player.getX() && !xIn) || (at.getY() == player.getY() && xIn);
	}

	/**
	 * Whether a tile touches one edge of a footprint square on: beside it,
	 * not on it and not off a corner. The game works everything from these
	 * tiles and no others - a tree, a rock, an anvil, a range.
	 */
	static boolean squareOn(WorldPoint min, WorldPoint max, WorldPoint at)
	{
		boolean xIn = at.getX() >= min.getX() && at.getX() <= max.getX();
		boolean yIn = at.getY() >= min.getY() && at.getY() <= max.getY();
		boolean xNext = at.getX() == min.getX() - 1 || at.getX() == max.getX() + 1;
		boolean yNext = at.getY() == min.getY() - 1 || at.getY() == max.getY() + 1;
		return (xIn && yNext) || (yIn && xNext);
	}

	// -------------------------------------------------------- work alongside

	/**
	 * What the follower works, alongside the player: the player's own tree,
	 * rock or spot when there is room at it, else the next one along of the
	 * same kind. Play request: at Motherlode, where the player's vein leaves
	 * no room, "look like they are harvesting some other ore vein". A vein
	 * is a wall, worked from the one tile across it.
	 */
	static final class Alongside
	{
		final int id;
		/** The fishing spot, or null for scenery. */
		final NPC npc;
		final boolean wall;
		final WorldPoint min;
		final WorldPoint max;
		/** The tile it works from, which is its seat. */
		final WorldPoint from;
		/** A patch: the same patch while it stands, whatever its crop is called now. */
		final boolean anyState;

		Alongside(int id, NPC npc, boolean wall, WorldPoint min, WorldPoint max, WorldPoint from,
			boolean anyState)
		{
			this.id = id;
			this.npc = npc;
			this.wall = wall;
			this.min = min;
			this.max = max;
			this.from = from;
			this.anyState = anyState;
		}
	}

	private Alongside alongside;

	/** The id last seen and found not to loop, so it is not asked about every tick. */
	private int notAWorkPose;

	/** Its work, while it is seated at it; null when the seat is only a seat. */
	private Alongside work()
	{
		return alongside != null && seat != null && alongside.from.equals(seat) ? alongside : null;
	}

	private boolean gathering()
	{
		return session != null && ("mining".equals(session.activity)
			|| "woodcutting".equals(session.activity) || "fishing".equals(session.activity));
	}

	/**
	 * Whether it works alongside rather than sits: gathering, with the work
	 * seen and the tool in hand. A target called just "Rocks" is an emptied
	 * rock, which keeps its "Mine" in the cache; nothing to work there.
	 */
	private boolean worksAlongside()
	{
		if (session == null || session.standing || session.workPose <= 0)
		{
			return false;
		}
		if (farmingPatch())
		{
			// Bare hands or the tool the work wants; borrowed as it goes.
			return config.borrowsTools();
		}
		return gathering() && !"Rocks".equals(session.targetName) && borrow.isHolding();
	}

	/**
	 * Whether it is working the patch with bare hands just now - picking - so
	 * its own weapon and shield are not left on through the arms.
	 */
	public boolean worksBareHanded()
	{
		return work() != null && farmingPatch() && Borrow.farmingTool(session.workPose) == 0;
	}

	/** A visit to a patch, which is worked alongside at the patch itself. */
	private boolean farmingPatch()
	{
		return session != null && "farming".equals(session.activity)
			&& modeFor(session.workSpot) == KeepingCompany.Mode.PATCH;
	}

	/**
	 * The work is the player's LOOPING animation - a swing of the axe, a cast -
	 * not whatever they played last, which can be a bite to eat. The first
	 * one seen while only seated moves it to the work.
	 */
	private void noteTheWorkPose(int animationId, int now)
	{
		boolean farming = "farming".equals(session.activity);
		if (!gathering() && !farming || animationId == session.workPose
			|| animationId == notAWorkPose)
		{
			return;
		}
		if (farming ? !Borrow.farmingWork(animationId) : !loops(animationId))
		{
			notAWorkPose = animationId;
			return;
		}
		boolean first = session.workPose == 0;
		session.workPose = animationId;
		if (first && seat != null && work() == null)
		{
			reseat = true;
			seatDueTick = now;
		}
	}

	/** Whether an animation is an authored loop: the work, not a one-off. */
	private boolean loops(int animationId)
	{
		Animation animation = client.loadAnimation(animationId);
		return animation != null && animation.getFrameStep() >= 0;
	}

	/** The company seated the work's way when it has work, the seat's way when not. */
	private void seatTheCompany(int now)
	{
		Alongside at = work();
		if (at != null)
		{
			follower.setStayFaceArea(at.min, at.max);
			company.seatedAtWork(seat, faceTile(), at.min, at.max, now);
		}
		else
		{
			follower.setStayFaceTile(faceTile());
			company.seated(seat, faceTile());
		}
	}

	/** Its work went: the swing stops, a look, and a choice again a moment later. */
	private void loseTheWork(int now)
	{
		alongside = null;
		company.workGone(now);
		reseat = true;
		seatDueTick = now + WORK_GONE_TICKS;
	}

	/**
	 * Somewhere to work from: the player's own target first, then the next
	 * one along, near the player and best two tiles off them, a tile it is
	 * already on before any it would walk to. Only a few are asked of the
	 * pathing, and only one it arrives on without a long way round.
	 */
	private Alongside chooseAlongside(WorldPoint player)
	{
		WorldView view = client.getTopLevelWorldView();
		WorldPoint at = follower.getWorldLocation();
		if (view == null || at == null)
		{
			return null;
		}
		Comparator<Alongside> byPlace = Comparator
			.comparingInt((Alongside a) -> alongsideRank(a.from, player))
			.thenComparingInt(a -> square(a.from, at));
		List<Alongside> ranked = ownWork();
		ranked.sort(byPlace);
		List<Alongside> others = farmingPatch() ? new ArrayList<>() : neighbours(view, player);
		others.sort(byPlace);
		ranked.addAll(others);
		Set<WorldPoint> taken = othersTiles(view);
		ranked.removeIf(a -> a.from.equals(player) || taken.contains(a.from)
			|| a.from.getPlane() != player.getPlane() || a.from.distanceTo(player) > ALONGSIDE_TILES);
		// A stable sort: one it already stands on first, the order otherwise kept.
		ranked.sort(Comparator.comparingInt(a -> a.from.equals(at) ? 0 : 1));
		Set<WorldPoint> asked = new HashSet<>();
		for (Alongside a : ranked)
		{
			if (!asked.add(a.from))
			{
				continue;
			}
			if (asked.size() > ALONGSIDE_PATH_ASKS)
			{
				break;
			}
			int steps = follower.stepsTo(a.from);
			if (steps >= 0 && steps <= at.distanceTo(a.from) + ALONGSIDE_DETOUR_STEPS
				&& follower.stayAt(a.from))
			{
				return a;
			}
		}
		return null;
	}

	/** Best two tiles off the player, then further; beside them last of all. */
	static int alongsideRank(WorldPoint tile, WorldPoint player)
	{
		int distance = tile.distanceTo(player);
		return distance >= 2 ? distance : ALONGSIDE_TILES + 1;
	}

	/**
	 * The work tiles of the player's own target, while it stands. Never a
	 * vein's: its one work tile, across its wall, is the player's own.
	 */
	private List<Alongside> ownWork()
	{
		List<Alongside> found = new ArrayList<>();
		if (session.depleted || session.targetTile == null)
		{
			return found;
		}
		WorldPoint min = session.targetTile;
		WorldPoint max = session.targetMax;
		boolean patch = farmingPatch();
		if (!stillLive(new Alongside(session.targetId, session.targetNpc, false, min, max, min,
			patch)))
		{
			// Felled, emptied or moved on before any event said so - or a
			// wall, which this is not asked about.
			return found;
		}
		for (WorldPoint c : ring(min, max, 1, min.getPlane()))
		{
			if (follower.goodGround(c) && againstTheWork(c))
			{
				found.add(new Alongside(session.targetId, session.targetNpc, false, min, max, c,
					patch));
			}
		}
		return found;
	}

	/**
	 * The live ones of the same kind near the player, and the tiles each is
	 * worked from: trees and rocks by their footprint, veins across their
	 * wall, spots by the NPC. Not the player's own target.
	 */
	private List<Alongside> neighbours(WorldView view, WorldPoint player)
	{
		List<Alongside> found = new ArrayList<>();
		int reach = ALONGSIDE_TILES + 1;
		if (session.targetNpc != null)
		{
			for (NPC npc : sameNameNpcs(view, player, reach))
			{
				WorldPoint at = npc.getWorldLocation();
				if (!liveNpc(npc))
				{
					continue;
				}
				WorldPoint far = farCorner(npc, at);
				for (WorldPoint c : workTilesOf(at, far, npc.getId()))
				{
					found.add(new Alongside(npc.getId(), npc, false, at, far, c, false));
				}
			}
			return found;
		}
		int plane = player.getPlane();
		WorldPoint min = session.targetTile;
		WorldPoint max = session.targetMax;
		for (int x = player.getX() - reach; x <= player.getX() + reach; x++)
		{
			for (int y = player.getY() - reach; y <= player.getY() + reach; y++)
			{
				Tile tile = sceneTile(view, x, y, plane);
				if (tile == null)
				{
					continue;
				}
				WorldPoint here = new WorldPoint(x, y, plane);
				WallObject wall = tile.getWallObject();
				if (wall != null && !covers(min, max, here) && liveKind(resolved(wall.getId())))
				{
					WorldPoint from = acrossTheWall(here, wall.getOrientationA());
					if (from != null && follower.goodGround(from))
					{
						found.add(new Alongside(wall.getId(), null, true, here, here, from, false));
					}
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object == null)
					{
						continue;
					}
					WorldPoint[] footprint = footprintOf(view, object);
					// Once per object, on its south-west tile; never the player's own.
					if (footprint[0] == null || !footprint[0].equals(here)
						|| covers(footprint[0], footprint[1], min)
						|| !liveKind(resolved(object.getId())))
					{
						continue;
					}
					for (WorldPoint c : workTilesOf(footprint[0], footprint[1], object.getId()))
					{
						found.add(new Alongside(object.getId(), null, false, footprint[0],
							footprint[1], c, false));
					}
				}
			}
		}
		return found;
	}

	/** Square on to a footprint, ground to stand on, no fence between. */
	private List<WorldPoint> workTilesOf(WorldPoint min, WorldPoint max, int id)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		if (ONE_SIDED.contains(id))
		{
			// Worked from one side the client cannot tell: not a neighbour's.
			return tiles;
		}
		for (WorldPoint c : ring(min, max, 1, min.getPlane()))
		{
			if (squareOn(min, max, c) && follower.goodGround(c) && !fenced(min, max, c))
			{
				tiles.add(c);
			}
		}
		return tiles;
	}

	/** Whether a fence runs along the edge between a square-on tile and the footprint. */
	private boolean fenced(WorldPoint min, WorldPoint max, WorldPoint c)
	{
		boolean xIn = c.getX() >= min.getX() && c.getX() <= max.getX();
		int dx = xIn ? 0 : c.getX() < min.getX() ? 1 : -1;
		int dy = !xIn ? 0 : c.getY() < min.getY() ? 1 : -1;
		return follower.walledToward(c, dx, dy);
	}

	/**
	 * The tile a wall is worked from: across the edge it stands on. The wall's
	 * own edge flag is the vein itself, so it is not a fence here.
	 *
	 * @param orientationA 1 west, 2 north, 4 east, 8 south; anything else, none
	 */
	static WorldPoint acrossTheWall(WorldPoint wallTile, int orientationA)
	{
		switch (orientationA)
		{
			case 1:
				return wallTile.dx(-1);
			case 2:
				return wallTile.dy(1);
			case 4:
				return wallTile.dx(1);
			case 8:
				return wallTile.dy(-1);
			default:
				return null;
		}
	}

	/**
	 * The same kind as the player's work, and still to be worked: the same
	 * name AND the clicked option on offer. The name alone lets in a tree
	 * that is only scenery; the option alone lets in an emptied rock.
	 */
	private boolean liveKind(ObjectComposition composition)
	{
		return composition != null && liveKind(composition.getName(), composition.getActions(),
			session.targetName, session.option);
	}

	static boolean liveKind(String name, String[] actions, String targetName, String option)
	{
		return name != null && !"Rocks".equals(targetName) && name.equals(targetName)
			&& offers(actions, option);
	}

	private boolean liveNpc(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition() != null
			? npc.getTransformedComposition() : npc.getComposition();
		return composition != null && session.targetName.equals(npc.getName())
			&& offers(composition.getActions(), session.option);
	}

	static boolean offers(String[] actions, String option)
	{
		if (actions == null || option == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if (action != null && plainOption(action).equalsIgnoreCase(option))
			{
				return true;
			}
		}
		return false;
	}

	/** A definition as it stands now: a multiloc's current state, not its base. */
	private ObjectComposition resolved(int id)
	{
		ObjectComposition composition = client.getObjectDefinition(id);
		if (composition != null && composition.getImpostorIds() != null)
		{
			try
			{
				return composition.getImpostor();
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}
		return composition;
	}

	/** Whether what it works is still there, and still to be worked. */
	private boolean stillLive(Alongside a)
	{
		if (a.npc != null)
		{
			return a.min.equals(a.npc.getWorldLocation()) && liveNpc(a.npc);
		}
		Tile tile = sceneTile(client.getTopLevelWorldView(), a.min.getX(), a.min.getY(),
			a.min.getPlane());
		if (tile == null)
		{
			return false;
		}
		if (a.wall)
		{
			WallObject wall = tile.getWallObject();
			return wall != null && wall.getId() == a.id && liveKind(resolved(a.id));
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && object.getId() == a.id)
			{
				return a.anyState || liveKind(resolved(a.id));
			}
		}
		return false;
	}

	/** The scene's tile at a world tile, or null off the loaded scene. */
	private static Tile sceneTile(WorldView view, int x, int y, int plane)
	{
		Tile[][][] tiles = view == null || view.getScene() == null ? null : view.getScene().getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		int sx = x - view.getBaseX();
		int sy = y - view.getBaseY();
		return sx < 0 || sy < 0 || sx >= tiles[plane].length || sy >= tiles[plane][sx].length
			? null : tiles[plane][sx][sy];
	}

	/** Where the other players stand: their tiles are theirs. */
	private Set<WorldPoint> othersTiles(WorldView view)
	{
		Set<WorldPoint> tiles = new HashSet<>();
		Player local = client.getLocalPlayer();
		for (Player other : view.players())
		{
			if (other != null && other != local && other.getWorldLocation() != null)
			{
				tiles.add(other.getWorldLocation());
			}
		}
		return tiles;
	}

	// ------------------------------------------------------------- fire line

	/**
	 * A fire lit within three tiles of where the player just stood is one
	 * of theirs. Every fourth, the follower walks over, warms its hands and
	 * catches up on foot - and one time in fifty, the errand's too-close gag
	 * instead. A brazier is not one of these, and neither is a campfire:
	 * this is the player's own line of fires, and that is what it is for.
	 * Where the follower may STAND is a separate question, and a campfire
	 * counts there - see {@link HotGround}.
	 */
	private void maybeTheFire(int id, WorldPoint min, WorldPoint max)
	{
		if (previousHere == null || min.getPlane() != previousHere.getPlane()
			|| min.distanceTo(previousHere) > FIRE_LINE_TILES
			|| !"Fire".equals(ErrandController.objectName(client, id)))
		{
			return;
		}
		int now = client.getTickCount();
		if (now - lastFireTick > FIRE_LINE_LAPSE_TICKS)
		{
			fires = 0;
		}
		lastFireTick = now;
		fires++;
		if (fires % FIRE_LINE_EVERY != 0 || fireSeat != null || trapSeat != null || bankTrip
			|| elsewhere())
		{
			return;
		}
		// public:cut errands
		if (errands != null && random.nextInt(FIRE_GAG_ONE_IN) == 0)
		{
			errands.accept("firedeath", false);
			return;
		}
		// public:end errands
		WorldPoint from = follower.getWorldLocation();
		List<WorldPoint> beside = ring(min, max, 1, min.getPlane());
		beside.removeIf(c -> c.getX() != min.getX() && c.getY() != min.getY());
		if (from != null)
		{
			beside.sort(stepsFrom(from));
		}
		for (WorldPoint candidate : beside)
		{
			// A line of fires is lit along a row, so the tile beside the new
			// one is very often the last one - and the nearest, which is how
			// the follower came to warm its hands from inside a fire.
			if (!follower.hot(candidate) && follower.stayAt(candidate))
			{
				fireSeat = candidate;
				follower.setStayFaceTile(min);
				int walk = from == null ? FIRE_LINE_TILES : from.distanceTo(candidate);
				fireWarmAt = now + walk + 2;
				fireLeaveAt = 0;
				log.debug("The fourth fire, at {}; going over", min); // public:cut logs
				return;
			}
		}
	}

	/** Once there, the hands to the flames; three seconds later, back on foot. */
	private void watchTheFireLine(int now, WorldPoint here)
	{
		if (fireSeat == null)
		{
			return;
		}
		if (!follower.isStaying() || !follower.isSpawned() || feetTaken.getAsBoolean())
		{
			dropTheFireLine(false);
			return;
		}
		if (inCombat.getAsBoolean() || here.getPlane() != fireSeat.getPlane())
		{
			dropTheFireLine(true);
			return;
		}
		if (fireWarmAt > 0 && now >= fireWarmAt)
		{
			// Arrived, or near enough. The walk was timed by counting tiles,
			// which is a guess: a corner to round or a tile refused and the
			// hands went to the flames from halfway there. Waits for the
			// follower to actually stop, and gives up if it never does.
			if (!follower.isSettled() && now < fireWarmAt + FIRE_ARRIVAL_PATIENCE)
			{
				return;
			}
			fireWarmAt = 0;
			if (!follower.isSettled())
			{
				dropTheFireLine(true);
				return;
			}
			fireLeaveAt = now + FIRE_WARM_TICKS;
			follower.playAnimation(KeepingCompany.WARM_HANDS);
		}
		else if (fireLeaveAt > 0 && now >= fireLeaveAt)
		{
			dropTheFireLine(true);
		}
	}

	/** @param release whether to hand the feet back, when they are still ours */
	private void dropTheFireLine(boolean release)
	{
		if (fireSeat == null)
		{
			return;
		}
		fireSeat = null;
		fireWarmAt = 0;
		fireLeaveAt = 0;
		if (release)
		{
			letGo();
		}
	}

	/** The tiles exactly so far from a footprint, nearest corner first. */
	static List<WorldPoint> ring(WorldPoint min, WorldPoint max, int radius, int plane)
	{
		WorldPoint far = max == null ? min : max;
		List<WorldPoint> ring = new ArrayList<>();
		for (int x = min.getX() - radius; x <= far.getX() + radius; x++)
		{
			for (int y = min.getY() - radius; y <= far.getY() + radius; y++)
			{
				int cx = Math.max(min.getX(), Math.min(far.getX(), x));
				int cy = Math.max(min.getY(), Math.min(far.getY(), y));
				if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) == radius)
				{
					ring.add(new WorldPoint(x, y, plane));
				}
			}
		}
		return ring;
	}

	// ------------------------------------------------------------- bank trip

	/**
	 * The player has left the work with a bank near: the seat is kept, facing
	 * the way they went, for the walk there and - once the bank opens - for
	 * the trip, under a cap. No stay lines: the seat is still the company's.
	 *
	 * @param banking whether the bank is already open, which confirms it
	 */
	private void holdForTheBankTrip(int now, WorldPoint here, boolean banking)
	{
		bankTrip = true;
		bankTripConfirmed = banking;
		bankTripAway = false;
		bankTripGathering = modeFor(here) == KeepingCompany.Mode.GATHERING;
		bankTripUntil = now + (banking ? BANK_TRIP_CAP_TICKS : BANK_WALK_TICKS);
		follower.setStayFaceTile(here);
		log.debug("A bank trip; the seat at {} is kept", seat); // public:cut logs
	}

	/**
	 * Every tick the seat is kept without a session: the bank opening confirms
	 * the trip and starts the cap; the player back within three tiles, the
	 * cap, the walk lapsing unconfirmed, a fight, or the leash snapping (the
	 * stay is simply gone) all let it go.
	 */
	private void watchTheBankTrip(int now, WorldPoint here)
	{
		if (!follower.isStaying() || !follower.isSpawned() || feetTaken.getAsBoolean()
			|| seat == null)
		{
			dropTheBankTrip(false);
			return;
		}
		if (inCombat.getAsBoolean())
		{
			dropTheBankTrip(true);
			return;
		}
		if (!bankTripConfirmed && bankOpen.getAsBoolean())
		{
			bankTripConfirmed = true;
			bankTripUntil = now + BANK_TRIP_CAP_TICKS;
			log.debug("The bank opened; the seat is kept for the trip"); // public:cut logs
			return;
		}
		boolean near = here.getPlane() == seat.getPlane()
			&& here.distanceTo(seat) <= WALK_OFF_TILES;
		if (!near)
		{
			// A bank chest by the work: the player may never leave the
			// seat's side, and then there is no coming back to end it on.
			bankTripAway = true;
		}
		if (near && bankTripAway && bankTripGathering)
		{
			// Back from the bank to the same rock or tree: kept a moment for
			// the first swing, which carries the seat into the new session
			// (see open), rather than walked off to meet the player and
			// straight back to it six ticks later.
			bankTripUntil = Math.min(bankTripUntil, now + BANK_BACK_TICKS);
		}
		else if (near && bankTripAway)
		{
			dropTheBankTrip(true);
			return;
		}
		if (now >= bankTripUntil)
		{
			dropTheBankTrip(true);
		}
	}

	/** @param release whether to hand the feet back, when they are still ours */
	private void dropTheBankTrip(boolean release)
	{
		if (!bankTrip)
		{
			return;
		}
		bankTrip = false;
		bankTripConfirmed = false;
		seat = null;
		alongside = null;
		if (release)
		{
			letGo();
		}
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
		KeepingCompany.Mode mode = modeFor(player);
		if ("prayer".equals(session.activity))
		{
			// The kneel at the altar is the altar errand's; no seat.
			return;
		}
		if ("thieving".equals(session.activity) && mode != KeepingCompany.Mode.STALL)
		{
			// The thieving session already keeps the follower off at a
			// distance, and a seat at the mark's elbow is the opposite of
			// what that was tuned for. Only a stall earns the watch.
			return;
		}
		if (worksAlongside())
		{
			// Keep at what it has while it is still there and near.
			Alongside kept = work();
			if (kept != null && seat.equals(follower.getStayTile()) && follower.goodGround(seat)
				&& !seat.equals(player) && seat.distanceTo(player) <= ALONGSIDE_TILES
				&& stillLive(kept) && (!kept.anyState || kept.min.equals(session.targetTile)))
			{
				if (!company.isAtWork())
				{
					seatTheCompany(client.getTickCount());
				}
				return;
			}
			Alongside chosen = chooseAlongside(player);
			if (chosen != null)
			{
				alongside = chosen;
				seat = chosen.from;
				company.watch(null);
				seatTheCompany(client.getTickCount());
				log.debug("Working alongside at {}, from {}", chosen.min, seat); // public:cut logs
				return;
			}
		}
		alongside = null;
		List<WorldPoint> candidates;
		// The nearest guard by name within the watch's reach.
		NPC guard = mode == KeepingCompany.Mode.STALL
			? nearestNpc(client, player, GUARD_TILES, Arrays.asList(GUARD_NAMES)::contains) : null;
		WorldPoint post = guard == null ? null : guard.getWorldLocation();
		WorldPoint face = faceTile();
		if (post != null)
		{
			candidates = stallCandidates(player, post, session.targetTile, session.targetMax);
			face = post;
		}
		else
		{
			// A stall with no guard worth watching still takes a seat: with
			// none taken the thieving drift owns the feet, and that was
			// written to keep moving rather than to settle anywhere.
			candidates = seatCandidates(player, session.targetTile, session.targetMax,
				reservedTiles(), variantFor(mode, session.activity));
		}
		// The seat already held stays while it is still a seat for the next
		// rock or tree: the next one clicked from the same spot, or a step
		// along the wall to the next vein, is no reason to get up. Only for
		// the same kind of work - a seat taken before the drop named it is
		// the wrong shape for the work it turned out to be.
		WorldPoint held = seat;
		if (mode == KeepingCompany.Mode.GATHERING && held != null
			&& held.equals(follower.getStayTile()) && follower.goodGround(held)
			&& stillASeat(player, session.targetTile, session.targetMax, reservedTiles(), held))
		{
			follower.setStayFaceTile(face);
			company.seated(seat, faceTile());
			log.debug("Kept the seat at {} for {}", seat, session.targetName); // public:cut logs
			return;
		}
		for (WorldPoint candidate : candidates)
		{
			// The ground is asked BEFORE the walk, and so is whether the walk
			// arrives: the seat is written down here, and a seat recorded on
			// a tile the follower will not stand on is one it gets dropped
			// from and re-takes, tick after tick. Closest approach calls
			// stopping beside a rock, or at the water's edge, arriving - and
			// that was the follower walking between two tiles at the player's
			// side for as long as they mined. Asked without setting off, so a
			// tile turned down is not walked to either.
			if (!follower.goodGround(candidate) || !follower.arrivesAt(candidate)
				|| !follower.stayAt(candidate))
			{
				continue;
			}
			seat = candidate;
			follower.setStayFaceTile(face);
			company.watch(guard);
			company.seated(seat, faceTile());
			log.debug("Seated at {} facing {}", seat, face); // public:cut logs
			return;
		}
		if (held != null && held.equals(follower.getStayTile()) && !feetTaken.getAsBoolean())
		{
			// The seat it had is no seat for this: off it, rather than left
			// standing there with nothing to say whose the stay is.
			follower.resumeFollowing();
		}
		seat = null;
		company.unseated();
		log.debug("No seat by {} for {}; following instead", player, session.targetName); // public:cut logs
	}

	/** A gathering seat kept for the next target is no further than this from the player. */
	static final int SEAT_KEEP_TILES = 3;

	/**
	 * Whether a seat already held is still a fair one for new work: the
	 * rules a fresh seat is chosen by - off the footprint, off the player's
	 * tile and the straight line to the work, off a same-name neighbour -
	 * and near the player, no more than three tiles off. A tile beside them
	 * will do: the player stepping along the wall to the next vein is no
	 * reason to get up.
	 */
	static boolean stillASeat(WorldPoint player, WorldPoint min, WorldPoint max,
		Collection<WorldPoint> reserved, WorldPoint seat)
	{
		WorldPoint far = max == null ? min : max;
		int nx = Math.max(min.getX(), Math.min(far.getX(), player.getX()));
		int ny = Math.max(min.getY(), Math.min(far.getY(), player.getY()));
		int distance = seat.distanceTo(player);
		return seat.getPlane() == player.getPlane() && distance >= 1
			&& distance <= SEAT_KEEP_TILES && !covers(min, far, seat) && !reserved.contains(seat)
			&& !onTheLine(player, nx, ny, seat);
	}

	/**
	 * The shape of the seat the session's work wants; see the modes. A
	 * cook at a fire and a cook at a range sit differently, and so on.
	 */
	KeepingCompany.Mode modeFor(WorldPoint here)
	{
		if (session == null)
		{
			return KeepingCompany.Mode.NONE;
		}
		String name = session.targetName.toLowerCase(Locale.ROOT);
		switch (session.activity)
		{
			case "mining":
			case "woodcutting":
			case "fishing":
			case "hunter":
				return KeepingCompany.Mode.GATHERING;
			case "cooking":
				// Any of the fires, not just the one named "Fire": a Forestry
				// campfire is cooked at exactly the same way, and sitting it on
				// a range's flank instead was simply wrong.
				return HotGround.isFire(name) ? KeepingCompany.Mode.FIRESIDE
					: KeepingCompany.Mode.RANGE;
			case "smithing":
				return "anvil".equals(name) ? KeepingCompany.Mode.ANVIL : KeepingCompany.Mode.NONE;
			case "crafting":
				if (session.standing)
				{
					return KeepingCompany.Mode.STANDING;
				}
				return name.contains("wheel") || name.contains("loom")
					? KeepingCompany.Mode.WHEEL : KeepingCompany.Mode.NONE;
			case "fletching":
			case "herblore":
				return session.standing ? KeepingCompany.Mode.STANDING : KeepingCompany.Mode.NONE;
			case "runecraft":
				return KeepingCompany.Mode.ALTAR;
			case "thieving":
				return "Steal-from".equalsIgnoreCase(session.option)
					? KeepingCompany.Mode.STALL : KeepingCompany.Mode.NONE;
			case "farming":
				// A grown patch is named for its crop - "Ranarr weed", "Potato"
				// - so the patch is known by being a multiloc, which a crop in
				// a field is not.
				return name.contains("patch") || multiloc() ? KeepingCompany.Mode.PATCH
					: KeepingCompany.Mode.NONE;
			case "construction":
			{
				WorldView view = client.getTopLevelWorldView();
				return view != null && view.isInstance()
					? KeepingCompany.Mode.FOREMAN : KeepingCompany.Mode.NONE;
			}
			case "firemaking":
				return here != null && COLD_REGIONS.contains(here.getRegionID())
					? KeepingCompany.Mode.BRAZIER : KeepingCompany.Mode.NONE;
			default:
				return KeepingCompany.Mode.NONE;
		}
	}

	/** Whether the target is scenery that changes as it grows: a patch. */
	private boolean multiloc()
	{
		if (session.targetNpc != null)
		{
			return false;
		}
		ObjectComposition composition = client.getObjectDefinition(session.targetId);
		return composition != null && composition.getImpostorIds() != null;
	}

	/** The geometry {@link #seatCandidates} is asked for, by mode. */
	static String variantFor(KeepingCompany.Mode mode, String activity)
	{
		switch (mode)
		{
			case FIRESIDE:
			case ANVIL:
			case PATCH:
				return "across";
			case RANGE:
			case WHEEL:
				return "beside";
			case FOREMAN:
			case BRAZIER:
				return "ring";
			default:
				return activity;
		}
	}

	/**
	 * The stall watch: five tiles off the player toward the guard - four or
	 * six when five is refused, and one to either side of the five - never
	 * on the stall, never past the guard.
	 */
	static List<WorldPoint> stallCandidates(WorldPoint player, WorldPoint guard,
		WorldPoint min, WorldPoint max)
	{
		List<WorldPoint> candidates = new ArrayList<>();
		int gx = guard.getX() - player.getX();
		int gy = guard.getY() - player.getY();
		int length = Math.max(Math.abs(gx), Math.abs(gy));
		if (length < 2)
		{
			return candidates;
		}
		int px = -Integer.signum(gy);
		int py = Integer.signum(gx);
		for (int reach : new int[]{STALL_WATCH_TILES, STALL_WATCH_TILES - 1, STALL_WATCH_TILES + 1})
		{
			int along = Math.min(reach, length - 1);
			int x = player.getX() + (int) Math.round(along * (double) gx / length);
			int y = player.getY() + (int) Math.round(along * (double) gy / length);
			int[][] offsets = reach == STALL_WATCH_TILES
				? new int[][]{{0, 0}, {px, py}, {-px, -py}} : new int[][]{{0, 0}};
			for (int[] offset : offsets)
			{
				WorldPoint c = new WorldPoint(x + offset[0], y + offset[1], player.getPlane());
				if (!candidates.contains(c) && !covers(min, max, c) && !c.equals(player)
					&& !c.equals(guard) && c.distanceTo(player) >= 2
					&& c.distanceTo(player) < length)
				{
					candidates.add(c);
				}
			}
		}
		return candidates;
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
		return seatCandidates(player, min, max, reserved, "");
	}

	/**
	 * The same, shaped to the work: a miner's company sits diagonal-behind,
	 * two back, where the swing is in view and the rock is not; an angler's
	 * on the bank beside the spot, the nearest bank tile that is not the
	 * player's, so it looks at the water rather than at a back. Three more
	 * shapes by name: "across" (the far side of a fire, an anvil, a patch),
	 * "beside" (one tile along the side of a range or a wheel) and "ring"
	 * (three tiles off a hotspot or a brazier). Every shape falls back to
	 * the flank when the ground refuses it.
	 */
	static List<WorldPoint> seatCandidates(WorldPoint player, WorldPoint min, WorldPoint max,
		Collection<WorldPoint> reserved, String activity)
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

		Set<WorldPoint> avoid = new HashSet<>(reserved);
		List<WorldPoint> candidates = new ArrayList<>();
		Predicate<WorldPoint> allowed = c -> !candidates.contains(c) && !avoid.contains(c)
			&& !covers(min, max, c) && !c.equals(player) && !onTheLine(player, nx, ny, c)
			&& c.distanceTo(player) >= 2;

		if ("across".equals(activity))
		{
			// The player mirrored through the footprint: the tile just past
			// its far edge, then the two along that edge, then one further.
			int fx = player.getX() < min.getX() ? max.getX() + 1
				: player.getX() > max.getX() ? min.getX() - 1 : player.getX();
			int fy = player.getY() < min.getY() ? max.getY() + 1
				: player.getY() > max.getY() ? min.getY() - 1 : player.getY();
			if (fx == player.getX() && fy == player.getY())
			{
				fy = max.getY() + 1;
			}
			int[][] far = {{fx, fy}, {fx + px, fy + py}, {fx - px, fy - py}, {fx + dx, fy + dy}};
			for (int[] at : far)
			{
				WorldPoint c = new WorldPoint(at[0], at[1], player.getPlane());
				if (allowed.test(c))
				{
					candidates.add(c);
				}
			}
		}
		else if ("beside".equals(activity) || "ring".equals(activity))
		{
			// Beside: the tiles that touch one of the footprint's edges square
			// on, not at a corner. Ring: three tiles off it. Nearest the player
			// first.
			boolean beside = "beside".equals(activity);
			for (WorldPoint c : ring(min, max, beside ? 1 : 3, player.getPlane()))
			{
				if ((!beside || squareOn(min, max, c)) && allowed.test(c))
				{
					candidates.add(c);
				}
			}
			candidates.sort(nearestTo(player));
		}

		if ("fishing".equals(activity))
		{
			for (int x = min.getX() - 1; x <= max.getX() + 1; x++)
			{
				for (int y = min.getY() - 1; y <= max.getY() + 1; y++)
				{
					WorldPoint c = new WorldPoint(x, y, player.getPlane());
					if (!avoid.contains(c) && !covers(min, max, c) && !c.equals(player)
						&& !onTheLine(player, nx, ny, c))
					{
						candidates.add(c);
					}
				}
			}
			// The bank tile nearest the player is the bank; the water side
			// of the spot is the ground's to refuse.
			candidates.sort(nearestTo(player));
			return candidates;
		}

		int[][] flank = {
			{2 * px, 2 * py}, {-2 * px, -2 * py},
			{2 * px - dx, 2 * py - dy}, {-2 * px - dx, -2 * py - dy},
			{2 * px + dx, 2 * py + dy}, {-2 * px + dx, -2 * py + dy},
		};
		int[][] behind = {
			{-2 * dx + px, -2 * dy + py}, {-2 * dx - px, -2 * dy - py}, {-2 * dx, -2 * dy},
		};
		boolean mining = "mining".equals(activity);
		int[][][] order = mining ? new int[][][]{behind, flank} : new int[][][]{flank, behind};
		for (int[][] group : order)
		{
			for (int[] offset : group)
			{
				// Every one of these is two tiles off the player or more.
				WorldPoint c = player.dx(offset[0]).dy(offset[1]);
				if (allowed.test(c))
				{
					candidates.add(c);
				}
			}
		}
		return candidates;
	}

	/** Nearest a tile first, as the crow flies. */
	static Comparator<WorldPoint> nearestTo(WorldPoint to)
	{
		return Comparator.comparingInt(a -> square(a, to));
	}

	/** Nearest a tile first, in steps. */
	static Comparator<WorldPoint> stepsFrom(WorldPoint to)
	{
		return Comparator.comparingInt(a -> a.distanceTo(to));
	}

	private static int square(WorldPoint a, WorldPoint b)
	{
		int dx = a.getX() - b.getX();
		int dy = a.getY() - b.getY();
		return dx * dx + dy * dy;
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
		if (view == null || session.standing)
		{
			return reserved;
		}
		if (session.targetNpc != null)
		{
			for (NPC npc : sameNameNpcs(view, min, NEIGHBOUR_TILES))
			{
				WorldPoint at = npc.getWorldLocation();
				addFootprint(reserved, at, farCorner(npc, at));
			}
			return reserved;
		}

		int plane = view.getPlane();
		for (int x = min.getX() - NEIGHBOUR_TILES; x <= max.getX() + NEIGHBOUR_TILES; x++)
		{
			for (int y = min.getY() - NEIGHBOUR_TILES; y <= max.getY() + NEIGHBOUR_TILES; y++)
			{
				Tile tile = sceneTile(view, x, y, plane);
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
		if (session == null || !config.borrowsTools())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		// The tool in the hand and the outfit's hat on the head, both, since
		// they are different slots; that is what the player looks like. A hand
		// that is busy with the scroll is not a refusal and is asked again on
		// the next drop; a thing the hands decline outright is not.
		Set<Integer> worn = Borrow.wornItems(local.getPlayerComposition());
		int inHand = Borrow.wornWeapon(local.getPlayerComposition());
		boolean fresh = !borrow.isBorrowing();
		int tool = Borrow.toolFor(session.activity, session.lastAnimation, worn, inHand,
			itemName(inHand));
		if (tool > 0 && "fishing".equals(session.activity) && borrow.isHolding()
			&& borrow.heldItem() != tool)
		{
			// The harpoon kept from the last spot is no net for this one.
			borrow.swap(tool);
		}
		if (tool > 0 && tool != refusedTool && !borrow.isHolding()
			&& !borrow.start(tool) && !hands.isHolding())
		{
			refusedTool = tool;
		}
		int hat = Borrow.hatFor(session.activity, worn);
		if (hat > 0 && hat != refusedHat && !borrow.hasHat() && !borrow.wearHat(hat))
		{
			refusedHat = hat;
		}
		if (fresh && borrow.isBorrowing())
		{
			// A fresh borrow starts the stint the send-off is measured on; one
			// carried from the last tree keeps the one it had.
			stintStart = client.getTickCount();
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
		return nearestObject(client, centre, centre, radius, nameMatch, null, null);
	}

	/**
	 * The same, skipping anything standing on a footprint - the depleted
	 * target itself, which an empty rock shares its name with - and with the
	 * square searched round {@code centre} but the nearest measured from
	 * {@code from}, which the errands need: they search round where the
	 * player is drawn and measure from where the server has them.
	 */
	static WorldPoint nearestObject(Client client, WorldPoint centre, WorldPoint from, int radius,
		Predicate<String> nameMatch, WorldPoint skipMin, WorldPoint skipMax)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || centre == null)
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
				Tile tile = sceneTile(view, x, y, plane);
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object == null || object.getWorldLocation() == null
						|| covers(skipMin, skipMax, object.getWorldLocation())
						|| !nameMatch.test(ErrandController.objectName(client, object.getId())))
					{
						continue;
					}
					int distance = object.getWorldLocation().distanceTo(from);
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

	/**
	 * The nearest NPC by name within a radius of a tile, or null: a stall's
	 * guard, and the errands' banker and cat. The name is tested before
	 * anything else, so the errands' scan sees every name in the scene.
	 */
	static NPC nearestNpc(Client client, WorldPoint centre, int radius, Predicate<String> nameMatch)
	{
		WorldView view = client.getTopLevelWorldView();
		if (centre == null || view == null)
		{
			return null;
		}
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc == null || npc.getName() == null || !nameMatch.test(npc.getName())
				? null : npc.getWorldLocation();
			// Another floor is infinitely far, as distanceTo has it.
			int distance = at == null ? Integer.MAX_VALUE : at.distanceTo(centre);
			if (distance <= radius && distance < bestDistance)
			{
				bestDistance = distance;
				best = npc;
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

	/** A row of skilling.properties, or null when there is none. */
	static String[] values(String key)
	{
		String row = DATA.getProperty(key);
		return row == null ? null : row.split(",");
	}

	/** A row of skilling.properties as ids - items, objects, regions, animations - or null. */
	static int[] ids(String key)
	{
		String[] cells = values(key);
		return cells == null ? null : Arrays.stream(cells).mapToInt(Integer::parseInt).toArray();
	}

	/** A row of skilling.properties as a set of ids. */
	static Set<Integer> idSet(String key)
	{
		return Arrays.stream(ids(key)).boxed().collect(Collectors.toSet());
	}
}
