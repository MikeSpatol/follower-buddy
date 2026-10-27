package com.follower;

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
 * Whether the mouse is on the follower is asked fifty times a second, and
 * the full clickbox (every face projected, merged and sorted) was most of
 * the plugin's CPU (perf audit, Oct 2026, #16). The model's bounding box,
 * projected first, answers "no" for the mouse anywhere else, and the
 * clickbox is built only for a point inside it.
 */
public class ClickboxGateTest
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
		follower.spawn(new ComposedAppearance(model, new Outfit()),
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

	@Test
	public void theClickboxIsBuiltOnlyForAPointOnTheFollowersBox()
	{
		// Where the follower is on screen: its middle, half way up the box.
		LocalPoint at = new LocalPoint(49 * 128 + 64, 50 * 128 + 64, client.getTopLevelWorldView());
		Point middle = Perspective.localToCanvas(client, at, 0, 100);
		assertNotNull("the camera sees the follower", middle);

		asked.clear();
		assertFalse("the mouse elsewhere", follower.isUnderMouse(new Point(middle.getX() + 300, middle.getY())));
		assertFalse(follower.isUnderMouse(new Point(5, 5)));
		assertEquals("and nothing but the box was asked", new ArrayList<>(), asked);

		assertTrue("the mouse on it", follower.isUnderMouse(middle));
		assertTrue("which is the only time the clickbox is built", asked.contains("useBoundingBox"));
	}
}
