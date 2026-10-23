package com.follower;

import com.follower.appearance.AppearanceService;
import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.Outfit;
import com.follower.appearance.OutfitParser;
import com.follower.appearance.OutfitProfileStore; // public:cut outfit-profiles
import com.follower.follower.FollowerEntity;
import com.follower.sim.FakeGame;
import com.follower.sim.Harness;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.NPC;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
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
	public void theReplaceThrallsHintNamesThrallOnlyWhileItIsTicked() throws Exception
	{
		// QA3: "Untick Replace thralls" does nothing with Thrall only on, so
		// with it ticked the hint names both switches (QA4, approved wording).
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n"));
		int at = source.indexOf("private void maybeSendThrallHint()");
		assertTrue("the lint has lost its place", at >= 0);
		String body = source.substring(at, source.indexOf("\n\t}", at));
		assertTrue(body, body.contains("config.thrallOnly()\n\t\t\t? \"Untick Thrall only and Replace thralls"
			+ " in settings to keep your own.\""));
		assertTrue(body, body.contains(": \"Untick Replace thralls in settings to keep your own.\""));
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
	public void turningItOffBuildsTheFollowerAfreshInItsOwnOutfit() throws Exception
	{
		// The mode's login builds no model at all, and its exit leaves the
		// thrall's behind. Turned off, the follower has to come back now, not
		// at the next loading screen, and in its own outfit, not the thrall's.
		FollowerPlugin plugin = plugin(false);
		set(plugin, "speechEngine", new Harness(folder.newFolder().toPath()).engine);
		set(plugin, "follower", new FollowerEntity(null, null));
		set(plugin, "clientThread", new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
				r.run();
			}
		});
		List<Outfit> asked = new ArrayList<>();
		set(plugin, "appearanceService", new AppearanceService(null)
		{
			@Override
			public void invalidate()
			{
				// No capture runs here to abort.
			}

			@Override
			public void request(Outfit outfit, Consumer<ComposedAppearance> onReady)
			{
				asked.add(outfit);
			}
		});

		ConfigChanged changed = new ConfigChanged();
		changed.setGroup(FollowerConfig.GROUP);
		changed.setKey("thrallOnly");
		plugin.onConfigChanged(changed);

		Outfit own = OutfitParser.parse(new FollowerConfig()
		{
		}.customOutfit());
		assertEquals("a model is asked for, in its own outfit",
			Collections.singletonList(own), asked);
	}

	/**
	 * A thrall-only plugin in a game with a thrall beside the player,
	 * speaking through the harness's engine.
	 */
	private static FollowerPlugin summoning(FakeGame game, Harness h, boolean replaceThralls)
		throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "client", game.client);
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public boolean thrallMode()
			{
				return replaceThralls;
			}

			@Override
			public boolean thrallOnly()
			{
				return true;
			}

			// The one-time Replace thralls hint goes to the chat, which this
			// plugin has none of; it is told already.
			@Override
			public boolean thrallHintShown()
			{
				return true;
			}
		});
		set(plugin, "speechEngine", h.engine);
		set(plugin, "ruleLoader", h.loader);
		set(plugin, "profileStore", new OutfitProfileStore(new Gson())); // public:cut outfit-profiles
		// The body's part runs on the client thread; only the taking is asked about.
		set(plugin, "clientThread", new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
			}
		});
		return plugin;
	}

	private static void adopt(FollowerPlugin plugin, NPC thrall) throws ReflectiveOperationException
	{
		Method adopt = FollowerPlugin.class.getDeclaredMethod("maybeAdoptThrall", NPC.class);
		adopt.setAccessible(true);
		adopt.invoke(plugin, thrall);
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

		FollowerPlugin plugin = summoning(game, h, false);
		assertTrue(hidden(plugin));

		adopt(plugin, thrall);
		assertFalse("the summon brings it", hidden(plugin));

		assertFalse("and with both off no thrall is ever taken", FollowerPlugin.replacesThralls(false, false));
	}

	@Test
	public void theVarbitTakesTheThrallEvenWithReplaceThrallsOff() throws Exception
	{
		// A thrall that spawns before the varbit rises is turned away at the
		// spawn, and only the varbit's path can take it. That path asked
		// Replace thralls alone too.
		FakeGame game = new FakeGame();
		game.spawnNpc(10886, "Greater Zombified Spawn", 0);
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);

		FollowerPlugin plugin = summoning(game, h, false);
		assertTrue(hidden(plugin));

		game.varbit(12290, 1);
		VarbitChanged risen = new VarbitChanged();
		risen.setVarbitId(12290);
		risen.setValue(1);
		plugin.onVarbitChanged(risen);
		assertFalse("the varbit brings it", hidden(plugin));
	}

	/**
	 * Summons a thrall into thrall-only mode's absence, on an engine the
	 * absent ticks left muted; what was said.
	 */
	private List<Harness.Spoken> summonedFromAbsence(boolean thieving) throws Exception
	{
		FakeGame game = new FakeGame().varbit(12290, 1);
		NPC thrall = game.spawnNpc(10886, "Greater Zombified Spawn", 0);
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);
		h.engine.setMuted(true);

		FollowerPlugin plugin = summoning(game, h, true);
		set(plugin, "wasThieving", thieving);
		adopt(plugin, thrall);
		return h.firedBy("thrall-start");
	}

	/** A thrall-mode plugin, Thrall only or not, with Mute the thrall as given. */
	private static FollowerPlugin thrallMode(FakeGame game, Harness h, boolean thrallOnly, boolean muteThrall)
		throws ReflectiveOperationException
	{
		FollowerPlugin plugin = summoning(game, h, true);
		set(plugin, "config", new FollowerConfig()
		{
			@Override
			public boolean thrallOnly()
			{
				return thrallOnly;
			}

			@Override
			public boolean muteThrall()
			{
				return muteThrall;
			}

			// No chat here for the one-time Replace thralls hint.
			@Override
			public boolean thrallHintShown()
			{
				return true;
			}
		});
		return plugin;
	}

	/** Summons, then lets the thrall go ten seconds later; what each end said. */
	private int[] helloAndGoodbye(boolean thrallOnly, boolean muteThrall) throws Exception
	{
		long[] now = {1_000_000L};
		FakeGame game = new FakeGame().varbit(12290, 1);
		NPC thrall = game.spawnNpc(10886, "Greater Zombified Spawn", 0);
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(() -> now[0]);
		h.gameTicks(2);
		// Thrall only's absent ticks leave the engine muted; the everyday
		// follower's leave it talking.
		h.engine.setMuted(thrallOnly);

		FollowerPlugin plugin = thrallMode(game, h, thrallOnly, muteThrall);
		adopt(plugin, thrall);
		now[0] += 10_000L;
		Method exit = FollowerPlugin.class.getDeclaredMethod("performThrallExit", String.class);
		exit.setAccessible(true);
		exit.invoke(plugin, "melee");
		return new int[]{h.firedBy("thrall-start").size(), h.firedBy("thrall-end").size()};
	}

	@Test
	public void theThrallTalksWheneverTheFollowerMay() throws Exception
	{
		assertArrayEquals("Replace thralls: hello and goodbye", new int[]{1, 1}, helloAndGoodbye(false, false));
		assertArrayEquals("Thrall only: the same", new int[]{1, 1}, helloAndGoodbye(true, false));
	}

	@Test
	public void muteTheThrallKeepsItQuietFromHelloToGoodbye() throws Exception
	{
		assertArrayEquals("Replace thralls, muted: neither end", new int[]{0, 0}, helloAndGoodbye(false, true));
		assertArrayEquals("Thrall only, muted: neither end", new int[]{0, 0}, helloAndGoodbye(true, true));

		// And only as the thrall: the rest of the time it talks as usual.
		assertTrue(FollowerPlugin.thrallKeepsQuiet(true, true));
		assertFalse(FollowerPlugin.thrallKeepsQuiet(true, false));
		assertFalse(FollowerPlugin.thrallKeepsQuiet(false, true));
	}

	@Test
	public void theTickKeepsTheThrallQuietBetweenItsEnds() throws IOException
	{
		// What the thrall says while it fights goes through the tick's mute,
		// glue no harness drives; the tick has to ask, as the thieving lift
		// that says the thieving lines does.
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		assertTrue("the tick's mute asks",
			source.contains("speechEngine.setMuted(config.muted() || thievingNow || absentNow || thrallQuiet()"));
		assertTrue("and so does the thieving lift",
			source.contains("speechEngine.setMuted(config.muted() || thrallQuiet());"));
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
