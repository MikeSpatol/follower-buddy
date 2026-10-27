package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ProfileChanged;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A RuneLite profile switch: the old profile's session goes, and the new
 * one's memory is read. What the switch does to the engine, and when it
 * must do nothing at all.
 */
public class ProfileSwitchTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String LOGIN_RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"greet\", \"group\": \"t\", \"cooldownMs\": 0, \"delayTicks\": 3,"
		+ " \"when\": {\"type\": \"login\"}, \"say\": [\"there you are\"]},"
		+ "{\"id\": \"update\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"gameUpdated\"}, \"say\": [\"the world moved\"]}]}";

	/** Stands in for the gear worn and the place stood in: true all along. */
	private static final String WORLD_RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"gear\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"always\"}, \"say\": [\"nice hat\"]}]}";

	private Harness harness(String rules) throws IOException
	{
		return new Harness(folder.newFolder().toPath(), rules);
	}

	/** The engine's half of the switch, in the plugin's order. */
	private static void switchProfile(Harness h, boolean loginComing)
	{
		h.engine.reset();
		if (FollowerPlugin.switchTakesTheBaseline(loginComing))
		{
			h.engine.primeEdgesOnNextTick();
		}
	}

	@Test
	public void aSwitchAtTheLoginScreenLeavesTheBaselineToTheLogin() throws IOException
	{
		// Primed at the login screen, the window had no ticks to run it
		// down there, and was still open when the login said hello: the
		// greeting was discarded with everything else, and so was the week's
		// update line, which is marked as told straight after.
		Harness h = harness(LOGIN_RULES);
		h.engine.reset();
		switchProfile(h, true);

		// The login, as the plugin does it: the hello, the news, then the
		// login's own baseline.
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.GAME_UPDATE));
		h.engine.primeEdgesOnNextTick();
		h.gameTicks(8);

		assertEquals("the login after the switch is greeted", 1, h.firedBy("greet").size());
		assertEquals("and told the game changed", 1, h.firedBy("update").size());
	}

	@Test
	public void aSwitchInTheWorldTakesTheBaselineItself() throws IOException
	{
		// No login is coming to do it. The reset clears every edge, and
		// without the window the gear worn all along rose as news.
		Harness h = harness(WORLD_RULES);
		h.gameTicks(3);
		assertEquals("the stand-in speaks once, when first seen", 1, h.firedBy("gear").size());
		h.clear();

		switchProfile(h, false);
		h.gameTicks(6);

		assertTrue("nothing that was true before the switch is news after it",
			h.firedBy("gear").isEmpty());
	}

	@Test
	public void aSwitchAtTheLoginScreenIsNotReadAgainByTheLogin()
	{
		// The switch reads the new profile's memory in, and nothing between
		// it and the login lets it go. The login read it again on top, and
		// the tallies and the line wear merge by adding: every count doubled
		// and was saved that way at once, and again at every switch made at
		// the login screen after it.
		Gson gson = new Gson();
		TriggerContext kept = new TriggerContext(new FakeGame().client);
		for (int i = 0; i < 40; i++)
		{
			kept.tally("kill:goblin");
		}
		kept.noteLineSaid("A line said once.");
		String stored = FollowerPlugin.snapshotMemory(kept, 0, gson);
		long profile = 7L;

		// Logged out: the memory is blank and nobody's.
		TriggerContext context = new TriggerContext(new FakeGame().client);
		long readFrom = -1L;
		assertTrue("a login after a logout reads it",
			FollowerPlugin.loginReadsTheMemory(readFrom, profile));

		// The switch at the login screen reads this profile's.
		FollowerPlugin.restoreMemory(context, stored, gson, 0);
		readFrom = profile;

		// Then the login, in the plugin's order.
		if (FollowerPlugin.loginReadsTheMemory(readFrom, profile))
		{
			FollowerPlugin.restoreMemory(context, stored, gson, 0);
		}
		assertEquals("counted once", 40, context.getTally("kill:goblin"));
		assertEquals("and worn once", 1, context.lineWear("A line said once."));
		assertEquals("so what the login saves is what was stored",
			stored, FollowerPlugin.snapshotMemory(context, 0, gson));
	}

	@Test
	public void aSwitchThatReachesAStoppedPluginDoesNothing() throws Exception
	{
		// Switched to a profile where the plugin is off, RuneLite stops it on
		// the Swing thread while the switch's own work waits on the client
		// thread, and the stop can come first. The work then read the new
		// profile's memory into the engine the stop had just cleared, and the
		// next start read its own on top: two memories added together, and
		// saved that way. This plugin was never started and nothing else in
		// it is filled in, so a switch that goes ahead falls over on the
		// first thing it needs.
		Harness h = harness(LOGIN_RULES);
		FollowerPlugin plugin = new FollowerPlugin();
		set(plugin, "clientThread", onTheClientThread());
		set(plugin, "speechEngine", h.engine);
		Object before = h.engine.getContext();

		plugin.onProfileChanged(new ProfileChanged());

		assertSame("the engine is left as the stop left it", before, h.engine.getContext());
	}

	/** A client thread that runs what it is given at once, as on the client thread itself. */
	private static ClientThread onTheClientThread() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("isClientThread") ? Boolean.TRUE : null);
		ClientThread thread = new ClientThread();
		Field field = ClientThread.class.getDeclaredField("client");
		field.setAccessible(true);
		field.set(thread, client);
		return thread;
	}

	private static void set(Object target, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
