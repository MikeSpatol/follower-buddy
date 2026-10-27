package com.follower;

import com.google.common.base.Strings;
import java.util.*;
import java.util.Set;
import net.runelite.api.*;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import lombok.*;
import com.google.gson.Gson;
import java.time.*;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;
import static java.lang.Math.*;

/**
 * A snapshot of game state, refreshed once per game tick and shared by every
 * condition evaluated that tick.
 */
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@Setter
@Getter
class TriggerContext
{

	final Client client;

	int hitpoints,
		maxHitpoints,
		prayerPoints,
		maxPrayerPoints,
		regionId = -1,
		idleTicks,
		ticksSincePrayerDrain = Integer.MAX_VALUE,
		lastPrayerPoints = -1;
	WorldPoint location;
	Set<Integer> loadedRegions = new HashSet<>(),
		equippedItems = new HashSet<>();

	/**
	 * Bumped every {@link #refresh()}: per-tick memoisation key for expensive
	 * state conditions (npcNearby caches its scan against it).
	 */
	int refreshGeneration;

	void refresh()
	{
		var local = client.getLocalPlayer();
		if (local == null)
			return;
		refreshGeneration++;

		if (boundaryTicksLeft > 0)
			boundaryTicksLeft--;

		inWilderness = client.getVarbitValue(WILDERNESS_VARBIT) == 1;

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
			ticksSincePrayerDrain = 0;
		else if (ticksSincePrayerDrain < Integer.MAX_VALUE)
			ticksSincePrayerDrain++;
		lastPrayerPoints = prayerPoints;

		var previousLocation = location;
		location = local.getWorldLocation();
		if (location != null)
		{
			int newRegion = location.getRegionID();
			if (newRegion != regionId)
			{
				// Leaving is what turns a fresh memory into one worth
				// bringing up when you next come back.
				placesJustFiled.remove(regionId);
				incidentJustFiled = false;
				regionId = newRegion;
				noteRegionEntered(newRegion);
			}
			// Seen THIS tick, so pacing along a region boundary - which fires
			// the change over and over - never reads as a string of visits.
			regionLastSeenTick.put(newRegion, client.getTickCount());
		}

		if (previousLocation != null && previousLocation.equals(location) && local.getAnimation() == -1)
			idleTicks++;
		else
			idleTicks = 0;

		// Before combat, which asks whether this is thieving before deciding.
		refreshThieving(local);
		refreshCombat(local);

		refreshLoadedRegions();
		refreshEquipment(local.getPlayerComposition());
		refreshRepetition(local);
		ageQuestion();
		checkWant();
		refreshAttention();
		driftMood();
		noteWhatWeAreDoing();
	}

	// ------------------------------------------------------------------ combat

	int ticksSinceCombat = Integer.MAX_VALUE;
	String combatTarget = "";
	int combatTargetLevel;

	/** Names the boss rules watch for, and those names compiled. */
	Set<String> bossNames = Collections.emptySet();
	List<Pattern> bossPatterns =
		Collections.emptyList();

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
	static final Set<Integer> THIEVING_STUNS = Resources.ids("thievingStuns");

	/**
	 * How long the follower keeps its distance while the player stays put.
	 *
	 * <p>Much longer, because the cost is only that the follower stands a few
	 * tiles away. Anchored to WHERE the thieving happened rather than to a
	 * clock alone: it ends the moment the player walks off, which is the signal
	 * a person actually gives when they are done.
	 */

	int ticksSinceThieving = Integer.MAX_VALUE;
	WorldPoint thievingSpot;

