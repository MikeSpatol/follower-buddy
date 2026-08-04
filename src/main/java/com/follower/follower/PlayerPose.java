package com.follower.follower;

/**
 * Standard unarmed player pose animations.
 *
 * <p>Real players take their pose set from the equipped weapon's definition, which
 * the RuneLite API doesn't expose. These unarmed defaults look right for most
 * outfits; override them in the config if you want weapon-appropriate stances.
 */
public final class PlayerPose
{
	public static final int IDLE = 808;
	public static final int WALK = 819;
	public static final int RUN = 824;
	public static final int TURN_180 = 820;
	public static final int SIDESTEP_LEFT = 821;
	public static final int SIDESTEP_RIGHT = 822;
	public static final int IDLE_TURN = 823;

	private PlayerPose()
	{
	}
}
