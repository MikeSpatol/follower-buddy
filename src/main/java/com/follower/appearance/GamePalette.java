package com.follower.appearance;

/**
 * The client's hardcoded body-colour tables, recovered empirically on 2026-08-02.
 *
 * <p>The game colours a player by plain find/replace over the merged model: each
 * body-colour slot has one source colour (hair has two - base and highlight are
 * authored separately, the highlight is never derived), replaced by the table
 * entry for the player's chosen index. The tables live in the client binary and
 * are not API-reachable, so they were read out with {@code ::follower harvest}:
 * every index of every slot was applied to the live composition client-side and
 * the resulting recolour extracted bit-exactly from the lit model.
 *
 * <p>Validation: the first 12 hair, 16 torso/legs, 6 boots and 8 skin entries
 * reproduce the classic-era tables value for value; the remainder are the modern
 * additions. Re-run the harvest after a game update that adds colours.
 */
public final class GamePalette
{
	/** Source colours the tables replace, one per body-colour slot. */
	public static final short HAIR_FIND = 6798;
	public static final short HAIR_HIGHLIGHT_FIND = (short) -10304;
	public static final short TORSO_FIND = 8741;
	public static final short LEGS_FIND = 25238;
	public static final short BOOTS_FIND = 4626;
	public static final short SKIN_FIND = 4550;

	public static final short[] HAIR = {
		6798, 107, 10283, 16, 4797, 7744, 5799, 4634, -31839, 22433,
		2983, -11343, 8, 5281, 10438, 3650, -27322, -21845, 200, 571,
		908, 21830, 28946, -15701, -14010, -22122, 937, 8130, -13422, 30385,
	};

	public static final short[] HAIR_HIGHLIGHT = {
		6554, 115, 10304, 28, 5702, 7756, 5681, 4510, -31835, 22437,
		2859, -11339, 16, 5157, 10446, 3658, -27314, -21965, 472, 580,
		784, 21966, 28950, -15697, -14002, -22116, 945, 8144, -13414, 30389,
	};

	public static final short[] TORSO = {
		8741, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153,
		-8915, 4783, 1341, 16578, -30533, 25239, 8, 5281, 10438, 3650,
		-27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010,
	};

	public static final short[] LEGS = {
		25238, 8742, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094,
		10153, -8915, 4783, 1341, 16578, -30533, 8, 5281, 10438, 3650,
		-27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010,
	};

	public static final short[] BOOTS = {
		4626, 11146, 6439, 12, 4758, 10270,
	};

	public static final short[] SKIN = {
		4550, 4537, 5681, 5673, 5790, 6806, 8076, 4574, 17050, 0,
		127, -31821, -17991,
	};

	private GamePalette()
	{
	}

	/** Table for one body-colour slot in {@code PlayerComposition#getColors()} order. */
	public static short[] table(int colorSlot)
	{
		switch (colorSlot)
		{
			case 0:
				return HAIR;
			case 1:
				return TORSO;
			case 2:
				return LEGS;
			case 3:
				return BOOTS;
			case 4:
				return SKIN;
			default:
				return null;
		}
	}

	/** Source colour for one body-colour slot; hair's highlight is handled separately. */
	public static short find(int colorSlot)
	{
		switch (colorSlot)
		{
			case 0:
				return HAIR_FIND;
			case 1:
				return TORSO_FIND;
			case 2:
				return LEGS_FIND;
			case 3:
				return BOOTS_FIND;
			case 4:
				return SKIN_FIND;
			default:
				return 0;
		}
	}
}
