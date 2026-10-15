package com.follower.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The beat before yes: a Send with a misgiving in it stops at the edge and
 * looks back first, and a parked follower walked straight at steps aside
 * at the last tick, not before.
 */
public class WillTest
{
	/** Records stays; every tile is ground, every step legal. */
	private static final class ParkedFollower extends FollowerEntity
	{
		WorldPoint tile = new WorldPoint(3200, 3400, 0);
		WorldPoint stayingAt;
		WorldPoint facing;
		final List<WorldPoint> stays = new ArrayList<>();
		boolean settled = true;

		ParkedFollower()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return tile;
		}

		@Override
		public boolean isStaying()
		{
			return stayingAt != null;
		}

		@Override
		public WorldPoint getStayTile()
		{
			return stayingAt;
		}

		@Override
		public boolean isSettled()
		{
			return settled;
		}

		@Override
		boolean standable(WorldPoint at)
		{
			return at != null;
		}

		@Override
		boolean canStep(WorldPoint from, int dx, int dy)
		{
			return dx != 0 || dy != 0;
		}

		@Override
		public boolean furnished(WorldPoint at)
		{
			return false;
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			if (target == null)
			{
				return false;
			}
			stayingAt = target;
			tile = target;
			stays.add(target);
			return true;
		}

		@Override
		public void setStayFaceTile(WorldPoint at)
		{
			facing = at;
		}

