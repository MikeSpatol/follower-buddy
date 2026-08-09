package com.follower.speech;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads dialogs.json and reloads it when it changes, exactly as
 * {@link RuleLoader} does for phrases.json - same poll, same defaults-on-first-
 * run, same refusal to replace good content with nothing on a bad read.
 */
@Slf4j
@Singleton
public class DialogLoader
{
	public static final String FILE_NAME = "dialogs.json";
	private static final String BUNDLED_DEFAULT = "/com/follower/default-dialogs.json";

	private static final int SUPPORTED_VERSION = 1;

	private final Gson gson;

	private Map<String, DialogTree> trees = Collections.emptyMap();

	@Getter
	private String status = "not loaded";

	@Getter
	private List<String> errors = Collections.emptyList();

	private Path file;
	private long lastModified = -1L;
	private long lastSize = -1L;

	@Inject
	public DialogLoader(Gson gson)
	{
		this.gson = gson;
	}

	public void initialise(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		if (!Files.exists(file))
		{
			writeDefaults();
		}
		reload();
	}

	public Path getFile()
	{
		return file;
	}

	public DialogTree get(String id)
	{
		return id == null ? null : trees.get(id);
	}

	/** Every tree, in file order. */
	public List<DialogTree> getTrees()
	{
		return new ArrayList<>(trees.values());
	}

	public boolean reloadIfChanged()
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}
		try
		{
			long modified = Files.getLastModifiedTime(file).toMillis();
			long size = Files.size(file);
			if (modified == lastModified && size == lastSize)
			{
				return false;
			}
		}
		catch (IOException e)
		{
			return false;
		}
		reload();
		return true;
	}

	public void reload()
	{
		if (file == null)
		{
			return;
		}

		List<String> problems = new ArrayList<>();

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			DialogFile parsed = gson.fromJson(reader, DialogFile.class);

			if (parsed == null)
			{
				// The same reasoning as phrases.json: an empty read is almost
				// always a poll landing between an editor truncating the file
				// and writing it back. Keep what we had and try again.
				errors = Collections.singletonList(
					"File is empty; keeping " + trees.size() + " previous trees");
				status = "empty file, keeping " + trees.size() + " previous trees";
				log.warn("dialogs.json read as empty; keeping the previous trees");
				return;
			}

			lastModified = Files.getLastModifiedTime(file).toMillis();
			lastSize = Files.size(file);

			if (parsed.version != SUPPORTED_VERSION)
			{
				problems.add("version is " + parsed.version
					+ ", plugin expects " + SUPPORTED_VERSION);
			}

			Map<String, DialogTree> valid = new LinkedHashMap<>();
			Set<String> seen = new HashSet<>();
			for (DialogTree tree : parsed.trees == null
				? Collections.<DialogTree>emptyList() : parsed.trees)
			{
				if (tree == null)
				{
					continue;
				}
				List<String> found = tree.problems();
				problems.addAll(found);
				if (!found.isEmpty())
				{
					// A broken tree is left out rather than half-loaded: a
					// conversation that dead-ends mid-branch is worse than one
					// that never opens, because the player is already in it.
					continue;
				}
				if (!seen.add(tree.id))
				{
					problems.add("Duplicate tree id '" + tree.id + "'");
				}
				valid.put(tree.id, tree);
			}

			trees = valid;
			errors = problems;
			status = valid.size() + " trees"
				+ (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)");
		}
		catch (IOException e)
		{
			problems.add("Could not read " + file + ": " + e.getMessage());
			errors = problems;
			status = "read error";
		}
		catch (JsonParseException e)
		{
			// Keep the previous trees on a syntax error, as with phrases.json.
			problems.add("JSON error: " + e.getMessage());
			errors = problems;
			status = "syntax error, keeping " + trees.size() + " previous trees";
			log.warn("dialogs.json failed to parse", e);
		}
	}

	private void writeDefaults()
	{
		try (InputStream in = DialogLoader.class.getResourceAsStream(BUNDLED_DEFAULT))
		{
			if (in == null)
			{
				log.warn("Bundled {} is missing", BUNDLED_DEFAULT);
				return;
			}
			Files.createDirectories(file.getParent());
			Files.copy(in, file);
			log.debug("Wrote default dialogs to {}", file);
		}
		catch (IOException e)
		{
			log.warn("Could not write default dialogs to {}", file, e);
		}
	}

	public void restoreDefaults()
	{
		try
		{
			Files.deleteIfExists(file);
		}
		catch (IOException e)
		{
			log.warn("Could not remove {}", file, e);
		}
		writeDefaults();
		reload();
	}

	private static final class DialogFile
	{
		int version;
		List<DialogTree> trees;
	}
}
