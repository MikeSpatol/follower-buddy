package com.follower;

import com.follower.appearance.ModelRepository;
import com.follower.appearance.PaletteHarvest;
import com.follower.appearance.SpotAnimRepository;
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

		SpotAnimRepository spotAnims = new SpotAnimRepository(new Gson());
		spotAnims.load(empty);
		assertNotNull(spotAnims.getStatus());

		ModelRepository models = new ModelRepository(new Gson());
		models.load(empty);
		assertNotNull(models.getStatus());

		PaletteHarvest palette = new PaletteHarvest(new Gson());
		palette.load(empty);
		assertEquals(0, palette.size());
	}

	@Test
	public void aMissingDumpLeavesAnEmptyRepositoryNotAnException() throws IOException
	{
		SpotAnimRepository spotAnims = new SpotAnimRepository(new Gson());
		spotAnims.load(dir());

		assertEquals("nothing should be found in an empty repository",
			null, spotAnims.get(246));
	}

	// -------------------------------------------------------------- corrupt

	@Test
	public void aCorruptSpotAnimDumpDoesNotStopStartup() throws IOException
	{
		for (String junk : BROKEN)
		{
			SpotAnimRepository repository = new SpotAnimRepository(new Gson());
			repository.load(write(SpotAnimRepository.FILE_NAME, junk));
			assertNotNull("no status after loading " + summarise(junk),
				repository.getStatus());
			// Still answerable, whatever it made of the file.
			repository.get(246);
			repository.isLoaded();
		}
	}

	@Test
	public void aCorruptModelDumpDoesNotStopStartup() throws IOException
	{
		for (String junk : BROKEN)
		{
			ModelRepository repository = new ModelRepository(new Gson());
			repository.load(write(ModelRepository.FILE_NAME, junk));
			assertNotNull("no status after loading " + summarise(junk),
				repository.getStatus());
			repository.itemName(4151);
			repository.isLoaded();
			repository.unload();
		}
	}

	@Test
	public void aCorruptPaletteHarvestDoesNotStopStartup() throws IOException
	{
		for (String junk : BROKEN)
		{
			PaletteHarvest palette = new PaletteHarvest(new Gson());
			palette.load(write("palette-harvest.json", junk));
			assertTrue("negative size after loading " + summarise(junk),
				palette.size() >= 0);
		}
	}

	private static String summarise(String contents)
	{
		return contents.length() > 30 ? contents.substring(0, 30) + "..." : "'" + contents + "'";
	}

	// ------------------------------------------------------------ a directory

	@Test
	public void aDirectoryWhereAFileShouldBeIsNotACrash() throws IOException
	{
		// Rare, but it happens - a sync client, a botched restore.
		Path dir = dir();
		Files.createDirectories(dir.resolve(SpotAnimRepository.FILE_NAME));

		SpotAnimRepository repository = new SpotAnimRepository(new Gson());
		repository.load(dir);
		assertNotNull(repository.getStatus());
	}

	// ------------------------------------------------------------- round trip

	@Test
	public void harvestedPaletteRunsSurviveASaveAndReload() throws IOException
	{
		Path dir = dir();
		PaletteHarvest first = new PaletteHarvest(new Gson());
		first.load(dir);

		java.util.Map<Short, Short> pairs = new java.util.HashMap<>();
		pairs.put((short) 100, (short) 200);
		int size = first.record(new int[]{1, 2, 3, 4, 5}, pairs);
		assertTrue("nothing was recorded", size > 0);

		PaletteHarvest second = new PaletteHarvest(new Gson());
		second.load(dir);
		assertEquals("the harvest did not survive the round trip", size, second.size());
	}

	@Test
	public void recordingTheSameRunTwiceDoesNotDoubleIt() throws IOException
	{
		PaletteHarvest palette = new PaletteHarvest(new Gson());
		palette.load(dir());

		java.util.Map<Short, Short> pairs = new java.util.HashMap<>();
		pairs.put((short) 100, (short) 200);
		int[] colours = {1, 2, 3, 4, 5};

		int first = palette.record(colours, pairs);
		int second = palette.record(colours, pairs);
		assertEquals("the same observation was counted twice", first, second);
	}

	// ------------------------------------------------------------- bundled

	@Test
	public void theBundledStanceLibraryIsReadableOnAFreshInstall() throws IOException
	{
		// Not a data file the user owns, but it loads on the same path and a
		// failure here would leave every weapon animating as unarmed.
		com.follower.follower.StanceLibrary library =
			new com.follower.follower.StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir());

		assertTrue("the shipped stance library is empty or unreadable",
			library.size() > 100);
		assertNotNull("unarmed must always resolve", library.forWeapon(0));
	}

	@Test
	public void aCorruptLearnedStanceFileFallsBackToTheBundledOne() throws IOException
	{
		Path dir = write("stances.json", "{ this is broken");

		com.follower.follower.StanceLibrary library =
			new com.follower.follower.StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir);

		assertTrue("a broken learned file must not take the shipped stances"
			+ " down with it", library.size() > 100);
	}

	@Test
	public void savingWithNothingToSaveWritesNothingSurprising() throws IOException
	{
		Path dir = dir();
		com.follower.follower.StanceLibrary library =
			new com.follower.follower.StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(dir);
		library.save();

		// Whatever it wrote, reading it back has to work.
		com.follower.follower.StanceLibrary again =
			new com.follower.follower.StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		again.load(dir);
		assertTrue(again.size() > 100);
	}

	// ------------------------------------------------------ unwritable places

	@Test
	public void savingIntoAPlaceThatCannotBeWrittenIsNotACrash() throws IOException
	{
		Path dir = dir();
		com.follower.follower.StanceLibrary library =
			new com.follower.follower.StanceLibrary(new Gson(), new ModelRepository(new Gson()));
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
