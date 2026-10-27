package com.follower;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tiles the map calls walkable that nobody walks on. Kept from the
 * objects the client announces, which is the only way the answer can be
 * exact and still cost a hash lookup per tile of a route.
 */
public class HotGroundTest
{
	private static final int FIRE = 26185;
	private static final int CAMPFIRE = 43475;
	private static final int TREE = 1276;

	private FakeGame game;
	private HotGround hot;

	@Before
	public void setUp()
	{
		game = new FakeGame();
		game.placeObject(FIRE, "Fire", 0, 0, 0);
		game.placeObject(CAMPFIRE, "Forester's Campfire", 0, 0, 0);
		game.placeObject(TREE, "Tree", 0, 0, 0);
		hot = new HotGround(game.client);
	}

	private static WorldPoint at(int x, int y)
	{
		return new WorldPoint(x, y, 0);
	}

	@Test
	public void aFireMakesItsTileHotAndBurningOutMakesItColdAgain()
	{
		assertFalse(hot.hot(at(50, 50)));
		hot.objectSpawned(FIRE, at(50, 50), at(50, 50));
		assertTrue(hot.hot(at(50, 50)));
		assertFalse("and only its own tile", hot.hot(at(51, 50)));
		assertEquals(1, hot.count());

		hot.objectDespawned(FIRE, at(50, 50), at(50, 50));
		assertFalse("burnt out", hot.hot(at(50, 50)));
		assertEquals(0, hot.count());
	}

	@Test
	public void everythingElseInTheSceneIsCold()
	{
		hot.objectSpawned(TREE, at(50, 50), at(51, 51));
		assertFalse(hot.hot(at(50, 50)));
		assertFalse(hot.hot(at(51, 51)));
		assertEquals(0, hot.count());
	}

	@Test
	public void aCampfireCountsToo()
	{
		hot.objectSpawned(CAMPFIRE, at(60, 60), null);
		assertTrue("standing in a campfire is the same silliness", hot.hot(at(60, 60)));
	}

	@Test
	public void theNamesAreMatchedHoweverTheGameCapitalisesThem()
	{
		assertTrue(HotGround.isFire("Fire"));
		assertTrue(HotGround.isFire("fire"));
		assertTrue(HotGround.isFire("Campfire"));
		assertTrue(HotGround.isFire("Forester's Campfire"));
		assertFalse(HotGround.isFire("Burning brazier"));
		assertFalse(HotGround.isFire("Firepit"));
		assertFalse(HotGround.isFire(null));
	}

	@Test
	public void aLineOfFiresIsAListOfTiles()
	{
		for (int x = 50; x < 56; x++)
		{
			hot.objectSpawned(FIRE, at(x, 50), at(x, 50));
		}
		assertEquals(6, hot.count());
		for (int x = 50; x < 56; x++)
		{
			assertTrue(hot.hot(at(x, 50)));
		}
		assertFalse(hot.hot(at(50, 51)));

		hot.clear();
		assertEquals("a hop takes the scene and its fires with it", 0, hot.count());
	}

	@Test
	public void nothingAtAllIsCold()
	{
		assertFalse(hot.hot(null));
		hot.objectSpawned(FIRE, null, null);
		assertEquals(0, hot.count());
	}

	@Test
	public void aFootprintTooBigToBeAFireIsNotWalkedRoundWholesale()
	{
		// Belt and braces: an id that resolves to a fire name on a footprint
		// the size of a building is a mistake somewhere, and answering with
		// the whole building would push the follower about the map.
		hot.objectSpawned(FIRE, at(50, 50), at(70, 70));
		assertEquals(1, hot.count());
		assertTrue(hot.hot(at(50, 50)));
		assertFalse(hot.hot(at(60, 60)));
	}
}
