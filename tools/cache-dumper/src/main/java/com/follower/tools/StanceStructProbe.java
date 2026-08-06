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
import java.util.TreeSet;
import net.runelite.cache.StructManager;
import net.runelite.cache.definitions.StructDefinition;
import net.runelite.cache.fs.Store;

/**
 * Second half of the stance question: item params turned out to hold combat
 * stats, not animations, so this asks whether the pose sets live in STRUCTs -
 * the cache's reusable property bags, which is where modern content keeps
 * this sort of table.
 *
 * <p>The test needs no assumptions: take the idle/walk/run triples the plugin
 * has genuinely observed and look for a struct carrying two or more of them
 * together. A struct that holds a real stance triple IS the weapon-type
 * record, and finding one would mean every weapon in the game could be
 * resolved without anybody teaching it.
 *
 * <pre>
 *   gradlew runStructProbe --args="&lt;stances.json&gt;"
 * </pre>
 */
public class StanceStructProbe
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

		// Every distinct pose id we have actually seen, and the triples.
		Set<Integer> poseIds = new TreeSet<>();
		Set<String> triples = new TreeSet<>();
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
				triples.add(idle + "/" + walk + "/" + run);
			}
		}
		System.out.printf("%d distinct pose ids across %d distinct stance sets%n",
			poseIds.size(), triples.size());
		System.out.println("stance sets observed: " + triples);

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			StructManager structs = new StructManager(store);
			structs.load();

			int examined = 0;
			int matched = 0;
			for (StructDefinition struct : structs.getStructs().values())
			{
				examined++;
				if (struct.params == null || struct.params.isEmpty())
				{
					continue;
				}
				Set<Integer> found = new HashSet<>();
				for (Object value : struct.params.values())
				{
					if (value instanceof Integer && poseIds.contains((Integer) value))
					{
						found.add((Integer) value);
					}
				}
				// One hit is coincidence - animation ids are just numbers. Two
				// or more from the same small set is a stance record.
				if (found.size() >= 2)
				{
					matched++;
					if (matched <= 15)
					{
						System.out.printf("  struct %-6d holds pose ids %s%n      params=%s%n",
							struct.id, new TreeSet<>(found), struct.params);
					}
				}
			}

			System.out.printf("%n%d structs examined, %d hold two or more observed pose ids%n",
				examined, matched);
			if (matched == 0)
			{
				System.out.println();
				System.out.println("Structs do not carry pose sets either. Weapon animations are");
				System.out.println("resolved by the client's own scripts at equip time, which the");
				System.out.println("cache does not expose as data - so observation stays the only");
				System.out.println("honest source.");
			}
		}
	}
}
