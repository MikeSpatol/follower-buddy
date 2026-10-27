package com.follower;

import java.util.*;
import java.util.List;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.Model;
import net.runelite.api.kit.KitType;
import lombok.RequiredArgsConstructor;
import java.util.Set;

/**
 * Rebuilds a player model from its parts, the same way the game does: load each
 * worn model and body kit model from the cache, apply that part's colour and
 * texture replacements, merge everything, then light it.
 *
 * <p>Must be called on the client thread.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AppearanceComposer
{

	final Client client;
	final ModelRepository repository;

	/**
	 * Women's worn models that Jagex rigged to the wrong arm, by item id, with
	 * how far the woman's model sits from the man's.
	 *
	 * <p>An animation moves a model by the labels on its vertices, so a model
	 * labelled for one arm goes wherever that arm goes. The charting quill's
	 * woman's model (59225) is the man's (59224) moved by (0, 7, -1), vertex
	 * for vertex, but labelled for the right hand (27) where the man's is
	 * labelled for the left hand (161). Writing with a quill (13302) writes
	 * with the left hand and holds the scroll still in the right, so on a woman
	 * the quill stayed by the scroll while the empty hand wrote; a woman in the
	 * game gets the same. The man's model is drawn instead, moved to where the
	 * woman's sits, which is her quill on his labels. Every other item a pose
	 * puts in a hand was checked against the cache: none has its two models on
	 * different arms. Both models of the quill sit at offset 0.
	 */
	static final java.util.Map<Integer, int[]> FEMALE_FROM_MALE =
		java.util.Collections.singletonMap(31829, new int[]{0, 7, -1});

	/**
	 * @return a composed model, or null if the dump is missing or has no data for
	 * any of the outfit's slots (caller should fall back to capture).
	 */
	Model compose(Outfit outfit)
	{
		return compose(outfit, null);
	}

	/**
	 * As {@link #compose(Outfit)}, noting in {@code downloading} each model it
	 * went without because the client does not have it yet. A model the client
	 * has never drawn is fetched from the game's servers: the first ask starts
	 * the download and gets nothing, and an ask once it has landed gets it.
	 */
	Model compose(Outfit outfit, List<Integer> downloading)
	{
		if (!repository.isLoaded())
			return null;

		var resolved = outfit.withDefaultBody();
		List<ModelData> parts = new ArrayList<>();

		// Body kits hidden by the gear on top of them.
		var hidden = hiddenSlots(resolved);

		for (var slot : KitType.values())
		{
			int raw = resolved.getRaw(slot);
			if (raw == 0)
				continue;

			// Skip whatever the gear covers: the arms under a platebody, the hair and
			// beard under a full helm, the shield behind a two-hander. Drawing both is
			// what pushes one through the other.
			if (hidden.contains(slot))
				continue;

			boolean item = raw >= Outfit.ITEM_OFFSET;
			int id = item ? raw - Outfit.ITEM_OFFSET : raw - Outfit.KIT_OFFSET;
			var entry = item ? repository.item(id) : repository.kit(id);

			if (entry == null)
				continue;

			// Parts stay in their authored source colours; body colouring happens
			// post-merge via the palette tables, exactly as the client does it.
			int before = parts.size();
			// A woman's model rigged to the wrong arm is drawn from the man's.
			int[] rerig = item && resolved.getGender() == 1 ? FEMALE_FROM_MALE.get(id) : null;
			addPart(parts, entry, rerig != null ? 0 : resolved.getGender(), downloading);
			if (rerig != null)
			{
				for (int i = before; i < parts.size(); i++)
					parts.set(i, parts.get(i).cloneVertices().translate(rerig[0], rerig[1], rerig[2]));
			}
		}

		// The ground clearance offset is applied when positioning, not baked into the
		// vertices - it belongs in world space alongside the terrain height, and this
		// keeps the composed model reusable across positions.
		return mergeAndLight(parts, resolved, 850 /* CONTRAST */, -30 /* LIGHT_X */, -50 /* LIGHT_Y */, -30 /* LIGHT_Z */);
	}

	/**
	 * Merges the parts, colours the body through the palette tables and lights
	 * the result; null when there is nothing to merge.
	 */
	Model mergeAndLight(List<ModelData> parts, Outfit resolved, int contrast, int x, int y, int z)
	{
		var merged = parts.isEmpty() ? null : client.mergeModels(parts.toArray(new ModelData[0]));
		if (merged == null)
			return null;
		// AMBIENT: Lighting applied to the composed model. Verified 2026-08-02 against the
		// deobfuscated client: PlayerComposition builds the player with
		// {@code toModel(64, 850, -30, -50, -30)}, matching these exactly.
		return applyBodyColors(merged, resolved.getColors()).light(64 /* AMBIENT */, contrast, x, y, z);
	}

	/**
	 * Composes the outfit's dialogue chathead: the game's own separate head models
	 * (helm head variant, hair chathead, jaw chathead), coloured through the same
	 * palette as the body and lit the same way. These models carry the skeletons
	 * that talk animations are authored for, which the body model does not.
	 *
	 * <p>Notes in {@code downloading}, as {@link #compose(Outfit, List)} does,
	 * each head model it went without because the client is still fetching it.
	 *
	 * @return the lit chathead, or null when the dump has no head data for this
	 * outfit (caller should fall back to cropping the body model)
	 */
	Model composeChathead(Outfit outfit, List<Integer> downloading)
	{
		if (!repository.isLoaded() || outfit == null)
			return null;

		var resolved = outfit.withDefaultBody();
		var hidden = hiddenSlots(resolved);
		List<ModelData> parts = new ArrayList<>();

		// Same wearPos rules as the body: a full helm hides hair and jaw in the
		// dialogue head too; a med helm hides hair but keeps the beard.
		if (resolved.isItem(KitType.HEAD))
			addHeadPart(parts, repository.item(resolved.itemId(KitType.HEAD)), resolved.getGender(), downloading);
		for (var slot : new KitType[]{KitType.HAIR, KitType.JAW})
		{
			if (!hidden.contains(slot) && resolved.isKit(slot))
				addHeadPart(parts, repository.kit(resolved.kitId(slot)), resolved.getGender(), downloading);
		}

		// WIDGET_CONTRAST: Interface models are lit DIFFERENTLY from actors - verified in the client:
		// widget models get {@code calculateNormals(64, 768, -50, -10, -50)}, a softer
		// contrast and a front-left light, where actors get {@code (64, 850, -30,
		// -50, -30)}. Lighting a chathead with the actor constants is what made it
		// shade differently from a real dialog's head.
		return mergeAndLight(parts, resolved, 768 /* WIDGET_CONTRAST */, -50 /* WIDGET_LIGHT_X */, -10 /* WIDGET_LIGHT_Y */, -50 /* WIDGET_LIGHT_Z */);
	}

	/** Loads an entry's dialogue-head models with its authored recolours applied. */
	void addHeadPart(List<ModelData> parts, ModelRepository.Entry entry, int gender,
		List<Integer> downloading)
	{
		int[] modelIds = entry == null ? null : entry.headModels(gender);
		if (modelIds == null)
			return;

		for (int modelId : modelIds)
		{
			var data = modelId < 0 ? null : client.loadModelData(modelId);
			if (data != null)
				parts.add(entry.recolour(data));
			else if (modelId >= 0 && downloading != null)
				downloading.add(modelId);
		}
	}


	/**
	 * Body-kit slots covered by the equipped gear, taken from each item's wearPos data.
	 *
	 * <p>This is how the game itself decides what to draw. An item declares the slot it
	 * occupies plus up to two more that it <em>hides</em>: a platebody hides the arms
	 * kit, a full helm hides both hair and jaw. Around 2,200 of the 6,300 wearable items
	 * hide something.
	 *
	 * <p>This replaces a single hardcoded rule - "a torso item hides the arms" - which
	 * covered one case out of many and left helmets rendering hair straight through
	 * themselves.
	 *
	 * <p>An animation's hand prop ({@link Outfit#isProp}) is outside the rule: the
	 * game lays it over the gear after the gear has decided what it covers. A felling
	 * axe declares the shield slot hidden, and the axe of a two-handed chop is drawn
	 * in that very slot; the banana a follower eats goes in the shield slot its own
	 * godsword hides.
	 */
	Set<KitType> hiddenSlots(Outfit outfit)
	{
		Set<KitType> hidden = java.util.EnumSet.noneOf(KitType.class);
		var slots = KitType.values();

		for (var slot : slots)
		{
			if (!outfit.isItem(slot) || outfit.isProp(slot))
				continue;

			var entry = repository.item(outfit.itemId(slot));
			if (entry == null)
				continue;

			for (var covered : new Integer[]{entry.wp2, entry.wp3})
			{
				if (covered != null && covered >= 0 && covered < slots.length)
					hidden.add(slots[covered]);
			}
		}

		hidden.removeIf(outfit::isProp);
		return hidden;
	}

	void addPart(List<ModelData> parts, ModelRepository.Entry entry, int gender, List<Integer> downloading)
	{
		int[] modelIds = entry.models(gender);
		if (modelIds == null)
			return;

		for (int modelId : modelIds)
		{
			if (modelId < 0)
				continue;

			var data = client.loadModelData(modelId);
			if (data == null)
			{
				if (downloading != null)
					downloading.add(modelId);
				continue;
			}

			data = entry.recolour(data);

			// Each worn model carries a vertical offset that positions it against the
			// body. Merging without it leaves every piece at its raw origin, so
			// neighbouring pieces sit slightly wrong and clip into one another.
			int offset = entry.offset(gender);
			if (offset != 0)
			{
				data = data.cloneVertices();
				data.translate(0, offset, 0);
			}

			parts.add(data);
		}
	}

	/** Whether any of these models, missing from an earlier build, has landed since. */
	boolean anyArrived(List<Integer> modelIds)
	{
		for (int modelId : modelIds)
		{
			if (client.loadModelData(modelId) != null)
				return true;
		}
		return false;
	}

	/** Applies the five body-colour indices through the game's own palette tables. */
	ModelData applyBodyColors(ModelData merged, int[] colorIndices)
	{
		if (colorIndices == null)
			return merged;

		merged = merged.cloneColors();
		for (int slot = 0; slot < colorIndices.length && slot < 5; slot++)
		{
			short[] table = GamePalette.table(slot);
			int index = colorIndices[slot];
			if (table == null || index < 0 || index >= table.length)
				continue;

			// Each table replaces its own first entry, the default colour.
			merged.recolor(table[0], table[index]);
			if (slot == 0 && index < GamePalette.HAIR_HIGHLIGHT.length)
				// The highlight is its own authored colour with its own table entry,
				// never derived from the base - deriving it is what kept hair
				// almost-but-not-quite right for months.
				merged.recolor(GamePalette.HAIR_HIGHLIGHT_FIND, GamePalette.HAIR_HIGHLIGHT[index]);
		}

		return merged;
	}
}
