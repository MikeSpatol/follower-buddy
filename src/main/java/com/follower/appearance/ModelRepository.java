package com.follower.appearance;

import com.google.gson.Gson;
// public:cut devdump
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
// public:end devdump
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.kit.KitType;

/**
 * Holds the item -> worn model mapping produced by tools/cache-dumper.
 *
 * <p>The RuneLite API's {@code ItemComposition} only exposes {@code getInventoryModel()};
 * the worn model ids ({@code maleModel0..2} / {@code femaleModel0..2}) and body kit models
 * live in the cache definitions and are not reachable at runtime. This class loads the
 * dump so {@link AppearanceComposer} can rebuild a player model from parts.
 */
@Slf4j
@Singleton
public class ModelRepository
{
	// public:cut devdump
	/** Bumped whenever the dump format changes incompatibly. */
	public static final int SUPPORTED_VERSION = 1;

	public static final String FILE_NAME = "equipment-models.json";

	// public:end devdump
	/** An item the follower can wear, for the outfit picker. */
	public static class WearableItem
	{
		public final int id;
		public final String name;

		WearableItem(int id, String name)
		{
			this.id = id;
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	public static class Entry extends Recolours
	{
		/** Item name; present in dumps produced by the current dumper. */
		public String n;
		/** Kits only: KitDefinition.bodyPartId, encoding both body part and gender. */
		public Integer bp;
		/** Vertical offsets the client applies to a worn model before merging. */
		public Integer mo;
		public Integer fo;

		/**
		 * Equipment slots this item occupies. wp1 is where it goes; wp2 and wp3 are
		 * further slots it HIDES - a platebody hides the arms kit, a full helm hides
		 * hair and jaw. Indices match KitType.
		 */
		public Integer wp1;
		public Integer wp2;
		public Integer wp3;

		public int offset(int gender)
		{
			Integer chosen = gender == 1 ? fo : mo;
			return chosen == null ? 0 : chosen;
		}
		/** Male worn model ids, -1 for unused. */
		public int[] m;
		/** Female worn model ids, -1 for unused. */
		public int[] f;

		public int[] models(int gender)
		{
			int[] chosen = gender == 1 ? f : m;
			if (chosen == null || chosen.length == 0)
			{
				chosen = gender == 1 ? m : f;
			}
			return chosen;
		}

		/**
		 * Chathead models: items carry male/female dialogue-head variants, kits
		 * carry KitDefinition.chatheadModels. These are the separate models real
		 * dialogs animate, with their own talk-animation skeletons.
		 */
		public int[] hm;
		public int[] hf;
		public int[] ch;

		/** The dialogue-head models for this entry, or null if it has none. */
		public int[] headModels(int gender)
		{
			if (ch != null)
			{
				return ch;
			}
			int[] chosen = gender == 1 ? hf : hm;
			if (chosen == null)
			{
				chosen = gender == 1 ? hm : hf;
			}
			return chosen;
		}
	}

	// public:cut devdump
	public static class Dump
	{
		public int version;
		public String cacheRevision;
		public Map<String, Entry> items;
		public Map<String, Entry> kits;
	}

	// public:end devdump
	private final Gson gson;

	@Getter
	private volatile boolean loaded;
	private volatile boolean liveRead; // public:cut devdump

	@Getter
	private volatile String status = "not loaded";

	private volatile Map<String, Entry> items = Collections.emptyMap();
	private volatile Map<String, Entry> kits = Collections.emptyMap();

	@Inject
	public ModelRepository(Gson gson)
	{
		this.gson = gson;
	}

	// public:cut devdump
	/**
	 * Loads from {@code <dataDir>/equipment-models.json}, falling back to a bundled
	 * resource of the same name if one was shipped with the jar.
	 */
	public void load(Path dataDir)
	{
		Path file = dataDir.resolve(FILE_NAME);
		if (Files.isRegularFile(file))
		{
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
			{
				if (read(reader, file.toString()))
				{
					return;
				}
			}
			catch (IOException | JsonSyntaxException e)
			{
				log.warn("Could not read {}", file, e);
				status = "failed to read " + file.getFileName() + ": " + e.getMessage();
			}
		}
		else
		{
			status = "no " + FILE_NAME + " in " + dataDir;
		}
	}

	private boolean read(Reader reader, String source)
	{
		Dump dump = gson.fromJson(reader, Dump.class);
		if (dump == null)
		{
			status = "empty dump (" + source + ")";
			return false;
		}
		if (dump.version != SUPPORTED_VERSION)
		{
			status = "dump version " + dump.version + " but plugin needs " + SUPPORTED_VERSION;
			log.warn("{}", status);
			return false;
		}

		items = dump.items == null ? Collections.emptyMap() : dump.items;
		kits = dump.kits == null ? Collections.emptyMap() : dump.kits;
		loaded = true;
		status = items.size() + " items, " + kits.size() + " kits"
			+ (dump.cacheRevision == null ? "" : " (cache " + dump.cacheRevision + ")");
		log.info("Loaded model dump from {}: {}", source, status); // public:cut logs
		return true;
	}

	// public:end devdump
	/**
	 * Builds the catalogue from the client's own loaded cache - the no-dump
	 * path every Hub install uses. A dump file, when present, wins for the
	 * ids it has: it is the development override and the validation baseline
	 * for the live parser. No-op while the cache indexes are not available
	 * yet; callers retry.
	 */
	public void loadFromClient(net.runelite.api.Client client)
	{
		Map<String, Entry> liveItems = LiveCacheParser.parseItems(client);
		if (liveItems.isEmpty())
		{
			return;
		}
		addLive(liveItems, LiveCacheParser.parseKits(client, null));
	}

	/**
	 * The live cache's items and kits, under a dump's: the items newer than
	 * the dump are in the catalogue too, where a dump from August left them
	 * out on the development client (qa5 #75).
	 */
	void addLive(Map<String, Entry> liveItems, Map<String, Entry> liveKits)
	{
		// public:cut devdump
		boolean dumped = loaded;
		liveItems.putAll(items);
		liveKits.putAll(kits);
		// public:end devdump
		items = liveItems;
		kits = liveKits;
		loaded = true;
		// public:cut devdump
		liveRead = true;
		if (!dumped)
		{
		// public:end devdump
			status = items.size() + " items, " + kits.size() + " kits (live cache)";
		} // public:cut devdump
		log.info("Loaded model catalogue from live cache: {} items, {} kits", items.size(), kits.size()); // public:cut logs
	}

	/** Whether the client's own cache has been read in yet, under a dump or not. */
	public boolean hasReadTheLiveCache()
	{
		return liveRead; // public:cut devdump
		// public:else return loaded;
	}

	public void unload()
	{
		items = Collections.emptyMap();
		kits = Collections.emptyMap();
		loaded = false;
		liveRead = false; // public:cut devdump
		status = "not loaded";
	}

	public Entry item(int itemId)
	{
		return items.get(Integer.toString(itemId));
	}

	/** Name of a dumped item, or null if this dump predates name support. */
	public String itemName(int itemId)
	{
		Entry entry = item(itemId);
		return entry == null ? null : entry.n;
	}

	/**
	 * Every wearable item whose name matches {@code query}, sorted by name. The dump
	 * contains exactly the items that have worn models, so this is the complete set
	 * of things the follower can actually be dressed in.
	 */
	public List<WearableItem> search(String query, int limit)
	{
		String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
		List<WearableItem> matches = new ArrayList<>();

		for (Map.Entry<String, Entry> entry : items.entrySet())
		{
			String name = entry.getValue().n;
			if (name == null || name.isEmpty() || "null".equals(name))
			{
				continue;
			}
			if (!needle.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(needle))
			{
				continue;
			}

			// public:cut devdump
			try
			{
			// public:end devdump
				matches.add(new WearableItem(Integer.parseInt(entry.getKey()), name));
			// public:cut devdump
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key in the dump; skip it.
			}
			// public:end devdump

			if (matches.size() >= limit * 4)
			{
				break;
			}
		}

		matches.sort((a, b) ->
		{
			// Exact and prefix matches first, then alphabetical.
			boolean ap = a.name.toLowerCase(java.util.Locale.ROOT).startsWith(needle);
			boolean bp = b.name.toLowerCase(java.util.Locale.ROOT).startsWith(needle);
			if (ap != bp)
			{
				return ap ? -1 : 1;
			}
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
	public static final KitType[] BODY_PARTS = {
		KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS,
		KitType.HANDS, KitType.LEGS, KitType.BOOTS,
	};

	private static final int PARTS_PER_GENDER = BODY_PARTS.length;

	/** @return the bodyPartId a kit must have to belong to this slot and gender. */
	public static int bodyPartId(KitType part, int gender)
	{
		for (int i = 0; i < BODY_PARTS.length; i++)
		{
			if (BODY_PARTS[i] == part)
			{
				return i + (gender == 1 ? PARTS_PER_GENDER : 0);
			}
		}
		return -1;
	}

	/**
	 * Kit ids the cache flags non-selectable - the styles character creation
	 * never offers. Read from the live cache; empty until that lands.
	 */
	private volatile java.util.Set<Integer> nonSelectableKits = Collections.emptySet();

	public void setNonSelectableKits(java.util.Set<Integer> ids)
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
	public List<Integer> kitsFor(KitType part, int gender)
	{
		int wanted = bodyPartId(part, gender);
		List<Integer> ids = new ArrayList<>();
		if (wanted < 0)
		{
			return ids;
		}

		for (Map.Entry<String, Entry> entry : kits.entrySet())
		{
			Integer bodyPart = entry.getValue().bp;
			if (bodyPart == null || bodyPart != wanted)
			{
				continue;
			}
			// public:cut devdump
			try
			{
			// public:end devdump
				int id = Integer.parseInt(entry.getKey());
				if (!nonSelectableKits.contains(id))
				{
					ids.add(id);
				}
			// public:cut devdump
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key; skip.
			}
			// public:end devdump
		}

		ids.sort(Integer::compareTo);

		List<Integer> distinct = new ArrayList<>(ids.size());
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int id : ids)
		{
			if (seen.add(appearanceSignature(kits.get(Integer.toString(id)))))
			{
				distinct.add(id);
			}
		}
		return distinct;
	}

	/** What the kit actually looks like: identical signatures render identically. */
	private static String appearanceSignature(Entry entry)
	{
		if (entry == null)
		{
			return "null";
		}
		return java.util.Arrays.toString(entry.m)
			+ java.util.Arrays.toString(entry.ch)
			+ java.util.Arrays.toString(entry.cf)
			+ java.util.Arrays.toString(entry.cr)
			+ java.util.Arrays.toString(entry.tf)
			+ java.util.Arrays.toString(entry.tr);
	}

	/** Every wearable item id in the dump. */
	public List<Integer> allItemIds()
	{
		List<Integer> ids = new ArrayList<>(items.size());
		for (String key : items.keySet())
		{
			// public:cut devdump
			try
			{
			// public:end devdump
				ids.add(Integer.parseInt(key));
			// public:cut devdump
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key; skip.
			}
			// public:end devdump
		}
		return ids;
	}

	public boolean hasNames()
	{
		for (Entry e : items.values())
		{
			return e.n != null;
		}
		return false;
	}

	public Entry kit(int kitId)
	{
		return kits.get(Integer.toString(kitId));
	}
}
