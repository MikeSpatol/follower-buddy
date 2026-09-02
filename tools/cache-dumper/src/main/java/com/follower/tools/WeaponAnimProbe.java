package com.follower.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Asks the cache whether it knows which animation a weapon attacks with.
 *
 * <p>The stance library learns attack animations by watching them, which is why
 * a thousand weapons are still missing one. If the cache carried the mapping
 * directly there would be nothing to learn and nothing to ask anybody for, so
 * this checks rather than assumes: it prints the params and category of a few
 * named weapons, and reports how many items carry each param id, so a param
 * that looks like an animation can be spotted by its spread.
 *
 *   gradlew runWeaponAnims --args="4151 1333 841 11802 [cacheDir]"
 */
public class WeaponAnimProbe
{
	public static void main(String[] args) throws Exception
	{
		List<Integer> wanted = new ArrayList<>();
		Path cacheDir = null;
		for (String arg : args)
		{
			try
			{
				wanted.add(Integer.parseInt(arg));
			}
			catch (NumberFormatException notAnId)
			{
				cacheDir = Paths.get(arg);
			}
		}
		if (cacheDir == null)
		{
			cacheDir = Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");
		}

		Map<Integer, Integer> paramSpread = new LinkedHashMap<>();
		int items = 0;
		int withParams = 0;

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index index = store.getIndex(IndexType.CONFIGS);
			Archive archive = index.getArchive(ConfigType.ITEM.getId());
			byte[] data = storage.loadArchive(archive);
			ArchiveFiles files = archive.getFiles(data);
			ItemLoader loader = new ItemLoader();

			for (FSFile file : files.getFiles())
			{
				ItemDefinition item = loader.load(file.getFileId(), file.getContents());
				items++;
				if (item.params != null && !item.params.isEmpty())
				{
					withParams++;
					for (Object key : item.params.keySet())
					{
						int id = ((Number) key).intValue();
						paramSpread.merge(id, 1, Integer::sum);
					}
				}
				if (wanted.contains(item.id))
				{
					System.out.println("== " + item.id + "  " + item.name);
					System.out.println("   category : " + item.category);
					System.out.println("   params   : "
						+ (item.params == null ? "(none)" : item.params));
				}
			}
		}

		System.out.println();
		System.out.println("items scanned: " + items + ", carrying params: " + withParams);
		System.out.println("param ids by how many items use them:");
		paramSpread.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(25)
			.forEach(e -> System.out.println("   param " + e.getKey()
				+ "  on " + e.getValue() + " items"));
	}
}
