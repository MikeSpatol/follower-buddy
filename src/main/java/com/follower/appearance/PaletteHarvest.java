package com.follower.appearance;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
 * Append-only record of every palette extraction, keyed by the character's colour
 * indices at the time.
 *
 * <p>The client's replacement tables (index -> packed colour, per body-colour slot)
 * are hardcoded and unreachable, but each {@code ::follower palette} run recovers
 * the entries for one combination of choices. Recording runs as the player cycles
 * colours at the makeover mage therefore reconstructs the real tables entry by
 * entry — which is what an exact colour picker needs. Nothing here interprets the
 * data; it just refuses to lose it.
 */
@Slf4j
@Singleton
public class PaletteHarvest
{
	private static final String FILE_NAME = "palette-harvest.json";

	/** One extraction: the 5 colour indices worn, and the recovered find->replace pairs. */
	public static class Run
	{
		public int[] colors;
		public Map<String, Integer> pairs;
		public String when;
	}

	private final Gson gson;

	private Path file;
	private List<Run> runs = new ArrayList<>();

	@Inject
	public PaletteHarvest(Gson gson)
	{
		this.gson = gson;
	}

	public void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		runs = new ArrayList<>();
		if (!Files.isRegularFile(file))
		{
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			List<Run> stored = gson.fromJson(reader, new TypeToken<List<Run>>()
			{
			}.getType());
			if (stored != null)
			{
				runs = stored;
			}
			log.info("Loaded {} harvested palette runs", runs.size());
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Could not read {}", file, e);
		}
	}

	/**
	 * Records one extraction; duplicates of an already-recorded run are ignored.
	 *
	 * @return total distinct runs banked so far
	 */
	public int record(int[] colors, Map<Short, Short> pairs)
	{
		Run run = new Run();
		run.colors = colors == null ? null : colors.clone();
		run.pairs = new LinkedHashMap<>();
		pairs.forEach((find, replace) -> run.pairs.put(String.valueOf(find), (int) replace));
		run.when = java.time.LocalDate.now().toString();

		for (Run existing : runs)
		{
			if (java.util.Arrays.equals(existing.colors, run.colors)
				&& run.pairs.equals(existing.pairs))
			{
				return runs.size();
			}
		}

		runs.add(run);
		save();
		return runs.size();
	}

	public int size()
	{
		return runs.size();
	}

	private void save()
	{
		if (file == null)
		{
			return;
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
		{
			gson.toJson(runs, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}
}
