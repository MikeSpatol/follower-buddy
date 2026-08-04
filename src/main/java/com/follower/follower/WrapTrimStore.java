package com.follower.follower;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists the per-animation wrap trims.
 *
 * <p>Measured values are cheap to recompute but deterministic, so saving them just
 * avoids redoing the work each session. Hand-tuned overrides are the ones that
 * genuinely must survive a restart.
 */
@Slf4j
@Singleton
public class WrapTrimStore
{
	public static final String FILE_NAME = "wrap-trims.json";

	public static class Saved
	{
		public Map<String, Integer> manual = new HashMap<>();
		public Map<String, Integer> measured = new HashMap<>();
	}

	private final Gson gson;
	private Path file;

	@Inject
	public WrapTrimStore(Gson gson)
	{
		this.gson = gson;
	}

	public void load(Path dataDir, FollowerEntity follower)
	{
		file = dataDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
		{
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Saved saved = gson.fromJson(reader, new TypeToken<Saved>()
			{
			}.getType());

			if (saved != null)
			{
				follower.restoreTrims(toIntKeys(saved.manual), toIntKeys(saved.measured));
				log.info("Loaded {} measured and {} manual wrap trims",
					saved.measured == null ? 0 : saved.measured.size(),
					saved.manual == null ? 0 : saved.manual.size());
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", file, e);
		}
	}

	public void save(FollowerEntity follower)
	{
		if (file == null)
		{
			return;
		}

		Saved saved = new Saved();
		saved.manual = toStringKeys(follower.getWrapTrims());
		saved.measured = toStringKeys(follower.getMeasuredTrims());

		if (saved.manual.isEmpty() && saved.measured.isEmpty())
		{
			return;
		}

		try
		{
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(saved, writer);
			}
			log.info("Saved {} measured and {} manual wrap trims",
				saved.measured.size(), saved.manual.size());
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}

	private static Map<String, Integer> toStringKeys(Map<Integer, Integer> source)
	{
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : source.entrySet())
		{
			out.put(Integer.toString(entry.getKey()), entry.getValue());
		}
		return out;
	}

	private static Map<Integer, Integer> toIntKeys(Map<String, Integer> source)
	{
		Map<Integer, Integer> out = new HashMap<>();
		if (source == null)
		{
			return out;
		}
		for (Map.Entry<String, Integer> entry : source.entrySet())
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
