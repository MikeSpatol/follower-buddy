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
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Store;

/**
 * Works out whether a weapon's animations can be READ from the cache instead
 * of learned by watching players wield it.
 *
 * <p>The plugin currently learns stances by observation, which means a weapon
 * nobody has been seen holding falls back to unarmed. The game itself clearly
 * does not need to watch anyone, so the data must be in the cache somewhere -
 * the likeliest home being item params (opcode 249), a free-form int-keyed
 * map. This correlates every learned stance against every param of that item:
 * if some param consistently holds the idle id, that param IS the idle pose
 * and every weapon in the game can be resolved without teaching.
 *
 * <pre>
 *   gradlew runProbe --args="&lt;stances.json&gt; [cacheDir]"
 * </pre>
 */
public class StanceParamProbe
{
	/** The stance fields we would like to resolve, in the JSON's own names. */
	private static final String[] FIELDS = {
		"idle", "walk", "run", "walkBack", "walkLeft", "walkRight",
		"turnLeft", "turnRight", "attack",
	};

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
		System.out.printf("%d learned weapons to correlate%n", stances.size());

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			ItemManager items = new ItemManager(store);
			items.load();

			Map<Integer, ItemDefinition> byId = new HashMap<>();
			for (ItemDefinition item : items.getItems())
			{
				if (item != null)
				{
					byId.put(item.id, item);
				}
			}

			// paramKey -> field -> how many weapons agree
			Map<Integer, Map<String, Integer>> hits = new TreeMap<>();
			Map<Integer, Integer> paramSeen = new TreeMap<>();
			int withParams = 0;
			int examined = 0;

			for (Map.Entry<String, JsonElement> entry : stances.entrySet())
			{
				int weaponId;
				try
				{
					weaponId = Integer.parseInt(entry.getKey());
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				if (weaponId <= 0)
				{
					continue;   // the unarmed baseline is not an item
				}
				ItemDefinition item = byId.get(weaponId);
				if (item == null)
				{
					continue;
				}
				examined++;
				Map<Integer, Object> params = item.params;
				if (params == null || params.isEmpty())
				{
					continue;
				}
				withParams++;

				JsonObject stance = entry.getValue().getAsJsonObject();
				for (Map.Entry<Integer, Object> param : params.entrySet())
				{
					paramSeen.merge(param.getKey(), 1, Integer::sum);
					if (!(param.getValue() instanceof Integer))
					{
						continue;
					}
					int value = (Integer) param.getValue();
					for (String field : FIELDS)
					{
						if (!stance.has(field))
						{
							continue;
						}
						int want = stance.get(field).getAsInt();
						if (want > 0 && want == value)
						{
							hits.computeIfAbsent(param.getKey(), k -> new TreeMap<>())
								.merge(field, 1, Integer::sum);
						}
					}
				}
			}

			System.out.printf("%d weapons found in the cache, %d of them carry params%n",
				examined, withParams);

			if (hits.isEmpty())
			{
				System.out.println();
				System.out.println("NO param on any weapon matched any learned animation id.");
				System.out.println("Weapon poses are not stored in item params - they live in");
				System.out.println("the weapon-type structs the combat scripts read, which is a");
				System.out.println("different lookup entirely.");
			}
			else
			{
				System.out.println();
				System.out.println("param -> field agreement (param seen on N weapons):");
				for (Map.Entry<Integer, Map<String, Integer>> hit : hits.entrySet())
				{
					System.out.printf("  param %-6d seen %-4d  %s%n",
						hit.getKey(), paramSeen.get(hit.getKey()), hit.getValue());
				}
			}

			// Stances are clearly shared between weapons - a harpoon and a mace
			// use the same one - so the real question is which cache field
			// names that GROUP. Whatever predicts it lets one observation
			// cover every weapon of that class.
			System.out.println();
			System.out.println("which field predicts the stance group?");

			Map<String, Map<String, String>> byCandidate = new TreeMap<>();
			Map<String, Integer> conflicts = new TreeMap<>();
			Map<String, Integer> covered = new TreeMap<>();

			for (Map.Entry<String, JsonElement> entry : stances.entrySet())
			{
				int weaponId;
				try
				{
					weaponId = Integer.parseInt(entry.getKey());
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				ItemDefinition item = byId.get(weaponId);
				if (item == null || weaponId <= 0)
				{
					continue;
				}
				JsonObject stance = entry.getValue().getAsJsonObject();
				String signature = stance.get("idle").getAsInt() + "/"
					+ stance.get("walk").getAsInt() + "/" + stance.get("run").getAsInt();

				Map<String, String> candidates = new TreeMap<>();
				candidates.put("category", Integer.toString(item.category));
				if (item.params != null)
				{
					for (Map.Entry<Integer, Object> param : item.params.entrySet())
					{
						if (param.getValue() instanceof Integer)
						{
							candidates.put("param" + param.getKey(), param.getValue().toString());
						}
					}
				}

				for (Map.Entry<String, String> candidate : candidates.entrySet())
				{
					String key = candidate.getKey() + "=" + candidate.getValue();
					Map<String, String> seen = byCandidate
						.computeIfAbsent(candidate.getKey(), k -> new TreeMap<>());
					String previous = seen.putIfAbsent(key, signature);
					covered.merge(candidate.getKey(), 1, Integer::sum);
					if (previous != null && !previous.equals(signature))
					{
						conflicts.merge(candidate.getKey(), 1, Integer::sum);
					}
				}
			}

			for (Map.Entry<String, Map<String, String>> candidate : byCandidate.entrySet())
			{
				String name = candidate.getKey();
				int seen = covered.getOrDefault(name, 0);
				int bad = conflicts.getOrDefault(name, 0);
				if (seen < 30)
				{
					continue;   // too rare to conclude anything from
				}
				System.out.printf("  %-10s covers %-4d weapons in %-4d groups, %d disagreements%s%n",
					name, seen, candidate.getValue().size(), bad,
					bad == 0 ? "   <-- PREDICTS THE STANCE" : "");
			}
		}
	}

}
