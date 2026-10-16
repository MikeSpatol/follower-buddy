package com.follower;

import com.follower.follower.FilmSet;
import com.follower.follower.FollowerEntity;
import com.follower.sim.Harness;
import com.follower.speech.Conversation;
import com.follower.speech.ConversationPlayer;
import com.follower.speech.FollowerOverlay;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where the film window's conversation plays, on the plugin's own stage:
 * which of the game's boxes it keeps clear of, and that the player's written
 * chat lines, which the chatbox hands back as if typed, are heard by no rule.
 */
public class ConversationStageTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness h;
	private FollowerPlugin plugin;

	/** The interfaces open now, by group id. */
	private final Set<Integer> open = new HashSet<>();

	@Before
	public void setUp() throws Exception
	{
		h = new Harness(folder.newFolder().toPath());
		plugin = new FollowerPlugin();
		Client game = h.game.client;
		Widget box = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, method, args) -> null);
		set("client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getWidget") && args != null && args.length == 2)
				{
					return open.contains((Integer) args[0]) ? box : null;
				}
				if (method.getName().equals("addChatMessage"))
				{
					// As the client does: the chatbox posts it to every listener at once.
					ChatMessage message = new ChatMessage();
					message.setType((ChatMessageType) args[0]);
					message.setName((String) args[1]);
					message.setMessage((String) args[2]);
					plugin.onChatMessage(message);
					return null;
				}
				try
				{
					return method.invoke(game, args);
				}
				catch (InvocationTargetException e)
				{
					throw e.getCause();
				}
			}));
		set("config", new FollowerConfig()
		{
		});
		set("speechEngine", h.engine);
		set("filmSet", new FilmSet(new FollowerEntity(null, null)));
		set("overlay", new FollowerOverlay(null, null, null, null));
	}

	@Test
	public void itKeepsClearOfEveryGameBoxOverTheChatbox() throws Exception
	{
		// QA2, 15 Oct: only the NPC, player and choice boxes were looked for,
		// and a written line was drawn over a level-up and took its click.
		ConversationPlayer.Stage stage = stage();
		assertFalse("nothing open", stage.gameDialogueOpen());
		for (int group : new int[]{InterfaceID.CHAT_LEFT, InterfaceID.CHAT_RIGHT, InterfaceID.CHATMENU,
			InterfaceID.MESSAGEBOX, InterfaceID.OBJECTBOX, InterfaceID.OBJECTBOX_DOUBLE, InterfaceID.LEVELUP_DISPLAY})
		{
			open.clear();
			open.add(group);
			assertTrue("interface " + group, stage.gameDialogueOpen());
		}
	}

	@Test
	public void thePlayersWrittenChatLineIsHeardByNoRule() throws Exception
	{
		// QA2, 15 Oct: a written "hello" went in as typed public chat and set
		// off greet-back, charging its cooldown in the middle of a take.
		h.gameTicks(1);
		stage().say(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, "hello there"), 3000);
		h.gameTicks(8);
		assertTrue("played back, not said", h.firedBy("greet-back").isEmpty());

		// The same words typed for real are heard as ever.
		ChatMessage typed = new ChatMessage();
		typed.setType(ChatMessageType.PUBLICCHAT);
		typed.setName("Tester");
		typed.setMessage("hello there");
		plugin.onChatMessage(typed);
		h.gameTicks(8);
		assertFalse("typed, so heard", h.firedBy("greet-back").isEmpty());
	}

	private ConversationPlayer.Stage stage() throws ReflectiveOperationException
	{
		Method stage = FollowerPlugin.class.getDeclaredMethod("conversationStage");
		stage.setAccessible(true);
		return (ConversationPlayer.Stage) stage.invoke(plugin);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}
