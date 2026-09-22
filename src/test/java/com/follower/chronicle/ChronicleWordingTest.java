package com.follower.chronicle;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the page says about how it is written, and what a locked page shows.
 *
 * <p>Play feedback: "one is met" was not fitting for a boss, and the World
 * tab did not say which quest opens a page. So a quest is named as the game
 * names it, a boss is called a boss, and a creature's page says where to
 * find one before it has been met.
 */
public class ChronicleWordingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private ChronicleLoader loader;

	@Before
	public void setUp() throws IOException
	{
		loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
	}

	private static int width(String s)
	{
		return s.length() * 6;
	}

	@Test
	public void aQuestIsNamedAsTheGameNamesIt()
	{
		assertEquals("Cook's Assistant is done",
			ChronicleWindow.unlockLabel(loader.get("quest-cooks-assistant")));
		assertEquals("Priest in Peril is done",
			ChronicleWindow.unlockLabel(loader.get("faction-seven-priestly-warriors")));
		assertEquals("Waterfall Quest is under way",
			ChronicleWindow.unlockLabel(loader.get("location-baxtorian-falls")));
	}

	@Test
	public void twoWaysInAreBothNamed()
	{
		assertEquals("Priest in Peril is done, or you go there",
			ChronicleWindow.unlockLabel(loader.get("world-morytania")));
		assertEquals("The scribe will write this up once Priest in Peril is done, or once you have been there.",
			ChronicleWindow.unwrittenHint(loader.get("world-morytania")));
	}

	@Test
	public void aBossIsCalledABoss()
	{
		assertEquals("the boss is met", ChronicleWindow.unlockLabel(loader.get("boss-giant-mole")));
		assertEquals("The scribe has not met this boss yet.",
			ChronicleWindow.unwrittenHint(loader.get("boss-giant-mole")));
		assertEquals("one is met", ChronicleWindow.unlockLabel(loader.get("enemy-goblin")));
		assertEquals("The scribe has not met one of these yet.",
			ChronicleWindow.unwrittenHint(loader.get("enemy-goblin")));
		assertEquals("The scribe has not been there yet.",
			ChronicleWindow.unwrittenHint(loader.get("location-lumbridge")));
	}

	@Test
	public void aLockedBossPageSaysWhereToFindIt()
	{
		ChronicleEntry mole = loader.get("boss-giant-mole");
		List<ChronicleLayout.Line> lines = ChronicleLayout.lockedPage(mole,
			ChronicleWindow.unwrittenHint(mole), 2000, ChronicleWordingTest::width, ChronicleWordingTest::width);
		assertEquals("BODY:The scribe has not met this boss yet.", lines.get(0).toString());
		assertEquals("HEADING:Where it is found", lines.get(1).toString());
		assertTrue(lines.get(2).toString(), lines.get(2).text.contains("Falador Park"));
	}

	@Test
	public void aLockedQuestPageGivesOnlyTheReason()
	{
		ChronicleEntry cooks = loader.get("quest-cooks-assistant");
		List<ChronicleLayout.Line> lines = ChronicleLayout.lockedPage(cooks,
			ChronicleWindow.unwrittenHint(cooks), 2000, ChronicleWordingTest::width, ChronicleWordingTest::width);
		assertEquals(1, lines.size());
		assertEquals("BODY:The scribe will write this up once Cook's Assistant is done.", lines.get(0).toString());
	}

	@Test
	public void everyBossAndEnemyPageHasSomewhereToBeFound()
	{
		// The locked page promises a place to look; the data has to keep it.
		for (String kind : new String[]{"boss", "enemy"})
		{
			for (ChronicleEntry entry : loader.ofKind(kind))
			{
				List<ChronicleLayout.Line> lines = ChronicleLayout.lockedPage(entry, "x", 2000,
					ChronicleWordingTest::width, ChronicleWordingTest::width);
				assertTrue(entry.id + " has no Where it is found section", lines.size() > 1);
			}
		}
	}
}
