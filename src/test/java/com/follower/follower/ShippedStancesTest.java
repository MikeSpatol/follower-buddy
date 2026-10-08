package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The stance library a new install starts with.
 *
 * <p>It is built by tools/cache-dumper from learned stances, Weapon Animation
 * Replacer's sets and the game cache, and the tool checks every id against the
 * cache's player skeleton as it writes. What can be checked without the cache
 * is checked here: that every id is one the game names, and that weapons the
 * sources agree on came through the merge as they should.
 */
public class ShippedStancesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The combat tab's stab sword category: daggers, shortswords, rapiers. */
	private static final int STAB_SWORD = 17;

	private StanceLibrary library;

	@Before
	public void setUp() throws IOException
	{
		library = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(folder.newFolder().toPath());
	}

	private void carries(int weapon, int idle, int walk, int run)
	{
		StanceLibrary.Stance stance = library.forWeapon(weapon);
		assertEquals("idle of " + weapon, idle, stance.idle);
		assertEquals("walk of " + weapon, walk, stance.walk);
		assertEquals("run of " + weapon, run, stance.run);
	}

	@Test
	public void theWhipWalksItsOwnWalkAndLashes()
	{
		carries(4151, 808, 1660, 1661);
		assertEquals(1658, library.attackFor(4151));
	}

	@Test
	public void aGodswordCarriesLikeAGodsword()
	{
		carries(11802, 7053, 7052, 7043);
	}

	@Test
	public void dharoksGreataxeRunsTheRunJagexGaveItLater()
	{
		// Weapon Animation Replacer's author saw 824 before the update; this
		// plugin saw 11466 since, and a live sighting wins.
		carries(4718, 2065, 2064, 11466);
	}

	@Test
	public void theLmsDharoksGreataxeRunsAsEveryOtherGreataxeDoes()
	{
		// Never seen itself, so it borrowed the plain greataxe's pose tuple from
		// before the update. The set and every greataxe seen since say 11466.
		carries(25516, 2065, 2064, 11466);
	}

	@Test
	public void theOrnamentedRuneCrossbowKeepsItsOwnCarry()
	{
		// Grouped with the plain crossbow for its variants, and carried
		// differently: the learned sighting must not be flattened into it.
		carries(26486, 9161, 9164, 9204);
	}

	@Test
	public void theDragonDaggerStabsLungesSlashesAndBlocksAsAStabSword()
	{
		int[] expected = {376, 376, 377, 376};
		for (int style = 0; style < expected.length; style++)
		{
			int kind = library.attackKind(STAB_SWORD, style);
			assertEquals("style " + style, expected[style], library.attackFor(1215, style, kind));
		}
	}

	@Test
	public void theCombatTabTableSaysWhatEachStabSwordButtonDeals()
	{
		assertEquals(StanceLibrary.STAB, library.attackKind(STAB_SWORD, 0));
		assertEquals(StanceLibrary.STAB, library.attackKind(STAB_SWORD, 1));
		assertEquals(StanceLibrary.SLASH, library.attackKind(STAB_SWORD, 2));
		assertEquals(StanceLibrary.STAB, library.attackKind(STAB_SWORD, 3));
		assertEquals("a category the table does not have", -1, library.attackKind(9999, 0));
		assertEquals("a style past the buttons", -1, library.attackKind(STAB_SWORD, 4));
	}

	@Test
	public void aStaffBashesTwoHandedUnlessTheOtherHandHoldsAShield()
	{
		// Staff of air: Weapon Animation Replacer's staff set.
		int bash = library.attackFor(1381);
		assertEquals(AnimationID.HUMAN_STAFFORB_PUMMEL, bash);
		assertEquals(bash, StanceLibrary.withShield(bash, false));
		assertEquals(AnimationID.HUMAN_AXE_CHOP, StanceLibrary.withShield(bash, true));
		assertEquals("the second staff set's bash too",
			AnimationID.HUMAN_AXE_CHOP, StanceLibrary.withShield(AnimationID.HUMAN_STAFF_PUMMEL, true));
		assertEquals("nothing else changes with a shield",
			1658, StanceLibrary.withShield(1658, true));
	}

	@Test
	public void theHastaBashesTwoHandedUnlessTheOtherHandHoldsAShield()
	{
		// Weapon Animation Replacer's hasta keeps the one-handed chop as its
		// crush and the two-handed bash as its "no offhand" crush.
		int crush = library.attackFor(11889, 2, StanceLibrary.CRUSH);
		assertEquals(AnimationID.HUMAN_STAFFORB_PUMMEL, crush);
		assertEquals(AnimationID.HUMAN_AXE_CHOP, StanceLibrary.withShield(crush, true));
	}

	@Test
	public void aCasterHoldingAStaffHasNoCastUntilOneIsLearned()
	{
		// The staff's shipped swing is its bash, which a magic follower must
		// not play; with nothing learned the plugin falls back to its strike.
		assertEquals(0, library.castFor(1381));
		// A powered staff's swing IS a cast, and is used as one.
		assertEquals(AnimationID.HUMAN_CASTWAVE_STAFF, library.castFor(11905));
	}

	@Test
	public void unarmedStillPunches()
	{
		assertEquals(AnimationID.HUMAN_UNARMEDPUNCH, library.attackFor(StanceLibrary.UNARMED));
	}

	@Test
	public void everyShippedAnimationIsOneTheGameNames() throws IOException
	{
		Set<Integer> named = new HashSet<>();
		for (Field field : AnimationID.class.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					named.add(field.getInt(null));
				}
				catch (IllegalAccessException e)
				{
					throw new AssertionError(e);
				}
			}
		}

		List<String> unknown = new ArrayList<>();
		JsonObject stances = resource(StanceLibrary.FILE_NAME);
		for (Map.Entry<String, JsonElement> entry : stances.entrySet())
		{
			for (Map.Entry<String, JsonElement> field : entry.getValue().getAsJsonObject().entrySet())
			{
				for (int id : ids(field.getValue()))
				{
					if (id > 0 && !named.contains(id))
					{
						unknown.add(entry.getKey() + "." + field.getKey() + "=" + id);
					}
				}
			}
		}
		JsonObject kinds = resource(StanceLibrary.KINDS_FILE);
		for (String list : new String[]{"notAttacks", "casts"})
		{
			for (int id : ids(kinds.get(list)))
			{
				if (!named.contains(id))
				{
					unknown.add(list + "=" + id);
				}
			}
		}
		assertTrue("ids the game does not have: " + unknown, unknown.isEmpty());
		assertTrue("the shipped library lost its weapons", stances.size() > 1000);
	}

	@Test
	public void weaponAnimationReplacersNoticeShipsBesideItsData() throws IOException
	{
		// Its licence asks for the notice in whatever is distributed, and the
		// jar is what the Plugin Hub distributes.
		try (Reader reader = new InputStreamReader(
			StanceLibrary.class.getResourceAsStream("/com/follower/THIRD-PARTY-NOTICES.txt"),
			StandardCharsets.UTF_8))
		{
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[4096];
			for (int read; (read = reader.read(buffer)) > 0; )
			{
				text.append(buffer, 0, read);
			}
			assertTrue(text.toString().contains("Copyright (c) 2021, geheur"));
			assertTrue(text.toString().contains("THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS"));
		}
	}

	private static List<Integer> ids(JsonElement element)
	{
		List<Integer> out = new ArrayList<>();
		if (element.isJsonArray())
		{
			for (JsonElement each : element.getAsJsonArray())
			{
				out.add(each.getAsInt());
			}
		}
		else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
		{
			out.add(element.getAsInt());
		}
		return out;
	}

	private static JsonObject resource(String name) throws IOException
	{
		try (Reader reader = new InputStreamReader(
			StanceLibrary.class.getResourceAsStream("/com/follower/" + name), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(reader, JsonObject.class);
		}
	}
}
