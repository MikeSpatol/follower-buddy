package com.follower.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Checks that every NPC name and item id the rules key on exists in the game.
 *
 * <p>These two are the silent failures of the rule file. A rule that watches
 * for an NPC called "Hans " with a trailing space, or "Guard" where the game
 * says "Guard dog", never fires and never says why - it is indistinguishable
 * from a rule whose trigger simply has not happened yet. The same holds for an
 * itemEquipped id that is one digit out.
 *
 * <p>Names are matched the way the plugin matches them: case-insensitively,
 * whole, with {@code *} as a wildcard. So this asks exactly the question the
 * plugin will ask at runtime, against the same cache it will ask it of.
 *
 * <pre>
 *   gradlew runTargetAudit --args="&lt;phrases.json&gt; [cacheDir]"
 * </pre>
 */
public class RuleTargetAudit
{
	public static void main(String[] args) throws IOException
	{
		if (args.length < 1)
		{
			System.out.println("usage: runTargetAudit --args=\"<phrases.json> [cacheDir]\"");
			return;
		}
		Path phrases = Paths.get(args[0]);
		Path cacheDir = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"),
				".runelite", "jagexcache", "oldschool", "LIVE");

		Set<String> npcNames = new HashSet<>();
		Map<Integer, String> items = new TreeMap<>();
		Set<Integer> npcIds = new HashSet<>();

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);

			Archive npcArchive = configs.getArchive(ConfigType.NPC.getId());
			ArchiveFiles npcFiles = npcArchive.getFiles(storage.loadArchive(npcArchive));
			NpcLoader npcLoader = new NpcLoader();
			for (FSFile file : npcFiles.getFiles())
			{
				NpcDefinition npc = npcLoader.load(file.getFileId(), file.getContents());
				if (npc.name != null && !"null".equals(npc.name))
				{
					npcNames.add(normalise(npc.name));
					npcIds.add(npc.id);
				}
			}

			Archive itemArchive = configs.getArchive(ConfigType.ITEM.getId());
			ArchiveFiles itemFiles = itemArchive.getFiles(storage.loadArchive(itemArchive));
			ItemLoader itemLoader = new ItemLoader();
			for (FSFile file : itemFiles.getFiles())
			{
				ItemDefinition item = itemLoader.load(file.getFileId(), file.getContents());
				if (item.name != null && !"null".equals(item.name))
				{
					items.put(item.id, item.name);
				}
			}
		}

		System.out.printf("cache holds %d named NPCs and %d named items%n%n",
			npcNames.size(), items.size());

		JsonObject file;
		try (Reader reader = Files.newBufferedReader(phrases, StandardCharsets.UTF_8))
		{
			file = new Gson().fromJson(reader, JsonObject.class);
		}

		Map<String, List<String>> namesByRule = new LinkedHashMap<>();
		Map<String, List<Integer>> idsByRule = new LinkedHashMap<>();
		Map<String, List<Integer>> npcIdsByRule = new LinkedHashMap<>();
		for (JsonElement element : file.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			collect(rule.get("id").getAsString(), rule.getAsJsonObject("when"),
				namesByRule, idsByRule, npcIdsByRule);
		}

		int checkedNames = 0;
		List<String> deadNames = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : namesByRule.entrySet())
		{
			for (String name : entry.getValue())
			{
				checkedNames++;
				if (!matchesAny(name, npcNames))
				{
					deadNames.add(String.format("%-28s \"%s\"", entry.getKey(), name));
				}
			}
		}

		int checkedItems = 0;
		List<String> deadItems = new ArrayList<>();
		for (Map.Entry<String, List<Integer>> entry : idsByRule.entrySet())
		{
			for (int id : entry.getValue())
			{
				checkedItems++;
				if (!items.containsKey(id))
				{
					deadItems.add(String.format("%-28s item %d", entry.getKey(), id));
				}
			}
		}

		int checkedNpcIds = 0;
		List<String> deadNpcIds = new ArrayList<>();
		for (Map.Entry<String, List<Integer>> entry : npcIdsByRule.entrySet())
		{
			for (int id : entry.getValue())
			{
				checkedNpcIds++;
				if (!npcIds.contains(id))
				{
					deadNpcIds.add(String.format("%-28s npc %d", entry.getKey(), id));
				}
			}
		}

		report("NPC names", checkedNames, deadNames,
			"these rules watch for an NPC the game does not have, so they can never fire");
		report("item ids", checkedItems, deadItems,
			"these rules watch for an item that does not exist");
		report("NPC ids", checkedNpcIds, deadNpcIds,
			"these rules watch for an NPC id that does not exist");
	}

	private static void report(String what, int checked, List<String> dead, String why)
	{
		System.out.printf("%d %s checked, %d dead%n", checked, what, dead.size());
		if (dead.isEmpty())
		{
			System.out.println("  all resolve\n");
			return;
		}
		System.out.println("  " + why + ":");
		for (String line : dead)
		{
			System.out.println("    " + line);
		}
		System.out.println();
	}

	private static void collect(String ruleId, JsonObject condition,
		Map<String, List<String>> names, Map<String, List<Integer>> items,
		Map<String, List<Integer>> npcIds)
	{
		if (condition == null)
		{
			return;
		}
		JsonElement typeElement = condition.get("type");
		String type = typeElement == null ? "" : typeElement.getAsString().toLowerCase(Locale.ROOT);

		if (type.equals("npcspawn") || type.equals("npcdespawn") || type.equals("npcnearby")
			|| type.equals("npckill"))
		{
			JsonArray list = condition.getAsJsonArray("names");
			if (list != null)
			{
				for (JsonElement name : list)
				{
					names.computeIfAbsent(ruleId, key -> new ArrayList<>())
						.add(name.getAsString());
				}
			}
			JsonArray ids = condition.getAsJsonArray("ids");
			if (ids != null)
			{
				for (JsonElement id : ids)
				{
					npcIds.computeIfAbsent(ruleId, key -> new ArrayList<>())
						.add(id.getAsInt());
				}
			}
		}
		if (type.equals("itemequipped"))
		{
			JsonArray ids = condition.getAsJsonArray("ids");
			if (ids != null)
			{
				for (JsonElement id : ids)
				{
					items.computeIfAbsent(ruleId, key -> new ArrayList<>())
						.add(id.getAsInt());
				}
			}
		}

		JsonArray children = condition.getAsJsonArray("conditions");
		if (children != null)
		{
			for (JsonElement child : children)
			{
				collect(ruleId, child.getAsJsonObject(), names, items, npcIds);
			}
		}
	}

	/** The plugin's own matching: whole-name, case-insensitive, {@code *} wildcards. */
	private static boolean matchesAny(String name, Set<String> known)
	{
		String normalised = normalise(name);
		if (normalised.indexOf('*') < 0)
		{
			return known.contains(normalised);
		}
		StringBuilder pattern = new StringBuilder();
		for (String literal : normalised.split("\\*", -1))
		{
			if (pattern.length() > 0)
			{
				pattern.append(".*");
			}
			pattern.append(Pattern.quote(literal));
		}
		Pattern compiled = Pattern.compile(pattern.toString());
		for (String candidate : known)
		{
			if (compiled.matcher(candidate).matches())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The game pads some names with non-breaking spaces; the plugin strips them,
	 * so this has to as well or the comparison is not the one that will run.
	 * Written as a code point: the literal does not survive being carried
	 * through tooling, which is how it arrived here broken the first time.
	 */
	private static String normalise(String name)
	{
		return name.replace((char) 0x00A0, ' ').trim().toLowerCase(Locale.ROOT);
	}
}
