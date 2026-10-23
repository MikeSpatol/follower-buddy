// public:file agility,agility-all
package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.follower.sim.FakeGame;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The obstacles the game starts with a running jump, as the user's traces
 * (17-18 Oct) show them: the player runs up, and on the next tick the
 * server has them three or four tiles over the gap while the client still
 * draws their walk, with the jump's clip on. Nothing odd shows until the
 * far side, so the crossing used to start there, where the follower cannot
 * walk: stuck, then placed, every lap.
 *
 * <p>Each is run at 58 frames a second, as in the traces, and at 100. What
 * the user asked for: begun from the near side about two ticks behind the
 * player, never placed. And the same course's other pops: the cable
 * swing's walk past its start, and the rope that ends on a closed tile.
 */
public class RunningJumpReplayTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The user's own unarmed set in the trace: 808 stand, 819 walk, 824 run, 820-822 turns, 823 turn on the spot. */
	private static final int[] NORMAL = {808, 819, 824, 820, 821, 822, 823, 823};

	private int offsetX;
	private int offsetY;

	private FakeGame game;
	private FollowerEntity follower;
	private Player player;
	private final List<Crossing> done = new ArrayList<>();
	private final List<String> trace = new ArrayList<>();
	/** The real lag at each crossing the follower begins: the player's clock then, less when they began it. */
	private final List<Long> lags = new ArrayList<>();
	/** The follower's x, in the real world, each frame a crossing waits for it. */
	private final java.util.Set<Integer> approachX = new java.util.TreeSet<>();

	private WorldPoint tile(int x, int y, int plane)
	{
		return new WorldPoint(x - offsetX, y - offsetY, plane);
	}

	private void block(int x, int y)
	{
		game.blockTile(x - offsetX, y - offsetY);
	}

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		game = new FakeGame().withCollision().keepsRuneLiteObjects();
		for (int id : new int[]{741, 764, 1122, 1124, 1332, 1995, 2583, 2588})
		{
			game.animation(id, -1);
		}
		for (int pose : NORMAL)
		{
			game.animation(pose, 0);
		}
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setAgilityTrace(trace::add); // public:cut dev
		follower.setOnCrossed(done::add);
		set("object", new RuneLiteObject(game.client));
		set("animatable", true);
		set("spawned", true);
	}

	@Test
	public void alKharidTropicalTreeAt58Fps()
	{
		tree(58);
	}

	@Test
	public void alKharidTropicalTreeAt100Fps()
	{
		tree(100);
	}

	@Test
	public void varrockWallAt58Fps()
	{
		wall(58);
	}

	@Test
	public void varrockWallAt100Fps()
	{
		wall(100);
	}

	@Test
	public void varrockClothesLineAt58Fps()
	{
		clothesLine(58, false);
	}

	@Test
	public void varrockClothesLineAt100Fps()
	{
		clothesLine(100, false);
	}

	@Test
	public void aFallOffTheClothesLineAt58Fps()
	{
		clothesLine(58, true);
	}

	@Test
	public void aFallOffTheClothesLineAt100Fps()
	{
		clothesLine(100, true);
	}

	/**
	 * Al Kharid's tropical tree (17 Oct 11:35:45-49): run up to 3318,3165,
	 * put four tiles over the gap in the walk pose with 2583 on, then the
	 * swing (1122, 1124) and the hop up a floor with its XP.
	 */
	private void tree(int fps)
	{
		offsetX = 3265;
		offsetY = 3113;
		for (int x = 3300; x <= 3330; x++)
		{
			for (int y = 3166; y <= 3168; y++)
			{
				block(x, y);
			}
		}
		player = new Player(fps, tile(3315, 3163, 1));
		player.idle(2).xp();
		player.runTo(3318, 3165).doing(2583).leap(3318, 3169).idle(1).poseBack()
			.doing(1122).forced(3318, 3170).idle(1).doing(1124).forced(3317, 3174).idle(1)
			.doing(2588).hop(3317, 3174, 2).doing(-1).xp().idle(17);
		assertBegunFromTheNearSide(fps, "tree", start -> start.getY() + offsetY <= 3165);
	}

	/**
	 * Varrock's wall (18 Oct 14:39:58-14:40:10): run to 3193,3416 with 1995
	 * on in the walk pose, put three tiles over to 3190,3414 with 2583, then
	 * along the wall (1122) and up (1124) with the XP.
	 */
	private void wall(int fps)
	{
		offsetX = 3150;
		offsetY = 3360;
		for (int x = 3189; x <= 3192; x++)
		{
			for (int y = 3405; y <= 3415; y++)
			{
				block(x, y);
			}
		}
		player = new Player(fps, tile(3197, 3416, 1));
		player.idle(2).xp();
		player.runTo(3194, 3416).doing(1995).walkTo(3193, 3416).doing(2583).leap(3190, 3414).idle(1).poseBack()
			.doing(1122).forced(3190, 3413).forced(3190, 3412).forced(3190, 3411).forced(3190, 3410)
			.doing(1124).forced(3190, 3409).idle(1).doing(-1).hop(3192, 3406, 3).xp().idle(20);
		assertBegunFromTheNearSide(fps, "wall", start -> start.getX() + offsetX >= 3193);
	}

	/**
	 * Varrock's clothes line (18 Oct 14:39:44-49): carried two tiles a tick
	 * from 3214,3414 in the walk pose with 741 on, a pause, then the last
	 * tiles standing, and the XP. Or the fall (14:55:10-15): 764 on the line
	 * at 3212,3414, and down to the ground floor with a hit.
	 */
	private void clothesLine(int fps, boolean falls)
	{
		offsetX = 3150;
		offsetY = 3360;
		for (int x = 3209; x <= 3213; x++)
		{
			block(x, 3414);
		}
		player = new Player(fps, tile(3219, 3414, 3));
		player.idle(2).xp();
		player.runTo(3214, 3414).idle(1).doing(741).carried(3212, 3414);
		if (falls)
		{
			player.doing(764).idle(1).doing(1332).hop(3212, 3414, 0).hurt().idle(3).poseBack().doing(-1).idle(20);
		}
		else
		{
			player.carried(3210, 3414).idle(1).poseBack().forced(3209, 3414).forced(3208, 3414)
				.doing(-1).xp().idle(20);
		}
		assertBegunFromTheNearSide(fps, falls ? "fall" : "clothes line",
			start -> start.getX() + offsetX >= 3213);
		assertFalse("one crossing, not split by its XP: " + trace,
			trace.stream().anyMatch(line -> line.contains("rebuilt")));
	}

	/**
	 * Al Kharid's cable swing (17 Oct 12:36:04-10): run up to 3266,3166, and
	 * on the very next tick the game carries them three tiles east (1995,
	 * standing), then fifteen more on the cable (751) with the XP. The
	 * follower is on the player's trail as it is queued, with steps
	 * released on past the start towards where they were carried, as the
	 * trace shows (3265,3168, then 3266,3166, then 3268,3166): it should
	 * stop at the start, not walk on past it and back.
	 */
	private void cableSwing(int fps)
	{
		offsetX = 3240;
		offsetY = 3113;
		for (int y = 3163; y <= 3169; y++)
		{
			for (int x = 3270; x <= 3283; x++)
			{
				block(x, y);
			}
		}
		player = new Player(fps, tile(3268, 3172, 3));
		player.idle(2).xp();
		player.runTo(3266, 3168).runTo(3266, 3166);
		standOn(tile(3265, 3168, 3));
		released(tile(3265, 3167, 3), tile(3266, 3166, 3), tile(3267, 3166, 3), tile(3268, 3166, 3));
		player.doing(1995).forced(3269, 3166)
			.doing(751).xp().forcedFast(3284, 3166).idle(1).doing(-1).idle(12);
		String said = "cable at " + fps + " fps: lags " + lags + ", approached over x " + approachX
			+ "\n" + String.join("\n", trace);
		assertEquals(said, 1, done.size());
		assertEquals(said, tile(3266, 3166, 3), done.get(0).getStart());
		assertTrue(said, approachX.stream().allMatch(x -> x <= 3266));
		assertTrue(said, lags.get(0) <= 1500);
	}

	@Test
	public void alKharidCableSwingAt58Fps()
	{
		cableSwing(58);
	}

	@Test
	public void alKharidCableSwingAt100Fps()
	{
		cableSwing(100);
	}

	/**
	 * Al Kharid's second tightrope ends on its last rope tile, 3302,3186,3,
	 * which is closed, and the player stops beside it for the mark (17 Oct
	 * 11:49:48-56). The follower stood on the rope for the whole agility
	 * patience, then hopped a tile. It steps off at once instead.
	 */
	@Test
	public void aRopeThatEndsOnAClosedTileIsSteppedOffAtOnce()
	{
		offsetX = 3265;
		offsetY = 3113;
		for (int x = 3292; x <= 3302; x++)
		{
			block(x, 3186);
		}
		player = new Player(58, tile(3302, 3187, 3));
		player.idle(1);
		standOn(tile(3302, 3186, 3));
		trace.clear();
		player.xp().idle(3);
		WorldPoint at = follower.getWorldLocation();
		String said = "at " + at + "\n" + String.join("\n", trace);
		assertFalse(said, at.equals(tile(3302, 3186, 3)));
		assertTrue(said, at.distanceTo(tile(3302, 3187, 3)) == 1);
		assertFalse("walked, not hopped: " + said, trace.stream().anyMatch(line -> line.contains("snap")));
	}

	private void assertBegunFromTheNearSide(int fps, String what,
		java.util.function.Predicate<WorldPoint> nearSide)
	{
		String said = what + " at " + fps + " fps: lags " + lags + "\n" + String.join("\n", trace);
		assertEquals(said, 1, done.size());
		assertTrue(said, nearSide.test(done.get(0).getStart()));
		assertFalse(said, trace.stream().anyMatch(line -> line.contains("placed")));
		assertTrue(said, lags.get(0) <= 1500);
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
		/** Which way they face: the way they last moved, as the client turns them. */
		int orientation;

		/** Tiles the drawn player has still to move through: x, y, units a cycle, and 0 a step, 1 forced standing, 2 forced in the walk pose. */
		final Deque<double[]> steps = new ArrayDeque<>();
		boolean movedThisCycle;
		boolean forcedThisCycle;
		boolean ranThisCycle;
		boolean walkPoseThisCycle;
		/** The walk pose held on after a running jump, as the trace shows it. */
		boolean walkPoseHeld;

		private Crossing current;

		Player(int fps, WorldPoint at)
		{
			frameMs = 1000.0 / fps;
			serverX = at.getX();
			serverY = at.getY();
			plane = at.getPlane();
			drawnX = serverX * 128 + 64;
			drawnY = serverY * 128 + 64;
			watch.setTrace(trace::add); // public:cut dev
			follower.setAgilityClock(() -> (long) clockMs);
			game.fineAt((int) drawnX, (int) drawnY, plane);
			frame();
			// Started beside the player, as after any snap.
			standOn(new WorldPoint(serverX + 1, serverY, plane));
		}

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

		Player walkTo(int x, int y)
		{
			return moveTo(x - offsetX, y - offsetY, 1, 4, 0);
		}

		Player runTo(int x, int y)
		{
			return moveTo(x - offsetX, y - offsetY, 2, 8, 0);
		}

		/** Carried two tiles a tick by the game, drawn in the walk pose: the clothes line. */
		Player carried(int x, int y)
		{
			walkPoseHeld = true;
			return moveTo(x - offsetX, y - offsetY, 2, 8, 2);
		}

		private Player moveTo(int x, int y, int perTick, int speed, int kind)
		{
			while (serverX != x || serverY != y)
			{
				for (int i = 0; i < perTick && (serverX != x || serverY != y); i++)
				{
					serverX += Integer.signum(x - serverX);
					serverY += Integer.signum(y - serverY);
					steps.addLast(new double[]{serverX, serverY, speed, kind});
				}
				tick();
			}
			return this;
		}

		/** Put over to the next tile by the game in a handful of cycles, standing. */
		Player forced(int x, int y)
		{
			serverX = x - offsetX;
			serverY = y - offsetY;
			steps.addLast(new double[]{serverX, serverY, 16, 1});
			return tick();
		}

		/** Put a long way over by the game, quickly, standing: a swing. */
		Player forcedFast(int x, int y)
		{
			serverX = x - offsetX;
			serverY = y - offsetY;
			steps.addLast(new double[]{serverX, serverY, 64, 1});
			return tick();
		}

		/** A running jump: put over a gap by the game, still drawn in the walk pose. */
		Player leap(int x, int y)
		{
			serverX = x - offsetX;
			serverY = y - offsetY;
			steps.addLast(new double[]{serverX, serverY, 24, 2});
			walkPoseHeld = true;
			return tick();
		}

		Player poseBack()
		{
			walkPoseHeld = false;
			return this;
		}

		/** Put down somewhere new in one frame: a climb, a fall. */
		Player hop(int x, int y, int toPlane)
		{
			steps.clear();
			serverX = x - offsetX;
			serverY = y - offsetY;
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

		/** A fall's damage. */
		Player hurt()
		{
			watch.noteDamage((long) clockMs);
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
			int pose = !movedThisCycle ? NORMAL[walkPoseHeld ? 1 : 0]
				: forcedThisCycle ? NORMAL[walkPoseThisCycle ? 1 : 0]
				: NORMAL[ranThisCycle ? 2 : 1];
			game.fineAt((int) Math.round(drawnX), (int) Math.round(drawnY), plane, serverX, serverY)
				.posing(pose).animating(action).basePoses(NORMAL).turned(orientation);
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
				walkPoseThisCycle = step[3] == 2;
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
				follower.updateFrame();
				if (lags.isEmpty() && !follower.isPlayingCrossing() && follower.getCrossingsQueued() > 0)
				{
					approachX.add(follower.getWorldLocation().getX() + offsetX);
				}
				if (follower.isPlayingCrossing() && (!wasPlaying || get("playing") != current))
				{
					current = (Crossing) get("playing");
					lags.add(now - current.getStartedAtMs());
				}
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		}
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

	/** Steps already released to the follower's feet, the last its server tile. */
	@SuppressWarnings("unchecked")
	private void released(WorldPoint... steps)
	{
		try
		{
			for (WorldPoint step : steps)
			{
				((Deque<WorldPoint>) get("route")).addLast(step);
				((Deque<Boolean>) get("routeRun")).addLast(true);
			}
			set("simTile", steps[steps.length - 1]);
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