		@Override
		public void resumeFollowing()
		{
			stayingAt = null;
		}
	}

	private FakeGame game;
	private ParkedFollower follower;
	private Will will;
	private final List<TriggerEvent> events = new ArrayList<>();
	private boolean plainlyParked = true;
	private int tick;

	@Before
	public void setUp()
	{
		game = new FakeGame().at(3200, 3401, 0);
		follower = new ParkedFollower();
		will = new Will(game.client, follower, events::add, () -> plainlyParked);
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			will.tick();
		}
	}

	// -------------------------------------------------------------- the send

	@Test
	public void aPlainSendSimplyGoes()
	{
		WorldPoint there = new WorldPoint(3205, 3405, 0);
		assertTrue(will.send(there));
		assertEquals(there, follower.stayingAt);
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
	}

	@Test
	public void aSendAcrossTheDitchStopsAtTheEdgeAndStaysThere()
	{
		game.at(3200, 3515, 0);
		follower.tile = new WorldPoint(3200, 3514, 0);
		WorldPoint over = new WorldPoint(3200, 3530, 0);
		tick(1);
		assertTrue(will.send(over));
		assertEquals("the row south of the ditch", new WorldPoint(3200, Will.DITCH_SOUTH_Y, 0),
			follower.stayingAt);
		assertEquals("turned to the player", new WorldPoint(3200, 3515, 0), follower.facing);
		assertTrue(will.isHesitating());
		assertEquals(1, events.size());
		assertEquals("wilderness", events.get(0).getPlaceholders().get("misgiving"));
		assertEquals("the Wilderness", events.get(0).getPlaceholders().get("thing"));

		tick(Will.HESITATE_TICKS + 8);
		assertFalse(will.isHesitating());
		assertEquals("no going on across the ditch", 1, follower.stays.size());
		assertNull("and the face tile is let go", follower.facing);
	}

	@Test
	public void fromInsideTheWildernessThereIsNoEdgeToStopAt()
	{
		game.at(3200, 3540, 0);
		follower.tile = new WorldPoint(3200, 3539, 0);
		WorldPoint deeper = new WorldPoint(3200, 3560, 0);
		assertTrue(will.send(deeper));
		assertEquals(deeper, follower.stayingAt);
		assertTrue(events.isEmpty());
	}

	@Test
	public void aSendBesideSomethingThatOutclassesThePlayerPausesThenGoes()
	{
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		WorldPoint beside = new WorldPoint(3209, 3401, 0);
		tick(1);
		assertTrue(will.send(beside));
		assertEquals("three short of it, on the follower's side",
			new WorldPoint(3206, 3400, 0), follower.stayingAt);
		assertTrue(will.isHesitating());
		assertEquals("monster", events.get(0).getPlaceholders().get("misgiving"));
		assertEquals("Hill Giant", events.get(0).getPlaceholders().get("thing"));

		tick(6 + Will.HESITATE_TICKS + 1);
		assertFalse(will.isHesitating());
		assertEquals("then it goes", beside, follower.stayingAt);

		// The same monster does not earn a second pause.
		events.clear();
		assertTrue(will.send(new WorldPoint(3211, 3401, 0)));
		assertTrue(events.isEmpty());
		assertEquals(new WorldPoint(3211, 3401, 0), follower.stayingAt);
	}

	@Test
	public void theMonsterAlreadyBeingFoughtIsNoMisgiving()
	{
		// Sent beside the thing the player is fighting: that fight is on
		// already, and a beat of reluctance about it now is a follower
		// dithering in the middle of it.
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		game.fighting(giant);
		tick(1);
		WorldPoint beside = new WorldPoint(3209, 3401, 0);
		assertTrue(will.send(beside));
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
		assertEquals("straight there", beside, follower.stayingAt);
	}

	@Test
	public void aMonsterWithNoNameIsNoMisgiving()
	{
		// QA, 15 Oct: a nameless monster filled the line with "that" -
		// "Next to the that?" With nothing to name, it simply goes.
		NPC nameless = game.spawnNpc(2098, null, 28);
		game.moveNpc(nameless, 3210, 3401, 0);
		tick(1);
		WorldPoint beside = new WorldPoint(3209, 3401, 0);
		assertTrue(will.send(beside));
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
		assertEquals("straight there", beside, follower.stayingAt);
	}

	@Test
	public void somethingThePlayerCanHandleIsNoMisgiving()
	{
		NPC goblin = game.spawnNpc(3029, "Goblin", 2);
		game.moveNpc(goblin, 3210, 3401, 0);
		assertTrue(will.send(new WorldPoint(3209, 3401, 0)));
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
	}

	@Test
	public void aFollowReleasesThePause()
	{
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		tick(1);
		will.send(new WorldPoint(3209, 3401, 0));
		follower.resumeFollowing();
		tick(2);
		assertFalse(will.isHesitating());
		assertEquals("and it does not set off again on its own", 1, follower.stays.size());
	}

	@Test
	public void aSendEndsPastAMonsterButAtTheDitch()
	{
		// What a park taken again after something else let go walks to. The
		// pause short of a monster is only a pause; the edge of the ditch is
		// where that Send ends, and nothing may walk it on across.
		WorldPoint there = new WorldPoint(3205, 3405, 0);
		assertTrue(will.send(there));
		assertEquals("a plain Send ends where it went", there, will.endsAt());

		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		tick(1);
		WorldPoint beside = new WorldPoint(3209, 3401, 0);
		assertTrue(will.send(beside));
		assertTrue(will.isHesitating());
		assertEquals("past the pause", beside, will.endsAt());

		game.at(3200, 3515, 0);
		follower.tile = new WorldPoint(3200, 3514, 0);
		assertTrue(will.send(new WorldPoint(3200, 3530, 0)));
		assertTrue(will.isHesitating());
		assertEquals("at the edge", new WorldPoint(3200, Will.DITCH_SOUTH_Y, 0), will.endsAt());
	}

	@Test
	public void theWildernessIsTheSurfaceNorthOfTheDitch()
	{
		assertTrue(Will.isWilderness(new WorldPoint(3100, 3600, 0)));
		assertFalse(Will.isWilderness(new WorldPoint(3100, 3519, 0)));
		assertFalse(Will.isWilderness(new WorldPoint(3100, 3600, 1)));
		assertFalse(Will.isWilderness(new WorldPoint(2000, 3600, 0)));
		assertNull(Will.edgeShortOf(new WorldPoint(10, 10, 0), new WorldPoint(12, 10, 0), 3));
		assertEquals(new WorldPoint(7, 13, 0),
			Will.edgeShortOf(new WorldPoint(10, 10, 0), new WorldPoint(0, 20, 0), 3));
	}

	// ------------------------------------------------------- the force-path

	@Test
	public void aForcedSendHesitatesOverAnyNpcBesideItsSpot()
	{
		// A goblin is nothing to the player, so the tick would wait for a
		// strong monster to happen to stand by the spot.
		NPC goblin = game.spawnNpc(3029, "Goblin", 2);
		game.moveNpc(goblin, 3210, 3401, 0);
		tick(1);
		will.forceHesitate();
		WorldPoint beside = new WorldPoint(3209, 3401, 0);
		assertTrue(will.send(beside));
		assertEquals("the real edge, three short", new WorldPoint(3206, 3400, 0), follower.stayingAt);
		assertTrue(will.isHesitating());
		assertEquals("monster", events.get(0).getPlaceholders().get("misgiving"));
		assertEquals("named, as the line needs", "Goblin", events.get(0).getPlaceholders().get("thing"));

		tick(6 + Will.HESITATE_TICKS + 1);
		assertEquals("then it goes, as it would", beside, follower.stayingAt);

		// Spent by that Send: the next is a plain one again. From far enough
		// off to have an edge, or there is nothing to hesitate at either way.
		follower.tile = new WorldPoint(3200, 3400, 0);
		events.clear();
		assertTrue(will.send(new WorldPoint(3211, 3401, 0)));
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
	}

	@Test
	public void aForcedSendHesitatesAgainOverOneItHesitatedOverBefore()
	{
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		tick(1);
		assertTrue(will.send(new WorldPoint(3209, 3401, 0)));
		tick(6 + Will.HESITATE_TICKS + 1);
		follower.tile = new WorldPoint(3200, 3400, 0);
		events.clear();

		will.forceHesitate();
		assertTrue(will.send(new WorldPoint(3211, 3401, 0)));
		assertTrue("the once-per-monster list does not hold a force", will.isHesitating());
		assertEquals(1, events.size());
	}

	@Test
	public void aForcedSendKeepsTheFightAndTheScene()
	{
		// The thing being fought is still no misgiving, forced or not, and a
		// hop or a logout drops the force with the rest of the scene.
		NPC giant = game.spawnNpc(2098, "Hill Giant", 28);
		game.moveNpc(giant, 3210, 3401, 0);
		game.fighting(giant);
		tick(1);
		will.forceHesitate();
		assertTrue(will.send(new WorldPoint(3209, 3401, 0)));
		assertFalse(will.isHesitating());

		NPC goblin = game.spawnNpc(3029, "Goblin", 2);
		game.moveNpc(goblin, 3190, 3401, 0);
		will.forceHesitate();
		will.reset();
		assertTrue(will.send(new WorldPoint(3191, 3401, 0)));
		assertFalse(will.isHesitating());
		assertTrue(events.isEmpty());
	}

	// -------------------------------------------------------- the step aside

	private void parkAt(int x, int y)
	{
		follower.tile = new WorldPoint(x, y, 0);
		follower.stayingAt = follower.tile;
		follower.stays.clear();
	}

	@Test
	public void walkedStraightAtItStepsAsideAtTheLastTickWithAWord()
	{
		parkAt(3200, 3404);
		game.at(3200, 3400, 0);
		tick(1);
		game.at(3200, 3401, 0);
		tick(1);
		game.at(3200, 3402, 0);
		tick(1);
		assertTrue("two tiles off is not the last tick", follower.stays.isEmpty());
		game.at(3200, 3403, 0);
		tick(1);
		assertEquals("one to the side of the line", 1, follower.stays.size());
		WorldPoint aside = follower.stays.get(0);
		assertEquals(3404, aside.getY());
		assertEquals(1, Math.abs(aside.getX() - 3200));
		assertEquals(1, events.size());
		assertEquals(TriggerEvent.Type.STEPPED_ASIDE, events.get(0).getType());
	}

	@Test
	public void aRunnerIsSeenTwoTilesOut()
	{
		parkAt(3200, 3406);
		game.at(3200, 3400, 0);
		tick(1);
		game.at(3200, 3402, 0);
		tick(1);
		game.at(3200, 3404, 0);
		tick(1);
		assertEquals("their next run step lands on it", 1, follower.stays.size());
	}

	@Test
	public void walkingPastIsNotWalkingAt()
	{
		parkAt(3201, 3404);
		game.at(3200, 3400, 0);
		tick(1);
		for (int y = 3401; y <= 3408; y++)
		{
			game.at(3200, y, 0);
			tick(1);
		}
		assertTrue("the line beside it is not the line through it", follower.stays.isEmpty());
	}

	@Test
	public void aSeatOrAMomentIsNotSteppedOutOf()
	{
		plainlyParked = false;
		parkAt(3200, 3404);
		game.at(3200, 3402, 0);
		tick(1);
		game.at(3200, 3403, 0);
		tick(1);
		assertTrue(follower.stays.isEmpty());
	}

	@Test
	public void notTwiceInAStretch()
	{
		parkAt(3200, 3404);
		game.at(3200, 3402, 0);
		tick(1);
		game.at(3200, 3403, 0);
		tick(1);
		assertEquals(1, follower.stays.size());
		// Parked on the new tile, walked at again straight away.
		WorldPoint aside = follower.stays.get(0);
		game.at(aside.getX(), aside.getY() - 2, 0);
		tick(1);
		game.at(aside.getX(), aside.getY() - 1, 0);
		tick(1);
		assertEquals("the cooldown holds", 1, follower.stays.size());
	}}
