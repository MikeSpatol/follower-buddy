package com.follower.follower;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.PlayerComposition;

/**
 * The follower borrowing one of the player's tools for a work session, and
 * giving it back.
 *
 * <p>One item at a time, through the same transient-prop path the scroll
 * uses ({@link ErrandController.Hands}): the item is overlaid on the outfit
 * at compose time and never persisted, so the stance follows whatever is in
 * the hand for free. Because the scroll and a tool share the weapon slot, a
 * borrow refuses to start over a held scroll and the scroll flourish refuses
 * to start over a held tool - see {@link PropFlourish#start}.
 *
 * <p>Two ways out. A session that ran long enough earns a beat on the way
 * out - a bow, or a salute for a hat - and the item goes back on the same
 * frame, since a rebuild mid-emote is safe. Every abort path releases
 * silently, and {@link #abort} is idempotent because it runs from all of
 * them whether or not anything is held.
 */
@Slf4j
public class Borrow
{
	/** Proven on the follower: two rules already play it. */
	static final int BOW_ANIMATION = 858;

	/** Proven by the mirror rules, which copy it onto the follower. */
	static final int SALUTE_ANIMATION = 2112;

	// Bronze first in each tiered list: it is the fallback when nothing is worn.
	static final int[] AXES = {
		1351, 1349, 1353, 1361, 1355, 1357, 1359, 6739, 13241, 23673, 20011,
	};

	static final int[] PICKAXES = {
		1265, 1267, 1269, 12297, 1273, 1271, 1275, 11920, 13243, 23680, 20014,
	};

	static final int[] HARPOONS = {311, 10129};
	static final int[] RODS = {307, 309};

	static final int ROCK_HAMMER = 4162;
	static final int SECATEURS = 5329;
	static final int SAW = 8794;

	/** Only if the cache says it is wearable; {@link #start} finds out. */
	static final int TORCH = 8987;
	static final int BUTTERFLY_NET = 10010;

	/** The skilling outfit hats. The smiths' tunic is a body, so it is not here. */
	static final Set<Integer> HATS = new HashSet<>(Arrays.asList(
		10941, 12013, 13258, 13646, 11850, 20708, 5554, 24872));

	/** The harpoon's animation, and the two rods'. */
	private static final int HARPOON_ANIMATION = 618;
	private static final int ROD_ANIMATION = 622;
	private static final int FLY_ROD_ANIMATION = 623;

	private final FollowerEntity follower;
	private final ErrandController.Hands hands;

	private int held;

	public Borrow(FollowerEntity follower, ErrandController.Hands hands)
	{
		this.follower = follower;
		this.hands = hands;
	}

	public boolean isHolding()
	{
		return held != 0;
	}

	public int heldItem()
	{
		return held;
	}

	/**
	 * Takes the item, unless the hands are already full - a tool already
	 * borrowed, or the scroll out for something else.
	 *
	 * @return whether it is now held. False also covers an item the cache has
	 * no wear position for, which the hands quietly decline
	 */
	public boolean start(int itemId)
	{
		if (itemId <= 0 || held != 0 || hands.isHolding())
		{
			return false;
		}
		hands.hold(itemId);
		if (!hands.isHolding())
		{
			return false;
		}
		held = itemId;
		log.debug("Borrowed item {}", itemId);
		return true;
	}

	/**
	 * Gives the item back.
	 *
	 * @param withBeat whether the session was long enough to be worth a bow -
	 * a salute for a hat. The release lands on the same frame as the emote
	 * starts; the caller keeps the feet still while it plays
	 */
	public void giveBack(boolean withBeat)
	{
		giveBack(withBeat, 0);
	}

	/**
	 * As {@link #giveBack(boolean)}, with something to play first.
	 *
	 * @param leadIn an animation the beat follows on from - the stand-up,
	 * when the follower was sitting - or 0 for none
	 */
	public void giveBack(boolean withBeat, int leadIn)
	{
		if (held == 0)
		{
			return;
		}
		if (withBeat)
		{
			int beat = HATS.contains(held) ? SALUTE_ANIMATION : BOW_ANIMATION;
			if (leadIn > 0)
			{
				follower.playAnimations(new int[]{leadIn, beat});
			}
			else
			{
				follower.playAnimation(beat);
			}
		}
		log.debug("Returned item {}{}", held, withBeat ? " with a beat" : "");
		held = 0;
		hands.release();
	}

	/** The silent release, for every abort path. Safe with nothing held. */
	public void abort()
	{
		if (held == 0)
		{
			return;
		}
		giveBack(false);
	}

	/**
	 * Which of the player's tools a session would borrow, or 0 for none.
	 *
	 * <p>The worn one when it is worn, the bronze one when it is not; the
	 * fishing tools go by the animation, since "Fish" says nothing about what
	 * is in the hand.
	 */
	public static int toolFor(String activity, int animationId, Set<Integer> worn)
	{
		if (activity == null)
		{
			return 0;
		}
		switch (activity)
		{
			case "woodcutting":
				return wornOr(worn, AXES, AXES[0]);
			case "mining":
				return wornOr(worn, PICKAXES, PICKAXES[0]);
			case "fishing":
				if (animationId == HARPOON_ANIMATION)
				{
					return wornOr(worn, HARPOONS, HARPOONS[0]);
				}
				if (animationId == ROD_ANIMATION || animationId == FLY_ROD_ANIMATION)
				{
					return wornOr(worn, RODS, RODS[0]);
				}
				return 0;
			case "farming":
				return SECATEURS;
			case "construction":
				return SAW;
			case "firemaking":
				return TORCH;
			default:
				return 0;
		}
	}

	private static int wornOr(Set<Integer> worn, int[] tiers, int fallback)
	{
		if (worn != null)
		{
			for (int id : tiers)
			{
				if (worn.contains(id))
				{
					return id;
				}
			}
		}
		return fallback;
	}

	/** Worn item ids off a composition, the way the trigger context reads them. */
	public static Set<Integer> wornItems(PlayerComposition composition)
	{
		Set<Integer> worn = new HashSet<>();
		int[] ids = composition == null ? null : composition.getEquipmentIds();
		if (ids != null)
		{
			for (int raw : ids)
			{
				if (raw >= PlayerComposition.ITEM_OFFSET)
				{
					worn.add(raw - PlayerComposition.ITEM_OFFSET);
				}
			}
		}
		return worn;
	}
}
