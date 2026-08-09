package com.follower.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The wrap trims, and the file they live in.
 *
 * <p>A trim is how many frames to cut off the end of a looping animation, so
 * the follower's loop wraps where the player's does. They are MEASURED over a
 * session and written out, which means the file is the only thing keeping work
 * the plugin did once - and it was the last of the plugin's stores with no test.
 *
 * <p>Two kinds live in it and the difference matters: measured values the
 * plugin worked out, and manual ones somebody set by hand with
 * {@code ::follower wrapearly}. Merging them the wrong way round would have a
 * measurement quietly overwrite a deliberate override.
 */
public class WrapTrimStoreTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The entity only has to hold two maps here; nothing touches a client. */
	private static FollowerEntity bareFollower()
	{
		return new FollowerEntity(null, null);
	}

	@Test
	public void trimsSurviveASaveAndLoad()
	{
		Path dir = folderPath();
		FollowerEntity saved = bareFollower();

		Map<Integer, Integer> manual = new HashMap<>();
		manual.put(808, 3);
		Map<Integer, Integer> measured = new HashMap<>();
		measured.put(819, 5);
		measured.put(824, 1);
		saved.restoreTrims(manual, measured);

		WrapTrimStore store = new WrapTrimStore(new Gson());
		store.load(dir, saved);
		store.save(saved);

		FollowerEntity reloaded = bareFollower();
		new WrapTrimStore(new Gson()).load(dir, reloaded);

		assertEquals("the hand-set trim came back", Integer.valueOf(3),
			reloaded.getWrapTrims().get(808));
		assertEquals("and both measured ones", Integer.valueOf(5),
			reloaded.getMeasuredTrims().get(819));
		assertEquals(Integer.valueOf(1), reloaded.getMeasuredTrims().get(824));
	}

	@Test
	public void aMeasurementNeverOverwritesAHandSetTrim()
	{
		// The two maps are separate for exactly this reason: a manual trim is
		// somebody deciding, a measurement is the plugin guessing. Round-trip
		// them together and the manual one has to still be manual.
		Path dir = folderPath();
		FollowerEntity follower = bareFollower();

		Map<Integer, Integer> manual = new HashMap<>();
		manual.put(808, 3);
		Map<Integer, Integer> measured = new HashMap<>();
		measured.put(808, 9);
		follower.restoreTrims(manual, measured);

		WrapTrimStore store = new WrapTrimStore(new Gson());
		store.load(dir, follower);
		store.save(follower);

		FollowerEntity reloaded = bareFollower();
		new WrapTrimStore(new Gson()).load(dir, reloaded);

		assertEquals("the same animation is in both maps and they must not merge",
			Integer.valueOf(3), reloaded.getWrapTrims().get(808));
		assertEquals(Integer.valueOf(9), reloaded.getMeasuredTrims().get(808));
	}

	@Test
	public void nothingLearnedWritesNoFileAtAll()
	{
		// A fresh install should not litter the data directory with an empty
		// object, and an empty file is one more thing the loader has to
		// survive on the next start.
		Path dir = folderPath();
		WrapTrimStore store = new WrapTrimStore(new Gson());
		FollowerEntity follower = bareFollower();

		store.load(dir, follower);
		store.save(follower);

		assertFalse("an empty store wrote a file anyway",
			Files.exists(dir.resolve(WrapTrimStore.FILE_NAME)));
	}

	@Test
	public void aMissingFileIsAFreshStartRatherThanAFailure()
	{
		FollowerEntity follower = bareFollower();
		new WrapTrimStore(new Gson()).load(folderPath(), follower);

		assertTrue("nothing to load is not an error", follower.getWrapTrims().isEmpty());
		assertTrue(follower.getMeasuredTrims().isEmpty());
	}

	@Test
	public void aCorruptFileLeavesTheFollowerUsableRatherThanThrowing()
	{
		// This runs during startup. Throwing here would take the plugin down
		// over a file whose only job is to save re-measuring some animations.
		Path dir = folderPath();
		write(dir.resolve(WrapTrimStore.FILE_NAME), "{ this is not json");

		FollowerEntity follower = bareFollower();
		new WrapTrimStore(new Gson()).load(dir, follower);

		assertTrue("a bad file must leave empty trims, not half-read ones",
			follower.getWrapTrims().isEmpty());
	}

	@Test
	public void anEmptyFileIsSurvivedToo()
	{
		Path dir = folderPath();
		write(dir.resolve(WrapTrimStore.FILE_NAME), "");

		FollowerEntity follower = bareFollower();
		new WrapTrimStore(new Gson()).load(dir, follower);

		assertTrue(follower.getWrapTrims().isEmpty());
	}

	@Test
	public void savingWithoutLoadingFirstDoesNothing()
	{
		// save() has nowhere to write until load() has told it where the data
		// directory is. Guessing would put the file somewhere unexpected.
		FollowerEntity follower = bareFollower();
		Map<Integer, Integer> manual = new HashMap<>();
		manual.put(808, 3);
		follower.restoreTrims(manual, null);

		new WrapTrimStore(new Gson()).save(follower);
		// The assertion is that this returned quietly rather than throwing at
		// a null path; there is nowhere to look for a file it never named.
	}

	// ------------------------------------------------------------- plumbing

	private Path folderPath()
	{
		try
		{
			return folder.newFolder().toPath();
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	private static void write(Path file, String contents)
	{
		try
		{
			Files.createDirectories(file.getParent());
			Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}
}
