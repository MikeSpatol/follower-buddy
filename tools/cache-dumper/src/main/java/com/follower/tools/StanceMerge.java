package com.follower.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.DBRowDefinition;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.DBRowLoader;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Builds the shipped weapon stance library from the only sources the plugin
 * may ship, and lists what still wants a look in game.
 *
 * <p>The sources, in the order they win:
 * <ol>
 * <li>Stances this plugin learned by watching players: the learned
 * {@code stances.json} and the current bundle. They are live observations, so
 * they win for idle, walk, run and the turns.</li>
 * <li>Weapon Animation Replacer by geheur (BSD-2-Clause): the pose tuples its
 * author observed, then its animation sets, which also carry the stab, slash
 * and crush swings and the block. A set reaches every variant of a weapon the
 * same way the plugin itself does it, through RuneLite's ItemVariationMapping.
 * Its attacks win over learned ones: the learned ones include blocks, spell
 * casts and skilling swings filed as attacks.</li>
 * <li>The game cache and RuneLite's gameval names, which decide nothing about
 * which weapon does what but check everything: an id must exist and must be on
 * the player skeleton, the combat tab's own table says which kind of blow each
 * style button is, and Jagex's names say which animations are blocks, specials
 * and spell casts.</li>
 * </ol>
 *
 * <p>Writes {@code src/main/resources/com/follower/stances.json} and
 * {@code attack-kinds.json}, and {@code docs/weapon-stance-sources.md}.
 *
 * <pre>
 *   gradlew runStanceMerge --args="--war &lt;weapon-animation-replacer checkout&gt;
 *       [--learned &lt;stances.json&gt;] [--cache &lt;dir&gt;]"
 * </pre>
 */
public class StanceMerge
{
	// Weapon Animation Replacer's Swap.AnimationType order, which the arrays in
	// its data.json follow.
	private static final int STAND = 0;
	private static final int WALK = 1;
	private static final int RUN = 2;
	private static final int WALK_BACKWARD = 3;
	private static final int SHUFFLE_LEFT = 4;
	private static final int SHUFFLE_RIGHT = 5;
	private static final int ROTATE = 6;
	private static final int ATTACK_STAB = 7;
	private static final int ATTACK_SLASH = 8;
	private static final int ATTACK_CRUSH = 9;
	private static final int ATTACK_SPEC = 10;
	private static final int DEFEND = 11;
	private static final int ATTACK_SLASH2 = 12;
	private static final int ATTACK_CRUSH2 = 13;
	private static final int ATTACK = 14;

	/** The plugin's key for "nothing wielded" (StanceLibrary.UNARMED). */
	private static final int UNARMED = 0;

	/** The order of a record's attackByType and of the style kinds: stab, slash, crush. */
	private static final String[] KINDS = {"Stab", "Slash", "Crush"};

	/** HUMAN_UNARMEDPUNCH and HUMAN_UNARMEDKICK: never a real weapon's swing. */
	private static final Set<Integer> UNARMED_SWINGS = new TreeSet<>(Arrays.asList(422, 423));

	/** HUMAN_AXE_CHOP: what a staff's bash becomes with a shield in the other hand. */
	private static final int ONE_HANDED_CHOP = 393;

	/** HUMAN_STAFFORB_PUMMEL and HUMAN_STAFF_PUMMEL: a staff swung with both hands. */
	private static final Set<Integer> STAFF_BASHES = new TreeSet<>(Arrays.asList(419, 414));

	/** The combat tab's table: weapon category, then each style button. */
	private static final int COMBAT_INTERFACE_TABLE = 78;

	/** The obj params holding the stab, slash and crush attack bonuses. */
	private static final int[] ATTACK_BONUS_PARAMS = {0, 1, 2};

	/** A stance as the plugin stores it. */
	static final class Rec
	{
		int idle;
		int walk;
		int run;
		int walkBack;
		int walkLeft;
		int walkRight;
		int turnLeft;
		int turnRight;
		int attack;
		int[] attackByStyle;
		int[] attackByType;

		/**
		 * Written on bundle records whose carry came from a learned stance.
		 * The plugin ignores it; this tool reads it back. Once the plugin drops
		 * learned records the bundle already holds, the bundle is the only
		 * place those observations still live, and without the mark a rerun
		 * would let Weapon Animation Replacer's older data replace them.
		 */
		boolean seen;

		int[] poses()
		{
			return new int[]{idle, walk, run, walkBack, walkLeft, walkRight, turnLeft, turnRight};
		}

		void poses(int[] p)
		{
			idle = p[0];
			walk = p[1];
			run = p[2];
			walkBack = p[3];
			walkLeft = p[4];
			walkRight = p[5];
			turnLeft = p[6];
			turnRight = p[7];
		}

		String carry()
		{
			return idle + "/" + walk + "/" + run;
		}
	}

	private Path cacheDir = Paths.get(System.getProperty("user.home"),
		".runelite", "jagexcache", "oldschool", "LIVE");
	private Path learnedFile = Paths.get(System.getProperty("user.home"),
		".runelite", "follower", "stances.json");
	private Path warDir;
	private final Path bundleFile = Paths.get("src/main/resources/com/follower/stances.json");
	private final Path kindsFile = Paths.get("src/main/resources/com/follower/attack-kinds.json");
	private final Path reportFile = Paths.get("docs/weapon-stance-sources.md");

