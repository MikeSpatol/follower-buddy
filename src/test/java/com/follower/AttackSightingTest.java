package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Telling a swing from everything else a player does mid-interaction.
 *
 * <p>Interaction alone was the whole test, and interaction is sticky: it
 * outlives the fight that set it. Walk away from an NPC, start mining, and the
 * pickaxe's swing is filed as that weapon's attack animation. It happened - a
 * single 30-frame mining loop became the learned attack of six pickaxes and of
 * a shortbow, so the follower in ranged thrall mode shot its bow by mining
 * with it.
 *
 * <p>The fix is the rule TriggerContext already arrived at for the same
 * reason, applied to the observer that was still trusting interaction.
 */
public class AttackSightingTest
{
	@Test
	public void miningWithAStaleTargetIsNotASwing()
	{
		// The local player, interaction still pointing at a finished fight,
		// and the context reporting no combat.
		assertFalse("a pickaxe swing is not a bow shot",
			FollowerPlugin.sightingIsASwing(true, false, -1));
		assertFalse("nor is it one because something is still targeted",
			FollowerPlugin.sightingIsASwing(true, false, 30));
	}

	@Test
	public void anActualFightStillTeaches()
	{
		assertTrue("the whole point of the observer",
			FollowerPlugin.sightingIsASwing(true, true, 30));
	}

	@Test
	public void somebodyElseIsJudgedByTheirTarget()
	{
		// No varps but our own, so the evidence is the target wearing a health
		// bar - which only happens in combat.
		assertTrue("their target is in combat",
			FollowerPlugin.sightingIsASwing(false, false, 30));
		assertFalse("their target has no health bar, so this is not a fight",
			FollowerPlugin.sightingIsASwing(false, false, -1));
		assertFalse("and a dead target is not being swung at",
			FollowerPlugin.sightingIsASwing(false, false, 0));
	}

	@Test
	public void ourOwnCombatFlagDoesNotVouchForOtherPlayers()
	{
		// Being in a fight ourselves says nothing about the player mining
		// beside us, and reading it as though it did is how one bad animation
		// spreads across every weapon in sight.
		assertFalse("our fight is not their swing",
			FollowerPlugin.sightingIsASwing(false, true, -1));
	}
}
