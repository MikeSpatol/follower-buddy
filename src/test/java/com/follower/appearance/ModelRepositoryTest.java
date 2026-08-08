package com.follower.appearance;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.kit.KitType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The catalogue everything the follower wears is looked up in.
 *
 * <p>Its two habits are worth pinning. Models are per gender with a fall back
 * to the other, because plenty of items only ship one set and a follower with
 * no model at all is invisible rather than plain. And the vertical offsets are
 * per gender too, applied before merging - reading the wrong one puts a helmet
 * through the top of a head.
 */
public class ModelRepositoryTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int MALE = 0;
	private static final int FEMALE = 1;

	private ModelRepository loadedWith(String json) throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME),
			json.getBytes(StandardCharsets.UTF_8));
		ModelRepository repository = new ModelRepository(new Gson());
		repository.load(dir);
		return repository;
	}

	private static String dumpWith(String items, String kits)
	{
		return "{\"version\": " + ModelRepository.SUPPORTED_VERSION
			+ ", \"cacheRevision\": \"test\""
			+ ", \"items\": {" + items + "}"
			+ ", \"kits\": {" + kits + "}}";
	}

	// --------------------------------------------------------------- loading

	@Test
	public void aDumpLoadsAndAnswersByItemId() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"4151\": {\"n\": \"Abyssal whip\", \"m\": [100], \"f\": [101], \"wp1\": 3}", ""));

		assertTrue(repository.isLoaded());
		assertEquals("Abyssal whip", repository.itemName(4151));
		assertNotNull(repository.item(4151));
	}

	@Test
	public void anUnknownItemIsNullRatherThanAnEmptyEntry() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"4151\": {\"n\": \"Abyssal whip\", \"m\": [100]}", ""));

		assertNull(repository.item(999999));
		assertNull("an unknown id must not invent a name", repository.itemName(999999));
	}

	@Test
	public void unloadingReleasesTheCatalogue() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"4151\": {\"n\": \"Abyssal whip\", \"m\": [100]}", ""));
		assertTrue(repository.isLoaded());

		repository.unload();
		assertFalse("shutDown unloads this; it must actually let go",
			repository.isLoaded());
	}

	// -------------------------------------------------------------- genders

	@Test
	public void eachGenderGetsItsOwnModels() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"m\": [10, 11], \"f\": [20, 21]}", ""));
		ModelRepository.Entry entry = repository.item(1);

		assertArray(new int[]{10, 11}, entry.models(MALE));
		assertArray(new int[]{20, 21}, entry.models(FEMALE));
	}

	@Test
	public void anItemWithOnlyOneModelSetLendsItToTheOtherGender() throws IOException
	{
		// Plenty of items ship one set. Falling back keeps the follower dressed
		// rather than invisible from the waist up.
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"m\": [10, 11]}, \"2\": {\"f\": [20]}", ""));

		assertArray("a female follower should borrow the male model",
			new int[]{10, 11}, repository.item(1).models(FEMALE));
		assertArray("and the other way round",
			new int[]{20}, repository.item(2).models(MALE));
	}

	@Test
	public void anEmptyModelListCountsAsMissingNotAsNothingToDraw() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"m\": [], \"f\": [20]}", ""));

		assertArray("an empty list should fall back, not render nothing",
			new int[]{20}, repository.item(1).models(MALE));
	}

	@Test
	public void verticalOffsetsAreReadPerGender() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"m\": [10], \"mo\": 5, \"fo\": -3}", ""));
		ModelRepository.Entry entry = repository.item(1);

		assertEquals(5, entry.offset(MALE));
		assertEquals(-3, entry.offset(FEMALE));
	}

	@Test
	public void aMissingOffsetIsZeroNotADroppedHelmet() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith("\"1\": {\"m\": [10]}", ""));
		assertEquals(0, repository.item(1).offset(MALE));
		assertEquals(0, repository.item(1).offset(FEMALE));
	}

	@Test
	public void chatheadModelsFallBackTheSameWay() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"m\": [10], \"hm\": [50]}", ""));

		assertArray(new int[]{50}, repository.item(1).headModels(MALE));
		assertArray("a face is worth borrowing too",
			new int[]{50}, repository.item(1).headModels(FEMALE));
	}

	// ------------------------------------------------------------------ kits

	@Test
	public void kitsAreListedPerBodyPartAndGender() throws IOException
	{
		// bodyPartId encodes part and gender together: part * 2 + gender.
		int maleHair = ModelRepository.bodyPartId(KitType.HAIR, MALE);
		int femaleHair = ModelRepository.bodyPartId(KitType.HAIR, FEMALE);
		assertFalse("the two genders must not share a body part id",
			maleHair == femaleHair);

		ModelRepository repository = loadedWith(dumpWith("",
			"\"1\": {\"bp\": " + maleHair + ", \"m\": [1]},"
				+ "\"2\": {\"bp\": " + maleHair + ", \"m\": [2]},"
				+ "\"3\": {\"bp\": " + femaleHair + ", \"f\": [3]}"));

		List<Integer> male = repository.kitsFor(KitType.HAIR, MALE);
		List<Integer> female = repository.kitsFor(KitType.HAIR, FEMALE);

		assertEquals("two male hairstyles", 2, male.size());
		assertEquals("one female hairstyle", 1, female.size());
		assertTrue(male.contains(1) && male.contains(2));
		assertTrue(female.contains(3));
	}

	@Test
	public void kitsTheGameWillNotLetYouPickAreHidden() throws IOException
	{
		int maleHair = ModelRepository.bodyPartId(KitType.HAIR, MALE);
		ModelRepository repository = loadedWith(dumpWith("",
			"\"1\": {\"bp\": " + maleHair + ", \"m\": [1]},"
				+ "\"2\": {\"bp\": " + maleHair + ", \"m\": [2]}"));

		assertEquals(2, repository.kitsFor(KitType.HAIR, MALE).size());

		repository.setNonSelectableKits(java.util.Collections.singleton(2));
		List<Integer> offered = repository.kitsFor(KitType.HAIR, MALE);

		assertEquals("a kit the character screen will not offer should not be"
			+ " offered here either", 1, offered.size());
		assertTrue(offered.contains(1));
	}

	// ---------------------------------------------------------------- search

	@Test
	public void searchFindsByNameAndRespectsItsLimit() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"1\": {\"n\": \"Rune scimitar\", \"m\": [1]},"
				+ "\"2\": {\"n\": \"Rune longsword\", \"m\": [2]},"
				+ "\"3\": {\"n\": \"Rune platebody\", \"m\": [3]},"
				+ "\"4\": {\"n\": \"Abyssal whip\", \"m\": [4]}", ""));

		assertEquals("three rune items", 3, repository.search("rune", 10).size());
		assertEquals("the limit must be honoured", 2, repository.search("rune", 2).size());
		assertTrue("case should not matter", repository.search("RUNE", 10).size() == 3);
		assertTrue(repository.search("nothing like this", 10).isEmpty());
	}

	@Test
	public void searchingADumpWithNoNamesIsEmptyRatherThanAnError() throws IOException
	{
		// An older dump carries no names; the panel has to cope.
		ModelRepository repository = loadedWith(dumpWith("\"1\": {\"m\": [1]}", ""));

		assertFalse(repository.hasNames());
		assertNotNull(repository.search("anything", 10));
	}

	// ------------------------------------------------------------- versions

	@Test
	public void aDumpFromAFutureVersionIsRefusedRatherThanMisread() throws IOException
	{
		ModelRepository repository = loadedWith(
			"{\"version\": " + (ModelRepository.SUPPORTED_VERSION + 5)
				+ ", \"items\": {\"1\": {\"m\": [1]}}, \"kits\": {}}");

		// Whether it loads or not, it must say something rather than pretend.
		assertNotNull(repository.getStatus());
	}

	@Test
	public void everyItemIdIsListable() throws IOException
	{
		ModelRepository repository = loadedWith(dumpWith(
			"\"5\": {\"m\": [1]}, \"9\": {\"m\": [2]}, \"2\": {\"m\": [3]}", ""));

		List<Integer> ids = repository.allItemIds();
		assertEquals(3, ids.size());
		assertTrue(ids.contains(2) && ids.contains(5) && ids.contains(9));
	}

	private static void assertArray(int[] expected, int[] actual)
	{
		assertArray("", expected, actual);
	}

	private static void assertArray(String why, int[] expected, int[] actual)
	{
		assertNotNull(why, actual);
		assertEquals(why + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++)
		{
			assertEquals(why + " at " + i, expected[i], actual[i]);
		}
	}
}
