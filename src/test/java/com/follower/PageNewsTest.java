// public:file chronicle
package com.follower;

import com.follower.sim.Harness;
import com.follower.sim.QuestSim;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * When a page written gets its mention.
 *
 * <p>Play test, 7 Oct: starting Priest in Peril with King Roald writes the
 * Paterdomus and Temple Guardian pages while his dialogue is still up. The
 * news went out at the first quiet tick, over the dialogue, where every line
 * is dropped, and the page was never mentioned.
 */
public class PageNewsTest
{
	private final Deque<FollowerPlugin.PageNews> news = new ArrayDeque<>();

	private TriggerEvent written(int tick)
	{
		TriggerEvent event = TriggerEvent.pageWritten("quest", "Priest in Peril");
		news.add(new FollowerPlugin.PageNews(event, tick));
		return event;
	}

	@Test
	public void aPageWrittenDuringADialogueWaitsForTheBoxToClose()
	{
		TriggerEvent page = written(100);
		assertNull("not over the dialogue, where it would be dropped",
			FollowerPlugin.nextPageNews(news, 101, true, true, FollowerPlugin.PAGE_NEWS_TICKS, 0));
		assertSame("but once the box closes", page, FollowerPlugin.nextPageNews(news, 102, true, false, FollowerPlugin.PAGE_NEWS_TICKS, 0));
	}

	@Test
	public void aLongDialogueDoesNotUseUpTheMinute()
	{
		TriggerEvent page = written(100);
		// King Roald talks for well over a minute.
		for (int tick = 101; tick <= 300; tick++)
		{
			assertNull(FollowerPlugin.nextPageNews(news, tick, true, true, FollowerPlugin.PAGE_NEWS_TICKS, 0));
		}
		assertSame("the minute starts when the box closes", page,
			FollowerPlugin.nextPageNews(news, 350, true, false, FollowerPlugin.PAGE_NEWS_TICKS, 0));
	}

	@Test
	public void withNoDialogueItStillWaitsForQuietAndLapsesAfterAMinute()
	{
		written(100);
		assertNull("something else is being said", FollowerPlugin.nextPageNews(news, 120, false, false, FollowerPlugin.PAGE_NEWS_TICKS, 0));
		assertNull("and a minute (100 ticks) later it is news no longer",
			FollowerPlugin.nextPageNews(news, 100 + 101, true, false, FollowerPlugin.PAGE_NEWS_TICKS, 0));
	}

