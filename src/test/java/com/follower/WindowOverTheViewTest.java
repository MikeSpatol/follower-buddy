package com.follower;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether the scribe sees an interface over the view, from which slot of the
 * layout has something in it.
 *
 * <p>Play review: the Falador sketch came out a picture of the world map,
 * tooltip and all. Only the main modal slot was looked in, where the bank
 * opens; the world map, the settings and the collection log open in the
 * floater slot, which stays open as you walk, and the world map made full
 * screen opens in a layout of its own.
 */
public class WindowOverTheViewTest
{
	/** A client whose only filled slot is {@code filled}, or none. */
	private static Client withSomethingIn(int filled)
	{
		Widget inside = widget(new Widget[0]);
		Widget slot = widget(new Widget[]{inside});
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getWidget") && args.length == 1)
				{
					return (Integer) args[0] == filled ? slot : widget(new Widget[0]);
				}
				Class<?> type = method.getReturnType();
				return type == int.class ? 0 : type == boolean.class ? false : null;
			});
	}

	private static Widget widget(Widget[] nested)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getNestedChildren":
						return nested;
					case "isHidden":
						return false;
					default:
						Class<?> type = method.getReturnType();
						return type == int.class ? 0 : type == boolean.class ? false : null;
				}
			});
	}

	@Test
	public void nothingOpenIsAClearView()
	{
		assertFalse(FollowerPlugin.windowOverTheView(withSomethingIn(-1)));
	}

	@Test
	public void theBankInTheMainModalSlotCoversTheView()
	{
		assertTrue(FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.ToplevelOsrsStretch.MAINMODAL)));
		assertTrue(FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.ToplevelPreEoc.MAINMODAL)));
		assertTrue(FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.Toplevel.MAINMODAL)));
	}

	@Test
	public void theWorldMapInTheFloaterSlotCoversTheView()
	{
		assertTrue("resizable", FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.ToplevelOsrsStretch.FLOATER)));
		assertTrue("resizable, bottom line", FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.ToplevelPreEoc.FLOATER)));
		assertTrue("fixed", FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.Toplevel.FLOATER)));
	}

	@Test
	public void theWorldMapFullScreenCoversTheView()
	{
		assertTrue(FollowerPlugin.windowOverTheView(withSomethingIn(InterfaceID.ToplevelDisplay.DISPLAYMODAL)));
	}
}
