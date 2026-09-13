package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.sim.FakeGame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where the follower looks when nothing else owns its head: the cursor, the
 * camera, and the look round while standing about.
 */
public class GazeTest
{
	private static final WorldPoint HERE = new WorldPoint(50, 50, 0);

	/** Records glances; nothing else. */
	private static final class GlancingFollower extends FollowerEntity
	{
		final List<WorldPoint> glances = new ArrayList<>();
		final List<Integer> holds = new ArrayList<>();

		GlancingFollower()
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
			return HERE;
		}

		@Override
		public void glanceAt(WorldPoint at, int ms)
		{
			glances.add(at);
			holds.add(ms);
		}
	}

	private FakeGame game;
	private GlancingFollower follower;
	private Gaze gaze;
	private int yaw;
	private int idle;
	private boolean busy;
	private boolean attentive = true;
	private String feeling = "";
	private List<WorldPoint> nearby = Collections.emptyList();
	private int tick;

	@Before
	public void setUp()
	{
		game = new FakeGame().at(50, 51, 0);
		follower = new GlancingFollower();
		FollowerConfig config = new FollowerConfig()
		{
			@Override
			public boolean paysAttention()
			{
				return attentive;
			}
		};
		gaze = new Gaze(game.client, follower, config, () -> yaw, () -> idle, () -> busy,
			() -> feeling, () -> nearby, new Random(7));
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			gaze.tick();
		}
	}

	private static Gaze.Hover thing(String key, int x, int y)
	{
		return new Gaze.Hover(key, null, new WorldPoint(x, y, 0));
	}

	// ------------------------------------------------------------- the cursor

	@Test
	public void theCursorRestingOnAThingTurnsTheHeadOnce()
	{
		gaze.noteHover(thing("npc:4", 55, 50));
		tick(1);
		assertTrue("one tick is a pass, not a rest", follower.glances.isEmpty());
		tick(1);
		assertEquals(Collections.singletonList(new WorldPoint(55, 50, 0)), follower.glances);
		assertEquals(Gaze.HOVER_GLANCE_MS, (int) follower.holds.get(0));

		tick(10);
		assertEquals("resting on the same thing is one look, not a stare", 1, follower.glances.size());

		// Off it and back on, and it is worth a second look.
		gaze.noteHover(null);
		tick(1);
		gaze.noteHover(thing("npc:4", 55, 50));
		tick(2);
		assertEquals(2, follower.glances.size());
	}

	@Test
	public void aNewThingUnderTheCursorStartsTheCountAgainAndTheHeadDoesNotFlick()
	{
		gaze.noteHover(thing("npc:4", 55, 50));
		tick(1);
		gaze.noteHover(thing("npc:9", 45, 50));
		tick(1);
		assertTrue("one tick on each is no rest on either", follower.glances.isEmpty());
		tick(1);
		assertEquals(Collections.singletonList(new WorldPoint(45, 50, 0)), follower.glances);

		// Straight on to a third: the cooldown holds the head where it is.
		gaze.noteHover(thing("npc:2", 50, 56));
		tick(2);
		assertEquals(1, follower.glances.size());
		tick(Gaze.HOVER_COOLDOWN_TICKS);
		gaze.noteHover(null);
		tick(1);
		gaze.noteHover(thing("npc:2", 50, 56));
		tick(2);
		assertEquals(2, follower.glances.size());
	}

	@Test
	public void theFollowersOwnTileIsNotALook()
	{
		gaze.noteHover(thing("tile:50,50:1", 50, 50));
		tick(3);
		assertTrue(follower.glances.isEmpty());
	}

	// ------------------------------------------------------------- the camera

	@Test
	public void aCameraSwingThatSettlesIsSharedAndAJitterIsNot()
	{
		idle = 1;
		tick(2);
		yaw = 512;
		tick(1);
		assertTrue("still swinging", follower.glances.isEmpty());
		tick(Gaze.CAMERA_SETTLE_TICKS);
		assertEquals("a look four tiles the way the camera looks", Collections.singletonList(
			Gaze.lookTile(HERE, 512, Gaze.CAMERA_LOOK_TILES)), follower.glances);

		yaw = 520;
		tick(3);
		assertEquals("a nudge is not a swing", 1, follower.glances.size());

		yaw = 1024;
		tick(3);
		assertEquals("and the cooldown holds the next", 1, follower.glances.size());
		tick(Gaze.CAMERA_COOLDOWN_TICKS);
		yaw = 0;
		tick(3);
		assertEquals(2, follower.glances.size());
	}

	@Test
	public void theCameraIsNotSharedWhileThePlayerIsMoving()
	{
		idle = 0;
		tick(2);
		yaw = 900;
		tick(4);
		assertTrue(follower.glances.isEmpty());
	}

	@Test
	public void theLookTileFollowsTheCamera()
	{
		// The same circle as an actor's orientation (0 south, 512 west, 1024
		// north, 1536 east), a half turn round: the camera at yaw 0 sits south
		// and looks north.
		assertEquals("north", new WorldPoint(50, 54, 0), Gaze.lookTile(HERE, 0, 4));
		assertEquals("east", new WorldPoint(54, 50, 0), Gaze.lookTile(HERE, 512, 4));
		assertEquals("south", new WorldPoint(50, 46, 0), Gaze.lookTile(HERE, 1024, 4));
		assertEquals("west", new WorldPoint(46, 50, 0), Gaze.lookTile(HERE, 1536, 4));
		assertEquals("the short way round", 16, Gaze.angle(2040, 8));
		assertEquals(16, Gaze.angle(8, 2040));
		assertEquals(1024, Gaze.angle(0, 1024));
	}

	// ---------------------------------------------------------- the look round

	@Test
	public void standingAboutItLooksRoundAndBackAgain()
	{
		nearby = Arrays.asList(new WorldPoint(53, 50, 0), new WorldPoint(50, 45, 0));
		idle = Gaze.IDLE_BEFORE_AROUND_TICKS;
		tick(Gaze.AROUND_MAX_TICKS + 2);
		assertEquals("one look in the longest wait", 1, follower.glances.size());
		assertTrue("at something nearby", nearby.contains(follower.glances.get(0)));
		assertEquals(Gaze.AROUND_MS, (int) follower.holds.get(0));
		tick(Gaze.AROUND_MAX_TICKS + 2);
		assertEquals(2, follower.glances.size());
	}

	@Test
	public void withNothingNearbyItLooksAtNothingInParticular()
	{
		idle = Gaze.IDLE_BEFORE_AROUND_TICKS;
		tick(Gaze.AROUND_MAX_TICKS + 2);
		assertEquals(1, follower.glances.size());
		int away = follower.glances.get(0).distanceTo(HERE);
		assertTrue("a few tiles off: " + away, away >= Gaze.AROUND_NEAR - 1 && away <= Gaze.AROUND_FAR + 1);
	}

	@Test
	public void somewhereItDislikesTheLooksAreQuickAndAtTheNearestThing()
	{
		feeling = "disliked";
		nearby = Arrays.asList(new WorldPoint(52, 50, 0), new WorldPoint(50, 45, 0));
		idle = Gaze.IDLE_BEFORE_AROUND_TICKS;
		tick(Gaze.DISLIKED_MAX_TICKS + 2);
		assertEquals(1, follower.glances.size());
		assertEquals("the nearest", new WorldPoint(52, 50, 0), follower.glances.get(0));
		assertEquals(Gaze.DISLIKED_MS, (int) follower.holds.get(0));
	}

	@Test
	public void somewhereItLikesTheLooksAreSlowAndLong()
	{
		feeling = "liked";
		idle = Gaze.IDLE_BEFORE_AROUND_TICKS;
		tick(Gaze.LIKED_MIN_TICKS - 2);
		assertTrue("not yet", follower.glances.isEmpty());
		tick(Gaze.LIKED_MAX_TICKS);
		assertEquals(1, follower.glances.size());
		assertEquals(Gaze.LIKED_MS, (int) follower.holds.get(0));
	}

	@Test
	public void nothingWhileBusyOrSwitchedOff()
	{
		busy = true;
		idle = 50;
		nearby = Collections.singletonList(new WorldPoint(53, 50, 0));
		gaze.noteHover(thing("npc:4", 55, 50));
		yaw = 700;
		tick(Gaze.AROUND_MAX_TICKS + 5);
		assertTrue(follower.glances.isEmpty());

		busy = false;
		attentive = false;
		tick(Gaze.AROUND_MAX_TICKS + 5);
		assertTrue(follower.glances.isEmpty());
	}
}
