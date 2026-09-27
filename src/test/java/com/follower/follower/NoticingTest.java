package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerEvent;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPC;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the follower notices that the player has not: only things that are
 * still there and close enough to be seen, each once, each kind on its own
 * clock, and one line for each.
 */
public class NoticingTest
{
	private static final WorldPoint PLAYER = new WorldPoint(50, 50, 0);
	private static final WorldPoint FOLLOWER = new WorldPoint(49, 49, 0);
	/** Where the drop lands: at the player's feet, as a kill's does. */
	private static final WorldPoint DROP = new WorldPoint(50, 50, 0);

	/** Records every look asked for; says it is looking until told otherwise. */
	private static final class RecordingLooker implements Noticing.Looker
	{
		final List<WorldPoint> looks = new ArrayList<>();
		final List<Boolean> writes = new ArrayList<>();
		boolean looking;
		boolean refuses;

		@Override
		public boolean goAndLook(WorldPoint at, int steps, boolean write, int holdTicks)
		{
			if (refuses)
			{
				return false;
			}
			looks.add(at);
			writes.add(write);
			looking = true;
			return true;
		}

		@Override
		public boolean isLooking()
		{
			return looking;
		}
	}

	private static final class StandingFollower extends FollowerEntity
	{
		StandingFollower()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return FOLLOWER;
		}
	}

	private FakeGame game;
	private RecordingLooker looker;
	private Noticing noticing;
	private final List<TriggerEvent> events = new ArrayList<>();
	private boolean busy;
	private int idle;
	private boolean notices = true;
	private List<Map.Entry<String, WorldPoint>> landmarks = Collections.emptyList();
	private int tick;

	@Before
	public void setUp()
	{
		game = new FakeGame().at(PLAYER.getX(), PLAYER.getY(), 0);
		looker = new RecordingLooker();
		FollowerConfig config = new FollowerConfig()
		{
			@Override
			public boolean noticesThings()
			{
				return notices;
			}
		};
		noticing = new Noticing(game.client, new StandingFollower(), config, looker,
			events::add, () -> busy, () -> idle, () -> landmarks);
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			noticing.tick();
		}
	}

	/** A ground item with an id and an owner; nothing else about it is asked. */
	private static TileItem item(int id, int ownership)
	{
		return (TileItem) java.lang.reflect.Proxy.newProxyInstance(
			TileItem.class.getClassLoader(), new Class<?>[]{TileItem.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "getOwnership":
						return ownership;
					case "getQuantity":
						return 1;
					case "hashCode":
						return id;
					case "equals":
						return proxy == args[0];
					case "toString":
						return "item " + id;
					default:
						return method.getReturnType() == boolean.class ? false
							: method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}

	private void walkAway()
	{
		game.at(PLAYER.getX() + Noticing.WALKED_AWAY_TILES, PLAYER.getY(), 0);
	}

	// -------------------------------------------------------------- the drop

	@Test
	public void aDropOfYoursWalkedAwayFromIsLookedAtOnceAndSaidOnce()
	{
		tick(1);
		noticing.itemSpawned(item(4151, TileItem.OWNERSHIP_SELF), DROP, "Abyssal whip", 1_500_000);
		tick(Noticing.LEFT_AFTER_TICKS + 1);
		assertTrue("still beside it: looting takes a moment", looker.looks.isEmpty());

		walkAway();
		tick(1);
		assertEquals(Collections.singletonList(DROP), looker.looks);
		assertEquals(false, looker.writes.get(0));
		assertEquals(1, events.size());
		assertEquals("valuable", events.get(0).getPlaceholders().get("kind"));
		assertEquals("Abyssal whip", events.get(0).getPlaceholders().get("thing"));

		looker.looking = false;
		tick(Noticing.Kind.VALUABLE.cooldownTicks + 5);
		assertEquals("each thing once", 1, looker.looks.size());
	}

	@Test
	public void aSecondDropWaitsOutTheKindsClock()
	{
		// A good kill leaves two piles. Each is a thing of its own and gets
		// its look, but not one straight after the other: a pile of drops in
		// a good hour is one remark, not a commentary.
		tick(1);
		noticing.itemSpawned(item(4151, TileItem.OWNERSHIP_SELF), DROP, "Abyssal whip", 1_500_000);
		noticing.itemSpawned(item(11840, TileItem.OWNERSHIP_SELF), new WorldPoint(50, 52, 0),
			"Dragon boots", 200_000);
		walkAway();
		tick(Noticing.LEFT_AFTER_TICKS + 1);
		assertEquals(1, looker.looks.size());

		looker.looking = false;
		tick(Noticing.Kind.VALUABLE.cooldownTicks - 5);
		assertEquals("the kind's clock holds the second", 1, looker.looks.size());
		tick(10);
		assertEquals("and lets it go once it has run", 2, looker.looks.size());
	}

	@Test
	public void somebodyElsesDropAndACheapOneAreNotYourBusiness()
	{
		tick(1);
		noticing.itemSpawned(item(4151, TileItem.OWNERSHIP_OTHER), DROP, "Abyssal whip", 1_500_000);
		noticing.itemSpawned(item(526, TileItem.OWNERSHIP_SELF), DROP, "Bones", 50);
		walkAway();
		tick(Noticing.LEFT_AFTER_TICKS + 2);
		assertTrue(looker.looks.isEmpty());
	}

	@Test
	public void aDropPickedUpBeforeTheWalkIsNotThereToLookAt()
	{
		tick(1);
		TileItem whip = item(4151, TileItem.OWNERSHIP_SELF);
		noticing.itemSpawned(whip, DROP, "Abyssal whip", 1_500_000);
		noticing.itemDespawned(whip, DROP);
		walkAway();
		tick(Noticing.LEFT_AFTER_TICKS + 2);
		assertTrue("gone: nothing to call out", looker.looks.isEmpty());
	}

	@Test
	public void aNestIsWorthALookWhoeverItBelongsTo()
	{
		tick(1);
		noticing.itemSpawned(item(5070, TileItem.OWNERSHIP_NONE), DROP, "Bird nest", 300);
		walkAway();
		tick(Noticing.LEFT_AFTER_TICKS + 2);
		assertEquals(Collections.singletonList(DROP), looker.looks);
		assertEquals("nest", events.get(0).getPlaceholders().get("kind"));
	}

	@Test
	public void aDropTooFarToBeOnScreenIsLeftAlone()
	{
		tick(1);
		WorldPoint far = new WorldPoint(FOLLOWER.getX() + Noticing.VISIBLE_TILES + 1, 50, 0);
		noticing.itemSpawned(item(4151, TileItem.OWNERSHIP_SELF), far, "Abyssal whip", 1_500_000);
		game.at(30, 50, 0);
		tick(Noticing.LEFT_AFTER_TICKS + 2);
		assertTrue(looker.looks.isEmpty());
	}

	// ----------------------------------------------------------- the impling

	@Test
	public void anImplingInSightIsSteppedToward()
	{
		NPC impling = game.spawnNpc(1635, "Dragon impling", 0);
		game.moveNpc(impling, 54, 52, 0);
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals(Collections.singletonList(new WorldPoint(54, 52, 0)), looker.looks);
		assertEquals("impling", events.get(0).getPlaceholders().get("kind"));
		assertEquals("Dragon impling", events.get(0).getPlaceholders().get("thing"));
	}

	@Test
	public void anImplingThePlayerIsAlreadyOnIsNotNews()
	{
		NPC impling = game.spawnNpc(1635, "Dragon impling", 0);
		game.moveNpc(impling, 54, 52, 0);
		game.facing(impling);
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue(looker.looks.isEmpty());
	}

	// -------------------------------------------------------------- the star

	@Test
	public void aStarIsWalkedOverToAndWrittenUp()
	{
		noticing.objectSpawned("Crashed star", new WorldPoint(55, 55, 0));
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals(Collections.singletonList(new WorldPoint(55, 55, 0)), looker.looks);
		assertTrue("the notes come out for a star", looker.writes.get(0));
		assertEquals("star", events.get(0).getPlaceholders().get("kind"));

		looker.looking = false;
		noticing.objectDespawned("Crashed star", new WorldPoint(55, 55, 0));
		noticing.objectSpawned("Crashed star", new WorldPoint(56, 55, 0));
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals("the kind's cooldown holds the next", 1, looker.looks.size());
	}

	// ---------------------------------------------------------- the landmark

	@Test
	public void aSignpostThePlayerStopsBesideIsRead()
	{
		landmarks = Collections.singletonList(
			new AbstractMap.SimpleImmutableEntry<>("Signpost", new WorldPoint(51, 50, 0)));
		idle = Noticing.LANDMARK_AFTER_IDLE_TICKS - 1;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue("not until they have stood a while", looker.looks.isEmpty());

		idle = Noticing.LANDMARK_AFTER_IDLE_TICKS;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals(Collections.singletonList(new WorldPoint(51, 50, 0)), looker.looks);
		assertTrue("read with the notes out", looker.writes.get(0));
		assertEquals("landmark", events.get(0).getPlaceholders().get("kind"));
		assertEquals("Signpost", events.get(0).getPlaceholders().get("thing"));

		looker.looking = false;
		tick(Noticing.Kind.LANDMARK.cooldownTicks + Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals("the same sign is not read twice", 1, looker.looks.size());
	}

	@Test
	public void aLandmarkOutOfReachIsNotWalkedTo()
	{
		landmarks = Collections.singletonList(
			new AbstractMap.SimpleImmutableEntry<>("Statue", new WorldPoint(56, 50, 0)));
		idle = 100;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue(looker.looks.isEmpty());
	}

	@Test
	public void theLandmarkListIsTheOneTheScanUses()
	{
		assertTrue(Noticing.isLandmark("Signpost"));
		assertTrue(Noticing.isLandmark("statue"));
		assertFalse(Noticing.isLandmark("Tree"));
		assertFalse(Noticing.isLandmark(null));
	}

	// ----------------------------------------------------------- the gates

	@Test
	public void nothingWhileBusyOrLookingOrSwitchedOff()
	{
		NPC impling = game.spawnNpc(1635, "Dragon impling", 0);
		game.moveNpc(impling, 54, 52, 0);
		busy = true;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue(looker.looks.isEmpty());

		busy = false;
		looker.looking = true;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue("one look at a time", looker.looks.isEmpty());

		looker.looking = false;
		notices = false;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue(looker.looks.isEmpty());
	}

	@Test
	public void aLookTheFeetRefuseLeavesTheThingForLater()
	{
		NPC impling = game.spawnNpc(1635, "Dragon impling", 0);
		game.moveNpc(impling, 54, 52, 0);
		looker.refuses = true;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertTrue(events.isEmpty());
		looker.refuses = false;
		tick(Noticing.SCAN_EVERY_TICKS + 1);
		assertEquals("not memoised as a refusal", 1, looker.looks.size());
	}
}
