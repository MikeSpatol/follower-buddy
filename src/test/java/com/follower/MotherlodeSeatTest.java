package com.follower;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Mining in a Motherlode tunnel, against the REAL {@link FollowerEntity}.
 *
 * <p>The play report: "while im mining in motherlode mine the follower will
 * walk back and fourth between the tile to my side and the tile behind me".
 * The tunnels are two tiles wide and the veins are in their walls, so the
 * miner's seat - two back and one to the side - is rock. The real pathing
 * does not refuse rock: closest approach stops beside it and reports
 * success. The seat was written down on the rock, found not to be ground
 * on the next tick, dropped, and taken again on the one after, for as long
 * as the player mined. The recording fake in {@link SkillingSessionTest}
 * refuses a blocked tile outright, which is how that loop got past it; this
 * drives the real stay, path and releases, the way {@link FireLineTest} does.
 */
public class MotherlodeSeatTest
{
	/** The tunnel runs north and south, two tiles wide: x 51 and 52. */
	private static final int WEST = 51;
	private static final int EAST = 52;

	/** The vein, in the east wall, and the tile it is worked from. */
	private static final WorldPoint VEIN = new WorldPoint(53, 52, 0);
	private static final WorldPoint WORK = new WorldPoint(52, 52, 0);

	/** HUMAN_MINING_RUNE_PICKAXE_WALL. */
	private static final int MINE_THE_WALL = 6752;

	private final FakeGame game = new FakeGame().withCollision();
	private final FollowerEntity follower = new FollowerEntity(game.client, null);
	private final int[] held = new int[1];

	@Test
	public void theMinersCompanyTakesOneSeatAndStaysOnIt()
	{
		tunnel();

		// Walked up the tunnel to the vein, the follower a tile behind.
		walkIn(new WorldPoint(EAST, 49, 0), new WorldPoint(EAST, 50, 0), new WorldPoint(EAST, 51, 0));
		SkillingController skilling = startMining();
		int[] ticks = {1};
		WorldPoint seat = settle(skilling, ticks);
		int tick = ticks[0];

		// And for as long as the player mines, not a step.
		for (int t = 0; t < 40; t++)
		{
			game.tick(++tick);
			skilling.tick();
			frame();
			assertTrue("still working, tick " + tick, skilling.isWorking());
			assertEquals("the seat never changes, tick " + tick, seat, skilling.getSeat());
			assertEquals("nor the stay, tick " + tick, seat, follower.getStayTile());
			assertEquals("nor where it stands, tick " + tick, seat, tile());
		}
	}

	/**
	 * Two veins side by side in the wall, worked turn about: a step along the
	 * wall to the next vein used to cost the follower a step too, and back
	 * again on the way back, one each time the player switched.
	 */
	@Test
	public void switchingBetweenNeighbouringVeinsIsNoReasonToGetUp()
	{
		tunnel();
		walkIn(new WorldPoint(EAST, 49, 0), new WorldPoint(EAST, 50, 0), new WorldPoint(EAST, 51, 0));
		SkillingController skilling = startMining();
		int[] tick = {1};
		WorldPoint seat = settle(skilling, tick);

		WorldPoint[] veins = {new WorldPoint(53, 53, 0), VEIN, new WorldPoint(53, 53, 0), VEIN};
		for (WorldPoint vein : veins)
		{
			WorldPoint work = new WorldPoint(vein.getX() - 1, vein.getY(), 0);
			skilling.noteClick("Mine", "Ore vein", 26661, vein, vein, null);
			game.at(work.getX(), work.getY(), 0);
			watchPlayer();
			for (int t = 0; t < SkillingController.SEAT_AFTER_TICKS + 4; t++)
			{
				game.tick(++tick[0]);
				skilling.tick();
				frame();
				assertEquals("not a step at " + vein + ", tick " + tick[0], seat, tile());
			}
			assertEquals("the same seat for " + vein, seat, skilling.getSeat());
		}
	}

