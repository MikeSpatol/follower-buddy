package com.follower;

import com.follower.appearance.SpotAnimRepository;
import com.follower.follower.FollowerEntity;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ChatColorConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.util.Text;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ::follower gfx plays a graphic on the follower at its feet, or as far up
 * its body as it is told. A graphic built to sit on the body - a teleport's
 * swirl, a book's turning page - needs the height the game sends with it to
 * be judged by eye, and watch prints that height for a graphic on the player.
 */
public class GfxCommandTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The page turned right as the book is read: its model and its own animation. */
	private static final int PAGE = 548;
	private static final int PAGE_MODEL = 14319;

	/** What the follower was asked to show, as "model at height". */
	private final List<String> shown = new ArrayList<>();

	private FollowerPlugin plugin;
	private ChatMessageManager chat;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(SpotAnimRepository.FILE_NAME), ("{\"version\": 1, \"spotanims\": {\""
			+ PAGE + "\": {\"m\": " + PAGE_MODEL + ", \"a\": 3138}}}").getBytes(StandardCharsets.UTF_8));
		SpotAnimRepository graphics = new SpotAnimRepository(new Gson());
		graphics.load(dir);

		// The chat's own queue, which is all a status line reaches.
		Constructor<ChatMessageManager> made = ChatMessageManager.class.getDeclaredConstructor(
			Client.class, ChatColorConfig.class, ClientThread.class, EventBus.class);
		made.setAccessible(true);
		chat = made.newInstance(null, null, null, new EventBus());

		plugin = new FollowerPlugin();
		set("config", new FollowerConfig()
		{
		});
		set("chatMessageManager", chat);
		set("spotAnimRepository", graphics);
		set("clientThread", new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
				r.run();
			}
		});
		set("follower", new FollowerEntity(null, null)
		{
			@Override
			public void playSpotAnim(SpotAnimRepository.Entry fx, int height)
			{
				shown.add(fx.modelId() + " at " + height);
			}
		});
	}

	@Test
	public void aHeightLiftsTheGraphicThatFarUpTheBody() throws ReflectiveOperationException
	{
		gfx(String.valueOf(PAGE), "92");
		assertEquals(Collections.singletonList(PAGE_MODEL + " at 92"), shown);
		assertSaid("Playing graphic 548 on the follower at height 92.");
	}

	@Test
	public void withoutOneItIsAtTheFeetAsBefore() throws ReflectiveOperationException
	{
		gfx(String.valueOf(PAGE));
		assertEquals(Collections.singletonList(PAGE_MODEL + " at 0"), shown);
		assertSaid("Playing graphic 548 on the follower. Add 'set' to keep it as the shield.");
	}

	@Test
	public void aThirdWordThatIsNeitherAHeightNorSetPlaysNothing() throws ReflectiveOperationException
	{
		gfx(String.valueOf(PAGE), "high");
		assertEquals(Collections.emptyList(), shown);
		assertSaid("Numbers only: ::follower gfx <graphicId> [height|set]");
	}

	@Test
	public void itsHelpNamesTheHeight() throws ReflectiveOperationException
	{
		gfx();
		assertSaid("::follower gfx <graphicId> <height> - that far up its body");
		assertSaid("::follower gfx <graphicId> set - and keep it as the shield");
	}

	private void gfx(String... words)
	{
		String[] args = new String[words.length + 1];
		args[0] = "gfx";
		System.arraycopy(words, 0, args, 1, words.length);
		plugin.onCommandExecuted(new CommandExecuted("follower", args));
	}

	/** One of the lines queued for the chat ends with this, after the follower's name. */
	private void assertSaid(String line) throws ReflectiveOperationException
	{
		Field queue = ChatMessageManager.class.getDeclaredField("queuedMessages");
		queue.setAccessible(true);
		List<String> said = new ArrayList<>();
		for (Object message : (Collection<?>) queue.get(chat))
		{
			said.add(Text.unescapeJagex(((QueuedMessage) message).getRuneLiteFormattedMessage()));
		}
		assertTrue("no line says \"" + line + "\": " + said,
			said.stream().anyMatch(message -> message.endsWith(line)));
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}
