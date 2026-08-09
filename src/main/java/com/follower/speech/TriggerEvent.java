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
		THIEVING_START,
		THIEVING_END,
		NPC_KILL,
		PLAYER_DEATH,
		LOOT,
		RECORD,
		EXAMINED,
		WANT_FULFILLED,
		WANT_EXPIRED,
		ANSWERED,
		MANUAL,
	}

	/**
	 * The player answered a question, by picking a branch in the conversation
	 * the follower opened. {@code yes} or {@code no}, and never anything else -
	 * an option cannot be misheard the way a typed word could.
	 */
	public static TriggerEvent answered(String answer)
	{
		TriggerEvent event = new TriggerEvent(Type.ANSWERED);
		event.name = answer == null ? "" : answer;
		event.placeholders.put("answer", event.name);
		return event;
	}

	/** A want got its answer, one way or the other. {want} names what it was. */
	public static TriggerEvent want(Type type, String label)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = label == null ? "" : label;
		event.placeholders.put("want", event.name);
		return event;
	}

	/**
	 * A personal best was just beaten. {record} names it, {value} is the new
	 * mark and {previous} the one it replaced - which is what makes the line
	 * land, since a record without the old number is just a number.
	 */
	public static TriggerEvent record(String what, int value, int previous)
	{
		TriggerEvent event = new TriggerEvent(Type.RECORD);
		event.name = what == null ? "" : what;
		event.value = value;
		event.previousValue = previous;
		event.placeholders.put("record", event.name);
		event.placeholders.put("value", Integer.toString(value));
		event.placeholders.put("previous", Integer.toString(previous));
		return event;
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
		return kill(npcId, npcName, combatLevel, 0);
	}

	/**
	 * @param count how many of this NPC have been killed this session, which
	 * rides along so a rule can fire on the fiftieth and say which one it was
	 */
	public static TriggerEvent kill(int npcId, String npcName, int combatLevel, int count)
	{
		TriggerEvent event = npc(Type.NPC_KILL, npcId, npcName);
		event.value = combatLevel;
		event.count = count;
		event.placeholders.put("level", Integer.toString(combatLevel));
		event.placeholders.put("count", Integer.toString(count));
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

	/** How many times this has happened before, where the raiser counts. */
	private int count;
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

	/**
	 * Something was said. The sender rides along in {@link #getName()} so a rule
	 * can tell the player's own line from the rest of the street - which is the
	 * difference between a follower that answers you and one that answers
	 * everybody.
	 */
	public static TriggerEvent chat(String message, int chatTypeId, String sender)
	{
		TriggerEvent event = new TriggerEvent(Type.CHAT);
		event.message = message == null ? "" : message;
		event.chatTypeId = chatTypeId;
		event.name = sender == null ? "" : sender;
		event.placeholders.put("message", event.message);
		event.placeholders.put("speaker", event.name);
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

	public int getCount()
	{
		return count;
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
