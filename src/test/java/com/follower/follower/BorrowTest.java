package com.follower.follower;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.PlayerComposition;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The borrowed tool: taken through the prop hands, given back with or
 * without a beat, never left in hand by an abort, and never fighting the
 * scroll for the weapon slot.
 */
public class BorrowTest
{
	/** Records what it was asked; does none of it. */
	private static final class RecordingFollower extends FollowerEntity
	{
		final List<Integer> animations = new ArrayList<>();
		boolean staying;
		boolean following = true;
		int poseOverride;

		RecordingFollower()
		{
			super(null, null);
		}

		@Override
		public void playAnimation(int id)
		{
			animations.add(id);
		}

		@Override
		public void stayHere()
		{
			staying = true;
			following = false;
		}

		@Override
		public void resumeFollowing()
		{
			staying = false;
			following = true;
		}

		@Override
		public void setPoseOverride(int id)
		{
			poseOverride = id;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return new WorldPoint(3222, 3218, 0);
		}
	}

	/** The plugin's hands, in miniature: one prop slot, and honest about it. */
	private final class FakeHands implements ErrandController.Hands
	{
		int held;

		/** Items the cache has no wear position for; the real hands decline them. */
		Set<Integer> unwearable = new HashSet<>();

		@Override
		public void hold(int itemId)
		{
			if (unwearable.contains(itemId))
			{
				return;
			}
			held = itemId;
		}

		@Override
		public void release()
		{
			held = 0;
		}

		@Override
		public boolean isHolding()
		{
			return held != 0;
		}

		int worn;

		@Override
		public void wear(int itemId)
		{
			if (!unwearable.contains(itemId))
			{
				worn = itemId;
			}
		}

		@Override
		public void takeOff()
		{
			worn = 0;
		}

		@Override
		public boolean isWearing()
		{
			return worn != 0;
		}
	}

	private RecordingFollower follower;
	private FakeHands hands;
	private Borrow borrow;

	@Before
	public void setUp()
	{
		follower = new RecordingFollower();
		hands = new FakeHands();
		borrow = new Borrow(follower, hands);
	}

	@Test
	public void startTakesTheToolThroughTheHands()
	{
		assertTrue(borrow.start(1351));
		assertTrue(borrow.isHolding());
		assertEquals(1351, borrow.heldItem());
		assertEquals(1351, hands.held);
	}

	@Test
	public void oneThingAtATime()
	{
		assertTrue(borrow.start(1351));
		assertFalse("a second tool over the first is refused", borrow.start(1265));
		assertEquals(1351, hands.held);
		assertFalse(borrow.start(0));
	}

	@Test
	public void theScrollAndTheToolShareTheSlot()
	{
		// The scroll is out for something else: no borrow over it.
		hands.hold(ErrandController.SCROLL_PROP);
		assertFalse(borrow.start(1351));
		assertFalse(borrow.isHolding());
		assertEquals("the scroll is untouched", ErrandController.SCROLL_PROP, hands.held);

		// And the other way: a tool in hand means the scroll flourish skips
		// its gesture rather than swapping the axe for a scroll.
		hands.release();
		assertTrue(borrow.start(1351));
		PropFlourish flourish = new PropFlourish(follower, hands);
		assertFalse(flourish.start(ErrandController.SCROLL_PROP, ErrandController.READ_SCROLL_POSE, 5));
		assertFalse(flourish.isActive());
		assertEquals("the axe stays", 1351, hands.held);
		assertEquals("no writing pose over an axe", 0, follower.poseOverride);
		assertTrue("and the feet were not planted for nothing", follower.following);
	}

	@Test
	public void theReturnBeatBowsAndReleasesOnTheSameCall()
	{
		borrow.start(1351);
		borrow.giveBack(true);

		assertEquals(Collections.singletonList(Borrow.BOW_ANIMATION), follower.animations);
		assertEquals("released on the same frame as the bow starts", 0, hands.held);
		assertFalse(borrow.isHolding());
	}

