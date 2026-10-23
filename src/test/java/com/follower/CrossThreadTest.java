package com.follower;

// public:cut sharevoice
import com.follower.share.PackFile;
import com.follower.sound.VoiceLibrary;
// public:end sharevoice
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.callback.ClientThread;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The things QA3's threading audit found shared between the event thread
 * and the client thread: the import's write of phrases.json, the sound
 * library, and the Memory window's Forget everything.
 */
public class CrossThreadTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	// public:cut sharevoice
	@Test
	public void anImportReplacesTheFileWholeAndLeavesNothingBeside() throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Path file = dir.resolve("phrases.json");
		Files.write(file, "{\"rules\": []}\n".getBytes(StandardCharsets.UTF_8));
		JsonObject data = new JsonObject();
		data.addProperty("version", 2);
		PackFile.save(file, data, new Gson());
		assertEquals("{\"version\":2}\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		assertEquals("no temp file left beside it", 1, Files.list(dir).count());
	}

	@Test
	public void aLineIsFoundWhileAnotherIsAttached() throws Exception
	{
		VoiceLibrary voices = new VoiceLibrary(new Gson(), null);
		voices.attach("Hello.", "hello.wav");
		AtomicInteger lost = new AtomicInteger();
		Thread attaching = new Thread(() ->
		{
			for (int i = 0; i < 3_000; i++)
			{
				voices.attach("Line " + i, "line.wav");
			}
		});
		attaching.start();
		while (attaching.isAlive())
		{
			if (voices.attachmentFor("Hello.") == null)
			{
				lost.incrementAndGet();
			}
		}
		attaching.join();
		assertEquals("the playing thread never misses a line that is there", 0, lost.get());
		assertEquals(3_001, voices.attachmentCount());
	}
	// public:end sharevoice

	// public:cut memory-window
	@Test
	public void forgetEverythingDoesNothingOnceThePluginIsStopped() throws Exception
	{
		// The Memory window outlived the plugin, and its button still wiped
		// the stopped plugin's memory. Here a stopped plugin has no config
		// manager at all, so anything it tried would throw.
		FollowerPlugin plugin = new FollowerPlugin();
		Field thread = FollowerPlugin.class.getDeclaredField("clientThread");
		thread.setAccessible(true);
		thread.set(plugin, new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
				r.run();
			}
		});
		Field started = FollowerPlugin.class.getDeclaredField("started");
		started.setAccessible(true);
		assertFalse((Boolean) started.get(plugin));
		Method forget = FollowerPlugin.class.getDeclaredMethod("forgetEverything");
		forget.setAccessible(true);
		forget.invoke(plugin);
	}
	// public:end memory-window
}
