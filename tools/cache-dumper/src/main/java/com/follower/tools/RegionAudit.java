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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.cache.AreaManager;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.WorldMapElementDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;

/**
 * Audits the location rules in phrases.json against the game's own world map.
 *
 * <p>The area rules were written with region ids computed from documented
 * coordinates, which is a guess wherever the documentation is loose. The cache
 * settles it: the world map's labels are {@link AreaDefinition}s carrying the
 * text the game draws ("Lumbridge", "Prifddinas"), and each is PLACED by a
 * {@link WorldMapElementDefinition} at a world position. Name plus position
 * gives the true region id for every named place in the game, with no walking
 * and nothing assumed.
 *
 * <pre>
 *   gradlew runAudit --args="&lt;phrases.json&gt; [cacheDir]"
 * </pre>
 *
 * <p>For each rule it prints the regions the game says that place occupies,
 * and flags any rule whose ids do not include them.
 */
public class RegionAudit
{
	/** Region id from world coordinates, exactly as the client computes it. */
	private static int regionOf(int x, int y)
	{
		return (x >> 6) * 256 + (y >> 6);
	}

	public static void main(String[] args) throws IOException
	{
		Path phrases = args.length > 0
			? Paths.get(args[0])
			: Paths.get(System.getProperty("user.home"), ".runelite", "follower", "phrases.json");
		Path cacheDir = args.length > 1
			? Paths.get(args[1])
			: Paths.get(System.getProperty("user.home"), ".runelite", "jagexcache", "oldschool", "LIVE");

		if (!Files.isRegularFile(phrases))
		{
			System.err.println("phrases.json not found: " + phrases);
			System.exit(1);
		}

		// name (lowercased) -> every region the map labels with it
		Map<String, TreeSet<Integer>> mapRegions = new HashMap<>();
		Map<String, TreeSet<String>> mapCoords = new HashMap<>();
		// the reverse: region -> every label the map draws inside it
		Map<Integer, TreeSet<String>> labelsByRegion = new HashMap<>();

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();

			AreaManager areas = new AreaManager(store);
			areas.load();
			Map<Integer, AreaDefinition> byId = new HashMap<>();
			for (AreaDefinition area : areas.getAreas())
			{
				byId.put(area.getId(), area);
			}

			WorldMapManager worldMap = new WorldMapManager(store);
			worldMap.load();

			for (WorldMapElementDefinition element : worldMap.getElements())
			{
				AreaDefinition area = byId.get(element.getAreaDefinitionId());
				Position at = element.getWorldPosition();
				if (area == null || area.getName() == null || at == null)
				{
					continue;
				}
				String key = normalise(area.getName());
				int region = regionOf(at.getX(), at.getY());
				mapRegions.computeIfAbsent(key, k -> new TreeSet<>()).add(region);
				mapCoords.computeIfAbsent(key, k -> new TreeSet<>())
					.add(at.getX() + "," + at.getY());
				labelsByRegion.computeIfAbsent(region, k -> new TreeSet<>()).add(key);
			}
		}

		System.out.printf("World map carries %d distinct labelled place names%n", mapRegions.size());

		JsonObject root;
		try (Reader reader = Files.newBufferedReader(phrases, StandardCharsets.UTF_8))
		{
			root = new Gson().fromJson(reader, JsonObject.class);
		}

		int checked = 0;
		int confirmed = 0;
		List<String> problems = new ArrayList<>();

