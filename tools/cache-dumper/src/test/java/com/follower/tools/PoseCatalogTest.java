package com.follower.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The posing list's builder, and the shipped list against the game cache on
 * this machine: every row moves the player's skeleton, holds what the cache
 * says and loops as it says. That is the check runPoseCatalog --check makes,
 * and it is skipped where there is no cache to make it against.
 */
public class PoseCatalogTest
{
	private static final Path CACHE = Paths.get(System.getProperty("user.home"),
		".runelite", "jagexcache", "oldschool", "LIVE");

	private static final Path SHIPPED = Paths.get("src/main/resources/com/follower/poses.tsv");

	/** The cache, read once for the tests that need it. */
	private static PoseCatalog cached;

	private static PoseCatalog catalog() throws IOException
	{
		Assume.assumeTrue("no game cache on this machine", Files.isDirectory(CACHE));
		if (cached == null)
		{
			cached = new PoseCatalog();
			cached.loadCache();
		}
		return cached;
	}

	@Test
	public void theShippedListAgreesWithTheCache() throws IOException
	{
		PoseCatalog catalog = catalog();
		assertEquals(Collections.emptyList(),
			catalog.check(Files.readAllLines(SHIPPED, StandardCharsets.UTF_8)));
	}

	@Test
	public void aCreaturesAnimationIsCaught() throws IOException
	{
		// SWARM_WALK, on a swarm's skeleton: a follower struck in it would be
		// twisted out of shape.
		List<String> problems = catalog().check(Collections.singletonList(
			"0\tSwarm\tOther\tonce\tkeep\tkeep\t0\tC\t-1\t4"));
		assertTrue(problems.toString(), problems.contains("0 Swarm: not on the player skeleton"));
	}

	@Test
	public void aMoveBetweenStancesIsNoStance()
	{
		List<String> none = Collections.emptyList();
		assertFalse(PoseCatalog.holds(-1, 6, Collections.singletonList("HUMAN_READY_INTO_REAL_SWIMMING")));
		assertFalse(PoseCatalog.holds(-1, 5, Collections.singletonList("HUMAN_STYMPHIKE_IDLE_START")));
		assertTrue("a TO that is no move", PoseCatalog.holds(-1, 20,
			Collections.singletonList("DTTD_BENT_TO_LISTEN_AT_DOOR_IDLE")));
		assertTrue("a round trip from a stance and back", PoseCatalog.holds(-1, 5,
			Collections.singletonList("MYQ3_READY_TO_CRAWL_CRAWL_CRAWL_TO_READY")));
		assertTrue("a loop the game makes is held whatever its name", PoseCatalog.holds(2, 5,
			Collections.singletonList("HUMAN_READY_INTO_REAL_SWIMMING")));
		assertFalse(PoseCatalog.holds(-1, 5, none));
	}

	@Test
	public void rowsThatReadTheSameAreNumberedWhateverTheirTiers()
	{
		List<PoseCatalog.Row> rows = new ArrayList<>(Arrays.asList(
			row("Sit", true, 'A'), row("Sit", true, 'B'), row("Sit", false, 'B'),
			row("Stake", false, 'B'), row("Stake", false, 'B'), row("Stake (2)", false, 'C')));
		PoseCatalog.number(rows);
		List<String> names = new ArrayList<>();
		for (PoseCatalog.Row row : rows)
		{
			names.add(row.name);
		}
		assertEquals("the named row keeps its name, a played-once one is told apart already, and a"
				+ " name that ends in a number is numbered on from its stem",
			Arrays.asList("Sit", "Sit (2)", "Sit", "Stake", "Stake (2)", "Stake (3)"), names);
	}

	@Test
	public void aWalkWithABookIsAWalkAndAWorkbenchIsNoBench()
	{
		assertEquals("Standing and walking", PoseCatalog.group("HUMAN_WEAPON_STAFF_BOOK01_WALK"));
		assertEquals("Standing and walking", PoseCatalog.group("HUMAN_WEAPON_STAFF_BOOK01_RUN"));
		assertEquals("Standing and walking", PoseCatalog.group("WALK_FORWARD_CLUE_SCROLL"));
		assertEquals("Standing and walking", PoseCatalog.group("RUNNING_CLUE_SCROLL"));
		assertEquals("Reading and writing", PoseCatalog.group("HUMAN_READBOOK"));
		assertEquals("Skilling", PoseCatalog.group("HUMAN_WORKBENCH_CRAFTING_LOOP"));
		assertEquals("a bench is still a seat", "Sitting and resting", PoseCatalog.group("GARDEN_BENCH"));
	}

	private static PoseCatalog.Row row(String name, boolean hold, char tier)
	{
		PoseCatalog.Row row = new PoseCatalog.Row();
		row.name = name;
		row.hold = hold;
		row.tier = tier;
		return row;
	}
}
