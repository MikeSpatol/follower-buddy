package com.follower;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import com.follower.follower.Moments;
import com.follower.follower.SkillingController;
import com.follower.sim.Harness;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A park the player gave - right-click Stay, a Send, Follow me off - holds
 * until they say Follow. Nothing of the follower's own takes the feet from
 * it: not the wander, the seat, a moment, the spectator, an errand or a
 * planted emote. And the player's own word acts whatever of those has the
 * feet at the time, instead of being taken for a stay already in place.
 * The fight is still announced at both ends and the logout tab still waved
 * at: neither moves the feet.
 *
 * <p>All of it is the plugin's glue, which no harness drives, so this goes
 * through a bare plugin the way the entity tests reach private state: the
 * follower's feet are a recording stand-in, and the clock is the harness's.
 */
public class StayHoldsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Records where the follower was put and how often it was let go. */
	private static final class Feet extends FollowerEntity
	{
		WorldPoint at = new WorldPoint(3221, 3218, 0);
		WorldPoint stay;
		int released;
		int sent;
		final List<Integer> played = new ArrayList<>();

		Feet()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public boolean isSettled()
		{
			return true;
		}

		@Override
		public boolean isStaying()
		{
			return stay != null;
		}

		@Override
		public WorldPoint getStayTile()
		{
			return stay;
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return at;
		}

		/** Every walk arrives at once: the tests are about who holds the feet, not the steps. */
		@Override
		public boolean stayAt(WorldPoint target)
		{
			if (target == null)
			{
				return false;
			}
			stay = target;
			at = target;
			sent++;
			return true;
		}

		@Override
		public void stayHere()
		{
			stay = at;
		}

		@Override
		public void resumeFollowing()
		{
			stay = null;
			released++;
		}

		@Override
		public void setStayFaceTile(WorldPoint tile)
		{
		}

		@Override
		public void setPoseOverride(int id)
		{
		}

		@Override
		public void playAnimation(int id)
		{
		}

		@Override
		public void playAnimations(int[] ids)
		{
			for (int id : ids)
			{
				played.add(id);
			}
		}

		@Override
		public void glanceAt(WorldPoint tile, int ms)
		{
		}

		@Override
		public boolean moveToFront(Player local)
		{
			stay = null;
			return true;
		}
	}

	private Harness h;
	private FollowerPlugin plugin;
	private Feet feet;
	/** Whether the NPC chat box is up, for the minute-taking. */
	private boolean npcDialogUp;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		h = new Harness(folder.newFolder().toPath());
		feet = new Feet();
		plugin = new FollowerPlugin();
		Client game = h.game.client;
		Widget box = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, method, args) -> nothing(method.getReturnType()));
		set("client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getWidget") && args != null && args.length == 2)
				{
					return npcDialogUp && (Integer) args[0] == InterfaceID.CHAT_LEFT ? box : null;
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
		set("follower", feet);
		set("speechEngine", h.engine);
		set("dialog", new com.follower.speech.FollowerDialog(null, null, null, null, null, null, null, null));
		// Empty hands: the minutes ask whether a kept tool is in them.
		set("hands", new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
			}

			@Override
			public void release()
			{
			}
		});
	}

	private static Object nothing(Class<?> type)
	{
		return type == int.class ? 0 : type == boolean.class ? false : null;
	}

	// ------------------------------------------------------------ the wander

	@Test
	public void theWanderLeavesAPlayersParkAlone() throws ReflectiveOperationException
	{
		// Follow me unticked, then a long stand: the drift window opens at
		// 48 seconds and nothing may take the post in it.
		call("applyFollowEnabled", false);
		WorldPoint post = feet.stay;
		assertEquals("parked where it stood", feet.at, post);

		standStill(100);
		for (int i = 0; i < 100; i++)
		{
			call("updateWander");
		}
		assertEquals("not drifted anywhere", 0, feet.sent);
		assertEquals(post, feet.stay);

		playerSteps();
		call("updateWander");
		call("keepThePlayersPark");
		assertEquals("and not let go when the player walks off", 0, feet.released);
		assertEquals(post, feet.stay);
		assertTrue(parked());
	}

	@Test
	public void stayHerePressedOverTheWandersDriftTakes() throws ReflectiveOperationException
	{
		drift();
		WorldPoint drifted = feet.stay;

		// The wander's own stayAt counts as a stay, so asking whether the
		// follower was staying found nothing to do, and the drift was let go
		// the moment the player walked off.
		call("applyFollowEnabled", false);
		assertTrue("the player's park now", parked());

		playerSteps();
		call("updateWander");
		call("keepThePlayersPark");
		assertEquals("not let go under the player's Stay", 0, feet.released);
		assertEquals(drifted, feet.stay);
	}

	@Test
	public void aSendDuringTheWanderIsTheSendsPost() throws ReflectiveOperationException
	{
		drift();
		WorldPoint sentTo = new WorldPoint(3230, 3220, 0);
		call("sendTo", sentTo);

		playerSteps();
		call("updateWander");
		call("keepThePlayersPark");
		assertEquals(0, feet.released);
		assertEquals(sentTo, feet.stay);
	}

	// -------------------------------------------------------------- the gates

	@Test
	public void everythingThatWouldTakeTheFeetReadsThePark() throws ReflectiveOperationException
	{
		for (String gate : new String[]{"seatFeetTaken", "momentFeetTaken", "errandBusy", "spectateBusy"})
		{
			assertFalse(gate + " with nothing going on", (Boolean) call(gate));
		}
		call("applyFollowEnabled", false);
		for (String gate : new String[]{"seatFeetTaken", "errandBusy"})
		{
			assertTrue(gate + " on a park the player gave", (Boolean) call(gate));
		}
		// The moments and the spectator are handed the park apart, since
		// the wave and the fight's announcements do not move the feet.
		for (String gate : new String[]{"momentFeetTaken", "spectateBusy"})
		{
			assertFalse(gate + " is not the park", (Boolean) call(gate));
		}
	}

	@Test
	public void theLogoutTabIsWavedAtFromThePost() throws ReflectiveOperationException
	{
		Moments moments = moments();
		call("applyFollowEnabled", false);
		WorldPoint post = feet.stay;

		moments.farewell();
		assertFalse("the wave, where it stands", feet.played.isEmpty());
		assertEquals(post, feet.stay);
	}

	@Test
	public void aHoldStillLinePlaysAtThePostWithoutPlanting() throws ReflectiveOperationException
	{
		// The plant's release at the end of the clip is a resumeFollowing, so
		// planting over the player's park handed it back to following.
		set("clientThread", clientThread());
		SpeechRule cheer = new SpeechRule();
		cheer.id = "hands-won";
		cheer.holdStill = true;
		cheer.animation = 862;

		speak(cheer);
		assertTrue("the probe reaches the plant", (Boolean) get("emoteHold"));

		set("emoteHold", false);
		call("applyFollowEnabled", false);
		speak(cheer);
		assertFalse("on the player's park nothing is planted", (Boolean) get("emoteHold"));
	}

	// ------------------------------------------ given while something has it

	@Test
	public void stayHerePressedDuringAMomentIsTheParkAsTheMomentStandsDown()
		throws ReflectiveOperationException
	{
		Moments moments = moments();
		assertTrue("off to look at a drop", moments.goAndLook(new WorldPoint(3226, 3218, 0), 1, false, 4));
		WorldPoint spot = feet.stay;

		call("applyFollowEnabled", false);
		assertTrue(parked());

		// The moment reads the park as its feet taken, and lets go without
		// handing the follower back to following.
		for (int i = 0; i < 20; i++)
		{
			h.gameTick();
			moments.tick();
			call("keepThePlayersPark");
		}
		assertFalse(moments.holdsTheFeet());
		assertEquals("never let go", 0, feet.released);
		assertEquals(spot, feet.stay);
		assertTrue(parked());
	}

	@Test
	public void aParkGivenWhileSomethingHadTheFeetIsTakenAgainAsItLetsGo()
		throws ReflectiveOperationException
	{
		// A planted emote stands in for anything that lets go by releasing:
		// an errand's abort, the spectator's stop, the minutes' end.
		feet.stayHere();
		set("emoteHold", true);
		WorldPoint post = feet.at;
		call("applyFollowEnabled", false);

		call("keepThePlayersPark");
		assertEquals("waits while the clip plays", post, feet.stay);

		set("emoteHold", false);
		feet.resumeFollowing();
		call("keepThePlayersPark");
		assertEquals("parked again on the spot it was given", post, feet.stay);
		assertTrue(parked());
	}

	@Test
	public void aStayGivenWhileSomethingHadTheFeetParksWhereThatLeftIt()
		throws ReflectiveOperationException
	{
		// Stay here pressed during thrall work, or an errand far off: what
		// lets go sends the follower home to the player's side. It used to
		// be walked back from there to wherever the Stay was pressed.
		set("thrallExitTicks", 2);
		call("applyFollowEnabled", false);
		WorldPoint pressedAt = feet.stay;

		set("thrallExitTicks", 0);
		feet.resumeFollowing();
		WorldPoint home = new WorldPoint(3224, 3222, 0);
		feet.at = home;
		call("keepThePlayersPark");
		assertEquals("parked at the player's side", home, feet.stay);
		assertNotEquals(pressedAt, feet.stay);
		assertEquals("not walked back", 0, feet.sent);
		assertTrue(parked());
	}

	@Test
	public void aSendGivenWhileSomethingHadTheFeetIsWalkedToOnceFree()
		throws ReflectiveOperationException
	{
		// A Send is a tile the player pointed at, not where the follower
		// happened to be, so it is gone to however it was let go.
		feet.stayHere();
		set("emoteHold", true);
		WorldPoint sentTo = new WorldPoint(3230, 3220, 0);
		call("sendTo", sentTo);

		set("emoteHold", false);
		feet.resumeFollowing();
		feet.at = new WorldPoint(3224, 3222, 0);
		call("keepThePlayersPark");
		assertEquals("gone to the Send's tile", sentTo, feet.stay);
		assertTrue(parked());
	}

	@Test
	public void aParkTakenOffByForceIsOver() throws ReflectiveOperationException
	{
		// The leash snap and a teleport bring the follower along to the
		// player; they are not somebody else's borrowing to wait out.
		call("applyFollowEnabled", false);
		feet.resumeFollowing();
		call("keepThePlayersPark");
		assertFalse(feet.isStaying());
		assertFalse("so the next Stay here takes again", parked());
		call("applyFollowEnabled", false);
		assertTrue(feet.isStaying());
	}

	// ------------------------------------------------ what the wander lets go

	@Test
	public void theWanderHandsOverAStayAMomentTookFromIt() throws ReflectiveOperationException
	{
		drift();
		Moments moments = moments();
		// Noticing, while the follower stood where the wander left it.
		assertTrue(moments.goAndLook(new WorldPoint(3226, 3218, 0), 1, false, 4));
		WorldPoint spot = feet.stay;

		call("updateWander");
		assertEquals("the look is not cancelled under the moment", 0, feet.released);
		assertEquals(spot, feet.stay);
		assertTrue(moments.holdsTheFeet());
		assertFalse("and the drift is forgotten", (Boolean) get("wandered"));
	}

	@Test
	public void aLongDialogKeepsTheMinuteTakerAtTheShoulder() throws ReflectiveOperationException
	{
		drift();

		// Talk-to on the NPC beside them, without a step: the minutes take
		// the shoulder over the wander's drift.
		npcDialogUp = true;
		call("updateMinutes");
		assertNotEquals("taken to the shoulder", 0, get("minutesState"));
		WorldPoint shoulder = feet.stay;

		// Reading on for minutes: the idle clock keeps climbing, since
		// clicking Continue is neither a step nor an animation.
		for (int i = 0; i < 120; i++)
		{
			call("updateWander");
		}
		assertEquals("held at the shoulder until the box closes", shoulder, feet.stay);
		assertEquals(0, feet.released);
	}

	// ---------------------------------------------------------------- the glue

	/**
	 * Every park the player gives is marked as theirs, which is what the
	 * wander, the seat, the moments, the spectator and the errands read.
	 * The menu ops and the spawns are glue no harness drives, so this reads
	 * them out of the source: a menu op that stays the follower, and a spawn
	 * that parks it for Follow me off, each has to say so. Without it a
	 * right-click Stay with Follow me already off raised no change event,
	 * and the wander took the post again.
	 */
	@Test
	public void everyParkThePlayerGivesIsMarkedAsTheirs() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8);
		List<String> unmarked = new ArrayList<>();
		int ops = 0;
		for (String marker : new String[]{"addFollowerMenuEntry(", ".onClick("})
		{
			for (String op : balancedAfter(source, marker, '(', ')'))
			{
				if (op.contains("stayHere") || op.contains("stayAt("))
				{
					ops++;
					if (!op.contains("parkedByThePlayer("))
					{
						unmarked.add(op);
					}
				}
			}
		}
		int spawns = 0;
		for (String spawn : balancedAfter(source, "if (!config.followEnabled())", '{', '}'))
		{
			spawns++;
			if (!spawn.contains("parkedByThePlayer("))
			{
				unmarked.add(spawn);
			}
		}
		assertTrue("no menu op stays the follower - the scan broke, not the glue", ops > 0);
		assertTrue("no spawn parks it for Follow me off - the scan broke, not the glue", spawns > 0);
		assertTrue("parks the player gave that nothing reads as theirs:\n"
			+ String.join("\n", unmarked), unmarked.isEmpty());
	}

	/** From each marker, the source up to the close matching the first open at or after it. */
	private static List<String> balancedAfter(String source, String marker, char open, char close)
	{
		List<String> found = new ArrayList<>();
		for (int at = source.indexOf(marker); at >= 0; at = source.indexOf(marker, at + 1))
		{
			int depth = 0;
			int end = source.indexOf(open, at);
			for (; end < source.length(); end++)
			{
				char c = source.charAt(end);
				if (c == open)
				{
					depth++;
				}
				else if (c == close && --depth == 0)
				{
					break;
				}
			}
			found.add(source.substring(at, Math.min(end + 1, source.length())));
		}
		return found;
	}

	// ---------------------------------------------------------------- helpers

	/** Stands still long enough for the wander, and lets it drift once. */
	private void drift() throws ReflectiveOperationException
	{
		standStill(100);
		call("updateWander");
		assertTrue("the wander drifted", (Boolean) get("wandered"));
		assertTrue(feet.isStaying());
	}

	private void standStill(int ticks)
	{
		h.gameTicks(ticks);
	}

	private void playerSteps()
	{
		WorldPoint here = h.game.player.getWorldLocation();
		h.game.at(here.getX() + 1, here.getY(), here.getPlane());
		h.gameTick();
	}

	/** The moments on these feet, wired to the plugin's own gate and park as the plugin wires them. */
	private Moments moments() throws ReflectiveOperationException
	{
		ErrandController.Hands hands = new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
			}

			@Override
			public void release()
			{
			}
		};
		FollowerConfig config = new FollowerConfig()
		{
		};
		SkillingController skilling = new SkillingController(h.game.client, feet, config, hands,
			event -> { }, () -> false, () -> false, () -> false);
		Moments moments = new Moments(h.game.client, feet, config, skilling,
			() -> gate("momentFeetTaken"), () -> false, () -> 30, null, () -> false,
			() -> field("parkedByPlayer"));
		set("moments", moments);
		return moments;
	}

	private boolean gate(String name)
	{
		try
		{
			return (Boolean) call(name);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private boolean parked() throws ReflectiveOperationException
	{
		return (Boolean) get("parkedByPlayer");
	}

	private boolean field(String name)
	{
		try
		{
			return (Boolean) get(name);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private void speak(SpeechRule rule) throws ReflectiveOperationException
	{
		Class<?> utterance = Class.forName("com.follower.FollowerPlugin$Utterance");
		Constructor<?> line = utterance.getDeclaredConstructor(String.class, SpeechOutput.class,
			SpeechRule.class, int.class, Runnable.class);
		line.setAccessible(true);
		Method speakNow = FollowerPlugin.class.getDeclaredMethod("speakNow", utterance);
		speakNow.setAccessible(true);
		speakNow.invoke(plugin, line.newInstance("", SpeechOutput.CHATBOX, rule, -1, null));
	}

	/** A client thread that queues and never runs: the plant is read off the flag it sets first. */
	private Object clientThread() throws ReflectiveOperationException
	{
		net.runelite.client.callback.ClientThread thread = new net.runelite.client.callback.ClientThread();
		Field client = thread.getClass().getDeclaredField("client");
		client.setAccessible(true);
		client.set(thread, h.game.client);
		return thread;
	}

	private Object call(String name, Object... args) throws ReflectiveOperationException
	{
		for (Method method : FollowerPlugin.class.getDeclaredMethods())
		{
			if (method.getName().equals(name) && method.getParameterCount() == args.length)
			{
				method.setAccessible(true);
				try
				{
					return method.invoke(plugin, args);
				}
				catch (InvocationTargetException e)
				{
					if (e.getCause() instanceof RuntimeException)
					{
						throw (RuntimeException) e.getCause();
					}
					if (e.getCause() instanceof Error)
					{
						throw (Error) e.getCause();
					}
					throw e;
				}
			}
		}
		throw new AssertionError("FollowerPlugin." + name + " has moved");
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(plugin);
	}
}
