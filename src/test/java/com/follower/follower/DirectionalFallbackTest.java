package com.follower.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Which side-step a weapon uses when its own was never observed.
 *
 * <p>The measured rule, and the one that produced the visible bug: of the 26
 * weapons using the DEFAULT walk, all 26 also use the default side-steps, so
 * the standard set is exactly right for them. Of the 41 with a weapon-specific
 * walk, only 5 use the standard side-step and not one uses the standard
 * back-pedal - so borrowing it is wrong about nine times in ten, and it shows:
 * an empty-handed strafe while carrying a bulwark.
 *
 * <p>Anything with its own walk therefore falls back to that walk instead,
 * which is the client's own behaviour for a pose resolving to -1 and keeps the
 * weapon in hand.
 */
public class DirectionalFallbackTest
{
	private static final int STANDARD_SIDESTEP = PlayerPose.SIDESTEP_LEFT;
	private static final int STANDARD_BACKPEDAL = PlayerPose.TURN_180;

	private static StanceLibrary.Stance walkingWith(int walk)
	{
		StanceLibrary.Stance stance = new StanceLibrary.Stance();
		stance.walk = walk;
		return stance;
	}

	@Test
	public void anUnarmedStanceTakesTheStandardDirectionalPoses()
	{
		StanceLibrary.Stance unarmed = walkingWith(PlayerPose.WALK);

		assertEquals("a default walk means the default side-step is right",
			STANDARD_SIDESTEP,
			FollowerEntity.directionalFallback(unarmed, STANDARD_SIDESTEP));
		assertEquals(STANDARD_BACKPEDAL,
			FollowerEntity.directionalFallback(unarmed, STANDARD_BACKPEDAL));
	}

	@Test
	public void aWeaponWithItsOwnWalkKeepsWalkingRatherThanBorrowing()
	{
		// A bulwark, a halberd, anything carried distinctively.
		int weaponWalk = 7511;
		StanceLibrary.Stance armed = walkingWith(weaponWalk);

		assertEquals("strafing must not drop into the empty-handed side-step",
			weaponWalk,
			FollowerEntity.directionalFallback(armed, STANDARD_SIDESTEP));
		assertEquals("and nor must backing away",
			weaponWalk,
			FollowerEntity.directionalFallback(armed, STANDARD_BACKPEDAL));
	}

	@Test
	public void theRuleTurnsOnTheWalkAloneNotOnTheStandardAskedFor()
	{
		// Whatever pose is asked for, the answer depends only on whether this
		// stance walks the default way.
		StanceLibrary.Stance armed = walkingWith(9999);
		for (int standard : new int[]{PlayerPose.SIDESTEP_LEFT,
			PlayerPose.SIDESTEP_RIGHT, PlayerPose.TURN_180, PlayerPose.IDLE_TURN})
		{
			assertEquals(9999, FollowerEntity.directionalFallback(armed, standard));
		}

		StanceLibrary.Stance unarmed = walkingWith(PlayerPose.WALK);
		for (int standard : new int[]{PlayerPose.SIDESTEP_LEFT,
			PlayerPose.SIDESTEP_RIGHT, PlayerPose.TURN_180, PlayerPose.IDLE_TURN})
		{
			assertEquals(standard,
				FollowerEntity.directionalFallback(unarmed, standard));
		}
	}

	@Test
	public void aStanceWithNoWalkAtAllStillAnswersSomething()
	{
		// A stance saved before the directional fields existed deserialises
		// with zeroes; it must not hand back a standard pose as though the
		// weapon had been observed walking the default way.
		StanceLibrary.Stance empty = new StanceLibrary.Stance();
		assertEquals("a missing walk is not the default walk", 0,
			FollowerEntity.directionalFallback(empty, STANDARD_SIDESTEP));
	}

	@Test
	public void theBundledUnarmedStanceUsesTheStandardSet()
	{
		// The one stance that is built in rather than learned. If it ever
		// stopped matching PlayerPose.WALK, every unarmed follower would
		// strafe with the wrong animation.
		StanceLibrary library = new StanceLibrary(new com.google.gson.Gson(),
			new com.follower.appearance.ModelRepository(new com.google.gson.Gson()));
		StanceLibrary.Stance unarmed = library.forWeapon(0);

		assertEquals("unarmed must walk with the standard walk pose",
			PlayerPose.WALK, unarmed.walk);
		assertEquals(STANDARD_SIDESTEP,
			FollowerEntity.directionalFallback(unarmed, STANDARD_SIDESTEP));
	}
}
