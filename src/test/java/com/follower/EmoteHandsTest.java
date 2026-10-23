// public:file devdump
package com.follower;

import com.follower.appearance.AppearanceService;
import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An emote's own hand items, drawn outside the film window too (QA, 15 Oct):
 * the game draws whatever an animation names for each hand, and the follower
 * played them empty-handed. Cache: 13302 names the charting quill (31829)
 * left and its scroll (31830) right; the home teleport's first stage, 4847,
 * the chalk stick (9702) right, and its second, 4850, nothing.
 *
 * <p>Reached on a bare plugin, as FilmPropsTest reaches the film's dressing.
 */
public class EmoteHandsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int WHIP = 4151;
	private static final int DRAGON_SQUARE = 1187;
	private static final int QUILL = ErrandController.QUILL_PROP;
	private static final int SCROLL = ErrandController.SCROLL_PROP;
	private static final int STICK = 9702;
	private static final int SEXTANT = 24487;

	private static final int WRITE = ErrandController.WRITE_SCROLL_POSE;
	private static final int DRAW_THE_CIRCLE = 4847;
	private static final int SIT_CROSSLEGGED = 4850;
	private static final int WAVE = 863;
	private static final int EXPLORE_LOOP = net.runelite.api.gameval.AnimationID.EMOTE_EXPLORE_LOOP;
	private static final int FLINCH = net.runelite.api.gameval.AnimationID.HUMAN_UNARMEDBLOCK;

	/** The follower: what each animation names for the hands. */
	private static final class Feet extends FollowerEntity
	{
		final Map<Integer, int[]> hands = new HashMap<>();

		Feet()
		{
			super(null, null);
		}

		@Override
		public int[] handItems(int animationId)
		{
			return hands.get(animationId);
		}
	}

	private FollowerPlugin plugin;
	private Feet feet;
	private final List<Outfit> built = new ArrayList<>();

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME), ("{\"version\":1,\"items\":{"
			+ "\"" + WHIP + "\":{\"n\":\"Abyssal whip\",\"wp1\":3,\"m\":[5409,-1,-1],\"f\":[5409,-1,-1]},"
			+ "\"" + DRAGON_SQUARE + "\":{\"n\":\"Dragon sq shield\",\"wp1\":5,\"m\":[2522,-1,-1],\"f\":[2522,-1,-1]},"
			+ "\"" + QUILL + "\":{\"n\":\"null\",\"m\":[60001,-1,-1],\"f\":[60001,-1,-1]},"
			+ "\"" + SCROLL + "\":{\"n\":\"null\",\"m\":[60002,-1,-1],\"f\":[60002,-1,-1]},"
			+ "\"" + STICK + "\":{\"n\":\"null\",\"m\":[60003,-1,-1],\"f\":[60003,-1,-1]},"
			+ "\"" + SEXTANT + "\":{\"n\":\"null\",\"m\":[60004,-1,-1],\"f\":[60004,-1,-1]}"
			+ "},\"kits\":{}}").getBytes(StandardCharsets.UTF_8));
		ModelRepository models = new ModelRepository(new Gson());
		models.load(dir);
		plugin = new FollowerPlugin();
		set("modelRepository", models);

		Player me = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, args) -> method.getName().equals("getWorldLocation")
				? new WorldPoint(3221, 3218, 0) : nothing(method.getReturnType()));
		set("client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("getLocalPlayer") ? me : nothing(method.getReturnType())));
		set("config", new FollowerConfig()
		{
			@Override
			public String customOutfit()
			{
				return "WEAPON=item:" + WHIP + ",SHIELD=item:" + DRAGON_SQUARE;
			}
		});
		set("appearanceService", new AppearanceService(null)
		{
			@Override
			public void request(Outfit outfit, Consumer<ComposedAppearance> onReady)
			{
				built.add(new Outfit(outfit));
			}
		});
		feet = new Feet();
		set("follower", feet);
		set("clientThread", new net.runelite.client.callback.ClientThread()
		{
			@Override
			public void invoke(Runnable r)
			{
				r.run();
			}
		});

		feet.hands.put(WRITE, new int[]{QUILL, SCROLL});
		feet.hands.put(DRAW_THE_CIRCLE, new int[]{-1, STICK});
		feet.hands.put(SIT_CROSSLEGGED, new int[]{-1, -1});
		feet.hands.put(WAVE, new int[]{-1, -1});
		feet.hands.put(EXPLORE_LOOP, new int[]{SEXTANT, -1});
		feet.hands.put(FLINCH, new int[]{-1, -1});
		IdleControllers.fill(plugin);
	}

	private static Object nothing(Class<?> type)
	{
		return type == int.class ? 0 : type == boolean.class ? false : null;
	}

	private Outfit lastBuilt()
	{
		return built.get(built.size() - 1);
	}

	/** An emote starting, and its first clip going on, as the follower reports them. */
	private void emote(int clip) throws ReflectiveOperationException
	{
		call("updateEmoteDisarm", true);
		call("dressEmoteHands", clip);
	}

	@Test
	public void theCloseCallWritesWithItsQuillAndScroll() throws ReflectiveOperationException
	{
		emote(WRITE);
		assertEquals("the quill in the left hand", QUILL, lastBuilt().itemId(KitType.SHIELD));
		assertEquals("the scroll in the right", SCROLL, lastBuilt().itemId(KitType.WEAPON));

		// And put away with the emote, in the rebuild that gives the gear back.
		int rebuilds = built.size();
		call("updateEmoteDisarm", false);
		assertEquals("rebuilt once", rebuilds + 1, built.size());
		assertEquals(WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));
	}

	@Test
	public void eachStageOfAChainHoldsOnlyWhatItNames() throws ReflectiveOperationException
	{
		emote(DRAW_THE_CIRCLE);
		assertEquals("the chalk stick for the circle", STICK, lastBuilt().itemId(KitType.WEAPON));
		assertFalse("and the other hand as the disarm left it", lastBuilt().isItem(KitType.SHIELD));

		call("dressEmoteHands", SIT_CROSSLEGGED);
		assertFalse("put down for the next stage", lastBuilt().isItem(KitType.WEAPON));
		assertFalse(lastBuilt().isItem(KitType.SHIELD));
	}

	@Test
	public void anEmoteThatNamesNothingIsNotRebuiltForIt() throws ReflectiveOperationException
	{
		emote(WAVE);
		int rebuilds = built.size();
		call("dressEmoteHands", WAVE);
		assertEquals(rebuilds, built.size());
		assertFalse("hands empty, as the emote disarm has them", lastBuilt().isItem(KitType.WEAPON));
	}

	@Test
	public void aHeldLoopHoldsWhatItNames() throws ReflectiveOperationException
	{
		// Explore's loop, mirrored as a pose after its entry clip.
		call("startPoseMirror", EXPLORE_LOOP);
		assertEquals("the sextant still to its eye", SEXTANT, lastBuilt().itemId(KitType.SHIELD));
		assertFalse("the weapon away, as for any emote", lastBuilt().isItem(KitType.WEAPON));

		call("stopPoseMirror");
		assertEquals("and the gear back once the player stops", WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));
	}

	// public:cut skilling
	@Test
	public void aCopyOfTheWorkKeepsToTheBorrowedTools() throws ReflectiveOperationException
	{
		set("copyAtWork", true);
		call("startPoseMirror", EXPLORE_LOOP);
		assertFalse(lastBuilt().isItem(KitType.SHIELD));
	}

	// public:end skilling
	@Test
	public void aBlockKeepsTheWeaponAndShieldUp() throws ReflectiveOperationException
	{
		emote(FLINCH);
		assertEquals("blocked with what it holds", WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));
		call("updateEmoteDisarm", false);

		emote(WAVE);
		assertFalse("any other emote still puts it away", lastBuilt().isItem(KitType.WEAPON));
	}

	@Test
	public void theCloseCallStillPlaysTheWritingClip() throws IOException
	{
		// Its quill and scroll come from the clip itself: a prop flourish
		// would not do, since it needs words to go with it and this rule
		// has none.
		JsonObject rule = null;
		try (Reader reader = new InputStreamReader(FollowerPlugin.class.getResourceAsStream(
			"/com/follower/default-phrases.json"), StandardCharsets.UTF_8))
		{
			for (JsonElement element : new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("rules"))
			{
				if ("memory-close-call".equals(element.getAsJsonObject().get("id").getAsString()))
				{
					rule = element.getAsJsonObject();
				}
			}
		}
		assertNotNull(rule);
		assertEquals(WRITE, rule.get("animation").getAsInt());
		assertFalse("silent", rule.has("say"));
	}

	@Test
	public void eachClipOfAnEmoteReachesTheHands() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		assertTrue(source.contains("follower.setOnEmoteClip(this::dressEmoteHands);"));
	}

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
}
