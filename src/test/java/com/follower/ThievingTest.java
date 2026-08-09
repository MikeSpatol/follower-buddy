package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import net.runelite.api.NPC;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pickpocketing, which looks exactly like a fight from inside the plugin.
 *
 * <p>Both of the signals combat is read from are true while thieving: the
 * target is an NPC with a combat level, and a failed attempt lands a hitsplat
 * on the player. A run of failures therefore had the follower stepping clear,
 * announcing a fight, coming back, and doing it again every few seconds.
 *
 * <p>The animation settles it. 881 is the pickpocket and 1054 the stun that
 * follows getting it wrong, both the player's own, so neither depends on
 * knowing which NPC is being robbed.
 */
public class ThievingTest
{
	private static final int PICKPOCKET = 881;
	private static final int STUN = 1054;

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

	@Test
	public void pickpocketingIsNoticed()
	{
		Sim sim = new Sim();
		sim.tick(2);
		assertFalse(sim.ctx.isThieving());

		sim.game.animating(PICKPOCKET);
		sim.tick(1);
		assertTrue(sim.ctx.isThieving());
	}

	@Test
	public void theStunFromAFailedAttemptCountsToo()
	{
		Sim sim = new Sim();
		sim.game.animating(STUN);
		sim.tick(1);
		assertTrue("the stun is part of the same session", sim.ctx.isThieving());
	}

	@Test
	public void robbingSomethingThatCanFightBackIsNotAFight()
	{
		// A guard has a combat level, so interaction alone reads as combat.
		Sim sim = new Sim();
		NPC guard = sim.game.spawnNpc(3269, "Guard", 21);
		sim.game.fighting(guard);
		sim.game.animating(PICKPOCKET);
		sim.tick(3);

		assertTrue(sim.ctx.isThieving());
		assertFalse("interacting with a guard while robbing it is not combat",
			sim.ctx.isInCombat());
	}

	@Test
	public void theHitFromAFailedPocketIsNotAFightEither()
	{
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		// Exactly what the plugin does on a hitsplat.
		sim.ctx.noteDamageTaken();

		assertFalse("the damage IS the failure, not an attack",
			sim.ctx.isInCombat());
	}

	@Test
	public void aRunOfFailuresDoesNotFlicker()
	{
		// The complaint: in and out of combat every few seconds across a run.
		Sim sim = new Sim();
		NPC guard = sim.game.spawnNpc(3269, "Guard", 21);
		sim.game.fighting(guard);

		for (int attempt = 0; attempt < 12; attempt++)
		{
			sim.game.animating(PICKPOCKET);
			sim.tick(3);
			sim.ctx.noteDamageTaken();
			sim.game.animating(STUN);
			sim.tick(8);
			// The pause between attempts, animation back to nothing.
			sim.game.animating(-1);
			sim.tick(4);

			assertFalse("combat read as true on attempt " + attempt,
				sim.ctx.isInCombat());
			assertTrue("thieving lapsed between attempts on " + attempt,
				sim.ctx.isThieving());
		}
	}

	@Test
	public void everyStunTheGameCanApplyIsRecognised()
	{
		// There is not one stun animation. Recognising only STUNNED_THIEVING
		// covered one case in seven, and the rest is why the follower came
		// back early against some targets and not others.
		int[] stuns = {1054, 1874, 848, 14422, 14423, 14424, 10087, 10088};
		for (int stun : stuns)
		{
			Sim sim = new Sim();
			sim.game.animating(stun);
			sim.tick(1);
			assertTrue("stun " + stun + " was not recognised as thieving",
				sim.ctx.isThieving());
		}
	}

	// ---------------------------------------------------------------- session

	@Test
	public void theSessionOutlastsAGapTheAttemptWindowWouldNot()
	{
		// The complaint: back too early. A pause to eat, or a slow re-click,
		// is longer than the window that suppresses combat.
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		sim.game.animating(-1);
		sim.tick(40);

		assertFalse("the attempt window is only for holding the session open",
			sim.ctx.isThieving());
		assertTrue("the session is what everything else asks about",
			sim.ctx.isInThievingSession());
		assertFalse("and it keeps combat suppressed across the gap",
			sim.ctx.isInCombat());
	}

	@Test
	public void walkingOffEndsTheSessionAtOnce()
	{
		// What a person actually does when they are done, and it should not
		// have to wait out any clock.
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);
		assertTrue(sim.ctx.isInThievingSession());

		sim.game.animating(-1);
		sim.game.at(3222 + 12, 3218, 0);
		sim.tick(1);

		assertFalse("walking away is the signal to come back",
			sim.ctx.isInThievingSession());
	}

	@Test
	public void shufflingAboutDoesNotEndTheSession()
	{
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		sim.game.animating(-1);
		sim.game.at(3222 + 2, 3218 + 1, 0);
		sim.tick(3);

		assertTrue("a step or two is still the same spot",
			sim.ctx.isInThievingSession());
	}

	@Test
	public void aChangeOfFloorEndsTheSession()
	{
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		sim.game.animating(-1);
		sim.game.at(3222, 3218, 1);
		sim.tick(1);

		assertFalse(sim.ctx.isInThievingSession());
	}

	@Test
	public void standingThereDoingNothingEventuallyEndsIt()
	{
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		sim.game.animating(-1);
		sim.tick(200);

		assertFalse("it cannot stand back forever on one attempt",
			sim.ctx.isInThievingSession());
	}

	@Test
	public void aLongRunOfAttemptsKeepsTheSessionAlive()
	{
		Sim sim = new Sim();
		for (int attempt = 0; attempt < 20; attempt++)
		{
			sim.game.animating(PICKPOCKET);
			sim.tick(3);
			sim.game.animating(1054);
			sim.tick(8);
			sim.game.animating(-1);
			sim.tick(10);

			assertTrue("the session lapsed during attempt " + attempt,
				sim.ctx.isInThievingSession());
		}
	}

	@Test
	public void aRealFightAfterThievingIsStillAFight()
	{
		// The cost of this is a few ticks where a genuine attack is missed.
		// It must not be more than a few.
		Sim sim = new Sim();
		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		// Walking off is what ends a session, so that is how a fight becomes
		// visible again - and being attacked is one of the few things that
		// actually makes a player move.
		sim.game.animating(-1);
		sim.game.at(3222 + 12, 3218, 0);
		sim.tick(2);

		NPC goblin = sim.game.spawnNpc(3029, "Goblin", 5);
		sim.game.fighting(goblin);
		sim.tick(2);

		assertTrue("a fight starting after thieving lapses must register",
			sim.ctx.isInCombat());
	}

	@Test
	public void aFightAlreadyRunningIsNotForgottenByOnePickpocket()
	{
		// Combat has its own grace window, and this must not shorten it.
		Sim sim = new Sim();
		NPC goblin = sim.game.spawnNpc(3029, "Goblin", 5);
		sim.game.fighting(goblin);
		sim.tick(2);
		assertTrue(sim.ctx.isInCombat());

		sim.game.animating(PICKPOCKET);
		sim.tick(2);

		assertTrue("combat should still be running its own grace out",
			sim.ctx.isInCombat());
	}
}
