package com.follower.appearance;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.kit.KitType;

/**
 * Rebuilds a player model from its parts, the same way the game does: load each
 * worn model and body kit model from the cache, apply that part's colour and
 * texture replacements, merge everything, then light it.
 *
 * <p>Must be called on the client thread.
 */
@Slf4j
@Singleton
public class AppearanceComposer
{
	/**
	 * Lighting applied to the composed model. Verified 2026-08-02 against the
	 * deobfuscated client: PlayerComposition builds the player with
	 * {@code toModel(64, 850, -30, -50, -30)}, matching these exactly.
	 */
	private static final int AMBIENT = 64;
	private static final int CONTRAST = 850;
	private static final int LIGHT_X = -30;
	private static final int LIGHT_Y = -50;
	private static final int LIGHT_Z = -30;

	/**
	 * Interface models are lit DIFFERENTLY from actors - verified in the client:
	 * widget models get {@code calculateNormals(64, 768, -50, -10, -50)}, a softer
	 * contrast and a front-left light, where actors get {@code (64, 850, -30,
	 * -50, -30)}. Lighting a chathead with the actor constants is what made it
	 * shade differently from a real dialog's head.
	 */
	private static final int WIDGET_CONTRAST = 768;
	private static final int WIDGET_LIGHT_X = -50;
	private static final int WIDGET_LIGHT_Y = -10;
	private static final int WIDGET_LIGHT_Z = -50;

	private final Client client;
	private final ModelRepository repository;

	/**
	 * Exact body recolours captured from the live client by {@code ::follower
	 * palette}: source colour -> replacement, both packed HSL, applied to the merged
	 * model exactly the way PlayerComposition does. While any pairs are present they
	 * take the place of the palette-index recolouring in {@link #applyBodyColors}.
	 */
	private final java.util.Map<Short, Short> exactPairs = new java.util.LinkedHashMap<>();

	public void setExactPairs(java.util.Map<Short, Short> pairs)
	{
		exactPairs.clear();
		if (pairs != null)
		{
			exactPairs.putAll(pairs);
		}
	}

	public java.util.Map<Short, Short> getExactPairs()
	{
		return new java.util.LinkedHashMap<>(exactPairs);
	}

	@Inject
	public AppearanceComposer(Client client, ModelRepository repository)
	{
		this.client = client;
		this.repository = repository;
	}

