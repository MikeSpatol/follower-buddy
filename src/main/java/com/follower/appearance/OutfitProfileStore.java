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

	/** The bare body every install starts from, and the fallback selection. */
	public static final String DEFAULT_PROFILE = "Default follower";

	/**
	 * The three combat-style looks, which double as the thrall-mode outfits,
	 * plus the bare body. These are complete characters - gear, body kits,
	 * body type and colours - so a first install has three finished thralls to
	 * summon rather than three sets of armour on a default mannequin.
	 */
	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
	static
	{
		// An empty outfit is the plain default body with no clothing.
		DEFAULTS.put(DEFAULT_PROFILE, "");
		DEFAULTS.put("Melee",
			"HEAD=item:1149,WEAPON=item:21009,TORSO=item:3140,SHIELD=item:21895,ARMS=kit:26,"
				+ "LEGS=item:4087,HAIR=kit:0,HANDS=kit:33,BOOTS=kit:42,JAW=kit:10");
		DEFAULTS.put("Ranged",
			"HEAD=item:2581,WEAPON=item:841,TORSO=item:12596,ARMS=kit:98,LEGS=item:23249,"
				+ "HAIR=kit:128,HANDS=kit:69,BOOTS=kit:79,JAW=kit:296,gender=female");
		DEFAULTS.put("Magic",
			"HEAD=item:7394,AMULET=item:10366,WEAPON=item:1383,TORSO=item:7390,SHIELD=item:6889,"
				+ "ARMS=kit:98,LEGS=item:7386,HAIR=kit:143,HANDS=kit:69,BOOTS=item:2579,"
				+ "JAW=kit:296,gender=female");
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

	/**
	 * Whether this profile is one of the seeded combat-style looks. Those
	 * double as the thrall-mode outfits, which are configured BY NAME, so
	 * deleting one would silently break thrall dress; they stay editable.
	 */
	public boolean isProtected(String name)
	{
		return name != null && DEFAULTS.containsKey(name.trim());
	}

	/**
	 * The first profile the user made themselves, or {@link #DEFAULT_PROFILE}
	 * when they have not made any - what a fresh session should be wearing.
	 */
	public String firstUserProfile()
	{
		for (String name : profiles.keySet())
		{
			if (!DEFAULTS.containsKey(name))
			{
				return name;
			}
		}
		return DEFAULT_PROFILE;
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
