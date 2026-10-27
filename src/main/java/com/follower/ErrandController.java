package com.follower;

import java.util.*;
import java.util.List;
import java.util.Random;
import java.util.function.*;
import java.util.function.Consumer;
import net.runelite.api.*;
import net.runelite.api.Player;
import net.runelite.api.coords.*;
import lombok.*;
import java.util.Map;
import java.util.function.Predicate;
import static com.follower.ErrandController.Errand.*;
import static com.follower.ErrandController.State.*;

/**
 * Little life errands: now and then the follower notices something nearby - a
 * bank, an altar, a fire, a cat - announces itself, walks over, has its
 * moment, and comes back. Everything is assembled from existing machinery:
 * {@link FollowerEntity#stayAt} for the trip (BFS, closest-approach),
 * {@link FollowerEntity#teleportToPlayer} for the way home, and the rules
 * engine for every line (group "errand", so the words stay editable).
 *
 * <p>Errands must never cost the player anything: combat, dialogs, thrall
 * mode, the Wilderness, or the player simply leaving all abort or suppress.
 */
class ErrandController
{
	/**
	 * The errand types; the lower-case names are the keys the rule
	 * {@code names} lists match. Each comes with its setting, and a roll
	 * offers them in this order.
	 */
	@RequiredArgsConstructor
	enum Errand
	{
		BANK("Bank visits", FollowerConfig::errandBank),
		ALTAR("Altar visits", FollowerConfig::errandAltar),
		FIRE("Warming up", FollowerConfig::errandFire),
		/** The 10% fire variant: walks INTO it. Announces itself as FIRE, and
		 * a roll never offers it on its own. */
		FIREDEATH("Warming up, too close", config -> false),
		CAT("Cats", FollowerConfig::errandCat),
		BOOTLACE("Bootlaces", FollowerConfig::errandBootlace),
		GLANCE("Did you see that?", FollowerConfig::errandGlance),
		/** Stops where it stands and writes something up in its scroll. */
		DOCUMENT("Field notes", FollowerConfig::errandDocument),
		/** Walks to something notable, looks at it, and writes THAT up. */
		STUDY("Field study", FollowerConfig::errandStudy),
		/** Plain nosiness: walks to something curious, looks, then notes it
		 * down in the scroll - everything explored is for posterity. Also
		 * the one errand with an arrival trigger - see noticeArrival. */
		EXPLORE("Exploring", FollowerConfig::errandExplore);

		final String key = name().toLowerCase(Locale.ROOT);
		/** What the side panel calls it; the settings use the same words. */
		final String label;
		/** Whether its setting is on. */
		final Predicate<FollowerConfig> enabled;
	}

	enum State
	{
		IDLE, TRAVELING, DOING, RETURNING
	}

	/**
	 * Writing in a scroll with a quill, the pose the Advanced posing window
	 * shows as Write with a quill. The animation is authored to play once,
	 * with no restart frame; held as a pose override it lifts the scroll once
	 * and writes on over its writing frames for as long as the pose is held,
	 * and lowers it when let go of (FollowerEntity's holdTheWriting and
	 * WRITE_PUT_AWAY).
	 */
	// Public: the minutes-taking borrows the same stance and props, so the
	// documenting look has exactly one definition.
	static final int WRITE_SCROLL_POSE = 13302;

	/**
	 * The two things the writing pose names for its hands, as the game draws
	 * them: the charting quill in the left hand (the shield slot) and the
	 * scroll it writes in in the right (the weapon slot). Held together
	 * through {@link Hands#hold(int, int)}.
	 */
	static final int QUILL_PROP = 31829,
		SCROLL_PROP = 31830;

	/**
	 * What the scribe considers worth walking over to.
	 *
	 * <p>Matched by exact name against the loaded scene, the same way the bank
	 * and altar errands find their targets. A name that never matches costs
	 * nothing - that study simply never fires - so the honest verification is
	 * in game: {@code ::follower errandscan} lists what the scene actually
	 * calls things. Every name here must have its study-<slug> start and end
	 * rules in phrases.json; a lint holds the two lists together.
	 *
	 * <p>Deliberately the mundane fixtures of the world rather than its
	 * wonders. The follower already has area lines for the famous things; the
	 * joke of the field study is the seriousness applied to a crate.
	 */
	static final String[] STUDY_TARGETS = {
		"Anvil", "Furnace", "Well", "Fountain", "Statue", "Bookcase", "Crate",
	};

	/**
	 * The errand-event key a studied or explored thing announces itself
	 * under, e.g. "study-anvil".
	 *
	 * @param kind the errand's own key, "study" or "explore"
	 */
	static String siteKey(String kind, String objectName)
	{
		return kind + "-" + objectName.toLowerCase(Locale.ROOT).replace(' ', '-');
	}

