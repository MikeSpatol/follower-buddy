package com.follower.chronicle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The scribe's sketches of places, kept on disk beside the rest of the
 * follower's files and one folder per account, because a second character
 * has not been where the first one has. Each place is drawn twice from the
 * same view: at the page plate's size, and larger for the plate opened
 * across the window, filed beside it as {@code <name>.large.png}. The emote
 * you struck for it, if you struck one, is filed beside them as a word in
 * {@code <name>.pose}; a sketch from before there were poses has none.
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

	/** The pose's file ending, beside the pictures. */
	static final String POSE = ".pose";

	/** How many backups of each file a redraw keeps; the oldest beyond go. */
	static final int BACKUPS_KEPT = 3;

	private final Path root;
	private final Map<String, BufferedImage> sketches = new ConcurrentHashMap<>();
	private final Map<String, BufferedImage> large = new ConcurrentHashMap<>();
	/**
	 * The pictures on disk not read yet. A whole book decoded at login was
	 * about 9 MB held and a quarter of a second on the client thread (perf
	 * audit, Oct 2026); each is read the first time it is asked for.
	 */
	private final Map<String, Path> unread = new ConcurrentHashMap<>();
	private final Map<String, Path> unreadLarge = new ConcurrentHashMap<>();
	private final Map<String, String> poses = new ConcurrentHashMap<>();
	private volatile Path folder;
	/** Turned by every forgetAll, so a drawing begun before one is not filed after it. */
	private volatile int edition;

	public SketchBook(Path dataDir)
	{
		this.root = dataDir == null ? null : dataDir.resolve(DIR_NAME);
	}

	/** Opens the book for an account, dropping whatever the last one had. */
	public void openFor(String account)
	{
		sketches.clear();
		large.clear();
		unread.clear();
		unreadLarge.clear();
		poses.clear();
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
				if (key.endsWith(LARGE))
				{
					unreadLarge.put(key.substring(0, key.length() - LARGE.length()), file);
				}
				else
				{
					unread.put(key, file);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read the sketch book at {}", folder, e);
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + POSE))
		{
			for (Path file : files)
			{
				String name = file.getFileName().toString();
				String pose = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
				if (!pose.isEmpty())
				{
					poses.put(name.substring(0, name.length() - POSE.length()), pose);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not read the poses at {}", folder, e);
		}
	}

	public BufferedImage get(String key)
	{
		return key == null ? null : read(key, sketches, unread);
	}

	/** The larger copy, or null for a place not drawn since there were two sizes. */
	public BufferedImage getLarge(String key)
	{
		return key == null ? null : read(key, large, unreadLarge);
	}

	/** A picture already read, or read now from the file found for it; null if it will not read. */
	private static BufferedImage read(String key, Map<String, BufferedImage> pictures, Map<String, Path> files)
	{
		BufferedImage picture = pictures.get(key);
		Path file = picture == null ? files.remove(key) : null;
		if (file == null)
		{
			return picture;
		}
		try
		{
			picture = ImageIO.read(file.toFile());
		}
		catch (IOException | RuntimeException e)
		{
			picture = null;
		}
		if (picture == null)
		{
			log.debug("Skipping unreadable sketch {}", file);
			return pictures.get(key);
		}
		// A sketch filed while this one was read is the newer.
		BufferedImage filed = pictures.putIfAbsent(key, picture);
		return filed != null ? filed : picture;
	}

	/** The emote the sketch caught you in, as a word ("dance"), or null for none. */
	public String poseOf(String key)
	{
		return key == null ? null : poses.get(key);
	}

	public boolean has(String key)
	{
		return get(key) != null;
	}

	/** Drawn at both sizes; a place drawn before there were two is drawn again. */
	public boolean hasBoth(String key)
	{
		return has(key) && (large.containsKey(key) || unreadLarge.containsKey(key));
	}

	/** Files the sketch at both sizes, in memory at once and on disk as well as it can. */
	public void put(String key, BufferedImage sketch, BufferedImage big)
	{
		put(key, sketch, big, null);
	}

	/**
	 * The same, with the emote you struck for it, or null for none. The pose
	 * belongs to this drawing: drawn again standing still, the old one's goes.
	 */
	public void put(String key, BufferedImage sketch, BufferedImage big, String pose)
	{
		put(key, sketch, big, pose, edition);
	}

	/** The book's edition now: a drawing takes it when it begins and files with it. */
	public int edition()
	{
		return edition;
	}

	/**
	 * The same, for a drawing begun in the given edition: one begun before
	 * a Forget everything is dropped, not filed into the emptied book.
	 */
	public synchronized void put(String key, BufferedImage sketch, BufferedImage big, String pose, int begun)
	{
		if (key == null || sketch == null || big == null || begun != edition)
		{
			return;
		}
		// The large one first, so a page never finds the small without it,
		// and the pose before either, so it never shows the last one's.
		if (pose == null)
		{
			poses.remove(key);
		}
		else
		{
			poses.put(key, pose);
		}
		unreadLarge.remove(key);
		unread.remove(key);
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
			Path poseFile = into.resolve(safe(key) + POSE);
			if (pose == null)
			{
				Files.deleteIfExists(poseFile);
			}
			else
			{
				Files.write(poseFile, pose.getBytes(StandardCharsets.UTF_8));
			}
			ImageIO.write(big, "png", into.resolve(safe(key) + LARGE + ".png").toFile());
			ImageIO.write(sketch, "png", into.resolve(safe(key) + ".png").toFile());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not save the sketch of {}", key, e);
		}
	}

	/**
	 * Copies what is on disk for a sketch to a backup beside it, before the
	 * place is drawn again on purpose: {@code name.png.bak}, then
	 * {@code name.png.1.bak} and on, so no file, sketch or backup, is ever
	 * written over. Its pose goes with it, if it had one. The book does not read them back;
	 * they are there to be put back by hand. The last {@value #BACKUPS_KEPT}
	 * of each are kept and older ones removed: every redraw added another
	 * for good (perf audit, Oct 2026).
	 *
	 * @return the backups' file names, empty when nothing was on disk
	 * @throws IOException when one could not be made, so nothing is drawn over it
	 */
	public List<String> backUp(String key) throws IOException
	{
		List<String> kept = new ArrayList<>();
		Path in = folder;
		if (key == null || in == null)
		{
			return kept;
		}
		for (String name : new String[]{safe(key) + LARGE + ".png", safe(key) + ".png", safe(key) + POSE})
		{
			Path file = in.resolve(name);
			if (!Files.isRegularFile(file))
			{
				continue;
			}
			// Numbered after the newest there is, so the numbers only go up
			// and the oldest is always the lowest.
			List<Integer> taken = backups(in, name);
			int next = taken.isEmpty() ? 0 : taken.get(taken.size() - 1) + 1;
			Path backup = backup(in, name, next);
			// No REPLACE_EXISTING: a backup that somehow appeared first fails
			// the copy rather than being lost to it.
			Files.copy(file, backup);
			kept.add(backup.getFileName().toString());
			for (int old : taken.subList(0, Math.max(0, taken.size() + 1 - BACKUPS_KEPT)))
			{
				Files.deleteIfExists(backup(in, name, old));
			}
		}
		return kept;
	}

	/**
	 * Tears out the drawings of the given accounts and of the one open, with
	 * their poses and backups, on disk and in memory, and drops a drawing
	 * still being made. Forget everything: the user's decisions, 16 Oct, that
	 * forgetting forgets the Chronicle too, and 21 Oct, that it takes only the
	 * accounts used under the memory it erases. The folders are shared by
	 * every RuneLite profile and the memory is one profile's, so an account
	 * not named is kept: a deletion cannot be undone.
	 *
	 * @param accounts the account keys whose books were opened under the memory
	 */
	public synchronized void forgetAll(Collection<String> accounts)
	{
		edition++;
		sketches.clear();
		large.clear();
		unread.clear();
		unreadLarge.clear();
		poses.clear();
		Set<Path> books = new LinkedHashSet<>();
		if (folder != null)
		{
			books.add(folder);
		}
		if (root != null && accounts != null)
		{
			for (String account : accounts)
			{
				if (account != null && !account.isEmpty())
				{
					books.add(root.resolve(safe(account)));
				}
			}
		}
		for (Path book : books)
		{
			if (Files.isDirectory(book))
			{
				tearOut(book);
			}
		}
	}

	private static void tearOut(Path in)
	{
		try (DirectoryStream<Path> files = Files.newDirectoryStream(in))
		{
			for (Path file : files)
			{
				if (Files.isRegularFile(file))
				{
					Files.deleteIfExists(file);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not tear every sketch out of {}", in, e);
		}
	}

	/** {@code name.bak} is the first, numbered 0; then {@code name.1.bak} and on. */
	private static Path backup(Path in, String name, int n)
	{
		return in.resolve(n == 0 ? name + ".bak" : name + "." + n + ".bak");
	}

	/** The numbers of a file's backups on disk, lowest first. */
	private static List<Integer> backups(Path in, String name) throws IOException
	{
		List<Integer> numbers = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(in, name + "*.bak"))
		{
			for (Path file : files)
			{
				String rest = file.getFileName().toString().substring(name.length());
				if (rest.equals(".bak"))
				{
					numbers.add(0);
				}
				else if (rest.matches("[.][0-9]{1,9}[.]bak"))
				{
					numbers.add(Integer.parseInt(rest.substring(1, rest.length() - ".bak".length())));
				}
			}
		}
		numbers.sort(null);
		return numbers;
	}

	/** A name that is a file name on every system: letters, digits, hyphens. */
	static String safe(String name)
	{
		return name.replaceAll("[^A-Za-z0-9-]", "_");
	}
}
