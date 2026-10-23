package com.follower;

import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * While an NPC dialogue is up the follower's words are dropped, and nothing
 * downstream of them happens: the latch a line carries - the question it
 * asks, the want, the wish, the wear, a first spent, a souvenir, a bet -
 * never closes on a line nobody heard.
 *
 * <p>The drop happens at the plugin's one door for speech, which no harness
 * drives, so this goes through that door on a bare plugin the way the entity
 * tests reach private state. The client has only the dialogue up, and the
 * rest of the plugin is left empty on purpose: a hushed line must not get far
 * enough to need it.
 */
public class DialogueHushTest
{
	/** A client with the one interface up, or nothing up at all for -1. */
	private static Client withOpen(int group)
	{
		Widget box = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, method, args) -> nothing(method.getReturnType()));
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getWidget") && args.length == 2)
				{
					return (Integer) args[0] == group ? box : null;
				}
				return nothing(method.getReturnType());
			});
	}

	private static Object nothing(Class<?> type)
	{
		return type == int.class ? 0 : type == long.class ? 0L : type == boolean.class ? false : null;
	}

	/** Says a line with a latch on it through the plugin's door; whether the latch closed. */
	private static boolean latched(int open) throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "client", withOpen(open));
		set(plugin, "config", new FollowerConfig()
		{
			// public:cut sharevoice
			@Override
			public boolean voiceEnabled()
			{
				return false;
			}
			// public:end sharevoice
		});

		boolean[] said = {false};
		Class<?> utterance = Class.forName("com.follower.FollowerPlugin$Utterance");
		Constructor<?> line = utterance.getDeclaredConstructor(String.class, SpeechOutput.class,
			SpeechRule.class, int.class, Runnable.class);
		line.setAccessible(true);
		Method speakNow = FollowerPlugin.class.getDeclaredMethod("speakNow", utterance);
		speakNow.setAccessible(true);
		// The chatbox output, with mirroring off: a line that lands goes
		// nowhere visible, so only the latch is left to say whether it did.
		Runnable onSaid = () -> said[0] = true;
		speakNow.invoke(plugin, line.newInstance("Talk to me a minute?", SpeechOutput.CHATBOX,
			null, -1, onSaid));
		return said[0];
	}

	private static void set(Object target, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	public void withNoDialogueUpTheLineLandsAndItsLatchCloses() throws ReflectiveOperationException
	{
		// The probe has to see a latch close before its silence means anything.
		assertTrue(latched(-1));
	}

	@Test
	public void aDialogueOfAnyShapeDropsTheLineAndItsLatch() throws ReflectiveOperationException
	{
		assertFalse("the NPC's head on the left", latched(InterfaceID.CHAT_LEFT));
		assertFalse("the player's own on the right", latched(InterfaceID.CHAT_RIGHT));
		assertFalse("and the options menu, which is still their conversation",
			latched(InterfaceID.CHATMENU));
	}
}
