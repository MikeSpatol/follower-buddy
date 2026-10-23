package com.follower;

import com.follower.speech.FollowerDialog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * talk.json builds exactly the conversation the Java in {@link OldTalk} did,
 * for every input that shapes it - the mood band, the wish and the bag - and
 * the day summary says what it said, for every band.
 */
public class TalkScriptEquivalenceTest
{
	private static final String[] BANDS = {null, "", "low", "down", "even", "good", "high", "neutral"};

	@Test
	public void theFileBuildsTheSameScriptAsTheJava()
	{
		FollowerPlugin.Talk talk = TalkScriptTest.talk();
		assertEquals("the joke pool", OldTalk.JOKES.length, talk.jokes.size());
		for (int i = 0; i < OldTalk.JOKES.length; i++)
		{
			assertEquals("joke " + i, Arrays.asList(OldTalk.JOKES[i]), Arrays.asList(talk.jokes.get(i)));
		}
		Supplier<String[]> summary = () -> new String[]{"one page", "and another"};
		int variants = 0;
		for (String band : BANDS)
		{
			// public:cut souvenirs-wishes
			for (String wish : new String[]{null, "", "feather", "bowl of stew"})
			{
				for (boolean bag : new boolean[]{true, false})
				{
			// public:end souvenirs-wishes
					List<String> oldAnswers = new ArrayList<>();
					List<String> newAnswers = new ArrayList<>();
					Map<String, FollowerDialog.Node> was = OldTalk.talkScript(summary, oldAnswers::add,
						wish, bag, band); // public:cut souvenirs-wishes
						// public:else band);
					Map<String, FollowerDialog.Node> now = FollowerPlugin.talkScript(talk, summary, newAnswers::add,
						wish, bag, band); // public:cut souvenirs-wishes
						// public:else band);
					String where = band + "/" + wish + "/" + bag; // public:cut souvenirs-wishes
					// public:else String where = band;
					assertEquals(where + " nodes, in order", new ArrayList<>(was.keySet()), new ArrayList<>(now.keySet()));
					for (String id : was.keySet())
					{
						FollowerDialog.Node a = was.get(id);
						FollowerDialog.Node b = now.get(id);
						String at = where + " " + id;
						assertEquals(at + " speaker", a.isPlayerSpeaking(), b.isPlayerSpeaking());
						assertEquals(at + " options", a.getOptionLabels(), b.getOptionLabels());
						assertEquals(at + " option targets", a.getOptionTargets(), b.getOptionTargets());
						assertEquals(at + " targets", a.getTargets(), b.getTargets());
						if (id.startsWith("chat-joke") && id.endsWith("-a"))
						{
							// Drawn from the pool on every visit, never the same one twice running.
							List<String> first = b.getPages();
							List<String> second = b.getPages();
							assertTrue(at + " tells a joke from the pool", isJoke(talk, first) && isJoke(talk, second));
							assertNotEquals(at + " twice the same", first, second);
							assertTrue(at + " the old one did too", isJoke(talk, a.getPages()));
						}
						else
						{
							assertEquals(at + " pages", a.getPages(), b.getPages());
							assertEquals(at + " lines", a.getLines(), b.getLines());
						}
						a.runFinish();
						b.runFinish();
						assertEquals(at + " answers", oldAnswers, newAnswers);
					}
					variants++;
			// public:cut souvenirs-wishes
				}
			}
			// public:end souvenirs-wishes
		}
		assertEquals(BANDS.length * 8, variants); // public:cut souvenirs-wishes
		// public:else assertEquals(BANDS.length, variants);
	}

	@Test
	public void theDaySummaryReadsTheSameFromTheFile()
	{
		FollowerPlugin.Talk talk = TalkScriptTest.talk();
		int[] counts = {0, 1, 2, 12};
		int checked = 0;
		for (String band : new String[]{null, "", "low", "down", "even", "good", "high", "neutral", "ordinary"})
		{
			for (int minutes : new int[]{0, 1, 2, 59, 60, 61, 95, 120, 121, 480, 999})
			{
				for (int kills : counts)
				{
					for (int other : counts)
					{
						for (String incident : new String[]{null, "", "that chicken"})
						{
							String[] was = OldTalk.daySummary(minutes, kills, other, kills ^ other, band, incident);
							String[] now = FollowerPlugin.daySummary(talk, minutes, kills, other, kills ^ other, band, incident);
							assertEquals(band + "/" + minutes + "/" + kills + "/" + other + "/" + incident,
								Arrays.asList(was), Arrays.asList(now));
							checked++;
						}
					}
				}
			}
		}
		assertEquals(9 * 11 * 4 * 4 * 3, checked);
	}

	private static boolean isJoke(FollowerPlugin.Talk talk, List<String> pages)
	{
		return talk.jokes.stream().anyMatch(joke -> Arrays.asList(joke).equals(pages));
	}
}
