package com.follower.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Looks a name up in the cache, so a question about what an NPC actually is
 * gets answered by the game rather than by memory.
 *
 * <p>Prints every NPC whose name contains the text, with its id, combat level
 * and the options on its right-click menu - enough to tell a boss from a pet
 * from a piece of scenery that happens to share a name.
 *
 *   gradlew runNpcFind --args="brutus [cacheDir]"
 */
public class NpcFind
{
	public static void main(String[] args) throws Exception
	{
		if (args.length < 1)
		{
			System.out.println("usage: runNpcFind --args=\"<name fragment> [cacheDir]\"");
			return;
		}
		String wanted = args[0].toLowerCase(Locale.ROOT);
		Path cacheDir = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");

		List<NpcDefinition> hits = new ArrayList<>();
		int total = 0;
		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index index = store.getIndex(IndexType.CONFIGS);
			Archive archive = index.getArchive(ConfigType.NPC.getId());
			byte[] data = storage.loadArchive(archive);
			ArchiveFiles files = archive.getFiles(data);
			NpcLoader loader = new NpcLoader();
			for (FSFile file : files.getFiles())
			{
				NpcDefinition npc = loader.load(file.getFileId(), file.getContents());
				total++;
				if (npc.name != null && !"null".equals(npc.name)
					&& npc.name.toLowerCase(Locale.ROOT).contains(wanted))
				{
					hits.add(npc);
				}
			}
		}

		System.out.println("Searched " + total + " NPCs for \"" + args[0] + "\"");
		System.out.println();
		if (hits.isEmpty())
		{
			System.out.println("  no NPC in the cache has that in its name");
			return;
		}
		for (NpcDefinition npc : hits)
		{
			System.out.println("  id " + npc.id + "  \"" + npc.name + "\""
				+ "  combat " + npc.combatLevel
				+ "  size " + npc.size);
		}
	}
}
