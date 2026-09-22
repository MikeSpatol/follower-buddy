package com.follower.chronicle;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import net.runelite.api.QuestState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which pages can be read, and what writes a new one.
 *
 * <p>Quests are the game's to remember and are read from it; creatures and
 * places are the scribe's, written the moment they are met and kept in the
 * follower's memory from then on.
 */
public class ChronicleProgressTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private FakeGame game;
	private TriggerContext memory;
	private ChronicleLoader loader;
	private ChronicleProgress progress;

	@Before
	public void setUp() throws IOException
	{
		game = new FakeGame();
		memory = new TriggerContext(game.client);
		loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		progress = new ChronicleProgress(game.client, loader, () -> memory);
	}

	@Test
	public void aQuestPageOpensWhenTheQuestIsFinishedAndNotBefore()
	{
		ChronicleEntry cooks = loader.get("quest-cooks-assistant");
		assertFalse("nothing known yet", progress.isReadable(cooks));

		progress.setQuestState("COOKS_ASSISTANT", QuestState.IN_PROGRESS);
		assertFalse("half done is not done", progress.isReadable(cooks));

		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		assertTrue(progress.isReadable(cooks));
	}

	@Test
	public void aQuestsPlacesAreKnownFromItsFirstStep()
	{
		ChronicleEntry falls = loader.get("location-baxtorian-falls");
		assertFalse(progress.isReadable(falls));
		progress.setQuestState("WATERFALL_QUEST", QuestState.IN_PROGRESS);
		assertTrue("the falls are on the map from the start of the quest", progress.isReadable(falls));
	}

	@Test
	public void meetingACreatureWritesItsPageOnceAndKeepsIt()
	{
		ChronicleEntry mole = loader.get("boss-giant-mole");
		assertFalse(progress.isReadable(mole));

		List<ChronicleEntry> written = progress.noteNpc("Giant Mole");
		assertEquals("the page, the first time", 1, written.size());
		assertEquals(mole.id, written.get(0).id);
		assertTrue(progress.isReadable(mole));

		assertTrue("and nothing new the second time", progress.noteNpc("Giant Mole").isEmpty());
		assertTrue("it went into the memory, where it is saved from", memory.isChronicled(mole.id));
	}

	@Test
	public void namesMatchHoweverTheyAreCapitalised()
	{
		assertEquals(1, progress.noteNpc("king black dragon").size());
		assertTrue(progress.isReadable(loader.get("boss-king-black-dragon")));
	}

	@Test
	public void aStrangerWritesNothing()
	{
		assertTrue(progress.noteNpc("Cabbage").isEmpty());
		assertTrue(progress.noteNpc(null).isEmpty());
		assertTrue(progress.noteNpc("").isEmpty());
	}

	@Test
	public void walkingIntoAPlaceWritesItsPage()
	{
		ChronicleEntry lumbridge = loader.get("location-lumbridge");
		assertFalse(progress.isReadable(lumbridge));
		assertEquals(1, progress.noteRegion(12850).size());
		assertTrue(progress.isReadable(lumbridge));
		assertTrue("somewhere with no page writes nothing", progress.noteRegion(9999).isEmpty());
	}

	@Test
	public void aPageWithTwoWaysInOpensByEither()
	{
		ChronicleEntry morytania = loader.get("world-morytania");
		assertFalse(progress.isReadable(morytania));
		progress.noteRegion(13878);
		assertTrue("walked into Canifis", progress.isReadable(morytania));

		setUp0();
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.FINISHED);
		assertTrue("or finished the quest that opens it", progress.isReadable(loader.get("world-morytania")));
	}

	private void setUp0()
	{
		memory = new TriggerContext(game.client);
		progress = new ChronicleProgress(game.client, loader, () -> memory);
	}

	@Test
	public void theTallyCountsWhatCanBeRead()
	{
		int[] none = progress.tally();
		assertEquals(0, none[0]);
		assertEquals(loader.getEntries().size(), none[1]);

		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		progress.noteNpc("Goblin");
		assertEquals(2, progress.tally()[0]);
		assertEquals(1, progress.tally("quest")[0]);
		assertEquals(3, progress.tally("quest")[1]);
		assertEquals(1, progress.tally("enemy")[0]);
	}
}
