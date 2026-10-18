package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Catching up on a long walk. Play test, 18 Oct: the follower fell a little
 * behind, then flicked into the run pose for a split second and straight back
 * to the walk, again and again, because the burst switched on and off at the
 * one backlog threshold. Once begun it now runs until properly caught up.
 */
public class CatchUpPaceTest
{
	private final FakeGame game = new FakeGame().withCollision().at(80, 50, 0);
	private final FollowerEntity follower = new FollowerEntity(game.client, null);

	@Test
	public void aCatchUpIsOneSteadyJogNotAFlicker() throws Exception
	{
		WorldPoint start = new WorldPoint(50, 50, 0);
		set("tile", start);
		set("fineWX", start.getX() * 128L + 64);
		set("fineWY", start.getY() * 128L + 64);
		Deque<WorldPoint> route = deque("route");
		@SuppressWarnings("unchecked")
		Deque<Boolean> run = (Deque<Boolean>) get("routeRun");
		// Walking, and ten tiles behind: a backlog worth catching up on.
		for (int x = 51; x <= 60; x++)
		{
			route.add(new WorldPoint(x, 50, 0));
			run.add(false);
		}
		set("yaw", 1536);
		set("dstYaw", 1536);

		List<Integer> speeds = new ArrayList<>();
		Method move = FollowerEntity.class.getDeclaredMethod("moveContinuous", double.class);
		move.setAccessible(true);
		while (!route.isEmpty())
		{
			move.invoke(follower, 1.0);
			speeds.add((Integer) get("lastMoveSpeed"));
			// The player keeps walking: a tile more every 32 cycles.
			if (speeds.size() % 32 == 0)
			{
				WorldPoint last = route.peekLast();
				if (last != null && last.getX() < 70)
				{
					route.add(new WorldPoint(last.getX() + 1, 50, 0));
					run.add(false);
				}
			}
		}

		assertTrue("it did catch up at a run", speeds.contains(8));
		int firstRun = speeds.indexOf(8);
		int backToWalk = firstRun;
		while (backToWalk < speeds.size() && speeds.get(backToWalk) >= 8)
		{
			backToWalk++;
		}
		assertTrue("the run lasts more than a moment: " + speeds, backToWalk - firstRun > 64);
		for (int i = backToWalk; i < speeds.size(); i++)
		{
			assertTrue("once back to a walk it stays walking, no flick back: " + speeds.subList(backToWalk, speeds.size()),
				speeds.get(i) < 8);
		}
	}

	@SuppressWarnings("unchecked")
	private Deque<WorldPoint> deque(String name) throws Exception
	{
		return (Deque<WorldPoint>) get(name);
	}

	private Object get(String name) throws Exception
	{
		Field f = FollowerEntity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(follower);
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = FollowerEntity.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(follower, value);
	}
}
