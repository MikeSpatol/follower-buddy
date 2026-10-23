// public:file posing
package com.follower.follower;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Advanced posing window's list, as shipped.
 *
 * <p>tools/cache-dumper builds it from the game cache and refuses any
 * animation that does not move the player's skeleton; its --check mode, and
 * the dumper's own PoseCatalogTest, test the shipped file against the cache
 * after a game update. What can be checked without the cache is checked here:
 * every animation and graphic is one the game names, every name can be drawn
 * and reads as no other row does, a held pose is one the game loops, and a
 * body left lying is played once.
 */
public class PoseCatalogTest
{
	/** As PoseCatalog.LONG_LAST_FRAME: a last frame held a minute or more. */
	private static final int LONG_LAST_FRAME = 3000;

	@Test
	public void theListIsThereAndEveryRowIsAnAnimationTheGameNames()
	{
		assertTrue("the list did not load", FilmSet.POSES.size() > 3000);
		Map<Integer, List<String>> names = gameval(AnimationID.class);
		Set<Integer> ids = new HashSet<>();
		for (FilmSet.Pose pose : FilmSet.POSES)
		{
			assertTrue(pose.id + " " + pose.name + " is listed twice", ids.add(pose.id));
			assertTrue(pose.id + " " + pose.name + " is no animation the game names", names.containsKey(pose.id));
		}
	}

	@Test
	public void everyNameIsPlainTextTheWindowCanShow()
	{
		for (FilmSet.Pose pose : FilmSet.POSES)
		{
			assertFalse(pose.id + " has no name", pose.name.trim().isEmpty());
			assertEquals(pose.id + " has spaces at an end", pose.name.trim(), pose.name);
			for (char c : pose.name.toCharArray())
			{
				assertTrue(pose.id + " " + pose.name + " has '" + c + "'", c >= 0x20 && c <= 0x7e);
			}
			assertTrue(pose.id + " starts in lower case", !Character.isLowerCase(pose.name.charAt(0)));
		}
	}

	@Test
	public void noTwoRowsReadTheSameInTheWindow()
	{
		// The window shows a name and whether it is held or played once; a
		// tier B move named as a tier A one is told apart by a number.
		Set<String> shown = new HashSet<>();
		for (FilmSet.Pose pose : FilmSet.POSES)
		{
			assertTrue(pose.id + " reads as another row does: " + pose.name,
				shown.add(pose.name.toLowerCase(Locale.ROOT) + (pose.hold ? "  [hold]" : "  [once]")));
		}
		assertEquals("the emote keeps its name", "Sit", FilmSet.pose(10061).name);
		assertEquals("SITTING_READY is the second", "Sit (2)", FilmSet.pose(1144).name);
	}

	@Test
	public void aRowIsInTheGroupItsMoveBelongsTo()
	{
		assertEquals("Skilling", FilmSet.pose(827).group);
		assertEquals("a workbench is no bench", "Skilling", FilmSet.pose(13694).group);
		for (int walk : new int[]{11943, 11944, 7272, 7273})
		{
			assertEquals("a walk with a book or a scroll is a walk",
				"Standing and walking", FilmSet.pose(walk).group);
		}
	}

	@Test
	public void aHeldRowIsOneTheGameLoopsAndABodyLeftLyingIsPlayedOnce() throws IOException
	{
		Map<Integer, List<String>> names = gameval(AnimationID.class);
		int held = 0;
		int lying = 0;
		for (String[] row : rows())
		{
			int id = Integer.parseInt(row[0]);
			boolean hold = row[3].equals("hold");
			int restart = Integer.parseInt(row[8]);
			int last = Integer.parseInt(row[9]);
			if (last >= LONG_LAST_FRAME)
			{
				lying++;
				assertFalse(id + " " + row[1] + " lies still for " + last + " cycles, and must not be held", hold);
			}
			if (hold)
			{
				held++;
				assertTrue(id + " " + row[1] + " is held but the game neither loops it nor stands in it",
					restart >= 0 || isStance(names.get(id)));
			}
		}
		assertTrue(held > 500);
		assertTrue("the deaths and knockdowns", lying > 20);
	}

