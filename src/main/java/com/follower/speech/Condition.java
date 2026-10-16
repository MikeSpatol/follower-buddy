package com.follower.speech;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;

/**
 * One condition in a rule's {@code when} block. Deliberately a single flat class
 * with a {@code type} discriminator and optional fields, so the JSON stays
 * hand-editable and Gson needs no custom adapters.
 *
 * <p>Supported types:
 * <table>
 *   <tr><td>all / any / none</td><td>combinators over {@code conditions}</td></tr>
 *   <tr><td>npcSpawn / npcDespawn</td><td>{@code names} (wildcards ok) or {@code ids}</td></tr>
 *   <tr><td>npcNearby</td><td>{@code names}/{@code ids} plus {@code within} tiles</td></tr>
 *   <tr><td>healthBelow / healthAbove</td><td>{@code percent}</td></tr>
 *   <tr><td>prayerBelow / prayerAbove</td><td>{@code percent}, {@code requirePrayerActive}</td></tr>
 *   <tr><td>inRegion / regionEnter</td><td>{@code regions}, {@code anyLoadedRegion}</td></tr>
 *   <tr><td>inArea</td><td>{@code x1,y1,x2,y2,plane}</td></tr>
 *   <tr><td>chatMessage</td><td>{@code contains} or {@code regex}; optional {@code every} - the nth matching message</td></tr>
 *   <tr><td>varbitEquals / varbitChanged</td><td>{@code varbit}, {@code value}</td></tr>
 *   <tr><td>questVar</td><td>{@code varbit} or {@code varp}, optional {@code minimum} / {@code maximum} (inclusive) - true while the value is in range</td></tr>
 *   <tr><td>dialogueOpen</td><td>no fields - an NPC dialogue box is up; used negated, so a line waits for the box to close</td></tr>
 *   <tr><td>questDone</td><td>{@code quest} (a {@link net.runelite.api.Quest} constant name) - that quest is finished</td></tr>
 *   <tr><td>animationSelf</td><td>{@code ids}; optional {@code every} - the nth time one plays</td></tr>
 *   <tr><td>levelUp</td><td>{@code names} (skill names)</td></tr>
 *   <tr><td>damageTaken</td><td>{@code minimum}</td></tr>
 *   <tr><td>itemEquipped</td><td>{@code ids}</td></tr>
 *   <tr><td>poisoned / venomed / skulled</td><td>no fields</td></tr>
 *   <tr><td>thrallStart / thrallSwitch / thrallEnd</td><td>no fields; {@code {style}} and, for a switch, {@code {from}}</td></tr>
 *   <tr><td>errandStart / errandEnd</td><td>optional {@code names} (errand names); {@code {errand}} placeholder</td></tr>
 *   <tr><td>petNearby</td><td>{@code within} — any NPC the game flags as a follower, i.e. any pet</td></tr>
 *   <tr><td>idleBelow</td><td>{@code ticks} — the other bound, for fidgets that should stop once resting</td></tr>
 *   <tr><td>playerDeath</td><td>no fields — the moment of death</td></tr>
 *   <tr><td>nearDeathSpot</td><td>{@code within} — standing near the session's last death, 2+ min after it</td></tr>
 *   <tr><td>lootWorth</td><td>{@code minimum} gp — {@code {item}} and {@code {value}} placeholders</td></tr>
 *   <tr><td>returnVisit</td><td>{@code minimum} — entering a region already visited that many times this session</td></tr>
 *   <tr><td>combat</td><td>no fields — true throughout a fight, including the gaps between targets</td></tr>
 *   <tr><td>bossFight</td><td>{@code minimum} — target combat level, default 100</td></tr>
 *   <tr><td>combatStart / combatEnd</td><td>optional {@code names}; {@code {npc}} placeholder</td></tr>
 *   <tr><td>npcKill</td><td>{@code minimum} / {@code maximum} combat level, optional {@code names} / {@code ids}, or {@code boss: true} for either big enough or named in the boss rules, or {@code namedBoss: true} for named only</td></tr>
 *   <tr><td>energyBelow</td><td>{@code percent}</td></tr>
 *   <tr><td>idle</td><td>{@code ticks}</td></tr>
 *   <tr><td>login / always</td><td>no fields</td></tr>
 *   <tr><td>chance</td><td>{@code percent} — rolled each evaluation</td></tr>
 *   <tr><td>tally</td><td>{@code of} counter name, {@code minimum} / {@code maximum} — a LIFETIME count</td></tr>
 *   <tr><td>personalBest</td><td>optional {@code names}; {@code {record}} {@code {value}} {@code {previous}}</td></tr>
 *   <tr><td>sessionCount</td><td>{@code minimum} / {@code every} — login only</td></tr>
 *   <tr><td>answered</td><td>{@code is} yes|no — the branch the player picked in the tree named by {@code asks}</td></tr>
 *   <tr><td>hovered</td><td>{@code ticks} — the mouse resting on the follower</td></tr>
 *   <tr><td>examined</td><td>no fields</td></tr>
 *   <tr><td>inventoryFree</td><td>{@code maximum} free slots — a warning, not a report</td></tr>
 *   <tr><td>inventoryHas</td><td>{@code ids} - true while any of those items is in the inventory</td></tr>
 *   <tr><td>playersNearby</td><td>{@code minimum} count, {@code within} tiles</td></tr>
 *   <tr><td>wanting</td><td>no fields — a want is open; usually used negated</td></tr>
 *   <tr><td>wantFulfilled / wantExpired</td><td>no fields; {@code {want}} placeholder</td></tr>
 *   <tr><td>feelsAbout</td><td>{@code is} liked|disliked — about the CURRENT region</td></tr>
 *   <tr><td>remembers</td><td>optional {@code is} incident key, {@code minimum} times; {@code {memory}}</td></tr>
 *   <tr><td>carrying</td><td>no fields — holding a souvenir; {@code {souvenir}}</td></tr>
 *   <tr><td>souvenirLost</td><td>no fields; {@code {souvenir}} names it one last time</td></tr>
 *   <tr><td>betting / betWon / betLost</td><td>no fields</td></tr>
 *   <tr><td>timeOfDay</td><td>{@code minimum} / {@code maximum} hour, 0-23, wrapping past midnight</td></tr>
 *   <tr><td>sessionMinutes</td><td>{@code minimum} / {@code maximum} minutes today</td></tr>
 *   <tr><td>dateIs</td><td>{@code month}/{@code day} on the player's own calendar, optional {@code untilMonth}/{@code untilDay} range wrapping past new year; {@code day} alone means that day of every month</td></tr>
 *   <tr><td>worldHop</td><td>no fields; {@code {world}} is the new world number</td></tr>
 *   <tr><td>gameUpdated</td><td>no fields — first login on a new game build</td></tr>
 *   <tr><td>notesEdited</td><td>no fields — phrases.json changed on disk and hot-reloaded</td></tr>
 *   <tr><td>musicUnlock</td><td>no fields; {@code {track}} names the new track</td></tr>
 *   <tr><td>musicTrack</td><td>optional {@code names} (wildcards ok) — the playing track changed; {@code {track}}</td></tr>
 *   <tr><td>collectionAdd</td><td>no fields; {@code {item}} is the new collection log entry</td></tr>
 *   <tr><td>questComplete</td><td>no fields; {@code {quest}} names the quest just finished</td></tr>
 *   <tr><td>pageWritten</td><td>optional {@code is} boss|enemy|location|faction|world for a page met or walked into, quest for one a quest opened; {@code {page}} names the chronicle page just written</td></tr>
 *   <tr><td>geOffer</td><td>{@code is} bought|sold; {@code {item}} and {@code {value}}</td></tr>
 *   <tr><td>channelJoined / channelLeft</td><td>no fields; {@code {name}} says who</td></tr>
 *   <tr><td>npcShout</td><td>{@code names} (wildcards ok) and/or {@code contains}; {@code {npc}} and {@code {message}}</td></tr>
 *   <tr><td>examinedThing</td><td>optional {@code names} (wildcards ok) — the player examined something; {@code {target}}</td></tr>
 *   <tr><td>attendedAgain</td><td>no fields — the player is back from an absence the follower came round to check on</td></tr>
 *   <tr><td>minutesTaken</td><td>no fields — the follower finished minuting an NPC dialog</td></tr>
 *   <tr><td>working</td><td>optional {@code is} (a skill name) and {@code contains} (part of the target's name) - a work session is on</td></tr>
 *   <tr><td>xpDrop</td><td>optional {@code names} (skill names) and {@code every} - the tick of a drop; {@code {skill}} {@code {count}}</td></tr>
 *   <tr><td>workEnded</td><td>optional {@code names} (activities) - the tick a session ends; {@code {activity}} {@code {target}}</td></tr>
 *   <tr><td>targetDepleted</td><td>no fields - the session's target changed id or went; {@code {target}}</td></tr>
 *   <tr><td>bagFull / bagHalf</td><td>no fields - the last slot went, or fourteen were left (once per fill)</td></tr>
 *   <tr><td>questWitnessed</td><td>{@code quest}, optional {@code minimum} days since - the follower saw that quest finish, or its quest page has a date</td></tr>
 *   <tr><td>daysSinceSaid</td><td>{@code rule} (a once rule's id), {@code minimum} / {@code maximum} days - false until that rule has been said</td></tr>
 * </table>
 *
 * <p>{@code npcNearby} also takes {@code minimum}/{@code maximum} combat level,
 * which on their own match any NPC in that bracket, and {@code visible}, which
 * additionally requires line of sight to the player - for lines that invite
 * the player to look at the thing.
 *
 * <p>{@code chatMessage} also takes {@code from} (player|others) and {@code is}
 * (a {@link net.runelite.api.ChatMessageType} name).
 */
