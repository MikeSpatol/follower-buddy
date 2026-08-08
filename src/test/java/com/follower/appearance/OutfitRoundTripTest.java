package com.follower.appearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.api.kit.KitType;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An outfit written out and read back must be the same outfit.
 *
 * <p>This is the format users copy between profiles and paste to each other, so
 * anything that drops on the way through is a bug someone hits by sharing a
 * costume. The round trip is checked exhaustively over every slot and then
 * fuzzed, because the failure would be one slot in twelve rather than the
 * whole string.
 */
public class OutfitRoundTripTest
{
	private static Outfit reparse(Outfit outfit)
	{
		return OutfitParser.parse(outfit.toString());
	}

	// ------------------------------------------------------------ round trip

	@Test
	public void anEmptyOutfitSurvives()
	{
		Outfit empty = new Outfit();
		assertTrue(empty.isEmpty());
		assertEquals(empty, reparse(empty));
	}

	@Test
	public void everySlotSurvivesAsAnItem()
	{
		for (KitType slot : KitType.values())
		{
			Outfit outfit = new Outfit();
			outfit.setItem(slot, 4151);
			Outfit back = reparse(outfit);

			assertTrue(slot + " should still hold an item", back.isItem(slot));
			assertEquals(slot + " lost its item id", 4151, back.itemId(slot));
			assertEquals("round trip changed " + slot, outfit, back);
		}
	}

	@Test
	public void everySlotSurvivesAsAKit()
	{
		for (KitType slot : KitType.values())
		{
			Outfit outfit = new Outfit();
			outfit.setKit(slot, 42);
			Outfit back = reparse(outfit);

			assertTrue(slot + " should still hold a kit", back.isKit(slot));
			assertEquals(slot + " lost its kit id", 42, back.kitId(slot));
			assertEquals("round trip changed " + slot, outfit, back);
		}
	}

	@Test
	public void kitZeroIsAKitNotAnEmptySlot()
	{
		// The parser treats a bare "0" as "clear this slot", so a kit whose id
		// really is zero has to survive as "kit:0" rather than being erased.
		Outfit outfit = new Outfit();
		outfit.setKit(KitType.HAIR, 0);
		Outfit back = reparse(outfit);

		assertTrue("kit 0 was read as an empty slot", back.isKit(KitType.HAIR));
		assertEquals(0, back.kitId(KitType.HAIR));
	}

	@Test
	public void genderSurvivesBothWays()
	{
		Outfit female = new Outfit();
		female.setGender(1);
		assertEquals(1, reparse(female).getGender());

		Outfit male = new Outfit();
		male.setGender(0);
		assertEquals(0, reparse(male).getGender());
	}

	@Test
	public void bodyColoursSurvive()
	{
		Outfit outfit = new Outfit();
		outfit.setColors(new int[]{3, 0, 7, 1, 5});
		Outfit back = reparse(outfit);

		assertArrayEquals("the panel's colour choices were lost on save",
			new int[]{3, 0, 7, 1, 5}, back.getColors());
	}

