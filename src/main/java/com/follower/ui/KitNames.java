package com.follower.ui;

import net.runelite.api.kit.KitType;

/**
 * Labels for body kits.
 *
 * <p><b>Body kits have no names anywhere in the game.</b> The identkit config
 * carries a body part id, model ids, chathead models, recolours and a
 * non-selectable flag - and nothing else; the game's own character creation
 * shows styles as a model with arrows either side, never a name.
 *
 * <p>This class previously showed names taken from the wiki's
 * character-creation lists, matched by position. That was a guess, and a
 * wrong one: after filtering out the styles the cache flags non-selectable
 * and collapsing byte-identical duplicates, the cache still holds 71 male
 * hair styles against the wiki's 57 names, so 14 unnamed styles sit at
 * unknown positions and everything after the first of them is mislabelled.
 * A wrong name is worse than no name, so styles are numbered - exactly what
 * the game shows - and the follower rebuilds live as its own preview.
 */
public final class KitNames
{
	private KitNames()
	{
	}

	/**
	 * @param index position of this kit within its body part's offered list
	 * @param total how many styles that body part offers
	 * @return a label such as "Style 2 of 71", or "-" when nothing is set
	 */
	public static String label(KitType part, int index, int total)
	{
		if (index < 0)
		{
			return "-";
		}
		return "Style " + (index + 1) + " of " + total;
	}
}
