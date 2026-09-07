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
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
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
		// The Wintertodt brazier is fed, not lit, once it is burning.
		"Feed",
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

	/** A bank this close to the work makes a walk-off a bank trip, maybe. */
	static final int BANK_TRIP_TILES = 40;

	/** The seat is kept this long for the bank to open; the walk there. */
	static final int BANK_WALK_TICKS = 30;

	/** Three minutes: the most a bank trip holds the seat once the bank opened. */
	static final int BANK_TRIP_CAP_TICKS = 300;

	/** How far the next same-name target is looked for after a depletion. */
	private static final int NEXT_TARGET_TILES = 10;

	/** Which scenery counts as the bank, for the trip and the bag's step. */
	static final Predicate<String> BANK_NAMES =
		name -> "Bank booth".equals(name) || "Bank chest".equals(name);

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
	private static final int TRAP_LOOK_TICKS = 3;

	/** A fire lit within this of where the player just stood is theirs. */
	static final int FIRE_LINE_TILES = 3;
	static final int FIRE_LINE_EVERY = 4;

	/** A fire this long after the last starts the count over. */
	private static final int FIRE_LINE_LAPSE_TICKS = 100;

	/** Three seconds at the fire once there. */
	static final int FIRE_WARM_TICKS = 5;
	private static final int FIRE_GAG_ONE_IN = 50;

	/** The cold-stamp-feet rule's regions: the Wintertodt camp and the snows. */
	static final Set<Integer> COLD_REGIONS = new HashSet<>(Arrays.asList(10553, 8253, 6462));

	/**
	 * How long a borrowed tool stays in the hand once a session ends. One
	 * tree is not the work; the walk to the next one is part of it, and
	 * handing an axe back twice a minute to take it straight out again
	 * reads as a fault rather than as company.
	 */
	static final int TOOL_GRACE_TICKS = 50;

	/** The look at the player that goes with the send-off, off the seat. */
	private static final int BEAT_GLANCE_MS = 1800;

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

	/** Ticks a borrowed tool is kept between sessions, and the work it is for. */
	private int graceTicks;
	private String graceActivity;

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
	private WorldPoint trapTile;
	private int trapId;
	private WorldPoint trapSeat;
	private boolean trapAway;
	private int trapUntil;
	private int trapLookTicks;

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

	/**
	 * A way to start an errand by key, silently or not: the prayer at the
	 * altar borrows the altar errand, the fire line the too-close gag. Null
	 * when nothing offered one.
	 */
	private BiConsumer<String, Boolean> errands;

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
		this.ledger = new PropFlourish(follower, hands);
	}

	/**
	 * Offers the errands: (key, silent), as {@link ErrandController#force}.
	 * Without one the prayer kneel and the fire gag simply do not happen.
	 */
	public void setErrands(BiConsumer<String, Boolean> force)
	{
		this.errands = force;
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

	public boolean holdsTheFeet()
	{
		return seat != null || releaseTicks > 0 || trapSeat != null || fireSeat != null
			|| ledger.isActive();
	}

	/**
	 * Whether the errands must stand aside: the feet are held, or a session
	 * is on - except a prayer at an altar, which is the one session that
	 * borrows an errand rather than a seat. This is the busy gate an errand
	 * under way must also pass; the errands' own session gate keeps every
	 * unforced start out of the prayer session too.
	 */
	/**
	 * Excuses the seat for a moment, or hands it back. Excused, the seat is
	 * kept in mind but not retaken - the moment has the feet - and the
	 * company's habits wait; handed back, the next tick retakes it.
	 */
	public void setExcused(boolean excused)
	{
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

	public boolean blocksErrands()
	{
		return holdsTheFeet() || (session != null && !"prayer".equals(session.activity));
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
		noteMenuOption(plain, "");
	}

	/**
	 * The same with the option's target, tags off. Compost goes on a patch
	 * as "Use" with the compost named in the target, and the patch inspector
	 * steps back from it.
	 */
	public void noteMenuOption(String plain, String target)
	{
		lastOption = plain == null ? "" : plain;
		if (session != null && "farming".equals(session.activity)
			&& "Use".equalsIgnoreCase(lastOption) && target != null
			&& target.toLowerCase(Locale.ROOT).contains("compost"))
		{
			company.compost(session.targetTile, session.targetMax);
			syncTheSeat();
		}
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
			case "feed":
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

		if (session != null && !session.standing && name.equals(session.targetName)
			&& min.getPlane() == session.targetTile.getPlane()
			&& (min.distanceTo(session.targetTile) <= WALK_OFF_TILES
				|| (session.depleted && "fishing".equals(session.activity)
					&& min.distanceTo(session.targetTile) <= NEW_SPOT_TILES)))
		{
			session.targetId = targetId;
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
			seatDueTick = now + SEAT_AFTER_TICKS;
			company.aimAt(faceTile(), session.activity, modeFor(here), now);
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
		if (animationId == LAY_TRAP)
		{
			// The trap object turns up on the player's tile a moment later.
			layTick = client.getTickCount();
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
		Player local = client.getLocalPlayer();
		if (local != null && tile.equals(local.getWorldLocation()))
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
		if (skill != null)
		{
			String named = skill.getName().toLowerCase(Locale.ROOT);
			if (!named.equals(session.activity))
			{
				// A "Use" click said nothing; the drop names the work, and the
				// seat may be the wrong shape for it: across a fire, not on
				// its flank. Chosen again, once the player is at it.
				session.activity = named;
				Player local = client.getLocalPlayer();
				WorldPoint here = local == null ? null : local.getWorldLocation();
				company.aimAt(faceTile(), named, modeFor(here), now);
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
		if (now - session.startTick >= SEAT_AFTER_TICKS)
		{
			// The drop may be what named the activity, and so the tool.
			maybeBorrow();
		}
		company.drop(session.xpDrops, now);
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
			if (id != trapId && covers(min, max, trapTile))
			{
				// The trap sprang, or fell: a glance at the player.
				trapId = id;
				Player local = client.getLocalPlayer();
				if (trapSeat != null && local != null && local.getWorldLocation() != null)
				{
					follower.setStayFaceTile(local.getWorldLocation());
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
			log.debug("The spot moved to {}", at);
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
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc == null ? null : npc.getWorldLocation();
			// The one on the target's own tile is the target, or what is
			// left of it in the list this tick.
			if (at == null || npc == session.targetNpc || at.equals(session.targetTile)
				|| !session.targetName.equals(npc.getName())
				|| at.getPlane() != session.targetTile.getPlane()
				|| at.distanceTo(session.targetTile) > NEW_SPOT_TILES
				|| (best != null && at.distanceTo(session.targetTile)
					>= best.distanceTo(session.targetTile)))
			{
				continue;
			}
			best = at;
		}
		return best;
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
		dropTheBankTrip(true);
		dropTheTrap(true);
		dropTheFireLine(true);
		dropTheGrace(false);
		letGoOfTheNotes();
		ledger.abort();
		ledgerAt = 0;
		pending = null;
		releaseTicks = 0;
		excused = false;
		lastFreeSlots = -1;
		halfArmed = true;
		ticksSinceLoading = 1000;
		previousHere = null;
		layTick = -1000;
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
		else if (plain.contains(TASK_DONE_MESSAGE))
		{
			taskDone(now);
		}
		else
		{
			for (String fragment : NEW_TASK_MESSAGES)
			{
				if (plain.contains(fragment))
				{
					newTask(now);
					return;
				}
			}
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
		log.debug("Stunned; a shake of the head");
	}

	/** Out of runes: a look at the player and a think. */
	private void outOfRunes(int now)
	{
		if (now < runesCooldownUntil)
		{
			return;
		}
		runesCooldownUntil = now + RUNES_COOLDOWN_TICKS;
		Player local = client.getLocalPlayer();
		if (local != null && local.getWorldLocation() != null)
		{
			company.lookAt(local.getWorldLocation(), CLAP_TICKS);
		}
		company.gesture(KeepingCompany.SCRATCH_HEAD);
	}

	/**
	 * The task ledger: applause, then the scroll for the entry. The scroll
	 * goes through its own flourish, which holds the feet, so it is skipped
	 * while something else has them.
	 */
	private void taskDone(int now)
	{
		if (now < ledgerCooldownUntil)
		{
			return;
		}
		ledgerCooldownUntil = now + LEDGER_COOLDOWN_TICKS;
		company.gesture(CLAP);
		if (!holdsTheFeet() && !feetTaken.getAsBoolean())
		{
			ledgerAt = now + CLAP_TICKS;
		}
	}

	/** A new task: a shrug. Whatever it is, it is a lot of them. */
	private void newTask(int now)
	{
		if (now < ledgerCooldownUntil)
		{
			return;
		}
		ledgerCooldownUntil = now + LEDGER_COOLDOWN_TICKS;
		company.gesture(KeepingCompany.SHRUG);
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
			dropTheBankTrip(true);
			dropTheTrap(true);
			dropTheFireLine(true);
			dropTheGrace(false);
			letGoOfTheNotes();
			ledger.abort();
			ledgerAt = 0;
			pending = null;
			return;
		}

		if (releaseTicks > 0 && --releaseTicks == 0)
		{
			letGoOfTheNotes();
			if (follower.isStaying() && !feetTaken.getAsBoolean())
			{
				// Only when the seat is still ours: a thrall or the spectator
				// that took the feet during the bow must not be released by us.
				follower.resumeFollowing();
			}
		}

		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			if (session != null)
			{
				end(true, false, true);
			}
			dropTheBankTrip(true);
			dropTheTrap(true);
			dropTheFireLine(true);
			dropTheGrace(false);
			previousHere = null;
			return;
		}

		int now = client.getTickCount();
		if (graceTicks > 0)
		{
			watchTheGrace();
		}
		if (bankTrip)
		{
			watchTheBankTrip(now, here);
		}
		watchTheTrap(now, here);
		watchTheFireLine(now, here);
		ledger.tick();
		if (ledgerAt > 0 && now >= ledgerAt)
		{
			ledgerAt = 0;
			if (!holdsTheFeet() && !feetTaken.getAsBoolean())
			{
				ledger.start(ErrandController.SCROLL_PROP, ErrandController.READ_SCROLL_POSE,
					LEDGER_TICKS);
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
			if (session.settling)
			{
				// The first swing at the moved target: this is where they
				// work from now, and if it took a walk to get there the seat
				// waits its six ticks from here, as it did at the start.
				session.settling = false;
				if (!here.equals(session.workSpot))
				{
					seatDueTick = now + SEAT_AFTER_TICKS;
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
		company.tick(now, here, local.getAnimation() != -1, session.activity);
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
		if (pending == null || inCombat.getAsBoolean() || feetTaken.getAsBoolean()
			|| !follower.isSpawned())
		{
			return;
		}
		int now = client.getTickCount();
		WorkSession opened = new WorkSession(pending.option, pending.activity,
			pending.targetName, pending.targetId, pending.targetTile, pending.targetMax,
			pending.targetNpc, pending.workSpot, now);
		opened.lastAnimation = pending.lastAnimation;
		pending = null;
		open(opened, now);
		// A prayer at an altar takes no seat: the follower goes and kneels
		// beside the player through the altar errand, with its lines skipped.
		if ("Pray-at".equalsIgnoreCase(session.option) && errands != null)
		{
			errands.accept("altar", true);
		}
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
		if (here == null || !config.keepsCompany() || inCombat.getAsBoolean()
			|| feetTaken.getAsBoolean() || !follower.isSpawned())
		{
			return;
		}
		double radians = (local.getCurrentOrientation() / 2048.0) * 2.0 * Math.PI;
		WorldPoint front = new WorldPoint(here.getX() + (int) Math.round(-Math.sin(radians)),
			here.getY() + (int) Math.round(-Math.cos(radians)), here.getPlane());
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
		// the new work seats itself.
		dropTheBankTrip(true);
		dropTheTrap(true);
		dropTheFireLine(true);
		letGoOfTheNotes();
		session = opened;
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
		seat = null;
		reseat = false;
		refusedTool = 0;
		seatDueTick = now + SEAT_AFTER_TICKS;
		company.begin(now);
		company.aimAt(faceTile(), session.activity, modeFor(session.workSpot), now);
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
		// Walking to the next tree is not the end of the work, so the tool
		// stays in the hand while the player repositions and the send-off
		// waits to see whether they start again. Only an abort - a fight, a
		// plane change, somebody else taking the follower - puts it straight
		// back, because then there is nobody to hand it to.
		// The patch inspector writes its notes at the end, and the scroll and
		// a tool share the one hand, so that end really is the end.
		boolean notes = !silent && "farming".equals(done.activity)
			&& seat != null && !bankTrip && follower.isStaying();
		boolean carry = !silent && !notes && borrow.isHolding();
		if (carry)
		{
			graceTicks = TOOL_GRACE_TICKS;
			graceActivity = done.activity;
		}
		boolean beat = !silent && !carry && withBeat && borrow.isHolding();
		if (carry)
		{
			if (standUp > 0)
			{
				follower.playAnimation(standUp);
			}
		}
		else if (beat)
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
		if (seat != null && !bankTrip)
		{
			seat = null;
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

	/**
	 * The kept tool, between sessions. It goes back when the player turns
	 * out to have finished rather than moved along, and straight back if
	 * anything else claims the follower first.
	 */
	private void watchTheGrace()
	{
		if (inCombat.getAsBoolean() || feetTaken.getAsBoolean() || !follower.isSpawned())
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
		if (--graceTicks <= 0)
		{
			dropTheGrace(true);
		}
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
		if (!borrow.isHolding())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (earned && client.getTickCount() - stintStart >= BEAT_AFTER_TICKS
			&& follower.isSettled() && here != null)
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
		log.debug("Target depleted: {} at {}", session.targetName, session.targetTile);

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
			next = nearestObject(client, session.targetTile, NEXT_TARGET_TILES,
				session.targetName::equals, session.targetTile, session.targetMax);
		}
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		company.depleted(session.activity, client.getTickCount() - session.targetSinceTick,
			session.targetTile, session.targetMax, next, here, client.getTickCount());
		// The step back moved the seat; the tick would catch up, but a
		// release in between must let go of where the follower now is.
		syncTheSeat();
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
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (now - layTick > LAY_SETTLE_TICKS || here == null || !covers(min, max, here))
		{
			return false;
		}
		layTick = -1000;
		if (bankTrip || fireSeat != null || feetTaken.getAsBoolean() || inCombat.getAsBoolean()
			|| !follower.isSpawned())
		{
			return true;
		}
		WorldPoint from = follower.getWorldLocation();
		List<WorldPoint> ring = ring(min, max, TRAP_WATCH_TILES, here.getPlane());
		ring.removeIf(here::equals);
		if (from != null)
		{
			ring.sort((a, b) -> Integer.compare(a.distanceTo(from), b.distanceTo(from)));
		}
		for (WorldPoint candidate : ring)
		{
			if (follower.stayAt(candidate))
			{
				trapTile = min;
				trapId = id;
				trapSeat = candidate;
				trapAway = false;
				trapUntil = now + TRAP_WATCH_TICKS;
				trapLookTicks = 0;
				follower.setStayFaceTile(min);
				log.debug("Watching the trap at {} from {}", min, candidate);
				return true;
			}
		}
		return true;
	}

	/**
	 * Every tick the trap is watched: the player back within three tiles
	 * after being away, two minutes, a fight, or the leash snapping (the
	 * stay is simply gone) all end it. The glance at the player when the
	 * trap changes runs down here, back to the trap.
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
		boolean near = here.distanceTo(trapTile) <= WALK_OFF_TILES;
		if (!near)
		{
			trapAway = true;
		}
		if (now >= trapUntil || (near && trapAway))
		{
			dropTheTrap(true);
			return;
		}
		if (trapLookTicks > 0 && --trapLookTicks == 0)
		{
			follower.setStayFaceTile(trapTile);
		}
	}

	/** @param release whether to hand the feet back, when they are still ours */
	private void dropTheTrap(boolean release)
	{
		if (trapSeat == null)
		{
			return;
		}
		trapSeat = null;
		trapTile = null;
		trapLookTicks = 0;
		if (release && follower.isStaying() && !feetTaken.getAsBoolean())
		{
			follower.resumeFollowing();
		}
	}

	// ------------------------------------------------------------- fire line

	/**
	 * A fire lit within three tiles of where the player just stood is one
	 * of theirs. Every fourth, the follower walks over, warms its hands and
	 * catches up on foot - and one time in fifty, the errand's too-close gag
	 * instead. Braziers and campfires are not fires.
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
			|| feetTaken.getAsBoolean() || inCombat.getAsBoolean() || !follower.isSpawned())
		{
			return;
		}
		if (errands != null && random.nextInt(FIRE_GAG_ONE_IN) == 0)
		{
			errands.accept("firedeath", false);
			return;
		}
		WorldPoint from = follower.getWorldLocation();
		List<WorldPoint> beside = ring(min, max, 1, min.getPlane());
		beside.removeIf(c -> c.getX() != min.getX() && c.getY() != min.getY());
		if (from != null)
		{
			beside.sort((a, b) -> Integer.compare(a.distanceTo(from), b.distanceTo(from)));
		}
		for (WorldPoint candidate : beside)
		{
			if (follower.stayAt(candidate))
			{
				fireSeat = candidate;
				follower.setStayFaceTile(min);
				int walk = from == null ? FIRE_LINE_TILES : from.distanceTo(candidate);
				fireWarmAt = now + walk + 2;
				fireLeaveAt = 0;
				log.debug("The fourth fire, at {}; going over", min);
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
			fireWarmAt = 0;
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
		if (release && follower.isStaying() && !feetTaken.getAsBoolean())
		{
			follower.resumeFollowing();
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
		bankTripUntil = now + (banking ? BANK_TRIP_CAP_TICKS : BANK_WALK_TICKS);
		follower.setStayFaceTile(here);
		log.debug("A bank trip; the seat at {} is kept", seat);
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
			log.debug("The bank opened; the seat is kept for the trip");
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
		if (now >= bankTripUntil || (near && bankTripAway))
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
		if (release && follower.isStaying() && !feetTaken.getAsBoolean())
		{
			follower.resumeFollowing();
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
		List<WorldPoint> candidates;
		NPC guard = null;
		WorldPoint face = faceTile();
		if (mode == KeepingCompany.Mode.STALL)
		{
			guard = nearestGuard(player);
			WorldPoint post = guard == null ? null : guard.getWorldLocation();
			if (post == null)
			{
				return;
			}
			candidates = stallCandidates(player, post, session.targetTile, session.targetMax);
			face = post;
		}
		else
		{
			candidates = seatCandidates(player, session.targetTile, session.targetMax,
				reservedTiles(), variantFor(mode, session.activity));
		}
		for (WorldPoint candidate : candidates)
		{
			if (follower.stayAt(candidate))
			{
				seat = candidate;
				follower.setStayFaceTile(face);
				company.watch(guard);
				company.seated(seat, faceTile());
				log.debug("Seated at {} facing {}", seat, face);
				return;
			}
		}
		seat = null;
		company.unseated();
		log.debug("No seat by {} for {}; following instead", player, session.targetName);
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
			case "cooking":
				return "fire".equals(name) ? KeepingCompany.Mode.FIRESIDE
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
				return name.contains("patch") ? KeepingCompany.Mode.PATCH : KeepingCompany.Mode.NONE;
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

	/** The nearest guard by name within the watch's reach, or null. */
	private NPC nearestGuard(WorldPoint player)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc == null ? null : npc.getWorldLocation();
			if (at == null || at.getPlane() != player.getPlane()
				|| !Arrays.asList(GUARD_NAMES).contains(npc.getName()))
			{
				continue;
			}
			int distance = at.distanceTo(player);
			if (distance <= GUARD_TILES && distance < bestDistance)
			{
				bestDistance = distance;
				best = npc;
			}
		}
		return best;
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
		else if ("beside".equals(activity))
		{
			// Alongside the footprint: the tiles that touch one of its edges
			// square on, not at a corner, nearest the player first.
			List<WorldPoint> sides = new ArrayList<>();
			for (WorldPoint c : ring(min, max, 1, player.getPlane()))
			{
				boolean xIn = c.getX() >= min.getX() && c.getX() <= max.getX();
				boolean yIn = c.getY() >= min.getY() && c.getY() <= max.getY();
				if ((xIn || yIn) && allowed.test(c))
				{
					sides.add(c);
				}
			}
			sides.sort((a, b) -> Integer.compare(square(a, player), square(b, player)));
			candidates.addAll(sides);
		}
		else if ("ring".equals(activity))
		{
			List<WorldPoint> around = new ArrayList<>();
			for (WorldPoint c : ring(min, max, 3, player.getPlane()))
			{
				if (allowed.test(c))
				{
					around.add(c);
				}
			}
			around.sort((a, b) -> Integer.compare(square(a, player), square(b, player)));
			candidates.addAll(around);
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
			candidates.sort((a, b) -> Integer.compare(square(a, player), square(b, player)));
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
				WorldPoint c = new WorldPoint(player.getX() + offset[0], player.getY() + offset[1],
					player.getPlane());
				if (candidates.contains(c) || avoid.contains(c) || covers(min, max, c)
					|| c.equals(player) || onTheLine(player, nx, ny, c))
				{
					continue;
				}
				candidates.add(c);
			}
		}
		return candidates;
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
		// The tool first. The hands hold one thing, and the tool is the half
		// of the borrow that reads as joining in - a hat instead of your
		// pickaxe is a worse trade than no hat. The outfit's hat is what the
		// work with no tool of its own gets: thieving, agility.
		Set<Integer> worn = Borrow.wornItems(local.getPlayerComposition());
		int held = Borrow.wornWeapon(local.getPlayerComposition());
		int[] wanted = {
			Borrow.toolFor(session.activity, session.lastAnimation, worn, held, itemName(held)),
			Borrow.hatFor(session.activity, worn),
		};
		for (int item : wanted)
		{
			if (item <= 0 || item == refusedTool)
			{
				continue;
			}
			if (borrow.start(item))
			{
				// A fresh borrow starts the stint the send-off is measured on;
				// a tool carried from the last tree keeps the one it had.
				stintStart = client.getTickCount();
				return;
			}
			refusedTool = item;
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
		return nearestObject(client, centre, radius, nameMatch, null, null);
	}

	/**
	 * The same, skipping anything standing on a footprint - the depleted
	 * target itself, which an empty rock shares its name with.
	 */
	static WorldPoint nearestObject(Client client, WorldPoint centre, int radius,
		Predicate<String> nameMatch, WorldPoint skipMin, WorldPoint skipMax)
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
						|| covers(skipMin, skipMax, object.getWorldLocation())
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
