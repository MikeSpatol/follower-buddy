package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.follower.appearance.SpotAnimRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Public QA, 27 Oct, on a fresh install: the first home teleport played on
 * the follower with no circle and no book, and a later one had both. A
 * graphic's model or animation the client has never drawn is fetched from
 * the game's servers, and the first ask for it gets nothing; the graphic was
 * skipped and never asked for again. The follower is driven here through a
 * client whose models and animations land when the test says so.
 */
public class GraphicDownloadTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int[] STAGES = {4847, 4850, 4853};
	private static final SpotAnimRepository.Entry CIRCLE = graphic(18539, 4848);
	private static final SpotAnimRepository.Entry BOOK = graphic(18537, 4854);
	private static final SpotAnimRepository.Entry GLOW = graphic(18538, 4856);

	/** The models and animations the client has; every other one is still downloading. */
	private final Set<Integer> landed = new HashSet<>();

	/** Every model asked for, landed or not. */
	private final List<Integer> asked = new ArrayList<>();

	/** Every graphic shown, in the order shown: the follower itself is not made this way. */
	private final List<RuneLiteObject> made = new ArrayList<>();
	private final Set<Object> registered = new HashSet<>();

	private FollowerEntity follower;

	private static SpotAnimRepository.Entry graphic(int model, int animation)
	{
		SpotAnimRepository.Entry entry = new SpotAnimRepository.Entry();
		entry.m = model;
		entry.a = animation;
		return entry;
	}

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
						int id = (Integer) args[0];
						boolean clip = Arrays.stream(STAGES).anyMatch(stage -> stage == id);
						return clip || landed.contains(id) ? animation(id) : null;
					case "loadModelData":
						asked.add((Integer) args[0]);
						return landed.contains(args[0]) ? modelData() : null;
					case "createRuneLiteObject":
						RuneLiteObject graphic = new RuneLiteObject(client[0]);
						made.add(graphic);
						return graphic;
					case "registerRuneLiteObject":
						registered.add(args[0]);
						return null;
					case "removeRuneLiteObject":
						registered.remove(args[0]);
						return null;
					case "isRuneLiteObjectRegistered":
						return registered.contains(args[0]);
					default:
						return zero(method.getReturnType());
				}
			});
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(client[0], stances);
		set("object", new RuneLiteObject(client[0]));
		set("animatable", true);
		set("lastRenderedLocation", new LocalPoint(6400, 6400, -1));
	}

	/** Ten frames of five client cycles. */
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

	/** One face held well off the ground, whose every clone and transform is itself. */
	private static ModelData modelData()
	{
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
			new Class<?>[]{ModelData.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getFaceCount":
						return 1;
					case "getVerticesY":
						return new float[]{-50, -50, -50};
					case "getFaceIndices1":
						return new int[]{0};
					case "getFaceIndices2":
						return new int[]{1};
					case "getFaceIndices3":
						return new int[]{2};
					default:
						Class<?> type = method.getReturnType();
						return type.isInstance(proxy) ? proxy : zero(type);
				}
			});
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

	private void land(SpotAnimRepository.Entry graphic)
	{
		landed.add(graphic.modelId());
		landed.add(graphic.animationId());
	}

	private void cast()
	{
		follower.startSlavedChain(STAGES, new SpotAnimRepository.Entry[]{CIRCLE, BOOK, GLOW});
	}

	@Test
	public void aGraphicStillDownloadingGoesOnTheFrameItLands()
	{
		follower.playSpotAnim(CIRCLE);
		follower.placePendingGraphics();
		assertEquals("nothing to show yet", 0, made.size());

		land(CIRCLE);
		follower.placePendingGraphics();
		assertEquals("on as it lands", 1, made.size());
		assertTrue(made.get(0).isActive());

		follower.placePendingGraphics();
		assertEquals("and once", 1, made.size());
	}

	@Test
	public void aModelInWhileItsAnimationIsNotWaitsForBoth()
	{
		landed.add(CIRCLE.modelId());
		follower.playSpotAnim(CIRCLE);
		follower.placePendingGraphics();
		assertEquals(0, made.size());

		landed.add(CIRCLE.animationId());
		follower.placePendingGraphics();
		assertEquals(1, made.size());
	}

	@Test
	public void aCastAsksForEveryStagesGraphicAsItStarts()
	{
		cast();
		assertTrue("the book and its glow download while the circle is drawn",
			asked.containsAll(Arrays.asList(CIRCLE.modelId(), BOOK.modelId(), GLOW.modelId())));
	}

	@Test
	public void theFirstStagesGraphicComesOnLateInItsOwnStage()
	{
		cast();
		assertEquals(0, made.size());

		land(CIRCLE);
		follower.placePendingGraphics();
		assertEquals("the circle, while its stage plays", 1, made.size());
	}

	@Test
	public void aStagesGraphicThatLandsAfterItsStageNeverShows()
	{
		land(BOOK);
		cast();
		follower.advanceSlavedChain();
		assertEquals("the book, the second stage's", 1, made.size());

		land(CIRCLE);
		follower.placePendingGraphics();
		assertEquals("the first stage's circle is not put on over it", 1, made.size());
	}

	@Test
	public void aCancelledCastTakesItsDownloadsWithIt()
	{
		cast();
		follower.cancelEmote();

		land(CIRCLE);
		follower.placePendingGraphics();
		assertEquals(0, made.size());
	}

	@Test
	public void aLateGraphicStartsWhereItWouldHaveGotTo()
	{
		Animation animation = animation(CIRCLE.animationId());
		assertEquals(0, FollowerEntity.frameAt(animation, 0));
		assertEquals("twelve cycles in is the third frame", 2, FollowerEntity.frameAt(animation, 12));
		assertEquals("and fifty is past its end", -1, FollowerEntity.frameAt(animation, 50));
	}
}
