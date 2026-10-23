package com.follower;

import com.follower.follower.FollowerEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A park the player gave outlasts the session. Play test, 29 Sep: told to
 * wait, the follower came back after a relog parked, but at the player's
 * side rather than where it was left. It now remembers the tile, and comes
 * back to it when the player is near enough for its leash to allow it.
 */
public class ParkRememberedTest
{
	private static final WorldPoint POST = new WorldPoint(3221, 3218, 0);

	@Test
	public void theFollowerComesBackToItsPostWhenThePlayerIsNearIt()
	{
		assertEquals(POST, FollowerPlugin.parkToReturnTo("3221,3218,0", new WorldPoint(3230, 3225, 0)));
	}

	@Test
	public void butNotWhenItsLeashWouldSnapItStraightBack()
	{
		int reach = FollowerEntity.STAY_LEASH - 2;
		assertEquals("at the edge of its reach it still comes back",
			POST, FollowerPlugin.parkToReturnTo("3221,3218,0", new WorldPoint(3221 + reach, 3218, 0)));
		assertNull("one step past it, it arrives beside the player",
			FollowerPlugin.parkToReturnTo("3221,3218,0", new WorldPoint(3221 + reach + 1, 3218, 0)));
		assertNull("nor from another floor",
			FollowerPlugin.parkToReturnTo("3221,3218,0", new WorldPoint(3221, 3218, 1)));
	}

	@Test
	public void nothingKeptOrNothingReadableMeansBesideThePlayer()
	{
		WorldPoint player = new WorldPoint(3221, 3218, 0);
		assertNull(FollowerPlugin.parkToReturnTo("", player));
		assertNull(FollowerPlugin.parkToReturnTo(null, player));
		assertNull(FollowerPlugin.parkToReturnTo("somewhere", player));
		assertNull(FollowerPlugin.parkToReturnTo("3221,3218", player));
		assertNull(FollowerPlugin.parkToReturnTo("3221,3218,0", null));
	}

	@Test
	public void theParkIsKeptWhileItHoldsAndForgottenWhenItIsOver()
	{
		assertEquals("parked by the player: its tile is kept",
			"3221,3218,0", FollowerPlugin.parkToKeep(true, true, false, POST, ""));
		assertEquals("Follow, or a leash snap: the park is over",
			"", FollowerPlugin.parkToKeep(true, false, false, null, "3221,3218,0"));
	}

	@Test
	public void theEndOfASceneIsNotThePlayerSayingFollow()
	{
		// A logout or a hop tears the scene down, the park with it, and the
		// follower is despawned until the next spawn reads the tile back.
		assertEquals("3221,3218,0", FollowerPlugin.parkToKeep(false, false, false, null, "3221,3218,0"));
	}

	@Test
	public void aParkWaitingToBeTakenKeepsWhatWasKept()
	{
		// Stay pressed mid-errand: the park is taken once the errand lets go.
		assertEquals("3221,3218,0",
			FollowerPlugin.parkToKeep(true, true, true, new WorldPoint(3240, 3240, 0), "3221,3218,0"));
	}

	/**
	 * The spawns are glue no harness drives, so they are read out of the
	 * source, as StayHoldsTest reads the parks: both spawns a login or a hop
	 * takes put a waiting follower back on its post.
	 */
	@Test
	public void bothSpawnsALoginTakesReadThePost() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		int reads = 0;
		for (int at = source.indexOf("parkToReturnTo(config.parkedAt()"); at >= 0;
			at = source.indexOf("parkToReturnTo(config.parkedAt()", at + 1))
		{
			reads++;
		}
		assertEquals("the queued rebuild and the tick's auto-spawn", 2, reads);
		int asPost = 0;
		for (int at = source.indexOf("config.spawnGraphicHeight(), post != null)"); at >= 0;
			at = source.indexOf("config.spawnGraphicHeight(), post != null)", at + 1))
		{
			asPost++;
		}
		assertEquals("and both spawn it AS a post, or the landing draws it back to the player"
			+ " (PostArrivalTest)", 2, asPost);
		assertTrue("and the tick keeps the park", source.contains("rememberThePark();"));
	}
}
