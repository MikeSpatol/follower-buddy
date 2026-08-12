package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

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
@Slf4j
public class ErrandController
{
	/** The errand types; names match the rule {@code names} lists. */
	private enum Errand
	{
		BANK("bank"),
		ALTAR("altar"),
		FIRE("fire"),
		/** The 10% fire variant: walks INTO it. Announces itself as FIRE. */
		FIREDEATH("firedeath"),
		CAT("cat"),
		BOOTLACE("bootlace"),
		GLANCE("glance"),
		/** Stops where it stands and writes something up in its scroll. */
		DOCUMENT("document");

		final String key;

		Errand(String key)
		{
			this.key = key;
		}
	}

	private enum State
	{
		IDLE, TRAVELING, DOING, RETURNING
	}

	/**
	 * Reading a scroll: harvested live with {@code sniffanims} and verified as
	 * a pose on the follower, 2026-08-12. Loops, so it is held as a pose
	 * override rather than played as a one-shot.
	 */
	private static final int READ_SCROLL_POSE = 5354;

	/**
	 * The plain Scroll, weapon slot. The animation mimes the scroll and draws
	 * nothing - the item in hand comes from equipment - and of the sixty-five
	 * hand-slot candidates this is the one that sits between the hands with no
	 * vertex nudge at all. Verified with the pose above, same session.
	 */
	private static final int SCROLL_PROP = 10485;

	/** Ticks between taking the prop and starting the pose: the model rebuild
	 * is asynchronous, and a scroll popping in mid-read gives the trick away. */
	private static final int PROP_SETTLE_TICKS = 2;

	/**
	 * What holds the prop. Implemented by the plugin, which owns the follower's
	 * composed appearance; the errand only says when the scroll comes out and
	 * when it goes away. Release must be idempotent - it is also called from
	 * the abort and reset paths, where nothing may be held.
	 */
	public interface Hands
	{
		void hold(int itemId);

		void release();
	}

	/** Praying at an altar - the classic kneel. */
	private static final int PRAY_ANIMATION = 645;

	/** Bending down: petting the cat, seeing to the bootlace. */
	private static final int BEND_ANIMATION = 827;

	/**
	 * AnimationID.HUMAN_FIRECOOKING: kneeling at the flames, hands toward
	 * them. The cache has no dedicated warming animation; this reads right.
	 * (FORESTRY_CAMPFIRE 10082 turned out to be the campfire's own animation.)
	 */
	private static final int WARM_ANIMATION = 897;

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

	/** Counting down to the scroll pose, so the prop's rebuild lands first. */
	private int posePendingTicks;

	/** Test hook: run an errand now - a specific one by name, or any ("*"). */
	public void force(String key)
	{
		forceKey = key == null || key.isEmpty() ? "*" : key.toLowerCase(java.util.Locale.ROOT);
	}

	private final Hands hands;

