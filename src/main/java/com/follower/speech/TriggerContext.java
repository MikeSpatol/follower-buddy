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

		// Before combat, which asks whether this is thieving before deciding.
		refreshThieving(local);
		refreshCombat(local);

		refreshLoadedRegions();
		refreshEquipment(local.getPlayerComposition());
		refreshRepetition(local);
		ageQuestion();
		checkWant();
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
	// ---------------------------------------------------------------- thieving

	/** HUMAN_PICKPOCKET, 1.78s. The attempt itself. */
	private static final int PICKPOCKET_ANIMATION = 881;

	/**
	 * Every stun the player can be put in for getting it wrong.
	 *
	 * <p>There is not one. Measured from the cache: 1054 STUNNED_THIEVING at
	 * 4.48s, 1874 STUNNED_BLACKJACK at 1.28s, 848 HUMAN_STUNNED at 0.64s, and
	 * a whole family sized to the stun's length - STUNNED_2CYCLE through
	 * 6CYCLE at 1.20s, 1.84s, 2.40s, 3.04s and 3.60s.
	 *
	 * <p>Recognising only STUNNED_THIEVING covered one case in seven, so any
	 * other target or method left the stun unaccounted for and the window
	 * running from the last successful attempt instead. That is the "sometimes"
	 * in "sometimes it comes back too early".
	 */
	private static final Set<Integer> THIEVING_STUNS = new HashSet<>(
		Arrays.asList(1054, 1874, 848, 14422, 14423, 14424, 10087, 10088));

	/**
	 * How long a thieving ANIMATION counts as an attempt in progress.
	 *
	 * <p>Only used to keep the session alive across the gap between attempts;
	 * the session itself is what everything else asks about.
	 */
	private static final int THIEVING_GRACE_TICKS = 20;

	/**
	 * How long the follower keeps its distance while the player stays put.
	 *
	 * <p>Much longer, because the cost is only that the follower stands a few
	 * tiles away. Anchored to WHERE the thieving happened rather than to a
	 * clock alone: it ends the moment the player walks off, which is the signal
	 * a person actually gives when they are done.
	 */
	private static final int THIEVING_SESSION_TICKS = 150;
	private static final int THIEVING_SESSION_RADIUS = 5;

	private int ticksSinceThieving = Integer.MAX_VALUE;
	private WorldPoint thievingSpot;

	private void refreshThieving(Player local)
	{
		int animation = local.getAnimation();
		if (animation == PICKPOCKET_ANIMATION || THIEVING_STUNS.contains(animation))
		{
			ticksSinceThieving = 0;
			thievingSpot = location;
		}
		else if (ticksSinceThieving < Integer.MAX_VALUE)
		{
			ticksSinceThieving++;
		}

		// Walking away ends the session outright, whatever the clock says.
		if (thievingSpot != null && location != null
			&& (location.getPlane() != thievingSpot.getPlane()
			|| location.distanceTo(thievingSpot) > THIEVING_SESSION_RADIUS
			|| ticksSinceThieving > THIEVING_SESSION_TICKS))
		{
			thievingSpot = null;
		}
	}

	/**
	 * Whether the player is mid-attempt, or was a moment ago. Used to keep a
	 * picked pocket from reading as a fight.
	 */
	public boolean isThieving()
	{
		return ticksSinceThieving <= THIEVING_GRACE_TICKS;
	}

	/**
	 * Whether the player is still working this spot, gaps between attempts
	 * included. Used to decide whether the follower keeps its distance.
	 */
	public boolean isInThievingSession()
	{
		return thievingSpot != null;
	}

	private void refreshCombat(Player local)
	{
		// Pickpocketing is not a fight, and it looks exactly like one from
		// here: the target is an NPC with a combat level, and a failed attempt
		// lands a hitsplat. Both of the signals combat is read from are
		// therefore true, and the follower flickered in and out of spectating
		// for the whole run.
		//
		// The whole SESSION is suppressed rather than the attempt, because the
		// gap between attempts is longer than any window sized to a stun, and
		// a fight registered in that gap surfaces the moment thieving ends -
		// which is the follower announcing a fight that never happened.
		//
		// The cost is real and accepted: an actual attack while working a
		// pocket goes unnoticed until the session ends, which it does as soon
		// as the player moves off - and being attacked is one of the few
		// things that makes a player move.
		if (isInThievingSession())
		{
			return;
		}

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

	/**
	 * Called by the plugin when the player takes a hit, which is also combat -
	 * unless they are picking a pocket, where the hit is the failure itself.
	 */
	public void noteDamageTaken()
	{
		if (isInThievingSession())
		{
			return;
		}
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

	// ------------------------------------------------------------ time away

	/**
	 * Minutes between the follower last seeing the player and this login, or -1
	 * when it has no idea - a first run, or a cleared config.
	 *
	 * <p>The only thing in here that outlives a session, and the only thing
	 * that should: it is the difference between a companion that exists when
	 * you are gone and one that is created fresh each time you look at it. A
	 * quick relog is not an absence; a day is.
	 */
	@lombok.Getter
	@lombok.Setter
	private long minutesAway = -1;

	// ---------------------------------------------------------------- tallies

	/**
	 * How many of each thing has happened, ever, keyed by a short label.
	 *
	 * <p>Nothing signals that someone is paying attention like a tally. The
	 * follower knowing this is the fiftieth kalphite is a different kind of
	 * remark from anything it can say about one kill, and it costs a map.
	 *
	 * <p>Keyed by string so a new thing to count needs no new field - the
	 * caller decides the label, the same way rules decide what moves the mood.
	 *
	 * <p>These outlive the session, written back to the config by the plugin.
	 * A count that resets every login is not a memory, it is a scoreboard for
	 * the current game: "your fiftieth" means nothing if the fifty were all
	 * this afternoon, and everything if they were the fifty since you met.
	 */
	private final java.util.Map<String, Integer> tallies = new java.util.HashMap<>();

	/**
	 * The best of each thing seen, keyed the same way. Separate from the
	 * tallies because the operation is different: one accumulates, the other
	 * only ever moves up, and a rule wants to know the moment it moved.
	 */
	private final java.util.Map<String, Integer> records = new java.util.HashMap<>();

	/** Counts one, and returns the new total. */
	public int tally(String what)
	{
		return tallies.merge(what, 1, Integer::sum);
	}

	public int getTally(String what)
	{
		return tallies.getOrDefault(what, 0);
	}

	/**
	 * Files a value against a record.
	 *
	 * @return whether this BEAT a record that already existed. The first value
	 * ever seen seeds the record silently and returns false on purpose: the
	 * first hit of a new install is not a personal best, it is the only
	 * measurement, and announcing it would make the feature look broken to
	 * every new user at once.
	 */
	public boolean noteRecord(String what, int value)
	{
		Integer previous = records.get(what);
		if (previous == null)
		{
			records.put(what, value);
			return false;
		}
		if (value <= previous)
		{
			return false;
		}
		records.put(what, value);
		return true;
	}

	public int getRecord(String what)
	{
		return records.getOrDefault(what, 0);
	}

	/**
	 * How many times the follower has been started up with this player,
	 * this one included. Counted at login, so the first session is 1.
	 */
	@lombok.Getter
	@lombok.Setter
	private int sessionCount;

	/** The live tally map, for the plugin to write out. Not a copy: read-only by convention. */
	public java.util.Map<String, Integer> getTallies()
	{
		return tallies;
	}

	public java.util.Map<String, Integer> getRecords()
	{
		return records;
	}

	/**
	 * Puts back what a previous session counted. Merged rather than replaced so
	 * a restore arriving after something has already been counted - a kill in
	 * the first ticks after login - does not throw that away.
	 */
	public void restoreCounters(java.util.Map<String, Integer> savedTallies,
		java.util.Map<String, Integer> savedRecords)
	{
		if (savedTallies != null)
		{
			savedTallies.forEach((key, value) -> tallies.merge(key, value, Integer::sum));
		}
		if (savedRecords != null)
		{
			savedRecords.forEach((key, value) -> records.merge(key, value, Math::max));
		}
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

	// -------------------------------------------------------------- attention

	/**
	 * How many ticks the mouse has been resting on the follower.
	 *
	 * <p>Fed by the plugin, which already projects the clickbox every client
	 * tick to draw the hover hint, so knowing this costs nothing extra.
	 */
	@lombok.Getter
	@lombok.Setter
	private int hoverTicks;

	// ---------------------------------------------------------------- traits

	/**
	 * Places this particular follower likes and dislikes, rolled once and then
	 * kept for good.
	 *
	 * <p>Taste is what makes a mood legible. A number that goes up and down for
	 * reasons the player cannot see is just weather; a follower that is always
	 * pleased to be back at the same place and always grumbles about the same
	 * swamp is one you can come to KNOW, and the mood stops being a mechanic
	 * and starts being a temperament.
	 *
	 * <p>Rolled per character rather than shipped as a list, so two people with
	 * this plugin do not have the same follower.
	 */
	private java.util.Set<Integer> likedRegions = java.util.Collections.emptySet();
	private java.util.Set<Integer> dislikedRegions = java.util.Collections.emptySet();

	public void setTraits(java.util.Set<Integer> liked, java.util.Set<Integer> disliked)
	{
		likedRegions = liked == null ? java.util.Collections.emptySet() : liked;
		dislikedRegions = disliked == null ? java.util.Collections.emptySet() : disliked;
	}

	public java.util.Set<Integer> getLikedRegions()
	{
		return likedRegions;
	}

	public java.util.Set<Integer> getDislikedRegions()
	{
		return dislikedRegions;
	}

	/** Whether the follower feels the named way about where it is standing. */
	public boolean feelsAbout(String how)
	{
		if ("disliked".equalsIgnoreCase(how))
		{
			return dislikedRegions.contains(regionId);
		}
		return likedRegions.contains(regionId);
	}

	// ----------------------------------------------------------------- wants

	/**
	 * Somewhere the follower has asked to go, and how long it will keep hoping.
	 *
	 * <p>This is the only thing in here that the follower WANTS rather than
	 * notices. Everything else in this class is a reaction: something happened,
	 * and a rule gets to remark on it. A want runs the other way - the follower
	 * says what it would like, and then the player either does it or does not,
	 * which makes the player's next few minutes an answer to something. A thing
	 * with desires that can be satisfied reads as alive in a way no amount of
	 * commentary manages.
	 *
	 * <p>Deliberately one at a time. Two open wants would make going anywhere
	 * satisfy something, which is the same as satisfying nothing.
	 */
	public enum WantOutcome
	{
		FULFILLED,
		EXPIRED,
	}

	private boolean wantOpen;
	private int wantRegion = -1;
	private String wantLabel = "";
	private int wantDeadlineTick;
	private WantOutcome wantOutcome;

	/**
	 * Asks for somewhere. Ignored while a want is already open, so a rule that
	 * fires twice extends nothing and a second rule cannot quietly replace the
	 * first - the follower asked for one thing, and that is the thing.
	 */
	public void setWant(int region, String label, int minutes)
	{
		if (wantOpen)
		{
			return;
		}
		// Asking to be taken somewhere you are already standing, and then being
		// delighted about arriving, is not a companion with a wish - it is a
		// companion that cannot see out of the window. The check has to be here
		// rather than in the rule, because a rule has no way to say "anywhere
		// except where we are".
		if (region == regionId)
		{
			log.debug("Not asking for region {}: we are already in it", region);
			return;
		}
		wantOpen = true;
		wantRegion = region;
		wantLabel = label == null ? "" : label;
		// A hundred ticks to the minute.
		wantDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;
	}

	public boolean isWanting()
	{
		return wantOpen;
	}

	/** Which region the open want is for, or -1. */
	public int getWantRegion()
	{
		return wantOpen ? wantRegion : -1;
	}

	/** Ticks left before the open want gives up, or 0. */
	public int getWantTicksLeft()
	{
		return wantOpen ? Math.max(0, wantDeadlineTick - client.getTickCount()) : 0;
	}

	/** The label of the current want, or of the one that just resolved. */
	public String getWantLabel()
	{
		return wantLabel;
	}

	/**
	 * Takes the outcome of a want that has just resolved, if any. Consumed by
	 * the engine once per tick and turned into an event, so a resolution is
	 * announced exactly once.
	 */
	public WantOutcome pollWant()
	{
		WantOutcome outcome = wantOutcome;
		wantOutcome = null;
		return outcome;
	}

	private void checkWant()
	{
		if (!wantOpen)
		{
			return;
		}
		if (regionId == wantRegion)
		{
			wantOpen = false;
			wantOutcome = WantOutcome.FULFILLED;
			return;
		}
		if (client.getTickCount() >= wantDeadlineTick)
		{
			wantOpen = false;
			wantOutcome = WantOutcome.EXPIRED;
		}
	}

	// ----------------------------------------------------------- conversation

	/**
	 * How long the follower keeps hoping for an answer.
	 *
	 * <p>Two minutes. It was twelve seconds when the answer was a word typed
	 * into public chat, which is about how long typing "yes" takes. Answering
	 * now means walking over and right-clicking Talk-to, and a window sized for
	 * typing would have expired somewhere around the second click.
	 */
	private static final int ANSWER_WINDOW_TICKS = 200;

	private int ticksSinceQuestion = Integer.MAX_VALUE;

	/**
	 * The dialog tree that answers the open question, or empty.
	 *
	 * <p>This is what makes Talk-to mean something different for a minute or
	 * two: the follower asked, so the conversation it opens is the one about
	 * what it asked, and the everyday script waits its turn.
	 */
	private String askedTree = "";

	/** Called when a rule carrying {@code asks} speaks. */
	public void noteQuestion(String treeId)
	{
		ticksSinceQuestion = 0;
		askedTree = treeId == null ? "" : treeId;
	}

	/** Called once the player has answered, or declined to. */
	public void noteAnswered()
	{
		ticksSinceQuestion = Integer.MAX_VALUE;
		askedTree = "";
	}

	public boolean isAwaitingAnswer()
	{
		return ticksSinceQuestion <= ANSWER_WINDOW_TICKS;
	}

	/** The tree Talk-to should open right now, or empty for the everyday one. */
	public String getAskedTree()
	{
		return isAwaitingAnswer() ? askedTree : "";
	}

	private void ageQuestion()
	{
		if (ticksSinceQuestion < Integer.MAX_VALUE)
		{
			ticksSinceQuestion++;
		}
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

	/**
	 * How many other players are standing within {@code within} tiles.
	 *
	 * <p>Memoised per tick against the refresh generation, like the NPC scan:
	 * a busy bank holds a couple of hundred players and the answer cannot
	 * change between two conditions evaluated on the same tick.
	 */
	public int countPlayersNearby(int within)
	{
		if (crowdGeneration == refreshGeneration && crowdWithin == within)
		{
			return crowdCached;
		}
		crowdGeneration = refreshGeneration;
		crowdWithin = within;

		int found = 0;
		Player local = client.getLocalPlayer();
		if (location != null)
		{
			for (Player other : client.getTopLevelWorldView().players())
			{
				if (other == null || other == local)
				{
					continue;
				}
				WorldPoint where = other.getWorldLocation();
				if (where != null && where.distanceTo(location) <= within)
				{
					found++;
				}
			}
		}
		crowdCached = found;
		return found;
	}

	private int crowdGeneration = -1;
	private int crowdWithin = -1;
	private int crowdCached;

	/** An inventory holds twenty-eight things and always has. */
	private static final int INVENTORY_SIZE = 28;

	private int freeSlotsGeneration = -1;
	private int freeSlots = INVENTORY_SIZE;

	/**
	 * Empty inventory slots.
	 *
	 * <p>Counted by walking the container and counting what is IN it, rather
	 * than trusting {@code size()} or {@code count()}: one of those is the
	 * container's capacity and the other the number of items, and which is
	 * which is not worth betting a wrong warning on. Everything with an id
	 * above zero occupies a slot under either reading, so counting those and
	 * subtracting is right whichever way round it is.
	 *
	 * <p>Worked out on demand and memoised per tick, so a rule set with no
	 * inventory rule in it never pays for this at all.
	 */
	public int getFreeInventorySlots()
	{
		if (freeSlotsGeneration == refreshGeneration)
		{
			return freeSlots;
		}
		freeSlotsGeneration = refreshGeneration;

		net.runelite.api.ItemContainer inventory =
			client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		if (inventory == null)
		{
			// Not loaded yet. Reporting a full inventory here would have the
			// follower warning about a bag it has not seen.
			freeSlots = INVENTORY_SIZE;
			return freeSlots;
		}

		int used = 0;
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				used++;
			}
		}
		freeSlots = Math.max(0, INVENTORY_SIZE - used);
		return freeSlots;
	}

	public String getPlayerName()
	{
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "you" : local.getName();
	}
}
