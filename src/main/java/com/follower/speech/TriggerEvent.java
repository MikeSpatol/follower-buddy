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
		SOUVENIR_LOST,
		BET_WON,
		BET_LOST,
		ADVICE_HEEDED,
		ADVICE_IGNORED,
		CHALLENGE_MET,
		CHALLENGE_FAILED,
		UNDERFOOT,
		MANUAL,
		WORLD_HOP,
		GAME_UPDATE,
		NOTES_EDITED,
		MUSIC_UNLOCK,
		COLLECTION_ADD,
		QUEST_COMPLETE,
		GE_OFFER,
		CHANNEL_JOIN,
		CHANNEL_LEAVE,
		NPC_SHOUT,
		TRACK_CHANGE,
		EXAMINED_THING,
		ATTENDED_AGAIN,
		MINUTES_TAKEN,
		XP_DROP,
		WORK_END,
		TARGET_DEPLETED,
		BAG_FULL,
		BAG_HALF,
		/** A boss kill count the game reported in chat. */
		KILL_COUNT,
		/** The logout tab opened. */
		FAREWELL,
		/** The follower noticed something and went to look. */
		NOTICED,
		/** A chronicle page was written for the first time. */
		PAGE_WRITTEN,
		/** A Send with a misgiving in it: the follower stopped at the edge first. */
		HESITATED,
		/** The player walked straight at the parked follower and it stepped aside. */
		STEPPED_ASIDE,
	}

	/**
	 * An XP drop landed. {skill} names it; {@code count} is how many the
	 * current work session has seen, this one included, so a rule can fire
	 * on every eighth the way npcKill fires on every fiftieth. Zero outside
	 * a session.
	 */
	public static TriggerEvent xpDrop(String skill, int count)
	{
		TriggerEvent event = new TriggerEvent(Type.XP_DROP);
		event.name = skill == null ? "" : skill;
		event.count = count;
		event.placeholders.put("skill", event.name);
		event.placeholders.put("count", Integer.toString(count));
		return event;
	}

	/**
	 * A work session ended. The name is the activity, for {@code names}
	 * lists; {activity} and {target} say what it was and on what.
	 */
	public static TriggerEvent workEnd(String activity, String target)
	{
		TriggerEvent event = new TriggerEvent(Type.WORK_END);
		event.name = activity == null ? "" : activity;
		event.placeholders.put("activity", event.name);
		event.placeholders.put("target", target == null ? "" : target);
		return event;
	}

	/** The thing being worked changed id or went. {target} names it. */
	public static TriggerEvent targetDepleted(String target)
	{
		TriggerEvent event = new TriggerEvent(Type.TARGET_DEPLETED);
		event.name = target == null ? "" : target;
		event.placeholders.put("target", event.name);
		return event;
	}

	/**
	 * The PLAYER examined something - the reverse direction of
	 * {@link Type#EXAMINED}, which is somebody examining the follower.
	 * {target} names what was looked at.
	 */
	public static TriggerEvent examinedThing(String target)
	{
		TriggerEvent event = new TriggerEvent(Type.EXAMINED_THING);
		event.name = target == null ? "" : target;
		event.placeholders.put("target", event.name);
		return event;
	}

	/**
	 * The game's music changed to another track - the local song, as against
	 * {@link Type#MUSIC_UNLOCK}, which is hearing one for the first time ever.
	 */
	public static TriggerEvent trackChange(String track)
	{
		TriggerEvent event = new TriggerEvent(Type.TRACK_CHANGE);
		event.name = track == null ? "" : track;
		event.placeholders.put("track", event.name);
		return event;
	}

	/** A world hop landed. {world} is the number on the new door. */
	public static TriggerEvent worldHop(int world)
	{
		TriggerEvent event = new TriggerEvent(Type.WORLD_HOP);
		event.value = world;
		event.placeholders.put("world", Integer.toString(world));
		return event;
	}

	/** A music track just unlocked. {track} names it for the catalogue. */
	public static TriggerEvent musicUnlock(String track)
	{
		TriggerEvent event = new TriggerEvent(Type.MUSIC_UNLOCK);
		event.name = track == null ? "" : track;
		event.placeholders.put("track", event.name);
		return event;
	}

	/**
	 * A page of the chronicle was written for the first time. {page} is the
	 * page's name. What a rule's "is" narrows to is the page's kind (boss,
	 * enemy, location, faction, world) when it was met or walked into, and
	 * "quest" for any page a quest begun or finished opened, which the
	 * follower has not seen for itself.
	 */
	public static TriggerEvent pageWritten(String kind, String page)
	{
		TriggerEvent event = new TriggerEvent(Type.PAGE_WRITTEN);
		event.name = kind == null ? "" : kind;
		event.placeholders.put("page", page == null ? "" : page);
		return event;
	}

	/** The other ledger gained a line. {item} is what went in it. */
	public static TriggerEvent collectionAdd(String item)
	{
		TriggerEvent event = new TriggerEvent(Type.COLLECTION_ADD);
		event.name = item == null ? "" : item;
		event.placeholders.put("item", event.name);
		return event;
	}

	/** A quest reward scroll was handed over. {quest} names the quest. */
	public static TriggerEvent questComplete(String quest)
	{
		TriggerEvent event = new TriggerEvent(Type.QUEST_COMPLETE);
		event.name = quest == null ? "" : quest;
		event.placeholders.put("quest", event.name);
		return event;
	}

	/**
	 * A Grand Exchange offer finished. The name carries "bought" or "sold"
	 * for the condition's {@code is}; {item} and {value} land as placeholders
	 * so the line can read like a clerk's telegram rather than a receipt.
	 */
	public static TriggerEvent geOffer(boolean bought, String item, long spent)
	{
		TriggerEvent event = new TriggerEvent(Type.GE_OFFER);
		event.name = bought ? "bought" : "sold";
		event.placeholders.put("item", item == null ? "" : item);
		event.placeholders.put("value", formatGp(spent));
		return event;
	}

	/** Somebody arrived in or left the player's chat channel. {name} says who. */
	public static TriggerEvent channel(Type type, String who)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = who == null ? "" : who;
		event.placeholders.put("name", event.name);
		return event;
	}

	/**
	 * A named NPC said something overhead. The name matches like npcSpawn's,
	 * the message like chatMessage's, and both land as placeholders - NPC
	 * overheads never reach the chatbox, so no chat rule can see these.
	 */
	public static TriggerEvent npcShout(String npc, String message)
	{
		TriggerEvent event = new TriggerEvent(Type.NPC_SHOUT);
		event.name = npc == null ? "" : npc;
		event.message = message == null ? "" : message;
		event.placeholders.put("npc", event.name);
		event.placeholders.put("message", event.message);
		return event;
	}

	/** The thing it was carrying is gone. {souvenir} names it one last time. */
	public static TriggerEvent souvenirLost(String what)
	{
		TriggerEvent event = new TriggerEvent(Type.SOUVENIR_LOST);
		event.name = what == null ? "" : what;
		event.placeholders.put("souvenir", event.name);
		return event;
	}

	/** A challenge was met inside the window, or it was not. */
	public static TriggerEvent challenge(Type type, String about)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = about == null ? "" : about;
		event.placeholders.put("challenge", event.name);
		return event;
	}

	/** The player took the follower's advice, or let the window shut. */
	public static TriggerEvent advice(Type type, String about)
	{
		TriggerEvent event = new TriggerEvent(type);
		event.name = about == null ? "" : about;
		event.placeholders.put("advice", event.name);
		return event;
	}

	/** A prediction came good, or did not. */
	public static TriggerEvent bet(Type type)
	{
		return new TriggerEvent(type);
	}

	/**
	 * The player answered a question, by picking a branch in the conversation
	 * the follower opened. {@code yes} or {@code no}, and never anything else -
	 * an option cannot be misheard the way a typed word could.
	 */
	public static TriggerEvent answered(String answer)
	{
		return answered(answer, "");
	}

	/** The same, naming the tree that was answered, for a rule's {@code of}. */
	public static TriggerEvent answered(String answer, String tree)
	{
		TriggerEvent event = new TriggerEvent(Type.ANSWERED);
		event.name = answer == null ? "" : answer;
		event.message = tree == null ? "" : tree;
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
	/** The level-up's wider ledger: the total and the base, before and after. */
	private int total;
	private int previousTotal;
	private int base;
	private int previousBase;
	/** Milliseconds since the last level in this skill, or -1 for none known. */
	private long sinceMs = -1;
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
		return levelUp(skill, level, 0, 0, 0, 0, 0, -1, -1);
	}

	/**
	 * A level, with everything a milestone rule wants to know beside the
	 * number: the total level before and after, the lowest skill before and
	 * after, how many skills are at ninety-nine now (this one included, if
	 * it is), and how long it has been - since the last level in this
	 * skill, and since the last level at all. Either gap is -1 when the
	 * follower has no record to measure from.
	 *
	 * <p>{skill}, {level}, {total}, {base}, {nines}, {ninesOrdinal},
	 * {sinceLast} and {sinceAny}. The two gaps are empty strings rather
	 * than a number when unknown, so a rule that prints them guards with
	 * levelGap.
	 */
	public static TriggerEvent levelUp(String skill, int level, int total, int previousTotal,
		int base, int previousBase, int masteries, long sinceLastMs, long sinceAnyMs)
	{
		TriggerEvent event = new TriggerEvent(Type.LEVEL_UP);
		event.name = skill == null ? "" : skill;
		event.value = level;
		event.total = total;
		event.previousTotal = previousTotal;
		event.base = base;
		event.previousBase = previousBase;
		event.count = masteries;
		event.sinceMs = sinceLastMs;
		event.placeholders.put("skill", event.name);
		event.placeholders.put("level", Integer.toString(level));
		event.placeholders.put("total", Integer.toString(total));
		event.placeholders.put("base", Integer.toString(base));
		event.placeholders.put("nines", Integer.toString(masteries));
		event.placeholders.put("ninesOrdinal", Words.ordinal(masteries));
		event.placeholders.put("sinceLast", sinceLastMs < 0 ? "" : Words.duration(sinceLastMs));
		event.placeholders.put("sinceAny", sinceAnyMs < 0 ? "" : Words.duration(sinceAnyMs));
		return event;
	}

	/**
	 * The follower noticed something the player had not, and set off to look
	 * at it. {kind} is what sort of thing (valuable, nest, impling, star,
	 * landmark) and {thing} names it.
	 */
	public static TriggerEvent noticed(String kind, String thing)
	{
		TriggerEvent event = new TriggerEvent(Type.NOTICED);
		event.name = kind == null ? "" : kind;
		event.message = thing == null ? "" : thing;
		event.placeholders.put("kind", event.name);
		event.placeholders.put("thing", event.message);
		return event;
	}

	/**
	 * Sent somewhere with a misgiving about it. {misgiving} is the kind
	 * (wilderness, monster) and {thing} what it is about: "the Wilderness",
	 * or the monster's name.
	 */
	public static TriggerEvent hesitated(String misgiving, String thing)
	{
		TriggerEvent event = new TriggerEvent(Type.HESITATED);
		event.name = misgiving == null ? "" : misgiving;
		event.message = thing == null ? "" : thing;
		event.placeholders.put("misgiving", event.name);
		event.placeholders.put("thing", event.message);
		return event;
	}

	/**
	 * The game said what the kill count is. {boss} names it, {count} is
	 * the number, {ordinal} says it ("forty-first"), and {lastCount} is the
	 * highest count the follower had seen before - zero for a boss it has
	 * never heard the number for.
	 */
	public static TriggerEvent killCount(String boss, int count, int lastCount)
	{
		TriggerEvent event = new TriggerEvent(Type.KILL_COUNT);
		event.name = boss == null ? "" : boss;
		event.count = count;
		event.value = count;
		event.previousValue = lastCount;
		event.placeholders.put("boss", event.name);
		event.placeholders.put("count", Integer.toString(count));
		event.placeholders.put("ordinal", Words.ordinal(count));
		event.placeholders.put("lastCount", Integer.toString(lastCount));
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

	public int getTotal()
	{
		return total;
	}

	public int getPreviousTotal()
	{
		return previousTotal;
	}

	public int getBase()
	{
		return base;
	}

	public int getPreviousBase()
	{
		return previousBase;
	}

	public long getSinceMs()
	{
		return sinceMs;
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