@Slf4j
public class Condition
{
	public String type;

	public List<Condition> conditions;

	public List<String> names;
	public List<Integer> ids;
	public List<Integer> regions;

	public Integer percent;
	public Integer minimum;

	/**
	 * Whether the NPC has to be a BOSS, or has to NOT be one.
	 *
	 * <p>Three states. Left out, it does not care. True, only bosses.
	 * False, only things that are not, which is how the generic "well fought"
	 * stands aside for a boss's own send-off instead of talking over it.
	 *
	 * <p>A bigger question than any combat level, and the reason this is not
	 * just {@code minimum: 100}.
	 *
	 * <p>The level is only a proxy, and it is wrong in both directions. It was
	 * wrong for Brutus, who fights at thirty and is unmistakably a boss, so
	 * the follower neither stood clear of him nor said anything when he went
	 * down. The names the boss rules already list are the other half of the
	 * answer, and this consults both.
	 */
	public Boolean boss;
	/**
	 * npcKill only: named in the boss rules, whatever its level. A level-100
	 * Slayer demon is big enough for {@code boss} but no occasion; play test,
	 * 14 Oct, the boss celebration had worn out on every demon of a task.
	 */
	public Boolean namedBoss;
	public Integer maximum;
	public Integer within;
	public Integer ticks;
	public Integer varbit;
	/** questVar: a varplayer, for the older quests that keep their progress in one. */
	public Integer varp;
	/** questDone / questWitnessed: the quest, by its {@link net.runelite.api.Quest} constant name. */
	public String quest;
	/** daysSinceSaid: the id of the one-time rule counted from. */
	public String rule;
	public Integer value;

	/** Fire on every Nth occurrence rather than on each one. */
	public Integer every;

	/** dateIs: the day on the player's own calendar, optionally through a range. */
	public Integer month;
	public Integer day;
	public Integer untilMonth;
	public Integer untilDay;

	public Integer x1;
	public Integer y1;
	public Integer x2;
	public Integer y2;
	public Integer plane;

	public String contains;
	public String regex;

	/** A named band, for conditions that read better as a word than a number. */
	public String is;

	/** Which counter a tally or record condition is asking about. */
	public String of;

	/** Who said it: "player" for the local player's own chat, "others" for the rest. */
	public String from;

	public Boolean requirePrayerActive;
	public Boolean anyLoadedRegion;

	/**
	 * On {@code npcNearby}: the NPC must also have line of sight to the
	 * player, by the game's own collision rules. For lines that invite the
	 * player to LOOK - a callout at something behind a wall is worse than no
	 * callout, because one bad one teaches the player to stop checking.
	 * Opt-in per rule rather than a change to what npcNearby means, since
	 * plenty of uses are atmosphere rather than assertion.
	 */
	public Boolean visible;

	private transient Pattern compiledRegex;
	private transient List<Pattern> compiledNames;

	/**
	 * {@link #ids} and {@link #regions} unboxed, built on first use.
	 *
	 * <p>These two carry the bulk of the rule set - most rules are "wearing
	 * this" or "standing here" - and every rule is evaluated against every
	 * event, so a {@code List<Integer>.contains(int)} here means boxing the
	 * needle and walking a chain of Integers, hundreds of times per event.
	 */
	private transient int[] idsUnboxed;
	private transient int[] regionsUnboxed;

	private static int[] unbox(List<Integer> boxed)
	{
		int[] out = new int[boxed.size()];
		for (int i = 0; i < out.length; i++)
		{
			Integer value = boxed.get(i);
			out[i] = value == null ? Integer.MIN_VALUE : value;
		}
		return out;
	}