	@Test
	public void aHatEarnsASaluteInstead()
	{
		// A hat is worn, not held: its own slot, over whatever is in the hand.
		// On its own it is returned with a salute; with a tool, the tool's bow.
		assertTrue(borrow.wearHat(10941));
		assertTrue(borrow.hasHat());
		assertFalse("a hat is not something held", borrow.isHolding());
		assertEquals(10941, hands.worn);
		borrow.giveBack(true);
		assertEquals(Collections.singletonList(2112), follower.animations);
		assertEquals(0, hands.worn);

		follower.animations.clear();
		assertTrue(borrow.start(1351));
		assertTrue(borrow.wearHat(10941));
		assertTrue("both at once", borrow.isHolding() && borrow.hasHat());
		borrow.giveBack(true);
		assertEquals("the tool's bow wins", Collections.singletonList(858), follower.animations);
		assertEquals(0, hands.held);
		assertEquals(0, hands.worn);
	}

	@Test
	public void aShortSessionGivesItBackQuietly()
	{
		borrow.start(1351);
		borrow.giveBack(false);
		assertTrue(follower.animations.isEmpty());
		assertEquals(0, hands.held);
	}

	@Test
	public void abortIsSilentAndSafeWithNothingHeld()
	{
		borrow.abort();
		assertEquals(0, hands.held);
		assertTrue(follower.animations.isEmpty());

		borrow.start(1351);
		borrow.abort();
		assertEquals(0, hands.held);
		assertFalse(borrow.isHolding());
		assertTrue("an abort never bows", follower.animations.isEmpty());

		borrow.abort();
		assertEquals(0, hands.held);
	}

	@Test
	public void anItemTheHandsDeclineIsNotHeld()
	{
		// The torch is only borrowed if the cache says it is wearable; the
		// hands say so by holding it or not, and the borrow believes them.
		hands.unwearable.add(Borrow.TORCH);
		assertFalse(borrow.start(Borrow.TORCH));
		assertFalse("nothing must be thought held that is not", borrow.isHolding());
		assertEquals(0, hands.held);
	}

	@Test
	public void theToolTableFollowsTheWornOneOrTheBronze()
	{
		Set<Integer> none = Collections.emptySet();
		assertEquals(1351, Borrow.toolFor("woodcutting", 879, none));
		assertEquals(6739, Borrow.toolFor("woodcutting", 879, worn(6739)));
		assertEquals("every tier is known", 23673, Borrow.toolFor("woodcutting", 879, worn(23673)));
		assertEquals("the felling axes too", 28217, Borrow.toolFor("woodcutting", 879, worn(28217)));

		// The hand beats the table, because the table always falls behind the
		// game: an axe nobody has heard of is still the axe in the hand.
		assertEquals("an unknown axe in the hand is the axe",
			99001, Borrow.toolFor("woodcutting", 879, none, 99001, "Obsidian felling axe"));
		assertEquals("an unknown pickaxe likewise",
			99002, Borrow.toolFor("mining", 625, none, 99002, "Obsidian pickaxe"));
		assertEquals("a battleaxe is not a felling axe",
			1351, Borrow.toolFor("woodcutting", 879, none, 1373, "Rune battleaxe"));
		assertEquals("nor is a pickaxe",
			1351, Borrow.toolFor("woodcutting", 879, none, 11920, "Dragon pickaxe"));
		assertEquals("an unreadable name falls back to the table",
			6739, Borrow.toolFor("woodcutting", 879, worn(6739), 6739, null));
		assertEquals("and the ornament kits", 25378, Borrow.toolFor("woodcutting", 879, worn(25378)));
		assertEquals(1265, Borrow.toolFor("mining", 625, none));
		assertEquals(11920, Borrow.toolFor("mining", 625, worn(11920)));
		assertEquals(20014, Borrow.toolFor("mining", 625, worn(20014)));
		assertEquals("the ornament pickaxes too", 23677, Borrow.toolFor("mining", 625, worn(23677)));

		// Work with no tool of its own is what the outfit's hat is for.
		assertEquals(0, Borrow.toolFor("thieving", -1, none));
		assertEquals(5554, Borrow.hatFor("thieving", worn(5554, 5553, 5555, 5557)));

		// Fishing goes by the animation, since "Fish" says nothing about the hand.
		assertEquals(311, Borrow.toolFor("fishing", 618, none));
		assertEquals(10129, Borrow.toolFor("fishing", 618, worn(10129)));
		assertEquals(307, Borrow.toolFor("fishing", 622, none));
		assertEquals(309, Borrow.toolFor("fishing", 623, worn(309)));
		assertEquals("a small net is a net", 303, Borrow.toolFor("fishing", 621, none));
		assertEquals("a big net likewise", 305, Borrow.toolFor("fishing", 620, none));
		assertEquals("and a cage is a pot", 301, Borrow.toolFor("fishing", 619, none));

		assertEquals(5329, Borrow.toolFor("farming", -1, none));
		assertEquals(8794, Borrow.toolFor("construction", -1, none));
		assertEquals(8987, Borrow.toolFor("firemaking", -1, none));
		assertEquals("the hammer for the anvil", 4162, Borrow.toolFor("smithing", 898, none));
		assertEquals("the net for the hunt", 10010, Borrow.toolFor("hunter", -1, none));
		assertEquals("cooking holds nothing", 0, Borrow.toolFor("cooking", 896, none));
		assertEquals(0, Borrow.toolFor("", 879, worn(6739)));
		assertEquals(0, Borrow.toolFor(null, 879, none));
	}

