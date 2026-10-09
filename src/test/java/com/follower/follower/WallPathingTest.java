package com.follower.follower;

import com.follower.sim.FakeGame;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The follower must never walk through a wall.
 *
 * <p>Everything else about it is an illusion held up by obeying the same rules
 * as everything else in the world. A model sliding through stone does not look
 * like a bug in the pathing, it looks like the whole thing is fake - which it
 * is, and the point is that you should not be able to tell.
 *
 * <p>The greedy stepper used to end with a raw step "so missing collision data
 * never freezes the follower". But no data already reads as open, so that
 * branch only ever fired when collision was PRESENT and said no. Indoors, where
 * the player is often two tiles away through a partition, it fired constantly.
 */
public class WallPathingTest
{
	private FollowerEntity followerOn(FakeGame game)
	{
		return new FollowerEntity(game.client, null);
	}

	@Test
	public void aWallStopsTheGreedyStepper()
	{
		// Standing at (10,10) wanting (12,10), with a wall on the east edge of
		// (10,10). There is no legal step east, so there is no step at all.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertFalse("the wall has to be readable at all",
			follower.canStep(from, 1, 0));
		assertNull("the follower must not step through it",
			follower.stepToward(from, new WorldPoint(12, 10, 0), false));
	}

	@Test
	public void itStepsRoundTheWallWhenThereIsAWayRound()
	{
		// The same wall, but the target is up and to the right, so the stepper
		// has a legal diagonal-turned-cardinal to take instead of giving up.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		WorldPoint step = follower.stepToward(
			new WorldPoint(10, 10, 0), new WorldPoint(12, 14, 0), false);
		assertNotNull("blocked one way is not blocked every way", step);
		assertTrue("and whatever it picked has to be legal",
			follower.canStep(new WorldPoint(10, 10, 0),
				step.getX() - 10, step.getY() - 10));
	}

	@Test
	public void retracingThePlayersOwnPathMayStepAnyway()
	{
		// The one place the raw step survives. These tiles were walked by the
		// player a moment ago, so a collision reading that disagrees is the
		// reading that is wrong, and refusing would leave the follow tile
		// unknown - which is worse than trusting the footprints.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		FollowerEntity follower = followerOn(game);

		assertNotNull("the player's own path is always walkable",
			follower.stepToward(new WorldPoint(10, 10, 0),
				new WorldPoint(12, 10, 0), true));
	}

	@Test
	public void aFullyBlockedTileIsNeverSteppedOnto()
	{
		FakeGame game = new FakeGame().withCollision();
		game.blockTile(11, 10);
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertFalse(follower.canStep(from, 1, 0));
		assertNull("a solid tile is not a step",
			follower.stepToward(from, new WorldPoint(11, 10, 0), false));
	}

	@Test
	public void noCollisionDataStillReadsAsOpen()
	{
		// An instance edge, or a scene that has not loaded. Refusing here would
		// freeze the follower for a reason that is not a wall - which is what
		// the raw fallback was originally defending against, and it is already
		// handled without it.
		FakeGame game = new FakeGame();
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertTrue("no data means no obstacle", follower.canStep(from, 1, 0));
		assertNotNull("so the follower still moves",
			follower.stepToward(from, new WorldPoint(12, 10, 0), false));
	}

	@Test
	public void aWallIsSeenFromBothSides()
	{
		// The game stores a wall on one tile's edge; the tile on the other side
		// carries the opposite bit for the same edge. Checking only the near
		// side would let the follower through half of every wall in the game.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(11, 10, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		FollowerEntity follower = followerOn(game);

		assertFalse("approaching from the west",
			follower.canStep(new WorldPoint(10, 10, 0), 1, 0));
		assertFalse("and from the east",
			follower.canStep(new WorldPoint(11, 10, 0), -1, 0));
	}

	@Test
	public void aDiagonalCannotSlipPastACornerPost()
	{
		// Cutting a corner diagonally past two walls that meet is the classic
		// way through a building, and it is exactly what the game forbids.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		FollowerEntity follower = followerOn(game);

		assertFalse("the corner is shut",
			follower.canStep(new WorldPoint(10, 10, 0), 1, 1));
	}

	// ------------------------------------------------------------- teleports

	/**
	 * Walking obeys the walls; snapping used to ignore them entirely.
	 *
	 * <p>Every teleport, plane change and scene recovery put the follower one
	 * step west of the player by arithmetic alone, and arithmetic does not
	 * know about stone. Play report: the follower teleporting onto tiles it
	 * could not stand on.
	 */
	@Test
	public void aSnapNeverLandsSomewhereItCouldNotWalkTo()
	{
		FakeGame game = new FakeGame().withCollision();
		// The tile a snap wants - one west of the player - is solid.
		game.wallAt(9, 10, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		FollowerEntity follower = followerOn(game);

		WorldPoint player = new WorldPoint(10, 10, 0);
		WorldPoint wanted = new WorldPoint(9, 10, 0);
		WorldPoint landing = follower.landingTile(wanted, player);

		assertFalse("it landed inside the scenery it was told to land in",
			wanted.equals(landing));
		// Not necessarily adjacent - going round the blocked tile can cost two
		// steps - but it must be somewhere connected to the player rather than
		// a hole on the far side of a wall.
		assertNotNull("whatever it chose has to be walkable ground",
			follower.stepToward(landing, player, false));
	}

	@Test
	public void aTileAThrallCanStandOnIsNotAlwaysOneTheFollowerCan()
	{
		// The gap that outlives possession. A thrall stands behind counters,
		// over water and inside scenery quite legally, and while possessed the
		// follower copies it exactly - correctly, because it IS the thrall.
		// The moment that ends, the same tile is just somewhere it cannot be,
		// so ending the hold there had to stop being unconditional.
		FakeGame game = new FakeGame().withCollision();
		game.wallAt(12, 12, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		FollowerEntity follower = followerOn(game);

		assertFalse("a solid tile is not somewhere to be left standing",
			follower.standable(new WorldPoint(12, 12, 0)));
		assertTrue("and open ground still is",
			follower.standable(new WorldPoint(10, 10, 0)));
	}

	@Test
	public void aPlayerBoxedInGetsTheirOwnTileShared()
	{
		// Nowhere legal at all: standing on top of the player for a moment is
		// a far smaller lie than standing inside a wall, and their tile is
		// standable by definition because they are standing on it.
		FakeGame game = new FakeGame().withCollision();
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				game.wallAt(10 + dx, 10 + dy, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
			}
		}
		FollowerEntity follower = followerOn(game);

		WorldPoint player = new WorldPoint(10, 10, 0);
		assertEquals("with nowhere else to go it must share the player's tile",
			player,
			follower.landingTile(new WorldPoint(9, 10, 0), player));
	}

	/**
	 * Aboard a boat the player is in a sub-world laid over the sea, with its
	 * own collision map, and that map is padded: the client stores scene
	 * (x, y) at [x + 1][y + 1]. Read unshifted, every tile answers for its
	 * neighbour, which on a real deck made the facilities read open and the
	 * walkway between them read blocked - so the follower walked the
	 * facilities. The sea's map underneath says nothing about the deck at all.
	 */
	@Test
	public void aboardABoatItReadsTheDecksMapNotTheSeas()
	{
		FakeGame game = new FakeGame().withCollision().aboardBoat();
		game.boatBlocksTile(4, 8);
		FollowerEntity follower = followerOn(game);

		WorldPoint from = new WorldPoint(3, 8, 0);
		assertFalse("the sea's map is open here; the deck's is not, and the deck's is the one that counts",
			follower.canStep(from, 1, 0));
		assertFalse("nor is it a place to stand",
			follower.standable(new WorldPoint(4, 8, 0)));
		assertTrue("the tile next to it is fine, which an unshifted read would deny",
			follower.standable(new WorldPoint(3, 7, 0)));
		assertTrue("and so is the one on the other side",
			follower.standable(new WorldPoint(5, 8, 0)));
	}

	/**
	 * Aboard, the boat is the whole world. The sea beside the hull resolves to
	 * the top-level view, whose map may well read open there - or be missing
	 * altogether, which the follower otherwise treats as open. Neither is a
	 * licence to stand in the water.
	 */
	@Test
	public void aboardABoatTheSeaBesideTheHullIsNotAPlaceToStand()
	{
		FakeGame game = new FakeGame().withCollision().aboardBoat();
		FollowerEntity follower = followerOn(game);

		assertFalse("just past the hull, open on the sea's map",
			follower.standable(new WorldPoint(FakeGame.BOAT_WIDTH, 8, 0)));
		assertFalse("and no stepping off the deck onto it",
			follower.canStep(new WorldPoint(FakeGame.BOAT_WIDTH - 1, 8, 0), 1, 0));
		assertTrue("the deck itself is still fine",
			follower.standable(new WorldPoint(FakeGame.BOAT_WIDTH - 1, 8, 0)));

		FollowerEntity unmapped = followerOn(new FakeGame().withCollision().aboardBoat().seaUnmapped());
		assertFalse("nor where the sea has no map at all",
			unmapped.standable(new WorldPoint(FakeGame.BOAT_WIDTH, 8, 0)));
		assertTrue(unmapped.standable(new WorldPoint(FakeGame.BOAT_WIDTH - 1, 8, 0)));
	}

	/**
	 * Boarding is a jump into a world whose map is still filling in. The
	 * follower lands the instant the player's coordinates change, and the
	 * deck's facilities reach the map over the ticks after, so the tile it
	 * landed on can turn into a cannon underneath it. Aboard, that is a
	 * reason to move. So it is on land (play test, 7 Oct: a bank booth).
	 */
	@Test
	public void aboardABoatAFacilityArrivingUnderfootIsAReasonToMove()
	{
		FakeGame game = new FakeGame().withCollision().aboardBoat();
		FollowerEntity follower = followerOn(game);
		WorldPoint landed = new WorldPoint(3, 8, 0);

		assertFalse("an open deck tile is fine to stand on",
			follower.blockedUnderfoot(landed, game.player));
		game.boatBlocksTile(3, 8);
		assertTrue("until the facility arrives on it",
			follower.blockedUnderfoot(landed, game.player));
	}

	/**
	 * Play test, 7 Oct: the follower was found standing in a bank booth,
	 * twice. Placed while the area was still loading, it read a map that did
	 * not show the booth, and on land nothing ever looked at its own tile
	 * again.
	 */
	@Test
	public void aFollowerLeftInABankBoothIsMovedOut()
	{
		FakeGame game = new FakeGame().withCollision().at(10, 10, 0);
		for (int y = 7; y <= 13; y++)
		{
			// The row of booths, west of the player.
			game.wallAt(9, y, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
		}
		FollowerEntity follower = followerOn(game);
		WorldPoint player = new WorldPoint(10, 10, 0);
		WorldPoint booth = new WorldPoint(9, 10, 0);

		assertFalse("a booth is not floor", follower.standable(booth));
		assertTrue("so standing in one is a reason to move", follower.blockedUnderfoot(booth, game.player));
		WorldPoint out = follower.landingTile(booth, player);
		assertTrue("and where it moves to is floor: " + out, follower.standable(out));

		game.gameState(GameState.LOADING);
		assertFalse("but not judged from a map still loading", follower.blockedUnderfoot(booth, game.player));
		game.gameState(GameState.LOGGED_IN);
		game.wallAt(10, 10, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
		assertFalse("and never moved off the player's own tile", follower.blockedUnderfoot(player, game.player));
	}

	/**
	 * The placements made from the frame callback, which runs while an area
	 * is still loading too, wait for it to load: then the map is the new
	 * area's, and a booth beside the player reads as a booth. The frame loop
	 * needs a live client, so the guards are read out of the source.
	 */
	@Test
	public void noPlacementIsMadeFromAMapStillLoading() throws java.io.IOException
	{
		String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
			"src/main/java/com/follower/follower/FollowerEntity.java")),
			java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
		String loaded = "client.getGameState() == GameState.LOGGED_IN";
		assertTrue("the teleport landing waits for the area to load",
			source.contains("if (" + loaded + " && (landed || now - vanishedAtMs > 8000))"));
		assertTrue("so does the leash and plane snap",
			source.contains("if (client.getGameState() != GameState.LOGGED_IN)\n\t\t\t{\n"
				+ "\t\t\t\t// Snapped from real data once the area has loaded.\n\t\t\t\treturn;"));
		assertTrue("and the hidden-object watchdog",
			source.contains("if (!object.isActive() && !hiddenForNpcOverlap && " + loaded + ")"));
	}

	/**
	 * Play test, 8 Oct: logging in at the top-floor bank of Lumbridge castle
	 * put the follower inside a bank booth, on a fully loaded map. Two booths
	 * in that row ignore collision and the floor under them is not blocked,
	 * so the map reads them open while the booth's model fills the tile.
	 * Laid out as the dump found it, on one floor here: the player south of
	 * the row, the closed booths at either end solid, the two open ones
	 * between them, and the floor behind the row blocked.
	 */
	@Test
	public void aBoothTheMapCallsOpenIsStillNoPlaceToBePut()
	{
		FakeGame game = new FakeGame().withCollision().at(10, 10, 0);
		game.blockTile(8, 11).blockTile(11, 11);
		for (int x = 8; x <= 11; x++)
		{
			game.blockTile(x, 12);
		}
		// The Bank booth and its neighbour: placed as type 10, 224 tall.
		game.furniture(9, 11, 0, 10, 0, 224).furniture(10, 11, 0, 10, 0, 224);
		FollowerEntity follower = followerOn(game);
		WorldPoint player = new WorldPoint(10, 10, 0);
		java.util.List<WorldPoint> booths = java.util.Arrays.asList(
			new WorldPoint(9, 11, 0), new WorldPoint(10, 11, 0));

		assertTrue("the map really does call the booth open", follower.standable(booths.get(1)));

		// Facing south, as after a login, behind is the booth straight north;
		// facing south-east it is the other one.
		for (int jau : new int[]{0, 1792})
		{
			WorldPoint behind = follower.restingTileBehind(game.turned(jau).player, 2);
			assertNotNull(behind);
			assertFalse("facing " + jau + " it was put in a booth: " + behind, booths.contains(behind));
			assertTrue(follower.standable(behind));
		}

		for (WorldPoint booth : booths)
		{
			WorldPoint landing = follower.landingTile(booth, player);
			assertFalse("told to land in a booth, it landed in one: " + landing, booths.contains(landing));
			assertTrue(follower.standable(landing));
			assertFalse("and not on the player", player.equals(landing));
		}
	}

	/** The band is a body standing on the tile; the types are how furniture is placed. */
	@Test
	public void onlyFurnitureAtBodyHeightIsFurniture()
	{
		FakeGame game = new FakeGame().withCollision()
			.furniture(5, 5, 0, 10, 0, 18)
			.furniture(6, 5, 0, 10, 512, 600)
			.furniture(7, 5, 0, 0, 0, 224)
			.furniture(8, 5, 0, 10, 128, 200)
			.furniture(9, 5, 0, 11, 0, 144);
		FollowerEntity follower = followerOn(game);

		assertFalse("a mark on the floor", follower.furnished(new WorldPoint(5, 5, 0)));
		assertFalse("a model hung overhead", follower.furnished(new WorldPoint(6, 5, 0)));
		assertFalse("a booth placed as a wall is stood in front of", follower.furnished(new WorldPoint(7, 5, 0)));
		assertTrue("a cupboard on the wall", follower.furnished(new WorldPoint(8, 5, 0)));
		assertTrue("a chair set diagonally", follower.furnished(new WorldPoint(9, 5, 0)));
		assertFalse("and bare floor", follower.furnished(new WorldPoint(10, 5, 0)));
	}

	/**
	 * Plants and crops count as furniture too, since nothing tells them from
	 * a booth. A player standing in a flowerbed still gets the follower
	 * beside them in it rather than on top of them.
	 */
	@Test
	public void ringedByFurnitureItStillLandsBesideThePlayer()
	{
		FakeGame game = new FakeGame().withCollision().at(10, 10, 0);
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				game.furniture(10 + dx, 10 + dy, 0, 10, 0, 90);
			}
		}
		FollowerEntity follower = followerOn(game);

		WorldPoint wanted = new WorldPoint(9, 10, 0);
		assertEquals(wanted, follower.landingTile(wanted, new WorldPoint(10, 10, 0)));

		// The login spawn too: no tile behind would send it to the player's
		// own, which the landing then keeps.
		assertEquals(new WorldPoint(10, 12, 0), follower.restingTileBehind(game.player, 2));
	}

	// ----------------------------------------------------------- the fires

	/**
	 * A lit fire has no collision at all. The game lets players walk straight
	 * over one, so every check the follower had was blind to it, and the
	 * follower walked over them too. Play report: it stood in a campfire while
	 * the player cooked on it.
	 */
	@Test
	public void aFireIsWalkedRoundRatherThanThrough()
	{
		FakeGame game = new FakeGame().withCollision();
		FollowerEntity follower = followerOn(game);
		WorldPoint fire = new WorldPoint(11, 11, 0);
		follower.setHotGround(fire::equals);

		WorldPoint from = new WorldPoint(10, 10, 0);
		assertTrue("the ground under a fire is open - that is the whole problem",
			follower.standable(fire));
		assertFalse("and the follower will not step onto it even so",
			follower.canStep(from, 1, 1));
		assertFalse("nor be left standing on it", follower.goodGround(fire));

		WorldPoint step = follower.stepToward(from, new WorldPoint(13, 12, 0), false);
		assertNotNull("a fire in the way is no reason to stand still", step);
		assertFalse("it went round, not through", fire.equals(step));
	}

	@Test
	public void steppingOutOfAFireIsAlwaysAllowed()
	{
		// Only the tile being stepped ONTO is asked about. A fire lit under the
		// follower has to be leavable, or refusing would trap it in the flames.
		FakeGame game = new FakeGame().withCollision();
		FollowerEntity follower = followerOn(game);
		WorldPoint fire = new WorldPoint(10, 10, 0);
		follower.setHotGround(fire::equals);

		assertTrue("out of it, east", follower.canStep(fire, 1, 0));
		assertTrue("and every other way", follower.canStep(fire, 0, 1));
	}

	@Test
	public void aSnapNeverLandsInAFire()
	{
		// A teleport puts the follower one tile west of the player by
		// arithmetic, and the player may well have lit that tile.
		FakeGame game = new FakeGame().withCollision();
		FollowerEntity follower = followerOn(game);
		WorldPoint wanted = new WorldPoint(9, 10, 0);
		follower.setHotGround(wanted::equals);

		WorldPoint landing = follower.landingTile(wanted, new WorldPoint(10, 10, 0));
		assertFalse("beside the fire, not in it", wanted.equals(landing));
		assertTrue("and somewhere it could have walked to",
			follower.goodGround(landing));
	}

	@Test
	public void nothingIsHotUntilSomethingSaysSo()
	{
		// The default, and the reason none of the rest of the plugin had to
		// learn about any of this.
		FollowerEntity follower = followerOn(new FakeGame().withCollision());
		WorldPoint open = new WorldPoint(10, 10, 0);

		assertTrue(follower.goodGround(open));
		assertTrue(follower.canStep(open, 1, 0));
	}
}
