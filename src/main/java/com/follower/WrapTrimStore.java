package com.follower;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Persists the per-animation wrap trims.
 *
 * <p>Measured values are cheap to recompute but deterministic, so saving them just
 * avoids redoing the work each session. Hand-tuned overrides are the ones that
 * genuinely must survive a restart.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WrapTrimStore
{
	static final String FILE_NAME = "wrap-trims.json";

	static class Saved
	{
		Map<String, Integer> manual = new HashMap<>(),
			measured = new HashMap<>();
	}

	final Gson gson;
	Path file;

	void load(Path dataDir, FollowerEntity follower)
	{
		file = dataDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
			return;

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Saved saved = gson.fromJson(reader, Saved.class);

			if (saved != null)
				follower.restoreTrims(toIntKeys(saved.manual), toIntKeys(saved.measured));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", file, e);
		}
	}

	void save(FollowerEntity follower)
	{
		if (file == null)
			return;

		var saved = new Saved();
		saved.manual = toStringKeys(follower.getWrapTrims());
		saved.measured = toStringKeys(follower.getMeasuredTrims());

		if (saved.manual.isEmpty() && saved.measured.isEmpty())
			return;

		try
		{
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(saved, writer);
			}
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}

	static Map<String, Integer> toStringKeys(Map<Integer, Integer> source)
	{
		Map<String, Integer> out = new HashMap<>();
		for (var entry : source.entrySet())
			out.put(Integer.toString(entry.getKey()), entry.getValue());
		return out;
	}

	static Map<Integer, Integer> toIntKeys(Map<String, Integer> source)
	{
		Map<Integer, Integer> out = new HashMap<>();
		if (source == null)
			return out;
		for (var entry : source.entrySet())
		{
			try
			{
				out.put(Integer.parseInt(entry.getKey()), entry.getValue());
			}
			catch (NumberFormatException ignored)
			{
				// Skip malformed key.
			}
		}
		return out;
	}
}
