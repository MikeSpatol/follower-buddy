package com.follower;

import java.lang.reflect.Proxy;
import java.util.Random;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link Outfit} against the class it replaced (kept in
 * {@code appearance.before}): random outfits built the same way through both
 * - items, kits, props, cleared slots, colours of every length, both genders,
 * and outfits read from a composition - must write the same text, agree on
 * emptiness and on equality with each other and with their copies, and fill
 * in the same default body. Lombok's hash is not the hand-written one, so
 * only its contract is held: equal outfits hash alike. Nothing keys a map by
 * an outfit.
 */
public class OutfitDiffTest
{
	/** Random outfit pairs; {@code -Doutfitdiff.outfits=N} for a longer run. */
	private static final int OUTFITS = Integer.getInteger("outfitdiff.outfits", 50_000);

	private static PlayerComposition composition(int[] ids, int[] colours, int gender)
	{
		return (PlayerComposition) Proxy.newProxyInstance(PlayerComposition.class.getClassLoader(),
			new Class<?>[]{PlayerComposition.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getEquipmentIds":
						return ids;
					case "getColors":
						return colours;
					case "getGender":
						return gender;
					default:
						return null;
				}
			});
	}

	@Test
	public void everyOutfitBehavesTheSame()
	{
		Random random = new Random(5);
		KitType[] slots = KitType.values();
		for (int i = 0; i < OUTFITS; i++)
		{
			Outfit now = new Outfit();
			Outfit now2 = new Outfit();
			BeforeOutfit before = new BeforeOutfit();
			BeforeOutfit before2 = new BeforeOutfit();
			for (int pass = 0; pass < 2; pass++)
			{
				Outfit n = pass == 0 ? now : now2;
				BeforeOutfit o = pass == 0 ? before : before2;
				for (KitType slot : slots)
				{
					int kind = random.nextInt(5);
					int id = random.nextInt(3) == 0 ? random.nextInt(3) - 1 : random.nextInt(30000);
					if (kind == 0)
					{
						n.setItem(slot, id);
						o.setItem(slot, id);
					}
					else if (kind == 1)
					{
						n.setKit(slot, id);
						o.setKit(slot, id);
					}
					else if (kind == 2 && random.nextInt(6) == 0)
					{
						n.setProp(slot, id);
						o.setProp(slot, id);
					}
				}
				int[] colours = new int[random.nextInt(7)];
				for (int j = 0; j < colours.length; j++)
				{
					colours[j] = random.nextInt(3) == 0 ? 0 : random.nextInt(30);
				}
				if (random.nextBoolean())
				{
					n.setColors(colours);
					o.setColors(colours);
				}
				int gender = random.nextInt(3);
				n.setGender(gender);
				o.setGender(gender);
				if (random.nextInt(4) == 0)
				{
					for (KitType slot : slots)
					{
						if (random.nextBoolean())
						{
							n.clear(slot);
							o.clear(slot);
						}
					}
				}
			}
			String what = "outfit " + i + ": " + before;
			assertEquals(what, before.toString(), now.toString());
			assertEquals(what, before.isEmpty(), now.isEmpty());
			assertEquals(what, before.equals(before2), now.equals(now2));
			assertEquals(what, before.withDefaultBody().toString(), now.withDefaultBody().toString());
			assertEquals(what, before.withDefaultBody().equals(before), now.withDefaultBody().equals(now));
			Outfit copy = new Outfit(now);
			assertTrue(what, copy.equals(now) && now.equals(copy));
			assertEquals(what, now.hashCode(), copy.hashCode());
			assertEquals(what, before.toString(), copy.toString());
			if (now.equals(now2))
			{
				assertEquals(what, now.hashCode(), now2.hashCode());
			}
			assertTrue(what, !now.equals(null) && !now.equals(before) && now.equals(now));
		}
	}

	@Test
	public void outfitsReadFromACompositionAreTheSame()
	{
		Random random = new Random(6);
		for (int i = 0; i < OUTFITS / 5; i++)
		{
			int[] ids = random.nextInt(20) == 0 ? null : new int[random.nextInt(16)];
			for (int j = 0; ids != null && j < ids.length; j++)
			{
				int kind = random.nextInt(4);
				ids[j] = kind == 0 ? 0 : kind == 1 ? 256 + random.nextInt(2000) : kind == 2 ? 2048 + random.nextInt(30000)
					: random.nextInt(4000) - 100;
			}
			int[] colours = random.nextInt(10) == 0 ? null : new int[random.nextInt(7)];
			for (int j = 0; colours != null && j < colours.length; j++)
			{
				colours[j] = random.nextInt(30);
			}
			PlayerComposition composition = random.nextInt(30) == 0 ? null : composition(ids, colours, random.nextInt(3));
			Outfit now = Outfit.from(composition);
			BeforeOutfit before = BeforeOutfit.from(composition);
			assertEquals("composition " + i, before.toString(), now.toString());
			assertEquals("composition " + i, before.isEmpty(), now.isEmpty());
			for (KitType slot : KitType.values())
			{
				assertEquals("composition " + i + " " + slot, before.getRaw(slot), now.getRaw(slot));
			}
		}
	}

	@Test
	public void outfitsParsedAlikeAreEqualAndHashAlike()
	{
		Outfit x = OutfitParser.parse("HEAD=item:1149,gender=female,colors=1/2/3/4/5");
		Outfit y = OutfitParser.parse("HEAD=item:1149,gender=female,colors=1/2/3/4/5");
		assertTrue(x != y && x.equals(y));
		assertEquals(x.hashCode(), y.hashCode());
	}
}
