package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.sim.FakeGame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The moments, driven tick by tick: a level clapped or walked over for,
 * the pet visited, the second wind, the stranger peered at - and, for each,
 * the feet handed back afterwards, the seat excused and retaken, and
 * nothing at all when the company is switched off.
 */
public class MomentsTest
{
	/** Records what the follower was asked to do; does none of it. */
	private static final class RecordingFollower extends FollowerEntity
	{
		boolean spawned = true;
		WorldPoint stayingAt;
		WorldPoint facing;
		boolean following = true;
		int poseOverride;
		int movedToFront;
		final List<Integer> animations = new ArrayList<>();
		final List<WorldPoint> stays = new ArrayList<>();
		final List<WorldPoint> glances = new ArrayList<>();

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

		/** Every tile is ground here; no test in this class blocks any. */
		@Override
		boolean standable(WorldPoint at)
		{
			return at != null;
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			if (target == null)
			{
				return false;
			}
			stayingAt = target;
			following = false;
			stays.add(target);
			return true;
		}

		@Override
		public WorldPoint getStayTile()
		{
			return stayingAt;
		}

		/** Every walk arrives here, as stayAt does. */
		@Override
		public boolean arrivesAt(WorldPoint target)
		{
			return target != null;
		}

		@Override
		public int getPoseOverride()
		{
			return poseOverride;
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

		@Override
		public boolean moveToFront(net.runelite.api.Player local)
		{
			movedToFront++;
			stayingAt = null;
			return true;
		}
	}

	private static final WorldPoint PLAYER = new WorldPoint(50, 50, 0);
	private static final WorldPoint TREE = new WorldPoint(53, 50, 0);

	/** HUMAN_WOODCUTTING_BRONZE_AXE. */
	private static final int CHOP = 879;

	private FakeGame game;
	private RecordingFollower follower;
	private SkillingController skilling;
	private Moments moments;
	private boolean inCombat;
	private boolean feetTaken;
	private boolean keepsCompany = true;
	private int daysKnown = 30;
	private int tick;
	private PropFlourish flourish;
	private int held;
	private boolean grateful;

