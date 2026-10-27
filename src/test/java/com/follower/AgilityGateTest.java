package com.follower;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
 * The agility trace is plugin glue too, and is here: written only with
 * Developer commands on, and only with agility about.
 */
public class AgilityGateTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int[] NORMAL = {808, 819, 824, 820, 821, 822, 823, 823};

	/** Counts what the plugin asks of the follower's agility. */
	private static final class Agile extends FollowerEntity
	{
		boolean doing;
		boolean holding;
		int held;
		int cleared;
		int strandedCleared;

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
		public boolean isHoldingForAgility()
		{
			return holding;
		}

		@Override
		public int getStrandedTicks()
		{
			return 10;
		}

		@Override
		public void clearStranded()
		{
			strandedCleared++;
		}

		@Override
		public void clearCrossings()
		{
			cleared++;
		}

		@Override
		public void holdForAgility()
		{
			held++;
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
		IdleControllers.fill(plugin);
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
		set("sceneOn", true);
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
		for (String gate : new String[]{
			"seatFeetTaken",
			"momentFeetTaken",
			"spectateBusy",
		})
		{
			assertFalse(gate + " with nothing going on", (Boolean) call(gate));
		}
		follower.doing = true;
		for (String gate : new String[]{
			"seatFeetTaken",
			"momentFeetTaken",
			"spectateBusy",
		})
		{
			assertTrue(gate + " while it does agility", (Boolean) call(gate));
		}
	}

	@Test
	public void agilityIsBehindNotStrandedSoNoTeleport() throws ReflectiveOperationException
	{
		follower.doing = true;
		call("updateStrandedTeleport");
		assertEquals("no teleport set off", 0, get("strandedLandingTicks"));
		assertEquals("and the count let go", 1, follower.strandedCleared);

		follower.doing = false;
		follower.holding = true;
		call("updateStrandedTeleport");
		assertEquals("nor while it waits on the player's agility", 0, get("strandedLandingTicks"));
		assertEquals(2, follower.strandedCleared);
	}

	@Test
	public void theWaitIsLetGoWithTheQueue() throws ReflectiveOperationException
	{
		follower.holding = true;
		set("parkedByPlayer", true);
		frame();
		assertEquals("a Stay ends the wait as it ends the queue", 1, follower.cleared);
	}

	/**
	 * Ape Atoll is run as a greegree monkey, which the watch cannot read, so
	 * nothing there is copied. Its XP then holds nothing either: the follower
	 * joins the player up each tree at once, as before (QA4 #7).
	 */
	@Test
	public void xpTheWatchCannotReadHoldsNothing() throws ReflectiveOperationException, IOException
	{
		set("speechEngine", new Harness(folder.newFolder().toPath()).engine);
		game.transformedInto(1480);
		agilityXp(1000);
		agilityXp(1010);
		assertEquals("a monkey's XP", 0, follower.held);

		game.transformedInto(-1);
		agilityXp(1020);
		assertEquals("their own", 1, follower.held);
	}

	/**
	 * On a course the run back to the first obstacle is not agility, and an
	 * errand begun there turned the watch off and missed the first climb.
	 * Agility XP in the last minute keeps errands off (QA4 #24).
	 */
	@Test
	public void noErrandWithinAMinuteOfAgilityXp() throws ReflectiveOperationException, IOException
	{
		set("dialog", new FollowerDialog(null, null, null, null, null, null, null, null));
		set("speechEngine", new Harness(folder.newFolder().toPath()).engine);
		assertFalse("nothing going on", (Boolean) call("errandBusy"));
		agilityXp(1000);
		agilityXp(1010);
		assertTrue("between laps", (Boolean) call("errandBusy"));
		set("lastAgilityXpMs", System.currentTimeMillis() - 61_000);
		assertFalse("a minute on", (Boolean) call("errandBusy"));
	}

	private void agilityXp(int xp)
	{
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(net.runelite.api.Skill.AGILITY, xp, 50, 50));
	}

	// ---------------------------------------------------------------- helpers

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(plugin);
	}

	private void lettingGo() throws ReflectiveOperationException
	{
		follower.doing = true;
		game.animating(866);
		frame();
		assertFalse("nothing watched", watch.isWatching());
		assertEquals("and the queue let go", 1, follower.cleared);
	}

	/** A jump: the clip, and the player put over a few units at a time on the stand. */
	private void somethingThatMoves() throws ReflectiveOperationException
	{
		game.animating(741);
		frame();
		for (int i = 1; i <= 3; i++)
		{
			game.fineAt(50 * 128 + 64 + 8 * i, 50 * 128 + 64, 0);
			frame();
		}
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