	@Test
	public void aFullyDressedOutfitSurvives()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.WEAPON, 4151);
		outfit.setItem(KitType.SHIELD, 8850);
		outfit.setItem(KitType.HEAD, 11832);
		outfit.setItem(KitType.TORSO, 11834);
		outfit.setItem(KitType.LEGS, 11836);
		outfit.setKit(KitType.HAIR, 3);
		outfit.setKit(KitType.JAW, 10);
		outfit.setGender(1);
		outfit.setColors(new int[]{1, 2, 3, 4, 5});

		assertEquals(outfit, reparse(outfit));
	}

	@Test
	public void randomOutfitsAlwaysSurvive()
	{
		Random random = new Random(20260808L);
		for (int i = 0; i < 3000; i++)
		{
			Outfit outfit = new Outfit();
			for (KitType slot : KitType.values())
			{
				switch (random.nextInt(3))
				{
					case 0:
						outfit.setItem(slot, random.nextInt(30000));
						break;
					case 1:
						outfit.setKit(slot, random.nextInt(600));
						break;
					default:
						break;
				}
			}
			outfit.setGender(random.nextInt(2));
			outfit.setColors(new int[]{
				random.nextInt(30), random.nextInt(30), random.nextInt(30),
				random.nextInt(30), random.nextInt(30)});

			Outfit back = reparse(outfit);
			assertEquals("lost on round trip: " + outfit, outfit, back);
		}
	}

	// -------------------------------------------------------- the game's ids

	@Test
	public void theGameEncodingRoundTripsThroughAComposition()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.WEAPON, 4151);
		outfit.setKit(KitType.HAIR, 3);

		int[] game = outfit.toGameEquipmentIds();
		assertEquals("an item must land in the game's item range",
			2048 + 4151, game[KitType.WEAPON.getIndex()]);
		assertEquals("a kit must land in the game's kit range",
			256 + 3, game[KitType.HAIR.getIndex()]);
	}

	@Test
	public void anAbsurdKitIdIsDroppedRatherThanWrittenAsAnItem()
	{
		// The game's scheme only has room for kit ids below 1792; above that a
		// kit would be written as though it were an item, which would put a
		// random weapon on the follower's head.
		Outfit outfit = new Outfit();
		outfit.setKit(KitType.HAIR, 5000);

		int[] game = outfit.toGameEquipmentIds();
		assertEquals("an out-of-range kit must be dropped, not corrupted",
			0, game[KitType.HAIR.getIndex()]);
	}

	// ------------------------------------------------------------ the parser

	@Test
	public void theParserAcceptsWhatPeopleActuallyType()
	{
		Outfit outfit = OutfitParser.parse(
			"  WEAPON = item:4151 ,\n"
				+ "# a comment line\n"
				+ "// another\n"
				+ "hair=kit:3,\n"
				+ "GENDER=Female,\n"
				+ "colours = 1/2/3/4/5\n");

		assertEquals(4151, outfit.itemId(KitType.WEAPON));
		assertEquals(3, outfit.kitId(KitType.HAIR));
		assertEquals(1, outfit.getGender());
		assertArrayEquals(new int[]{1, 2, 3, 4, 5}, outfit.getColors());
	}

	@Test
	public void aBareNumberIsAnItemBecauseThatIsWhatPeopleMean()
	{
		assertEquals(4151, OutfitParser.parse("weapon=4151").itemId(KitType.WEAPON));
	}

	@Test
	public void clearingASlotHasThreeSpellings()
	{
		for (String spelling : new String[]{"none", "-1", "0"})
		{
			Outfit outfit = OutfitParser.parse("weapon=item:4151,weapon=" + spelling);
			assertEquals("'" + spelling + "' should empty the slot",
				0, outfit.getRaw(KitType.WEAPON));
		}
	}

	@Test
	public void badInputIsReportedRatherThanSilentlyDropped()
	{
		// One of each mistake, checked by what it says rather than by counting,
		// so the test explains which report went missing.
		assertReports("weapon", "Missing '='");
		assertReports("hat=item:1", "Unknown slot");
		assertReports("weapon=item:abc", "is not a number");
		assertReports("weapon=hat:5", "Expected 'item' or 'kit'");
		assertReports("color.hair=red", "no longer supported");
		assertReports("colors=a/b", "Bad colour index");
	}

	private static void assertReports(String text, String expected)
	{
		List<String> errors = new ArrayList<>();
		OutfitParser.parse(text, errors);
		for (String error : errors)
		{
			if (error.contains(expected))
			{
				return;
			}
		}
		throw new AssertionError("'" + text + "' should have reported \""
			+ expected + "\", said " + errors);
	}

	@Test
	public void aGoodOutfitReportsNothing()
	{
		List<String> errors = new ArrayList<>();
		OutfitParser.parse("weapon=item:4151,hair=kit:3,gender=female,colors=1/2/3/4/5",
			errors);
		assertTrue("a valid outfit should not warn: " + errors, errors.isEmpty());
	}

	@Test
	public void nonsenseNeverThrows()
	{
		String[] nonsense = {
			null, "", "   ", ",,,,", "=", "=5", "weapon=", "weapon=item:",
			"weapon=item:99999999999999999999", "weapon=kit:-5",
			"colors=", "colors=a/b/c", "colors=1/2/3/4/5/6/7/8/9",
			"gender=", "gender=banana", "\n\n\n", "#only a comment",
		};
		for (String text : nonsense)
		{
			Outfit outfit = OutfitParser.parse(text);
			// Whatever it made of it, it must still be usable.
			outfit.toString();
			outfit.toGameEquipmentIds();
			outfit.withDefaultBody();
		}
	}

	@Test
	public void moreColoursThanTheGameHasAreIgnoredNotOverflowed()
	{
		Outfit outfit = OutfitParser.parse("colors=1/2/3/4/5/6/7/8");
		assertArrayEquals("only the five the game has should be taken",
			new int[]{1, 2, 3, 4, 5}, outfit.getColors());
	}

	// ------------------------------------------------------- the default body

	@Test
	public void theDefaultBodyFillsEmptyPartsAndLeavesGearAlone()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.TORSO, 11834);
		outfit.setKit(KitType.HAIR, 99);

		Outfit dressed = outfit.withDefaultBody();

		assertEquals("gear must win over a default kit",
			11834, dressed.itemId(KitType.TORSO));
		assertEquals("a chosen kit must not be replaced", 99, dressed.kitId(KitType.HAIR));
		assertFalse("the empty parts should have been filled", dressed.isEmpty());
	}

	@Test
	public void theDefaultBodyDoesNotModifyTheOutfitItWasBuiltFrom()
	{
		Outfit outfit = new Outfit();
		outfit.withDefaultBody();
		assertTrue("withDefaultBody must return a copy, not dress in place",
			outfit.isEmpty());
	}

	@Test
	public void bothGendersHaveADefaultBody()
	{
		for (int gender : new int[]{0, 1})
		{
			Outfit outfit = new Outfit();
			outfit.setGender(gender);
			assertFalse("gender " + gender + " has no default body at all",
				outfit.withDefaultBody().isEmpty());
		}
	}

	// -------------------------------------------------------------- equality

	@Test
	public void equalityCoversEverythingThatCanDiffer()
	{
		Outfit base = new Outfit();
		base.setItem(KitType.WEAPON, 4151);
		base.setGender(0);
		base.setColors(new int[]{1, 1, 1, 1, 1});

		Outfit copy = new Outfit(base);
		assertEquals(base, copy);
		assertEquals(base.hashCode(), copy.hashCode());

		Outfit differentGender = new Outfit(base);
		differentGender.setGender(1);
		assertFalse("gender must count", base.equals(differentGender));

		Outfit differentColour = new Outfit(base);
		differentColour.setColors(new int[]{2, 1, 1, 1, 1});
		assertFalse("colours must count", base.equals(differentColour));

		Outfit differentItem = new Outfit(base);
		differentItem.setItem(KitType.WEAPON, 1234);
		assertFalse("gear must count", base.equals(differentItem));
	}

	@Test
	public void aCopyIsIndependentOfItsOriginal()
	{
		Outfit base = new Outfit();
		base.setItem(KitType.WEAPON, 4151);

		Outfit copy = new Outfit(base);
		copy.setItem(KitType.WEAPON, 1234);
		copy.setColors(new int[]{9, 9, 9, 9, 9});

		assertEquals("editing a copy changed the original", 4151, base.itemId(KitType.WEAPON));
		assertArrayEquals(new int[]{0, 0, 0, 0, 0}, base.getColors());
	}

	@Test
	public void getColorsHandsOutACopyNotTheInternals()
	{
		Outfit outfit = new Outfit();
		int[] colours = outfit.getColors();
		colours[0] = 99;
		assertEquals("getColors leaked the internal array", 0, outfit.getColors()[0]);
	}
}
