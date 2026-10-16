package com.follower;

import com.follower.follower.Crossing;
import com.follower.follower.CrossingWatch;
import com.follower.follower.FollowerEntity;
import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When the plugin lets the follower do agility at all. A Stay the player
 * gave means no agility; thrall work, a film, and the setting turned off
 * let the queue go; a logout or a hop too. A cutscene only stops the
 * watching, and what is queued waits for it. A scene rebuild is neither:
 * the recording carries on after it. And while agility has the follower,
 * the seat, the moments and the spectator stand aside.
 *
 * <p>Plugin glue no harness drives, so it goes through a bare plugin as
 * StayHoldsTest does, with a stand-in for the follower that only counts.
 */
public class AgilityGateTest
{
	private static final int[] NORMAL = {808, 819, 824, 820, 821, 822, 823, 823};

	/** Counts what the plugin asks of the follower's agility. */
	private static final class Agile extends FollowerEntity
	{
		boolean doing;
		int cleared;

		Agile()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public boolean isDoingAgility()
		{
			return doing;
		}

		@Override
		public void clearCrossings()
		{
			cleared++;
		}

		@Override
		public void expectCrossing(WorldPoint start)
		{
		}

		@Override
		public void queueCrossing(Crossing crossing)
		{
		}

		@Override
		public void cancelExpectedCrossing()
		{
		}
	}

	private final FakeGame game = new FakeGame();
	private final Agile follower = new Agile();
	private final CrossingWatch watch = new CrossingWatch(follower);
	private final FollowerPlugin plugin = new FollowerPlugin();
	private boolean settingOn = true;

	@Before
	public void setUp() throws ReflectiveOperationException
	{
		set("client", game.client);
		set("follower", follower);
		set("crossingWatch", watch);
		set("config", new FollowerConfig()
		{
			@Override
			public boolean copiesAgility()
			{
				return settingOn;
			}
		});
		game.at(50, 50, 0).basePoses(NORMAL).posing(808);
		frame();
	}

	@Test
	public void theSettingIsOnOutOfTheBox()
	{
		assertTrue(new FollowerConfig()
		{
		}.copiesAgility());
	}

	@Test
	public void somethingOddIsWatchedWhenNothingStandsInTheWay() throws ReflectiveOperationException
	{
		somethingOdd();
		assertTrue(watch.isWatching());
		assertEquals(0, follower.cleared);
	}

	@Test
	public void aStayTheyGaveMeansNoAgility() throws ReflectiveOperationException
	{
		set("parkedByPlayer", true);
		lettingGo();
	}

	@Test
	public void thrallWorkLetsItGo() throws ReflectiveOperationException
	{
		set("thrallLimbo", true);
		lettingGo();
	}

	@Test
	public void aFilmLetsItGo() throws ReflectiveOperationException
	{
		set("filming", true);
		lettingGo();
	}

	@Test
	public void theSettingOffLetsItGo() throws ReflectiveOperationException
	{
		settingOn = false;
		lettingGo();
	}

	@Test
	public void aLogoutOrAHopLetsItGo() throws ReflectiveOperationException
	{
		somethingOdd();
		follower.doing = true;
		game.gameState(GameState.HOPPING);
		frame();
		assertFalse(watch.isWatching());
		assertEquals(1, follower.cleared);
	}

	@Test
	public void aCutsceneHoldsTheQueueAndWatchesNothing() throws ReflectiveOperationException
	{
		somethingOdd();
		follower.doing = true;
		set("cutsceneForced", true);
		frame();
		assertFalse("the scene's moves are not copied", watch.isWatching());
		assertEquals("and the queue is kept for after it", 0, follower.cleared);
	}

	@Test
	public void aRebuildIsSteppedOverNotTakenForALogout() throws ReflectiveOperationException
	{
		somethingOdd();
		follower.doing = true;
		game.gameState(GameState.LOADING);
		frame();
		assertTrue("still watching", watch.isWatching());
		assertEquals(0, follower.cleared);
	}

	@Test
	public void theFollowersOwnBusinessStandsAsideForIt() throws ReflectiveOperationException
	{
		for (String gate : new String[]{"seatFeetTaken", "momentFeetTaken", "spectateBusy"})
		{
			assertFalse(gate + " with nothing going on", (Boolean) call(gate));
		}
		follower.doing = true;
		for (String gate : new String[]{"seatFeetTaken", "momentFeetTaken", "spectateBusy"})
		{
			assertTrue(gate + " while it does agility", (Boolean) call(gate));
		}
	}

	// ---------------------------------------------------------------- helpers

	private void lettingGo() throws ReflectiveOperationException
	{
		follower.doing = true;
		game.animating(866);
		frame();
		assertFalse("nothing watched", watch.isWatching());
		assertEquals("and the queue let go", 1, follower.cleared);
	}

	/** An animation begins: something the watch looks at. */
	private void somethingOdd() throws ReflectiveOperationException
	{
		game.animating(866);
		frame();
	}

	private void frame() throws ReflectiveOperationException
	{
		call("watchForAgility");
	}

	private Object call(String name) throws ReflectiveOperationException
	{
		Method method = FollowerPlugin.class.getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(plugin);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}
