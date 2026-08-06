package com.follower.appearance;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;

/**
 * Parses item, kit and spotanim definitions straight out of the client's own
 * loaded cache ({@link Client#getIndexConfig()}), so the plugin needs no
 * offline dumps: Hub users get the full outfit catalogue and graphic
 * repository the moment the client is running.
 *
 * <p>The opcode logic is a faithful port of runelite-cache's ItemLoader,
 * KitLoader and SpotAnimLoader, transcribed from the library's bytecode and
 * validated in game by diffing every entry against the offline dumper's
 * output ({@code ::follower cachecheck}). Output shapes mirror the dump files
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
	private static final int CONFIG_SPOTANIM = 13;

	private LiveCacheParser()
	{
	}

	/** Wearable items keyed by id, matching equipment-models.json's items map. */
	public static Map<String, ModelRepository.Entry> parseItems(Client client)
	{
		IndexDataBase configs = client.getIndexConfig();
		Map<String, ModelRepository.Entry> items = new LinkedHashMap<>();
		if (configs == null)
		{
			return items;
		}
		int failures = 0;
		for (int id : configs.getFileIds(CONFIG_ITEM))
		{
			byte[] data = configs.loadData(CONFIG_ITEM, id);
			if (data == null)
			{
				continue;
			}
			try
			{
				ModelRepository.Entry entry = decodeItem(data);
				if (entry != null)
				{
					items.put(Integer.toString(id), entry);
				}
			}
			catch (RuntimeException e)
			{
				if (failures++ == 0)
				{
					log.warn("Item {} failed to parse (further failures counted silently)", id, e);
				}
			}
		}
		if (failures > 0)
		{
			log.warn("{} item definitions failed to parse", failures);
		}
		return items;
	}

	/** Body kits keyed by id, matching equipment-models.json's kits map. */
	public static Map<String, ModelRepository.Entry> parseKits(Client client)
	{
		return parseKits(client, null);
	}

	/**
	 * @param nonSelectableOut when non-null, receives the ids of kits flagged
	 * non-selectable - the styles character creation does not offer, which is
	 * how the NPC-only and unreleased extras are told apart from real player
	 * styles. Collected on the side so {@link ModelRepository.Entry} keeps the
	 * dump file's exact shape and {@code ::follower cachecheck} stays honest.
	 */
	public static Map<String, ModelRepository.Entry> parseKits(Client client,
		java.util.Set<Integer> nonSelectableOut)
	{
		IndexDataBase configs = client.getIndexConfig();
		Map<String, ModelRepository.Entry> kits = new LinkedHashMap<>();
		if (configs == null)
		{
			return kits;
		}
		for (int id : configs.getFileIds(CONFIG_IDENTKIT))
		{
			byte[] data = configs.loadData(CONFIG_IDENTKIT, id);
			if (data == null)
			{
				continue;
			}
			try
			{
				boolean[] nonSelectable = new boolean[1];
				ModelRepository.Entry entry = decodeKit(data, nonSelectable);
				if (entry != null)
				{
					kits.put(Integer.toString(id), entry);
					if (nonSelectable[0] && nonSelectableOut != null)
					{
						nonSelectableOut.add(id);
					}
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Kit {} failed to parse", id, e);
			}
		}
		return kits;
	}

	/** Spotanims keyed by id, matching spotanims.json. */
	public static Map<String, SpotAnimRepository.Entry> parseSpotAnims(Client client)
	{
		IndexDataBase configs = client.getIndexConfig();
		Map<String, SpotAnimRepository.Entry> spotAnims = new LinkedHashMap<>();
		if (configs == null)
		{
			return spotAnims;
		}
		int failures = 0;
		for (int id : configs.getFileIds(CONFIG_SPOTANIM))
		{
			byte[] data = configs.loadData(CONFIG_SPOTANIM, id);
			if (data == null)
			{
				continue;
			}
			try
			{
				SpotAnimRepository.Entry entry = decodeSpotAnim(data);
				if (entry != null)
				{
					spotAnims.put(Integer.toString(id), entry);
				}
			}
			catch (RuntimeException e)
			{
				if (failures++ == 0)
				{
					log.warn("Spotanim {} failed to parse: {}", id, e.getMessage());
				}
			}
		}
		if (failures > 0)
		{
			log.warn("{} spotanim definitions failed to parse", failures);
		}
		return spotAnims;
	}

	// ------------------------------------------------------------------ items

	/**
	 * ItemLoader's opcode walk. Only the fields the follower needs are kept,
	 * but EVERY opcode must be consumed at its exact width or the stream
	 * desynchronises and the rest of the definition parses as garbage.
	 */
	private static ModelRepository.Entry decodeItem(byte[] data)
	{
		Buffer in = new Buffer(data);
		// The cache's convention for an unnamed item is the literal string
		// "null" (the definition's constructor default), and the dumper
		// preserved it - so the live parse must too.
		String name = "null";
		int maleModel0 = -1;
		int maleModel1 = -1;
		int maleModel2 = -1;
		int femaleModel0 = -1;
		int femaleModel1 = -1;
		int femaleModel2 = -1;
		int maleOffset = 0;
		int femaleOffset = 0;
		int maleHead = -1;
		int maleHead2 = -1;
		int femaleHead = -1;
		int femaleHead2 = -1;
		int wearPos1 = -1;
		int wearPos2 = -1;
		int wearPos3 = -1;
		short[] colorFind = null;
		short[] colorReplace = null;
		short[] textureFind = null;
		short[] textureReplace = null;

		for (int opcode = in.readUnsignedByte(); opcode != 0; opcode = in.readUnsignedByte())
		{
			switch (opcode)
			{
				case 1:
					in.readUnsignedShort(); // inventory model
					break;
				case 2:
					name = in.readString();
					break;
				case 3:
				case 9:
					in.readString(); // examine / unknown1
					break;
				case 4:
				case 5:
				case 6:
				case 7:
				case 8:
					in.readUnsignedShort(); // 2d zoom/rotation/offsets
					break;
				case 11:
				case 15:
				case 16:
				case 65:
				case 160:
					break; // flag opcodes carry no payload
				case 12:
					in.readInt(); // cost
					break;
				case 13:
					wearPos1 = in.readByte();
					break;
				case 14:
					wearPos2 = in.readByte();
					break;
				case 23:
					maleModel0 = in.readUnsignedShort();
					maleOffset = in.readUnsignedByte();
					break;
				case 24:
					maleModel1 = in.readUnsignedShort();
					break;
				case 25:
					femaleModel0 = in.readUnsignedShort();
					femaleOffset = in.readUnsignedByte();
					break;
				case 26:
					femaleModel1 = in.readUnsignedShort();
					break;
				case 27:
					wearPos3 = in.readByte();
					break;
				case 30:
				case 31:
				case 32:
				case 33:
				case 34:
				case 35:
				case 36:
				case 37:
				case 38:
				case 39:
					in.readString(); // ground / interface actions
					break;
				case 40:
				{
					int count = in.readUnsignedByte();
					colorFind = new short[count];
					colorReplace = new short[count];
					for (int i = 0; i < count; i++)
					{
						colorFind[i] = (short) in.readUnsignedShort();
						colorReplace[i] = (short) in.readUnsignedShort();
					}
					break;
				}
				case 41:
				{
					int count = in.readUnsignedByte();
					textureFind = new short[count];
					textureReplace = new short[count];
					for (int i = 0; i < count; i++)
					{
						textureFind[i] = (short) in.readUnsignedShort();
						textureReplace[i] = (short) in.readUnsignedShort();
					}
					break;
				}
				case 42:
					in.readByte(); // shift-click drop index
					break;
				case 43:
					// Menu subops: group byte, then (index byte, string) pairs
					// terminated by a zero index byte.
					in.readUnsignedByte();
					while (in.readUnsignedByte() != 0)
					{
						in.readString();
					}
					break;
				case 44:
					in.readInt(); // large inventory model
					break;
				case 45:
					maleModel0 = in.readInt();
					maleOffset = in.readUnsignedByte();
					break;
				case 46:
					maleModel1 = in.readInt();
					break;
				case 47:
					maleModel2 = in.readInt();
					break;
				case 48:
					femaleModel0 = in.readInt();
					femaleOffset = in.readUnsignedByte();
					break;
				case 49:
					femaleModel1 = in.readInt();
					break;
				case 50:
					femaleModel2 = in.readInt();
					break;
				case 51:
					maleHead = in.readInt();
					break;
				case 52:
					maleHead2 = in.readInt();
					break;
				case 53:
					femaleHead = in.readInt();
					break;
				case 54:
					femaleHead2 = in.readInt();
					break;
				case 75:
					in.readShort(); // weight
					break;
				case 78:
					maleModel2 = in.readUnsignedShort();
					break;
				case 79:
					femaleModel2 = in.readUnsignedShort();
					break;
				case 90:
					maleHead = in.readUnsignedShort();
					break;
				case 91:
					femaleHead = in.readUnsignedShort();
					break;
				case 92:
					maleHead2 = in.readUnsignedShort();
					break;
				case 93:
					femaleHead2 = in.readUnsignedShort();
					break;
				case 94:
				case 95:
				case 97:
				case 98:
				case 110:
				case 111:
				case 112:
				case 139:
				case 140:
				case 148:
				case 149:
					in.readUnsignedShort(); // category/noted/resize/links
					break;
				case 100:
				case 101:
				case 102:
				case 103:
				case 104:
				case 105:
				case 106:
				case 107:
				case 108:
				case 109:
					in.readUnsignedShort(); // stack count variant
					in.readUnsignedShort();
					break;
				case 113:
				case 114:
					in.readByte(); // ambient / contrast
					break;
				case 115:
					in.readUnsignedByte(); // team
					break;
				case 200:
					// EntityOpsLoader.decodeSubOp: byte, byte, string.
					in.readUnsignedByte();
					in.readUnsignedByte();
					in.readString();
					break;
				case 201:
					// decodeConditionalOp: byte, short, short, int, int, string.
					in.readUnsignedByte();
					in.readUnsignedShort();
					in.readUnsignedShort();
					in.readInt();
					in.readInt();
					in.readString();
					break;
				case 202:
					// decodeConditionalSubOp: byte, 3x short, int, int, string.
					in.readUnsignedByte();
					in.readUnsignedShort();
					in.readUnsignedShort();
					in.readUnsignedShort();
					in.readInt();
					in.readInt();
					in.readString();
					break;
				case 249:
					in.skipParams();
					break;
				default:
					throw new IllegalStateException("unknown item opcode " + opcode);
			}
		}

		// Mirror the dumper's wearable filter: only items with a worn model.
		if (maleModel0 == -1 && femaleModel0 == -1)
		{
			return null;
		}

		ModelRepository.Entry entry = new ModelRepository.Entry();
		entry.n = name;
		entry.mo = maleOffset;
		entry.fo = femaleOffset;
		entry.wp1 = wearPos1;
		entry.wp2 = wearPos2;
		entry.wp3 = wearPos3;
		entry.m = new int[]{maleModel0, maleModel1, maleModel2};
		entry.f = new int[]{femaleModel0, femaleModel1, femaleModel2};
		if (maleHead != -1 || maleHead2 != -1)
		{
			entry.hm = new int[]{maleHead, maleHead2};
		}
		if (femaleHead != -1 || femaleHead2 != -1)
		{
			entry.hf = new int[]{femaleHead, femaleHead2};
		}
		entry.cf = colorFind;
		entry.cr = colorReplace;
		entry.tf = textureFind;
		entry.tr = textureReplace;
		return entry;
	}

	// ------------------------------------------------------------------- kits

	private static ModelRepository.Entry decodeKit(byte[] data, boolean[] nonSelectableOut)
	{
		Buffer in = new Buffer(data);
		Integer bodyPartId = null;
		int[] models = null;
		int[] chatheads = {-1, -1, -1, -1, -1};
		short[] recolorFind = null;
		short[] recolorReplace = null;
		short[] retextureFind = null;
		short[] retextureReplace = null;

		for (int opcode = in.readUnsignedByte(); opcode != 0; opcode = in.readUnsignedByte())
		{
			if (opcode == 1)
			{
				bodyPartId = in.readUnsignedByte();
			}
			else if (opcode == 2)
			{
				int count = in.readUnsignedByte();
				models = new int[count];
				for (int i = 0; i < count; i++)
				{
					models[i] = in.readUnsignedShort();
				}
			}
			else if (opcode == 3)
			{
				// Flagged non-selectable: character creation never offers this
				// style. No payload.
				nonSelectableOut[0] = true;
			}
			else if (opcode == 5)
			{
				int count = in.readUnsignedByte();
				models = new int[count];
				for (int i = 0; i < count; i++)
				{
					models[i] = in.readInt();
				}
			}
			else if (opcode == 40)
			{
				int count = in.readUnsignedByte();
				recolorFind = new short[count];
				recolorReplace = new short[count];
				for (int i = 0; i < count; i++)
				{
					// NOTE: signed shorts here, unlike items and spotanims.
					recolorFind[i] = (short) in.readShort();
					recolorReplace[i] = (short) in.readShort();
				}
			}
			else if (opcode == 41)
			{
				int count = in.readUnsignedByte();
				retextureFind = new short[count];
				retextureReplace = new short[count];
				for (int i = 0; i < count; i++)
				{
					retextureFind[i] = (short) in.readShort();
					retextureReplace[i] = (short) in.readShort();
				}
			}
			else if (opcode >= 60 && opcode < 70)
			{
				chatheads[opcode - 60] = in.readUnsignedShort();
			}
			else if (opcode >= 70 && opcode < 80)
			{
				chatheads[opcode - 70] = in.readInt();
			}
			else
			{
				throw new IllegalStateException("unknown kit opcode " + opcode);
			}
		}

		// Mirror the dumper's filter: kits without models are unusable.
		if (models == null || models.length == 0)
		{
			return null;
		}

		ModelRepository.Entry entry = new ModelRepository.Entry();
		entry.bp = bodyPartId;
		entry.m = models;
		entry.f = models;
		boolean anyHead = false;
		for (int head : chatheads)
		{
			anyHead |= head != -1;
		}
		if (anyHead)
		{
			entry.ch = chatheads;
		}
		entry.cf = recolorFind;
		entry.cr = recolorReplace;
		entry.tf = retextureFind;
		entry.tr = retextureReplace;
		return entry;
	}

	// -------------------------------------------------------------- spotanims

	private static SpotAnimRepository.Entry decodeSpotAnim(byte[] data)
	{
		Buffer in = new Buffer(data);
		int modelId = 0;
		int animationId = -1;
		int resizeX = 128;
		int resizeY = 128;
		int rotation = 0;
		int ambient = 0;
		int contrast = 0;
		short[] recolorFind = null;
		short[] recolorReplace = null;
		short[] retextureFind = null;
		short[] retextureReplace = null;

		for (int opcode = in.readUnsignedByte(); opcode != 0; opcode = in.readUnsignedByte())
		{
			switch (opcode)
			{
				case 1:
					modelId = in.readUnsignedShort();
					break;
				case 2:
					animationId = in.readUnsignedShort();
					break;
				case 3:
					modelId = in.readInt();
					break;
				case 4:
					resizeX = in.readUnsignedShort();
					break;
				case 5:
					resizeY = in.readUnsignedShort();
					break;
				case 6:
					rotation = in.readUnsignedShort();
					break;
				case 7:
					ambient = in.readUnsignedByte();
					break;
				case 8:
					contrast = in.readUnsignedByte();
					break;
				case 9:
					in.readString(); // debug name
					break;
				case 10:
					// A newer flag opcode with NO payload - proven empirically:
					// the offline dumper's library ignores unknown opcodes
					// without consuming bytes, and every affected definition
					// (39 of them, ids 3882+) still dumped perfectly clean.
					break;
				case 40:
				{
					int count = in.readUnsignedByte();
					recolorFind = new short[count];
					recolorReplace = new short[count];
					for (int i = 0; i < count; i++)
					{
						recolorFind[i] = (short) in.readUnsignedShort();
						recolorReplace[i] = (short) in.readUnsignedShort();
					}
					break;
				}
				case 41:
				{
					int count = in.readUnsignedByte();
					retextureFind = new short[count];
					retextureReplace = new short[count];
					for (int i = 0; i < count; i++)
					{
						retextureFind[i] = (short) in.readUnsignedShort();
						retextureReplace[i] = (short) in.readUnsignedShort();
					}
					break;
				}
				default:
					throw new IllegalStateException("unknown spotanim opcode " + opcode);
			}
		}

		// Mirror the dumper's filter: a spotanim without a model shows nothing.
		if (modelId <= 0)
		{
			return null;
		}

		SpotAnimRepository.Entry entry = new SpotAnimRepository.Entry();
		entry.m = modelId;
		entry.a = animationId;
		entry.rx = resizeX == 128 ? null : resizeX;
		entry.ry = resizeY == 128 ? null : resizeY;
		entry.rot = rotation == 0 ? null : rotation;
		entry.am = ambient == 0 ? null : ambient;
		entry.co = contrast == 0 ? null : contrast;
		entry.cf = recolorFind;
		entry.cr = recolorReplace;
		entry.tf = retextureFind;
		entry.tr = retextureReplace;
		return entry;
	}

	// ----------------------------------------------------------------- buffer

	/** Minimal big-endian reader matching runelite-cache's InputStream widths. */
	private static final class Buffer
	{
		private final byte[] data;
		private int offset;

		Buffer(byte[] data)
		{
			this.data = data;
		}

		int readUnsignedByte()
		{
			return data[offset++] & 0xFF;
		}

		int readByte()
		{
			return data[offset++];
		}

		int readUnsignedShort()
		{
			return (readUnsignedByte() << 8) | readUnsignedByte();
		}

		int readShort()
		{
			return (short) readUnsignedShort();
		}

		int read24BitInt()
		{
			return (readUnsignedByte() << 16) | (readUnsignedByte() << 8) | readUnsignedByte();
		}

		int readInt()
		{
			return (readUnsignedByte() << 24) | (readUnsignedByte() << 16)
				| (readUnsignedByte() << 8) | readUnsignedByte();
		}

		/**
		 * A 0-terminated string. Only item NAMES are kept, and those are
		 * plain CP1252; the special-character remapping the cache library
		 * applies affects codepoints 128-159, which item names do not use -
		 * anything in that range maps through the same table here for parity.
		 */
		String readString()
		{
			StringBuilder sb = new StringBuilder();
			for (int b = readUnsignedByte(); b != 0; b = readUnsignedByte())
			{
				if (b >= 128 && b < 160)
				{
					char mapped = CP1252_SPECIALS[b - 128];
					sb.append(mapped == 0 ? '?' : mapped);
				}
				else
				{
					sb.append((char) b);
				}
			}
			return sb.toString();
		}

		/** Skips a params map: count, then (isString byte, 24-bit key, value). */
		void skipParams()
		{
			int count = readUnsignedByte();
			for (int i = 0; i < count; i++)
			{
				boolean isString = readUnsignedByte() == 1;
				read24BitInt();
				if (isString)
				{
					readString();
				}
				else
				{
					readInt();
				}
			}
		}
	}

	/** Windows-1252's 0x80-0x9F block, as the cache text format defines it. */
	private static final char[] CP1252_SPECIALS = {
		'€', 0, '‚', 'ƒ', '„', '…', '†', '‡',
		'ˆ', '‰', 'Š', '‹', 'Œ', 0, 'Ž', 0,
		0, '‘', '’', '“', '”', '•', '–', '—',
		'˜', '™', 'š', '›', 'œ', 0, 'ž', 'Ÿ',
	};
}
