package com.follower;

import com.follower.chronicle.ChronicleLoader;
import com.follower.chronicle.ChronicleProgress;
import com.follower.sim.Harness;
import com.follower.speech.Condition;
import com.follower.speech.SpeechRule;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A callback that remembers a quest done together is said only when the
 * follower was there for it.
 *
 * <p>A finished quest is not enough: most players did the early quests years
 * before the follower existed, and "Ned did get us to Crandor" is a memory it
 * cannot have (QA pass 2, #31). The book already tells the two apart, a quest
 * page found finished at the first look being "before I kept dates". The
 * follower was there when it saw the quest's reward scroll, or wrote the
 * quest's page as it finished.
 */
public class QuestWitnessedTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private int tick;

	/** The shipped rules named, and the chronicle wired to them as the plugin wires it. */
	private final class World
	{
		final Harness h;
		final ChronicleProgress book;

		World(String... ids) throws IOException
		{
			h = harnessWith(Arrays.asList(ids));
			ChronicleLoader pages = new ChronicleLoader(new Gson());
			pages.load(folder.newFolder().toPath());
			book = new ChronicleProgress(h.game.client, pages, h.engine::getContext);
			h.engine.setQuestWitness(book::witnessedOn);
		}

		World ticks(int count)
		{
			for (int i = 0; i < count; i++)
			{
				h.gameTick();
				book.refresh(++tick);
			}
			return this;
		}
	}

	private Harness harnessWith(List<String> ids) throws IOException
	{
		JsonArray rules;
		try (InputStream in = getClass().getResourceAsStream("/com/follower/default-phrases.json"))
		{
			rules = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonArray("rules");
		}
		Set<String> wanted = new HashSet<>(ids);
		JsonArray kept = new JsonArray();
		for (JsonElement rule : rules)
		{
			if (wanted.contains(rule.getAsJsonObject().get("id").getAsString()))
			{
				kept.add(rule);
			}
		}
		assertEquals("every rule named is shipped", ids.size(), kept.size());
		JsonObject file = new JsonObject();
		file.addProperty("version", 1);
		file.add("rules", kept);
		return new Harness(folder.newFolder().toPath(), file.toString());
	}

	private static List<String> said(Harness h)
	{
		List<String> out = new ArrayList<>();
		for (Harness.Spoken s : h.spoken)
		{
			out.add(s.rule.id);
		}
		return out;
	}

	// ----------------------------------------------------------- the people

	@Test
	public void aVeteranMeetingNedHearsTheBaseLineNotAMemoryOfCrandor() throws IOException
	{
		World w = new World("quest-ned", "fun-ned-after-dragon-slayer");
		w.h.game.quest(Quest.DRAGON_SLAYER_I, QuestState.FINISHED);
		w.ticks(2);
		w.h.game.spawnNpc(3308, "Ned", 0);
		w.ticks(4);
		assertEquals(Arrays.asList("quest-ned"), said(w.h));
	}

	@Test
	public void aFollowerThatSawDragonSlayerFinishRemembersIt() throws IOException
	{
		World w = new World("quest-ned", "fun-ned-after-dragon-slayer");
		w.h.game.quest(Quest.DRAGON_SLAYER_I, QuestState.IN_PROGRESS);
		w.ticks(2);
		w.h.game.quest(Quest.DRAGON_SLAYER_I, QuestState.FINISHED);
		w.book.sawQuestFinish("Dragon Slayer I");
		w.ticks(2);
		w.h.game.spawnNpc(3308, "Ned", 0);
		w.ticks(4);
		assertEquals(Arrays.asList("fun-ned-after-dragon-slayer"), said(w.h));
	}

	@Test
	public void theBookIsStillAskedAfterTheEngineIsReset() throws IOException
	{
		// A logout, a profile switch and a Forget each reset the engine, which
		// builds a new context. The lookup went with the old one, so every
		// callback fell silent after the first logout (QA pass 3, #4).
		World w = new World("quest-ned", "fun-ned-after-dragon-slayer");
		w.h.game.quest(Quest.DRAGON_SLAYER_I, QuestState.FINISHED);
		w.book.sawQuestFinish("Dragon Slayer I");
		Gson gson = new Gson();
		String stored = FollowerPlugin.snapshotMemory(w.h.engine.getContext(), 0, gson);
		w.h.engine.reset();
		// The login that follows reads the memory back into the new context.
		FollowerPlugin.restoreMemory(w.h.engine.getContext(), stored, gson, 0);
		w.ticks(2);
		w.h.game.spawnNpc(3308, "Ned", 0);
		w.ticks(4);
		assertEquals(Arrays.asList("fun-ned-after-dragon-slayer"), said(w.h));
	}

	@Test
	public void thePluginHandsTheBookToTheEngineNotToOneContext() throws IOException
	{
		String plugin = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8);
		assertTrue("the plugin wires the book to the engine",
			plugin.contains("speechEngine.setQuestWitness(chronicleProgress::witnessedOn);"));
		assertTrue("a context's lookup is gone at the next reset",
			!plugin.contains("getContext().setQuestWitness("));
	}

	@Test
	public void aQuestPageWrittenAsItsQuestFinishedIsWitnessEnough() throws IOException
	{
		World w = new World("quest-duke-horacio", "fun-duke-horacio-after-cooks-assistant");
		w.h.game.quest(Quest.COOKS_ASSISTANT, QuestState.IN_PROGRESS);
		w.ticks(2);
		w.h.game.quest(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		w.ticks(2);
		w.h.game.spawnNpc(815, "Duke Horacio", 0);
		w.ticks(4);
		assertEquals(Arrays.asList("fun-duke-horacio-after-cooks-assistant"), said(w.h));

		World veteran = new World("quest-duke-horacio", "fun-duke-horacio-after-cooks-assistant");
		veteran.h.game.quest(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		veteran.ticks(2);
		veteran.h.game.spawnNpc(815, "Duke Horacio", 0);
		veteran.ticks(4);
		assertEquals("the page is undated: before the book kept dates",
			Arrays.asList("quest-duke-horacio"), said(veteran.h));
	}

	@Test
	public void tobynOnTheRoofIsRememberedTheDayAfterAndNotAsTheQuestEnds() throws IOException
	{
		// Alina and Noah are gone from the square once Children of the Sun is
		// under way, so a callback on them could never be heard (QA pass 2,
		// #13). Tobyn stays on the roof, and is beside you as the quest ends.
		World today = new World("quest-sergeant-tobyn", "fun-sergeant-tobyn-after-children-of-the-sun");
		today.h.game.spawnNpc(12648, "Sergeant Tobyn", 0);
		today.h.game.quest(Quest.CHILDREN_OF_THE_SUN, QuestState.FINISHED);
		today.book.sawQuestFinish("Children of the Sun");
		today.ticks(4);
		assertEquals("not a 'still' on the day itself", Arrays.asList("quest-sergeant-tobyn"), said(today.h));

		World later = new World("quest-sergeant-tobyn", "fun-sergeant-tobyn-after-children-of-the-sun");
		later.h.game.quest(Quest.CHILDREN_OF_THE_SUN, QuestState.FINISHED);
		later.h.engine.getContext().noteQuestSeenFinished("CHILDREN_OF_THE_SUN",
			java.time.LocalDate.now().toEpochDay() - 3);
		later.ticks(2);
		later.h.game.spawnNpc(12648, "Sergeant Tobyn", 0);
		later.ticks(4);
		assertEquals(Arrays.asList("fun-sergeant-tobyn-after-children-of-the-sun"), said(later.h));

		World veteran = new World("quest-sergeant-tobyn", "fun-sergeant-tobyn-after-children-of-the-sun");
		veteran.h.game.quest(Quest.CHILDREN_OF_THE_SUN, QuestState.FINISHED);
		veteran.ticks(2);
		veteran.h.game.spawnNpc(12648, "Sergeant Tobyn", 0);
		veteran.ticks(4);
		assertEquals(Arrays.asList("quest-sergeant-tobyn"), said(veteran.h));
	}

	@Test
	public void noCallbackWaitsOnAlinaOrNoahAfterChildrenOfTheSun() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (SpeechRule rule : h.loader.getRules())
		{
			if (uses(rule.when, "questWitnessed") || uses(rule.when, "questDone"))
			{
				assertTrue(rule.id + " waits for Alina or Noah, who are gone once the quest is done",
					!names(rule.when).contains("Alina") && !names(rule.when).contains("Noah"));
			}
		}
	}

	private static Set<String> names(Condition c)
	{
		Set<String> out = new HashSet<>();
		if (c != null)
		{
			if (c.names != null)
			{
				out.addAll(c.names);
			}
			if (c.conditions != null)
			{
				for (Condition child : c.conditions)
				{
					out.addAll(names(child));
				}
			}
		}
		return out;
	}

	// ----------------------------------------------------------- the moments

	@Test
	public void aVeteranAtTheCipherHearsTheBaseMomentNotTheJailbreak() throws IOException
	{
		World w = new World("qm-x-marks-the-spot-the-cipher",
			"qm-x-marks-the-spot-the-cipher-after-prince-ali-rescue");
		w.h.game.quest(Quest.PRINCE_ALI_RESCUE, QuestState.FINISHED);
		w.ticks(2);
		w.h.game.varbit(8063, 5);
		w.ticks(4);
		assertEquals(Arrays.asList("qm-x-marks-the-spot-the-cipher"), said(w.h));

		World there = new World("qm-x-marks-the-spot-the-cipher",
			"qm-x-marks-the-spot-the-cipher-after-prince-ali-rescue");
		there.h.game.quest(Quest.PRINCE_ALI_RESCUE, QuestState.FINISHED);
		there.book.sawQuestFinish("Prince Ali Rescue");
		there.ticks(2);
		there.h.game.varbit(8063, 5);
		there.ticks(4);
		assertEquals(Arrays.asList("qm-x-marks-the-spot-the-cipher-after-prince-ali-rescue"),
			said(there.h));
	}

	// ----------------------------------------------------------- the memory

	@Test
	public void theScrollIsReadByTheQuestsOwnNameAndPerAccount() throws IOException
	{
		World w = new World("quest-ned");
		String[] who = {"main"};
		w.book.setAccount(() -> who[0]);
		w.book.sawQuestFinish("The Restless Ghost");
		assertEquals(Long.valueOf(java.time.LocalDate.now().toEpochDay()),
			w.book.witnessedOn("THE_RESTLESS_GHOST"));
		w.book.sawQuestFinish("Not A Quest At All");
		who[0] = "alt";
		assertNull("the alt did not do it with the follower there",
			w.book.witnessedOn("THE_RESTLESS_GHOST"));

		Gson gson = new Gson();
		com.follower.speech.TriggerContext after =
			new com.follower.speech.TriggerContext(new com.follower.sim.FakeGame().client);
		FollowerPlugin.restoreMemory(after,
			FollowerPlugin.snapshotMemory(w.h.engine.getContext(), 0, gson), gson, 0);
		assertEquals("and it is remembered across a restart",
			Long.valueOf(java.time.LocalDate.now().toEpochDay()),
			after.questSeenFinishedOn("THE_RESTLESS_GHOST@main"));
	}

	// ----------------------------------------------------------- the rule set

	@Test
	public void noShippedCallbackClaimsAMemoryOnAFinishedQuestAlone() throws IOException
	{
		// Every fun-*-after-* callback speaks as one who was there. The quest
		// moments that do are listed; the rest only know of the quest.
		Set<String> sharedMemory = new HashSet<>(Arrays.asList(
			"qm-cooks-assistant-egg-coop-after-sheep-shearer",
			"qm-rune-mysteries-tower-arrival-after-cooks-assistant",
			"qm-rune-mysteries-mugger-street-after-children-of-the-sun",
			"qm-priest-in-peril-the-kings-verdict-after-children-of-the-sun",
			"qm-priest-in-peril-the-coffin-sealed-after-vampyre-slayer",
			"qm-waterfall-quest-golries-pebble-after-tree-gnome-village",
			"qm-children-of-the-sun-the-roof-after-priest-in-peril",
			"qm-x-marks-the-spot-the-cipher-after-prince-ali-rescue",
			"qm-client-of-kourend-dark-altar-after-priest-in-peril"));
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> bad = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			boolean memory = (rule.id.startsWith("fun-") && rule.id.contains("-after-"))
				|| sharedMemory.contains(rule.id);
			if (memory && (uses(rule.when, "questDone") || !uses(rule.when, "questWitnessed")))
			{
				bad.add(rule.id);
			}
		}
		assertTrue("callbacks remembering a quest the follower may not have seen: " + bad,
			bad.isEmpty());
	}

	private static boolean uses(Condition c, String type)
	{
		if (c == null)
		{
			return false;
		}
		if (type.equalsIgnoreCase(c.type))
		{
			return true;
		}
		if (c.conditions != null)
		{
			for (Condition child : c.conditions)
			{
				if (uses(child, type))
				{
					return true;
				}
			}
		}
		return false;
	}
}
