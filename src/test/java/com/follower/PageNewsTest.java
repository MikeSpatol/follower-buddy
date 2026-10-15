package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
			FollowerPlugin.nextPageNews(news, 101, true, true));
		assertSame("but once the box closes", page, FollowerPlugin.nextPageNews(news, 102, true, false));
	}

	@Test
	public void aLongDialogueDoesNotUseUpTheMinute()
	{
		TriggerEvent page = written(100);
		// King Roald talks for well over a minute.
		for (int tick = 101; tick <= 300; tick++)
		{
			assertNull(FollowerPlugin.nextPageNews(news, tick, true, true));
		}
		assertSame("the minute starts when the box closes", page,
			FollowerPlugin.nextPageNews(news, 350, true, false));
	}

	@Test
	public void withNoDialogueItStillWaitsForQuietAndLapsesAfterAMinute()
	{
		written(100);
		assertNull("something else is being said", FollowerPlugin.nextPageNews(news, 120, false, false));
		assertNull("and a minute (100 ticks) later it is news no longer",
			FollowerPlugin.nextPageNews(news, 100 + 101, true, false));
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
}