	private static boolean contains(int[] values, int wanted)
	{
		for (int value : values)
		{
			if (value == wanted)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Matches of this condition so far, for {@link #every} on the conditions
	 * that have no count of their own to read - a chat message, an animation.
	 * Transient, so it is the rule's lifetime and not the file's.
	 */
	private transient int seen;

	/**
	 * Whether this match is the every-nth one. Counted only when it is
	 * reached, so under an "all" the conditions before it gate the count.
	 */
	private boolean everyNth()
	{
		if (every == null || every <= 0)
		{
			return true;
		}
		return ++seen % every == 0;
	}

	private boolean idsContain(int wanted)
	{
		if (ids == null)
		{
			return false;
		}
		if (idsUnboxed == null)
		{
			idsUnboxed = unbox(ids);
		}
		return contains(idsUnboxed, wanted);
	}

	/**
	 * The lowercased {@link #type}, cached: every rule is evaluated on every
	 * event, and re-lowercasing the discriminator allocated a string per
	 * evaluation across hundreds of rules.
	 */
	private transient String normalizedType;

	/**
	 * npcNearby scans the whole NPC list; its answer cannot change within a
	 * tick, so it is cached per {@link TriggerContext#getRefreshGeneration()}.
	 * A chance roll is kept the same way (a condition has one type, so the
	 * two never share these).
	 */
	private transient int nearbyGeneration = -1;
	private transient boolean nearbyCached;

	public boolean matches(TriggerContext ctx, TriggerEvent event)
	{
		if (type == null)
		{
			return false;
		}
		if (normalizedType == null)
		{
			normalizedType = type.toLowerCase(Locale.ROOT);
		}

		switch (normalizedType)
		{
			case "all":
				return allOf(ctx, event);
			case "any":
				return anyOf(ctx, event);
			case "none":
				return !anyOf(ctx, event);

			case "always":
				return true;

			// Rolled once a tick, not once a pass. QA, 15 Oct: with npcSpawn
			// rules loaded every NPC spawn is a pass, so a chunk load re-rolled
			// a 4 percent fidget dozens of times in one tick.
			case "chance":
			{
				int generation = ctx.getRefreshGeneration();
				if (nearbyGeneration != generation)
				{
					nearbyGeneration = generation;
					nearbyCached = ThreadLocalRandom.current().nextInt(100) < orDefault(percent, 100);
				}
				return nearbyCached;
			}

			case "npcspawn":
				return event.getType() == TriggerEvent.Type.NPC_SPAWN && matchesNpc(event);
			case "npcdespawn":
				return event.getType() == TriggerEvent.Type.NPC_DESPAWN && matchesNpc(event);

			case "npcnearby":
			{
				int generation = ctx.getRefreshGeneration();
				if (nearbyGeneration != generation)
				{
					nearbyGeneration = generation;
					nearbyCached = ctx.isNpcNearby(this::isNearbyCandidate,
						orDefault(within, 15), Boolean.TRUE.equals(visible));
				}
				return nearbyCached;
			}

			// Any NPC the game itself flags as a follower - every pet in the
			// game, and every one added later, without naming any of them.
			// Cached per tick like npcNearby, and for the same reason.
			case "petnearby":
			{
				int generation = ctx.getRefreshGeneration();
				if (nearbyGeneration != generation)
				{
					nearbyGeneration = generation;
					nearbyCached = ctx.isNpcNearby(
						npc -> npc.getComposition() != null && npc.getComposition().isFollower(),
						orDefault(within, 5));
				}
				return nearbyCached;
			}

			case "healthbelow":
				return ctx.getHitpointsPercent() < orDefault(percent, 50);
			case "healthabove":
				return ctx.getHitpointsPercent() > orDefault(percent, 50);

			case "prayerbelow":
				return ctx.getPrayerPercent() < orDefault(percent, 20)
					&& (!Boolean.TRUE.equals(requirePrayerActive) || ctx.isPrayerActive());
			case "prayerabove":
				return ctx.getPrayerPercent() > orDefault(percent, 20);

			case "inregion":
				return matchesRegion(ctx, ctx.getRegionId());
			case "regionenter":
				return event.getType() == TriggerEvent.Type.REGION_CHANGE && matchesRegion(ctx, event.getValue());

			case "inarea":
				return matchesArea(ctx);

			// "every" counts this condition's own matches, for life: 3 fires
			// on the third burn message and every third after, the way the
			// skilling company shakes its head at a run of them, not each.
			case "chatmessage":
				return event.getType() == TriggerEvent.Type.CHAT
					&& matchesChatType(event)
					&& matchesSpeaker(ctx, event)
					&& matchesText(event.getMessage())
					&& everyNth();

			// The player answering something the follower asked, by picking a
			// branch in the conversation it opened. The options ARE the
			// vocabulary, so there is nothing to misread: yes and no from the
			// question trees, and the handful of named acts the everyday
			// script can perform - "gift" being the first.
			// "of" names the tree the answer belongs to, so a yes to the
			// nickname is not a yes to the outing.
			case "answered":
				return event.getType() == TriggerEvent.Type.ANSWERED
					&& (is == null || is.equalsIgnoreCase(event.getName()))
					&& (of == null || of.equalsIgnoreCase(event.getMessage()));

			// No player, no answer: at client start a chat message can arrive
			// before the game has loaded its varbits, the client's own lookup
			// throws, and a rule that throws is disabled for the session - the
			// two Wilderness rules were, in five logs.
			case "varbitequals":
				return varbit != null && ctx.getClient().getLocalPlayer() != null
					&& ctx.getClient().getVarbitValue(varbit) == orDefault(value, 1);
			case "varbitchanged":
				return event.getType() == TriggerEvent.Type.VARBIT
					&& varbit != null && event.getId() == varbit
					&& (value == null || event.getValue() == value);

			// A quest's progress inside a band of steps. State, so the rule
			// rises on entering the band: a jump from 1 to 13 still enters
			// 11-20. Newer quests keep progress in a varbit, older ones in a
			// varp. Same guard as varbitEquals, for the same reason.
			case "questvar":
			{
				if ((varbit == null && varp == null) || ctx.getClient().getLocalPlayer() == null)
				{
					return false;
				}
				int current = varbit != null
					? ctx.getClient().getVarbitValue(varbit)
					: ctx.getClient().getVarpValue(varp);
				return (minimum == null || current >= minimum)
					&& (maximum == null || current <= maximum);
			}

			// An NPC dialogue box is up, or was a moment ago: page flips drop
			// it for a beat. Used negated, inside an "all", so a rule whose
			// state changes mid-conversation rises as the box closes.
			case "dialogueopen":
				return ctx.isDialogueOpen();

			// Another quest already finished, for a line that calls back to
			// it. State; read the way the chronicle reads it, and false with
			// no player or a name the game does not know.
			case "questdone":
				return ctx.isQuestDone(quest);

			// The follower was there for that quest: saw it finish, or wrote
			// its page at the time. For a callback that claims a shared
			// memory; minimum is days since, so a "still" waits a day.
			case "questwitnessed":
			{
				Long on = ctx.questWitnessedOn(quest);
				return on != null
					&& java.time.LocalDate.now().toEpochDay() - on >= orDefault(minimum, 0);
			}

			case "animationself":
				return event.getType() == TriggerEvent.Type.ANIMATION && idsContain(event.getId())
					&& everyNth();

			case "levelup":
				return event.getType() == TriggerEvent.Type.LEVEL_UP && matchesText(event.getName())
					&& event.getValue() >= orDefault(minimum, 1)
					&& (maximum == null || event.getValue() <= maximum);

			// A level that means something beyond being the next one. "is"
			// picks which: halfway (92, half the experience to 99), round (a
			// multiple of ten, from "minimum" up, fifty by default), mastery
			// (99, with "minimum"/"maximum" on how many that makes), total
			// (the total level crossing a multiple of "every", a hundred by
			// default, from "minimum" up) and base (every skill now at
			// "minimum" or better, the tick that became true).
			case "milestone":
			{
				if (event.getType() != TriggerEvent.Type.LEVEL_UP || is == null)
				{
					return false;
				}
				int level = event.getValue();
				switch (is.toLowerCase(Locale.ROOT))
				{
					case "halfway":
						return level == 92;
					case "round":
						return level % 10 == 0 && level >= orDefault(minimum, 50)
							&& (maximum == null || level <= maximum);
					case "mastery":
						return level == 99 && event.getCount() >= orDefault(minimum, 1)
							&& (maximum == null || event.getCount() <= maximum);
					case "total":
					{
						int step = Math.max(1, orDefault(every, 100));
						return event.getTotal() >= orDefault(minimum, 0)
							&& event.getTotal() / step > event.getPreviousTotal() / step;
					}
					case "base":
						return minimum != null && event.getBase() >= minimum
							&& event.getPreviousBase() < minimum;
					default:
						return false;
				}
			}

			// How long it had been since the last level in that skill, in
			// minutes - the gap the {sinceLast} placeholder says. Only when the
			// follower has a stamp to measure from: a first level in a skill
			// since it started keeping them is not a gap of any size.
			case "levelgap":
			{
				if (event.getType() != TriggerEvent.Type.LEVEL_UP || event.getSinceMs() < 0)
				{
					return false;
				}
				long gap = event.getSinceMs() / 60_000L;
				return gap >= orDefault(minimum, 0) && (maximum == null || gap <= maximum);
			}

			// The game announcing a kill count - the account's own number, not
			// the follower's tally, which is why it can say "your forty-first"
			// on the first day they met. "names" narrows the boss, "every"
			// fires on multiples, and is: round fires on the counts a person
			// would say out loud (1, 5, 10, 25, 50, 75, the hundreds, then
			// every five hundred).
			case "killcount":
			{
				if (event.getType() != TriggerEvent.Type.KILL_COUNT)
				{
					return false;
				}
				int n = event.getCount();
				return (names == null || matchesAnyName(event.getName()))
					&& n >= orDefault(minimum, 1)
					&& (maximum == null || n <= maximum)
					&& (every == null || every <= 0 || n % every == 0)
					&& (is == null || !"round".equalsIgnoreCase(is) || isRoundCount(n));
			}

			// The half hour after the player did something the follower asked
			// for. Reciprocity: a favour is owed, and the body pays it back
			// (closer attention, quicker gestures); this is the rules' share.
			case "grateful":
				return ctx.isGrateful();

			// What the two of them were doing when the last session ended, for
			// the greeting. Login only, and only when there is something to
			// say; {lastTime} says it.
			// The follower noticed something and went to look at it. "is" is
			// the kind (valuable, nest, impling, star, landmark); "contains"
			// narrows on the thing's name. {kind}, {thing}.
			case "noticed":
				return event.getType() == TriggerEvent.Type.NOTICED
					&& (is == null || is.equalsIgnoreCase(event.getName()))
					&& (contains == null || event.getMessage().toLowerCase(Locale.ROOT)
						.contains(contains.toLowerCase(Locale.ROOT)));

			// A Send the follower had misgivings about: it has stopped at the
			// edge and turned to the player. "is" is the kind (wilderness,
			// monster); {misgiving}, {thing}.
			case "hesitated":
				return event.getType() == TriggerEvent.Type.HESITATED
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			// The player walked straight at the parked follower and it stepped
			// aside at the last tick.
			case "steppedaside":
				return event.getType() == TriggerEvent.Type.STEPPED_ASIDE;

			// An earned name the player has not been asked about yet. {candidate}
			// says it; the asking rule is what turns it into a nickname.
			case "nicknameearned":
				return ctx.hasNicknameToOffer();

			// The player is addressing the follower: within a tile and turned
			// toward it. What makes an emote a reply rather than an echo.
			case "addressed":
				return ctx.isAddressingFollower();

			// The logout tab opening. The body waves; this is the rare word.
			case "farewell":
				return event.getType() == TriggerEvent.Type.FAREWELL;

			case "lasttime":
				return event.getType() == TriggerEvent.Type.LOGIN && !ctx.getLastTime().isEmpty();

			case "damagetaken":
				return event.getType() == TriggerEvent.Type.DAMAGE_TAKEN
					&& event.getValue() >= orDefault(minimum, 1);

			case "itemequipped":
			{
				if (ids == null)
				{
					return false;
				}
				if (idsUnboxed == null)
				{
					idsUnboxed = unbox(ids);
				}
				for (int id : idsUnboxed)
				{
					if (ctx.isEquipped(id))
					{
						return true;
					}
				}
				return false;
			}

			case "poisoned":
			{
				int poison = ctx.getClient().getVarpValue(net.runelite.api.gameval.VarPlayerID.POISON);
				return poison > 0 && poison < VENOM_THRESHOLD;
			}
			case "venomed":
				return ctx.getClient().getVarpValue(net.runelite.api.gameval.VarPlayerID.POISON) >= VENOM_THRESHOLD;

			case "skulled":
				return ctx.isSkulled();

			case "energybelow":
				return ctx.getEnergyPercent() < orDefault(percent, 20);

			case "idle":
				return ctx.getIdleTicks() >= orDefault(ticks, 50);

			// The other bound, so a fidget can be told apart from a full rest:
			// "been still a while" AND "not settled in for the night".
			case "idlebelow":
				return ctx.getIdleTicks() < orDefault(ticks, 500);

			case "playerdeath":
				return event.getType() == TriggerEvent.Type.PLAYER_DEATH;

			// Standing where the last death happened, well after the fact.
			case "neardeathspot":
				return ctx.isNearDeathSpot(orDefault(within, 5));

			case "lootworth":
				return event.getType() == TriggerEvent.Type.LOOT
					&& event.getValue() >= orDefault(minimum, 100_000);

			// Entering a region the session has already seen a few times.
			case "returnvisit":
				return event.getType() == TriggerEvent.Type.REGION_CHANGE
					&& ctx.getRegionVisits() >= orDefault(minimum, 5);

			// True through a whole fight, including the short gaps between a
			// target dying and the next being clicked, so a rule using this
			// does not flicker on and off mid-kill.
			case "combat":
				return ctx.isInCombat();

			// The same, but only for something big. "minimum" overrides the
			// default combat level for anyone who wants a different bar.
			case "bossfight":
				return ctx.isInCombat()
					&& ctx.getCombatTargetLevel() >= orDefault(minimum, 100);

			case "login":
				return event.getType() == TriggerEvent.Type.LOGIN;

			case "thrallstart":
				return event.getType() == TriggerEvent.Type.THRALL_START;
			case "thrallswitch":
				return event.getType() == TriggerEvent.Type.THRALL_SWITCH;
			case "thrallend":
				return event.getType() == TriggerEvent.Type.THRALL_END;

			case "errandstart":
				return event.getType() == TriggerEvent.Type.ERRAND_START
					&& (names == null || matchesAnyName(event.getName()));
			case "errandend":
				return event.getType() == TriggerEvent.Type.ERRAND_END
					&& (names == null || matchesAnyName(event.getName()));

			// The moment a fight starts or ends, as opposed to "combat", which
			// stays true throughout one. Optional names match what is fought.
			case "combatstart":
				return event.getType() == TriggerEvent.Type.COMBAT_START
					&& (names == null || matchesAnyName(event.getName()));
			case "combatend":
				return event.getType() == TriggerEvent.Type.COMBAT_END
					// The level comes from the context rather than the event,
					// which carries only the name: the target's level is still
					// the one the fight was fought at when this fires.
					&& (boss == null
						|| boss == ctx.isBoss(event.getName(), ctx.getCombatTargetLevel()))
					&& (names == null || matchesAnyName(event.getName()));

			// Something the player killed. "minimum"/"maximum" bracket the
			// victim's COMBAT LEVEL, which is how one rule celebrates a boss
			// and another shrugs at a chicken; "names"/"ids" narrow it further.
			// How the follower is feeling. "is" names a band (low, down, even,
			// good, high); minimum and maximum bracket the raw 0..100 for
			// anything that wants a finer edge than the bands give.
			case "mood":
			{
				if (is != null)
				{
					return is.equalsIgnoreCase(ctx.getMoodBand());
				}
				int value = ctx.getMood();
				return value >= orDefault(minimum, 0)
					&& value <= orDefault(maximum, 100);
			}

			case "npckill":
				return event.getType() == TriggerEvent.Type.NPC_KILL
					&& (boss == null
						|| boss == ctx.isBoss(event.getName(), event.getValue()))
					&& (namedBoss == null || namedBoss == ctx.isNamedBoss(event.getName()))
					&& event.getValue() >= orDefault(minimum, 0)
					&& (maximum == null || event.getValue() <= maximum)
					&& (names == null && ids == null || matchesNpc(event))
					// "every": 50 fires on the fiftieth of THIS npc and every
					// fiftieth after, which is what makes a tally a remark.
					&& (every == null || every <= 0
						|| (event.getCount() > 0 && event.getCount() % every == 0));

			// The two ends of a thieving session. Everything between them is
			// silent, and these are what make that silence read as the
			// follower giving the player room rather than having broken.
			case "thievingstart":
				return event.getType() == TriggerEvent.Type.THIEVING_START;
			case "thievingend":
				return event.getType() == TriggerEvent.Type.THIEVING_END;

			// True for the whole session, unlike the two edges. Speech is muted
			// throughout, so this is really for animation-only rules - which
			// skip the mute, being movement rather than chatter.
			case "thieving":
				return ctx.isInThievingSession();

			// How long the follower went without seeing the player, in minutes.
			// Only meaningful on the login event, which is when it is worked
			// out; -1 means it has no idea, and must never read as "just now".
			case "awayfor":
				return event.getType() == TriggerEvent.Type.LOGIN
					&& ctx.getMinutesAway() >= 0
					&& ctx.getMinutesAway() >= orDefault(minimum, 60)
					&& (maximum == null || ctx.getMinutesAway() <= maximum);

			// A lifetime count, as against npcKill's "every" which fires on the
			// moment. This is state: it stays true once passed, so it belongs
			// alongside something that picks the moment to say it.
			// The incident the follower is still thinking about. "is" names a
			// particular one; without it, any incident will do - which is what
			// a generic "I still think about {memory}" line wants. A maximum
			// holds a line to the counts it is true for: "That's three".
			case "remembers":
				return ctx.hasIncident()
					&& (is == null || is.equalsIgnoreCase(ctx.getIncidentKey()))
					&& ctx.getIncidentCount() >= orDefault(minimum, 1)
					&& (maximum == null || ctx.getIncidentCount() <= maximum);

			// Standing where the incident happened, and the thing it was with
			// being nearby again: the two moments a memory is worth jogging.
			case "memoryhere":
				return ctx.isAtTheIncident();
			case "memoryfoenearby":
				return ctx.isIncidentFoeNearby(orDefault(within, 12));

			// You clicked the tile the follower was standing on.
			case "underfoot":
				return event.getType() == TriggerEvent.Type.UNDERFOOT;

			// A challenge is outstanding, was met, or ran out. {challenge}
			// names it. "challenging" is mostly used negated, so the follower
			// does not set a second one over the top of the first.
			case "challenging":
				return ctx.isChallenging();
			case "challengemet":
				return event.getType() == TriggerEvent.Type.CHALLENGE_MET;
			case "challengefailed":
				return event.getType() == TriggerEvent.Type.CHALLENGE_FAILED;

			// Nobody has touched the camera for this long. Distinct from idle,
			// which a player working a furnace also satisfies.
			case "unattended":
				return ctx.getUnattendedTicks() >= orDefault(ticks, 200);

			// How long the follower has known you, in days, and the day of
			// the year it met you coming round again.
			// A player-commanded Stay: watched, parked, and free to have a
			// little life of its own.
			case "staying":
				return ctx.isFollowerStaying();

			// Standing in the Wilderness, as a state rather than the crossing.
			case "inwilderness":
				return ctx.isInWilderness();

			// A few ticks of breather after something ENDS - combat, a level,
			// an arrival, thieving, the bank. "is" names which ending; left
			// out, any ending counts.
			case "boundary":
				return ctx.isAtBoundary()
					&& (is == null || is.equalsIgnoreCase(ctx.getBoundaryKind()));

			case "daysknown":
				return ctx.getDaysKnown() >= orDefault(minimum, 1)
					&& (maximum == null || ctx.getDaysKnown() <= maximum);
			// Days since a one-time rule was heard: a running bit's stages
			// count from its opener, and stay quiet until it has been said.
			case "dayssincesaid":
			{
				int days = ctx.daysSinceSaid(rule);
				return days >= 0 && days >= orDefault(minimum, 0)
					&& (maximum == null || days <= maximum);
			}
			case "anniversary":
				return ctx.isAnniversary();

			// The player is "minimum" times better dressed than the day they
			// met. Needs a first-meeting figure to compare against, so it stays
			// quiet for a follower that has only ever known you rich.
			case "outgrew":
				return ctx.getTimesBetterDressed() >= orDefault(minimum, 10);

			// The follower has taken to calling you something. {nickname} says
			// it; without this guard the placeholder prints nothing.
			case "nicknamed":
				return ctx.hasNickname()
					&& (is == null || is.equalsIgnoreCase(ctx.getNickname()));

			// The player did what the follower suggested, or the window shut
			// without them. {advice} names what it was about. "is" narrows to
			// one piece of advice, so the food line and the bag line can react
			// differently.
			case "heeded":
				return event.getType() == TriggerEvent.Type.ADVICE_HEEDED
					&& (is == null || is.equalsIgnoreCase(ctx.getAdviceAbout()));
			case "ignored":
				return event.getType() == TriggerEvent.Type.ADVICE_IGNORED
					&& (is == null || is.equalsIgnoreCase(ctx.getAdviceAbout()));

			// Advice is outstanding. Used NEGATED, so the follower does not
			// give the same advice twice while still waiting on the first.
			case "advising":
				return ctx.isAdvising();

			// Something happened HERE that the follower has not let go of.
			// {here} says it. Distinct from "remembers", which holds one
			// incident wherever you are; this one is the place's, and waits
			// for you to come back to it.
			case "happenedhere":
				return ctx.hasPlaceMemory();

			// How strongly it feels about where it is standing, as a number
			// rather than a verdict, so a rule can want a STRONG opinion.
			// Negative is the bad direction.
			case "placescore":
			{
				int score = ctx.getPlaceScore();
				return (minimum == null || score >= minimum)
					&& (maximum == null || score <= maximum);
			}

			// A question is already on the table. Used NEGATED, and every rule
			// with an "asks" needs it: opening a second question replaces the
			// first, so without the guard a follower that asks to go somewhere
			// and then offers a game has silently withdrawn the outing, and the
			// player answers a question nobody is listening for.
			case "asking":
				return ctx.isAwaitingAnswer()
					&& (is == null || is.equalsIgnoreCase(ctx.getAskedTree()));

			// Carrying something it picked up. Mostly used negated, to stop it
			// picking up a second thing while its hands are full.
			case "carrying":
				return ctx.isCarrying();

			case "souvenirlost":
				return event.getType() == TriggerEvent.Type.SOUVENIR_LOST;

			// A prediction is outstanding. Negated, this stops it betting twice
			// on the same drop.
			case "betting":
				return ctx.isBetting();

			case "betwon":
				return event.getType() == TriggerEvent.Type.BET_WON;
			case "betlost":
				return event.getType() == TriggerEvent.Type.BET_LOST;

			// The hour on the player's own wall, not the game's. "minimum" and
			// "maximum" bound it, wrapping past midnight so a late-night window
			// can be written the way a person would say it: 23 to 5.
			case "timeofday":
			{
				int hour = ctx.getHourOfDay();
				int from = orDefault(minimum, 0);
				int to = orDefault(maximum, 23);
				return from <= to
					? hour >= from && hour <= to
					: hour >= from || hour <= to;
			}

			// How long today has run, in minutes.
			case "sessionminutes":
				return ctx.getSessionMinutes() >= orDefault(minimum, 60)
					&& (maximum == null || ctx.getSessionMinutes() <= maximum);

			// The date on the player's own calendar, like timeOfDay is the
			// player's own clock. A single day, or a range that may wrap past
			// new year the way a late-night window wraps past midnight - the
			// scribe dates its entries, so this is the most in-character
			// knowledge it could have. Rising-edge, so a matching day fires
			// once per session, same as anniversary.
			case "dateis":
			{
				if (day == null)
				{
					return false;
				}
				int today = ctx.getMonthDay();
				// Day alone means that day of EVERY month - the first of the
				// month is the scribe's favourite day of it.
				if (month == null)
				{
					return today % 100 == day;
				}
				int from = month * 100 + day;
				int to = untilMonth == null || untilDay == null
					? from : untilMonth * 100 + untilDay;
				return from <= to
					? today >= from && today <= to
					: today >= from || today <= to;
			}

			// A world hop landed. Rare by nature, so no flood guard needed.
			case "worldhop":
				return event.getType() == TriggerEvent.Type.WORLD_HOP;

			// First login since the game itself changed under everyone's feet.
			case "gameupdated":
				return event.getType() == TriggerEvent.Type.GAME_UPDATE;

			// The notebook was edited from outside and hot-reloaded. In-world
			// this is somebody writing in the scribe's own book.
			case "notesedited":
				return event.getType() == TriggerEvent.Type.NOTES_EDITED;

			case "musicunlock":
				return event.getType() == TriggerEvent.Type.MUSIC_UNLOCK;

			// The music changed to another track - the town's own song, where
			// musicUnlock is hearing one for the FIRST time. Names narrow it
			// to particular tracks, which is how a rule gets an opinion about
			// Sea Shanty 2 without one about everything.
			case "musictrack":
				return event.getType() == TriggerEvent.Type.TRACK_CHANGE
					&& (names == null || matchesAnyName(event.getName()));

			case "collectionadd":
				return event.getType() == TriggerEvent.Type.COLLECTION_ADD;

			case "questcomplete":
				return event.getType() == TriggerEvent.Type.QUEST_COMPLETE;

			// A chronicle page was written for the first time. "is" narrows it
			// to one kind of page met or walked into (boss, enemy, location,
			// faction, world), or "quest" for any page a quest opened.
			case "pagewritten":
				return event.getType() == TriggerEvent.Type.PAGE_WRITTEN
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			// A Grand Exchange offer finished; "is" narrows to bought or sold.
			case "geoffer":
				return event.getType() == TriggerEvent.Type.GE_OFFER
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			case "channeljoined":
				return event.getType() == TriggerEvent.Type.CHANNEL_JOIN;
			case "channelleft":
				return event.getType() == TriggerEvent.Type.CHANNEL_LEAVE;

			// A named NPC's overhead line. Names match like npcSpawn's and the
			// message like chatMessage's contains - spelled out here rather
			// than through matchesText, which would also try the message
			// against the NAMES and quietly widen what a rule matches.
			case "npcshout":
				return event.getType() == TriggerEvent.Type.NPC_SHOUT
					&& (names == null || matchesAnyName(event.getName()))
					&& (contains == null || event.getMessage().toLowerCase(Locale.ROOT)
						.contains(contains.toLowerCase(Locale.ROOT)));

			// How this particular follower feels about where it is standing.
			// The set is rolled once per character, so the answer is a fact
			// about your follower rather than about the game.
			case "feelsabout":
				return ctx.feelsAbout(is == null ? "liked" : is);

			// The ROLL alone, before experience overrode it. Paired with
			// placeScore, this is how a rule catches the two disagreeing -
			// which feelsAbout cannot express, having already resolved the
			// argument. The disagreement is where the temperament shows.
			case "rolledfeeling":
				return ctx.rolledFeeling(is == null ? "liked" : is);

			// The follower is hoping for something. Mostly used NEGATED, to stop
			// a second want being asked for while one is still open.
			case "wanting":
				return ctx.isWanting();

			// A small thing has been wished for and not yet found. Negated by
			// the wish rules (one hope at a time) and required by the gift's
			// thank-you, which names the thing via {wish}.
			case "wishing":
				return ctx.isWishing()
					&& (is == null || is.equalsIgnoreCase(ctx.getWishLabel()));

			// And the wished-for thing is actually in the player's bag. The
			// gift's thank-you requires this; the bluff rule requires its
			// absence - claiming to have found a feather is checkable, and a
			// follower that cannot tell is a follower that cannot see.
			// One of these items is in the bag now: state, so a rule rises when
			// it arrives - the golden key in Priest in Peril, picked up off the
			// right monk.
			case "inventoryhas":
				return ctx.isInInventory(ids);

			case "wishitemheld":
				return ctx.isWishedItemInBag();

			case "wantfulfilled":
				return event.getType() == TriggerEvent.Type.WANT_FULFILLED;
			case "wantexpired":
				return event.getType() == TriggerEvent.Type.WANT_EXPIRED;

			// Room left in the bag. The point of this one is the WARNING - set
			// maximum to 2 and the follower speaks up while there is still
			// somewhere to put the next log, which is a companion paying
			// attention rather than a companion narrating a full inventory.
			case "inventoryfree":
			{
				int free = ctx.getFreeInventorySlots();
				return free <= orDefault(maximum, 2) && free >= orDefault(minimum, 0);
			}

			// How busy it is here. "within" is the radius, "minimum" how many
			// other players it takes before that counts as a crowd.
			case "playersnearby":
				return ctx.countPlayersNearby(orDefault(within, 10))
					>= orDefault(minimum, 5);

			// Somebody is looking at the follower. "ticks" is how long the mouse
			// has to rest on it: a cursor crossing the screen is not attention.
			case "hovered":
				return ctx.getHoverTicks() >= orDefault(ticks, 5);

			case "examined":
				return event.getType() == TriggerEvent.Type.EXAMINED;

			// The player examined something - the other direction. Names
			// narrow it to particular things, so the scarecrow can have its
			// own line without everything having one.
			case "examinedthing":
				return event.getType() == TriggerEvent.Type.EXAMINED_THING
					&& (names == null || matchesAnyName(event.getName()));

			// The player came back from a long absence during which the
			// follower walked round to peer at them. The check itself is
			// silent - the damper holds while nobody is there to read a line -
			// so this is the payoff, landing on a present player.
			case "attendedagain":
				return event.getType() == TriggerEvent.Type.ATTENDED_AGAIN;

			// The follower finished minuting one of the player's NPC dialogs:
			// it stepped aside, wrote through the meeting, and put the scroll
			// away as the box closed.
			case "minutestaken":
				return event.getType() == TriggerEvent.Type.MINUTES_TAKEN;

			case "tally":
			{
				if (of == null)
				{
					return false;
				}
				int counted = ctx.getTally(of);
				return counted >= orDefault(minimum, 1)
					&& (maximum == null || counted <= maximum);
			}

			// The moment a record was beaten. {record}, {value} and {previous}
			// are on the event; "names" narrows to particular records.
			case "personalbest":
				return event.getType() == TriggerEvent.Type.RECORD
					&& (names == null || matchesAnyName(event.getName()));

			// How many times the follower has been started up with this player.
			// Login only, which is the one moment it changes and the one moment
			// remarking on it is not out of nowhere.
			case "sessioncount":
			{
				if (event.getType() != TriggerEvent.Type.LOGIN)
				{
					return false;
				}
				int sessions = ctx.getSessionCount();
				return sessions >= orDefault(minimum, 1)
					&& (maximum == null || sessions <= maximum)
					&& (every == null || every <= 0 || sessions % every == 0);
			}

			// How long the player has been doing the same thing. Knows nothing
			// about trees or rocks: an animation running for minutes IS the
			// activity, so this covers every skill at once.
			case "repeating":
				return ctx.getRepeatingTicks() >= orDefault(ticks, 100)
					&& (ids == null || idsContain(ctx.getRepeatingAnimation()));

			// The player is working something - a tree, a rock, a spot - as
			// the skilling company sees it. "is" names the skill, "contains"
			// a piece of the target's name, so a yew can have its own line.
			case "working":
				return ctx.isWorking()
					&& (is == null || is.equalsIgnoreCase(ctx.getWorkActivity()))
					&& (contains == null || ctx.getWorkTargetName().toLowerCase(Locale.ROOT)
						.contains(contains.toLowerCase(Locale.ROOT)));

			// The tick of an XP drop. "every" counts within the work session,
			// the way npcKill's counts within the fight: 8 fires on the
			// eighth drop and every eighth after.
			case "xpdrop":
				return event.getType() == TriggerEvent.Type.XP_DROP
					&& (names == null || matchesAnyName(event.getName()))
					&& (every == null || every <= 0
						|| (event.getCount() > 0 && event.getCount() % every == 0));

			case "workended":
				return event.getType() == TriggerEvent.Type.WORK_END
					&& (names == null || matchesAnyName(event.getName()));

			case "targetdepleted":
				return event.getType() == TriggerEvent.Type.TARGET_DEPLETED;

			case "bagfull":
				return event.getType() == TriggerEvent.Type.BAG_FULL;
			case "baghalf":
				return event.getType() == TriggerEvent.Type.BAG_HALF;

			default:
				log.warn("Unknown condition type '{}'", type);
				return false;
		}
	}

	private boolean allOf(TriggerContext ctx, TriggerEvent event)
	{
		if (conditions == null || conditions.isEmpty())
		{
			return true;
		}
		for (Condition child : conditions)
		{
			if (!child.matches(ctx, event))
			{
				return false;
			}
		}
		return true;
	}

	private boolean anyOf(TriggerContext ctx, TriggerEvent event)
	{
		if (conditions == null)
		{
			return false;
		}
		for (Condition child : conditions)
		{
			if (child.matches(ctx, event))
			{
				return true;
			}
		}
		return false;
	}

	private boolean matchesNpc(TriggerEvent event)
	{
		if (idsContain(event.getId()))
		{
			return true;
		}
		return names != null && matchesAnyName(event.getName());
	}

	/**
	 * Whether an NPC in the scene is one this rule cares about.
	 *
	 * <p>{@code minimum} and {@code maximum} bracket its COMBAT LEVEL, which is
	 * what turns npcNearby into "something big is standing near us" without
	 * naming a single monster - and keeps covering everything the game adds
	 * later. With no names or ids given, that bracket is the whole test.
	 */
	private boolean isNearbyCandidate(NPC npc)
	{
		if (minimum != null && npc.getCombatLevel() < minimum)
		{
			return false;
		}
		if (maximum != null && npc.getCombatLevel() > maximum)
		{
			return false;
		}
		if (names == null && ids == null)
		{
			return minimum != null || maximum != null;
		}
		return matchesNpcObject(npc);
	}

	private boolean matchesNpcObject(NPC npc)
	{
		if (idsContain(npc.getId()))
		{
			return true;
		}
		return names != null && npc.getName() != null && matchesAnyName(npc.getName());
	}

	private boolean matchesRegion(TriggerContext ctx, int currentRegion)
	{
		if (regions == null || regions.isEmpty())
		{
			return false;
		}
		if (regionsUnboxed == null)
		{
			regionsUnboxed = unbox(regions);
		}
		if (Boolean.TRUE.equals(anyLoadedRegion))
		{
			for (int region : regionsUnboxed)
			{
				if (ctx.isRegionLoaded(region))
				{
					return true;
				}
			}
			return false;
		}
		return contains(regionsUnboxed, currentRegion);
	}

	private boolean matchesArea(TriggerContext ctx)
	{
		if (ctx.getLocation() == null || x1 == null || y1 == null || x2 == null || y2 == null)
		{
			return false;
		}
		int px = ctx.getLocation().getX();
		int py = ctx.getLocation().getY();
		if (plane != null && ctx.getLocation().getPlane() != plane)
		{
			return false;
		}
		return px >= Math.min(x1, x2) && px <= Math.max(x1, x2)
			&& py >= Math.min(y1, y2) && py <= Math.max(y1, y2);
	}

	/**
	 * Narrows a chat rule to one kind of message. {@code is} takes the
	 * {@link net.runelite.api.ChatMessageType} name exactly as {@code ::follower
	 * chatwatch} prints it, so the way to write one of these is to watch the
	 * real message go past and copy what it says. No table of friendly aliases,
	 * because a table is a thing that drifts out of date silently.
	 */
	private boolean matchesChatType(TriggerEvent event)
	{
		if (is == null)
		{
			return true;
		}
		if (wantedChatType == Integer.MIN_VALUE)
		{
			int resolved = -1;
			try
			{
				resolved = net.runelite.api.ChatMessageType
					.valueOf(is.toUpperCase(Locale.ROOT)).getType();
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Unknown chat type '{}' - see ::follower chatwatch for the real names", is);
			}
			wantedChatType = resolved;
		}
		return wantedChatType >= 0 && event.getChatTypeId() == wantedChatType;
	}

	private transient int wantedChatType = Integer.MIN_VALUE;

	/**
	 * {@code from} is "player" for the local player's own lines and "others"
	 * for everybody else's. Absent means either.
	 */
	private boolean matchesSpeaker(TriggerContext ctx, TriggerEvent event)
	{
		if (from == null)
		{
			return true;
		}
		boolean mine = isFromPlayer(ctx, event);
		return "player".equalsIgnoreCase(from) ? mine : !mine;
	}

	private static boolean isFromPlayer(TriggerContext ctx, TriggerEvent event)
	{
		// Read straight off the client rather than through getPlayerName(),
		// which substitutes "you" when there is no player - a placeholder
		// fallback that would be a wrong ANSWER here.
		net.runelite.api.Player local = ctx.getClient().getLocalPlayer();
		String player = local == null || local.getName() == null ? "" : local.getName();
		if (player.isEmpty() || event.getName().isEmpty())
		{
			return false;
		}
		// The game pads display names with non-breaking spaces.
		return player.replace(' ', ' ').trim()
			.equalsIgnoreCase(event.getName().replace(' ', ' ').trim());
	}

	/** Matches against {@code contains} (case-insensitive), {@code regex}, or {@code names}. */
	private boolean matchesText(String text)
	{
		if (text == null)
		{
			return false;
		}
		if (contains != null && text.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)))
		{
			return true;
		}
		if (regex != null)
		{
			if (compiledRegex == null)
			{
				compiledRegex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			}
			if (compiledRegex.matcher(text).find())
			{
				return true;
			}
		}
		return names != null && matchesAnyName(text);
	}

	/** Name matching is case-insensitive and supports {@code *} wildcards. */
	/**
	 * Compiles one name pattern the way every {@code names} list here is
	 * matched: literal, case-insensitive, with {@code *} standing for any run
	 * of characters.
	 */
	public static Pattern compileName(String name)
	{
		StringBuilder pattern = new StringBuilder();
		for (String literal : name.split("\\*", -1))
		{
			if (pattern.length() > 0)
			{
				pattern.append(".*");
			}
			pattern.append(Pattern.quote(literal));
		}
		return Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE);
	}