	/**
	 * Every seat good ground but fenced in, so the pathing only ever gets
	 * beside one. Walking to each to find that out, and letting go when
	 * none arrived, left the last walk queued: out to it and back every
	 * ten ticks, the same shuffle slower. The walk is asked about first.
	 */
	@Test
	public void seatsThatCanOnlyBeApproachedAreNotWalkedTo()
	{
		game.blockTile(VEIN.getX(), VEIN.getY());
		for (WorldPoint c : SkillingController.seatCandidates(WORK, VEIN, VEIN,
			java.util.Collections.emptyList(), "mining"))
		{
			int x = c.getX();
			int y = c.getY();
			game.wallAt(x, y, CollisionDataFlag.BLOCK_MOVEMENT_NORTH
				| CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH
				| CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			game.wallAt(x, y + 1, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			game.wallAt(x + 1, y, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			game.wallAt(x, y - 1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
			game.wallAt(x - 1, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		}

		// Walked in from the west, the follower a tile behind.
		walkIn(new WorldPoint(49, 52, 0), new WorldPoint(50, 52, 0), new WorldPoint(51, 52, 0));
		SkillingController skilling = startMining();
		int tick = 1;
		for (int t = 0; t < 5; t++)
		{
			game.tick(++tick);
			skilling.tick();
			frame();
		}
		WorldPoint stood = tile();

		for (int t = 0; t < 60; t++)
		{
			game.tick(++tick);
			skilling.tick();
			frame();
			assertTrue("still working, tick " + tick, skilling.isWorking());
			assertEquals("not a step, tick " + tick, stood, tile());
		}
		assertTrue("no seat to be had", skilling.getSeat() == null);
	}

	private static final String[] MINE = {"Mine", null, null, null, "Examine"};
	private static final String[] NOTHING = {null, null, null, null, "Examine"};

	/**
	 * The mine as the game has it: veins in the tunnel's east wall, each worked
	 * from the tile across it, which carries the vein's own edge.
	 */
	private void veins(int... ys)
	{
		tunnel();
		game.animation(MINE_THE_WALL, 74);
		for (int y : ys)
		{
			game.placeWall(26661, "Ore vein", MINE, VEIN.getX(), y, 0, 1);
			game.wallAt(EAST, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		}
	}

	private void tickOnce(SkillingController skilling, int[] tick)
	{
		game.tick(++tick[0]);
		skilling.tick();
		frame();
	}

	/**
	 * Play request: "if im in motherloade mine then i would want them to look
	 * like they are harvesting some other ore vein". The player's vein has no
	 * room - its one work tile is theirs - so the company mines the next one
	 * along, from across its wall, facing it, with the pickaxe.
	 */
	@Test
	public void withNoRoomAtItsVeinTheCompanyMinesTheNextOneAlong()
	{
		veins(VEIN.getY(), 54);
		walkIn(new WorldPoint(EAST, 49, 0), new WorldPoint(EAST, 50, 0), new WorldPoint(EAST, 51, 0));
		SkillingController skilling = startMining();
		int[] tick = {1};
		WorldPoint seat = settle(skilling, tick);
		assertEquals("across the next vein's wall", new WorldPoint(EAST, 54, 0), seat);
		assertEquals("facing it", new WorldPoint(53, 54, 0), get("stayFaceTile"));

		tickOnce(skilling, tick);
		assertEquals("mining it", MINE_THE_WALL, follower.getPoseOverride());
		assertEquals("with the pickaxe", 1265, held[0]);
		for (int t = 0; t < 30; t++)
		{
			tickOnce(skilling, tick);
			assertEquals("not a step, tick " + tick[0], seat, tile());
		}
	}

	@Test
	public void whenItsVeinEmptiesItStopsThenMinesAnotherLiveOne()
	{
		veins(VEIN.getY(), 54, 57);
		walkIn(new WorldPoint(EAST, 49, 0), new WorldPoint(EAST, 50, 0), new WorldPoint(EAST, 51, 0));
		SkillingController skilling = startMining();
		int[] tick = {1};
		settle(skilling, tick);
		tickOnce(skilling, tick);
		assertEquals(MINE_THE_WALL, follower.getPoseOverride());

		game.placeWall(26665, "Depleted vein", NOTHING, VEIN.getX(), 54, 0, 1);
		tickOnce(skilling, tick);
		assertEquals("the swing stops with the vein", 0, follower.getPoseOverride());

		WorldPoint next = new WorldPoint(EAST, 57, 0);
		for (int t = 0; t < 20 && !(next.equals(tile()) && follower.getPoseOverride() == MINE_THE_WALL); t++)
		{
			tickOnce(skilling, tick);
		}
		assertEquals("over to the next live vein", next, tile());
		assertEquals("never the emptied one", new WorldPoint(53, 57, 0), get("stayFaceTile"));
		assertEquals("and at it", MINE_THE_WALL, follower.getPoseOverride());
	}

	@Test
	public void thePlayerTakingItsVeinSendsItToAnother()
	{
		veins(VEIN.getY(), 54);
		walkIn(new WorldPoint(EAST, 49, 0), new WorldPoint(EAST, 50, 0), new WorldPoint(EAST, 51, 0));
		SkillingController skilling = startMining();
		int[] tick = {1};
		WorldPoint seat = settle(skilling, tick);
		assertEquals(new WorldPoint(EAST, 54, 0), seat);

		// The player clicks the follower's vein and steps along to work it.
		skilling.noteClick("Mine", "Ore vein", 26661, new WorldPoint(53, 54, 0),
			new WorldPoint(53, 54, 0), null);
		game.at(EAST, 53, 0);
		watchPlayer();
		tickOnce(skilling, tick);
		game.at(EAST, 54, 0);
		watchPlayer();
		for (int t = 0; t < 20 && !(new WorldPoint(EAST, 52, 0).equals(tile())
			&& follower.getPoseOverride() == MINE_THE_WALL); t++)
		{
			tickOnce(skilling, tick);
		}
		assertEquals("to the vein the player left", new WorldPoint(EAST, 52, 0), tile());
		assertEquals(VEIN, get("stayFaceTile"));
		assertEquals(MINE_THE_WALL, follower.getPoseOverride());
	}

	/** Rock all round but for the tunnel, two tiles wide. */
	private void tunnel()
	{
		for (int x = 40; x <= 64; x++)
		{
			for (int y = 40; y <= 64; y++)
			{
				if (x != WEST && x != EAST)
				{
					game.blockTile(x, y);
				}
			}
		}
	}

	/** Seated, and walked there; the seat. */
	private WorldPoint settle(SkillingController skilling, int[] tick)
	{
		WorldPoint seat = null;
		for (int t = 0; t < 20 && (seat == null || !seat.equals(tile())); t++)
		{
			game.tick(++tick[0]);
			skilling.tick();
			frame();
			seat = skilling.getSeat();
		}
		assertTrue("never settled on a seat it stands on: a seat on rock is dropped and"
			+ " taken again every tick", seat != null && seat.equals(tile()));
		assertTrue("on ground, not rock", follower.goodGround(seat));
		return seat;
	}

	/** The player walks in over three tiles to the work, the follower starting where given. */
	private void walkIn(WorldPoint followerAt, WorldPoint first, WorldPoint second)
	{
		game.at(first.getX(), first.getY(), 0);
		set("spawned", true);
		standOn(followerAt);
		set("simTile", followerAt);
		watchPlayer();
		game.at(second.getX(), second.getY(), 0);
		watchPlayer();
		game.at(WORK.getX(), WORK.getY(), 0);
		watchPlayer();
		for (int i = 0; i < 3; i++)
		{
			frame();
		}
	}

	/** The controller, and the click and first swing at the vein. */
	private SkillingController startMining()
	{
		SkillingController skilling = new SkillingController(game.client, follower,
			new FollowerConfig()
			{
			}, new ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
					held[0] = itemId;
				}

				@Override
				public void release()
				{
					held[0] = 0;
				}

				@Override
				public boolean isHolding()
				{
					return held[0] != 0;
				}

				@Override
				public void wear(int itemId)
				{
				}

				@Override
				public void takeOff()
				{
				}

				@Override
				public boolean isWearing()
				{
					return false;
				}
			}, event -> { }, () -> false, () -> false, () -> false);
		game.tick(1);
		skilling.noteClick("Mine", "Ore vein", 26661, VEIN, VEIN, null);
		game.animating(MINE_THE_WALL);
		return skilling;
	}

	// ---------------------------------------------------------- plumbing

	private Object get(String name)
	{
		try
		{
			Field field = FollowerEntity.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(follower);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(name, e);
		}
	}

	private void set(String name, Object value)
	{
		try
		{
			Field field = FollowerEntity.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(follower, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(name, e);
		}
	}

	private Object invoke(String name, Class<?>[] types, Object... args)
	{
		try
		{
			Method method = FollowerEntity.class.getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(follower, args);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(name, e);
		}
	}

	private void standOn(WorldPoint at)
	{
		set("tile", at);
		set("fineWX", at.getX() * 128L + 64);
		set("fineWY", at.getY() * 128L + 64);
	}

	private WorldPoint tile()
	{
		return (WorldPoint) get("tile");
	}

	private WorldPoint player()
	{
		return game.client.getLocalPlayer().getWorldLocation();
	}

	private void watchPlayer()
	{
		invoke("watchPlayer", new Class<?>[]{Player.class}, game.client.getLocalPlayer());
	}

	@SuppressWarnings("unchecked")
	private Deque<WorldPoint> path(String name)
	{
		return (Deque<WorldPoint>) get(name);
	}

	/** One tick of updateFrame's feet, in its order, with the player still. */
	private void frame()
	{
		WorldPoint stay = (WorldPoint) get("stayTile");
		WorldPoint follow = (WorldPoint) get("playerFollowTile");
		WorldPoint sim = (WorldPoint) get("simTile");
		if (stay != null)
		{
			if (!stay.equals(tile()) && path("route").isEmpty() && path("serverPath").isEmpty())
			{
				invoke("extendPathBfs", new Class<?>[]{WorldPoint.class, WorldPoint.class}, stay, player());
			}
		}
		else if (path("serverPath").isEmpty() && path("route").isEmpty() && follow != null
			&& !follow.equals(sim))
		{
			invoke("extendPathBfs", new Class<?>[]{WorldPoint.class, WorldPoint.class}, follow,
				follow.equals(player()) ? follow : null);
		}
		if (!path("serverPath").isEmpty())
		{
			invoke("releaseSteps", new Class<?>[]{});
		}
		Deque<WorldPoint> route = path("route");
		@SuppressWarnings("unchecked")
		Deque<Boolean> run = (Deque<Boolean>) get("routeRun");
		while (!route.isEmpty())
		{
			standOn(route.pollFirst());
			run.pollFirst();
		}
	}
}
