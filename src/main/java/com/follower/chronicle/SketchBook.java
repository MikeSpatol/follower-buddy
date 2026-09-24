package com.follower.chronicle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The scribe's sketches of places, kept on disk beside the rest of the
 * follower's files and one folder per account, because a second character
 * has not been where the first one has. Each place is drawn twice from the
 * same view: at the page plate's size, and larger for the plate opened
 * across the window, filed beside it as {@code <name>.large.png}.
 *
 * <p>Nothing here throws: a missing folder is an empty book and a picture
 * that will not read is skipped, as every loader in the plugin does.
 */
@Slf4j
public class SketchBook
{
	public static final String DIR_NAME = "chronicle-sketches";

	/** The large copy's file ending; a name is letters, digits and hyphens, so it cannot be mistaken for one. */
	static final String LARGE = ".large";

	private final Path root;
	private final Map<String, BufferedImage> sketches = new ConcurrentHashMap<>();
	private final Map<String, BufferedImage> large = new ConcurrentHashMap<>();
	private volatile Path folder;

	public SketchBook(Path dataDir)
	{
		this.root = dataDir == null ? null : dataDir.resolve(DIR_NAME);
	}

	/** Opens the book for an account, dropping whatever the last one had. */
	public void openFor(String account)
	{
		sketches.clear();
		large.clear();
		folder = null;
		if (root == null || account == null || account.isEmpty())
		{
			return;
		}
		folder = root.resolve(safe(account));
		if (!Files.isDirectory(folder))
		{
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.png"))
		{
			for (Path file : files)
			{
				String name = file.getFileName().toString();
				String key = name.substring(0, name.length() - ".png".length());
				boolean big = key.endsWith(LARGE);
				try
				{
					BufferedImage image = ImageIO.read(file.toFile());
					if (image != null && big)
					{
						large.put(key.substring(0, key.length() - LARGE.length()), image);
					}
					else if (image != null)
					{
						sketches.put(key, image);
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Skipping unreadable sketch {}", file, e);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read the sketch book at {}", folder, e);
		}
	}

	public BufferedImage get(String key)
	{
		return key == null ? null : sketches.get(key);
	}

	/** The larger copy, or null for a place not drawn since there were two sizes. */
	public BufferedImage getLarge(String key)
	{
		return key == null ? null : large.get(key);
	}

	public boolean has(String key)
	{
		return key != null && sketches.containsKey(key);
	}

	/** Drawn at both sizes; a place drawn before there were two is drawn again. */
	public boolean hasBoth(String key)
	{
		return has(key) && large.containsKey(key);
	}

	/** Files the sketch at both sizes, in memory at once and on disk as well as it can. */
	public void put(String key, BufferedImage sketch, BufferedImage big)
	{
		if (key == null || sketch == null || big == null)
		{
			return;
		}
		// The large one first, so a page never finds the small without it.
		large.put(key, big);
		sketches.put(key, sketch);
		Path into = folder;
		if (into == null)
		{
			return;
		}
		try
		{
			Files.createDirectories(into);
			ImageIO.write(big, "png", into.resolve(safe(key) + LARGE + ".png").toFile());
			ImageIO.write(sketch, "png", into.resolve(safe(key) + ".png").toFile());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not save the sketch of {}", key, e);
		}
	}

	/** A name that is a file name on every system: letters, digits, hyphens. */
	static String safe(String name)
	{
		return name.replaceAll("[^A-Za-z0-9-]", "_");
	}
}
