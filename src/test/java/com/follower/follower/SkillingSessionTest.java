package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The skilling company, driven tick by tick with nothing running: a click
 * that becomes a session once the work is proven, a seat off the flank six
 * ticks in, the tool borrowed, and every way the session lets go again.
 *
 * <p>Most of what matters here is refusal and release. A seat on the line to
 * the tree, on the tree, or on the next tree is worse than no seat; a seat
 * held after the player has walked off is a stuck follower; a tool held after
 * a fight starts is a follower swinging an axe at a goblin.
 */
public class SkillingSessionTest
{
	/** Records what the follower was asked to do; does none of it. */
	private static final class RecordingFollower extends FollowerEntity
	{
		boolean spawned = true;
		WorldPoint stayingAt;
		WorldPoint facing;
		boolean following = true;
		int stayAttempts;
		int poseOverride;
		final List<Integer> animations = new ArrayList<>();

		/** Every tile it was sent to, in order, and every glance. */
		final List<WorldPoint> stays = new ArrayList<>();
		final List<WorldPoint> glances = new ArrayList<>();

		/** Tiles the ground refuses; null means everywhere is standable. */
		java.util.function.Predicate<WorldPoint> walled;

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
			return true;
		}

		@Override
		public boolean isStaying()
		{
			return stayingAt != null;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return stayingAt != null ? stayingAt : new WorldPoint(49, 49, 0);
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			stayAttempts++;
			if (target == null || (walled != null && walled.test(target)))
			{
				return false;
			}
			stayingAt = target;
			following = false;
			stays.add(target);
			return true;
		}

		@Override
		public void stayHere()
		{
			stayingAt = getWorldLocation();
			following = false;
		}

		@Override
		public void resumeFollowing()
		{
			stayingAt = null;
			facing = null;
			following = true;
		}

		@Override
		public void setStayFaceTile(WorldPoint tile)
		{
			facing = tile;
		}

		@Override
		public void playAnimation(int id)
		{
			animations.add(id);
		}

		@Override
		public void playAnimations(int[] ids)
		{
			for (int id : ids)
			{
				animations.add(id);
			}
		}

		@Override
		public void setPoseOverride(int id)
		{
			poseOverride = id;
		}

