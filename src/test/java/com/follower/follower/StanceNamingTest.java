package com.follower.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The two name reductions that decide which weapons may inherit an animation
 * from which.
 *
 * <p>Both are deliberately narrow, and the narrowness is the point: over-eager
 * matching hands a weapon the wrong stance, which looks worse than having no
 * stance at all. These cases are the ones that drove the current shape - the
 * markers the old whitelist missed, and the pairs that must NOT collapse.
 */
public class StanceNamingTest
{
	// -------------------------------------------------------------- baseName

	@Test
	public void ornamentAndPoisonMarkersAreStripped()
	{
		assertEquals("abyssal whip", StanceLibrary.baseName("Abyssal whip (or)"));
		assertEquals("dragon dagger", StanceLibrary.baseName("Dragon dagger(p++)"));
		assertEquals("dragon spear", StanceLibrary.baseName("Dragon spear(kp)"));
		assertEquals("granite maul", StanceLibrary.baseName("Granite maul (or)"));
	}

	@Test
	public void twoMarkersOnOneNameBothGo()
	{
		assertEquals("rune scimitar", StanceLibrary.baseName("Rune scimitar (bh)(p++)"));
	}

	@Test
	public void wearAndChargeCountsAreStripped()
	{
		assertEquals("dharok's greataxe", StanceLibrary.baseName("Dharok's greataxe 25"));
		assertEquals("dharok's greataxe", StanceLibrary.baseName("Dharok's greataxe 100"));
		assertEquals("enchanted lyre", StanceLibrary.baseName("Enchanted lyre(2)"));
	}

	@Test
	public void differentWeaponsThatMerelyShareAWordStayApart()
	{
		// The reason the match stays exact on what is left: these two are not
		// the same weapon and must never share a stance.
		assertEquals("toxic staff", StanceLibrary.baseName("Toxic staff (uncharged)"));
		assertEquals("toxic staff of the dead",
			StanceLibrary.baseName("Toxic staff of the dead"));
	}

	@Test
	public void junkNamesReduceToNothingRatherThanToAKey()
	{
		assertNull(StanceLibrary.baseName(null));
		assertNull(StanceLibrary.baseName(""));
		assertNull(StanceLibrary.baseName("null"));
		assertNull("a name that is only a marker must not become a group",
			StanceLibrary.baseName("(or)"));
	}

	// ---------------------------------------------------------- tierBaseName

	@Test
	public void plainMetalTiersCollapseTogether()
	{
		assertEquals("longsword", StanceLibrary.tierBaseName("Adamant longsword"));
		assertEquals("longsword", StanceLibrary.tierBaseName("Black longsword"));
		assertEquals("longsword", StanceLibrary.tierBaseName("Rune longsword"));
		assertEquals("scimitar", StanceLibrary.tierBaseName("Mithril scimitar"));
	}

	@Test
	public void tierStrippingRunsAfterMarkerStripping()
	{
		assertEquals("scimitar", StanceLibrary.tierBaseName("Rune scimitar (bh)"));
	}

	/**
	 * Measured, not assumed: the Dragon longsword animates at 809 where the
	 * Black longsword animates at 808, so the ornamental tiers are excluded on
	 * purpose. If this test ever goes green, the exclusion has been lost.
	 */
	@Test
	public void ornamentalTiersAreNotTreatedAsPlainMetal()
	{
		assertNull(StanceLibrary.tierBaseName("Dragon longsword"));
		assertNull(StanceLibrary.tierBaseName("Crystal halberd"));
		assertNull(StanceLibrary.tierBaseName("Gilded scimitar"));
		assertNull(StanceLibrary.tierBaseName("3rd age longsword"));
	}

	@Test
	public void anUntieredWeaponNeverJoinsATierGroup()
	{
		assertNull("nothing to strip means no group at all",
			StanceLibrary.tierBaseName("Abyssal whip"));
		assertNull(StanceLibrary.tierBaseName("Toktz-xil-ak"));
	}

	@Test
	public void aTierWordOnItsOwnIsNotAWeapon()
	{
		assertNull(StanceLibrary.tierBaseName("Rune"));
		assertNull(StanceLibrary.tierBaseName("Bronze "));
	}

	@Test
	public void aTierWordInsideANameIsNotAPrefix()
	{
		// "Black" here is part of the noun, and the pattern is anchored so it
		// cannot be mistaken for a tier.
		assertEquals("mask", StanceLibrary.tierBaseName("Black mask"));
		assertNull(StanceLibrary.tierBaseName("Ancient staff"));
	}
}
