package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.follower.appearance.SpotAnimRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The home teleport's graphics on the follower, one at a time as on the
 * player: each stage's graphic takes the place of the last.
 *
 * <p>Cache measurements behind it: the book the follower reads from in the
 * home teleport is part of the graphics, not of anything held - the third
 * stage's 802 (model 18537, the book alone) and the fourth's 803 (18538, the
 * same 58 book faces and a glow). 803's own animation, 4856, keeps the book
 * opaque on every frame and runs 250 client ticks, 50 more than the fourth
 * stage's clip (4855), which the server cuts short besides. The fifth stage's
 * 804 (18540) is the flash and has no book, and its clip, 4857, is the one
 * that fades the body out (alpha on 57 labels, to fully clear by frame 12).
 * Each graphic left to run out kept 803's book in the hands while the body
 * faded around it.
 */
public class TeleportStageGraphicTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int[] STAGES = {4847, 4850, 4853, 4855, 4857};
	private static final int CIRCLE = 800;
	private static final int BOOK = 802;
	private static final int BOOK_AND_GLOW = 803;
	private static final int FLASH = 804;

	/** Every graphic shown, in the order shown: the follower itself is not made this way. */
	private final List<RuneLiteObject> made = new ArrayList<>();
	private final Set<Object> registered = new HashSet<>();

	private FollowerEntity follower;
	private SpotAnimRepository graphics;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(SpotAnimRepository.FILE_NAME), ("{\"version\": 1, \"spotanims\": {"
			+ "\"800\": {\"m\": 18539, \"a\": 4848, \"rx\": 97},"
			+ "\"802\": {\"m\": 18537, \"a\": 4854},"
			+ "\"803\": {\"m\": 18538, \"a\": 4856, \"am\": 50, \"co\": 20},"
			+ "\"804\": {\"m\": 18540, \"a\": 4858, \"am\": 50, \"co\": 20}}}")
			.getBytes(StandardCharsets.UTF_8));
		graphics = new SpotAnimRepository(new Gson());
		graphics.load(dir);

		Client[] client = new Client[1];
		client[0] = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadAnimation":
						return animation((Integer) args[0]);
					case "loadModelData":
						return modelData();
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

	/** A mesh whose every clone and transform is itself, and which lights to nothing. */
	private static ModelData modelData()
	{
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
			new Class<?>[]{ModelData.class}, (proxy, method, args) ->
			{
				Class<?> type = method.getReturnType();
				return type.isInstance(proxy) ? proxy : zero(type);
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

	private void cast()
	{
		follower.startSlavedChain(STAGES, new SpotAnimRepository.Entry[]{
			graphics.get(CIRCLE), null, graphics.get(BOOK), graphics.get(BOOK_AND_GLOW), graphics.get(FLASH)});
	}

	/** The graphics on show, by the order they were made in. */
	private List<Integer> showing()
	{
		List<Integer> on = new ArrayList<>();
		for (int i = 0; i < made.size(); i++)
		{
			if (made.get(i).isActive())
			{
				on.add(i);
			}
		}
		return on;
	}

	@Test
	public void theFlashTakesTheBooksPlaceAsTheBodyStartsToFade()
	{
		cast();
		follower.advanceSlavedChain();
		follower.advanceSlavedChain();
		follower.advanceSlavedChain();
		assertEquals("the circle, the book, the book and its glow", 3, made.size());
		RuneLiteObject book = made.get(2);
		assertTrue("the book is out in the fourth stage", book.isActive());

		follower.advanceSlavedChain();

		assertEquals(4, made.size());
		assertFalse("803's book goes as the fifth stage starts, as on the player", book.isActive());
		assertEquals("the flash alone", Arrays.asList(3), showing());
	}

	@Test
	public void eachStagesGraphicTakesTheLastOnesPlace()
	{
		cast();
		assertEquals("the circle", Arrays.asList(0), showing());

		follower.advanceSlavedChain();
		follower.advanceSlavedChain();
		assertEquals("the book in the circle's place", Arrays.asList(1), showing());
	}

	@Test
	public void aStageWithNoGraphicOfItsOwnLeavesTheLastOneShowing()
	{
		cast();
		follower.advanceSlavedChain();

		assertEquals("nothing new was sent, so the circle stays drawn", Arrays.asList(0), showing());
	}

	@Test
	public void theCastsEndTakesTheFlashWithIt()
	{
		cast();
		for (int i = 1; i < STAGES.length; i++)
		{
			follower.advanceSlavedChain();
		}
		assertEquals(Arrays.asList(3), showing());

		follower.slavedPlayerStopped();

		assertFalse(follower.isSlavedChainActive());
		assertEquals("nothing left behind at the departure", Arrays.asList(), showing());
	}

	@Test
	public void graphicsOutsideTheChainAreNotTheChainsToReplace()
	{
		follower.playSpotAnim(graphics.get(CIRCLE));
		cast();

		// A graphic the chain did not put on is not a stage's to replace.
		assertEquals(Arrays.asList(0, 1), showing());
	}
}