	// From the cache.
	private final Map<Integer, ItemDefinition> items = new HashMap<>();
	private final Map<Integer, Integer> skeletonOf = new HashMap<>();
	private final Map<Integer, int[]> styleKinds = new TreeMap<>();

	// From the gameval names.
	private final Map<Integer, List<String>> animNames = new HashMap<>();

	// From the plugin's own observations.
	private Map<Integer, Rec> learned;
	private Map<Integer, Rec> oldBundle;

	// From Weapon Animation Replacer.
	private final Map<String, int[]> warSets = new LinkedHashMap<>();
	private final Map<String, int[]> warPoseanims = new LinkedHashMap<>();
	private final Map<Integer, int[]> warObserved = new HashMap<>();
	private final Map<String, String> warUnsure = new TreeMap<>();
	private String warVersion = "unknown";

	// The report, section by section.
	private final Map<String, List<String>> report = new LinkedHashMap<>();

	public static void main(String[] args) throws IOException
	{
		StanceMerge merge = new StanceMerge();
		for (int i = 0; i + 1 < args.length; i += 2)
		{
			switch (args[i])
			{
				case "--war":
					merge.warDir = Paths.get(args[i + 1]);
					break;
				case "--learned":
					merge.learnedFile = Paths.get(args[i + 1]);
					break;
				case "--cache":
					merge.cacheDir = Paths.get(args[i + 1]);
					break;
				default:
					throw new IllegalArgumentException("Unknown option " + args[i]);
			}
		}
		if (merge.warDir == null)
		{
			throw new IllegalArgumentException(
				"--war <weapon-animation-replacer checkout> is required");
		}
		merge.run();
	}

	private void run() throws IOException
	{
		loadCache();
		loadAnimationNames();
		learned = readStances(learnedFile);
		oldBundle = readStances(bundleFile);
		oldBundle.values().removeIf(rec -> !rec.seen);
		loadWar();

		Set<Integer> notAttacks = notAttacks();
		Set<Integer> casts = casts();

		Map<Integer, String> setOf = warSetPerItem();
		Map<Integer, Rec> out = new TreeMap<>();
		Map<String, Integer> poseSources = new TreeMap<>();
		Map<String, Integer> attackSources = new TreeMap<>();

		Set<Integer> candidates = new TreeSet<>();
		candidates.addAll(learned.keySet());
		candidates.addAll(oldBundle.keySet());
		candidates.addAll(setOf.keySet());
		candidates.addAll(warObserved.keySet());

		for (int id : candidates)
		{
			if (id != UNARMED && !isWeapon(id))
			{
				continue;
			}
			Rec rec = new Rec();
			String poseSource = poses(id, setOf.get(id), rec);
			if (poseSource == null)
			{
				note("Weapons left out: no source gives a playable idle, walk and run",
					label(id));
				continue;
			}
			rec.seen = "learned".equals(poseSource);
			poseSources.merge(poseSource, 1, Integer::sum);
			attackSources.merge(attacks(id, setOf.get(id), rec, notAttacks), 1, Integer::sum);
			out.put(id, rec);
		}

		writeBundle(out);
		writeKinds(notAttacks, casts);
		uncovered(out);
		writeReport(out, poseSources, attackSources, notAttacks, casts);

		System.out.println("records " + out.size() + " poses " + poseSources
			+ " attacks " + attackSources);
		System.out.println("not attacks " + notAttacks.size() + ", casts " + casts.size()
			+ ", style tables " + styleKinds.size());
	}

	// ------------------------------------------------------------------ cache

