package com.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hide in PvP areas: the setting says the Wilderness and other PvP areas,
 * and the follower goes in both. QA2, 15 Oct: only the Wilderness was read,
 * so on a PvP world it stayed out beside the player.
 */
public class HideInPvpTest
{
	@Test
	public void itGoesInTheWildernessAndEveryOtherPvpArea() throws ReflectiveOperationException
	{
		assertFalse("anywhere else it stays", hidden(new FakeGame(), true));
		assertTrue("the Wilderness", hidden(new FakeGame().varbit(VarbitID.INSIDE_WILDERNESS, 1), true));
		assertTrue("a PvP area, such as a PvP world outside its safe zones",
			hidden(new FakeGame().varbit(VarbitID.PVP_AREA_CLIENT, 1), true));
		assertFalse("and none of it with the setting off",
			hidden(new FakeGame().varbit(VarbitID.PVP_AREA_CLIENT, 1), false));
	}

	private static boolean hidden(FakeGame game, boolean hideInPvp) throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "client", game.client);
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public boolean hideInPvp()
			{
				return hideInPvp;
			}
		});
		Method shouldHide = FollowerPlugin.class.getDeclaredMethod("shouldHide");
		shouldHide.setAccessible(true);
		return (Boolean) shouldHide.invoke(plugin);
	}

	private static void set(Object target, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
