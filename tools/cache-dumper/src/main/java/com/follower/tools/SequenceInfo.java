package com.follower.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Prints what an animation id actually IS, so a number taken from anywhere
 * outside the game can be checked before it is trusted.
 *
 * <p>Weapon stances cannot be read from the cache, so ids sometimes have to
 * come from elsewhere. The animation itself still lives in the cache though:
 * an id that does not exist there is simply wrong, and the frame count and
 * duration say whether what does exist is the right shape for the job.
 *
 * <pre>
 *   gradlew runSeqInfo --args="7508 7509 7510 7511"
 * </pre>
 */
public class SequenceInfo
{
	public static void main(String[] args) throws IOException
	{
		Path cacheDir = Paths.get(System.getProperty("user.home"),
			".runelite", "jagexcache", "oldschool", "LIVE");

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);
			Archive archive = configs.getArchive(ConfigType.SEQUENCE.getId());
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			SequenceLoader loader = new SequenceLoader();

			for (String arg : args)
			{
				int id;
				try
				{
					id = Integer.parseInt(arg.trim());
				}
				catch (NumberFormatException e)
				{
					continue;
				}

				FSFile file = files.findFile(id);
				if (file == null)
				{
					System.out.printf("%-6d DOES NOT EXIST - the id is wrong%n", id);
					continue;
				}

				SequenceDefinition def = loader.load(id, file.getContents());
				int frames = def.frameIDs == null ? 0 : def.frameIDs.length;
				int ticks = 0;
				if (def.frameLengths != null)
				{
					for (int length : def.frameLengths)
					{
						ticks += length;
					}
				}
				// frameStep is NOT a stance/attack discriminator: pose
				// animations carry -1 just as attacks do, which is why the
				// follower has to loop them defensively. What this tool
				// actually proves is that an id EXISTS and how long it runs.
				System.out.printf("%-6d %2d frames, %3d client ticks (%.2fs), frameStep %d%n",
					id, frames, ticks, ticks * 0.02, def.frameStep);
			}
		}
	}
}