	/**
	 * What plain curiosity considers worth a look.
	 *
	 * <p>The study list is the mundane fixtures, deadpan; this is the other
	 * register - the things a person new to a place actually noses at: what's
	 * in the chest, where the trapdoor goes, what the noticeboard says. Same
	 * contract as the study list: matched by exact scene name, a name that
	 * never matches costs nothing, {@code ::follower errandscan} is the
	 * verification, and the lint holds every entry to its explore-<slug>
	 * start and end rules in phrases.json.
	 */
	static final String[] EXPLORE_TARGETS = {
		"Chest", "Trapdoor", "Signpost", "Noticeboard", "Gravestone",
		"Scarecrow", "Web",
	};

	/**
	 * Every errand key the force command accepts, straight from the enum so
	 * the chat command's validation can never drift from what exists.
	 */
	static List<String> keys()
	{
		return new ArrayList<>(displayNames().keySet());
	}

	/** Every errand key with its display name, in the order keys() gives them. */
	static Map<String, String> displayNames()
	{
		Map<String, String> names = new java.util.LinkedHashMap<>();
		for (var errand : Errand.values())
			names.put(errand.key, errand.label);
		return names;
	}

	/**
	 * What holds the prop. Implemented by the plugin, which owns the follower's
	 * composed appearance; the errand only says when the scroll comes out and
	 * when it goes away. Release must be idempotent - it is also called from
	 * the abort and reset paths, where nothing may be held.
	 */
	interface Hands
	{
		void hold(int itemId);

		/**
		 * Puts a thing in each hand, as an animation names them: the left in
		 * the shield slot, the right in the weapon slot - the quill and the
		 * scroll of the writing pose. One {@link #release} lets go of both,
		 * and {@link #isHolding} is true while they are out. Hands that only
		 * know one hand hold the right-hand thing.
		 */
		default void hold(int leftItemId, int rightItemId)
		{
			hold(rightItemId);
		}

		void release();

		/**
		 * Whether something is held right now. The scroll and a borrowed
		 * tool share the weapon slot, so whichever is out first keeps it:
		 * the flourish skips its scroll over a tool, the borrow skips its
		 * tool over a scroll. Default false for hands that never say.
		 */
		/**
		 * Puts something on, in a slot other than the hand: the hat of a
		 * skilling outfit while the tool stays in the hand. Hands that only
		 * know the hand wear nothing, and say so through {@link #isWearing}.
		 */
		default void wear(int itemId)
		{
		}

		/** Takes off whatever {@link #wear} put on. Idempotent. */
		default void takeOff()
		{
		}

		default boolean isWearing()
		{
			return false;
		}

		default boolean isHolding()
		{
			return false;
		}
	}

	/**
	 * Praying at an altar: HUMAN_PRAY_LOOP held as a pose, which kneels and
	 * stays kneeling (frame step 5), then the rise from the one-shot kneel to
	 * stand. That one-shot, 645, played alone is 3.9 ticks and ends almost
	 * upright, so the follower knelt once and stood idle at the altar for the
	 * rest of the visit (QA, 15 Oct).
	 */
	static final int PRAY_LOOP = 179,
		GET_UP = FollowerEntity.PRAY_STAND_UP;

	/** Bending down: petting the cat, seeing to the bootlace. */
	static final int BEND_ANIMATION = 827;

	/**
	 * AnimationID.HUMAN_FIRECOOKING: kneeling at the flames, hands toward
	 * them. The cache has no dedicated warming animation; this reads right.
	 * (FORESTRY_CAMPFIRE 10082 turned out to be the campfire's own animation.)
	 */
	static final int WARM_ANIMATION = 897;

	/**
	 * The warming replayed this often, as the fireside seat does: the clip is
	 * 3.1 ticks, and played once the follower knelt and then stood about at
	 * the fire for four seconds (QA, 15 Oct). Three times over the visit.
	 */
	static final int WARM_EVERY_TICKS = 4;

	static final int TELEPORT_ARRIVE_ANIMATION = 715;

	/** SpotanimID.TELEPORT_CASTING and TELEPORT_REVERSE: swirl out, swirl back in. */
	static final int TELEPORT_CAST_SPOTANIM = 111;

	static final int SEARCH_RADIUS = 12,
		TRAVEL_TIMEOUT_TICKS = 25,
		RETRY_TICKS = 200;

	final Client client;
	final FollowerEntity follower;
	final FollowerConfig config;
	final Consumer<TriggerEvent> dispatch;
	final SpotAnimRepository spotAnims;

	/** True while the follower is otherwise engaged (dialog open, thrall mode). */
	final BooleanSupplier busy;

