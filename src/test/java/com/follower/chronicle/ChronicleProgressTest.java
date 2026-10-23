// public:file chronicle
package com.follower.chronicle;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.QuestState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
	public void aGatedPassageWaitsForItsQuestToBeFinished()
	{
		assertFalse("nothing known yet", progress.isDone("RECIPE_FOR_DISASTER"));
		progress.setQuestState("RECIPE_FOR_DISASTER", QuestState.IN_PROGRESS);
		assertFalse("begun is not done", progress.isDone("RECIPE_FOR_DISASTER"));
		progress.setQuestState("RECIPE_FOR_DISASTER", QuestState.FINISHED);
		assertTrue(progress.isDone("RECIPE_FOR_DISASTER"));
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
		progress.setQuestState("WATERFALL_QUEST", QuestState.FINISHED);
		assertTrue("and stay there once it is done", progress.isReadable(falls));

		// A quest finished before the plugin was installed is only ever seen
		// finished, never begun.
		setUp0();
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.FINISHED);
		assertTrue(progress.isReadable(loader.get("location-paterdomus")));
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
		assertEquals(12, progress.tally("quest")[1]);
		assertEquals(1, progress.tally("enemy")[0]);
	}

	// ------------------------------------------------ when and where written

	private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

	/** The pages the follower is told of, in order. */
	private List<String> told()
	{
		List<String> ids = new ArrayList<>();
		progress.setOnWritten((entry, byQuest) -> ids.add(entry.id + (byQuest ? " by quest" : "")));
		progress.setClock(Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
		memory.setRegionNames(Collections.singletonMap(12850, "Lumbridge: castle and the bridge"));
		return ids;
	}

	@Test
	public void aPlaceWalkedIntoIsDatedPlacedAndSpokenOfOnce()
	{
		List<String> told = told();
		progress.look();
		progress.noteRegion(12850);
		ChronicleEntry lumbridge = loader.get("location-lumbridge");
		assertEquals(Long.valueOf(TODAY.toEpochDay()), progress.writtenOn(lumbridge));
		assertEquals("the place's name, to its first clause", "Lumbridge", progress.writtenAt(lumbridge));
		assertEquals(Collections.singletonList("location-lumbridge"), told);
		progress.noteRegion(12850);
		assertEquals("once", 1, told.size());
	}

	@Test
	public void aPageMetBetweenLooksIsNotToldAgainByTheNextLook()
	{
		// The book looks at the quests every few ticks. A page a sighting
		// wrote since the last look is readable now and was not then, but it
		// is the sighting's news, not a quest's, and it has been told.
		List<String> told = told();
		progress.look();
		progress.noteNpc("Goblin");
		progress.noteRegion(12850);
		progress.look();
		progress.look();
		assertEquals(Arrays.asList("enemy-goblin", "location-lumbridge"), told);
	}

	@Test
	public void aCreatureMetSomewhereUnnamedIsDatedWithoutAPlace()
	{
		List<String> told = told();
		progress.look();
		progress.noteNpc("Goblin");
		ChronicleEntry goblin = loader.get("enemy-goblin");
		assertEquals(Long.valueOf(TODAY.toEpochDay()), progress.writtenOn(goblin));
		assertEquals("", progress.writtenAt(goblin));
		assertEquals(Collections.singletonList("enemy-goblin"), told);
	}

	@Test
	public void aQuestPageIsDatedWhenItsQuestIsSeenToFinish()
	{
		List<String> told = told();
		progress.setQuestState("COOKS_ASSISTANT", QuestState.IN_PROGRESS);
		progress.look();
		assertTrue("taking stock tells nothing", told.isEmpty());
		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		progress.look();
		assertEquals(Collections.singletonList("quest-cooks-assistant by quest"), told);
		assertEquals(Long.valueOf(TODAY.toEpochDay()), progress.writtenOn(loader.get("quest-cooks-assistant")));
		progress.look();
		assertEquals("and only the once", 1, told.size());
	}

	/**
	 * Chronicle QA5 #47: Temple of the Eye ends in the Wizards' Tower
	 * basement and Below Ice Mountain in Camdozaal, which no location rule
	 * names; the book has a page for each, and the quest page says so.
	 */
	@Test
	public void aQuestFinishedSomewhereUnnamedIsWrittenAtThePlacePageThatCoversIt()
	{
		told();
		String[][] cases = {
			{"TEMPLE_OF_THE_EYE", "quest-temple-of-the-eye", "3104", "9571", "Wizards' Tower"},
			{"BELOW_ICE_MOUNTAIN", "quest-below-ice-mountain", "2950", "5769", "Ruins of Camdozaal"},
		};
		for (String[] c : cases)
		{
			game.at(Integer.parseInt(c[2]), Integer.parseInt(c[3]), 0);
			memory.refresh();
			assertEquals("no location rule names it", "", memory.placeName(memory.getRegionId()));
			progress.setQuestState(c[0], QuestState.IN_PROGRESS);
			progress.look();
			progress.setQuestState(c[0], QuestState.FINISHED);
			progress.look();
			assertEquals(c[4], progress.writtenAt(loader.get(c[1])));
		}
	}

	@Test
	public void aPageAlreadyWrittenAtTheFirstLookIsLeftUndated()
	{
		// A quest done before the book kept dates, or before the plugin: the
		// book does not know when, so it does not say.
		List<String> told = told();
		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		progress.look();
		progress.look();
		assertTrue(told.isEmpty());
		assertNull(progress.writtenOn(loader.get("quest-cooks-assistant")));
	}

	@Test
	public void aPageAQuestOpenedIsNotWrittenAgainWhenItsCreatureIsMet()
	{
		List<String> told = told();
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.FINISHED);
		progress.look();
		progress.noteNpc("Temple Guardian");
		assertTrue("already readable, so nothing new to say", told.isEmpty());
		progress.look();
		assertTrue("nor on the next look", told.isEmpty());
	}

	@Test
	public void onlyACreatureWithAPageStillToWriteIsWorthALook()
	{
		told();
		assertTrue(progress.wantsToMeet("Monk of Zamorak"));
		assertTrue("as the game spells it, any case", progress.wantsToMeet("monk of zamorak"));
		assertFalse("no page names it", progress.wantsToMeet("Cow"));
		progress.noteNpc("Monk of Zamorak");
		assertFalse("written already", progress.wantsToMeet("Monk of Zamorak"));
	}

	@Test
	public void aNewSessionTakesStockAfresh()
	{
		List<String> told = told();
		progress.look();
		progress.reset();
		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		progress.look();
		assertTrue("finished before this session's first look, as far as the book knows", told.isEmpty());
	}

	@Test
	public void aPageAQuestOpensIsToldAsTheQuestsNotAsASighting()
	{
		// Starting Priest in Peril opens the temple's page before it is
		// seen: the follower must not claim it saw it. The dog's waits to be
		// met (play test, 7 Oct).
		List<String> told = told();
		progress.look();
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.IN_PROGRESS);
		progress.look();
		assertTrue(told.toString(), told.contains("location-paterdomus by quest"));
		assertFalse(told.toString(), told.toString().contains("enemy-temple-guardian"));
		progress.noteNpc("Temple Guardian");
		assertTrue("met on the quest, it is the follower's own sighting",
			told.contains("enemy-temple-guardian"));
	}

	@Test
	public void aQuestPagesDateBelongsToTheAccountThatDidTheQuest()
	{
		// One RuneLite profile, two accounts: the memory is shared, the
		// quests are not.
		told();
		String[] who = {"main"};
		progress.setAccount(() -> who[0]);
		ChronicleEntry cooks = loader.get("quest-cooks-assistant");
		progress.look();
		progress.setQuestState("COOKS_ASSISTANT", QuestState.FINISHED);
		progress.look();
		assertEquals(Long.valueOf(TODAY.toEpochDay()), progress.writtenOn(cooks));
		who[0] = "alt";
		assertNull("the alt's Cook's Assistant was not written today", progress.writtenOn(cooks));

		// A creature met is the follower's own, shared by both.
		progress.noteNpc("Goblin");
		who[0] = "main";
		assertEquals(Long.valueOf(TODAY.toEpochDay()), progress.writtenOn(loader.get("enemy-goblin")));
	}

	@Test
	public void aCreatureMetBeforeTheFirstLookWaitsForItToDecide()
	{
		// At login the quests are not read yet, so a creature whose page a
		// quest had already opened would look new.
		List<String> told = told();
		progress.noteNpc("Temple Guardian");
		progress.noteNpc("Goblin");
		assertTrue("nothing said before the quests are read", told.isEmpty());
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.IN_PROGRESS);
		progress.look();
		assertTrue("both new: a quest under way does not open the dog's page", told.containsAll(
			java.util.Arrays.asList("enemy-goblin", "enemy-temple-guardian")));

		// The same with the quest long finished, as it is for most who log in.
		setUp0();
		told = told();
		progress.noteNpc("Temple Guardian");
		progress.setQuestState("PRIEST_IN_PERIL", QuestState.FINISHED);
		progress.look();
		assertTrue(told.toString(), told.isEmpty());
		assertNull(progress.writtenOn(loader.get("enemy-temple-guardian")));
	}

	@Test
	public void anotherProfilesMemoryIsTakenStockOfNotAnnounced()
	{
		// Switching RuneLite profile swaps the memory under the book; the
		// plugin resets the book when it does.
		List<String> told = told();
		memory.noteChronicled("enemy-goblin");
		progress.look();
		memory = new TriggerContext(game.client);
		progress.reset();
		progress.look();
		memory.noteChronicled("boss-giant-mole");
		progress.reset();
		progress.look();
		progress.look();
		assertTrue(told.toString(), told.isEmpty());
		assertNull(progress.writtenOn(loader.get("boss-giant-mole")));
	}

	@Test
	public void aPagesNumberIsTheFollowersCountOrNothing()
	{
		ChronicleEntry goblin = loader.get("enemy-goblin");
		ChronicleEntry mole = loader.get("boss-giant-mole");
		assertEquals("nothing counted yet", 0, progress.count(goblin));
		assertEquals(0, progress.count(mole));

		memory.tally("kill:goblin");
		memory.tally("kill:goblin");
		memory.noteRecord("kc:giant mole", 41);
		assertEquals("the kills it saw", 2, progress.count(goblin));
		assertEquals("the game's own kill count", 41, progress.count(mole));
		assertEquals("a page that names no counter", 0, progress.count(loader.get("quest-cooks-assistant")));
		assertEquals(0, progress.count(null));

		assertEquals(0, new ChronicleProgress(game.client, loader, () -> null).count(goblin));
	}

	@Test
	public void aBossCountIsThisAccountsOwn()
	{
		// The game keeps kill counts per account, and a profile can be shared
		// by a main and an alt (QA pass 3, #40).
		ChronicleEntry mole = loader.get("boss-giant-mole");
		String[] who = {"main"};
		progress.setAccount(() -> who[0]);
		memory.noteRecord("kc:giant mole", 41);
		assertEquals("a count kept before counts were per account", 41, progress.count(mole));
		memory.noteRecord(progress.accountKey("kc:giant mole"), 1200);
		assertEquals(1200, progress.count(mole));
		who[0] = "alt";
		memory.noteRecord(progress.accountKey("kc:giant mole"), 4);
		assertEquals("not the main's", 4, progress.count(mole));
	}

	@Test
	public void aPagesNumberIsOnlyALineOnceItIsAboveNought()
	{
		ChronicleEntry goblin = loader.get("enemy-goblin");
		assertNull(ChronicleWindow.tallyLine(goblin, 0));
		assertEquals("Goblins you've killed while I watched: 214", ChronicleWindow.tallyLine(goblin, 214));
		assertNull("a page that counts nothing", ChronicleWindow.tallyLine(loader.get("quest-cooks-assistant"), 5));
	}
}
