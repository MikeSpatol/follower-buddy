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
	public Integer within;
	public Integer ticks;
	public Integer varbit;
	public Integer value;

	public Integer x1;
	public Integer y1;
	public Integer x2;
	public Integer y2;
	public Integer plane;

	public String contains;
	public String regex;

	public Boolean requirePrayerActive;
	public Boolean anyLoadedRegion;

	private transient Pattern compiledRegex;
	private transient List<Pattern> compiledNames;

	public boolean matches(TriggerContext ctx, TriggerEvent event)
	{
		if (type == null)
		{
			return false;
		}

		switch (type.toLowerCase(Locale.ROOT))
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
				return ctx.isNpcNearby(this::matchesNpcObject, orDefault(within, 15));

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
				return event.getType() == TriggerEvent.Type.ANIMATION && ids != null && ids.contains(event.getId());

			case "levelup":
				return event.getType() == TriggerEvent.Type.LEVEL_UP && matchesText(event.getName());

			case "damagetaken":
				return event.getType() == TriggerEvent.Type.DAMAGE_TAKEN
					&& event.getValue() >= orDefault(minimum, 1);

			case "itemequipped":
				if (ids == null)
				{
					return false;
				}
				for (int id : ids)
				{
					if (ctx.isEquipped(id))
					{
						return true;
					}
				}
				return false;

			case "poisoned":
			{
				int poison = ctx.getClient().getVarpValue(net.runelite.api.VarPlayer.POISON);
				return poison > 0 && poison < VENOM_THRESHOLD;
			}
			case "venomed":
				return ctx.getClient().getVarpValue(net.runelite.api.VarPlayer.POISON) >= VENOM_THRESHOLD;

			case "skulled":
				return ctx.isSkulled();

			case "energybelow":
				return ctx.getEnergyPercent() < orDefault(percent, 20);

			case "idle":
				return ctx.getIdleTicks() >= orDefault(ticks, 50);

			case "login":
				return event.getType() == TriggerEvent.Type.LOGIN;

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
		if (ids != null && ids.contains(event.getId()))
		{
			return true;
		}
		return names != null && matchesAnyName(event.getName());
	}

	private boolean matchesNpcObject(NPC npc)
	{
		if (ids != null && ids.contains(npc.getId()))
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
		if (Boolean.TRUE.equals(anyLoadedRegion))
		{
			for (int region : regions)
			{
				if (ctx.isRegionLoaded(region))
				{
					return true;
				}
			}
			return false;
		}
		return regions.contains(currentRegion);
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