	State state = IDLE;

	/** True while an errand owns the follower's feet, so nothing else moves it. */
	boolean isBusy()
	{
		return state != IDLE;
	}

	Errand current;
	WorldPoint targetTile,
		errandSite;
	int waitTicks,
		nextRollTicks;
	String forceKey;

	/** The look phase of a study or explore: facing the thing before the
	 * scroll comes out. */
	int studyLookTicks;

	/** What the current study or explore announced itself as, e.g. "study-anvil". */
	String currentSiteKey;

	/** True from a region change until the follower has had its look around. */
	boolean arrivalWatched;

	/** Ticks the player has to hold still before the arrival look begins. */
	int arrivalSettleTicks;

	/** Ticks until the next arrival may trigger an explore; the schedule is
	 * untouched by this - a shopping trip through four regions must not
	 * become four inspections. */
	int arrivalCooldownTicks;

	/** Where the player stood last idle tick, for the settle watch. */
	WorldPoint lastPlayerTile;

	static final int ARRIVAL_SETTLE_TICKS = 8;

	/**
	 * The player has arrived somewhere new. Once they settle - hold still for
	 * a few ticks - the follower goes and noses at something nearby, if the
	 * scene offers anything from the explore list and the cooldown allows.
	 * The scheduled roll is the follower on its own time; this is curiosity
	 * about THIS place, which is the half that reads as a companion relating
	 * to the world rather than running a timer.
	 */
	void noticeArrival()
	{
		// An instance's region id names a slot, not a place: a quest scene, a house
		// or a raid is "somewhere new" on every visit. Play test, 9 Oct: tailing the
		// guard in Children of the Sun, the follower went off to a chest. A look
		// still pending from the region left goes with it.
		var view = client.getTopLevelWorldView();
		arrivalWatched = view == null || !view.isInstance();
		arrivalSettleTicks = ARRIVAL_SETTLE_TICKS;
	}

	/** Test hook: run an errand now - a specific one by name, or any ("*"). */
	void force(String key)
	{
		force(key, false);
	}

	/**
	 * As {@link #force(String)}, with the option of nobody hearing of it.
	 *
	 * @param silent whether the errand's start and end lines are skipped.
	 * The skilling company sends the follower to kneel beside a player
	 * praying at an altar, and that is company, not an errand worth a remark
	 */
	void force(String key, boolean silent)
	{
		forceKey = key == null || key.isEmpty() ? "*" : key.toLowerCase(Locale.ROOT);
		forceSilently = silent;
	}

	/** Whether the forced errand waiting to run keeps its lines to itself. */
	boolean forceSilently;

	/**
	 * Told the key of a forced errand that could not start where the follower
	 * is. The command that asked for it promised a run, so the player hears
	 * why there was none; a silent force tells nobody.
	 */
	@Setter
	Consumer<String> onForcedMiss = key -> { };

	/**
	 * Whether a work session is on. Errands do not start during one; the
	 * exception is the errand the session itself asks for through
	 * {@link #force(String, boolean)}, which is company rather than a
	 * wander, so only the unforced starts read this.
	 */
	@Setter
	BooleanSupplier session = () -> false;

	/** Whether the errand under way says nothing at either end. */
	boolean silent;

	/** The errand's start or end, unless this run was asked to keep quiet. */
	void announce(TriggerEvent event)
	{
		if (!silent)
			dispatch.accept(event);
	}

	final Hands hands;

	/** Every roll the errands make: the schedule, the order, the details. */
	final Random random;

	ErrandController(Client client, FollowerEntity follower, FollowerConfig config,
		Consumer<TriggerEvent> dispatch, SpotAnimRepository spotAnims,
		BooleanSupplier busy, Hands hands)
	{
		this(client, follower, config, dispatch, spotAnims, busy, hands, new Random());
	}