		@Override
		public void glanceAt(WorldPoint at, int ms)
		{
			glances.add(at);
		}
	}

	private static final WorldPoint PLAYER = new WorldPoint(50, 50, 0);

	/** A two-by-two yew east of the player. */
	private static final WorldPoint TREE_MIN = new WorldPoint(53, 50, 0);
	private static final WorldPoint TREE_MAX = new WorldPoint(54, 51, 0);
	private static final int TREE_ID = 9001;

	/** HUMAN_WOODCUTTING_BRONZE_AXE. */
	private static final int CHOP = 879;

	private FakeGame game;
	private RecordingFollower follower;
	private SkillingController skilling;
	private List<TriggerEvent> dispatched;
	private boolean inCombat;
	private boolean feetTaken;
	private boolean bankOpen;
	private boolean keepsCompany = true;
	private boolean borrowsTools = true;
	private int held;
	private int tick;

	@Before
	public void setUp()
	{
		game = new FakeGame().at(PLAYER.getX(), PLAYER.getY(), 0);
		follower = new RecordingFollower();
		dispatched = new ArrayList<>();
		// The loop tests call this again mid-test; everything the world
		// remembers goes back with it.
		inCombat = false;
		feetTaken = false;
		bankOpen = false;
		keepsCompany = true;
		borrowsTools = true;
		held = 0;
		tick = 0;
		skilling = new SkillingController(game.client, follower, new FollowerConfig()
		{
			@Override
			public boolean keepsCompany()
			{
				return keepsCompany;
			}

			@Override
			public boolean borrowsTools()
			{
				return borrowsTools;
			}
		}, new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				held = itemId;
			}

			@Override
			public void release()
			{
				held = 0;
			}

			@Override
			public boolean isHolding()
			{
				return held != 0;
			}
		}, dispatched::add, () -> inCombat, () -> feetTaken, () -> bankOpen);
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			skilling.tick();
		}
	}

	private void clickTheTree()
	{
		skilling.noteClick("Chop down", "Yew tree", TREE_ID, TREE_MIN, TREE_MAX, null);
	}

	/** Click, swing, and sit through the seat delay. */
	private void workTheTree()
	{
		clickTheTree();
		game.animating(CHOP);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);
	}

	private int count(TriggerEvent.Type type)
	{
		int n = 0;
		for (TriggerEvent event : dispatched)
		{
			if (event.getType() == type)
			{
				n++;
			}
		}
		return n;
	}

	private static boolean onTheLine(WorldPoint from, WorldPoint to, WorldPoint c)
	{
		int ax = to.getX() - from.getX();
		int ay = to.getY() - from.getY();
		int bx = c.getX() - from.getX();
		int by = c.getY() - from.getY();
		return ax * by - ay * bx == 0 && bx * ax + by * ay >= 0
			&& bx * bx + by * by <= ax * ax + ay * ay;
	}

	private static boolean inside(WorldPoint min, WorldPoint max, WorldPoint c)
	{
		return c.getX() >= min.getX() && c.getX() <= max.getX()
			&& c.getY() >= min.getY() && c.getY() <= max.getY();
	}

	// ------------------------------------------------------------- the session

	@Test
	public void aClickAloneMovesNothing()
	{
		clickTheTree();
		tick(10);

		assertFalse("a click is intent, not work", skilling.isWorking());
		assertTrue("and the follower stays at heel", follower.following);
	}

	@Test
	public void workBeginsOnTheSwingAndTheSeatFollowsSixTicksLater()
	{
		clickTheTree();
		game.animating(CHOP);
		tick(1);
		assertTrue("the swing proves the click", skilling.isWorking());
		assertEquals("woodcutting", skilling.getSession().getActivity());
		assertEquals("Yew tree", skilling.getSession().getTargetName());

		tick(SkillingController.SEAT_AFTER_TICKS - 1);
		assertFalse("a single click must not move the follower", follower.isStaying());

		tick(1);
		assertNotNull("the seat is taken once the player has been at it", follower.stayingAt);
		assertEquals("faced at the middle of the tree", new WorldPoint(53, 50, 0), follower.facing);
		assertTrue("the seat is the company's, not a Stay", skilling.holdsTheFeet());
	}

	@Test
	public void anXpDropIsProofTooAndNamesTheWork()
	{
		skilling.noteClick("Use", "Range", 9010, new WorldPoint(52, 50, 0), null, null);
		assertEquals("Use says nothing about the skill", "",
			SkillingController.activityFor("Use"));

		assertEquals(1, skilling.noteXpDrop(Skill.COOKING));
		assertTrue(skilling.isWorking());
		assertEquals("the drop has the last word", "cooking", skilling.getSession().getActivity());
	}

	@Test
	public void xpOutsideASessionCountsNothing()
	{
		assertEquals(0, skilling.noteXpDrop(Skill.WOODCUTTING));
		assertFalse(skilling.isWorking());
	}

	@Test
	public void aStaleClickIsForgotten()
	{
		clickTheTree();
		tick(SkillingController.PENDING_TICKS + 1);
		game.animating(CHOP);
		tick(1);

		assertFalse("a swing a minute after the click is something else", skilling.isWorking());
	}

	@Test
	public void theDropsAreCountedAndHandedToTheRules()
	{
		workTheTree();
		assertEquals(1, skilling.noteXpDrop(Skill.WOODCUTTING));
		tick(3);
		assertEquals(2, skilling.noteXpDrop(Skill.WOODCUTTING));

		TriggerContext context = new TriggerContext(game.client);
		skilling.describeTo(context);
		assertTrue(context.isWorking());
		assertEquals("woodcutting", context.getWorkActivity());
		assertEquals("Yew tree", context.getWorkTargetName());
		assertEquals(TREE_MIN, context.getWorkTargetTile());
		assertEquals(2, context.getXpDropsThisSession());
		assertEquals(0, context.getTicksSinceXpDrop());
		assertTrue(context.getWorkTicks() >= SkillingController.SEAT_AFTER_TICKS);

		tick(4);
		skilling.describeTo(context);
		assertEquals(4, context.getTicksSinceXpDrop());
	}

	@Test
	public void nothingStartsMidFight()
	{
		inCombat = true;
		clickTheTree();
		game.animating(CHOP);
		tick(5);

		assertFalse("a swing during a fight is a swing at something", skilling.isWorking());
	}

	@Test
	public void keepsCompanyOffMeansNoSessionAtAll()
	{
		keepsCompany = false;
		clickTheTree();
		game.animating(CHOP);
		tick(20);

		assertFalse(skilling.isWorking());
		assertTrue(follower.following);
		assertEquals(0, held);
		assertTrue("and the rules hear nothing of it", dispatched.isEmpty());
	}

	// ---------------------------------------------------------------- the seat

	@Test
	public void theSeatKeepsOffTheLineTheFootprintAndTheNeighbours()
	{
		// Every player position around a two-by-two, with a same-name
		// neighbour on each side: no candidate may be on the tree, on the
		// neighbour, on the line to the tree, or at the player's elbow.
		WorldPoint min = new WorldPoint(60, 60, 0);
		WorldPoint max = new WorldPoint(61, 61, 0);
		List<WorldPoint> reserved = Arrays.asList(
			new WorldPoint(63, 60, 0), new WorldPoint(58, 61, 0), new WorldPoint(60, 63, 0));

		for (int x = 56; x <= 65; x++)
		{
			for (int y = 56; y <= 65; y++)
			{
				WorldPoint player = new WorldPoint(x, y, 0);
				if (inside(min, max, player))
				{
					continue;
				}
				int nx = Math.max(min.getX(), Math.min(max.getX(), x));
				int ny = Math.max(min.getY(), Math.min(max.getY(), y));
				WorldPoint nearest = new WorldPoint(nx, ny, 0);

				List<WorldPoint> seats = SkillingController.seatCandidates(player, min, max, reserved);
				assertFalse("no seat at all from " + player, seats.isEmpty());
				for (WorldPoint seat : seats)
				{
					assertFalse(seat + " is on the tree, from " + player, inside(min, max, seat));
					assertFalse(seat + " is the next tree, from " + player, reserved.contains(seat));
					assertFalse(seat + " is on the line from " + player, onTheLine(player, nearest, seat));
					assertTrue(seat + " is underfoot at " + player, seat.distanceTo(player) >= 2);
					assertTrue(seat + " is too far from " + player, seat.distanceTo(player) <= 3);
				}
			}
		}
	}

	@Test
	public void theFirstSeatIsTwoTilesOffTheFlank()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("two tiles off, not one", 2, seat.distanceTo(PLAYER));
		assertEquals("square off the flank: same column as the player", PLAYER.getX(), seat.getX());
		assertFalse(onTheLine(PLAYER, TREE_MIN, seat));
		assertFalse(inside(TREE_MIN, TREE_MAX, seat));
	}

	@Test
	public void aBlockedTileIsSkippedForTheNextCandidate()
	{
		// The whole flank is wall; the seat lands on a candidate that is not,
		// and still obeys the rules. stayAt is the pathing's own refusal.
		follower.walled = tile -> tile.getX() == PLAYER.getX();
		workTheTree();

		WorldPoint seat = follower.stayingAt;
		assertNotNull("a walled flank is not a reason to give up", seat);
		assertTrue(seat.getX() != PLAYER.getX());
		assertFalse(onTheLine(PLAYER, TREE_MIN, seat));
		assertFalse(inside(TREE_MIN, TREE_MAX, seat));
	}

	@Test
	public void nowhereToSitDegradesToFollowing()
	{
		follower.walled = tile -> true;
		workTheTree();

		assertTrue("still working", skilling.isWorking());
		assertTrue("and simply following", follower.following);
		assertFalse(skilling.holdsTheFeet());
		int attempts = follower.stayAttempts;
		tick(10);
		assertTrue("it looks again now and then", follower.stayAttempts > attempts);
	}

	@Test
	public void theNextTreeOverIsNotASeat()
	{
		// A same-name neighbour on the flank tile the seat would take first.
		// The scan happens once, at seat time, against the scene.
		WorldPoint target = new WorldPoint(52, 50, 0);
		game.placeObject(TREE_ID, "Tree", 52, 50, 0);
		game.placeObject(9002, "Tree", 50, 52, 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, target, null, null);
		game.animating(CHOP);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);

		assertNotNull(follower.stayingAt);
		assertFalse("that is where the player clicks next",
			follower.stayingAt.equals(new WorldPoint(50, 52, 0)));
	}

	@Test
	public void aSeatLetGoBySomebodyElseIsTakenAgain()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;

		// A held emote ending releases every stay, the company's included.
		follower.resumeFollowing();
		tick(1);

		assertEquals("the session is still on, so the seat is retaken", seat, follower.stayingAt);
	}

	@Test
	public void shiftingToTheNextTreeMovesTheSeatWithoutEndingTheSession()
	{
		workTheTree();
		WorldPoint first = follower.stayingAt;
		WorldPoint nextMin = new WorldPoint(50, 53, 0);
		WorldPoint nextMax = new WorldPoint(51, 54, 0);

		skilling.noteClick("Chop down", "Yew tree", TREE_ID + 1, nextMin, nextMax, null);
		assertTrue("the same tree over is the same session", skilling.isWorking());
		assertEquals(0, count(TriggerEvent.Type.WORK_END));
		assertEquals(nextMin, skilling.getSession().getTargetTile());

		tick(SkillingController.SEAT_AFTER_TICKS);
		WorldPoint second = follower.stayingAt;
		assertNotNull(second);
		assertFalse("the old seat was on the line to the new tree", first.equals(second));
		assertFalse(onTheLine(PLAYER, nextMin, second));
		assertFalse(inside(nextMin, nextMax, second));
	}

	@Test
	public void aClickOnSomethingElseMovesTheSessionToIt()
	{
		// Copper next to the tin, one spot for both: the player clicks the
		// rock two tiles over and keeps swinging from where they stand. The
		// session has to follow the click, or the seat, the drops and the
		// depletion all stay on a rock nobody is mining.
		workTheTree();
		WorldPoint first = follower.stayingAt;
		WorldPoint rock = new WorldPoint(PLAYER.getX() + 2, PLAYER.getY(), 0);

		skilling.noteClick("Mine", "Copper rocks", 11161, rock, null, null);
		assertEquals("the old session is still on until the proof",
			"Yew tree", skilling.getSession().getTargetName());
		skilling.noteAnimation(624);

		assertTrue(skilling.isWorking());
		assertEquals("the session followed the click",
			"Copper rocks", skilling.getSession().getTargetName());
		assertEquals(rock, skilling.getSession().getTargetTile());
		assertEquals("and the old one ended properly", 1, count(TriggerEvent.Type.WORK_END));

		tick(SkillingController.SEAT_AFTER_TICKS);
		WorldPoint second = follower.stayingAt;
		assertNotNull("a seat for the new work", second);
		assertFalse("chosen again for the rock, not kept from the tree",
			onTheLine(PLAYER, rock, second));
		assertFalse(first != null && first.equals(second) && onTheLine(PLAYER, rock, first));
	}

	@Test
	public void thievingTakesNoSeat()
	{
		// The thieving session already keeps the follower off at a distance;
		// its own watch comes later. The session itself still runs.
		NPC guard = game.spawnNpc(3010, "Guard", 21);
		game.moveNpc(guard, 52, 50, 0);
		skilling.noteClick("Pickpocket", "Guard", 3010, new WorldPoint(52, 50, 0), null, guard);
		game.animating(881);
		tick(1 + SkillingController.SEAT_AFTER_TICKS + 5);

		assertTrue(skilling.isWorking());
		assertEquals("thieving", skilling.getSession().getActivity());
		assertTrue(follower.following);
	}

	// ------------------------------------------------------------ letting go

	@Test
	public void walkingOffReleasesTheSeatAndEndsTheSession()
	{
		workTheTree();
		game.at(50, 54, 0);
		tick(1);

		assertFalse(skilling.isWorking());
		assertTrue("the seat is let go", follower.following);
		assertFalse(skilling.holdsTheFeet());
		assertEquals("and the ending is said exactly once", 1, count(TriggerEvent.Type.WORK_END));
		tick(5);
		assertEquals(1, count(TriggerEvent.Type.WORK_END));
	}

	@Test
	public void threeTilesIsStillAtIt()
	{
		workTheTree();
		game.at(53, 50, 0);
		tick(1);
		assertTrue("pacing round the tree is not leaving", skilling.isWorking());
	}

	@Test
	public void goingQuietEndsIt()
	{
		workTheTree();
		game.animating(-1);
		tick(SkillingController.QUIET_END_TICKS);
		assertTrue("a pause is not a stop", skilling.isWorking());
		tick(1);
		assertFalse("twenty-five ticks of nothing is", skilling.isWorking());
		assertTrue(follower.following);
	}

	@Test
	public void aDropKeepsAQuietSessionAlive()
	{
		// Some work animates only now and then; the XP says it goes on.
		workTheTree();
		game.animating(-1);
		tick(SkillingController.QUIET_END_TICKS - 1);
		skilling.noteXpDrop(Skill.WOODCUTTING);
		tick(SkillingController.QUIET_END_TICKS - 1);
		assertTrue(skilling.isWorking());
	}

	@Test
	public void theBankEndsIt()
	{
		workTheTree();
		bankOpen = true;
		tick(1);
		assertFalse(skilling.isWorking());
		assertTrue(follower.following);
	}

	@Test
	public void everyAbortPathLetsGoSilently()
	{
		String[] aborts = {"fight", "plane", "despawn", "thrall or spectate", "logout"};
		for (String abort : aborts)
		{
			setUp();
			game.wearing(6739);
			workTheTree();
			assertEquals(abort + ": the axe should be borrowed by now", 6739, held);
			// Long enough that a natural end would have earned the bow.
			tick(SkillingController.BEAT_AFTER_TICKS);

			switch (abort)
			{
				case "fight":
					inCombat = true;
					break;
				case "plane":
					game.at(50, 50, 1);
					break;
				case "despawn":
					follower.spawned = false;
					break;
				case "thrall or spectate":
					feetTaken = true;
					break;
				default:
					skilling.reset();
					break;
			}
			tick(1);

			assertFalse(abort + ": the session carried on", skilling.isWorking());
			assertEquals(abort + ": the tool must go straight back", 0, held);
			assertTrue(abort + ": an abort is silent, no bow", follower.animations.isEmpty());
			assertFalse(abort + ": the feet are not held", skilling.holdsTheFeet());
		}
	}

	@Test
	public void aNewOwnerOfTheFeetIsNotReleasedByTheCompany()
	{
		// The spectator or a thrall took the feet: ending the seat must not
		// yank them back to following underneath the new owner.
		workTheTree();
		feetTaken = true;
		tick(1);

		assertFalse(skilling.isWorking());
		assertFalse("the stay belongs to somebody else now", follower.following);
	}

	@Test
	public void aLogoutSaysNothing()
	{
		workTheTree();
		dispatched.clear();
		skilling.reset();

		assertFalse(skilling.isWorking());
		assertTrue(follower.following);
		assertTrue("nobody is there to tell", dispatched.isEmpty());
	}

	@Test
	public void switchingTheCompanyOffMidSessionEndsIt()
	{
		workTheTree();
		keepsCompany = false;
		tick(1);

		assertFalse(skilling.isWorking());
		assertTrue(follower.following);
		assertEquals(0, held);
	}

	// ------------------------------------------------------------ the tools

	@Test
	public void theBorrowedAxeIsTheWornOneOrBronze()
	{
		game.wearing(6739);
		workTheTree();
		assertEquals("the dragon axe the player wears", 6739, held);

		setUp();
		workTheTree();
		assertEquals("a bronze one when none is worn", 1351, held);

		setUp();
		borrowsTools = false;
		workTheTree();
		assertEquals("nothing with the setting off", 0, held);
	}

	@Test
	public void aLongSessionEarnsABowOnTheWayOut()
	{
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS);
		game.at(50, 54, 0);
		tick(1);

		assertEquals("the bow", Collections.singletonList(858), follower.animations);
		assertEquals("and the axe went back on the same call", 0, held);
		assertEquals("facing the player for it", new WorldPoint(50, 54, 0), follower.facing);
		assertTrue("the feet stay still while it plays", skilling.holdsTheFeet());
		assertFalse(follower.following);

		tick(3);
		assertTrue("then the follower comes back", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void aShortSessionGivesTheToolBackWithoutCeremony()
	{
		workTheTree();
		tick(20);
		game.at(50, 54, 0);
		tick(1);

		assertTrue(follower.animations.isEmpty());
		assertEquals(0, held);
		assertTrue(follower.following);
	}

	// ------------------------------------------------------------- the world

	@Test
	public void theTargetGoingIsSaidOnce()
	{
		workTheTree();
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertEquals(1, count(TriggerEvent.Type.TARGET_DEPLETED));
		assertTrue(skilling.getSession().isDepleted());

		// The stump appearing on the same tiles is the same news.
		skilling.objectSpawned(TREE_ID + 50, TREE_MIN, TREE_MAX);
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertEquals("said once", 1, count(TriggerEvent.Type.TARGET_DEPLETED));
		assertTrue("and the session goes on until the player leaves", skilling.isWorking());
	}

	@Test
	public void aStumpAloneIsDepletionToo()
	{
		workTheTree();
		skilling.objectSpawned(TREE_ID + 50, TREE_MIN, TREE_MAX);
		assertEquals(1, count(TriggerEvent.Type.TARGET_DEPLETED));
	}

	@Test
	public void otherTilesAreNobodysBusiness()
	{
		workTheTree();
		skilling.objectDespawned(TREE_ID, new WorldPoint(60, 60, 0), new WorldPoint(61, 61, 0));
		skilling.objectSpawned(TREE_ID + 50, new WorldPoint(52, 50, 0), null);
		assertEquals(0, count(TriggerEvent.Type.TARGET_DEPLETED));
	}

	@Test
	public void theSceneShufflingOnALoadIsNotATreeFalling()
	{
		workTheTree();
		skilling.sceneLoading();
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		tick(2);
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertEquals("dropped around a load", 0, count(TriggerEvent.Type.TARGET_DEPLETED));

		tick(1);
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertEquals("steady play again", 1, count(TriggerEvent.Type.TARGET_DEPLETED));
	}

	@Test
	public void aFishingSpotMovingIsItsDepletionAndTheNewOneIsNoted()
	{
		NPC spot = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(spot, 53, 50, 0);
		WorldPoint at = new WorldPoint(53, 50, 0);
		skilling.noteClick("Net", "Fishing spot", 1530, at, at, spot);
		game.animating(621);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("fishing", skilling.getSession().getActivity());
		assertNotNull(follower.stayingAt);

		// Some other spot going is nothing.
		NPC other = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(other, 56, 52, 0);
		skilling.npcDespawned(other);
		assertEquals(0, count(TriggerEvent.Type.TARGET_DEPLETED));

		skilling.npcDespawned(spot);
		assertEquals(1, count(TriggerEvent.Type.TARGET_DEPLETED));
		assertNull(skilling.getSession().getNewSpot());

		NPC moved = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(moved, 55, 53, 0);
		skilling.npcSpawned(moved);
		assertEquals("where it went", new WorldPoint(55, 53, 0), skilling.getSession().getNewSpot());
	}

	// --------------------------------------------------------------- the bag

	@Test
	public void theBagEdgesAsADecision()
	{
		assertNull("no reading yet", SkillingController.bagEdge(-1, 0, true));
		assertEquals(TriggerEvent.Type.BAG_FULL, SkillingController.bagEdge(1, 0, true));
		assertNull("still full is not filling", SkillingController.bagEdge(0, 0, true));
		assertEquals(TriggerEvent.Type.BAG_HALF, SkillingController.bagEdge(15, 14, true));
		assertEquals("a jump past it still crosses it",
			TriggerEvent.Type.BAG_HALF, SkillingController.bagEdge(20, 10, true));
		assertNull("once per fill", SkillingController.bagEdge(15, 14, false));
		assertNull("emptying is not filling", SkillingController.bagEdge(10, 20, true));
	}

	@Test
	public void theBagIsWatchedWhetherOrNotAnyoneIsWorking()
	{
		game.inventoryUsing(10);
		tick(1);
		game.inventoryUsing(14);
		tick(1);
		assertEquals(1, count(TriggerEvent.Type.BAG_HALF));
		game.inventoryUsing(16);
		tick(1);
		assertEquals("once per fill", 1, count(TriggerEvent.Type.BAG_HALF));
		game.inventoryUsing(28);
		tick(1);
		assertEquals(1, count(TriggerEvent.Type.BAG_FULL));

		game.inventoryUsing(0);
		tick(1);
		game.inventoryUsing(20);
		tick(1);
		assertEquals("re-armed once the bag emptied", 2, count(TriggerEvent.Type.BAG_HALF));
	}

	// ------------------------------------------------------------ the company

	/** A fire east of the player. */
	private static final WorldPoint FIRE = new WorldPoint(53, 50, 0);

	/** HUMAN_COOKING. */
	private static final int COOK = 896;

	/** A cook at a fire: nothing to borrow, so the hands are free for the scroll. */
	private void workTheFire()
	{
		skilling.noteClick("Cook", "Fire", 9020, FIRE, null, null);
		game.animating(COOK);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);
	}

	/** Drops that far apart until the scroll comes out; the drop it came out on, or -1. */
	private int dropsUntilTheScroll(int gap, int most)
	{
		for (int i = 1; i <= most; i++)
		{
			tick(gap);
			skilling.noteXpDrop(Skill.COOKING);
			if (held == ErrandController.SCROLL_PROP)
			{
				return i;
			}
		}
		return -1;
	}

	@Test
	public void theTallyScrollComesOutAroundEveryEighthDrop()
	{
		workTheFire();
		int out = dropsUntilTheScroll(4, 12);
		assertTrue("came out on drop " + out, out >= 7 && out <= 9);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		tick(1);
		assertEquals("facing the player for it", PLAYER, follower.facing);

		tick(KeepingCompany.TALLY_TICKS);
		assertEquals("and away after four seconds", 0, held);
		assertEquals(0, follower.poseOverride);
		assertEquals("back to the work", FIRE, follower.facing);
		assertEquals("the seat was never let go", 1, follower.stays.size());
	}

	@Test
	public void theTallyIsNotOnAMetronome()
	{
		skilling.random.setSeed(7);
		workTheFire();
		List<Integer> gaps = new ArrayList<>();
		for (int cycle = 0; cycle < 6; cycle++)
		{
			int out = dropsUntilTheScroll(4, 12);
			assertTrue(out > 0);
			gaps.add(out);
			tick(KeepingCompany.TALLY_TICKS);
		}
		assertTrue("six cycles all the same length: " + gaps,
			gaps.stream().distinct().count() > 1);
	}

	@Test
	public void fastDropsTallyAroundEveryTwelfth()
	{
		workTheFire();
		int out = dropsUntilTheScroll(2, 16);
		assertTrue("came out on drop " + out, out >= 11 && out <= 13);
	}

	@Test
	public void aToolInHandMeansNoScroll()
	{
		workTheTree();
		assertEquals(1351, held);
		for (int i = 0; i < 20; i++)
		{
			tick(4);
			skilling.noteXpDrop(Skill.WOODCUTTING);
			assertEquals("the axe keeps the weapon slot", 1351, held);
		}
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void theNodIsAboutEveryThirdDropAndNeverTwoRunning()
	{
		// Off the seat the look is a glance, which the recorder can see.
		follower.walled = tile -> true;
		workTheFire();
		List<Integer> nods = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			tick(4);
			int before = follower.glances.size();
			skilling.noteXpDrop(Skill.COOKING);
			if (follower.glances.size() > before)
			{
				nods.add(i);
				assertEquals("at the work", FIRE, follower.glances.get(before));
			}
		}
		assertTrue("too few looks: " + nods, nods.size() >= 7);
		assertTrue("too many looks: " + nods, nods.size() <= 15);
		for (int i = 1; i < nods.size(); i++)
		{
			assertTrue("two in a row: " + nods, nods.get(i) - nods.get(i - 1) >= 2);
		}
	}

	@Test
	public void theStopEdge()
	{
		workTheFire();
		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS - 1);
		assertEquals("four ticks is a pause", FIRE, follower.facing);

		tick(1);
		assertEquals("five is a stop: look over", PLAYER, follower.facing);
		assertTrue("and wait", follower.animations.isEmpty());

		tick(KeepingCompany.STOP_WAIT_TICKS + 2);
		assertEquals("then the head scratch", Collections.singletonList(857), follower.animations);

		game.animating(COOK);
		tick(1);
		assertEquals("back to the work when the player is", FIRE, follower.facing);

		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS + KeepingCompany.STOP_WAIT_TICKS + 2);
		assertEquals("not again inside two minutes", 1, follower.animations.size());
		assertEquals(FIRE, follower.facing);
	}

	@Test
	public void aShuffleIsNotAStop()
	{
		workTheFire();
		game.animating(-1);
		tick(3);
		game.at(51, 50, 0);
		// The tick that sees the move resets the count; two still ticks follow.
		tick(3);
		assertEquals("moving resets the count", FIRE, follower.facing);
		tick(KeepingCompany.STOP_AFTER_TICKS - 2);
		assertEquals("five still ticks from the new tile is a stop",
			PLAYER.getX() + 1, follower.facing.getX());
	}

	@Test
	public void pacingTheLineAfterFiveMinutes()
	{
		workTheFire();
		WorldPoint seat = follower.stayingAt;
		tick(KeepingCompany.PACE_AFTER_TICKS - 60);
		assertEquals("nothing yet", 1, follower.stays.size());

		tick(120);
		assertTrue("the walk out", follower.stays.size() >= 2);
		WorldPoint away = follower.stays.get(1);
		assertEquals("three tiles from the seat", 3, away.distanceTo(seat));
		assertTrue("on the far side from the fire",
			away.distanceTo(FIRE) > seat.distanceTo(FIRE));

		tick(15);
		assertEquals("and back", seat, follower.stayingAt);
		assertEquals(FIRE, follower.facing);
		assertEquals(3, follower.stays.size());
		assertTrue(skilling.isWorking());
	}

	@Test
	public void sittingInForTheLongHaul()
	{
		workTheFire();
		tick(KeepingCompany.SIT_AFTER_TICKS + 70);
		assertTrue(skilling.isSitting());
		assertEquals("the rest pose", 10061, follower.poseOverride);
		assertEquals("facing the player", PLAYER, follower.facing);

		// An emote stands first; the pose sits it back down after.
		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS + KeepingCompany.STOP_WAIT_TICKS + 2);
		assertEquals(Arrays.asList(KeepingCompany.STAND_UP, 857), follower.animations);
		assertTrue(skilling.isSitting());
		follower.animations.clear();
		game.animating(COOK);
		tick(1);

		// The end stands, and the feet wait for it.
		game.at(50, 54, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertFalse(skilling.isSitting());
		assertEquals(0, follower.poseOverride);
		assertEquals(Collections.singletonList(KeepingCompany.STAND_UP), follower.animations);
		assertTrue(skilling.holdsTheFeet());
		tick(KeepingCompany.STAND_UP_TICKS + 3);
		assertTrue(follower.following);
	}

	@Test
	public void anAbortWhileSittingLetsThePoseGoWithoutCeremony()
	{
		workTheFire();
		tick(KeepingCompany.SIT_AFTER_TICKS + 70);
		assertTrue(skilling.isSitting());
		inCombat = true;
		tick(1);
		assertFalse(skilling.isSitting());
		assertEquals(0, follower.poseOverride);
		assertTrue("no stand-up, no anything", follower.animations.isEmpty());
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void stretchingItsLegsAtTheHourAndAgainAtTwo()
	{
		workTheFire();
		WorldPoint seat = follower.stayingAt;
		WorldPoint behind = new WorldPoint(48, 50, 0);
		WorldPoint otherFlank = new WorldPoint(50, 48, 0);

		tick(KeepingCompany.STRETCH_AFTER_TICKS + 350);
		assertTrue("up first", follower.animations.contains(KeepingCompany.STAND_UP));
		// The seat, the pace out and back, then the half circle home.
		assertEquals(follower.stays.toString(), 7, follower.stays.size());
		assertEquals(behind, follower.stays.get(3));
		assertEquals(otherFlank, follower.stays.get(4));
		assertEquals(behind, follower.stays.get(5));
		assertEquals(seat, follower.stays.get(6));
		assertFalse("standing after the hour", skilling.isSitting());
		assertEquals(0, follower.poseOverride);
		assertEquals(FIRE, follower.facing);

		tick(KeepingCompany.STRETCH_AGAIN_TICKS - KeepingCompany.STRETCH_AFTER_TICKS);
		assertEquals(11, follower.stays.size());
		assertEquals(seat, follower.stayingAt);
		assertTrue("and sits after the second", skilling.isSitting());
		assertEquals(10061, follower.poseOverride);
	}

	@Test
	public void halfABagIsANote()
	{
		workTheFire();
		game.inventoryUsing(10);
		tick(1);
		game.inventoryUsing(14);
		tick(1);
		assertEquals(ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		tick(KeepingCompany.NOTE_TICKS);
		assertEquals(0, held);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void aFullBagIsAShrugAndAStepBankward()
	{
		game.placeObject(9030, "Bank booth", 60, 50, 0);
		workTheFire();
		WorldPoint seat = follower.stayingAt;
		game.inventoryUsing(27);
		tick(1);
		game.inventoryUsing(28);
		tick(1);
		assertEquals(Collections.singletonList(2113), follower.animations);
		assertEquals("facing the player", PLAYER, follower.facing);

		tick(4);
		WorldPoint step = follower.stayingAt;
		assertEquals("one step, no more", 1, step.distanceTo(seat));
		assertTrue("toward the booth", step.distanceTo(new WorldPoint(60, 50, 0))
			< seat.distanceTo(new WorldPoint(60, 50, 0)));
		assertEquals("the seat moved with it", step, skilling.getSeat());
	}

	@Test
	public void noBoothMeansTheShrugAlone()
	{
		workTheFire();
		game.inventoryUsing(27);
		tick(1);
		game.inventoryUsing(28);
		tick(5);
		assertEquals(Collections.singletonList(2113), follower.animations);
		assertEquals(1, follower.stays.size());
	}

	@Test
	public void aPlayerDroppingAsTheyGoGetsNoBagBusiness()
	{
		game.placeObject(9030, "Bank booth", 60, 50, 0);
		workTheFire();
		skilling.noteMenuOption("Drop");
		game.inventoryUsing(10);
		tick(1);
		game.inventoryUsing(14);
		tick(1);
		assertEquals("no note", 0, held);
		game.inventoryUsing(28);
		tick(5);
		assertTrue("no shrug", follower.animations.isEmpty());
		assertEquals("no step", 1, follower.stays.size());
	}

	@Test
	public void nothingFiresWithTheCompanyOff()
	{
		keepsCompany = false;
		game.placeObject(9030, "Bank booth", 60, 50, 0);
		skilling.noteClick("Cook", "Fire", 9020, FIRE, null, null);
		game.animating(COOK);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		for (int i = 0; i < 30; i++)
		{
			tick(4);
			skilling.noteXpDrop(Skill.COOKING);
		}
		game.animating(-1);
		tick(12);
		game.animating(COOK);
		game.inventoryUsing(10);
		tick(1);
		game.inventoryUsing(14);
		tick(1);
		game.inventoryUsing(28);
		tick(KeepingCompany.SIT_AFTER_TICKS + 100);

		assertTrue(follower.animations.isEmpty());
		assertTrue(follower.glances.isEmpty());
		assertTrue(follower.stays.isEmpty());
		assertEquals(0, follower.poseOverride);
		assertEquals(0, held);
		assertTrue(follower.following);
	}

	// ----------------------------------------------------------- the words

	@Test
	public void theOptionWordsAreMatchedWholeAndWithoutTags()
	{
		assertTrue(SkillingController.isWorkOption("Chop down"));
		assertTrue(SkillingController.isWorkOption("<col=ffff00>Mine</col>"));
		assertTrue(SkillingController.isWorkOption("Use"));
		assertFalse("a bare prefix is a different word", SkillingController.isWorkOption("Pick-up"));
		assertFalse(SkillingController.isWorkOption("Attack"));
		assertFalse(SkillingController.isWorkOption(null));

		assertEquals("woodcutting", SkillingController.activityFor("Chop down"));
		assertEquals("fishing", SkillingController.activityFor("Harpoon"));
		assertEquals("farming", SkillingController.activityFor("Check-health"));
		assertEquals("hunter", SkillingController.activityFor("Set-trap"));
		assertEquals("", SkillingController.activityFor("Use"));
	}
}
