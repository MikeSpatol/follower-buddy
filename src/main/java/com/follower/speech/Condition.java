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
 *   <tr><td>chatMessage</td><td>{@code contains} or {@code regex}</td></tr>
 *   <tr><td>varbitEquals / varbitChanged</td><td>{@code varbit}, {@code value}</td></tr>
 *   <tr><td>animationSelf</td><td>{@code ids}</td></tr>
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
 *   <tr><td>npcKill</td><td>{@code minimum} / {@code maximum} combat level, optional {@code names} / {@code ids}</td></tr>
 *   <tr><td>energyBelow</td><td>{@code percent}</td></tr>
 *   <tr><td>idle</td><td>{@code ticks}</td></tr>
 *   <tr><td>login / always</td><td>no fields</td></tr>
 *   <tr><td>chance</td><td>{@code percent} — rolled each evaluation</td></tr>
 * </table>
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
	public Integer maximum;
	public Integer within;
	public Integer ticks;
	public Integer varbit;
	public Integer value;

	/** Fire on every Nth occurrence rather than on each one. */
	public Integer every;

	public Integer x1;
	public Integer y1;
	public Integer x2;
	public Integer y2;
	public Integer plane;

	public String contains;
	public String regex;

	/** A named band, for conditions that read better as a word than a number. */
	public String is;

	public Boolean requirePrayerActive;
	public Boolean anyLoadedRegion;

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

			case "chance":
				return ThreadLocalRandom.current().nextInt(100) < orDefault(percent, 100);

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
					nearbyCached = ctx.isNpcNearby(this::matchesNpcObject, orDefault(within, 15));
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

			case "chatmessage":
				return event.getType() == TriggerEvent.Type.CHAT && matchesText(event.getMessage());

			case "varbitequals":
				return varbit != null && ctx.getClient().getVarbitValue(varbit) == orDefault(value, 1);
			case "varbitchanged":
				return event.getType() == TriggerEvent.Type.VARBIT
					&& varbit != null && event.getId() == varbit
					&& (value == null || event.getValue() == value);

			case "animationself":
				return event.getType() == TriggerEvent.Type.ANIMATION && idsContain(event.getId());

			case "levelup":
				return event.getType() == TriggerEvent.Type.LEVEL_UP && matchesText(event.getName());

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

			// How long the player has been doing the same thing. Knows nothing
			// about trees or rocks: an animation running for minutes IS the
			// activity, so this covers every skill at once.
			case "repeating":
				return ctx.getRepeatingTicks() >= orDefault(ticks, 100)
					&& (ids == null || idsContain(ctx.getRepeatingAnimation()));

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
				StringBuilder pattern = new StringBuilder();
				for (String literal : name.split("\\*", -1))
				{
					if (pattern.length() > 0)
					{
						pattern.append(".*");
					}
					pattern.append(Pattern.quote(literal));
				}
				compiledNames.add(Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE));
			}
		}
		// The game pads names with non-breaking spaces in places; normalise first.
		String normalised = candidate.replace(' ', ' ').trim();
		for (Pattern pattern : compiledNames)
		{
			if (pattern.matcher(normalised).matches())
			{
				return true;
			}
		}
		return false;
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
