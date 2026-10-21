package com.follower.follower;

import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import com.follower.sim.FakeGame;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.AABB;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
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
 * The follower out of sight because someone stands on its tile: nothing of
 * it is left behind (no menu where it stood, no graphic hanging in the
 * air), a turn on the spot is still standing there, and someone the client
 * does not draw (Entity Hider) is not someone to give way to.
 */
public class SharedTileHideTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final byte[][][] FLAT_SETTINGS = new byte[4][104][104];
	private static final int[][][] FLAT_HEIGHTS = new int[4][105][105];

	private FakeGame game;
	private final Set<Object> inScene = new HashSet<>();
	/** What the clickbox code asked of the model, besides its bounding box. */
	private final List<String> asked = new ArrayList<>();
	private Client client;
	private FollowerEntity follower;

	@Before
	public void setUp() throws IOException
	{
		game = new FakeGame().at(50, 50, 0).animation(808, 0);
		Client[] proxy = new Client[1];
		proxy[0] = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (self, method, args) ->
			{
				switch (method.getName())
				{
					case "createRuneLiteObject":
						return new RuneLiteObject(proxy[0])
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
					// A software camera south of the follower, looking north and down.
					case "isGpu":
						return false;
					case "getCameraX":
						return 49 * 128 + 64;
					case "getCameraY":
						return 40 * 128;
					case "getCameraZ":
						return -1200;
					case "getCameraPitch":
						return 256;
					case "getCameraYaw":
						return 0;
					case "getViewportWidth":
						return 800;
					case "getViewportHeight":
						return 600;
					case "getViewportXOffset":
					case "getViewportYOffset":
						return 0;
					case "getScale":
						return 512;
					default:
						Object answer = forward(game.client, method, args);
						return answer instanceof WorldView ? flat((WorldView) answer) : answer;
				}
			});
		client = proxy[0];
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(client, stances);

		AABB box = (AABB) Proxy.newProxyInstance(AABB.class.getClassLoader(), new Class<?>[]{AABB.class},
			(self, method, args) ->
			{
				switch (method.getName())
				{
					case "getCenterY":
						return -100;
					case "getExtremeY":
						return 100;
					case "getExtremeX":
					case "getExtremeZ":
						return 50;
					default:
						return 0;
				}
			});
		Model model = (Model) Proxy.newProxyInstance(Model.class.getClassLoader(),
			new Class<?>[]{Model.class}, (self, method, args) ->
			{
				if (method.getName().equals("getAABB"))
				{
					return box;
				}
				asked.add(method.getName());
				// The box is the clickbox, so a hit needs no faces to show it.
				if (method.getName().equals("useBoundingBox"))
				{
					return true;
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? false : type == int.class ? 0 : null;
			});
		follower.setSpawnAnimation(new int[0]);
		follower.spawn(new ComposedAppearance(model, ComposedAppearance.Source.DUMP, new Outfit()),
			new WorldPoint(49, 50, 0), null, 0);
		follower.updateFrame();
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

	private static WorldView flat(WorldView view)
	{
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
			new Class<?>[]{WorldView.class}, (proxy, method, args) ->
				method.getName().equals("getTileSettings") ? FLAT_SETTINGS
					: method.getName().equals("getTileHeights") ? FLAT_HEIGHTS
					: forward(view, method, args));
	}

	@SuppressWarnings("unchecked")
	private java.util.Map<String, Object> answersOf(net.runelite.api.Player other) throws ReflectiveOperationException
	{
		java.lang.reflect.Field f = FakeGame.class.getDeclaredField("otherAnswers");
		f.setAccessible(true);
		return ((java.util.Map<net.runelite.api.Player, java.util.Map<String, Object>>) f.get(game)).get(other);
	}

	private Point middle;

	@Before
	public void aim()
	{
		LocalPoint at = new LocalPoint(49 * 128 + 64, 50 * 128 + 64, client.getTopLevelWorldView());
		middle = Perspective.localToCanvas(client, at, 0, 100);
		assertTrue("shown first", follower.isShown());
		assertTrue("and under the mouse", follower.isUnderMouse(middle));
	}

	/** Another player stops on the follower's tile. */
	private net.runelite.api.Player standsOnIt() throws ReflectiveOperationException
	{
		net.runelite.api.Player other = game.spawnPlayer(-1, 0);
		answersOf(other).put("getIdlePoseAnimation", 808);
		answersOf(other).put("getIdleRotateLeft", 823);
		answersOf(other).put("getIdleRotateRight", 822);
		answersOf(other).put("getPoseAnimation", 808);
		follower.updateFrame();
		return other;
	}

	@Test
	public void hiddenForAPlayerItTakesNoMouse() throws ReflectiveOperationException
	{
		standsOnIt();
		assertFalse(follower.isShown());
		assertFalse("no menu, no click where it stood", follower.isUnderMouse(middle));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void itsGraphicsHideWithItAndComeBackWithIt() throws ReflectiveOperationException
	{
		RuneLiteObject graphic = client.createRuneLiteObject();
		graphic.setActive(true);
		java.lang.reflect.Field f = FollowerEntity.class.getDeclaredField("activeGraphics");
		f.setAccessible(true);
		((List<RuneLiteObject>) f.get(follower)).add(graphic);

		standsOnIt();
		assertFalse("hidden with the body", graphic.isActive());
		game.clearPlayers();
		follower.updateFrame();
		assertTrue(follower.isShown());
		assertTrue("back with it", graphic.isActive());
	}

	@Test
	public void aPlayerTurningOnTheSpotIsStillStandingThere() throws ReflectiveOperationException
	{
		net.runelite.api.Player other = standsOnIt();
		StringBuilder shown = new StringBuilder();
		for (int pose : new int[]{808, 823, 823, 808, 822, 808})
		{
			answersOf(other).put("getPoseAnimation", pose);
			follower.updateFrame();
			shown.append(pose).append(follower.isShown() ? ":SHOWN " : ":hidden ");
		}
		assertEquals("808:hidden 823:hidden 823:hidden 808:hidden 822:hidden 808:hidden ",
			shown.toString());
	}

	@Test
	public void anNpcTurningOnTheSpotIsStillStandingThere() throws ReflectiveOperationException
	{
		net.runelite.api.NPC npc = game.spawnNpc(3106, "Man", 2);
		java.lang.reflect.Field f = FakeGame.class.getDeclaredField("npcAnswers");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> answers =
			((java.util.Map<net.runelite.api.NPC, java.util.Map<String, Object>>) f.get(game)).get(npc);
		answers.put("getIdlePoseAnimation", 808);
		answers.put("getIdleRotateLeft", 823);
		answers.put("getIdleRotateRight", 823);
		answers.put("getPoseAnimation", 823);
		java.lang.reflect.Method standing = FollowerEntity.class.getDeclaredMethod("standing", net.runelite.api.Actor.class);
		standing.setAccessible(true);
		assertTrue((Boolean) standing.invoke(null, npc));
		answers.put("getPoseAnimation", 819);
		assertFalse("walking is not", (Boolean) standing.invoke(null, npc));
	}

	@Test
	public void underSomebodyIdleOnItsTileALongWhileItStepsAside() throws ReflectiveOperationException
	{
		standsOnIt();
		java.lang.reflect.Field last = FollowerEntity.class.getDeclaredField("lastFrameMs");
		last.setAccessible(true);
		for (int i = 0; i < 40; i++)
		{
			last.set(follower, System.currentTimeMillis() - 600);
			follower.updateFrame();
		}
		assertTrue("out of sight a while, then a step aside", follower.isShown());
		assertEquals(1, follower.getWorldLocation().distanceTo(new WorldPoint(49, 50, 0)));
	}

	@Test
	public void aPlayerTheClientDoesNotDrawIsNotGivenWay() throws ReflectiveOperationException
	{
		follower.setDrawn(actor -> !(actor instanceof net.runelite.api.Player));
		standsOnIt();
		assertTrue("Entity Hider hides them: nobody there to give way to", follower.isShown());
	}
}
