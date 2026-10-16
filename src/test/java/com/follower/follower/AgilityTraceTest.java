package com.follower.follower;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The agility trace is written on the client thread and flushed by the
 * plugin's shutdown on another (QA4 #21, #38, #71). A flush that collided
 * with a line being added threw, and left the shutdown half done.
 */
public class AgilityTraceTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aFlushFromAnotherThreadNeverCollidesWithALineBeingAdded() throws Exception
	{
		AgilityTrace trace = new AgilityTrace();
		trace.initialise(folder.getRoot().toPath());
		trace.setEnabled(true);
		int lines = 20_000;
		AtomicReference<Throwable> failed = new AtomicReference<>();
		Thread client = new Thread(() ->
		{
			for (int i = 0; i < lines; i++)
			{
				trace.event("line " + i);
			}
		});
		Thread shutdown = new Thread(() ->
		{
			try
			{
				while (client.isAlive())
				{
					trace.flush();
				}
			}
			catch (Throwable t)
			{
				failed.set(t);
			}
		});
		client.start();
		shutdown.start();
		client.join();
		shutdown.join();
		trace.flush();

		assertNull("the flush threw: " + failed.get(), failed.get());
		long written = Files.readAllLines(trace.getFile()).stream().filter(l -> l.contains("* line ")).count();
		assertEquals("every line, once", lines, written);
	}
}
