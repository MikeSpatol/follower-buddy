package com.follower;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Public QA, 27 Oct, on a fresh install: the head in the Talk-to box was
 * there sometimes and not others. A dialogue-head model the client has never
 * drawn is fetched from the game's servers, and the first ask for it gets
 * nothing; the head was built without it and kept that way for the whole
 * node. The box is driven here through a client whose models land when the
 * test says so.
 */
public class ChatheadDownloadTest
{
	private static final int HELM = 201;
	private static final int HELM_HEAD = 6001;
	private static final int BEARD = 6002;

	/** The models the client has; every other one is still downloading. */
	private final Set<Integer> landed = new HashSet<>();

	/** Every model asked for, landed or not. */
	private final List<Integer> asked = new ArrayList<>();

	/** How many parts the last head was merged from. */
	private int merged;

	private FollowerDialog dialog;
	private Method draw;

	@Before
	public void setUp() throws Exception
	{
		ModelRepository repository = new ModelRepository(new Gson());
		ModelRepository.Entry helm = new ModelRepository.Entry();
		helm.m = new int[]{5001, -1, -1};
		helm.hm = new int[]{HELM_HEAD, BEARD};
		helm.wp1 = KitType.HEAD.getIndex();
		Map<String, ModelRepository.Entry> items = new LinkedHashMap<>();
		items.put(String.valueOf(HELM), helm);
		Method live = ModelRepository.class.getDeclaredMethod("addLive", Map.class, Map.class);
		live.setAccessible(true);
		live.invoke(repository, items, new LinkedHashMap<>());

		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadModelData":
						asked.add((Integer) args[0]);
						return landed.contains(args[0]) ? parts() : null;
					case "mergeModels":
						merged = ((Object[]) args[0]).length;
						return parts();
					default:
						Class<?> type = method.getReturnType();
						return type == boolean.class ? false : type == int.class ? 0 : null;
				}
			});

		dialog = new FollowerDialog(client, new FollowerEntity(null, null), null, null, new ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
				r.run();
			}
		}, new AppearanceComposer(client, repository), null, null);
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.HEAD, HELM);
		dialog.setFollowerOutfit(() -> outfit);
		set("node", FollowerDialog.Node.says("Hello."));
		draw = FollowerDialog.class.getDeclaredMethod("animatedHead", int.class);
		draw.setAccessible(true);
	}

	/** Model data that copies to itself and lights into a head. */
	private static ModelData parts()
	{
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(), new Class<?>[]{ModelData.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("light"))
				{
					return ChatheadFrameCacheTest.head();
				}
				return method.getReturnType().isInstance(proxy) ? proxy : null;
			});
	}

	/** A node opening: goTo builds the speaker's head. */
	private void openNode() throws ReflectiveOperationException
	{
		Method refresh = FollowerDialog.class.getDeclaredMethod("refreshHead");
		refresh.setAccessible(true);
		refresh.invoke(dialog);
	}

	/** One frame of the box. */
	private BufferedImage frame() throws ReflectiveOperationException
	{
		return (BufferedImage) draw.invoke(dialog, 1);
	}

	@Test
	public void aHeadStillDownloadingIsThereTheFrameItLands() throws Exception
	{
		openNode();
		assertNull("nothing to draw yet", frame());

		landed.add(HELM_HEAD);
		landed.add(BEARD);
		assertNotNull("the next frame has the head, on the same node", frame());
		assertEquals("all of it", 2, merged);
	}

	@Test
	public void aHeadLandingPartByPartIsDrawnOnlyWhole() throws Exception
	{
		landed.add(BEARD);
		openNode();
		assertNull("not a beard on its own", frame());

		landed.add(HELM_HEAD);
		assertNotNull(frame());
		assertEquals(2, merged);
	}

	@Test
	public void aPartThatNeverLandsIsGoneWithoutAfterTheWait() throws Exception
	{
		landed.add(BEARD);
		openNode();
		assertNull(frame());

		set("headHeldUntil", 1L);
		BufferedImage partial = frame();
		assertNotNull("past the wait, what there is", partial);
		assertEquals(1, merged);

		landed.add(HELM_HEAD);
		assertNotSame("and the rest once it lands after all", partial, frame());
		assertEquals(2, merged);
	}

	@Test
	public void nothingIsAskedForOnceTheHeadIsWhole() throws Exception
	{
		landed.add(HELM_HEAD);
		landed.add(BEARD);
		openNode();
		assertNotNull(frame());

		int before = asked.size();
		for (int i = 0; i < 20; i++)
		{
			frame();
		}
		assertEquals(before, asked.size());
	}

	@Test
	public void theHeadsAreAskedForBeforeTheBoxNeedsThem() throws Exception
	{
		dialog.fetchHeads();
		assertTrue("the follower's head models are on their way",
			asked.contains(HELM_HEAD) && asked.contains(BEARD));

		// The glue no harness drives is read out of the source.
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8);
		String talking = source.substring(source.indexOf("void startTalking()"));
		int fetch = talking.indexOf("dialog.fetchHeads();");
		assertTrue("a tick before Talk-to opens the box",
			fetch >= 0 && fetch < talking.indexOf("dialog.startNextTick("));
		String build = source.substring(source.indexOf("appearanceService.request(outfit, appearance ->"));
		int built = build.indexOf("dialog.fetchHeads();");
		assertTrue("and with each look the follower is given",
			built >= 0 && built < build.indexOf("follower.setAppearance(appearance);"));
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerDialog.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(dialog, value);
	}
}
