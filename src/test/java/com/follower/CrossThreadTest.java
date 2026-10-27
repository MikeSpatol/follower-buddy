package com.follower;

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
}
