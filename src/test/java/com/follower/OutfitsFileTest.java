package com.follower;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static com.follower.OutfitProfileStore.DEFAULT_PROFILE;
import static org.junit.Assert.assertEquals;

/**
 * outfits.tsv holds the ready-made outfit profiles a first install starts
 * with, so a slip in it must fail here. The profiles as the code wrote them
 * before they moved there are kept below as they stood: the same names and
 * outfits, in the same order.
 */
public class OutfitsFileTest
{
	@Test
	public void theFileGivesTheProfilesTheCodeGave() throws Exception
	{
		Map<String, String> before = new LinkedHashMap<>();
		// An empty outfit is the plain default body with no clothing.
		before.put(DEFAULT_PROFILE, "");
		before.put("Melee",
			"HEAD=item:1149,WEAPON=item:21009,TORSO=item:3140,SHIELD=item:21895,ARMS=kit:26,"
				+ "LEGS=item:4087,HAIR=kit:0,HANDS=kit:33,BOOTS=kit:42,JAW=kit:10");
		before.put("Ranged",
			"HEAD=item:2581,WEAPON=item:841,TORSO=item:12596,ARMS=kit:98,LEGS=item:23249,"
				+ "HAIR=kit:128,HANDS=kit:69,BOOTS=kit:79,JAW=kit:296,gender=female");
		before.put("Magic",
			"HEAD=item:7394,AMULET=item:10366,WEAPON=item:1383,TORSO=item:7390,SHIELD=item:6889,"
				+ "ARMS=kit:98,LEGS=item:7386,HAIR=kit:143,HANDS=kit:69,BOOTS=item:2579,"
				+ "JAW=kit:296,gender=female");

		Field field = OutfitProfileStore.class.getDeclaredField("DEFAULTS");
		field.setAccessible(true);
		Map<?, ?> now = (Map<?, ?>) field.get(null);
		assertEquals(new ArrayList<>(before.entrySet()), new ArrayList<>(now.entrySet()));
	}
}