	@Test
	public void theHandsAndGraphicsAreWhatTheGameNames() throws IOException
	{
		Set<Integer> graphics = gameval(SpotanimID.class).keySet();
		Map<Integer, String[]> byId = new HashMap<>();
		for (String[] row : rows())
		{
			byId.put(Integer.parseInt(row[0]), row);
			for (String hand : new String[]{row[4], row[5]})
			{
				assertTrue(row[0] + " " + hand, hand.equals("keep") || hand.equals("empty") || hand.matches("\\d+"));
			}
			int graphic = Integer.parseInt(row[6]);
			assertTrue(row[0] + " pairs graphic " + graphic + ", which the game does not name",
				graphic == 0 || graphics.contains(graphic));
		}
		// The cache's own props.
		assertHands(byId, 1350, "empty", "3850");
		assertHands(byId, 5354, "empty", "10485");
		assertHands(byId, 13302, "31829", "31830");
		assertHands(byId, 4961, "9898", "9899");
		assertHands(byId, 4073, "empty", "8798");
		assertHands(byId, 13993, "keep", "keep");
		// And the graphics Jagex's names pair with them.
		assertEquals("1239", byId.get(4751)[6]);
		assertEquals("712", byId.get(4276)[6]);
		assertEquals("713", byId.get(4278)[6]);
		assertEquals("823", byId.get(4959)[6]);
		assertEquals("a pairing the names do not make is left out", "0", byId.get(1374)[6]);
		assertEquals("Trick's bats, which the cache pairs: their own animation is 10504", "2570",
			byId.get(10503)[6]);
		// The second Sailing cape animation holds the Cooking cape's frying
		// pan, so it is not offered as a named cape.
		assertHands(byId, 13074, "empty", "9896");
		assertEquals("B", byId.get(13074)[7]);
	}

	@Test
	public void theNamedRowsComeFirstAndShowAllHidesOnlyTheRest()
	{
		char tier = 'A';
		Map<Character, Integer> counts = new HashMap<>();
		for (FilmSet.Pose pose : FilmSet.POSES)
		{
			assertTrue(pose.id + " is out of tier order", pose.tier >= tier);
			tier = pose.tier;
			counts.merge(pose.tier, 1, Integer::sum);
		}
		assertTrue(counts.get('A') >= 230);
		assertTrue(counts.get('B') > 1000);
		assertTrue(counts.get('C') > 1000);
		assertEquals("the dropdown's first group", "Emotes", FilmSet.GROUPS.get(0));
	}

	private static void assertHands(Map<Integer, String[]> byId, int id, String left, String right)
	{
		assertEquals(id + " left", left, byId.get(id)[4]);
		assertEquals(id + " right", right, byId.get(id)[5]);
	}

	/**
	 * A READY or an IDLE: the game loops a stance as the standing pose. Not a
	 * move from one stance to another, which is named for the move: the swim
	 * into idle and back, looped, jumped 136 units at every wrap (QA, 15 Oct).
	 */
	private static boolean isStance(List<String> names)
	{
		if (names == null)
		{
			return false;
		}
		for (String name : names)
		{
			if (isTransition(name))
			{
				continue;
			}
			for (String word : name.split("_"))
			{
				if (word.equals("READY") || word.equals("IDLE"))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** As PoseCatalog.isTransition: a START, an INTO, or a TO that leaves a stance or arrives at one. */
	private static boolean isTransition(String name)
	{
		String[] words = name.split("_");
		boolean leaves = false;
		boolean arrives = false;
		for (int i = 0; i < words.length; i++)
		{
			if (words[i].equals("START") || words[i].equals("INTO"))
			{
				return true;
			}
			if (words[i].equals("TO"))
			{
				leaves |= i > 0 && (words[i - 1].equals("READY") || words[i - 1].equals("IDLE"));
				arrives |= i + 1 < words.length && (words[i + 1].equals("READY") || words[i + 1].equals("IDLE"));
			}
		}
		return leaves != arrives;
	}

	@Test
	public void aMoveBetweenStancesIsPlayedOnce()
	{
		for (int id : new int[]{3416, 3417, 14286})
		{
			assertFalse(id + " " + FilmSet.pose(id).name + " moves between stances and must not be held",
				FilmSet.pose(id).hold);
		}
		assertTrue("the swimming idle itself is still held", FilmSet.pose(3418).hold);
	}

	private static List<String[]> rows() throws IOException
	{
		List<String[]> out = new ArrayList<>();
		try (InputStream in = PoseCatalogTest.class.getResourceAsStream("/com/follower/poses.tsv");
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("id\t"))
				{
					out.add(line.split("\t"));
				}
			}
		}
		return out;
	}

	private static Map<Integer, List<String>> gameval(Class<?> type)
	{
		Map<Integer, List<String>> out = new HashMap<>();
		for (Field field : type.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					out.computeIfAbsent(field.getInt(null), id -> new ArrayList<>()).add(field.getName());
				}
				catch (IllegalAccessException e)
				{
					throw new AssertionError(e);
				}
			}
		}
		return out;
	}
}
