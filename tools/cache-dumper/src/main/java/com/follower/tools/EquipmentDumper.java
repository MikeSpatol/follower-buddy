package com.follower.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.KitDefinition;
import net.runelite.cache.definitions.SpotAnimDefinition;
import net.runelite.cache.definitions.loaders.KitLoader;
import net.runelite.cache.definitions.loaders.SpotAnimLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Writes equipment-models.json for the Follower Buddy plugin.
 *
 * <p>Run once, and again after a game update that adds gear you care about:
 *
 * <pre>
 *   gradlew run --args="&lt;cacheDir&gt; &lt;outputFile&gt;"
 * </pre>
 *
 * Both arguments are optional; the defaults are RuneLite's own cache directory
 * and {@code ~/.runelite/follower/equipment-models.json}.
 *
 * <p>Only items with at least one worn model are emitted — roughly 4,000 of the
 * 30,000-odd item definitions — which keeps the output a few megabytes rather
 * than tens.
 */
public class EquipmentDumper
{
	private static final int FORMAT_VERSION = 1;

	/** Mirrors com.follower.appearance.ModelRepository.Entry. */
	static class Entry
	{
		/** Item name, so the plugin's outfit picker has a searchable catalogue. */
		String n;
		/** Kits only: KitDefinition.bodyPartId - identifies part AND gender. */
		Integer bp;
		/**
		 * Vertical offsets the client applies to a worn model before merging it.
		 * Without them every piece sits at its raw origin and neighbouring pieces
		 * clip into one another.
		 */
		Integer mo;
		Integer fo;
		/**
		 * Equipment slots this item occupies. wp1 is the slot it goes in; wp2 and wp3
		 * are further slots it HIDES - a platebody hides the arms kit, a full helm
		 * hides hair and jaw. They index the same 12-slot array as KitType.
		 */
		Integer wp1;
		Integer wp2;
		Integer wp3;
		int[] m;
		int[] f;
		short[] cf;
		short[] cr;
		short[] tf;
		short[] tr;
		/**
		 * Chathead models: for items, the male/female dialogue head variants; for
		 * kits, KitDefinition.chatheadModels. These are what real dialogs animate -
		 * separate models with their own talk-animation skeletons.
		 */
		int[] hm;
		int[] hf;
		int[] ch;
	}

	static class Dump
	{
		int version = FORMAT_VERSION;
		String cacheRevision;
		Map<String, Entry> items = new LinkedHashMap<>();
		Map<String, Entry> kits = new LinkedHashMap<>();
	}

	/** Mirrors com.follower.appearance.SpotAnimRepository.Entry. */
	static class SpotAnimEntry
	{
		int m;
		int a;
		Integer rx;
		Integer ry;
		Integer rot;
		Integer am;
		Integer co;
		short[] cf;
		short[] cr;
		short[] tf;
		short[] tr;
	}

	static class SpotAnimDump
	{
		int version = FORMAT_VERSION;
		String cacheRevision;
		Map<String, SpotAnimEntry> spotanims = new LinkedHashMap<>();
	}

	public static void main(String[] args) throws IOException
	{
		Path cacheDir = args.length > 0
			? Paths.get(args[0])
			: defaultCacheDir();

		Path output = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"), ".runelite", "follower", "equipment-models.json");

		if (!Files.isDirectory(cacheDir))
		{
			System.err.println("Cache directory not found: " + cacheDir);
			System.err.println("Pass it explicitly: gradlew run --args=\"C:/path/to/cache out.json\"");
			System.exit(1);
		}

		System.out.println("Reading cache from " + cacheDir);

		Dump dump = new Dump();
		dump.cacheRevision = LocalDate.now().toString();

		SpotAnimDump spotAnims = new SpotAnimDump();
		spotAnims.cacheRevision = dump.cacheRevision;

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			dumpItems(store, dump);
			dumpKits(store, dump);
			dumpSpotAnims(store, spotAnims);
		}

