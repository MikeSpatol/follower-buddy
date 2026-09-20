package com.follower.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ObjectLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Every object in the game a player can set light to, and what
 * {@code HotGround} would make of it.
 *
 * <p>The hot-ground set is matched by NAME, and a name-based match fails
 * silently: the follower simply walks into the thing, with no log line and
 * nothing to tell it apart from a fire nobody lit. The list was written from
 * memory the first time. This is the audit it should have had.
 *
 * <pre>
 *   gradlew runFireAudit
 * </pre>
 */
public class FireAudit
{
	/** The names HotGround.NAMES holds, kept in step by hand. */
	private static final List<String> MATCHED = Arrays.asList(
		"fire", "campfire", "forester's campfire", "bonfire");

	/** Anything whose name contains one of these is worth a look. */
	private static final String[] LOOK_FOR = {
		"fire", "flame", "brazier", "embers", "burning", "pyre", "furnace",
	};

	public static void main(String[] args) throws IOException
	{
		Path cacheDir = args.length > 0
			? Paths.get(args[0])
			: Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");

		List<ObjectDefinition> candidates = new ArrayList<>();
		int named = 0;

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);
			Archive archive = configs.getArchive(ConfigType.OBJECT.getId());
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			ObjectLoader loader = new ObjectLoader();

			for (FSFile file : files.getFiles())
			{
				ObjectDefinition object = loader.load(file.getFileId(), file.getContents());
				String name = object.getName();
				if (name == null || "null".equals(name))
				{
					continue;
				}
				named++;
				String lower = name.toLowerCase(Locale.ROOT);
				for (String look : LOOK_FOR)
				{
					if (lower.contains(look))
					{
						candidates.add(object);
						break;
					}
				}
			}
		}

		System.out.printf("%d named objects in the cache, %d look like fire%n%n",
			named, candidates.size());

		candidates.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

		System.out.printf("%-8s %-34s %-5s %-8s %-6s %s%n",
			"id", "name", "size", "matched", "solid", "actions");
		List<String> missed = new ArrayList<>();
		for (ObjectDefinition object : candidates)
		{
			String name = object.getName();
			boolean matched = MATCHED.contains(name.toLowerCase(Locale.ROOT));
			String actions = actionsOf(object);
			System.out.printf("%-8d %-34s %dx%-2d %-8s %-6s %s%n",
				object.getId(), name, object.getSizeX(), object.getSizeY(),
				matched ? "HOT" : "-",
				object.getInteractType() == 0 ? "no" : "yes",
				actions);
			// A thing you can light, or cook on, that the follower would walk
			// straight into.
			if (!matched && (actions.contains("Light") || actions.contains("Cook")
				|| actions.contains("Add logs") || actions.contains("Feed")))
			{
				missed.add(object.getId() + " " + name + " " + actions);
			}
		}

		System.out.println();
		if (missed.isEmpty())
		{
			System.out.println("nothing lightable or cookable is missing from the hot list");
		}
		else
		{
			System.out.println("LIGHTABLE OR COOKABLE AND NOT IN THE HOT LIST:");
			for (String line : missed)
			{
				System.out.println("  " + line);
			}
		}

		System.out.println("\nthe names HotGround matches, and what carries them:");
		for (String wanted : MATCHED)
		{
			List<String> carriers = new ArrayList<>();
			for (ObjectDefinition object : candidates)
			{
				if (object.getName().toLowerCase(Locale.ROOT).equals(wanted))
				{
					carriers.add(String.valueOf(object.getId()));
				}
			}
			System.out.printf("  %-24s %s%n", wanted,
				carriers.isEmpty() ? "NOTHING IN THE GAME IS CALLED THIS" : carriers);
		}
	}

	private static String actionsOf(ObjectDefinition object)
	{
		net.runelite.cache.EntityOpsDefinition ops = object.getOps();
		if (ops == null || ops.getOps() == null)
		{
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (net.runelite.cache.EntityOpsDefinition.Op op : ops.getOps())
		{
			if (op != null && op.text != null && !"null".equals(op.text))
			{
				if (out.length() > 0)
				{
					out.append(", ");
				}
				out.append(op.text);
			}
		}
		return out.toString();
	}
}
