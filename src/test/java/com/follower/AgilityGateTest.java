package com.follower;

import com.follower.follower.AgilityTrace;
import com.follower.follower.Crossing;
import com.follower.follower.CrossingWatch;
import com.follower.follower.FollowerEntity;
import com.follower.sim.FakeGame;
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
	private boolean developer;

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

			@Override
			public boolean developerMode()
			{
				return developer;
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

	// ---------------------------------------------------------------- trace

	@Test
	public void theTraceIsWrittenOnlyWithDeveloperCommandsOn() throws ReflectiveOperationException, IOException
	{
		AgilityTrace trace = trace();
		somethingOdd();
		call("traceAgilityTick");
		trace.flush();
		assertFalse("nothing at all with it off", Files.exists(trace.getFile()));

		developer = true;
		call("traceAgilityTick");
		trace.flush();
		List<String> lines = Files.readAllLines(trace.getFile());
		assertTrue("opened with its columns: " + lines, lines.get(0).contains("agility trace on"));
		assertTrue("and a tick line: " + lines, lines.stream().anyMatch(line ->
			line.contains("| 50,50,0 pose 808 anim 866 gfx -1 still xp+0 hit - | watch ")
				&& line.contains("| - q0 follow")));

		developer = false;
		call("traceAgilityTick");
		call("traceAgilityTick");
		trace.flush();
		List<String> after = Files.readAllLines(trace.getFile());
		assertEquals("closed, and nothing after", lines.size() + 1, after.size());
		assertTrue(after.get(after.size() - 1).contains("agility trace off"));
	}

	@Test
	public void theTraceKeepsQuietWithNoAgilityAbout() throws ReflectiveOperationException, IOException
	{
		AgilityTrace trace = trace();
		developer = true;
		for (int i = 0; i < 5; i++)
		{
			frame();
			call("traceAgilityTick");
		}
		trace.flush();
		assertEquals("just the heading: " + Files.readAllLines(trace.getFile()),
			1, Files.readAllLines(trace.getFile()).size());
	}

	@Test
	public void theTraceTakesTheTicksXpAndHitsplat() throws ReflectiveOperationException, IOException
	{
		AgilityTrace trace = trace();
		developer = true;
		set("agilityXpThisTick", 8);
		set("agilityHitsplatThisTick", 16);
		call("traceAgilityTick");
		trace.flush();
		assertTrue(Files.readAllLines(trace.getFile()).stream()
			.anyMatch(line -> line.contains("xp+8 hit 16")));
	}

	// ---------------------------------------------------------------- helpers

	private AgilityTrace trace() throws ReflectiveOperationException, IOException
	{
		AgilityTrace trace = (AgilityTrace) get("agilityTrace");
		trace.initialise(folder.newFolder().toPath());
		return trace;
	}

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
