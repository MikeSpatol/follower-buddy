package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.kit.KitType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A model the client has never drawn is fetched from the game's servers, and
 * the first ask for it gets nothing. Public QA, 27 Oct, on a fresh install: a
 * new shirt and legs left the follower without a torso or legs for a while,
 * because the look built without them was kept as if it were whole and
 * nothing asked for them again. The service is asked here through a client
 * whose models land when the test says so.
 */
public class ModelDownloadTest
{
	private static final int HAT = 101;
	private static final int SHIRT = 102;
	private static final int LEGS = 103;
	private static final int HAT_MODEL = 5001;
	private static final int SHIRT_MODEL = 5002;
	private static final int LEGS_MODEL = 5003;

	/** The models the client has; every other one is still downloading. */
	private final Set<Integer> landed = new HashSet<>();

	/** How many times a model was asked for, landed or not. */
	private int asks;

	/** What the follower was given to draw, in order. */
	private final List<String> drawn = new ArrayList<>();

	private AppearanceService service;

	@Before
	public void setUp()
	{
		ModelRepository repository = new ModelRepository(new Gson());
		Map<String, ModelRepository.Entry> items = new LinkedHashMap<>();
		items.put(String.valueOf(HAT), worn(HAT_MODEL, KitType.HEAD));
		items.put(String.valueOf(SHIRT), worn(SHIRT_MODEL, KitType.TORSO));
		items.put(String.valueOf(LEGS), worn(LEGS_MODEL, KitType.LEGS));
		repository.addLive(items, new LinkedHashMap<>());

		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadModelData":
						asks++;
						return landed.contains(args[0]) ? model(ModelData.class, String.valueOf(args[0])) : null;
					case "mergeModels":
						return model(ModelData.class, Arrays.toString((Object[]) args[0]));
					default:
						return null;
				}
			});
		service = new AppearanceService(new AppearanceComposer(client, repository));
	}

	private static ModelRepository.Entry worn(int model, KitType slot)
	{
		ModelRepository.Entry entry = new ModelRepository.Entry();
		entry.m = new int[]{model, -1, -1};
		entry.wp1 = slot.getIndex();
		return entry;
	}

	/** A model that names its parts when asked, lights into one that does, and copies to itself. */
	private static <T> T model(Class<T> type, String parts)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "toString":
						return parts;
					case "light":
						return model(Model.class, parts);
					default:
						return method.getReturnType().isInstance(proxy) ? proxy : null;
				}
			}));
	}

	private static Outfit dressed()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.HEAD, HAT);
		outfit.setItem(KitType.TORSO, SHIRT);
		outfit.setItem(KitType.LEGS, LEGS);
		return outfit;
	}

	/** A rebuild, as the plugin asks for one. */
	private void rebuild(Outfit outfit)
	{
		service.request(outfit, appearance -> drawn.add(appearance.getModel().toString()));
	}

	@Test
	public void aShirtAndLegsStillDownloadingAreOnTheFollowerOnceTheyLand()
	{
		landed.add(HAT_MODEL);
		rebuild(dressed());
		assertEquals("drawn with what there was", "[" + HAT_MODEL + "]", drawn.get(0));
		assertFalse("and nothing has landed yet", service.partsArrived());

		landed.add(SHIRT_MODEL);
		landed.add(LEGS_MODEL);
		assertTrue("the tick sees them land", service.partsArrived());
		rebuild(dressed());
		assertEquals("and the same look built again has them",
			"[" + HAT_MODEL + ", " + SHIRT_MODEL + ", " + LEGS_MODEL + "]", drawn.get(1));

		int before = asks;
		assertFalse("nothing is left to watch for", service.partsArrived());
		rebuild(dressed());
		assertEquals("and the whole look is reused as before", 3, drawn.size());
		assertEquals("without asking the client for anything", before, asks);
	}

	@Test
	public void aModelThatNeverLandsIsGivenUpOnAfterAMinute()
	{
		landed.add(HAT_MODEL);
		landed.add(LEGS_MODEL);
		rebuild(dressed());
		for (int tick = 0; tick < AppearanceService.WAIT_TICKS; tick++)
		{
			assertFalse(service.partsArrived());
		}

		int before = asks;
		assertFalse("a minute on, it stops watching", service.partsArrived());
		assertEquals("and stops asking", before, asks);

		// An emote's end, say: the same look built again does not start the wait over.
		rebuild(dressed());
		before = asks;
		assertFalse(service.partsArrived());
		assertEquals(before, asks);
		assertEquals("each build had what there was", 2, drawn.size());
	}

	@Test
	public void anotherLookIsWatchedForAfresh()
	{
		landed.add(HAT_MODEL);
		rebuild(dressed());
		for (int tick = 0; tick <= AppearanceService.WAIT_TICKS; tick++)
		{
			service.partsArrived();
		}

		Outfit bareHeaded = dressed();
		bareHeaded.clear(KitType.HEAD);
		rebuild(bareHeaded);
		landed.add(SHIRT_MODEL);
		assertTrue("a new look is a new wait", service.partsArrived());

		// So is an outfit edit or a login, which clear the cache, of the same look.
		service.invalidate();
		rebuild(dressed());
		for (int tick = 0; tick <= AppearanceService.WAIT_TICKS; tick++)
		{
			service.partsArrived();
		}
		service.invalidate();
		rebuild(dressed());
		landed.add(LEGS_MODEL);
		assertTrue(service.partsArrived());
	}

	@Test
	public void aLookWithNothingLandedYetWaitsRatherThanFailing()
	{
		// Every part new: the build has nothing to draw. It is not a failed
		// build, which would say so and take the follower away.
		rebuild(dressed());
		assertEquals("the follower is left as it was", 0, drawn.size());

		landed.add(SHIRT_MODEL);
		assertTrue(service.partsArrived());
		rebuild(dressed());
		assertEquals("[" + SHIRT_MODEL + "]", drawn.get(0));
	}

	/**
	 * The tick's part is glue no harness drives, so it is read out of the
	 * source, as ParkRememberedTest reads the spawns.
	 */
	@Test
	public void theTickWatchesWhileTheFollowerIsShownAndBeforeItsRespawn() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8);
		String tick = source.substring(source.indexOf("if (rebuildQueued)"));
		int hide = tick.indexOf("if (shouldHide())");
		int watch = tick.indexOf("else if (appearanceService.partsArrived())");
		assertTrue("not while it is hidden, which would only take it away again",
			hide >= 0 && hide < watch);
		assertTrue("and ahead of the bare respawn, which would bring back the unfinished look",
			watch < tick.indexOf("spawnFollower(appearanceService.getCurrent(), local, false);"));
	}
}
