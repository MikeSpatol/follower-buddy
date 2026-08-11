package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the per-tick state snapshot against a game that is not running.
 *
 * <p>Everything here is a reading the rules depend on being right: a fight that
 * flickers, an idle counter that never climbs or a run-energy figure that lies
 * all show up as rules firing at the wrong moment, which is very hard to
 * diagnose from inside the game.
 */
public class TriggerContextSimulationTest
{
	private static final class Sim
	{
		final FakeGame game = new FakeGame();
		final TriggerContext ctx = new TriggerContext(game.client);
		int tick;

		void tick(int count)
		{
			for (int i = 0; i < count; i++)
			{
				game.tick(++tick);
				ctx.refresh();
			}
		}
	}

	// ----------------------------------------------------------------- energy

	@Test
	public void runEnergyIsReportedAsARealPercentage()
	{
		Sim sim = new Sim();

		sim.game.energy(10000);
		sim.tick(1);
		assertEquals("full energy is 100 percent", 100, sim.ctx.getEnergyPercent());

		sim.game.energy(5000);
		sim.tick(1);
		assertEquals(50, sim.ctx.getEnergyPercent());

		sim.game.energy(1900);
		sim.tick(1);
		assertEquals(19, sim.ctx.getEnergyPercent());
	}

	/**
	 * The client counts run energy in hundredths of a percent, so anything at or
	 * below 100 is the player almost out of breath - not almost full.
	 */
	@Test
	public void nearlyExhaustedEnergyIsNotReportedAsFull()
	{
		Sim sim = new Sim();

		sim.game.energy(100);
		sim.tick(1);
		assertEquals("one percent of energy left", 1, sim.ctx.getEnergyPercent());

		sim.game.energy(50);
		sim.tick(1);
		assertEquals("half a percent rounds down to nothing", 0, sim.ctx.getEnergyPercent());

		sim.game.energy(0);
		sim.tick(1);
		assertEquals(0, sim.ctx.getEnergyPercent());
	}

	// ----------------------------------------------------------------- combat

	@Test
	public void walkingUpToTalkToSomebodyIsNotAFight()
	{
		// Interacting with a levelled NPC used to be the whole test for
		// combat, and that is also what approaching a guard for directions
		// looks like, and trading, and robbing. The follower announced a
		// battle every time.
		Sim sim = new Sim();
		NPC guard = sim.game.spawnNpc(3269, "Guard", 21);

		sim.game.facing(guard);
		sim.tick(6);

		assertFalse("looking at someone is not fighting them",
			sim.ctx.isInCombat());
	}

	@Test
	public void theFirstBlowStartsTheFightEvenIfNobodyIsFacingBack()
	{
		// The player swings first at something that dies before it can turn
		// round, or cannot reach them at all. Facing plus a landed hit is a
		// fight even without the other side squaring up.
		Sim sim = new Sim();
		NPC goblin = sim.game.spawnNpc(3029, "Goblin", 5);

		sim.game.facing(goblin);
		sim.tick(2);
		assertFalse("no blows yet", sim.ctx.isInCombat());

		sim.ctx.noteDamageDealt();
		sim.tick(1);
		assertTrue("landing one starts it", sim.ctx.isInCombat());
		assertEquals("and the target is named", "Goblin", sim.ctx.getCombatTarget());
	}

	@Test
	public void beingSquaredUpAtIsAFightWithoutAnyDamageYet()
	{
		// The other arming path: both sides facing each other, before either
		// has landed anything.
		Sim sim = new Sim();
		NPC goblin = sim.game.spawnNpc(3029, "Goblin", 5);

		sim.game.fighting(goblin);
		sim.tick(1);

		assertTrue("two sides squared up is a fight", sim.ctx.isInCombat());
	}

	@Test
	public void aFightSurvivesTheGapBetweenTargets()
	{
		Sim sim = new Sim();
		NPC goblin = sim.game.spawnNpc(3029, "Goblin", 5);

		sim.game.fighting(goblin);
		sim.tick(1);
		assertTrue(sim.ctx.isInCombat());
		assertEquals("Goblin", sim.ctx.getCombatTarget());

		// Target dies, nothing is being clicked for a few ticks.
		sim.game.fighting(null);
		sim.tick(5);
		assertTrue("a short gap between targets is still the same fight",
			sim.ctx.isInCombat());

		sim.tick(6);
		assertFalse("but it does end eventually", sim.ctx.isInCombat());
	}

