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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.definitions.loaders.ObjectLoader;
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
 * <p>The plugin's own Java keeps name lists with the same risk - the superior
 * slayer monsters, the stall guards, the bank, the landmarks - and some of
 * them match exactly, capitals and all, so a name one capital out is just as
 * dead. Those are read from the plugin's source, not copied here, so this
 * checks the list that ships. Each is matched the way its call site matches.
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
		Map<String, List<Integer>> npcsByName = new TreeMap<>();
		Map<String, List<ObjectDefinition>> objectsByName = new TreeMap<>();

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
					npcsByName.computeIfAbsent(npc.name, key -> new ArrayList<>()).add(npc.id);
				}
			}

			Archive objectArchive = configs.getArchive(ConfigType.OBJECT.getId());
			ArchiveFiles objectFiles = objectArchive.getFiles(storage.loadArchive(objectArchive));
			ObjectLoader objectLoader = new ObjectLoader();
			for (FSFile file : objectFiles.getFiles())
			{
				ObjectDefinition object = objectLoader.load(file.getFileId(), file.getContents());
				if (object.getName() != null && !"null".equals(object.getName()))
				{
					objectsByName.computeIfAbsent(object.getName(), key -> new ArrayList<>())
						.add(object);
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

		String source = System.getProperty("follower.source");
		if (source == null)
		{
			System.out.println("the plugin's own lists were NOT audited: run this through"
				+ " gradlew runTargetAudit, which says where the plugin source is");
			return;
		}
		// Each list is matched as its call site matches it: SpectateController
		// isSuperior and SkillingController's stall guard (nearestNpc) exactly, BANK_NAMES
		// and Noticing.isLandmark in any case. Change one, change both.
		Path java = Paths.get(source, "com", "follower", "follower");
		List<String> deadLists = new ArrayList<>();
		int checkedLists = 0;
		checkedLists += auditNpcs("SpectateController.SUPERIOR_NAMES",
			literals(java.resolve("SpectateController.java"), "SUPERIOR_NAMES"),
			npcsByName, deadLists);
		checkedLists += auditNpcs("SkillingController.GUARD_NAMES",
			literals(java.resolve("SkillingController.java"), "GUARD_NAMES"),
			npcsByName, deadLists);
		checkedLists += auditObjects("SkillingController.BANK_NAMES",
			literals(java.resolve("SkillingController.java"), "BANK_NAMES"),
			objectsByName, deadLists);
		checkedLists += auditObjects("Noticing.LANDMARKS",
			literals(java.resolve("Noticing.java"), "LANDMARKS"),
			objectsByName, deadLists);
		report("plugin list names", checkedLists, deadLists,
			"the plugin looks for these and can never find them");
	}

	/**
	 * The quoted names in one field's declaration in the plugin source, up to
	 * its semicolon. Finding none is an error, never an empty pass.
	 */
	private static List<String> literals(Path source, String field) throws IOException
	{
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
		int start = text.indexOf(field + " =");
		if (start < 0)
		{
			throw new IllegalStateException(field + " is no longer declared in " + source);
		}
		List<String> names = new ArrayList<>();
		Matcher quoted = Pattern.compile("\"([^\"]*)\"")
			.matcher(text.substring(start, text.indexOf(';', start)));
		while (quoted.find())
		{
			names.add(quoted.group(1));
		}
		if (names.isEmpty())
		{
			throw new IllegalStateException(field + " holds no quoted names in " + source);
		}
		return names;
	}

	/**
	 * Names the plugin compares with equals against NPC.getName(): exact,
	 * capitals and all. The game does not always capitalise one name the same
	 * way twice, so the other spellings the list does not carry are shown:
	 * those NPCs are missed, which may or may not be wanted.
	 */
	private static int auditNpcs(String list, List<String> names,
		Map<String, List<Integer>> npcsByName, List<String> dead)
	{
		System.out.println(list + " (NPC names, exact case):");
		for (String name : names)
		{
			List<String> others = new ArrayList<>();
			for (Map.Entry<String, List<Integer>> entry : npcsByName.entrySet())
			{
				if (entry.getKey().equalsIgnoreCase(name) && !names.contains(entry.getKey()))
				{
					others.add("\"" + entry.getKey() + "\" " + ids(entry.getValue()));
				}
			}
			String unmatched = others.isEmpty() ? "" : "; not matched: " + String.join(", ", others);
			List<Integer> found = npcsByName.get(name);
			if (found != null)
			{
				System.out.printf("  %-32s found %s%s%n", name, ids(found), unmatched);
				continue;
			}
			System.out.printf("  %-32s NOT IN THE GAME%s%n", name, unmatched);
			dead.add(String.format("%-36s \"%s\"", list, name));
		}
		System.out.println();
		return names.size();
	}

	private static String ids(List<Integer> ids)
	{
		return ids.size() <= 8 ? ids.toString() : "(" + ids.size() + " ids)";
	}

	/**
	 * Names the plugin compares against a scene object's name in any case.
	 * Both scans read only the tile's game objects, so a name the cache only
	 * models as a wall, a wall decoration or a ground decoration is never seen
	 * by them either.
	 */
	private static int auditObjects(String list, List<String> names,
		Map<String, List<ObjectDefinition>> objectsByName, List<String> dead)
	{
		System.out.println(list + " (object names, any case, game objects only):");
		for (String name : names)
		{
			int matched = 0;
			int gameObjects = 0;
			Set<String> shapes = new TreeSet<>();
			Set<String> near = new TreeSet<>();
			for (Map.Entry<String, List<ObjectDefinition>> entry : objectsByName.entrySet())
			{
				String candidate = entry.getKey();
				if (!candidate.equalsIgnoreCase(name))
				{
					if (candidate.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))
					{
						near.add("\"" + candidate + "\"");
					}
					continue;
				}
				for (ObjectDefinition object : entry.getValue())
				{
					String shape = shape(object.getObjectTypes());
					matched++;
					shapes.add(shape);
					if (shape.contains("game object"))
					{
						gameObjects++;
					}
				}
			}
			if (gameObjects > 0)
			{
				System.out.printf("  %-32s found, %d of %d as game objects%n",
					name, gameObjects, matched);
				continue;
			}
			if (matched > 0)
			{
				System.out.printf("  %-32s NEVER SEEN BY THE SCAN: %d modelled only as %s%n",
					name, matched, String.join(", ", shapes));
			}
			else
			{
				List<String> some = new ArrayList<>(near).subList(0, Math.min(8, near.size()));
				System.out.printf("  %-32s NOT IN THE GAME%s%n", name, near.isEmpty() ? ""
					: ", nearest " + String.join(", ", some) + (near.size() > 8 ? " ..." : ""));
			}
			dead.add(String.format("%-36s \"%s\"", list, name));
		}
		System.out.println();
		return names.size();
	}

	/**
	 * Where an object can stand, from the shapes the cache has models for. No
	 * shape list means the one default model, a centrepiece's. Walls are 0-3,
	 * wall decorations 4-8 (all drawn from shape 4), game objects 9-21, ground
	 * decorations 22.
	 */
	private static String shape(int[] types)
	{
		if (types == null)
		{
			return "game object";
		}
		Set<String> kinds = new TreeSet<>();
		for (int type : types)
		{
			kinds.add(type <= 3 ? "wall" : type <= 8 ? "wall decoration"
				: type <= 21 ? "game object" : "ground decoration");
		}
		return String.join("/", kinds);
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