		Files.createDirectories(output.getParent());
		Gson gson = new GsonBuilder().create();
		try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			gson.toJson(dump, writer);
		}

		Path spotAnimOutput = output.resolveSibling("spotanims.json");
		try (Writer writer = Files.newBufferedWriter(spotAnimOutput, StandardCharsets.UTF_8))
		{
			gson.toJson(spotAnims, writer);
		}

		System.out.printf("Wrote %d items and %d kits to %s (%.1f MB)%n",
			dump.items.size(), dump.kits.size(), output, Files.size(output) / 1024.0 / 1024.0);
		System.out.printf("Wrote %d spotanims to %s (%.1f KB)%n",
			spotAnims.spotanims.size(), spotAnimOutput, Files.size(spotAnimOutput) / 1024.0);
	}

	private static void dumpItems(Store store, Dump dump) throws IOException
	{
		ItemManager itemManager = new ItemManager(store);
		itemManager.load();

		for (ItemDefinition item : itemManager.getItems())
		{
			if (item == null)
			{
				continue;
			}

			boolean wearable = item.maleModel0 != -1 || item.femaleModel0 != -1;
			if (!wearable)
			{
				continue;
			}

			Entry entry = new Entry();
			entry.n = item.name;
			entry.mo = item.maleOffset;
			entry.fo = item.femaleOffset;
			entry.wp1 = item.wearPos1;
			entry.wp2 = item.wearPos2;
			entry.wp3 = item.wearPos3;
			entry.m = new int[]{item.maleModel0, item.maleModel1, item.maleModel2};
			entry.f = new int[]{item.femaleModel0, item.femaleModel1, item.femaleModel2};
			if (item.maleHeadModel != -1 || item.maleHeadModel2 != -1)
			{
				entry.hm = new int[]{item.maleHeadModel, item.maleHeadModel2};
			}
			if (item.femaleHeadModel != -1 || item.femaleHeadModel2 != -1)
			{
				entry.hf = new int[]{item.femaleHeadModel, item.femaleHeadModel2};
			}
			entry.cf = item.colorFind;
			entry.cr = item.colorReplace;
			entry.tf = item.textureFind;
			entry.tr = item.textureReplace;

			dump.items.put(Integer.toString(item.id), entry);
		}
	}

	private static void dumpKits(Store store, Dump dump) throws IOException
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.IDENTKIT.getId());

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);
		KitLoader loader = new KitLoader();

		for (FSFile file : files.getFiles())
		{
			KitDefinition kit = loader.load(file.getFileId(), file.getContents());
			if (kit == null || kit.models == null || kit.models.length == 0)
			{
				continue;
			}

			Entry entry = new Entry();
			entry.bp = kit.bodyPartId;
			// Kits are gender-specific by id, so both genders map to the same models.
			entry.m = kit.models;
			entry.f = kit.models;
			boolean anyHead = false;
			for (int headModel : kit.chatheadModels)
			{
				anyHead |= headModel != -1;
			}
			if (anyHead)
			{
				entry.ch = kit.chatheadModels;
			}
			entry.cf = kit.recolorToFind;
			entry.cr = kit.recolorToReplace;
			entry.tf = kit.retextureToFind;
			entry.tr = kit.retextureToReplace;

			dump.kits.put(Integer.toString(file.getFileId()), entry);
		}
	}

	/**
	 * Spotanims are the game's "graphics": teleport swirls, spell impacts, the
	 * home teleport's rune circle. Each maps a graphic id to a model, the
	 * animation that plays it, scaling, rotation, recolours and lighting tweaks.
	 * The live client API exposes none of this, which is why it is dumped.
	 */
	private static void dumpSpotAnims(Store store, SpotAnimDump dump) throws IOException
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SPOTANIM.getId());

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);
		SpotAnimLoader loader = new SpotAnimLoader();

		for (FSFile file : files.getFiles())
		{
			SpotAnimDefinition def = loader.load(file.getFileId(), file.getContents());
			if (def == null || def.modelId <= 0)
			{
				continue;
			}

			SpotAnimEntry entry = new SpotAnimEntry();
			entry.m = def.modelId;
			entry.a = def.animationId;
			// Defaults are omitted so the file stays small: 128 scale is 100%,
			// rotation 0, ambient/contrast 0.
			entry.rx = def.resizeX == 128 ? null : def.resizeX;
			entry.ry = def.resizeY == 128 ? null : def.resizeY;
			entry.rot = def.rotaton == 0 ? null : def.rotaton;
			entry.am = def.ambient == 0 ? null : def.ambient;
			entry.co = def.contrast == 0 ? null : def.contrast;
			entry.cf = def.recolorToFind;
			entry.cr = def.recolorToReplace;
			entry.tf = def.textureToFind;
			entry.tr = def.textureToReplace;

			dump.spotanims.put(Integer.toString(file.getFileId()), entry);
		}
	}

	private static Path defaultCacheDir()
	{
		Path runeliteCache = Paths.get(System.getProperty("user.home"),
			".runelite", "jagexcache", "oldschool", "LIVE");
		if (Files.isDirectory(runeliteCache))
		{
			return runeliteCache;
		}

		// Fall back to the standalone client's cache.
		return Paths.get(System.getProperty("user.home"), "jagexcache", "oldschool", "LIVE");
	}
}
