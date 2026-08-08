package com.follower.appearance;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The graphic definitions every particle effect is drawn from.
 *
 * <p>Its fields are one and two letters because the dump is written that way to
 * keep the file small, and most of them are optional. That combination makes
 * the defaults load-bearing: a missing resize is 128, not 0, and a spotanim
 * scaled to nothing is a particle that never appears - the same failure the
 * shield had while it was being built.
 */
public class SpotAnimRepositoryTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private SpotAnimRepository loadedWith(String json) throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(SpotAnimRepository.FILE_NAME),
			json.getBytes(StandardCharsets.UTF_8));
		SpotAnimRepository repository = new SpotAnimRepository(new Gson());
		repository.load(dir);
		return repository;
	}

	// --------------------------------------------------------------- loading

	@Test
	public void aDumpLoadsAndAnswersById() throws IOException
	{
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"cacheRevision\": \"test\", \"spotanims\": {"
				+ "\"246\": {\"m\": 100, \"a\": 200}}}");

		assertTrue(repository.isLoaded());
		SpotAnimRepository.Entry entry = repository.get(246);
		assertNotNull("the graphic the shield uses did not load", entry);
		assertEquals(100, entry.modelId());
		assertEquals(200, entry.animationId());
	}

	@Test
	public void anUnknownIdIsNullRatherThanAnEmptyEntry() throws IOException
	{
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"spotanims\": {\"246\": {\"m\": 1, \"a\": 2}}}");

		assertNull("an entry that is not there must be null, so the caller can"
			+ " tell and skip rather than draw nothing at scale zero",
			repository.get(999999));
	}

	@Test
	public void anEmptyDumpIsNotLoaded() throws IOException
	{
		SpotAnimRepository repository = loadedWith("{\"version\": 1}");
		assertFalse(repository.isLoaded());
		assertNotNull(repository.getStatus());
	}

	// -------------------------------------------------------------- defaults

	@Test
	public void anOmittedResizeMeansFullSizeNotZero() throws IOException
	{
		// 128 is the game's unit scale. A zero here would shrink every particle
		// to nothing, which looks exactly like the effect not firing at all.
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"spotanims\": {\"1\": {\"m\": 5, \"a\": 6}}}");
		SpotAnimRepository.Entry entry = repository.get(1);

		assertEquals(128, entry.resizeX());
		assertEquals(128, entry.resizeY());
	}

	@Test
	public void theOtherOmittedFieldsDefaultToNothingApplied() throws IOException
	{
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"spotanims\": {\"1\": {\"m\": 5, \"a\": 6}}}");
		SpotAnimRepository.Entry entry = repository.get(1);

		assertEquals("no rotation", 0, entry.rotation());
		assertEquals("no ambient lift", 0, entry.ambient());
		assertEquals("no contrast lift", 0, entry.contrast());
	}

	@Test
	public void suppliedValuesWinOverTheDefaults() throws IOException
	{
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"spotanims\": {\"1\": {\"m\": 5, \"a\": 6,"
				+ " \"rx\": 64, \"ry\": 256, \"rot\": 512, \"am\": 10, \"co\": 20}}}");
		SpotAnimRepository.Entry entry = repository.get(1);

		assertEquals(64, entry.resizeX());
		assertEquals(256, entry.resizeY());
		assertEquals(512, entry.rotation());
		assertEquals(10, entry.ambient());
		assertEquals(20, entry.contrast());
	}

	@Test
	public void aRecolourTableSurvivesLoading() throws IOException
	{
		SpotAnimRepository repository = loadedWith(
			"{\"version\": 1, \"spotanims\": {\"1\": {\"m\": 5, \"a\": 6,"
				+ " \"cf\": [100, 200], \"cr\": [300, 400]}}}");
		SpotAnimRepository.Entry entry = repository.get(1);

		assertNotNull("a graphic that recolours itself lost its palette", entry.cf);
		assertEquals(2, entry.cf.length);
		assertEquals(100, entry.cf[0]);
		assertEquals(400, entry.cr[1]);
	}

	// ------------------------------------------------------------- the real one

	@Test
	public void theGraphicsTheShieldUsesResolveInARealDump() throws IOException
	{
		// Skipped rather than failed where there is no dump: this is a
		// development machine's file, not something the plugin ships.
		Path live = Path.of(System.getProperty("user.home"),
			".runelite", "follower", SpotAnimRepository.FILE_NAME);
		org.junit.Assume.assumeTrue("no local spotanims dump to check against",
			Files.isRegularFile(live));

		SpotAnimRepository repository = new SpotAnimRepository(new Gson());
		repository.load(live.getParent());
		assertTrue(repository.isLoaded());

		// Every graphic the shipped config can play.
		for (int id : new int[]{246, 800, 802, 803, 804})
		{
			assertNotNull("graphic " + id + " is configured but not in the dump",
				repository.get(id));
		}
	}
}
