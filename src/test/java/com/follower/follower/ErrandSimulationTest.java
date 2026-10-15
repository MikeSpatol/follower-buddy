package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.appearance.SpotAnimRepository;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
 *
 * <p>It does mean the TELEPORT home is out of reach here. Both errands that run
 * to completion are the two that come back on foot, and every errand that walks
 * far enough to teleport is one that needs an object to walk to. That path is
 * checked in game rather than pretended at.
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

		int poseOverride;

		@Override
		public void setPoseOverride(int id)
		{
			poseOverride = id;
		}

		@Override
		public int getPoseOverride()
		{
			return poseOverride;
		}

		@Override
		public void clearPoseOverride(int id)
		{
			if (poseOverride == id)
			{
				poseOverride = 0;
			}
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

	/** The errands' rolls, fixed so a long run plays out the same every time. */
	private static final long SEED = 20261014L;

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
			dispatched::add, spotAnims, () -> busy, new ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
					heldProp = itemId;
					heldLeft = 0;
				}

				@Override
				public void hold(int leftItemId, int rightItemId)
				{
					heldProp = rightItemId;
					heldLeft = leftItemId;
				}

				@Override
				public void release()
				{
					heldProp = 0;
					heldLeft = 0;
				}

				@Override
				public boolean isHolding()
				{
					return heldProp != 0;
				}
			}, new Random(SEED));
	}

	/** What the hands are holding; 0 for nothing. */
	private int heldProp;

	/** The left hand's thing of a pair, the quill; 0 for nothing. */
	private int heldLeft;

	/** For the secondary controllers a few tests build: hands nobody watches. */
	private static ErrandController.Hands idleHands()
	{
		return new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
			}

			@Override
			public void release()
			{
			}
		};
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

	/**
	 * The altar and the fire need a scene object to walk to, which this
	 * harness has none of, so each is put in place of a bootlace already at
	 * its business, the visit timed from there.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void doing(String errand) throws ReflectiveOperationException
	{
		errands.force("bootlace");
		tick(1);
		assertTrue(errands.isBusy());
		follower.animations.clear();
		java.lang.reflect.Field current = ErrandController.class.getDeclaredField("current");
		current.setAccessible(true);
		current.set(errands, Enum.valueOf((Class) Class.forName(
			"com.follower.follower.ErrandController$Errand"), errand));
		java.lang.reflect.Method begin = ErrandController.class.getDeclaredMethod("beginDoing");
		begin.setAccessible(true);
		begin.invoke(errands);
	}

	@Test
	public void theAltarIsKneltAtForTheVisitAndStoodUpFromAtTheEnd() throws ReflectiveOperationException
	{
		// QA, 15 Oct: the one-shot kneel, 645, ended almost upright after 3.9
		// ticks, and the follower stood idle at the altar for the rest.
		doing("ALTAR");
		assertEquals("HUMAN_PRAY_LOOP, held: it kneels and stays down", 179, follower.poseOverride);
		tick(6);
		assertEquals("still at prayer", 179, follower.poseOverride);
		tick(1);
		assertEquals("up off the knees before the walk home", 0, follower.poseOverride);
		assertEquals("the rise from the kneel", java.util.Collections.singletonList(FollowerEntity.PRAY_STAND_UP),
			follower.animations);
		tick(1);
		assertFalse(errands.isBusy());
		assertTrue(follower.following);
	}

	@Test
	public void anAltarVisitCutShortStillLetsGoOfTheKneel() throws ReflectiveOperationException
	{
		doing("ALTAR");
		busy = true;
		tick(1);
		assertFalse(errands.isBusy());
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void theHandsAreWarmedAtTheFireForTheWholeVisit() throws ReflectiveOperationException
	{
		// QA, 15 Oct: one 3.1-tick kneel to the fire, then four seconds stood
		// about. Replayed every four ticks, as the fireside seat does it.
		doing("FIRE");
		List<Integer> expected = new ArrayList<>(java.util.Collections.singletonList(897));
		assertEquals(expected, follower.animations);
		tick(4);
		expected.add(897);
		assertEquals(expected, follower.animations);
		tick(4);
		expected.add(897);
		assertEquals(expected, follower.animations);
		tick(4);
		assertEquals("none started that the walk home would cut off", expected, follower.animations);
		assertFalse(errands.isBusy());
	}

	@Test
	public void aSilentlyForcedErrandSaysNothingAtEitherEnd()
	{
		errands.force("bootlace", true);
		tick(1);
		assertTrue("it ran", follower.animations.contains(827));
		tick(10);
		assertTrue("and gave the follower back", follower.following);
		assertTrue("without a word at either end", dispatched.isEmpty());

		// The next, asked for aloud, announces itself as ever.
		errands.force("bootlace");
		tick(11);
		assertTrue(started("bootlace"));
	}

	@Test
	public void aSessionKeepsEveryErrandInButTheOneItAskedFor()
	{
		errands.setSession(() -> working);
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		// The arrival look is the follower's own idea, and a session says no.
		working = true;
		errands.noticeArrival();
		tick(15);
		assertFalse("no errand of its own during a session", errands.isBusy());
		assertTrue(dispatched.isEmpty());

		// The one the session sent for goes ahead.
		errands.force("bootlace", true);
		tick(1);
		assertTrue("the forced errand is the session's own", errands.isBusy());
		tick(10);
		assertTrue(follower.following);

		// The session over, the same arrival earns its look.
		working = false;
		errands.noticeArrival();
		tick(15);
		assertTrue("the look, once the session is done", started("explore-chest"));
	}

	/** Whether a work session is on, for the session gate. */
	private boolean working;

	@Test
	public void theBankErrandFindsABoothTheGameCapitalises()
	{
		// Burgh de Rott's is a "Bank Booth", Fossil Island's a "Bank Chest".
		game.at(50, 50, 0);
		game.placeObject(9002, "Bank Booth", 54, 50, 0);
		errands.force("bank");
		tick(1);

		assertTrue("the errand never started", errands.isBusy());
		assertTrue("sent to the booth", follower.where != null
			&& follower.where.distanceTo(new WorldPoint(54, 50, 0)) <= 2);
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
	public void theDocumentErrandHoldsTheScrollForExactlyTheRead()
	{
		errands.force("document");
		tick(1);

		assertTrue("the errand never started", errands.isBusy());
		assertTrue("it should have stopped where it stood", follower.staying);
		assertEquals("the scroll is out", ErrandController.SCROLL_PROP, heldProp);
		assertEquals("and the quill with it", ErrandController.QUILL_PROP, heldLeft);
		assertEquals("and the pose plays from the same tick - the dump path is"
			+ " synchronous, so waiting only shows a scroll in idle hands",
			ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertTrue("and it announced itself", started("document"));

		tick(30);
		assertFalse("the errand should have finished", errands.isBusy());
		assertEquals("the pose released", 0, follower.poseOverride);
		assertEquals("and the scroll went away", 0, heldProp);
		assertEquals("and the quill", 0, heldLeft);
		assertTrue("and the follower came back", follower.following);
	}

	@Test
	public void anInterruptedDocumentStillPutsTheScrollAway()
	{
		// The failure shape every interesting bug here has had: state
		// outliving its owner. A follower stuck holding a scroll in its
		// walking pose would wear this bug forever.
		errands.force("document");
		tick(3);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);

		busy = true;      // the dialog opened mid-read
		tick(1);

		assertFalse("the errand should have aborted", errands.isBusy());
		assertEquals("the pose must not outlive it", 0, follower.poseOverride);
		assertEquals("nor the scroll", 0, heldProp);
		assertEquals("nor the quill", 0, heldLeft);
	}

	@Test
	public void aStudyLooksBeforeItWritesAndNamesWhatItStudied()
	{
		// The find half needs a real scene; everything after it is lifecycle,
		// injected through the same seam a real find would use.
		assertTrue(errands.beginStudyAt(new WorldPoint(3225, 3218, 0), "Well"));
		assertTrue(errands.isBusy());
		assertTrue("it announced the specific thing", started("study-well"));

		tick(2);      // arrive (the recording follower is always settled)
		assertEquals("looking first: no scroll during the look", 0, heldProp);
		assertEquals("and no pose either", 0, follower.poseOverride);

		tick(5);      // the look ends
		assertEquals("the scroll comes out", ErrandController.SCROLL_PROP, heldProp);
		assertEquals("and the quill", ErrandController.QUILL_PROP, heldLeft);
		assertEquals("with the pose on the same tick", ErrandController.WRITE_SCROLL_POSE,
			follower.poseOverride);

		tick(30);
		assertFalse("the study should have finished", errands.isBusy());
		assertEquals(0, follower.poseOverride);
		assertEquals(0, heldProp);

		boolean endNamed = false;
		for (TriggerEvent event : dispatched)
		{
			endNamed |= event.getType() == TriggerEvent.Type.ERRAND_END
				&& "study-well".equals(event.getName());
		}
		assertTrue("the verdict names the thing too", endNamed);
	}

	@Test
	public void anExploreLooksThenNotesItDownForPosterity()
	{
		// The explore is the study's nosy sibling: same trip, same
		// look-then-write beat - everything explored goes in the scroll.
		assertTrue(errands.beginExploreAt(new WorldPoint(3225, 3218, 0), "Chest"));
		assertTrue(errands.isBusy());
		assertTrue("it announced the specific thing", started("explore-chest"));

		tick(2);      // arrive (the recording follower is always settled)
		assertEquals("looking first: no scroll during the look", 0, heldProp);
		assertEquals("and no pose either", 0, follower.poseOverride);

		tick(5);      // the look ends
		assertEquals("the entry gets written", ErrandController.SCROLL_PROP, heldProp);
		assertEquals("with the quill", ErrandController.QUILL_PROP, heldLeft);
		assertEquals("with the pose on the same tick", ErrandController.WRITE_SCROLL_POSE,
			follower.poseOverride);

		tick(25);
		assertFalse("the look should have finished", errands.isBusy());
		assertEquals("the scroll went away", 0, heldProp);
		assertEquals("and the pose released", 0, follower.poseOverride);
		assertTrue("and the follower came back", follower.following);

		boolean endNamed = false;
		for (TriggerEvent event : dispatched)
		{
			endNamed |= event.getType() == TriggerEvent.Type.ERRAND_END
				&& "explore-chest".equals(event.getName());
		}
		assertTrue("the verdict names the thing too", endNamed);
	}

	@Test
	public void anInterruptedExploreReleasesEverything()
	{
		errands.beginExploreAt(new WorldPoint(3225, 3218, 0), "Chest");
		tick(9);      // deep enough that the scroll is out and the pose holds
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);

		busy = true;
		tick(1);

		assertFalse(errands.isBusy());
		assertEquals("the pose must not outlive the look", 0, follower.poseOverride);
		assertEquals("nor the scroll", 0, heldProp);
	}

	@Test
	public void arrivingSomewhereNewEarnsALookOnceSettled()
	{
		// The R19 half: a region change arms a watch, and the explore begins
		// only after the player holds still - never while they are mid-run,
		// where the distance abort would yank it back three ticks later.
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		errands.noticeArrival();
		tick(1);

		// Stop-start travel: never eight QUIET ticks in a row, though far
		// more than eight in total. The watch must demand consecutive
		// stillness - a counter that merely accumulated across the pauses
		// in the walking would start the errand mid-journey. (The first
		// version of this test moved too briefly to tell the difference;
		// the mutation sweep caught it.)
		for (int leg = 0; leg < 4; leg++)
		{
			game.at(51 + leg, 50, 0);
			tick(4);
		}
		assertFalse("no exploring while the player is stop-starting",
			errands.isBusy());

		// Now they genuinely stop, and eight quiet ticks later the
		// nosiness begins.
		tick(8);
		assertTrue("the settled arrival earns a look", started("explore-chest"));
		assertTrue(errands.isBusy());

		tick(40);
		assertFalse(errands.isBusy());
		assertTrue(follower.following);
	}

	@Test
	public void anArrivalLostToAFightDoesNotBurnTheCooldown()
	{
		// Settling mid-combat forfeits THIS look, not the next one: the
		// cooldown is charged only when an explore actually starts, so the
		// next region change still gets its curiosity.
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		game.fighting(game.spawnNpc(3029, "Goblin", 5));
		errands.noticeArrival();
		tick(12);
		assertFalse("no nosing about mid-fight", errands.isBusy());

		game.fighting(null);
		errands.noticeArrival();
		tick(12);
		assertTrue("the next arrival still gets its look", started("explore-chest"));
	}

	/**
	 * Play test, 9 Oct: tailing the guard in Children of the Sun, a quest
	 * instance where the player stands still at each hiding spot, the
	 * follower went off to a chest. An instance's region id is new on every
	 * visit, so it is not somewhere new.
	 */
	@Test
	public void anInstanceIsNotSomewhereNew()
	{
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		game.instanced(true);
		errands.noticeArrival();
		tick(12);
		assertFalse("no look round a quest's instance", started("explore-chest"));

		game.instanced(false);
		errands.noticeArrival();
		tick(12);
		assertTrue("the open world still earns one", started("explore-chest"));
	}

	@Test
	public void aLookPendingFromTheRegionLeftGoesWithIt()
	{
		// Entering the instance only restarted the count of a look armed
		// in the region before it, and the look fired inside.
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		errands.noticeArrival();
		tick(3);
		game.instanced(true);
		errands.noticeArrival();
		tick(12);
		assertFalse(started("explore-chest"));
	}

	@Test
	public void anArrivalWaitsOutAToolKeptBetweenTrees()
	{
		// A tool kept in the hand between two trees holds the errands up,
		// since every errand lets go of the hand when it ends. It goes back
		// within the minute, so a look at somewhere new waits for it rather
		// than being lost to it, as a look is lost to the work itself.
		errands.setSession(() -> working);
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		working = true;
		busy = true;
		heldProp = 1351;
		errands.noticeArrival();
		tick(12);
		working = false;
		busy = false;
		heldProp = 0;
		tick(12);
		assertFalse("the session's own tool is no pause", started("explore-chest"));

		busy = true;
		heldProp = 1351;
		errands.noticeArrival();
		tick(30);
		assertFalse("not over the kept axe", errands.isBusy());

		busy = false;
		heldProp = 0;
		tick(7);
		assertFalse("a moment for the bow that gave it back", errands.isBusy());
		tick(1);
		assertTrue("then the look it waited for", started("explore-chest"));
	}

	@Test
	public void arrivalLooksAreRationedByTheCooldown()
	{
		game.at(50, 50, 0);
		follower.where = new WorldPoint(50, 51, 0);
		game.placeObject(9001, "Chest", 53, 50, 0);

		errands.noticeArrival();
		tick(10);
		assertTrue(started("explore-chest"));
		tick(40);
		assertFalse(errands.isBusy());

		// The next region, minutes too soon: watched, settled, and refused.
		errands.noticeArrival();
		tick(15);

		int starts = 0;
		for (TriggerEvent event : dispatched)
		{
			if (event.getType() == TriggerEvent.Type.ERRAND_START)
			{
				starts++;
			}
		}
		assertEquals("a trip through several regions is one inspection, not several",
			1, starts);
	}

	@Test
	public void anInterruptedStudyReleasesEverything()
	{
		errands.beginStudyAt(new WorldPoint(3225, 3218, 0), "Anvil");
		tick(9);      // deep enough that the scroll is out and the pose holds

		busy = true;
		tick(1);

		assertFalse(errands.isBusy());
		assertEquals("the pose must not outlive the study", 0, follower.poseOverride);
		assertEquals("nor the scroll", 0, heldProp);
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
			dispatched::add, new SpotAnimRepository(new Gson()), () -> busy, idleHands());

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

		// The last tick can fall mid-errand, when the follower is posed on
		// purpose. That errand has to finish and hand the follower back.
		for (int i = 0; i < 60 && errands.isBusy(); i++)
		{
			tick(1);
		}
		assertFalse("the last errand never finished", errands.isBusy());
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

			@Override
			public boolean errandDocument()
			{
				return false;
			}
		};
		ErrandController silent = new ErrandController(game.client, follower, none,
			dispatched::add, new SpotAnimRepository(new Gson()), () -> busy, idleHands());

		for (int i = 0; i < 3000; i++)
		{
			game.tick(++tick);
			silent.tick();
		}

		assertFalse(silent.isBusy());
		assertTrue("with every errand switched off, none may run", dispatched.isEmpty());
	}

	// ------------------------------------------------------------- rotation

	@Test
	public void theSameErrandDoesNotComeRoundAgainAndAgain()
	{
		// Fifteen days of real play, measured: three errands were 84% of every
		// one that ran. Not because the roll preferred them - it shuffles -
		// but because they are the three that need nothing in the world. A
		// bank errand needs a booth in sight; the bootlace needs only a boot,
		// so it won every roll the others could not. It ran 189 times on five
		// lines, and the player heard each of them thirty-seven times.
		//
		// An empty scene is exactly that situation: nothing to walk to, so
		// only the self-contained errands can start. Whatever runs here must
		// still take turns.
		List<String> order = new ArrayList<>();
		for (int i = 0; i < 20_000; i++)
		{
			game.tick(++tick);
			errands.tick();
			for (TriggerEvent event : dispatched)
			{
				if (event.getType() == TriggerEvent.Type.ERRAND_START)
				{
					order.add(event.getName());
				}
			}
			dispatched.clear();
		}

		assertTrue("nothing ran at all, so this proves nothing", order.size() >= 4);

		int repeats = 0;
		for (int i = 1; i < order.size(); i++)
		{
			if (order.get(i).equals(order.get(i - 1)))
			{
				repeats++;
			}
		}
		assertEquals("an errand ran twice in a row: " + order, 0, repeats);

		long distinct = order.stream().distinct().count();
		assertTrue("only one kind of errand ever ran: " + order, distinct >= 2);
	}
}
