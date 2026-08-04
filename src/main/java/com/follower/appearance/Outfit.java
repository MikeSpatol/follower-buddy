package com.follower.appearance;

import java.util.Arrays;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;

/**
 * The follower's appearance, stored in the same encoding the game uses for
 * {@link PlayerComposition#getEquipmentIds()}:
 *
 * <pre>
 *   0                     empty slot
 *   {@value #KIT_OFFSET} + kitId    a body kit (torso, arms, hair, ...)
 *   {@value #ITEM_OFFSET} + itemId  an equipped item
 * </pre>
 *
 * Slot order is {@link KitType#getIndex()}: HEAD, CAPE, AMULET, WEAPON, TORSO,
 * SHIELD, ARMS, LEGS, HAIR, HANDS, BOOTS, JAW.
 */
public final class Outfit
{
	/**
	 * Internal encoding offsets, spaced so the kit and item ranges cannot collide.
	 * The game encoding is produced and consumed only at the boundary, in
	 * {@link #toGameEquipmentIds()} and {@link #from(PlayerComposition)}.
	 */
	public static final int KIT_OFFSET = 1 << 20;
	public static final int ITEM_OFFSET = 1 << 21;
	public static final int SLOTS = 12;

	/**
	 * The game's own offsets, used only when talking to PlayerComposition: a slot
	 * holds {@code 0} for empty, {@code 256 + kitId} for a kit, and
	 * {@code 2048 + itemId} for an item.
	 *
	 * <p>The item offset is 2048, not 512. That leaves kit ids room to run up to
	 * 1791, which they need - hair and jaw kits are past 300. With 512 here, kit 305
	 * encoded to 561, which reads back as item 49, and every kit above 255 was
	 * silently corrupted in both directions: live compositions decoded into
	 * nonexistent items, and the capture path swapped garbage into the player.
	 */
	private static final int GAME_KIT_OFFSET = PlayerComposition.KIT_OFFSET;
	private static final int GAME_ITEM_OFFSET = PlayerComposition.ITEM_OFFSET;

	/**
	 * Default identikit ids, used to fill body slots the outfit leaves empty so
	 * the follower isn't a floating head. These are the standard character
	 * creation kits; if your dump shows different ids, adjust here.
	 */
	private static final int[][] DEFAULT_KITS_MALE = {
		{KitType.HAIR.getIndex(), 0},
		// The jaw kit is facial geometry, not merely a beard - without one the head
		// renders incomplete, so both genders always get a default.
		{KitType.JAW.getIndex(), 10},
		{KitType.TORSO.getIndex(), 18},
		{KitType.ARMS.getIndex(), 26},
		{KitType.HANDS.getIndex(), 33},
		{KitType.LEGS.getIndex(), 36},
		{KitType.BOOTS.getIndex(), 42},
	};

	private static final int[][] DEFAULT_KITS_FEMALE = {
		{KitType.HAIR.getIndex(), 45},
		// Female jaw kits are 292-306 with their own distinct models. They only ever
		// looked broken because ids above 255 overflowed the old slot encoding.
		{KitType.JAW.getIndex(), 292},
		{KitType.TORSO.getIndex(), 56},
		{KitType.ARMS.getIndex(), 61},
		{KitType.HANDS.getIndex(), 67},
		{KitType.LEGS.getIndex(), 70},
		{KitType.BOOTS.getIndex(), 79},
	};

	private final int[] equipment = new int[SLOTS];
	private final int[] colors = new int[5];
	private int gender;

	public Outfit()
	{
	}

	public Outfit(Outfit other)
	{
		copyFrom(other);
	}

	public static Outfit from(PlayerComposition composition)
	{
		Outfit outfit = new Outfit();
		if (composition == null)
		{
			return outfit;
		}

		int[] ids = composition.getEquipmentIds();
		if (ids != null)
		{
			for (int i = 0; i < Math.min(ids.length, SLOTS); i++)
			{
				int raw = ids[i];
				if (raw >= GAME_ITEM_OFFSET)
				{
					outfit.equipment[i] = ITEM_OFFSET + (raw - GAME_ITEM_OFFSET);
				}
				else if (raw >= GAME_KIT_OFFSET)
				{
					outfit.equipment[i] = KIT_OFFSET + (raw - GAME_KIT_OFFSET);
				}
				else
				{
					outfit.equipment[i] = 0;
				}
			}
		}
		outfit.setColors(composition.getColors());
		outfit.setGender(composition.getGender());
		return outfit;
	}

	public int[] rawEquipment()
	{
		return equipment.clone();
	}

	/**
	 * This outfit in the game's own composition encoding, for writing back into a
	 * live {@code PlayerComposition}.
	 *
	 * <p>The scheme holds kit ids up to 1791 before they would overflow into the item
	 * range; anything beyond that is dropped rather than written as a corrupt value.
	 * No real kit comes close, so in practice nothing is lost.
	 */
	public int[] toGameEquipmentIds()
	{
		int[] out = new int[SLOTS];
		for (int i = 0; i < SLOTS; i++)
		{
			int value = equipment[i];
			if (value >= ITEM_OFFSET)
			{
				out[i] = GAME_ITEM_OFFSET + (value - ITEM_OFFSET);
			}
			else if (value >= KIT_OFFSET)
			{
				int kitId = value - KIT_OFFSET;
				out[i] = kitId < (GAME_ITEM_OFFSET - GAME_KIT_OFFSET)
					? GAME_KIT_OFFSET + kitId
					: 0;
			}
			else
			{
				out[i] = 0;
			}
		}
		return out;
	}

