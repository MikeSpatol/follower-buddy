package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The teleport's hold on the follower (QA, 15 Oct): it stays planted for the
 * whole cast, whatever else lets go of it meanwhile, and only a cast that
 * reached its last stage takes the follower with it.
 */
public class TeleportHoldTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int[] HOME = {4847, 4850, 4853, 4855, 4857};
	private static final WorldPoint HERE = new WorldPoint(3222, 3218, 0);

	private FollowerEntity follower;
	private PropFlourish flourish;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
				method.getName().equals("loadAnimation") ? animation((Integer) args[0])
					: zero(method.getReturnType()));
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(client, stances);
		set("object", new RuneLiteObject(client));
		set("animatable", true);
		set("spawned", true);
		set("tile", HERE);
		flourish = new PropFlourish(follower, new ErrandController.Hands()
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

	private static Animation animation(int id)
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "getFrameLengths":
						int[] lengths = new int[10];
						Arrays.fill(lengths, 5);
						return lengths;
					case "getFrameStep":
						return -1;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return zero(method.getReturnType());
				}
			});
	}

	private static Object zero(Class<?> type)
	{
		if (type == boolean.class)
		{
			return false;
		}
		if (type == int.class)
		{
			return 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		return null;
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(follower);
	}

	private boolean vanished() throws ReflectiveOperationException
	{
		return (Boolean) get("vanished");
	}

	/** What mirror-home-teleport does: plant, start the slaved chain, arm the vanish. */
	private void homeTeleport()
	{
		follower.stayHere();
		follower.startSlavedChain(HOME, null);
		follower.vanishAfterEmote();
	}

	/** The player steps on through this many stages. */
	private void toStage(int stages)
	{
		for (int i = 0; i < stages; i++)
		{
			follower.advanceSlavedChain();
		}
	}

	@Test
	public void aHomeTeleportCutShortKeepsTheFollower() throws Exception
	{
		homeTeleport();
		toStage(1);
		// The player clicks away mid-cast: their animation goes to -1.
		follower.slavedPlayerStopped();
		assertFalse("cut short, it stays", vanished());
		assertFalse(follower.isSlavedChainActive());
		assertFalse(follower.isEmotePlaying());
	}

	@Test
	public void aCutShortCastArmsNoLaterVanish() throws Exception
	{
		homeTeleport();
		toStage(2);
		follower.slavedPlayerStopped();
		// The next emote ending must not take the disarmed vanish with it.
		follower.playAnimation(863);
		follower.cancelEmote();
		assertFalse(vanished());
	}

	@Test
	public void aCompletedHomeTeleportVanishes() throws Exception
	{
		homeTeleport();
		toStage(HOME.length - 1);
		follower.slavedPlayerStopped();
		assertTrue("the last stage done, it leaves with the player", vanished());
		assertFalse(follower.isSlavedChainActive());
	}

	@Test
	public void aCosmeticWithMoreStagesHoldsTheLastOneUntilThePlayerLeaves() throws Exception
	{
		// Leagues 6: eight player stages to the follower's five.
		homeTeleport();
		toStage(7);
		assertFalse("still with the player on their last stage", vanished());
		assertTrue(follower.isSlavedChainActive());
		follower.slavedPlayerStopped();
		assertTrue("and gone with them", vanished());
	}

	@Test
	public void aStageThatRunsOutHoldsItsFinalFrame() throws Exception
	{
		homeTeleport();
		toStage(HOME.length - 1);
		AnimationController clip = ((RuneLiteObject) get("object")).getAnimationController();
		// Ten frames of five ticks, run well past their end.
		clip.tick(70);
		assertEquals("held on its last frame, not started again", 9, clip.getFrame());
		assertTrue("and still playing it", clip.getAnimation() != null);
	}

	@Test
	public void aFlourishEndingMidCastDoesNotLetGo() throws Exception
	{
		assertTrue(flourish.start(0, ErrandController.SCROLL_PROP, ErrandController.WRITE_SCROLL_POSE, 2));
		homeTeleport();
		toStage(1);
		flourish.tick();
		flourish.tick();
		assertFalse("the flourish is over", flourish.isActive());
		assertEquals("still planted for the cast", HERE, get("stayTile"));
		assertTrue("so the cast plays on", follower.isSlavedChainActive());

		// The release it asked for comes with the cast's end.
		toStage(HOME.length - 2);
		assertEquals(HERE, get("stayTile"));
		follower.slavedPlayerStopped();
		assertTrue(vanished());
		assertNull("let go once the cast is over", get("stayTile"));
	}

	@Test
	public void aReleaseMidCastIsCarriedOutWhenTheCastIsCutShort() throws Exception
	{
		homeTeleport();
		toStage(2);
		// A work session or an errand ending mid-cast.
		follower.resumeFollowing();
		assertEquals(HERE, get("stayTile"));
		follower.slavedPlayerStopped();
		assertFalse(vanished());
		assertNull("cut short, it follows again", get("stayTile"));
	}

	@Test
	public void aOneShotTeleportHoldsTooAndReleasesAsItVanishes() throws Exception
	{
		// mirror-teleport: a one-shot cast with the vanish armed.
		follower.stayHere();
		follower.playAnimation(714);
		follower.vanishAfterEmote();
		follower.resumeFollowing();
		assertEquals("planted while the cast plays", HERE, get("stayTile"));
		follower.cancelEmote();
		assertNull("the cast cut short lets go", get("stayTile"));
		assertFalse(vanished());
	}

	@Test
	public void eachClipThatGoesOnIsReportedForItsHands()
	{
		java.util.List<Integer> clips = new java.util.ArrayList<>();
		follower.setOnEmoteClip(clips::add);
		homeTeleport();
		toStage(1);
		assertEquals("the chalk stick's stage, then the next", Arrays.asList(4847, 4850), clips);
		follower.cancelEmote();
		follower.playAnimation(3864);
		assertEquals(Integer.valueOf(3864), clips.get(clips.size() - 1));
	}

	@Test
	public void aCastThatNeverStartedHoldsNothingBack() throws Exception
	{
		set("animatable", false);
		follower.stayHere();
		follower.playAnimation(714);
		follower.vanishAfterEmote();
		follower.resumeFollowing();
		assertNull(get("stayTile"));
	}

	@Test
	public void aReleaseOutsideACastIsImmediate() throws Exception
	{
		follower.stayHere();
		follower.resumeFollowing();
		assertNull(get("stayTile"));
	}
}
