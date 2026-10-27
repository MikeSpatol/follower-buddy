package com.follower;

/**
 * Standard unarmed player pose animations.
 *
 * <p>Real players take their pose set from the equipped weapon's definition, which
 * the RuneLite API doesn't expose. These unarmed defaults look right for most
 * outfits; override them in the config if you want weapon-appropriate stances.
 */
class PlayerPose
{
	static final int IDLE = 808,
		WALK = 819,
		TURN_180 = 820,
		SIDESTEP_LEFT = 821,
		SIDESTEP_RIGHT = 822,
		IDLE_TURN = 823;

}
