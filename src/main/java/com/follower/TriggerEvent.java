package com.follower;

import java.util.*;
import java.util.Map;
import lombok.*;
import java.util.Set;
import static com.follower.TriggerEvent.Type.*;

/**
 * One thing that happened, handed to {@link SpeechEngine}. A synthetic
 * {@link Type#TICK} event is raised every game tick so that purely state-based
 * rules (low HP, low prayer) get a chance to fire.
 */
@RequiredArgsConstructor
@Getter
class TriggerEvent
{
	enum Type
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
		/** A Send with a misgiving in it: the follower stopped at the edge first. */
		HESITATED,
		/** The player walked straight at the parked follower and it stepped aside. */
		STEPPED_ASIDE,
		/** The follower did an agility obstacle after the player. */
		CROSSED,
	}

	/**
	 * An XP drop landed. {skill} names it; {@code count} is how many the
	 * current work session has seen, this one included, so a rule can fire
	 * on every eighth the way npcKill fires on every fiftieth. Zero outside
	 * a session.
	 */
	static TriggerEvent xpDrop(String skill, int count)
	{
		var event = named(XP_DROP, "skill", skill).put("count", count);
		event.count = count;
		return event;
	}

	/**
	 * A work session ended. The name is the activity, for {@code names}
	 * lists; {activity} and {target} say what it was and on what.
	 */
	static TriggerEvent workEnd(String activity, String target)
	{
		return named(WORK_END, "activity", activity).put("target", target == null ? "" : target);
	}

	/** The thing being worked changed id or went. {target} names it. */
	static TriggerEvent targetDepleted(String target)
	{
		return named(TARGET_DEPLETED, "target", target);
	}

	/**
	 * The PLAYER examined something - the reverse direction of
	 * {@link Type#EXAMINED}, which is somebody examining the follower.
	 * {target} names what was looked at.
	 */
	static TriggerEvent examinedThing(String target)
	{
		return named(EXAMINED_THING, "target", target);
	}

	/**
	 * As {@link #examinedThing}, for a creature, or anything else standing in
	 * the scene: {target} is a common noun after "the" or "this" - the guard,
	 * this statue (text QA5: "this Statue", "One more Crate").
	 */
	static TriggerEvent examinedCreature(String target)
	{
		var event = examinedThing(target);
		event.creatures.add("target");
		return event;
	}

	/**
	 * The game's music changed to another track - the local song, as against
	 * {@link Type#MUSIC_UNLOCK}, which is hearing one for the first time ever.
	 */
	static TriggerEvent trackChange(String track)
	{
		return named(TRACK_CHANGE, "track", track);
	}

	/** A world hop landed. {world} is the number on the new door. */
	static TriggerEvent worldHop(int world)
	{
		var event = new TriggerEvent(WORLD_HOP).put("world", world);
		event.value = world;
		return event;
	}

	/** A music track just unlocked. {track} names it for the catalogue. */
	static TriggerEvent musicUnlock(String track)
	{
		return named(MUSIC_UNLOCK, "track", track);
	}

	/** The other ledger gained a line. {item} is what went in it. */
	static TriggerEvent collectionAdd(String item)
	{
		return named(COLLECTION_ADD, "item", item);
	}

	/** A quest reward scroll was handed over. {quest} names the quest. */
	static TriggerEvent questComplete(String quest)
	{
		return named(QUEST_COMPLETE, "quest", quest);
	}

	/**
	 * A Grand Exchange offer finished. The name carries "bought" or "sold"
	 * for the condition's {@code is}; {item} and {value} land as placeholders
	 * so the line can read like a clerk's telegram rather than a receipt.
	 */
	static TriggerEvent geOffer(boolean bought, String item, long spent)
	{
		var event = new TriggerEvent(GE_OFFER).put("item", item == null ? "" : item)
			.put("value", formatGp(spent));
		event.name = bought ? "bought" : "sold";
		return event;
	}

	/** Somebody arrived in or left the player's chat channel. {name} says who. */
	static TriggerEvent channel(Type type, String who)
	{
		return named(type, "name", who);
	}

	/**
	 * A named NPC said something overhead. The name matches like npcSpawn's,
	 * the message like chatMessage's, and both land as placeholders - NPC
	 * overheads never reach the chatbox, so no chat rule can see these.
	 */
	static TriggerEvent npcShout(String npc, String message)
	{
		return named(NPC_SHOUT, "npc", npc).message("message", message);
	}

	/**
	 * The player answered a question, by picking a branch in the conversation
	 * the follower opened. {@code yes} or {@code no}, and never anything else -
	 * an option cannot be misheard the way a typed word could.
	 */
	static TriggerEvent answered(String answer)
	{
		return answered(answer, "");
	}

	/** The same, naming the tree that was answered, for a rule's {@code of}. */
	static TriggerEvent answered(String answer, String tree)
	{
		var event = named(ANSWERED, "answer", answer);
		event.message = tree == null ? "" : tree;
		return event;
	}

	/** A want got its answer, one way or the other. {want} names what it was. */
	static TriggerEvent want(Type type, String label)
	{
		return named(type, "want", label);
	}

	/**
	 * A personal best was just beaten. {record} names it, {value} is the new
	 * mark and {previous} the one it replaced - which is what makes the line
	 * land, since a record without the old number is just a number.
	 */
	static TriggerEvent record(String what, int value, int previous)
	{
		var event = named(RECORD, "record", what).put("value", value).put("previous", previous);
		event.value = value;
		return event;
	}

	/** The player has died. Where it happened lives in the context, not here. */
	static TriggerEvent death()
	{
		return new TriggerEvent(PLAYER_DEATH);
	}

	/**
	 * Loot arrived. {@code value} carries the total for {@code lootWorth}
	 * conditions; {item} and {value} land as placeholders so a line can name
	 * the prize without quoting a raw number.
	 */
	static TriggerEvent loot(int totalValue, String bestItem)
	{
		var event = named(LOOT, "item", bestItem).put("value", formatGp(totalValue));
		event.value = totalValue;
		return event;
	}

	/** 1.5B / 1.2M / 3M / 214K / 950 - the way a player says an amount, not a ledger. */
	static String formatGp(long value)
	{
		if (value >= 999_950_000L)
			return tenths(value, 1_000_000_000L) + "B";
		if (value >= 1_000_000)
			return tenths(value, 1_000_000) + "M";
		if (value >= 10_000)
			return (value / 1_000) + "K";
		return Long.toString(value);
	}

	/** The amount in units to one decimal place, a round one without its ".0": 1.5, 3. */
	static String tenths(long value, long unit)
	{
		long tenths = Math.round(value * 10.0 / unit);
		return tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
	}

	/**
	 * A fight starting or ending, with what is being fought in {npc} so a line
	 * can name it.
	 */
	static TriggerEvent combat(Type type, String npcName)
	{
		var event = named(type, "npc", npcName);
		event.creatures.add("npc");
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
	static TriggerEvent kill(int npcId, String npcName, int combatLevel)
	{
		return kill(npcId, npcName, combatLevel, 0);
	}

	/**
	 * @param count how many of this NPC have been killed this session, which
	 * rides along so a rule can fire on the fiftieth and say which one it was
	 */
	static TriggerEvent kill(int npcId, String npcName, int combatLevel, int count)
	{
		var event = npc(NPC_KILL, npcId, npcName).put("level", combatLevel).put("count", count);
		event.value = combatLevel;
		event.count = count;
		return event;
	}

	/** Thrall-mode transitions; {@code style} lands in the {style} placeholder. */
	static TriggerEvent thrall(Type type, String style)
	{
		return named(type, "style", style);
	}

	/**
	 * A resummon while already possessed: {@code style} is the new thrall
	 * type, {@code from} the one being left behind.
	 */
	static TriggerEvent thrallSwitch(String from, String style)
	{
		return thrall(THRALL_SWITCH, style).put("from", from == null ? "" : from);
	}

	/** Errand transitions; the errand's name matches rule {@code names} lists. */
	static TriggerEvent errand(Type type, String errand)
	{
		return named(type, "errand", errand);
	}

	final Type type;
	final Map<String, String> placeholders = new HashMap<>();

	/** The placeholders that hold a creature's name, which reads lower case after an article. */
	final Set<String> creatures = new java.util.HashSet<>();

	/** Generic payload; meaning depends on {@link #type}. */
	String name = "";
	int id = -1,
		value;

	/** How many times this has happened before, where the raiser counts. */
	int count;
	/** The level-up's wider ledger: the total and the base, before and after. */
	int total,
		previousTotal,
		base,
		previousBase;
	/** Milliseconds since the last level in this skill, or -1 for none known. */
	long sinceMs = -1;
	String message = "";
	int chatTypeId = -1;

	/** An event about one thing by name, said by the placeholder {@code key}. */
	static TriggerEvent named(Type type, String key, String name)
	{
		var event = new TriggerEvent(type);
		event.name = name == null ? "" : name;
		event.placeholders.put(key, event.name);
		return event;
	}

	/** Sets the message, said by the placeholder {@code key}. */
	TriggerEvent message(String key, String text)
	{
		message = text == null ? "" : text;
		placeholders.put(key, message);
		return this;
	}

	/** Adds a placeholder; a number is written in digits. */
	TriggerEvent put(String key, Object value)
	{
		placeholders.put(key, String.valueOf(value));
		return this;
	}

	static TriggerEvent tick()
	{
		return new TriggerEvent(TICK);
	}

	static TriggerEvent npc(Type type, int npcId, String npcName)
	{
		var event = combat(type, npcName).put("npcId", npcId);
		event.id = npcId;
		return event;
	}

	/**
	 * Something was said. The sender rides along in {@link #getName()} so a rule
	 * can tell the player's own line from the rest of the street - which is the
	 * difference between a follower that answers you and one that answers
	 * everybody.
	 */
	static TriggerEvent chat(String message, int chatTypeId, String sender)
	{
		var event = named(CHAT, "speaker", sender).message("message", message);
		event.chatTypeId = chatTypeId;
		return event;
	}

	static TriggerEvent varbit(int varbitId, int newValue, int oldValue)
	{
		var event = new TriggerEvent(VARBIT).put("value", newValue);
		event.id = varbitId;
		event.value = newValue;
		return event;
	}

	static TriggerEvent animation(int animationId)
	{
		var event = new TriggerEvent(ANIMATION);
		event.id = animationId;
		return event;
	}

	static TriggerEvent levelUp(String skill, int level)
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
	static TriggerEvent levelUp(String skill, int level, int total, int previousTotal,
		int base, int previousBase, int masteries, long sinceLastMs, long sinceAnyMs)
	{
		var event = named(LEVEL_UP, "skill", skill).put("level", level).put("total", total)
			.put("base", base).put("nines", Words.number(masteries)).put("ninesOrdinal", Words.ordinal(masteries))
			.put("sinceLast", sinceLastMs < 0 ? "" : Words.duration(sinceLastMs))
			.put("sinceAny", sinceAnyMs < 0 ? "" : Words.duration(sinceAnyMs));
		event.value = level;
		event.total = total;
		event.previousTotal = previousTotal;
		event.base = base;
		event.previousBase = previousBase;
		event.count = masteries;
		event.sinceMs = sinceLastMs;
		return event;
	}

	/**
	 * The follower noticed something the player had not, and set off to look
	 * at it. {kind} is what sort of thing (valuable, nest, impling, star,
	 * landmark) and {thing} names it.
	 */
	static TriggerEvent noticed(String kind, String thing)
	{
		var event = named(NOTICED, "kind", kind).message("thing", thing);
		// A landmark's name is a common noun after "this": text QA5, 17 Oct,
		// "the wording of this Statue". An item keeps the game's spelling.
		if ("landmark".equals(event.name))
			event.creatures.add("thing");
		return event;
	}

	/**
	 * Sent somewhere with a misgiving about it. {misgiving} is the kind
	 * (wilderness, monster) and {thing} what it is about: "the Wilderness",
	 * or the monster's name.
	 */
	static TriggerEvent hesitated(String misgiving, String thing)
	{
		var event = named(HESITATED, "misgiving", misgiving).message("thing", thing);
		if ("monster".equals(event.name))
			event.creatures.add("thing");
		return event;
	}

	/**
	 * The game said what the kill count is. {boss} names it, {count} is
	 * the number, {ordinal} says it ("forty-first"), and {lastCount} is the
	 * highest count the follower had seen before - zero for a boss it has
	 * never heard the number for.
	 */
	static TriggerEvent killCount(String boss, int count, int lastCount)
	{
		var event = named(KILL_COUNT, "boss", boss).put("count", count)
			.put("ordinal", Words.ordinal(count)).put("lastCount", lastCount);
		event.count = count;
		event.value = count;
		return event;
	}

	static TriggerEvent regionChange(int newRegion, int oldRegion)
	{
		var event = new TriggerEvent(REGION_CHANGE).put("region", newRegion);
		event.id = newRegion;
		event.value = newRegion;
		return event;
	}

	/**
	 * The follower has done an agility obstacle or shortcut after the player.
	 * The name is "fell" where the player fell, and so the follower with
	 * them, and "crossed" otherwise.
	 */
	static TriggerEvent crossed(boolean fell)
	{
		var event = new TriggerEvent(CROSSED);
		event.name = fell ? "fell" : "crossed";
		return event;
	}


	static TriggerEvent damageTaken(int amount)
	{
		var event = new TriggerEvent(DAMAGE_TAKEN).put("damage", amount);
		event.value = amount;
		return event;
	}

	static TriggerEvent simple(Type type)
	{
		return new TriggerEvent(type);
	}

	Set<String> getCreatureKeys()
	{
		return creatures;
	}
}