	/** As above, with the rolls seeded, so a long simulation plays out the same every run. */
	ErrandController(Client client, FollowerEntity follower, FollowerConfig config,
		Consumer<TriggerEvent> dispatch, SpotAnimRepository spotAnims,
		BooleanSupplier busy, Hands hands, Random random)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.dispatch = dispatch;
		this.spotAnims = spotAnims;
		this.busy = busy;
		this.hands = hands;
		this.random = random;
		reset();
	}

	/** Logout/shutdown: clean slate, fresh schedule. */
	void reset()
	{
		putTheScrollAway();
		state = IDLE;
		current = null;
		targetTile = null;
		errandSite = null;
		// Unlike clearErrand, this is the logout path. A forced errand that
		// never got to run is not owed to the next session - the chat command
		// asking for it was in the one before.
		forceKey = null;
		arrivalWatched = false;
		arrivalSettleTicks = 0;
		arrivalCooldownTicks = 0;
		lastPlayerTile = null;
		nextRollTicks = scheduleTicks();
	}

	/** Call once per game tick, on the client thread. */
	void tick()
	{
		if (!config.errandsEnabled())
		{
			if (state != IDLE)
				abort();
			// One asked for before errands were switched off is not owed
			// whenever they next come back on.
			forceKey = null;
			forceSilently = false;
			return;
		}

		if (arrivalCooldownTicks > 0)
			arrivalCooldownTicks--;

		if (state == IDLE)
		{
			idleTick();
			return;
		}

		// An active errand must never inconvenience the player.
		if (!safeToContinue())
		{
			abort();
			return;
		}

		if (state == TRAVELING)
		{
			var at = follower.getWorldLocation();
			// Fully settled, not merely close: an animation started while the
			// last steps play out is cancelled by the movement system.
			boolean arrived = at != null && targetTile != null
				&& at.distanceTo(targetTile) <= 2 && follower.isSettled();
			if (arrived || --waitTicks <= 0)
				beginDoing();
		}
		else if (state == DOING)
		{
			// The study looks BEFORE it writes: a few ticks facing the thing,
			// and only then the scroll. Reversing that order reads as a scribe
			// deciding the conclusion before the evidence.
			if (studyLookTicks > 0 && --studyLookTicks == 0)
				takeOutTheScroll();
			if (--waitTicks <= 0)
				finish();
			else if (current == FIRE && waitTicks % WARM_EVERY_TICKS == 0)
				follower.playAnimation(WARM_ANIMATION);
			else if (current == ALTAR && waitTicks == 1)
			{
				// Up off the knees a tick before the walk home, which would
				// cancel the get-up on its first frame.
				follower.clearPoseOverride(PRAY_LOOP);
				follower.playAnimation(GET_UP);
			}
		}
		else if (state == RETURNING)
		{
			if (--waitTicks <= 0)
				completeReturn();
		}
	}

	// ------------------------------------------------------------------ idle

	void idleTick()
	{
		if (forceKey != null)
		{
			String key = forceKey;
			forceKey = null;
			// Set before the start, since the start is what announces it.
			silent = forceSilently;
			forceSilently = false;
			if (clearToStart())
			{
				for (var errand : Errand.values())
				{
					if (("*".equals(key) || errand.key.equals(key)) && tryStart(errand))
						return;
				}
			}
			boolean quiet = silent;
			silent = false;
			if (!quiet)
				onForcedMiss.accept(key);
			return;
		}

		// The arrival look: the player entered a new region and has now held
		// still long enough to count as settled. Watching stillness rather
		// than the region edge matters - regions change constantly mid-run,
		// and a follower that peels off while the player is still moving is
		// aborted three ticks later by the distance check, which reads as a
		// glitch rather than a character.
		if (arrivalWatched)
		{
			var walker = client.getLocalPlayer();
			var here = walker == null ? null : walker.getWorldLocation();
			// A tool kept between trees is still in the hand, and every errand
			// lets go of the hand when it ends. It goes back within the minute,
			// so the look waits for it, and for the bow, rather than being lost
			// to it. A session's own tool is no such pause: that look is lost
			// to the work, as ever.
			if (here == null || !here.equals(lastPlayerTile)
				|| (hands.isHolding() && !session.getAsBoolean()))
				arrivalSettleTicks = ARRIVAL_SETTLE_TICKS;
			else if (--arrivalSettleTicks <= 0)
			{
				arrivalWatched = false;
				if (arrivalCooldownTicks == 0 && config.errandExplore()
					&& safeToStart() && tryStart(EXPLORE))
				{
					arrivalCooldownTicks = 700 /* ARRIVAL_COOLDOWN_TICKS */;
					// Counts as its turn, or the roll could offer another
					// explore straight after this one.
					lastRun.put(EXPLORE, client.getTickCount());
					lastPlayerTile = here;
					return;
				}
			}
			lastPlayerTile = here;
		}

		if (--nextRollTicks > 0)
			return;
		if (!safeToStart())
		{
			nextRollTicks = RETRY_TICKS;
			return;
		}

		// An errand that has just run stands aside entirely; the rest are
		// offered stalest first. Between them, the errands that need
		// something in the world get their turn when that something is
		// there, instead of losing every roll to the three that never fail.
		List<Errand> candidates = new ArrayList<>();
		for (var errand : Errand.values())
		{
			if (errand.enabled.test(config) && offAgainCooldown(errand))
				candidates.add(errand);
		}
		orderByStaleness(candidates);

		for (var errand : candidates)
		{
			if (tryStart(errand))
			{
				lastRun.put(errand, client.getTickCount());
				return;
			}
		}
		nextRollTicks = RETRY_TICKS;
	}

	boolean tryStart(Errand errand)
	{
		switch (errand)
		{
			case BANK:
			{
				// Most banks have booth/chest OBJECTS; the Grand Exchange has
				// only banker NPCs (measured by scan - no booth object there).
				var bank = findObject(
					name -> SkillingController.BANK_NAMES.test(name)
						|| name.equals("Grand Exchange booth"));
				if (bank == null)
					bank = findNpc(name -> name.equals("Banker")
						|| name.equals("Grand Exchange Clerk"));
				// Closest approach stops the walk in front of a booth, but one
				// the map calls open floor is walked into, and the feet refuse
				// to stop in that: the tile beside it then, as at a fire.
				return startTravelErrand(errand, bank) || startAdjacentErrand(errand, bank);
			}
			case ALTAR:
				return startTravelErrand(errand, findObject(name -> name.equals("Altar")));
			case FIRE:
			{
				var fire = findObject(HotGround::isFire);
				if (fire == null)
					return false;
				// FIRE_DEATH_PERCENT: Chance the fire errand goes catastrophically wrong.
				if (random.nextInt(100) < 10 /* FIRE_DEATH_PERCENT */)
					// The rare version, in which warming the hands goes
					// catastrophically wrong.
					return startTravelErrand(FIREDEATH, fire);
				// Fires are walkable: BESIDE the flames, never in them.
				return startAdjacentErrand(errand, fire);
			}
			case FIREDEATH:
				// Only rolled from FIRE naturally; reachable directly by the
				// force command for testing. Walks straight INTO the flames.
				return startTravelErrand(errand, findObject(HotGround::isFire));
			case CAT:
				// NPCs don't block either; standing ON the cat ruins the moment.
				return startAdjacentErrand(errand, findNpc(
					name -> name.equals("Cat") || name.equals("Kitten") || name.equals("Stray dog")));
			case BOOTLACE:
			case DOCUMENT:
			{
				// No trip at all: stop where it stands and see to the boot, or
				// - something here is worth the record, apparently - scroll and
				// pose together, the read running long enough to look deliberate.
				current = errand;
				errandSite = follower.getWorldLocation();
				follower.stayHere();
				if (errand == BOOTLACE)
					follower.playAnimation(BEND_ANIMATION);
				else
					takeOutTheScroll();
				announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, errand.key));
				state = DOING;
				waitTicks = errand == BOOTLACE ? 6 : 12 + random.nextInt(8);
				return true;
			}
			case STUDY:
			case EXPLORE:
			{
				// Whatever notable fixture the scene has closest, or for the
				// explore the same hunt in the other register: whatever
				// curiosity it offers. A scene with none of them simply
				// produces no study, or no exploring, today.
				List<String> names = new ArrayList<>(java.util.Arrays.asList(
					errand == STUDY ? STUDY_TARGETS : EXPLORE_TARGETS));
				Collections.shuffle(names, random);
				for (String name : names)
				{
					var found = findObject(name::equals);
					if (found != null && beginSiteVisit(errand, found, siteKey(errand.key, name)))
						return true;
				}
				return false;
			}
			case GLANCE:
			{
				var local = client.getLocalPlayer();
				if (local == null || local.getWorldLocation() == null)
					return false;
				var p = local.getWorldLocation();
				int[][] dirs = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
				int[] dir = dirs[random.nextInt(dirs.length)];
				return startTravelErrand(errand,
					p.dx(dir[0]).dy(dir[1]));
			}
			default:
				return false;
		}
	}

	/**
	 * Starts a study of a specific named thing. Package-private as the test
	 * seam: the find half needs a real scene, but everything after the find -
	 * the walk, the look, the scroll, the write-up, the return - is the part
	 * with lifecycle promises, and this is where a test can inject the thing
	 * the fake scene cannot provide.
	 */
	boolean beginStudyAt(WorldPoint target, String objectName)
	{
		return beginSiteVisit(STUDY, target, siteKey("study", objectName));
	}

	/** The explore twin of {@link #beginStudyAt}, and a test seam for the same reason. */
	boolean beginExploreAt(WorldPoint target, String objectName)
	{
		return beginSiteVisit(EXPLORE, target, siteKey("explore", objectName));
	}

	/**
	 * The shared trip to a named thing: beside it, facing it, announced by
	 * name. The walkable targets (fires, cats) come this way too, under
	 * their errand's own key.
	 */
	boolean beginSiteVisit(Errand errand, WorldPoint target, String siteKey)
	{
		List<WorldPoint> besides = new ArrayList<>(List.of(
			target.dx(1),
			target.dx(-1),
			target.dy(1),
			target.dy(-1)));
		var from = follower.getWorldLocation();
		if (from != null)
			besides.sort(Comparator.comparingInt(p -> p.distanceTo(from)));
		for (var beside : besides)
		{
			if (follower.stayAt(beside))
			{
				follower.setStayFaceTile(target);
				current = errand;
				currentSiteKey = siteKey;
				targetTile = beside;
				errandSite = target;
				announce(TriggerEvent.errand(
					TriggerEvent.Type.ERRAND_START, currentSiteKey));
				state = TRAVELING;
				waitTicks = TRAVEL_TIMEOUT_TICKS;
				return true;
			}
		}
		return false;
	}

	/** For walkable targets (fires, cats): aim at a free tile BESIDE them. */
	boolean startAdjacentErrand(Errand errand, WorldPoint target)
	{
		return target != null && beginSiteVisit(errand, target, errand.key);
	}

	boolean startTravelErrand(Errand errand, WorldPoint target)
	{
		// The one thing in the plugin that walks INTO a fire on purpose is
		// the gag that does it for the laugh. The feet refuse every other
		// route onto one, so this trip has to say so outright.
		if (target == null || !(errand == FIREDEATH
			? follower.insistOn(target) : follower.stayAt(target)))
			return false;
		// Once arrived, face the business itself - not the player. For the
		// glance the target IS where it stands, so the facing helper no-ops
		// and the follower keeps staring the way it walked. Also right.
		follower.setStayFaceTile(target);
		current = errand;
		targetTile = target;
		errandSite = target;
		// The doomed fire trip announces itself as an innocent warm-up: the
		// joke needs the audience not to see it coming.
		String announce = errand == FIREDEATH ? FIRE.key : errand.key;
		announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, announce));
		state = TRAVELING;
		waitTicks = TRAVEL_TIMEOUT_TICKS;
		return true;
	}

	// ---------------------------------------------------------------- active

	void beginDoing()
	{
		state = DOING;
		switch (current)
		{
			case STUDY:
				// Look, then write. The whole visit runs about twelve seconds:
				// four of honest looking, the rest with the scroll out.
				studyLookTicks = 4;
				waitTicks = 18 + random.nextInt(5);
				break;
			case EXPLORE:
				// Look, then note it down - everything explored is for
				// posterity. Same beat as the study, a touch brisker: the
				// scribe writes a report, the explorer jots an entry.
				studyLookTicks = 4;
				waitTicks = 14 + random.nextInt(5);
				break;
			case ALTAR:
				follower.setPoseOverride(PRAY_LOOP);
				waitTicks = 8;
				break;
			case FIRE:
				follower.playAnimation(WARM_ANIMATION);
				waitTicks = 12 /* WARM_TICKS */;
				break;
			case FIREDEATH:
				// Standing IN the fire now. The pain is immediate and vocal.
				// The disappearance is armed on the death animation's final
				// frame, so the corpse never stands back up first.
				announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, current.key));
				// FIRE_DEATH_ANIMATION: AnimationID.TRAILBLAZER_DEATH_PLAYER_01: the comedy fire death.
				follower.playAnimation(10629 /* FIRE_DEATH_ANIMATION */);
				// FIRE_DEATH_SPOTANIM: SpotanimID.TRAILBLAZER_DEATH_SPOTANIM: the flames that consume it.
				follower.playSpotAnim(spotAnims.get(2610 /* FIRE_DEATH_SPOTANIM */));
				// Generous window; the return home ends the hide exactly.
				follower.hideAfterEmote(3600);
				waitTicks = 6;
				break;
			case CAT:
				follower.playAnimation(BEND_ANIMATION);
				waitTicks = 7;
				break;
			case GLANCE:
				waitTicks = 4;
				break;
			default:
				waitTicks = 8;
				break;
		}
	}

	void finish()
	{
		putTheScrollAway();
		if (current == FIREDEATH)
		{
			// The vanish already happened on the death animation's last frame
			// (hideAfterEmote); this stage just times the reappearance.
			follower.resumeFollowing();
			state = RETURNING;
			waitTicks = 2;
			return;
		}
		boolean walkedAway = current != BOOTLACE && current != GLANCE
			&& current != DOCUMENT;
		// RETURN_ON_FOOT_DISTANCE: At or under this distance the follower comes back on foot - the
		// catch-up pathing walks a short gap and runs a longer one on its own.
		if (walkedAway && distanceToPlayer() > 10 /* RETURN_ON_FOOT_DISTANCE */)
		{
			// Too far to jog: a proper teleport home. Cast the spell where it
			// stands, swirl and all, then vanish to the player a beat later.
			// TELEPORT_ANIMATION: The standard teleport cast (measured by the mirror-teleport work) and its landing.
			follower.playAnimation(714 /* TELEPORT_ANIMATION */);
			follower.playSpotAnim(spotAnims.get(TELEPORT_CAST_SPOTANIM),
				FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
			// Gone on the cast's last frame. The cast runs 1.58s and the
			// landing is three ticks out at 1.80s, so without this the
			// follower drops back to its idle pose for a fifth of a second
			// mid-teleport - brief, and quite visible. Cleared by
			// teleportToPlayer on arrival.
			follower.hideAfterEmote(1200);
			state = RETURNING;
			waitTicks = 3;
			return;
		}
		// Close enough to come back on foot: releasing the pose lets the
		// catch-up pathing bring it home - walking a short gap, running a
		// longer one - which is what a person would do.
		follower.resumeFollowing();
		home();
	}

	int distanceToPlayer()
	{
		WorldPoint at = follower.getWorldLocation(),
			pt = FollowerEntity.playerTile(client);
		// Another floor is infinitely far, as distanceTo has it.
		return at != null && pt != null ? at.distanceTo(pt) : Integer.MAX_VALUE;
	}

	/** Stage two of the teleport home: land beside the player, swirl in, report. */
	void completeReturn()
	{
		follower.resumeFollowing();
		follower.teleportToPlayer();
		follower.playAnimation(TELEPORT_ARRIVE_ANIMATION);
		// Raised to mid-body: at ground height the swirl sat in the ankles.
		follower.playSpotAnim(spotAnims.get(1299 /* TELEPORT_REVERSE_SPOTANIM */),
			FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
		home();
	}

	/**
	 * Back with the player, on foot or by teleport: the end is reported as
	 * what the errand announced itself as - a study or explore names the
	 * thing itself - and the slate is cleared for the next one.
	 */
	void home()
	{
		String key = (current == STUDY || current == EXPLORE) && currentSiteKey != null
			? currentSiteKey : current.key;
		announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_END, key));
		clearErrand();
	}

	/**
	 * The scroll and the writing pose, on the same tick and therefore the
	 * same rendered frame.
	 *
	 * <p>They can be simultaneous because the dump path of the appearance
	 * service is synchronous: the composed model with the scroll in it is
	 * applied before {@code hold} returns. The first version put two "settle"
	 * ticks between them out of caution about a rebuild delay that does not
	 * exist on this path - and those two ticks were exactly the window in
	 * which the follower stood in its idle pose holding a scroll, which is
	 * the wrong order to ever show. Measured by eye in game: prop-then-wait
	 * reads wrong, together reads right.
	 */
	void takeOutTheScroll()
	{
		hands.hold(QUILL_PROP, SCROLL_PROP);
		follower.setPoseOverride(WRITE_SCROLL_POSE);
	}

	/**
	 * Ends the documenting stance, wherever the errand went from here.
	 *
	 * <p>Idempotent on purpose: it runs on the finish, abort AND reset paths,
	 * and on most of those nothing is held. The alternative - remembering
	 * which paths can be reached with the scroll out - is exactly the kind of
	 * bookkeeping that left a floor held by a rule that no longer existed.
	 */
	void putTheScrollAway()
	{
		studyLookTicks = 0;
		if (current == ALTAR)
			follower.clearPoseOverride(PRAY_LOOP);
		if (current == DOCUMENT || current == STUDY
			|| current == EXPLORE)
			follower.setPoseOverride(0);
		hands.release();
	}

	void abort()
	{
		putTheScrollAway();
		follower.resumeFollowing();
		var local = client.getLocalPlayer();
		var at = follower.getWorldLocation();
		// A death interrupted mid-vanish must not stay invisible; the
		// teleport home also clears any staged hide.
		boolean mustClearHide = current == FIREDEATH || state == RETURNING;
		if (mustClearHide
			|| (local != null && local.getWorldLocation() != null && at != null
				&& at.distanceTo(local.getWorldLocation()) > 8))
			follower.teleportToPlayer();
		clearErrand();
	}

	void clearErrand()
	{
		state = IDLE;
		current = null;
		currentSiteKey = null;
		silent = false;
		targetTile = null;
		errandSite = null;
		nextRollTicks = scheduleTicks();
	}

	// ------------------------------------------------------------ conditions

	/** An errand of the follower's own choosing: not during a session. */
	boolean safeToStart()
	{
		return clearToStart() && !session.getAsBoolean();
	}

	/** What any errand needs before it sets off, forced or not: free feet as well. */
	boolean clearToStart()
	{
		return quiet(client.getLocalPlayer()) && !follower.isStaying();
	}

	/**
	 * What an errand needs throughout: the player about, not fighting or
	 * talking, and out of the Wilderness; the follower out and not wanted.
	 */
	boolean quiet(Player local)
	{
		return local != null
			&& local.getWorldLocation() != null
			&& follower.isSpawned()
			&& !busy.getAsBoolean()
			&& local.getInteracting() == null
			&& client.getVarbitValue(WILDERNESS_VARBIT) != 1;
	}

	boolean safeToContinue()
	{
		var local = client.getLocalPlayer();
		// The player leaving - walked off, teleported, changed plane (which
		// distanceTo counts as infinitely far) - ends it.
		return quiet(local) && errandSite != null
			&& local.getWorldLocation().distanceTo(errandSite) <= 16;
	}

	static final int WILDERNESS_VARBIT = 5963;

	// -------------------------------------------------------------- scanning

	/** The nearest matching scene object's tile within the search radius, or null. */
	WorldPoint findObject(Predicate<String> nameMatch)
	{
		var local = client.getLocalPlayer();
		var lp = local == null ? null : local.getLocalLocation();
		var view = client.getTopLevelWorldView();
		// Between worlds, or mid-login, there is no scene to search. The
		// skilling company's scan does the rest: the square round where the
		// player is drawn, the nearest measured from where the server has them.
		return lp == null || local.getWorldLocation() == null || view == null ? null
			: SkillingController.nearestObject(client,
				WorldPoint.fromScene(view, lp.getSceneX(), lp.getSceneY(), view.getPlane()),
				local.getWorldLocation(), SEARCH_RADIUS, name -> name != null && nameMatch.test(name),
				null, null);
	}

	/**
	 * An object's real name, resolving multilocs.
	 *
	 * <p>Public and static because the examine echo needs exactly this and had
	 * its own naive version: most scenery is a multiloc whose base definition
	 * is called "null" until its impostor is asked, so reading getName() alone
	 * silently answers nothing for a large share of the world.
	 */
	static String objectName(Client client, int objectId)
	{
		var comp = client.getObjectDefinition(objectId);
		if (comp == null)
			return null;
		String name = comp.getName();
		if ((name == null || name.equals("null")) && comp.getImpostorIds() != null)
		{
			try
			{
				var impostor = comp.getImpostor();
				name = impostor == null ? null : impostor.getName();
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}
		return name == null || name.equals("null") ? null : name;
	}

	/** The nearest matching NPC's tile within the search radius, or null. */
	WorldPoint findNpc(Predicate<String> nameMatch)
	{
		var npc = SkillingController.nearestNpc(client, FollowerEntity.playerTile(client),
			SEARCH_RADIUS, nameMatch);
		return npc == null ? null : npc.getWorldLocation();
	}

	// ------------------------------------------------------------- schedule

	/**
	 * When each errand last set off, by tick.
	 *
	 * <p>Measured over fifteen days of play: three errands accounted for 84%
	 * of every one that ran. Not because they were preferred - the roll
	 * shuffles fairly - but because they are the three that need nothing in
	 * the world to work. A bank errand needs a booth in sight and an altar
	 * needs an altar, so most rolls they simply cannot start; the bootlace,
	 * the glance and the note-taking can always start, so they won every
	 * roll the others could not. The bank ran 24 times in that fortnight.
	 * The altar ran twice. The bootlace ran 189 times, on five lines.
	 */
	final Map<Errand, Integer> lastRun = new java.util.EnumMap<>(Errand.class);

	/** Whether this errand has rested long enough to be offered again. */
	boolean offAgainCooldown(Errand errand)
	{
		var last = lastRun.get(errand);
		return last == null
			// REPEAT_GAPS: How long an errand waits before it may come round again, as a multiple
			// of the ordinary gap between errands. Scaled rather than fixed so the
			// frequency setting keeps its meaning: whatever pace was chosen, an
			// errand does not repeat until roughly three others could have happened.
			|| client.getTickCount() - last >= baseScheduleTicks() * 3 /* REPEAT_GAPS */;
	}

	/**
	 * Puts the least recently run errands first, so a roll offers variety
	 * before it offers convenience. Ties are shuffled, so equally stale
	 * errands do not fall into a fixed order.
	 */
	void orderByStaleness(List<Errand> candidates)
	{
		Collections.shuffle(candidates, random);
		candidates.sort(Comparator.comparingInt(
			errand -> lastRun.getOrDefault(errand, Integer.MIN_VALUE)));
	}

	int scheduleTicks()
	{
		// 60%..140% of the base, so errands never feel metronomic.
		return (int) (baseScheduleTicks()
			* (0.6 + random.nextDouble() * 0.8));
	}

	int baseScheduleTicks()
	{
		return config.errandFrequency().ticks;
	}
}
