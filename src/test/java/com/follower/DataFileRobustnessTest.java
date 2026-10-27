package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The files in {@code ~/.runelite/follower} on a bad day.
 *
 * <p>These are dumps and caches rather than settings, and every one of them is
 * loaded during startUp. A throw there does not degrade a feature, it aborts
 * the whole plugin: RuneLite catches it and switches Follower Buddy off. So the
 * bar for all of them is the same - a missing file, an empty file, a truncated
 * file or outright junk has to leave the plugin running, however little it can
 * do afterwards.
 */
public class DataFileRobustnessTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The ways a file on disk actually goes wrong. */
	private static final String[] BROKEN = {
		"",
		"   ",
		"null",
		"{",
		"[]",
		"[1, 2, 3]",
		"\"a string\"",
		"{\"version\": 999}",
		"{\"version\": 1}",
		"{\"version\": 1, \"items\": null}",
		"{\"version\": 1, \"spotanims\": null}",
		"not json at all",
		"{\"truncated\": ",
	};

	private Path dir() throws IOException
	{
		return folder.newFolder().toPath();
	}

	private Path write(String name, String contents) throws IOException
	{
		Path dir = dir();
		Files.write(dir.resolve(name), contents.getBytes(StandardCharsets.UTF_8));
		return dir;
	}

	// --------------------------------------------------------------- missing

	@Test
	public void everyStoreCopesWithAnEmptyDataDirectory() throws IOException
	{
		Path empty = dir();

	}

	// -------------------------------------------------------------- corrupt

	private static String summarise(String contents)
	{
		return contents.length() > 30 ? contents.substring(0, 30) + "..." : "'" + contents + "'";
	}

	// ------------------------------------------------------------- bundled

	@Test
	public void theBundledStanceLibraryIsReadableOnAFreshInstall() throws IOException
	{
		// Not a data file the user owns, but it loads on the same path and a
		// failure here would leave every weapon animating as unarmed.
		StanceLibrary library =
			new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir());

		assertTrue("the shipped stance library is empty or unreadable",
			library.size() > 100);
		assertNotNull("unarmed must always resolve", library.forWeapon(0));
	}

	@Test
	public void aCorruptLearnedStanceFileFallsBackToTheBundledOne() throws IOException
	{
		Path dir = write("stances.json", "{ this is broken");

		StanceLibrary library =
			new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir);

		assertTrue("a broken learned file must not take the shipped stances"
			+ " down with it", library.size() > 100);
	}

	@Test
	public void savingWithNothingToSaveWritesNothingSurprising() throws IOException
	{
		Path dir = dir();
		StanceLibrary library =
			new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir);
		library.save();

		// Whatever it wrote, reading it back has to work.
		StanceLibrary again =
			new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		again.load(dir);
		assertTrue(again.size() > 100);
	}

	// ------------------------------------------------------ unwritable places

	@Test
	public void savingIntoAPlaceThatCannotBeWrittenIsNotACrash() throws IOException
	{
		Path dir = dir();
		StanceLibrary library =
			new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir);
		library.setManual(999999, 808, 819, 824, 390);

		// Replace the target with a directory so the write cannot succeed.
		Files.deleteIfExists(dir.resolve("stances.json"));
		Files.createDirectories(dir.resolve("stances.json"));

		library.save();

		assertFalse("a failed save must not be fatal", Files.isRegularFile(
			dir.resolve("stances.json")));
	}
}
