package com.follower.appearance;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.kit.KitType;

/**
 * Parses the human-editable outfit string from the config panel.
 *
 * <p>Format: comma or newline separated {@code SLOT=item:ID} / {@code SLOT=kit:ID}
 * entries, plus optional {@code gender=male|female} and {@code colors=a/b/c/d/e}.
 * Slot names are {@link KitType} names, case-insensitive. Lines starting with
 * {@code #} are comments.
 *
 * <pre>
 *   # Bandos melee
 *   gender=male
 *   HEAD=item:11826, TORSO=item:11832, LEGS=item:11834
 *   WEAPON=item:4151, SHIELD=item:8850, CAPE=item:6570
 *   HANDS=item:7462, BOOTS=item:11840, AMULET=item:6585
 * </pre>
 */
@Slf4j
public final class OutfitParser
{
	private OutfitParser()
	{
	}

	public static Outfit parse(String text, List<String> errorsOut)
	{
		Outfit outfit = new Outfit();
		if (text == null || text.trim().isEmpty())
		{
			return outfit;
		}

		for (String rawToken : text.split("[,\\r\\n]+"))
		{
			String token = rawToken.trim();
			if (token.isEmpty() || token.startsWith("#") || token.startsWith("//"))
			{
				continue;
			}

			int eq = token.indexOf('=');
			if (eq < 0)
			{
				addError(errorsOut, "Missing '=' in: " + token);
				continue;
			}

			String key = token.substring(0, eq).trim();
			String value = token.substring(eq + 1).trim();

			if (key.equalsIgnoreCase("gender"))
			{
				outfit.setGender(value.toLowerCase().startsWith("f") || value.equals("1") ? 1 : 0);
				continue;
			}

			if (key.equalsIgnoreCase("colors") || key.equalsIgnoreCase("colours"))
			{
				parseColors(value, outfit, errorsOut);
				continue;
			}

			// The heuristic-era free-form overrides. Body colours are now palette
			// indices (the colors= key), which is how the game itself stores them.
			if (key.toLowerCase().startsWith("color.") || key.toLowerCase().startsWith("colour."))
			{
				addError(errorsOut, "'" + key + "' is no longer supported - "
					+ "pick colours in the panel, or set colors=h/t/l/b/s indices");
				continue;
			}

			KitType slot = slotByName(key);
			if (slot == null)
			{
				addError(errorsOut, "Unknown slot '" + key + "'");
				continue;
			}

			if (value.equalsIgnoreCase("none") || value.equals("-1") || value.equals("0"))
			{
				outfit.clear(slot);
				continue;
			}

			int colon = value.indexOf(':');
			String kind = colon < 0 ? "item" : value.substring(0, colon).trim();
			String idText = colon < 0 ? value : value.substring(colon + 1).trim();

			int id;
			try
			{
				id = Integer.parseInt(idText);
			}
			catch (NumberFormatException e)
			{
				addError(errorsOut, "'" + idText + "' is not a number (slot " + key + ")");
				continue;
			}

			if (kind.equalsIgnoreCase("kit"))
			{
				outfit.setKit(slot, id);
			}
			else if (kind.equalsIgnoreCase("item"))
			{
				outfit.setItem(slot, id);
			}
			else
			{
				addError(errorsOut, "Expected 'item' or 'kit', got '" + kind + "'");
			}
		}

		return outfit;
	}

	public static Outfit parse(String text)
	{
		return parse(text, new ArrayList<>());
	}

	private static void parseColors(String value, Outfit outfit, List<String> errorsOut)
	{
		String[] parts = value.split("[/ ]+");
		int[] colors = new int[5];
		for (int i = 0; i < Math.min(parts.length, 5); i++)
		{
			try
			{
				colors[i] = Integer.parseInt(parts[i].trim());
			}
			catch (NumberFormatException e)
			{
				addError(errorsOut, "Bad colour index '" + parts[i] + "'");
			}
		}
		outfit.setColors(colors);
	}

	private static KitType slotByName(String name)
	{
		for (KitType slot : KitType.values())
		{
			if (slot.name().equalsIgnoreCase(name))
			{
				return slot;
			}
		}
		return null;
	}

	private static void addError(List<String> errors, String message)
	{
		if (errors != null)
		{
			errors.add(message);
		}
		log.debug("Outfit parse: {}", message);
	}
}
