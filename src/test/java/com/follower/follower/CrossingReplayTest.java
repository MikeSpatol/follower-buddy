package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.follower.sim.FakeGame;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The follower doing the player's agility after them, driven against the
 * real {@link FollowerEntity}: it walks to where each crossing began, plays
 * it back tile by tile and floor by floor with the player's own animations,
 * takes them in order with none skipped, copies a fall, and the teleport
 * snap that would otherwise fetch it after a player on a roof stays out of
 * it. And what interrupts it: the leash, the cap, a Stay, a cutscene,
 * thrall work, a snap, a hide, a despawn.
 *
 * <p>The frame loop needs a RuneLiteObject, set by reflection as in
 * TeleportLandingTest; a frame is twenty milliseconds, wound back on the
 * follower's own clock.
 */
public class CrossingReplayTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int[] NORMAL = {808, 819, 824, 820, 821, 822, 823, 823};
	private static final int LOG_WALK = 762;
	private static final int LOG_READY = 763;
	private static final int JUMP = 741;
	private static final int CLIMB = 737;
	private static final int FALL = 3068;

	private FakeGame game;
	private FollowerEntity follower;
	private int snaps;
	private final List<Crossing> done = new ArrayList<>();

	/** Every tile the follower stood on, in order, once each time it arrived. */
	private final List<WorldPoint> visited = new ArrayList<>();
	/** Every animation the crossings put on it. */
	private final List<Integer> shown = new ArrayList<>();

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		game = new FakeGame().withCollision().keepsRuneLiteObjects()
			.animation(LOG_WALK, 0).animation(LOG_READY, 0)
			.animation(JUMP, -1).animation(CLIMB, -1).animation(FALL, -1);
		for (int pose : NORMAL)
		{
			game.animation(pose, 0);
		}
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setOnSnapped(() -> snaps++);
		follower.setOnCrossed(done::add);
		set("object", new RuneLiteObject(game.client));
		set("animatable", true);
		set("spawned", true);
		game.at(50, 52, 0);
		// The first frame puts the object in the scene, beside the player;
		// then it is stood where each test wants it.
		frames(1);
		standOn(new WorldPoint(50, 50, 0));
		visited.clear();
		snaps = 0;
	}

	// --------------------------------------------------------------- replay

	@Test
	public void itWalksToTheStartThenTakesTheCrossingTileByTileAndFloorByFloor()
	{
		// The player is up on the roof, twenty tiles on: the snap's business
		// any other time.
		game.at(70, 52, 1);
		Crossing log = new Recording(55, 50, 0)
			.stand(200, LOG_READY)
			.glide(5, 0, LOG_WALK, -1)
			.hop(1, 0, 1, CLIMB)
			.stand(200, 808, CLIMB)
			.stand(400, 808)
			.build(false);
		follower.queueCrossing(log);
		runUntilDone();

		assertEquals("the one crossing, done", List.of(log), done);
		int start = visited.indexOf(new WorldPoint(55, 50, 0));
		assertTrue("it went to where the crossing began: " + visited, start >= 0);
		for (int x = 56; x <= 60; x++)
		{
			assertEquals("then along it a tile at a time: " + visited,
				new WorldPoint(x, 50, 0), visited.get(start + x - 55));
		}
		assertEquals("and up a floor", new WorldPoint(61, 50, 1), visited.get(start + 6));
		assertEquals(new WorldPoint(61, 50, 1), follower.getWorldLocation());
		assertTrue("the balance pose, the player's own: " + shown, shown.contains(LOG_WALK));
		assertTrue(shown.contains(LOG_READY));
		assertTrue("the climb", shown.contains(CLIMB));
		assertEquals("never snapped after the player", 0, snaps);
		assertFalse(follower.isDoingAgility());
	}

	@Test
	public void severalQueuedCrossingsAreTakenInOrderNoneSkipped()
	{
		game.at(75, 52, 0);
		Crossing a = new Recording(52, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).stand(300, 808).build(false);
		Crossing b = new Recording(56, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).stand(300, 808).build(false);
		Crossing c = new Recording(60, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).stand(300, 808).build(false);
		follower.queueCrossing(a);
		follower.queueCrossing(b);
		follower.queueCrossing(c);
		assertEquals(3, follower.getCrossingsQueued());
		runUntilDone();

		assertEquals("all three, in order", List.of(a, b, c), done);
		int previous = -1;
		for (int x : new int[]{52, 53, 54, 56, 57, 58, 60, 61, 62})
		{
			int at = visited.indexOf(new WorldPoint(x, 50, 0));
			assertTrue(x + " after the last: " + visited, at > previous);
			previous = at;
		}
		assertEquals(new WorldPoint(62, 50, 0), follower.getWorldLocation());
		assertEquals(0, snaps);
	}

	@Test
	public void aFallIsCopiedAndEndsWhereThePlayerLanded()
	{
		standOn(new WorldPoint(53, 50, 2));
		game.at(64, 52, 0);
		Crossing fall = new Recording(55, 50, 2)
			.stand(200, 808)
			.glide(1, 0, 808, JUMP)
			.hop(2, 0, -2, FALL)
			.stand(900, 808, FALL)
			.stand(400, 808)
			.build(true);
		follower.queueCrossing(fall);
		runUntilDone();

		assertEquals(List.of(fall), done);
		assertTrue(done.get(0).isFell());
		assertTrue(shown.contains(FALL));
		assertEquals("down where the player came down", new WorldPoint(58, 50, 0), follower.getWorldLocation());
		assertEquals(0, snaps);
	}

	@Test
	public void farBehindItStillTakesThemAllRatherThanSnapping()
	{
		game.at(85, 52, 3);
		List<Crossing> course = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			course.add(new Recording(52 + 4 * i, 50, 0).stand(100, 808).glide(2, 0, 808, JUMP).stand(200, 808).build(false));
		}
		course.forEach(follower::queueCrossing);
		runUntilDone();
		assertEquals(course, done);
		assertEquals("thirty tiles behind and a floor down, and no snap", 0, snaps);
	}

	@Test
	public void whileThePlayerIsStillOnItItWaitsBesideTheStartAndDoesNotSnap()
	{
		WorldPoint start = new WorldPoint(55, 50, 0);
		game.at(55, 50, 0).animating(CLIMB);
		follower.expectCrossing(start);
		frames(400);
		assertNotEquals("not on the player climbing", start, follower.getWorldLocation());
		assertTrue("but up beside them: " + follower.getWorldLocation(),
			follower.getWorldLocation().distanceTo(start) <= 1);

		// The player is on the roof: no snap while the climb is awaited.
		game.at(56, 50, 1).animating(-1);
		frames(30);
		assertEquals(0, snaps);
		assertTrue(follower.isDoingAgility());

		follower.queueCrossing(new Recording(55, 50, 0).stand(600, 808, CLIMB).hop(1, 0, 1, -1).stand(300, 808)
			.build(false));
		runUntilDone();
		assertEquals(new WorldPoint(56, 50, 1), follower.getWorldLocation());
		assertEquals(0, snaps);
	}

	@Test
	public void theWholeChainFromThePlayersLogToTheFollowersLog()
	{
		CrossingWatch watch = new CrossingWatch(follower);
		long[] clock = {1_000_000};
		game.basePoses(NORMAL).posing(808);
		// The player walks up to the log, crosses it on the swapped walk, and stops.
		for (int x = 50 * 128 + 64; x <= 55 * 128 + 64; x += 4)
		{
			playerFrame(watch, clock, x, 819);
		}
		game.basePoses(LOG_READY, LOG_WALK, LOG_WALK, LOG_WALK, LOG_WALK, LOG_WALK, LOG_READY, LOG_READY);
		for (int x = 55 * 128 + 64; x <= 61 * 128 + 64; x += 4)
		{
			playerFrame(watch, clock, x, LOG_WALK);
		}
		game.basePoses(NORMAL);
		for (int i = 0; i < 100; i++)
		{
			playerFrame(watch, clock, 61 * 128 + 64, 808);
		}
		assertFalse("recorded and handed over", watch.isWatching());
		runUntilDone();
		assertEquals(1, done.size());
		assertTrue(visited.contains(new WorldPoint(58, 50, 0)));
		assertEquals("at the far end of the log", new WorldPoint(61, 50, 0), follower.getWorldLocation());
		assertTrue(shown.contains(LOG_WALK));
	}

	// -------------------------------------------------------- interruptions

	@Test
	public void pastTheLeashTheQueueIsLetGoAndTheOldCatchUpTakesOver()
	{
		game.at(95, 52, 0);
		follower.queueCrossing(new Recording(52, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		follower.queueCrossing(new Recording(56, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		frames(2);
		assertFalse("let go", follower.isDoingAgility());
		assertTrue("and snapped to the player the usual way", snaps >= 1);
		assertTrue(follower.getWorldLocation().distanceTo(new WorldPoint(95, 52, 0)) <= 2);
	}

	@Test
	public void aQueuePastTheCapIsLetGo()
	{
		for (int i = 0; i < FollowerEntity.MAX_QUEUED_CROSSINGS; i++)
		{
			follower.queueCrossing(new Recording(52, 50, 0).stand(200, 808).build(false));
		}
		assertEquals(FollowerEntity.MAX_QUEUED_CROSSINGS, follower.getCrossingsQueued());
		follower.queueCrossing(new Recording(52, 50, 0).stand(200, 808).build(false));
		assertEquals(0, follower.getCrossingsQueued());
		assertFalse(follower.isDoingAgility());
	}

	@Test
	public void lettingGoFinishesTheCrossingUnderWay()
	{
		Crossing first = new Recording(51, 50, 0).glide(3, 0, LOG_WALK, -1).stand(200, 808).build(false);
		Crossing second = new Recording(55, 50, 0).glide(2, 0, LOG_WALK, -1).build(false);
		follower.queueCrossing(first);
		follower.queueCrossing(second);
		untilPlaying();
		follower.clearCrossings();
		assertTrue("still on the log", follower.isDoingAgility());
		runUntilDone();
		assertEquals("finished, and nothing after it", List.of(first), done);
		assertEquals(new WorldPoint(54, 50, 0), follower.getWorldLocation());
	}

	@Test
	public void aStayGivenMidCrossingHoldsWhereTheCrossingEnds()
	{
		Crossing log = new Recording(51, 50, 0).glide(4, 0, LOG_WALK, -1).stand(200, 808).build(false);
		follower.queueCrossing(log);
		untilPlaying();
		frames(20);
		// The player's Stay, and the plugin letting the queue go for it.
		follower.stayHere();
		follower.clearCrossings();
		runUntilDone();
		frames(50);
		assertTrue(follower.isStaying());
		assertEquals("held at the far end, not halfway along the log",
			new WorldPoint(55, 50, 0), follower.getStayTile());
		assertEquals(new WorldPoint(55, 50, 0), follower.getWorldLocation());
	}

	@Test
	public void aCutsceneHoldsTheQueueWhereItStands()
	{
		follower.queueCrossing(new Recording(55, 50, 0).stand(100, 808).glide(2, 0, 808, JUMP).build(false));
		follower.setWatchingWithThePlayer(true);
		frames(200);
		assertEquals("held", new WorldPoint(50, 50, 0), follower.getWorldLocation());
		assertEquals(1, follower.getCrossingsQueued());
		follower.setWatchingWithThePlayer(false);
		runUntilDone();
		assertEquals(1, done.size());
		assertEquals(new WorldPoint(57, 50, 0), follower.getWorldLocation());
	}

	@Test
	public void thrallWorkTakesItOverAndDropsTheLot()
	{
		follower.queueCrossing(new Recording(52, 50, 0).glide(2, 0, LOG_WALK, -1).build(false));
		follower.queueCrossing(new Recording(56, 50, 0).glide(2, 0, LOG_WALK, -1).build(false));
		untilPlaying();
		follower.slaveToNpc(game.spawnNpc(10886, "Greater Zombified Thrall", 0));
		assertFalse(follower.isDoingAgility());
		assertTrue(done.isEmpty());
	}

	@Test
	public void aSnapOrADespawnDropsTheLot()
	{
		follower.queueCrossing(new Recording(52, 50, 0).glide(2, 0, LOG_WALK, -1).build(false));
		untilPlaying();
		follower.teleportToPlayer();
		assertFalse("a snap ends it", follower.isDoingAgility());

		follower.queueCrossing(new Recording(52, 50, 0).glide(2, 0, LOG_WALK, -1).build(false));
		follower.despawn();
		assertFalse("and so does a despawn", follower.isDoingAgility());
		follower.queueCrossing(new Recording(52, 50, 0).glide(2, 0, LOG_WALK, -1).build(false));
		assertFalse("nor is anything taken while it is gone", follower.isDoingAgility());
	}

	@Test
	public void hiddenItWaitsAndCarriesOnAfter() throws ReflectiveOperationException
	{
		Crossing log = new Recording(51, 50, 0).glide(4, 0, LOG_WALK, -1).build(false);
		follower.queueCrossing(log);
		untilPlaying();
		long played = (Long) get("playedMs");
		set("outOfPicture", true);
		frames(50);
		assertEquals("nothing played while out of sight", played, (long) (Long) get("playedMs"));
		set("outOfPicture", false);
		runUntilDone();
		assertEquals(List.of(log), done);
	}

	// ---------------------------------------------------------------- helpers

	/** A recording made frame by frame, twenty milliseconds apart, from a starting tile. */
	private static final class Recording
	{
		private final List<Crossing.Frame> frames = new ArrayList<>();
		private long t;
		private long x;
		private long y;
		private int plane;

		Recording(int tileX, int tileY, int plane)
		{
			x = tileX * 128L + 64;
			y = tileY * 128L + 64;
			this.plane = plane;
		}

		Recording stand(long ms, int pose)
		{
			return stand(ms, pose, -1);
		}

		Recording stand(long ms, int pose, int action)
		{
			for (long i = 0; i < ms; i += 20)
			{
				add(pose, action);
			}
			return this;
		}

		Recording glide(int tilesX, int tilesY, int pose, int action)
		{
			long toX = x + tilesX * 128L;
			long toY = y + tilesY * 128L;
			add(pose, action);
			while (x != toX || y != toY)
			{
				x += Math.max(-4, Math.min(4, toX - x));
				y += Math.max(-4, Math.min(4, toY - y));
				add(pose, action);
			}
			return this;
		}

		/** Put down so many tiles over and floors up or down, with this playing. */
		Recording hop(int tilesX, int tilesY, int floors, int action)
		{
			x += tilesX * 128L;
			y += tilesY * 128L;
			plane += floors;
			add(808, action);
			return this;
		}

		private void add(int pose, int action)
		{
			frames.add(new Crossing.Frame(t, x, y, plane, 1536, pose, 0, action, 0));
			t += 20;
		}

		Crossing build(boolean fell)
		{
			return new Crossing(frames, NORMAL, fell);
		}
	}

	private void playerFrame(CrossingWatch watch, long[] clock, int localX, int pose)
	{
		clock[0] += 20;
		game.fineAt(localX, 50 * 128 + 64, 0).posing(pose);
		watch.sample(CrossingWatch.Sample.read(game.player, clock[0]));
	}

	private void frames(int count)
	{
		for (int i = 0; i < count; i++)
		{
			frame();
		}
	}

	private void frame()
	{
		try
		{
			set("lastFrameMs", System.currentTimeMillis() - 20);
			follower.updateFrame();
			WorldPoint at = follower.getWorldLocation();
			if (visited.isEmpty() || !visited.get(visited.size() - 1).equals(at))
			{
				visited.add(at);
			}
			int animation = (Integer) get("crossingAnimation");
			if (animation > 0 && (shown.isEmpty() || shown.get(shown.size() - 1) != animation))
			{
				shown.add(animation);
			}
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private void runUntilDone()
	{
		for (int i = 0; i < 5000 && follower.isDoingAgility(); i++)
		{
			frame();
		}
		assertFalse("done within the time: " + visited, follower.isDoingAgility());
	}

	private void untilPlaying()
	{
		for (int i = 0; i < 2000 && !follower.isPlayingCrossing(); i++)
		{
			frame();
		}
		assertTrue("got to the crossing", follower.isPlayingCrossing());
	}

	private void standOn(WorldPoint at)
	{
		try
		{
			set("tile", at);
			set("simTile", at);
			set("fineWX", at.getX() * 128L + 64);
			set("fineWY", at.getY() * 128L + 64);
			((java.util.Deque<?>) get("serverPath")).clear();
			((java.util.Deque<?>) get("route")).clear();
			((java.util.Deque<?>) get("routeRun")).clear();
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(follower);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}
}
