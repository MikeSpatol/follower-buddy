package com.follower;

import com.follower.appearance.Outfit;
import net.runelite.api.kit.KitType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The emote disarm empties the follower's hands; a borrowed tool must
 * survive it. The two are ordered in one place, and this pins the order.
 */
public class DisarmKeepsBorrowedToolTest
{
	private static Outfit armed()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.WEAPON, 4151);
		outfit.setItem(KitType.SHIELD, 1540);
		return outfit;
	}

	@Test
	public void theDisarmEmptiesBothHands()
	{
		Outfit dressed = FollowerPlugin.dressForRender(armed(), true, null, 0);
		assertFalse(dressed.isItem(KitType.WEAPON));
		assertFalse(dressed.isItem(KitType.SHIELD));
	}

	@Test
	public void leftArmedTheOutfitKeepsItsOwnWeapon()
	{
		Outfit dressed = FollowerPlugin.dressForRender(armed(), false, null, 0);
		assertEquals(4151, dressed.itemId(KitType.WEAPON));
		assertEquals(1540, dressed.itemId(KitType.SHIELD));
	}

	@Test
	public void aBorrowedToolSurvivesTheMimic()
	{
		// The mirror rule puts the sword away; the axe the follower borrowed
		// goes back into the emptied hand, because the prop is laid on after.
		Outfit dressed = FollowerPlugin.dressForRender(armed(), true, KitType.WEAPON, 1351);
		assertEquals("the axe stays in hand", 1351, dressed.itemId(KitType.WEAPON));
		assertFalse("the shield still goes", dressed.isItem(KitType.SHIELD));
		assertTrue(dressed.isItem(KitType.WEAPON));
	}

	@Test
	public void thePropOutranksTheOutfitsOwnWeaponEvenArmed()
	{
		// The stance follows the weapon read after this, so a borrowed axe
		// gives the follower an axe stance for free.
		Outfit dressed = FollowerPlugin.dressForRender(armed(), false, KitType.WEAPON, 1351);
		assertEquals(1351, dressed.itemId(KitType.WEAPON));
	}

	@Test
	public void aPropInTheWeaponHandTakesTheShieldWithIt()
	{
		// The scroll is read two-handed and a tool is swung with the arm the
		// shield is on, so the shield goes whether or not anything disarmed.
		Outfit reading = FollowerPlugin.dressForRender(armed(), false, KitType.WEAPON, 10485);
		assertEquals("the scroll is in the hand", 10485, reading.itemId(KitType.WEAPON));
		assertFalse("and the shield is off the arm", reading.isItem(KitType.SHIELD));
	}

	@Test
	public void aPropInAnotherSlotLeavesTheHandsAlone()
	{
		// A borrowed hat is not an argument with the shield.
		Outfit dressed = FollowerPlugin.dressForRender(armed(), false, KitType.HEAD, 10941);
		assertEquals(10941, dressed.itemId(KitType.HEAD));
		assertEquals("the weapon stays", 4151, dressed.itemId(KitType.WEAPON));
		assertEquals("and so does the shield", 1540, dressed.itemId(KitType.SHIELD));
	}
}