	/**
	 * Quests QA, 16 Oct: at Minimal the news needs 45 s of quiet after the
	 * quest's own line, said a couple of seconds after the scroll, and lapsed
	 * a minute after the writing whenever anything else was said in it.
	 */
	@Test
	public void atALongGapTheNewsWaitsForTwoGaps()
	{
		assertEquals("a minute at the shipped gaps", FollowerPlugin.PAGE_NEWS_TICKS,
			FollowerPlugin.pageNewsTicks(6_000L));
		int window = FollowerPlugin.pageNewsTicks(45_000L);
		assertEquals(170, window);
		TriggerEvent page = written(100);
		assertNull("another line, then the gap after it", FollowerPlugin.nextPageNews(news, 180, false, false, window, 0));
		assertSame("still news once the gap after that is out", page,
			FollowerPlugin.nextPageNews(news, 260, true, false, window, 0));
	}

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/**
	 * QA, 15 Oct: after a burst of three lines the gap ran out while the
	 * director was still resting, so the news went out, was held back by the
	 * rest, and the page was never mentioned.
	 */
	@Test
	public void theNewsWaitsOutTheRestAfterABurst() throws IOException
	{
		java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(clock::get);
		h.engine.setGlobalCooldownMs(6_000L);
		h.engine.getContext().setSessionCount(20);
		h.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay() - 30);
		for (String line : new String[]{"combat-start", "kill-cheer", "combat-end"})
		{
			h.engine.force(line);
			for (int i = 0; i < 11; i++)
			{
				clock.addAndGet(600L);
				h.gameTick();
			}
		}
		int waited = 0;
		while (!h.engine.isQuiet() && waited < 200)
		{
			clock.addAndGet(600L);
			h.gameTick();
			waited++;
		}
		assertTrue("quiet means the rest is over too",
			!h.engine.getDirector().isRelaxing(clock.get()));
		h.clear();
		h.dispatch(TriggerEvent.pageWritten("location", "Lumbridge"));
		for (int i = 0; i < 3; i++)
		{
			clock.addAndGet(600L);
			h.gameTick();
		}
		assertTrue("the page is mentioned", !h.firedBy("chronicle-page-place").isEmpty());
	}

	/**
	 * Text QA4, 16 Oct: at Minimal a place's page news waits up to 102 s for
	 * quiet, and was mostly said after the player had left: "Find me a good
	 * spot and pose for me." for a sketch only that place can take. It is
	 * let go once they have left the place's regions.
	 */
	@Test
	public void aPlacesNewsIsLetGoOnceThePlayerHasLeftIt()
	{
		TriggerEvent lumbridge = TriggerEvent.pageWritten("location", "Lumbridge");
		news.add(new FollowerPlugin.PageNews(lumbridge, 100, java.util.Arrays.asList(12850, 12849)));
		assertNull("something else is being said", FollowerPlugin.nextPageNews(news, 120, false, false,
			FollowerPlugin.PAGE_NEWS_TICKS, 12849));
		assertSame("still there", lumbridge, FollowerPlugin.nextPageNews(news, 130, true, false,
			FollowerPlugin.PAGE_NEWS_TICKS, 12849));

		news.add(new FollowerPlugin.PageNews(lumbridge, 200, java.util.Arrays.asList(12850, 12849)));
		assertNull(FollowerPlugin.nextPageNews(news, 220, true, true, FollowerPlugin.PAGE_NEWS_TICKS, 12851));
		assertTrue("gone from the queue once they have left", news.isEmpty());
		assertTrue("the plugin gives a place's page its regions", java.nio.file.Files.exists(PLUGIN)
			&& source().contains("!byQuest && \"location\".equals(entry.kindOrEmpty()) && entry.unlock != null\n"
				+ "\t\t\t\t? entry.unlock.regions : null)));"));
	}

	/**
	 * Quests QA4, 16 Oct: Priest in Peril's scroll writes three pages, its
	 * own, Seven Priestly Warriors and Morytania, all quest news. The second
	 * and third were spent on the quest page's line while it cooled, and two
	 * pages were never mentioned. Each now waits for the line.
	 *
	 * <p>Text QA5 #63: the scroll's line names the quest, and its own page's
	 * news straight after named it again: "Priest in Peril, done and
	 * witnessed." then "That's Priest in Peril in the book." A scroll line
	 * that names the quest is its page's news; the other pages keep theirs.
	 */
	@Test
	public void aScrollThatWritesThreePagesMentionsAllThree() throws IOException
	{
		Set<Boolean> named = new HashSet<>();
		for (int run = 0; run < 12; run++)
		{
			long gap = run % 2 == 0 ? 6000L : 45_000L;
			QuestSim s = new QuestSim(folder.newFolder().toPath(), gap);
			s.started(Quest.PRIEST_IN_PERIL);
			s.ticks(20);
			s.questComplete(Quest.PRIEST_IN_PERIL, "Priest in Peril", "Seven Priestly Warriors", "Morytania");
			s.ticks(600);
			s.summary();
			String scroll = s.heard.get(s.heardIds.indexOf("quest-complete"));
			named.add(scroll.contains("Priest in Peril"));
			assertEquals("gap " + gap + "\n" + s.transcript(), scroll.contains("Priest in Peril")
				? java.util.Arrays.asList("Seven Priestly Warriors", "Morytania")
				: java.util.Arrays.asList("Priest in Peril", "Seven Priestly Warriors", "Morytania"), s.pagesSaid);
		}
		assertTrue("the scroll line named the quest in some runs", named.contains(true));
		assertTrue("the plugin lets the quest's own page news go as QuestSim does", source().contains(
			"if (!text.isEmpty() && rule != null\n"
				+ "\t\t\t&& (\"quest-complete\".equals(rule.id) || \"quest-complete-parked\".equals(rule.id)))\n"
				+ "\t\t{\n"
				+ "\t\t\tpageNews.removeIf(page -> \"quest\".equals(page.event.getName())\n"
				+ "\t\t\t\t&& text.contains(page.event.getPlaceholders().get(\"page\")));"));
	}

	private static final java.nio.file.Path PLUGIN = java.nio.file.Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static String source()
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(PLUGIN), java.nio.charset.StandardCharsets.UTF_8)
				.replace("\r\n", "\n");
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}
}
