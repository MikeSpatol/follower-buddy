package com.follower;

import com.google.gson.Gson;
import java.util.*;
import java.util.List;
import java.util.Map;
import javax.inject.*;
import lombok.*;
import net.runelite.api.kit.KitType;
import java.util.Set;

/**
 * Holds the item -> worn model mapping produced by tools/cache-dumper.
 *
 * <p>The RuneLite API's {@code ItemComposition} only exposes {@code getInventoryModel()};
 * the worn model ids ({@code maleModel0..2} / {@code femaleModel0..2}) and body kit models
 * live in the cache definitions and are not reachable at runtime. This class loads the
 * dump so {@link AppearanceComposer} can rebuild a player model from parts.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ModelRepository
{
	/** An item the follower can wear, for the outfit picker. */
	@RequiredArgsConstructor
	static class WearableItem
	{
		final int id;
		final String name;

		@Override
		public String toString()
		{
			return name;
		}
	}

	static class Entry extends Recolours
	{
		/** Item name; present in dumps produced by the current dumper. */
		String n;
		/** Kits only: KitDefinition.bodyPartId, encoding both body part and gender. */
		Integer bp;
		/** Vertical offsets the client applies to a worn model before merging. */
		Integer mo,
			fo;

		/**
		 * Equipment slots this item occupies. wp1 is where it goes; wp2 and wp3 are
		 * further slots it HIDES - a platebody hides the arms kit, a full helm hides
		 * hair and jaw. Indices match KitType.
		 */
		Integer wp1,
			wp2,
			wp3;

		int offset(int gender)
		{
			var chosen = gender == 1 ? fo : mo;
			return chosen == null ? 0 : chosen;
		}
		/** Male worn model ids, -1 for unused. */
		int[] m;
		/** Female worn model ids, -1 for unused. */
		int[] f;

		int[] models(int gender)
		{
			int[] chosen = gender == 1 ? f : m;
			if (chosen == null || chosen.length == 0)
				chosen = gender == 1 ? m : f;
			return chosen;
		}

		/**
		 * Chathead models: items carry male/female dialogue-head variants, kits
		 * carry KitDefinition.chatheadModels. These are the separate models real
		 * dialogs animate, with their own talk-animation skeletons.
		 */
		int[] hm,
			hf,
			ch;

		/** The dialogue-head models for this entry, or null if it has none. */
		int[] headModels(int gender)
		{
			if (ch != null)
				return ch;
			int[] chosen = gender == 1 ? hf : hm;
			if (chosen == null)
				chosen = gender == 1 ? hm : hf;
			return chosen;
		}
	}

	final Gson gson;

	@Getter
	volatile boolean loaded;

	@Getter
	volatile String status = "not loaded";

	volatile Map<String, Entry> items = Collections.emptyMap(),
		kits = Collections.emptyMap();

	/**
	 * Builds the catalogue from the client's own loaded cache - the no-dump
	 * path every Hub install uses. A dump file, when present, wins for the
	 * ids it has: it is the development override and the validation baseline
	 * for the live parser. No-op while the cache indexes are not available
	 * yet; callers retry.
	 */
	void loadFromClient(net.runelite.api.Client client)
	{
		var liveItems = LiveCacheParser.parseItems(client);
		if (liveItems.isEmpty())
			return;
		addLive(liveItems, LiveCacheParser.parseKits(client, null));
	}

	/**
	 * The live cache's items and kits, under a dump's: the items newer than
	 * the dump are in the catalogue too, where a dump from August left them
	 * out on the development client (qa5 #75).
	 */
	void addLive(Map<String, Entry> liveItems, Map<String, Entry> liveKits)
	{
		items = liveItems;
		kits = liveKits;
		loaded = true;
			status = items.size() + " items, " + kits.size() + " kits (live cache)";
	}

	/** Whether the client's own cache has been read in yet, under a dump or not. */
	boolean hasReadTheLiveCache()
	{
		return loaded;
	}

	void unload()
	{
		items = Collections.emptyMap();
		kits = Collections.emptyMap();
		loaded = false;
		status = "not loaded";
	}

	Entry item(int itemId)
	{
		return items.get(Integer.toString(itemId));
	}

	/** Name of a dumped item, or null if this dump predates name support. */
	String itemName(int itemId)
	{
		var entry = item(itemId);
		return entry == null ? null : entry.n;
	}

	/**
	 * Every wearable item whose name matches {@code query}, sorted by name. The dump
	 * contains exactly the items that have worn models, so this is the complete set
	 * of things the follower can actually be dressed in.
	 */
	List<WearableItem> search(String query, int limit)
	{
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<WearableItem> matches = new ArrayList<>();

		for (var entry : items.entrySet())
		{
			String name = entry.getValue().n;
			if (name == null || name.isEmpty() || "null".equals(name))
				continue;
			if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle))
				continue;

				matches.add(new WearableItem(Integer.parseInt(entry.getKey()), name));

			if (matches.size() >= limit * 4)
				break;
		}

		matches.sort((a, b) ->
		{
			// Exact and prefix matches first, then alphabetical.
			boolean ap = a.name.toLowerCase(Locale.ROOT).startsWith(needle),
				bp = b.name.toLowerCase(Locale.ROOT).startsWith(needle);
			if (ap != bp)
				return ap ? -1 : 1;
			return a.name.compareToIgnoreCase(b.name);
		});

		return matches.size() > limit ? matches.subList(0, limit) : matches;
	}

	/**
	 * The body kits, the parts whose kit ids are gender-specific, in bodyPartId
	 * order. The id is this index for a male kit, and this index + 7 for a
	 * female one - verified against the whole dump, which is symmetric across
	 * the two halves. The side panel edits them in this order too.
	 */
	static final KitType[] BODY_PARTS = {
		KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS,
		KitType.HANDS, KitType.LEGS, KitType.BOOTS,
	};

	static final int PARTS_PER_GENDER = BODY_PARTS.length;

	/** @return the bodyPartId a kit must have to belong to this slot and gender. */
	static int bodyPartId(KitType part, int gender)
	{
		for (int i = 0; i < BODY_PARTS.length; i++)
		{
			if (BODY_PARTS[i] == part)
				return i + (gender == 1 ? PARTS_PER_GENDER : 0);
		}
		return -1;
	}

	/**
	 * Kit ids the cache flags non-selectable - the styles character creation
	 * never offers. Read from the live cache; empty until that lands.
	 */
	volatile Set<Integer> nonSelectableKits = Collections.emptySet();

	void setNonSelectableKits(Set<Integer> ids)
	{
		nonSelectableKits = ids == null ? Collections.emptySet() : ids;
	}

	/**
	 * Every kit the picker should offer for one body part and gender, sorted
	 * by id. Two filters make this the list a player recognises rather than a
	 * raw cache listing:
	 *
	 * <ul>
	 *   <li>kits flagged non-selectable are dropped - those are the NPC-only
	 *   and unreleased styles character creation hides;</li>
	 *   <li>kits whose models and chatheads are byte-identical to an earlier
	 *   one are dropped - the cache holds several such pairs (33/35 hands,
	 *   43/44 boots, 141/142/152 female hair, ...) which rendered as
	 *   duplicate entries that looked and behaved identically.</li>
	 * </ul>
	 */
	List<Integer> kitsFor(KitType part, int gender)
	{
		int wanted = bodyPartId(part, gender);
		List<Integer> ids = new ArrayList<>();
		if (wanted < 0)
			return ids;

		for (var entry : kits.entrySet())
		{
			var bodyPart = entry.getValue().bp;
			if (bodyPart == null || bodyPart != wanted)
				continue;
				int id = Integer.parseInt(entry.getKey());
				if (!nonSelectableKits.contains(id))
					ids.add(id);
		}

		ids.sort(Integer::compareTo);

		List<Integer> distinct = new ArrayList<>(ids.size());
		Set<String> seen = new java.util.HashSet<>();
		for (int id : ids)
		{
			if (seen.add(appearanceSignature(kits.get(Integer.toString(id)))))
				distinct.add(id);
		}
		return distinct;
	}

	/** What the kit actually looks like: identical signatures render identically. */
	static String appearanceSignature(Entry entry)
	{
		if (entry == null)
			return "null";
		return Arrays.toString(entry.m)
			+ Arrays.toString(entry.ch)
			+ Arrays.toString(entry.cf)
			+ Arrays.toString(entry.cr)
			+ Arrays.toString(entry.tf)
			+ Arrays.toString(entry.tr);
	}

	/** Every wearable item id in the dump. */
	List<Integer> allItemIds()
	{
		List<Integer> ids = new ArrayList<>(items.size());
		for (String key : items.keySet())
				ids.add(Integer.parseInt(key));
		return ids;
	}

	boolean hasNames()
	{
		for (var e : items.values())
			return e.n != null;
		return false;
	}

	Entry kit(int kitId)
	{
		return kits.get(Integer.toString(kitId));
	}
}
