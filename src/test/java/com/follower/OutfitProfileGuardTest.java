// public:file outfit-profiles
package com.follower;

import com.follower.appearance.OutfitProfileStore;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ChatColorConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.util.Text;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * QA3: New profile... named Melee, Ranged, Magic or Default follower wrote
 * the outfit worn over that look, and nothing restores a changed one; and
 * the list is written from both the event thread and the client thread.
 */
public class OutfitProfileGuardTest
{
	// public:cut thrall-outfits
	@Test
	public void aThrallLookIsNotWrittenOverByNewProfile() throws Exception
	{
		OutfitProfileStore store = new OutfitProfileStore(new Gson());
		store.load(null);
		String melee = store.get("Melee");
		String bare = store.get("Default follower");

		Constructor<ChatMessageManager> made = ChatMessageManager.class.getDeclaredConstructor(
			Client.class, ChatColorConfig.class, ClientThread.class, EventBus.class);
		made.setAccessible(true);
		ChatMessageManager chat = made.newInstance(null, null, null, new EventBus());

		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public String customOutfit()
			{
				return "HEAD=item:1153";
			}
		});
		set(plugin, "chatMessageManager", chat);
		set(plugin, "profileStore", store);
		Method save = FollowerPlugin.class.getDeclaredMethod("saveOutfitProfile", String.class);
		save.setAccessible(true);
		save.invoke(plugin, "Melee");
		save.invoke(plugin, " Default follower ");

		assertEquals(melee, store.get("Melee"));
		assertEquals(bare, store.get("Default follower"));
		Field queue = ChatMessageManager.class.getDeclaredField("queuedMessages");
		queue.setAccessible(true);
		List<String> said = new ArrayList<>();
		for (Object message : (Collection<?>) queue.get(chat))
		{
			said.add(Text.unescapeJagex(((QueuedMessage) message).getRuneLiteFormattedMessage()));
		}
		assertEquals("one refusal each: " + said, 2, said.size());
		assertTrue(said.toString(), said.get(0).contains("'Melee'") && !said.get(0).contains("created"));
	}

	// public:end thrall-outfits
	@Test
	public void twoThreadsSavingAtOnceLoseNoProfile() throws Exception
	{
		OutfitProfileStore store = new OutfitProfileStore(new Gson());
		store.load(null);
		int each = 500;
		Thread[] savers = new Thread[2];
		for (int t = 0; t < savers.length; t++)
		{
			String prefix = "t" + t + "-";
			savers[t] = new Thread(() ->
			{
				for (int i = 0; i < each; i++)
				{
					store.put(prefix + i, "");
				}
			});
		}
		for (Thread t : savers)
		{
			t.start();
		}
		for (Thread t : savers)
		{
			t.join();
		}
		assertEquals("the four built-in looks and every one saved", 4 + 2 * each, store.names().size());
	}

	private static void set(Object owner, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}
}