	public int getRaw(KitType slot)
	{
		return equipment[slot.getIndex()];
	}

	public void setRaw(KitType slot, int raw)
	{
		equipment[slot.getIndex()] = raw;
	}

	public void setItem(KitType slot, int itemId)
	{
		equipment[slot.getIndex()] = itemId <= 0 ? 0 : ITEM_OFFSET + itemId;
	}

	public void setKit(KitType slot, int kitId)
	{
		equipment[slot.getIndex()] = kitId < 0 ? 0 : KIT_OFFSET + kitId;
	}

	public void clear(KitType slot)
	{
		equipment[slot.getIndex()] = 0;
	}

	public boolean isItem(KitType slot)
	{
		return equipment[slot.getIndex()] >= ITEM_OFFSET;
	}

	public boolean isKit(KitType slot)
	{
		int v = equipment[slot.getIndex()];
		return v >= KIT_OFFSET && v < ITEM_OFFSET;
	}

	public int itemId(KitType slot)
	{
		return equipment[slot.getIndex()] - ITEM_OFFSET;
	}

	public int kitId(KitType slot)
	{
		return equipment[slot.getIndex()] - KIT_OFFSET;
	}

	public int getGender()
	{
		return gender;
	}

	public void setGender(int gender)
	{
		this.gender = gender == 1 ? 1 : 0;
	}

	public int[] getColors()
	{
		return colors.clone();
	}

	public void setColors(int[] source)
	{
		if (source != null)
		{
			System.arraycopy(source, 0, colors, 0, Math.min(source.length, colors.length));
		}
	}

	public void copyFrom(Outfit other)
	{
		System.arraycopy(other.equipment, 0, equipment, 0, SLOTS);
		System.arraycopy(other.colors, 0, colors, 0, colors.length);
		gender = other.gender;
	}

	/**
	 * Returns a copy with empty body slots filled by default kits. Item slots and
	 * already-populated kit slots are untouched, so gear always wins.
	 */
	/** The default kit id for a body part, or -1 if that part has no default. */
	public static int defaultKitId(KitType slot, int gender)
	{
		for (int[] pair : (gender == 1 ? DEFAULT_KITS_FEMALE : DEFAULT_KITS_MALE))
		{
			if (pair[0] == slot.getIndex())
			{
				return pair[1];
			}
		}
		return -1;
	}

	public Outfit withDefaultBody()
	{
		Outfit filled = new Outfit(this);

		// Every body kit is filled here, including ones the gear will cover. Which of
		// them actually render is decided at compose time from each item's wearPos
		// data, the same way the game does it - a platebody hides the arms but a
		// chainbody does not, and only the item itself knows which it is. Deciding
		// that here, by slot type alone, stripped the arms under every torso item.
		for (int[] pair : (gender == 1 ? DEFAULT_KITS_FEMALE : DEFAULT_KITS_MALE))
		{
			if (pair[1] >= 0 && filled.equipment[pair[0]] == 0)
			{
				filled.equipment[pair[0]] = KIT_OFFSET + pair[1];
			}
		}
		return filled;
	}

	public boolean isEmpty()
	{
		for (int v : equipment)
		{
			if (v != 0)
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof Outfit))
		{
			return false;
		}
		Outfit other = (Outfit) o;
		return gender == other.gender
			&& Arrays.equals(equipment, other.equipment)
			&& Arrays.equals(colors, other.colors);
	}

	@Override
	public int hashCode()
	{
		return 31 * (31 * Arrays.hashCode(equipment) + Arrays.hashCode(colors)) + gender;
	}

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		for (KitType slot : KitType.values())
		{
			int v = getRaw(slot);
			if (v == 0)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(slot.name()).append('=')
				.append(v >= ITEM_OFFSET ? "item:" + (v - ITEM_OFFSET) : "kit:" + (v - KIT_OFFSET));
		}

		if (gender == 1)
		{
			sb.append(sb.length() > 0 ? "," : "").append("gender=female");
		}

		// Body-colour indices into the game's palette tables; the parser already
		// reads this key, but until 2026-08-02 nothing wrote it, so panel-chosen
		// indices silently vanished on every save.
		boolean anyColor = false;
		for (int c : colors)
		{
			if (c != 0)
			{
				anyColor = true;
				break;
			}
		}
		if (anyColor)
		{
			sb.append(sb.length() > 0 ? "," : "").append("colors=");
			for (int i = 0; i < colors.length; i++)
			{
				if (i > 0)
				{
					sb.append('/');
				}
				sb.append(colors[i]);
			}
		}

		return sb.toString();
	}
}