	private void loadCache() throws IOException
	{
		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index configs = store.getIndex(IndexType.CONFIGS);

			Archive itemArchive = configs.getArchive(ConfigType.ITEM.getId());
			ItemLoader itemLoader = new ItemLoader();
			for (FSFile file : itemArchive.getFiles(storage.loadArchive(itemArchive)).getFiles())
			{
				items.put(file.getFileId(), itemLoader.load(file.getFileId(), file.getContents()));
			}

			// The skeleton an animation moves is named at the head of its first
			// frame's archive. The player's is 0; anything else would twist the
			// follower into an NPC's shape, or into nothing at all.
			Archive seqArchive = configs.getArchive(ConfigType.SEQUENCE.getId());
			SequenceLoader seqLoader = new SequenceLoader().configureForRevision(seqArchive.getRevision());
			Index frames = store.getIndex(IndexType.ANIMATIONS);
			Map<Integer, Integer> archiveSkeleton = new HashMap<>();
			for (FSFile file : seqArchive.getFiles(storage.loadArchive(seqArchive)).getFiles())
			{
				SequenceDefinition seq;
				try
				{
					seq = seqLoader.load(file.getFileId(), file.getContents());
				}
				catch (RuntimeException unreadable)
				{
					continue;
				}
				int skeleton = -1;
				if (seq.animMayaID < 0 && seq.frameIDs != null && seq.frameIDs.length > 0)
				{
					int frameArchive = seq.frameIDs[0] >>> 16;
					Integer known = archiveSkeleton.get(frameArchive);
					if (known == null)
					{
						Archive archive = frames.getArchive(frameArchive);
						known = -1;
						if (archive != null)
						{
							byte[] head = archive.getFiles(storage.loadArchive(archive))
								.getFiles().iterator().next().getContents();
							known = ((head[0] & 0xff) << 8) | (head[1] & 0xff);
						}
						archiveSkeleton.put(frameArchive, known);
					}
					skeleton = known;
				}
				skeletonOf.put(file.getFileId(), skeleton);
			}

			// The combat tab's own table. Each row is one weapon category; each
			// button carries a tooltip such as "(Accurate)<br>(Stab)<br>(Attack
			// XP)", and the second bracket is the kind of blow that style deals.
			Archive rowArchive = configs.getArchive(ConfigType.DBROW.getId());
			DBRowLoader rowLoader = new DBRowLoader();
			Pattern kind = Pattern.compile("\\((Stab|Slash|Crush)\\)");
			for (FSFile file : rowArchive.getFiles(storage.loadArchive(rowArchive)).getFiles())
			{
				DBRowDefinition row = rowLoader.load(file.getFileId(), file.getContents());
				if (row.getTableId() != COMBAT_INTERFACE_TABLE)
				{
					continue;
				}
				Object[][] columns = row.getColumnValues();
				if (columns.length < 2 || columns[0] == null || columns[1] == null)
				{
					continue;
				}
				int category = (Integer) columns[0][0];
				int[] kinds = {-1, -1, -1, -1};
				Object[] buttons = columns[1];
				for (int i = 0; i + 2 < buttons.length; i += 4)
				{
					int button = (Integer) buttons[i];
					Matcher matcher = kind.matcher(String.valueOf(buttons[i + 2]));
					if (button >= 0 && button < kinds.length && matcher.find())
					{
						kinds[button] = Arrays.asList(KINDS).indexOf(matcher.group(1));
					}
				}
				styleKinds.put(category, kinds);
			}
		}
	}

	private boolean playable(int animationId)
	{
		Integer skeleton = skeletonOf.get(animationId);
		return animationId > 0 && skeleton != null && skeleton == 0;
	}

	private boolean isWeapon(int itemId)
	{
		ItemDefinition item = items.get(itemId);
		return item != null && item.wearPos1 == 3 && item.notedTemplate == -1
			&& item.placeholderTemplateId == -1;
	}

	private String name(int itemId)
	{
		if (itemId == UNARMED)
		{
			return "(unarmed)";
		}
		ItemDefinition item = items.get(itemId);
		return item == null ? "?" : item.name;
	}

	private String label(int itemId)
	{
		return name(itemId) + " (" + itemId + ")";
	}

	// ---------------------------------------------------------- gameval names

	private void loadAnimationNames()
	{
		for (Field field : net.runelite.api.gameval.AnimationID.class.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					animNames.computeIfAbsent(field.getInt(null), id -> new ArrayList<>())
						.add(field.getName());
				}
				catch (IllegalAccessException ignored)
				{
					// Public constants only.
				}
			}
		}
	}

	private String animName(int id)
	{
		List<String> names = animNames.get(id);
		return names == null ? "?" : String.join("/", names);
	}

	private boolean nameMatches(int id, Pattern pattern)
	{
		List<String> names = animNames.get(id);
		if (names == null)
		{
			return false;
		}
		for (String name : names)
		{
			if (pattern.matcher(name).find())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Blocks, defends and specials: animations a fighting player plays that
	 * are not their weapon's ordinary swing, so a learned "attack" that is one
	 * of these was filed wrongly. Built from Weapon Animation Replacer's defend
	 * and special slots and from Jagex's own names, less anything that IS some
	 * weapon's attack - DH_SWORD_UPDATE_BLOCK is the godswords' second slash.
	 *
	 * <p>A special slot holding what Jagex names an ordinary swing is left out
	 * too: the Dual Macauhuitl set's special is 10990 SULPHUR_BLADE_SLASH, the
	 * Sulphur blades' own swing, and guarding it would stop the blades ever
	 * swinging it once seen.
	 */
	private Set<Integer> notAttacks()
	{
		Pattern ordinarySwing = Pattern.compile("_(SLASH|STAB|CRUSH|ATTACK)$");
		Pattern special = Pattern.compile("SPEC");
		Set<Integer> warAttacks = new TreeSet<>();
		Set<Integer> out = new TreeSet<>();
		for (int[] set : warSets.values())
		{
			for (int slot : new int[]{ATTACK_STAB, ATTACK_SLASH, ATTACK_CRUSH,
				ATTACK_SLASH2, ATTACK_CRUSH2, ATTACK})
			{
				if (set[slot] > 0)
				{
					warAttacks.add(set[slot]);
				}
			}
			if (playable(set[DEFEND]))
			{
				out.add(set[DEFEND]);
			}
			int spec = set[ATTACK_SPEC];
			if (playable(spec) && nameMatches(spec, ordinarySwing) && !nameMatches(spec, special))
			{
				note("Special attacks not guarded: Jagex names them an ordinary swing",
					spec + " " + animName(spec));
			}
			else if (playable(spec))
			{
				out.add(spec);
			}
		}
		Pattern guard = Pattern.compile("BLOCK|DEFEND|DEFENCE|SPEC");
		for (int id : animNames.keySet())
		{
			if (playable(id) && nameMatches(id, guard))
			{
				out.add(id);
			}
		}
		for (int attack : warAttacks)
		{
			if (out.remove(attack))
			{
				note("Kept as attacks although named or slotted like a block or special",
					attack + " " + animName(attack));
			}
		}
		return out;
	}

	/**
	 * Spell casts, by Jagex's name: a word that starts or ends with CAST, so
	 * HUMAN_CASTSTRIKE_STAFF, ZAROS_VERTICAL_CASTING and HUMAN_SPELLCAST_GRASP,
	 * but not the fishing casts or anything at Castle Wars.
	 */
	private Set<Integer> casts()
	{
		Set<Integer> out = new TreeSet<>();
		for (int id : animNames.keySet())
		{
			if (!playable(id))
			{
				continue;
			}
			for (String name : animNames.get(id))
			{
				if (name.contains("FISHING"))
				{
					continue;
				}
				for (String word : name.split("_"))
				{
					if ((word.startsWith("CAST") && !word.startsWith("CASTLE")) || word.endsWith("CAST"))
					{
						out.add(id);
					}
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------- learned stances

	private Map<Integer, Rec> readStances(Path file) throws IOException
	{
		Map<Integer, Rec> out = new TreeMap<>();
		if (!Files.isRegularFile(file))
		{
			return out;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet())
			{
				out.put(Integer.parseInt(entry.getKey()),
					new Gson().fromJson(entry.getValue(), Rec.class));
			}
		}
		return out;
	}

	// --------------------------------------------- Weapon Animation Replacer

	private void loadWar() throws IOException
	{
		Path data = warDir.resolve("src/main/resources/com/weaponanimationreplacer/data.json");
		try (Reader reader = Files.newBufferedReader(data, StandardCharsets.UTF_8))
		{
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (JsonElement element : root.getAsJsonArray("animationSets"))
			{
				JsonObject set = element.getAsJsonObject();
				warSets.put(set.get("name").getAsString(), ints(set.getAsJsonArray("animations")));
			}
			// Kept in file order: the plugin takes the FIRST set that lists any
			// variant of a weapon, so the order is part of the data.
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("poseanims").entrySet())
			{
				warPoseanims.put(entry.getKey(), ints(entry.getValue().getAsJsonArray()));
			}
		}

		// The pose tuples the author saw on real players, kept as comments on
		// each putWeapon line: stand, rotate right, walk, walk back, shuffle
		// left, shuffle right, run, rotate left.
		Path tools = warDir.resolve(
			"src/test/java/com/weaponanimationreplacer/WeaponAnimationReplacerToolsPlugin.java");
		Pattern line = Pattern.compile("^\\s*putWeapon\\(itemIdToAnimationSet,\\s*(\\d+).*//(.*)$");
		Pattern tuple = Pattern.compile("\\[(-?\\d+(?:, -?\\d+){7})]");
		for (String text : Files.readAllLines(tools, StandardCharsets.UTF_8))
		{
			Matcher matcher = line.matcher(text);
			if (!matcher.find())
			{
				continue;
			}
			Matcher observed = tuple.matcher(matcher.group(2));
			int[] last = null;
			while (observed.find())
			{
				last = Arrays.stream(observed.group(1).split(", ")).mapToInt(Integer::parseInt).toArray();
			}
			if (last != null)
			{
				warObserved.put(Integer.parseInt(matcher.group(1)), last);
			}
		}

		// The author's own doubts, written beside the sets.
		Path sets = warDir.resolve("src/test/java/com/weaponanimationreplacer/AnimationSets.java");
		Pattern start = Pattern.compile("new AnimationSetBuilder\\(\"([^\"]+)\"\\)");
		Pattern doubt = Pattern.compile("//\\s*(.*(?:guess|not sure|unconfirmed).*)$",
			Pattern.CASE_INSENSITIVE);
		String current = null;
		for (String text : Files.readAllLines(sets, StandardCharsets.UTF_8))
		{
			Matcher begin = start.matcher(text);
			if (begin.find())
			{
				current = begin.group(1);
			}
			Matcher comment = doubt.matcher(text);
			if (current != null && comment.find())
			{
				warUnsure.merge(current, comment.group(1).trim(), (a, b) -> a + "; " + b);
			}
		}

		Path head = warDir.resolve(".git/HEAD");
		if (Files.isRegularFile(head))
		{
			String ref = new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
			if (ref.startsWith("ref: "))
			{
				Path refFile = warDir.resolve(".git").resolve(ref.substring(5));
				warVersion = Files.isRegularFile(refFile)
					? new String(Files.readAllBytes(refFile), StandardCharsets.UTF_8).trim()
					: ref.substring(5);
			}
			else
			{
				warVersion = ref;
			}
		}
	}

	private static int[] ints(JsonArray array)
	{
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = array.get(i).getAsInt();
		}
		return out;
	}

	/**
	 * The set each weapon gets, worked out exactly as Weapon Animation
	 * Replacer does at runtime: the first set, in its file's order, that lists
	 * any variant of the weapon.
	 */
	private Map<Integer, String> warSetPerItem()
	{
		Map<Integer, String> out = new TreeMap<>();
		Map<Integer, Set<String>> claims = new TreeMap<>();
		for (Map.Entry<String, int[]> entry : warPoseanims.entrySet())
		{
			if (!warSets.containsKey(entry.getKey()))
			{
				note("Weapon Animation Replacer names a set it does not define", entry.getKey());
				continue;
			}
			for (int listed : entry.getValue())
			{
				for (int variant : variations(listed))
				{
					out.putIfAbsent(variant, entry.getKey());
					claims.computeIfAbsent(variant, v -> new TreeSet<>()).add(entry.getKey());
				}
			}
		}
		for (Map.Entry<Integer, Set<String>> entry : claims.entrySet())
		{
			if (entry.getValue().size() > 1 && isWeapon(entry.getKey()))
			{
				note("Weapons two Weapon Animation Replacer sets claim (the first in its file is used)",
					label(entry.getKey()) + ": " + entry.getValue() + ", used "
						+ out.get(entry.getKey()));
			}
		}

		// Some sets are defined and named after a weapon but given no items,
		// so the plugin only offers them to pick by hand: Voidwaker, Arclight,
		// Soulreaper axe, Eclipse atlatl. A weapon whose name is exactly the
		// set's name gets it, with its variants, unless a listed set already
		// covers it.
		for (String set : warSets.keySet())
		{
			if (warPoseanims.containsKey(set))
			{
				continue;
			}
			for (Map.Entry<Integer, ItemDefinition> item : items.entrySet())
			{
				if (!isWeapon(item.getKey()) || !set.equalsIgnoreCase(item.getValue().name))
				{
					continue;
				}
				for (int variant : variations(item.getKey()))
				{
					if (isWeapon(variant) && out.putIfAbsent(variant, set) == null)
					{
						note("Weapons given a set by its name (the set lists no items of its own)",
							label(variant) + " [" + set + "]");
					}
				}
			}
		}
		return out;
	}

	private static Collection<Integer> variations(int itemId)
	{
		return ItemVariationMapping.getVariations(ItemVariationMapping.map(itemId));
	}

	/**
	 * An observed tuple for the weapon, or else for the plain weapon its
	 * variation group is filed under. Not any member of the group: the author's
	 * one sighting of a Holy scythe carries the Nightmare staff's poses, and
	 * taking whichever variant came first handed that to every scythe.
	 *
	 * <p>A tuple borrowed from the plain weapon gives way when the weapon's set
	 * and a learned member of the group agree on a different carry. The author
	 * saw Dharok's greataxe run at 824 before Jagex gave it 11466; the set and
	 * every learned greataxe say 11466, and the LMS greataxe should too. Where
	 * the learned members side with the tuple instead, as for the (bh) Zuriel's
	 * staff and the poisoned keris, the tuple stays.
	 */
	private int[] observedFor(int itemId, String set)
	{
		int[] exact = warObserved.get(itemId);
		if (exact != null)
		{
			return exact;
		}
		int[] borrowed = warObserved.get(ItemVariationMapping.map(itemId));
		if (borrowed == null || set == null)
		{
			return borrowed;
		}
		int[] a = warSets.get(set);
		if (borrowed[0] == a[STAND] && borrowed[2] == a[WALK] && borrowed[6] == a[RUN])
		{
			return borrowed;
		}
		for (int member : variations(itemId))
		{
			Rec mine = learnedPoses(member);
			if (mine != null && mine.idle == a[STAND] && mine.walk == a[WALK] && mine.run == a[RUN])
			{
				note("Pose tuples borrowed from the plain weapon passed over: its set and a learned "
						+ "weapon of the group agree on another carry",
					label(itemId) + ": borrowed " + borrowed[0] + "/" + borrowed[2] + "/" + borrowed[6]
						+ ", " + label(member) + " and [" + set + "] " + mine.carry());
				return null;
			}
		}
		return borrowed;
	}

	// ------------------------------------------------------------------ merge

	/**
	 * The poses learned for a weapon: its learned record when that has an
	 * idle, walk and run, else the previous bundle's seen record. The plugin
	 * saves a swing seen with a weapon carried as shipped as a record with no
	 * poses, which says nothing about how it is carried.
	 */
	private Rec learnedPoses(int id)
	{
		Rec mine = learned.get(id);
		return mine != null && mine.idle > 0 && mine.walk > 0 && mine.run > 0 ? mine : oldBundle.get(id);
	}

	/** @return where the poses came from, or null when nothing playable was found */
	private String poses(int id, String set, Rec rec)
	{
		List<String> names = new ArrayList<>();
		List<int[]> sources = new ArrayList<>();

		Rec mine = learnedPoses(id);
		if (mine != null)
		{
			names.add("learned");
			sources.add(mine.poses());
		}
		int[] seen = id == UNARMED ? null : observedFor(id, set);
		if (seen != null)
		{
			names.add("war-observed");
			sources.add(new int[]{seen[0], seen[2], seen[6], seen[3], seen[4], seen[5], seen[7], seen[1]});
		}
		if (set != null)
		{
			int[] a = warSets.get(set);
			names.add("war-set");
			sources.add(new int[]{a[STAND], a[WALK], a[RUN], a[WALK_BACKWARD],
				a[SHUFFLE_LEFT], a[SHUFFLE_RIGHT], a[ROTATE], a[ROTATE]});
		}

		int chosen = -1;
		for (int i = 0; i < sources.size(); i++)
		{
			int[] p = sources.get(i);
			if (playable(p[0]) && playable(p[1]) && playable(p[2]))
			{
				chosen = i;
				break;
			}
			note("Pose sources passed over: an idle, walk or run the player cannot play",
				label(id) + " " + names.get(i) + " " + p[0] + "/" + p[1] + "/" + p[2]);
		}
		if (chosen < 0)
		{
			return null;
		}

		int[] p = sources.get(chosen).clone();
		for (int field = 3; field < p.length; field++)
		{
			if (!playable(p[field]))
			{
				p[field] = 0;
			}
		}
		for (int i = chosen + 1; i < sources.size(); i++)
		{
			int[] other = sources.get(i);
			if (other[0] == p[0] && other[1] == p[1] && other[2] == p[2])
			{
				// Same carry from a later source: it may still know a turn or
				// side-step the winner never recorded.
				for (int field = 3; field < p.length; field++)
				{
					if (p[field] == 0 && playable(other[field]))
					{
						p[field] = other[field];
					}
				}
			}
			else if (playable(other[0]) && playable(other[1]) && playable(other[2]))
			{
				note("Carry disagreements (the first source is used)",
					label(id) + ": " + names.get(chosen) + " " + p[0] + "/" + p[1] + "/" + p[2]
						+ ", " + names.get(i) + " " + other[0] + "/" + other[1] + "/" + other[2]
						+ (set != null ? " [" + set + "]" : ""));
			}
		}
		rec.poses(p);
		return names.get(chosen);
	}

	/** @return where the attack came from */
	private String attacks(int id, String set, Rec rec, Set<Integer> notAttacks)
	{
		Rec mine = learned.get(id);
		if (mine == null)
		{
			mine = oldBundle.get(id);
		}
		int learnedAttack = mine == null ? 0 : mine.attack;

		if (id == UNARMED)
		{
			set = "Unarmed";
		}
		if (set != null)
		{
			int[] a = warSets.get(set);
			if (a[ATTACK] > 0)
			{
				// A single swing for every style: ranged and magic weapons, and
				// the few melee ones the author gave just one. Any stab, slash
				// or crush beside it was copied in from the set it started as.
				rec.attack = warAttack(id, set, a[ATTACK]);
			}
			else
			{
				int[] byType = {
					warAttack(id, set, a[ATTACK_STAB]),
					warAttack(id, set, a[ATTACK_SLASH] > 0 ? a[ATTACK_SLASH] : a[ATTACK_SLASH2]),
					warAttack(id, set, crush(a)),
				};
				if (byType[0] > 0 || byType[1] > 0 || byType[2] > 0)
				{
					rec.attackByType = byType;
					rec.attack = byType[favouredKind(id, byType)];
				}
			}
			if (rec.attack > 0)
			{
				if (learnedAttack > 0 && learnedAttack != rec.attack
					&& (rec.attackByType == null || !contains(rec.attackByType, learnedAttack)))
				{
					note("Learned attacks that Weapon Animation Replacer overrules",
						label(id) + ": learned " + learnedAttack + " " + animName(learnedAttack)
							+ (notAttacks.contains(learnedAttack) ? " (a block, defend or special)" : "")
							+ ", shipped " + rec.attack + " " + animName(rec.attack) + " [" + set + "]");
				}
				if (warUnsure.containsKey(set))
				{
					note("Weapons on a set its author marked as unsure",
						label(id) + " [" + set + "]: " + warUnsure.get(set));
				}
				if (rec.attackByType != null && rec.attackByType[2] == 419)
				{
					note("Staff bashes that become 393 when the follower carries a shield",
						label(id) + " [" + set + "]");
				}
				return "war";
			}
		}

		if (learnedAttack > 0)
		{
			if (notAttacks.contains(learnedAttack))
			{
				note("Learned attacks left out: a block, defend or special",
					label(id) + ": " + learnedAttack + " " + animName(learnedAttack));
			}
			else if (id != UNARMED && UNARMED_SWINGS.contains(learnedAttack))
			{
				note("Learned attacks left out: the unarmed punch or kick on a real weapon",
					label(id) + ": " + learnedAttack);
			}
			else if (!playable(learnedAttack))
			{
				note("Learned attacks left out: not playable on the player skeleton",
					label(id) + ": " + learnedAttack);
			}
			else
			{
				rec.attack = learnedAttack;
				return "learned";
			}
		}
		return "none";
	}

	/**
	 * A set's crush. The Zamorakian hasta's set keeps the one-handed chop
	 * (393) in its crush and the two-handed staff bash (419) in its second
	 * crush, labelled there "no offhand". The plugin turns that bash into the
	 * chop itself when the follower holds a shield (StanceLibrary.withShield),
	 * so the bash is the one that ships, as it does for the staff sets.
	 */
	private static int crush(int[] a)
	{
		if (a[ATTACK_CRUSH] == ONE_HANDED_CHOP && STAFF_BASHES.contains(a[ATTACK_CRUSH2]))
		{
			return a[ATTACK_CRUSH2];
		}
		return a[ATTACK_CRUSH] > 0 ? a[ATTACK_CRUSH] : a[ATTACK_CRUSH2];
	}

	/** A set's attack for one weapon, or 0 when it cannot be shipped. */
	private int warAttack(int id, String set, int animation)
	{
		if (animation <= 0)
		{
			return 0;
		}
		if (!playable(animation))
		{
			note("Weapon Animation Replacer attacks left out: not playable on the player skeleton",
				label(id) + " [" + set + "]: " + animation);
			return 0;
		}
		if (id != UNARMED && UNARMED_SWINGS.contains(animation))
		{
			note("Weapon Animation Replacer attacks left out: the unarmed punch or kick on a real weapon",
				label(id) + " [" + set + "]: " + animation + " " + animName(animation));
			return 0;
		}
		return animation;
	}

	/**
	 * The kind of blow a weapon's own stats favour, for its swing when the
	 * style is not known. The cache holds each weapon's stab, slash and crush
	 * bonuses; the highest is what the weapon is for. A weapon with no stats
	 * takes the first kind it has, in stab, slash, crush order.
	 */
	private int favouredKind(int id, int[] byType)
	{
		ItemDefinition item = items.get(id);
		int best = -1;
		int bestBonus = Integer.MIN_VALUE;
		for (int kind = 0; kind < byType.length; kind++)
		{
			if (byType[kind] <= 0)
			{
				continue;
			}
			int bonus = 0;
			if (item != null && item.params != null)
			{
				Object value = item.params.get(ATTACK_BONUS_PARAMS[kind]);
				bonus = value instanceof Integer ? (Integer) value : 0;
			}
			if (best < 0 || bonus > bestBonus)
			{
				best = kind;
				bestBonus = bonus;
			}
		}
		return best;
	}

	private static boolean contains(int[] values, int value)
	{
		for (int v : values)
		{
			if (v == value)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Weapons nothing covers, grouped the way the plugin inherits by name: a
	 * weapon whose plain name, or whose name less its metal, belongs to one on
	 * record is covered at runtime, so only the rest are listed. The name rules
	 * are StanceLibrary.baseName's and tierBaseName's.
	 */
	private void uncovered(Map<Integer, Rec> out)
	{
		Set<String> coveredBase = new java.util.HashSet<>();
		Set<String> coveredTier = new java.util.HashSet<>();
		for (int id : out.keySet())
		{
			String base = baseName(name(id));
			String tier = tierName(name(id));
			if (base != null)
			{
				coveredBase.add(base);
			}
			if (tier != null)
			{
				coveredTier.add(tier);
			}
		}
		Map<String, List<Integer>> groups = new TreeMap<>();
		for (int id : new TreeSet<>(items.keySet()))
		{
			String base = baseName(name(id));
			if (!isWeapon(id) || base == null || out.containsKey(id) || coveredBase.contains(base)
				|| coveredTier.contains(tierName(name(id))))
			{
				continue;
			}
			groups.computeIfAbsent(base, b -> new ArrayList<>()).add(id);
		}
		for (List<Integer> ids : groups.values())
		{
			note("Weapon names nothing covers, even by name or metal (they stand unarmed)",
				name(ids.get(0)) + (ids.size() > 1 ? " and " + (ids.size() - 1) + " more" : "")
					+ " " + ids.subList(0, Math.min(ids.size(), 6)));
		}
	}

	private static String baseName(String name)
	{
		if (name == null || name.trim().isEmpty() || "null".equalsIgnoreCase(name))
		{
			return null;
		}
		String base = name.toLowerCase(java.util.Locale.ROOT).trim();
		String previous;
		do
		{
			previous = base;
			base = base.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
		}
		while (!base.equals(previous));
		base = base.replaceAll("\\s+\\d+$", "").trim();
		return base.isEmpty() ? null : base;
	}

	/** Null for a weapon with no plain metal tier, which never inherits across metals. */
	private static String tierName(String name)
	{
		String base = baseName(name);
		if (base == null)
		{
			return null;
		}
		Matcher matcher = Pattern.compile("^(bronze|iron|steel|black|white|mithril|adamant|rune|runite)\\s+")
			.matcher(base);
		return matcher.find() && !base.substring(matcher.end()).trim().isEmpty()
			? base.substring(matcher.end()).trim() : null;
	}

	// ----------------------------------------------------------------- output

	private void writeBundle(Map<Integer, Rec> out) throws IOException
	{
		StringBuilder json = new StringBuilder("{\n");
		boolean first = true;
		for (Map.Entry<Integer, Rec> entry : out.entrySet())
		{
			Rec r = entry.getValue();
			if (!first)
			{
				json.append(",\n");
			}
			first = false;
			json.append('"').append(entry.getKey()).append("\":{")
				.append("\"idle\":").append(r.idle)
				.append(",\"walk\":").append(r.walk)
				.append(",\"run\":").append(r.run)
				.append(",\"walkBack\":").append(r.walkBack)
				.append(",\"walkLeft\":").append(r.walkLeft)
				.append(",\"walkRight\":").append(r.walkRight)
				.append(",\"turnLeft\":").append(r.turnLeft)
				.append(",\"turnRight\":").append(r.turnRight);
			if (r.seen)
			{
				json.append(",\"seen\":true");
			}
			if (r.attack > 0)
			{
				json.append(",\"attack\":").append(r.attack);
			}
			if (r.attackByType != null)
			{
				json.append(",\"attackByType\":[").append(r.attackByType[0]).append(',')
					.append(r.attackByType[1]).append(',').append(r.attackByType[2]).append(']');
			}
			json.append('}');
		}
		json.append("\n}\n");
		Files.write(bundleFile, json.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void writeKinds(Set<Integer> notAttacks, Set<Integer> casts) throws IOException
	{
		StringBuilder json = new StringBuilder("{\n");
		json.append("\"notAttacks\":").append(list(notAttacks)).append(",\n");
		json.append("\"casts\":").append(list(casts)).append(",\n");
		json.append("\"styleKinds\":{");
		boolean first = true;
		for (Map.Entry<Integer, int[]> entry : styleKinds.entrySet())
		{
			json.append(first ? "\n" : ",\n");
			first = false;
			int[] k = entry.getValue();
			json.append('"').append(entry.getKey()).append("\":[")
				.append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(',').append(k[3])
				.append(']');
		}
		json.append("\n}\n}\n");
		Files.write(kindsFile, json.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String list(Collection<Integer> ids)
	{
		StringBuilder out = new StringBuilder("[");
		int n = 0;
		for (int id : ids)
		{
			out.append(n == 0 ? "" : n % 20 == 0 ? ",\n" : ",").append(id);
			n++;
		}
		return out.append(']').toString();
	}

	private void note(String section, String line)
	{
		List<String> lines = report.computeIfAbsent(section, s -> new ArrayList<>());
		if (!lines.contains(line))
		{
			lines.add(line);
		}
	}

	private void writeReport(Map<Integer, Rec> out, Map<String, Integer> poseSources,
		Map<String, Integer> attackSources, Set<Integer> notAttacks, Set<Integer> casts)
		throws IOException
	{
		int withType = 0;
		for (Rec r : out.values())
		{
			if (r.attackByType != null)
			{
				withType++;
			}
		}

		StringBuilder md = new StringBuilder();
		md.append("# Weapon stance sources\n\n")
			.append("Written by `tools/cache-dumper` (`gradlew runStanceMerge`) on ")
			.append(LocalDate.now()).append(". Do not edit by hand: rerun the tool.\n\n")
			.append("The shipped stance library, `src/main/resources/com/follower/stances.json`, is built ")
			.append("from three sources and nothing else:\n\n")
			.append("1. Stances this plugin learned by watching players (the learned `stances.json` ")
			.append("and the previous bundle). They win for idle, walk, run and the turns.\n")
			.append("2. Weapon Animation Replacer by geheur, BSD-2-Clause (see THIRD-PARTY-NOTICES.md), ")
			.append("commit `").append(warVersion).append("`: its observed pose tuples, then its ")
			.append("animation sets, reaching every variant through RuneLite's ItemVariationMapping. ")
			.append("Its stab, slash and crush swings win over learned attacks.\n")
			.append("3. The game cache and RuneLite's gameval names: every id must exist and be on the ")
			.append("player skeleton, the combat tab's table says which blow each style button deals, ")
			.append("and Jagex's names mark the blocks, specials and spell casts.\n\n")
			.append("## Totals\n\n")
			.append("- Records: ").append(out.size()).append('\n')
			.append("- Poses from: ").append(poseSources).append('\n')
			.append("- Attacks from: ").append(attackSources).append('\n')
			.append("- Records with a stab, slash and crush swing: ").append(withType).append('\n')
			.append("- Not-an-attack ids (blocks, defends, specials): ").append(notAttacks.size()).append('\n')
			.append("- Spell cast ids: ").append(casts.size()).append('\n')
			.append("- Combat tab style tables: ").append(styleKinds.size()).append("\n\n")
			.append("## Needs eyes\n\n")
			.append("Everything below is a disagreement between sources or a guess one of them admits to. ")
			.append("The tool already picked an answer for each; these are the ones worth checking in game.\n");

		for (Map.Entry<String, List<String>> section : report.entrySet())
		{
			md.append("\n### ").append(section.getKey()).append(" (")
				.append(section.getValue().size()).append(")\n\n");
			for (String line : section.getValue())
			{
				md.append("- ").append(line).append('\n');
			}
		}
		Files.write(reportFile, md.toString().getBytes(StandardCharsets.UTF_8));
	}
}
