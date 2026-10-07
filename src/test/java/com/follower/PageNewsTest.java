package com.follower;

import com.follower.speech.TriggerEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
}
