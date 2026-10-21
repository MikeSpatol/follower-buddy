package com.follower;

import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.follower.ui.MemoryDialog;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The memory surface (R20): what the window claims has to match what the
 * follower actually holds, in both directions - a fresh follower must not be
 * dressed up with history, and a lived-in one must not have its memory
 * understated. The window itself is Swing; the claims are a pure function.
 */
public class MemorySurfaceTest
{
	private static String flat(List<String[]> rows)
	{
		StringBuilder out = new StringBuilder();
		for (String[] row : rows)
		{
			out.append(row[0]);
			if (row[1] != null)
			{
				out.append(": ").append(row[1]);
			}
			out.append('\n');
		}
		return out.toString();
	}

	@Test
	public void aLivedInMemoryShowsItsContents()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.setMetOnDay(LocalDate.now().toEpochDay() - 31);
		context.setSessionCount(36);
		context.tally("kill:goblin");
		context.tally("kill:goblin");
		context.noteRecord("hit", 42);
		context.noteIncident("chicken-death", "that chicken");
		context.notePlaceFeeling(45);
		context.notePlaceMemory("the drop");
		context.noteSaidOnce("first-meeting");
		context.noteLineSaid("a line");
		context.setTraits(java.util.Collections.singleton(12850),
			java.util.Collections.singleton(10553));
		context.setWish("feather", 5, java.util.Collections.singletonList(314));

		String surface = flat(MemoryDialog.summarise(context,
			java.util.Collections.singletonMap(12850, "Lumbridge town and castle")));

