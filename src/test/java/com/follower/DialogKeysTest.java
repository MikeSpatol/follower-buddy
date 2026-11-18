package com.follower;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.client.input.KeyListener;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Space continues and the number keys pick an option, as in the game's own
 * dialogues (issue #3, Oct 2026: "I'm so used to pressing space and using
 * number keys to go through dialogue"). Like a click, a key takes effect on
 * the next tick.
 */
public class DialogKeysTest
{
	private final Canvas source = new Canvas();
	private String typed = "";
	private FollowerDialog dialog;
	private KeyListener keys;

	@Before
	public void openABox() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> "getVarcStrValue".equals(method.getName()) ? typed : null);
		dialog = new FollowerDialog(client, null, null, null, null, null, null, null);
		Map<String, FollowerDialog.Node> script = new LinkedHashMap<>();
		script.put("talk", FollowerDialog.Node.says("One.", "Two."));
		script.put("menu", FollowerDialog.Node.says().choices("Stay", "gone", "Go", "gone too"));
		dialog.openForTest(script);
		Field field = FollowerDialog.class.getDeclaredField("dialogKeys");
		field.setAccessible(true);
		keys = (KeyListener) field.get(dialog);
	}

	/** Presses a key the way AWT delivers it, and says whether the box took it from the game. */
	private boolean press(int code, char c)
	{
		KeyEvent pressed = new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, code, c);
		keys.keyPressed(pressed);
		KeyEvent typedEvent = new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c);
		keys.keyTyped(typedEvent);
		assertEquals("the character typed goes where its key went", pressed.isConsumed(), typedEvent.isConsumed());
		return pressed.isConsumed();
	}

	@Test
	public void spaceContinuesOnTheNextTick()
	{
		dialog.showForTest("talk");
		assertTrue(press(KeyEvent.VK_SPACE, ' '));
		assertEquals("not before the tick", 0, dialog.pageForTest());
		dialog.tick();
		assertEquals(1, dialog.pageForTest());
		assertFalse("any other key is the game's", press(KeyEvent.VK_A, 'a'));
		dialog.tick();
		assertEquals(1, dialog.pageForTest());
	}

	@Test
	public void aSecondPressBeforeTheTickIsSwallowed()
	{
		dialog.showForTest("talk");
		press(KeyEvent.VK_SPACE, ' ');
		assertTrue(press(KeyEvent.VK_SPACE, ' '));
		dialog.tick();
		assertEquals("one page per tick, as with clicks", 1, dialog.pageForTest());
	}

	@Test
	public void aNumberPicksThatOption()
	{
		dialog.showForTest("menu");
		assertFalse("only two options", press(KeyEvent.VK_3, '3'));
		assertFalse("space does not pick", press(KeyEvent.VK_SPACE, ' '));
		assertEquals(-1, dialog.optionInFlightForTest());
		assertTrue(press(KeyEvent.VK_2, '2'));
		assertEquals(1, dialog.optionInFlightForTest());
		dialog.tick();
		assertFalse("its target is not in the script, so the box closes", dialog.isOpen());
	}

	@Test
	public void theNumberPadPicksToo()
	{
		dialog.showForTest("menu");
		assertTrue(press(KeyEvent.VK_NUMPAD1, '1'));
		assertEquals(0, dialog.optionInFlightForTest());
	}

	@Test
	public void aMessageBeingTypedKeepsItsKeys()
	{
		typed = "brb";
		dialog.showForTest("talk");
		assertFalse(press(KeyEvent.VK_SPACE, ' '));
		dialog.tick();
		assertEquals(0, dialog.pageForTest());
		dialog.showForTest("menu");
		assertFalse(press(KeyEvent.VK_1, '1'));
		assertEquals(-1, dialog.optionInFlightForTest());
	}

	@Test
	public void noBoxNoKeys()
	{
		dialog.showForTest("talk");
		dialog.close();
		assertFalse(press(KeyEvent.VK_SPACE, ' '));
	}
}
