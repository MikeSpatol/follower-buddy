package com.follower;

import com.follower.speech.RuleLoader;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The bundled rules are merged into the player's file only when one of the
 * two has changed since the last merge. Every start re-read three large
 * files and rewrote the 590 KB base when there was nothing to do: 70-100 ms
 * of each start on the Swing thread (perf audit, Oct 2026, #59).
 */
public class MergeOnlyWhenSomethingChangedTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aStartWithNothingNewMergesNothing() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Path base = dir.resolve(RuleLoader.BASE_FILE);
		Path phrases = dir.resolve(RuleLoader.FILE_NAME);
		new RuleLoader(GSON).initialise(dir, true);
		new RuleLoader(GSON).initialise(dir, true);
		FileTime marked = FileTime.fromMillis(1_000_000_000_000L);
		Files.setLastModifiedTime(base, marked);

		new RuleLoader(GSON).initialise(dir, true);
		assertEquals("nothing new: the base is not written again", marked, Files.getLastModifiedTime(base));

		// The player's file changed since: merged as ever.
		Files.write(phrases, (new String(Files.readAllBytes(phrases), StandardCharsets.UTF_8) + "\n")
			.getBytes(StandardCharsets.UTF_8));
		new RuleLoader(GSON).initialise(dir, true);
		assertNotEquals("the player's file changed", marked, Files.getLastModifiedTime(base));

		// And with the base gone, merged so it is written again.
		Files.setLastModifiedTime(base, marked);
		new RuleLoader(GSON).initialise(dir, true);
		assertEquals(marked, Files.getLastModifiedTime(base));
		Files.delete(base);
		new RuleLoader(GSON).initialise(dir, true);
		assertEquals("the base is written again", true, Files.exists(base));
	}
}
