// public:file chronicle
package com.follower.chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the chronicle's pages: {@code chronicle.json} in the data directory
 * when there is one, else the bundled {@code default-chronicle.json}.
 *
 * <p>Read-only data, the same shape of override as the fonts: a copy in the
 * data directory replaces the bundled file outright. Nothing here throws.
 * A missing, empty, truncated or nonsense file leaves the chronicle empty
 * and says so in {@link #getStatus()}, because a throw in startUp disables
 * the whole plugin, and a lore book is not worth that.
 */
@Slf4j
@Singleton
public class ChronicleLoader
{
	public static final String FILE_NAME = "chronicle.json";
	public static final String BUNDLED = "/com/follower/default-chronicle.json";

	private final Gson gson;
	private final Map<String, ChronicleEntry> entries = new LinkedHashMap<>();

	/** Problems found in the last read, one line each; empty when clean. */
	@Getter
	private final List<String> errors = new ArrayList<>();

	@Getter
	private String status = "not loaded";

	@Inject
	public ChronicleLoader(Gson gson)
	{
		this.gson = gson;
	}

	public void load(Path dataDir)
	{
		Path file = dataDir == null ? null : dataDir.resolve(FILE_NAME);
		if (file != null && Files.isRegularFile(file))
		{
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
			{
				if (read(reader, file.toString()))
				{
					return;
				}
			}
			catch (IOException | RuntimeException e)
			{
				status = "failed to read " + FILE_NAME;
				log.warn("Could not load {}", file, e);
			}
		}

		try (InputStream in = ChronicleLoader.class.getResourceAsStream(BUNDLED))
		{
			if (in == null)
			{
				status = "no chronicle bundled or on disk";
				return;
			}
			read(new InputStreamReader(in, StandardCharsets.UTF_8), "bundled resource");
		}
		catch (IOException | RuntimeException e)
		{
			status = "failed to read the bundled chronicle";
			log.warn("Could not load the bundled chronicle", e);
		}
	}

	private boolean read(Reader reader, String source)
	{
		ChronicleFile file;
		try
		{
			file = gson.fromJson(reader, ChronicleFile.class);
		}
		catch (JsonParseException e)
		{
			status = FILE_NAME + " is not valid JSON (" + source + ")";
			log.warn("Chronicle at {} did not parse", source, e);
			return false;
		}
		if (file == null || file.entries == null)
		{
			status = FILE_NAME + " is empty (" + source + ")";
			return false;
		}

		errors.clear();
		if (file.version != ChronicleFile.SUPPORTED_VERSION)
		{
			errors.add("version " + file.version + " is not " + ChronicleFile.SUPPORTED_VERSION);
		}
		Map<String, ChronicleEntry> read = new LinkedHashMap<>();
		for (ChronicleEntry entry : file.entries)
		{
			if (entry == null || entry.id == null || entry.id.isEmpty())
			{
				errors.add("an entry has no id");
				continue;
			}
			if (read.containsKey(entry.id))
			{
				errors.add("duplicate id " + entry.id);
				continue;
			}
			if (!ChronicleEntry.KINDS.contains(entry.kindOrEmpty()))
			{
				errors.add(entry.id + ": kind " + entry.kind + " is not one of " + ChronicleEntry.KINDS);
				continue;
			}
			if (entry.name == null || entry.name.isEmpty())
			{
				errors.add(entry.id + ": no name");
				continue;
			}
			read.put(entry.id, entry);
		}
		entries.clear();
		entries.putAll(read);
		status = entries.size() + " pages (" + source + ")"
			+ (errors.isEmpty() ? "" : ", " + errors.size() + " problem(s)");
		log.info("Loaded chronicle: {}", status); // public:cut logs
		return true;
	}

	/** Every page, in file order. */
	public List<ChronicleEntry> getEntries()
	{
		return Collections.unmodifiableList(new ArrayList<>(entries.values()));
	}

	/** The pages of one kind, in file order. */
	public List<ChronicleEntry> ofKind(String kind)
	{
		List<ChronicleEntry> out = new ArrayList<>();
		for (ChronicleEntry entry : entries.values())
		{
			if (entry.kindOrEmpty().equals(kind))
			{
				out.add(entry);
			}
		}
		return out;
	}

	public ChronicleEntry get(String id)
	{
		return entries.get(id);
	}

	public boolean isLoaded()
	{
		return !entries.isEmpty();
	}
}
