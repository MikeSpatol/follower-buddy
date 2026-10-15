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
import static org.junit.Assert.assertSame;
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

		/** Asked for one tile, closest approach stops on another: these. */
		final java.util.Map<WorldPoint, WorldPoint> shortOf = new java.util.HashMap<>();

		/** False while it is still coming round to face where it was told. */
		boolean turned = true;

		/** Tiles with a fence along the edge toward the work. */
		final java.util.Set<WorldPoint> fenced = new java.util.HashSet<>();

		/**
		 * Rock, as the real pathing sees it: not ground to stand on, and yet
		 * a walk to it "arrives" - closest approach stops beside it, on
		 * {@link #shortOf}, and reports success. Unlike {@link #walled}.
		 */
		final java.util.Set<WorldPoint> solid = new java.util.HashSet<>();

		@Override
		boolean walledToward(WorldPoint at, int dx, int dy)
		{
			return fenced.contains(at);
		}

		/** The far corner of a footprint it was told to face the middle of, or null. */
		WorldPoint facingMax;

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
			return at != null && (walled == null || !walled.test(at)) && !solid.contains(at);
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			stayAttempts++;
			if (target == null || (walled != null && walled.test(target)))
			{
				return false;
			}
			stayingAt = shortOf.getOrDefault(target, target);
			following = false;
			stays.add(target);
			return true;
		}

		@Override
		public WorldPoint getStayTile()
		{
			return stayingAt;
		}

		@Override
		public boolean arrivesAt(WorldPoint target)
		{
			return target != null && (walled == null || !walled.test(target))
				&& !shortOf.containsKey(target);
		}

		@Override
		public int stepsTo(WorldPoint target)
		{
			return arrivesAt(target) ? getWorldLocation().distanceTo(target) : -1;
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
		public int getPoseOverride()
		{
			return poseOverride;
		}

		@Override
		public boolean isTurned()
		{
			return turned;
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
			facingMax = null;
		}

		@Override
		public void setStayFaceArea(WorldPoint min, WorldPoint max)
		{
			facing = min;
			facingMax = max;
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

	/** The left hand's thing of a pair, the quill, or 0. */
	private int heldLeft;

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
		heldLeft = 0;
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
				heldLeft = 0;
			}

			@Override
			public void hold(int leftItemId, int rightItemId)
			{
				held = rightItemId;
				heldLeft = leftItemId;
			}

			@Override
			public void release()
			{
				held = 0;
				heldLeft = 0;
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

	/**
	 * Looks over the shoulder played so far. The peer is a one-shot, held
	 * off the seat for {@link KeepingCompany#PEER_TICKS} from the tick it plays.
	 */
	private int peers()
	{
		return Collections.frequency(follower.animations, KeepingCompany.PEER);
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

	/** The tool on loan, in the hand or set down under a gatherer's scroll. */
	private int onLoan()
	{
		return skilling.getBorrow().heldItem();
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
	public void aTreeClickedFromAcrossTheClearingStillGetsCompany()
	{
		// Release review: the click remembered the tile it was made from as
		// where the player works, so a click from more than three tiles off -
		// every return from the bank - ended the session on its first swing,
		// the player being "walked off" from a tile they had only passed.
		game.at(PLAYER.getX() - 7, PLAYER.getY(), 0);
		tick(1);
		clickTheTree();
		for (int x = PLAYER.getX() - 6; x <= PLAYER.getX(); x++)
		{
			game.at(x, PLAYER.getY(), 0);
			tick(1);
		}
		game.animating(CHOP);
		tick(1);
		assertTrue("the first swing opens the session", skilling.isWorking());
		tick(SkillingController.SEAT_AFTER_TICKS + 2);
		assertTrue("and it lasts", skilling.isWorking());
		assertNotNull("with a seat by the tree", follower.stayingAt);
		assertTrue("near where the player works, not where they clicked from",
			follower.stayingAt.distanceTo(PLAYER) <= 3);
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
		// and still obeys the rules. The ground is asked before the walk.
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
		follower.walled = null;
		tick(11);
		assertNotNull("it looks again now and then, and finds one", follower.stayingAt);
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
			assertEquals(abort + ": the axe should be borrowed by now", 6739, onLoan());
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
		assertEquals("the dragon axe the player wears", 6739, onLoan());

		setUp();
		workTheTree();
		assertEquals("a bronze one when none is worn", 1351, onLoan());

		setUp();
		borrowsTools = false;
		workTheTree();
		assertEquals("nothing with the setting off", 0, onLoan());
	}

	@Test
	public void theToolAndTheOutfitHatAreBothTaken()
	{
		// The hand and the head are different slots, so the follower looks
		// the way the player does: axe in hand, hat on. A tool earns the bow.
		game.wearing(6739, 10941, 10939, 10940, 10933);
		workTheTree();
		assertEquals("the axe on loan", 6739, onLoan());
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
	public void withNoWorkOnThereIsNoCopyOfIt()
	{
		// A swing with no tree in front of it is what the walk over is for.
		assertFalse("nothing to stand against", skilling.joinIn());
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

	/** Whether the tile touches the tree's footprint square on, as the game works it. */
	private static boolean squareOnTheTree(WorldPoint at)
	{
		boolean xIn = at.getX() >= TREE_MIN.getX() && at.getX() <= TREE_MAX.getX();
		boolean yIn = at.getY() >= TREE_MIN.getY() && at.getY() <= TREE_MAX.getY();
		return (xIn && (at.getY() == TREE_MIN.getY() - 1 || at.getY() == TREE_MAX.getY() + 1))
			|| (yIn && (at.getX() == TREE_MIN.getX() - 1 || at.getX() == TREE_MAX.getX() + 1));
	}

	@Test
	public void onlyTheTilesSquareOnToTheWorkCount()
	{
		for (int x = 50; x <= 57; x++)
		{
			for (int y = 47; y <= 54; y++)
			{
				WorldPoint at = new WorldPoint(x, y, 0);
				assertEquals(at.toString(), squareOnTheTree(at),
					SkillingController.squareOn(TREE_MIN, TREE_MAX, at));
			}
		}
		assertFalse("a corner is not", SkillingController.squareOn(TREE_MIN, TREE_MAX,
			new WorldPoint(52, 49, 0)));
		assertFalse("the trunk is not", SkillingController.squareOn(TREE_MIN, TREE_MAX,
			new WorldPoint(53, 50, 0)));
		assertTrue("the side is", SkillingController.squareOn(TREE_MIN, TREE_MAX,
			new WorldPoint(52, 51, 0)));
	}

	/**
	 * Play report: the follower chops, mines and smiths, "but not against
	 * the appropriate skilling items". The game works a tree only from a
	 * tile square on to it, facing the exact middle of it.
	 */
	@Test
	public void theCopyIsMadeFromSquareOnToTheTreeFacingItsMiddle()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue("square on, not off a corner: " + follower.stayingAt,
			squareOnTheTree(follower.stayingAt));
		assertEquals("facing the middle of all four tiles, not the one",
			TREE_MIN, follower.facing);
		assertEquals(TREE_MAX, follower.facingMax);
	}

	@Test
	public void aCornerOfTheTreeIsNeverWhereTheCopyIsMadeFrom()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		follower.walled = SkillingSessionTest::squareOnTheTree;

		assertFalse(skilling.joinIn());
		assertFalse("the corners are open, and still it does not go", skilling.joiningIn());
		assertEquals("it keeps its seat", seat, follower.stayingAt);
	}

	@Test
	public void aWalkThatStopsShortOfTheTileIsNotAJoin()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		WorldPoint open = new WorldPoint(55, 51, 0);
		for (WorldPoint c : SkillingController.ring(TREE_MIN, TREE_MAX, 1, 0))
		{
			if (!c.equals(open))
			{
				// Behind a fence: closest approach leaves it a step short.
				follower.shortOf.put(c, new WorldPoint(51, 52, 0));
			}
		}
		assertFalse(skilling.joinIn());
		assertTrue(skilling.joiningIn());
		assertEquals("the one tile it can actually reach", open, follower.stayingAt);

		skilling.dropTheJoin();
		follower.shortOf.put(open, new WorldPoint(51, 52, 0));
		assertFalse(skilling.joinIn());
		assertFalse("none it can reach, so no copy", skilling.joiningIn());
		assertEquals("and back on the seat, not left short", seat, follower.stayingAt);
	}

	@Test
	public void theCopyWaitsUntilItHasTurnedToTheWork()
	{
		workTheTree();
		follower.turned = false;
		skilling.joinIn();
		assertTrue(skilling.joiningIn());
		assertFalse("there, but still coming round", skilling.joinedIn());

		follower.turned = true;
		assertTrue(skilling.joinedIn());
	}

	@Test
	public void fromASeatAlreadySquareOnTheCopyIsMadeWhereItSits()
	{
		// An anvil worked from the west: the seat is across it, square on.
		WorldPoint anvil = new WorldPoint(53, 50, 0);
		game.at(52, 50, 0);
		skilling.noteClick("Smith", "Anvil", 2097, anvil, anvil, null);
		game.animating(898);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("across the anvil", new WorldPoint(54, 50, 0), follower.stayingAt);
		int stays = follower.stays.size();

		assertFalse("not in the same breath: the turn to it comes first", skilling.joinIn());
		assertTrue(skilling.joiningIn());
		assertEquals("without a step", stays, follower.stays.size());
		assertEquals("turned to the anvil", anvil, follower.facing);
		assertTrue("and then the copy is made from the seat", skilling.joinedIn());
	}

	@Test
	public void theAnvilsNotesStayOutThroughACopy()
	{
		// The smith's company studies its notes for the whole session; only a
		// gatherer's parked scroll is put down to join in.
		WorldPoint anvil = new WorldPoint(53, 50, 0);
		game.at(52, 50, 0);
		borrowsTools = false;
		skilling.noteClick("Smith", "Anvil", 2097, anvil, anvil, null);
		game.animating(898);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("the notes out", ErrandController.SCROLL_PROP, held);

		skilling.joinIn();
		assertEquals("and still out for the copy", ErrandController.SCROLL_PROP, held);
	}

	@Test
	public void anOvenOpenOnEverySideIsWorkedFromAnyOfThem()
	{
		// The Hosidius clay oven: one tile, open all round but for a wall.
		WorldPoint oven = new WorldPoint(53, 50, 0);
		game.at(53, 49, 0);
		skilling.noteClick("Cook", "Clay oven", 21302, oven, oven, null);
		game.animating(COOK);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		follower.fenced.add(new WorldPoint(53, 51, 0));

		skilling.joinIn();
		assertTrue(skilling.joiningIn());
		WorldPoint at = follower.stayingAt;
		assertTrue("beside it on the west or east: " + at,
			at.getY() == 50 && Math.abs(at.getX() - 53) == 1);
	}

	@Test
	public void noCopyAgainstAStump()
	{
		workTheTree();
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		assertTrue(skilling.getSession().isDepleted());
		assertFalse(skilling.joinIn());
		assertFalse("the tree is down; nothing to go over to", skilling.joiningIn());
	}

	@Test
	public void theTreeFallingEndsACopyUnderWay()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue(skilling.joiningIn());
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		tick(1);
		assertFalse(skilling.joiningIn());
	}

	@Test
	public void aMomentThatHasTheFeetKeepsThem()
	{
		workTheTree();
		skilling.setExcused(true);
		WorldPoint momentsSpot = new WorldPoint(48, 50, 0);
		follower.stayAt(momentsSpot);

		assertFalse(skilling.joinIn());
		assertFalse("no walk over in the middle of the moment", skilling.joiningIn());
		assertEquals(momentsSpot, follower.stayingAt);
	}

	@Test
	public void aMomentStartingMidCopyEndsItAndIsLeftAlone()
	{
		workTheTree();
		skilling.joinIn();
		WorldPoint joined = follower.stayingAt;
		skilling.setExcused(true);
		tick(1);
		assertFalse(skilling.joiningIn());
		assertEquals("not pulled back to the seat under the moment", joined, follower.stayingAt);
	}

	@Test
	public void standingWorkIsCopiedFromTheSeat()
	{
		// A knife on logs has nothing in the world to stand against: the
		// copy is made where the follower sits, not at the tile in front of
		// the player.
		game.animating(FLETCH);
		tick(1);
		skilling.noteXpDrop(Skill.FLETCHING);
		tick(SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		int stays = follower.stays.size();

		skilling.joinIn();
		assertTrue(skilling.joiningIn());
		assertEquals("not a step", stays, follower.stays.size());
		assertEquals(seat, follower.stayingAt);
		assertTrue(skilling.joinedIn());
	}

	@Test
	public void aTreeBehindAFenceIsNotWorkedAcrossIt()
	{
		workTheTree();
		// The west and south sides are fenced, as the Edgeville yew's are.
		for (WorldPoint c : SkillingController.ring(TREE_MIN, TREE_MAX, 1, 0))
		{
			if (c.getX() < TREE_MIN.getX() || c.getY() < TREE_MIN.getY())
			{
				follower.fenced.add(c);
			}
		}
		skilling.joinIn();
		assertTrue(skilling.joiningIn());
		WorldPoint at = follower.stayingAt;
		assertTrue("the open north or east side: " + at,
			squareOnTheTree(at) && (at.getX() > TREE_MAX.getX() || at.getY() > TREE_MAX.getY()));
	}

	/** A one-by-two range worked from the west, as the Lumbridge one is. */
	private static final WorldPoint LONG_RANGE_MIN = new WorldPoint(53, 50, 0);
	private static final WorldPoint LONG_RANGE_MAX = new WorldPoint(53, 51, 0);

	private void workTheRangeFromItsSide()
	{
		game.at(52, 50, 0);
		skilling.noteClick("Cook", "Range", 114, LONG_RANGE_MIN, LONG_RANGE_MAX, null);
		game.animating(COOK);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertNotNull(follower.stayingAt);
	}

	@Test
	public void atARangeTheCopyIsMadeFromThePlayersSide()
	{
		workTheRangeFromItsSide();
		assertEquals("the seat is round the back", new WorldPoint(54, 50, 0), follower.stayingAt);

		assertFalse("and a range is not worked from its back", skilling.joinIn());
		assertTrue(skilling.joiningIn());
		assertEquals("beside the player, the one side certain to be open",
			new WorldPoint(52, 51, 0), follower.stayingAt);
		assertEquals(LONG_RANGE_MIN, follower.facing);
		assertEquals("the middle of both tiles", LONG_RANGE_MAX, follower.facingMax);
	}

	@Test
	public void noTallyScrollMidSwing()
	{
		workTheRangeFromItsSide();
		skilling.joinIn();
		assertTrue(skilling.joinedIn());
		assertEquals("never out while it copies", -1, dropsUntilTheScroll(4, 12));

		skilling.dropTheJoin();
		assertTrue("and out again once it is back", dropsUntilTheScroll(4, 12) > 0);
	}

	@Test
	public void theOtherWordsForTheSameWorkAreWork()
	{
		for (String word : new String[]{"Chop", "Chop-down", "Cut", "Cut down", "Cut-Down"})
		{
			assertTrue(word, SkillingController.isWorkOption(word));
			assertEquals(word, "woodcutting", SkillingController.activityFor(word));
		}
		for (String word : new String[]{"Small Net", "Small net", "Big Net", "Use-rod"})
		{
			assertTrue(word, SkillingController.isWorkOption(word));
			assertEquals(word, "fishing", SkillingController.activityFor(word));
		}
		// Two spots say it, and so does every spell cast on something.
		assertFalse("a spell is not fishing", SkillingController.isWorkOption("Cast"));
	}

	@Test
	public void droppingTheCopyGoesStraightBackToTheSeat()
	{
		workTheTree();
		WorldPoint seat = follower.stayingAt;
		skilling.joinIn();
		skilling.dropTheJoin();
		assertEquals("no tick spent following in between", seat, follower.stayingAt);
		assertFalse(follower.following);
	}

	@Test
	public void movedOffTheWorkTheCopyIsOverAndTheFeetAreNotTakenBack()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue(skilling.stillJoined());

		// A moment's walk to the front, say.
		WorldPoint elsewhere = new WorldPoint(48, 50, 0);
		follower.stayAt(elsewhere);
		assertFalse("copying from somewhere else is not copying", skilling.stillJoined());
		tick(1);
		assertFalse(skilling.joiningIn());
		assertEquals("whoever moved it keeps it", elsewhere, follower.stayingAt);
	}

	@Test
	public void theCopyEndsWithTheWork()
	{
		workTheTree();
		skilling.joinIn();
		assertTrue(skilling.stillJoined());
		game.at(50, 55, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertFalse(skilling.stillJoined());
	}

	@Test
	public void overAtTheWorkTheCompanyKeepsItsHabitsToItself()
	{
		// The angler's company looks over the water now and then; not while
		// it is casting.
		workTheSpot();
		skilling.joinIn();
		assertTrue(skilling.joinedIn());
		WorldPoint at = follower.stayingAt;
		int stays = follower.stays.size();

		tick(160);
		assertTrue("the work went on", skilling.isWorking());
		assertEquals("never moved off it", at, follower.stayingAt);
		assertEquals("never sent anywhere", stays, follower.stays.size());
		assertEquals("never turned from the spot", SPOT, follower.facing);
		assertEquals(SPOT, follower.facingMax);
	}

	@Test
	public void theCopyStopsWhenTheWorkMovesOn()
	{
		workTheSpot();
		skilling.joinIn();
		assertTrue(skilling.stillJoined());

		// The spot moves two tiles up the bank and the player clicks it there.
		WorldPoint newSpot = new WorldPoint(53, 52, 0);
		NPC moved = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(moved, newSpot.getX(), newSpot.getY(), 0);
		skilling.noteClick("Net", "Fishing spot", 1530, newSpot, newSpot, moved);
		tick(1);
		assertTrue("the same session", skilling.isWorking());
		assertFalse("not casting at where the spot was", skilling.joiningIn());
	}

	@Test
	public void aCastIsNotCopiedFromTheWater()
	{
		workTheSpot();
		WorldPoint seat = follower.stayingAt;
		// A straight bank: everything from the spot east is water, so the
		// only dry tile square on to the spot is the player's own.
		follower.walled = tile -> tile.getX() >= SPOT.getX();

		assertFalse(skilling.joinIn());
		assertFalse("not from the corner of the bank either", skilling.joiningIn());
		assertEquals(seat, follower.stayingAt);
	}

	@Test
	public void theAnglerSetAndTheNetAreBothTaken()
	{
		// The report: a full angler set and a small net, and the hat only
		// sometimes. Now the net is in the hand and the hat is on, every time.
		game.wearing(13258, 13259, 13260, 13261);
		workTheSpot();
		assertEquals("the net on loan", 303, onLoan());
		assertEquals("the angler hat on", 13258, worn);
	}

	@Test
	public void theToolStaysInHandFromOneTreeToTheNext()
	{
		// One tree is not the work. Walking to the next one used to hand the
		// axe back, bow and all, and take it straight out again.
		game.wearing(6739);
		workTheTree();
		assertEquals(6739, onLoan());
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
		assertEquals(1351, onLoan());
		game.at(56, 50, 0);
		tick(2 + SkillingController.TOOL_GRACE_TICKS);
		assertEquals(0, held);
		assertTrue("a minute of chopping is not a ceremony", follower.animations.isEmpty());
	}

	@Test
	public void theStintIsTheWholeRunOfTreesNotTheLastOne()
	{
		// An hour of chopping earns its bow though no single tree lasted two
		// minutes: the stint runs from the first borrow, and the axe carried
		// to the next tree does not start it again.
		workTheTree();
		tick(SkillingController.BEAT_AFTER_TICKS / 2);
		game.at(56, 50, 0);
		tick(2);
		assertFalse("the first tree let go", skilling.isWorking());

		skilling.noteClick("Chop down", "Yew tree", TREE_ID + 2,
			new WorldPoint(58, 50, 0), null, null);
		game.animating(CHOP);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertTrue("the next tree is work", skilling.isWorking());
		tick(SkillingController.BEAT_AFTER_TICKS / 2);
		follower.animations.clear();

		game.at(62, 50, 0);
		tick(2 + SkillingController.TOOL_GRACE_TICKS);
		assertEquals("neither tree took two minutes, the two together did",
			Collections.singletonList(858), follower.animations);
		assertEquals(0, held);
	}

	@Test
	public void differentWorkSwapsTheKeptTool()
	{
		workTheTree();
		assertEquals(1351, onLoan());
		game.at(56, 50, 0);
		tick(2);
		assertEquals("kept for the moment", 1351, held);

		skilling.noteClick("Mine", "Rocks", ROCK_ID, new WorldPoint(58, 50, 0), null, null);
		game.animating(MINE);
		tick(1);
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertEquals("an axe is no good at a rock", 1265, onLoan());
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
	public void noErrandTakesTheHandFromAToolKeptBetweenTrees()
	{
		// Every errand lets go of the hand when it ends, and the documenting
		// ones take the scroll out into it: over a kept axe, that is the axe
		// gone while the loan still thinks it is held.
		workTheTree();
		assertEquals(1351, onLoan());
		game.at(56, 50, 0);
		tick(2);
		assertFalse("the session let go", skilling.isWorking());
		assertFalse("and the feet", skilling.holdsTheFeet());
		assertEquals(1351, held);
		assertTrue("but not the hand", skilling.blocksErrands());

		tick(SkillingController.TOOL_GRACE_TICKS);
		assertEquals("given back", 0, held);
		assertFalse("and the errands are free again", skilling.blocksErrands());
	}

	@Test
	public void aFellingAxeIsBorrowedAsItself()
	{
		// A short tier list read a dragon felling axe as a bronze axe, which
		// is the one thing a borrow must never get wrong.
		game.wearing(28217);
		workTheTree();
		assertEquals("the felling axe the player wears", 28217, onLoan());
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
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

	/**
	 * Play report: the follower walked between the tile at the player's
	 * side and the one behind them, and "it looks off". A gatherer's
	 * company is parked: the scroll out over the tool on loan, and left out.
	 */
	@Test
	public void aGatherersScrollIsParkedOverTheToolOnLoan()
	{
		workTheTree();
		assertEquals("the scroll in the hand", ErrandController.SCROLL_PROP, held);
		assertEquals("the axe on loan, set down for it", 1351, onLoan());
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
		for (int i = 0; i < 20; i++)
		{
			tick(4);
			skilling.noteXpDrop(Skill.WOODCUTTING);
			assertEquals("and left out, drop after drop", ErrandController.SCROLL_PROP, held);
		}
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
		assertEquals("never a step", 1, follower.stays.size());
	}

	@Test
	public void aGatheringSeatIsNeverPacedStretchedOrSatOn()
	{
		workTheRock();
		WorldPoint seat = follower.stayingAt;
		tick(KeepingCompany.STRETCH_AGAIN_TICKS + 400);
		assertTrue("the work went on", skilling.isWorking());
		assertEquals("not a step in over two hours", 1, follower.stays.size());
		assertEquals(seat, follower.stayingAt);
		assertFalse("and never sat down", skilling.isSitting());
		assertEquals("still reading", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
	}

	@Test
	public void joiningInPutsTheScrollDownAndTheToolBackInTheHand()
	{
		workTheTree();
		assertEquals(ErrandController.SCROLL_PROP, held);
		skilling.joinIn();
		assertEquals("the copy swings the axe, not the scroll", 1351, held);
		assertEquals(0, follower.poseOverride);

		skilling.dropTheJoin();
		tick(1);
		assertEquals("and back on the seat the scroll comes out again",
			ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
	}

	@Test
	public void aFullBagAtAGatheringSeatIsNoStepBankward()
	{
		game.placeObject(9030, "Bank booth", 60, 50, 0);
		workTheRock();
		game.inventoryUsing(27);
		tick(1);
		game.inventoryUsing(28);
		tick(1);
		assertEquals("the shrug", Collections.singletonList(2113), follower.animations);
		assertEquals("the scroll down for the look at the player", 1265, held);
		tick(10);
		assertEquals("but it stays parked", 1, follower.stays.size());
		assertEquals("and the scroll comes back out after the look",
			ErrandController.SCROLL_PROP, held);
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
	public void aCopyMadeOverTheSitSitsBackDownAfter()
	{
		// Standing work is copied from the seat, and the copy's pose takes the
		// sit's place. When it let go at the end of the batch the follower
		// stood for the rest of the session while the company still counted
		// it sitting: never another look over the shoulder, and a stand-up
		// played from standing before every emote.
		skilling.random.setSeed(0);
		game.animating(FLETCH);
		tick(1);
		skilling.noteXpDrop(Skill.FLETCHING);
		tick(SkillingController.SEAT_AFTER_TICKS + KeepingCompany.SIT_AFTER_TICKS + 70);
		WorldPoint seat = follower.stayingAt;
		assertTrue(skilling.isSitting());
		assertEquals("the rest pose", 10061, follower.poseOverride);

		skilling.joinIn();
		assertTrue("copied from the seat", skilling.joinedIn());
		follower.setPoseOverride(FLETCH);
		tick(5);
		// The batch over: the copy lets go of its own pose, and of the join.
		follower.clearPoseOverride(FLETCH);
		skilling.dropTheJoin();
		tick(1);
		assertEquals(seat, follower.stayingAt);
		assertTrue(skilling.isSitting());
		assertEquals("back down, as it still counts itself", 10061, follower.poseOverride);

		// One of the emote loops the mimic copies is its own while it lasts.
		follower.setPoseOverride(189);
		tick(3);
		assertEquals("not sat through", 189, follower.poseOverride);
		follower.clearPoseOverride(189);
		tick(1);
		assertEquals("and the sit after it", 10061, follower.poseOverride);
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
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

	/** The step knows the same banks the trip does, capitals and all. */
	@Test
	public void theStepBankwardFindsABankTheGameCapitalises()
	{
		game.placeObject(9030, "Bank Chest", 60, 50, 0);
		workTheRange();
		WorldPoint seat = follower.stayingAt;
		game.inventoryUsing(27);
		tick(1);
		game.inventoryUsing(28);
		tick(5);
		assertEquals("one step toward it", 1, follower.stayingAt.distanceTo(seat));
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

	/** Twenty ticks on the one seat: not a step, not a moment following. */
	private void assertParked(WorldPoint seat)
	{
		for (int i = 0; i < 20; i++)
		{
			tick(1);
			assertEquals("parked, tick " + i, seat, follower.stayingAt);
			assertTrue("and the company's, tick " + i, skilling.holdsTheFeet());
			assertFalse(follower.following);
		}
		for (WorldPoint stay : follower.stays)
		{
			assertEquals("never sent anywhere else", seat, stay);
		}
	}

	/**
	 * Play report, at Motherlode: the follower "will walk back and fourth
	 * between the tile to my side and the tile behind me". The first seat
	 * was rock; closest approach stopped behind the player and called it
	 * arriving, so the rock was written down as the seat, found not to be
	 * ground on the next tick, dropped, and taken again the tick after.
	 */
	@Test
	public void aSeatOnRockIsNeverTakenSoItIsNeverDroppedAndRetaken()
	{
		WorldPoint rock = new WorldPoint(48, 51, 0);
		follower.solid.add(rock);
		follower.shortOf.put(rock, new WorldPoint(49, 50, 0));
		workTheRock();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertNotEquals("not the rock", rock, skilling.getSeat());
		assertEquals("the seat is where it stands", seat, skilling.getSeat());
		assertParked(seat);
	}

	@Test
	public void anAnglersSeatIsNeverTakenInTheWater()
	{
		WorldPoint water = new WorldPoint(52, 49, 0);
		follower.solid.add(water);
		follower.shortOf.put(water, new WorldPoint(51, 50, 0));
		workTheSpot();
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		assertNotEquals("not the water", water, skilling.getSeat());
		assertParked(seat);
	}

	@Test
	public void aSeatThePathingOnlyGetsNearIsNoSeat()
	{
		// Good ground, but the far side of a fence: closest approach stops a
		// tile short and calls it arriving. The seat is the next one instead.
		follower.shortOf.put(new WorldPoint(48, 51, 0), new WorldPoint(49, 50, 0));
		workTheRock();
		assertEquals(new WorldPoint(48, 49, 0), skilling.getSeat());
		assertEquals("and it stands on it", skilling.getSeat(), follower.stayingAt);
	}

	@Test
	public void anywhereItCanOnlyGetNearIsNowhereToSit()
	{
		for (WorldPoint c : SkillingController.seatCandidates(PLAYER, ROCK, ROCK,
			Collections.emptyList(), "mining"))
		{
			follower.shortOf.put(c, new WorldPoint(49, 50, 0));
		}
		workTheRock();
		assertNull(skilling.getSeat());
		assertTrue("following, not left standing short", follower.following);
		assertTrue("and no tile it turned down was walked to", follower.stays.isEmpty());
	}

	@Test
	public void aSeatThatIsNoSeatForTheNextSpotIsLetGoNotLeftStanding()
	{
		workTheSpot();
		assertNotNull(follower.stayingAt);

		// The spot moves up the bank to where it is water all round, and the
		// old seat is too far back to keep.
		WorldPoint moved = new WorldPoint(53, 53, 0);
		WorldPoint player = new WorldPoint(52, 53, 0);
		follower.walled = tile -> tile.distanceTo(moved) == 1 && !tile.equals(player);
		NPC spot = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(spot, moved.getX(), moved.getY(), 0);
		skilling.noteClick("Net", "Fishing spot", 1530, moved, moved, spot);
		game.at(player.getX(), player.getY(), 0);
		tick(SkillingController.SEAT_AFTER_TICKS + 2);

		assertTrue("the same session", skilling.isWorking());
		assertNull("no seat by the new spot", skilling.getSeat());
		assertTrue("and not left standing on the old one", follower.following);
		assertNotEquals("nor reading on the move", ErrandController.SCROLL_PROP, held);
	}

	@Test
	public void aReadingPoseSomethingElseLetGoOfIsPutBack()
	{
		workTheTree();
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);

		// A stranger's peer, a held emote copied: somebody else's pose on top.
		follower.setPoseOverride(KeepingCompany.PEER);
		tick(2);
		assertEquals("theirs, while they have it", KeepingCompany.PEER, follower.poseOverride);

		// And let go of, leaving the scroll in the hand.
		follower.setPoseOverride(0);
		tick(1);
		assertEquals("reading it again", ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
	}

	@Test
	public void theNextRockFromTheSameSpotKeepsTheSeat()
	{
		workTheRock();
		WorldPoint seat = follower.stayingAt;
		assertEquals(new WorldPoint(48, 51, 0), seat);

		// A rock on the other side, clicked without a step: the old seat is a
		// fair seat for it still, though not the one it would choose fresh.
		WorldPoint next = new WorldPoint(50, 48, 0);
		skilling.noteClick("Mine", "Rocks", ROCK_ID, next, null, null);
		tick(SkillingController.SEAT_AFTER_TICKS + 2);
		assertTrue("the same session", skilling.isWorking());
		assertEquals("not a step", seat, follower.stayingAt);
		assertEquals("facing the new rock", next, follower.facing);
		assertParked(seat);
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
	public void anEmptiedRockIsThreeLooksAndNoStep()
	{
		WorldPoint next = new WorldPoint(52, 53, 0);
		game.placeObject(ROCK_ID + 1, "Rocks", next.getX(), next.getY(), 0);
		workTheRock();
		tick(KeepingCompany.ROCK_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;

		// The empty rock takes the tile. A gatherer's company stays parked.
		skilling.objectSpawned(ROCK_ID + 2, ROCK, null);
		assertEquals("no step back", 1, follower.stays.size());
		assertEquals(seat, follower.stayingAt);
		assertEquals(seat, skilling.getSeat());

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
	public void aSessionEndingMidReadStillGivesThePickaxeBack()
	{
		// The tool is on loan while it is set down under the scroll, so an end
		// that lands while the scroll is out must not lose it.
		workTheRock();
		assertEquals(ErrandController.SCROLL_PROP, held);
		assertEquals(1265, onLoan());

		inCombat = true;
		tick(1);
		assertFalse("the session let go", skilling.isWorking());
		assertEquals("and the hands are empty", 0, held);
		assertEquals("the pickaxe given back", 0, onLoan());
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
		assertEquals("no step clear of the stump: it stays parked", 1, follower.stays.size());
		assertEquals(seat, follower.stayingAt);

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
		assertEquals("HUMAN_PICKUPFLOOR, down to the nest, not a reach at table height (QA, 15 Oct)",
			827, KeepingCompany.NEST_LOOK);

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
		return walkOffToTheBank("Bank booth");
	}

	private WorldPoint walkOffToTheBank(String bank)
	{
		game.placeObject(9030, bank, BOOTH.getX(), BOOTH.getY(), 0);
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
		assertEquals("and the axe with it, long past the grace a walk to the next tree gets",
			1351, held);

		// Back within three tiles, and no work taken up: a gatherer's seat
		// waits a moment for the first swing, then lets go.
		game.animating(-1);
		game.at(50, 51, 0);
		tick(1);
		assertEquals("waiting for the swing", seat, follower.stayingAt);
		tick(SkillingController.BANK_BACK_TICKS);
		assertTrue("none came: following again", follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	/**
	 * The game capitalises a few banks its own way - "Bank Booth" at Burgh de
	 * Rott, "Bank Chest" on Fossil Island - and an exact match walked past
	 * them as if there were no bank at all.
	 */
	@Test
	public void aBankTheGameCapitalisesIsStillABank()
	{
		for (String bank : new String[]{"Bank Booth", "Bank Chest", "Bank chest"})
		{
			setUp();
			WorldPoint seat = walkOffToTheBank(bank);
			assertEquals(bank + ": the seat is kept", seat, follower.stayingAt);
			assertTrue(bank, skilling.holdsTheFeet());
		}
	}

	/**
	 * Back from the bank to the same tree: the follower used to walk off its
	 * seat to meet the player, and six ticks into the chopping walk straight
	 * back to it. The seat is carried into the new session instead.
	 */
	@Test
	public void backFromTheBankToTheSameTreeTheSeatIsNeverLeft()
	{
		WorldPoint seat = walkOffToTheBank();
		game.animating(-1);
		game.at(59, 50, 0);
		tick(10);
		bankOpen = true;
		tick(1);
		bankOpen = false;
		tick(20);

		game.at(52, 50, 0);
		tick(1);
		game.at(PLAYER.getX(), PLAYER.getY(), 0);
		clickTheTree();
		game.animating(CHOP);
		for (int i = 0; i < SkillingController.SEAT_AFTER_TICKS + 3; i++)
		{
			tick(1);
			assertTrue("at it again, tick " + i, skilling.isWorking());
			assertFalse("never let go to follow, tick " + i, follower.following);
			assertEquals("never off the seat, tick " + i, seat, follower.stayingAt);
		}
		assertEquals("the same seat", seat, skilling.getSeat());
		for (WorldPoint stay : follower.stays)
		{
			assertEquals("never sent anywhere else", seat, stay);
		}
		assertEquals("and reading again", ErrandController.SCROLL_PROP, held);
	}

	@Test
	public void aSeatBesideThePlayerIsKeptForTheNextVeinAlong()
	{
		workTheRock();
		WorldPoint seat = follower.stayingAt;
		assertEquals(new WorldPoint(48, 51, 0), seat);

		// A step along the wall to the next rock: the seat is beside them now.
		WorldPoint next = new WorldPoint(51, 52, 0);
		skilling.noteClick("Mine", "Rocks", ROCK_ID, next, null, null);
		game.at(49, 52, 0);
		tick(SkillingController.SEAT_AFTER_TICKS + 2);
		assertTrue(skilling.isWorking());
		assertEquals("not a step", seat, follower.stayingAt);
		assertParked(seat);
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
	public void goingQuietByABankIsNoTripToIt()
	{
		// A walk-off or the bank opening is the trip. The player simply
		// stopping is neither, bank or no bank, and the seat goes at once.
		game.placeObject(9030, "Bank booth", BOOTH.getX(), BOOTH.getY(), 0);
		workTheTree();
		game.animating(-1);
		tick(SkillingController.QUIET_END_TICKS + 1);
		assertFalse(skilling.isWorking());
		assertTrue("let go, not kept for a trip nobody is making", follower.following);
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

	// ---------------------------------------------------- working alongside

	private static final String[] CHOP_DOWN = {"Chop down", null, null, null, "Examine"};

	/** HUMAN_EAT: a one-shot the player plays in the middle of the work. */
	private static final int EAT = 829;

	/** The yew worked from its west side, as the game has the player do, and chopped. */
	private void chopAlongside()
	{
		game.animation(CHOP, 6);
		game.placeObject(TREE_ID, "Yew tree", CHOP_DOWN, TREE_MIN.getX(), TREE_MIN.getY(),
			TREE_MAX.getX(), TREE_MAX.getY(), 0);
		game.at(52, 50, 0);
		clickTheTree();
		game.animating(CHOP);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
	}

	/**
	 * Play request: "most of the time look like they are doing the activity
	 * with the player". With room at the tree, it chops the same tree from
	 * another side, facing its middle, with the axe.
	 */
	@Test
	public void withRoomAtTheTreeTheCompanyChopsItFromAnotherSide()
	{
		chopAlongside();
		WorldPoint at = follower.stayingAt;
		assertTrue("square on to the yew: " + at, squareOnTheTree(at));
		assertNotEquals("not the player's tile", new WorldPoint(52, 50, 0), at);
		assertEquals("facing the middle of it", TREE_MIN, follower.facing);
		assertEquals(TREE_MAX, follower.facingMax);

		tick(1);
		assertEquals("chopping", CHOP, follower.poseOverride);
		assertEquals("with the axe in the hand", 1351, held);
	}

	@Test
	public void theSwingWaitsUntilItHasTurnedToTheTree()
	{
		follower.turned = false;
		chopAlongside();
		tick(3);
		assertNotEquals("not while still coming round", CHOP, follower.poseOverride);
		follower.turned = true;
		tick(1);
		assertEquals(CHOP, follower.poseOverride);
	}

	/** "Only sometimes be tending to their scroll." */
	@Test
	public void mostOfTheTimeItWorksAndNowAndThenItReads()
	{
		chopAlongside();
		int chopping = 0;
		int breaks = 0;
		boolean reading = false;
		for (int i = 0; i < 600; i++)
		{
			tick(1);
			if (follower.poseOverride == CHOP)
			{
				chopping++;
				assertEquals("the axe in the hand while it chops", 1351, held);
			}
			boolean nowReading = follower.poseOverride == ErrandController.WRITE_SCROLL_POSE;
			if (nowReading)
			{
				assertEquals("the scroll in the hand while it reads", ErrandController.SCROLL_PROP, held);
				assertEquals("and the quill", ErrandController.QUILL_PROP, heldLeft);
			}
			if (nowReading && !reading)
			{
				breaks++;
			}
			reading = nowReading;
		}
		assertTrue("chopping most of the time: " + chopping + " of 600", chopping >= 480);
		assertTrue("with a few breaks for the scroll: " + breaks, breaks >= 3 && breaks <= 7);
		assertEquals("and never a step", 1, follower.stays.size());
	}

	@Test
	public void theNextTreeAlongIsChoppedWhenItsOwnHasNoRoom()
	{
		game.animation(CHOP, 6);
		WorldPoint own = new WorldPoint(53, 50, 0);
		WorldPoint next = new WorldPoint(53, 54, 0);
		game.placeObject(TREE_ID, "Tree", CHOP_DOWN, 53, 50, 53, 50, 0);
		game.placeObject(TREE_ID + 1, "Tree", CHOP_DOWN, 53, 54, 53, 54, 0);
		WorldPoint player = new WorldPoint(52, 50, 0);
		follower.walled = t -> t.distanceTo(own) == 1 && !t.equals(player);
		game.at(player.getX(), player.getY(), 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, own, own, null);
		game.animating(CHOP);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);

		assertEquals("the next one along", next, follower.facing);
		assertEquals(next, follower.facingMax);
		assertEquals("from beside it", 1, follower.stayingAt.distanceTo(next));
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void thePlayersTreeFallingLeavesItsOwnStanding()
	{
		game.animation(CHOP, 6);
		WorldPoint own = new WorldPoint(53, 50, 0);
		WorldPoint next = new WorldPoint(53, 54, 0);
		game.placeObject(TREE_ID, "Tree", CHOP_DOWN, 53, 50, 53, 50, 0);
		game.placeObject(TREE_ID + 1, "Tree", CHOP_DOWN, 53, 54, 53, 54, 0);
		WorldPoint player = new WorldPoint(52, 50, 0);
		follower.walled = t -> t.distanceTo(own) == 1 && !t.equals(player);
		game.at(player.getX(), player.getY(), 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, own, own, null);
		game.animating(CHOP);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals(next, follower.facing);
		tick(KeepingCompany.TIMBER_AFTER_TICKS);

		// The player's tree comes down; the follower's does not.
		game.removeObject(TREE_ID);
		skilling.objectDespawned(TREE_ID, own, own);
		assertFalse("no flinch for somebody else's tree",
			follower.animations.contains(KeepingCompany.FLINCH));
		tick(1);
		assertEquals("still chopping its own", CHOP, follower.poseOverride);
		assertEquals(next, follower.facing);
	}

	@Test
	public void aStumpScenerOrAnotherKindIsNoNeighbour()
	{
		game.animation(CHOP, 6);
		WorldPoint own = new WorldPoint(53, 50, 0);
		game.placeObject(TREE_ID, "Tree", CHOP_DOWN, 53, 50, 53, 50, 0);
		game.placeObject(TREE_ID + 1, "Tree stump", new String[]{null, null, null, null, "Examine"},
			53, 54, 53, 54, 0);
		game.placeObject(TREE_ID + 2, "Tree", new String[]{null, null, null, null, "Examine"},
			55, 52, 55, 52, 0);
		game.placeObject(TREE_ID + 3, "Oak tree", CHOP_DOWN, 50, 54, 50, 54, 0);
		WorldPoint player = new WorldPoint(52, 50, 0);
		follower.walled = t -> t.distanceTo(own) == 1 && !t.equals(player);
		game.at(player.getX(), player.getY(), 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, own, own, null);
		game.animating(CHOP);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);

		assertNotEquals("nothing of its own to chop", CHOP, follower.poseOverride);
		assertEquals("so it reads, parked", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
	}

	/**
	 * Review finding: a plain seat's scroll carried onto a work tile was never
	 * put away, and the follower read at its tree for the rest of the session.
	 */
	@Test
	public void aScrollFromAPlainSeatIsPutAwayForTheWork()
	{
		game.animation(CHOP, 6);
		WorldPoint own = new WorldPoint(53, 50, 0);
		game.placeObject(TREE_ID, "Tree", CHOP_DOWN, 53, 50, 53, 50, 0);
		WorldPoint player = new WorldPoint(52, 50, 0);
		follower.walled = t -> t.distanceTo(own) == 1 && !t.equals(player);
		game.at(player.getX(), player.getY(), 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, own, own, null);
		game.animating(CHOP);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("nothing to work: a plain seat, reading",
			ErrandController.SCROLL_PROP, held);

		// The next tree along, clicked and chopped from its south side.
		WorldPoint next = new WorldPoint(50, 53, 0);
		game.placeObject(TREE_ID + 1, "Tree", CHOP_DOWN, 50, 53, 50, 53, 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID + 1, next, next, null);
		game.at(50, 52, 0);
		tick(3 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("working it now", CHOP, follower.poseOverride);
		assertEquals("with the axe, not the scroll", 1351, held);
		assertEquals(next, follower.facing);
	}

	/**
	 * Review finding: the player clicking the follower's tree and stepping onto
	 * its tile shared the tile for six ticks, the retarget's own re-seat
	 * holding back the move.
	 */
	@Test
	public void thePlayerClickingItsTreeAndSteppingOntoItsTileMovesItAtOnce()
	{
		game.animation(CHOP, 6);
		WorldPoint own = new WorldPoint(53, 50, 0);
		WorldPoint next = new WorldPoint(53, 53, 0);
		game.placeObject(TREE_ID, "Tree", CHOP_DOWN, 53, 50, 53, 50, 0);
		game.placeObject(TREE_ID + 1, "Tree", CHOP_DOWN, 53, 53, 53, 53, 0);
		WorldPoint player = new WorldPoint(52, 50, 0);
		follower.walled = t -> t.distanceTo(own) == 1 && !t.equals(player);
		game.at(player.getX(), player.getY(), 0);
		skilling.noteClick("Chop down", "Tree", TREE_ID, own, own, null);
		game.animating(CHOP);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint at = follower.stayingAt;
		assertEquals("chopping the next tree along", next, follower.facing);

		skilling.noteClick("Chop down", "Tree", TREE_ID + 1, next, next, null);
		game.at(at.getX(), at.getY(), 0);
		tick(2);
		assertNotEquals("off the player's tile", at, follower.stayingAt);
		assertEquals("to another side of the same tree", 1, follower.stayingAt.distanceTo(next));
	}

	@Test
	public void aClickOnTheNextPatchSendsItThere()
	{
		String[] rake = {"Rake", "Inspect", null, "Guide", null};
		game.placeObject(8150, "Herb patch", rake, 53, 50, 54, 51, 0);
		game.placeObject(8152, "Herb patch", rake, 53, 53, 54, 54, 0);
		game.at(52, 50, 0);
		skilling.noteClick("Rake", "Herb patch", 8150, new WorldPoint(53, 50, 0),
			new WorldPoint(54, 51, 0), null);
		game.animating(2273);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals(new WorldPoint(53, 50, 0), follower.facing);

		WorldPoint min = new WorldPoint(53, 53, 0);
		WorldPoint max = new WorldPoint(54, 54, 0);
		skilling.noteClick("Rake", "Herb patch", 8152, min, max, null);
		game.at(52, 53, 0);
		tick(3 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("raking the patch the player is at", min, follower.facing);
		assertTrue(SkillingController.squareOn(min, max, follower.stayingAt));
		assertEquals(2273, follower.poseOverride);
	}

	/**
	 * Review finding: the compost's turn-away was queued by the menu option
	 * and wiped a moment later by the same click's retarget.
	 */
	@Test
	public void theCompostTurnAwaySurvivesTheClickThatPutsItOn()
	{
		WorldPoint min = new WorldPoint(53, 50, 0);
		WorldPoint max = new WorldPoint(54, 51, 0);
		game.placeObject(8150, "Herb patch", new String[]{"Rake", "Inspect", null, "Guide", null},
			53, 50, 54, 51, 0);
		game.at(52, 50, 0);
		skilling.noteClick("Rake", "Herb patch", 8150, min, max, null);
		game.animating(2273);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;

		// The plugin's own order: the option first, then the click.
		skilling.noteMenuOption("Use", "Compost -> Herb patch");
		skilling.noteClick("Use", "Herb patch", 8150, min, max, null);
		tick(1);
		assertTrue("facing away from the patch",
			follower.facing.distanceTo(min) > seat.distanceTo(min));
	}

	/**
	 * Review finding: a net at a spot where the player had harpooned kept the
	 * harpoon spots and the harpoon.
	 */
	@Test
	public void aNetWhereTheyHarpoonedIsNewWorkWithItsOwnTool()
	{
		game.animation(618, 4);
		game.animation(621, 44);
		game.npcActions(1530, "Cage", "Harpoon");
		game.npcActions(1531, "Net", "Bait");
		game.at(BANK_SIDE.getX(), BANK_SIDE.getY(), 0);
		NPC harpoonSpot = game.spawnNpc(1530, "Fishing spot", 0);
		game.moveNpc(harpoonSpot, SPOT.getX(), SPOT.getY(), 0);
		skilling.noteClick("Harpoon", "Fishing spot", 1530, SPOT, SPOT, harpoonSpot);
		game.animating(618);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("harpooning", 618, follower.poseOverride);
		assertEquals(311, held);

		WorldPoint netAt = new WorldPoint(53, 52, 0);
		NPC netSpot = game.spawnNpc(1531, "Fishing spot", 0);
		game.moveNpc(netSpot, netAt.getX(), netAt.getY(), 0);
		skilling.noteClick("Net", "Fishing spot", 1531, netAt, netAt, netSpot);
		game.at(52, 52, 0);
		game.animating(621);
		tick(3 + SkillingController.SEAT_AFTER_TICKS);
		assertEquals("netting", 621, follower.poseOverride);
		assertEquals("with the net", Borrow.SMALL_NET, held);
		assertEquals("at the net spot", netAt, follower.facing);
	}

	@Test
	public void aVeinIsWorkedFromTheTileAcrossItsWall()
	{
		WorldPoint vein = new WorldPoint(53, 52, 0);
		assertEquals(new WorldPoint(52, 52, 0), SkillingController.acrossTheWall(vein, 1));
		assertEquals(new WorldPoint(53, 53, 0), SkillingController.acrossTheWall(vein, 2));
		assertEquals(new WorldPoint(54, 52, 0), SkillingController.acrossTheWall(vein, 4));
		assertEquals(new WorldPoint(53, 51, 0), SkillingController.acrossTheWall(vein, 8));
		assertNull("a corner is not worked across", SkillingController.acrossTheWall(vein, 3));
	}

	@Test
	public void onlyALiveOneOfTheSameKindCounts()
	{
		String[] mine = {"Mine", null, null, null, "Examine"};
		assertTrue(SkillingController.liveKind("Ore vein", mine, "Ore vein", "Mine"));
		assertFalse("emptied", SkillingController.liveKind("Depleted vein",
			new String[]{null, null, null, null, "Examine"}, "Ore vein", "Mine"));
		assertFalse("an emptied rock keeps its Mine",
			SkillingController.liveKind("Rocks", mine, "Rocks", "Mine"));
		assertFalse("scenery", SkillingController.liveKind("Tree",
			new String[]{null, null, null, null, "Examine"}, "Tree", "Chop down"));
		assertFalse("another kind", SkillingController.liveKind("Oak tree", CHOP_DOWN,
			"Yew tree", "Chop down"));
		assertTrue("the option read with its tags off",
			SkillingController.offers(new String[]{"<col=ff9040>Net</col>"}, "Net"));
	}

	@Test
	public void twoTilesOffThePlayerIsBestAndBesideThemIsLast()
	{
		WorldPoint player = new WorldPoint(50, 50, 0);
		int two = SkillingController.alongsideRank(new WorldPoint(52, 50, 0), player);
		int three = SkillingController.alongsideRank(new WorldPoint(53, 50, 0), player);
		int beside = SkillingController.alongsideRank(new WorldPoint(51, 50, 0), player);
		assertTrue(two < three);
		assertTrue(three < beside);
	}

	@Test
	public void atWorkThereIsNoWalkOverToJoinIn()
	{
		chopAlongside();
		tick(1);
		int stays = follower.stays.size();
		assertFalse(skilling.joinIn());
		assertFalse(skilling.joiningIn());
		assertEquals("working already: nothing to walk over for", stays, follower.stays.size());
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void thePlayerStoppingStopsTheSwingAndStartingAgainStartsIt()
	{
		chopAlongside();
		tick(1);
		assertEquals(CHOP, follower.poseOverride);
		game.animating(-1);
		tick(KeepingCompany.STOP_AFTER_TICKS + 1);
		assertNotEquals("the player stood still: so does it", CHOP, follower.poseOverride);
		game.animating(CHOP);
		tick(2);
		assertEquals("and back at it with them", CHOP, follower.poseOverride);
	}

	@Test
	public void somebodyElsesPoseIsWaitedOutAndTheSwingComesBack()
	{
		chopAlongside();
		tick(1);
		follower.setPoseOverride(KeepingCompany.PEER);
		tick(3);
		assertEquals("theirs while they have it", KeepingCompany.PEER, follower.poseOverride);
		follower.setPoseOverride(0);
		tick(1);
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void aMomentTakesTheSwingAndItComesBackAfter()
	{
		chopAlongside();
		tick(1);
		skilling.setExcused(true);
		assertEquals("the moment has it", 0, follower.poseOverride);
		tick(3);
		skilling.setExcused(false);
		tick(2);
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void whenItsTreeFallsItStopsAndWithNoMoreItReadsWhereItIs()
	{
		chopAlongside();
		tick(1);
		WorldPoint at = follower.stayingAt;
		game.removeObject(TREE_ID);
		skilling.objectDespawned(TREE_ID, TREE_MIN, TREE_MAX);
		tick(1);
		assertNotEquals("the swing stops", CHOP, follower.poseOverride);

		tick(SkillingController.WORK_GONE_TICKS + 2);
		assertEquals("no more yews: it stays where it is", at, follower.stayingAt);
		assertEquals("and reads", ErrandController.SCROLL_PROP, held);
		assertEquals(1, follower.stays.size());
	}

	@Test
	public void aTreeGoneWithoutAWordIsNotWorkedAgain()
	{
		// A tree that changes state without an event - a multiloc turning
		// over - is gone all the same; its tiles are not offered again.
		chopAlongside();
		tick(1);
		game.removeObject(TREE_ID);
		tick(SkillingController.WORK_GONE_TICKS + 3);
		assertNotEquals(CHOP, follower.poseOverride);
		assertEquals("it reads where it is instead", ErrandController.SCROLL_PROP, held);
	}

	@Test
	public void aBiteToEatIsNotTakenForTheWork()
	{
		game.animation(EAT, -1);
		chopAlongside();
		tick(1);
		game.animating(EAT);
		tick(1);
		assertEquals("still chopping, not eating", CHOP, follower.poseOverride);
		game.animating(CHOP);
		tick(1);
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void withoutToolsToBorrowItReadsAsBefore()
	{
		borrowsTools = false;
		chopAlongside();
		tick(3);
		assertEquals(ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
	}

	@Test
	public void thePlayerTakingItsTileMovesItOver()
	{
		chopAlongside();
		tick(1);
		WorldPoint at = follower.stayingAt;
		game.at(at.getX(), at.getY(), 0);
		tick(2);
		assertNotEquals("off the player's new tile", at, follower.stayingAt);
		assertTrue("to another side of the same yew", squareOnTheTree(follower.stayingAt));
	}

	@Test
	public void somebodyElseStandingAtTheTreeKeepsTheirTile()
	{
		chopAlongside();
		WorldPoint first = follower.stayingAt;
		assertTrue(squareOnTheTree(first));

		// The same yew with another player on the side it chose.
		setUp();
		game.at(52, 50, 0);
		game.spawnPlayer(first.getX() - 52, first.getY() - 50);
		chopAlongside();
		tick(1);
		assertNotEquals("their tile is theirs", first, follower.stayingAt);
		assertTrue("another side of the same yew", squareOnTheTree(follower.stayingAt));
		assertEquals(CHOP, follower.poseOverride);
	}

	@Test
	public void theAnglerNetsTheSpotFromAnotherSideWithTheNet()
	{
		game.animation(621, 44);
		game.npcActions(1530, "Net", "Bait");
		workTheSpot();
		tick(1);
		WorldPoint at = follower.stayingAt;
		assertEquals("beside the spot, square on", 1, at.distanceTo(SPOT));
		assertTrue(at.getX() == SPOT.getX() || at.getY() == SPOT.getY());
		assertNotEquals(BANK_SIDE, at);
		assertEquals("facing it", SPOT, follower.facing);
		assertEquals("netting", 621, follower.poseOverride);
		assertEquals("with the small net", Borrow.SMALL_NET, held);
	}

	@Test
	public void aSpotThatOffersSomethingElseIsNoNeighbour()
	{
		game.animation(621, 44);
		game.npcActions(1530, "Net", "Bait");
		game.npcActions(1531, "Cage", "Harpoon");
		NPC other = game.spawnNpc(1531, "Fishing spot", 0);
		game.moveNpc(other, 53, 53, 0);
		// No room at the player's own spot.
		follower.walled = t -> t.distanceTo(SPOT) == 1 && !t.equals(BANK_SIDE);
		workTheSpot();
		tick(1);
		assertNotEquals("a cage spot is not a net spot", 621, follower.poseOverride);
		assertNotEquals("nor sent to it", new WorldPoint(53, 53, 0), follower.facing);
		assertTrue(follower.stays.isEmpty());
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);

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
	public void aTallyDueMidLookWaitsRatherThanSpoilingIt()
	{
		// The look over the shoulder and the tally both come at random. On
		// about one draw in forty a drop's tally fell on the very tick of the
		// look, took the scroll out in front of the player and read it for the
		// whole look, so the look was never seen. The first three hundred
		// draws, each its own seed, all get their look, with the scroll away.
		java.util.List<Integer> missed = new java.util.ArrayList<>();
		for (int seed = 0; seed < 300; seed++)
		{
			setUp();
			skilling.random.setSeed(seed);
			game.animating(FLETCH);
			tick(1);
			skilling.noteXpDrop(Skill.FLETCHING);
			tick(SkillingController.SEAT_AFTER_TICKS);
			boolean looked = false;
			for (int i = 1; i <= KeepingCompany.SHOULDER_AFTER_TICKS + 130 && !looked; i++)
			{
				int peers = peers();
				tick(1);
				if (i % 3 == 0)
				{
					skilling.noteXpDrop(Skill.FLETCHING);
				}
				looked = peers() > peers && held != ErrandController.SCROLL_PROP;
			}
			if (!looked)
			{
				missed.add(seed);
			}
		}
		assertTrue("seeds whose look never came: " + missed, missed.isEmpty());
	}

	@Test
	public void fletchingOpensOnItsFirstDropAndEarnsALookOverTheShoulder()
	{
		// The look and the notes around it are rolled; seeded, the window
		// below is measured rather than lucky.
		skilling.random.setSeed(7);
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
			if (peers() > 0)
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
	public void aWalkDoesNotSetOffWhileALookOverTheShoulderIsUnderWay()
	{
		// The pace and the look over the shoulder both come at random. On a
		// few draws in a hundred the pace fell due while the follower was off
		// its seat peering, and the walk set off from the player's front tile
		// with the peer still held. The walks now wait for a peer under way
		// to be over: late, never lost. The first three hundred draws, each
		// its own seed: no leg of a walk is asked for while a peer is held,
		// every draw still paces, and some draws really did have the pace
		// waiting on a peer - without those this would prove nothing.
		//
		// The other order too: a peer could begin on the very tick a walk
		// did (seed 245), and the walk's first leg followed it a tick later.
		// A look now waits for a walk just begun as well.
		List<Integer> spoiled = new ArrayList<>();
		List<Integer> unpaced = new ArrayList<>();
		int waited = 0;
		for (int seed = 0; seed < 300; seed++)
		{
			setUp();
			skilling.random.setSeed(seed);
			game.animating(FLETCH);
			tick(1);
			skilling.noteXpDrop(Skill.FLETCHING);
			tick(SkillingController.SEAT_AFTER_TICKS);
			WorldPoint seat = follower.stayingAt;
			int peerHeld = 0;
			int peerOver = -1;
			int peerStart = -1;
			int paced = -1;
			for (int i = 1; i <= KeepingCompany.PACE_AFTER_TICKS + 80 && paced < 0; i++)
			{
				int asked = follower.stays.size();
				int peers = peers();
				tick(1);
				if (peers() > peers)
				{
					peerStart = i;
				}
				boolean peering = peerStart >= 0 && i - peerStart < KeepingCompany.PEER_TICKS;
				if (i % 3 == 0)
				{
					skilling.noteXpDrop(Skill.FLETCHING);
				}
				for (WorldPoint stay : follower.stays.subList(asked, follower.stays.size()))
				{
					if (!stay.equals(seat))
					{
						paced = i;
					}
				}
				if (paced == i && peerHeld >= 1)
				{
					spoiled.add(seed);
				}
				if (peerHeld > 0 && !peering)
				{
					peerOver = i;
				}
				peerHeld = peering ? peerHeld + 1 : 0;
			}
			if (paced < 0)
			{
				unpaced.add(seed);
			}
			else if (peerOver >= 0 && paced - peerOver <= 3)
			{
				waited++;
			}
		}
		assertTrue("a walk set off mid-peer on seeds " + spoiled, spoiled.isEmpty());
		assertTrue("the pace is late, never lost; none on seeds " + unpaced, unpaced.isEmpty());
		assertTrue("no draw had the pace waiting on a peer, so nothing was proved", waited > 0);
	}

	@Test
	public void theHoursStretchIsNotWalkedPeeringOverTheShoulder()
	{
		// The sit keeps the look waiting a tick at a time, so by the hour it
		// is long overdue, and the stretch stands the follower up on the very
		// tick the look is free to go. It went, to the player's front tile,
		// and the half circle was walked from there with the peer held at
		// every leg. Not a chance draw: every hour's stretch after a sit.
		skilling.random.setSeed(0);
		game.animating(FLETCH);
		tick(1);
		skilling.noteXpDrop(Skill.FLETCHING);
		tick(SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		boolean sat = false;
		List<Integer> spoiled = new ArrayList<>();
		int legs = 0;
		int lastLeg = -1;
		int lastPeer = -1;
		int peerStart = -1;
		for (int i = 1; i <= KeepingCompany.STRETCH_AFTER_TICKS + 350; i++)
		{
			boolean peering = peerStart >= 0 && i - 1 - peerStart < KeepingCompany.PEER_TICKS;
			int asked = follower.stays.size();
			int peers = peers();
			tick(1);
			if (peers() > peers)
			{
				peerStart = i;
			}
			if (i % 3 == 0)
			{
				skilling.noteXpDrop(Skill.FLETCHING);
			}
			sat |= skilling.isSitting();
			for (WorldPoint stay : follower.stays.subList(asked, follower.stays.size()))
			{
				if (!stay.equals(seat))
				{
					legs++;
					lastLeg = i;
					if (peering)
					{
						spoiled.add(i);
					}
				}
			}
			if (peerStart >= 0 && i - peerStart < KeepingCompany.PEER_TICKS)
			{
				lastPeer = i;
			}
		}
		assertTrue("it sat before the hour, which is what holds the look", sat);
		assertEquals("the pace out, then the half circle", 4, legs);
		assertTrue("legs walked with the peer held, at ticks " + spoiled, spoiled.isEmpty());
		assertTrue("the look is late, never lost", lastPeer > lastLeg);
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
	public void aPatchIsWorkedAlongsideWithTheToolTheWorkWants()
	{
		// A herb patch, two by two as the game has it, raked from its west side.
		WorldPoint min = new WorldPoint(53, 50, 0);
		WorldPoint max = new WorldPoint(54, 51, 0);
		game.placeObject(8150, "Herb patch", new String[]{"Rake", "Inspect", null, "Guide", null},
			53, 50, 54, 51, 0);
		game.at(52, 50, 0);
		skilling.noteClick("Rake", "Herb patch", 8150, min, max, null);
		game.animating(2273);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		assertTrue("beside the patch, square on: " + seat,
			SkillingController.squareOn(min, max, seat));
		assertNotEquals(new WorldPoint(52, 50, 0), seat);
		assertEquals("facing its middle", min, follower.facing);
		assertEquals(max, follower.facingMax);
		assertEquals("raking", 2273, follower.poseOverride);
		assertEquals("with the rake", 5341, held);

		// Picking: the hands empty for it.
		game.animating(2282);
		tick(2);
		assertEquals("picking", 2282, follower.poseOverride);
		assertEquals("with bare hands", 0, held);

		// The compost goes on: a turn away where it stands, and no step.
		skilling.noteMenuOption("Use", "Compost -> Herb patch");
		tick(1);
		assertEquals("no step", 1, follower.stays.size());
		assertTrue("facing away from the patch",
			follower.facing.distanceTo(min) > seat.distanceTo(min));

		game.at(50, 56, 0);
		tick(1);
		assertFalse(skilling.isWorking());
		assertEquals("the notes, now the session is over", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
		assertTrue("the feet kept for them", skilling.holdsTheFeet());
		tick(KeepingCompany.NOTE_TICKS + 4);
		assertEquals("and away", 0, held);
		assertEquals(0, follower.poseOverride);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void aGrownPatchIsAPatchByWhatItIsNotByItsName()
	{
		// A patch grown into its crop is named for the crop.
		WorldPoint min = new WorldPoint(53, 50, 0);
		WorldPoint max = new WorldPoint(54, 51, 0);
		game.placeObject(8151, "Ranarr weed", new String[]{"Harvest", "Inspect", null, "Guide", null},
			53, 50, 54, 51, 0);
		game.multiloc(8151);
		game.at(52, 50, 0);
		skilling.noteClick("Harvest", "Ranarr weed", 8151, min, max, null);
		game.animating(2282);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertTrue(SkillingController.squareOn(min, max, follower.stayingAt));
		assertEquals("picking alongside", 2282, follower.poseOverride);
		tick(KeepingCompany.SIT_AFTER_TICKS + 100);
		assertFalse("and a patch is never sat at", skilling.isSitting());
		assertEquals("nor walked from", 1, follower.stays.size());
	}

	@Test
	public void aHarvestAndItsCompostAreOneVisit()
	{
		WorldPoint min = new WorldPoint(53, 50, 0);
		WorldPoint max = new WorldPoint(54, 51, 0);
		game.placeObject(8151, "Ranarr weed", new String[]{"Harvest", "Inspect", null, "Guide", null},
			53, 50, 54, 51, 0);
		game.multiloc(8151);
		game.at(52, 50, 0);
		skilling.noteClick("Harvest", "Ranarr weed", 8151, min, max, null);
		game.animating(2282);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		Object visit = skilling.getSession();
		WorldPoint seat = follower.stayingAt;

		// Picked clean, the same patch is a herb patch again, and the compost goes on it.
		skilling.noteClick("Use", "Herb patch", 8151, min, max, null);
		game.animating(2283);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		assertSame("the same visit", visit, skilling.getSession());
		assertEquals("the patch's name as it is now", "Herb patch", skilling.getSession().getTargetName());
		assertEquals("and the same place to work it from", seat, follower.stayingAt);
		assertEquals("pouring", 2283, follower.poseOverride);
		assertEquals("from the bucket", 1925, held);
	}

	@Test
	public void aFullBagAtAPatchIsNoStepBankward()
	{
		game.placeObject(9030, "Bank booth", 60, 50, 0);
		WorldPoint min = new WorldPoint(53, 50, 0);
		WorldPoint max = new WorldPoint(54, 51, 0);
		game.placeObject(8151, "Ranarr weed", new String[]{"Harvest", "Inspect", null, "Guide", null},
			53, 50, 54, 51, 0);
		game.multiloc(8151);
		game.at(52, 50, 0);
		skilling.noteClick("Harvest", "Ranarr weed", 8151, min, max, null);
		game.animating(2282);
		tick(2 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = follower.stayingAt;
		game.inventoryUsing(27);
		tick(1);
		game.inventoryUsing(28);
		tick(10);
		assertEquals("it stays at the patch", seat, follower.stayingAt);
		assertEquals(1, follower.stays.size());
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

		// Back among the traps: the run goes on, from the same seat.
		game.at(52, 50, 0);
		tick(1);
		assertEquals(seat, follower.stayingAt);
		assertTrue(skilling.holdsTheFeet());

		// Off more than ten tiles from its first trap: the run is over.
		game.at(PLAYER.getX() + SkillingController.TRAP_RUN_TILES + 1, 50, 0);
		tick(1);
		assertTrue(follower.following);
		assertFalse(skilling.holdsTheFeet());
	}

	/**
	 * Play request: "park farming patches and trap runs too". One seat by the
	 * first trap for the whole run, however many go down, mostly watching and
	 * now and then the notes.
	 */
	@Test
	public void aTrapRunIsOneSeatWithTheNotesNowAndThen()
	{
		skilling.random.setSeed(7);
		layATrap(PLAYER);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		boolean read = false;
		for (int i = 0; i < 6; i++)
		{
			WorldPoint next = new WorldPoint(PLAYER.getX() + 2 + i % 3, 47 + i, 0);
			game.at(next.getX(), next.getY(), 0);
			for (int t = 0; t < 60; t++)
			{
				if (t == 20)
				{
					layATrap(next);
				}
				tick(1);
				read |= held == ErrandController.SCROLL_PROP
					&& follower.poseOverride == ErrandController.WRITE_SCROLL_POSE;
			}
			skilling.noteXpDrop(Skill.HUNTER);
		}
		assertEquals("one seat for the whole run", seat, follower.stayingAt);
		assertEquals("and no other walk", 1, follower.stays.size());
		assertTrue("the notes came out at some point", read);
		assertTrue(skilling.holdsTheFeet());
	}

	@Test
	public void aTrapLaidWhereItStandsMovesItOnceStillByTheFirst()
	{
		layATrap(PLAYER);
		WorldPoint seat = follower.stayingAt;
		game.at(seat.getX(), seat.getY(), 0);
		tick(2);
		layATrap(seat);
		WorldPoint moved = follower.stayingAt;
		assertNotEquals("off the new trap", seat, moved);
		assertEquals("still watching the first", SkillingController.TRAP_WATCH_TILES,
			moved.distanceTo(PLAYER));
		assertEquals("the nearest tile, not across the traps", 1, moved.distanceTo(seat));
		assertEquals(2, follower.stays.size());
	}

	@Test
	public void aCatchKeepsTheRunGoing()
	{
		layATrap(PLAYER);
		WorldPoint seat = follower.stayingAt;
		for (int i = 0; i < 3; i++)
		{
			tick(SkillingController.TRAP_WATCH_TICKS - 50);
			skilling.noteXpDrop(Skill.HUNTER);
		}
		assertTrue("catches coming in, and nothing new laid: still the run",
			skilling.holdsTheFeet());
		assertEquals(seat, follower.stayingAt);
	}

	@Test
	public void aTrapIsFoundWhereItWasLaidNotWhereThePlayerSteppedTo()
	{
		skilling.noteAnimation(SkillingController.LAY_TRAP);
		tick(1);
		// The player steps off before the trap turns up.
		game.at(PLAYER.getX() - 1, PLAYER.getY(), 0);
		skilling.objectSpawned(9380, PLAYER, null);
		assertTrue("a trap run all the same", skilling.holdsTheFeet());
		assertEquals(SkillingController.TRAP_WATCH_TILES, follower.stayingAt.distanceTo(PLAYER));
	}

	@Test
	public void aTrapUnderfootMovesItToTheNearestTileNotTheFirstInLine()
	{
		// Only the north-east corner of the watch ring is open ground.
		follower.walled = t -> t.distanceTo(PLAYER) == SkillingController.TRAP_WATCH_TILES
			&& !(t.getX() >= 51 && t.getY() >= 51);
		layATrap(PLAYER);
		WorldPoint seat = follower.stayingAt;
		assertEquals(new WorldPoint(51, 52, 0), seat);
		game.at(seat.getX(), seat.getY(), 0);
		tick(2);
		layATrap(seat);
		assertEquals("the tile next to it, not the next in the list",
			new WorldPoint(52, 52, 0), follower.stayingAt);
	}

	@Test
	public void aMomentPutsTheTrapNotesAway()
	{
		layATrap(PLAYER);
		boolean read = false;
		for (int t = 0; t < KeepingCompany.WORK_STRETCH_TICKS + KeepingCompany.WORK_STRETCH_SPREAD + 2
			&& !read; t++)
		{
			tick(1);
			read = held == ErrandController.SCROLL_PROP;
		}
		assertTrue(read);
		skilling.setExcused(true);
		tick(1);
		assertEquals("not reading through the moment", 0, held);
		assertEquals(0, follower.poseOverride);
		tick(KeepingCompany.WORK_STRETCH_TICKS * 2);
		assertEquals("nor taking them out during it", 0, held);
	}

	@Test
	public void theNotesGoAwayWithTheRun()
	{
		layATrap(PLAYER);
		boolean read = false;
		for (int t = 0; t < KeepingCompany.WORK_STRETCH_TICKS + KeepingCompany.WORK_STRETCH_SPREAD + 2
			&& !read; t++)
		{
			tick(1);
			read = held == ErrandController.SCROLL_PROP;
		}
		assertTrue("reading", read);
		inCombat = true;
		tick(1);
		assertEquals("put away with the run", 0, held);
		assertEquals(0, follower.poseOverride);
		assertFalse(skilling.holdsTheFeet());
	}

	@Test
	public void aTrapIsNotWatchedFromRock()
	{
		// The same ground question as the seat's, before the walk: a watch
		// from a rock the pathing stopped beside was dropped the next tick.
		WorldPoint rock = new WorldPoint(48, 48, 0);
		follower.solid.add(rock);
		follower.shortOf.put(rock, new WorldPoint(49, 49, 0));
		layATrap(PLAYER);
		WorldPoint seat = follower.stayingAt;
		assertNotNull(seat);
		tick(3);
		assertTrue("still watching it", skilling.holdsTheFeet());
		assertEquals(seat, follower.stayingAt);
		assertNotEquals(rock, seat);
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
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

	@Test
	public void aHandBusyAtTheSeatIsAskedAgainOnTheNextDrop()
	{
		// The scroll out for something else when the seat is taken is not the
		// saw refused. A refusal is remembered for the session; a busy hand
		// only until the next drop.
		WorldPoint hotspot = new WorldPoint(52, 50, 0);
		skilling.noteClick("Build", "Chair space", 15410, hotspot, null, null);
		game.animating(HAMMER);
		tick(1);
		held = ErrandController.SCROLL_PROP;
		tick(SkillingController.SEAT_AFTER_TICKS);
		assertNotNull(follower.stayingAt);
		assertEquals("no saw over somebody else's scroll", ErrandController.SCROLL_PROP, held);

		held = 0;
		skilling.noteXpDrop(Skill.CONSTRUCTION);
		assertEquals("the hand free again, and the saw in it", Borrow.SAW, held);
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
	public void aLinePickedUpAgainAfterABreakCountsFromOne()
	{
		// The count is of one line of fires. Three, a break of over a minute,
		// and the next is the first of a new line rather than the fourth.
		game.placeObject(FIRE_ID, "Fire", 0, 0, 0);
		tick(1);
		for (int i = 0; i < 3; i++)
		{
			lightAFire(50 - i);
		}
		tick(120);
		for (int i = 3; i < 6; i++)
		{
			lightAFire(50 - i);
			assertTrue("fire " + (i - 2) + " of the new line is nobody's business",
				follower.following);
		}
		game.at(43, 50, 0);
		skilling.objectSpawned(FIRE_ID, new WorldPoint(44, 50, 0), null);
		assertNotNull("its fourth: over to it", follower.stayingAt);
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
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.poseOverride);
		assertEquals("the quill with it", ErrandController.QUILL_PROP, heldLeft);
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
