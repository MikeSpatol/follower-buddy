package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.AnimationID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Where a weapon's stance and swing come from, now that what ships and what
 * was learned are kept apart.
 *
 * <p>Poses: learned, then shipped, then a weapon of the same name, then
 * unarmed. Swings: the shipped one for the kind of blow, the learned one for
 * the style unless it was really a block or a spell, the shipped one, the
 * learned one unless it was a block, then the weapon class - and never the
 * unarmed punch for a real weapon.
 */
public class StanceSourcesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A model repository that knows only the names this test gives it. */
	private static final class Names extends ModelRepository
	{
		private final Map<Integer, String> names = new HashMap<>();

		Names()
		{
			super(new Gson());
		}

		Names name(int id, String name)
		{
			names.put(id, name);
			return this;
		}

		@Override
		public String itemName(int itemId)
		{
			return names.get(itemId);
		}
	}

	/** Invented poses, so a learned stance cannot be mistaken for a shipped one. */
	private static final int[] INVENTED = {900001, 900002, 900003};

	/** Item ids well past anything the shipped library holds. */
	private static final int BASE = 800000;

	private static final int WHIP = 4151;
	private static final int DRAGON_DAGGER = 1215;
	private static final int STAFF_OF_AIR = 1381;
	private static final int TRIDENT = 11905;
	private static final int SULPHUR_BLADES = 29084;

	private Names names;
	private Path dir;
	private StanceLibrary library;

	@Before
	public void setUp() throws IOException
	{
		names = new Names();
		dir = folder.newFolder().toPath();
		library = load();
	}

	private StanceLibrary load()
	{
		StanceLibrary fresh = new StanceLibrary(new Gson(), names);
		fresh.load(dir);
		return fresh;
	}

	private void writeLearned(String json) throws IOException
	{
		Files.write(dir.resolve(StanceLibrary.FILE_NAME), json.getBytes(StandardCharsets.UTF_8));
	}

	private JsonObject savedFile() throws IOException
	{
		return new Gson().fromJson(new String(
			Files.readAllBytes(dir.resolve(StanceLibrary.FILE_NAME)), StandardCharsets.UTF_8),
			JsonObject.class);
	}

	// ----------------------------------------------------------------- poses

	@Test
	public void aLearnedStanceBeatsTheShippedOne()
	{
		library.setManual(WHIP, INVENTED[0], INVENTED[1], INVENTED[2], 0);
		assertEquals(INVENTED[1], library.forWeapon(WHIP).walk);
	}

	@Test
	public void aShippedStanceBeatsOneLearnedForAWeaponOfTheSameName()
	{
		// The lowest id of a name is the one its variants inherit from, so the
		// learned weapon has to sit below the whip to be its donor at all.
		names.name(WHIP, "Abyssal whip");
		names.name(1, "Abyssal whip (or)");
		names.name(BASE + 1, "Abyssal whip (t)");
		library.setManual(1, INVENTED[0], INVENTED[1], INVENTED[2], 0);
		assertEquals("the learned weapon is the name's donor", INVENTED[1],
			library.forWeapon(BASE + 1).walk);

		assertEquals("the whip's own shipped walk", 1660, library.forWeapon(WHIP).walk);
	}

	// ------------------------------------------------------------ the swings

	@Test
	public void aWeaponNothingIsKnownAboutNeverPunches()
	{
		// It stands and walks unarmed, and used to fight unarmed too.
		library.setStyleSource(id -> StanceLibrary.StyleSource.packed(
			StanceLibrary.StyleSource.MELEE, true));
		assertEquals(0, library.attackFor(999999));
		assertEquals(0, library.attackFor(999999, 0, StanceLibrary.CRUSH));
	}

	@Test
	public void aLearnedPunchOnARealWeaponIsNotUsed()
	{
		library.setManual(BASE + 2, INVENTED[0], INVENTED[1], INVENTED[2], 422);
		assertEquals(0, library.attackFor(BASE + 2));
	}

	@Test
	public void theShippedSwingForTheKindOfBlowComesFirst()
	{
		library.setManual(DRAGON_DAGGER, 808, 819, 824, 0);
		library.forWeapon(DRAGON_DAGGER).attackByStyle = new int[]{0, 386, 0, 0};

		assertEquals("community data over a learned style", 376,
			library.attackFor(DRAGON_DAGGER, 1, StanceLibrary.STAB));
		assertEquals("the learned style when the kind is not known", 386,
			library.attackFor(DRAGON_DAGGER, 1, -1));
		assertEquals("the shipped swing when neither is", 376,
			library.attackFor(DRAGON_DAGGER, -1, -1));
	}

	@Test
	public void aKindTheWeaponDoesNotDealFallsBackToItsSwing()
	{
		// The whip only slashes; a stab from the player's dagger gets the lash.
		assertEquals(1658, library.attackFor(WHIP, 0, StanceLibrary.STAB));
	}

	@Test
	public void theShippedSwingBeatsALearnedOne()
	{
		library.setManual(WHIP, 808, 1660, 1661, 390);
		assertEquals(1658, library.attackFor(WHIP));
	}

	@Test
	public void blocksAndSpecialsLearnedAsSwingsArePassedOver()
	{
		// Steel pickaxe 397 HUMAN_AXE_BLOCK, Zombie axe 1156 HUMAN_SHIELD_DEFENCE:
		// both were learned as attacks. Neither weapon class lends a swing here.
		library.setManual(BASE + 3, INVENTED[0], INVENTED[1], INVENTED[2], 397);
		library.setManual(BASE + 4, INVENTED[0], INVENTED[1], INVENTED[2], 1156);
		assertEquals(0, library.attackFor(BASE + 3));
		assertEquals(0, library.attackFor(BASE + 4));
	}

	@Test
	public void theGodswordsSecondSlashIsNotMistakenForABlock()
	{
		// DH_SWORD_UPDATE_BLOCK by name, a real godsword swing by use.
		library.setManual(BASE + 5, INVENTED[0], INVENTED[1], INVENTED[2], 7055);
		assertEquals(7055, library.attackFor(BASE + 5));
	}

	@Test
	public void theSulphurBladesSwingTheirOwnSlashOnceSeen()
	{
		// 10990 SULPHUR_BLADE_SLASH: Weapon Animation Replacer files it as the
		// Dual Macauhuitl's special, which must not make it a banned swing.
		Player player = player(SULPHUR_BLADES, new int[]{808, 819, 824, 820, 821, 822, 823, 823}, true);
		library.learnAttack(player, 10990, -1);

		assertEquals(10990, library.attackFor(SULPHUR_BLADES));
	}

	@Test
	public void aLearnedStyleThatWasABlockOrASpellIsPassedOver()
	{
		library.setManual(BASE + 6, INVENTED[0], INVENTED[1], INVENTED[2], 0);
		library.forWeapon(BASE + 6).attackByStyle = new int[]{397, 1162, 390, 0};

		assertEquals("a block", 0, library.attackFor(BASE + 6, 0, -1));
		assertEquals("a spell cast at the target", 0, library.attackFor(BASE + 6, 1, -1));
		assertEquals("a real swing", 390, library.attackFor(BASE + 6, 2, -1));
	}

	@Test
	public void thePlainHumanClassLendsNoSwing()
	{
		// Unarmed, swords, maces, daggers and bows all stand, walk and run this
		// way, so agreement inside it says nothing about the weapon.
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);
		for (int i = 10; i < 13; i++)
		{
			library.setManual(BASE + i, 808, 819, 824, 390);
			usage.put(BASE + i, melee);
		}
		library.setManual(BASE + 13, 808, 819, 824, 0);
		usage.put(BASE + 13, melee);
		library.setStyleSource(id -> usage.getOrDefault(id, StanceLibrary.StyleSource.UNKNOWN));

		assertEquals(0, library.attackFor(BASE + 13));
	}

	@Test
	public void aClassNamedForItsWeaponStillLends()
	{
		// SLAYER_ABYSSAL_WHIP_WALK: the walk alone names the weapon.
		Map<Integer, Integer> usage = new HashMap<>();
		int melee = StanceLibrary.StyleSource.packed(StanceLibrary.StyleSource.MELEE, false);
		library.setManual(BASE + 20, 808, 1660, 1661, 1658);
		library.setManual(BASE + 21, 808, 1660, 1661, 0);
		usage.put(BASE + 20, melee);
		usage.put(BASE + 21, melee);
		library.setStyleSource(id -> usage.getOrDefault(id, StanceLibrary.StyleSource.UNKNOWN));

		assertEquals(1658, library.attackFor(BASE + 21));
	}

	@Test
	public void seeingAWeaponThatInheritsItsSwingByNameKeepsTheSwing()
	{
		// Not shipped, so its stance and swings come from the plain dagger;
		// seen once, it has a record of its own that knows no swing.
		names.name(DRAGON_DAGGER, "Dragon dagger");
		names.name(BASE + 50, "Dragon dagger (cr)");
		assertEquals(376, library.attackFor(BASE + 50, 0, StanceLibrary.STAB));

		library.observe(client(player(BASE + 50, new int[]{808, 819, 824, 820, 821, 822, 823, 823}, false)));

		assertEquals("seen carried, it now has a record of its own", 819,
			library.describe(BASE + 50).walk);
		assertEquals(376, library.attackFor(BASE + 50, 0, StanceLibrary.STAB));
		assertEquals(377, library.attackFor(BASE + 50, 2, StanceLibrary.SLASH));
		assertEquals(376, library.attackFor(BASE + 50));
	}

	@Test
	public void aWeaponThatInheritsByNameCastsAsItsPlainVersionDoes()
	{
		names.name(TRIDENT, "Trident of the seas (full)");
		names.name(BASE + 51, "Trident of the seas (e)");
		library.setManual(BASE + 51, INVENTED[0], INVENTED[1], INVENTED[2], 0);

		assertEquals(AnimationID.HUMAN_CASTWAVE_STAFF, library.castFor(BASE + 51));
	}

	@Test
	public void aWeaponNothingIsKnownAboutTakesNoCastFromUnarmed()
	{
		library.setManual(StanceLibrary.UNARMED, 808, 819, 824, 1162);
		assertEquals(0, library.castFor(999999));
	}

	@Test
	public void aLearnedCastIsWhatACasterUses()
	{
		library.setManual(1381, 813, 1205, 1210, 11423);
		assertEquals(11423, library.castFor(1381));
		assertEquals("and the staff still bashes for anyone else", 419, library.attackFor(1381));
	}

	// ----------------------------------------------------- what gets written

	@Test
	public void onlyWhatWasLearnedIsSaved() throws IOException
	{
		library.setManual(BASE + 30, INVENTED[0], INVENTED[1], INVENTED[2], 0);
		library.save();

		JsonObject saved = savedFile();
		assertEquals("the shipped stances were written out too: " + saved.keySet(),
			Collections.singleton(Integer.toString(BASE + 30)), saved.keySet());
	}

	@Test
	public void copiesOfTheShippedStancesAreDroppedOnLoad() throws IOException
	{
		writeLearned("{"
			// Exactly as an older save wrote the shipped whip.
			+ "\"4151\":{\"idle\":808,\"walk\":1660,\"run\":1661,\"walkBack\":1660,\"walkLeft\":1660,"
			+ "\"walkRight\":1660,\"turnLeft\":823,\"turnRight\":823,\"attack\":1658},"
			// An older bundle's copy, before it knew the turns.
			+ "\"1215\":{\"idle\":808,\"walk\":819,\"run\":824,\"walkBack\":0,\"walkLeft\":0,"
			+ "\"walkRight\":0,\"turnLeft\":0,\"turnRight\":0,\"attack\":0},"
			// A real observation the shipped data does not have.
			+ "\"" + (BASE + 31) + "\":{\"idle\":900001,\"walk\":900002,\"run\":900003}"
			+ "}");

		StanceLibrary reloaded = load();
		assertEquals("the shipped turns are no longer hidden", 820,
			reloaded.forWeapon(DRAGON_DAGGER).walkBack);

		reloaded.save();
		assertEquals(Collections.singleton(Integer.toString(BASE + 31)), savedFile().keySet());
	}

	@Test
	public void aLearnedRecordWithSomethingOfItsOwnIsKept() throws IOException
	{
		writeLearned("{\"4151\":{\"idle\":808,\"walk\":1660,\"run\":1661,\"attack\":390}}");

		StanceLibrary reloaded = load();
		assertEquals("the shipped swing is still swung", 1658, reloaded.attackFor(WHIP));

		reloaded.save();
		JsonObject whip = savedFile().getAsJsonObject(Integer.toString(WHIP));
		assertEquals("its own attack survives", 390, whip.get("attack").getAsInt());
		assertEquals("the copied poses do not", 0, whip.get("idle").getAsInt());
	}

	@Test
	public void aFixToTheShippedPosesShowsThroughASwingLearnedOnAnOldCopy() throws IOException
	{
		// An older bundle's Staff of air, before it knew the turns, saved with
		// a cast this install saw on top.
		writeLearned("{\"1381\":{\"idle\":813,\"walk\":1205,\"run\":1210,\"walkBack\":0,"
			+ "\"walkLeft\":0,\"walkRight\":0,\"turnLeft\":0,\"turnRight\":0,\"attack\":1162}}");

		StanceLibrary reloaded = load();
		assertEquals("the shipped turns are no longer hidden", 1206, reloaded.forWeapon(STAFF_OF_AIR).walkBack);
		assertEquals("the cast it learned is kept", 1162, reloaded.castFor(STAFF_OF_AIR));
	}

	@Test
	public void theLearnedFileIsBackedUpOnceBeforeItIsFirstTrimmed() throws IOException
	{
		String before = "{\"1215\":{\"idle\":808,\"walk\":819,\"run\":824}}";
		writeLearned(before);
		load().save();

		Path backup = dir.resolve(StanceLibrary.BACKUP_NAME);
		assertEquals(before, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));

		// A later trim leaves the first backup alone.
		writeLearned("{\"4151\":{\"idle\":808,\"walk\":1660,\"run\":1661}}");
		load().save();
		assertEquals(before, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
	}

	@Test
	public void nothingToTrimWritesNoBackup() throws IOException
	{
		writeLearned("{\"" + (BASE + 32) + "\":{\"idle\":900001,\"walk\":900002,\"run\":900003}}");
		load();
		assertFalse(Files.exists(dir.resolve(StanceLibrary.BACKUP_NAME)));
	}

	// --------------------------------------------------------- live learning

	@Test
	public void seeingAWeaponCarriedAsShippedLearnsNothing() throws IOException
	{
		Player player = player(WHIP, new int[]{808, 1660, 1661, 1660, 1660, 1660, 823, 823}, false);
		library.observe(client(player));
		library.save();

		assertFalse("nothing new was seen, so nothing is written",
			Files.isRegularFile(dir.resolve(StanceLibrary.FILE_NAME)));
	}

	@Test
	public void seeingAWeaponCarriedDifferentlyLearnsIt()
	{
		// A stand of its own too: on the shipped stand it would be a passing swap.
		Player player = player(WHIP, new int[]{INVENTED[0], 1660, 11466, 1660, 1660, 1660, 823, 823}, false);
		library.observe(client(player));

		assertEquals(11466, library.forWeapon(WHIP).run);
	}

	@Test
	public void aSwingSeenWithAShippedWeaponKeepsItsShippedCarry() throws IOException
	{
		Player player = player(WHIP, new int[]{808, 1660, 1661, 1660, 1660, 1660, 823, 823}, true);
		library.learnAttack(player, 1658, 0);
		library.learnAttack(player, 390, 1);

		assertEquals("carried as shipped", 1660, library.forWeapon(WHIP).walk);
		assertEquals("the style it was seen in", 390, library.attackFor(WHIP, 1, -1));

		// Saved as the swing alone: a copy of the shipped poses would outlive
		// every later fix to them.
		library.save();
		JsonObject whip = savedFile().getAsJsonObject(Integer.toString(WHIP));
		assertNotNull(whip);
		assertEquals(0, whip.get("idle").getAsInt());
		assertEquals(0, whip.get("walk").getAsInt());
		assertEquals(390, whip.getAsJsonArray("attackByStyle").get(1).getAsInt());
	}

	@Test
	public void aWeaponSwungAndThenSeenCarriedAsShippedLearnsNoPoses() throws IOException
	{
		Player player = player(WHIP, new int[]{808, 1660, 1661, 1660, 1660, 1660, 823, 823}, true);
		library.learnAttack(player, 390, 1);
		library.observe(client(player));
		library.save();

		assertEquals(0, savedFile().getAsJsonObject(Integer.toString(WHIP)).get("idle").getAsInt());
	}

	@Test
	public void aWeaponSwungAndThenSeenCarriedDifferentlyKeepsItsSwings()
	{
		library.learnAttack(player(WHIP, new int[]{808, 1660, 1661, 1660, 1660, 1660, 823, 823}, true), 390, 1);
		library.observe(client(player(WHIP, new int[]{INVENTED[0], 1660, 11466, 1660, 1660, 1660, 823, 823}, false)));

		assertEquals(11466, library.forWeapon(WHIP).run);
		assertEquals("the swing in the style it was seen in", 390, library.attackFor(WHIP, 1, -1));
	}

	// ------------------------------------------------- a player on an obstacle

	/** HUMAN_WALK_LOGBALANCE_READY and HUMAN_WALK_LOGBALANCE: the set the game swaps in for a log. */
	private static final int[] ON_A_LOG = {763, 762, 762, 762, 762, 762, 763, 763};

	/**
	 * The game does a log or a ledge by swapping the player's base set, so
	 * a player seen on one shows the balance where their weapon's stand and
	 * walk would be (QA4 #5). Unarmed is shipped and never learned, and a set
	 * whose stand, walk or run empties a hand is no weapon's: every real
	 * stance keeps both, every obstacle set empties both.
	 */
	@Test
	public void aPlayerOnALogTeachesNoStance()
	{
		Client client = client(player(-1, ON_A_LOG, false));
		library.observe(client);
		assertEquals("empty-handed: still the follower's own walk", 819, library.forWeapon(-1).walk);
		assertEquals(808, library.forWeapon(-1).idle);

		int before = library.forWeapon(STAFF_OF_AIR).walk;
		library.observe(client(player(STAFF_OF_AIR, ON_A_LOG, false)));
		assertEquals("nor a weapon's", before, library.forWeapon(STAFF_OF_AIR).walk);

		library.observe(client(player(WHIP, new int[]{808, 762, 824, 820, 821, 822, 823, 823}, false)));
		assertEquals("nor a walk alone swapped", 1660, library.forWeapon(WHIP).walk);
	}

	/** One an older build learned is taken out of the file on the first tick, its swing kept. */
	@Test
	public void anObstacleSetLearnedBeforeIsForgotten() throws IOException
	{
		writeLearned("{\"0\":{\"idle\":763,\"walk\":762,\"run\":762,\"attack\":423},"
			+ "\"" + STAFF_OF_AIR + "\":{\"idle\":763,\"walk\":762,\"run\":762},"
			+ "\"" + (BASE + 7) + "\":{\"idle\":900001,\"walk\":900002,\"run\":900003}}");
		library = load();
		library.observe(client());
		library.save();

		JsonObject saved = savedFile();
		assertEquals("the unarmed swing stays", 423, saved.getAsJsonObject("0").get("attack").getAsInt());
		assertEquals("its balance goes", 0, saved.getAsJsonObject("0").get("walk").getAsInt());
		assertFalse(saved.has(Integer.toString(STAFF_OF_AIR)));
		assertEquals("a real one stays", 900002,
			saved.getAsJsonObject(Integer.toString(BASE + 7)).get("walk").getAsInt());
		assertEquals(819, library.forWeapon(-1).walk);
	}

	private static final int RUNE_2H = 1319;

	/**
	 * A two-hander seen on its own stand but the plain walk and run is
	 * mid-swap, and teaches nothing: one sighting used to carry it like a
	 * one-handed sword for good (qa5 #31).
	 */
	@Test
	public void aShippedStandWithAnotherWalkTeachesNothing() throws IOException
	{
		library.observe(client(player(RUNE_2H, new int[]{2561, 819, 824, 820, 821, 822, 823, 823}, false)));
		library.save();

		StanceLibrary reloaded = load();
		assertEquals(2562, reloaded.forWeapon(RUNE_2H).walk);
		assertEquals(2563, reloaded.forWeapon(RUNE_2H).run);
	}

	/** Such a set learned before is taken out on the first tick, its swing kept. */
	@Test
	public void aPassingSwapLearnedBeforeIsForgotten() throws IOException
	{
		writeLearned("{\"" + RUNE_2H + "\":{\"idle\":2561,\"walk\":819,\"run\":824,\"attack\":7045},"
			+ "\"20368\":{\"idle\":7053,\"walk\":819,\"run\":824}}");
		library = load();
		library.observe(client());
		library.save();

		JsonObject saved = savedFile();
		assertEquals("its swing stays", 7045, saved.getAsJsonObject(Integer.toString(RUNE_2H)).get("attack").getAsInt());
		assertEquals("its walk goes", 0, saved.getAsJsonObject(Integer.toString(RUNE_2H)).get("walk").getAsInt());
		assertFalse(saved.has("20368"));
		assertEquals(2562, library.forWeapon(RUNE_2H).walk);
		assertEquals(7052, library.forWeapon(20368).walk);
	}

	// ------------------------------------------------------------- doubles

	private static Player player(int weapon, int[] poses, boolean fighting)
	{
		Map<String, Object> composition = new HashMap<>();
		composition.put("getEquipmentId", weapon);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getPlayerComposition", proxy(PlayerComposition.class, composition));
		answers.put("getIdlePoseAnimation", poses[0]);
		answers.put("getWalkAnimation", poses[1]);
		answers.put("getRunAnimation", poses[2]);
		answers.put("getWalkRotate180", poses[3]);
		answers.put("getWalkRotateLeft", poses[4]);
		answers.put("getWalkRotateRight", poses[5]);
		answers.put("getIdleRotateLeft", poses[6]);
		answers.put("getIdleRotateRight", poses[7]);
		if (fighting)
		{
			answers.put("getInteracting", proxy(NPC.class, new HashMap<>()));
		}
		return proxy(Player.class, answers);
	}

	private static Client client(Player... seen)
	{
		List<Player> players = java.util.Arrays.asList(seen);
		IndexedObjectSet<Player> set = new IndexedObjectSet<Player>()
		{
			@Override
			public Player byIndex(int index)
			{
				return index < players.size() ? players.get(index) : null;
			}

			@Override
			public Iterator<Player> iterator()
			{
				return players.iterator();
			}
		};
		Map<String, Object> view = new HashMap<>();
		view.put("players", set);
		Map<String, Object> client = new HashMap<>();
		client.put("getTopLevelWorldView", proxy(WorldView.class, view));
		// The log balance empties both hands, as every obstacle set does.
		net.runelite.api.Animation keeps = proxy(net.runelite.api.Animation.class,
			Map.of("getLeftHandItem", -1, "getRightHandItem", -1));
		net.runelite.api.Animation empties = proxy(net.runelite.api.Animation.class,
			Map.of("getLeftHandItem", -512, "getRightHandItem", -512));
		Client plain = proxy(Client.class, client);
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(self, method, args) -> method.getName().equals("loadAnimation")
				? ((Integer) args[0] == 762 || (Integer) args[0] == 763 ? empties : keeps)
				: method.invoke(plain, args));
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(self, method, args) ->
			{
				if (answers.containsKey(method.getName()))
				{
					return answers.get(method.getName());
				}
				if (method.getName().equals("hashCode"))
				{
					return System.identityHashCode(self);
				}
				if (method.getName().equals("equals"))
				{
					return self == args[0];
				}
				Class<?> returns = method.getReturnType();
				return returns == int.class ? (Object) 0 : returns == boolean.class ? (Object) false : null;
			});
	}
}