		for (JsonElement element : root.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			if (!"area".equals(optString(rule, "group")))
			{
				continue;
			}
			JsonObject when = rule.getAsJsonObject("when");
			if (when == null || !when.has("regions"))
			{
				continue;
			}

			String id = optString(rule, "id");
			List<Integer> ruleRegions = new ArrayList<>();
			for (JsonElement r : when.getAsJsonArray("regions"))
			{
				ruleRegions.add(r.getAsInt());
			}

			// "area-tree-gnome-stronghold" -> "tree gnome stronghold"
			String place = normalise(id.startsWith("area-") ? id.substring(5).replace('-', ' ') : id);
			String label = matchLabel(mapRegions.keySet(), place);
			checked++;

			if (label == null)
			{
				problems.add(String.format("%-24s NO MAP LABEL%s%s",
					id, suggest(mapRegions, place), inside(labelsByRegion, ruleRegions)));
				continue;
			}

			TreeSet<Integer> truth = mapRegions.get(label);
			// A label marks where the map draws its TEXT, near the middle of
			// the place - a town spans several regions, so a rule region one
			// region away is the same town, not an error. Only a rule that
			// lands well clear of every label is actually suspect.
			int best = Integer.MAX_VALUE;
			for (int region : ruleRegions)
			{
				for (int labelled : truth)
				{
					best = Math.min(best, regionDistance(region, labelled));
				}
			}

			if (best == 0)
			{
				confirmed++;
			}
			else if (best <= 1)
			{
				confirmed++;
				System.out.printf("  %-24s adjacent to \"%s\" %s - same place, fine%n",
					id, label, truth);
			}
			else
			{
				problems.add(String.format("%-24s rule %s is %d regions from \"%s\" %s at %s%s",
					id, ruleRegions, best, label, truth, mapCoords.get(label),
					inside(labelsByRegion, ruleRegions)));
			}
		}

		System.out.printf("%n%d location rules checked, %d confirmed against the map%n",
			checked, confirmed);
		if (problems.isEmpty())
		{
			System.out.println("No mismatches.");
			return;
		}
		System.out.printf("%n%d need attention:%n", problems.size());
		for (String problem : problems)
		{
			System.out.println("  " + problem);
		}
	}

	/**
	 * Map labels are drawn text: they carry {@code <br>} line breaks and
	 * punctuation ("Seers'<br>Village"). Flatten to bare lowercase words so a
	 * rule id can be compared to them.
	 */
	private static String normalise(String text)
	{
		return text.toLowerCase()
			.replace("<br>", " ")
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
	}

	/**
	 * What the map actually labels INSIDE the rule's own regions - the
	 * decisive check for underground places, whose surface entrance label
	 * sits regions away from the area the player is standing in.
	 */
	private static String inside(Map<Integer, TreeSet<String>> labelsByRegion, List<Integer> regions)
	{
		TreeSet<String> found = new TreeSet<>();
		for (int region : regions)
		{
			TreeSet<String> here = labelsByRegion.get(region);
			if (here != null)
			{
				found.addAll(here);
			}
		}
		return found.isEmpty()
			? "\n" + " ".repeat(29) + "nothing labelled inside the rule's own regions"
			: "\n" + " ".repeat(29) + "INSIDE those regions the map labels: " + found;
	}

	/** Labels sharing the place's first word, to steer a manual check. */
	private static String suggest(Map<String, TreeSet<Integer>> mapRegions, String place)
	{
		String first = place.split(" ")[0];
		List<String> near = new ArrayList<>();
		for (Map.Entry<String, TreeSet<Integer>> entry : mapRegions.entrySet())
		{
			if (entry.getKey().contains(first))
			{
				near.add("\"" + entry.getKey() + "\" " + entry.getValue());
			}
			if (near.size() == 3)
			{
				break;
			}
		}
		return near.isEmpty() ? " - verify this one in game" : " - map has " + String.join(", ", near);
	}

	/** Chebyshev distance in regions: 0 same, 1 touching, more is far apart. */
	private static int regionDistance(int a, int b)
	{
		int dx = Math.abs((a / 256) - (b / 256));
		int dy = Math.abs((a % 256) - (b % 256));
		return Math.max(dx, dy);
	}

	/**
	 * The map label that best names a rule's place: exact first, then the
	 * most specific label containing the whole place name, then the longest
	 * label the place name contains ("east ardougne" -> "Ardougne").
	 *
	 * <p>Short labels are excluded from that last case. The map has one and
	 * two character labels, and letting those match by containment matched
	 * nearly every rule to the same stray marker.
	 */
	private static String matchLabel(java.util.Set<String> labels, String place)
	{
		if (labels.contains(place))
		{
			return place;
		}
		String best = null;
		for (String label : labels)
		{
			if (label.contains(place) && (best == null || label.length() < best.length()))
			{
				best = label;
			}
		}
		if (best != null)
		{
			return best;
		}
		for (String label : labels)
		{
			if (label.length() >= 5 && place.contains(label)
				&& (best == null || label.length() > best.length()))
			{
				best = label;
			}
		}
		return best;
	}

	private static String optString(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsString() : null;
	}
}
