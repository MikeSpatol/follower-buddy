package com.follower;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import java.util.function.Consumer;

/**
 * Named outfit profiles: profile name -> the same outfit string the config
 * panel edits. Kept in each RuneLite profile's own settings, saved on every
 * mutation, insertion order preserved so the dropdown lists profiles in the
 * order they were created. The outfit-profiles.json earlier versions shared
 * between profiles is only read, as where a profile's list starts.
 *
 * <p>Synchronized: the panel's New profile and Delete run on the event
 * thread, the autosave after an outfit edit on the client thread (QA3).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class OutfitProfileStore
{
	static final String FILE_NAME = "outfit-profiles.json";

	final Gson gson;

	/** Where the list is kept: the RuneLite profile's own setting. */
	Consumer<String> writer = json -> { };
	Map<String, String> profiles = new LinkedHashMap<>();

	/** The bare body every install starts from, and the fallback selection. */
	static final String DEFAULT_PROFILE = "Default follower";

	/**
	 * The three combat-style looks, which double as the thrall-mode outfits,
	 * plus the bare body. These are complete characters - gear, body kits,
	 * body type and colours - so a first install has three finished thralls to
	 * summon rather than three sets of armour on a default mannequin.
	 */
	static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
	static
	{
		// An empty outfit is the plain default body with no clothing.
		DEFAULTS.put(DEFAULT_PROFILE, "");
		for (var row : Resources.rows("outfits.tsv"))
			DEFAULTS.put(row[0], row[1]);
	}

	/** Without a RuneLite profile to keep them in: the shared list and the defaults, nothing written. */
	void load(Path dataDir)
	{
		load(dataDir, null, null, null, null);
	}

	/**
	 * Loads this RuneLite profile's own outfit profiles.
	 *
	 * <p>Each RuneLite profile keeps its list in its own settings,
	 * {@code stored}, written back through {@code writer}. Up to this version
	 * one list in outfit-profiles.json was shared by every profile, every
	 * outfit change saved into the selected name, and two profiles on the same
	 * name wrote over each other's outfit. A profile the old version ran, with
	 * no list of its own yet, starts from that shared file as it stands, which
	 * from then on is only read, and the entry it has selected takes the outfit
	 * it is wearing: another profile may already have written over the shared
	 * one. Any other profile with no list starts from the defaults.
	 */
	synchronized void load(Path dataDir, String stored, String active, String worn,
		Consumer<String> writer)
	{
		this.writer = writer == null ? json -> { } : writer;
		profiles = new LinkedHashMap<>();
		Map<String, String> own = stored == null || stored.trim().isEmpty() ? null : parse(stored, "the profile's own list");
		boolean first = own == null;
		// Only a profile the old version ran has the shared list to start
		// from: that version always recorded the outfit it had selected. One
		// reset to defaults, or made since, starts from the defaults rather
		// than from the shared list as it stood on the day of the upgrade.
		boolean ranTheOldVersion = active != null && !active.trim().isEmpty();
		Map<String, String> read = !first ? own : ranTheOldVersion ? readShared(dataDir) : null;
		boolean fromShared = first && read != null;
		if (read != null)
			profiles = read;
		boolean changed = first;
		if (first && active != null && worn != null && profiles.containsKey(active.trim()))
			profiles.put(active.trim(), worn);

		// The style profiles double as the thrall-mode outfits, so a missing
		// one is restored on load. Same-name edits are kept; only absence heals.
		for (var entry : DEFAULTS.entrySet())
		{
			if (!profiles.containsKey(entry.getKey()))
			{
				profiles.put(entry.getKey(), entry.getValue());
				changed = true;
			}
		}
		if (changed)
			save();
	}

	/** The list every profile shared before this version, or null. */
	Map<String, String> readShared(Path dataDir)
	{
		var file = dataDir == null ? null : dataDir.resolve(FILE_NAME);
		if (file == null || !Files.isRegularFile(file))
			return null;
		try
		{
			return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
		}
		catch (IOException e)
		{
			log.warn("Could not read {}", file, e);
			return null;
		}
	}

	Map<String, String> parse(String json, String what)
	{
		try
		{
			return gson.fromJson(json, new TypeToken<LinkedHashMap<String, String>>() { }.getType());
		}
		catch (RuntimeException e)
		{
			log.warn("Could not read {}", what, e);
			return null;
		}
	}

	synchronized List<String> names()
	{
		return new ArrayList<>(profiles.keySet());
	}

	/**
	 * Whether this profile is one of the seeded combat-style looks. Those
	 * double as the thrall-mode outfits, which are configured BY NAME, so
	 * deleting one would silently break thrall dress; they stay editable.
	 */
	boolean isProtected(String name)
	{
		return name != null && DEFAULTS.containsKey(name.trim());
	}

	/**
	 * The first profile the user made themselves, or {@link #DEFAULT_PROFILE}
	 * when they have not made any - what a fresh session should be wearing.
	 */
	synchronized String firstUserProfile()
	{
		for (String name : profiles.keySet())
		{
			if (!DEFAULTS.containsKey(name))
				return name;
		}
		return DEFAULT_PROFILE;
	}

	/** The stored outfit string, or null when no profile has that name. */
	synchronized String get(String name)
	{
		return name == null ? null : profiles.get(name.trim());
	}

	synchronized void put(String name, String outfit)
	{
		profiles.put(name.trim(), outfit == null ? "" : outfit);
		save();
	}

	/** Returns true when a profile existed and was removed. */
	synchronized boolean remove(String name)
	{
		boolean removed = name != null && profiles.remove(name.trim()) != null;
		if (removed)
			save();
		return removed;
	}

	void save()
	{
		writer.accept(gson.newBuilder().disableHtmlEscaping().create().toJson(profiles));
	}
}
