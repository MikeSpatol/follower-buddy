package com.follower;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.*;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import static net.runelite.api.kit.KitType.*;

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
 *
 * <p>Two outfits are equal when every field is: a prop shows where the same
 * item worn might be hidden, so it is another model. The fields are compared
 * as they are, not through getColors' copy.
 */
@EqualsAndHashCode(doNotUseGetters = true)
final class Outfit
{
	/**
	 * Internal encoding offsets, spaced so the kit and item ranges cannot collide.
	 * The game encoding is produced and consumed only at the boundary, in
	 * {@link #toGameEquipmentIds()} and {@link #from(PlayerComposition)}.
	 */
	static final int KIT_OFFSET = 1 << 20,
		ITEM_OFFSET = 1 << 21,
		SLOTS = 12;

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
	static final int GAME_KIT_OFFSET = PlayerComposition.KIT_OFFSET,
		GAME_ITEM_OFFSET = PlayerComposition.ITEM_OFFSET;

	/**
	 * Default identikit ids, used to fill body slots the outfit leaves empty so
	 * the follower isn't a floating head. These are the standard character
	 * creation kits; if your dump shows different ids, adjust here.
	 */
	static final int[][] DEFAULT_KITS_MALE = {
		{HAIR.getIndex(), 0},
		// The jaw kit is facial geometry, not merely a beard - without one the head
		// renders incomplete, so both genders always get a default.
		{JAW.getIndex(), 10},
		{TORSO.getIndex(), 18},
		{ARMS.getIndex(), 26},
		{HANDS.getIndex(), 33},
		{LEGS.getIndex(), 36},
		{BOOTS.getIndex(), 42},
	};

	static final int[][] DEFAULT_KITS_FEMALE = {
		{HAIR.getIndex(), 45},
		// Female jaw kits are 292-306 with their own distinct models. They only ever
		// looked broken because ids above 255 overflowed the old slot encoding.
		{JAW.getIndex(), 292},
		{TORSO.getIndex(), 56},
		{ARMS.getIndex(), 61},
		{HANDS.getIndex(), 67},
		{LEGS.getIndex(), 70},
		{BOOTS.getIndex(), 79},
	};

	final int[] equipment = new int[SLOTS],
		colors = new int[5];
	@Getter
	int gender;

	/**
	 * The slots that hold an animation's own hand props, a bit per slot index.
	 * The game lays an animation's hand items over the gear once the gear has
	 * settled what it covers, so nothing hides them and they hide nothing.
	 */
	int props;

	Outfit()
	{
	}

	Outfit(Outfit other)
	{
		System.arraycopy(other.equipment, 0, equipment, 0, SLOTS);
		System.arraycopy(other.colors, 0, colors, 0, colors.length);
		gender = other.gender;
		props = other.props;
	}

	static Outfit from(PlayerComposition composition)
	{
		var outfit = new Outfit();
		if (composition == null)
			return outfit;

		int[] ids = composition.getEquipmentIds();
		if (ids != null)
		{
			for (int i = 0; i < Math.min(ids.length, SLOTS); i++)
			{
				int raw = ids[i];
				if (raw >= GAME_ITEM_OFFSET)
					outfit.equipment[i] = ITEM_OFFSET + (raw - GAME_ITEM_OFFSET);
				else if (raw >= GAME_KIT_OFFSET)
					outfit.equipment[i] = KIT_OFFSET + (raw - GAME_KIT_OFFSET);
			}
		}
		outfit.setColors(composition.getColors());
		outfit.setGender(composition.getGender());
		return outfit;
	}

	int getRaw(KitType slot)
	{
		return equipment[slot.getIndex()];
	}

	void setItem(KitType slot, int itemId)
	{
		equipment[slot.getIndex()] = itemId <= 0 ? 0 : ITEM_OFFSET + itemId;
	}

	void setKit(KitType slot, int kitId)
	{
		equipment[slot.getIndex()] = kitId < 0 ? 0 : KIT_OFFSET + kitId;
	}

	void clear(KitType slot)
	{
		equipment[slot.getIndex()] = 0;
	}

	/**
	 * An animation's hand prop in a slot, drawn as the game draws one: over the
	 * gear, whatever the gear covers, and covering nothing itself.
	 */
	void setProp(KitType slot, int itemId)
	{
		setItem(slot, itemId);
		props |= 1 << slot.getIndex();
	}

	/** Whether the slot holds an animation's hand prop. */
	boolean isProp(KitType slot)
	{
		return (props & 1 << slot.getIndex()) != 0;
	}

	boolean isItem(KitType slot)
	{
		return equipment[slot.getIndex()] >= ITEM_OFFSET;
	}

	boolean isKit(KitType slot)
	{
		int v = equipment[slot.getIndex()];
		return v >= KIT_OFFSET && v < ITEM_OFFSET;
	}

	int itemId(KitType slot)
	{
		return equipment[slot.getIndex()] - ITEM_OFFSET;
	}

	int kitId(KitType slot)
	{
		return equipment[slot.getIndex()] - KIT_OFFSET;
	}

	void setGender(int gender)
	{
		this.gender = gender == 1 ? 1 : 0;
	}

	int[] getColors()
	{
		return colors.clone();
	}

	void setColors(int[] source)
	{
		if (source != null)
			System.arraycopy(source, 0, colors, 0, Math.min(source.length, colors.length));
	}

	/**
	 * Returns a copy with empty body slots filled by default kits. Item slots and
	 * already-populated kit slots are untouched, so gear always wins.
	 */
	Outfit withDefaultBody()
	{
		var filled = new Outfit(this);

		// Every body kit is filled here, including ones the gear will cover. Which of
		// them actually render is decided at compose time from each item's wearPos
		// data, the same way the game does it - a platebody hides the arms but a
		// chainbody does not, and only the item itself knows which it is. Deciding
		// that here, by slot type alone, stripped the arms under every torso item.
		for (int[] pair : (gender == 1 ? DEFAULT_KITS_FEMALE : DEFAULT_KITS_MALE))
		{
			if (pair[1] >= 0 && filled.equipment[pair[0]] == 0)
				filled.equipment[pair[0]] = KIT_OFFSET + pair[1];
		}
		return filled;
	}

	boolean isEmpty()
	{
		return Arrays.stream(equipment).allMatch(v -> v == 0);
	}

	@Override
	public String toString()
	{
		var sb = new StringBuilder();
		for (var slot : KitType.values())
		{
			int v = getRaw(slot);
			if (v == 0)
				continue;
			if (sb.length() > 0)
				sb.append(',');
			sb.append(slot.name()).append('=')
				.append(v >= ITEM_OFFSET ? "item:" + (v - ITEM_OFFSET) : "kit:" + (v - KIT_OFFSET));
		}

		if (gender == 1)
			sb.append(sb.length() > 0 ? "," : "").append("gender=female");

		// Body-colour indices into the game's palette tables; the parser already
		// reads this key, but until 2026-08-02 nothing wrote it, so panel-chosen
		// indices silently vanished on every save.
		if (Arrays.stream(colors).anyMatch(c -> c != 0))
			sb.append(sb.length() > 0 ? "," : "").append("colors=")
				.append(Arrays.stream(colors).mapToObj(String::valueOf).collect(Collectors.joining("/")));

		return sb.toString();
	}
}
