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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.runelite.cache.IndexType;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * The last and broadest search for weapon stance data: item params, item
 * category and structs were all ruled out, but the cache library only names
 * the config archives it happens to know about. The pose table could sit in an
 * archive nobody has named.
 *
 * <p>So this looks everywhere, without assuming a format. Every archive of
 * every index, every file, scanned for 16-bit values matching the pose ids the
 * plugin has genuinely observed. A file holding two or more of them is a
 * stance record - the numbers are far too specific to co-occur by chance.
 *
 * <pre>
 *   gradlew runArchiveProbe --args="&lt;stances.json&gt;"
 * </pre>
 */
public class StanceArchiveProbe
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

		// Pose ids we have actually seen, and the triples they came in.
		Set<Integer> poseIds = new TreeSet<>();
		Map<String, String> tripleOwner = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : stances.entrySet())
		{
			JsonObject stance = entry.getValue().getAsJsonObject();
			int idle = stance.get("idle").getAsInt();
			int walk = stance.get("walk").getAsInt();
			int run = stance.get("run").getAsInt();
			if (idle > 0 && walk > 0)
			{
				poseIds.add(idle);
				poseIds.add(walk);
				poseIds.add(run);
				tripleOwner.put(idle + "/" + walk + "/" + run, entry.getKey());
			}
		}
		System.out.printf("looking for %d observed pose ids from %d stance classes%n",
			poseIds.size(), tripleOwner.size());

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();

			int filesScanned = 0;
			Map<String, Integer> hitsByArchive = new TreeMap<>();
			int reported = 0;

			for (Index index : store.getIndexes())
			{
				// Maps and models are megabytes of geometry; a 16-bit scan
				// there is all false positives and no signal.
				if (index.getId() == IndexType.MAPS.getNumber()
					|| index.getId() == IndexType.MODELS.getNumber()
					|| index.getId() == IndexType.SPRITES.getNumber()
					|| index.getId() == IndexType.MUSIC_TRACKS.getNumber())
				{
					continue;
				}

				for (Archive archive : index.getArchives())
				{
					byte[] raw;
					try
					{
						raw = storage.loadArchive(archive);
						if (raw == null)
						{
							continue;
						}
						ArchiveFiles files = archive.getFiles(raw);
						for (FSFile file : files.getFiles())
						{
							byte[] data = file.getContents();
							if (data == null || data.length < 4)
							{
								continue;
							}
							filesScanned++;
							Set<Integer> found = scan(data, poseIds);
							if (found.size() >= 2)
							{
								String key = "index " + index.getId() + " archive " + archive.getArchiveId();
								hitsByArchive.merge(key, 1, Integer::sum);
								if (reported++ < 12)
								{
									System.out.printf("  %s file %d holds %s%n",
										key, file.getFileId(), new TreeSet<>(found));
								}
							}
						}
					}
					catch (RuntimeException e)
					{
						// Encrypted or unreadable archive; nothing to learn here.
					}
				}
			}

			System.out.printf("%n%d files scanned%n", filesScanned);
			if (hitsByArchive.isEmpty())
			{
				System.out.println();
				System.out.println("Nothing anywhere in the cache pairs these pose ids.");
				System.out.println("Weapon stances are assembled by the client at runtime, not");
				System.out.println("stored as a table - observation really is the only source.");
			}
			else
			{
				System.out.println();
				System.out.println("archives holding paired pose ids:");
				for (Map.Entry<String, Integer> hit : hitsByArchive.entrySet())
				{
					System.out.printf("  %-28s %d files%n", hit.getKey(), hit.getValue());
				}
			}
		}
	}

	/** Every observed pose id appearing as a 16-bit big-endian value. */
	private static Set<Integer> scan(byte[] data, Set<Integer> poseIds)
	{
		Set<Integer> found = new HashSet<>();
		for (int i = 0; i + 1 < data.length; i++)
		{
			int value = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			if (poseIds.contains(value))
			{
				found.add(value);
			}
		}
		return found;
	}
}