	@Test
	public void theHatOfAFullOutfitAndOnlyThat()
	{
		// The whole lumberjack set on: the hat, for a woodcutting session.
		Set<Integer> lumberjack = worn(10941, 10939, 10940, 10933);
		assertEquals(10941, Borrow.hatFor("woodcutting", lumberjack));
		assertEquals("the outfit is for the one skill", 0, Borrow.hatFor("mining", lumberjack));

		// Three pieces of four is not the outfit.
		assertEquals(0, Borrow.hatFor("woodcutting", worn(10941, 10939, 10940)));
		assertEquals("the hat alone is a hat", 0, Borrow.hatFor("woodcutting", worn(10941)));

		// Every set is known, and the hat is always the first of it.
		assertEquals(12013, Borrow.hatFor("mining", worn(12013, 12014, 12015, 12016)));
		assertEquals(13258, Borrow.hatFor("fishing", worn(13258, 13259, 13260, 13261)));
		assertEquals(13646, Borrow.hatFor("farming", worn(13646, 13642, 13640, 13644)));
		assertEquals(20708, Borrow.hatFor("firemaking", worn(20708, 20704, 20706, 20710)));
		assertEquals(5554, Borrow.hatFor("thieving", worn(5554, 5553, 5555, 5557)));
		assertEquals(24872, Borrow.hatFor("construction", worn(24872, 24874, 24876, 24878)));
		for (int[] outfit : Borrow.OUTFITS)
		{
			assertTrue("the hat is what earns the salute", Borrow.HATS.contains(outfit[0]));
		}

		// Graceful is worn everywhere and is for none of this work.
		Set<Integer> graceful = worn(11850, 11854, 11856, 11860);
		for (String work : new String[]{"woodcutting", "mining", "fishing", "thieving"})
		{
			assertEquals(work, 0, Borrow.hatFor(work, graceful));
		}
		assertEquals(0, Borrow.hatFor(null, lumberjack));
		assertEquals(0, Borrow.hatFor("woodcutting", null));
	}

	@Test
	public void wornItemsComeOffTheCompositionInTheGamesEncoding()
	{
		int[] raw = new int[12];
		raw[3] = 6739 + PlayerComposition.ITEM_OFFSET;
		raw[0] = 300;    // a body kit, not an item
		PlayerComposition composition = (PlayerComposition) java.lang.reflect.Proxy.newProxyInstance(
			PlayerComposition.class.getClassLoader(), new Class<?>[]{PlayerComposition.class},
			(proxy, method, args) -> "getEquipmentIds".equals(method.getName()) ? raw : null);

		Set<Integer> worn = Borrow.wornItems(composition);
		assertEquals(Collections.singleton(6739), worn);
		assertTrue(Borrow.wornItems(null).isEmpty());
	}

	private static Set<Integer> worn(int... ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}
}
