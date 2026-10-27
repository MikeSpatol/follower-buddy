package com.follower.appearance;

import com.follower.Resources;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;

/**
 * Parses item, kit and spotanim definitions straight out of the client's own
 * loaded cache ({@link Client#getIndexConfig()}), so the plugin needs no
 * offline dumps: Hub users get the full outfit catalogue and graphic
 * repository the moment the client is running.
 *
 * <p>The opcode layouts are data, in {@code cache-opcodes.txt}: a faithful
 * port of runelite-cache's ItemLoader, KitLoader and SpotAnimLoader,
 * transcribed from the library's bytecode and validated in game by diffing
 * every entry against the offline dumper's output
 * ({@code ::follower cachecheck}). This class walks a definition by that
 * table and keeps the named fields. Output shapes mirror the dump files
 * exactly, defaults included, so the repositories cannot tell the difference.
 *
 * <p>Every definition parses independently: a malformed or future-format
 * entry is skipped with a note rather than failing the whole catalogue.
 */
@Slf4j
public final class LiveCacheParser
{
	/** Config-index archive ids (net.runelite.cache.ConfigType). */
	private static final int CONFIG_IDENTKIT = 3;
	private static final int CONFIG_ITEM = 10;
	private static final int CONFIG_SEQUENCE = 12; // public:cut dev
	private static final int CONFIG_SPOTANIM = 13;

	/**
	 * The cache's text encoding. Windows-1252 leaves five bytes unassigned,
	 * which the cache library reads as '?'.
	 */
	private static final Charset CP1252 = Charset.forName("windows-1252");

	/** Per definition type, each opcode's payload fields (null: an unknown opcode). */
	private static final Map<String, String[][]> LAYOUTS = new HashMap<>();

