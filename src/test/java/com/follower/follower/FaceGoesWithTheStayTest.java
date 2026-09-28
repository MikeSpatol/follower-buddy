package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A stay's face belongs to the stay. A drift over to an NPC turns the
 * follower to face it, a trap run turns it to the trap, and the face used to
 * outlive the stay it came with: Step-in-front strafed over looking at the
 * NPC and stood in front of the player still looking at it, the level
 * walk-over clapped at the trap, and the next Stay or Send stared at
 * whatever the last one had. Whatever ends the stay ends the face with it.
 *
 * <p>The frame that turns the follower cannot run headless, so the face is
 * read where it is kept.
 */
public class FaceGoesWithTheStayTest
{
	private static final WorldPoint PLAYER = new WorldPoint(50, 50, 0);
	/** South of the player, who faces south: the tile in front of them. */
	private static final WorldPoint FRONT = new WorldPoint(50, 49, 0);
	private static final WorldPoint DRIFTED = new WorldPoint(53, 50, 0);
	private static final WorldPoint NPC_TILE = new WorldPoint(54, 50, 0);

	private FakeGame game;
	private FollowerEntity follower;

	@Before
	public void setUp() throws ReflectiveOperationException
	{
		game = new FakeGame().withCollision().at(PLAYER.getX(), PLAYER.getY(), 0).turned(0);
		follower = new FollowerEntity(game.client, new StanceLibrary(new com.google.gson.Gson(),
			new com.follower.appearance.ModelRepository(new com.google.gson.Gson())));
		set("spawned", true);
		set("tile", DRIFTED);
		set("simTile", DRIFTED);
		// Drifted over to an NPC and turned to it, as the wander does.
		set("stayTile", DRIFTED);
		follower.setStayFaceTile(NPC_TILE);
	}

	@Test
	public void calledOverItArrivesFacingThePlayer() throws ReflectiveOperationException
	{
		assertTrue(follower.moveToFront(game.player));
		assertFalse("called over is the end of the stay", follower.isStaying());
		assertNull("and of the way the stay faced", get("stayFaceTile"));
		assertNull(get("stayFaceMax"));
	}

	@Test
	public void noRoomInFrontKeepsTheStayAndItsFace() throws ReflectiveOperationException
	{
		game.blockTile(FRONT.getX(), FRONT.getY());
		assertFalse(follower.moveToFront(game.player));
		assertEquals("nothing moved, so the stay goes on", DRIFTED, follower.getStayTile());
		assertEquals("facing what it faced", NPC_TILE, get("stayFaceTile"));
	}

	@Test
	public void aSnapHomeEndsTheFaceWithThePose() throws ReflectiveOperationException
	{
		// The player off the fake scene, which has no heights to draw the
		// follower by where it lands; the landing is not the point here.
		game.at(3222, 3218, 0);
		follower.teleportToPlayer();
		assertFalse(follower.isStaying());
		assertNull("a fresh Stay after it faces the player, not the old NPC", get("stayFaceTile"));
	}

	@Test
	public void thrallWorkEndsTheFaceWithThePose() throws ReflectiveOperationException
	{
		follower.slaveToNpc(game.spawnNpc(10886, "Greater Zombified Spawn", 0));
		assertFalse(follower.isStaying());
		assertNull(get("stayFaceTile"));
	}

	@Test
	public void goingEndsTheFaceWithThePose() throws ReflectiveOperationException
	{
		follower.despawn();
		assertNull(get("stayFaceTile"));
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(follower);
	}
}
