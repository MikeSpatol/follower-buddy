package com.follower;

// public:cut chronicle
import com.follower.chronicle.ChronicleLoader;
import com.follower.chronicle.SketchBook;
import com.follower.chronicle.SketchController;
// public:end chronicle
import com.follower.follower.ErrandController;
import com.follower.follower.FilmSet; // public:cut posing
import com.follower.follower.FollowerEntity;
import com.follower.follower.Gaze; // public:cut company-extras,gaze
import com.follower.follower.Moments; // public:cut moments
import com.follower.follower.Noticing; // public:cut company-extras,noticing
import com.follower.follower.PropFlourish;
import com.follower.follower.SkillingController; // public:cut skilling
import com.follower.follower.Will; // public:cut company-extras,will
import com.follower.sim.Harness;
import com.follower.speech.FollowerOverlay;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import com.follower.ui.FilmDialog; // public:cut posing
import com.google.gson.Gson;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
		/** The last pose put on, or 0. */
		int pose;
		final List<Integer> played = new ArrayList<>();
		/** Each one-shot played on its own, as the seat's reactions play theirs. */
		final List<Integer> once = new ArrayList<>();

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
		public boolean goodGround(WorldPoint tile)
		{
			return true;
		}

		@Override
		public boolean furnished(WorldPoint tile)
		{
			return false;
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
			pose = id;
		}

		@Override
		public void playAnimation(int id)
		{
			once.add(id);
		}

		/** No client to load an animation from, so no props to put in the hands. */
		@Override
		public int[] handItems(int animationId)
		{
			return null;
		}

		@Override
		public void playAnimations(int[] ids)
		{
			for (int id : ids)
			{
				played.add(id);
			}
		}

		/** Whether a cutscene holds it now, and whether one ever has. */
		boolean watching;
		boolean everWatched;

		@Override
		public void setWatchingWithThePlayer(boolean watching)
		{
			this.watching = watching;
			everWatched |= watching;
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

		@Override
		public void facePlayer()
		{
		}
	}

	private Harness h;
	private FollowerPlugin plugin;
	private Feet feet;
	/** Whether the NPC chat box is up, for the minute-taking. */
	private boolean npcDialogUp;
	/** Whether the logout tab is the side panel on show. */
	private boolean logoutUp;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		h = new Harness(folder.newFolder().toPath());
		feet = new Feet();
		plugin = new FollowerPlugin();
		Client game = h.game.client;
		Widget box = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, method, args) -> nothing(method.getReturnType()));
		// Loaded once at login and only shown or hidden after, as the game does.
		Widget logoutPanel = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, method, args) -> method.getName().equals("isHidden")
				? !logoutUp : nothing(method.getReturnType()));
		set("client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getWidget") && args != null && args.length == 2)
				{
					if ((Integer) args[0] == InterfaceID.LOGOUT)
					{
						return logoutPanel;
					}
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
		IdleControllers.fill(plugin);
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
		for (String gate : new String[]{
			"seatFeetTaken", // public:cut skilling
			"momentFeetTaken",
			"errandBusy", // public:cut errands
			"spectateBusy", // public:cut spectate
		})
		{
			assertFalse(gate + " with nothing going on", (Boolean) call(gate));
		}
		call("applyFollowEnabled", false);
		for (String gate : new String[]{
			"seatFeetTaken", // public:cut skilling
			"errandBusy", // public:cut errands
		})
		{
			assertTrue(gate + " on a park the player gave", (Boolean) call(gate));
		}
		// The moments and the spectator are handed the park apart, since
		// the wave and the fight's announcements do not move the feet.
		for (String gate : new String[]{"momentFeetTaken", "spectateBusy"}) // public:cut spectate
		// public:else for (String gate : new String[]{"momentFeetTaken"})
		{
			assertFalse(gate + " is not the park", (Boolean) call(gate));
		}
	}

	// public:cut moments
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
	// public:end moments

	/**
	 * Play test, 29 Sep: opening the logout tab never waved. The panel is
	 * loaded once, at login, and a tab press only shows it, so the load the
	 * wave waited for never came with the press. It is watched instead, as
	 * the panel coming into view.
	 */
	// public:cut moments
	@Test
	public void openingTheLogoutTabWavesEachTimeItComesIntoView() throws ReflectiveOperationException
	{
		moments();
		call("noticeTheLogoutTab");
		assertTrue("nothing while another tab is up", feet.played.isEmpty());
		logoutUp = true;
		call("noticeTheLogoutTab");
		// Behaviour QA5: not as it opens, which is also the way to the World
		// Switcher; a tick on with it still up.
		assertTrue("not as it opens", feet.played.isEmpty());
		call("noticeTheLogoutTab");
		assertFalse("the tab stayed open: a wave", feet.played.isEmpty());
		int waved = feet.played.size();
		call("noticeTheLogoutTab");
		assertEquals("still open is not opened again", waved, feet.played.size());
	}

	@Test
	public void aLogoutTabAlreadyUpAtLoginIsNotAGoodbye() throws ReflectiveOperationException
	{
		moments();
		logoutUp = true;
		call("noticeTheLogoutTab");
		assertTrue("the session is starting, not ending", feet.played.isEmpty());
	}
	// public:end moments

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

	// public:cut moments
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
	// public:end moments

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

	// public:cut company-extras,will
	@Test
	public void aSendThatPausedShortOfAMonsterStillGoesOnOnceFree()
		throws ReflectiveOperationException
	{
		// Sent beside something that outclasses the player, the follower
		// stops short of it first, and the park was recorded on that edge.
		// Let go by what had the feet, the pause was dropped as a Follow,
		// and the follower was parked at the edge for good, three tiles
		// short of where it was sent.
		set("will", new Will(h.game.client, feet, event -> { }, () -> true));
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.moveNpc(giant, 3232, 3218, 0);
		WorldPoint sentTo = new WorldPoint(3231, 3218, 0);
		feet.stayHere();
		set("emoteHold", true);
		h.gameTick();
		call("sendTo", sentTo);
		assertEquals("stopped short first", new WorldPoint(3228, 3218, 0), feet.stay);

		set("emoteHold", false);
		feet.resumeFollowing();
		for (int i = 0; i < 12; i++)
		{
			h.gameTick();
			((Will) get("will")).tick();
			call("keepThePlayersPark");
		}
		assertEquals("gone on to where it was sent", sentTo, feet.stay);
		assertTrue(parked());
	}

	// public:end company-extras,will
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

	// public:cut moments
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
	// public:end moments

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
	// public:cut dev

	// ---------------------------------------------------------- the force-paths

	@Test
	public void aForcedShoulderIsTakenAndLetGoAtThePlayersFirstStep() throws ReflectiveOperationException
	{
		// No dialog up at all: the minutes take the shoulder as for one that
		// had just opened, and let it go as they would at its close.
		String reply = (String) call("forceShoulder");
		assertNotEquals("taken to the shoulder: " + reply, 0, get("minutesState"));
		WorldPoint shoulder = feet.stay;
		for (int i = 0; i < 5; i++)
		{
			call("updateMinutes");
		}
		assertEquals("held while the player stands there", shoulder, feet.stay);
		assertEquals(0, feet.released);

		playerSteps();
		for (int i = 0; i < 3; i++)
		{
			call("updateMinutes");
		}
		assertEquals("let go a beat after the step, as after a page flip", 0, get("minutesState"));
		assertEquals(1, feet.released);
	}

	@Test
	public void aForcedShoulderEndsAfterItsTime() throws ReflectiveOperationException
	{
		call("forceShoulder");
		for (int i = 0; i < FollowerPlugin.SHOULDER_FORCE_TICKS - 5; i++)
		{
			call("updateMinutes");
		}
		assertNotEquals("still at the shoulder", 0, get("minutesState"));
		for (int i = 0; i < 8; i++)
		{
			call("updateMinutes");
		}
		assertEquals("the pretend meeting is over", 0, get("minutesState"));
		assertEquals(1, feet.released);
	}

	@Test
	public void aForcedShoulderLeavesThePlayersParkAlone() throws ReflectiveOperationException
	{
		call("applyFollowEnabled", false);
		WorldPoint post = feet.stay;
		assertEquals("The follower is staying put.", call("forceShoulder"));
		assertEquals(0, get("minutesState"));
		assertEquals(post, feet.stay);
		assertEquals("and nothing is left pretending", 0, get("shoulderForcedTicks"));
	}

	@Test
	public void aForcedCutsceneHoldsTheFeetAsARealOneDoes() throws ReflectiveOperationException
	{
		assertEquals("Holding the cutscene: it keeps its place and looks where you look.",
			call("forceNow", (Object) new String[]{"force", "cutscene", "on"}));
		assertTrue((Boolean) call("inCutscene"));
		standStill(100);
		call("updateWander");
		assertFalse("no drift while it is held", (Boolean) get("wandered"));

		call("forceNow", (Object) new String[]{"force", "cutscene", "off"});
		assertFalse((Boolean) call("inCutscene"));
		call("updateWander");
		assertTrue("and the drift once it is let go", (Boolean) get("wandered"));
	}

	@Test
	public void noForceMovesTheBodyInAFight() throws ReflectiveOperationException
	{
		h.engine.getContext().noteDamageDealt();
		for (String what : new String[]{
			"look", // public:cut company-extras,gaze
			"notice", // public:cut company-extras,noticing
			"shoulder",
			"wave",
			"drop",
			"sketch", // public:cut chronicle
		})
		{
			assertEquals(what, "Not during a fight.", call("forceNow", (Object) new String[]{"force", what}));
		}
		assertEquals("No force called 'fly'. Try: "
			+ "look, " // public:cut company-extras,gaze
			+ "notice, " // public:cut company-extras,noticing
			+ "hesitate, " // public:cut company-extras,will
			+ "shoulder, cutscene, wave, drop"
			+ ", sketch", // public:cut chronicle
			// public:else ,
			call("forceNow", (Object) new String[]{"force", "fly"}));
	}

	@Test
	public void aForcedCutsceneEndsWithDeveloperCommands() throws ReflectiveOperationException
	{
		// With them unticked, ::follower force cutscene off is refused, and
		// the hold outlived every scene after until the plugin was toggled.
		call("forceNow", (Object) new String[]{"force", "cutscene", "on"});
		assertTrue((Boolean) call("inCutscene"));

		ConfigChanged changed = new ConfigChanged();
		changed.setGroup(FollowerConfig.GROUP);
		changed.setKey("developerMode");
		plugin.onConfigChanged(changed);
		assertFalse("let go with the commands that set it", (Boolean) call("inCutscene"));
	}

	// public:end dev
	// ------------------------------------------------------------ a cutscene

	@Test
	public void aCutsceneKeepsTheFollowerUntilThreeCleanTicks() throws ReflectiveOperationException
	{
		scene(true);
		assertTrue((Boolean) call("inCutscene"));
		assertTrue("held as an extra", feet.watching);

		// A beat between two shots is not the end of the scene.
		scene(false);
		scene(false);
		assertTrue((Boolean) call("inCutscene"));
		assertEquals(0, feet.released);

		scene(false);
		assertFalse((Boolean) call("inCutscene"));
		assertEquals("let go once, on the third clean tick", 1, feet.released);
		assertFalse(feet.watching);
	}

	@Test
	public void aPlayersParkIsLeftToThePlayer() throws ReflectiveOperationException
	{
		call("applyFollowEnabled", false);
		WorldPoint post = feet.stay;
		scene(true);
		for (int i = 0; i < 3; i++)
		{
			scene(false);
		}
		assertFalse("the post is the player's, not the scene's", feet.everWatched);
		assertEquals(0, feet.released);
		assertEquals(post, feet.stay);
	}

	/**
	 * Play test, 9 Oct, Children of the Sun: the follower walked to the
	 * player's shoulder and back for each of the scene's dozen lines, since
	 * a pause between two of them ends a meeting.
	 */
	@Test
	public void aSceneDialogueSendsNobodyToTheShoulder() throws ReflectiveOperationException
	{
		scene(true);
		npcDialogUp = true;
		call("updateMinutes");
		assertEquals(0, get("minutesState"));
		assertEquals("not walked anywhere", 0, feet.sent);
	}

	@Test
	public void nothingOfItsOwnTakesTheFeetInAScene() throws ReflectiveOperationException
	{
		String[] gates = {
			"errandBusy", // public:cut errands
			"momentFeetTaken",
			"spectateBusy", // public:cut spectate
			"seatFeetTaken", // public:cut skilling
		};
		scene(true);
		for (String gate : gates)
		{
			assertTrue(gate + " in a scene", (Boolean) call(gate));
		}
		for (int i = 0; i < 3; i++)
		{
			scene(false);
		}
		for (String gate : new String[]{
			"errandBusy", // public:cut errands
			"spectateBusy", // public:cut spectate
			"seatFeetTaken", // public:cut skilling
		})
		{
			assertFalse(gate + " once it is over", (Boolean) call(gate));
		}
		// Play test, 13 Oct: no moment begun in the quiet after the scene.
		for (int i = 0; i < FollowerPlugin.SCENE_QUIET_TICKS; i++)
		{
			assertTrue("momentFeetTaken in the quiet", (Boolean) call("momentFeetTaken"));
			h.gameTick();
		}
		assertFalse("momentFeetTaken once the quiet is over", (Boolean) call("momentFeetTaken"));
	}

	@Test
	public void theSceneStartsQuietAndDoesNotWanderStraightAfter() throws ReflectiveOperationException
	{
		set("speakingUntilMs", Long.MAX_VALUE);
		call("speak", "a line from before", SpeechOutput.OVERHEAD, null, -1, null);
		assertFalse("waiting its turn", ((java.util.Collection<?>) get("speechQueue")).isEmpty());

		standStill(100);
		scene(true);
		assertTrue("what was about to be said goes", ((java.util.Collection<?>) get("speechQueue")).isEmpty());
		assertTrue("and the engine keeps quiet", engineQuiet());
		assertEquals("nothing waits behind the line that went", 0L, get("speakingUntilMs"));

		for (int i = 0; i < 3; i++)
		{
			scene(false);
		}
		int gap = (Integer) get("WANDER_MIN_GAP");
		for (int i = 1; i < gap; i++)
		{
			call("updateWander");
			assertFalse("no wander on the idle time the scene ran up", (Boolean) get("wandered"));
		}
		call("updateWander");
		assertTrue("one in its own time", (Boolean) get("wandered"));
	}

	/**
	 * Asked for after the Children of the Sun play test: nothing said for a
	 * little while once a cutscene is over. A page the scene wrote waits for
	 * the quiet, its minute starting again once it is over.
	 */
	@Test
	public void theQuietOutlastsTheSceneAndThePageWaitsForIt() throws ReflectiveOperationException
	{
		// A tick between each clean read, so the quiet counts from the third.
		scene(true);
		for (int i = 0; i < 3; i++)
		{
			h.gameTick();
			scene(false);
		}
		// public:cut chronicle
		java.util.Deque<FollowerPlugin.PageNews> news = new java.util.ArrayDeque<>();
		com.follower.speech.TriggerEvent page =
			com.follower.speech.TriggerEvent.pageWritten("quest", "Children of the Sun");
		news.add(new FollowerPlugin.PageNews(page, h.game.client.getTickCount()));
		// public:end chronicle
		for (int i = 0; i < FollowerPlugin.SCENE_QUIET_TICKS; i++)
		{
			assertTrue("still the quiet", (Boolean) call("sceneQuiet"));
			call("noticeTheScene");
			assertTrue(engineQuiet());
			// public:cut chronicle
			assertNull("the page waits", FollowerPlugin.nextPageNews(news, h.game.client.getTickCount(), true,
				(Boolean) call("sceneQuiet"), FollowerPlugin.PAGE_NEWS_TICKS, 0));
			// public:end chronicle
			h.gameTick();
		}
		assertFalse((Boolean) call("sceneQuiet"));
		call("noticeTheScene");
		assertFalse("the engine may speak again", engineQuiet());
		// public:cut chronicle
		assertEquals("and the page is mentioned", page, FollowerPlugin.nextPageNews(news,
			h.game.client.getTickCount(), true, (Boolean) call("sceneQuiet"), FollowerPlugin.PAGE_NEWS_TICKS, 0));
		// public:end chronicle
	}

	/**
	 * The scene is read on the game tick, after that tick's messages: a line
	 * fired alongside the varbit's rise is held by the varbit itself.
	 */
	@Test
	public void theSceneIsQuietFromTheVarbit() throws ReflectiveOperationException
	{
		set("ruleLoader", h.loader);
		VarbitChanged risen = new VarbitChanged();
		risen.setVarbitId(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS);
		risen.setValue(1);
		plugin.onVarbitChanged(risen);
		assertTrue(engineQuiet());
	}
	// public:cut dev

	// public:cut moments
	@Test
	public void eachForceReachesItsOwnFeature() throws ReflectiveOperationException
	{
		// ForceCommandsTest holds each name to a case; this holds each case
		// to its feature, by a reply or a move only that feature makes.
		Moments moments = moments();
		FollowerConfig config = new FollowerConfig()
		{
		};
		// public:cut company-extras
		// public:cut gaze
		set("gaze", new Gaze(h.game.client, feet, config, () -> 0, () -> 0, () -> false, () -> "",
			Collections::emptyList, new Random(1)));
		// public:end gaze
		// public:cut noticing
		set("noticing", new Noticing(h.game.client, feet, config, moments, event -> { }, () -> false,
			() -> 0, Collections::emptyList));
		// public:end noticing
		// public:end company-extras
		// public:cut chronicle
		set("sketching", new SketchController(h.game.client, feet, (ErrandController.Hands) get("hands"),
			new ChronicleLoader(new Gson()), new SketchBook(null), null, null, () -> false, () -> 0,
			() -> 9999, () -> feet.at, () -> -1, line -> { }, line -> { }, line -> { }));
		// public:end chronicle
		set("will", new Will(h.game.client, feet, event -> { }, () -> true)); // public:cut company-extras,will
		h.gameTick();

		// public:cut company-extras
		assertEquals("A look the way the camera is turned.", force("look", "camera")); // public:cut gaze
		assertEquals("Nothing to notice in view.", force("notice")); // public:cut noticing
		// public:end company-extras
		assertEquals("No page sketches this place (region 9999).", force("sketch")); // public:cut chronicle

		// Straight after a real wave, which the tab's own next one waits out.
		assertTrue(moments.farewell());
		feet.played.clear();
		assertEquals("A wave, as at the logout tab.", force("wave"));
		assertFalse(feet.played.isEmpty());

		// public:cut company-extras,will
		// The will's note shows at the next Send: stopped short of a goblin,
		// which is nothing to the player.
		NPC goblin = h.game.spawnNpc(3029, "Goblin", 2);
		h.game.moveNpc(goblin, 3232, 3218, 0);
		force("hesitate");
		call("sendTo", new WorldPoint(3231, 3218, 0));
		assertEquals("stopped short first", new WorldPoint(3228, 3218, 0), feet.stay);
		// public:end company-extras,will
	}

	@Test
	public void aForcedDropIsItsRungOfTheLadderAndTheLootsWords() throws ReflectiveOperationException
	{
		moments();
		h.gameTick();
		assertEquals("Coins worth 5000 at your feet: too small for the ladder, so nothing.",
			force("drop", "5000"));
		assertEquals("Coins worth 20000 at your feet: a glance.", force("drop", "20000"));
		// No scroll to take out here, so the notes are the nod on its own.
		assertEquals("Coins worth 200000 at your feet: a nod.", force("drop", "200000"));
		assertEquals("Dragon warhammer worth 2000000 at your feet: the walk over to write it up.",
			force("drop", "2000000", "Dragon", "warhammer"));
		assertEquals("and the loot's words, with the item named", "the Dragon warhammer that dropped",
			h.engine.getContext().getIncidentPhrase());
	}
	// public:end moments
	// public:end dev

	// public:cut posing
	// ---------------------------------------------------------------- the film

	@Test
	public void filmingParksTheFollowerAndTheEndHandsItBack() throws ReflectiveOperationException
	{
		film();
		assertNull("the scene starts", call("ensureFilming"));
		assertTrue((Boolean) get("filming"));
		assertEquals("stopped where it stood", feet.at, feet.stay);
		assertTrue("as the player's park, which everything reads", parked());

		call("endFilming");
		assertFalse((Boolean) get("filming"));
		assertFalse("following again", feet.isStaying());
		assertFalse(parked());
	}

	@Test
	public void aParkThePlayerGaveBeforeFilmingOutlastsTheScene() throws ReflectiveOperationException
	{
		film();
		WorldPoint sentTo = new WorldPoint(3230, 3220, 0);
		call("sendTo", sentTo);
		call("ensureFilming");
		call("endFilming");
		assertEquals("still where it was sent", sentTo, feet.stay);
		assertEquals(0, feet.released);
		assertTrue(parked());
	}

	@Test
	public void aSendWhileFilmingGoesStraightThereAndIsTheFilms() throws ReflectiveOperationException
	{
		// No beat at the edge for a monster: the scene has a director.
		film();
		set("will", new Will(h.game.client, feet, event -> { }, () -> true)); // public:cut company-extras,will
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.moveNpc(giant, 3232, 3218, 0);
		h.gameTick();
		call("ensureFilming");
		WorldPoint sentTo = new WorldPoint(3231, 3218, 0);
		call("sendTo", sentTo);
		assertEquals(sentTo, feet.stay);

		// The film parked it from a follow, so the end hands it back.
		call("endFilming");
		assertFalse(feet.isStaying());
	}

	// public:cut company-extras,will
	@Test
	public void aSendPausedAtAMonsterBeforeTheSceneDoesNotGoOnIntoIt() throws ReflectiveOperationException
	{
		// Sent beside the giant just before the window opened: the beat at
		// the edge ran out mid-take, and the follower left the film's spot
		// for the giant's.
		film();
		Will will = new Will(h.game.client, feet, event -> { }, () -> true);
		set("will", will);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.moveNpc(giant, 3232, 3218, 0);
		h.gameTick();
		call("sendTo", new WorldPoint(3231, 3218, 0));
		assertTrue("paused at the edge", will.isHesitating());

		call("ensureFilming");
		WorldPoint filmsSpot = new WorldPoint(3225, 3216, 0);
		call("sendTo", filmsSpot);
		for (int i = 0; i < 12; i++)
		{
			h.gameTick();
			will.tick();
			call("keepThePlayersPark");
		}
		assertEquals("still where the film sent it", filmsSpot, feet.stay);
	}

	// public:end company-extras,will
	@Test
	public void aFlourishUnderWayWhenTheSceneStartsIsPutAwayFirst() throws ReflectiveOperationException
	{
		// A rule's scroll, or the notes, still out as the window opened:
		// run out under the film's sit, it stood the follower up and handed
		// it back to following, and the park it came back to faced you.
		film();
		PropFlourish flourish = new PropFlourish(feet, (ErrandController.Hands) get("hands"));
		set("flourish", flourish);
		assertTrue(flourish.start(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
			ErrandController.WRITE_SCROLL_POSE, 8));
		FilmDialog.Crew crew = (FilmDialog.Crew) call("filmCrew");
		assertNull(crew.start());
		assertFalse("put away as the scene starts", flourish.isActive());
		assertNull(crew.hold(10061));
		int released = feet.released;
		for (int i = 0; i < 12; i++)
		{
			flourish.tick();
			call("keepThePlayersPark");
		}
		assertEquals("still sitting", 10061, feet.pose);
		assertEquals("never handed back to following", released, feet.released);
		assertTrue(feet.isStaying());
	}

	// public:cut skilling
	@Test
	public void theSlayerLedgerUnderWayWhenTheSceneStartsIsPutAwayFirst() throws ReflectiveOperationException
	{
		film();
		SkillingController skilling = skilling();
		h.gameTick();
		skilling.noteChatMessage("You've completed 26 tasks; return to a Slayer master.",
			ChatMessageType.GAMEMESSAGE);
		for (int i = 0; i < 3; i++)
		{
			h.gameTick();
			skilling.tick();
		}
		assertEquals("the entry under way", ErrandController.WRITE_SCROLL_POSE, feet.pose);

		FilmDialog.Crew crew = (FilmDialog.Crew) call("filmCrew");
		assertNull(crew.start());
		assertNull(crew.hold(10061));
		int released = feet.released;
		for (int i = 0; i < 12; i++)
		{
			h.gameTick();
			skilling.tick();
			call("keepThePlayersPark");
		}
		assertEquals("still sitting", 10061, feet.pose);
		assertEquals("never handed back to following", released, feet.released);
	}

	@Test
	public void aLedgerEntryStillDueWhenTheSceneStartsIsNotMade() throws ReflectiveOperationException
	{
		// The applause was played and the scroll was a beat away.
		film();
		SkillingController skilling = skilling();
		h.gameTick();
		skilling.noteChatMessage("You've completed 26 tasks; return to a Slayer master.",
			ChatMessageType.GAMEMESSAGE);
		h.gameTick();
		skilling.tick();
		assertNull(((FilmDialog.Crew) call("filmCrew")).start());
		for (int i = 0; i < 12; i++)
		{
			h.gameTick();
			skilling.tick();
			assertFalse("no scroll out in the take", skilling.holdsTheFeet());
		}
	}

	// public:end skilling
	// public:cut moments
	@Test
	public void aLevelOrAGameMessageInATakeIsNotReactedTo() throws ReflectiveOperationException
	{
		// None of these comes through a line, so the film's cut never saw
		// them: the clap for a level, the nod at a new collection log item,
		// the slayer task's applause and the scratch of the head at no
		// runes each turned the follower and played over the film's pose.
		film();
		moments();
		skilling(); // public:cut skilling
		h.gameTick();
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 0, 10, 10));
		set("filming", true);
		levelAndMessages(11);
		assertTrue("nothing played in the take: " + feet.played + feet.once,
			feet.played.isEmpty() && feet.once.isEmpty());

		// The probe: once the scene is over, each of them is played.
		set("filming", false);
		levelAndMessages(12);
		assertEquals("the clap and the nod", Arrays.asList(865, 855), feet.played);
		assertEquals("the applause and the think", Arrays.asList(865, 857), feet.once); // public:cut skilling
	}
	// public:end moments

	@Test
	public void aSendTheFilmCannotWalkIsNotSaidOverhead() throws ReflectiveOperationException
	{
		film();
		call("ensureFilming");
		call("sendTo", (Object) null);
		assertEquals("nothing overhead to spoil the take", 0L, get("speakingUntilMs"));
	}

	@Test
	public void choosingAFacingInAWalkingShotHoldsItStill() throws ReflectiveOperationException
	{
		film();
		FilmDialog.Crew crew = (FilmDialog.Crew) call("filmCrew");
		assertNull(crew.start());
		call("followAgain");
		assertFalse(feet.isStaying());

		assertNull(crew.face(1024));
		assertTrue("stopped to face north", feet.isStaying());
		assertTrue(parked());
		call("endFilming");
		assertFalse("and the film's stop is handed back", feet.isStaying());
	}

	@Test
	public void aPressAfterTheSceneEndedStartsItAgain() throws ReflectiveOperationException
	{
		// A logout ends the scene and leaves the window open.
		film();
		FilmDialog.Crew crew = (FilmDialog.Crew) call("filmCrew");
		crew.start();
		call("endFilming");
		assertNull(crew.holdHere());
		assertTrue((Boolean) get("filming"));
		assertTrue(parked());
	}

	@Test
	public void aPressQueuedBeforeAStopFindsThePluginOff() throws ReflectiveOperationException
	{
		film();
		set("started", false);
		assertEquals("The follower is not out right now.", call("ensureFilming"));
		assertFalse((Boolean) get("filming"));
		assertFalse(feet.isStaying());
	}

	@Test
	public void nothingOfItsOwnTakesTheFeetInAWalkingShot() throws ReflectiveOperationException
	{
		// Follow me in the film window: no park, and still nothing of its own.
		set("filming", true);
		for (String gate : new String[]{
			"momentFeetTaken",
			"spectateBusy", // public:cut spectate
			"errandBusy", // public:cut errands
		})
		{
			assertTrue(gate + " while filming", (Boolean) call(gate));
		}
		standStill(100);
		call("updateWander");
		assertFalse("no drift while the player stops to type", (Boolean) get("wandered"));
		assertEquals(0, feet.sent);
	}

	@Test
	public void aRulesLineAndEmoteAreCutWhileFilmingButATypedLineIsSaid()
		throws ReflectiveOperationException
	{
		SpeechRule rule = new SpeechRule();
		assertFalse(FollowerPlugin.cutForTheFilm(false, rule));
		assertTrue("a rule's line, copy or plant", FollowerPlugin.cutForTheFilm(true, rule));
		assertFalse("the film's own line has no rule", FollowerPlugin.cutForTheFilm(true, null));

		// And the gate is where every line comes in.
		set("clientThread", clientThread());
		SpeechRule cheer = new SpeechRule();
		cheer.id = "hands-won";
		cheer.holdStill = true;
		cheer.animation = 862;
		call("speak", "", SpeechOutput.CHATBOX, cheer, 862, null);
		assertTrue("the probe reaches the plant", (Boolean) get("emoteHold"));
		set("emoteHold", false);
		set("filming", true);
		call("speak", "", SpeechOutput.CHATBOX, cheer, 862, null);
		assertFalse("nothing copied while filming", (Boolean) get("emoteHold"));
	}

	/**
	 * What no harness drives is read out of the source: the look round,
	 * noticing, the sketch, the will's step aside, the rest and the mute
	 * each stand down for the film, the reactions played where it stands
	 * are not asked for in a take, and the player's animations are kept
	 * for Copy my emote.
	 */
	@Test
	public void everythingOfItsOwnReadsTheFilm() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n"));
		String start = source.substring(source.indexOf("protected void startUp() throws Exception"));
		for (String built : new String[]{
			"gaze = new", // public:cut company-extras,gaze
			"noticing = new", // public:cut company-extras,noticing
			"sketching = new", // public:cut chronicle
			"will = new", // public:cut company-extras,will
		})
		{
			int at = start.indexOf(built);
			assertTrue("startUp no longer builds " + built + " - the lint has lost its place", at >= 0);
			String construction = start.substring(at, start.indexOf(");\n", at));
			assertTrue(built + " ... must stand down while filming", construction.contains("filming"));
		}
		String rest = source.substring(source.indexOf("private void updateRest()"));
		assertTrue("the rest must not sit it down over the film's pose",
			rest.substring(0, rest.indexOf("boolean wantRest")).contains("|| filming"));
		assertTrue("a take is muted, so a rule's line is not counted as said", source.contains(
			"speechEngine.setMuted(config.muted() || thievingNow || absentNow || thrallQuiet() || filming);"));
		// The drop's and the graphic's way in, which no test here drives:
		// each reaction is asked for only outside a take.
		for (String reaction : new String[]{"skilling.noteChatMessage(", "skilling.noteGraphic(", // public:cut skilling
		// public:else for (String reaction : new String[]{
			"moments.levelUp(", "moments.noteworthy("}) // public:cut moments
			// public:else })
		{
			int found = 0;
			for (int at = source.indexOf(reaction); at >= 0; at = source.indexOf(reaction, at + 1))
			{
				found++;
				String guard = source.substring(source.lastIndexOf("if (", at), at);
				assertTrue(reaction + " ... must not be asked for while filming", guard.contains("!filming)"));
			}
			assertTrue("no " + reaction + " - the lint has lost its place", found > 0);
		}
		String animation = source.substring(source.indexOf("public void onAnimationChanged(AnimationChanged event)"));
		assertTrue("the player's animations are kept for Copy my emote",
			animation.substring(0, animation.indexOf("\n\t}\n")).contains("filmSet.notePlayerAnimation(animationId);"));
		assertTrue("the film's pose holds its own props, so the emote disarm stands down for it",
			source.contains("&& (filmSet == null || !filmSet.isPosing());"));
		assertTrue("and the model is dressed with them",
			source.contains("dressForPose(outfit, filmHands);"));
	}

	// public:end posing
	// public:cut chronicle
	// ------------------------------------------------------- the posed sketch

	@Test
	public void aPoseHoldsEveryOtherLineTillTheScrollIsAway() throws ReflectiveOperationException, IOException
	{
		// A line said over the count would be in the picture, or talk over
		// the count. Held, as in a conversation, and said after.
		film();
		set("clientThread", clientThread());
		set("config", new FollowerConfig()
		{
			// public:cut sharevoice
			@Override
			public boolean voiceEnabled()
			{
				return false;
			}
			// public:end sharevoice
		});
		// Muted: the count was asked for, so it shows anyway.
		h.engine.setMuted(true);
		List<String> said = new ArrayList<>();
		SketchController scribe = posedSketch(said);
		assertNull(scribe.askForSketch());
		String start = shown();
		assertTrue("the count starts over its head", start != null && !start.isEmpty());

		set("speakingUntilMs", 0L);
		call("speak", "a rule's line", SpeechOutput.OVERHEAD, null, -1, null);
		call("drainSpeechQueue");
		assertEquals("held, not said over the count", start, shown());
		for (int i = 0; i < 20 && shown() != null; i++)
		{
			// The game tick's order: the sketch, then the queue, with each
			// count line's time over by then, as an emote's quick picture
			// can find it.
			scribe.tick();
			set("speakingUntilMs", 0L);
			call("drainSpeechQueue");
			assertNotEquals("nothing else said into the picture", "a rule's line", shown());
		}
		assertNull("the overhead clear for the picture", shown());

		scribe.tick();
		String there = shown();
		assertTrue("the word after the picture", there != null && !there.equals("a rule's line"));
		assertTrue("and it holds the floor as a line does",
			(Long) get("speakingUntilMs") > System.currentTimeMillis());
		set("speakingUntilMs", 0L);
		call("drainSpeechQueue");
		assertEquals("still held while the scroll is out", there, shown());

		scribe.tick();
		call("drainSpeechQueue");
		assertFalse("the scroll away", scribe.isSketching());
		assertEquals("then what waited is said", "a rule's line", shown());
	}

	@Test
	public void aPoseIsNotWalkedOutOf() throws ReflectiveOperationException, IOException
	{
		SketchController scribe = posedSketch(new ArrayList<>());
		assertNull(scribe.askForSketch());
		for (String gate : new String[]{"momentFeetTaken", "errandBusy"}) // public:cut errands
		// public:else for (String gate : new String[]{"momentFeetTaken"})
		{
			assertTrue(gate + " while posing", (Boolean) call(gate));
		}
		standStill(100);
		call("updateWander");
		assertFalse("no drift out of the picture", (Boolean) get("wandered"));
		assertEquals(0, feet.sent);
	}

	@Test
	public void aDriftAlreadyTakenIsNotLetGoOfMidPose() throws ReflectiveOperationException, IOException
	{
		// Asked for where it had drifted to: the pose shut the wander's
		// window, and the emote reset the stand-still count, so the drift
		// was let go of and the scribe walked back to you, scroll out.
		drift();
		WorldPoint drifted = feet.stay;
		SketchController scribe = posedSketch(new ArrayList<>());
		assertNull(scribe.askForSketch());
		h.game.animating(866);
		h.gameTick();
		assertEquals("the emote resets the count", 0, h.engine.getContext().getIdleTicks());
		call("updateWander");
		assertEquals("still where it drifted to", drifted, feet.stay);
		assertEquals("never handed back to following", 0, feet.released);

		// The scroll away, the drift is let go of as it would have been.
		for (int i = 0; i < 20 && scribe.isSketching(); i++)
		{
			scribe.tick();
		}
		assertFalse(scribe.isSketching());
		call("updateWander");
		assertEquals(1, feet.released);
		assertFalse((Boolean) get("wandered"));
	}

	@Test
	public void theWordAfterThePoseIsSaidNextAheadOfWhatWaited() throws ReflectiveOperationException, IOException
	{
		// "There." still has the floor when the word after comes. Queued
		// behind the lines held through the pose, it came after some
		// unrelated remark, seconds late, or not at all with three waiting.
		film();
		set("clientThread", clientThread());
		set("config", new FollowerConfig()
		{
			// public:cut sharevoice
			@Override
			public boolean voiceEnabled()
			{
				return false;
			}
			// public:end sharevoice
		});
		speakThroughThePlugin();
		List<String> said = new ArrayList<>();
		SketchController scribe = posedSketch(said);
		assertNull(scribe.askForSketch());
		call("speak", "a rule's line", SpeechOutput.OVERHEAD, null, -1, null);
		for (int i = 0; i < 20 && shown() != null; i++)
		{
			scribe.tick();
			set("speakingUntilMs", 0L);
			call("drainSpeechQueue");
		}
		assertNull("taken", shown());

		scribe.tick();
		String there = shown();
		// Put away while it still has the floor, as it always does.
		scribe.tick();
		assertFalse(scribe.isSketching());
		assertEquals("the word after", 1, said.size());
		call("drainSpeechQueue");
		assertEquals(there, shown());
		set("speakingUntilMs", 0L);
		call("drainSpeechQueue");
		assertEquals("the word after, straight after it", said.get(0), shown());
		set("speakingUntilMs", 0L);
		call("drainSpeechQueue");
		assertEquals("then what waited", "a rule's line", shown());
	}

	@Test
	public void aFidgetIsNotPlayedOverTheScroll() throws ReflectiveOperationException, IOException
	{
		// Setting up the camera for the picture is standing still, long
		// enough for the silent fidgets, which no mute or hold stops: one
		// played over the scroll, and could be in the picture.
		set("clientThread", onTheClientThread());
		SpeechRule think = h.rule("idle-fidget-think");
		call("speak", "", SpeechOutput.OVERHEAD, think, 857, null);
		assertEquals("the probe: played", Collections.singletonList(857), feet.once);
		feet.once.clear();

		SketchController scribe = posedSketch(new ArrayList<>());
		assertNull(scribe.askForSketch());
		call("speak", "", SpeechOutput.OVERHEAD, think, 857, null);
		assertTrue("nothing played over the scroll", feet.once.isEmpty());
	}

	@Test
	public void askingAgainMidCountSaysNothing() throws ReflectiveOperationException, IOException
	{
		// Only ::follower sketch can, as the menu hides Sketch meanwhile.
		// Its refusal was held through the count and said after the
		// picture, as if the sketch just taken had been refused.
		SketchController scribe = posedSketch(new ArrayList<>());
		call("askForSketch");
		assertTrue(scribe.isPosing());
		call("askForSketch");
		assertTrue("no refusal waiting", ((java.util.Deque<?>) get("speechQueue")).isEmpty());
	}

	// public:cut posing
	@Test
	public void aSceneStartingPutsAPoseAwayWithoutAWord() throws ReflectiveOperationException, IOException
	{
		film();
		List<String> said = new ArrayList<>();
		SketchController scribe = posedSketch(said);
		assertNull(scribe.askForSketch());
		assertNull(((FilmDialog.Crew) call("filmCrew")).start());
		assertFalse("put away as the scene starts", scribe.isSketching());
		for (int i = 0; i < 12; i++)
		{
			scribe.tick();
			assertNull("nothing over the take", shown());
		}
		assertTrue("nor said after it", said.isEmpty());
	}

	// public:end posing
	/**
	 * What no harness drives is read out of the source: the menu offers
	 * Sketch only where a plate is and never in a take, the command asks on
	 * the client thread, an emote while sketching is the pose and not
	 * answered, and the look round and noticing stand aside for it.
	 */
	@Test
	public void theSketchIsAskedForAndStoodAsideFor() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n"));
		int entry = source.indexOf("addFollowerMenuEntry(\"Sketch\", this::askForSketch);");
		assertTrue("the Sketch entry has moved - the scan broke, not the glue", entry >= 0);
		String offered = source.substring(source.lastIndexOf("if (", entry), entry);
		assertTrue("offered only where asking would start one", offered.contains("sketching.canSketchNow()"));
		assertTrue("and never in a take", offered.contains("!filming")); // public:cut posing
		assertTrue("::follower sketch asks on the client thread",
			source.contains("case \"sketch\":\n\t\t\t\tclientThread.invoke(this::askForSketch);"));

		String animation = source.substring(source.indexOf("public void onAnimationChanged(AnimationChanged event)"));
		animation = animation.substring(0, animation.indexOf("\n\t}\n"));
		int skip = animation.indexOf("if (sketching != null && sketching.isSketching())\n\t\t{\n\t\t\treturn;");
		assertTrue("an emote while sketching is not answered or copied",
			skip >= 0 && skip < animation.indexOf("dispatch(TriggerEvent.animation(animationId));"));
		// public:cut posing
		assertTrue("but the film still keeps it for Copy my emote",
			animation.indexOf("filmSet.notePlayerAnimation(animationId);") < skip);
		// public:end posing

		String start = source.substring(source.indexOf("protected void startUp() throws Exception"));
		// public:cut company-extras
		for (String built : new String[]{
			"gaze = new", // public:cut gaze
			"noticing = new", // public:cut noticing
		})
		{
			int at = start.indexOf(built);
			assertTrue("startUp no longer builds " + built + " - the lint has lost its place", at >= 0);
			String construction = start.substring(at, start.indexOf(");\n", at));
			assertTrue(built + " ... must stand aside for a sketch", construction.contains("sketching.isSketching()"));
		}
		// public:end company-extras
		int built = start.indexOf("sketching = new");
		String sketching = start.substring(built, start.indexOf(");\n", built));
		assertTrue("the done line and the invitation hold to the mute", sketching.contains("speechEngine::remark"));
		assertTrue("the count is shown whatever the mute", sketching.contains("this::sketchCount"));
	}

	// public:end chronicle
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
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
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

	/**
	 * Play test, 29 Sep: mid-fight the right-click offered only Follow. The
	 * choice read any stay, and the spectator standing clear to watch holds
	 * one, as the seat and the wander do, so the player could not give a Stay
	 * over any of them. It reads the player's own park.
	 */
	@Test
	public void theMenuOffersStayUnlessThePlayerParkedIt() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		int follow = source.indexOf("addFollowerMenuEntry(\"Follow-me\"");
		assertTrue("the Follow entry has moved - the scan broke, not the glue", follow >= 0);
		int branch = source.lastIndexOf("if (", follow);
		assertEquals("if (parkedByPlayer)", source.substring(branch, source.indexOf(')', branch) + 1));
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

	/**
	 * The film's own pieces on the bare plugin. Its rebuild and its graphics // public:cut posing
	 // public:else * The bare plugin, its overlay set and started.
	// public:cut posing
	 * go nowhere, since these tests are about who holds the feet; FilmPropsTest
	 * builds it with the plugin's own rebuild, as startUp does.
	// public:end posing
	 */
	private void film() throws ReflectiveOperationException
	{
		set("overlay", new FollowerOverlay(null, null, null, null));
		set("filmSet", new FilmSet(feet)); // public:cut posing
		set("started", true);
	}

	// public:cut chronicle
	/**
	 * A posed sketch in Lumbridge on these feet, its count shown and its word
	 * after said the plugin's own way. What it says is recorded in {@code said}.
	 */
	private SketchController posedSketch(List<String> said) throws ReflectiveOperationException, IOException
	{
		boolean[] holding = {false};
		ErrandController.Hands hands = new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				holding[0] = true;
			}

			@Override
			public void release()
			{
				holding[0] = false;
			}

			@Override
			public boolean isHolding()
			{
				return holding[0];
			}
		};
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		if (get("overlay") == null)
		{
			set("overlay", new FollowerOverlay(null, null, null, null));
		}
		SketchController sketching = new SketchController(h.game.client, feet, hands, loader, new SketchBook(null),
			null, null, () -> false, () -> 0, () -> 12850, () -> feet.at, () -> -1, said::add,
			line ->
			{
				said.add(line);
				try
				{
					call("sketchSaysNext", line);
				}
				catch (ReflectiveOperationException e)
				{
					throw new AssertionError(e);
				}
			},
			line ->
			{
				try
				{
					call("sketchCount", line);
				}
				catch (ReflectiveOperationException e)
				{
					throw new AssertionError(e);
				}
			});
		set("sketching", sketching);
		return sketching;
	}

	// public:end chronicle
	/** What is over the follower's head now, or null. */
	private String shown() throws ReflectiveOperationException
	{
		Field message = FollowerOverlay.class.getDeclaredField("message");
		message.setAccessible(true);
		return (String) message.get(get("overlay"));
	}

	// public:cut skilling
	/** The seat on these feet and the plugin's hands, with nothing else taking them. */
	private SkillingController skilling() throws ReflectiveOperationException
	{
		SkillingController skilling = new SkillingController(h.game.client, feet, new FollowerConfig()
		{
		}, (ErrandController.Hands) get("hands"), event -> { }, () -> false, () -> false, () -> false);
		set("skilling", skilling);
		return skilling;
	}

	// public:end skilling
	/** A level up to this, a new collection log item, a slayer task done and no runes. */
	private void levelAndMessages(int level)
	{
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 0, level, level));
		plugin.onChatMessage(gameMessage("New item added to your collection log: Bronze med helm"));
		plugin.onChatMessage(gameMessage("You've completed 26 tasks; return to a Slayer master."));
		plugin.onChatMessage(gameMessage("You do not have enough Fire Runes to cast this spell."));
	}

	private static ChatMessage gameMessage(String text)
	{
		ChatMessage message = new ChatMessage();
		message.setType(ChatMessageType.GAMEMESSAGE);
		message.setName("");
		message.setMessage(text);
		return message;
	}

	/** One tick's reading of the cutscene, through the real varbit. */
	private void scene(boolean on) throws ReflectiveOperationException
	{
		if (get("overlay") == null)
		{
			set("overlay", new FollowerOverlay(null, null, null, null));
		}
		h.game.varbit(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS, on ? 1 : 0);
		call("noticeTheScene");
	}

	/** Whether the engine has been told to keep quiet for a scene. */
	private boolean engineQuiet() throws ReflectiveOperationException
	{
		Field quiet = com.follower.speech.SpeechEngine.class.getDeclaredField("sceneQuiet");
		quiet.setAccessible(true);
		return (Boolean) quiet.get(h.engine);
	}

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

	// public:cut moments
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
		// public:cut skilling
		FollowerConfig config = new FollowerConfig()
		{
		};
		SkillingController skilling = new SkillingController(h.game.client, feet, config, hands,
			event -> { }, () -> false, () -> false, () -> false);
		// public:end skilling
		Moments moments = new Moments(h.game.client, feet, skilling, // public:cut skilling
		// public:else Moments moments = new Moments(h.game.client, feet,
			() -> gate("momentFeetTaken"), () -> false, () -> 30, null, () -> false, // public:cut company-extras,gestures
			// public:else () -> gate("momentFeetTaken"), () -> false, () -> 30, null,
			() -> field("parkedByPlayer"));
		set("moments", moments);
		return moments;
	}
	// public:end moments

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

	/** A client thread that runs what it is given at once, as on the client thread itself. */
	private static Object onTheClientThread() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("isClientThread") ? Boolean.TRUE : null);
		net.runelite.client.callback.ClientThread thread = new net.runelite.client.callback.ClientThread();
		Field field = thread.getClass().getDeclaredField("client");
		field.setAccessible(true);
		field.set(thread, client);
		return thread;
	}

	/** The engine's lines into the plugin's own queue, as startUp wires them. */
	private void speakThroughThePlugin()
	{
		h.engine.setSink((text, output, rule, animationId, onSaid) ->
		{
			try
			{
				call("speak", text, output, rule, animationId, onSaid);
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		});
	}
	// public:cut dev

	/** ::follower force with these words after it, as the chat command hands them over. */
	private String force(String... words) throws ReflectiveOperationException
	{
		String[] args = new String[words.length + 1];
		args[0] = "force";
		System.arraycopy(words, 0, args, 1, words.length);
		return (String) call("forceNow", (Object) args);
	}
	// public:end dev

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
