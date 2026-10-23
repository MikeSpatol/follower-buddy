// public:file devdump,posing
package com.follower;

import com.follower.appearance.AppearanceComposer;
import com.follower.appearance.AppearanceService;
import com.follower.appearance.ComposedAppearance;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import com.follower.follower.ErrandController;
import com.follower.follower.FilmSet;
import com.follower.follower.FollowerEntity;
import com.google.gson.Gson;
import java.io.IOException;
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
import java.util.Set;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The film's pose dressed as the game dresses it: each hand as its animation
 * names it, the left-hand item in the shield slot and the right-hand item in
 * the weapon slot.
 *
 * <p>The quill of the charting write has no wear slot, and the hand slot for
 * anything without one used to be the weapon's, so the quill and the scroll
 * both went for the weapon hand. Reached on a bare plugin, as StayHoldsTest
 * reaches its glue, with a model dump of just the items in play.
 *
 * <p>The film is built here as startUp builds it, its rebuild the plugin's
 * own, so what is checked is the outfit the plugin asks a model of: the
 * props as a pose starts, and the follower's own gear when a one-shot ends
 * or a held pose gives way to a walk.
 */
public class FilmPropsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int WHIP = 4151;
	private static final int DRAGON_SQUARE = 1187;
	private static final int QUILL = 31829;
	private static final int CHART = 31830;
	private static final int BOOK = 3850;
	/** A snowball: in the cache, with no worn model. */
	private static final int SNOWBALL = 10509;
	/** Every felling axe is worn in the weapon slot and hides the shield slot. */
	private static final int FELLING_AXE = 28196;
	private static final int LOGS = 10810;
	/** Hides the shield slot too, as every two-hander does. */
	private static final int GODSWORD = 11802;
	private static final int BANANA = 3177;
	private static final int CHAIR = 8798;

	private static final int WRITE_WITH_A_QUILL = 13302;
	private static final int SIT_ON_A_WOODEN_CHAIR = 4073;

	/** The follower as the film sees it: what each animation holds, and whether it emotes or walks. */
	private static final class Feet extends FollowerEntity
	{
		final Map<Integer, int[]> hands = new HashMap<>();
		boolean emoting;
		boolean moving;

		Feet()
		{
			super(null, null);
		}

		@Override
		public int[] handItems(int animationId)
		{
			return hands.get(animationId);
		}

		@Override
		public void playAnimation(int animationId)
		{
			emoting = true;
		}

		@Override
		public boolean isEmotePlaying()
		{
			return emoting;
		}

		@Override
		public boolean isMoving()
		{
			return moving;
		}
	}

	private FollowerPlugin plugin;
	private ModelRepository models;
	private Feet feet;
	private FilmSet film;

	/** Every outfit the plugin asked a model of, in order. */
	private final List<Outfit> built = new ArrayList<>();

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME), ("{\"version\":1,\"items\":{"
			+ "\"" + WHIP + "\":{\"n\":\"Abyssal whip\",\"wp1\":3,\"m\":[5409,-1,-1],\"f\":[5409,-1,-1]},"
			+ "\"" + DRAGON_SQUARE + "\":{\"n\":\"Dragon sq shield\",\"wp1\":5,\"m\":[2522,-1,-1],\"f\":[2522,-1,-1]},"
			+ "\"" + QUILL + "\":{\"n\":\"null\",\"m\":[60001,-1,-1],\"f\":[60001,-1,-1]},"
			+ "\"" + CHART + "\":{\"n\":\"null\",\"m\":[60002,-1,-1],\"f\":[60002,-1,-1]},"
			+ "\"" + BOOK + "\":{\"n\":\"null\",\"m\":[4591,-1,-1],\"f\":[4591,-1,-1]},"
			+ "\"" + SNOWBALL + "\":{\"n\":\"Snowball\",\"m\":[-1,-1,-1],\"f\":[-1,-1,-1]},"
			+ "\"" + FELLING_AXE + "\":{\"n\":\"Bronze felling axe\",\"wp1\":3,\"wp2\":5,"
			+ "\"m\":[60003,-1,-1],\"f\":[60003,-1,-1]},"
			+ "\"" + LOGS + "\":{\"n\":\"Arctic pine logs\",\"wp1\":5,\"m\":[60004,-1,-1],\"f\":[60004,-1,-1]},"
			+ "\"" + GODSWORD + "\":{\"n\":\"Armadyl godsword\",\"wp1\":3,\"wp2\":5,"
			+ "\"m\":[60005,-1,-1],\"f\":[60005,-1,-1]},"
			+ "\"" + BANANA + "\":{\"n\":\"null\",\"m\":[60006,-1,-1],\"f\":[60006,-1,-1]},"
			+ "\"" + CHAIR + "\":{\"n\":\"null\",\"wp1\":3,\"m\":[60007,-1,-1],\"f\":[60007,-1,-1]}"
			+ "},\"kits\":{}}").getBytes(StandardCharsets.UTF_8));
		models = new ModelRepository(new Gson());
		models.load(dir);
		plugin = new FollowerPlugin();
		set("modelRepository", models);
	}

	/**
	 * The rest of what a rebuild reads: a player in the world, the follower's
	 * own whip and shield, and a model service that records what it is asked
	 * for. The film rebuilds through the plugin, as startUp builds it.
	 */
	private void filmOnThePlugin() throws ReflectiveOperationException
	{
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
		film = new FilmSet(feet, () ->
		{
			try
			{
				call("rebuildFollower");
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		}, (graphic, height) ->
		{
		});
		set("filmSet", film);
	}

	private static Object nothing(Class<?> type)
	{
		return type == int.class ? 0 : type == boolean.class ? false : null;
	}

	private Outfit lastBuilt()
	{
		return built.get(built.size() - 1);
	}

	@Test
	public void aLeftHandItemGoesInTheShieldSlot() throws ReflectiveOperationException
	{
		assertEquals(KitType.SHIELD, call("handSlot", QUILL, true));
		assertEquals(KitType.WEAPON, call("handSlot", CHART, false));
		assertEquals("whatever it is worn as", KitType.SHIELD, call("handSlot", WHIP, true));
		assertNull("the game draws nothing for it", call("handSlot", SNOWBALL, true));
		assertNull("nor for an item it has no model of at all", call("handSlot", 99999, false));
	}

	@Test
	public void eachHandIsDressedAsTheAnimationNamesIt() throws ReflectiveOperationException
	{
		Outfit writing = dress(QUILL, CHART);
		assertEquals("the quill in the shield slot", QUILL, writing.itemId(KitType.SHIELD));
		assertEquals("the chart in the weapon slot", CHART, writing.itemId(KitType.WEAPON));

		Outfit reading = dress(FilmSet.EMPTY, BOOK);
		assertFalse("an empty hand is empty", reading.isItem(KitType.SHIELD));
		assertEquals(BOOK, reading.itemId(KitType.WEAPON));

		Outfit peering = dress(FilmSet.KEEP, FilmSet.KEEP);
		assertEquals("a hand it names nothing for keeps the follower's own", WHIP, peering.itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, peering.itemId(KitType.SHIELD));

		Outfit throwing = dress(SNOWBALL, FilmSet.KEEP);
		assertFalse("an item with no worn model shows nothing, as in the game", throwing.isItem(KitType.SHIELD));
		assertEquals(WHIP, throwing.itemId(KitType.WEAPON));
	}

	@Test
	public void aPropIsDrawnWhereverTheGearWouldHideIt() throws ReflectiveOperationException
	{
		// A felling axe hides the shield slot when worn, and the axe of the
		// two-handed chop is in the shield slot: it hid itself, and the
		// follower chopped with empty hands.
		Outfit chopping = dress(FELLING_AXE, FilmSet.EMPTY);
		assertEquals(FELLING_AXE, chopping.itemId(KitType.SHIELD));
		assertFalse("the model keeps the axe", hidden(chopping).contains(KitType.SHIELD));

		Outfit carving = dress(LOGS, FELLING_AXE);
		assertFalse("the axe in the right hand leaves the logs in the left",
			hidden(carving).contains(KitType.SHIELD));

		Outfit eating = new Outfit();
		eating.setItem(KitType.WEAPON, GODSWORD);
		call("dressForPose", eating, new int[]{BANANA, FilmSet.KEEP});
		assertEquals("the godsword kept", GODSWORD, eating.itemId(KitType.WEAPON));
		assertFalse("and the banana beside it", hidden(eating).contains(KitType.SHIELD));
	}

	@Test
	public void aOneShotsPropsComeOffWhenTheFollowerSaysItHasEnded() throws ReflectiveOperationException
	{
		filmOnThePlugin();
		feet.hands.put(WRITE_WITH_A_QUILL, new int[]{QUILL, CHART});
		film.once(WRITE_WITH_A_QUILL);
		Outfit writing = lastBuilt();
		assertEquals("dressed as it starts", QUILL, writing.itemId(KitType.SHIELD));
		assertEquals(CHART, writing.itemId(KitType.WEAPON));

		// What the follower's emote-ended call reaches.
		int rebuilds = built.size();
		feet.emoting = false;
		call("updateEmoteDisarm", false);
		assertNull("no props left on", film.hands());
		assertFalse("and the emote disarm is back in charge", film.isPosing());
		assertEquals("rebuilt once", rebuilds + 1, built.size());
		assertEquals("with its own whip", WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals("and shield", DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));
	}

	@Test
	public void aHeldPropGivesWayToTheFollowersOwnGearForAWalk() throws ReflectiveOperationException
	{
		filmOnThePlugin();
		feet.hands.put(SIT_ON_A_WOODEN_CHAIR, new int[]{FilmSet.EMPTY, CHAIR});
		film.hold(SIT_ON_A_WOODEN_CHAIR);
		assertEquals(CHAIR, lastBuilt().itemId(KitType.WEAPON));
		assertFalse(lastBuilt().isItem(KitType.SHIELD));

		// A Send: it sets off for the spot. The chair stayed in its hand, as
		// its weapon, and it walked in the chair's stance.
		feet.moving = true;
		film.frame();
		assertEquals("its own whip for the walk, and so its own stance", WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));

		feet.moving = false;
		film.frame();
		assertEquals("the chair again once it stands", CHAIR, lastBuilt().itemId(KitType.WEAPON));
		assertFalse(lastBuilt().isItem(KitType.SHIELD));
	}

	@Test
	public void theWritingPropsAreDressedAsTheFilmDressesThem() throws ReflectiveOperationException
	{
		// The quill and the scroll the errands, the minutes and the sketch
		// take out through the plugin's hands, laid on as the film lays a
		// pose's: the quill in the shield slot over the follower's own shield.
		filmOnThePlugin();
		set("propHands", new int[]{ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP});
		call("rebuildFollower");
		assertEquals("the quill in the left hand", QUILL, lastBuilt().itemId(KitType.SHIELD));
		assertEquals("the scroll in the right", CHART, lastBuilt().itemId(KitType.WEAPON));
		assertFalse("drawn whatever the gear hides", hidden(lastBuilt()).contains(KitType.SHIELD));

		set("propHands", null);
		call("rebuildFollower");
		assertEquals("put away, its own whip", WHIP, lastBuilt().itemId(KitType.WEAPON));
		assertEquals("and shield", DRAGON_SQUARE, lastBuilt().itemId(KitType.SHIELD));
	}

	/**
	 * The film's wiring, which only startUp and the frame make: its rebuild
	 * is the plugin's and its graphics the follower's, the emote's end
	 * reaches it, and so does each frame after the follower's own.
	 */
	@Test
	public void theFilmIsWiredToTheModelTheGraphicsAndTheFrame() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		String start = source.substring(source.indexOf("protected void startUp() throws Exception"));
		assertTrue("startUp builds the film with the plugin's rebuild and the follower's graphics", start.contains(
			"filmSet = new com.follower.follower.FilmSet(follower, this::rebuildFollower,\n"
				+ "\t\t\t(graphicId, height) -> follower.playSpotAnim(spotAnimRepository.get(graphicId), height));"));
		assertTrue("the follower's emote end reaches updateEmoteDisarm",
			source.contains("follower.setOnEmoteStateChanged(this::updateEmoteDisarm);"));
		String render = source.substring(source.indexOf("public void onBeforeRender(BeforeRender event)"));
		render = render.substring(0, render.indexOf("\n\t}\n"));
		int frame = render.indexOf("follower.updateFrame();");
		assertTrue("each frame reaches the film after the follower's own",
			frame > 0 && render.indexOf("filmSet.frame();") > frame);
	}

	@Test
	public void noPoseOnLeavesTheFollowersOwnGear() throws ReflectiveOperationException
	{
		Outfit own = armed();
		call("dressForPose", own, null);
		assertEquals(WHIP, own.itemId(KitType.WEAPON));
		assertEquals(DRAGON_SQUARE, own.itemId(KitType.SHIELD));
	}

	private Outfit dress(int left, int right) throws ReflectiveOperationException
	{
		Outfit outfit = armed();
		call("dressForPose", outfit, new int[]{left, right});
		return outfit;
	}

	/** What the composer would leave out of the model, as compose asks it. */
	private Set<?> hidden(Outfit outfit) throws ReflectiveOperationException
	{
		Method hiddenSlots = AppearanceComposer.class.getDeclaredMethod("hiddenSlots", Outfit.class);
		hiddenSlots.setAccessible(true);
		return (Set<?>) hiddenSlots.invoke(new AppearanceComposer(null, models), outfit.withDefaultBody());
	}

	private static Outfit armed()
	{
		Outfit outfit = new Outfit();
		outfit.setItem(KitType.WEAPON, WHIP);
		outfit.setItem(KitType.SHIELD, DRAGON_SQUARE);
		return outfit;
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