	/**
	 * @return a composed model, or null if the dump is missing or has no data for
	 * any of the outfit's slots (caller should fall back to capture).
	 */
	public Model compose(Outfit outfit)
	{
		if (!repository.isLoaded())
		{
			return null;
		}

		Outfit resolved = outfit.withDefaultBody();
		List<ModelData> parts = new ArrayList<>();
		List<String> missing = new ArrayList<>();

		// Body kits hidden by the gear on top of them.
		java.util.Set<KitType> hidden = hiddenSlots(resolved);

		for (KitType slot : KitType.values())
		{
			int raw = resolved.getRaw(slot);
			if (raw == 0)
			{
				continue;
			}

			// Skip whatever the gear covers: the arms under a platebody, the hair and
			// beard under a full helm, the shield behind a two-hander. Drawing both is
			// what pushes one through the other.
			if (hidden.contains(slot))
			{
				continue;
			}

			boolean item = raw >= Outfit.ITEM_OFFSET;
			int id = item ? raw - Outfit.ITEM_OFFSET : raw - Outfit.KIT_OFFSET;
			ModelRepository.Entry entry = item ? repository.item(id) : repository.kit(id);

			if (entry == null)
			{
				missing.add(slot.name() + " " + (item ? "item " : "kit ") + id);
				continue;
			}

			// Parts stay in their authored source colours; body colouring happens
			// post-merge via the palette tables, exactly as the client does it.
			addPart(parts, entry, resolved.getGender());
		}

		if (!missing.isEmpty())
		{
			log.debug("Model dump has no data for: {}", String.join(", ", missing));
		}

		if (parts.isEmpty())
		{
			return null;
		}

		ModelData merged = client.mergeModels(parts.toArray(new ModelData[0]));
		if (merged == null)
		{
			return null;
		}

		if (!exactPairs.isEmpty())
		{
			// The client's own mechanism: plain find/replace on the merged model.
			merged = merged.cloneColors();
			for (java.util.Map.Entry<Short, Short> pair : exactPairs.entrySet())
			{
				merged.recolor(pair.getKey(), pair.getValue());
			}
		}
		else
		{
			merged = applyBodyColors(merged, resolved.getColors());
		}

		// The ground clearance offset is applied when positioning, not baked into the
		// vertices - it belongs in world space alongside the terrain height, and this
		// keeps the composed model reusable across positions.
		return merged.light(AMBIENT, CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
	}

	/**
	 * Composes the outfit's dialogue chathead: the game's own separate head models
	 * (helm head variant, hair chathead, jaw chathead), coloured through the same
	 * palette as the body and lit the same way. These models carry the skeletons
	 * that talk animations are authored for, which the body model does not.
	 *
	 * @return the lit chathead, or null when the dump has no head data for this
	 * outfit (caller should fall back to cropping the body model)
	 */
	public Model composeChathead(Outfit outfit)
	{
		if (!repository.isLoaded() || outfit == null)
		{
			return null;
		}

		Outfit resolved = outfit.withDefaultBody();
		java.util.Set<KitType> hidden = hiddenSlots(resolved);
		List<ModelData> parts = new ArrayList<>();

		// Same wearPos rules as the body: a full helm hides hair and jaw in the
		// dialogue head too; a med helm hides hair but keeps the beard.
		if (resolved.isItem(KitType.HEAD))
		{
			addHeadPart(parts, repository.item(resolved.itemId(KitType.HEAD)), resolved.getGender());
		}
		if (!hidden.contains(KitType.HAIR) && resolved.isKit(KitType.HAIR))
		{
			addHeadPart(parts, repository.kit(resolved.kitId(KitType.HAIR)), resolved.getGender());
		}
		if (!hidden.contains(KitType.JAW) && resolved.isKit(KitType.JAW))
		{
			addHeadPart(parts, repository.kit(resolved.kitId(KitType.JAW)), resolved.getGender());
		}

		if (parts.isEmpty())
		{
			return null;
		}

		ModelData merged = client.mergeModels(parts.toArray(new ModelData[0]));
		if (merged == null)
		{
			return null;
		}

		if (!exactPairs.isEmpty())
		{
			merged = merged.cloneColors();
			for (java.util.Map.Entry<Short, Short> pair : exactPairs.entrySet())
			{
				merged.recolor(pair.getKey(), pair.getValue());
			}
		}
		else
		{
			merged = applyBodyColors(merged, resolved.getColors());
		}

		return merged.light(AMBIENT, WIDGET_CONTRAST,
			WIDGET_LIGHT_X, WIDGET_LIGHT_Y, WIDGET_LIGHT_Z);
	}

	/** Loads an entry's dialogue-head models with its authored recolours applied. */
	private void addHeadPart(List<ModelData> parts, ModelRepository.Entry entry, int gender)
	{
		int[] modelIds = entry == null ? null : entry.headModels(gender);
		if (modelIds == null)
		{
			return;
		}

		for (int modelId : modelIds)
		{
			if (modelId < 0)
			{
				continue;
			}

			ModelData data = client.loadModelData(modelId);
			if (data == null)
			{
				continue;
			}

			if (hasPairs(entry.cf, entry.cr))
			{
				data = data.cloneColors();
				for (int i = 0; i < entry.cf.length && i < entry.cr.length; i++)
				{
					data.recolor(entry.cf[i], entry.cr[i]);
				}
			}
			if (hasPairs(entry.tf, entry.tr))
			{
				data = data.cloneTextures();
				for (int i = 0; i < entry.tf.length && i < entry.tr.length; i++)
				{
					data.retexture(entry.tf[i], entry.tr[i]);
				}
			}

			parts.add(data);
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
	 */
	private java.util.Set<KitType> hiddenSlots(Outfit outfit)
	{
		java.util.Set<KitType> hidden = java.util.EnumSet.noneOf(KitType.class);
		KitType[] slots = KitType.values();

		for (KitType slot : slots)
		{
			if (!outfit.isItem(slot))
			{
				continue;
			}

			ModelRepository.Entry entry = repository.item(outfit.itemId(slot));
			if (entry == null)
			{
				continue;
			}

			for (Integer covered : new Integer[]{entry.wp2, entry.wp3})
			{
				if (covered != null && covered >= 0 && covered < slots.length)
				{
					hidden.add(slots[covered]);
				}
			}
		}

		return hidden;
	}

	/**
	 * Extracts the client's body-colour palette by differencing.
	 *
	 * <p>The client colours a player by two plain find/replace passes per colour slot
	 * over the merged model, using palette tables hardcoded in the client and not
	 * reachable from a plugin. But the tables can be read out empirically: compose
	 * this outfit with NO body recolouring, then diff unlit face colours against the
	 * client's own build of the same outfit, where the palette has already been
	 * applied. Faces are compared positionally - both builds merge the same parts in
	 * the same slot order, proven by exact face-count and priority matches - so every
	 * differing face yields one exact (find -> replace) pair from Jagex's tables.
	 *
	 * <p>This replaces guessing at hair colours entirely: the pair for the player's
	 * current hair IS the table entry, highlight included, since base and highlight
	 * are separate source colours with separate replacements.
	 */
	public List<String> comparePalette(Outfit outfit, Model clientModel,
		java.util.Map<Short, Short> collect)
	{
		List<String> out = new ArrayList<>();
		if (!repository.isLoaded() || clientModel == null)
		{
			out.add("Need the model dump and a visible player model.");
			return out;
		}

		Outfit resolved = outfit.withDefaultBody();
		java.util.Set<KitType> hidden = hiddenSlots(resolved);

		List<ModelData> parts = new ArrayList<>();
		List<KitType> partSlots = new ArrayList<>();

		for (KitType slot : KitType.values())
		{
			int raw = resolved.getRaw(slot);
			if (raw == 0 || hidden.contains(slot))
			{
				continue;
			}

			boolean item = raw >= Outfit.ITEM_OFFSET;
			int id = item ? raw - Outfit.ITEM_OFFSET : raw - Outfit.KIT_OFFSET;
			ModelRepository.Entry entry = item ? repository.item(id) : repository.kit(id);
			if (entry == null)
			{
				continue;
			}

			int before = parts.size();
			addPart(parts, entry, resolved.getGender());
			while (partSlots.size() < parts.size())
			{
				partSlots.add(slot);
			}
			if (parts.size() == before)
			{
				out.add(slot + ": no models loaded, attribution may shift");
			}
		}

		ModelData merged = client.mergeModels(parts.toArray(new ModelData[0]));
		if (merged == null)
		{
			out.add("Merge failed.");
			return out;
		}

		// Live player models carry no unlit colours (getUnlitFaceColors() is null on
		// that path), so the comparison runs on LIT colours. Lighting rewrites only
		// the low 7 luminance bits of a packed colour; hue and saturation pass
		// through, so the client's lit faces reveal the replacement's hue+sat
		// directly. The luminance is recovered by brute force below.
		short[] unlit = merged.getFaceColors();
		Model mineLit = merged.shallowCopy().cloneColors()
			.light(AMBIENT, CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
		if (unlit == null || mineLit == null)
		{
			out.add("Could not light the composed model.");
			return out;
		}

		int[] mc1 = mineLit.getFaceColors1();
		int[] cc1 = clientModel.getFaceColors1();
		int[] mc2 = mineLit.getFaceColors2();
		int[] cc2 = clientModel.getFaceColors2();
		int[] mc3 = mineLit.getFaceColors3();
		int[] cc3 = clientModel.getFaceColors3();
		if (mc1 == null || cc1 == null || mc1.length != cc1.length)
		{
			out.add("Lit face arrays unavailable or mismatched ("
				+ (mc1 == null ? "-" : mc1.length) + " vs "
				+ (cc1 == null ? "-" : cc1.length) + "), aborting.");
			return out;
		}

		// Face index -> contributing slot, from each part's face count in merge order.
		int[] partEnds = new int[parts.size()];
		int running = 0;
		for (int i = 0; i < parts.size(); i++)
		{
			short[] colors = parts.get(i).getFaceColors();
			running += colors == null ? 0 : colors.length;
			partEnds[i] = running;
		}

		// Group differing faces by their source colour in my un-recoloured build.
		java.util.Map<Short, List<Integer>> groups = new java.util.LinkedHashMap<>();
		java.util.Map<Short, java.util.Set<KitType>> where = new java.util.HashMap<>();

		int part = 0;
		for (int face = 0; face < mc1.length; face++)
		{
			while (part < partEnds.length - 1 && face >= partEnds[part])
			{
				part++;
			}
			if (mc1[face] == cc1[face] && mc2[face] == cc2[face] && mc3[face] == cc3[face])
			{
				continue;
			}

			groups.computeIfAbsent(unlit[face], k -> new ArrayList<>()).add(face);
			where.computeIfAbsent(unlit[face], k -> java.util.EnumSet.noneOf(KitType.class))
				.add(partSlots.get(part));
		}

		if (groups.isEmpty())
		{
			out.add("All " + mc1.length + " faces already match - the client applied "
				+ "no body recolouring to this outfit.");
			return out;
		}

		out.add(groups.size() + " recoloured source colours over " + mc1.length + " faces:");
		for (java.util.Map.Entry<Short, List<Integer>> group : groups.entrySet())
		{
			short find = group.getKey();
			List<Integer> faces = group.getValue();

			// Any non-hidden face's lit colour carries the replacement's hue+sat.
			int hueSat = -1;
			for (int face : faces)
			{
				if (cc3[face] != -2)
				{
					hueSat = cc1[face] & 0xFF80;
					break;
				}
			}
			if (hueSat < 0)
			{
				out.add(String.format("%s: %s x%d - every face hidden, cannot recover",
					where.get(find), describeHsl(find), faces.size()));
				continue;
			}

			// Recover luminance: relight with each candidate and keep those whose lit
			// output matches the client's on every face in the group.
			List<Integer> lums = new ArrayList<>();
			for (int lum = 0; lum < 128; lum++)
			{
				short candidate = (short) (hueSat | lum);
				ModelData work = merged.shallowCopy().cloneColors();
				work.recolor(find, candidate);
				Model lit = work.light(AMBIENT, CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
				if (lit == null)
				{
					continue;
				}

				int[] wc1 = lit.getFaceColors1();
				int[] wc2 = lit.getFaceColors2();
				int[] wc3 = lit.getFaceColors3();
				boolean all = true;
				for (int face : faces)
				{
					if (wc1[face] != cc1[face] || wc2[face] != cc2[face] || wc3[face] != cc3[face])
					{
						all = false;
						break;
					}
				}
				if (all)
				{
					lums.add(lum);
				}
			}

			if (lums.isEmpty())
			{
				out.add(String.format("%s: %s x%d -> hue/sat %d but NO luminance "
						+ "reproduces the client - alignment or lighting is off",
					where.get(find), describeHsl(find), faces.size(), hueSat >> 7));
				continue;
			}

			short replacement = (short) (hueSat | lums.get(0));
			if (collect != null)
			{
				collect.put(find, replacement);
			}
			out.add(String.format("%s: %s -> %s x%d  (raw %d -> %d)%s",
				where.get(find), describeHsl(find), describeHsl(replacement),
				faces.size(), find, replacement,
				lums.size() > 1 ? "  [" + lums.size() + " luminances fit: " + lums + "]" : ""));
		}
		return out;
	}

	private static String describeHsl(short packed)
	{
		return String.format("h%02d s%d l%03d",
			HslColor.hue(packed), HslColor.saturation(packed), HslColor.luminance(packed));
	}

	/** Reports which slots the current gear covers, and which item covers each. */
	public List<String> describeHidden(Outfit outfit)
	{
		Outfit resolved = outfit.withDefaultBody();
		List<String> lines = new ArrayList<>();
		KitType[] slots = KitType.values();

		for (KitType slot : slots)
		{
			if (!resolved.isItem(slot))
			{
				continue;
			}

			int itemId = resolved.itemId(slot);
			ModelRepository.Entry entry = repository.item(itemId);
			if (entry == null)
			{
				lines.add(slot + ": item " + itemId + " not in dump");
				continue;
			}

			List<String> covers = new ArrayList<>();
			for (Integer covered : new Integer[]{entry.wp2, entry.wp3})
			{
				if (covered != null && covered >= 0 && covered < slots.length)
				{
					covers.add(slots[covered].name().toLowerCase(java.util.Locale.ROOT));
				}
			}

			lines.add(slot.name().toLowerCase(java.util.Locale.ROOT) + ": "
				+ (entry.n == null ? "item " + itemId : entry.n)
				+ (covers.isEmpty() ? " hides nothing" : " hides " + String.join(" + ", covers)));
		}

		if (lines.isEmpty())
		{
			lines.add("No gear equipped, so nothing is hidden.");
		}
		return lines;
	}

	private void addPart(List<ModelData> parts, ModelRepository.Entry entry, int gender)
	{
		int[] modelIds = entry.models(gender);
		if (modelIds == null)
		{
			return;
		}

		for (int modelId : modelIds)
		{
			if (modelId < 0)
			{
				continue;
			}

			ModelData data = client.loadModelData(modelId);
			if (data == null)
			{
				log.debug("Model {} not in cache", modelId);
				continue;
			}

			// loadModelData shares arrays between callers; clone before mutating.
			if (hasPairs(entry.cf, entry.cr))
			{
				data = data.cloneColors();
				for (int i = 0; i < entry.cf.length && i < entry.cr.length; i++)
				{
					data.recolor(entry.cf[i], entry.cr[i]);
				}
			}

			if (hasPairs(entry.tf, entry.tr))
			{
				data = data.cloneTextures();
				for (int i = 0; i < entry.tf.length && i < entry.tr.length; i++)
				{
					data.retexture(entry.tf[i], entry.tr[i]);
				}
			}

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

	/** Applies the five body-colour indices through the game's own palette tables. */
	private ModelData applyBodyColors(ModelData merged, int[] colorIndices)
	{
		if (colorIndices == null)
		{
			return merged;
		}

		merged = merged.cloneColors();
		for (int slot = 0; slot < colorIndices.length && slot < 5; slot++)
		{
			short[] table = GamePalette.table(slot);
			int index = colorIndices[slot];
			if (table == null || index < 0 || index >= table.length)
			{
				continue;
			}

			merged.recolor(GamePalette.find(slot), table[index]);
			if (slot == 0 && index < GamePalette.HAIR_HIGHLIGHT.length)
			{
				// The highlight is its own authored colour with its own table entry,
				// never derived from the base - deriving it is what kept hair
				// almost-but-not-quite right for months.
				merged.recolor(GamePalette.HAIR_HIGHLIGHT_FIND, GamePalette.HAIR_HIGHLIGHT[index]);
			}
		}

		return merged;
	}

	private static boolean hasPairs(short[] find, short[] replace)
	{
		return find != null && replace != null && find.length > 0 && replace.length > 0;
	}
}
