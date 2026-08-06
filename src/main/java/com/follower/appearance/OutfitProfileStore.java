package com.follower.appearance;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Named outfit profiles: profile name -> the same outfit string the config
 * panel edits. Stored as outfit-profiles.json in the plugin's data directory,
 * saved on every mutation, insertion order preserved so the dropdown lists
 * profiles in the order they were created.
 */
@Slf4j
@Singleton
public class OutfitProfileStore
{
	private static final String FILE_NAME = "outfit-profiles.json";

	private final Gson gson;

	private Path file;
	private Map<String, String> profiles = new LinkedHashMap<>();

	@Inject
	public OutfitProfileStore(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * The three combat-style looks, also the thrall-mode defaults. Item ids
	 * verified against the cache dump: rune set + rune scimitar, green
	 * d'hide + shortbow, blue wizard robes + staff of water.
	 */
	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
	static
	{
		DEFAULTS.put("Melee",
			"HEAD=item:1163, TORSO=item:1127, LEGS=item:1079, SHIELD=item:1201, WEAPON=item:1333");
		DEFAULTS.put("Ranged",
			"HEAD=item:1167, TORSO=item:1135, LEGS=item:1099, HANDS=item:1065, BOOTS=item:1061, WEAPON=item:841");
		DEFAULTS.put("Magic",
			"HEAD=item:579, TORSO=item:577, LEGS=item:1011, WEAPON=item:1383");
	}

	public void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		profiles = new LinkedHashMap<>();
		if (Files.isRegularFile(file))
		{
			try
			{
				String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				Map<String, String> parsed = gson.fromJson(json,
					new TypeToken<LinkedHashMap<String, String>>() { }.getType());
				if (parsed != null)
				{
					profiles = parsed;
				}
				log.info("Loaded {} outfit profiles", profiles.size());
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not read {}", file, e);
			}
		}

		// The style profiles double as the thrall-mode outfits, so a missing
		// one is restored on load. Same-name edits are kept; only absence heals.
		boolean seeded = false;
		for (Map.Entry<String, String> entry : DEFAULTS.entrySet())
		{
			if (!profiles.containsKey(entry.getKey()))
			{
				profiles.put(entry.getKey(), entry.getValue());
				seeded = true;
			}
		}
		if (seeded)
		{
			save();
		}
	}

	public List<String> names()
	{
		return new ArrayList<>(profiles.keySet());
	}

	/** The stored outfit string, or null when no profile has that name. */
	public String get(String name)
	{
		return name == null ? null : profiles.get(name.trim());
	}

	public void put(String name, String outfit)
	{
		profiles.put(name.trim(), outfit == null ? "" : outfit);
		save();
	}

	/** Returns true when a profile existed and was removed. */
	public boolean remove(String name)
	{
		boolean removed = name != null && profiles.remove(name.trim()) != null;
		if (removed)
		{
			save();
		}
		return removed;
	}

	private void save()
	{
		if (file == null)
		{
			return;
		}
		try
		{
			String json = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create()
				.toJson(profiles);
			Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}
}
