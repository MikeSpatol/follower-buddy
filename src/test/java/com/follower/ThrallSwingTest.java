package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * What a possessed thrall's follower plays when it attacks.
 *
 * <p>The shipped stances now know every staff's melee bash, which is right for
 * a melee follower holding one and wrong for a caster: a magic thrall must keep
 * casting rather than club its target with the staff.
 */
public class ThrallSwingTest
{
	private static final int STAFF_BASH = 419;
	private static final int LEARNED_CAST = 11423;
	private static final int STRIKE = 1162;

	/** The combat tab's weapon categories, as VarbitID.COMBAT_WEAPON_CATEGORY holds them. */
	private static final int SPEAR = 15;
	private static final int STAB_SWORD = 17;
	private static final int STAFF = 18;

	private static final int STAFF_OF_AIR = 1381;
	private static final int DRAGON_DAGGER = 1215;
	private static final int ZAMORAKIAN_HASTA = 11889;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aMagicThrallCastsEvenWhenTheStaffHasABash()
	{
		assertEquals(STRIKE, FollowerPlugin.thrallSwing("magic", 0, STAFF_BASH));
	}

	@Test
	public void aMagicThrallUsesTheCastLearnedWithItsStaff()
	{
		assertEquals(LEARNED_CAST, FollowerPlugin.thrallSwing("magic", LEARNED_CAST, STAFF_BASH));
	}

	@Test
	public void meleeAndRangedUseTheWeaponsSwing()
	{
		assertEquals(STAFF_BASH, FollowerPlugin.thrallSwing("melee", LEARNED_CAST, STAFF_BASH));
		assertEquals(7552, FollowerPlugin.thrallSwing("ranged", 0, 7552));
	}

	@Test
	public void withNoSwingKnownEachStyleHasAPlainOne()
	{
		assertEquals(390, FollowerPlugin.thrallSwing("melee", 0, 0));
		assertEquals(426, FollowerPlugin.thrallSwing("ranged", 0, 0));
	}

	// ----------------------------------------- with the shipped stance library

	private StanceLibrary shipped() throws IOException
	{
		StanceLibrary library = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(folder.newFolder().toPath());
		return library;
	}

	@Test
	public void theFollowerThrowsTheKindOfBlowThePlayersStyleDeals() throws IOException
	{
		// The player's stab, lunge, slash, block on a stab sword: the third
		// button slashes, and the dagger's slash is its own.
		assertEquals(377, FollowerPlugin.thrallSwing(shipped(), "melee", DRAGON_DAGGER, STAB_SWORD, 2, false));
		assertEquals(376, FollowerPlugin.thrallSwing(shipped(), "melee", DRAGON_DAGGER, STAB_SWORD, 0, false));
	}

	@Test
	public void aStaffIsSwungOneHandedWhenTheFollowerHoldsAShield() throws IOException
	{
		StanceLibrary library = shipped();
		assertEquals(STAFF_BASH, FollowerPlugin.thrallSwing(library, "melee", STAFF_OF_AIR, STAFF, 0, false));
		assertEquals(393, FollowerPlugin.thrallSwing(library, "melee", STAFF_OF_AIR, STAFF, 0, true));
	}

	@Test
	public void theHastasCrushIsTwoHandedWithoutAShield() throws IOException
	{
		StanceLibrary library = shipped();
		assertEquals(STAFF_BASH, FollowerPlugin.thrallSwing(library, "melee", ZAMORAKIAN_HASTA, SPEAR, 2, false));
		assertEquals(393, FollowerPlugin.thrallSwing(library, "melee", ZAMORAKIAN_HASTA, SPEAR, 2, true));
	}

	@Test
	public void aCasterWithAStaffAndAShieldStillCasts() throws IOException
	{
		assertEquals(STRIKE, FollowerPlugin.thrallSwing(shipped(), "magic", STAFF_OF_AIR, STAFF, 0, true));
	}
}