	public ErrandController(Client client, FollowerEntity follower, FollowerConfig config,
		Consumer<TriggerEvent> dispatch, com.follower.appearance.SpotAnimRepository spotAnims,
		BooleanSupplier busy, Hands hands)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.dispatch = dispatch;
		this.spotAnims = spotAnims;
		this.busy = busy;
		this.hands = hands;
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
			return;
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
			// The scroll pose starts a beat after the prop, so the model
			// rebuild has landed and the scroll is in hand from frame one.
			if (posePendingTicks > 0 && --posePendingTicks == 0)
			{
				follower.setPoseOverride(READ_SCROLL_POSE);
			}
			if (--waitTicks <= 0)
			{
				finish();
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
			if (safeToStart())
			{
				for (Errand errand : Errand.values())
				{
					if (("*".equals(key) || errand.key.equals(key)) && tryStart(errand))
					{
						return;
					}
				}
			}
			log.debug("Forced errand '{}' could not start here", key);
			return;
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

		List<Errand> candidates = new ArrayList<>();
		if (config.errandBank())
		{
			candidates.add(Errand.BANK);
		}
		if (config.errandAltar())
		{
			candidates.add(Errand.ALTAR);
		}
		if (config.errandFire())
		{
			candidates.add(Errand.FIRE);
		}
		if (config.errandCat())
		{
			candidates.add(Errand.CAT);
		}
		if (config.errandBootlace())
		{
			candidates.add(Errand.BOOTLACE);
		}
		if (config.errandGlance())
		{
			candidates.add(Errand.GLANCE);
		}
		if (config.errandDocument())
		{
			candidates.add(Errand.DOCUMENT);
		}
		Collections.shuffle(candidates);

		for (Errand errand : candidates)
		{
			if (tryStart(errand))
			{
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
					name -> name.equals("Bank booth") || name.equals("Bank chest")
						|| name.equals("Grand Exchange booth"));
				if (bank == null)
				{
					bank = findNpc(name -> name.equals("Banker")
						|| name.equals("Grand Exchange Clerk"));
				}
				return startTravelErrand(errand, bank);
			}
			case ALTAR:
				return startTravelErrand(errand, findObject(name -> name.equals("Altar")));
			case FIRE:
			{
				WorldPoint fire = findObject(
					name -> name.equals("Fire") || name.equals("Campfire")
						|| name.equals("Forester's Campfire"));
				if (fire == null)
				{
					return false;
				}
				if (ThreadLocalRandom.current().nextInt(100) < FIRE_DEATH_PERCENT)
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
				return startTravelErrand(errand, findObject(
					name -> name.equals("Fire") || name.equals("Campfire")
						|| name.equals("Forester's Campfire")));
			case CAT:
				// NPCs don't block either; standing ON the cat ruins the moment.
				return startAdjacentErrand(errand, findNpc(
					name -> name.equals("Cat") || name.equals("Kitten") || name.equals("Stray dog")));
			case BOOTLACE:
			{
				// No trip at all: stop where it stands, see to the boot.
				current = errand;
				errandSite = follower.getWorldLocation();
				follower.stayHere();
				follower.playAnimation(BEND_ANIMATION);
				dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, errand.key));
				state = State.DOING;
				waitTicks = 6;
				log.debug("Errand started: {}", errand.key);
				return true;
			}
			case DOCUMENT:
			{
				// No trip: something here is worth the record, apparently. The
				// scroll comes out first and the pose follows once the rebuilt
				// model lands; the read runs long enough to look deliberate.
				current = errand;
				errandSite = follower.getWorldLocation();
				follower.stayHere();
				hands.hold(SCROLL_PROP);
				dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, errand.key));
				state = State.DOING;
				posePendingTicks = PROP_SETTLE_TICKS;
				waitTicks = 12 + ThreadLocalRandom.current().nextInt(8);
				log.debug("Errand started: {}", errand.key);
				return true;
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
				int[] dir = dirs[ThreadLocalRandom.current().nextInt(dirs.length)];
				return startTravelErrand(errand,
					new WorldPoint(p.getX() + dir[0], p.getY() + dir[1], p.getPlane()));
			}
			default:
				return false;
		}
	}

	/** For walkable targets (fires, cats): aim at a free tile BESIDE them. */
	private boolean startAdjacentErrand(Errand errand, WorldPoint target)
	{
		if (target == null)
		{
			return false;
		}
		List<WorldPoint> besides = new ArrayList<>();
		besides.add(new WorldPoint(target.getX() + 1, target.getY(), target.getPlane()));
		besides.add(new WorldPoint(target.getX() - 1, target.getY(), target.getPlane()));
		besides.add(new WorldPoint(target.getX(), target.getY() + 1, target.getPlane()));
		besides.add(new WorldPoint(target.getX(), target.getY() - 1, target.getPlane()));
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
				targetTile = beside;
				errandSite = target;
				dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, errand.key));
				state = State.TRAVELING;
				waitTicks = TRAVEL_TIMEOUT_TICKS;
				log.debug("Errand started: {} beside {}", errand.key, target);
				return true;
			}
		}
		return false;
	}

	private boolean startTravelErrand(Errand errand, WorldPoint target)
	{
		if (target == null || !follower.stayAt(target))
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
		dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, announce));
		state = State.TRAVELING;
		waitTicks = TRAVEL_TIMEOUT_TICKS;
		log.debug("Errand started: {} toward {}", errand.key, target);
		return true;
	}

	// ---------------------------------------------------------------- active

	private void beginDoing()
	{
		state = State.DOING;
		switch (current)
		{
			case ALTAR:
				follower.playAnimation(PRAY_ANIMATION);
				waitTicks = 8;
				break;
			case FIRE:
				follower.playAnimation(WARM_ANIMATION);
				waitTicks = 10;
				break;
			case FIREDEATH:
				// Standing IN the fire now. The pain is immediate and vocal.
				// The disappearance is armed on the death animation's final
				// frame, so the corpse never stands back up first.
				dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_START, current.key));
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
			follower.playSpotAnim(spotAnims.get(TELEPORT_CAST_SPOTANIM));
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
		Errand done = current;
		follower.resumeFollowing();
		dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_END, done.key));
		log.debug("Errand finished: {}", done.key);
		clearErrand();
	}

	private int distanceToPlayer()
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = follower.getWorldLocation();
		WorldPoint pt = local == null ? null : local.getWorldLocation();
		return at != null && pt != null && at.getPlane() == pt.getPlane()
			? at.distanceTo(pt) : Integer.MAX_VALUE;
	}

	/** Stage two of the teleport home: land beside the player, swirl in, report. */
	private void completeReturn()
	{
		Errand done = current;
		follower.resumeFollowing();
		follower.teleportToPlayer();
		follower.playAnimation(TELEPORT_ARRIVE_ANIMATION);
		// Raised to mid-body: at ground height the swirl sat in the ankles.
		follower.playSpotAnim(spotAnims.get(TELEPORT_REVERSE_SPOTANIM), 92);
		dispatch.accept(TriggerEvent.errand(TriggerEvent.Type.ERRAND_END, done.key));
		log.debug("Errand finished: {}", done.key);
		clearErrand();
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
		posePendingTicks = 0;
		if (current == Errand.DOCUMENT)
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
		log.debug("Errand aborted: {}", current);
		clearErrand();
	}

	private void clearErrand()
	{
		state = State.IDLE;
		current = null;
		targetTile = null;
		errandSite = null;
		nextRollTicks = scheduleTicks();
	}

	// ------------------------------------------------------------ conditions

	private boolean safeToStart()
	{
		Player local = client.getLocalPlayer();
		return local != null
			&& local.getWorldLocation() != null
			&& follower.isSpawned()
			&& !follower.isStaying()
			&& !busy.getAsBoolean()
			&& local.getInteracting() == null
			&& client.getVarbitValue(WILDERNESS_VARBIT) != 1;
	}

	private boolean safeToContinue()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null || !follower.isSpawned()
			|| busy.getAsBoolean() || local.getInteracting() != null
			|| client.getVarbitValue(WILDERNESS_VARBIT) == 1)
		{
			return false;
		}
		// The player leaving - walked off, teleported, changed plane - ends it.
		WorldPoint p = local.getWorldLocation();
		return errandSite != null
			&& p.getPlane() == errandSite.getPlane()
			&& p.distanceTo(errandSite) <= 16;
	}

	private static final int WILDERNESS_VARBIT = 5963;

	// -------------------------------------------------------------- scanning

	/** Measurement aid: logs every distinct object and NPC name in scan range. */
	public void debugScan()
	{
		java.util.Set<String> names = new java.util.TreeSet<>();
		findObject(name ->
		{
			names.add(name);
			return false;
		});
		log.info("Errand scan, objects in range: {}", names);
		java.util.Set<String> npcs = new java.util.TreeSet<>();
		findNpc(name ->
		{
			npcs.add(name);
			return false;
		});
		log.info("Errand scan, npcs in range: {}", npcs);
	}

	/** The nearest matching scene object's tile within the search radius, or null. */
	private WorldPoint findObject(java.util.function.Predicate<String> nameMatch)
	{
		Player local = client.getLocalPlayer();
		LocalPoint lp = local == null ? null : local.getLocalLocation();
		WorldPoint centre = local == null ? null : local.getWorldLocation();
		if (lp == null || centre == null)
		{
			return null;
		}
		Scene scene = client.getTopLevelWorldView().getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = client.getTopLevelWorldView().getPlane();
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();

		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++)
		{
			for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++)
			{
				int x = sx + dx;
				int y = sy + dy;
				if (x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length)
				{
					continue;
				}
				Tile tile = tiles[plane][x][y];
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
					String name = objectName(object.getId());
					if (name == null || !nameMatch.test(name))
					{
						continue;
					}
					WorldPoint where = object.getWorldLocation();
					int distance = where.distanceTo(centre);
					if (distance < bestDistance)
					{
						bestDistance = distance;
						best = where;
					}
				}
			}
		}
		return best;
	}

	private String objectName(int objectId)
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

	/** The nearest matching NPC's tile within the search radius, or null. */
	private WorldPoint findNpc(java.util.function.Predicate<String> nameMatch)
	{
		Player local = client.getLocalPlayer();
		WorldPoint centre = local == null ? null : local.getWorldLocation();
		if (centre == null)
		{
			return null;
		}
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getName() == null || !nameMatch.test(npc.getName()))
			{
				continue;
			}
			WorldPoint where = npc.getWorldLocation();
			if (where == null || where.getPlane() != centre.getPlane())
			{
				continue;
			}
			int distance = where.distanceTo(centre);
			if (distance <= SEARCH_RADIUS && distance < bestDistance)
			{
				bestDistance = distance;
				best = where;
			}
		}
		return best;
	}

	// ------------------------------------------------------------- schedule

	private int scheduleTicks()
	{
		int base;
		switch (config.errandFrequency())
		{
			case RARE:
				base = 3000;
				break;
			case LIVELY:
				base = 800;
				break;
			case OCCASIONAL:
			default:
				base = 1800;
				break;
		}
		// 60%..140% of the base, so errands never feel metronomic.
		return (int) (base * (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8));
	}
}
