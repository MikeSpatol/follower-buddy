package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The transcript.
 *
 * <p>It exists to answer questions that were previously settled by feel - is it
 * repeating itself, is it being throttled, did that pacing change help - so the
 * thing worth testing is that its figures are right and that it writes nothing
 * at all until asked.
 */
public class SpeechJournalTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private SpeechJournal journalIn(Path dir)
	{
		SpeechJournal journal = new SpeechJournal();
		journal.initialise(dir);
		return journal;
	}

	private static SpeechRule rule(String id, String group)
	{
		SpeechRule r = new SpeechRule();
		r.id = id;
		r.group = group;
		return r;
	}

	@Test
	public void writesNothingUntilItIsSwitchedOn() throws IOException
	{
		// A diagnostic that logs a player's session unasked is a surprising
		// thing for a plugin to do. Off is the default and off must mean off.
		Path dir = folder.newFolder().toPath();
		SpeechJournal journal = journalIn(dir);

		assertFalse("starts off", journal.isEnabled());
		journal.spoke(rule("idle-chatter", "idle"), "Still here. Still standing.");
		journal.suppressed(rule("low-hp", "health"), "gap");
		journal.flush();

		assertFalse("no file until it is asked for",
			Files.exists(journal.getFile()));
		assertTrue("and nothing counted either",
			journal.summary().get(0).contains("nothing said yet"));
	}

	@Test
	public void beingSwitchedOnDirectlyIsTheSameAsToggling() throws IOException
	{
		// The plugin restores the saved state this way at startup. Found the
		// hard way: the first real bug-fix verification run recorded nothing,
		// because a restart quietly turned the transcript off again.
		java.nio.file.Path dir = folder.newFolder().toPath();
		SpeechJournal journal = journalIn(dir);

		journal.setEnabled(true);
		assertTrue(journal.isEnabled());
		journal.spoke(rule("idle-chatter", "idle"), "Quiet, isn't it.");
		journal.flush();
		assertTrue("recording after being switched on directly",
			Files.exists(journal.getFile()));

		// And setting it to what it already is must not open a second time.
		journal.setEnabled(true);
		journal.flush();
		String written = new String(Files.readAllBytes(journal.getFile()),
			StandardCharsets.UTF_8);
		assertEquals("one opening marker, not two", 1,
			written.split("transcript opened", -1).length - 1);
	}

	@Test
	public void recordsWhatWasSaidAndWhatWasHeldBack() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		SpeechJournal journal = journalIn(dir);
		journal.toggle();

		journal.spoke(rule("idle-chatter", "idle"), "Still here. Still standing.");
		journal.suppressed(rule("low-hp", "health"), "gap");
		journal.suppressed(rule("combat-cheer", "combat"), "gap");
		journal.suppressed(rule("area-varrock", "area"), "hush");
		journal.flush();

		String written = new String(Files.readAllBytes(journal.getFile()),
			StandardCharsets.UTF_8);
		assertTrue("the line the player saw", written.contains("Still here. Still standing."));
		assertTrue("the rule that said it", written.contains("idle-chatter"));
		assertTrue("and the ones that could not", written.contains("low-hp"));
		assertTrue("with the reason", written.contains("hush"));

		String head = journal.summary().get(0);
		assertTrue("counts both halves: " + head,
			head.contains("1 lines") && head.contains("3 held back"));
	}

	@Test
	public void everyRowSaysWhereItHappened() throws IOException
	{
		// Several behaviours are answers to "where" - place memory, taste,
		// return visits - and a transcript without it cannot be used to check
		// any of them. A verification run was lost to exactly that.
		Path dir = folder.newFolder().toPath();
		SpeechJournal journal = journalIn(dir);
		journal.setRegionSource(() -> 12850);
		journal.toggle();

		journal.spoke(rule("place-remembers", "area"), "This is where it happened.");
		journal.suppressed(rule("low-hp", "health"), "gap");
		journal.flush();

		for (String line : new String(Files.readAllBytes(journal.getFile()),
			StandardCharsets.UTF_8).split("\n"))
		{
			if (line.startsWith("----") || line.isEmpty())
			{
				continue;
			}
			assertTrue("every row carries the region: " + line,
				line.contains("\tr12850\t"));
		}
	}

	@Test
	public void withNoRegionSourceItStillWrites() throws IOException
	{
		// The supplier is set by the plugin; nothing else sets it, and a
		// missing one must not take the transcript down.
		Path dir = folder.newFolder().toPath();
		SpeechJournal journal = journalIn(dir);
		journal.toggle();
		journal.spoke(rule("idle-chatter", "idle"), "Quiet, isn't it.");
		journal.flush();
		assertTrue(Files.exists(journal.getFile()));
	}

	@Test
	public void findsTheLineItKeepsRepeating()
	{
		// The question the transcript exists to answer.
		SpeechJournal journal = journalIn(folder.getRoot().toPath());
		journal.toggle();

		SpeechRule idle = rule("idle-chatter", "idle");
		journal.spoke(idle, "Quiet, isn't it.");
		journal.spoke(idle, "Still here. Still standing.");
		journal.spoke(idle, "Quiet, isn't it.");
		journal.spoke(idle, "Quiet, isn't it.");

		String repeated = journal.summary().stream()
			.filter(l -> l.contains("most repeated"))
			.findFirst().orElse("");
		assertTrue("should name the repeat: " + repeated,
			repeated.contains("Quiet, isn't it.") && repeated.contains("3x"));
	}

	@Test
	public void saysNothingAboutRepeatsWhenThereAreNone()
	{
		SpeechJournal journal = journalIn(folder.getRoot().toPath());
		journal.toggle();
		journal.spoke(rule("a", "idle"), "One.");
		journal.spoke(rule("b", "idle"), "Two.");

		assertTrue("a line said once is not a repeat",
			journal.summary().stream().noneMatch(l -> l.contains("most repeated")));
	}

	@Test
	public void ranksTheLoudestRules()
	{
		SpeechJournal journal = journalIn(folder.getRoot().toPath());
		journal.toggle();
		for (int i = 0; i < 5; i++)
		{
			journal.spoke(rule("combat-cheer", "combat"), "Keep at it. " + i);
		}
		journal.spoke(rule("idle-chatter", "idle"), "Quiet, isn't it.");

		String loudest = journal.summary().stream()
			.filter(l -> l.contains("loudest rules"))
			.findFirst().orElse("");
		assertTrue("the busiest rule comes first: " + loudest,
			loudest.indexOf("combat-cheer") < loudest.indexOf("idle-chatter"));
	}

	@Test
	public void aCommandDrivenLineHasNoRuleAndIsStillRecorded()
	{
		// ::follower say goes through the same sink with a null rule; it must
		// not take the transcript down with it.
		SpeechJournal journal = journalIn(folder.getRoot().toPath());
		journal.toggle();
		journal.spoke(null, "Testing, testing.");
		assertTrue(journal.summary().get(0).contains("1 lines"));
	}

	@Test
	public void theEngineReportsWhyALineWasHeldBack() throws IOException
	{
		// The half of the story the player never sees, and the reason the
		// transcript is worth having at all. Driven through the real engine
		// rather than by calling the journal directly.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"one\", \"two\"]}]}");

		List<String> reasons = new java.util.ArrayList<>();
		h.engine.setOnSuppressed((rule, why) -> reasons.add(rule.describe() + ":" + why));

		h.engine.setMuted(true);
		h.gameTicks(3);

		assertFalse("a muted rule still wins and is still held back", reasons.isEmpty());
		assertEquals("chatty:muted", reasons.get(0));
	}

	@Test
	public void theDirectorsReasonsReachTheTranscript() throws IOException
	{
		// The transcript is the only way to tell a follower with little to say
		// from one being throttled, and the director became the biggest source
		// of throttling in the plugin the day it landed. A reason that stops at
		// the engine is a blind spot in the one diagnostic there is.
		// An "always" rule rises exactly once and then never again, so it can
		// never be suppressed twice - which is why this needs a real event to
		// re-trigger on, with a tick between to let the edge fall.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"scenery\", \"group\": \"area\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"one\", \"two\"]}]}");

		SpeechJournal journal = new SpeechJournal();
		journal.initialise(folder.newFolder().toPath());
		journal.setEnabled(true);
		h.engine.setOnSuppressed(journal::suppressed);

		h.engine.setGlobalCooldownMs(3_000L);
		h.engine.getContext().setSessionCount(1);
		h.gameTicks(1);

		// The first one speaks, which arms the settling damper; the next two
		// arrive well inside its window and are the ones held.
		for (int i = 0; i < 3; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}

		// Then a rest, by handing the director a burst directly.
		com.follower.speech.SpeechRule filler = new com.follower.speech.SpeechRule();
		filler.id = "filler";
		filler.group = "reactions";
		for (int i = 0; i < 4; i++)
		{
			h.engine.getDirector().noteSpoke(filler, System.currentTimeMillis());
		}
		for (int i = 0; i < 2; i++)
		{
			h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			h.gameTicks(1);
		}
		journal.flush();

		String written = String.join("\n", java.nio.file.Files.readAllLines(
			journal.getFile()));
		assertTrue("the transcript never mentions the director resting:\n" + written,
			written.contains("\trelax"));
		assertTrue("the transcript never mentions the settling damper:\n" + written,
			written.contains("\tsettling"));
	}
}
