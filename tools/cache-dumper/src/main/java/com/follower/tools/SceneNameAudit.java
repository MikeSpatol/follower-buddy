package com.follower.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.definitions.loaders.ObjectLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Checks the names and ids hardcoded in the PLUGIN's own Java against the game.
 *
 * <p>{@link com.follower.tools.RuleTargetAudit} covers the rule file; this
 * covers the other half, which is just as able to fail silently. The errand
 * controller looks for scene objects by name - "Bank booth", "Forester's
 * Campfire" - and an errand whose name matches nothing simply never happens.
 * There is no error, no log line, and nothing to distinguish it from an errand
 * that has not been rolled yet.
 *
 * <p>The pose constants have the same shape of risk one level down: a pose id
 * that is not a sequence in the cache leaves the follower standing still, or
 * sliding along in its idle animation.
 *
 * <pre>
 *   gradlew runSceneAudit
 * </pre>
 */
public class SceneNameAudit
{
	/** Object names ErrandController searches the scene for. */
	private static final String[] OBJECT_NAMES = {
		"Altar", "Bank booth", "Bank chest", "Grand Exchange booth",
		"Fire", "Campfire", "Forester's Campfire",
	};

	/** NPC names it searches for. */
	private static final String[] NPC_NAMES = {
		"Banker", "Grand Exchange Clerk", "Cat", "Kitten", "Stray dog",
	};

	/** PlayerPose, the standard unarmed set every fallback leans on. */
	private static final int[][] POSES = {
		{808, 0}, {819, 0}, {824, 0}, {820, 0}, {821, 0}, {822, 0}, {823, 0},
	};

	private static final String[] POSE_NAMES = {
		"IDLE", "WALK", "RUN", "TURN_180", "SIDESTEP_LEFT", "SIDESTEP_RIGHT", "IDLE_TURN",
	};

	public static void main(String[] args) throws IOException
	{
		Path cacheDir = args.length > 0
			? Paths.get(args[0])
			: Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");

		Set<String> objects = new HashSet<>();
		Set<String> npcs = new HashSet<>();
		Set<Integer> sequences = new HashSet<>();

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);

			Archive objectArchive = configs.getArchive(ConfigType.OBJECT.getId());
			ArchiveFiles objectFiles = objectArchive.getFiles(storage.loadArchive(objectArchive));
			ObjectLoader objectLoader = new ObjectLoader();
			for (FSFile file : objectFiles.getFiles())
			{
				ObjectDefinition object = objectLoader.load(file.getFileId(), file.getContents());
				if (object.getName() != null && !"null".equals(object.getName()))
				{
					objects.add(normalise(object.getName()));
				}
			}

			Archive npcArchive = configs.getArchive(ConfigType.NPC.getId());
			ArchiveFiles npcFiles = npcArchive.getFiles(storage.loadArchive(npcArchive));
			NpcLoader npcLoader = new NpcLoader();
			for (FSFile file : npcFiles.getFiles())
			{
				NpcDefinition npc = npcLoader.load(file.getFileId(), file.getContents());
				if (npc.name != null && !"null".equals(npc.name))
				{
					npcs.add(normalise(npc.name));
				}
			}

			Archive seqArchive = configs.getArchive(ConfigType.SEQUENCE.getId());
			ArchiveFiles seqFiles = seqArchive.getFiles(storage.loadArchive(seqArchive));
			SequenceLoader seqLoader = new SequenceLoader();
			for (FSFile file : seqFiles.getFiles())
			{
				SequenceDefinition sequence = seqLoader.load(file.getFileId(), file.getContents());
				if (sequence != null)
				{
					sequences.add(file.getFileId());
				}
			}
		}

		System.out.printf("cache holds %d named objects, %d named NPCs, %d sequences%n%n",
			objects.size(), npcs.size(), sequences.size());

		List<String> dead = new ArrayList<>();

		System.out.println("errand scene objects:");
		for (String name : OBJECT_NAMES)
		{
			boolean found = objects.contains(normalise(name));
			System.out.printf("  %-24s %s%n", name, found ? "found" : "NOT IN THE GAME");
			if (!found)
			{
				dead.add("object \"" + name + "\"");
			}
		}

		System.out.println("\nerrand NPCs:");
		for (String name : NPC_NAMES)
		{
			boolean found = npcs.contains(normalise(name));
			System.out.printf("  %-24s %s%n", name, found ? "found" : "NOT IN THE GAME");
			if (!found)
			{
				dead.add("npc \"" + name + "\"");
			}
		}

		System.out.println("\nPlayerPose constants:");
		for (int i = 0; i < POSES.length; i++)
		{
			int id = POSES[i][0];
			boolean found = sequences.contains(id);
			System.out.printf("  %-16s %-6d %s%n", POSE_NAMES[i], id,
				found ? "found" : "NOT A SEQUENCE");
			if (!found)
			{
				dead.add("pose " + POSE_NAMES[i] + " (" + id + ")");
			}
		}

		System.out.println();
		if (dead.isEmpty())
		{
			System.out.println("everything the plugin hardcodes resolves against the cache");
		}
		else
		{
			System.out.println("THESE CANNOT EVER MATCH:");
			for (String line : dead)
			{
				System.out.println("  " + line);
			}
		}

		// A near-miss list is what actually helps when something is wrong.
		if (!dead.isEmpty())
		{
			System.out.println("\nnear misses in the cache:");
			for (String line : dead)
			{
				int quote = line.indexOf('"');
				if (quote < 0)
				{
					continue;
				}
				String wanted = normalise(line.substring(quote + 1, line.lastIndexOf('"')));
				String head = wanted.split(" ")[0];
				Set<String> pool = line.startsWith("object") ? objects : npcs;
				Set<String> near = new TreeSet<>();
				for (String candidate : pool)
				{
					if (candidate.contains(head))
					{
						near.add(candidate);
					}
				}
				System.out.printf("  %s -> %s%n", wanted,
					near.isEmpty() ? "(nothing similar)" : near);
			}
		}
	}

	private static String normalise(String name)
	{
		return name.replace((char) 0x00A0, ' ').trim().toLowerCase(Locale.ROOT);
	}
}
