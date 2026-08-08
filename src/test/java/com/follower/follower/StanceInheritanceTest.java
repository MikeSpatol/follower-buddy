package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * How a weapon nobody has been seen holding gets an animation anyway.
 *
 * <p>This is the plugin's most consequential guesswork, and its failure mode is
 * silent: a wrong borrow gives a weapon somebody else's swing, which reads as a
 * bug in the follower rather than in a lookup table. The rules it follows were
 * measured, and each one is pinned here - including the refusals, which are the
 * half that stops the borrowing going too far.
 */
public class StanceInheritanceTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A model repository that knows only the names this test gives it. */
	private static final class Names extends ModelRepository
	{
		private final Map<Integer, String> names = new HashMap<>();

		Names()
		{
			super(new Gson());
		}

		Names name(int id, String name)
		{
			names.put(id, name);
			return this;
		}

		@Override
		public String itemName(int itemId)
		{
			return names.get(itemId);
		}
	}

	private Names names;
	private StanceLibrary library;

	/**
	 * Two invented pose classes and an item id range the game does not use.
	 *
	 * <p>{@link StanceLibrary#load} always reads the SHIPPED library first, so
	 * a test naming a real weapon is really testing the 191 stances that come
	 * with the plugin. Worse, real pose ids overlap the unarmed set, which
	 * would make "borrowed correctly" and "fell back to unarmed" look identical.
	 * Made-up numbers keep the question being asked an honest one.
	 */
	private static final int[] SCIMITAR = {900001, 900002, 900003};
	private static final int[] STAFF = {910001, 910002, 910003};

	/** Item ids well past anything the shipped library holds. */
	private static final int BASE = 800000;

	@Before
	public void setUp() throws IOException
	{
		names = new Names();
		library = new StanceLibrary(new Gson(), names);
		Path dir = folder.newFolder().toPath();
		library.load(dir);
	}

	private void observe(int itemId, String name, int[] poses, int attack)
	{
		names.name(itemId, name);
		library.setManual(itemId, poses[0], poses[1], poses[2], attack);
	}

	// -------------------------------------------------------------- variants

	@Test
	public void aTrimmedWeaponInheritsFromThePlainOne()
	{
		observe(BASE + 1, "Abyssal whip", SCIMITAR, 1658);
		names.name(BASE + 2, "Abyssal whip (or)");

		StanceLibrary.Stance inherited = library.forWeapon(BASE + 2);
		assertEquals("the ornamented whip should walk like the plain one",
			SCIMITAR[0], inherited.idle);
		assertTrue(library.knows(BASE + 2));
	}

	@Test
	public void aPoisonedDaggerInheritsFromThePlainOne()
	{
		observe(BASE + 3, "Dragon dagger", SCIMITAR, 376);
		names.name(BASE + 4, "Dragon dagger(p++)");
		assertEquals(SCIMITAR[0], library.forWeapon(BASE + 4).idle);
	}

	@Test
	public void aDegradedBarrowsWeaponInheritsFromTheFullOne()
	{
		observe(BASE + 5, "Dharok's greataxe", STAFF, 2067);
		names.name(BASE + 6, "Dharok's greataxe 25");
		assertEquals(STAFF[0], library.forWeapon(BASE + 6).idle);
	}

	@Test
	public void aWeaponWithTwoMarkersStillFindsItsPlainForm()
	{
		observe(BASE + 7, "Rune scimitar", SCIMITAR, 390);
		names.name(BASE + 8, "Rune scimitar (bh)(p++)");
		assertEquals(SCIMITAR[0], library.forWeapon(BASE + 8).idle);
	}

	// ------------------------------------------------------------ metal tiers

	@Test
	public void aDifferentMetalOfTheSameWeaponInherits()
	{
		observe(BASE + 9, "Black longsword", SCIMITAR, 390);
		names.name(BASE + 10, "Adamant longsword");
		assertEquals("adamant should walk like black", SCIMITAR[0],
			library.forWeapon(BASE + 10).idle);
	}

	/**
	 * Measured before it was adopted: the Dragon longsword animates at 809
	 * where the Black one animates at 808, so the ornamental tiers are excluded
	 * on purpose. A dragon weapon must fall back rather than borrow.
	 */
	@Test
	public void anOrnamentalTierDoesNotBorrowFromAPlainMetal()
	{
		observe(BASE + 9, "Black longsword", SCIMITAR, 390);
		names.name(BASE + 11, "Dragon longsword");

		StanceLibrary.Stance stance = library.forWeapon(BASE + 11);
		assertEquals("dragon must fall back to unarmed, not borrow from black",
			library.forWeapon(0).idle, stance.idle);
	}

	// ----------------------------------------------------------- the refusals

	@Test
	public void aDifferentWeaponSharingAWordDoesNotInherit()
	{
		observe(BASE + 12, "Dragon axe", SCIMITAR, 401);
		names.name(BASE + 13, "Dragon felling axe");

		assertEquals("a felling axe is not an axe with a marker on it",
			library.forWeapon(0).idle, library.forWeapon(BASE + 13).idle);
	}

	@Test
	public void aWeaponWithNoNameAtAllFallsBackQuietly()
	{
		StanceLibrary.Stance stance = library.forWeapon(999999);
		assertNotNull("an unknown weapon must still get something to walk with", stance);
		assertEquals(library.forWeapon(0).idle, stance.idle);
	}

	@Test
	public void unarmedIsAlwaysKnown()
	{
		assertTrue(library.knows(0));
		assertTrue("a negative id is unarmed too", library.knows(-1));
		assertNotNull(library.forWeapon(-1));
	}

	// -------------------------------------------------------- attack borrowing

	private StanceLibrary.StyleSource styles(Map<Integer, Integer> usage)
	{
		return itemId -> usage.getOrDefault(itemId, StanceLibrary.StyleSource.UNKNOWN);
	}

	@Test
	public void anUnseenWeaponBorrowsTheAttackItsClassAgreesOn()
	{
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);

		// Three scimitars seen swinging: two share the generic swing, one has a
		// signature of its own.
		observe(BASE + 14, "Steel scimitar", SCIMITAR, 390);
		observe(BASE + 15, "Mithril scimitar", SCIMITAR, 390);
		observe(BASE + 16, "Dragon scimitar", SCIMITAR, 12031);
		for (int id : new int[]{BASE + 14, BASE + 15, BASE + 16})
		{
			usage.put(id, melee);
		}

		// A fourth, never seen used, but in the same class and style.
		names.name(BASE + 17, "Adamant scimitar");
		library.setManual(BASE + 17, SCIMITAR[0], SCIMITAR[1], SCIMITAR[2], 0);
		usage.put(BASE + 17, melee);
		library.setStyleSource(styles(usage));

		assertEquals("the attack the most of its class agree on", 390,
			library.attackFor(BASE + 17));
	}

	@Test
	public void aTieGoesToTheLowerItemIdSoItIsTheSameEverySession()
	{
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);

		// One vote each, so only the item id can separate them. The LOWER id is
		// the one that must win, whichever order they were entered in.
		int lower = BASE + 18;
		int higher = BASE + 19;
		observe(higher, "Alpha blade", SCIMITAR, 555);
		observe(lower, "Beta blade", SCIMITAR, 111);
		usage.put(higher, melee);
		usage.put(lower, melee);

		names.name(BASE + 20, "Gamma blade");
		library.setManual(BASE + 20, SCIMITAR[0], SCIMITAR[1], SCIMITAR[2], 0);
		usage.put(BASE + 20, melee);
		library.setStyleSource(styles(usage));

		// The lower donor id breaks the tie, so the answer cannot depend on
		// hash order and change between sessions.
		int first = library.attackFor(BASE + 20);
		assertEquals(111, first);
		for (int i = 0; i < 20; i++)
		{
			assertEquals("the tie-break must be stable", first, library.attackFor(BASE + 20));
		}
	}

	@Test
	public void aBorrowNeverCrossesCombatStyles()
	{
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);
		int ranged = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.RANGED, false);

		// A harpoon and a bow can be CARRIED alike; they are not swung alike.
		observe(BASE + 21, "Harpoon", SCIMITAR, 390);
		usage.put(BASE + 21, melee);

		names.name(BASE + 22, "Shortbow");
		library.setManual(BASE + 22, SCIMITAR[0], SCIMITAR[1], SCIMITAR[2], 0);
		usage.put(BASE + 22, ranged);
		library.setStyleSource(styles(usage));

		assertEquals("a bow must not borrow a melee swing", 0, library.attackFor(BASE + 22));
	}

	@Test
	public void aBorrowNeverCrossesHandedness()
	{
		Map<Integer, Integer> usage = new HashMap<>();
		int oneHanded = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.RANGED, false);
		int twoHanded = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.RANGED, true);

		observe(BASE + 23, "Bronze dart", SCIMITAR, 806);
		usage.put(BASE + 23, oneHanded);

		names.name(BASE + 22, "Shortbow");
		library.setManual(BASE + 22, SCIMITAR[0], SCIMITAR[1], SCIMITAR[2], 0);
		usage.put(BASE + 22, twoHanded);
		library.setStyleSource(styles(usage));

		assertEquals("a thrown dart and a drawn bow are not the same motion",
			0, library.attackFor(BASE + 22));
	}

	@Test
	public void withNoStyleSourceNothingIsBorrowed()
	{
		observe(BASE + 14, "Steel scimitar", SCIMITAR, 390);
		names.name(BASE + 17, "Adamant scimitar");
		library.setManual(BASE + 17, SCIMITAR[0], SCIMITAR[1], SCIMITAR[2], 0);

		assertEquals("without knowing the style, refuse rather than guess",
			0, library.attackFor(BASE + 17));
	}

	@Test
	public void aWeaponWithItsOwnAttackKeepsIt()
	{
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);
		observe(BASE + 14, "Steel scimitar", SCIMITAR, 390);
		observe(BASE + 16, "Dragon scimitar", SCIMITAR, 12031);
		usage.put(BASE + 14, melee);
		usage.put(BASE + 16, melee);
		library.setStyleSource(styles(usage));

		assertEquals("an observed attack always wins over a borrowed one",
			12031, library.attackFor(BASE + 16));
	}

	// ------------------------------------------------------------ persistence

	@Test
	public void whatIsLearnedSurvivesASaveAndReload() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		StanceLibrary first = new StanceLibrary(new Gson(), names);
		first.load(dir);
		names.name(BASE + 1, "Abyssal whip");
		first.setManual(4151, 808, 819, 824, 1658);
		first.save();

		StanceLibrary second = new StanceLibrary(new Gson(), names);
		second.load(dir);

		StanceLibrary.Stance stance = second.describe(4151);
		assertNotNull("the observation did not survive the round trip", stance);
		assertEquals(808, stance.idle);
		assertEquals(819, stance.walk);
		assertEquals(824, stance.run);
		assertEquals(1658, stance.attack);
	}

	@Test
	public void savingWithNothingLearnedDoesNotFail() throws IOException
	{
		StanceLibrary fresh = new StanceLibrary(new Gson(), names);
		fresh.load(folder.newFolder().toPath());
		fresh.save();
		fresh.save();
	}

	@Test
	public void theBundledLibraryLoadsAndCoversARealWeapon() throws IOException
	{
		// The shipped stances.json is what a new install starts from; if it
		// stopped loading, every weapon would quietly fall back to unarmed.
		StanceLibrary shipped = new StanceLibrary(new Gson(), names);
		shipped.load(folder.newFolder().toPath());
		assertTrue("the bundled library should hold hundreds of weapons",
			shipped.size() > 100);
	}
}
