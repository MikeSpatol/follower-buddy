package com.follower.speech;

import java.util.HashMap;
import java.util.Map;

/**
 * One thing that happened, handed to {@link SpeechEngine}. A synthetic
 * {@link Type#TICK} event is raised every game tick so that purely state-based
 * rules (low HP, low prayer) get a chance to fire.
 */
public final class TriggerEvent
{
	public enum Type
	{
		TICK,
		NPC_SPAWN,
		NPC_DESPAWN,
		CHAT,
		VARBIT,
		ANIMATION,
		LEVEL_UP,
		REGION_CHANGE,
		LOGIN,
		DAMAGE_TAKEN,
		EQUIPMENT_CHANGE,
		THRALL_START,
		THRALL_SWITCH,
		THRALL_END,
		ERRAND_START,
		ERRAND_END,
		COMBAT_START,
		COMBAT_END,
		NPC_KILL,
		PLAYER_DEATH,
		LOOT,
		MANUAL,
	}

	/** The player has died. Where it happened lives in the context, not here. */
	public static TriggerEvent death()
	{
		return new TriggerEvent(Type.PLAYER_DEATH);
	}

	/**
	 * Loot arrived. {@code value} carries the total for {@code lootWorth}
	 * conditions; {item} and {value} land as placeholders so a line can name
	 * the prize without quoting a raw number.
	 */
	public static TriggerEvent loot(int totalValue, String bestItem)
	{
		TriggerEvent event = new TriggerEvent(Type.LOOT);
		event.value = totalValue;
		event.name = bestItem == null ? "" : bestItem;
		event.placeholders.put("item", event.name);
		event.placeholders.put("value", formatGp(totalValue));
		return event;
	}

	/** 1.2M / 214K / 950 - the way a player says an amount, not a ledger. */
	private static String formatGp(long value)
	{
		if (value >= 1_000_000)
		{
			return String.format("%.1fM", value / 1_000_000.0);
		}
		if (value >= 10_000)
		{
			return (value / 1_000) + "K";
		}
		return Long.toString(value);
	}

	/**
	 * A fight starting or ending, with what is being fought in {npc} so a line
	 * can name it.
	 */
	public static TriggerEvent combat(Type type, String npcName)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = npcName == null ? "" : npcName;
		event.placeholders.put("npc", event.name);
		return event;
	}

	/**
	 * Something the PLAYER killed. {@code value} carries the combat level, which
	 * is how {@code npcKill} tells a boss from a rat; the name lands in {npc}.
	 *
	 * <p>Only raised for kills the player had a hand in - see the hitsplat
	 * attribution in the plugin - so a follower does not applaud a guard losing
	 * a fight with a dog on the other side of the street.
	 */
	public static TriggerEvent kill(int npcId, String npcName, int combatLevel)
	{
		TriggerEvent event = npc(Type.NPC_KILL, npcId, npcName);
		event.value = combatLevel;
		event.placeholders.put("level", Integer.toString(combatLevel));
		return event;
	}

	/** Thrall-mode transitions; {@code style} lands in the {style} placeholder. */
	public static TriggerEvent thrall(Type type, String style)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = style == null ? "" : style;
		event.placeholders.put("style", event.name);
		return event;
	}

	/**
	 * A resummon while already possessed: {@code style} is the new thrall
	 * type, {@code from} the one being left behind.
	 */
	public static TriggerEvent thrallSwitch(String from, String style)
	{
		TriggerEvent event = thrall(Type.THRALL_SWITCH, style);
		event.placeholders.put("from", from == null ? "" : from);
		return event;
	}

	/** Errand transitions; the errand's name matches rule {@code names} lists. */
	public static TriggerEvent errand(Type type, String errand)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = errand == null ? "" : errand;
		event.placeholders.put("errand", event.name);
		return event;
	}

	private final Type type;
	private final Map<String, String> placeholders = new HashMap<>();

	/** Generic payload; meaning depends on {@link #type}. */
	private String name = "";
	private int id = -1;
	private int value;
	private int previousValue;
	private String message = "";
	private int chatTypeId = -1;

	private TriggerEvent(Type type)
	{
		this.type = type;
	}

	public static TriggerEvent tick()
	{
		return new TriggerEvent(Type.TICK);
	}

	public static TriggerEvent npc(Type type, int npcId, String npcName)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.id = npcId;
		event.name = npcName == null ? "" : npcName;
		event.placeholders.put("npc", event.name);
		event.placeholders.put("npcId", Integer.toString(npcId));
		return event;
	}

	public static TriggerEvent chat(String message, int chatTypeId)
	{
		TriggerEvent event = new TriggerEvent(Type.CHAT);
		event.message = message == null ? "" : message;
		event.chatTypeId = chatTypeId;
		event.placeholders.put("message", event.message);
		return event;
	}

	public static TriggerEvent varbit(int varbitId, int newValue, int oldValue)
	{
		TriggerEvent event = new TriggerEvent(Type.VARBIT);
		event.id = varbitId;
		event.value = newValue;
		event.previousValue = oldValue;
		event.placeholders.put("value", Integer.toString(newValue));
		return event;
	}

	public static TriggerEvent animation(int animationId)
	{
		TriggerEvent event = new TriggerEvent(Type.ANIMATION);
		event.id = animationId;
		return event;
	}

	public static TriggerEvent levelUp(String skill, int level)
	{
		TriggerEvent event = new TriggerEvent(Type.LEVEL_UP);
		event.name = skill == null ? "" : skill;
		event.value = level;
		event.placeholders.put("skill", event.name);
		event.placeholders.put("level", Integer.toString(level));
		return event;
	}

	public static TriggerEvent regionChange(int newRegion, int oldRegion)
	{
		TriggerEvent event = new TriggerEvent(Type.REGION_CHANGE);
		event.id = newRegion;
		event.value = newRegion;
		event.previousValue = oldRegion;
		event.placeholders.put("region", Integer.toString(newRegion));
		return event;
	}

	public static TriggerEvent damageTaken(int amount)
	{
		TriggerEvent event = new TriggerEvent(Type.DAMAGE_TAKEN);
		event.value = amount;
		event.placeholders.put("damage", Integer.toString(amount));
		return event;
	}

	public static TriggerEvent simple(Type type)
	{
		return new TriggerEvent(type);
	}

	public Type getType()
	{
		return type;
	}

	public String getName()
	{
		return name;
	}

	public int getId()
	{
		return id;
	}

	public int getValue()
	{
		return value;
	}

	public int getPreviousValue()
	{
		return previousValue;
	}

	public String getMessage()
	{
		return message;
	}

	public int getChatTypeId()
	{
		return chatTypeId;
	}

	public Map<String, String> getPlaceholders()
	{
		return placeholders;
	}
}
