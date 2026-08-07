package com.follower.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Lists every NPC the game treats as a pet, so a rule can greet them by name
 * without the list being guesswork.
 *
 * <p>Written after a cat called Trotters walked past a follower that had been
 * told to look for NPCs named "Cat" and "Kitten". Guessing names one encounter
 * at a time never converges; the game already knows which NPCs are pets.
 *
 * <p>The signal is not a heuristic: {@code NpcDefinition.isFollower} is the
 * game's own flag for an NPC that trots along behind a player. Nothing has to
 * be inferred from names or options.
 *
 * <pre>
 *   gradlew runPetProbe
 * </pre>
 */
public class PetProbe
{
	public static void main(String[] args) throws IOException
	{
		Path cacheDir = args.length > 0
			? Paths.get(args[0])
			: Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");

		// An optional name fragment turns this into a lookup: what IS that NPC,
		// and does the game consider it a follower?
		String search = null;
		for (String arg : args)
		{
			if (arg.startsWith("find="))
			{
				search = arg.substring(5).toLowerCase();
			}
		}

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);
			Archive archive = configs.getArchive(ConfigType.NPC.getId());
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			NpcLoader loader = new NpcLoader();

			List<String> pets = new ArrayList<>();
			List<String> cats = new ArrayList<>();
			int total = 0;

			for (FSFile file : files.getFiles())
			{
				total++;
				NpcDefinition def = loader.load(file.getFileId(), file.getContents());
				if (def == null || def.name == null || "null".equals(def.name))
				{
					continue;
				}

				String lower = def.name.toLowerCase();
				if (search != null && lower.contains(search))
				{
					System.out.printf("FOUND id %-6d %-28s isFollower=%s combatLevel=%d size=%d%n",
						def.id, def.name, def.isFollower, def.combatLevel, def.size);
				}
				boolean catLike = lower.contains("cat") || lower.contains("kitten");

				if (def.isFollower)
				{
					pets.add(def.name);
				}
				else if (catLike)
				{
					cats.add(def.name);
				}
			}

			java.util.Collections.sort(pets);
			java.util.Collections.sort(cats);

			System.out.printf("%d NPC definitions scanned%n%n", total);
			System.out.printf("--- %d flagged isFollower (pets) ---%n", pets.size());
			for (String name : dedupe(pets))
			{
				System.out.println("  " + name);
			}
			System.out.printf("%n--- %d more matching cat/kitten by name ---%n", cats.size());
			for (String name : dedupe(cats))
			{
				System.out.println("  " + name);
			}
		}
	}

	/** The same pet exists once per variant; the rule only needs the name. */
	private static List<String> dedupe(List<String> names)
	{
		List<String> unique = new ArrayList<>();
		String last = null;
		for (String name : names)
		{
			if (!name.equals(last))
			{
				unique.add(name);
				last = name;
			}
		}
		return unique;
	}
}
