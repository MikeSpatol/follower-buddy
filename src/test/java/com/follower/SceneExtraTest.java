package com.follower;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The follower as an extra in a cutscene: it holds the tile it stands on,
 * steps across the way of an actor that comes onto it, and walks over to the
 * player once after the scene has put them down somewhere else.
 *
 * <p>Play test, 9 Oct, Children of the Sun: "The follower was walking around
 * a bit too much and was at some points standing inside of an npc."
 *
 * <p>Driven against the real {@link FollowerEntity}. The frame loop needs a
 * RuneLiteObject the fake client cannot make, so its state is set by
 * reflection, as FireLineTest and MotherlodeSeatTest do, and the scene's
 * keeping is called a tick at a time.
 */
public class SceneExtraTest
{
	private static final WorldPoint MARK = new WorldPoint(50, 50, 0);

	private final FakeGame game = new FakeGame().withCollision();
	private final FollowerEntity follower = new FollowerEntity(game.client, null);
	private int tick;

	@Before
	public void setUp()
	{
		game.at(52, 52, 0);
		set("spawned", true);
		standOn(MARK);
	}

	@Test
	public void itHoldsWhereItStands()
	{
		keep();
		assertEquals("its own tile is its mark", MARK, follower.getStayTile());
		assertTrue("and nothing is walked", path("serverPath").isEmpty());
	}

	@Test
	public void anActorArrivingIsGivenWayAcrossItsPath()
	{
		keep();
		NPC guard = game.spawnNpc(100, "Guard", 21);
		game.moveNpc(guard, 50, 50, 0).npcTurned(guard, 1536);
		keep();
		WorldPoint step = follower.getStayTile();
		assertTrue("across an eastbound guard's way, not along it: " + step,
			step.equals(new WorldPoint(50, 51, 0)) || step.equals(new WorldPoint(50, 49, 0)));
	}

	@Test
	public void aGiantsWholeFootprintCounts()
	{
		keep();
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 49, 49, 0).npcSize(giant, 2);
		keep();
		WorldPoint step = follower.getStayTile();
		assertNotEquals("off the giant's north-east tile", MARK, step);
		assertFalse("and outside all four of its tiles: " + step,
			step.getX() >= 49 && step.getX() <= 50 && step.getY() >= 49 && step.getY() <= 50);
	}

	@Test
	public void neverOntoAnotherNpcOrThePlayer()
	{
		game.at(50, 49, 0);
		keep();
		NPC guard = game.spawnNpc(100, "Guard", 21);
		game.moveNpc(guard, 50, 50, 0).npcTurned(guard, 1536);
		NPC noah = game.spawnNpc(12647, "Noah", 0);
		game.moveNpc(noah, 50, 51, 0);
		keep();
		WorldPoint step = follower.getStayTile();
		assertNotEquals("not onto Noah", new WorldPoint(50, 51, 0), step);
		assertNotEquals("not onto the player", new WorldPoint(50, 49, 0), step);
		assertEquals("a free diagonal instead", 1, Math.abs(step.getX() - 50));
		assertEquals(1, Math.abs(step.getY() - 50));
	}

	@Test
	public void boxedInItKeepsItsTile()
	{
		keep();
		NPC guard = game.spawnNpc(100, "Guard", 21);
		game.moveNpc(guard, 50, 50, 0);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					game.moveNpc(game.spawnNpc(3000, "Bandit", 0), 50 + dx, 50 + dy, 0);
				}
			}
		}
		keep();
		assertEquals("nowhere to go, so the tile is kept", MARK, follower.getStayTile());
	}

	@Test
	public void itHoldsWhileThePlayerIsWalkedAndWalksOverOnceTheyStop()
	{
		game.at(51, 50, 0);
		keep();
		for (int x = 52; x <= 57; x++)
		{
			game.at(x, 50, 0);
			keep();
			assertEquals("still on its mark while the scene walks the player", MARK, follower.getStayTile());
		}
		keep();
		assertEquals("one still tick is not stopped", MARK, follower.getStayTile());
		keep();
		assertNull("stopped seven tiles off: let go to walk over", follower.getStayTile());
		assertTrue((Boolean) get("catchingUp"));

		WorldPoint beside = new WorldPoint(56, 50, 0);
		standOn(beside);
		keep();
		assertEquals("held again once there", beside, follower.getStayTile());
		assertFalse((Boolean) get("catchingUp"));
	}

	@Test
	public void oneLookATick()
	{
		keep();
		NPC guard = game.spawnNpc(100, "Guard", 21);
		game.moveNpc(guard, 50, 50, 0);
		invoke();
		assertEquals("the same tick is not looked at twice", MARK, follower.getStayTile());
		keep();
		assertNotEquals("the next one is", MARK, follower.getStayTile());
	}

	/**
	 * Play test, 15 Oct (Children of the Sun): the player turned and the
	 * follower went on facing them. The hold's own facing ran ahead of the
	 * frame's turn and pointed it back at the player every frame.
	 */
	@Test
	public void theHoldLooksWhereThePlayerLooks()
	{
		keep();
		set("watchingWithThePlayer", true);
		for (int jau : new int[]{1536, 512, 0})
		{
			game.turned(jau);
			faceTheHold();
			assertEquals("turned the way the player is", jau, get("dstYaw"));
		}
		set("watchingWithThePlayer", false);
		set("lastRenderedLocation", net.runelite.api.coords.LocalPoint.fromWorld(game.client, MARK));
		faceTheHold();
		assertNotEquals("out of a scene, a stay faces the player again", 0, get("dstYaw"));
	}

	/**
	 * Both of the frame's facings of a held follower go through the scene's
	 * look (the order inside it: WallPathingTest). A third caller of the
	 * stay's own facing is how the scene's look was lost.
	 */
	@Test
	public void theFrameFacesTheHoldOnlyThroughTheScenesLook() throws java.io.IOException
	{
		String source = PublicMarkers.strip(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
			"src/main/java/com/follower/FollowerEntity.java")), java.nio.charset.StandardCharsets.UTF_8));
		int calls = 0;
		for (int at = source.indexOf("faceStayAnchor();"); at >= 0; at = source.indexOf("faceStayAnchor();", at + 1))
		{
			calls++;
		}
		assertEquals("faceTheHold, and faceAnchor while the feet move", 2, calls);
	}

	// ---------------------------------------------------------------- helpers

	private void faceTheHold()
	{
		try
		{
			Method face = FollowerEntity.class.getDeclaredMethod("faceTheHold", net.runelite.api.Player.class);
			face.setAccessible(true);
			face.invoke(follower, game.client.getLocalPlayer());
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("FollowerEntity.faceTheHold has moved", e);
		}
	}

	/** The next tick's keeping of the scene, with the player where they are. */
	private void keep()
	{
		game.tick(++tick);
		invoke();
	}

	private void invoke()
	{
		try
		{
			Method keep = FollowerEntity.class.getDeclaredMethod("keepToTheScene", WorldPoint.class);
			keep.setAccessible(true);
			keep.invoke(follower, game.client.getLocalPlayer().getWorldLocation());
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("FollowerEntity.keepToTheScene has moved", e);
		}
	}

	/** Spawned and at rest on a tile, its walk there over. */
	private void standOn(WorldPoint at)
	{
		set("tile", at);
		set("simTile", at);
		set("fineWX", at.getX() * 128L + 64);
		set("fineWY", at.getY() * 128L + 64);
		path("serverPath").clear();
		path("route").clear();
	}

	@SuppressWarnings("unchecked")
	private Deque<WorldPoint> path(String name)
	{
		return (Deque<WorldPoint>) get(name);
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
}