	void refreshThieving(Player local)
	{
		int animation = local.getAnimation();
		// PICKPOCKET_ANIMATION: HUMAN_PICKPOCKET, 1.78s. The attempt itself.
		if (animation == 881 /* PICKPOCKET_ANIMATION */ || THIEVING_STUNS.contains(animation))
		{
			ticksSinceThieving = 0;
			thievingSpot = location;
		}
		else if (ticksSinceThieving < Integer.MAX_VALUE)
			ticksSinceThieving++;

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
			// THIEVING_SESSION_RADIUS: How far the player may drift from the last attempt before the session is
			// considered over.
			// <p>Five tiles was sized for a STALL - a fixed point you stand at. Elves
			// wander and you follow them, so five tiles ended the session every time
			// the mark walked, several times a minute: the follower announced the end
			// of a theft that was still going on, and cheered on the "fight" the next
			// failed attempt looked like. The anchor moves to the player on every
			// attempt, so this only has to cover the drift BETWEEN attempts.
			|| location.distanceTo(thievingSpot) > 12 /* THIEVING_SESSION_RADIUS */
			// THIEVING_SESSION_TICKS: How long a pause can run before the session is over. Two and a half
			// minutes rather than ninety seconds: opening a run of coin pouches or
			// drinking a stamina dose ran past the old figure, which ended the session
			// mid-run and let the next failed pickpocket read as a fight.
			|| ticksSinceThieving > 250 /* THIEVING_SESSION_TICKS */))
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
	void noteThievingIntent()
	{
		ticksSinceThieving = 0;
		if (location != null)
			thievingSpot = location;
		else
			awaitingThievingAnchor = true;
	}

	/** Set when intent arrived before there was a location to pin it to. */
	boolean awaitingThievingAnchor;

	/**
	 * The player has said they are about to fight something, which ends any
	 * theft outright.
	 *
	 * <p>The session deliberately outlives the gaps between attempts, and the
	 * cost of that is a window where a genuine fight goes unnoticed. An
	 * explicit Attack is the one unambiguous signal that the window should
	 * close now rather than when the player next wanders off.
	 */
	void noteFightIntent()
	{
		thievingSpot = null;
		awaitingThievingAnchor = false;
		ticksSinceThieving = Integer.MAX_VALUE;
	}

	/**
	 * Whether the player is mid-attempt, or was a moment ago. Used to keep a
	 * picked pocket from reading as a fight.
	 */
	boolean isThieving()
	{
		// THIEVING_GRACE_TICKS: How long a thieving ANIMATION counts as an attempt in progress.
		// <p>Only used to keep the session alive across the gap between attempts;
		// the session itself is what everything else asks about.
		return ticksSinceThieving <= 20 /* THIEVING_GRACE_TICKS */;
	}

	/**
	 * Whether the player is still working this spot, gaps between attempts
	 * included. Used to decide whether the follower keeps its distance.
	 */
	boolean isInThievingSession()
	{
		return thievingSpot != null;
	}

	void refreshCombat(Player local)
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
			return;

		var target = local.getInteracting();
		boolean facing = target instanceof NPC && target.getCombatLevel() > 0
			&& target.getHealthRatio() != 0;

		if (facing && blowsHaveBeenExchanged(local, target))
		{
			combatTarget = Strings.nullToEmpty(target.getName());
			combatTargetLevel = target.getCombatLevel();
			ticksSinceCombat = 0;
		}
		else if (ticksSinceCombat < Integer.MAX_VALUE)
			ticksSinceCombat++;
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
	boolean blowsHaveBeenExchanged(Player local, Actor target)
	{
		return target.getInteracting() == local || isInCombat();
	}

	/**
	 * Called by the plugin when the player takes a hit.
	 *
	 * <p>A hit is combat only if the player is ENGAGED - facing a levelled
	 * NPC. Taken alone it is not: running through an aggressive area collects
	 * hits from things the player is neither fighting nor going to fight, and
	 * every combat line is written for the player's own fight ("room to
	 * swing", "you make this look easy") - which is wrong in kind, not just
	 * in volume, when they are the one being chased. Play report: a night's
	 * transcript at 57% combat that was mostly the player getting hit
	 * running past aggro. Retaliation still starts a fight instantly, since
	 * attacking back is interacting; a hit landed by the player still counts
	 * through {@link #noteDamageDealt}; and a boss whose mechanics hit the
	 * player while they are targeting it is engaged by definition.
	 */
	void noteDamageTaken()
	{
		if (isInThievingSession())
			return;
		var local = client.getLocalPlayer();
		var target = local == null ? null : local.getInteracting();
		boolean engaged = target instanceof NPC && target.getCombatLevel() > 0;
		if (engaged)
			ticksSinceCombat = 0;
		else if (local != null)
			noteAttacker(local);
	}

	/**
	 * Who hit the player last while they were not fighting back, and when:
	 * the thing chasing them. A memory filed then is about it. Behaviour
	 * QA5, 19 Oct: run down in the Abyss with nothing targeted, the death
	 * was filed as "the time you died", and said so for two days.
	 */
	String attacker = "";
	int attackerTick = Integer.MIN_VALUE / 2;

	void noteAttacker(Player local)
	{
		var view = client.getTopLevelWorldView();
		var here = local.getWorldLocation();
		if (view == null || here == null)
			return;
		NPC nearest = null;
		int best = Integer.MAX_VALUE;
		for (var npc : view.npcs())
		{
			if (npc == null || npc.getInteracting() != local || npc.getCombatLevel() <= 0
				|| npc.getName() == null || npc.getWorldLocation() == null)
				continue;
			int distance = npc.getWorldLocation().distanceTo(here);
			if (distance < best)
			{
				best = distance;
				nearest = npc;
			}
		}
		if (nearest != null)
		{
			attacker = nearest.getName();
			attackerTick = client.getTickCount();
		}
	}

	/** What hit the player within the last {@code ticks} while they were not fighting it, or empty. */
	String getRecentAttacker(int ticks)
	{
		return client != null && client.getTickCount() - attackerTick <= ticks ? attacker : "";
	}

	/**
	 * The player landed a hit on something, which is the other way a fight
	 * starts - and the only signal available when the target dies before it
	 * can turn round, or cannot reach the player at all.
	 */
	void noteDamageDealt()
	{
		if (isInThievingSession())
			return;
		ticksSinceCombat = 0;
	}

	/** Whether the player is fighting, or was a moment ago. */
	boolean isInCombat()
	{
		// COMBAT_GRACE_TICKS: How long a fight is considered to still be going after the last sign of
		// one. Monsters die, targets are re-clicked, and a boss between phases can
		// leave a tick or two with no interaction at all; without a grace window
		// the follower would bob in and out of spectating throughout a kill.
		return ticksSinceCombat <= 8 /* COMBAT_GRACE_TICKS */;
	}

	/** Whether the player has fought in the last {@code ticks} ticks, or is fighting now. */
	boolean foughtWithin(int ticks)
	{
		return ticksSinceCombat <= ticks;
	}

	/** Whether that fight is with something big enough to call a boss. */
	/**
	 * The names the boss rules list, so that "boss" means one thing.
	 *
	 * <p>It used to mean combat level 100, in two places that had to be kept
	 * agreeing by hand, and the proxy simply fails for anything that fights
	 * above its level. Taking the names from the rules rather than from a
	 * second list in here means adding a boss rule is all it takes: the
	 * follower stands clear of it and cheers it going down without anybody
	 * remembering to say so twice.
	 */
	void setBossNames(Set<String> names)
	{
		if (names == bossNames)
			return;
		bossNames = names == null ? Collections.emptySet() : names;
		List<Pattern> compiled =
			new ArrayList<>(bossNames.size());
		for (String name : bossNames)
			compiled.add(Condition.compileName(name));
		bossPatterns = compiled;
	}

	boolean isNamedBoss(String name)
	{
		if (Strings.isNullOrEmpty(name))
			return false;
		String normalised = Condition.normaliseName(name);
		for (var pattern : bossPatterns)
		{
			if (pattern.matcher(normalised).matches())
				return true;
		}
		return false;
	}

	/** Big enough to be a boss, or named as one. Either will do. */
	boolean isBoss(String name, int level)
	{
		// BOSS_COMBAT_LEVEL: A rough line between "a monster" and "a boss", by combat level.
		return level >= 100 /* BOSS_COMBAT_LEVEL */ || isNamedBoss(name);
	}

	boolean isBossFight()
	{
		return isInCombat() && isBoss(combatTarget, combatTargetLevel);
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
	long minutesAway = -1;

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
	final Map<String, Integer> tallies = new HashMap<>();

	/**
	 * The best of each thing seen, keyed the same way. Separate from the
	 * tallies because the operation is different: one accumulates, the other
	 * only ever moves up, and a rule wants to know the moment it moved.
	 */
	final Map<String, Integer> records = new HashMap<>();

	/**
	 * Whether anything countable has moved since the last save.
	 *
	 * <p>The save is nine kilobytes of JSON at the cap and it used to happen
	 * every minute regardless. Most minutes nothing is counted at all: no kill,
	 * no level, no death, and the longest-session record only moves on a day
	 * that beats every previous one.
	 */
	boolean countersDirty;

	/** Marks the saved blob as needing a write, for state set from outside. */
	void markCountersDirty()
	{
		countersDirty = true;
	}

	void clearCountersDirty()
	{
		countersDirty = false;
	}

	/** Counts one, and returns the new total. */
	int tally(String what)
	{
		countersDirty = true;
		return tallies.merge(what, 1, Integer::sum);
	}

	int getTally(String what)
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
	static final String TODAY = ":today";

	/** Counts one against both the lifetime total and today's. */
	int tallyBoth(String what)
	{
		tally(what + TODAY);
		return tally(what);
	}

	/** Called at login: yesterday's session counters are not today's. */
	void clearDailyTallies()
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
	boolean noteRecord(String what, int value)
	{
		var previous = records.get(what);
		if (previous == null)
		{
			records.put(what, value);
			countersDirty = true;
			return false;
		}
		if (value <= previous)
			// The overwhelmingly common case, minute after minute: today is not
			// the longest day. Nothing changed, so nothing needs writing.
			return false;
		records.put(what, value);
		countersDirty = true;
		return true;
	}

	int getRecord(String what)
	{
		return records.getOrDefault(what, 0);
	}

	/** Drops a record that has done its job, such as a count now kept under another key. */
	void forgetRecord(String what)
	{
		if (records.remove(what) != null)
			countersDirty = true;
	}

	/**
	 * Each boss's name as the game writes it ("TzTok-Jad"), by the lower-case
	 * name its kill count is kept under, so the Memory window can show it
	 * the game's way rather than as "Tztok-jad".
	 */
	final Map<String, String> killCountNames = new HashMap<>();

	void noteKillCountName(String asWritten)
	{
		if (!asWritten.equals(killCountNames.put(asWritten.toLowerCase(Locale.ROOT), asWritten)))
			countersDirty = true;
	}

	/**
	 * How many times the follower has been started up with this player,
	 * this one included. Counted at login, so the first session is 1.
	 */
	int sessionCount;

	void setSessionCount(int sessionCount)
	{
		this.sessionCount = sessionCount;
		countersDirty = true;
	}

	/**
	 * Puts back what a previous session counted. Merged rather than replaced so
	 * a restore arriving after something has already been counted - a kill in
	 * the first ticks after login - does not throw that away.
	 */
	void restoreCounters(Map<String, Integer> savedTallies,
		Map<String, Integer> savedRecords)
	{
		if (savedTallies != null)
			savedTallies.forEach((key, value) -> tallies.merge(key, value, Integer::sum));
		if (savedRecords != null)
			savedRecords.forEach((key, value) -> records.merge(key, value, Math::max));
		// A restore merges rather than replaces, so what is now in memory is
		// not what is on disk - the next save has real work to do.
		countersDirty = true;
	}

	// -------------------------------------------------------------- wilderness

	/**
	 * Standing in the Wilderness, as a STATE - the crossing already has its
	 * occasion via the varbit event; this is what lets whole bark sets swap
	 * while we are in there (R18).
	 */
	boolean inWilderness;

	static final int WILDERNESS_VARBIT = 5963;

	// -------------------------------------------------------------- stay mode

	/**
	 * True while the follower is on a player-commanded Stay (R25) - not the
	 * stays its own machinery takes for errands, spectating or thrall work.
	 * Fed by the plugin each tick, because only the plugin can tell those
	 * apart. Stay is when the follower is most watched and least occupied,
	 * which is exactly where a little life pays best.
	 */
	boolean followerStaying;

	// ------------------------------------------------------------ npc dialogue

	/**
	 * True while an NPC dialogue box is up, and for the couple of ticks a page
	 * flip drops it. Fed by the plugin each tick, which reads the widgets and
	 * rides out the flips; a quest step that changes mid-conversation waits
	 * on this to fall, so its line arrives just after the box closes.
	 */
	boolean dialogueOpen;

	String speaker;
	int speakerTick;

	/**
	 * The NPC the player is talking to this tick, by the name the box shows.
	 * Not one speaking in a cutscene: the player did not walk up to them.
	 */
	void noteSpeaker(String name)
	{
		speaker = name;
		speakerTick = client.getTickCount();
	}

	/**
	 * Who the player was talking to in the last minute, or null. A line
	 * about them held through that talk is still about someone just met,
	 * though they are a few tiles off by the time it is said.
	 */
	String getRecentSpeaker()
	{
		// SPEAKER_TICKS: A minute: how long after the box the person in it still counts as just
		// talked to. Long enough for a line about them to wait out the gap and a
		// rest behind whatever the box's close raised, a quest's moment most often.
		return speaker != null && client.getTickCount() - speakerTick <= 100 /* SPEAKER_TICKS */ ? speaker : null;
	}

	// ------------------------------------------------------------ quest states

	/** Quest states read this tick, by enum name; cleared when the tick moves on. */
	final Map<String, Boolean> questsDone = new HashMap<>();
	int questsDoneGeneration = -1;

	/**
	 * Whether the named quest is finished, read the way ChronicleProgress
	 * reads it: the {@link net.runelite.api.Quest} constant's own state
	 * script. Asked for only when a rule gets that far, and remembered for
	 * the rest of the tick, so the script runs at most once a tick a quest.
	 *
	 * <p>False with no player, for a name that is no quest, and when the read
	 * throws (before the scripts are ready): a condition that threw would
	 * disable its rule for the session.
	 */
	boolean isQuestDone(String name)
	{
		if (name == null || client.getLocalPlayer() == null)
			return false;
		if (questsDoneGeneration != refreshGeneration)
		{
			questsDoneGeneration = refreshGeneration;
			questsDone.clear();
		}
		var done = questsDone.get(name);
		if (done == null)
		{
			done = readQuestDone(name);
			questsDone.put(name, done);
		}
		return done;
	}

	boolean readQuestDone(String name)
	{
		Quest quest;
		try
		{
			quest = Quest.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
		try
		{
			return quest.getState(client) == net.runelite.api.QuestState.FINISHED;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/**
	 * Quests the follower saw finish, and the day (an epoch day), under the
	 * key the chronicle gives them: a quest belongs to the account, where the
	 * memory belongs to the profile.
	 */
	final Map<String, Long> questsSeenFinished = new HashMap<>();

	void noteQuestSeenFinished(String key, long epochDay)
	{
		if (key != null && questsSeenFinished.putIfAbsent(key, epochDay) == null)
			countersDirty = true;
	}

	Long questSeenFinishedOn(String key)
	{
		return key == null ? null : questsSeenFinished.get(key);
	}

	/**
	 * Whether the follower was there for a quest, as the chronicle answers
	 * it: the day it saw the quest through, or null for one done before it
	 * came along. A finished quest is not enough for a line that remembers
	 * doing it together; most players did the early quests years ago.
	 */
	Function<String, Long> questWitness = quest -> null;

	void setQuestWitness(Function<String, Long> lookup)
	{
		questWitness = lookup == null ? quest -> null : lookup;
	}

	Long questWitnessedOn(String quest)
	{
		return quest == null ? null : questWitness.apply(quest);
	}

	// ---------------------------------------------------------- work sessions

	/**
	 * What the player is working at, as the skilling company sees it: a
	 * session opened by a click and proven by the animation or the XP, with
	 * the target and the drops it has counted. Fed by the plugin each tick,
	 * because the controller that keeps the session lives on the follower's
	 * side and this is the rules' side.
	 */
	boolean working;
	String workActivity = "",
		workTargetName = "";
	WorldPoint workTargetTile;
	int workTicks,
		xpDropsThisSession,
		ticksSinceXpDrop = Integer.MAX_VALUE;

	void setWork(boolean working, String activity, String targetName,
		WorldPoint targetTile, int ticks, int drops, int ticksSinceDrop)
	{
		this.working = working;
		workActivity = Strings.nullToEmpty(activity);
		workTargetName = Strings.nullToEmpty(targetName);
		workTargetTile = targetTile;
		workTicks = ticks;
		xpDropsThisSession = drops;
		ticksSinceXpDrop = ticksSinceDrop;
	}

	// --------------------------------------------------------------- boundaries

	/**
	 * A few ticks of breather after something ENDS (R6): combat, a level, an
	 * arrival, a thieving session, the bank. The research is blunt about
	 * timing - a line in the middle of a task is an interruption, the same
	 * line just after the task ends is company - and this is the state that
	 * lets rules aim at the second one.
	 */
	int boundaryTicksLeft;
	String boundaryKind = "";

	void noteBoundary(String kind)
	{
		boundaryKind = Strings.nullToEmpty(kind);
		// BOUNDARY_TICKS: How long an ending stays fresh enough to remark on: about six seconds.
		boundaryTicksLeft = 10 /* BOUNDARY_TICKS */;
	}

	boolean isAtBoundary()
	{
		return boundaryTicksLeft > 0;
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
	final Map<String, Integer> lineWear = new HashMap<>();

	static String wearKey(String template)
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
	void noteLineSaid(String template)
	{
		if (Strings.isNullOrEmpty(template))
			return;
		lineWear.merge(wearKey(template), 1, Integer::sum);
		// MAX_WORN_LINES: Entries kept. The least-worn are dropped first: they are the farthest
		// from ever standing a line aside, so forgetting them costs the least.
		while (lineWear.size() > 500 /* MAX_WORN_LINES */)
			lineWear.remove(Collections.min(lineWear.entrySet(),
				Map.Entry.comparingByValue()).getKey());
	}

	int lineWear(String template)
	{
		return Strings.isNullOrEmpty(template)
			? 0 : lineWear.getOrDefault(wearKey(template), 0);
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
	final Set<String> spokenOnce = new HashSet<>();

	boolean hasSaidOnce(String ruleId)
	{
		return ruleId != null && spokenOnce.contains(ruleId);
	}

	void noteSaidOnce(String ruleId)
	{
		if (ruleId != null && spokenOnce.add(ruleId))
		{
			spokenOnceOn.put(ruleId, LocalDate.now().toEpochDay());
			countersDirty = true;
		}
	}

	/**
	 * The day (an epoch day) each one-time line was said. A running bit's
	 * later stages count from the day its opener was heard, not from the
	 * first meeting: counted from the meeting, a follower already past the
	 * last stage's day when the bit arrived would open it and close it in
	 * the same session.
	 */
	final Map<String, Long> spokenOnceOn = new HashMap<>();

	/** Days since the one-time line was said, or -1 if it has not been. */
	int daysSinceSaid(String ruleId)
	{
		var on = ruleId == null ? null : spokenOnceOn.get(ruleId);
		if (on == null)
			return -1;
		long days = LocalDate.now().toEpochDay() - on;
		return days < 0 ? 0 : (int) min(days, Integer.MAX_VALUE);
	}

	/**
	 * The lines and, where saved, their days. A line saved before the days
	 * were kept is dated today: its bit then starts again from its first
	 * stage, which is a repeat, where dating it from the meeting could jump
	 * straight to the end.
	 */
	void restoreSpokenOnce(Collection<String> saved, Map<String, Long> on)
	{
		if (saved != null)
			spokenOnce.addAll(saved);
		if (on != null)
			spokenOnceOn.putAll(on);
		long today = LocalDate.now().toEpochDay();
		for (String id : spokenOnce)
		{
			if (spokenOnceOn.putIfAbsent(id, today) == null)
				countersDirty = true;
		}
	}

	// -------------------------------------------------------------- repeating

	int repeatingAnimation = -1,
		repeatingTicks,
		ticksSinceAnimation;

	void refreshRepetition(Player local)
	{
		int animation = local.getAnimation();

		if (animation == -1)
		{
			// A pause, not necessarily a stop. Hold the count for a moment.
			// REPEAT_GAP_GRACE_TICKS: How long the player has been doing the same thing, in ticks.
			// <p>Repetition is the texture of the game, and a companion that never
			// notices it is not watching. This needs to know nothing about trees or
			// rocks or fish: an animation repeating for minutes IS the activity, so
			// counting the ticks it has been running covers every skill at once,
			// including ones added later.
			// <p>Brief gaps are tolerated. Most gathering drops to no animation for a
			// tick between swings, and treating that as stopping would keep the count
			// at zero forever.
			if (repeatingAnimation != -1 && ++ticksSinceAnimation > 4 /* REPEAT_GAP_GRACE_TICKS */)
			{
				repeatingAnimation = -1;
				repeatingTicks = 0;
			}
			return;
		}

		ticksSinceAnimation = 0;
		if (animation == repeatingAnimation)
			repeatingTicks++;
		else
		{
			repeatingAnimation = animation;
			repeatingTicks = 1;
		}
	}

	// -------------------------------------------------------------- attention

	/**
	 * How many ticks the mouse has been resting on the follower.
	 *
	 * <p>Fed by the plugin, which already projects the clickbox every client
	 * tick to draw the hover hint, so knowing this costs nothing extra.
	 */
	int hoverTicks;

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
	String incidentKey = "",
		incidentPhrase = "";
	int incidentCount;

	/**
	 * The particulars: where it happened (a region id), when (epoch ms) and
	 * who was involved (an NPC name), so a recall can jog the player's memory
	 * rather than allude to it. "How close that was" is a hint; "how close
	 * that was with the Hill Giant, in Edgeville, three days ago" is a
	 * memory two people share.
	 */
	int incidentWhere;
	long incidentWhenMs;
	String incidentWho = "";

	/**
	 * And the rest of what was there to see: the place as said ("Edgeville",
	 * "the Wilderness"), whether that is only the nearest named place, the
	 * item (a drop) and the hitpoints left. Kept apart from the phrase so the
	 * memory can be said again, longer or shorter, in words written later.
	 */
	String incidentPlace = "";
	boolean incidentNear;
	String incidentItem = "";
	int incidentHp = -1;

	/** Place names by region id, from the Locations rules; for {memoryWhere}. */
	Map<Integer, String> regionNames = Collections.emptyMap();

	void setRegionNames(Map<Integer, String> names)
	{
		regionNames = names == null ? Collections.emptyMap() : names;
	}

	/**
	 * Files an incident, or counts another of the same, with the particulars.
	 * Another of the same kind keeps the count and takes the newest
	 * particulars: the memory it brings up is the freshest one, and the count
	 * is what makes "again" honest.
	 *
	 * @param key   what happened, for a rule to recognise
	 * @param phrase how to refer to it out loud - "that chicken"
	 * @param who the NPC involved, or empty
	 */
	void noteIncident(String key, String phrase, String who, long nowMs)
	{
		noteIncident(key, phrase, who, "", -1, nowMs);
	}

	/**
	 * @param item the item involved (a drop), or empty
	 * @param hp   the hitpoints left, or -1
	 */
	void noteIncident(String key, String phrase, String who, String item, int hp, long nowMs)
	{
		if (Strings.isNullOrEmpty(key))
			return;
		if (key.equals(incidentKey))
			incidentCount++;
		else
		{
			incidentKey = key;
			incidentCount = 1;
		}
		incidentPhrase = Strings.nullToEmpty(phrase);
		incidentWhere = regionId;
		incidentJustFiled = true;
		incidentRecalledMs = 0L;
		incidentWhenMs = nowMs;
		incidentWho = Strings.nullToEmpty(who);
		incidentItem = item == null ? "" : item;
		incidentHp = hp;
		incidentPlace = getPlaceHere();
		incidentNear = isPlaceHereNear();
		incidentToRetell = false;
		countersDirty = true;
	}

	/** Whether {@link #getIncidentPlace} is only the nearest named place, not the place itself. */
	boolean isIncidentNear()
	{
		return incidentNear && !incidentPlace.isEmpty();
	}

	/**
	 * The place the incident happened as a sentence says it: "Edgeville",
	 * "the God Wars Dungeon". Filed with the incident; a memory saved before
	 * places were filed reads its region's name from the Locations rules.
	 * Empty when there is none or no incident is held.
	 */
	String getIncidentPlace()
	{
		if (!incidentPlace.isEmpty())
			return incidentPlace;
		String place = placeName(incidentWhere);
		return place.isEmpty() ? "" : shortPlace(place);
	}

	/** "in Edgeville", or "near Lumbridge" for the nearest named place; empty when unknown. */
	String getIncidentWhereSaid()
	{
		return where(getIncidentPlace(), isIncidentNear());
	}

	static String where(String place, boolean near)
	{
		return place.isEmpty() ? "" : (near ? "near " : "in ") + place;
	}

	/** The name the Wilderness goes by, where no Locations rule names the spot. */
	static final String WILDERNESS = "the Wilderness";

	/**
	 * The place here, as a memory filed now would say it: this region's name
	 * from the Locations rules; failing that the Wilderness; failing that the
	 * nearest named region next door, which {@link #isPlaceHereNear} owns up
	 * to. Empty when there is nothing better than a region id.
	 */
	String getPlaceHere()
	{
		String place = placeName(regionId);
		if (!place.isEmpty())
			return shortPlace(place);
		if (inWilderness)
			return WILDERNESS;
		int next = namedNeighbour();
		return next == 0 ? "" : shortPlace(placeName(next));
	}

	/** Whether {@link #getPlaceHere} is a neighbouring region's name rather than this one's. */
	boolean isPlaceHereNear()
	{
		return placeName(regionId).isEmpty() && !inWilderness && namedNeighbour() != 0;
	}

	/** "in Edgeville", "near Lumbridge" or "" for here: the {where} a memory files. */
	String getWhereHere()
	{
		return where(getPlaceHere(), isPlaceHereNear());
	}

	/**
	 * The named region among the eight around this one whose middle is
	 * closest to where the player stands, or 0. A region is 64 tiles a side,
	 * so this is never more than a short walk from the name it borrows.
	 */
	int namedNeighbour()
	{
		if (regionId <= 0)
			return 0;
		int rx = regionId >> 8,
			ry = regionId & 0xFF,
			best = 0;
		long bestDistance = Long.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int x = rx + dx,
					y = ry + dy;
				if ((dx == 0 && dy == 0) || x < 0 || y < 0 || y > 0xFF)
					continue;
				int region = (x << 8) | y;
				if (placeName(region).isEmpty())
					continue;
				long distance = location == null ? abs(dx) + abs(dy)
					: square((x << 6) + 32 - location.getX()) + square((y << 6) + 32 - location.getY());
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = region;
				}
			}
		}
		return best;
	}

	static long square(long n)
	{
		return n * n;
	}

	String placeName(int region)
	{
		String name = region == 0 ? null : regionNames.get(region);
		if (name == null)
			return "";
		int cut = name.length();
		for (String stop : new String[]{":", ",", " - ", " ("})
		{
			int at = name.indexOf(stop);
			if (at > 0 && at < cut)
				cut = at;
		}
		return name.substring(0, cut).trim();
	}

	/**
	 * " in Edgeville", ready to follow the memory in a line; empty when
	 * unknown. The place as said in passing: "in the God Wars Dungeon",
	 * "in Lumbridge", never "in Lumbridge town and castle".
	 */
	String getMemoryWhere()
	{
		return memoryWhereFor(incidentPhrase);
	}

	/**
	 * The same, for the memory said as {@code phrase}: empty when the
	 * phrase names the place already ("your death in Edgeville").
	 */
	String memoryWhereFor(String phrase)
	{
		String place = getIncidentPlace();
		if (place.isEmpty() || (phrase != null && phrase.contains(place)))
			return "";
		return " " + getIncidentWhereSaid();
	}

	/**
	 * A place as the middle of a sentence names it. The Locations rules name
	 * places descriptively: "Lumbridge town and castle" is Lumbridge, "The
	 * Barrows mounds" is the Barrows.
	 */
	static String shortPlace(String place)
	{
		String name = place.trim();
		int and = name.indexOf(" and ");
		if (and > 0)
			name = name.substring(0, and);
		if (name.startsWith("The "))
			name = "the " + name.substring(4);
		for (String tail : new String[]{" town", " plaza", " mounds", " lobby area", " camp"})
		{
			if (name.endsWith(tail) && name.length() > tail.length() + 4)
			{
				name = name.substring(0, name.length() - tail.length());
				break;
			}
		}
		return name;
	}

	/** "just now", "three days ago", or "a while ago" for a memory with no date. */
	String getMemoryWhen(long nowMs)
	{
		if (!hasIncident())
			return "";
		if (incidentWhenMs <= 0 || incidentWhenMs > nowMs)
			return "a while ago";
		long ago = nowMs - incidentWhenMs;
		return ago < 120_000L ? "just now" : Words.duration(ago) + " ago";
	}

	/**
	 * When it happened, the way somebody would say it in passing: "earlier
	 * today", "yesterday", "three days ago", "last week", "a while back".
	 * By the calendar on your clock, so last night is yesterday. An old
	 * record with no date is a while back. Empty with no incident.
	 */
	String getMemoryAgo(long nowMs)
	{
		return getMemoryAgo(nowMs, ZoneId.systemDefault());
	}

	String getMemoryAgo(long nowMs, ZoneId zone)
	{
		if (!hasIncident())
			return "";
		if (incidentWhenMs <= 0 || incidentWhenMs > nowMs)
			return AGO_LONG;
		long days = java.time.temporal.ChronoUnit.DAYS.between(
			Instant.ofEpochMilli(incidentWhenMs).atZone(zone).toLocalDate(),
			Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate());
		if (days <= 0)
			return "earlier today" /* AGO_TODAY */;
		if (days == 1)
			return "yesterday" /* AGO_YESTERDAY */;
		if (days < 7)
			return Words.number((int) days) + " days ago" /* AGO_DAYS */;
		return days < 14 ? "last week" /* AGO_LAST_WEEK */ : AGO_LONG;
	}

	// The words {memoryAgo} says. Awaiting the player's approval, and said
	// by no shipped line until then: see the memories text review.
	static final String AGO_LONG = "a while back";

	/**
	 * Filed in this region and not left since: still the visit it happened
	 * on. QA, 15 Oct: "Here again, and I've not forgotten the orb breaking"
	 * was said twelve seconds after the orb broke, to somebody still there.
	 */
	boolean incidentJustFiled;

	/**
	 * How old a memory has to be before it is brought up: twenty minutes.
	 * Quests QA5, 20 Oct: "Persten went into the rift. I am not writing
	 * anything for a moment." and, 15 s later, "My mind goes back to Persten
	 * going into the rift, earlier today." Leaving the region was no wait at
	 * all where quest steps and boats cross borders every minute.
	 */
	static final long MEMORY_MIN_AGE_MS = 20L * 60 * 1000;

	/** The same in game ticks, for what a place holds, which is kept without a date. */
	static final int MEMORY_MIN_AGE_TICKS = (int) (MEMORY_MIN_AGE_MS / 600);

	/** Filed less than {@link #MEMORY_MIN_AGE_MS} before {@code nowMs}: too fresh to be a memory yet. */
	boolean isIncidentFresh(long nowMs)
	{
		return hasIncident() && incidentWhenMs > 0 && incidentWhenMs <= nowMs
			&& nowMs - incidentWhenMs < MEMORY_MIN_AGE_MS;
	}

	boolean isFresh(int filedTick)
	{
		return filedTick >= 0 && tickNow() - filedTick < MEMORY_MIN_AGE_TICKS;
	}

	int tickNow()
	{
		return client == null ? 0 : client.getTickCount();
	}

	/**
	 * When the incident was last brought up unprompted, as epoch millis, or
	 * 0. Kept with the memory. Text QA5, 18 Oct: the Castle Wars death was
	 * brought up eighteen times in one day, the only memory there was.
	 */
	long incidentRecalledMs;

	void noteIncidentRecalled(long nowMs)
	{
		incidentRecalledMs = nowMs;
		countersDirty = true;
	}

	/** From the stored memory, after {@link #restoreIncident}. */
	void restoreIncidentRecalled(long epochMs)
	{
		incidentRecalledMs = max(0L, epochMs);
	}

	/** Whether the incident was brought up unprompted already today, by the calendar on your clock. */
	boolean wasIncidentRecalledToday(long nowMs)
	{
		var zone = ZoneId.systemDefault();
		return incidentRecalledMs > 0
			&& Instant.ofEpochMilli(incidentRecalledMs).atZone(zone).toLocalDate()
				.equals(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate());
	}

	/** Back in the region the incident happened in. */
	boolean isAtTheIncident()
	{
		return hasIncident() && incidentWhere != 0 && incidentWhere == regionId && !incidentJustFiled;
	}

	/**
	 * Where a death costs nothing and is given back: Castle Wars, its arena
	 * and the tunnels under it, and Last Man Standing's maps, as RuneLite's
	 * loot tracker lists them (LAST_MAN_STANDING_REGIONS, 1.13.1). The user,
	 * 20 Oct: no grief, no memory, no mark. Text QA5: a Castle Wars death was
	 * mourned, filed, and brought up 24 times.
	 */
	static final Set<Integer> SAFE_DEATH_REGIONS = Resources.ids("safeDeathRegions");

	boolean isSafeDeathHere()
	{
		return SAFE_DEATH_REGIONS.contains(regionId);
	}

	/** Whether the NPC the incident was with has one of its kind within so many tiles. */
	boolean isIncidentFoeNearby(int within)
	{
		if (!hasIncident() || incidentWho.isEmpty())
			return false;
		return isNpcNearby(npc -> npc.getName() != null && npc.getName().equalsIgnoreCase(incidentWho),
			within);
	}

	boolean hasIncident()
	{
		return !incidentKey.isEmpty();
	}

	/** Puts back the incident a previous session was still thinking about. */
	void restoreIncident(String key, String phrase, int count, int where, long whenMs,
		String who)
	{
		restoreIncident(key, phrase, count, where, whenMs, who, null, false, null, -1);
	}

	/**
	 * With every particular. A save from before places, items and hitpoints
	 * were filed brings nulls and -1: the place is then read off the region.
	 */
	void restoreIncident(String key, String phrase, int count, int where, long whenMs,
		String who, String place, boolean near, String item, int hp)
	{
		if (Strings.isNullOrEmpty(key))
			return;
		incidentKey = key;
		incidentPhrase = Strings.nullToEmpty(phrase);
		incidentCount = max(1, count);
		incidentWhere = where;
		incidentJustFiled = false;
		incidentWhenMs = whenMs;
		incidentWho = Strings.nullToEmpty(who);
		incidentPlace = Strings.nullToEmpty(place);
		incidentNear = near;
		incidentItem = item == null ? "" : item;
		incidentHp = hp;
		incidentToRetell = isLegacy(incidentPhrase);
	}

	/**
	 * Puts a better way of saying the incident in place of the old words it
	 * was saved with, once the engine has worked one out from the particulars.
	 */
	void retellIncident(String phrase)
	{
		incidentToRetell = false;
		if (!Strings.isNullOrEmpty(phrase) && !phrase.equals(incidentPhrase))
		{
			incidentPhrase = phrase;
			countersDirty = true;
		}
	}

	/** Whether the incident came back from a save in words from before memories named the moment. */
	boolean isIncidentToRetell()
	{
		return incidentToRetell && hasIncident();
	}

	boolean incidentToRetell;

	/**
	 * The fallbacks the memory rules filed before they named the moment.
	 * "I was rereading the entry on the way that went" told the player
	 * nothing. A memory in these words is never said: a restore says it
	 * again from its particulars if it can, and otherwise it waits, unsaid,
	 * to be replaced by the next incident.
	 */
	static final Set<String> VAGUE = Resources.words("vague");

	/** The old forms that named only the foe, which a restore says again in the current words. */
	static final String[] LEGACY_PREFIXES = {"how close that was with ", "the way that went with "};

	/** Whether a memory said this way would tell the player nothing about which moment it was. */
	static boolean isVague(String phrase)
	{
		return Strings.isNullOrEmpty(phrase) || VAGUE.contains(phrase);
	}

	/**
	 * The memory rules' plain forms: what is filed when nothing particular
	 * was known. A memory filed so is not brought up, though a particular
	 * one can still be said this way when nothing longer fits. Behaviour
	 * QA5, 19-20 Oct: "I was rereading the entry on the time you died." six
	 * times, about a death in the Abyss.
	 */
	static final Set<String> PLAIN = Resources.words("plain");

	/** Whether a memory filed this way was filed knowing nothing particular about the moment. */
	static boolean isPlain(String phrase)
	{
		return PLAIN.contains(phrase);
	}

	static boolean isLegacy(String phrase)
	{
		if (isVague(phrase) || isPlain(phrase))
			return true;
		for (String prefix : LEGACY_PREFIXES)
		{
			if (phrase.startsWith(prefix))
				return true;
		}
		return false;
	}

	/** What the follower brings up about a place, keyed the same way. */
	final Map<Integer, String> placeMemories = new HashMap<>();

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
	final Set<Integer> placesJustFiled = new HashSet<>();

	/** The tick each place's memory was filed on in this session, for {@link #MEMORY_MIN_AGE_TICKS}. */
	final Map<Integer, Integer> placeFiledTick = new HashMap<>();

	/** Records what happened here, for a rule to bring up next time. */
	void notePlaceMemory(String phrase)
	{
		if (Strings.isNullOrEmpty(phrase) || regionId == 0)
			return;
		placeMemories.put(regionId, phrase);
		placesJustFiled.add(regionId);
		placeFiledTick.put(regionId, tickNow());
		countersDirty = true;
		trimPlaces();
	}

	/**
	 * Drops the places it feels least strongly about once there are too many.
	 * A follower with an opinion about everywhere has an opinion about nowhere,
	 * and the saved blob has to stay a sensible size.
	 */
	void trimPlaces()
	{
		// MAX_PLACES: How many places it can hold an opinion about, oldest-weakest evicted.
		while (placeMemories.size() > 60 /* MAX_PLACES */)
			placeMemories.remove(placeMemories.keySet().iterator().next());
	}

	void restorePlaces(
		Map<Integer, String> memories)
	{
		placeMemories.clear();
		if (memories != null)
			placeMemories.putAll(memories);
	}

	/**
	 * Something the follower remembers about this place, or empty.
	 *
	 * <p>Empty while the memory is still fresh from this visit, or less than
	 * twenty minutes old: a thing that happened a moment ago is not a thing
	 * the place reminds you of.
	 */
	String getPlaceMemory()
	{
		if (placesJustFiled.contains(regionId) || isFresh(placeFiledTick.getOrDefault(regionId, -1)))
			return "";
		String here = placeMemories.get(regionId);
		return here == null || isVague(here) ? "" : here;
	}

	boolean hasPlaceMemory()
	{
		return !getPlaceMemory().isEmpty();
	}

	/**
	 * Whether the wished-for thing is actually in the player's bag right now.
	 * A wish with no item list is grantable on word alone.
	 */
	/**
	 * Whether any of these items is in the inventory now. False before the
	 * inventory has loaded, so a rule never rises on an empty read.
	 */
	boolean isInInventory(Collection<Integer> ids)
	{
		var inventory =
			client.getItemContainer(InventoryID.INV);
		if (inventory == null || ids == null)
			return false;
		for (var item : inventory.getItems())
		{
			if (item != null && ids.contains(item.getId()))
				return true;
		}
		return false;
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
	boolean wantOpen;
	int wantRegion = -1;
	String wantLabel = "";
	int wantDeadlineTick;

	/**
	 * Asks for somewhere. Ignored while a want is already open, so a rule that
	 * fires twice extends nothing and a second rule cannot quietly replace the
	 * first - the follower asked for one thing, and that is the thing.
	 */
	void setWant(int region, String label, int minutes)
	{
		if (wantOpen)
			return;
		// Asking to be taken somewhere you are already standing, and then being
		// delighted about arriving, is not a companion with a wish - it is a
		// companion that cannot see out of the window. The check has to be here
		// rather than in the rule, because a rule has no way to say "anywhere
		// except where we are".
		if (region == regionId)
			return;
		wantOpen = true;
		wantRegion = region;
		wantLabel = Strings.nullToEmpty(label);
		wantDeadlineTick = minutesFromNow(minutes);

		// Logged at INFO rather than debug, and so are both endings. A want
		// happens at most once every twenty minutes, so this is three lines a
		// session - and when one does not land the way it should, the log is
		// the only place that can say whether it opened, what it was watching
		// for, and where the player actually was.
	}

	boolean isWanting()
	{
		return wantOpen;
	}

	/** Ticks left before the open want gives up, or 0. */
	int getWantTicksLeft()
	{
		return wantOpen ? max(0, wantDeadlineTick - client.getTickCount()) : 0;
	}

	/**
	 * Takes what has just come of something, if anything: of a want, the
	 * souvenir, a challenge, advice, a bet, or the player's click underfoot.
	 * Consumed by the engine once per tick and turned into an event, so each
	 * is announced exactly once.
	 */
	TriggerEvent.Type poll(String kind)
	{
		return outcomes.remove(kind);
	}

	/**
	 * What has just come of each kind ("want", "souvenir", "challenge",
	 * "advice", "bet", "underfoot"), until polled: the latest.
	 */
	final Map<String, TriggerEvent.Type> outcomes = new HashMap<>();

	void checkWant()
	{
		if (!wantOpen)
			return;
		if (regionId == wantRegion)
		{
			wantOpen = false;
			outcomes.put("want", TriggerEvent.Type.WANT_FULFILLED);
			return;
		}
		if (client.getTickCount() >= wantDeadlineTick)
		{
			wantOpen = false;
			outcomes.put("want", TriggerEvent.Type.WANT_EXPIRED);
		}
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
	long metOnDay;

	/** Days since the first meeting, or 0 if it has not been recorded yet. */
	int getDaysKnown()
	{
		if (metOnDay <= 0)
			return 0;
		long days = LocalDate.now().toEpochDay() - metOnDay;
		return days < 0 ? 0 : (int) min(days, Integer.MAX_VALUE);
	}

	/**
	 * Whether today is the same day of the year as the first meeting - and not
	 * the first meeting itself, which is not an anniversary of anything.
	 */
	boolean isAnniversary()
	{
		if (metOnDay <= 0 || getDaysKnown() < 300)
			return false;
		LocalDate met = LocalDate.ofEpochDay(metOnDay),
			today = LocalDate.now();
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
	int wornValue,
		metWearingValue = -1;

	/**
	 * How many times better dressed the player is than the day they met,
	 * or 0 while there is nothing to compare against.
	 */
	int getTimesBetterDressed()
	{
		if (metWearingValue <= 0 || wornValue <= 0)
			return 0;
		return wornValue / max(1, metWearingValue);
	}

	// ---------------------------------------------------------- the levels

	/**
	 * When each skill last went up, as epoch milliseconds keyed by the skill
	 * name in lower case. Kept between sessions, because the gap is the
	 * point: "eleven days since the last one" is something only somebody
	 * who was there for the last one can say.
	 */
	final Map<String, Long> levelStamps = new HashMap<>();

	/**
	 * Files a level and returns how long it had been since the last one in
	 * that skill, or -1 when there is no stamp to measure from.
	 */
	long noteLevel(String skill, int level, long nowMs)
	{
		if (Strings.isNullOrEmpty(skill))
			return -1;
		var previous = levelStamps.put(skill.toLowerCase(Locale.ROOT), nowMs);
		countersDirty = true;
		noteDoing("getting " + skill + " to " + level);
		return previous == null || previous > nowMs ? -1 : nowMs - previous;
	}

	/** How long since any skill went up, or -1 with nothing on record. */
	long sinceAnyLevel(long nowMs)
	{
		long latest = -1;
		for (long stamp : levelStamps.values())
			latest = max(latest, stamp);
		return latest < 0 || latest > nowMs ? -1 : nowMs - latest;
	}

	// ------------------------------------------------------------- goodwill

	/** Half an hour of owing the player one. */
	static final int FAVOUR_TICKS = 3000;

	int favourUntilTick;

	/**
	 * The player did the thing the follower asked for - went where it wanted,
	 * handed over what it wished for. People expect a favour returned, from
	 * software as readily as from anybody, and feel it when it is; so for a
	 * while after, the follower is visibly more attentive. The body reads
	 * {@link #isGrateful} for that; the rules read it as a condition.
	 */
	void noteFavour()
	{
		favourUntilTick = client.getTickCount() + FAVOUR_TICKS;
	}

	boolean isGrateful()
	{
		return client.getTickCount() < favourUntilTick;
	}

	// ------------------------------------------------------------ last time

	/**
	 * The last thing the two of them were doing, in words a greeting can use:
	 * "the woodcutting", "fighting Vorkath", "getting Fishing to 70". The
	 * current session's goes to disk; the previous session's comes back at
	 * login as {@link #getLastTime}, which is what makes the greeting about
	 * last time rather than about the clock.
	 */
	String lastThing = "",
		lastTime = "";

	void noteDoing(String what)
	{
		if (Strings.isNullOrEmpty(what) || what.equals(lastThing))
			return;
		lastThing = what;
		countersDirty = true;
	}

	void restoreLastThing(String saved)
	{
		if (saved == null || saved.isEmpty())
			return;
		lastTime = saved;
		// Carried forward too, so a session in which nothing nameable
		// happens does not wipe the memory on its way out.
		if (lastThing.isEmpty())
			lastThing = saved;
	}

	/** The two things the context can see for itself: the work, and a boss. */
	void noteWhatWeAreDoing()
	{
		if (isWorking() && !workActivity.isEmpty())
			noteDoing("the " + workActivity);
		else if (isInCombat() && isBossFight() && !combatTarget.isEmpty())
			noteDoing("fighting " + combatTarget);
	}

	// ------------------------------------------------------------ addressed

	/**
	 * Where the follower is standing, fed by the plugin each tick, so a
	 * condition can ask whether the player is addressing it: within a tile
	 * and turned toward it. An emote done AT the follower is a different
	 * thing from an emote done near it, and the difference is the difference
	 * between an echo and a reply.
	 */
	WorldPoint followerLocation;

	boolean isAddressingFollower()
	{
		var local = client.getLocalPlayer();
		var here = local == null ? null : local.getWorldLocation();
		if (here == null || followerLocation == null || here.getPlane() != followerLocation.getPlane()
			|| here.distanceTo(followerLocation) != 1)
			return false;
		int toward = bearing(followerLocation.getX() - here.getX(),
			followerLocation.getY() - here.getY());
		int d = (local.getOrientation() - toward) & 0x7ff;
		// FACING_TOLERANCE: Within this much of the exact bearing counts as facing it.
		return min(d, 2048 - d) <= 300 /* FACING_TOLERANCE */;
	}

	/**
	 * The orientation that faces (dx, dy), on the actor circle: 0 south, 512
	 * west, 1024 north, 1536 east - the inverse of the facing vector
	 * (-sin, -cos) the entity uses.
	 */
	static int bearing(int dx, int dy)
	{
		return (int) round(atan2(-dx, -dy) / (2.0 * PI) * 2048.0) & 0x7ff;
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
	void noteUnderfoot()
	{
		outcomes.put("underfoot", TriggerEvent.Type.UNDERFOOT);
	}

	// ------------------------------------------------------- gone, or just still

	/**
	 * Whether the player has stopped touching the controls, as distinct from
	 * standing still on purpose.
	 *
	 * <p>{@code idle} cannot tell those apart: a player at a furnace and a
	 * player who has gone to make tea look identical to it, and the follower
	 * saying "still here, still standing" to an empty chair is only funny by
	 * accident. The camera is one tell; the feet and hands are the others -
	 * a player who just moved or animated is at the keyboard whatever the
	 * camera is doing, which matters because plenty of real play (and every
	 * simulation) runs minutes at a time on an untouched camera.
	 */
	int cameraX = Integer.MIN_VALUE,
		cameraY,
		cameraPitch,
		stillTicks;

	/** Evidence of presence from outside the snapshot: a real event arrived. */
	void noteAttention()
	{
		stillTicks = 0;
	}

	void refreshAttention()
	{
		int x = client.getCameraX(),
			y = client.getCameraY(),
			pitch = client.getCameraPitch();
		if (cameraX == Integer.MIN_VALUE)
		{
			cameraX = x;
			cameraY = y;
			cameraPitch = pitch;
			return;
		}
		boolean cameraMoved = x != cameraX || y != cameraY || pitch != cameraPitch;
		if (cameraMoved)
		{
			cameraX = x;
			cameraY = y;
			cameraPitch = pitch;
		}
		// Runs after the idle update on purpose: idleTicks at zero means the
		// player moved or animated THIS tick, which is presence too.
		if (cameraMoved || idleTicks == 0)
		{
			stillTicks = 0;
			return;
		}
		if (stillTicks < Integer.MAX_VALUE)
			stillTicks++;
	}

	/** Ticks since the camera last moved. */
	int getUnattendedTicks()
	{
		return stillTicks;
	}

	/** The tick each animation the player played was last played. */
	final Map<Integer, Integer> actTicks = new HashMap<>();

	/**
	 * Whether the player has played any of these since that tick: for a
	 * warning kept waiting, already answered. Behaviour QA, 16 Oct: "Get
	 * out!" was said after the teleport, and a minute later the advice was
	 * called ignored.
	 */
	boolean actedSince(Collection<Integer> ids, int tick)
	{
		for (var id : ids)
		{
			var at = actTicks.get(id);
			if (at != null && at >= tick)
				return true;
		}
		return false;
	}

	/** Offered every animation the player plays, and settles advice outstanding. */
	void offerAct(int animationId)
	{
		actTicks.put(animationId, client.getTickCount());
	}
	// ----------------------------------------------------------- the clock

	/**
	 * The hour on the player's own wall, 0-23.
	 *
	 * <p>Nothing else in the game acknowledges the room the player is sitting
	 * in, which is exactly why a follower noticing it lands the way it does.
	 */
	int getHourOfDay()
	{
		return java.time.LocalTime.now().getHour();
	}

	/** The tick so many minutes from now, one at least: a hundred ticks to the minute. */
	int minutesFromNow(int minutes)
	{
		return client.getTickCount() + max(1, minutes) * 100;
	}

	/**
	 * Today's date on the same wall, packed month*100+day so a range compare
	 * can wrap past new year the way timeOfDay wraps past midnight.
	 */
	int getMonthDay()
	{
		var today = LocalDate.now();
		return today.getMonthValue() * 100 + today.getDayOfMonth();
	}

	/** Minutes this session has run, fed by the plugin's own timer. */
	int sessionMinutes;

	// ----------------------------------------------------------- conversation

	/**
	 * How long the follower keeps hoping for an answer.
	 *
	 * <p>Two minutes. It was twelve seconds when the answer was a word typed
	 * into public chat, which is about how long typing "yes" takes. Answering
	 * now means walking over and right-clicking Talk-to, and a window sized for
	 * typing would have expired somewhere around the second click.
	 */
	static final int ANSWER_WINDOW_TICKS = 200;

	int ticksSinceQuestion = Integer.MAX_VALUE;

	/**
	 * The dialog tree that answers the open question, or empty.
	 *
	 * <p>This is what makes Talk-to mean something different for a minute or
	 * two: the follower asked, so the conversation it opens is the one about
	 * what it asked, and the everyday script waits its turn.
	 */
	String askedTree = "";

	/** Called when a rule carrying {@code asks} speaks. */
	void noteQuestion(String treeId)
	{
		ticksSinceQuestion = 0;
		askedTree = Strings.nullToEmpty(treeId);
	}

	/** Called once the player has answered, or declined to. */
	void noteAnswered()
	{
		ticksSinceQuestion = Integer.MAX_VALUE;
		askedTree = "";
		// Engaging with a question clears the ignored ledger: the back-off
		// exists for a player who is not answering, not one who just did.
		ignoredAsks = 0;
		askBackoffUntilTick = 0;
	}

	boolean isAwaitingAnswer()
	{
		return ticksSinceQuestion <= ANSWER_WINDOW_TICKS;
	}

	/** The tree Talk-to should open right now, or empty for the everyday one. */
	String getAskedTree()
	{
		return isAwaitingAnswer() ? askedTree : "";
	}

	void ageQuestion()
	{
		if (ticksSinceQuestion < Integer.MAX_VALUE)
			ticksSinceQuestion++;
		// The question just lapsed with nobody answering. That is a signal,
		// not a non-event: a transcript showed fifteen outings asked into one
		// AFK afternoon, each politely expiring and each politely re-asked at
		// the next cooldown, which added up to nagging. A hint dropped and
		// ignored earns a long pause; two of them end the asking for the day.
		if (ticksSinceQuestion == ANSWER_WINDOW_TICKS + 1 && !askedTree.isEmpty())
		{
			askedTree = "";
			ignoredAsks++;
			// ASK_BACKOFF_TICKS: Two hours before an ignored question may be followed by another.
			askBackoffUntilTick = client.getTickCount() + 12_000 /* ASK_BACKOFF_TICKS */;
		}
	}

	// ------------------------------------------------------- ignored overtures


	int ignoredAsks,
		askBackoffUntilTick;

	/**
	 * Whether rules that ASK something should hold their tongue: a question
	 * was ignored recently, or two have been ignored this session. Answering
	 * any question resets both.
	 */
	boolean isAskBackedOff()
	{
		// MAX_IGNORED_ASKS: Ignored questions per session before the asking stops altogether.
		return ignoredAsks >= 2 /* MAX_IGNORED_ASKS */
			|| client.getTickCount() < askBackoffUntilTick;
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
	static final int MOOD_NEUTRAL = 50;

	int mood = MOOD_NEUTRAL,
		ticksSinceMoodDrift;

	/**
	 * Nudges the mood and clamps it. Called when a rule carrying a nudge fires.
	 *
	 * @return the mood after the nudge
	 */
	int adjustMood(int delta)
	{
		int before = mood;
		mood = max(0 /* MOOD_MIN */, min(100 /* MOOD_MAX */, mood + delta));
		if (mood != before)
		{
		}
		return mood;
	}

	/**
	 * The band the mood falls in, which is what rules should normally ask for -
	 * a name reads better in a rule file than a number, and the boundaries can
	 * move without every rule having to.
	 */
	String getMoodBand()
	{
		if (mood <= 20)
			return "low";
		if (mood <= 40)
			return "down";
		if (mood < 60)
			return "even";
		if (mood < 80)
			return "good";
		return "high";
	}

	/**
	 * Pulls the mood back toward neutral a point at a time, so nothing that
	 * happens is permanent and a bad stretch is recovered from by carrying on.
	 */
	void driftMood()
	{
		if (mood == MOOD_NEUTRAL)
		{
			ticksSinceMoodDrift = 0;
			return;
		}
		// MOOD_DRIFT_TICKS: Ticks between one point of drift back toward neutral: about 30 seconds.
		if (++ticksSinceMoodDrift < 50 /* MOOD_DRIFT_TICKS */)
			return;
		ticksSinceMoodDrift = 0;
		mood += mood > MOOD_NEUTRAL ? -1 : 1;
	}

	// ----------------------------------------------------------------- memory

	final Map<Integer, Integer> regionVisits = new HashMap<>(),
		regionLastSeenTick = new HashMap<>();

	WorldPoint deathLocation;
	int deathTick = -1;

	void noteRegionEntered(int region)
	{
		var lastSeen = regionLastSeenTick.get(region);
		// REVISIT_GAP_TICKS: How long a region has to go unvisited before coming back counts as a
		// RETURN rather than a wander along its edge. Region boundaries run through
		// the middle of towns, so without this a trip across Varrock square would
		// tally half a dozen visits.
		if (lastSeen == null || client.getTickCount() - lastSeen > 250 /* REVISIT_GAP_TICKS */)
			regionVisits.merge(region, 1, Integer::sum);
	}

	/** Session visits to the CURRENT region, for the returnVisit condition. */
	int getRegionVisits()
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
	void noteDeath(WorldPoint where)
	{
		deathLocation = where;
		deathTick = client.getTickCount();
		countersDirty = true;
	}

	/**
	 * Puts back a death from a previous session. The arming delay is spent:
	 * whenever it was, it was not this walk back.
	 */
	void restoreDeathSpot(WorldPoint where)
	{
		deathLocation = where;
		deathTick = Integer.MIN_VALUE / 2;
	}

	/**
	 * Whether the player is standing near where they last died - and long
	 * enough after the death that being there again is a RETURN, not the
	 * dying itself or the walk back for the gravestone.
	 */
	boolean isNearDeathSpot(int within)
	{
		return deathLocation != null && location != null
			// DEATH_SPOT_ARM_TICKS: Death-site lines stay quiet this long after the death itself, since the
			// first person at the spot is the dying player.
			&& client.getTickCount() - deathTick > 200 /* DEATH_SPOT_ARM_TICKS */
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
	boolean playerReady;

	/** Run energy as a whole percentage, 0-100. */
	int energyPercent = 100;

	boolean skulled;

	/** The raw arrays behind the two sets, kept so an unchanged tick costs nothing. */
	int[] lastRegionIds,
		lastEquipmentIds;

	/**
	 * The loaded map regions, rebuilt only when they actually change.
	 *
	 * <p>They change on a chunk load and are identical on every other tick, so
	 * boxing the array into a fresh set each time was allocating for no reason
	 * several times a second for the whole session.
	 */
	void refreshLoadedRegions()
	{
		var view = client.getTopLevelWorldView();
		if (view == null)
			// Between worlds: the regions we last knew about still stand.
			return;
		int[] regions = view.getMapRegions();
		if (Arrays.equals(regions, lastRegionIds))
			return;
		lastRegionIds = regions == null ? null : regions.clone();

		Set<Integer> rebuilt = new HashSet<>();
		if (regions != null)
		{
			for (int region : regions)
				rebuilt.add(region);
		}
		loadedRegions = rebuilt;
	}

	void refreshEquipment(PlayerComposition composition)
	{
		playerReady = composition != null;
		int[] ids = composition == null ? null : composition.getEquipmentIds();

		// Same reasoning as the regions: worn gear is the same array tick after
		// tick, and the old code rebuilt a set and compared it just to decide
		// whether to log.
		if (Arrays.equals(ids, lastEquipmentIds))
			return;
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
					worn.add(raw - PlayerComposition.ITEM_OFFSET);
			}
		}
		equippedItems = worn;
	}

	int getHitpointsPercent()
	{
		return maxHitpoints <= 0 ? 100 : (hitpoints * 100) / maxHitpoints;
	}

	int getPrayerPercent()
	{
		return maxPrayerPoints <= 0 ? 100 : (prayerPoints * 100) / maxPrayerPoints;
	}

	boolean isPrayerActive()
	{
		// PRAYER_DRAIN_MEMORY_TICKS: Prayer counts as "active" if points have dropped within this many ticks.
		return ticksSincePrayerDrain <= 5 /* PRAYER_DRAIN_MEMORY_TICKS */;
	}

	boolean isRegionLoaded(int region)
	{
		return loadedRegions.contains(region);
	}

	boolean isEquipped(int itemId)
	{
		return equippedItems.contains(itemId);
	}

	boolean isNpcNearby(Predicate<NPC> predicate, int within)
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
	boolean isNpcNearby(Predicate<NPC> predicate, int within,
		boolean requireVisible)
	{
		var view = client.getTopLevelWorldView();
		if (view == null)
			// Mid-login the scene is not there to search, and a rule that
			// throws here is disabled for the session - an expensive way to
			// answer a question whose honest answer is no.
			return false;
		for (var npc : view.npcs())
		{
			if (npc == null)
				continue;
			// Distance first: it is cheap, and the predicate is a name match
			// per pattern, which over a whole loaded scene once a tick per
			// rule cost milliseconds in a crowd (perf audit, Oct 2026).
			var npcLocation = npc.getWorldLocation();
			if (location == null || npcLocation == null)
				continue;
			if (npcLocation.distanceTo(location) > within || !predicate.test(npc))
				continue;
			if (!requireVisible || canSee(npcLocation, npcSize(npc)))
				return true;
		}
		return false;
	}

	static int npcSize(NPC npc)
	{
		return npc.getComposition() == null ? 1
			: max(1, npc.getComposition().getSize());
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
	boolean canSee(WorldPoint from, int size)
	{
		var wv = client.getTopLevelWorldView();
		if (wv == null || wv.getCollisionMaps() == null)
			return true;
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
	int countPlayersNearby(int within)
	{
		if (crowdGeneration == refreshGeneration && crowdWithin == within)
			return crowdCached;
		crowdGeneration = refreshGeneration;
		crowdWithin = within;

		int found = 0;
		var local = client.getLocalPlayer();
		var view = client.getTopLevelWorldView();
		if (location != null && view != null)
		{
			for (var other : view.players())
			{
				if (other == null || other == local)
					continue;
				var where = other.getWorldLocation();
				if (where != null && where.distanceTo(location) <= within)
					found++;
			}
		}
		crowdCached = found;
		return found;
	}

	int crowdGeneration = -1,
		crowdWithin = -1,
		crowdCached;

	/** An inventory holds twenty-eight things and always has. */
	static final int INVENTORY_SIZE = 28;

	int freeSlotsGeneration = -1,
		freeSlots = INVENTORY_SIZE;

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
	int getFreeInventorySlots()
	{
		if (freeSlotsGeneration == refreshGeneration)
			return freeSlots;
		freeSlotsGeneration = refreshGeneration;

		var inventory =
			client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			// Not loaded yet. Reporting a full inventory here would have the
			// follower warning about a bag it has not seen.
			freeSlots = INVENTORY_SIZE;
			return freeSlots;
		}

		int used = 0;
		for (var item : inventory.getItems())
		{
			if (item != null && item.getId() > 0)
				used++;
		}
		freeSlots = max(0, INVENTORY_SIZE - used);
		return freeSlots;
	}

	String getPlayerName()
	{
		var local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "you" : local.getName();
	}

	// ----------------------------------------------------------- saved memory

	/**
	 * What the follower has counted, kept between sessions.
	 *
	 * <p>The counts themselves are the point of the feature: "your fiftieth"
	 * means nothing if the fifty were all this afternoon, and everything if
	 * they were the fifty since you met. A session-scoped tally is a scoreboard;
	 * this is a memory.
	 *
	 * <p>One config value holding one JSON object, for the same reason the
	 * last-seen stamp is a config value: RuneLite already persists config per
	 * profile, and a file of our own would be more machinery than this deserves.
	 */
	static class SavedCounters
	{
		Map<String, Integer> tallies,
			records;
		/** Each boss's name as the game writes it, by its lower-case kill count key. */
		Map<String, String> killCountNames;
		int sessions;

		/** The incident the follower is still thinking about, and how to say it. */
		String incidentKey,
			incidentPhrase;
		int incidentCount;
		/** Where (a region id), when (epoch ms) and who it was with. */
		int incidentWhere;
		long incidentWhenMs;
		String incidentWho;
		/**
		 * The place as said, whether that is only the nearest named place,
		 * the item and the hitpoints left. Absent from older saves, which
		 * read the place off the region and know no item or hitpoints.
		 */
		String incidentPlace;
		Boolean incidentNear;
		String incidentItem;
		Integer incidentHp;
		/** When it was last brought up unprompted, so that is once a day. */
		long incidentRecalledMs;

		/**
		 * Where the last death happened. Session-scoped memory made
		 * nearDeathSpot a thing that could only fire on the walk back; kept
		 * between sessions it becomes an anniversary, which is a much better
		 * line - "this is the spot" six weeks later.
		 */
		int deathX,
			deathY,
			deathPlane = -1;

		/**
		 * What each place has come to mean, and the one thing worth bringing up
		 * about it. Keyed by region id. Earned rather than rolled, so unlike the
		 * traits blob this one is never regenerated - losing it would have the
		 * follower forget which places it has reason to feel anything about.
		 */
		Map<Integer, String> placeMemories;

		/**
		 * Ids of the one-time lines already said. Small and slow-growing: there
		 * are only ever as many entries as there are firsts worth marking.
		 */
		List<String> spokenOnce;
		/** And the day (an epoch day) each was said; missing from older saves. */
		Map<String, Long> spokenOnceOn;
		/** Quests the follower saw finish, by quest and account, and the day. */
		Map<String, Long> questsSeenFinished;

		/**
		 * How worn each line is - times actually said, keyed by the template's
		 * hash so the blob stays small. Capped at 500 entries by the context,
		 * least-worn dropped first.
		 */
		Map<String, Integer> lineWear;

		/**
		 * The day the follower first met this player, as an epoch day. Written
		 * once and never again: it is the only thing here that would be a lie
		 * if it were ever recalculated.
		 */
		long metOnDay;

		/** What the player was wearing then, so an upgrade has something to beat. */
		int metWearingValue = -1;

		/** When each skill last went up, so the next level knows how long it took. */
		Map<String, Long> levelStamps;

		/** What the two of them were doing when the session ended, for the greeting. */
		String lastThing;
	}

	/**
	 * How many counters are kept. Every distinct NPC name the player has ever
	 * killed earns an entry, so this grows for as long as the plugin is used;
	 * without a bound it would grow into the config file forever. The rarest
	 * counts go first, since a count of one is the least likely to be the
	 * subject of a milestone rule.
	 */
	static final int MAX_COUNTERS = 300;

	/**
	 * Puts a saved blob back, and returns the gear value the follower first
	 * met the player wearing. FollowerPlugin.restoreMemory is the seam, and
	 * reads a blob that is not JSON as no memory at all.
	 */
	int restoreMemory(String stored, Gson gson, int fallbackMetWearing)
	{
		if (Strings.isNullOrEmpty(stored))
			return fallbackMetWearing;
		SavedCounters saved = gson.fromJson(stored, SavedCounters.class);
		if (saved == null)
			return fallbackMetWearing;
		restoreCounters(saved.tallies, saved.records);
		if (saved.killCountNames != null)
			saved.killCountNames.forEach(killCountNames::putIfAbsent);
		setSessionCount(saved.sessions);
		restoreIncident(saved.incidentKey, saved.incidentPhrase,
			saved.incidentCount, saved.incidentWhere, saved.incidentWhenMs, saved.incidentWho,
			saved.incidentPlace, Boolean.TRUE.equals(saved.incidentNear), saved.incidentItem,
			saved.incidentHp == null ? -1 : saved.incidentHp);
		restoreIncidentRecalled(saved.incidentRecalledMs);
		restorePlaces(saved.placeMemories);
		restoreSpokenOnce(saved.spokenOnce, saved.spokenOnceOn);
		if (saved.questsSeenFinished != null)
			questsSeenFinished.putAll(saved.questsSeenFinished);
		if (saved.lineWear != null)
		{
			saved.lineWear.forEach((key, value) -> lineWear.merge(key, value, Integer::sum));
			countersDirty = true;
		}
		setMetOnDay(saved.metOnDay);
		setMetWearingValue(saved.metWearingValue);
		if (saved.levelStamps != null)
			saved.levelStamps.forEach((skill, stamp) -> levelStamps.merge(skill, stamp, Math::max));
		restoreLastThing(saved.lastThing);
		if (saved.deathPlane >= 0)
			restoreDeathSpot(new WorldPoint(
				saved.deathX, saved.deathY, saved.deathPlane));
		return saved.metWearingValue;
	}

	/** Everything the follower remembers, as it goes to disk. */
	String snapshotMemory(Gson gson, int metWearing)
	{
		var saved = new SavedCounters();
		saved.tallies = trimCounters(tallies);
		saved.records = trimCounters(records);
		saved.killCountNames = copyOrNull(killCountNames);
		saved.sessions = sessionCount;
		saved.incidentKey = incidentKey;
		saved.incidentPhrase = incidentPhrase;
		saved.incidentCount = incidentCount;
		saved.incidentWhere = incidentWhere;
		saved.incidentWhenMs = incidentWhenMs;
		saved.incidentWho = orNull(incidentWho);
		saved.incidentPlace = orNull(getIncidentPlace());
		saved.incidentNear = isIncidentNear() ? Boolean.TRUE : null;
		saved.incidentItem = orNull(incidentItem);
		saved.incidentHp = incidentHp < 0 ? null : incidentHp;
		saved.incidentRecalledMs = incidentRecalledMs;
		saved.placeMemories = placeMemories;
		saved.spokenOnce = new ArrayList<>(spokenOnce);
		saved.spokenOnceOn = copyOrNull(spokenOnceOn);
		saved.questsSeenFinished = copyOrNull(questsSeenFinished);
		saved.lineWear = lineWear;
		saved.metOnDay = metOnDay;
		saved.metWearingValue = metWearing;
		saved.levelStamps = copyOrNull(levelStamps);
		saved.lastThing = orNull(lastThing);
		var died = deathLocation;
		if (died != null)
		{
			saved.deathX = died.getX();
			saved.deathY = died.getY();
			saved.deathPlane = died.getPlane();
		}
		return gson.toJson(saved);
	}

	/** Nothing is written as absent. */
	static String orNull(String text)
	{
		return text.isEmpty() ? null : text;
	}

	/** A copy to write, or absent for an empty map. */
	static <K, V> Map<K, V> copyOrNull(Map<K, V> map)
	{
		return map.isEmpty() ? null : new HashMap<>(map);
	}

	static Map<String, Integer> trimCounters(Map<String, Integer> counters)
	{
		// Today's counters are for today. Writing them out would have the
		// follower's summary open with yesterday's afternoon still in it.
		Map<String, Integer> lasting = new LinkedHashMap<>();
		counters.forEach((key, value) ->
		{
			if (!key.endsWith(TODAY))
				lasting.put(key, value);
		});
		counters = lasting;

		if (counters.size() <= MAX_COUNTERS)
			return counters;
		return counters.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(MAX_COUNTERS)
			.collect(java.util.stream.Collectors.toMap(
				Map.Entry::getKey, Map.Entry::getValue,
				(a, b) -> a, LinkedHashMap::new));
	}
}
