package com.follower;

import com.follower.speech.SpeechJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The daily developer logs are let go of after a while: a summer of
 * transcripts was 51 files nobody would read again (perf audit, Oct 2026, #62).
 */
public class LogRetentionTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aMonthOfTranscriptsIsKeptAndOlderOnesGo() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		LocalDate today = LocalDate.now();
		Path old = Files.createFile(dir.resolve("transcript-" + today.minusDays(LogRetention.DAYS_KEPT + 1) + ".log"));
		Path kept = Files.createFile(dir.resolve("transcript-" + today.minusDays(LogRetention.DAYS_KEPT) + ".log"));
		Path other = Files.createFile(dir.resolve("transcript-notes.log"));
		Path trace = Files.createFile(dir.resolve("agility-trace-" + today.minusYears(1) + ".log"));

		new SpeechJournal().initialise(dir);

		assertFalse("older than the month, gone", Files.exists(old));
		assertTrue("the month kept", Files.exists(kept));
		assertTrue("a name that is not a day is not ours", Files.exists(other));
		assertTrue("and another log's are its own business", Files.exists(trace));
	}

	@Test
	public void noFolderIsNothingToTidy()
	{
		LogRetention.prune(null, "transcript-", LocalDate.now());
		LogRetention.prune(folder.getRoot().toPath().resolve("missing"), "transcript-", LocalDate.now());
	}
}
