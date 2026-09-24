package com.follower.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Checks every portrait in the chronicle against the cache: the NPC id is a
 * real NPC, and the plate's {@code idle} is that NPC's own standing
 * animation, which exists. The plugin cannot ask an NPC id for its standing
 * animation at runtime, so the id is written into the page, and a wrong one
 * would play some other creature's idle, or nothing, without a word.
 *
 * <p>Prints one line per portrait with what the cache says, so a new plate's
 * {@code idle} can be copied from here.
 *
 *   gradlew runChronicleAudit
 *   gradlew runChronicleAudit --args="<chronicle.json> [cacheDir]"
 */
public class ChronicleAudit
{
	public static void main(String[] args) throws Exception
	{
		Path chronicle = args.length > 0
			? Paths.get(args[0])
			: Paths.get("..", "..", "src", "main", "resources", "com", "follower", "default-chronicle.json");
		Path cacheDir = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"), ".runelite", "jagexcache", "oldschool", "LIVE");

		JsonObject root;
		try (Reader in = Files.newBufferedReader(chronicle, StandardCharsets.UTF_8))
		{
			root = JsonParser.parseReader(in).getAsJsonObject();
		}

		int portraits = 0;
		int problems = 0;
		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);
			Archive npcArchive = configs.getArchive(ConfigType.NPC.getId());
			ArchiveFiles npcs = npcArchive.getFiles(storage.loadArchive(npcArchive));
			Archive seqArchive = configs.getArchive(ConfigType.SEQUENCE.getId());
			ArchiveFiles seqs = seqArchive.getFiles(storage.loadArchive(seqArchive));
			NpcLoader npcLoader = new NpcLoader();
			SequenceLoader seqLoader = new SequenceLoader();

			for (JsonElement e : root.getAsJsonArray("entries"))
			{
				JsonObject entry = e.getAsJsonObject();
				String page = entry.get("id").getAsString();
				if (!entry.has("plates"))
				{
					continue;
				}
				for (JsonElement p : entry.getAsJsonArray("plates"))
				{
					JsonObject plate = p.getAsJsonObject();
					if (!plate.has("npc"))
					{
						continue;
					}
					portraits++;
					int id = plate.get("npc").getAsInt();
					Integer idle = plate.has("idle") ? plate.get("idle").getAsInt() : null;
					FSFile file = npcs.findFile(id);
					if (file == null)
					{
						System.out.printf("  %-34s npc %-6d NO SUCH NPC%n", page, id);
						problems++;
						continue;
					}
					NpcDefinition npc = npcLoader.load(id, file.getContents());
					int stand = npc.standingAnimation;
					String verdict;
					if (stand == -1)
					{
						// The integrity test wants an idle on every portrait, so
						// an NPC that never stands in one cannot be a portrait.
						verdict = "WRONG: it has no standing animation; choose an NPC id that has one";
					}
					else if (idle == null)
					{
						verdict = "MISSING: idle should be " + stand;
					}
					else if (idle != stand)
					{
						verdict = "WRONG: idle is " + idle + ", its standing animation is " + stand;
					}
					else
					{
						FSFile seq = seqs.findFile(stand);
						if (seq == null)
						{
							verdict = "WRONG: animation " + stand + " does not exist";
						}
						else
						{
							SequenceDefinition def = seqLoader.load(stand, seq.getContents());
							verdict = "ok, " + (def.animMayaID != -1
								? "a newer animation"
								: (def.frameIDs == null ? 0 : def.frameIDs.length) + " frames");
						}
					}
					if (!verdict.startsWith("ok"))
					{
						problems++;
					}
					System.out.printf("  %-34s npc %-6d %-24s stand %-6d %s%n", page, id,
						"\"" + npc.name + "\"", stand, verdict);
				}
			}
		}
		System.out.println();
		System.out.println(portraits + " portraits checked, " + problems + " to fix");
	}
}
