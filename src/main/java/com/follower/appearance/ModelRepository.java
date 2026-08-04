package com.follower.appearance;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
	/** Bumped whenever the dump format changes incompatibly. */
	public static final int SUPPORTED_VERSION = 1;

	public static final String FILE_NAME = "equipment-models.json";

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

	public static class Entry
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
		/** Colours to find / replace with. */
		public short[] cf;
		public short[] cr;
		/** Textures to find / replace with. */
		public short[] tf;
		public short[] tr;

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

	public static class Dump
	{
		public int version;
		public String cacheRevision;
		public Map<String, Entry> items;
		public Map<String, Entry> kits;
	}

	private final Gson gson;

	@Getter
	private volatile boolean loaded;

	@Getter
	private volatile String status = "not loaded";

	private volatile Map<String, Entry> items = Collections.emptyMap();
	private volatile Map<String, Entry> kits = Collections.emptyMap();

	@Inject
	public ModelRepository(Gson gson)
	{
		this.gson = gson;
	}

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

		try (InputStream in = ModelRepository.class.getResourceAsStream("/com/follower/" + FILE_NAME))
		{
			if (in != null)
			{
				read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), "bundled resource");
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Could not read bundled model dump", e);
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
		log.info("Loaded model dump from {}: {}", source, status);
		return true;
	}

	public void unload()
	{
		items = Collections.emptyMap();
		kits = Collections.emptyMap();
		loaded = false;
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

			try
			{
				matches.add(new WearableItem(Integer.parseInt(entry.getKey()), name));
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key in the dump; skip it.
			}

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
	 * Body parts in bodyPartId order. The id is this index for a male kit, and this
	 * index + 7 for a female one - verified against the whole dump, which is
	 * symmetric across the two halves.
	 */
	private static final KitType[] BODY_PART_ORDER = {
		KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS,
		KitType.HANDS, KitType.LEGS, KitType.BOOTS,
	};

	private static final int PARTS_PER_GENDER = BODY_PART_ORDER.length;

	/** @return the bodyPartId a kit must have to belong to this slot and gender. */
	public static int bodyPartId(KitType part, int gender)
	{
		for (int i = 0; i < BODY_PART_ORDER.length; i++)
		{
			if (BODY_PART_ORDER[i] == part)
			{
				return i + (gender == 1 ? PARTS_PER_GENDER : 0);
			}
		}
		return -1;
	}

	/**
	 * Every kit valid for one body part and gender, sorted by id. This is what stops
	 * the picker offering a beard for the boots slot, or female hair on a male body.
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
			try
			{
				ids.add(Integer.parseInt(entry.getKey()));
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key; skip.
			}
		}

		ids.sort(Integer::compareTo);
		return ids;
	}

	/** True if the dump carries body-part metadata (older dumps do not). */
	public boolean hasKitParts()
	{
		for (Entry e : kits.values())
		{
			return e.bp != null;
		}
		return false;
	}

	/** Every wearable item id in the dump. */
	public List<Integer> allItemIds()
	{
		List<Integer> ids = new ArrayList<>(items.size());
		for (String key : items.keySet())
		{
			try
			{
				ids.add(Integer.parseInt(key));
			}
			catch (NumberFormatException ignored)
			{
				// Malformed key; skip.
			}
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

	public boolean hasItem(int itemId)
	{
		return items.containsKey(Integer.toString(itemId));
	}
}
