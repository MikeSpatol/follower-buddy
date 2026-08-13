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
				// Leaving is what turns a fresh memory into one worth
				// bringing up when you next come back.
				placesJustFiled.remove(regionId);
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
		checkSouvenir();
		checkBet();
		checkAdvice();
		checkChallenge();
		refreshAttention();
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
	/**
	 * How long a pause can run before the session is over. Two and a half
	 * minutes rather than ninety seconds: opening a run of coin pouches or
	 * drinking a stamina dose ran past the old figure, which ended the session
	 * mid-run and let the next failed pickpocket read as a fight.
	 */
	private static final int THIEVING_SESSION_TICKS = 250;
	/**
	 * How far the player may drift from the last attempt before the session is
	 * considered over.
	 *
	 * <p>Five tiles was sized for a STALL - a fixed point you stand at. Elves
	 * wander and you follow them, so five tiles ended the session every time
	 * the mark walked, several times a minute: the follower announced the end
	 * of a theft that was still going on, and cheered on the "fight" the next
	 * failed attempt looked like. The anchor moves to the player on every
	 * attempt, so this only has to cover the drift BETWEEN attempts.
	 */
	private static final int THIEVING_SESSION_RADIUS = 12;

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

		// Intent can arm the clock before there is anywhere to anchor it - the
		// click arrives from the menu, which does not wait for a refresh. Adopt
		// the first location seen after it, ONCE: without the latch this also
		// re-anchors a session that walking away had just ended, and walking
		// away stops working.
		if (awaitingThievingAnchor && location != null)
		{
			thievingSpot = location;
			awaitingThievingAnchor = false;
		}

		// Walking away ends the session outright, whatever the clock says.
		if (thievingSpot != null && location != null
			&& (location.getPlane() != thievingSpot.getPlane()
			|| location.distanceTo(thievingSpot) > THIEVING_SESSION_RADIUS
			|| ticksSinceThieving > THIEVING_SESSION_TICKS))
		{
			thievingSpot = null;
			awaitingThievingAnchor = false;
		}
	}

	/**
	 * The player has said they are about to steal something.
	 *
	 * <p>Arming on the ANIMATION was too late. Clicking Pickpocket on an elf
	 * across the room sets the interaction target immediately and then the
	 * player walks over, and for every tick of that walk the follower saw an
	 * NPC with a combat level and called it a fight - so the very first attempt
	 * of a session announced a battle that was actually a theft. The click is
	 * intent and arrives first; the animation is outcome and arrives after the
	 * damage.
	 */
	public void noteThievingIntent()
	{
		ticksSinceThieving = 0;
		if (location != null)
		{
			thievingSpot = location;
		}
		else
		{
			awaitingThievingAnchor = true;
		}
		log.debug("Thieving intent at {}", location);
	}

	/** Set when intent arrived before there was a location to pin it to. */
	private boolean awaitingThievingAnchor;

	/**
	 * The player has said they are about to fight something, which ends any
	 * theft outright.
	 *
	 * <p>The session deliberately outlives the gaps between attempts, and the
	 * cost of that is a window where a genuine fight goes unnoticed. An
	 * explicit Attack is the one unambiguous signal that the window should
	 * close now rather than when the player next wanders off.
	 */
	public void noteFightIntent()
	{
		thievingSpot = null;
		awaitingThievingAnchor = false;
		ticksSinceThieving = Integer.MAX_VALUE;
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
		boolean facing = target instanceof NPC && target.getCombatLevel() > 0
			&& target.getHealthRatio() != 0;

		if (facing && blowsHaveBeenExchanged(local, target))
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
	 * Whether facing this NPC is a FIGHT, as opposed to merely looking at it.
	 *
	 * <p>Interaction alone used to be enough, and interaction covers walking
	 * up to somebody, talking to them and trading with them. Any levelled NPC
	 * would do - so approaching a guard for directions read as a battle
	 * starting, and the follower announced one.
	 *
	 * <p>A fight has two sides or it has blows. Either the NPC is facing back,
	 * or damage has passed between them recently - the first hit in either
	 * direction arms this through {@link #noteDamageTaken} and
	 * {@link #noteDamageDealt}, and this then keeps it alive for as long as the
	 * two are still squared up.
	 */
	private boolean blowsHaveBeenExchanged(Player local, Actor target)
	{
		return target.getInteracting() == local || isInCombat();
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

	/**
	 * The player landed a hit on something, which is the other way a fight
	 * starts - and the only signal available when the target dies before it
	 * can turn round, or cannot reach the player at all.
	 */
	public void noteDamageDealt()
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

	/**
	 * Whether anything countable has moved since the last save.
	 *
	 * <p>The save is nine kilobytes of JSON at the cap and it used to happen
	 * every minute regardless. Most minutes nothing is counted at all: no kill,
	 * no level, no death, and the longest-session record only moves on a day
	 * that beats every previous one.
	 */
	@lombok.Getter
	private boolean countersDirty;

	/** Marks the saved blob as needing a write, for state set from outside. */
	public void markCountersDirty()
	{
		countersDirty = true;
	}

	public void clearCountersDirty()
	{
		countersDirty = false;
	}

	/** Counts one, and returns the new total. */
	public int tally(String what)
	{
		countersDirty = true;
		return tallies.merge(what, 1, Integer::sum);
	}

	public int getTally(String what)
	{
		return tallies.getOrDefault(what, 0);
	}

	/**
	 * The suffix marking a counter as belonging to THIS session.
	 *
	 * <p>They live in the same map as the lifetime ones so a rule can ask about
	 * either with the same condition - "your third death today" and "your
	 * hundredth ever" are the same question with a different key. They are
	 * dropped at login and never written out, which is what makes today mean
	 * today.
	 */
	public static final String TODAY = ":today";

	/** Counts one against both the lifetime total and today's. */
	public int tallyBoth(String what)
	{
		tally(what + TODAY);
		return tally(what);
	}

	/** Called at login: yesterday's session counters are not today's. */
	public void clearDailyTallies()
	{
		tallies.keySet().removeIf(key -> key.endsWith(TODAY));
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
			countersDirty = true;
			return false;
		}
		if (value <= previous)
		{
			// The overwhelmingly common case, minute after minute: today is not
			// the longest day. Nothing changed, so nothing needs writing.
			return false;
		}
		records.put(what, value);
		countersDirty = true;
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
	private int sessionCount;

	public void setSessionCount(int sessionCount)
	{
		this.sessionCount = sessionCount;
		countersDirty = true;
	}

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
		// A restore merges rather than replaces, so what is now in memory is
		// not what is on disk - the next save has real work to do.
		countersDirty = true;
	}

	// ---------------------------------------------------------------- line wear

	/**
	 * How many times each line has actually been SAID, across every session -
	 * the ledger behind retiring content the player has heard to death (R17).
	 *
	 * <p>Keyed by the template's hash in hex rather than its text, so the
	 * saved blob stays small: a collision merely shares one wear count
	 * between two lines, which costs a little freshness and shows nothing.
	 * An edited line hashes fresh, which is right - new words are new
	 * content, and they arrive unheard.
	 */
	private final java.util.Map<String, Integer> lineWear = new java.util.HashMap<>();

	/**
	 * Entries kept. The least-worn are dropped first: they are the farthest
	 * from ever standing a line aside, so forgetting them costs the least.
	 */
	private static final int MAX_WORN_LINES = 500;

	private static String wearKey(String template)
	{
		return Integer.toHexString(template.hashCode());
	}

	/**
	 * One more actual saying of this line - called at delivery, not at the win.
	 *
	 * <p>Deliberately NOT marking the counters dirty: something is said every
	 * minute or two, and wear alone would turn the every-hundred-ticks
	 * housekeeping write back on for good - the exact rewriting the dirty
	 * flag exists to stop. The ledger rides out with the next write anything
	 * else causes, and the orderly shutdown writes unconditionally; a crash
	 * costs one session's wear, which a statistical ledger can afford.
	 */
	public void noteLineSaid(String template)
	{
		if (template == null || template.isEmpty())
		{
			return;
		}
		lineWear.merge(wearKey(template), 1, Integer::sum);
		while (lineWear.size() > MAX_WORN_LINES)
		{
			String lightest = null;
			int least = Integer.MAX_VALUE;
			for (java.util.Map.Entry<String, Integer> entry : lineWear.entrySet())
			{
				if (entry.getValue() < least)
				{
					least = entry.getValue();
					lightest = entry.getKey();
				}
			}
			lineWear.remove(lightest);
		}
	}

	public int lineWear(String template)
	{
		return template == null || template.isEmpty()
			? 0 : lineWear.getOrDefault(wearKey(template), 0);
	}

	/** The live ledger, for the plugin to write out. Read-only by convention. */
	public java.util.Map<String, Integer> getLineWear()
	{
		return lineWear;
	}

	public void restoreLineWear(java.util.Map<String, Integer> saved)
	{
		if (saved != null)
		{
			saved.forEach((key, value) -> lineWear.merge(key, value, Integer::sum));
			countersDirty = true;
		}
	}

	// ------------------------------------------------------------ one-time lines

	/**
	 * Rules marked {@code once} that have already had their one time.
	 *
	 * <p>Kept here rather than on the rule for two reasons, and each one is a
	 * bug avoided. The rule file reloads whenever it changes on disk, throwing
	 * every rule object away and parsing fresh ones - a flag on the object
	 * would un-say the follower's introduction every time a phrase was edited.
	 * And this belongs with the tallies and the places anyway: what the
	 * follower has already told you is memory, and memory is kept in one place.
	 */
	private final java.util.Set<String> spokenOnce = new java.util.HashSet<>();

	public boolean hasSaidOnce(String ruleId)
	{
		return ruleId != null && spokenOnce.contains(ruleId);
	}

	public void noteSaidOnce(String ruleId)
	{
		if (ruleId != null && spokenOnce.add(ruleId))
		{
			countersDirty = true;
		}
	}

	/** Live set, for the plugin to write out. Read-only by convention. */
	public java.util.Set<String> getSpokenOnce()
	{
		return spokenOnce;
	}

	public void restoreSpokenOnce(java.util.Collection<String> saved)
	{
		if (saved != null)
		{
			spokenOnce.addAll(saved);
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

	// ------------------------------------------------------------- incidents

	/**
	 * The last thing daft enough to be worth bringing up again.
	 *
	 * <p>The tallies count; this remembers. They are not the same faculty and
	 * the difference is most of what makes a companion feel like one. "Your
	 * four hundredth yew" is data delivered well. "Careful, I've seen what a
	 * chicken can do to you" is a shared history, and it only works because
	 * something specific happened and was kept.
	 *
	 * <p>One at a time on purpose. A follower with a filing cabinet of
	 * grievances is a different and much worse character than one with a
	 * favourite story.
	 */
	private String incidentKey = "";
	private String incidentPhrase = "";
	private int incidentCount;

	/**
	 * Files an incident, or counts another of the same.
	 *
	 * @param key   what happened, for a rule to recognise
	 * @param phrase how to refer to it out loud - "that chicken"
	 */
	public void noteIncident(String key, String phrase)
	{
		if (key == null || key.isEmpty())
		{
			return;
		}
		if (key.equals(incidentKey))
		{
			incidentCount++;
		}
		else
		{
			incidentKey = key;
			incidentPhrase = phrase == null ? "" : phrase;
			incidentCount = 1;
		}
		countersDirty = true;
		log.debug("Incident: {} ({}), now {}", key, incidentPhrase, incidentCount);
	}

	public boolean hasIncident()
	{
		return !incidentKey.isEmpty();
	}

	public String getIncidentKey()
	{
		return incidentKey;
	}

	/** How to say it out loud, for the {memory} placeholder. */
	public String getIncidentPhrase()
	{
		return incidentPhrase;
	}

	public int getIncidentCount()
	{
		return incidentCount;
	}

	/** Puts back the incident a previous session was still thinking about. */
	public void restoreIncident(String key, String phrase, int count)
	{
		if (key == null || key.isEmpty())
		{
			return;
		}
		incidentKey = key;
		incidentPhrase = phrase == null ? "" : phrase;
		incidentCount = Math.max(1, count);
	}

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

	/**
	 * The ROLLED feeling about where it is standing, ignoring everything
	 * experience has since proved.
	 *
	 * <p>{@link #feelsAbout} answers with experience outranking the roll,
	 * which is right everywhere except the one moment that makes the
	 * temperament visible: when the two disagree. A rule cannot express "it
	 * rolled dislike but the evidence says otherwise" through feelsAbout,
	 * because the override has already swallowed the disagreement - and the
	 * disagreement is the character. Conceding the facts while keeping the
	 * feeling needs both halves readable.
	 */
	public boolean rolledFeeling(String how)
	{
		return "disliked".equalsIgnoreCase(how)
			? dislikedRegions.contains(regionId)
			: likedRegions.contains(regionId);
	}

	// ------------------------------------------------------- earned taste

	/**
	 * What each place has come to mean, learned rather than rolled.
	 *
	 * <p>The rolled sets above give the follower a taste before anything has
	 * happened to it, which is the only honest thing to do on the first login.
	 * After that they are the WEAKER claim: a place you died in twice should
	 * not stay a favourite because a shuffle said so on day one.
	 *
	 * <p>The signal costs nothing to author, because the rules already carry
	 * it. Every rule that knows a boss dying is worth +18 and a death is worth
	 * -25 is already saying how much the moment mattered; all this does is
	 * remember WHERE it happened. A new influence on how the follower feels
	 * about a place is a mood value on a rule, same as before.
	 */
	private final java.util.Map<Integer, Integer> placeScores = new java.util.HashMap<>();

	/** What the follower brings up about a place, keyed the same way. */
	private final java.util.Map<Integer, String> placeMemories = new java.util.HashMap<>();

	/**
	 * Places whose memory was filed on THIS visit, and so is not yet worth
	 * bringing up.
	 *
	 * <p>Found in a transcript: the follower set a personal best, filed the
	 * spot, and told the player about the spot five seconds later - "this is
	 * where you hit harder than you ever had", said to somebody still standing
	 * there. The whole value of a place memory is that it waits, and nothing
	 * made it wait. A region leaves this set when the player leaves the region,
	 * which is precisely what "come back to it" means.
	 */
	private final java.util.Set<Integer> placesJustFiled = new java.util.HashSet<>();

	/** Past this, an accumulation of small moments counts as an opinion. */
	public static final int OPINION_AT = 40;

	/**
	 * Bounded so an opinion can be strong and still turn around. Without a cap
	 * a region farmed for a week reaches a score no amount of later misery can
	 * move, and the follower is left insisting it loves somewhere you both now
	 * hate.
	 */
	private static final int OPINION_CAP = 150;

	/** How many places it can hold an opinion about, oldest-weakest evicted. */
	private static final int MAX_PLACES = 60;

	/**
	 * Records that something worth {@code delta} happened where we are standing.
	 *
	 * @param delta the same number the rule moves the mood by
	 */
	public void notePlaceFeeling(int delta)
	{
		if (delta == 0 || regionId == 0)
		{
			return;
		}
		int now = placeScores.getOrDefault(regionId, 0) + delta;
		placeScores.put(regionId, Math.max(-OPINION_CAP, Math.min(OPINION_CAP, now)));
		countersDirty = true;
		trimPlaces();
	}

	/** Records what happened here, for a rule to bring up next time. */
	public void notePlaceMemory(String phrase)
	{
		if (phrase == null || phrase.isEmpty() || regionId == 0)
		{
			return;
		}
		placeMemories.put(regionId, phrase);
		placesJustFiled.add(regionId);
		countersDirty = true;
		trimPlaces();
	}

	/**
	 * Drops the places it feels least strongly about once there are too many.
	 * A follower with an opinion about everywhere has an opinion about nowhere,
	 * and the saved blob has to stay a sensible size.
	 */
	private void trimPlaces()
	{
		while (placeScores.size() > MAX_PLACES)
		{
			Integer weakest = null;
			int least = Integer.MAX_VALUE;
			for (java.util.Map.Entry<Integer, Integer> entry : placeScores.entrySet())
			{
				int strength = Math.abs(entry.getValue());
				if (strength < least)
				{
					least = strength;
					weakest = entry.getKey();
				}
			}
			if (weakest == null)
			{
				return;
			}
			placeScores.remove(weakest);
			placeMemories.remove(weakest);
		}
		while (placeMemories.size() > MAX_PLACES)
		{
			placeMemories.remove(placeMemories.keySet().iterator().next());
		}
	}

	public int getPlaceScore()
	{
		return placeScores.getOrDefault(regionId, 0);
	}

	public java.util.Map<Integer, Integer> getPlaceScores()
	{
		return placeScores;
	}

	public java.util.Map<Integer, String> getPlaceMemories()
	{
		return placeMemories;
	}

	public void restorePlaces(java.util.Map<Integer, Integer> scores,
		java.util.Map<Integer, String> memories)
	{
		placeScores.clear();
		placeMemories.clear();
		if (scores != null)
		{
			placeScores.putAll(scores);
		}
		if (memories != null)
		{
			placeMemories.putAll(memories);
		}
	}

	/**
	 * Something the follower remembers about this place, or empty.
	 *
	 * <p>Empty while the memory is still fresh from this visit: a thing that
	 * happened a moment ago is not a thing the place reminds you of.
	 */
	public String getPlaceMemory()
	{
		if (placesJustFiled.contains(regionId))
		{
			return "";
		}
		String here = placeMemories.get(regionId);
		return here == null ? "" : here;
	}

	public boolean hasPlaceMemory()
	{
		return !getPlaceMemory().isEmpty();
	}

	/**
	 * Whether the follower feels the named way about where it is standing.
	 *
	 * <p>Experience outranks the roll, and only in the direction experience
	 * points: a place it has come to dislike is not liked, whatever the shuffle
	 * said on the first login. Where nothing has happened yet, the roll still
	 * answers, so a new follower has a temperament from the start.
	 */
	public boolean feelsAbout(String how)
	{
		int earned = getPlaceScore();
		boolean askingDisliked = "disliked".equalsIgnoreCase(how);

		if (earned <= -OPINION_AT)
		{
			return askingDisliked;
		}
		if (earned >= OPINION_AT)
		{
			return !askingDisliked;
		}
		return askingDisliked
			? dislikedRegions.contains(regionId)
			: likedRegions.contains(regionId);
	}

	// ---------------------------------------------------------------- wishes

	/**
	 * A small thing the follower has said it could use - a feather, a bit of
	 * string - waiting for the player to "find" one via the Talk-to gift.
	 *
	 * <p>The want asks for somewhere; the wish asks for someTHING, and it
	 * exists because the first gift design failed in play: an unspecified
	 * gift reads as a null action, and giving with no wanting before it has
	 * no pull. The wish is the wanting. Everything downstream - the option
	 * label, the thank-you, the souvenir - names the specific thing, so the
	 * player always knows what they gave because the follower said what it
	 * wanted.
	 *
	 * <p>One at a time, like the want and for the same reason. Lapses
	 * silently at its deadline: a small hope that quietly expires needs no
	 * announcement.
	 */
	private String wishLabel = "";
	private int wishDeadlineTick;
	private java.util.Set<Integer> wishItems = java.util.Collections.emptySet();

	public void setWish(String label, int minutes)
	{
		setWish(label, minutes, null);
	}

	/**
	 * @param itemIds the real inventory items that grant this wish. The wish
	 * is fictional in that nothing is consumed, but it is honest in that the
	 * thing must actually be in the bag - play testing found the bluff
	 * immediately, claiming a pot of ink with empty pockets, and a follower
	 * that cannot tell is a follower that cannot see.
	 */
	public void setWish(String label, int minutes, java.util.List<Integer> itemIds)
	{
		if (isWishing() || label == null || label.isEmpty())
		{
			return;
		}
		wishLabel = label;
		wishItems = itemIds == null || itemIds.isEmpty()
			? java.util.Collections.emptySet()
			: new java.util.HashSet<>(itemIds);
		wishDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;
		log.info("Wish opened: {} for {} minutes, granted by {}", label, minutes, wishItems);
	}

	/**
	 * Whether the wished-for thing is actually in the player's bag right now.
	 * A wish with no item list is grantable on word alone.
	 */
	public boolean isWishedItemInBag()
	{
		if (!isWishing())
		{
			return false;
		}
		if (wishItems.isEmpty())
		{
			return true;
		}
		net.runelite.api.ItemContainer inventory =
			client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		if (inventory == null)
		{
			return false;
		}
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && wishItems.contains(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	public boolean isWishing()
	{
		return !wishLabel.isEmpty() && client.getTickCount() <= wishDeadlineTick;
	}

	/** The small thing currently hoped for, or empty. */
	public String getWishLabel()
	{
		return isWishing() ? wishLabel : "";
	}

	/** The wish came true (or is being abandoned); either way it is over. */
	public void clearWish()
	{
		wishLabel = "";
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
			log.info("Want refused: asked for region {} but we are standing in it", region);
			return;
		}
		wantOpen = true;
		wantRegion = region;
		wantLabel = label == null ? "" : label;
		// A hundred ticks to the minute.
		wantDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;

		// Logged at INFO rather than debug, and so are both endings. A want
		// happens at most once every twenty minutes, so this is three lines a
		// session - and when one does not land the way it should, the log is
		// the only place that can say whether it opened, what it was watching
		// for, and where the player actually was.
		log.info("Want opened: {} (region {}), {} minutes, from region {}",
			wantLabel, region, minutes, regionId);
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
			log.info("Want fulfilled: {} (region {})", wantLabel, wantRegion);
			return;
		}
		if (client.getTickCount() >= wantDeadlineTick)
		{
			wantOpen = false;
			wantOutcome = WantOutcome.EXPIRED;
			log.info("Want expired: {} (region {}), never left region {}",
				wantLabel, wantRegion, regionId);
		}
	}

	// --------------------------------------------------------- the souvenir

	/**
	 * Something the follower picked up and is carrying about.
	 *
	 * <p>Nothing else in the plugin persists an OBJECT. Everything it has is a
	 * number, a mood or a place - and a companion holding a particular rock it
	 * found is a different kind of detail: it is the same rock an hour later,
	 * and then one day it is not.
	 */
	private String souvenir = "";
	private int souvenirDroppedTick;

	public void pickUp(String what, int minutes)
	{
		if (what == null || what.isEmpty() || !souvenir.isEmpty())
		{
			return;
		}
		souvenir = what;
		souvenirDroppedTick = client.getTickCount() + Math.max(1, minutes) * 100;
		log.debug("Picked up {} for {} minutes", what, minutes);
	}

	/**
	 * Puts this down and carries that instead, deliberately.
	 *
	 * <p>{@link #pickUp} refuses while carrying, and that guard is right for
	 * every accidental path - a souvenir rule firing while the pocket is full
	 * must not silently vanish something the follower has been talking about.
	 * The trade is the AUTHORED swap: the rule that calls it says the
	 * exchange out loud, which is the whole difference.
	 */
	public void tradeFor(String what, int minutes)
	{
		if (what == null || what.isEmpty())
		{
			return;
		}
		log.debug("Traded {} for {}", souvenir, what);
		souvenir = what;
		souvenirDroppedTick = client.getTickCount() + Math.max(1, minutes) * 100;
	}

	public boolean isCarrying()
	{
		return !souvenir.isEmpty();
	}

	/** What it is carrying, or what it just lost, for the {souvenir} placeholder. */
	public String getSouvenir()
	{
		return souvenir;
	}

	private boolean souvenirLost;

	/** Consumed once by the engine when the souvenir goes, like a want outcome. */
	public boolean pollSouvenirLost()
	{
		boolean lost = souvenirLost;
		souvenirLost = false;
		return lost;
	}

	private void checkSouvenir()
	{
		if (souvenir.isEmpty() || client.getTickCount() < souvenirDroppedTick)
		{
			return;
		}
		souvenirLost = true;
		log.debug("Lost the {}", souvenir);
		// The name survives the loss so the line can mourn it by name.
		souvenirDroppedTick = 0;
	}

	/** Called once the loss has been announced. */
	public void clearSouvenir()
	{
		souvenir = "";
	}

	// ---------------------------------------------------- how long we have known each other

	/**
	 * The day the follower first met this player, as an epoch day.
	 *
	 * <p>The session count already says a hundred logins. It cannot say a
	 * hundred DAYS, and those are different claims about a friendship: one is a
	 * number of visits, the other is a stretch of somebody's life. Only a date
	 * can tell you it has been a year, and only a date can notice that today is
	 * the anniversary of the first one.
	 */
	private long metOnDay;

	public void setMetOnDay(long epochDay)
	{
		metOnDay = epochDay;
	}

	public long getMetOnDay()
	{
		return metOnDay;
	}

	/** Days since the first meeting, or 0 if it has not been recorded yet. */
	public int getDaysKnown()
	{
		if (metOnDay <= 0)
		{
			return 0;
		}
		long days = java.time.LocalDate.now().toEpochDay() - metOnDay;
		return days < 0 ? 0 : (int) Math.min(days, Integer.MAX_VALUE);
	}

	/**
	 * Whether today is the same day of the year as the first meeting - and not
	 * the first meeting itself, which is not an anniversary of anything.
	 */
	public boolean isAnniversary()
	{
		if (metOnDay <= 0 || getDaysKnown() < 300)
		{
			return false;
		}
		java.time.LocalDate met = java.time.LocalDate.ofEpochDay(metOnDay);
		java.time.LocalDate today = java.time.LocalDate.now();
		return met.getMonthValue() == today.getMonthValue()
			&& met.getDayOfMonth() == today.getDayOfMonth();
	}

	/**
	 * What the player is wearing now, and what they were wearing the day the
	 * follower met them, both in gp.
	 *
	 * <p>Priced by the plugin, which has the item manager; kept here so a
	 * condition can compare them. The comparison is the whole point - a number
	 * on its own is a wealth tracker, and there are better ones. "You were in
	 * bronze when I met you" is only available to something that was there.
	 */
	private int wornValue;
	private int metWearingValue = -1;

	public void setWornValue(int value)
	{
		wornValue = value;
	}

	public int getWornValue()
	{
		return wornValue;
	}

	public void setMetWearingValue(int value)
	{
		metWearingValue = value;
	}

	public int getMetWearingValue()
	{
		return metWearingValue;
	}

	/**
	 * How many times better dressed the player is than the day they met,
	 * or 0 while there is nothing to compare against.
	 */
	public int getTimesBetterDressed()
	{
		if (metWearingValue <= 0 || wornValue <= 0)
		{
			return 0;
		}
		return wornValue / Math.max(1, metWearingValue);
	}

	// ------------------------------------------------------------ the nickname

	/**
	 * What the follower has taken to calling you, earned from whatever you do
	 * most.
	 *
	 * <p>It counts everything already; this is the one place it draws a
	 * conclusion from the counting. The name moves as your play moves, which
	 * makes it a mirror rather than a label - and being called the gravedigger
	 * for a fortnight is a fact about you that no line written in advance could
	 * have known.
	 */
	private final java.util.Map<String, String> nicknamesFor = new java.util.LinkedHashMap<>();

	/** Registered by the plugin from the rule file, so the names stay editable. */
	public void setNicknames(java.util.Map<String, String> byTally)
	{
		nicknamesFor.clear();
		if (byTally != null)
		{
			nicknamesFor.putAll(byTally);
		}
	}

	/** How much of a lead a tally needs before it earns you the name. */
	private static final int NICKNAME_AT = 25;

	public String getNickname()
	{
		String best = "";
		int most = NICKNAME_AT;
		for (java.util.Map.Entry<String, String> entry : nicknamesFor.entrySet())
		{
			int count = getTally(entry.getKey());
			if (count > most)
			{
				most = count;
				best = entry.getValue();
			}
		}
		return best;
	}

	public boolean hasNickname()
	{
		return !getNickname().isEmpty();
	}

	// -------------------------------------------------------- the challenge

	/**
	 * A wager on the PLAYER, against the clock.
	 *
	 * <p>The bet is about what the world will do - what the next drop is worth.
	 * This is about what you will do, which is the difference between a
	 * companion watching and a companion involved. It also gives the follower
	 * something to be wrong about that is your fault, which is funnier.
	 *
	 * <p>Measured against a tally it already keeps, so a challenge is a rule
	 * naming a counter and a number, not a scoring system of its own.
	 */
	public enum ChallengeOutcome
	{
		MET,
		FAILED,
	}

	private String challengeAbout = "";
	private String challengeTally = "";
	private int challengeTarget;
	private int challengeStartedAt;
	private int challengeDeadlineTick;
	private ChallengeOutcome challengeOutcome;

	public void setChallenge(String about, String tally, int target, int minutes)
	{
		if (tally == null || tally.isEmpty() || isChallenging())
		{
			return;
		}
		challengeAbout = about == null ? "" : about;
		challengeTally = tally;
		challengeTarget = Math.max(1, target);
		challengeStartedAt = getTally(tally);
		challengeDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;
		log.debug("Challenge: {} of {} in {} minutes", target, tally, minutes);
	}

	public boolean isChallenging()
	{
		return challengeDeadlineTick > 0;
	}

	/** What the challenge was, for the {challenge} placeholder. */
	public String getChallengeAbout()
	{
		return challengeAbout;
	}

	/** How many are still needed, for a line that can count down. */
	public int getChallengeLeft()
	{
		if (!isChallenging())
		{
			return 0;
		}
		return Math.max(0, challengeTarget - (getTally(challengeTally) - challengeStartedAt));
	}

	private void checkChallenge()
	{
		if (!isChallenging())
		{
			return;
		}
		if (getTally(challengeTally) - challengeStartedAt >= challengeTarget)
		{
			challengeDeadlineTick = 0;
			challengeOutcome = ChallengeOutcome.MET;
			return;
		}
		if (client.getTickCount() >= challengeDeadlineTick)
		{
			challengeDeadlineTick = 0;
			challengeOutcome = ChallengeOutcome.FAILED;
		}
	}

	public ChallengeOutcome pollChallenge()
	{
		ChallengeOutcome outcome = challengeOutcome;
		challengeOutcome = null;
		return outcome;
	}

	// ------------------------------------------------------------- underfoot

	/**
	 * The player clicked the tile the follower happened to be standing on.
	 *
	 * <p>The one nuisance of a companion that nothing in here acknowledged. It
	 * keeps clear of fights and it walks behind you, and then every so often it
	 * is simply in the way, and a follower that never notices that is a follower
	 * you are managing rather than travelling with.
	 *
	 * <p>Consumed on read, because it is a moment rather than a state: the tile
	 * is walked to a second later and the fact stops being true.
	 */
	private boolean underfoot;

	public void noteUnderfoot()
	{
		underfoot = true;
	}

	public boolean pollUnderfoot()
	{
		boolean was = underfoot;
		underfoot = false;
		return was;
	}

	// ------------------------------------------------------- gone, or just still

	/**
	 * Whether the player has stopped touching the controls, as distinct from
	 * standing still on purpose.
	 *
	 * <p>{@code idle} cannot tell those apart: a player at a furnace and a
	 * player who has gone to make tea look identical to it, and the follower
	 * saying "still here, still standing" to an empty chair is only funny by
	 * accident. The camera is the tell - it moves when somebody is there.
	 */
	private int cameraX = Integer.MIN_VALUE;
	private int cameraY;
	private int cameraPitch;
	private int stillTicks;

	private void refreshAttention()
	{
		int x = client.getCameraX();
		int y = client.getCameraY();
		int pitch = client.getCameraPitch();
		if (cameraX == Integer.MIN_VALUE)
		{
			cameraX = x;
			cameraY = y;
			cameraPitch = pitch;
			return;
		}
		if (x != cameraX || y != cameraY || pitch != cameraPitch)
		{
			cameraX = x;
			cameraY = y;
			cameraPitch = pitch;
			stillTicks = 0;
			return;
		}
		if (stillTicks < Integer.MAX_VALUE)
		{
			stillTicks++;
		}
	}

	/** Ticks since the camera last moved. */
	public int getUnattendedTicks()
	{
		return stillTicks;
	}

	// ----------------------------------------------------------- the advice

	/**
	 * Whether the player did the thing the follower just told them to.
	 *
	 * <p>Everything else here runs one way: something happens, and a rule gets
	 * to remark on it. The follower shouts "eat something!" and then, however
	 * that turns out, never mentions it again - which is commentary rather than
	 * conversation, and after the fiftieth time it is obviously a recording.
	 *
	 * <p>What satisfies a piece of advice is named by the RULE that gives it,
	 * not decided here, so there is no vocabulary of blessed actions in the
	 * code to fall out of date against the rule file.
	 */
	public enum AdviceOutcome
	{
		HEEDED,
		IGNORED,
	}

	private String adviceAbout = "";
	private java.util.Set<Integer> adviceIds = java.util.Collections.emptySet();
	private boolean adviceWantsRoom;
	private int adviceFreeSlotsAtStart;
	private int adviceStartTick;
	private int adviceDeadlineTick;

	/**
	 * How long the player must take before making room counts as taking the
	 * advice.
	 *
	 * <p>Only the room kind needs this. Eating when told to eat is a discrete
	 * act that can be attributed however quickly it happens; a bag getting
	 * emptier is ambient, and in any gathering loop it was going to happen
	 * anyway. Without the delay the follower warned about the bag and thanked
	 * the player four seconds later, over and over - fifty-six lines of one
	 * three-hour session, all of it noise.
	 */
	private static final int ROOM_ADVICE_MIN_TICKS = 25;
	private AdviceOutcome adviceOutcome;

	/**
	 * @param ids animations that would settle it - eating, teleporting
	 * @param wantsRoom whether freeing an inventory slot settles it instead
	 */
	public void adviseOn(String about, java.util.Set<Integer> ids, boolean wantsRoom, int minutes)
	{
		if (about == null || about.isEmpty() || isAdvising())
		{
			return;
		}
		adviceAbout = about;
		adviceIds = ids == null ? java.util.Collections.emptySet() : ids;
		adviceWantsRoom = wantsRoom;
		adviceFreeSlotsAtStart = getFreeInventorySlots();
		adviceStartTick = client.getTickCount();
		adviceDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;
		log.debug("Advised about {} for {} minutes", about, minutes);
	}

	public boolean isAdvising()
	{
		return adviceDeadlineTick > 0;
	}

	/** What the last piece of advice was about, for the {advice} placeholder. */
	public String getAdviceAbout()
	{
		return adviceAbout;
	}

	/** Offered every animation the player plays, while advice is outstanding. */
	public void offerAct(int animationId)
	{
		if (isAdvising() && adviceIds.contains(animationId))
		{
			settleAdvice(AdviceOutcome.HEEDED);
		}
	}

	private void settleAdvice(AdviceOutcome outcome)
	{
		adviceDeadlineTick = 0;
		adviceOutcome = outcome;
		log.debug("Advice about {} was {}", adviceAbout, outcome);
	}

	public AdviceOutcome pollAdvice()
	{
		AdviceOutcome outcome = adviceOutcome;
		adviceOutcome = null;
		return outcome;
	}

	private void checkAdvice()
	{
		if (!isAdvising())
		{
			return;
		}
		// Making room counts the moment the bag is emptier than it was, which
		// covers banking, dropping and eating the thing that was in the way.
		if (adviceWantsRoom && getFreeInventorySlots() > adviceFreeSlotsAtStart
			&& client.getTickCount() - adviceStartTick >= ROOM_ADVICE_MIN_TICKS)
		{
			settleAdvice(AdviceOutcome.HEEDED);
			return;
		}
		if (client.getTickCount() >= adviceDeadlineTick)
		{
			settleAdvice(AdviceOutcome.IGNORED);
		}
	}

	// -------------------------------------------------------------- the bet

	/**
	 * A prediction about the next thing to drop, and what it is worth.
	 *
	 * <p>A companion with an opinion about what happens next has a stake in it,
	 * which is a different thing from commentary. Being WRONG is the better
	 * half: anything that can only be right is not really predicting.
	 */
	public enum BetOutcome
	{
		WON,
		LOST,
	}

	private boolean betOpen;
	private boolean betOnRich;
	private int betThreshold;
	private int betDeadlineTick;
	private BetOutcome betOutcome;

	/**
	 * @param onRich whether the follower is betting the drop BEATS the
	 * threshold. Both directions exist so it can be pessimistic, which is
	 * funnier and, on most drops, correct.
	 */
	public void placeBet(boolean onRich, int threshold, int minutes)
	{
		if (betOpen)
		{
			return;
		}
		betOpen = true;
		betOnRich = onRich;
		betThreshold = threshold;
		betDeadlineTick = client.getTickCount() + Math.max(1, minutes) * 100;
	}

	public boolean isBetting()
	{
		return betOpen;
	}

	/** Called by the plugin when loot lands, while a bet is open. */
	public void settleBet(int lootValue)
	{
		if (!betOpen)
		{
			return;
		}
		betOpen = false;
		boolean rich = lootValue >= betThreshold;
		betOutcome = rich == betOnRich ? BetOutcome.WON : BetOutcome.LOST;
		log.debug("Bet {} on {}{}: loot was {}", betOutcome,
			betOnRich ? ">=" : "<", betThreshold, lootValue);
	}

	public BetOutcome pollBet()
	{
		BetOutcome outcome = betOutcome;
		betOutcome = null;
		return outcome;
	}

	private void checkBet()
	{
		// A bet nobody collected on. Quietly forgotten rather than counted as
		// a win, which would let it be right by saying nothing.
		if (betOpen && client.getTickCount() >= betDeadlineTick)
		{
			betOpen = false;
		}
	}

	// ----------------------------------------------------------- the clock

	/**
	 * The hour on the player's own wall, 0-23.
	 *
	 * <p>Nothing else in the game acknowledges the room the player is sitting
	 * in, which is exactly why a follower noticing it lands the way it does.
	 */
	public int getHourOfDay()
	{
		return java.time.LocalTime.now().getHour();
	}

	/** Minutes this session has run, fed by the plugin's own timer. */
	@lombok.Getter
	@lombok.Setter
	private int sessionMinutes;

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

	/**
	 * Called by the plugin when the player dies.
	 *
	 * <p>This used to be session memory, on the reasoning that a companion
	 * remembering last week's death forever would wear thin. That was the
	 * wrong call: within a session the only time you are near the spot is the
	 * walk back for your gravestone, which is the one moment the line must NOT
	 * fire - hence the arming delay. Kept between sessions it becomes what it
	 * was always trying to be, which is an anniversary.
	 */
	public void noteDeath(WorldPoint where)
	{
		deathLocation = where;
		deathTick = client.getTickCount();
		countersDirty = true;
	}

	/** Where the player last died, for the plugin to write out. */
	public WorldPoint getDeathLocation()
	{
		return deathLocation;
	}

	/**
	 * Puts back a death from a previous session. The arming delay is spent:
	 * whenever it was, it was not this walk back.
	 */
	public void restoreDeathSpot(WorldPoint where)
	{
		deathLocation = where;
		deathTick = Integer.MIN_VALUE / 2;
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

	/** Everything worn, by item id. Used to decide when repricing is needed. */
	public Set<Integer> getEquippedItems()
	{
		return equippedItems;
	}

	public boolean isEquipped(int itemId)
	{
		return equippedItems.contains(itemId);
	}

	public boolean isNpcNearby(java.util.function.Predicate<NPC> predicate, int within)
	{
		return isNpcNearby(predicate, within, false);
	}

	/**
	 * @param requireVisible the NPC must also have line of sight to the
	 * player. For rules whose line invites the player to LOOK at something:
	 * "that thing is very large and very close" pointing at a wall with a
	 * kalphite behind it is worse than silence, because a bad callout teaches
	 * the player to stop checking. Distance alone was the whole test before
	 * this, and a different plane was only excluded by accident of
	 * {@code distanceTo} returning MAX_VALUE across planes.
	 */
	public boolean isNpcNearby(java.util.function.Predicate<NPC> predicate, int within,
		boolean requireVisible)
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
			if (npcLocation.distanceTo(location) > within)
			{
				continue;
			}
			if (!requireVisible || canSee(npcLocation, npcSize(npc)))
			{
				return true;
			}
		}
		return false;
	}

	private static int npcSize(NPC npc)
	{
		return npc.getComposition() == null ? 1
			: Math.max(1, npc.getComposition().getSize());
	}

	/**
	 * Line of sight from an NPC's area to the player, by the game's own
	 * algorithm - {@link net.runelite.api.coords.WorldArea#hasLineOfSightTo}
	 * reads the scene's sight-blocking collision flags, which is exactly what
	 * the game consults to decide the same question.
	 *
	 * <p>The area is built from location and size rather than asked of the
	 * actor, and no collision data reads as visible: during loading there is
	 * nothing to consult, and refusing to speak about a boss because the map
	 * has not finished arriving would be the wrong kind of caution.
	 */
	private boolean canSee(WorldPoint from, int size)
	{
		net.runelite.api.WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.getCollisionMaps() == null)
		{
			return true;
		}
		return new net.runelite.api.coords.WorldArea(from, size, size)
			.hasLineOfSightTo(wv, location);
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
