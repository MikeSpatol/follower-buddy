package com.follower.ui;

import net.runelite.api.kit.KitType;

/**
 * Display names for body kits.
 *
 * <p>Kits carry no names in the cache - only ids and models - so these come from
 * the wiki's character-creation lists, matched by position within the kits of
 * that body part.
 *
 * <p><b>The alignment is not exact and cannot be.</b> The cache holds 71 male
 * hair kits against the wiki's 57 names, and 16 jaw kits against 15: character
 * creation offers a subset, and the extras (NPC-only and unreleased styles) are
 * interleaved at unknown positions. Names are therefore shown with their
 * position, so a mislabelled style is visible rather than misleading, and any
 * kit past the end of a list is numbered instead of guessed at.
 *
 * <p>Torso, arms, hands, legs and boots are numbered on purpose: the game does
 * not name those styles either - character creation shows them as numbers - so
 * there is nothing authentic to show.
 */
public final class KitNames
{
	private KitNames()
	{
	}

	private static final String[] HAIR = {
		"Bald", "Dreadlocks", "Long", "Medium", "Tonsure", "Short", "Cropped",
		"Wild spikes", "Spikes", "Mohawk", "Wind braids", "Quiff", "Samurai",
		"Princely", "Curtains", "Long curtains", "Front split", "Tousled",
		"Side wedge", "Front wedge", "Front spikes", "Frohawk", "Rear skirt",
		"Queue", "Bun", "Pigtails", "Earmuffs", "Side pony", "Curls", "Ponytail",
		"Braids", "Bunches", "Bob", "Layered", "Straight", "Straight braids",
		"Two-back", "Mullet", "Undercut", "Low bun", "Messy bun", "Pompadour",
		"Afro", "Short locs", "Spiky mohawk", "Slicked mohawk", "Long quiff",
		"Short choppy", "Side afro", "Punk", "Half-shaved", "Fremennik", "Elven",
		"Medium coils", "High ponytail", "Plaits", "High bunches",
	};

	private static final String[] JAW = {
		"Clean shaven", "Goatee", "Long", "Handlebar", "Medium", "Moustache",
		"Short", "Pointy", "Split", "Mutton", "Full mutton", "Big moustache",
		"Waxed moustache", "Dali", "Vizier",
	};

	/**
	 * @param index position of this kit within its body part's list
	 * @param total how many kits that body part has
	 * @return a label such as "Dreadlocks (2/71)", or "Style 63 of 71" when the
	 * wiki list does not reach this far
	 */
	public static String label(KitType part, int index, int total)
	{
		if (index < 0)
		{
			return "-";
		}

		String[] names = part == KitType.HAIR ? HAIR : part == KitType.JAW ? JAW : null;
		if (names != null && index < names.length)
		{
			return names[index] + " (" + (index + 1) + "/" + total + ")";
		}
		return "Style " + (index + 1) + " of " + total;
	}
}