	/** The game pads names with non-breaking spaces in places. */
	public static String normaliseName(String candidate)
	{
		return candidate == null ? "" : candidate.replace('\u00A0', ' ').trim();
	}

	private boolean matchesAnyName(String candidate)
	{
		if (candidate == null || names == null)
		{
			return false;
		}
		if (compiledNames == null)
		{
			compiledNames = new java.util.ArrayList<>(names.size());
			for (String name : names)
			{
				compiledNames.add(compileName(name));
			}
		}
		String normalised = normaliseName(candidate);
		for (Pattern pattern : compiledNames)
		{
			if (pattern.matcher(normalised).matches())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Every region id this condition tree names, for the trait roll - the
	 * follower's likes are drawn from the places the rule set already has
	 * opinions about, so the pool maintains itself as rules are added.
	 */
	public void collectRegions(java.util.Collection<Integer> into)
	{
		if (regions != null)
		{
			for (Integer region : regions)
			{
				if (region != null)
				{
					into.add(region);
				}
			}
		}
		if (conditions != null)
		{
			for (Condition child : conditions)
			{
				if (child != null)
				{
					child.collectRegions(into);
				}
			}
		}
	}

	/** Whether this condition tree contains the given type (case-insensitive). */
	public boolean usesType(String wanted)
	{
		if (wanted.equalsIgnoreCase(type))
		{
			return true;
		}
		if (conditions != null)
		{
			for (Condition child : conditions)
			{
				if (child != null && child.usesType(wanted))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether the quest steps this condition requires - its questVar, alone
	 * or inside an "all" - still hold. True when it reads none. For a line
	 * kept waiting, which can outlive the step it was about.
	 */
	public boolean questStepHolds(TriggerContext ctx, TriggerEvent event)
	{
		return holds(ctx, event, QUEST_STEP);
	}

	private static final java.util.Set<String> QUEST_STEP = java.util.Set.of("questvar");

	/**
	 * The conditions that say how things stand rather than what just
	 * happened, and read nothing else: no roll, no count, no event.
	 */
	private static final java.util.Set<String> STANDING = java.util.Set.of(
		"healthbelow", "prayerbelow", "poisoned", "venomed", "npcnearby");

	/**
	 * Whether how things stood when the line won - its health, prayer,
	 * poison or the person near, alone or inside an "all" - still holds.
	 * True when it reads none. For a line kept waiting: behaviour QA, 16 Oct,
	 * "49 HP! Get out!" said at 70 percent after the player had eaten, and a
	 * passer-by announced after he had gone.
	 */
	public boolean standingHolds(TriggerContext ctx, TriggerEvent event)
	{
		return holds(ctx, event, STANDING);
	}

	/** Whether this condition's parts of these types, alone or inside an "all", still match. */
	private boolean holds(TriggerContext ctx, TriggerEvent event, java.util.Set<String> types)
	{
		if (type != null && types.contains(type.toLowerCase(Locale.ROOT)))
		{
			return matches(ctx, event);
		}
		if ("all".equalsIgnoreCase(type) && conditions != null)
		{
			for (Condition child : conditions)
			{
				if (child != null && !child.holds(ctx, event, types))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** The counts a person says out loud, rather than every one. */
	static boolean isRoundCount(int n)
	{
		if (n <= 0)
		{
			return false;
		}
		if (n == 1 || n == 5 || n == 10 || n == 25 || n == 50 || n == 75)
		{
			return true;
		}
		return n < 1000 ? n % 100 == 0 : n % 500 == 0;
	}

	private static int orDefault(Integer boxed, int fallback)
	{
		return boxed == null ? fallback : boxed;
	}

	/**
	 * The poison varp holds small cycle values while poisoned; venom is encoded
	 * as the same varp pushed above a million. Same scheme RuneLite's own
	 * poison plugin decodes.
	 */
	private static final int VENOM_THRESHOLD = 1_000_000;
}