	static
	{
		try (BufferedReader reader = Resources.reader("cache-opcodes.txt"))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				String[] words = line.split("#", 2)[0].trim().split("\\s+");
				if (words.length < 2)
				{
					continue;
				}
				String[] fields = Arrays.copyOfRange(words, 2, words.length);
				for (String run : words[1].split(","))
				{
					String[] ends = run.split("-");
					for (int op = Integer.parseInt(ends[0]); op <= Integer.parseInt(ends[ends.length - 1]); op++)
					{
						LAYOUTS.computeIfAbsent(words[0], type -> new String[256][])[op] = fields;
					}
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private LiveCacheParser()
	{
	}

	// public:cut dev
	/**
	 * The id of every animation the cache holds.
	 *
	 * <p>Only the archive's file list is read, never the definitions: an id is
	 * either in there or it is not, which is the whole question a validity
	 * check asks. Used by {@code ::follower stanceaudit} to prove that no
	 * stance names an animation that does not exist.
	 */
	public static java.util.Set<Integer> sequenceIds(Client client)
	{
		IndexDataBase configs = client.getIndexConfig();
		java.util.Set<Integer> ids = new java.util.HashSet<>();
		if (configs == null)
		{
			return ids;
		}
		for (int id : configs.getFileIds(CONFIG_SEQUENCE))
		{
			ids.add(id);
		}
		return ids;
	}

	// public:end dev
	/** Wearable items keyed by id, matching equipment-models.json's items map. */
	public static Map<String, ModelRepository.Entry> parseItems(Client client)
	{
		return parse(client, CONFIG_ITEM, (id, data) -> decodeItem(data), "item",
			(id, e) -> log.warn("Item {} failed to parse (further failures counted silently)", id, e));
	}

	/**
	 * Body kits keyed by id, matching equipment-models.json's kits map.
	 *
	 * @param nonSelectableOut when non-null, receives the ids of kits flagged
	 * non-selectable - the styles character creation does not offer, which is
	 * how the NPC-only and unreleased extras are told apart from real player
	 * styles. Collected on the side so {@link ModelRepository.Entry} keeps the
	 * dump file's exact shape and {@code ::follower cachecheck} stays honest.
	 */
	public static Map<String, ModelRepository.Entry> parseKits(Client client, Set<Integer> nonSelectableOut)
	{
		return parse(client, CONFIG_IDENTKIT, (id, data) ->
		{
			boolean[] nonSelectable = new boolean[1];
			ModelRepository.Entry entry = decodeKit(data, nonSelectable);
			if (entry != null && nonSelectable[0] && nonSelectableOut != null)
			{
				nonSelectableOut.add(id);
			}
			return entry;
		}, null,
			(id, e) -> log.debug("Kit {} failed to parse", id, e)); // public:cut logs
			// public:else (id, e) -> { });
	}

	/** Spotanims keyed by id, matching spotanims.json. */
	public static Map<String, SpotAnimRepository.Entry> parseSpotAnims(Client client)
	{
		return parse(client, CONFIG_SPOTANIM, (id, data) -> decodeSpotAnim(data), "spotanim",
			(id, e) -> log.warn("Spotanim {} failed to parse: {}", id, e.getMessage()));
	}

	/**
	 * Every definition in one config archive, keyed by id. One that fails to
	 * parse is skipped and handed to {@code failed}: every one when
	 * {@code counted} is null, otherwise the first, with a count of them all
	 * at the end under that name.
	 */
	private static <E> Map<String, E> parse(Client client, int archive, BiFunction<Integer, byte[], E> decoder,
		String counted, BiConsumer<Integer, RuntimeException> failed)
	{
		IndexDataBase configs = client.getIndexConfig();
		Map<String, E> entries = new LinkedHashMap<>();
		if (configs == null)
		{
			return entries;
		}
		int failures = 0;
		for (int id : configs.getFileIds(archive))
		{
			byte[] data = configs.loadData(archive, id);
			if (data == null)
			{
				continue;
			}
			try
			{
				E entry = decoder.apply(id, data);
				if (entry != null)
				{
					entries.put(Integer.toString(id), entry);
				}
			}
			catch (RuntimeException e)
			{
				if (failures++ == 0 || counted == null)
				{
					failed.accept(id, e);
				}
			}
		}
		if (failures > 0 && counted != null)
		{
			log.warn("{} {} definitions failed to parse", failures, counted);
		}
		return entries;
	}

	static ModelRepository.Entry decodeItem(byte[] data)
	{
		Map<String, Object> v = decode("item", data);
		int[] male = ids(v, "male0", "male1", "male2");
		int[] female = ids(v, "female0", "female1", "female2");
		// Mirror the dumper's wearable filter: only items with a worn model.
		if (male[0] == -1 && female[0] == -1)
		{
			return null;
		}

		ModelRepository.Entry entry = new ModelRepository.Entry();
		// The cache's convention for an unnamed item is the literal string
		// "null" (the definition's constructor default), and the dumper
		// preserved it - so the live parse must too.
		entry.n = (String) v.getOrDefault("name", "null");
		entry.mo = (Integer) v.getOrDefault("maleOffset", 0);
		entry.fo = (Integer) v.getOrDefault("femaleOffset", 0);
		entry.wp1 = (Integer) v.getOrDefault("wearPos1", -1);
		entry.wp2 = (Integer) v.getOrDefault("wearPos2", -1);
		entry.wp3 = (Integer) v.getOrDefault("wearPos3", -1);
		entry.m = male;
		entry.f = female;
		entry.hm = anySet(ids(v, "maleHead0", "maleHead1"));
		entry.hf = anySet(ids(v, "femaleHead0", "femaleHead1"));
		return recoloured(entry, v);
	}

	static ModelRepository.Entry decodeKit(byte[] data, boolean[] nonSelectableOut)
	{
		Map<String, Object> v = decode("kit", data);
		nonSelectableOut[0] |= v.containsKey("nonSelectable");
		int[] models = (int[]) v.get("models");
		// Mirror the dumper's filter: kits without models are unusable.
		if (models == null || models.length == 0)
		{
			return null;
		}

		ModelRepository.Entry entry = new ModelRepository.Entry();
		entry.bp = (Integer) v.get("bodyPart");
		entry.m = models;
		entry.f = models;
		entry.ch = anySet(ids(v, "head0", "head1", "head2", "head3", "head4"));
		return recoloured(entry, v);
	}

	private static SpotAnimRepository.Entry decodeSpotAnim(byte[] data)
	{
		Map<String, Object> v = decode("spotanim", data);
		SpotAnimRepository.Entry entry = new SpotAnimRepository.Entry();
		entry.m = (Integer) v.getOrDefault("model", 0);
		// Mirror the dumper's filter: a spotanim without a model shows nothing.
		if (entry.m <= 0)
		{
			return null;
		}
		entry.a = (Integer) v.getOrDefault("animation", -1);
		entry.rx = unlessDefault(v, "resizeX", 128);
		entry.ry = unlessDefault(v, "resizeY", 128);
		entry.rot = unlessDefault(v, "rotation", 0);
		entry.am = unlessDefault(v, "ambient", 0);
		entry.co = unlessDefault(v, "contrast", 0);
		return recoloured(entry, v);
	}

	/**
	 * Walks one definition by its type's layouts; the named fields come back
	 * by name. EVERY opcode is consumed at its exact width, or the stream
	 * would desynchronise and the rest parse as garbage.
	 */
	private static Map<String, Object> decode(String type, byte[] data)
	{
		String[][] layouts = LAYOUTS.get(type);
		ByteBuffer in = ByteBuffer.wrap(data);
		Map<String, Object> values = new HashMap<>();
		for (int opcode = in.get() & 0xFF; opcode != 0; opcode = in.get() & 0xFF)
		{
			if (layouts[opcode] == null)
			{
				throw new IllegalStateException("unknown " + type + " opcode " + opcode);
			}
			for (String field : layouts[opcode])
			{
				Object value = read(in, field.charAt(0));
				if (field.length() > 2)
				{
					values.put(field.substring(2), value);
				}
			}
		}
		return values;
	}

	/** One payload field; cache-opcodes.txt lists the kinds. */
	private static Object read(ByteBuffer in, char kind)
	{
		switch (kind)
		{
			case 'b':
				return (int) in.get();
			case 'B':
				return in.get() & 0xFF;
			case 'h':
				return in.getShort() & 0xFFFF;
			case 'i':
				return in.getInt();
			case 's':
				int start = in.position();
				while (in.get() != 0)
				{
					// to the terminator
				}
				return new String(in.array(), start, in.position() - start - 1, CP1252).replace('\uFFFD', '?');
			case 'f':
				return true;
			case 'c':
				short[][] pairs = new short[2][in.get() & 0xFF];
				for (int i = 0; i < pairs[0].length; i++)
				{
					pairs[0][i] = in.getShort();
					pairs[1][i] = in.getShort();
				}
				return pairs;
			case 'H':
			case 'I':
			case 'L':
				int[] list = new int[kind == 'L' ? in.getShort() & 0xFFFF : in.get() & 0xFF];
				for (int i = 0; i < list.length; i++)
				{
					list[i] = kind == 'I' ? in.getInt() : in.getShort() & 0xFFFF;
				}
				return list;
			case 'o':
				in.get();
				while (in.get() != 0)
				{
					read(in, 's');
				}
				return null;
			case 'p':
				for (int count = in.get() & 0xFF; count > 0; count--)
				{
					boolean isString = in.get() == 1;
					in.position(in.position() + 3);
					read(in, isString ? 's' : 'i');
				}
				return null;
			default:
				throw new IllegalStateException("unknown field kind " + kind);
		}
	}

	/** The named ids, -1 for any the definition did not set. */
	private static int[] ids(Map<String, Object> values, String... names)
	{
		int[] ids = new int[names.length];
		for (int i = 0; i < names.length; i++)
		{
			ids[i] = (Integer) values.getOrDefault(names[i], -1);
		}
		return ids;
	}

	/** The dump leaves out an id array that is all -1 rather than writing it. */
	private static int[] anySet(int[] ids)
	{
		return Arrays.stream(ids).allMatch(id -> id == -1) ? null : ids;
	}

	/** The definition's find/replace pairs onto its entry; null where it has none. */
	private static <E extends Recolours> E recoloured(E entry, Map<String, Object> values)
	{
		short[][] colours = (short[][]) values.getOrDefault("colours", new short[2][]);
		short[][] textures = (short[][]) values.getOrDefault("textures", new short[2][]);
		entry.cf = colours[0];
		entry.cr = colours[1];
		entry.tf = textures[0];
		entry.tr = textures[1];
		return entry;
	}

	/** The dump leaves out a value at its default. */
	private static Integer unlessDefault(Map<String, Object> values, String name, int defaultValue)
	{
		Integer value = (Integer) values.get(name);
		return value == null || value == defaultValue ? null : value;
	}
}