		assertTrue(surface.contains("(31 days ago)"));
		assertTrue(surface.contains("Sessions together: 36"));
		assertTrue(surface.contains("Goblin kills: 2"));
		assertTrue(surface.contains("Biggest hit: 42"));
		assertTrue("the incident quoted as its words: " + surface,
			surface.contains("\"that chicken, just now\" (x1)"));
		assertFalse("and not by its file key: " + surface, surface.contains("chicken-death"));
		assertTrue("the place, its feeling and its memory: " + surface,
			surface.contains("likes it (+45) - the drop"));
		// A region the Locations rules name is shown by its name alone; one
		// they do not stays a region id.
		assertTrue("the named region: " + surface,
			surface.contains("Likes on a whim: Lumbridge town and castle\n"));
		assertTrue(surface.contains("region 10553\n"));
		assertTrue("the open wish says how to hand it over: " + surface,
			surface.contains("Hoping for: feather (right-click the follower and choose Talk-to to hand it over)"));
		assertTrue(surface.contains("One-time lines already said: 1"));
		assertTrue(surface.contains("Lines counted, to vary them: 1"));
	}

	@Test
	public void theWishSaysToAnswerFirstWhileAQuestionWaits()
	{
		// QA2, 15 Oct: it said Talk-to while the menu read Answer. QA3: and
		// Answer opens the question, which has no handover in it, so the
		// hint says to answer it first.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.setWish("feather", 5, java.util.Collections.singletonList(314));
		context.noteQuestion("want-outing");
		String surface = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()));
		assertTrue(surface, surface.contains("Hoping for: feather (answer its question first, then"
			+ " right-click the follower and choose Talk-to to hand it over)"));
	}

	@Test
	public void killCountsShowThisAccountsOneRowPerBoss()
	{
		// QA pass 4, #15 and #42: the old profile-wide count, this account's
		// and an alt's all showed, under one label with three numbers.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.noteRecord("kc:zulrah", 150);
		context.noteRecord("hit", 42);
		context.noteRecord("kc:zulrah@1234567890", 151);
		context.noteRecord("kc:zulrah@9876543210", 12);
		context.noteRecord("kc:vorkath@9876543210", 3);

		String mine = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap(),
			key -> key + "@1234567890"));
		assertTrue(mine, mine.contains("Zulrah kill count: 151\n"));
		assertEquals(mine, 1, mine.split("kill count", -1).length - 1);
		assertTrue(mine, mine.contains("Biggest hit: 42\n"));

		// An account with none of its own reads the old count, as the book does.
		String another = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap(),
			key -> key + "@5555"));
		assertTrue(another, another.contains("Zulrah kill count: 150\n"));
		assertEquals(another, 1, another.split("kill count", -1).length - 1);

		// Only another account's counts: nothing of this one's to show.
		context.getRecords().remove("hit");
		context.getRecords().remove("kc:zulrah");
		String none = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap(),
			key -> key + "@5555"));
		assertTrue(none, none.contains("No records held yet."));
	}

	@Test
	public void aBossIsNamedAsTheGameWritesIt()
	{
		// QA pass 4, #54: "Tztok-jad kill count", from the lower-case key.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.noteRecord("kc:tztok-jad@main", 5);
		context.noteKillCountName("TzTok-Jad");
		String shown = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap(),
			key -> key + "@main"));
		assertTrue(shown, shown.contains("TzTok-Jad kill count: 5\n"));
	}

	@Test
	public void countsAndBestsAreNamedInWords()
	{
		// QA, 15 Oct: the window showed the keys the plugin files them under.
		assertEquals("Goblin kills", MemoryDialog.keyLabel("kill:goblin"));
		assertEquals("Hill giant kills", MemoryDialog.keyLabel("kill:hill giant"));
		assertEquals("Kills", MemoryDialog.keyLabel("kills"));
		assertEquals("Kills today", MemoryDialog.keyLabel("kills" + TriggerContext.TODAY));
		assertEquals("Levels gained today", MemoryDialog.keyLabel("levels:today"));
		assertEquals("Deaths today", MemoryDialog.keyLabel("deaths:today"));
		assertEquals("Wishes granted", MemoryDialog.keyLabel("want:kept"));
		assertEquals("Wishes missed", MemoryDialog.keyLabel("want:missed"));
		assertEquals("Advice taken", MemoryDialog.keyLabel("advice:taken"));
		assertEquals("Advice ignored", MemoryDialog.keyLabel("advice:ignored"));
		assertEquals("Zulrah kill count", MemoryDialog.keyLabel("kc:zulrah"));
		assertEquals("Zulrah kill count", MemoryDialog.keyLabel("kc:zulrah@main"));
		assertEquals("Biggest hit", MemoryDialog.keyLabel("hit"));
		assertEquals("Longest session (minutes)", MemoryDialog.keyLabel("session"));
		assertEquals("Quests completed", MemoryDialog.keyLabel("quests"));
		assertEquals("Music tracks unlocked", MemoryDialog.keyLabel("music"));
		assertEquals("Collection log items", MemoryDialog.keyLabel("collections"));
		assertEquals("Marks of grace picked up on a course", MemoryDialog.keyLabel("graces"));
		assertEquals("a key with no words is shown as it is",
			"someone:else", MemoryDialog.keyLabel("someone:else"));
		assertEquals("kill:", MemoryDialog.keyLabel("kill:"));
	}

	@Test
	public void everyKeyThePluginFilesHasWords() throws java.io.IOException
	{
		// A new tally or record written without words here would show raw
		// again. The keys are read out of the source that writes them.
		StringBuilder source = new StringBuilder();
		for (String file : new String[]{"src/main/java/com/follower/FollowerPlugin.java",
			"src/main/java/com/follower/speech/SpeechEngine.java"})
		{
			source.append(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)),
				java.nio.charset.StandardCharsets.UTF_8));
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
			"(?:\\.tally|tallyBoth|noteRecord)\\(\\s*\"([^\"]+)\"|\\? \"(\\w+:\\w+)\" : \"(\\w+:\\w+)\"")
			.matcher(source);
		int found = 0;
		while (m.find())
		{
			for (int g = 1; g <= 3; g++)
			{
				String key = m.group(g);
				if (key != null)
				{
					// A prefix ("kill:" + the name) is checked with a name on.
					key = key.endsWith(":") ? key + "goblin" : key;
					found++;
					assertFalse(key + " has no words in the Memory window",
						key.equals(MemoryDialog.keyLabel(key)));
				}
			}
		}
		assertTrue("the lint found nothing to check - the pattern has lost its place", found >= 10);
	}

	@Test
	public void firstMetCountsTodayAndOneDayPlainly()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		long today = LocalDate.now().toEpochDay();

		context.setMetOnDay(today);
		assertTrue(flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()))
			.contains("First met: " + LocalDate.ofEpochDay(today) + " (today)\n"));

		context.setMetOnDay(today - 1);
		assertTrue(flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()))
			.contains("First met: " + LocalDate.ofEpochDay(today - 1) + " (1 day ago)\n"));

		context.setMetOnDay(today - 2);
		assertTrue(flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()))
			.contains("First met: " + LocalDate.ofEpochDay(today - 2) + " (2 days ago)\n"));
	}

	@Test
	public void aQuestionWaitingIsSaidInWordsNotByItsId()
	{
		// Text sweep, 14 Oct: the window showed "want-outing". A tree of the
		// player's own still shows its id, having nothing better to show.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		context.noteQuestion("want-outing");
		String shown = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()));
		assertTrue(shown, shown.contains("Waiting on an answer: Whether you'll take it somewhere\n"));
		context.noteQuestion("my-own-tree");
		shown = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()));
		assertTrue(shown, shown.contains("Waiting on an answer: my-own-tree\n"));
	}

	@Test
	public void forgetEverythingTakesTheMarksOfGraceWithIt() throws java.io.IOException
	{
		// The plugin's Forget everything resets the engine, which drops the
		// context the count lives in; the window then shows a clean page.
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("graces");
		com.follower.sim.Harness h = new com.follower.sim.Harness(dir);
		h.engine.getContext().tally("graces");
		h.engine.getContext().tally("graces");
		assertTrue(flat(MemoryDialog.summarise(h.engine.getContext(), java.util.Collections.emptyMap()))
			.contains("Marks of grace picked up on a course: 2"));
		h.engine.reset();
		assertEquals(0, h.engine.getContext().getTally("graces"));
		assertFalse(flat(MemoryDialog.summarise(h.engine.getContext(), java.util.Collections.emptyMap()))
			.contains("Marks of grace"));
	}

	@Test
	public void theNamedCountsShowHoweverManyCreaturesWereKilled()
	{
		// QA pass 5, #28: every kind of creature competed with the named
		// counts for ten rows, and the marks of grace fell off the end.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		for (int kind = 0; kind < 12; kind++)
		{
			for (int n = 0; n < 50 + kind; n++)
			{
				context.tally("kill:creature" + (char) ('a' + kind));
			}
		}
		for (int n = 0; n < 600; n++)
		{
			context.tallyBoth("kills");
		}
		context.tallyBoth("levels");
		context.tally("graces");
		context.tally("advice:taken");

		String surface = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()));
		assertTrue(surface, surface.contains("Kills: 600\nKills today: 600\nLevels gained: 1\nLevels gained today: 1\n"));
		assertTrue(surface, surface.contains("Marks of grace"));
		assertTrue(surface, surface.contains("Advice taken: 1"));
		assertTrue("the kills by creature, ten of them, most first: " + surface,
			surface.contains("Creaturel kills: 61\n"));
		assertFalse(surface, surface.contains("Creaturea kills"));
		assertTrue(surface, surface.contains("...and 2 more."));
	}

	@Test
	public void aStrangerHasNothingToShow()
	{
		TriggerContext context = new TriggerContext(new FakeGame().client);
		String surface = flat(MemoryDialog.summarise(context, java.util.Collections.emptyMap()));

		assertTrue(surface.contains("First met: not recorded yet"));
		assertTrue(surface.contains("Sessions together: 0"));
		assertTrue(surface.contains("Nothing counted yet."));
		assertTrue(surface.contains("No records held yet."));
		assertTrue(surface.contains("Nothing has stuck yet."));
		assertTrue(surface.contains("Opinions held: 0"));
		assertTrue(surface.contains("nowhere in particular"));
		assertTrue(surface.contains("Nothing in hand."));
		assertTrue(surface.contains("One-time lines already said: 0"));

		// The other direction of honesty: no leftover-looking claims.
		assertFalse(surface.contains("x1"));
		assertFalse(surface.contains("Hoping for"));
	}
}