	@Before
	public void setUp()
	{
		game = new FakeGame().at(PLAYER.getX(), PLAYER.getY(), 0);
		follower = new RecordingFollower();
		FollowerConfig config = new FollowerConfig()
		{
			@Override
			public boolean keepsCompany()
			{
				return keepsCompany;
			}

			@Override
			public boolean borrowsTools()
			{
				return false;
			}
		};
		skilling = new SkillingController(game.client, follower, config,
			new ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
				}

				@Override
				public void release()
				{
				}
			}, event -> { }, () -> inCombat, () -> feetTaken, () -> false);
		ErrandController.Hands hands = new ErrandController.Hands()
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
		};
		flourish = new PropFlourish(follower, hands);
		moments = new Moments(game.client, follower, config, skilling,
			() -> feetTaken, () -> inCombat, () -> daysKnown, flourish, () -> grateful);
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			skilling.tick();
			flourish.tick();
			moments.tick();
		}
	}

	/** A woodcutting session with the seat taken. */
	private void takeTheSeat()
	{
		skilling.noteClick("Chop down", "Tree", 1276, TREE, null, null);
		game.animating(CHOP);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		assertNotNull("the seat was never taken", skilling.getSeat());
		assertEquals(skilling.getSeat(), follower.stayingAt);
	}

	// --------------------------------------------------------------- level up

	@Test
	public void twoLevelsClapInPlaceAndTheThirdIsWalkedOverFor()
	{
		tick(1);
		moments.levelUp(41);
		moments.levelUp(42);
		assertEquals(Arrays.asList(Moments.CLAP, Moments.CLAP), follower.animations);
		assertEquals("in place", 0, follower.movedToFront);
		assertEquals("facing the player for it", 2, follower.glances.size());
		assertFalse("a clap in place holds nothing", moments.holdsTheFeet());

		follower.animations.clear();
		moments.levelUp(43);
		assertEquals(1, follower.movedToFront);
		assertTrue("the walk is a moment of its own", moments.holdsTheFeet());
		assertTrue("the clap waits for the arrival", follower.animations.isEmpty());

		tick(3);
		assertEquals("clapped on arrival", Collections.singletonList(Moments.CLAP),
			follower.animations);
		tick(4);
		assertFalse("and the feet are handed back after it", moments.holdsTheFeet());
	}

	@Test
	public void theFormalEraBowsInstead()
	{
		daysKnown = 1;
		tick(1);
		moments.levelUp(20);
		assertEquals(Collections.singletonList(Moments.BOW), follower.animations);

		daysKnown = 3;
		follower.animations.clear();
		moments.levelUp(21);
		assertEquals(Collections.singletonList(Moments.CLAP), follower.animations);
	}

	@Test
	public void ninetyNineGetsTheSendOffABossDoes()
	{
		tick(1);
		moments.levelUp(99);
		assertEquals(Arrays.asList(862, 866, 2109), follower.animations);
	}

	@Test
	public void theWalkIsSkippedWhenSomebodyElseHasTheFeet()
	{
		tick(1);
		feetTaken = true;
		moments.levelUp(10);
		moments.levelUp(11);
		moments.levelUp(12);
		assertEquals("the third claps in place instead", 0, follower.movedToFront);
		assertEquals(3, follower.animations.size());
		assertFalse(moments.holdsTheFeet());
	}

	@Test
	public void theSeatIsExcusedForTheWalkAndRetakenAfter()
	{
		takeTheSeat();
		WorldPoint seat = skilling.getSeat();
		int stays = follower.stays.size();

		moments.levelUp(50);
		moments.levelUp(51);
		moments.levelUp(52);
		assertEquals(1, follower.movedToFront);
		assertNull("off the seat", follower.stayingAt);
		tick(1);
		assertEquals("the seat is not retaken under the walk", stays, follower.stays.size());
		assertTrue("but it is still the company's, so no Stay line", skilling.holdsTheFeet());

		tick(6);
		assertFalse(moments.holdsTheFeet());
		tick(1);
		assertEquals("the seat is retaken", seat, follower.stayingAt);
		assertEquals(seat, follower.stays.get(follower.stays.size() - 1));
	}

	// -------------------------------------------------------------------- pet

	private void petDropped()
	{
		moments.noteChatMessage("You have a funny feeling like you're being followed.",
			ChatMessageType.GAMEMESSAGE);
	}

	private NPC petAt(int x, int y)
	{
		NPC pet = game.spawnNpc(6001, "Beaver", 0, true);
		game.moveNpc(pet, x, y, 0);
		moments.npcSpawned(pet);
		return pet;
	}

	@Test
	public void thePetGetsAVisitAndTheWanderKeepsCloseAfter()
	{
		tick(1);
		petDropped();
		NPC pet = petAt(52, 50);
		assertNotNull("walked over to it", follower.stayingAt);
		assertEquals("a tile beside it", 1, follower.stayingAt.distanceTo(new WorldPoint(52, 50, 0)));
		assertEquals("facing it", new WorldPoint(52, 50, 0), follower.facing);
		assertTrue(moments.holdsTheFeet());
		assertTrue(moments.keepsClose());

		tick(5);
		assertEquals("a bend to look at it", Collections.singletonList(Moments.BEND),
			follower.animations);
		tick(3);
		assertTrue("and back", follower.following);
		assertFalse(moments.holdsTheFeet());
		assertTrue(moments.keepsClose());
		tick(Moments.PET_CLOSE_TICKS);
		assertFalse("ten minutes of it", moments.keepsClose());
		assertNotNull(pet);
	}

	@Test
	public void thePetMayTurnUpJustBeforeItsMessage()
	{
		tick(1);
		petAt(51, 51);
		tick(1);
		petDropped();
		assertNotNull("the spawn a tick before the message is the pet", follower.stayingAt);
	}

	@Test
	public void aFollowerTooLateIsSomebodyElsesPet()
	{
		tick(1);
		petDropped();
		tick(Moments.PET_HUNT_TICKS + 1);
		petAt(52, 50);
		assertNull(follower.stayingAt);
		assertTrue(follower.following);
		assertFalse("no pet, no keeping close", moments.keepsClose());
	}

	@Test
	public void theVisitEndsWithThePetGoingOrSomebodyTakingTheFeet()
	{
		tick(1);
		petDropped();
		NPC pet = petAt(52, 50);
		moments.npcDespawned(pet);
		assertTrue("nothing left to visit", follower.following);
		assertFalse(moments.holdsTheFeet());

		tick(Moments.PET_CLOSE_TICKS + 1);
		petDropped();
		petAt(52, 50);
		assertTrue(moments.holdsTheFeet());
		feetTaken = true;
		tick(1);
		assertFalse("the spectator took the feet", moments.holdsTheFeet());
		assertNotNull("and they are not released from under it", follower.stayingAt);
	}

	// ------------------------------------------------------------ second wind

	private void running(boolean on)
	{
		game.varp(VarPlayerID.OPTION_RUN, on ? 1 : 0);
	}

	@Test
	public void runGoingOffOutOfBreathSendsItAheadToLookBackOncePerStretch()
	{
		running(true);
		tick(1);
		game.energy(500);
		tick(1);
		running(false);
		tick(1);
		assertEquals("ahead of the player", 1, follower.movedToFront);
		assertTrue(moments.holdsTheFeet());
		tick(6);
		assertFalse("and back behind", moments.holdsTheFeet());

		// The same stretch: run back on and off again is not a second wind.
		running(true);
		tick(1);
		running(false);
		tick(1);
		assertEquals(1, follower.movedToFront);

		// Energy back over the line re-arms it, but the ten minutes have not passed.
		game.energy(5000);
		running(true);
		tick(1);
		game.energy(500);
		running(false);
		tick(1);
		assertEquals(1, follower.movedToFront);
		tick(Moments.WIND_COOLDOWN_TICKS);
		running(true);
		tick(1);
		running(false);
		tick(1);
		assertEquals("a new stretch, ten minutes on", 2, follower.movedToFront);
	}

	@Test
	public void runGoingOffWithEnergyToSpareIsNothing()
	{
		running(true);
		tick(1);
		game.energy(5000);
		running(false);
		tick(1);
		assertEquals(0, follower.movedToFront);
	}

	// ------------------------------------------------------------ who are you

	private NPC strangerAt(int x, int y, int combatLevel, boolean pet)
	{
		NPC npc = game.spawnNpc(7001, "Genie", combatLevel, pet);
		game.moveNpc(npc, x, y, 0);
		moments.npcSpawned(npc);
		return npc;
	}

	@Test
	public void aNewcomerThatTurnsToThePlayerIsPeeredAtFromAStepOff()
	{
		tick(1);
		NPC genie = strangerAt(52, 50, 0, false);
		tick(1);
		assertNull("it has not shown its business yet", follower.stayingAt);

		game.npcFacingPlayer(genie);
		tick(1);
		assertEquals("one step away from it", new WorldPoint(48, 48, 0), follower.stayingAt);
		assertEquals("facing it", new WorldPoint(52, 50, 0), follower.facing);
		assertEquals("the peer, held", Moments.PEER, follower.poseOverride);
		assertTrue(moments.holdsTheFeet());

		game.moveNpc(genie, 51, 49, 0);
		tick(1);
		assertEquals("the face follows it", new WorldPoint(51, 49, 0), follower.facing);

		moments.npcDespawned(genie);
		assertEquals("the peer ends with it", 0, follower.poseOverride);
		assertTrue(follower.following);
		assertFalse(moments.holdsTheFeet());
	}

	@Test
	public void aPetAGuardOrANewcomerWithOtherBusinessIsNoStranger()
	{
		tick(1);
		NPC pet = strangerAt(52, 50, 0, true);
		game.npcFacingPlayer(pet);
		tick(1);
		assertNull("a pet catching up is not a stranger", follower.stayingAt);

		NPC guard = strangerAt(52, 50, 21, false);
		game.npcFacingPlayer(guard);
		tick(1);
		assertNull("something with a combat level is the ward's business", follower.stayingAt);

		NPC passerby = strangerAt(52, 50, 0, false);
		tick(Moments.STRANGER_WINDOW_TICKS + 1);
		game.npcFacingPlayer(passerby);
		tick(1);
		assertNull("too late to have come for the player", follower.stayingAt);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void aStrangerDuringTheSeatExcusesItAndTheSeatComesBack()
	{
		takeTheSeat();
		WorldPoint seat = skilling.getSeat();
		NPC genie = strangerAt(48, 52, 0, false);
		game.npcFacingPlayer(genie);
		tick(1);
		assertEquals(Moments.PEER, follower.poseOverride);
		assertFalse("off the seat, a step from it", seat.equals(follower.stayingAt));
		tick(3);
		assertFalse("not dragged back while it talks", seat.equals(follower.stayingAt));

		moments.npcDespawned(genie);
		tick(1);
		assertEquals("the seat retaken once it has gone", seat, follower.stayingAt);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void aMomentDoesNotGetItUpFromTheLongHaulSit()
	{
		// A cook's seat: a gatherer's stays parked with the scroll and never sits.
		skilling.noteClick("Cook", "Range", 9020, TREE, null, null);
		game.animating(896);
		tick(1 + SkillingController.SEAT_AFTER_TICKS);
		WorldPoint seat = skilling.getSeat();
		assertNotNull("the seat was never taken", seat);
		tick(KeepingCompany.SIT_AFTER_TICKS + 70);
		assertTrue(skilling.isSitting());
		int sit = follower.poseOverride;
		assertTrue(sit > 0);
		follower.animations.clear();

		// The third level, walked over for on its feet, is clapped from the sit.
		moments.levelUp(60);
		moments.levelUp(61);
		moments.levelUp(62);
		assertEquals("no walk from a sit", 0, follower.movedToFront);
		assertFalse(moments.holdsTheFeet());
		assertEquals(Arrays.asList(KeepingCompany.STAND_UP, Moments.CLAP,
			KeepingCompany.STAND_UP, Moments.CLAP, KeepingCompany.STAND_UP, Moments.CLAP),
			follower.animations);
		assertEquals("and the sit is still the seat's", sit, follower.poseOverride);
		assertEquals(seat, follower.stayingAt);

		// A stranger is left to talk; the peer would sit it down where it stepped.
		NPC genie = strangerAt(48, 52, 0, false);
		game.npcFacingPlayer(genie);
		tick(1);
		assertEquals(sit, follower.poseOverride);
		assertEquals(seat, follower.stayingAt);
		assertFalse(moments.holdsTheFeet());
		assertTrue(skilling.isSitting());
	}

	// ------------------------------------------------------------------- off

	@Test
	public void nothingHappensWithTheCompanyOff()
	{
		keepsCompany = false;
		running(true);
		tick(1);
		moments.levelUp(50);
		moments.levelUp(51);
		moments.levelUp(52);
		petDropped();
		petAt(52, 50);
		NPC genie = strangerAt(52, 50, 0, false);
		game.npcFacingPlayer(genie);
		game.energy(500);
		running(false);
		tick(3);

		assertTrue(follower.animations.isEmpty());
		assertEquals(0, follower.movedToFront);
		assertNull(follower.stayingAt);
		assertEquals(0, follower.poseOverride);
		assertFalse(moments.holdsTheFeet());
		assertFalse(moments.keepsClose());
	}

	@Test
	public void aResetLetsGoOfEverything()
	{
		tick(1);
		NPC genie = strangerAt(52, 50, 0, false);
		game.npcFacingPlayer(genie);
		tick(1);
		assertEquals(Moments.PEER, follower.poseOverride);

		moments.reset();
		assertEquals(0, follower.poseOverride);
		assertFalse(moments.holdsTheFeet());
	}
	// --------------------------------------------------------------- the news

	private static final WorldPoint DROP = new WorldPoint(54, 50, 0);

	@Test
	public void aBigDropIsWalkedOverToAndWrittenUpOnTheSpot()
	{
		tick(1);
		moments.noteworthy(Moments.Tier.BIG, DROP);
		assertNotNull("walked over", follower.stayingAt);
		assertEquals("to a tile beside it", 1, follower.stayingAt.distanceTo(DROP));
		assertEquals("facing it", DROP, follower.facing);
		assertTrue(moments.holdsTheFeet());
		assertTrue(moments.isLooking());
		assertEquals("nothing in hand on the way", 0, held);

		tick(follower.stayingAt.distanceTo(new WorldPoint(49, 49, 0)) + 2);
		assertEquals("the scroll out on arrival", ErrandController.SCROLL_PROP, held);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.poseOverride);

		tick(Moments.NOTES_ON_THE_SPOT_TICKS - 1);
		assertTrue("the look up at the player", follower.glances.contains(PLAYER));
		assertTrue("and the nod", follower.animations.contains(Moments.NOD));

		tick(2);
		assertFalse("the feet handed back", moments.holdsTheFeet());
		assertEquals("the scroll put away", 0, held);
		assertEquals(0, follower.poseOverride);
	}

	@Test
	public void aNiceDropTakesTheNotesOutWhereItStands()
	{
		tick(1);
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		assertFalse("no walk for a medium one", moments.isLooking());
		assertEquals("the scroll out at once", ErrandController.SCROLL_PROP, held);
		assertTrue("facing the drop", follower.glances.contains(DROP));

		tick(Moments.NOTES_TICKS - 1);
		assertTrue("the look up", follower.glances.contains(PLAYER));
		assertTrue(follower.animations.contains(Moments.NOD));
		tick(2);
		assertEquals("put away", 0, held);
	}

	@Test
	public void somethingInTheBagIsWrittenUpFacingThePlayer()
	{
		tick(1);
		moments.noteworthy(Moments.Tier.MEDIUM, null);
		assertEquals(ErrandController.SCROLL_PROP, held);
		assertTrue(follower.glances.contains(PLAYER));
	}

	@Test
	public void aSmallDropGetsAGlanceAndTheLedgerStopsTheNextOne()
	{
		tick(1);
		moments.noteworthy(Moments.Tier.SMALL, DROP);
		assertEquals(Collections.singletonList(DROP), follower.glances);
		assertEquals("no scroll for a small one", 0, held);

		moments.noteworthy(Moments.Tier.SMALL, DROP);
		assertEquals("not twice inside the glance's cooldown", 1, follower.glances.size());

		tick(Gestures.Gesture.GLANCE.cooldownTicks + 1);
		moments.noteworthy(Moments.Tier.SMALL, DROP);
		assertEquals(2, follower.glances.size());
	}

	@Test
	public void theNotesDoNotComeOutTwiceInAStretch()
	{
		tick(1);
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		assertEquals(ErrandController.SCROLL_PROP, held);
		tick(Moments.NOTES_TICKS + 2);
		assertEquals(0, held);

		follower.glances.clear();
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		assertEquals("the tier below stands in: a glance", Collections.singletonList(DROP),
			follower.glances);
		assertEquals(0, held);

		// And a big one, with the notes spent, is not walked over for either.
		moments.noteworthy(Moments.Tier.BIG, DROP);
		assertFalse(moments.isLooking());
	}

	@Test
	public void goodwillHalvesEveryWait()
	{
		tick(1);
		grateful = true;
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		tick(Moments.NOTES_TICKS + 2);
		tick(Gestures.Gesture.NOTES.cooldownTicks / 2 + 1);
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		assertEquals("half the cooldown is enough while it owes you one",
			ErrandController.SCROLL_PROP, held);
	}

	@Test
	public void withTheHandsFullTheWriteUpIsANod()
	{
		tick(1);
		held = 1351;
		moments.noteworthy(Moments.Tier.MEDIUM, DROP);
		assertEquals("the axe stays", 1351, held);
		assertEquals("the nod on its own", Collections.singletonList(Moments.NOD),
			follower.animations);
	}

	@Test
	public void theFarewellIsAWaveAndNotTwiceInFiveMinutes()
	{
		tick(1);
		moments.farewell();
		assertEquals(Collections.singletonList(Moments.WAVE), follower.animations);
		assertTrue("facing the player for it", follower.glances.contains(PLAYER));

		moments.farewell();
		assertEquals("the tab left open is not a second goodbye", 1, follower.animations.size());

		tick(Gestures.Gesture.WAVE.cooldownTicks + 1);
		moments.farewell();
		assertEquals(2, follower.animations.size());
	}

	@Test
	public void theLadderMatchesTheLootRules()
	{
		assertNull(Moments.tierFor(9_999));
		assertEquals(Moments.Tier.SMALL, Moments.tierFor(10_000));
		assertEquals(Moments.Tier.MEDIUM, Moments.tierFor(100_000));
		assertEquals(Moments.Tier.BIG, Moments.tierFor(1_000_000));
	}

	@Test
	public void nothingIsWrittenUpWithTheCompanySwitchedOff()
	{
		keepsCompany = false;
		tick(1);
		moments.noteworthy(Moments.Tier.BIG, DROP);
		moments.noteworthy(Moments.Tier.SMALL, DROP);
		assertEquals(0, held);
		assertTrue(follower.glances.isEmpty());
		assertNull(follower.stayingAt);
	}
}
