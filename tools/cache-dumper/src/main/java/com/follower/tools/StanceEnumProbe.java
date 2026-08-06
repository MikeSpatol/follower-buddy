package com.follower.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.EnumDefinition;
import net.runelite.cache.definitions.loaders.EnumLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Chases the one lead the broad archive sweep left standing: 41 ENUM files
 * hold two or more real pose ids. Enums are how the game keys tables off item
 * ids, so if a weapon's stance is data anywhere, it is here.
 *
 * <p>This decodes every enum properly rather than scanning bytes, and reports
 * any whose values include observed pose ids - with its key type, so we can
 * see whether it is keyed BY ITEM (which would give a direct weapon to stance
 * mapping) or by something else.
 *
 * <pre>
 *   gradlew runEnumProbe --args="&lt;stances.json&gt;"
 * </pre>
 */
public class StanceEnumProbe
{
	public static void main(String[] args) throws IOException
	{
		Path stancesFile = args.length > 0
			? Paths.get(args[0])
			: Paths.get(System.getProperty("user.home"), ".runelite", "follower", "stances.json");
		Path cacheDir = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"), ".runelite", "jagexcache", "oldschool", "LIVE");

		JsonObject stances;
		try (Reader reader = Files.newBufferedReader(stancesFile, StandardCharsets.UTF_8))
		{
			stances = new Gson().fromJson(reader, JsonObject.class);
		}

		Set<Integer> poseIds = new TreeSet<>();
		Map<Integer, String> weaponStance = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : stances.entrySet())
		{
			JsonObject stance = entry.getValue().getAsJsonObject();
			int idle = stance.get("idle").getAsInt();
			int walk = stance.get("walk").getAsInt();
			int run = stance.get("run").getAsInt();
			if (idle <= 0 || walk <= 0)
			{
				continue;
			}
			poseIds.add(idle);
			poseIds.add(walk);
			poseIds.add(run);
			try
			{
				weaponStance.put(Integer.parseInt(entry.getKey()), idle + "/" + walk + "/" + run);
			}
			catch (NumberFormatException ignored)
			{
				// not an item id
			}
		}

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);
			Archive archive = configs.getArchive(ConfigType.ENUM.getId());
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			EnumLoader loader = new EnumLoader();

			int total = 0;
			int interesting = 0;

			for (FSFile file : files.getFiles())
			{
				total++;
				EnumDefinition def = loader.load(file.getFileId(), file.getContents());
				if (def == null)
				{
					continue;
				}
				int[] vals = def.getIntVals();
				int[] keys = def.getKeys();
				if (vals == null || keys == null)
				{
					continue;
				}

				Set<Integer> found = new HashSet<>();
				for (int value : vals)
				{
					if (poseIds.contains(value))
					{
						found.add(value);
					}
				}
				if (found.size() < 2)
				{
					continue;
				}
				interesting++;

				// The decisive question: are the KEYS weapons we know? If an
				// enum keyed by item id maps our weapons to their real pose
				// ids, every weapon in the game resolves without observation.
				int keyedWeapons = 0;
				int agreeing = 0;
				for (int i = 0; i < keys.length && i < vals.length; i++)
				{
					String known = weaponStance.get(keys[i]);
					if (known != null)
					{
						keyedWeapons++;
						if (known.startsWith(vals[i] + "/") || known.contains("/" + vals[i]))
						{
							agreeing++;
						}
					}
				}

				System.out.printf("enum %-6d key=%-12s val=%-12s size=%-5d pose ids %s%n",
					def.getId(), def.getKeyType(), def.getValType(), def.getSize(),
					new TreeSet<>(found));
				if (keyedWeapons > 0)
				{
					System.out.printf("        keys include %d weapons we know, %d agree%s%n",
						keyedWeapons, agreeing,
						agreeing == keyedWeapons ? "   <-- THIS MAPS WEAPONS TO POSES" : "");
				}
			}

			System.out.printf("%n%d enums, %d hold two or more real pose ids%n", total, interesting);
		}
	}
}