	@Test
	public void beingHitCountsAsCombatEvenWhileDoingNothingBack()
	{
		Sim sim = new Sim();
		sim.tick(1);
		assertFalse(sim.ctx.isInCombat());

		sim.ctx.noteDamageTaken();
		assertTrue("taking damage is a fight whether or not you joined in",
			sim.ctx.isInCombat());
	}

	@Test
	public void talkingToSomeoneHarmlessIsNotAFight()
	{
		Sim sim = new Sim();
		NPC banker = sim.game.spawnNpc(1613, "Banker", 0);

		sim.game.fighting(banker);
		sim.tick(2);

		assertFalse("a zero combat level cannot fight back", sim.ctx.isInCombat());
	}

	@Test
	public void aBossIsToldApartFromAnOrdinaryMonster()
	{
		Sim ordinary = new Sim();
		ordinary.game.fighting(ordinary.game.spawnNpc(3029, "Goblin", 5));
		ordinary.tick(1);
		assertFalse(ordinary.ctx.isBossFight());

		Sim boss = new Sim();
		boss.game.fighting(boss.game.spawnNpc(2042, "Zulrah", 725));
		boss.tick(1);
		assertTrue(boss.ctx.isBossFight());
	}

	// ------------------------------------------------------------------- idle

	@Test
	public void standingStillClimbsTheIdleCounterAndMovingResetsIt()
	{
		Sim sim = new Sim();
		sim.tick(5);
		assertTrue("standing still should accumulate", sim.ctx.getIdleTicks() >= 3);

		sim.game.at(3225, 3218, 0);
		sim.tick(1);
		assertEquals("a step resets it", 0, sim.ctx.getIdleTicks());
	}

	@Test
	public void animatingCountsAsBusyEvenWithoutMoving()
	{
		Sim sim = new Sim();
		sim.tick(5);
		assertTrue(sim.ctx.getIdleTicks() > 0);

		sim.game.animating(899);
		sim.tick(1);
		assertEquals("mining on the spot is not idling", 0, sim.ctx.getIdleTicks());
	}

	// ----------------------------------------------------------------- memory

	@Test
	public void pacingAcrossARegionBoundaryIsNotASeriesOfVisits()
	{
		Sim sim = new Sim();
		// Two tiles either side of a region edge, stepped over repeatedly.
		WorldPoint a = new WorldPoint(3200, 3200, 0);
		WorldPoint b = new WorldPoint(3264, 3200, 0);
		assertTrue("test needs two different regions",
			a.getRegionID() != b.getRegionID());

		sim.game.at(a.getX(), a.getY(), 0);
		sim.tick(1);
		for (int i = 0; i < 10; i++)
		{
			sim.game.at(b.getX(), b.getY(), 0);
			sim.tick(1);
			sim.game.at(a.getX(), a.getY(), 0);
			sim.tick(1);
		}

		assertEquals("ten crossings is still one visit to this region",
			1, sim.ctx.getRegionVisits());
	}

	@Test
	public void theDeathSpotStaysQuietUntilWellAfterTheDeath()
	{
		Sim sim = new Sim();
		sim.tick(1);
		sim.ctx.noteDeath(new WorldPoint(3222, 3218, 0));

		sim.tick(5);
		assertFalse("the first person standing at the spot is the one who died",
			sim.ctx.isNearDeathSpot(5));

		sim.tick(250);
		assertTrue("coming back much later is a return", sim.ctx.isNearDeathSpot(5));
	}

	@Test
	public void aDeathOnAnotherFloorIsNotThisSpot()
	{
		Sim sim = new Sim();
		sim.tick(1);
		sim.ctx.noteDeath(new WorldPoint(3222, 3218, 2));
		sim.tick(250);
		assertFalse(sim.ctx.isNearDeathSpot(5));
	}

	// -------------------------------------------------------------- equipment

	@Test
	public void wornItemsAreReadableAndBodyKitsAreNotMistakenForThem()
	{
		Sim sim = new Sim();
		sim.game.wearing(4151, 11832);
		sim.tick(1);

		assertTrue(sim.ctx.isEquipped(4151));
		assertTrue(sim.ctx.isEquipped(11832));
		assertFalse("nothing else should read as worn", sim.ctx.isEquipped(1038));
	}

	// --------------------------------------------------------------- lifecycle

	@Test
	public void refreshingWithNoPlayerIsHarmless()
	{
		FakeGame game = new FakeGame();
		TriggerContext ctx = new TriggerContext(game.client);
		// Logged out: the client has no local player at all.
		game.loggedOut();
		ctx.refresh();

		assertFalse("nothing should have been read", ctx.isPlayerReady());
		assertEquals(100, ctx.getHitpointsPercent());
	}
}
