package com.follower.appearance;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.runelite.api.kit.KitType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which body parts the gear on top of them covers.
 *
 * <p>The game decides this per item rather than by slot: an item declares the
 * slot it occupies plus up to two more it HIDES, so a platebody hides the arms
 * kit and a full helm hides both hair and jaw, while a chainbody and a med helm
 * hide less. About 2,200 of the 6,300 wearable items hide something.
 *
 * <p>This replaced a single hardcoded rule - "a torso item hides the arms" -
 * which covered one case out of many, stripped the arms under every torso item
 * including the ones that should keep them, and left helmets rendering hair
 * straight through themselves. Both directions of that are pinned here.
 */
public class HiddenSlotsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** wearPos indices are KitType ordinals, which is what the dump stores. */
	private static int slot(KitType type)
	{
		return type.ordinal();
	}

	private AppearanceComposer composerWith(String items) throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME),
			("{\"version\": " + ModelRepository.SUPPORTED_VERSION
				+ ", \"items\": {" + items + "}, \"kits\": {}}")
				.getBytes(StandardCharsets.UTF_8));

		ModelRepository repository = new ModelRepository(new Gson());
		repository.load(dir);
		// hiddenSlots asks the repository and nothing else, so the client the
		// composer would otherwise use is not needed here.
		return new AppearanceComposer(null, repository);
	}

	// ------------------------------------------------------------- the cases

	@Test
	public void aPlatebodyHidesTheArms() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"1\": {\"n\": \"Rune platebody\", \"wp1\": " + slot(KitType.TORSO)
				+ ", \"wp2\": " + slot(KitType.ARMS) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 1);
		Set<KitType> hidden = composer.hiddenSlots(outfit);

		assertTrue("a platebody covers the arms", hidden.contains(KitType.ARMS));
		assertFalse("but not the hair", hidden.contains(KitType.HAIR));
	}

	@Test
	public void aChainbodyKeepsTheArms() throws IOException
	{
		// The case the old hardcoded rule got wrong: a torso item that hides
		// nothing. Stripping the arms here left the follower with bare stumps.
		AppearanceComposer composer = composerWith(
			"\"2\": {\"n\": \"Rune chainbody\", \"wp1\": " + slot(KitType.TORSO) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 2);

		assertTrue("a chainbody hides nothing, so the arms must stay",
			composer.hiddenSlots(outfit).isEmpty());
	}

	@Test
	public void aFullHelmHidesHairAndJaw() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"3\": {\"n\": \"Rune full helm\", \"wp1\": " + slot(KitType.HEAD)
				+ ", \"wp2\": " + slot(KitType.HAIR)
				+ ", \"wp3\": " + slot(KitType.JAW) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.HEAD, 3);
		Set<KitType> hidden = composer.hiddenSlots(outfit);

		assertTrue("hair must not render through a full helm",
			hidden.contains(KitType.HAIR));
		assertTrue("nor the beard", hidden.contains(KitType.JAW));
	}

	@Test
	public void aMedHelmHidesHairButKeepsTheBeard() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"4\": {\"n\": \"Rune med helm\", \"wp1\": " + slot(KitType.HEAD)
				+ ", \"wp2\": " + slot(KitType.HAIR) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.HEAD, 4);
		Set<KitType> hidden = composer.hiddenSlots(outfit);

		assertTrue(hidden.contains(KitType.HAIR));
		assertFalse("a med helm leaves the jaw showing", hidden.contains(KitType.JAW));
	}

	@Test
	public void severalItemsHideTheUnionOfWhatTheyCover() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"1\": {\"wp1\": " + slot(KitType.TORSO) + ", \"wp2\": " + slot(KitType.ARMS) + "},"
				+ "\"3\": {\"wp1\": " + slot(KitType.HEAD)
				+ ", \"wp2\": " + slot(KitType.HAIR)
				+ ", \"wp3\": " + slot(KitType.JAW) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 1);
		outfit.setItem(KitType.HEAD, 3);
		Set<KitType> hidden = composer.hiddenSlots(outfit);

		assertEquals(3, hidden.size());
		assertTrue(hidden.contains(KitType.ARMS));
		assertTrue(hidden.contains(KitType.HAIR));
		assertTrue(hidden.contains(KitType.JAW));
	}

	// ------------------------------------------------------------ the refusals

	@Test
	public void anEmptyOutfitHidesNothing() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"1\": {\"wp1\": " + slot(KitType.TORSO) + ", \"wp2\": " + slot(KitType.ARMS) + "}");

		assertTrue(composer.hiddenSlots(new Outfit()).isEmpty());
	}

	@Test
	public void aKitInASlotHidesNothingOnlyGearDoes() throws IOException
	{
		// A body kit is the thing being covered, never the cover.
		AppearanceComposer composer = composerWith(
			"\"1\": {\"wp1\": " + slot(KitType.TORSO) + ", \"wp2\": " + slot(KitType.ARMS) + "}");

		Outfit outfit = new Outfit();
		outfit.setKit(KitType.TORSO, 1);

		assertTrue("a kit id must not be looked up as an item id",
			composer.hiddenSlots(outfit).isEmpty());
	}

	@Test
	public void anItemTheCatalogueDoesNotKnowHidesNothing() throws IOException
	{
		AppearanceComposer composer = composerWith("\"1\": {\"wp1\": 0}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 999999);

		assertTrue("an unknown item must not throw, and must not guess",
			composer.hiddenSlots(outfit).isEmpty());
	}

	@Test
	public void aWearPosOutsideTheSlotRangeIsIgnored() throws IOException
	{
		// -1 is the dump's "unused", and a corrupt or future value must not
		// index off the end of KitType.
		AppearanceComposer composer = composerWith(
			"\"1\": {\"wp1\": " + slot(KitType.TORSO) + ", \"wp2\": -1, \"wp3\": 9999}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 1);

		assertTrue(composer.hiddenSlots(outfit).isEmpty());
	}

	@Test
	public void anItemThatHidesItsOwnSlotIsHarmless() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"1\": {\"wp1\": " + slot(KitType.TORSO)
				+ ", \"wp2\": " + slot(KitType.TORSO) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 1);

		// It says the torso KIT is covered, which it is - by the item itself.
		assertTrue(composer.hiddenSlots(outfit).contains(KitType.TORSO));
	}

	/**
	 * The {@code ::follower hidden} report walks the same wearPos fields in its
	 * own copy of the loop, so it can drift from the renderer and then describe
	 * something that is not happening. This holds the two together without
	 * merging them: whatever the report SAYS is hidden must be what the
	 * renderer actually hides.
	 */
	@Test
	public void theHiddenReportAgreesWithWhatIsActuallyHidden() throws IOException
	{
		AppearanceComposer composer = composerWith(
			"\"1\": {\"n\": \"Rune platebody\", \"wp1\": " + slot(KitType.TORSO)
				+ ", \"wp2\": " + slot(KitType.ARMS) + "},"
				+ "\"3\": {\"n\": \"Rune full helm\", \"wp1\": " + slot(KitType.HEAD)
				+ ", \"wp2\": " + slot(KitType.HAIR)
				+ ", \"wp3\": " + slot(KitType.JAW) + "},"
				+ "\"2\": {\"n\": \"Rune chainbody\", \"wp1\": " + slot(KitType.LEGS) + "}");

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 1);
		outfit.setItem(KitType.HEAD, 3);
		outfit.setItem(KitType.LEGS, 2);

		Set<KitType> actuallyHidden = composer.hiddenSlots(outfit);

		Set<KitType> reported = java.util.EnumSet.noneOf(KitType.class);
		for (String line : composer.describeHidden(outfit))
		{
			int hides = line.indexOf(" hides ");
			if (hides < 0 || line.endsWith(" hides nothing"))
			{
				continue;
			}
			for (String named : line.substring(hides + 7).split(" \\+ "))
			{
				reported.add(KitType.valueOf(named.trim().toUpperCase(java.util.Locale.ROOT)));
			}
		}

		assertEquals("the report and the renderer disagree about what gear covers",
			actuallyHidden, reported);
	}

	@Test
	public void everyWearableSlotCanBeHiddenBySomething() throws IOException
	{
		// The mapping is by ordinal, so an off-by-one anywhere in it would show
		// up as the wrong body part vanishing.
		for (KitType target : KitType.values())
		{
			AppearanceComposer composer = composerWith(
				"\"1\": {\"wp1\": " + slot(KitType.TORSO)
					+ ", \"wp2\": " + slot(target) + "}");

			Outfit outfit = new Outfit();
			outfit.setItem(KitType.TORSO, 1);

			assertTrue("hiding " + target + " named a different slot",
				composer.hiddenSlots(outfit).contains(target));
		}
	}
}
