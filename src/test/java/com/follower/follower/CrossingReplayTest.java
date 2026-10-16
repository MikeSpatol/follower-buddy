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

	/** The Draynor rooftop's clips, by their gameval names. */
	private static final int REACHFORLADDER = 828;
	private static final int INTO_SIDESTEPL = 753;
	private static final int WALK_SIDESTEPL = 756;
	private static final int READY_SIDESTEPL = 757;
	private static final int OUTOF_SIDESTEPL = 759;
	private static final int WALL_JUMP = 2583;
	private static final int WALL_JUMP2 = 2585;
	private static final int WALL_JUMPDOWN = 2586;
	private static final int WALL_JUMPDOWN2 = 2588;

	/** The base sets the game swaps in: the tightrope's balance, and the narrow wall's side-step. */
	private static final int[] ON_A_ROPE = {LOG_READY, LOG_WALK, LOG_WALK, LOG_WALK, LOG_WALK, LOG_WALK,
		LOG_READY, LOG_READY};
	private static final int[] SIDESTEPPING = {READY_SIDESTEPL, WALK_SIDESTEPL, WALK_SIDESTEPL,
		WALK_SIDESTEPL, WALK_SIDESTEPL, WALK_SIDESTEPL, READY_SIDESTEPL, READY_SIDESTEPL};

	private FakeGame game;
	private FollowerEntity follower;
	private int snaps;
	private final List<Crossing> done = new ArrayList<>();

	/** Every tile the follower stood on, in order, once each time it arrived. */
	private final List<WorldPoint> visited = new ArrayList<>();
	/** Every animation the crossings put on it. */
	private final List<Integer> shown = new ArrayList<>();
	/** The agility trace's event lines, from the follower and the watch. */
	private final List<String> trace = new ArrayList<>();

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		game = new FakeGame().withCollision().keepsRuneLiteObjects()
			.animation(LOG_WALK, 0).animation(LOG_READY, 0)
			.animation(JUMP, -1).animation(CLIMB, -1).animation(FALL, -1)
			.animation(REACHFORLADDER, -1).animation(INTO_SIDESTEPL, -1).animation(OUTOF_SIDESTEPL, -1)
			.animation(WALK_SIDESTEPL, 0).animation(READY_SIDESTEPL, 0)
			.animation(WALL_JUMP, -1).animation(WALL_JUMP2, -1)
			.animation(WALL_JUMPDOWN, -1).animation(WALL_JUMPDOWN2, -1);
		for (int pose : NORMAL)
		{
			game.animation(pose, 0);
		}
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setOnSnapped(() -> snaps++);
		follower.setAgilityTrace(trace::add);
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
		trace.clear();
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

	// ------------------------------------------------------------ streaming

	@Test
	public void itSetsOffTwoTicksAfterThePlayerNotAfterTheyFinish()
	{
		Mover player = new Mover(51, 50, 0);
		// Long enough for the follower to settle beside them.
		player.stand(1500);
		int began = player.frames;
		player.onto(ON_A_ROPE).stand(300).move(8, 0, LOG_WALK, 4).onto(NORMAL).stand(2000);

		long lagMs = (player.setOffAt - began) * 20L;
		assertTrue("set off while the player was still on it", player.setOffWhilePlayerOn);
		assertTrue("about two ticks after they began, not before: " + lagMs + " ms",
			lagMs >= FollowerEntity.START_LAG_MS && lagMs <= FollowerEntity.START_LAG_MS + 300);
		runUntilDone();
		assertEquals(1, done.size());
		assertEquals("then all of it, to the far end", new WorldPoint(59, 50, 0), follower.getWorldLocation());
		assertTrue(visited.contains(new WorldPoint(55, 50, 0)));
		assertTrue(shown.contains(LOG_WALK));
		assertEquals(0, snaps);
	}

	@Test
	public void aRecordingCutShortEndsTheReplayWhereItGotTo()
	{
		Mover player = new Mover(51, 50, 0);
		player.stand(200).onto(ON_A_ROPE).move(6, 0, LOG_WALK, 4);
		assertTrue(follower.isPlayingCrossing());
		// Logged out on the rope: the plugin resets the watch.
		player.watch.reset();
		runUntilDone();
		assertEquals(1, done.size());
		assertEquals("as far as the player got", new WorldPoint(57, 50, 0), follower.getWorldLocation());
	}

	@Test
	public void aCrossingDroppedBeforeItWasBegunIsSkipped()
	{
		Mover player = new Mover(51, 50, 0);
		player.stand(200).onto(ON_A_ROPE).move(1, 0, LOG_WALK, 4);
		assertEquals(1, follower.getCrossingsQueued());
		// A fight's knock-back: the watch lets it go as not an obstacle.
		game.fighting(game.spawnNpc(3029, "Rat", 0));
		player.move(1, 0, LOG_WALK, 4);
		assertFalse(player.watch.isWatching());
		frames(5);
		assertFalse(follower.isDoingAgility());
		assertTrue(done.isEmpty());
	}

	/**
	 * A rope's first tile is one nobody can walk onto: the game puts the
	 * player there. The follower sets off from beside it, eased on over the
	 * first moments, rather than standing stuck for 4.8 s and then being
	 * placed on it (QA4 #1, trace 16 Oct).
	 */
	@Test
	public void aStartNobodyCanWalkOntoIsBegunFromBesideIt() throws ReflectiveOperationException
	{
		for (int x = 52; x <= 58; x++)
		{
			game.blockTile(x, 50);
		}
		Mover player = new Mover(51, 50, 0);
		player.stand(1500);
		// Onto the rope's first tile on their own walk, then the balance.
		player.walk(1, 0).onto(ON_A_ROPE).move(6, 0, LOG_WALK, 4).onto(NORMAL).walk(1, 0).xp();

		long biggestStep = 0;
		long lastX = (Long) get("fineWX");
		long lastY = (Long) get("fineWY");
		for (int i = 0; i < 1000 && follower.isDoingAgility(); i++)
		{
			player.stand(20);
			long x = (Long) get("fineWX");
			long y = (Long) get("fineWY");
			biggestStep = Math.max(biggestStep, Math.max(Math.abs(x - lastX), Math.abs(y - lastY)));
			lastX = x;
			lastY = y;
		}
		assertEquals(1, done.size());
		assertTrue("never placed: " + trace, trace.stream().noneMatch(l -> l.contains("placed")));
		assertEquals(0, snaps);
		assertTrue("on at about two ticks behind: " + (player.setOffAt * 20L), player.setOffWhilePlayerOn);
		assertTrue("eased on, no pop: " + biggestStep + " units in a frame", biggestStep <= 16);
	}

	/**
	 * Waiting by the start, it turns the way the player set off before it
	 * goes, rather than sliding along the log sideways for a third of a
	 * second (QA4 #27).
	 */
	@Test
	public void itFacesTheWayThePlayerWentBeforeItSetsOff() throws ReflectiveOperationException
	{
		// From the north, onto a crossing taken eastward.
		standOn(new WorldPoint(52, 53, 0));
		game.at(60, 52, 0);
		follower.queueCrossing(new Recording(52, 50, 0).stand(100, 808).glide(4, 0, LOG_WALK, -1)
			.stand(200, 808).build(false));
		untilPlaying();
		int yaw = (Integer) get("yaw");
		assertTrue("facing east (1536) as it sets off: " + yaw, Math.abs(yaw - 1536) <= 256);
	}

	/**
	 * The game empties the hands for a climb, a squeeze or a swing, as the
	 * clip's own hand items say; the follower's crossing does too, and hands
	 * them back after (QA4 #12). A swapped pose, the balance, keeps them.
	 */
	@Test
	public void aClipThatEmptiesThePlayersHandsEmptiesTheFollowers() throws ReflectiveOperationException
	{
		game.animation(CLIMB, -1, -512, -512);
		int[] rebuilds = {0};
		follower.setOnCrossingHands(() -> rebuilds[0]++);
		game.at(70, 52, 1);
		follower.queueCrossing(new Recording(52, 50, 0).stand(200, LOG_READY).glide(2, 0, LOG_WALK, -1)
			.stand(600, 808, CLIMB).hop(1, 0, 1, CLIMB).stand(200, 808).build(false));
		List<int[]> seen = new ArrayList<>();
		for (int i = 0; i < 2000 && follower.isDoingAgility(); i++)
		{
			frame();
			int[] hands = follower.getCrossingHands();
			if (seen.isEmpty() ? hands != null : !java.util.Arrays.equals(seen.get(seen.size() - 1), hands))
			{
				seen.add(hands);
				if (hands != null)
				{
					// The rebuild for it swaps the model under the clip.
					Object clip = get("crossingController");
					follower.setAppearance(new com.follower.appearance.ComposedAppearance(
						(net.runelite.api.Model) java.lang.reflect.Proxy.newProxyInstance(
							net.runelite.api.Model.class.getClassLoader(), new Class<?>[]{net.runelite.api.Model.class},
							(proxy, method, args) -> method.getReturnType() == boolean.class ? false
								: method.getReturnType() == int.class ? 0 : null),
						com.follower.appearance.ComposedAppearance.Source.DUMP,
						new com.follower.appearance.Outfit()));
					assertTrue("the climb keeps playing on the new model, no stand put up for a frame",
						((RuneLiteObject) get("object")).getAnimationController() == clip);
				}
			}
		}
		assertEquals("emptied for the climb, and back after", 2, seen.size());
		assertEquals(-512, seen.get(0)[0]);
		assertEquals(-512, seen.get(0)[1]);
		assertEquals(null, seen.get(1));
		assertEquals("a rebuild for each", 2, rebuilds[0]);
	}

	// ------------------------------------------------------- rebuilt from XP

	/**
	 * Off a course, a climb with no XP inside the two ticks it waits is a
	 * ladder: let go, and the follower joins the player upstairs then, as it
	 * did before any agility, not eight seconds later (QA4 #3). XP that comes
	 * after it rebuilds nothing, the follower being there already. (This was
	 * a rebuild with no snap, before the wait was let go with the hop.)
	 */
	@Test
	public void offACourseAClimbWithNoXpIsALadderAndJoinedAtOnce()
	{
		Mover player = new Mover(51, 50, 0);
		follower.setOnAgilitySnap(player.watch::forgetMissed);
		player.stand(600).doing(CLIMB).stand(1200).hop(1, 0, 1).doing(-1);
		int hopped = player.frames;
		while (snaps == 0 && player.frames - hopped < 500)
		{
			player.stand(20);
		}
		long joinedMs = (player.frames - hopped) * 20L;
		assertEquals("joined upstairs", 1, snaps);
		assertTrue("once the hop's two ticks were up, not eight seconds on: " + joinedMs + " ms",
			joinedMs <= CrossingWatch.CONFIRM_WAIT_MS + 300);
		assertEquals(1, follower.getWorldLocation().getPlane());
		assertFalse(follower.isHoldingForAgility());

		player.xp().stand(2000);
		assertFalse("the late XP rebuilds nothing", follower.isDoingAgility());
		assertTrue(done.isEmpty());
	}

	/** On a course a hop waits five ticks for its XP; none, and it is a ladder after all. */
	@Test
	public void onACourseALadderIsJoinedOnceItsLongerWaitIsUp()
	{
		Mover player = new Mover(51, 50, 0);
		player.stand(600).xp();
		follower.holdForAgility();
		player.stand(600).doing(CLIMB).stand(1200).hop(1, 0, 1).doing(-1);
		int hopped = player.frames;
		while (snaps == 0 && player.frames - hopped < 500)
		{
			player.stand(20);
		}
		long joinedMs = (player.frames - hopped) * 20L;
		assertEquals(1, snaps);
		assertTrue(joinedMs + " ms", joinedMs >= CrossingWatch.CONFIRM_WAIT_MS
			&& joinedMs <= CrossingWatch.CONFIRM_WAIT_ON_COURSE_MS + 300);
	}

	// ----------------------------------------------------- a Draynor-like lap

	/**
	 * The seven obstacles of the Draynor rooftop course, in order, each as the
	 * game is thought to show it: the rough wall a clip and a hop up to the
	 * roof, plane 3, with its XP a tick late; two tightropes and the narrow
	 * wall on swapped base sets; the wall a clip and a one-tile glide; the gap
	 * a clip and a one-tile hop; the crate a glide and a hop back down to the
	 * ground. Copied in order, all seven, and the follower never snapped,
	 * placed or teleported.
	 */
	@Test
	public void aDraynorLikeLapIsCopiedObstacleByObstacleWithNoSnap()
	{
		// The harness's object is put in the scene by the watchdog on the
		// first frame a test runs: that one is not agility's.
		frames(1);
		trace.clear();
		standOn(new WorldPoint(64, 59, 0));
		Mover player = new Mover(63, 58, 0);
		player.stand(600);
		// Rough wall: up onto the roof.
		player.doing(REACHFORLADDER).stand(1200).hop(-1, 0, 3).doing(-1).stand(600).xp().stand(300);
		// Tightrope, west.
		player.walk(-3, 0).onto(ON_A_ROPE).stand(600).move(-9, 0, LOG_WALK, 4).onto(NORMAL).xp();
		// Tightrope, south.
		player.walk(0, -1).onto(ON_A_ROPE).stand(300).move(0, -9, LOG_WALK, 4).onto(NORMAL).xp();
		// Narrow wall: on, along, off.
		player.walk(-2, -2).doing(INTO_SIDESTEPL).onto(SIDESTEPPING).stand(600).doing(-1)
			.move(0, -3, WALK_SIDESTEPL, 4).doing(OUTOF_SIDESTEPL).stand(600).doing(-1).onto(NORMAL).xp();
		// Wall: the jump up, and a tile over.
		player.walk(0, -3).doing(WALL_JUMP).stand(600).doing(WALL_JUMP2).move(0, -1, 808, 8)
			.stand(300).doing(-1).xp();
		// Gap: set down a tile over, diagonally.
		player.walk(7, 0).doing(WALL_JUMPDOWN).stand(600).doing(WALL_JUMPDOWN2).hop(1, 1, 0)
			.stand(600).doing(-1).xp();
		// Crate: a tile on, then down to the ground.
		player.walk(4, 4).doing(WALL_JUMPDOWN).stand(600).move(1, 0, 808, 8).doing(WALL_JUMPDOWN2)
			.hop(2, 0, -3).stand(600).doing(-1).xp();
		player.walk(0, 2).stand(3000);
		runUntilDone();

		List<WorldPoint> starts = new ArrayList<>();
		done.forEach(c -> starts.add(c.getStart()));
		assertEquals("all seven, in order", List.of(
			new WorldPoint(63, 58, 0), new WorldPoint(59, 58, 3), new WorldPoint(50, 57, 3),
			new WorldPoint(48, 46, 3), new WorldPoint(48, 40, 3), new WorldPoint(55, 39, 3),
			new WorldPoint(60, 44, 3)), starts);
		assertEquals("down on the ground where the crate put them",
			new WorldPoint(63, 44, 0), done.get(6).getEnd());
		assertEquals("never snapped: " + trace, 0, snaps);
		assertTrue("never placed or snapped: " + trace,
			trace.stream().noneMatch(line -> line.contains("snap") || line.contains("placed")));
		assertTrue(shown.contains(REACHFORLADDER));
		assertTrue(shown.contains(WALK_SIDESTEPL));
		assertTrue(shown.contains(WALL_JUMPDOWN2));
	}

	// ---------------------------------------------------------------- snaps

	@Test
	public void justAfterAgilityItWaitsForAPlayerOnAnotherFloorThenSnaps()
	{
		follower.holdForAgility();
		game.at(51, 50, 3);
		// Six seconds: an obstacle the watch has not queued yet.
		frames(300);
		assertEquals("waited", 0, snaps);
		assertEquals(0, follower.getWorldLocation().getPlane());
		// Past the wait: whatever that was, it was not agility.
		frames(150);
		assertEquals(1, snaps);
		assertEquals(3, follower.getWorldLocation().getPlane());
	}

	@Test
	public void withNoAgilityAboutAFloorChangeSnapsAsBefore()
	{
		game.at(51, 50, 3);
		frames(2);
		assertEquals(1, snaps);
	}

	@Test
	public void aRealTeleportStillSnapsAtOnce()
	{
		Mover player = new Mover(51, 50, 0);
		player.stand(100);
		follower.holdForAgility();
		follower.queueCrossing(new Recording(60, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		player.hop(30, 0, 0);
		frames(2);
		assertFalse(follower.isDoingAgility());
		assertTrue("snapped", snaps >= 1);
		assertTrue(follower.getWorldLocation().distanceTo(new WorldPoint(81, 50, 0)) <= 2);
	}

	// -------------------------------------------------------- interruptions

	@Test
	public void pastTheCapTheQueueIsLetGoAndTheOldCatchUpTakesOver()
	{
		standOn(new WorldPoint(20, 50, 0));
		game.at(20 + FollowerEntity.MAX_BEHIND_TILES + 2, 52, 0);
		follower.queueCrossing(new Recording(22, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		follower.queueCrossing(new Recording(26, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		frames(2);
		assertFalse("let go", follower.isDoingAgility());
		assertTrue("and snapped to the player the usual way", snaps >= 1);
		assertTrue(follower.getWorldLocation().distanceTo(new WorldPoint(86, 52, 0)) <= 2);
	}

	@Test
	public void aLongCoursesWorthBehindIsNotLetGo()
	{
		// Forty-five tiles: past the old cap (the stay leash), well inside a lap.
		game.at(95, 52, 3);
		follower.queueCrossing(new Recording(52, 50, 0).stand(200, 808).glide(2, 0, 808, JUMP).build(false));
		runUntilDone();
		assertEquals(1, done.size());
		assertEquals("nor snapped after, with the player up on the roof and the wait still on", 0, snaps);
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

	/**
	 * The player, frame by frame, read by a real watch feeding this follower,
	 * with the follower's own frame run after each: the plugin's order.
	 */
	private final class Mover
	{
		final CrossingWatch watch = new CrossingWatch(follower);
		long clock = 1_000_000;
		long x;
		long y;
		int plane;
		int pose = 808;
		int action = -1;
		int[] base = NORMAL;
		int frames;
		/** The first frame the follower was playing a crossing, and whether the player was still on one then. */
		int setOffAt = -1;
		boolean setOffWhilePlayerOn;

		Mover(int tileX, int tileY, int plane)
		{
			x = tileX * 128L + 64;
			y = tileY * 128L + 64;
			this.plane = plane;
			watch.setTrace(trace::add);
			follower.setAgilityClock(() -> clock);
			frame();
		}

		void frame()
		{
			clock += 20;
			frames++;
			game.fineAt((int) x, (int) y, plane).posing(pose).animating(action).basePoses(base);
			watch.sample(CrossingWatch.Sample.read(game.player, clock));
			CrossingReplayTest.this.frame();
			if (setOffAt < 0 && follower.isPlayingCrossing())
			{
				setOffAt = frames;
				setOffWhilePlayerOn = watch.isWatching();
			}
		}

		Mover stand(long ms)
		{
			for (long i = 0; i < ms; i += 20)
			{
				frame();
			}
			return this;
		}

		Mover doing(int clip)
		{
			action = clip;
			return this;
		}

		/** The base set the game has on them now: their own, or an obstacle's. */
		Mover onto(int[] set)
		{
			base = set;
			pose = set[0];
			return this;
		}

		/** Their own walk, a tile every 32 frames. */
		Mover walk(int tilesX, int tilesY)
		{
			return move(tilesX, tilesY, base[1], 4);
		}

		Mover move(int tilesX, int tilesY, int movePose, int unitsPerFrame)
		{
			long toX = x + tilesX * 128L;
			long toY = y + tilesY * 128L;
			pose = movePose;
			while (x != toX || y != toY)
			{
				x += Math.max(-unitsPerFrame, Math.min(unitsPerFrame, toX - x));
				y += Math.max(-unitsPerFrame, Math.min(unitsPerFrame, toY - y));
				frame();
			}
			pose = base[0];
			return this;
		}

		/** Put down so many tiles over and floors up or down in one frame. */
		Mover hop(int tilesX, int tilesY, int floors)
		{
			x += tilesX * 128L;
			y += tilesY * 128L;
			plane += floors;
			frame();
			return this;
		}

		Mover xp()
		{
			watch.noteAgilityXp(clock);
			return this;
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
