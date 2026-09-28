package com.follower;

import com.follower.appearance.OutfitProfileStore;
import com.follower.sim.FakeGame;
import com.follower.sim.Harness;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.client.callback.ClientThread;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
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
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

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

	@Test
	public void thrallOnlyTakesTheThrallEvenWithReplaceThrallsOff() throws Exception
	{
		// Thrall only is the replacement and nothing else, so it brings the
		// replacement with it. With Replace thralls off as well, no summon was
		// ever taken, and the follower was hidden for good with no word why.
		FakeGame game = new FakeGame().varbit(12290, 1);
		NPC thrall = game.spawnNpc(10886, "Greater Zombified Spawn", 0);
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);

		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "client", game.client);
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public boolean thrallMode()
			{
				return false;
			}

			@Override
			public boolean thrallOnly()
			{
				return true;
			}
		});
		set(plugin, "speechEngine", h.engine);
		set(plugin, "profileStore", new OutfitProfileStore(new Gson()));
		// The body's part runs on the client thread; only the taking is asked about.
		set(plugin, "clientThread", new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
			}
		});
		assertTrue(hidden(plugin));

		Method adopt = FollowerPlugin.class.getDeclaredMethod("maybeAdoptThrall", NPC.class);
		adopt.setAccessible(true);
		adopt.invoke(plugin, thrall);
		assertFalse("the summon brings it", hidden(plugin));

		assertTrue("the varbit's path asks the same question", FollowerPlugin.replacesThralls(false, true));
		assertFalse("and with both off no thrall is ever taken", FollowerPlugin.replacesThralls(false, false));
	}

	/**
	 * Announces a summon the way adoptThrall does, on an engine the absent
	 * ticks left muted; what was said.
	 */
	private List<Harness.Spoken> summonedFromAbsence(boolean thieving) throws Exception
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);
		h.engine.setMuted(true);

		FollowerPlugin plugin = plugin(true);
		set(plugin, "speechEngine", h.engine);
		set(plugin, "wasThieving", thieving);
		// adoptThrall has taken the thrall before it says hello.
		set(plugin, "thrallNpc", new FakeGame().spawnNpc(10886, "Greater Zombified Spawn", 0));
		Method announce = FollowerPlugin.class.getDeclaredMethod("announceSummon", String.class);
		announce.setAccessible(true);
		announce.invoke(plugin, "melee");
		return h.firedBy("thrall-start");
	}

	@Test
	public void theSummonIsGreetedAlthoughTheAbsenceMutedTheEngine() throws Exception
	{
		// The summon arrives between ticks, while the mute the last absent
		// tick set is still on. Said under that mute, the hello was lost on
		// every summon and only the goodbye was ever heard.
		assertEquals("the summon ends the absence, and the hello is said",
			1, summonedFromAbsence(false).size());
		assertTrue("but a pocket being worked keeps its silence",
			summonedFromAbsence(true).isEmpty());
	}
}
