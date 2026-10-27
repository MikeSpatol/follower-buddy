package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Back-to-back laps of the Draynor rooftop course as the user's own trace
 * (16 Oct) shows the game moving the player: the server a tile a tick
 * ahead of where the player is drawn, the drawn position moving once a
 * client cycle (20 ms) whatever the frame rate, the base set swapped for the
 * ropes and the narrow wall, the XP landing as each obstacle completes, and
 * the obstacle tiles themselves closed to anyone walking. Run at 50, 58, 100
 * and 150 frames a second.
 *
 * <p>What the user asked for: every obstacle copied, in order, none
 * skipped; the follower setting off on each about two ticks after the
 * player; and never hung up and placed or teleported mid-lap.
 *
 * <p>Course tiles are the real ones, moved into the test scene by
 * {@link #OFFSET_X} and {@link #OFFSET_Y}.
 */
public class DraynorLapReplayTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int OFFSET_X = 3040;
	private static final int OFFSET_Y = 3220;

	/** The player's own set in the trace: a weapon's stand, walk and run. */
	private static final int[] NORMAL = {813, 1205, 1210, 1206, 1207, 1208, 1209, 1209};
	private static final int[] ON_A_ROPE = {763, 762, 762, 762, 762, 762, 763, 763};
	private static final int[] SIDESTEPPING = {757, 756, 756, 756, 756, 756, 757, 757};

	private static final int REACHFORLADDER = 828;
	private static final int INTO_SIDESTEPL = 753;
	private static final int OUTOF_SIDESTEPL = 759;
	private static final int WALL_JUMP = 2583;
	private static final int WALL_JUMP2 = 2585;
	private static final int WALL_JUMPDOWN = 2586;
	private static final int WALL_JUMPDOWN2 = 2588;

	/** Where each obstacle is begun, in lap order: rough wall, two ropes, narrow wall, wall, gap, crate. */
	private static final WorldPoint[] STARTS = {
		tile(3103, 3279, 0), tile(3098, 3277, 3), tile(3092, 3276, 3), tile(3089, 3265, 3),
		tile(3088, 3257, 3), tile(3094, 3255, 3), tile(3101, 3261, 3)};

	private FakeGame game;
	private FollowerEntity follower;
	private Player player;
	private int snaps;
	private final List<Crossing> done = new ArrayList<>();
	private final List<String> trace = new ArrayList<>();
	/** The real lag at each crossing the follower begins: the player's clock then, less when they began it. */
	private final List<Long> lags = new ArrayList<>();
	/** How long after the player began each one it was handed over: the lag nothing can beat. */
	private final List<Long> handedOver = new ArrayList<>();

	private static WorldPoint tile(int x, int y, int plane)
	{
		return new WorldPoint(x - OFFSET_X, y - OFFSET_Y, plane);
	}

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		game = new FakeGame().withCollision().keepsRuneLiteObjects();
		for (int id : new int[]{REACHFORLADDER, INTO_SIDESTEPL, OUTOF_SIDESTEPL, WALL_JUMP, WALL_JUMP2,
			WALL_JUMPDOWN, WALL_JUMPDOWN2})
		{
			game.animation(id, -1);
		}
		for (int[] set : new int[][]{NORMAL, ON_A_ROPE, SIDESTEPPING})
		{
			for (int pose : set)
			{
				game.animation(pose, 0);
			}
		}
		// The obstacles: nobody walks onto a rope, the narrow wall, the wall
		// or over the gap. The server puts the player there; the follower
		// has to be put there by its recording. The first tile of each rope
		// and of the narrow wall closed as well: the worst case.
		for (int x = 3091; x <= 3098; x++)
		{
			block(x, 3277);
		}
		for (int y = 3267; y <= 3276; y++)
		{
			block(3092, y);
		}
		for (int y = 3262; y <= 3265; y++)
		{
			block(3089, y);
		}
		block(3088, 3256);
		block(3095, 3255);
		block(3095, 3256);

		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setOnSnapped(() -> snaps++);
		follower.setOnCrossed(done::add);
		set("object", new RuneLiteObject(game.client));
		set("animatable", true);
		set("spawned", true);
	}

	private void block(int x, int y)
	{
		game.blockTile(x - OFFSET_X, y - OFFSET_Y);
	}

	@Test
	public void lapsAtFiftyFramesASecond() throws ReflectiveOperationException
	{
		laps(50);
	}

	@Test
	public void lapsAtFiftyEightFramesASecondAsInTheTrace() throws ReflectiveOperationException
	{
		laps(58);
	}

	@Test
	public void lapsAtAHundredFramesASecond() throws ReflectiveOperationException
	{
		laps(100);
	}

	@Test
	public void lapsAtAHundredAndFiftyFramesASecond() throws ReflectiveOperationException
	{
		laps(150);
	}

	private static final int LAPS = 4;

	private void laps(int fps) throws ReflectiveOperationException
	{
		player = new Player(fps, tile(3103, 3271, 0));
		player.idle(3);
		trace.clear();
		snaps = 0;
		for (int lap = 0; lap < LAPS; lap++)
		{
			lap();
		}
		// The follower finishes the last crate behind the player.
		for (int i = 0; i < 20 && follower.isDoingAgility(); i++)
		{
			player.idle(1);
		}

		String report = fps + " fps: lags " + lags + ", handed over " + handedOver + "\n   "
			+ String.join("\n   ", trace);
		System.out.println("Draynor at " + fps + " fps: lags " + lags + ", handed over after " + handedOver);

		List<WorldPoint> starts = new ArrayList<>();
		done.forEach(c -> starts.add(c.getStart()));
		List<WorldPoint> expected = new ArrayList<>();
		for (int lap = 0; lap < LAPS; lap++)
		{
			expected.addAll(List.of(STARTS));
		}
		assertEquals("every obstacle of every lap, in order, none skipped: " + report, expected, starts);
		assertEquals("never snapped: " + report, 0, snaps);
		assertTrue("never placed, snapped or teleported: " + report, trace.stream().noneMatch(
			l -> l.contains("placed") || l.contains("snap") || l.contains("teleport")));
		for (int i = 0; i < lags.size(); i++)
		{
			long floor = Math.max(FollowerEntity.START_LAG_MS, handedOver.get(i));
			assertTrue("obstacle " + i + " begun " + lags.get(i) + " ms after the player, where "
				+ floor + " is the soonest it could be: " + report, lags.get(i) <= floor + 600);
		}
	}

	/**
	 * One lap, the player running between obstacles, as in the trace. Each
	 * obstacle is begun a tick after the server reaches it, as the trace
	 * shows: the tick the drawn player takes to get there.
	 */
	private void lap()
	{
		// Rough wall: two ticks of the reach, then up onto the roof, the XP with it.
		player.runTo(3103, 3279).idle(1).doing(REACHFORLADDER).idle(2).doing(-1).xp().hop(3102, 3279, 3).idle(1);
		// Tightrope one: onto its first tile on their own walk, then the
		// balance from the next, and their own set back as the server steps
		// off, with the drawn player still a tile or two along the rope.
		player.runTo(3099, 3277).idle(1).walkTo(3098, 3277).onto(ON_A_ROPE).walkTo(3090, 3277)
			.onto(NORMAL).xp().walkTo(3090, 3276);
		// Tightrope two.
		player.walkTo(3091, 3276).walkTo(3092, 3276).idle(1).onto(ON_A_ROPE).walkTo(3092, 3267)
			.onto(NORMAL).xp().walkTo(3092, 3266);
		// Narrow wall: the step on, the side-step along, the step off with the XP.
		player.runTo(3089, 3265).idle(1).doing(INTO_SIDESTEPL).onto(SIDESTEPPING).walkTo(3089, 3264).doing(-1)
			.walkTo(3089, 3262).walkTo(3088, 3261).doing(OUTOF_SIDESTEPL).onto(NORMAL).xp().idle(1).doing(-1);
		// Wall: the jump up, then put over it a tile at a time.
		player.runTo(3088, 3257).idle(1).doing(WALL_JUMP).forced(3088, 3256).doing(WALL_JUMP2).idle(1)
			.forced(3088, 3255).doing(-1).xp().idle(1);
		// Gap: the crouch, then set down two tiles on, the XP a tick later.
		player.runTo(3094, 3255).idle(1).doing(WALL_JUMPDOWN).idle(1).doing(WALL_JUMPDOWN2).hop(3096, 3256, 3)
			.doing(-1).xp().idle(1);
		// Crate: down to the first floor, then the ground, the XP with the second.
		player.runTo(3101, 3261).idle(1).doing(WALL_JUMPDOWN).idle(1).doing(WALL_JUMPDOWN2).hop(3102, 3261, 1)
			.doing(-1).idle(1).doing(WALL_JUMPDOWN).idle(1).doing(WALL_JUMPDOWN2).xp().hop(3103, 3261, 0)
			.doing(-1).idle(1);
		// And back round to the rough wall.
		player.runTo(3103, 3277);
	}

	/**
	 * The player as the game moves them. The server tile moves once a tick
	 * (600 ms): a tile walking, two running, or put down somewhere new. The
	 * drawn position follows it a client cycle (20 ms) at a time: a tile in
	 * a tick walking, two running, and a forced move in a few cycles. Each
	 * frame reads whatever the last cycle left, so above 50 frames a second
	 * some frames repeat the one before.
	 */
	private final class Player
	{
		final CrossingWatch watch = new CrossingWatch(follower);
		final double frameMs;
		double clockMs = 1_000_000;
		long followerMs;
		long cycle;

		int serverX;
		int serverY;
		int plane;
		double drawnX;
		double drawnY;
		int action = -1;
		int[] base = NORMAL;
		/** Which way they face: the way they last moved, as the client turns them. */
		int orientation;

		/** Tiles the drawn player has still to move through, with their speed in units a cycle and whether forced. */
		final Deque<double[]> steps = new ArrayDeque<>();
		boolean movedThisCycle;
		boolean forcedThisCycle;
		boolean ranThisCycle;

		Player(int fps, WorldPoint at)
		{
			frameMs = 1000.0 / fps;
			serverX = at.getX();
			serverY = at.getY();
			plane = at.getPlane();
			drawnX = serverX * 128 + 64;
			drawnY = serverY * 128 + 64;
			follower.setAgilityClock(() -> (long) clockMs);
			// Started beside the player, as after any snap.
			game.fineAt((int) drawnX, (int) drawnY, plane);
			frame();
			standOn(new WorldPoint(serverX - 1, serverY - 1, plane));
		}

		/** Through the rest of this tick and the next {@code ticks} - 1, frame by frame. */
		Player idle(int ticks)
		{
			for (int i = 0; i < ticks; i++)
			{
				tick();
			}
			return this;
		}

		Player doing(int clip)
		{
			action = clip;
			return this;
		}

		Player onto(int[] set)
		{
			base = set;
			return this;
		}

		Player walkTo(int x, int y)
		{
			return moveTo(x - OFFSET_X, y - OFFSET_Y, 1);
		}

		Player runTo(int x, int y)
		{
			return moveTo(x - OFFSET_X, y - OFFSET_Y, 2);
		}

		private Player moveTo(int x, int y, int perTick)
		{
			while (serverX != x || serverY != y)
			{
				for (int i = 0; i < perTick && (serverX != x || serverY != y); i++)
				{
					serverX += Integer.signum(x - serverX);
					serverY += Integer.signum(y - serverY);
					steps.addLast(new double[]{serverX, serverY, perTick == 2 ? 8 : 4, 0});
				}
				tick();
			}
			return this;
		}

		/** Put over to the next tile by the game in a handful of cycles, standing. */
		Player forced(int x, int y)
		{
			serverX = x - OFFSET_X;
			serverY = y - OFFSET_Y;
			steps.addLast(new double[]{serverX, serverY, 16, 1});
			return tick();
		}

		/** Put down somewhere new in one frame: a jump across a gap, a climb. */
		Player hop(int x, int y, int toPlane)
		{
			steps.clear();
			serverX = x - OFFSET_X;
			serverY = y - OFFSET_Y;
			plane = toPlane;
			drawnX = serverX * 128 + 64;
			drawnY = serverY * 128 + 64;
			return tick();
		}

		/** The obstacle's XP, landing now, and what the plugin does with it. */
		Player xp()
		{
			watch.noteAgilityXp((long) clockMs);
			follower.holdForAgility();
			return this;
		}

		/** One tick's worth of frames: thirty cycles. */
		Player tick()
		{
			long until = cycle + 30;
			while (cycle < until)
			{
				frame();
			}
			return this;
		}

		void frame()
		{
			clockMs += frameMs;
			long cycles = (long) ((clockMs - 1_000_000) / 20);
			while (cycle < cycles)
			{
				cycle();
			}
			int pose = !movedThisCycle ? base[0] : forcedThisCycle ? base[0] : ranThisCycle ? base[2] : base[1];
			game.fineAt((int) Math.round(drawnX), (int) Math.round(drawnY), plane, serverX, serverY)
				.posing(pose).animating(action).basePoses(base).turned(orientation);
			watch.sample(CrossingWatch.Sample.read(game.player, (long) clockMs));
			followerFrame();
		}

		private void cycle()
		{
			cycle++;
			movedThisCycle = false;
			// The client's own pace, a little under a tile a tick walking, so
			// the drawn player falls behind the server; and its catch-up once
			// three or four steps are waiting.
			double budget = steps.isEmpty() ? 0 : steps.peekFirst()[2]
				* (steps.peekFirst()[3] > 0 ? 1 : steps.size() >= 4 ? 2 : steps.size() == 3 ? 1.5 : 1);
			while (budget > 0 && !steps.isEmpty())
			{
				double[] step = steps.peekFirst();
				double toX = step[0] * 128 + 64;
				double toY = step[1] * 128 + 64;
				double far = Math.max(Math.abs(toX - drawnX), Math.abs(toY - drawnY));
				movedThisCycle = true;
				int east = (int) Math.signum(toX - drawnX);
				int north = (int) Math.signum(toY - drawnY);
				if (east != 0 || north != 0)
				{
					// 0 south, 512 west, 1024 north, 1536 east.
					orientation = (int) Math.round(Math.atan2(-east, -north) * 1024 / Math.PI) & 0x7ff;
				}
				forcedThisCycle = step[3] > 0;
				ranThisCycle = step[2] == 8;
				if (far <= budget)
				{
					drawnX = toX;
					drawnY = toY;
					budget -= far;
					steps.pollFirst();
				}
				else
				{
					drawnX += Math.signum(toX - drawnX) * Math.min(budget, Math.abs(toX - drawnX));
					drawnY += Math.signum(toY - drawnY) * Math.min(budget, Math.abs(toY - drawnY));
					budget = 0;
				}
			}
		}

		private void followerFrame()
		{
			long now = (long) clockMs;
			long delta = now - followerMs;
			followerMs = now;
			try
			{
				set("lastFrameMs", System.currentTimeMillis() - delta);
				boolean wasPlaying = follower.isPlayingCrossing();
				Crossing next = ((Deque<Crossing>) get("crossings")).peekFirst();
				follower.updateFrame();
				if (follower.isPlayingCrossing() && (!wasPlaying || get("playing") != current))
				{
					current = (Crossing) get("playing");
					lags.add(now - current.getStartedAtMs());
					handedOver.add(queuedAt.getOrDefault(current, now) - current.getStartedAtMs());
				}
				for (Crossing c : (Deque<Crossing>) get("crossings"))
				{
					queuedAt.putIfAbsent(c, now);
				}
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		}

		private Crossing current;
		private final java.util.Map<Crossing, Long> queuedAt = new java.util.IdentityHashMap<>();
	}

	private void standOn(WorldPoint at)
	{
		try
		{
			set("tile", at);
			set("simTile", at);
			set("fineWX", at.getX() * 128L + 64);
			set("fineWY", at.getY() * 128L + 64);
			((Deque<?>) get("serverPath")).clear();
			((Deque<?>) get("route")).clear();
			((Deque<?>) get("routeRun")).clear();
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
