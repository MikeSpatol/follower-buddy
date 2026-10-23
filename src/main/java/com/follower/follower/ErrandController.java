package com.follower.follower;

// public:cut errands
import com.follower.FollowerConfig;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
// public:end errands
import net.runelite.api.Client;
// public:cut errands
import net.runelite.api.NPC;
// public:end errands
import net.runelite.api.ObjectComposition;
// public:cut errands
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
// public:end errands

// public:cut errands
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
// public:end errands
@Slf4j // public:cut errands
// public:else /** The writing pose and its props, the shared prop hands, and the multiloc-aware object name. */
public class ErrandController
{
	// public:cut errands
	/**
	 * The errand types; the lower-case names are the keys the rule
	 * {@code names} lists match. Each comes with its setting, and a roll
	 * offers them in this order.
	 */
	private enum Errand
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

		final String key = name().toLowerCase(java.util.Locale.ROOT);
		/** What the side panel calls it; the settings use the same words. */
		final String label;
		/** Whether its setting is on. */
		final java.util.function.Predicate<FollowerConfig> enabled;

		Errand(String label, java.util.function.Predicate<FollowerConfig> enabled)
		{
			this.label = label;
			this.enabled = enabled;
		}
	}

	private enum State
	{
		IDLE, TRAVELING, DOING, RETURNING
	}

	// public:end errands
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
	public static final int WRITE_SCROLL_POSE = 13302;

	/**
	 * The two things the writing pose names for its hands, as the game draws
	 * them: the charting quill in the left hand (the shield slot) and the
	 * scroll it writes in in the right (the weapon slot). Held together
	 * through {@link Hands#hold(int, int)}.
	 */
	public static final int QUILL_PROP = 31829;
	public static final int SCROLL_PROP = 31830;

	// public:cut errands
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
	public static final String[] STUDY_TARGETS = {
		"Anvil", "Furnace", "Well", "Fountain", "Statue", "Bookcase", "Crate",
	};

	/**
	 * The errand-event key a studied or explored thing announces itself
	 * under, e.g. "study-anvil".
	 *
	 * @param kind the errand's own key, "study" or "explore"
	 */
	public static String siteKey(String kind, String objectName)
	{
		return kind + "-" + objectName.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
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
	public static final String[] EXPLORE_TARGETS = {
		"Chest", "Trapdoor", "Signpost", "Noticeboard", "Gravestone",
		"Scarecrow", "Web",
	};

	/**
	 * Every errand key the force command accepts, straight from the enum so
	 * the chat command's validation can never drift from what exists.
	 */
	public static java.util.List<String> keys()
	{
		return new java.util.ArrayList<>(displayNames().keySet());
	}

	/** Every errand key with its display name, in the order keys() gives them. */
	public static java.util.Map<String, String> displayNames()
	{
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		for (Errand errand : Errand.values())
		{
			names.put(errand.key, errand.label);
		}
		return names;
	}

	// public:end errands
	/**
	 * What holds the prop. Implemented by the plugin, which owns the follower's
	 * composed appearance; the errand only says when the scroll comes out and
	 * when it goes away. Release must be idempotent - it is also called from
	 * the abort and reset paths, where nothing may be held.
	 */
	public interface Hands
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
		// public:cut skilling
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

		// public:end skilling
		default boolean isHolding()
		{
			return false;
		}
	}
	// public:cut errands

	/**
	 * Praying at an altar: HUMAN_PRAY_LOOP held as a pose, which kneels and
	 * stays kneeling (frame step 5), then the rise from the one-shot kneel to
	 * stand. That one-shot, 645, played alone is 3.9 ticks and ends almost
	 * upright, so the follower knelt once and stood idle at the altar for the
	 * rest of the visit (QA, 15 Oct).
	 */
	private static final int PRAY_LOOP = 179;
	private static final int GET_UP = FollowerEntity.PRAY_STAND_UP;

	/** Bending down: petting the cat, seeing to the bootlace. */
	private static final int BEND_ANIMATION = 827;

	/**
	 * AnimationID.HUMAN_FIRECOOKING: kneeling at the flames, hands toward
	 * them. The cache has no dedicated warming animation; this reads right.
	 * (FORESTRY_CAMPFIRE 10082 turned out to be the campfire's own animation.)
	 */
	private static final int WARM_ANIMATION = 897;

	/**
	 * The warming replayed this often, as the fireside seat does: the clip is
	 * 3.1 ticks, and played once the follower knelt and then stood about at
	 * the fire for four seconds (QA, 15 Oct). Three times over the visit.
	 */
	private static final int WARM_EVERY_TICKS = 4;
	private static final int WARM_TICKS = 12;

	/** At or under this distance the follower comes back on foot - the
	 * catch-up pathing walks a short gap and runs a longer one on its own. */
	private static final int RETURN_ON_FOOT_DISTANCE = 10;

	/** AnimationID.TRAILBLAZER_DEATH_PLAYER_01: the comedy fire death. */
	private static final int FIRE_DEATH_ANIMATION = 10629;

	/** SpotanimID.TRAILBLAZER_DEATH_SPOTANIM: the flames that consume it. */
	private static final int FIRE_DEATH_SPOTANIM = 2610;

	/** Chance the fire errand goes catastrophically wrong. */
	private static final int FIRE_DEATH_PERCENT = 10;

	/** The standard teleport cast (measured by the mirror-teleport work) and its landing. */
	private static final int TELEPORT_ANIMATION = 714;
	private static final int TELEPORT_ARRIVE_ANIMATION = 715;

	/** SpotanimID.TELEPORT_CASTING and TELEPORT_REVERSE: swirl out, swirl back in. */
	private static final int TELEPORT_CAST_SPOTANIM = 111;
	private static final int TELEPORT_REVERSE_SPOTANIM = 1299;

	private static final int SEARCH_RADIUS = 12;
	private static final int TRAVEL_TIMEOUT_TICKS = 25;
	private static final int RETRY_TICKS = 200;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final Consumer<TriggerEvent> dispatch;
	private final com.follower.appearance.SpotAnimRepository spotAnims;

	/** True while the follower is otherwise engaged (dialog open, thrall mode). */
	private final BooleanSupplier busy;

	private State state = State.IDLE;

	/** True while an errand owns the follower's feet, so nothing else moves it. */
	public boolean isBusy()
	{
		return state != State.IDLE;
	}

	private Errand current;
	private WorldPoint targetTile;
	private WorldPoint errandSite;
	private int waitTicks;
	private int nextRollTicks;
	private String forceKey;

	/** The look phase of a study or explore: facing the thing before the
	 * scroll comes out. */
	private int studyLookTicks;

	/** What the current study or explore announced itself as, e.g. "study-anvil". */
	private String currentSiteKey;

	/** True from a region change until the follower has had its look around. */
	private boolean arrivalWatched;

	/** Ticks the player has to hold still before the arrival look begins. */
	private int arrivalSettleTicks;

	/** Ticks until the next arrival may trigger an explore; the schedule is
	 * untouched by this - a shopping trip through four regions must not
	 * become four inspections. */
	private int arrivalCooldownTicks;

	/** Where the player stood last idle tick, for the settle watch. */
	private WorldPoint lastPlayerTile;

	private static final int ARRIVAL_SETTLE_TICKS = 8;
	private static final int ARRIVAL_COOLDOWN_TICKS = 700;

	/**
	 * The player has arrived somewhere new. Once they settle - hold still for
	 * a few ticks - the follower goes and noses at something nearby, if the
	 * scene offers anything from the explore list and the cooldown allows.
	 * The scheduled roll is the follower on its own time; this is curiosity
	 * about THIS place, which is the half that reads as a companion relating
	 * to the world rather than running a timer.
	 */
	public void noticeArrival()
	{
		// An instance's region id names a slot, not a place: a quest scene, a house
		// or a raid is "somewhere new" on every visit. Play test, 9 Oct: tailing the
		// guard in Children of the Sun, the follower went off to a chest. A look
		// still pending from the region left goes with it.
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		arrivalWatched = view == null || !view.isInstance();
		arrivalSettleTicks = ARRIVAL_SETTLE_TICKS;
	}

	/** Test hook: run an errand now - a specific one by name, or any ("*"). */
	public void force(String key)
	{
		force(key, false); // public:cut skilling
		// public:else forceKey = key == null || key.isEmpty() ? "*" : key.toLowerCase(java.util.Locale.ROOT);
	}

	// public:cut skilling
	/**
	 * As {@link #force(String)}, with the option of nobody hearing of it.
	 *
	 * @param silent whether the errand's start and end lines are skipped.
	 * The skilling company sends the follower to kneel beside a player
	 * praying at an altar, and that is company, not an errand worth a remark
	 */
	public void force(String key, boolean silent)
	{
		forceKey = key == null || key.isEmpty() ? "*" : key.toLowerCase(java.util.Locale.ROOT);
		forceSilently = silent;
	}

	/** Whether the forced errand waiting to run keeps its lines to itself. */
	private boolean forceSilently;

	// public:end skilling
	/**
	 * Told the key of a forced errand that could not start where the follower
	 * is. The command that asked for it promised a run, so the player hears
	 * why there was none; a silent force tells nobody.
	 */
	private Consumer<String> onForcedMiss = key -> { };

	public void setOnForcedMiss(Consumer<String> onForcedMiss)
	{
		this.onForcedMiss = onForcedMiss;
	}

	/**
	 * Whether a work session is on. Errands do not start during one; the
	 * exception is the errand the session itself asks for through
	 * {@link #force(String, boolean)}, which is company rather than a
	 * wander, so only the unforced starts read this.
	 */
	private BooleanSupplier session = () -> false;

	public void setSession(BooleanSupplier session)
	{
		this.session = session;
	}

	// public:cut skilling
	/** Whether the errand under way says nothing at either end. */
	private boolean silent;

	// public:end skilling
	/** The errand's start or end, unless this run was asked to keep quiet. */
	private void announce(TriggerEvent event)
	{
		if (!silent) // public:cut skilling
		// public:else dispatch.accept(event);
		// public:cut skilling
		{
			dispatch.accept(event);
		}
		// public:end skilling
	}

	private final Hands hands;

	/** Every roll the errands make: the schedule, the order, the details. */
	private final Random random;

	public ErrandController(Client client, FollowerEntity follower, FollowerConfig config,
		Consumer<TriggerEvent> dispatch, com.follower.appearance.SpotAnimRepository spotAnims,
		BooleanSupplier busy, Hands hands)
	{
		this(client, follower, config, dispatch, spotAnims, busy, hands, new Random());
	}

	/** As above, with the rolls seeded, so a long simulation plays out the same every run. */
	ErrandController(Client client, FollowerEntity follower, FollowerConfig config,
		Consumer<TriggerEvent> dispatch, com.follower.appearance.SpotAnimRepository spotAnims,
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
	public void reset()
	{
		putTheScrollAway();
		state = State.IDLE;
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
	public void tick()
	{
		if (!config.errandsEnabled())
		{
			if (state != State.IDLE)
			{
				abort();
			}
			// One asked for before errands were switched off is not owed
			// whenever they next come back on.
			forceKey = null;
			forceSilently = false; // public:cut skilling
			return;
		}

		if (arrivalCooldownTicks > 0)
		{
			arrivalCooldownTicks--;
		}

		if (state == State.IDLE)
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

		if (state == State.TRAVELING)
		{
			WorldPoint at = follower.getWorldLocation();
			// Fully settled, not merely close: an animation started while the
			// last steps play out is cancelled by the movement system.
			boolean arrived = at != null && targetTile != null
				&& at.distanceTo(targetTile) <= 2 && follower.isSettled();
			if (arrived || --waitTicks <= 0)
			{
				beginDoing();
			}
		}
		else if (state == State.DOING)
		{
			// The study looks BEFORE it writes: a few ticks facing the thing,
			// and only then the scroll. Reversing that order reads as a scribe
			// deciding the conclusion before the evidence.
			if (studyLookTicks > 0 && --studyLookTicks == 0)
			{
				takeOutTheScroll();
			}
			if (--waitTicks <= 0)
			{
				finish();
			}
			else if (current == Errand.FIRE && waitTicks % WARM_EVERY_TICKS == 0)
			{
				follower.playAnimation(WARM_ANIMATION);
			}
			else if (current == Errand.ALTAR && waitTicks == 1)
			{
				// Up off the knees a tick before the walk home, which would
				// cancel the get-up on its first frame.
				follower.clearPoseOverride(PRAY_LOOP);
				follower.playAnimation(GET_UP);
			}
		}
		else if (state == State.RETURNING)
		{
			if (--waitTicks <= 0)
			{
				completeReturn();
			}
		}
	}

	// ------------------------------------------------------------------ idle

	private void idleTick()
	{
		if (forceKey != null)
		{
			String key = forceKey;
			forceKey = null;
			// Set before the start, since the start is what announces it.
			// public:cut skilling
			silent = forceSilently;
			forceSilently = false;
			// public:end skilling
			if (clearToStart())
			{
				for (Errand errand : Errand.values())
				{
					if (("*".equals(key) || errand.key.equals(key)) && tryStart(errand))
					{
						return;
					}
				}
			}
			// public:cut skilling
			boolean quiet = silent;
			silent = false;
			// public:end skilling
			log.debug("Forced errand '{}' could not start here", key); // public:cut logs
			if (!quiet) // public:cut skilling
			// public:else onForcedMiss.accept(key);
			// public:cut skilling
			{
				onForcedMiss.accept(key);
			}
			// public:end skilling
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
			Player walker = client.getLocalPlayer();
			WorldPoint here = walker == null ? null : walker.getWorldLocation();
			// A tool kept between trees is still in the hand, and every errand
			// lets go of the hand when it ends. It goes back within the minute,
			// so the look waits for it, and for the bow, rather than being lost
			// to it. A session's own tool is no such pause: that look is lost
			// to the work, as ever.
			if (here == null || !here.equals(lastPlayerTile)
				|| (hands.isHolding() && !session.getAsBoolean()))
			{
				arrivalSettleTicks = ARRIVAL_SETTLE_TICKS;
			}
			else if (--arrivalSettleTicks <= 0)
			{
				arrivalWatched = false;
				if (arrivalCooldownTicks == 0 && config.errandExplore()
					&& safeToStart() && tryStart(Errand.EXPLORE))
				{
					arrivalCooldownTicks = ARRIVAL_COOLDOWN_TICKS;
					// Counts as its turn, or the roll could offer another
					// explore straight after this one.
					lastRun.put(Errand.EXPLORE, client.getTickCount());
					lastPlayerTile = here;
					return;
				}
			}
			lastPlayerTile = here;
		}

		if (--nextRollTicks > 0)
		{
			return;
		}
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
		for (Errand errand : Errand.values())
		{
			if (errand.enabled.test(config) && offAgainCooldown(errand))
			{
				candidates.add(errand);
			}
		}
		orderByStaleness(candidates);

		for (Errand errand : candidates)
		{
			if (tryStart(errand))
			{
				lastRun.put(errand, client.getTickCount());
				return;
			}
		}
		nextRollTicks = RETRY_TICKS;
	}

	private boolean tryStart(Errand errand)
	{
		switch (errand)
		{
			case BANK:
			{
				// Most banks have booth/chest OBJECTS; the Grand Exchange has
				// only banker NPCs (measured by scan - no booth object there).
				WorldPoint bank = findObject(
					name -> SkillingController.BANK_NAMES.test(name) // public:cut skilling
					// public:else name -> "Bank booth".equalsIgnoreCase(name) || "Bank chest".equalsIgnoreCase(name)
						|| name.equals("Grand Exchange booth"));
				if (bank == null)
				{
					bank = findNpc(name -> name.equals("Banker")
						|| name.equals("Grand Exchange Clerk"));
				}
				// Closest approach stops the walk in front of a booth, but one
				// the map calls open floor is walked into, and the feet refuse
				// to stop in that: the tile beside it then, as at a fire.
				return startTravelErrand(errand, bank) || startAdjacentErrand(errand, bank);
			}
			case ALTAR:
				return startTravelErrand(errand, findObject(name -> name.equals("Altar")));
			case FIRE:
			{
				WorldPoint fire = findObject(HotGround::isFire);
				if (fire == null)
				{
					return false;
				}
				if (random.nextInt(100) < FIRE_DEATH_PERCENT)
				{
					// The rare version, in which warming the hands goes
					// catastrophically wrong.
					return startTravelErrand(Errand.FIREDEATH, fire);
				}
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
				if (errand == Errand.BOOTLACE)
				{
					follower.playAnimation(BEND_ANIMATION);
				}
				else
				{
					takeOutTheScroll();
				}
				announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, errand.key));
				state = State.DOING;
				waitTicks = errand == Errand.BOOTLACE ? 6 : 12 + random.nextInt(8);
				log.debug("Errand started: {}", errand.key); // public:cut logs
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
					errand == Errand.STUDY ? STUDY_TARGETS : EXPLORE_TARGETS));
				Collections.shuffle(names, random);
				for (String name : names)
				{
					WorldPoint found = findObject(name::equals);
					if (found != null && beginSiteVisit(errand, found, siteKey(errand.key, name)))
					{
						return true;
					}
				}
				return false;
			}
			case GLANCE:
			{
				Player local = client.getLocalPlayer();
				if (local == null || local.getWorldLocation() == null)
				{
					return false;
				}
				WorldPoint p = local.getWorldLocation();
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
		return beginSiteVisit(Errand.STUDY, target, siteKey("study", objectName));
	}

	/** The explore twin of {@link #beginStudyAt}, and a test seam for the same reason. */
	boolean beginExploreAt(WorldPoint target, String objectName)
	{
		return beginSiteVisit(Errand.EXPLORE, target, siteKey("explore", objectName));
	}

	/**
	 * The shared trip to a named thing: beside it, facing it, announced by
	 * name. The walkable targets (fires, cats) come this way too, under
	 * their errand's own key.
	 */
	private boolean beginSiteVisit(Errand errand, WorldPoint target, String siteKey)
	{
		List<WorldPoint> besides = new ArrayList<>(List.of(
			target.dx(1),
			target.dx(-1),
			target.dy(1),
			target.dy(-1)));
		WorldPoint from = follower.getWorldLocation();
		if (from != null)
		{
			besides.sort(java.util.Comparator.comparingInt(p -> p.distanceTo(from)));
		}
		for (WorldPoint beside : besides)
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
				state = State.TRAVELING;
				waitTicks = TRAVEL_TIMEOUT_TICKS;
				// public:cut logs
				log.debug(siteKey.equals(errand.key) ? "Errand started: {} beside {}"
					: "Errand started: {} at {}", currentSiteKey, target);
				// public:end logs
				return true;
			}
		}
		return false;
	}

	/** For walkable targets (fires, cats): aim at a free tile BESIDE them. */
	private boolean startAdjacentErrand(Errand errand, WorldPoint target)
	{
		return target != null && beginSiteVisit(errand, target, errand.key);
	}

	private boolean startTravelErrand(Errand errand, WorldPoint target)
	{
		// The one thing in the plugin that walks INTO a fire on purpose is
		// the gag that does it for the laugh. The feet refuse every other
		// route onto one, so this trip has to say so outright.
		if (target == null || !(errand == Errand.FIREDEATH
			? follower.insistOn(target) : follower.stayAt(target)))
		{
			return false;
		}
		// Once arrived, face the business itself - not the player. For the
		// glance the target IS where it stands, so the facing helper no-ops
		// and the follower keeps staring the way it walked. Also right.
		follower.setStayFaceTile(target);
		current = errand;
		targetTile = target;
		errandSite = target;
		// The doomed fire trip announces itself as an innocent warm-up: the
		// joke needs the audience not to see it coming.
		String announce = errand == Errand.FIREDEATH ? Errand.FIRE.key : errand.key;
		announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, announce));
		state = State.TRAVELING;
		waitTicks = TRAVEL_TIMEOUT_TICKS;
		log.debug("Errand started: {} toward {}", errand.key, target); // public:cut logs
		return true;
	}

	// ---------------------------------------------------------------- active

	private void beginDoing()
	{
		state = State.DOING;
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
				waitTicks = WARM_TICKS;
				break;
			case FIREDEATH:
				// Standing IN the fire now. The pain is immediate and vocal.
				// The disappearance is armed on the death animation's final
				// frame, so the corpse never stands back up first.
				announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, current.key));
				follower.playAnimation(FIRE_DEATH_ANIMATION);
				follower.playSpotAnim(spotAnims.get(FIRE_DEATH_SPOTANIM));
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

	private void finish()
	{
		putTheScrollAway();
		if (current == Errand.FIREDEATH)
		{
			// The vanish already happened on the death animation's last frame
			// (hideAfterEmote); this stage just times the reappearance.
			follower.resumeFollowing();
			state = State.RETURNING;
			waitTicks = 2;
			return;
		}
		boolean walkedAway = current != Errand.BOOTLACE && current != Errand.GLANCE
			&& current != Errand.DOCUMENT;
		if (walkedAway && distanceToPlayer() > RETURN_ON_FOOT_DISTANCE)
		{
			// Too far to jog: a proper teleport home. Cast the spell where it
			// stands, swirl and all, then vanish to the player a beat later.
			follower.playAnimation(TELEPORT_ANIMATION);
			follower.playSpotAnim(spotAnims.get(TELEPORT_CAST_SPOTANIM),
				FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
			// Gone on the cast's last frame. The cast runs 1.58s and the
			// landing is three ticks out at 1.80s, so without this the
			// follower drops back to its idle pose for a fifth of a second
			// mid-teleport - brief, and quite visible. Cleared by
			// teleportToPlayer on arrival.
			follower.hideAfterEmote(1200);
			state = State.RETURNING;
			waitTicks = 3;
			return;
		}
		// Close enough to come back on foot: releasing the pose lets the
		// catch-up pathing bring it home - walking a short gap, running a
		// longer one - which is what a person would do.
		follower.resumeFollowing();
		home();
	}

	private int distanceToPlayer()
	{
		WorldPoint at = follower.getWorldLocation();
		WorldPoint pt = FollowerEntity.playerTile(client);
		// Another floor is infinitely far, as distanceTo has it.
		return at != null && pt != null ? at.distanceTo(pt) : Integer.MAX_VALUE;
	}

	/** Stage two of the teleport home: land beside the player, swirl in, report. */
	private void completeReturn()
	{
		follower.resumeFollowing();
		follower.teleportToPlayer();
		follower.playAnimation(TELEPORT_ARRIVE_ANIMATION);
		// Raised to mid-body: at ground height the swirl sat in the ankles.
		follower.playSpotAnim(spotAnims.get(TELEPORT_REVERSE_SPOTANIM),
			FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
		home();
	}

	/**
	 * Back with the player, on foot or by teleport: the end is reported as
	 * what the errand announced itself as - a study or explore names the
	 * thing itself - and the slate is cleared for the next one.
	 */
	private void home()
	{
		String key = (current == Errand.STUDY || current == Errand.EXPLORE) && currentSiteKey != null
			? currentSiteKey : current.key;
		announce(TriggerEvent.errand(TriggerEvent.Type.ERRAND_END, key));
		log.debug("Errand finished: {}", key); // public:cut logs
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
	private void takeOutTheScroll()
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
	private void putTheScrollAway()
	{
		studyLookTicks = 0;
		if (current == Errand.ALTAR)
		{
			follower.clearPoseOverride(PRAY_LOOP);
		}
		if (current == Errand.DOCUMENT || current == Errand.STUDY
			|| current == Errand.EXPLORE)
		{
			follower.setPoseOverride(0);
		}
		hands.release();
	}

	private void abort()
	{
		putTheScrollAway();
		follower.resumeFollowing();
		Player local = client.getLocalPlayer();
		WorldPoint at = follower.getWorldLocation();
		// A death interrupted mid-vanish must not stay invisible; the
		// teleport home also clears any staged hide.
		boolean mustClearHide = current == Errand.FIREDEATH || state == State.RETURNING;
		if (mustClearHide
			|| (local != null && local.getWorldLocation() != null && at != null
				&& at.distanceTo(local.getWorldLocation()) > 8))
		{
			follower.teleportToPlayer();
		}
		log.debug("Errand aborted: {}", current); // public:cut logs
		clearErrand();
	}

	private void clearErrand()
	{
		state = State.IDLE;
		current = null;
		currentSiteKey = null;
		silent = false; // public:cut skilling
		targetTile = null;
		errandSite = null;
		nextRollTicks = scheduleTicks();
	}

	// ------------------------------------------------------------ conditions

	/** An errand of the follower's own choosing: not during a session. */
	private boolean safeToStart()
	{
		return clearToStart() && !session.getAsBoolean();
	}

	/** What any errand needs before it sets off, forced or not: free feet as well. */
	private boolean clearToStart()
	{
		return quiet(client.getLocalPlayer()) && !follower.isStaying();
	}

	/**
	 * What an errand needs throughout: the player about, not fighting or
	 * talking, and out of the Wilderness; the follower out and not wanted.
	 */
	private boolean quiet(Player local)
	{
		return local != null
			&& local.getWorldLocation() != null
			&& follower.isSpawned()
			&& !busy.getAsBoolean()
			&& local.getInteracting() == null
			&& client.getVarbitValue(WILDERNESS_VARBIT) != 1;
	}

	private boolean safeToContinue()
	{
		Player local = client.getLocalPlayer();
		// The player leaving - walked off, teleported, changed plane (which
		// distanceTo counts as infinitely far) - ends it.
		return quiet(local) && errandSite != null
			&& local.getWorldLocation().distanceTo(errandSite) <= 16;
	}

	private static final int WILDERNESS_VARBIT = 5963;

	// -------------------------------------------------------------- scanning

	// public:cut dev
	/** Measurement aid: logs every distinct object and NPC name in scan range. */
	public void debugScan()
	{
		java.util.Set<String> names = new java.util.TreeSet<>();
		findObject(name ->
		{
			names.add(name);
			return false;
		});
		log.info("Errand scan, objects in range: {}", names); // public:cut logs
		java.util.Set<String> npcs = new java.util.TreeSet<>();
		findNpc(name ->
		{
			npcs.add(name);
			return false;
		});
		log.info("Errand scan, npcs in range: {}", npcs); // public:cut logs
	}

	// public:end dev
	/** The nearest matching scene object's tile within the search radius, or null. */
	private WorldPoint findObject(java.util.function.Predicate<String> nameMatch)
	{
		Player local = client.getLocalPlayer();
		LocalPoint lp = local == null ? null : local.getLocalLocation();
		// public:cut skilling
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		// Between worlds, or mid-login, there is no scene to search. The
		// skilling company's scan does the rest: the square round where the
		// player is drawn, the nearest measured from where the server has them.
		return lp == null || local.getWorldLocation() == null || view == null ? null
			: SkillingController.nearestObject(client,
				WorldPoint.fromScene(view, lp.getSceneX(), lp.getSceneY(), view.getPlane()),
				local.getWorldLocation(), SEARCH_RADIUS, name -> name != null && nameMatch.test(name),
				null, null);
		// public:end skilling
		// public:else WorldPoint centre = local == null ? null : local.getWorldLocation();
		// public:else if (lp == null || centre == null)
		// public:else {
			// public:else return null;
		// public:else }
		// public:else net.runelite.api.WorldView view = client.getTopLevelWorldView();
		// public:else if (view == null)
		// public:else {
			// public:else // Between worlds, or mid-login: there is no scene to search.
			// public:else return null;
		// public:else }
		// public:else net.runelite.api.Scene scene = view.getScene();
		// public:else net.runelite.api.Tile[][][] tiles = scene.getTiles();
		// public:else int plane = view.getPlane();
		// public:else int sx = lp.getSceneX();
		// public:else int sy = lp.getSceneY();
		// public:else
		// public:else WorldPoint best = null;
		// public:else int bestDistance = Integer.MAX_VALUE;
		// public:else for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++)
		// public:else {
			// public:else for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++)
			// public:else {
				// public:else int x = sx + dx;
				// public:else int y = sy + dy;
				// public:else if (x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length)
				// public:else {
					// public:else continue;
				// public:else }
				// public:else net.runelite.api.Tile tile = tiles[plane][x][y];
				// public:else if (tile == null)
				// public:else {
					// public:else continue;
				// public:else }
				// public:else for (net.runelite.api.GameObject object : tile.getGameObjects())
				// public:else {
					// public:else if (object == null)
					// public:else {
						// public:else continue;
					// public:else }
					// public:else String name = objectName(client, object.getId());
					// public:else if (name == null || !nameMatch.test(name))
					// public:else {
						// public:else continue;
					// public:else }
					// public:else WorldPoint where = object.getWorldLocation();
					// public:else int distance = where.distanceTo(centre);
					// public:else if (distance < bestDistance)
					// public:else {
						// public:else bestDistance = distance;
						// public:else best = where;
					// public:else }
				// public:else }
			// public:else }
		// public:else }
		// public:else return best;
	}

	// public:end errands
	/**
	 * An object's real name, resolving multilocs.
	 *
	 * <p>Public and static because the examine echo needs exactly this and had
	 * its own naive version: most scenery is a multiloc whose base definition
	 * is called "null" until its impostor is asked, so reading getName() alone
	 * silently answers nothing for a large share of the world.
	 */
	public static String objectName(Client client, int objectId)
	{
		ObjectComposition comp = client.getObjectDefinition(objectId);
		if (comp == null)
		{
			return null;
		}
		String name = comp.getName();
		if ((name == null || name.equals("null")) && comp.getImpostorIds() != null)
		{
			try
			{
				ObjectComposition impostor = comp.getImpostor();
				name = impostor == null ? null : impostor.getName();
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}
		return name == null || name.equals("null") ? null : name;
	}
	// public:cut errands

	/** The nearest matching NPC's tile within the search radius, or null. */
	private WorldPoint findNpc(java.util.function.Predicate<String> nameMatch)
	{
		// public:cut skilling
		NPC npc = SkillingController.nearestNpc(client, FollowerEntity.playerTile(client),
			SEARCH_RADIUS, nameMatch);
		return npc == null ? null : npc.getWorldLocation();
		// public:end skilling
		// public:else WorldPoint centre = FollowerEntity.playerTile(client);
		// public:else if (centre == null)
		// public:else {
			// public:else return null;
		// public:else }
		// public:else net.runelite.api.WorldView view = client.getTopLevelWorldView();
		// public:else if (view == null)
		// public:else {
			// public:else return null;
		// public:else }
		// public:else WorldPoint best = null;
		// public:else int bestDistance = Integer.MAX_VALUE;
		// public:else for (NPC npc : view.npcs())
		// public:else {
			// public:else if (npc == null || npc.getName() == null || !nameMatch.test(npc.getName()))
			// public:else {
				// public:else continue;
			// public:else }
			// public:else WorldPoint where = npc.getWorldLocation();
			// public:else if (where == null || where.getPlane() != centre.getPlane())
			// public:else {
				// public:else continue;
			// public:else }
			// public:else int distance = where.distanceTo(centre);
			// public:else if (distance <= SEARCH_RADIUS && distance < bestDistance)
			// public:else {
				// public:else bestDistance = distance;
				// public:else best = where;
			// public:else }
		// public:else }
		// public:else return best;
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
	private final java.util.Map<Errand, Integer> lastRun = new java.util.EnumMap<>(Errand.class);

	/**
	 * How long an errand waits before it may come round again, as a multiple
	 * of the ordinary gap between errands. Scaled rather than fixed so the
	 * frequency setting keeps its meaning: whatever pace was chosen, an
	 * errand does not repeat until roughly three others could have happened.
	 */
	private static final int REPEAT_GAPS = 3;

	/** Whether this errand has rested long enough to be offered again. */
	private boolean offAgainCooldown(Errand errand)
	{
		Integer last = lastRun.get(errand);
		return last == null
			|| client.getTickCount() - last >= baseScheduleTicks() * REPEAT_GAPS;
	}

	/**
	 * Puts the least recently run errands first, so a roll offers variety
	 * before it offers convenience. Ties are shuffled, so equally stale
	 * errands do not fall into a fixed order.
	 */
	private void orderByStaleness(List<Errand> candidates)
	{
		Collections.shuffle(candidates, random);
		candidates.sort(java.util.Comparator.comparingInt(
			errand -> lastRun.getOrDefault(errand, Integer.MIN_VALUE)));
	}

	private int scheduleTicks()
	{
		// 60%..140% of the base, so errands never feel metronomic.
		return (int) (baseScheduleTicks()
			* (0.6 + random.nextDouble() * 0.8));
	}

	private int baseScheduleTicks()
	{
		return config.errandFrequency().ticks;
	}
	// public:end errands
}
