package com.follower;

import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechRule;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rules file being read on the game thread while it is written on another.
 *
 * <p>This really happens: the plugin polls the file every game tick so edits go
 * live within a second, and the phrase editors write it from the Swing thread.
 * Nothing coordinates the two. The editor now writes to a sibling and renames,
 * which is what makes a torn read impossible - but the loader still has to hold
 * up on its own, because a user editing in Notepad gets no such courtesy.
 *
 * <p>Two things must hold whatever the interleaving: nothing thrown onto the
 * game thread, and the follower never left mute. Losing a personality file to a
 * half-written save would be far worse than running the previous rules for
 * another second.
 */
public class ConcurrentReloadTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static String rules(int count, String tag)
	{
		StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
		for (int i = 0; i < count; i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append("{\"id\": \"r").append(i).append("\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"").append(tag)
				.append(' ').append(i).append("\"]}");
		}
		return out.append("]}").toString();
	}

	/** In place, the way a text editor does it: truncate, then write. */
	private static void writeInPlace(Path file, String contents) throws IOException
	{
		Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Sibling and rename, the way the phrase editors do it - including the
	 * retry, because Windows will not replace a file another handle has open
	 * and the plugin opens this one every tick.
	 */
	private static void writeAtomically(Path file, String contents) throws IOException
	{
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
		Files.write(temp, bytes);

		for (int attempt = 0; attempt < 20; attempt++)
		{
			try
			{
				Files.move(temp, file,
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return;
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			catch (java.nio.file.FileSystemException e)
			{
				try
				{
					Thread.sleep(25);
				}
				catch (InterruptedException interrupted)
				{
					Thread.currentThread().interrupt();
					return;
				}
			}
		}

		Files.write(file, bytes);
		Files.deleteIfExists(temp);
	}

	private interface Writer
	{
		void write(Path file, String contents) throws IOException;
	}

	/**
	 * Hammers the file from one thread while polling it from another.
	 *
	 * @return how many polls came back with a parse error
	 */
	private int hammer(Writer writer) throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Path file = dir.resolve(RuleLoader.FILE_NAME);
		writer.write(file, rules(200, "first"));

		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(dir);
		assertFalse("nothing loaded to begin with", loader.getRules().isEmpty());

		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<Throwable> thrown = new AtomicReference<>();

		Thread scribe = new Thread(() ->
		{
			try
			{
				for (int i = 0; !stop.get() && i < 400; i++)
				{
					writer.write(file, rules(200, "pass" + i));
				}
			}
			catch (Throwable t)
			{
				thrown.compareAndSet(null, t);
			}
		});
		scribe.setDaemon(true);
		scribe.start();

		int errors = 0;
		try
		{
			for (int i = 0; i < 4000 && scribe.isAlive(); i++)
			{
				// Exactly what the tick handler does.
				loader.reloadIfChanged();
				if (!loader.getErrors().isEmpty())
				{
					errors++;
				}
				assertFalse("the follower went mute mid-edit",
					loader.getRules().isEmpty());
			}
		}
		finally
		{
			stop.set(true);
			scribe.join(10000);
		}

		if (thrown.get() != null)
		{
			throw new AssertionError("the writer failed: " + thrown.get(), thrown.get());
		}
		return errors;
	}

	@Test
	public void anInPlaceWriterNeverSilencesTheFollower() throws Exception
	{
		// A user editing in Notepad. Torn reads are possible here and the
		// loader is allowed to complain about them - it just may not lose the
		// rules it already had, and may not throw onto the game thread.
		hammer(ConcurrentReloadTest::writeInPlace);
	}

	@Test
	public void theEditorsWriteSurvivesAReaderHoldingTheFile() throws Exception
	{
		// The rename path, which on Windows loses to an open handle. It must
		// not give up and lose the user's edits: retrying, and falling back to
		// an in-place write, is what keeps the save succeeding. Reaching the
		// fallback means a torn read is possible again, which is why the
		// loader keeping its previous rules is the test above and not an
		// afterthought.
		hammer(ConcurrentReloadTest::writeAtomically);
	}

	@Test
	public void theRuleListIsSwappedWholeNeverPartially() throws Exception
	{
		// Whatever a reader catches, it must be one complete set of rules -
		// never a list being filled in as it reads.
		Path dir = folder.newFolder().toPath();
		Path file = dir.resolve(RuleLoader.FILE_NAME);
		writeAtomically(file, rules(300, "first"));

		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(dir);

		AtomicBoolean stop = new AtomicBoolean();
		Thread scribe = new Thread(() ->
		{
			try
			{
				for (int i = 0; !stop.get() && i < 300; i++)
				{
					writeAtomically(file, rules(300, "pass" + i));
				}
			}
			catch (IOException ignored)
			{
				// The assertion below is what matters.
			}
		});
		scribe.setDaemon(true);
		scribe.start();

		try
		{
			for (int i = 0; i < 3000 && scribe.isAlive(); i++)
			{
				loader.reloadIfChanged();
				List<SpeechRule> snapshot = loader.getRules();
				assertTrue("read a set of " + snapshot.size() + " rules, which is"
					+ " neither empty nor the full 300", snapshot.size() == 300);
				for (SpeechRule rule : snapshot)
				{
					assertFalse("a rule arrived without an id", rule.id == null);
				}
			}
		}
		finally
		{
			stop.set(true);
			scribe.join(10000);
		}
	}
}
