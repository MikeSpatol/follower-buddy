package com.follower.follower;

import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import com.follower.sim.FakeGame;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * When the follower's body is in the scene and when it is not (QA, 16 Oct):
 * what a despawn cut off does not follow it into the next body, and a
 * sketch's frame is drawn without it.
 */
public class OutOfSightTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int IDLE = 808;
	private static final int WAVE = 863;
	private static final int HOME = 4847;

	private FakeGame game;
	/** What the client has in its scene: a RuneLiteObject is active while it is in here. */
	private final Set<Object> inScene = new HashSet<>();
	private final List<Boolean> emoteStates = new ArrayList<>();
	private FollowerEntity follower;

	@Before
	public void setUp() throws IOException
	{
		game = new FakeGame().at(50, 50, 0).animation(IDLE, 0).animation(WAVE, -1).animation(HOME, -1);
		Client[] client = new Client[1];
		client[0] = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "createRuneLiteObject":
						// Placed nowhere: there are no tile heights to stand it on.
						return new RuneLiteObject(client[0])
						{
							@Override
							public void setLocation(LocalPoint point, int plane)
							{
							}
						};
					case "registerRuneLiteObject":
						inScene.add(args[0]);
						return null;
					case "removeRuneLiteObject":
						inScene.remove(args[0]);
						return null;
					case "isRuneLiteObjectRegistered":
						return inScene.contains(args[0]);
					default:
						Object answer = forward(game.client, method, args);
						return answer instanceof WorldView ? flat((WorldView) answer) : answer;
				}
			});
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(client[0], stances);
		follower.setOnEmoteStateChanged(emoteStates::add);
	}

	private static Object forward(Object to, java.lang.reflect.Method method, Object[] args) throws Throwable
	{
		try
		{
			return method.invoke(to, args);
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	private static final byte[][][] FLAT_SETTINGS = new byte[4][104][104];
	private static final int[][][] FLAT_HEIGHTS = new int[4][105][105];

	/** The game's view on level ground, so the follower can be stood in it. */
	private static WorldView flat(WorldView view)
	{
		byte[][][] settings = FLAT_SETTINGS;
		int[][][] heights = FLAT_HEIGHTS;
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
			new Class<?>[]{WorldView.class}, (proxy, method, args) ->
				method.getName().equals("getTileSettings") ? settings
					: method.getName().equals("getTileHeights") ? heights
					: forward(view, method, args));
	}

	/** Spawned beside the player as the plugin does it, with no spawn animation. */
	private void spawn()
	{
		spawnAt(new WorldPoint(49, 50, 0));
	}

	private void spawnAt(WorldPoint at)
	{
		Model model = (Model) Proxy.newProxyInstance(Model.class.getClassLoader(),
			new Class<?>[]{Model.class}, (proxy, method, args) ->
			{
				Class<?> type = method.getReturnType();
				return type == boolean.class ? false : type == int.class ? 0 : null;
			});
		follower.setSpawnAnimation(new int[0]);
		follower.spawn(new ComposedAppearance(model, ComposedAppearance.Source.DUMP, new Outfit()),
			at, null, 0);
	}

	private RuneLiteObject body() throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField("object");
		field.setAccessible(true);
		return (RuneLiteObject) field.get(follower);
	}

	@Test
	public void aFollowerOutOfSightIsNotShown() throws ReflectiveOperationException
	{
		spawn();
		follower.updateFrame();
		assertTrue("in the scene", follower.isShown());

		// A home teleport that ran to its end: the follower goes with it.
		follower.startSlavedChain(new int[]{HOME}, null);
		follower.vanishAfterEmote();
		follower.slavedPlayerStopped();
		follower.updateFrame();
		assertFalse("gone through the teleport", follower.isShown());

		follower.despawn();
		assertFalse(follower.isShown());
	}

	@Test
	public void anEmoteCutOffByADespawnDoesNotHoldTheNextBody() throws ReflectiveOperationException
	{
		spawn();
		follower.updateFrame();
		follower.playAnimation(WAVE);
		assertTrue(follower.isEmotePlaying());
		emoteStates.clear();

		// A logout, a hop or a hide mid-wave, and back with no spawn animation.
		follower.despawn();
		spawn();
		assertFalse("the wave went with the old body", follower.isEmotePlaying());
		assertEquals("and the plugin was told, so the weapon comes back",
			java.util.Collections.singletonList(false), emoteStates);

		follower.updateFrame();
		AnimationController playing = body().getAnimationController();
		assertNotNull("the new body is given its idle on its first frame", playing);
		assertTrue("and is in the scene", follower.isShown());
	}

	@Test
	public void aTeleportsVanishDoesNotOutliveADespawn()
	{
		spawn();
		follower.updateFrame();
		follower.startSlavedChain(new int[]{HOME}, null);
		follower.vanishAfterEmote();
		follower.slavedPlayerStopped();
		follower.despawn();

		spawn();
		follower.updateFrame();
		assertTrue("the new body is not waiting on the old one's landing", follower.isShown());
	}

	@Test
	public void aSketchsFrameIsDrawnWithoutTheScribe()
	{
		// Not on the west tile: that is where a recovery snap would put it,
		// and a snap there would pass for standing still.
		spawnAt(new WorldPoint(51, 50, 0));
		follower.updateFrame();
		follower.stayHere();
		follower.updateFrame();
		assertTrue(follower.isShown());
		WorldPoint post = follower.getWorldLocation();
		WorldPoint stay = follower.getStayTile();

		follower.setOutOfPicture(true);
		follower.updateFrame();
		assertFalse("out of the frame the sketch is taken from", follower.isShown());

		follower.setOutOfPicture(false);
		follower.updateFrame();
		assertTrue("back for the next", follower.isShown());
		assertEquals("where it stood", post, follower.getWorldLocation());
		assertEquals("still told to stay", stay, follower.getStayTile());
	}
}
