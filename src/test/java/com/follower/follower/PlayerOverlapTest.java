package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Method;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower gives way to another player standing on its tile, as it does
 * to an NPC. Play test, 16 Oct: "make it so the follower disappears when
 * they stop on the same tile as another player".
 */
public class PlayerOverlapTest
{
	private final FakeGame game = new FakeGame().at(50, 50, 0);
	private final FollowerEntity follower = new FollowerEntity(game.client, null);

	@Test
	public void anotherPlayerStandingThereHidesIt()
	{
		game.spawnPlayer(2, 0);
		assertTrue("stopped on the follower's tile", playerOn(new WorldPoint(52, 50, 0)));
		assertFalse("and nobody on the next one", playerOn(new WorldPoint(53, 50, 0)));
	}

	@Test
	public void oneWalkingThroughDoesNot()
	{
		Player passing = game.spawnPlayer(2, 0);
		game.otherWalking(passing);
		assertFalse("a moment, not worth a blink", playerOn(new WorldPoint(52, 50, 0)));
	}

	@Test
	public void theLocalPlayerDoesNot()
	{
		assertFalse("the follower keeps off them by itself", playerOn(new WorldPoint(50, 50, 0)));
	}

	@Test
	public void theDrawHidesForAPlayerAsForAnNpc() throws java.io.IOException
	{
		String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
			"src/main/java/com/follower/follower/FollowerEntity.java")), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(source.contains("hiddenForNpcOverlap = isSettled() && (npcOnTile(tile) || playerOnTile(tile));"));
	}

	private boolean playerOn(WorldPoint where)
	{
		try
		{
			Method on = FollowerEntity.class.getDeclaredMethod("playerOnTile", WorldPoint.class);
			on.setAccessible(true);
			return (Boolean) on.invoke(follower, where);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("FollowerEntity.playerOnTile has moved", e);
		}
	}
}
