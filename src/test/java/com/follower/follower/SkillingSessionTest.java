package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

		/**
		 * Tiles with a lit fire on them. Walkable by the map and refused by
		 * the follower, which is what a fire actually is - so these are NOT
		 * walls, and a test that makes one a wall is testing something else.
		 */
		final java.util.Set<WorldPoint> fires = new java.util.HashSet<>();

		RecordingFollower()
		{
			super(null, null);
			// Wired as FollowerPlugin wires the real one.
			setHotGround(fires::contains);
		}

		@Override
		public boolean isSpawned()
		{
			return spawned;
		}

		/** False while the follower is taken to be still catching up. */
		boolean settled = true;

		@Override
		public boolean isSettled()
		{
			return settled;
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
		boolean standable(WorldPoint at)
		{
			return at != null && (walled == null || !walled.test(at));
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

		/** Whether it was ever called to the front of the player; the stay goes with it. */
		boolean movedToFront;

		@Override
		public boolean moveToFront(net.runelite.api.Player local)
		{
			movedToFront = true;
			stayingAt = null;
			return true;
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

	/** What the fake hands have put on, or 0. */
	private int worn;
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
		worn = 0;
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

			@Override
			public void wear(int itemId)
			{
				worn = itemId;
			}

			@Override
			public void takeOff()
			{
				worn = 0;
			}

			@Override
			public boolean isWearing()
			{
				return worn != 0;
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
	public void theToolAndTheOutfitHatAreBothTaken()
	{
		// The hand and the head are different slots, so the follower looks
		// the way the player does: axe in hand, hat on. A tool earns the bow.
		game.wearing(6739, 10941, 10939, 10940, 10933);
		workTheTree();
		assertEquals("the axe in the hand", 6739, held);
		assertEquals("and the hat on the head", 10941, worn);

		tick(SkillingController.BEAT_AFTER_TICKS);
		game.at(50, 54, 0);
		tick(1 + SkillingController.TOOL_GRACE_TICKS);
		assertEquals("a bow for a tool", Collections.singletonList(858), follower.animations);
		assertEquals(0, held);
		assertEquals("the hat comes off with it", 0, worn);
	}

	@Test
	public void theMimicOnlyCopiesACastFromTheSeat()
	{
		assertTrue("no session: the mimic is free", skilling.mirrorsFromHere());
		follower.walled = tile -> true;
		workTheSpot();
		assertNull("no seat could be had", follower.stayingAt);
		assertFalse("so a cast is not copied from the grass", skilling.mirrorsFromHere());

		setUp();
		workTheSpot();
		assertNotNull(follower.stayingAt);
		assertTrue("from the seat it is", skilling.mirrorsFromHere());
	}

	/** Whether the tile touches the tree's footprint. */
	private static boolean againstTheTree(WorldPoint at)
	{
		if (at == null)
		{
			return false;
		}
		int cx = Math.max(TREE_MIN.getX(), Math.min(TREE_MAX.getX(), at.getX()));
		int cy = Math.max(TREE_MIN.getY(), Math.min(TREE_MAX.getY(), at.getY()));
		return Math.max(Math.abs(at.getX() - cx), Math.abs(at.getY() - cy)) <= 1;
	}

	/**
	 * The seat is placed against the PLAYER and never on the line to the
	 * work, so it is two or three tiles off the trunk. A chop copied from
	 * there is an axe swung at open air. Play report: "sometimes the
	 * follower will chop a tree but not be up against the tree".
	 */
	@Test
	public void theCopyIsMadeFromAgainstTheWorkAndNotFromTheSeat()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertFalse("the seat itself is not within reach of the tree",
			againstTheTree(seat));

		assertFalse("so the copying does not start from it", skilling.joinIn());
		assertTrue("it goes over instead", skilling.joiningIn());
		assertTrue("to somewhere within reach of the tree",
			againstTheTree(follower.stayingAt));
		assertTrue("and once there, that is when the copying starts",
			skilling.joinedIn());
	}

	@Test
	public void theCopyingOverDoesNotStandOnThePlayer()
	{
		workTheTree();
		skilling.joinIn();
		assertNotEquals("their tile is theirs", PLAYER, follower.stayingAt);
	}

	@Test
	public void theTripBackToTheSeatComesAfterTheCopying()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		skilling.joinIn();
		assertNotEquals("off the seat while it copies", seat, follower.stayingAt);

		skilling.dropTheJoin();
		assertFalse(skilling.joiningIn());
		tick(2);
		assertEquals("and back on it afterwards", seat, follower.stayingAt);
	}

	@Test
	public void nothingIsCopiedWithNowhereToSit()
	{
		// No seat means the follower is trailing the player, and a cast
		// copied from there fishes the grass. Unchanged by the walk over.
		follower.walled = tile -> true;
		workTheSpot();
		assertNull("no seat could be had", follower.stayingAt);
		assertFalse(skilling.joinIn());
		assertFalse("and it does not set off either", skilling.joiningIn());
	}

	@Test
	public void withNoWorkOnTheMimicKeepsItsFreedom()
	{
		assertTrue("nothing to stand against", skilling.joinIn());
		assertFalse(skilling.joiningIn());
	}

	@Test
	public void theCopyIsGivenUpWhenSomethingElseTakesTheFeet()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue(skilling.joiningIn());

		follower.settled = false;
		feetTaken = true;
		tick(1);
		assertFalse("the copy is given up rather than left hanging",
			skilling.joiningIn());
	}

	@Test
	public void theWalkOverIsGivenUpWhenItNeverGetsThere()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue(skilling.joiningIn());

		// Still walking, and still walking. Nothing has taken the feet and
		// the work is still on, so only the walk's own patience can end it.
		follower.settled = false;
		tick(SkillingController.JOIN_ARRIVAL_PATIENCE * 3);

		assertTrue("the session is still on", skilling.isWorking());
		assertFalse("so this is the walk over being given up, and nothing else",
			skilling.joiningIn());
	}

	@Test
	public void theAnglerSetAndTheNetAreBothTaken()
	{
		// The report: a full angler set and a small net, and the hat only
		// sometimes. Now the net is in the hand and the hat is on, every time.
		game.wearing(13258, 13259, 13260, 13261);
		workTheSpot();
		assertEquals("the net in the hand", 303, held);
		assertEquals("the angler hat on", 13258, worn);
	}

	@Test
	public void theToolStaysInHandFromOneTreeToTheNext()
	{
		// One tree is not the work. Walking to the next one used to hand the
		// axe back, bow and all, and take it straight out again.
		game.wearing(6739);
		workTheTree();
		assertEquals(6739, held);
		tick(SkillingController.BEAT_AFTER_TICKS);
		follower.animations.clear();

		game.at(56, 50, 0);
		tick(2);
		assertFalse("the session let go", skilling.isWorking());
		assertEquals("the axe did not", 6739, held);
		assertTrue("and nothing was played at it", follower.animations.isEmpty());

		skilling.noteClick("Chop down", "Yew tree", TREE_ID + 2,
			new WorldPoint(58, 50, 0), null, null);
		game.animating(CHOP);
		tick(1);
		assertTrue("the next tree is work again", skilling.isWorking());
		assertEquals("with the same axe, never handed back", 6739, held);
		assertTrue(follower.animations.isEmpty());
	}

	@Test
	public void aToolKeptAndNotUsedAgainGoesBackWithTheStintsSendOff()
	{
		game.wearing(6739);
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS);
		follower.animations.clear();

		game.at(56, 50, 0);
		tick(2);
		assertEquals("held while they might only be moving along", 6739, held);

		tick(SkillingController.TOOL_GRACE_TICKS);
		assertEquals("the work really was over", 0, held);
		assertEquals("and the stint earned its bow",
			Collections.singletonList(858), follower.animations);
	}

	@Test
	public void theSendOffWaitsForThePlayerToStandStill()
	{
		// A bow played while the follower is still hurrying after the player
		// is cancelled by the movement, so it waits for somewhere to land.
		game.wearing(6739);
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS);
		follower.animations.clear();

		follower.settled = false;
		game.at(56, 50, 0);
		tick(2 + SkillingController.TOOL_GRACE_TICKS);
		assertEquals("still holding it, waiting for a still moment", 6739, held);
		assertTrue("and nothing played into the walk", follower.animations.isEmpty());

		follower.settled = true;
		tick(1);
		assertEquals("the bow, once there is somewhere to put it",
			Collections.singletonList(858), follower.animations);
		assertEquals(0, held);
	}

	@Test
	public void aSendOffWithNowhereToLandGivesUpRatherThanHoldingForEver()
	{
		game.wearing(6739);
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS);
		follower.animations.clear();

		follower.settled = false;
		game.at(56, 50, 0);
		tick(2 + SkillingController.TOOL_GRACE_TICKS + SkillingController.BEAT_WAIT_TICKS);
		assertEquals("the tool goes back regardless", 0, held);
		assertTrue("quietly, since there was never a moment", follower.animations.isEmpty());
	}

	@Test
	public void aShortStintGetsNoSendOffAtTheEndOfTheGrace()
	{
		workTheTree();
		assertEquals(1351, held);
		game.at(56, 50, 0);
		tick(2 + SkillingController.TOOL_GRACE_TICKS);
		assertEquals(0, held);
		assertTrue("a minute of chopping is not a ceremony", follower.animations.isEmpty());
	}

	@Test
	public void differentWorkSwapsTheKeptTool()
	{
		workTheTree();
		assertEquals(1351, held);
		game.at(56, 50, 0);
		tick(2);
		assertEquals("kept for the moment", 1351, held);

		skilling.noteClick("Mine", "Rocks", ROCK_ID, new WorldPoint(58, 50, 0), null, null);
		game.animating(MINE);
		tick(1);
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertEquals("an axe is no good at a rock", 1265, held);
	}

	@Test
	public void aFightDuringTheGracePutsTheToolStraightBack()
	{
		workTheTree();
		game.at(56, 50, 0);
		tick(2);
		assertEquals(1351, held);

		inCombat = true;
		tick(1);
		assertEquals("nobody to hand it to mid-fight", 0, held);
		assertTrue(follower.animations.isEmpty());
	}

	@Test
	public void aFellingAxeIsBorrowedAsItself()
	{
		// A short tier list read a dragon felling axe as a bronze axe, which
		// is the one thing a borrow must never get wrong.
		game.wearing(28217);
		workTheTree();
		assertEquals("the felling axe the player wears", 28217, held);
	}

	@Test
	public void anExcusedSeatIsNeitherRetakenNorHabitedUntilHandedBack()
	{
		workTheTree();
		WorldPoint seat = skilling.getSeat();
		int stays = follower.stays.size();

		// A moment takes the follower off the seat and excuses it.
		skilling.setExcused(true);
		follower.stayingAt = null;
		tick(5);
		assertEquals("not retaken while excused", stays, follower.stays.size());
		assertEquals("but the seat is kept in mind", seat, skilling.getSeat());
		assertTrue("and stays the company's, so no Stay line", skilling.holdsTheFeet());
		assertTrue("the session goes on underneath", skilling.isWorking());

		skilling.setExcused(false);
		tick(1);
		assertEquals("retaken the tick it is handed back", seat, follower.stayingAt);

		// A session that ends while excused still lets everything go.
		skilling.setExcused(true);
		game.at(50, 54, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertNull(skilling.getSeat());
	}

	@Test
	public void aLongStintEarnsABowWhenTheWorkIsReallyOver()
	{
		// The send-off belongs to the stint, not to the last tree of it, so
		// it waits out the walk that might have been to the next one.
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS);
		game.at(50, 54, 0);
		tick(1);

		assertFalse("the session let go", skilling.isWorking());
		assertTrue("but nothing is played yet", follower.animations.isEmpty());
		assertEquals("and the axe is still in hand", 1351, held);

		tick(SkillingController.TOOL_GRACE_TICKS);
		assertEquals("the bow, once they are plainly done",
			Collections.singletonList(858), follower.animations);
		assertEquals("and the axe went back on the same call", 0, held);
		assertTrue("the follower was following all through it", follower.following);
	}

	@Test
	public void aShortSessionGivesTheToolBackWithoutCeremony()
	{
		workTheTree();
		tick(20);
		game.at(50, 54, 0);
		tick(1 + SkillingController.TOOL_GRACE_TICKS);

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

	/**
	 * A range east of the player. A range rather than a fire: the seat at a
	 * fire is across the flames with the hands to them, and these are the
	 * habits every seat shares.
	 */
	private static final WorldPoint RANGE = new WorldPoint(53, 50, 0);

	/** HUMAN_COOKING. */
	private static final int COOK = 896;

	/** A cook at a range: nothing to borrow, so the hands are free for the scroll. */
	private void workTheRange()
	{
		skilling.noteClick("Cook", "Range", 9020, RANGE, null, null);
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
		workTheRange();
		int out = dropsUntilTheScroll(4, 12);
		assertTrue("came out on drop " + out, out >= 7 && out <= 9);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		tick(1);
		assertEquals("facing the player for it", PLAYER, follower.facing);

		tick(KeepingCompany.TALLY_TICKS);
		assertEquals("and away after four seconds", 0, held);
		assertEquals(0, follower.poseOverride);
		assertEquals("back to the work", RANGE, follower.facing);
		assertEquals("the seat was never let go", 1, follower.stays.size());
	}

	@Test
	public void theTallyIsNotOnAMetronome()
	{
		skilling.random.setSeed(7);
		workTheRange();
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
		workTheRange();
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
		workTheRange();
		List<Integer> nods = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			tick(4);
			int before = follower.glances.size();
			skilling.noteXpDrop(Skill.COOKING);
			if (follower.glances.size() > before)
			{
				nods.add(i);
				assertEquals("at the work", RANGE, follower.glances.get(before));
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
		workTheRange();
		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS - 1);
		assertEquals("four ticks is a pause", RANGE, follower.facing);

		tick(1);
		assertEquals("five is a stop: look over", PLAYER, follower.facing);
		assertTrue("and wait", follower.animations.isEmpty());

		tick(KeepingCompany.STOP_WAIT_TICKS + 2);
		assertEquals("then the head scratch", Collections.singletonList(857), follower.animations);

		game.animating(COOK);
		tick(1);
		assertEquals("back to the work when the player is", RANGE, follower.facing);

		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS + KeepingCompany.STOP_WAIT_TICKS + 2);
		assertEquals("not again inside two minutes", 1, follower.animations.size());
		assertEquals(RANGE, follower.facing);
	}

	@Test
	public void aShuffleIsNotAStop()
	{
		workTheRange();
		game.animating(-1);
		tick(3);
		game.at(51, 50, 0);
		// The tick that sees the move resets the count; two still ticks follow.
		tick(3);
		assertEquals("moving resets the count", RANGE, follower.facing);
		tick(KeepingCompany.STOP_AFTER_TICKS - 2);
		assertEquals("five still ticks from the new tile is a stop",
			PLAYER.getX() + 1, follower.facing.getX());
	}

	@Test
	public void pacingTheLineAfterFiveMinutes()
	{
		workTheRange();
		WorldPoint seat = follower.stayingAt;
		tick(KeepingCompany.PACE_AFTER_TICKS - 60);
		assertEquals("nothing yet", 1, follower.stays.size());

		tick(120);
		assertTrue("the walk out", follower.stays.size() >= 2);
		WorldPoint away = follower.stays.get(1);
		assertEquals("three tiles from the seat", 3, away.distanceTo(seat));
		assertTrue("on the far side from the range",
			away.distanceTo(RANGE) > seat.distanceTo(RANGE));

		tick(15);
		assertEquals("and back", seat, follower.stayingAt);
		assertEquals(RANGE, follower.facing);
		assertEquals(3, follower.stays.size());
		assertTrue(skilling.isWorking());
	}

	@Test
	public void sittingInForTheLongHaul()
	{
		workTheRange();
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
		workTheRange();
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
		workTheRange();
		WorldPoint seat = follower.stayingAt;
		WorldPoint behind = new WorldPoint(48, 50, 0);
		// The seat is along the range's side; both flanks are as far from it,
		// and the first is taken.
		WorldPoint otherFlank = new WorldPoint(50, 52, 0);

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
		assertEquals(RANGE, follower.facing);

		tick(KeepingCompany.STRETCH_AGAIN_TICKS - KeepingCompany.STRETCH_AFTER_TICKS);
		assertEquals(11, follower.stays.size());
		assertEquals(seat, follower.stayingAt);
		assertTrue("and sits after the second", skilling.isSitting());
		assertEquals(10061, follower.poseOverride);
	}

	@Test
	public void halfABagIsANote()
	{
		workTheRange();
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
		workTheRange();
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
		workTheRange();
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
		workTheRange();
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
		skilling.noteClick("Cook", "Range", 9020, RANGE, null, null);
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

	// ------------------------------------------------------------- gathering

	/** A rock two tiles east of the player. */
	private static final WorldPoint ROCK = new WorldPoint(52, 50, 0);
	private static final int ROCK_ID = 11161;

	/** HUMAN_MINING_BRONZE_PICKAXE. */
	private static final int MINE = 625;

	/** A spot in the water, with the player on the bank beside it. */
	private static final WorldPoint SPOT = new WorldPoint(53, 50, 0);
	private static final WorldPoint BANK_SIDE = new WorldPoint(52, 50, 0);

	private void workTheRock()
	{
		skilling.noteClick("Mine", "Rocks", ROCK_ID, ROCK, null, null);
		game.animating(MINE);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);
	}

	private NPC workTheSpot()
	{
		game.at(BANK_SIDE.getX(), BANK_SIDE.getY(), 0);
		NPC spot = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(spot, SPOT.getX(), SPOT.getY(), 0);
		skilling.noteClick("Net", "Fishing spot", 1530, SPOT, SPOT, spot);
		game.animating(621);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);
		return spot;
	}

	@Test
	public void theAnglersSeatIsOnTheBankBesideTheSpot()
	{
		workTheSpot();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("beside the spot", 1, seat.distanceTo(SPOT));
		assertFalse("not the player's own tile", seat.equals(BANK_SIDE));
		assertEquals("facing the water", SPOT, follower.facing);
	}

	@Test
	public void theWaterGetsALookNowAndThen()
	{
		workTheSpot();
		boolean lookedAway = false;
		boolean back = false;
		for (int i = 0; i < 80; i++)
		{
			tick(1);
			WorldPoint face = follower.facing;
			if (!SPOT.equals(face))
			{
				lookedAway = true;
				assertFalse("at the water, not the player", BANK_SIDE.equals(face));
				assertTrue("a different bit of the same water: " + face, face.distanceTo(SPOT) <= 3);
			}
			else if (lookedAway)
			{
				back = true;
			}
		}
		assertTrue("no look at the water inside 45 seconds", lookedAway);
		assertTrue("and back to the spot after", back);
		assertEquals("the seat was never let go", 1, follower.stays.size());
	}

	@Test
	public void theSpotMovingIsALookAtTheNewOneAndASeatBesideIt()
	{
		NPC spot = workTheSpot();
		skilling.npcDespawned(spot);
		WorldPoint newSpot = new WorldPoint(58, 53, 0);
		NPC moved = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(moved, newSpot.getX(), newSpot.getY(), 0);
		skilling.npcSpawned(moved);

		tick(1);
		assertEquals("a beat on the player first", BANK_SIDE, follower.facing);
		tick(3);
		assertEquals("then the new spot, before the click", newSpot, follower.facing);

		// The player clicks it and walks over: five tiles is not a walk-off
		// when the spot moved, and the seat waits for the first cast.
		game.animating(-1);
		skilling.noteClick("Net", "Fishing spot", 1530, newSpot, newSpot, moved);
		assertTrue(skilling.isWorking());
		game.at(55, 52, 0);
		tick(2);
		game.at(57, 53, 0);
		tick(2);
		assertTrue("on the way to the new spot", skilling.isWorking());
		assertEquals(0, count(TriggerEvent.Type.WORK_END));
		assertEquals("the seat waits for the cast", 1, follower.stays.size());

		game.animating(621);
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertEquals("six ticks at the new spot first", 1, follower.stays.size());
		tick(1);
		WorldPoint seat = follower.stayingAt;
		assertEquals("re-seated beside the new spot", 1, seat.distanceTo(newSpot));
		assertFalse(seat.equals(new WorldPoint(57, 53, 0)));
		assertEquals(newSpot, follower.facing);
	}

	@Test
	public void theMinersSeatIsDiagonalBehindTwoBack()
	{
		workTheRock();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("two back from the player", PLAYER.getX() - 2, seat.getX());
		assertEquals("and one to the side", 1, Math.abs(seat.getY() - PLAYER.getY()));
		assertEquals("facing the rock", ROCK, follower.facing);
	}

	@Test
	public void anEmptiedRockIsAStepBackAndThreeLooks()
	{
		WorldPoint next = new WorldPoint(52, 53, 0);
		game.placeObject(ROCK_ID + 1, "Rocks", next.getX(), next.getY(), 0);
		workTheRock();
		tick(KeepingCompany.ROCK_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;

		// The empty rock takes the tile.
		skilling.objectSpawned(ROCK_ID + 2, ROCK, null);
		assertEquals("one step back", 2, follower.stays.size());
		WorldPoint back = follower.stayingAt;
		assertEquals(1, back.distanceTo(seat));
		assertTrue("away from the rock", back.distanceTo(ROCK) > seat.distanceTo(ROCK));
		assertEquals("the seat moved with it", back, skilling.getSeat());

		tick(1);
		assertEquals("the empty rock", ROCK, follower.facing);
		tick(3);
		assertEquals("the next full one", next, follower.facing);
		tick(4);
		assertEquals("then the player", PLAYER, follower.facing);
		tick(3);
		assertEquals("and back to the work", ROCK, follower.facing);
		assertTrue(follower.animations.isEmpty());
	}

	@Test
	public void aRockWorkedAMomentIsJustALookForTheNext()
	{
		WorldPoint next = new WorldPoint(52, 53, 0);
		game.placeObject(ROCK_ID + 1, "Rocks", next.getX(), next.getY(), 0);
		workTheRock();
		tick(3);
		skilling.objectSpawned(ROCK_ID + 2, ROCK, null);
		assertEquals("no step for a twig", 1, follower.stays.size());

		tick(1);
		assertEquals("the player", PLAYER, follower.facing);
		tick(3);
		assertEquals("then where the next one is", next, follower.facing);
	}

	@Test
	public void twoDepletionsInsideFifteenTicksShareTheOneLook()
	{
		WorldPoint next = new WorldPoint(52, 53, 0);
		game.placeObject(ROCK_ID + 1, "Rocks", next.getX(), next.getY(), 0);
		workTheRock();
		tick(3);
		skilling.objectSpawned(ROCK_ID + 2, ROCK, null);
		tick(1);
		assertEquals("the first gets its look", PLAYER, follower.facing);

		skilling.noteClick("Mine", "Rocks", ROCK_ID + 1, next, null, null);
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertEquals(next, follower.facing);
		skilling.objectSpawned(ROCK_ID + 3, next, null);
		tick(1);
		assertFalse("inside fifteen ticks, no second look", PLAYER.equals(follower.facing));
	}

	@Test
	public void threeMinutesAtOneRockIsAReadOfTheNotes()
	{
		borrowsTools = false;
		workTheRock();
		tick(KeepingCompany.GERALD_AFTER_TICKS);
		assertEquals("the note pose", KeepingCompany.NOTE_POSE, follower.poseOverride);
		assertEquals("with the scroll", ErrandController.SCROLL_PROP, held);
		assertEquals("turned to the rock", ROCK, follower.facing);
		assertTrue(follower.animations.isEmpty());

		tick(KeepingCompany.TALLY_TICKS);
		assertEquals(0, follower.poseOverride);
		assertEquals(0, held);
		assertEquals("one shake of the head",
			Collections.singletonList(KeepingCompany.HEAD_SHAKE), follower.animations);

		tick(KeepingCompany.GERALD_AFTER_TICKS);
		assertEquals("once per rock", 1, follower.animations.size());
	}

	@Test
	public void theNotesAreReadWithTheScrollAndThePickaxeComesBack()
	{
		// The pose is a reading of the notes, so the notes have to be in the
		// hand: the pickaxe is put down for it and picked up again after.
		workTheRock();
		assertEquals(1265, held);
		tick(KeepingCompany.GERALD_AFTER_TICKS);
		assertEquals(KeepingCompany.NOTE_POSE, follower.poseOverride);
		assertEquals("a reader holds the scroll, not the pickaxe",
			ErrandController.SCROLL_PROP, held);
		tick(KeepingCompany.TALLY_TICKS);
		assertEquals(0, follower.poseOverride);
		assertEquals("and the pickaxe is back in the hand after", 1265, held);
		assertEquals(Collections.singletonList(KeepingCompany.HEAD_SHAKE), follower.animations);
	}

	@Test
	public void aSessionEndingMidReadStillGivesThePickaxeBack()
	{
		// The tool is on loan while it is set aside, so an end that lands
		// between the scroll coming out and going away must not lose it.
		workTheRock();
		tick(KeepingCompany.GERALD_AFTER_TICKS);
		assertEquals(ErrandController.SCROLL_PROP, held);

		inCombat = true;
		tick(1);
		assertFalse("the session let go", skilling.isWorking());
		assertEquals("and the hands are empty", 0, held);
	}

	@Test
	public void timberAfterThirtyTicks()
	{
		WorldPoint next = new WorldPoint(53, 56, 0);
		game.placeObject(TREE_ID + 1, "Yew tree", next.getX(), next.getY(), 0);
		workTheTree();
		tick(KeepingCompany.TIMBER_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;

		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertEquals("the flinch", Collections.singletonList(KeepingCompany.FLINCH),
			follower.animations);
		assertEquals("one step clear of the stump", 2, follower.stays.size());
		WorldPoint away = follower.stayingAt;
		assertEquals(1, away.distanceTo(seat));
		assertTrue(away.distanceTo(TREE_MIN) > seat.distanceTo(TREE_MIN));

		tick(1);
		assertEquals("then the player", PLAYER, follower.facing);
		tick(3);
		assertEquals("then the next yew", next, follower.facing);
	}

	@Test
	public void aQuickChopIsNoFall()
	{
		WorldPoint next = new WorldPoint(53, 56, 0);
		game.placeObject(TREE_ID + 1, "Yew tree", next.getX(), next.getY(), 0);
		workTheTree();
		tick(5);
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertTrue("no flinch for a twig", follower.animations.isEmpty());
		assertEquals("no step", 1, follower.stays.size());
		tick(1);
		assertEquals(PLAYER, follower.facing);
		tick(3);
		assertEquals(next, follower.facing);
	}

	@Test
	public void aNestAtTheFeetIsALookDownAndThePeerOnceProven()
	{
		workTheTree();
		skilling.itemSpawned("Bird nest", PLAYER);
		tick(1);
		assertEquals("a look at the ground", PLAYER, follower.facing);
		assertTrue("no peer until a player has been seen doing it", follower.animations.isEmpty());
		tick(4);
		assertEquals(TREE_MIN, follower.facing);

		skilling.notePlayerAnimation(KeepingCompany.NEST_LOOK);
		skilling.itemSpawned("Bird nest", PLAYER);
		assertEquals(Collections.singletonList(KeepingCompany.NEST_LOOK), follower.animations);

		skilling.itemSpawned("Bird nest", new WorldPoint(60, 60, 0));
		skilling.itemSpawned("Logs", PLAYER);
		assertEquals("somebody else's nest, and logs, are nothing", 1, follower.animations.size());
	}

	@Test
	public void aNestOutsideAChopIsNothing()
	{
		workTheRange();
		skilling.notePlayerAnimation(KeepingCompany.NEST_LOOK);
		skilling.itemSpawned("Bird nest", PLAYER);
		tick(1);
		assertTrue(follower.animations.isEmpty());
		assertEquals(RANGE, follower.facing);
	}

	// ------------------------------------------------------------- bank trip

	private static final WorldPoint BOOTH = new WorldPoint(60, 50, 0);

	/** Works the tree with a bank ten tiles off, then walks off toward it. */
	private WorldPoint walkOffToTheBank()
	{
		game.placeObject(9030, "Bank booth", BOOTH.getX(), BOOTH.getY(), 0);
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		game.at(55, 50, 0);
		tick(1);
		assertFalse("the session is over", skilling.isWorking());
		assertEquals(1, count(TriggerEvent.Type.WORK_END));
		return seat;
	}

	@Test
	public void theBankTripKeepsTheSeat()
	{
		WorldPoint seat = walkOffToTheBank();
		assertEquals("the seat is kept", seat, follower.stayingAt);
		assertTrue("and is still the company's, not a Stay", skilling.holdsTheFeet());
		assertEquals("facing the way they went", new WorldPoint(55, 50, 0), follower.facing);
		assertEquals("and so is the axe, for the same reason", 1351, held);

		game.at(59, 50, 0);
		tick(10);
		bankOpen = true;
		tick(1);
		bankOpen = false;
		tick(100);
		assertEquals("kept through the banking", seat, follower.stayingAt);
		assertTrue(skilling.holdsTheFeet());

		game.at(50, 51, 0);
		tick(1);
		assertTrue("back within three tiles: following again", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void aWalkOffThatNeverReachesTheBankLetsGoAfterTheWalk()
	{
		walkOffToTheBank();
		tick(SkillingController.BANK_WALK_TICKS - 1);
		assertTrue(skilling.holdsTheFeet());
		tick(1);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void theBankTripHasACap()
	{
		walkOffToTheBank();
		bankOpen = true;
		tick(1);
		tick(SkillingController.BANK_TRIP_CAP_TICKS - 1);
		assertTrue("three minutes is the most", skilling.holdsTheFeet());
		tick(1);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void theLeashSnapOrAFightEndsTheTrip()
	{
		walkOffToTheBank();
		bankOpen = true;
		tick(1);
		// The entity's own leash release: the stay is simply gone.
		follower.resumeFollowing();
		tick(1);
		assertFalse(skilling.holdsTheFeet());
		assertTrue(follower.following);

		setUp();
		walkOffToTheBank();
		inCombat = true;
		tick(1);
		assertFalse(skilling.holdsTheFeet());
		assertTrue(follower.following);
	}

	@Test
	public void theBankOpeningAtTheSeatIsATripToo()
	{
		game.placeObject(9030, "Bank booth", BOOTH.getX(), BOOTH.getY(), 0);
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		bankOpen = true;
		tick(1);
		assertFalse(skilling.isWorking());
		assertEquals("kept", seat, follower.stayingAt);
		tick(SkillingController.BANK_WALK_TICKS + 5);
		assertTrue("confirmed from the start, so the walk's limit does not apply",
			skilling.holdsTheFeet());
	}

	@Test
	public void noBankNearMeansTheOldRelease()
	{
		game.placeObject(9030, "Bank booth", 95, 95, 0);
		workTheTree();
		game.at(55, 50, 0);
		tick(1);
		assertTrue("forty-five tiles is not a bank trip", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void newWorkLetsTheKeptSeatGo()
	{
		walkOffToTheBank();
		game.at(55, 50, 0);
		skilling.noteClick("Chop down", "Oak tree", 9005, new WorldPoint(58, 50, 0), null, null);
		game.animating(CHOP);
		tick(1);
		assertTrue(skilling.isWorking());
		assertTrue("the old seat is let go for the new work", follower.following);
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertNotNull("and the new one taken", follower.stayingAt);
		assertEquals(2, follower.stays.size());
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
		assertEquals("firemaking", SkillingController.activityFor("Feed"));
		assertEquals("", SkillingController.activityFor("Use"));
	}

	// ------------------------------------------------------------ production

	/** A fire two tiles east of the player. */
	private static final WorldPoint FIRE = new WorldPoint(52, 50, 0);

	private void workTheFire()
	{
		skilling.noteClick("Cook", "Fire", 9021, FIRE, null, null);
		game.animating(COOK);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);
	}

	private int warmed()
	{
		return Collections.frequency(follower.animations, KeepingCompany.WARM_HANDS);
	}

	@Test
	public void theCookAtAFireIsSatAcrossItWithHandsToTheFlames()
	{
		workTheFire();
		assertEquals("across the fire from the player", new WorldPoint(53, 50, 0), follower.stayingAt);
		assertEquals("facing the flames", FIRE, follower.facing);
		assertTrue("the hands to them", warmed() >= 1);
		int before = warmed();
		tick(8);
		assertTrue("and again while the player cooks", warmed() > before);

		game.animating(-1);
		tick(1);
		before = warmed();
		tick(4);
		assertEquals("not once they stop", before, warmed());
	}

	/**
	 * The report, in one test: the player lights a campfire, cooks on it, and
	 * the follower stands in the flames. The seat across a fire is precisely
	 * the tile the next fire in a line goes on, and a fire is walkable ground,
	 * so nothing in the plugin had any reason to refuse it.
	 */
	@Test
	public void theCookIsNeverSeatedInTheFlames()
	{
		follower.fires.add(FIRE);
		follower.fires.add(new WorldPoint(53, 50, 0));   // the seat, lit
		workTheFire();

		WorldPoint seat = follower.stayingAt;
		assertNotNull("somewhere else to sit, not nowhere at all", seat);
		assertFalse("and not in a fire", follower.fires.contains(seat));
		assertTrue("still at the fireside", seat.distanceTo(FIRE) <= 2);
		for (WorldPoint sent : follower.stays)
		{
			assertFalse("nor sent into one on the way", follower.fires.contains(sent));
		}
	}

	@Test
	public void aCampfireIsCookedAtLikeAnyOtherFire()
	{
		// A Forestry campfire is cooked on exactly as a fire is. Sitting the
		// follower on a range's flank for one was simply wrong, and the flank
		// of a campfire is where the next one gets lit.
		skilling.noteClick("Cook", "Forester's Campfire", 43475, FIRE, null, null);
		game.animating(COOK);
		tick(1);
		assertTrue("the session never started", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS);

		assertEquals("across it, as at any fire",
			new WorldPoint(53, 50, 0), follower.stayingAt);
		assertEquals("facing the flames", FIRE, follower.facing);
		assertTrue("hands to them", warmed() >= 1);
	}

	/**
	 * The other half of the report: "moves erratically". Between batches the
	 * player lights the next fire, and that firemaking experience renamed the
	 * work, re-shaped the seat and moved the follower - then the next fish
	 * renamed it back and moved it again, every few seconds, all session.
	 */
	@Test
	public void firemakingBetweenBatchesDoesNotMoveTheCook()
	{
		workTheFire();
		skilling.noteXpDrop(Skill.COOKING);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		int stays = follower.stays.size();

		skilling.noteXpDrop(Skill.FIREMAKING);
		tick(4);
		skilling.noteXpDrop(Skill.COOKING);
		tick(4);
		skilling.noteXpDrop(Skill.FIREMAKING);
		tick(4);

		assertEquals("the work is still the cook",
			"cooking", skilling.getSession().getActivity());
		assertEquals("and the follower has not been moved", seat, follower.stayingAt);
		assertEquals("nor sent anywhere at all", stays, follower.stays.size());
	}

	@Test
	public void theCookAtARangeIsOneTileAlongItsSide()
	{
		workTheRange();
		WorldPoint seat = follower.stayingAt;
		assertEquals("touching the range", 1, seat.distanceTo(RANGE));
		assertEquals("square on to its side, not at a corner", RANGE.getX(), seat.getX());
		assertEquals("facing it", RANGE, follower.facing);
		tick(10);
		assertEquals("no warming at a range", 0, warmed());
	}

	@Test
	public void aUseClickOnTheFireIsSeatedAcrossItOnceTheDropNamesTheWork()
	{
		// "Use" says nothing about the skill, so the seat goes to the flank;
		// the first drop says cooking, and the seat is chosen again.
		skilling.noteClick("Use", "Fire", 9021, FIRE, null, null);
		game.animating(COOK);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("the flank, for work with no name", 2, follower.stayingAt.distanceTo(PLAYER));
		skilling.noteXpDrop(Skill.COOKING);
		tick(1);
		assertEquals("across the fire once named", new WorldPoint(53, 50, 0), follower.stayingAt);
	}

	/** HUMAN_SMITHING. */
	private static final int SMITH = 898;

	@Test
	public void theAnvilStudyKeepsTheNotesOutAndShrugsOverThemNowAndThen()
	{
		WorldPoint anvil = new WorldPoint(52, 50, 0);
		skilling.noteClick("Smith", "Anvil", 2097, anvil, null, null);
		game.animating(SMITH);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("the anvil's far side", new WorldPoint(53, 50, 0), follower.stayingAt);
		assertEquals("the notes out from the first", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		assertEquals("about the anvil, not the player", anvil, follower.facing);
		assertTrue("and the anvil errand cannot roll on top of it", skilling.blocksErrands());

		int down = -1;
		for (int i = 1; i <= 60 && down < 0; i++)
		{
			tick(1);
			if (held == 0)
			{
				down = i;
			}
		}
		assertTrue("the notes go down around the fiftieth tick: " + down,
			down >= KeepingCompany.STUDY_SHRUG_EVERY - 7 && down <= KeepingCompany.STUDY_SHRUG_EVERY + 5);
		assertEquals("for a shrug", Collections.singletonList(KeepingCompany.SHRUG),
			follower.animations);
		tick(4);
		assertEquals("and out again", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);

		for (int i = 0; i < 12; i++)
		{
			tick(2);
			skilling.noteXpDrop(Skill.SMITHING);
		}
		assertEquals("the tally never doubles up over the study", ErrandController.SCROLL_PROP, held);

		game.at(50, 54, 0);
		tick(1);
		assertEquals("away at the end", 0, held);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void aSpinningWheelIsASeatBesideItAndASitAtOnce()
	{
		WorldPoint wheel = new WorldPoint(52, 50, 0);
		skilling.noteClick("Spin", "Spinning wheel", 14889, wheel, null, null);
		game.animating(894);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("beside the wheel", 1, follower.stayingAt.distanceTo(wheel));
		assertTrue("sat at once", skilling.isSitting());
		assertEquals(10061, follower.poseOverride);

		game.at(50, 54, 0);
		tick(1);
		assertFalse(skilling.isSitting());
		assertEquals("up at the end", Collections.singletonList(KeepingCompany.STAND_UP),
			follower.animations);
	}

	// -------------------------------------------------------------- standing

	/** HUMAN_FLETCHING; the player faces south, so the work is the tile below them. */
	private static final int FLETCH = 1248;
	private static final WorldPoint FRONT = new WorldPoint(50, 49, 0);

	@Test
	public void fletchingOpensOnItsFirstDropAndEarnsALookOverTheShoulder()
	{
		game.animating(FLETCH);
		tick(1);
		assertFalse("an animation alone, with nothing clicked, is not work", skilling.isWorking());
		assertEquals(1, skilling.noteXpDrop(Skill.FLETCHING));
		assertTrue("the drop opens it", skilling.isWorking());
		assertTrue(skilling.getSession().isStanding());
		assertEquals("fletching", skilling.getSession().getActivity());
		assertEquals("the work is in front of the player", FRONT, skilling.getSession().getTargetTile());
		tick(SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertNotNull("a seat off the flank", seat);
		assertEquals(2, seat.distanceTo(PLAYER));

		int at = -1;
		for (int i = 1; i <= KeepingCompany.SHOULDER_AFTER_TICKS + 130 && at < 0; i++)
		{
			tick(1);
			if (i % 3 == 0)
			{
				skilling.noteXpDrop(Skill.FLETCHING);
			}
			if (follower.poseOverride == KeepingCompany.PEER)
			{
				at = i;
			}
		}
		assertTrue("the look over the shoulder never came", at >= 0);
		assertTrue("not inside three minutes: " + at, at >= KeepingCompany.SHOULDER_AFTER_TICKS - 105);
		assertTrue("to the front of the player", follower.movedToFront);
		assertFalse("off the seat for it", follower.isStaying());
		assertTrue("the session goes on", skilling.isWorking());
		tick(KeepingCompany.PEER_TICKS);
		assertEquals("and back on the seat", seat, follower.stayingAt);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void nothingInTheWorldCanDepleteStandingWork()
	{
		skilling.noteXpDrop(Skill.HERBLORE);
		tick(SkillingController.SEAT_AFTER_TICKS + 1);
		skilling.objectSpawned(1234, FRONT, null);
		assertFalse(skilling.getSession().isDepleted());
		assertEquals(0, count(TriggerEvent.Type.TARGET_DEPLETED));
	}

	@Test
	public void everySeventhPotionIsATurnAwayFromTheFumes()
	{
		game.animating(4969);
		skilling.noteXpDrop(Skill.HERBLORE);
		tick(SkillingController.SEAT_AFTER_TICKS + 1);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("two tiles off", 2, seat.distanceTo(PLAYER));

		List<Integer> turned = new ArrayList<>();
		for (int drop = 2; drop <= 21; drop++)
		{
			tick(4);
			skilling.noteXpDrop(Skill.HERBLORE);
			tick(1);
			if (follower.facing != null && follower.facing.distanceTo(FRONT) > seat.distanceTo(FRONT)
				&& !follower.facing.equals(PLAYER))
			{
				turned.add(drop);
			}
		}
		assertEquals(Arrays.asList(7, 14, 21), turned);
	}

	// ------------------------------------------------------------------ altar

	@Test
	public void theRunecraftAltarIsAKneelFacingIt()
	{
		WorldPoint altar = new WorldPoint(52, 50, 0);
		skilling.noteClick("Craft-rune", "Altar", 34760, altar, null, null);
		game.animating(791);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertNotNull(follower.stayingAt);
		assertEquals("facing the altar", altar, follower.facing);
		assertEquals(Collections.singletonList(KeepingCompany.KNEEL), follower.animations);

		game.at(50, 54, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertTrue("up and following when the player moves", follower.following);
	}

	@Test
	public void prayingAtAnAltarBorrowsTheAltarErrandSilently()
	{
		List<String> forced = new ArrayList<>();
		skilling.setErrands((key, silent) -> forced.add(key + (silent ? " silently" : " aloud")));
		skilling.noteClick("Pray-at", "Altar", 409, new WorldPoint(52, 50, 0), null, null);
		game.animating(645);
		tick(1 + SkillingController.SEAT_AFTER_TICKS + 2);
		assertTrue(skilling.isWorking());
		assertEquals(Collections.singletonList("altar silently"), forced);
		assertTrue("no seat of its own", follower.following);
		assertFalse("and the errands are not blocked, since one is wanted",
			skilling.blocksErrands());
	}

	// --------------------------------------------------------------- thieving

	/** HUMAN_PICKPOCKET. */
	private static final int PICKPOCKET = 881;

	@Test
	public void aFireLitOnTheSeatMovesTheFollowerOffIt()
	{
		// Lighting a line of fires is how this shows: the follower is given a
		// tile, the next fire goes up on it, and a stay persists by design, so
		// without this it stands in the flames until the session ends. The
		// fire is a real one here - open ground the follower refuses - because
		// a fire faked as a wall proves nothing about fires.
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);

		follower.fires.add(seat);
		tick(3);
		assertNotEquals("not left standing in it", seat, follower.stayingAt);
		assertTrue("and it found somewhere else or simply followed",
			follower.stayingAt != null || follower.following);
	}

	@Test
	public void aFireLitOnTheFireLineSpotLetsTheVisitGo()
	{
		game.at(50, 50, 0);
		tick(1);
		game.at(51, 50, 0);
		tick(1);
		for (int i = 0; i < SkillingController.FIRE_LINE_EVERY; i++)
		{
			game.placeObject(26185, "Fire", 51, 50, 0);
			skilling.objectSpawned(26185, new WorldPoint(51, 50, 0), null);
		}
		WorldPoint visit = follower.stayingAt;
		if (visit == null)
		{
			return;
		}

		follower.fires.add(visit);
		tick(2);
		assertTrue("the visit is given up rather than stood out in the fire",
			follower.stayingAt == null || !visit.equals(follower.stayingAt));
	}

	@Test
	public void aStallWithNoGuardStillEarnsASeatAndKeepsIt()
	{
		// With no seat taken the thieving drift owns the feet and moves the
		// follower every few seconds, which at a stall reads as pacing.
		WorldPoint stall = new WorldPoint(51, 50, 0);
		skilling.noteClick("Steal-from", "Baker's stall", 11730, stall, null, null);
		game.animating(PICKPOCKET);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);

		WorldPoint seat = follower.stayingAt;
		assertNotNull("a seat, guard or no guard", seat);
		assertTrue("and it is the company's, so the drift is off", skilling.holdsTheFeet());

		int stays = follower.stays.size();
		tick(60);
		assertEquals("and it stays put", seat, follower.stayingAt);
		assertEquals("without being sent anywhere again", stays, follower.stays.size());
	}

	@Test
	public void theStallWatchStandsBetweenThePlayerAndTheGuard()
	{
		WorldPoint stall = new WorldPoint(51, 50, 0);
		WorldPoint post = new WorldPoint(50, 58, 0);
		NPC guard = game.spawnNpc(3010, "Guard", 21);
		game.moveNpc(guard, post.getX(), post.getY(), 0);
		skilling.noteClick("Steal-from", "Baker's stall", 11730, stall, null, null);
		game.animating(PICKPOCKET);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertNotNull("a stall earns a watch, unlike a pocket", seat);
		assertEquals("five tiles off the player, toward the guard", new WorldPoint(50, 55, 0), seat);
		assertEquals("facing the guard", post, follower.facing);

		game.npcPosing(guard, 1);
		tick(1);
		assertEquals("the guard stirs: a glance at the player", PLAYER, follower.facing);
		tick(2);
		assertEquals("then the guard again", post, follower.facing);
		// The guard keeps stirring; the glance is thirty ticks from the last.
		tick(KeepingCompany.GUARD_GLANCE_GAP_TICKS - 3);
		assertEquals("not again inside thirty ticks", post, follower.facing);
		tick(1);
		assertEquals("and again after them", PLAYER, follower.facing);
	}

	@Test
	public void pickingAPocketStillKeepsItsDistanceRatherThanSitting()
	{
		// A stall earns a seat now, guard or no guard, but a pocket does
		// not: the drift that keeps the follower off the mark's elbow was
		// tuned for exactly that and still owns the feet.
		pickAPocket();
		tick(SkillingController.SEAT_AFTER_TICKS + 5);
		assertTrue(skilling.isWorking());
		assertTrue("no seat at the mark's elbow", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	private void pickAPocket()
	{
		NPC mark = game.spawnNpc(3010, "Guard", 21);
		game.moveNpc(mark, 52, 50, 0);
		skilling.noteClick("Pickpocket", "Guard", 3010, new WorldPoint(52, 50, 0), null, mark);
		game.animating(PICKPOCKET);
		tick(2);
		assertTrue(skilling.isWorking());
	}

	private int shakes()
	{
		return Collections.frequency(follower.animations, KeepingCompany.HEAD_SHAKE);
	}

	@Test
	public void aStunIsAShakeOfTheHeadOneTimeInThree()
	{
		skilling.random.setSeed(11);
		pickAPocket();
		int shakes = 0;
		for (int stun = 0; stun < 30; stun++)
		{
			int before = shakes();
			// The stars land, then the message; one stun.
			skilling.noteGraphic(SkillingController.STUN_GRAPHIC);
			skilling.noteChatMessage("You have been stunned!", ChatMessageType.GAMEMESSAGE);
			assertTrue("the graphic and the message doubled up", shakes() - before <= 1);
			shakes += shakes() - before;
			tick(SkillingController.STUN_COOLDOWN_TICKS + 1);
		}
		assertTrue("some, not all, not none: " + shakes, shakes >= 3 && shakes <= 18);
	}

	@Test
	public void theStunHasACooldown()
	{
		pickAPocket();
		for (int stun = 0; stun < 40 && shakes() == 0; stun++)
		{
			skilling.noteGraphic(SkillingController.STUN_GRAPHIC);
			tick(3);
		}
		assertEquals(1, shakes());
		for (int stun = 0; stun < 6; stun++)
		{
			skilling.noteGraphic(SkillingController.STUN_GRAPHIC);
			tick(3);
		}
		assertEquals("not again inside twenty seconds", 1, shakes());
	}

	@Test
	public void somebodyElseSayingStunnedIsNotAStun()
	{
		pickAPocket();
		for (int stun = 0; stun < 40; stun++)
		{
			skilling.noteChatMessage("lol you got stunned again", ChatMessageType.PUBLICCHAT);
			skilling.noteChatMessage("stunned twice in a row", ChatMessageType.CLAN_CHAT);
			tick(3);
		}
		assertEquals("only the game's own message counts", 0, shakes());
	}

	@Test
	public void aStunOutsideThievingIsNothing()
	{
		workTheTree();
		for (int stun = 0; stun < 20; stun++)
		{
			skilling.noteGraphic(SkillingController.STUN_GRAPHIC);
			skilling.noteChatMessage("You have been stunned!", ChatMessageType.GAMEMESSAGE);
			tick(3);
		}
		assertEquals(0, shakes());
	}

	// ---------------------------------------------------------------- farming

	@Test
	public void thePatchInspectorSitsAtTheFarEdgeAndKeepsItsNotesForTheEnd()
	{
		WorldPoint min = new WorldPoint(52, 50, 0);
		WorldPoint max = new WorldPoint(54, 52, 0);
		skilling.noteClick("Rake", "Herb patch", 8150, min, max, null);
		game.animating(2273);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertEquals("just past the far edge", new WorldPoint(55, 50, 0), seat);
		assertEquals("secateurs in hand", Borrow.SECATEURS, held);

		for (int i = 0; i < 20; i++)
		{
			tick(3);
			skilling.noteXpDrop(Skill.FARMING);
		}
		assertEquals("no notes mid-session", Borrow.SECATEURS, held);

		// The compost goes on: a step back and a turn away, then the work.
		skilling.noteMenuOption("Use", "Compost -> Herb patch");
		assertEquals("one step back", 2, follower.stays.size());
		assertEquals(1, follower.stayingAt.distanceTo(seat));
		assertTrue("away from the patch", follower.stayingAt.getX() > seat.getX());
		assertEquals("the seat moved with it", follower.stayingAt, skilling.getSeat());
		tick(1);
		assertTrue("facing the other way", follower.facing.getX() > follower.stayingAt.getX());
		tick(3);
		assertEquals("then the patch again", new WorldPoint(53, 51, 0), follower.facing);

		game.at(50, 56, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertEquals("the notes, now the session is over", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		assertTrue("the feet kept for them", skilling.holdsTheFeet());
		tick(KeepingCompany.NOTE_TICKS + 4);
		assertEquals("and away", 0, held);
		assertEquals(0, follower.poseOverride);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void aPatchLostToAFightTakesNoNotes()
	{
		skilling.noteClick("Rake", "Herb patch", 8150, new WorldPoint(52, 50, 0),
			new WorldPoint(54, 52, 0), null);
		game.animating(2273);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		inCombat = true;
		tick(1);
		assertEquals(0, held);
		assertFalse(skilling.holdsTheFeet());
	}

	// ----------------------------------------------------------------- hunter

	private void layATrap(WorldPoint where)
	{
		skilling.noteAnimation(SkillingController.LAY_TRAP);
		tick(1);
		skilling.objectSpawned(9380, where, null);
	}

	@Test
	public void theTrapWatcherStandsOffTheFirstTrapLaid()
	{
		layATrap(PLAYER);
		assertFalse("no session for a trap", skilling.isWorking());
		WorldPoint seat = follower.stayingAt;
		assertNotNull("standing off the trap", seat);
		assertEquals(SkillingController.TRAP_WATCH_TILES, seat.distanceTo(PLAYER));
		assertEquals("facing it", PLAYER, follower.facing);
		assertTrue("the company's feet, not a Stay", skilling.holdsTheFeet());

		// Off to lay the next: the second trap is nobody's business.
		WorldPoint next = new WorldPoint(56, 50, 0);
		game.at(next.getX(), next.getY(), 0);
		tick(2);
		layATrap(next);
		assertEquals("the first laid, only", seat, follower.stayingAt);
		assertEquals(PLAYER, follower.facing);

		// The trap springs: a glance at the player, then back to it.
		skilling.objectSpawned(9381, PLAYER, null);
		assertEquals(next, follower.facing);
		tick(3);
		assertEquals(PLAYER, follower.facing);

		// Back within three tiles: following again.
		game.at(52, 50, 0);
		tick(1);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void theTrapWatchLetsGoAfterTwoMinutesOrAFight()
	{
		layATrap(PLAYER);
		game.at(58, 50, 0);
		tick(SkillingController.TRAP_WATCH_TICKS - 3);
		assertTrue(skilling.holdsTheFeet());
		tick(3);
		assertTrue("two minutes is the most", follower.following);

		setUp();
		layATrap(PLAYER);
		inCombat = true;
		tick(1);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void anObjectOnTheTileWithoutTheLayIsNoTrap()
	{
		skilling.objectSpawned(9380, PLAYER, null);
		tick(1);
		assertTrue(follower.following);
		skilling.noteAnimation(SkillingController.LAY_TRAP);
		tick(6);
		skilling.objectSpawned(9380, PLAYER, null);
		assertTrue("a spawn long after the lay is something else", follower.following);
	}

	// ----------------------------------------------------------- construction

	/** HUMAN_POH_BUILD. */
	private static final int HAMMER = 3676;

	@Test
	public void theForemanStandsThreeOffTheHotspotWithTheNotesOutAndNodsPerHammer()
	{
		game.instanced(true);
		WorldPoint hotspot = new WorldPoint(52, 50, 0);
		skilling.noteClick("Build", "Chair space", 15410, hotspot, null, null);
		game.animating(HAMMER);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("three tiles off the hotspot", 3, seat.distanceTo(hotspot));
		assertEquals("the notes out", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		assertEquals("about the work", hotspot, follower.facing);

		game.animating(-1);
		tick(1);
		assertEquals("a nod as the hammer cycle ends",
			Collections.singletonList(KeepingCompany.NOD), follower.animations);
		game.animating(HAMMER);
		tick(2);
		game.animating(-1);
		tick(1);
		assertEquals(2, follower.animations.size());
	}

	@Test
	public void buildingOutsideAnInstanceIsTheOrdinarySeat()
	{
		WorldPoint hotspot = new WorldPoint(52, 50, 0);
		skilling.noteClick("Build", "Chair space", 15410, hotspot, null, null);
		game.animating(HAMMER);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("the flank", 2, follower.stayingAt.distanceTo(PLAYER));
		assertEquals("the saw, not the notes", Borrow.SAW, held);
	}

	// ------------------------------------------------------------- firemaking

	private static final int FIRE_ID = 26185;

	/** The player steps west after each fire, as they do; the fire is where they stood. */
	private void lightAFire(int x)
	{
		game.at(x - 1, 50, 0);
		skilling.objectSpawned(FIRE_ID, new WorldPoint(x, 50, 0), null);
		tick(2);
	}

	@Test
	public void everyFourthFireGetsAVisitAndTheHandsWarmed()
	{
		game.placeObject(FIRE_ID, "Fire", 0, 0, 0);
		tick(1);
		for (int i = 0; i < 3; i++)
		{
			lightAFire(50 - i);
			assertTrue("fire " + (i + 1) + " is nobody's business", follower.following);
		}
		game.at(46, 50, 0);
		skilling.objectSpawned(FIRE_ID, new WorldPoint(47, 50, 0), null);
		WorldPoint seat = follower.stayingAt;
		assertNotNull("the fourth: over to it", seat);
		assertEquals("beside it", 1, seat.distanceTo(new WorldPoint(47, 50, 0)));
		assertEquals(new WorldPoint(47, 50, 0), follower.facing);
		assertTrue(skilling.holdsTheFeet());
		tick(6);
		assertEquals(Collections.singletonList(KeepingCompany.WARM_HANDS), follower.animations);
		tick(SkillingController.FIRE_WARM_TICKS);
		assertTrue("and back on foot", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void braziersAndCampfiresAreNotFires()
	{
		game.placeObject(29314, "Burning brazier", 0, 0, 0);
		game.placeObject(43475, "Forester's Campfire", 0, 0, 0);
		tick(1);
		for (int i = 0; i < 8; i++)
		{
			game.at(50 - i - 1, 50, 0);
			skilling.objectSpawned(i % 2 == 0 ? 29314 : 43475, new WorldPoint(50 - i, 50, 0), null);
			tick(2);
		}
		assertTrue(follower.following);
		assertTrue(follower.animations.isEmpty());
	}

	@Test
	public void oneFireInFiftyIsTheErrandsTooCloseGag()
	{
		int seed = 0;
		while (new java.util.Random(seed).nextInt(50) != 0)
		{
			seed++;
		}
		skilling.random.setSeed(seed);
		List<String> forced = new ArrayList<>();
		skilling.setErrands((key, silent) -> forced.add(key + (silent ? " silently" : " aloud")));
		game.placeObject(FIRE_ID, "Fire", 0, 0, 0);
		tick(1);
		for (int i = 0; i < 4; i++)
		{
			lightAFire(50 - i);
		}
		assertEquals(Collections.singletonList("firedeath aloud"), forced);
		assertTrue("the errand has the feet, not the company", follower.following);
	}

	@Test
	public void coldAtTheBrazier()
	{
		// Region 6462, the Wintertodt camp; the fake scene has no tiles out
		// here, and the seat needs none.
		game.at(1630, 3990, 0);
		WorldPoint brazier = new WorldPoint(1633, 3990, 0);
		skilling.noteClick("Feed", "Burning brazier", 29314, brazier, null, null);
		game.animating(832);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertEquals("firemaking", skilling.getSession().getActivity());
		assertEquals("three tiles off the brazier", 3, seat.distanceTo(brazier));
		assertEquals(brazier, follower.facing);

		int first = -1;
		for (int i = 1; i <= KeepingCompany.STAMP_EVERY_TICKS + 12 && first < 0; i++)
		{
			tick(1);
			if (follower.animations.contains(KeepingCompany.STAMP_FEET))
			{
				first = i;
			}
		}
		assertTrue("the feet stamped inside a minute", first >= 0);
		tick(KeepingCompany.STAMP_EVERY_TICKS + 12);
		assertTrue("and again after", Collections.frequency(follower.animations,
			KeepingCompany.STAMP_FEET) >= 2);
	}

	@Test
	public void aBrazierOutOfTheColdIsTheOrdinarySeat()
	{
		WorldPoint brazier = new WorldPoint(52, 50, 0);
		skilling.noteClick("Feed", "Burning brazier", 29314, brazier, null, null);
		game.animating(832);
		tick(1 + SkillingController.SEAT_AFTER_TICKS + 70);
		assertEquals(2, follower.stayingAt.distanceTo(PLAYER));
		assertFalse(follower.animations.contains(KeepingCompany.STAMP_FEET));
	}

	// ------------------------------------------------------------------ words

	@Test
	public void outOfRunesIsALookAtThePlayerAndAThink()
	{
		String message = "You do not have enough Fire Runes to cast this spell.";
		skilling.noteChatMessage(message, ChatMessageType.GAMEMESSAGE);
		assertEquals(Collections.singletonList(KeepingCompany.SCRATCH_HEAD), follower.animations);
		assertEquals("a glance at the player", Collections.singletonList(PLAYER), follower.glances);
		skilling.noteChatMessage(message, ChatMessageType.GAMEMESSAGE);
		assertEquals("not again inside the cooldown", 1, follower.animations.size());
		tick(SkillingController.RUNES_COOLDOWN_TICKS + 1);
		skilling.noteChatMessage(message, ChatMessageType.GAMEMESSAGE);
		assertEquals(2, follower.animations.size());

		skilling.noteChatMessage(message, ChatMessageType.PUBLICCHAT);
		assertEquals("somebody saying it in public is not the game", 2, follower.animations.size());
	}

	@Test
	public void theTaskLedgerIsApplauseThenTheScrollAndAShrugForTheNext()
	{
		tick(1);
		skilling.noteChatMessage("You've completed 26 tasks and received 20 points, giving you"
			+ " a total of 371; return to a Slayer master.", ChatMessageType.GAMEMESSAGE);
		assertEquals(Collections.singletonList(SkillingController.CLAP), follower.animations);
		assertEquals("the scroll waits for the clap", 0, held);
		tick(3);
		assertEquals(ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);
		assertTrue("the entry holds the feet", skilling.holdsTheFeet());
		tick(SkillingController.LEDGER_TICKS);
		assertEquals(0, held);
		assertEquals(0, follower.poseOverride);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());

		tick(SkillingController.LEDGER_COOLDOWN_TICKS);
		skilling.noteChatMessage("Vannaka|Your new task is to kill 45 Kalphites.",
			ChatMessageType.DIALOG);
		assertEquals(Arrays.asList(SkillingController.CLAP, KeepingCompany.SHRUG),
			follower.animations);
		tick(SkillingController.LEDGER_COOLDOWN_TICKS);
		skilling.noteChatMessage("Excellent, you're now assigned to kill Zulrah 20 times.",
			ChatMessageType.DIALOG);
		assertEquals(3, follower.animations.size());
	}

	@Test
	public void theWordsAreNothingWithTheCompanyOff()
	{
		keepsCompany = false;
		skilling.noteChatMessage("You do not have enough Fire Runes to cast this spell.",
			ChatMessageType.GAMEMESSAGE);
		skilling.noteChatMessage("You've completed your first task; return to a Slayer master.",
			ChatMessageType.GAMEMESSAGE);
		tick(5);
		assertTrue(follower.animations.isEmpty());
		assertTrue(follower.glances.isEmpty());
		assertEquals(0, held);
	}

	// ------------------------------------------------------------- the seats

	@Test
	public void theSeatVariantsKeepTheRulesEverySeatKeeps()
	{
		WorldPoint min = new WorldPoint(60, 60, 0);
		WorldPoint max = new WorldPoint(61, 61, 0);
		List<WorldPoint> reserved = Collections.singletonList(new WorldPoint(63, 60, 0));
		for (String variant : new String[]{"across", "beside", "ring"})
		{
			for (int x = 56; x <= 65; x++)
			{
				for (int y = 56; y <= 65; y++)
				{
					WorldPoint player = new WorldPoint(x, y, 0);
					if (inside(min, max, player))
					{
						continue;
					}
					WorldPoint nearest = new WorldPoint(Math.max(60, Math.min(61, x)),
						Math.max(60, Math.min(61, y)), 0);
					List<WorldPoint> seats = SkillingController.seatCandidates(player, min, max,
						reserved, variant);
					assertFalse(variant + ": no seat at all from " + player, seats.isEmpty());
					for (WorldPoint seat : seats)
					{
						assertFalse(variant + ": " + seat + " is on the thing", inside(min, max, seat));
						assertFalse(variant + ": " + seat + " is reserved", reserved.contains(seat));
						assertFalse(variant + ": " + seat + " is on the line from " + player,
							onTheLine(player, nearest, seat));
						assertTrue(variant + ": " + seat + " is underfoot", seat.distanceTo(player) >= 2);
					}
				}
			}
		}
	}

	@Test
	public void theStallWatchNeverStandsOnTheStallOrPastTheGuard()
	{
		WorldPoint player = new WorldPoint(50, 50, 0);
		WorldPoint stall = new WorldPoint(51, 50, 0);
		for (int gx = -8; gx <= 8; gx++)
		{
			for (int gy = -8; gy <= 8; gy++)
			{
				WorldPoint guard = new WorldPoint(50 + gx, 50 + gy, 0);
				for (WorldPoint seat : SkillingController.stallCandidates(player, guard, stall, stall))
				{
					assertFalse(seat + " is the stall", seat.equals(stall));
					assertFalse(seat + " is the guard", seat.equals(guard));
					assertTrue(seat + " is past the guard at " + guard,
						seat.distanceTo(player) < guard.distanceTo(player));
					assertTrue(seat + " is too near", seat.distanceTo(player) >= 2);
					assertTrue(seat + " is too far", seat.distanceTo(player) <= SkillingController.STALL_WATCH_TILES + 1);
				}
			}
		}
	}
	@Test
	public void whileAFavourIsOwedTheSeatIsTakenSooner()
	{
		skilling.setGrateful(() -> true);
		clickTheTree();
		game.animating(CHOP);
		tick(1);
		tick(SkillingController.GRATEFUL_SEAT_AFTER_TICKS);
		assertNotNull("the seat, in half the time", skilling.getSeat());
	}
}
