package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Public QA, 27 Oct: mid home teleport, the chalk circle round the
 * follower's feet was drawn wholly over its legs, back half and all. The
 * game sorts a player's spotanim into the player's own model face by face;
 * the follower's graphics are objects of their own, and same-tile objects
 * draw in the order they joined the scene. A graphic's faces on the ground
 * go under the follower and the rest over it.
 */
public class GraphicLayeringTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final SpotAnimRepository.Entry GRAPHIC = new SpotAnimRepository.Entry();

	static
	{
		GRAPHIC.m = 18539;
		GRAPHIC.a = 4848;
	}

	/** The height of each face of the graphic's model, up being negative. */
	private float[] faceHeights;

	/** Every graphic object made, in order, and the face colours it was lit with. */
	private final List<RuneLiteObject> made = new ArrayList<>();
	private final List<int[]> lit = new ArrayList<>();

	/** What is in the scene, in the order it joined. */
	private final List<Object> scene = new ArrayList<>();

	private RuneLiteObject body;
	private FollowerEntity follower;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Client[] client = new Client[1];
		client[0] = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadAnimation":
						return animation();
					case "loadModelData":
						return modelData();
					case "createRuneLiteObject":
						RuneLiteObject graphic = new RuneLiteObject(client[0]);
						made.add(graphic);
						return graphic;
					case "registerRuneLiteObject":
						if (!scene.contains(args[0]))
						{
							scene.add(args[0]);
						}
						return null;
					case "removeRuneLiteObject":
						scene.remove(args[0]);
						return null;
					case "isRuneLiteObjectRegistered":
						return scene.contains(args[0]);
					default:
						return zero(method.getReturnType());
				}
			});
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(client[0], stances);
		body = new RuneLiteObject(client[0]);
		body.setActive(true);
		set("object", body);
		set("animatable", true);
		set("lastRenderedLocation", new LocalPoint(6400, 6400, -1));
	}

	private static Animation animation()
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getFrameLengths":
						int[] lengths = new int[10];
						Arrays.fill(lengths, 5);
						return lengths;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return zero(method.getReturnType());
				}
			});
	}

	/** One face per height, whose every clone and transform is itself. */
	private ModelData modelData()
	{
		int faces = faceHeights.length;
		float[] ys = new float[faces * 3];
		int[][] corners = new int[3][faces];
		for (int i = 0; i < faces; i++)
		{
			for (int corner = 0; corner < 3; corner++)
			{
				ys[i * 3 + corner] = faceHeights[i];
				corners[corner][i] = i * 3 + corner;
			}
		}
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
			new Class<?>[]{ModelData.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getFaceCount":
						return faces;
					case "getVerticesY":
						return ys;
					case "getFaceIndices1":
						return corners[0];
					case "getFaceIndices2":
						return corners[1];
					case "getFaceIndices3":
						return corners[2];
					case "light":
						int[] colours = new int[faces];
						lit.add(colours);
						return model(colours);
					default:
						Class<?> type = method.getReturnType();
						return type.isInstance(proxy) ? proxy : zero(type);
				}
			});
	}

	private static Model model(int[] colours)
	{
		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(),
			new Class<?>[]{Model.class}, (proxy, method, args) ->
				method.getName().equals("getFaceColors3") ? colours : zero(method.getReturnType()));
	}

	/** What a proxy answers for anything it was not told about. */
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
		if (type == short.class)
		{
			return (short) 0;
		}
		if (type == float.class)
		{
			return 0f;
		}
		return null;
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	@Test
	public void aCircleOnTheGroundIsDrawnUnderTheFollower()
	{
		faceHeights = new float[]{0, -2, 0};
		follower.playSpotAnim(GRAPHIC);

		assertEquals("one object", 1, made.size());
		assertEquals("joined ahead of the follower", Arrays.asList(made.get(0), body), scene);
		assertArrayEquals("with every face of it", new int[3], lit.get(0));
	}

	@Test
	public void aSwirlRoundTheBodyIsDrawnOverIt()
	{
		faceHeights = new float[]{-40, -90, -140};
		follower.playSpotAnim(GRAPHIC, FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);

		assertEquals(1, made.size());
		assertEquals("joined after the follower", Arrays.asList(body, made.get(0)), scene);
	}

	@Test
	public void aGraphicOnTheGroundAndOffItIsSplitAroundTheFollower()
	{
		// The book and its glow over the circle.
		faceHeights = new float[]{0, -60, -1, -80};
		follower.playSpotAnim(GRAPHIC);

		assertEquals("two objects", 2, made.size());
		RuneLiteObject over = made.get(0);
		RuneLiteObject under = made.get(1);
		assertEquals(Arrays.asList(under, body, over), scene);
		assertArrayEquals("the over one skips the ground faces", new int[]{-2, 0, -2, 0}, lit.get(0));
		assertArrayEquals("and the under one the rest", new int[]{0, -2, 0, -2}, lit.get(1));
	}

	@Test
	public void aLaterGraphicKeepsTheEarlierOnesOnTheirSides()
	{
		faceHeights = new float[]{-40};
		follower.playSpotAnim(GRAPHIC);
		RuneLiteObject swirl = made.get(0);
		faceHeights = new float[]{0};
		follower.playSpotAnim(GRAPHIC);
		RuneLiteObject circle = made.get(1);

		assertEquals(Arrays.asList(circle, body, swirl), scene);
	}
}
