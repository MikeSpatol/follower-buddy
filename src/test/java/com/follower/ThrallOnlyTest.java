package com.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.NPC;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Thrall only: the follower exists while a thrall has it, in any way, and
 * not otherwise. The rule itself is pinned beside the setting's default;
 * what this pins is "in any way", which is the plugin's to decide and which
 * no truth table of the rule can see. The phase-out after the thrall goes
 * and a scene load mid-thrall both still count - hidden on either, the exit
 * is never seen, and a load in the middle of a fight loses the thrall.
 */
public class ThrallOnlyTest
{
	private static FollowerPlugin plugin(boolean thrallOnly) throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "client", new FakeGame().client);
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public boolean thrallOnly()
			{
				return thrallOnly;
			}
		});
		return plugin;
	}

	private static boolean hidden(FollowerPlugin plugin) throws ReflectiveOperationException
	{
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

	@Test
	public void absentUntilAThrallHasIt() throws ReflectiveOperationException
	{
		assertTrue("no thrall, no follower", hidden(plugin(true)));

		FollowerPlugin summoned = plugin(true);
		NPC thrall = new FakeGame().spawnNpc(10886, "Greater Zombified Spawn", 0);
		set(summoned, "thrallNpc", thrall);
		assertFalse("a thrall has it", hidden(summoned));

		assertFalse("and the everyday follower is never hidden by the mode", hidden(plugin(false)));
	}

	@Test
	public void thePhaseOutAndASceneLoadStillCountAsTheThrallsBusiness()
		throws ReflectiveOperationException
	{
		FollowerPlugin leaving = plugin(true);
		set(leaving, "thrallExitTicks", 4);
		assertFalse("the phase-out plays where it stands before it goes", hidden(leaving));

		FollowerPlugin waiting = plugin(true);
		set(waiting, "thrallExitPendingTicks", 3);
		assertFalse("a vanished thrall might be a resummon: held a beat", hidden(waiting));

		FollowerPlugin loading = plugin(true);
		set(loading, "thrallLimbo", true);
		assertFalse("a scene load drops the thrall NPC, not the thrall", hidden(loading));
	}
}
