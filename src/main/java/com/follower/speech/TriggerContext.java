package com.follower.speech;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * A snapshot of game state, refreshed once per game tick and shared by every
 * condition evaluated that tick.
 */
@lombok.extern.slf4j.Slf4j
public final class TriggerContext
{
	/** Prayer counts as "active" if points have dropped within this many ticks. */
	private static final int PRAYER_DRAIN_MEMORY_TICKS = 5;

	private final Client client;

	private int hitpoints;
	private int maxHitpoints;
	private int prayerPoints;
	private int maxPrayerPoints;
	private int regionId = -1;
	private int previousRegionId = -1;
	private int idleTicks;
	private int ticksSincePrayerDrain = Integer.MAX_VALUE;
	private int lastPrayerPoints = -1;
	private WorldPoint location;
	private Set<Integer> loadedRegions = new HashSet<>();
	private Set<Integer> equippedItems = new HashSet<>();

	public TriggerContext(Client client)
	{
		this.client = client;
	}

	/**
	 * Bumped every {@link #refresh()}: per-tick memoisation key for expensive
	 * state conditions (npcNearby caches its scan against it).
	 */
	@lombok.Getter
	private int refreshGeneration;

	public void refresh()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		refreshGeneration++;

		hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);
		maxHitpoints = client.getRealSkillLevel(Skill.HITPOINTS);

		prayerPoints = client.getBoostedSkillLevel(Skill.PRAYER);
		maxPrayerPoints = client.getRealSkillLevel(Skill.PRAYER);

		// getEnergy counts hundredths of a percent, 0-10000 - the same scale
		// RuneLite's own run-energy plugin measures against.
		//
		// This used to divide only when the reading was above 100, meaning to
		// tolerate an older 0-100 API. That guard was backwards where it
		// mattered: 100 hundredths is one percent left, and it was reported as
		// a hundred. Every reading under one percent - exactly the moment a
		// low-energy line is worth saying - read as nearly full.
		energyPercent = client.getEnergy() / 100;

		skulled = local.getSkullIcon() != -1;

		if (lastPrayerPoints >= 0 && prayerPoints < lastPrayerPoints)
		{
			ticksSincePrayerDrain = 0;
		}
		else if (ticksSincePrayerDrain < Integer.MAX_VALUE)
		{
			ticksSincePrayerDrain++;
		}
		lastPrayerPoints = prayerPoints;

		WorldPoint previousLocation = location;
		location = local.getWorldLocation();
		if (location != null)
		{
			int newRegion = location.getRegionID();
			if (newRegion != regionId)
			{
				previousRegionId = regionId;
				regionId = newRegion;
				noteRegionEntered(newRegion);
			}
			// Seen THIS tick, so pacing along a region boundary - which fires
			// the change over and over - never reads as a string of visits.
			regionLastSeenTick.put(newRegion, client.getTickCount());
		}

		if (previousLocation != null && previousLocation.equals(location) && local.getAnimation() == -1)
		{
			idleTicks++;
		}
		else
		{
			idleTicks = 0;
		}

		refreshCombat(local);

		refreshLoadedRegions();
		refreshEquipment(local.getPlayerComposition());
		refreshRepetition(local);
		driftMood();
	}

	// ------------------------------------------------------------------ combat

	/**
	 * How long a fight is considered to still be going after the last sign of
	 * one. Monsters die, targets are re-clicked, and a boss between phases can
	 * leave a tick or two with no interaction at all; without a grace window
	 * the follower would bob in and out of spectating throughout a kill.
	 */
	private static final int COMBAT_GRACE_TICKS = 8;

	/** A rough line between "a monster" and "a boss", by combat level. */
	private static final int BOSS_COMBAT_LEVEL = 100;

	private int ticksSinceCombat = Integer.MAX_VALUE;
	private String combatTarget = "";
	private int combatTargetLevel;

	/**
	 * Works out whether the player is fighting, from the two things that are
	 * always true of a fight: they are interacting with something that can
	 * fight back, or something is hitting them.
	 *
	 * <p>Interaction alone is not enough - talking to a banker and pickpocketing
	 * a guard are both interactions - so the target has to have a combat level.
	 * Damage is fed in separately by the plugin's hitsplat handler, which
	 * catches being attacked while doing nothing back.
	 */
	private void refreshCombat(Player local)
	{
		Actor target = local.getInteracting();
		if (target instanceof NPC && target.getCombatLevel() > 0
			&& target.getHealthRatio() != 0)
		{
			combatTarget = target.getName() == null ? "" : target.getName();
			combatTargetLevel = target.getCombatLevel();
			ticksSinceCombat = 0;
		}
		else if (ticksSinceCombat < Integer.MAX_VALUE)
		{
			ticksSinceCombat++;
		}
	}

	/** Called by the plugin when the player takes a hit, which is also combat. */
	public void noteDamageTaken()
	{
		ticksSinceCombat = 0;
	}

	/** Whether the player is fighting, or was a moment ago. */
	public boolean isInCombat()
	{
		return ticksSinceCombat <= COMBAT_GRACE_TICKS;
	}

	/** Whether that fight is with something big enough to call a boss. */
	public boolean isBossFight()
	{
		return isInCombat() && combatTargetLevel >= BOSS_COMBAT_LEVEL;
	}

	/** The name of what is being fought, for the {npc} placeholder. */
	public String getCombatTarget()
	{
		return combatTarget;
	}

	public int getCombatTargetLevel()
	{
		return combatTargetLevel;
	}

	// ---------------------------------------------------------------- tallies

	/**
	 * How many of each thing has happened this session, keyed by a short label.
	 *
	 * <p>Nothing signals that someone is paying attention like a tally. The
	 * follower knowing this is the fiftieth kalphite is a different kind of
	 * remark from anything it can say about one kill, and it costs a map.
	 *
	 * <p>Keyed by string so a new thing to count needs no new field - the
	 * caller decides the label, the same way rules decide what moves the mood.
	 */
	private final java.util.Map<String, Integer> tallies = new java.util.HashMap<>();

	/** Counts one, and returns the new total. */
	public int tally(String what)
	{
		return tallies.merge(what, 1, Integer::sum);
	}

	public int getTally(String what)
	{
		return tallies.getOrDefault(what, 0);
	}

	// -------------------------------------------------------------- repeating

	/**
	 * How long the player has been doing the same thing, in ticks.
	 *
	 * <p>Repetition is the texture of the game, and a companion that never
	 * notices it is not watching. This needs to know nothing about trees or
	 * rocks or fish: an animation repeating for minutes IS the activity, so
	 * counting the ticks it has been running covers every skill at once,
	 * including ones added later.
	 *
	 * <p>Brief gaps are tolerated. Most gathering drops to no animation for a
	 * tick between swings, and treating that as stopping would keep the count
	 * at zero forever.
	 */
	private static final int REPEAT_GAP_GRACE_TICKS = 4;

	private int repeatingAnimation = -1;
	private int repeatingTicks;
	private int ticksSinceAnimation;

	private void refreshRepetition(Player local)
	{
		int animation = local.getAnimation();

		if (animation == -1)
		{
			// A pause, not necessarily a stop. Hold the count for a moment.
			if (repeatingAnimation != -1 && ++ticksSinceAnimation > REPEAT_GAP_GRACE_TICKS)
			{
				repeatingAnimation = -1;
				repeatingTicks = 0;
			}
			return;
		}

		ticksSinceAnimation = 0;
		if (animation == repeatingAnimation)
		{
			repeatingTicks++;
		}
		else
		{
			repeatingAnimation = animation;
			repeatingTicks = 1;
		}
	}

	/** The animation the player has been repeating, or -1. */
	public int getRepeatingAnimation()
	{
		return repeatingAnimation;
	}

	/** How many ticks that animation has been going, gaps included. */
	public int getRepeatingTicks()
	{
		return repeatingTicks;
	}

	// ------------------------------------------------------------------- mood

	/**
	 * How the follower is feeling, 0 (flat) to 100 (delighted), starting even.
	 *
	 * <p>One number rather than several axes, because the point is not to model
	 * an emotion - it is to give the lines already written a STATE to be said
	 * from. A rule that only fires when the follower is low turns the same
	 * event into a different moment depending on how the session has gone,
	 * which is most of what makes a companion feel like it is present rather
	 * than reacting.
	 *
	 * <p>Nothing here decides what moves it. Rules do, by carrying a {@code
	 * mood} nudge, which means every trigger the engine already understands can
	 * affect it and a new influence is an edit to phrases.json rather than to
	 * this file.
	 *
	 * <p>Session-scoped on purpose. A follower that remembered last night's bad
	 * run would be strange, and a fresh start each login is also the honest
	 * default for something that cannot see what you did while it was gone.
	 */
	private static final int MOOD_NEUTRAL = 50;
	private static final int MOOD_MIN = 0;
	private static final int MOOD_MAX = 100;

	/** Ticks between one point of drift back toward neutral: about 30 seconds. */
	private static final int MOOD_DRIFT_TICKS = 50;

	private int mood = MOOD_NEUTRAL;
	private int ticksSinceMoodDrift;

	/** The mood right now, 0..100. */
	public int getMood()
	{
		return mood;
	}

	/**
	 * Nudges the mood and clamps it. Called when a rule carrying a nudge fires.
	 *
	 * @return the mood after the nudge
	 */
	public int adjustMood(int delta)
	{
		int before = mood;
		mood = Math.max(MOOD_MIN, Math.min(MOOD_MAX, mood + delta));
		if (mood != before)
		{
			log.debug("mood {} -> {} ({}{})", before, mood, delta > 0 ? "+" : "", delta);
		}
		return mood;
	}

	/**
	 * The band the mood falls in, which is what rules should normally ask for -
	 * a name reads better in a rule file than a number, and the boundaries can
	 * move without every rule having to.
	 */
	public String getMoodBand()
	{
		if (mood <= 20)
		{
			return "low";
		}
		if (mood <= 40)
		{
			return "down";
		}
		if (mood < 60)
		{
			return "even";
		}
		if (mood < 80)
		{
			return "good";
		}
		return "high";
	}

	/** Every band name, so a rule naming one that does not exist can be caught. */
	public static java.util.Set<String> moodBands()
	{
		return new java.util.LinkedHashSet<>(
			java.util.Arrays.asList("low", "down", "even", "good", "high"));
	}

	/**
	 * Pulls the mood back toward neutral a point at a time, so nothing that
	 * happens is permanent and a bad stretch is recovered from by carrying on.
	 */
	private void driftMood()
	{
		if (mood == MOOD_NEUTRAL)
		{
			ticksSinceMoodDrift = 0;
			return;
		}
		if (++ticksSinceMoodDrift < MOOD_DRIFT_TICKS)
		{
			return;
		}
		ticksSinceMoodDrift = 0;
		mood += mood > MOOD_NEUTRAL ? -1 : 1;
	}

	// ----------------------------------------------------------------- memory

	/**
	 * How long a region has to go unvisited before coming back counts as a
	 * RETURN rather than a wander along its edge. Region boundaries run through
	 * the middle of towns, so without this a trip across Varrock square would
	 * tally half a dozen visits.
	 */
	private static final int REVISIT_GAP_TICKS = 250;

	/** Death-site lines stay quiet this long after the death itself, since the
	 * first person at the spot is the dying player. */
	private static final int DEATH_SPOT_ARM_TICKS = 200;

	private final java.util.Map<Integer, Integer> regionVisits = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> regionLastSeenTick = new java.util.HashMap<>();

	private WorldPoint deathLocation;
	private int deathTick = -1;

	private void noteRegionEntered(int region)
	{
		Integer lastSeen = regionLastSeenTick.get(region);
		if (lastSeen == null || client.getTickCount() - lastSeen > REVISIT_GAP_TICKS)
		{
			regionVisits.merge(region, 1, Integer::sum);
		}
	}

	/** Session visits to the CURRENT region, for the returnVisit condition. */
	public int getRegionVisits()
	{
		return regionVisits.getOrDefault(regionId, 0);
	}

	/** Called by the plugin when the player dies. Session memory, on purpose:
	 * a companion remembering last week's death forever would wear thin. */
	public void noteDeath(WorldPoint where)
	{
		deathLocation = where;
		deathTick = client.getTickCount();
	}

	/**
	 * Whether the player is standing near where they last died - and long
	 * enough after the death that being there again is a RETURN, not the
	 * dying itself or the walk back for the gravestone.
	 */
	public boolean isNearDeathSpot(int within)
	{
		return deathLocation != null && location != null
			&& client.getTickCount() - deathTick > DEATH_SPOT_ARM_TICKS
			&& location.getPlane() == deathLocation.getPlane()
			&& location.distanceTo(deathLocation) <= within;
	}

	/**
	 * Worn item ids taken from the player's composition. Note this covers only
	 * slots with a visible model: rings and ammo never appear here.
	 */
	/**
	 * Whether a refresh has actually seen the local player's composition yet.
	 * False for the first ticks after login, while the player entity is still
	 * materialising - state read before this is empty, not truth.
	 */
	@lombok.Getter
	private boolean playerReady;

	/** Run energy as a whole percentage, 0-100. */
	@lombok.Getter
	private int energyPercent = 100;

	@lombok.Getter
	private boolean skulled;

	/** The raw arrays behind the two sets, kept so an unchanged tick costs nothing. */
	private int[] lastRegionIds;
	private int[] lastEquipmentIds;

	/**
	 * The loaded map regions, rebuilt only when they actually change.
	 *
	 * <p>They change on a chunk load and are identical on every other tick, so
	 * boxing the array into a fresh set each time was allocating for no reason
	 * several times a second for the whole session.
	 */
	private void refreshLoadedRegions()
	{
		int[] regions = client.getTopLevelWorldView().getMapRegions();
		if (Arrays.equals(regions, lastRegionIds))
		{
			return;
		}
		lastRegionIds = regions == null ? null : regions.clone();

		Set<Integer> rebuilt = new HashSet<>();
		if (regions != null)
		{
			for (int region : regions)
			{
				rebuilt.add(region);
			}
		}
		loadedRegions = rebuilt;
	}

	private void refreshEquipment(PlayerComposition composition)
	{
		playerReady = composition != null;
		int[] ids = composition == null ? null : composition.getEquipmentIds();

		// Same reasoning as the regions: worn gear is the same array tick after
		// tick, and the old code rebuilt a set and compared it just to decide
		// whether to log.
		if (Arrays.equals(ids, lastEquipmentIds))
		{
			return;
		}
		lastEquipmentIds = ids == null ? null : ids.clone();

		Set<Integer> worn = new HashSet<>();
		if (ids != null)
		{
			for (int raw : ids)
			{
				// Items start at 2048; 256..2047 is the kit range. Using 512 here
				// meant every body kit was reported as a worn item, so an
				// equippedItem rule could fire on a hairstyle.
				if (raw >= PlayerComposition.ITEM_OFFSET)
				{
					worn.add(raw - PlayerComposition.ITEM_OFFSET);
				}
			}
		}
		equippedItems = worn;
		log.debug("equipment now {}", worn);
	}

	public Client getClient()
	{
		return client;
	}

	public int getHitpoints()
	{
		return hitpoints;
	}

	public int getMaxHitpoints()
	{
		return maxHitpoints;
	}

	public int getHitpointsPercent()
	{
		return maxHitpoints <= 0 ? 100 : (hitpoints * 100) / maxHitpoints;
	}

	public int getPrayerPoints()
	{
		return prayerPoints;
	}

	public int getMaxPrayerPoints()
	{
		return maxPrayerPoints;
	}

	public int getPrayerPercent()
	{
		return maxPrayerPoints <= 0 ? 100 : (prayerPoints * 100) / maxPrayerPoints;
	}

	public boolean isPrayerActive()
	{
		return ticksSincePrayerDrain <= PRAYER_DRAIN_MEMORY_TICKS;
	}

	public int getRegionId()
	{
		return regionId;
	}

	public int getPreviousRegionId()
	{
		return previousRegionId;
	}

	public int getIdleTicks()
	{
		return idleTicks;
	}

	public WorldPoint getLocation()
	{
		return location;
	}

	public boolean isRegionLoaded(int region)
	{
		return loadedRegions.contains(region);
	}

	public boolean isEquipped(int itemId)
	{
		return equippedItems.contains(itemId);
	}

	public boolean isNpcNearby(java.util.function.Predicate<NPC> predicate, int within)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !predicate.test(npc))
			{
				continue;
			}
			WorldPoint npcLocation = npc.getWorldLocation();
			if (location == null || npcLocation == null)
			{
				continue;
			}
			if (npcLocation.distanceTo(location) <= within)
			{
				return true;
			}
		}
		return false;
	}

	public String getPlayerName()
	{
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "you" : local.getName();
	}
}
