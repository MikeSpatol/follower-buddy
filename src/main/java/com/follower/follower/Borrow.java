// public:file skilling
package com.follower.follower;

import java.util.HashSet;
import java.util.Locale;
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

	/** Whether an animation is farming work: the patch's own, not a bite to eat. */
	static boolean farmingWork(int animationId)
	{
		return SkillingController.values("tool.farming." + animationId) != null;
	}

	/** The tool for a farming animation; secateurs when it is not one we know. */
	static int farmingTool(int animationId)
	{
		return toolFor("farming", animationId, null);
	}

	/**
	 * Every harpoon swing and every rod cast the cache names, the tables' own
	 * and the rest, a dragon harpoon's or a pearl rod's: skilling.properties.
	 */
	private static final Set<Integer> HARPOONING = SkillingController.idSet("harpooning");
	private static final Set<Integer> ROD_FISHING = SkillingController.idSet("rodFishing");

	private final FollowerEntity follower;
	private final ErrandController.Hands hands;

	private int held;

	/** The hat worn alongside, or 0: the second prop, which is not the hand. */
	private int hat;

	/** True while the borrowed item is out of the hands, but still on loan. */
	private boolean aside;

	public Borrow(FollowerEntity follower, ErrandController.Hands hands)
	{
		this.follower = follower;
		this.hands = hands;
	}

	/** Whether the hand has a tool in it; a hat alone is not holding. */
	public boolean isHolding()
	{
		return held != 0;
	}

	public int heldItem()
	{
		return held;
	}

	public boolean hasHat()
	{
		return hat != 0;
	}

	// public:cut dev
	public int hatWorn()
	{
		return hat;
	}

	// public:end dev
	/** Whether anything at all is on loan, in the hand or on the head. */
	public boolean isBorrowing()
	{
		return held != 0 || hat != 0;
	}

	/**
	 * Puts the hat on. Its own slot, so it goes over a held tool and never
	 * argues with the scroll.
	 *
	 * @return whether it is now worn; false when the hands decline it
	 */
	public boolean wearHat(int itemId)
	{
		if (itemId <= 0 || hat != 0 || hands.isWearing())
		{
			return false;
		}
		hands.wear(itemId);
		if (!hands.isWearing())
		{
			return false;
		}
		hat = itemId;
		log.debug("Borrowed hat {}", itemId); // public:cut logs
		return true;
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
		log.debug("Borrowed item {}", itemId); // public:cut logs
		return true;
	}

	/**
	 * Gives the item back.
	 *
	 * @param withBeat whether the session was long enough to be worth a bow -
	 * a salute when only a hat was borrowed. The release lands on the same
	 * frame as the emote starts; the caller keeps the feet still while it plays
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
		if (held == 0 && hat == 0)
		{
			return;
		}
		if (withBeat)
		{
			// A tool is handed back with a bow; a hat on its own is saluted.
			int beat = held == 0 ? SALUTE_ANIMATION : BOW_ANIMATION;
			if (leadIn > 0)
			{
				follower.playAnimations(new int[]{leadIn, beat});
			}
			else
			{
				follower.playAnimation(beat);
			}
		}
		log.debug("Returned item {} and hat {}{}", held, hat, withBeat ? " with a beat" : ""); // public:cut logs
		if (hat != 0)
		{
			hat = 0;
			hands.takeOff();
		}
		boolean hadTool = held != 0;
		held = 0;
		if (aside)
		{
			// Set aside: what is in the hands belongs to somebody else, and
			// releasing it here would take their scroll away.
			aside = false;
			return;
		}
		if (hadTool)
		{
			hands.release();
		}
	}

	/**
	 * Puts the borrowed item down for a moment so the hands can hold
	 * something else - the scroll, for a read of the notes - without giving
	 * it back. The session still has it on loan, and {@link #takeBack}
	 * returns it to the hand.
	 *
	 * @return whether there was something in the hands to put down
	 */
	public boolean setAside()
	{
		if (held == 0 || aside)
		{
			return false;
		}
		aside = true;
		hands.release();
		log.debug("Item {} set aside", held); // public:cut logs
		return true;
	}

	/**
	 * Changes the tool in the hand to another - the rake for the dibber as
	 * the work at the patch moves on - or empties the hand for 0. While it is
	 * set aside the hand is somebody else's, so only the loan changes, and
	 * the right tool - or none - comes back with {@link #takeBack}.
	 */
	public void swap(int itemId)
	{
		if (held == itemId)
		{
			return;
		}
		if (aside)
		{
			held = Math.max(itemId, 0);
			aside = held != 0;
			return;
		}
		if (held != 0)
		{
			held = 0;
			hands.release();
		}
		if (itemId > 0)
		{
			start(itemId);
		}
	}

	/** Takes a set-aside item back, once whatever borrowed the hands is done. */
	public void takeBack()
	{
		if (held == 0 || !aside)
		{
			return;
		}
		aside = false;
		hands.hold(held);
		if (!hands.isHolding())
		{
			// Refused on the way back, which the outward hold makes unlikely;
			// the loan is over rather than pending forever.
			log.debug("Item {} was not taken back", held); // public:cut logs
			held = 0;
		}
	}

	/** The silent release, for every abort path. Safe with nothing held. */
	public void abort()
	{
		giveBack(false);
	}

	/**
	 * Which of the player's tools a session would borrow, or 0 for none,
	 * preferring whatever is actually in their weapon hand.
	 *
	 * <p>The id tables below cannot help but fall behind the game: a dragon
	 * felling axe was borrowed as a bronze one for exactly that reason. So
	 * the hand is asked first, by NAME, which no update can invalidate, and
	 * the tables are what answers when the tool is in the bag rather than
	 * the hand. A battleaxe is not a felling axe, whatever it ends in.
	 *
	 * @param wornWeapon the item in the player's weapon slot, or 0
	 * @param wornWeaponName that item's name, or null when it cannot be read
	 */
	public static int toolFor(String activity, int animationId, Set<Integer> worn,
		int wornWeapon, String wornWeaponName)
	{
		if (wornWeapon > 0 && wornWeaponName != null && activity != null)
		{
			String name = wornWeaponName.toLowerCase(Locale.ROOT);
			boolean pickaxe = name.contains("pickaxe");
			boolean fighting = name.contains("battleaxe") || name.contains("greataxe")
				|| name.contains("thrownaxe") || name.contains("poleaxe");
			switch (activity)
			{
				case "woodcutting":
					if (name.endsWith("axe") && !pickaxe && !fighting)
					{
						return wornWeapon;
					}
					break;
				case "mining":
					if (pickaxe)
					{
						return wornWeapon;
					}
					break;
				case "fishing":
					// A harpoon left on through a cage or a net is still called
					// a harpoon, so the name answers only for its own kind of
					// swing, or for one nothing here knows at all. A weapon
					// called a rod, the rod of ivandis, is no fishing rod.
					boolean rod = name.contains("fishing rod") || name.contains("barbarian rod");
					boolean unknown = toolFor(activity, animationId, worn) == 0
						&& !HARPOONING.contains(animationId) && !ROD_FISHING.contains(animationId);
					if ((name.contains("harpoon") && (unknown || HARPOONING.contains(animationId)))
						|| (rod && (unknown || ROD_FISHING.contains(animationId))))
					{
						return wornWeapon;
					}
					break;
				default:
					break;
			}
		}
		return toolFor(activity, animationId, worn);
	}

	/**
	 * The same question with nothing known about the hand: skilling.properties'
	 * tool row, the one worn when it is worn, the first, the bronze one, when
	 * it is not. Where the work alone cannot say - "Fish" says nothing about
	 * the hand - the row for the animation picks it; 0 is bare hands.
	 */
	public static int toolFor(String activity, int animationId, Set<Integer> worn)
	{
		int[] tiers = SkillingController.ids("tool." + activity + "." + animationId);
		if (tiers == null)
		{
			tiers = SkillingController.ids("tool." + activity);
		}
		return tiers == null ? 0 : wornOr(worn, tiers);
	}

	/**
	 * The hat a session would borrow, or 0: the hat of the skilling outfit
	 * for that work, when the player has the whole set on - skilling.properties'
	 * outfit row, the hat first, then the body, the legs and the boots. Only
	 * the hat - the rest stays where it is - and it takes the place of the
	 * tool, since the hands hold one thing.
	 */
	public static int hatFor(String activity, Set<Integer> worn)
	{
		int[] outfit = SkillingController.ids("outfit." + activity);
		if (outfit == null || worn == null)
		{
			return 0;
		}
		for (int piece : outfit)
		{
			if (!worn.contains(piece))
			{
				return 0;
			}
		}
		return outfit[0];
	}

	/** The first of the tiers that is worn, or the first tier, the bronze one, when none is. */
	private static int wornOr(Set<Integer> worn, int[] tiers)
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
		return tiers[0];
	}

	/**
	 * The item in the player's weapon hand, or 0 when the hand is empty or
	 * the composition is not there to ask.
	 */
	public static int wornWeapon(PlayerComposition composition)
	{
		if (composition == null)
		{
			return 0;
		}
		int raw = composition.getEquipmentId(net.runelite.api.kit.KitType.WEAPON);
		// getEquipmentId already takes the offset off; a kit id or an empty
		// hand comes back as 0 or -1.
		return raw > 0 ? raw : 0;
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
