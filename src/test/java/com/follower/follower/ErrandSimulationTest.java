package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.appearance.SpotAnimRepository;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Errands, which are the only thing that sends the follower away on its own.
 *
 * <p>Everything here is about the refusals. An errand is a small piece of
 * character that must never cost the player anything, so it may not begin
 * while they are fighting, in the wilderness, or already using the follower for
 * something else - and once begun it has to let go the moment any of that
 * changes, leaving the follower back at heel rather than standing in a bank
 * three rooms away.
 *
 * <p>Only bootlace and glance are driven end to end. The rest look for a scene
 * object, and a scene is the one thing this harness has none of - which is
 * fine, because "found nothing, so did not start" is itself a refusal worth
 * checking.
 */
public class ErrandSimulationTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Records what the follower was asked to do; does none of it. */
	private static final class RecordingFollower extends FollowerEntity
	{
		boolean spawned = true;
		boolean settled = true;
		boolean staying;
		boolean following = true;
		boolean teleportedHome;
		WorldPoint where = new WorldPoint(3222, 3218, 0);
		final List<Integer> animations = new ArrayList<>();

		RecordingFollower()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return spawned;
		}

		@Override
		public boolean isSettled()
		{
			return settled;
		}

		@Override
		public boolean isStaying()
		{
			return staying;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return where;
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			staying = true;
			following = false;
			where = target;
			return target != null;
		}

		@Override
		public void stayHere()
		{
			staying = true;
			following = false;
		}

		@Override
		public void resumeFollowing()
		{
			staying = false;
			following = true;
		}

		@Override
		public void teleportToPlayer()
		{
			teleportedHome = true;
			where = new WorldPoint(3222, 3218, 0);
		}

		@Override
		public void playAnimation(int id)
		{
			animations.add(id);
		}

		@Override
		public void setStayFaceTile(WorldPoint tile)
		{
		}

		@Override
		public void hideAfterEmote(int ms)
		{
		}
	}

	private static final int WILDERNESS_VARBIT = 5963;

	private FakeGame game;
	private RecordingFollower follower;
	private ErrandController errands;
	private List<TriggerEvent> dispatched;
	private boolean busy;
	private int tick;

	@Before
	public void setUp() throws IOException
	{
		game = new FakeGame();
		follower = new RecordingFollower();
		dispatched = new ArrayList<>();
		busy = false;

		SpotAnimRepository spotAnims = new SpotAnimRepository(new Gson());
		spotAnims.load(folder.newFolder().toPath());

		errands = new ErrandController(game.client, follower, config(),
			dispatched::add, spotAnims, () -> busy);
	}

	private FollowerConfig config()
	{
		return new FollowerConfig()
		{
		};
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			errands.tick();
		}
	}

	private boolean started(String key)
	{
		for (TriggerEvent event : dispatched)
		{
			if (event.getType() == TriggerEvent.Type.ERRAND_START
				&& key.equals(event.getName()))
			{
				return true;
			}
		}
		return false;
	}

	// --------------------------------------------------------------- happy path

	@Test
	public void aBootlaceErrandRunsAndGivesTheFollowerBack()
	{
		errands.force("bootlace");
		tick(1);

		assertTrue("the errand never started", errands.isBusy());
		assertTrue("it should have stopped where it stood", follower.staying);
		assertFalse("and played something", follower.animations.isEmpty());
		assertTrue("and announced itself", started("bootlace"));

		tick(30);

		assertFalse("the errand should have finished", errands.isBusy());
		assertTrue("and handed the follower back", follower.following);
	}

	@Test
	public void aGlanceSendsItAShortWayAndBringsItBack()
	{
		errands.force("glance");
		tick(1);

		assertTrue(errands.isBusy());
		assertTrue("it should have been sent somewhere", follower.where != null);
		assertTrue("but not far", follower.where
			.distanceTo(new WorldPoint(3222, 3218, 0)) <= 4);

		tick(60);
		assertFalse(errands.isBusy());
		assertTrue(follower.following);
	}

	@Test
	public void anErrandAnnouncesBothItsEndsExactlyOnce()
	{
		errands.force("bootlace");
		tick(40);

		int starts = 0;
		int ends = 0;
		for (TriggerEvent event : dispatched)
		{
			if (event.getType() == TriggerEvent.Type.ERRAND_START)
			{
				starts++;
			}
			if (event.getType() == TriggerEvent.Type.ERRAND_END)
			{
				ends++;
			}
		}
		assertEquals("one start", 1, starts);
		assertEquals("one end", 1, ends);
	}

	// ---------------------------------------------------------- the refusals

	@Test
	public void nothingStartsWhileThePlayerIsFighting()
	{
		game.fighting(game.spawnNpc(3029, "Goblin", 5));
		errands.force("bootlace");
		tick(10);

		assertFalse("an errand must not begin mid-fight", errands.isBusy());
	}

	@Test
	public void nothingStartsInTheWilderness()
	{
		game.varbit(WILDERNESS_VARBIT, 1);
		errands.force("bootlace");
		tick(10);

		assertFalse("wandering off in the wilderness is how a follower gets lost",
			errands.isBusy());
	}

	@Test
	public void nothingStartsWhileSomethingElseOwnsTheFollower()
	{
		busy = true;
		errands.force("bootlace");
		tick(10);

		assertFalse("thrall mode and spectating outrank an errand", errands.isBusy());
	}

	@Test
	public void nothingStartsWhileTheFollowerIsAlreadyPosed()
	{
		follower.staying = true;
		errands.force("bootlace");
		tick(10);

		assertFalse(errands.isBusy());
	}

	@Test
	public void nothingStartsWithNoFollowerInTheWorld()
	{
		follower.spawned = false;
		errands.force("bootlace");
		tick(10);

		assertFalse(errands.isBusy());
	}

	@Test
	public void anErrandThatCanFindNowhereToGoSimplyDoesNotStart()
	{
		// No scene here, so every errand that needs an object finds none.
		for (String key : new String[]{"bank", "altar", "fire", "cat"})
		{
			setUpQuietly();
			errands.force(key);
			tick(5);
			assertFalse(key + " started with nothing to go to", errands.isBusy());
		}
	}

	private void setUpQuietly()
	{
		try
		{
			setUp();
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	// -------------------------------------------------------- letting go again

	@Test
	public void theFollowerIsAlwaysReleasedHoweverAnErrandEnds()
	{
		String[] interruptions = {"fight", "wilderness", "busy", "despawn", "walked away"};
		for (String interruption : interruptions)
		{
			setUpQuietly();
			errands.force("glance");
			tick(1);
			assertTrue(interruption + ": the errand never started", errands.isBusy());

			switch (interruption)
			{
				case "fight":
					game.fighting(game.spawnNpc(3029, "Goblin", 5));
					break;
				case "wilderness":
					game.varbit(WILDERNESS_VARBIT, 1);
					break;
				case "busy":
					busy = true;
					break;
				case "despawn":
					follower.spawned = false;
					break;
				default:
					// The player teleports away and leaves it behind.
					game.at(3300, 3300, 0);
					break;
			}
			tick(3);

			assertFalse(interruption + ": the errand carried on regardless",
				errands.isBusy());
			assertTrue(interruption + ": the follower was left posed, not following",
				follower.following);
		}
	}

	@Test
	public void aFollowerLeftBehindIsBroughtHomeRatherThanStranded()
	{
		errands.force("glance");
		tick(1);

		// It ended up a long way off and the player has gone.
		follower.where = new WorldPoint(3300, 3300, 0);
		game.at(3400, 3400, 0);
		tick(3);

		assertTrue("a follower stranded out of sight has to be brought back",
			follower.teleportedHome);
		assertTrue(follower.following);
	}

	@Test
	public void switchingErrandsOffMidErrandEndsItAtOnce()
	{
		FollowerConfig off = new FollowerConfig()
		{
			@Override
			public boolean errandsEnabled()
			{
				return false;
			}
		};
		ErrandController disabled = new ErrandController(game.client, follower, off,
			dispatched::add, new SpotAnimRepository(new Gson()), () -> busy);

		disabled.force("bootlace");
		for (int i = 0; i < 10; i++)
		{
			game.tick(++tick);
			disabled.tick();
		}

		assertFalse("the setting is off", disabled.isBusy());
		assertTrue(follower.following);
	}

	// ---------------------------------------------------------------- resets

	@Test
	public void aResetLeavesNothingOwedToTheNextSession()
	{
		errands.force("bootlace");
		errands.reset();
		tick(20);

		assertFalse("a forced errand from before a logout must not fire after it",
			errands.isBusy());
		assertTrue(started("bootlace") == false);
	}

	@Test
	public void aResetMidErrandStopsIt()
	{
		errands.force("bootlace");
		tick(1);
		assertTrue(errands.isBusy());

		errands.reset();
		assertFalse(errands.isBusy());
	}

	// ----------------------------------------------------------- long running

	/**
	 * Left alone, errands should happen on their own - that is the whole point
	 * - but at the pace the setting describes rather than back to back.
	 *
	 * <p>Bootlace and glance need nothing from the scene, so these are the two
	 * that can run here. At the default frequency the interval is 1800 ticks
	 * either side of 40%, which is around eighteen minutes of real time.
	 */
	@Test
	public void leftAloneErrandsHappenButAreSpacedOut()
	{
		tick(20000);

		int starts = 0;
		for (TriggerEvent event : dispatched)
		{
			if (event.getType() == TriggerEvent.Type.ERRAND_START)
			{
				starts++;
			}
		}

		assertTrue("over 20,000 ticks the follower never once did anything"
			+ " off its own back", starts > 0);
		assertTrue("errands fired " + starts + " times in 20,000 ticks, which is"
			+ " far more often than the interval allows", starts <= 20);
		assertTrue("it should be back at heel at the end", follower.following);
	}

	@Test
	public void everyToggleOffMeansNoErrandsAtAll()
	{
		FollowerConfig none = new FollowerConfig()
		{
			@Override
			public boolean errandBank()
			{
				return false;
			}

			@Override
			public boolean errandAltar()
			{
				return false;
			}

			@Override
			public boolean errandFire()
			{
				return false;
			}

			@Override
			public boolean errandCat()
			{
				return false;
			}

			@Override
			public boolean errandBootlace()
			{
				return false;
			}

			@Override
			public boolean errandGlance()
			{
				return false;
			}
		};
		ErrandController silent = new ErrandController(game.client, follower, none,
			dispatched::add, new SpotAnimRepository(new Gson()), () -> busy);

		for (int i = 0; i < 3000; i++)
		{
			game.tick(++tick);
			silent.tick();
		}

		assertFalse(silent.isBusy());
		assertTrue("with every errand switched off, none may run", dispatched.isEmpty());
	}
}
