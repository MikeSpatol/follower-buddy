package com.follower.tools;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.SpotAnimDefinition;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.definitions.loaders.SpotAnimLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Builds the Advanced posing window's list of poses from the game cache and
 * RuneLite's gameval names, and checks a shipped list against the cache.
 *
 * <p>Every animation whose frames move the player's skeleton is a pose the
 * follower can strike; anything else would twist it into a creature's shape,
 * or into nothing. For each the cache says whether it loops, how long its last
 * frame is held, and what it puts in each hand. The names come in three tiers:
 * <ol>
 * <li>A: written by hand in {@code tools/cache-dumper/poses/names.tsv}: the
 * emotes, the skill capes, reading, sitting and the rest worth offering
 * first.</li>
 * <li>B: built from Jagex's own animation names - HUMAN_, EMOTE_, CHAIR_, the
 * skill capes and the skilling prefixes - through the words in
 * {@code poses/words.tsv}. The metal tiers of one tool and the male and female
 * copies of one move are merged into a single row; the copies go to tier C.</li>
 * <li>C: everything else on the skeleton, quest scenes and creatures in human
 * shape among them, tidied the same way, and shown only under Show all.</li>
 * </ol>
 *
 * <p>A pose is held when the game loops it, or when Jagex names it a stance (a
 * READY or an IDLE, which the game loops as the standing pose), unless its
 * last frame is held for longer than {@link #LONG_LAST_FRAME}: the deaths and
 * knockdowns, which lie still for minutes and are played once. A name that
 * moves between two stances (a START, an INTO, a TO) is no stance itself: the
 * swim-to-idle pair looped with a jump of 136 units at every wrap.
 *
 * <p>A graphic is paired with a pose only where Jagex's names pair them, or
 * the cache does by numbering the graphic's own animation just after the
 * pose, from the table in {@link #GRAPHICS}. Each pair is checked by name here,
 * and a cache pair by the cache too, so a renumbering cannot pair the wrong
 * two.
 *
 * <p>Writes {@code src/main/resources/com/follower/poses.tsv}.
 *
 * <pre>
 *   gradlew runPoseCatalog [--args="--cache &lt;dir&gt;"]
 *   gradlew runPoseCatalog --args="--check"      the shipped list against the cache
 * </pre>
 */
public class PoseCatalog
{
	/** The player's skeleton, named at the head of each frame archive. */
	private static final int PLAYER_SKELETON = 0;

	/** A last frame held this long, in 20 ms client cycles, is a body left lying: a minute. */
	static final int LONG_LAST_FRAME = 3000;

	/** The window's groups, in the order its dropdown lists them. */
	static final String[] GROUPS = {
		"Emotes", "Skill capes", "Reading and writing", "Sitting and resting", "Eating and drinking",
		"Music and play", "Skilling", "Magic and prayer", "Looking around", "Combat",
		"Standing and walking", "Climbing and agility", "Falls and deaths", "Other"};

	/**
	 * Tiers B and C sorted into groups by Jagex's name, first match wins. Tier
	 * A's groups are written beside its names.
	 */
	private static final String[][] GROUP_PATTERNS = {
		{"Emotes", "(^|_)EMOTE(_|$)|^BDAY17_|_CRABDANCE"},
		{"Skill capes", "SKILLCAPE|SKILL_CAPE|MAX_CAPE"},
		{"Falls and deaths", "DEATH|(^|_)DIE(_|$)|DYING|(^|_)DEAD(_|$)|KNOCK|(^|_)STUN|FALL|COLLAPSE|FLYBACK"
			+ "|UNCONSCIOUS|TACKLED|BLOWN"},
		{"Sitting and resting", "(^|_)CHAIR|SIT(_|$|TING|DOWN)|(^|_)SIT_|STOOL|THRONE|(?<!WORK)BENCH|SLEEP|SNOOZE"
			+ "|INBED|OUTBED|KNEEL|(^|_)LEAN(_|$)"},
		// Not a walk or a run that carries a book or a scroll: a staff's book
		// stance and the clue scroll walk are ways of walking.
		{"Reading and writing", "^(?!.*((^|_)WALK|(^|_)RUN(_|$)|RUNNING|READY))"
			+ ".*(READ(?!Y)|BOOK(?!CASE)|SCROLL|WRIT|MAPPING|CHARTING|DOSSIER|PREACH|LECTERN|STUDY)"},
		{"Eating and drinking", "(^|_)EAT|DRINK|(^|_)TEA(_|$)|TEACUP|KEG|TANKARD|(^|_)RUM(_|$)|BEER|WINE"},
		{"Skilling", "WOODCUT|WOODCRAFT|CHOP|FORESTRY|FELLING|MINING|PICKAXE|FISH|HARPOON|NET(_|$)|LOBSTER"
			+ "|COOK|SMITH|ANVIL|FURNACE|CRAFT|CUTTING|GLASSBLOW|FLETCH|STRINGING|HERB|POTION|FARMING"
			+ "|PRUN|RAKE|(^|_)DIG(_|$)|SPADE|CREATEFIRE|FIREMAKING|CAMPFIRE|HUNTING|LAYTRAP|POH_BUILD"
			+ "|THIEV|PICKPOCKET|PICKLOCK|SAILING|SHEAR|CHURN|MILK|POTTERY|SPINNING|CANOEING|TRAWLER"
			+ "|BREWING|BIRDHOUSE|COMPOST|SALVAGE|HAMMER_HIT|SEED|WATERING|PICKING|CLAY|APPLECRUSH|GRAPE"},
		{"Magic and prayer", "CAST|SPELL|TELEPORT|(^|_)TELE(_|$)|MAGICTRAINING|MAGIC_TABLET|PRAY|ALTAR|ENCHANT"
			+ "|ALCH|OFFERING|SACRIFICE|CRYSTALBALL|SCRYING"},
		{"Climbing and agility", "CLIMB|JUMP|SWING|BALANCE|SQUEEZE|CRAWL|VAULT|SWIM|LEDGE|ROPE|STEPPING"
			+ "|AGILITY|AGILTY|SLIDE|TOBOGGAN|CARPET|MONKEYBARS|HURDLE|WADING|ZIPLINE|ROOFTOP|STILE"},
		{"Combat", "ATTACK|SLASH|STAB|CRUSH|BLOCK|DEFEND|(^|_)DEF(_|$)|DEFENCE|SPEC(_|$|IAL)|PUNCH|KICK"
			+ "|(^|_)FIRE(_|$)|SHOOT|(^|_)THROW|SMASH|BASH|LUNGE|SWIPE|(^|_)HIT(_|$)|PUMMEL|POUND|SPIKE"
			+ "|HACK|SWEEP|PARRY|JAB"},
		{"Standing and walking", "READY|(^|_)WALK|(^|_)RUN(_|$)|RUNNING|IDLE|TURN|SIDESTEP|SPRINT"},
		{"Looking around", "LOOK|TELESCOPE|TELECOPE|SEARCH|PEER|LISTEN|WHISTLE"},
		{"Music and play", "DANCE|DANCING|JUGGL|YOYO|PLAY_|PLAYHORSEY|FLUTE|LYRE|HORN|MARIONETTE|TOY"},
	};

	/**
	 * The name prefixes of tier B: the player's own moves. The rest of the
	 * skeleton's animations are quests, cutscenes and creatures in human shape.
	 */
	private static final Pattern TIER_B = Pattern.compile(
		"^(HUMAN|EMOTE|CHAIR|SKILLCAPE|SKILLCAPES|SKILL|MAX|FORESTRY|SAILING|AGILITY|AGILITYARENA"
			+ "|FARMING|HUNTING|PICKAXE|PRUNING|STRINGING|PICKING|BREWING|MIXING|BIRDHOUSE|CANOEING|SPADE"
			+ "|MILKIT|MILK|GLASSBLOWING|ULTRACOMPOST|POTTERYOVEN|ROOFTOPS|ZIPLINE|YOYO|SITTING|READING"
			+ "|SITUPS|SITUP|PUSHUPS|PUSHUP|STARJUMP|POH)_");

	/**
	 * Graphics paired with a pose: animation, its name, graphic, its name.
	 * Jagex's names pair all but one, and most also sit beside each other in
	 * the cache, the graphic's own animation numbered just after the pose. The
	 * one the names do not pair, the Trick emote's bats, is paired by the cache
	 * alone, which {@link #CACHE_PAIRED} lists and {@link #graphics()} checks.
	 * A pairing is left out where neither settles it: the Premier Club shield
	 * has a bronze, a silver and a gold graphic, and Hitpoints has a man's and
	 * a woman's (the man's is taken). The plugin draws each at the feet except
	 * the page turns of 3140 and 3141, which are built to sit up on the body
	 * and are lifted by its FilmSet.GRAPHIC_HEIGHTS; a new pair built like
	 * them needs its height there too.
	 */
	static final Object[][] GRAPHICS = {
		{4276, "EMOTE_LIGHTBULB", 712, "EMOTE_LIGHTBULB_SPOT"},
		{12072, "EMOTE_LIGHTBULB_LOOP", 712, "EMOTE_LIGHTBULB_SPOT"},
		{4278, "EMOTE_STAMPFEET", 713, "EMOTE_DUSTSTAMP_SPOT"},
		{12080, "EMOTE_STAMPFEET_LOOP", 3230, "EMOTE_DUSTSTAMP_SPOT_LOOP"},
		{4751, "EMOTE_AIR_GUITAR", 1239, "AIR_GUITAR_SPOTANIM"},
		{1708, "HW07_ARM_FROM_THE_GROUND_EMOTE", 320, "HW07_ARM_FROM_THE_GROUND_SPOTANIM"},
		{10503, "EMOTE_TRICK", 2570, "HW23_EMOTE_BAT_SPOTANIM"},
		{10031, "EMOTE_PARTY", 2365, "FX_EMOTE_PARTY01_ACTIVE"},
		{12065, "EMOTE_PARTY_LOOP", 2365, "FX_EMOTE_PARTY01_ACTIVE"},
		{5313, "TRAIL_YAWN_EMOTE", 967, "TRAIL_YAWN_SPOTANIM"},
		{3140, "HUMAN_READBOOK_TURNRIGHT", 548, "READBOOK_SPOTANIM_TURNRIGHT"},
		{3141, "HUMAN_READBOOK_TURNLEFT", 549, "READBOOK_SPOTANIM_TURNLEFT"},
		{7529, "HUMAN_MAKE_WINE", 47, "COOKING_MAKE_WINE_SPOTANIM"},
		{4959, "SKILL_CAPE_ATTACK", 823, "SKILLCAPE_ATTACK_SPOTANIM"},
		{4961, "SKILL_CAPE_DEFEND", 824, "SKILLCAPE_DEFEND_SPOTANIM"},
		{4963, "SKILLCAPE_FARMING", 825, "SKILLCAPE_FARMING_SPOTANIM"},
		{4965, "SKILL_CAPE_THIEVING", 826, "SKILLCAPE_THIEVING_SPOTANIM"},
		{4967, "SKILL_CAPE_SLAYER", 827, "SKILLCAPE_SLAYER_SPOTANIM"},
		{4981, "SKILLCAPES_HUMAN_STRENGTH", 828, "SKILLCAPES_STRENGTH"},
		{4979, "SKILLCAPES_HUMAN_PRAYER", 829, "SKILLCAPES_PRAYER"},
		{4977, "SKILLCAPES_HUMAN_AGILITY", 830, "SKILLCAPES_AGILITY"},
		{4975, "SKILLCAPES_HUMAN_FIREMAKING", 831, "SKILLCAPES_FIREMAKING"},
		{4973, "SKILLCAPES_HUMAN_RANGE", 832, "SKILLCAPES_RANGE"},
		{4971, "SKILLCAPES_HUMAN_HITPOINTS", 833, "SKILLCAPES_HITPOINTS"},
		{4969, "SKILLCAPES_HUMAN_HERBLORE", 835, "SKILLCAPES_HERBLORE"},
		{4937, "SKILLCAPES_PLAYER_FLETCHING_BOW", 812, "SKILLCAPES_FLETCHING_BOW_SPOTANIM"},
		{4939, "SKILLCAPES_PLAYER_MAGIC", 813, "SKILLCAPES_MAGIC_SPOTANIM"},
		{4941, "SKILLCAPES_PLAYER_MINING", 814, "SKILLCAPES_MINING_SPOTANIM"},
		{4943, "SKILLCAPES_PLAYER_SMITHING", 815, "SKILLCAPES_SMITHING_SPOTANIM"},
		{4945, "SKILLCAPES_PLAYER_QUEST_CAPE", 816, "SKILLCAPES_QUEST_CAPE_SPOTANIM"},
		{4947, "SKILLCAPES_PLAYER_RUNECRAFTING", 817, "SKILLCAPES_RUNECRAFTING_SPOTANIM"},
		{4949, "SKILLCAPES_CRAFTING_PLAYER_ANIM", 818, "SKILLCAPES_CRAFTING_SPOTANIM"},
		{4951, "SKILLCAPES_FISHING_PLAYER_ANIM", 819, "SKILLCAPES_FISHING_SPOTANIM"},
		{4953, "SKILLCAPES_CONSTRUCTION_PLAYER_ANIM", 820, "SKILLCAPES_CONSTRUCTION_SPOTANIM"},
		{4955, "SKILLCAPES_COOKING_PLAYER_ANIM", 821, "SKILLCAPES_COOKING_SPOTANIM"},
		{4957, "SKILLCAPES_WOODCUTTING_PLAYER_ANIM", 822, "SKILLCAPES_WOODCUTTING_SPOTANIM"},
		{5158, "SKILLCAPES_HUMAN_HUNTING", 907, "SKILLCAPES_HUNTING"},
		{13074, "SKILLCAPES_SAILING_PLAYER_ANIM", 3473, "SKILLCAPES_SAILING_SPOTANIM"},
		{13077, "HUMAN_SKILLCAPE_SAILING", 3560, "SKILLCAPE_SAILING_EMOTE_SPOTANIM"},
		{7121, "MAX_CAPE_PLAYER_ANIM", 1286, "MAX_CAPE"},
		{2709, "DIARY_EMOTE_PLAYERANIM", 323, "SKILLCAPE_DIARY_SPOTANIM"},
	};

	/**
	 * The pairs above that rest on the cache rather than the names: the
	 * graphic's own animation is the one numbered just after the pose. EMOTE_TRICK
	 * is 10503, and the bats' HW23_EMOTE_BAT_SPOTANIM plays 10504.
	 */
	static final List<Integer> CACHE_PAIRED = Arrays.asList(10503);

	private static final String HEADER = "# The Advanced posing window's poses, built by tools/cache-dumper"
		+ " (gradlew runPoseCatalog) from the game cache and RuneLite's gameval names.\n"
		+ "# Every id moves the player's skeleton. play: hold loops until let go, once plays through."
		+ " left/right: what the pose shows in that hand, keep (its own), empty, or an item id.\n"
		+ "# graphic: a spotanim Jagex's names or the cache pair with it, or 0. tier: A named by hand, B named from"
		+ " the game's names, C shown under Show all. restart: the cache's frame step, -1 for none."
		+ " last: the last frame's length in client cycles.\n";

	static final String COLUMNS = "id\tname\tgroup\tplay\tleft\tright\tgraphic\ttier\trestart\tlast";

	private Path cacheDir = Paths.get(System.getProperty("user.home"),
		".runelite", "jagexcache", "oldschool", "LIVE");
	private final Path namesFile = Paths.get("tools/cache-dumper/poses/names.tsv");
	private final Path wordsFile = Paths.get("tools/cache-dumper/poses/words.tsv");
	private final Path outFile = Paths.get("src/main/resources/com/follower/poses.tsv");

	/** What the cache says of one animation. */
	static final class Seq
	{
		int skeleton = -1;
		int restart;
		int last;
		/** As stored: -1 keeps the hand, 0 empties it, 512 and up is an item plus 512. */
		int left;
		int right;
	}

	/** One line of the list. */
	static final class Row
	{
		int id;
		String name;
		String group;
		boolean hold;
		String left;
		String right;
		int graphic;
		char tier;
		int restart;
		int last;
		/** The gameval name the row was named from, or null. */
		String gameval;
		/** The name with its metal, gender and technical words left out, for merging. */
		String key;
	}

	/** A word of a Jagex name: how it reads, and what kind of word it is. */
	static final class Word
	{
		final String kind;
		final String text;

		Word(String kind, String text)
		{
			this.kind = kind;
			this.text = text;
		}
	}

	private final Map<Integer, Seq> seqs = new TreeMap<>();
	private final Map<Integer, ItemDefinition> items = new HashMap<>();
	private final Map<Integer, SpotAnimDefinition> spotanims = new HashMap<>();
	private final Map<Integer, List<String>> animNames = gameval(net.runelite.api.gameval.AnimationID.class);
	private final Map<Integer, List<String>> spotNames = gameval(net.runelite.api.gameval.SpotanimID.class);
	private final Map<String, Word> words = new HashMap<>();

	public static void main(String[] args) throws IOException
	{
		PoseCatalog catalog = new PoseCatalog();
		boolean check = false;
		for (int i = 0; i < args.length; i++)
		{
			switch (args[i])
			{
				case "--check":
					check = true;
					break;
				case "--cache":
					catalog.cacheDir = Paths.get(args[++i]);
					break;
				default:
					throw new IllegalArgumentException("Unknown option " + args[i]);
			}
		}
		catalog.loadCache();
		if (check)
		{
			List<String> problems = catalog.check(Files.readAllLines(catalog.outFile, StandardCharsets.UTF_8));
			problems.forEach(System.out::println);
			System.out.println(problems.isEmpty() ? "poses.tsv agrees with the cache" : problems.size() + " problems");
			System.exit(problems.isEmpty() ? 0 : 1);
		}
		catalog.loadWords();
		List<Row> rows = catalog.build();
		catalog.write(rows);
		catalog.summarise(rows);
	}

	// ------------------------------------------------------------------ cache

	void loadCache() throws IOException
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

			Archive spotArchive = configs.getArchive(ConfigType.SPOTANIM.getId());
			SpotAnimLoader spotLoader = new SpotAnimLoader();
			for (FSFile file : spotArchive.getFiles(storage.loadArchive(spotArchive)).getFiles())
			{
				spotanims.put(file.getFileId(), spotLoader.load(file.getFileId(), file.getContents()));
			}

			// The skeleton an animation moves is named at the head of its first
			// frame's archive. A maya animation has no frames of that kind and
			// is left out with the rest that are not the player's.
			Archive seqArchive = configs.getArchive(ConfigType.SEQUENCE.getId());
			SequenceLoader seqLoader = new SequenceLoader().configureForRevision(seqArchive.getRevision());
			Index frames = store.getIndex(IndexType.ANIMATIONS);
			Map<Integer, Integer> archiveSkeleton = new HashMap<>();
			for (FSFile file : seqArchive.getFiles(storage.loadArchive(seqArchive)).getFiles())
			{
				SequenceDefinition def;
				try
				{
					def = seqLoader.load(file.getFileId(), file.getContents());
				}
				catch (RuntimeException unreadable)
				{
					continue;
				}
				Seq seq = new Seq();
				if (def.animMayaID < 0 && def.frameIDs != null && def.frameIDs.length > 0)
				{
					int frameArchive = def.frameIDs[0] >>> 16;
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
					seq.skeleton = known;
				}
				seq.restart = def.frameStep;
				int[] lengths = def.frameLengths;
				seq.last = lengths == null || lengths.length == 0 ? 0 : lengths[lengths.length - 1];
				seq.left = def.leftHandItem;
				seq.right = def.rightHandItem;
				seqs.put(file.getFileId(), seq);
			}
		}
	}

	private static Map<Integer, List<String>> gameval(Class<?> type)
	{
		Map<Integer, List<String>> out = new HashMap<>();
		for (Field field : type.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					out.computeIfAbsent(field.getInt(null), id -> new ArrayList<>()).add(field.getName());
				}
				catch (IllegalAccessException ignored)
				{
					// Public constants only.
				}
			}
		}
		return out;
	}

	private boolean onPlayerSkeleton(int id)
	{
		Seq seq = seqs.get(id);
		return seq != null && seq.skeleton == PLAYER_SKELETON;
	}

	/** Whether the game loops it, as a loop or as a standing pose. */
	static boolean holds(int restart, int last, List<String> names)
	{
		if (last >= LONG_LAST_FRAME)
		{
			return false;
		}
		if (restart >= 0)
		{
			return true;
		}
		for (String name : names)
		{
			if (isTransition(name))
			{
				continue;
			}
			for (String word : name.split("_"))
			{
				if (word.equals("READY") || word.equals("IDLE"))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A clip from one stance into another, named for the move rather than the
	 * stance: a START or an INTO, or a TO that leaves a stance or arrives at
	 * one. A TO that does both is a round trip (READY_TO_CRAWL ... CRAWL_TO_READY)
	 * and ends where it began, and a TO between two other words is no move at
	 * all (BENT_TO_LISTEN_AT_DOOR_IDLE).
	 */
	static boolean isTransition(String name)
	{
		String[] words = name.split("_");
		boolean leaves = false;
		boolean arrives = false;
		for (int i = 0; i < words.length; i++)
		{
			if (words[i].equals("START") || words[i].equals("INTO"))
			{
				return true;
			}
			if (words[i].equals("TO"))
			{
				leaves |= i > 0 && isStanceWord(words[i - 1]);
				arrives |= i + 1 < words.length && isStanceWord(words[i + 1]);
			}
		}
		return leaves != arrives;
	}

	private static boolean isStanceWord(String word)
	{
		return word.equals("READY") || word.equals("IDLE");
	}

	/**
	 * A hand as the window shows it. An item the game has no worn model for is
	 * drawn as nothing, so it is written as an empty hand.
	 */
	private String hand(int stored)
	{
		if (stored < 0)
		{
			return "keep";
		}
		if (stored < 512)
		{
			return "empty";
		}
		ItemDefinition item = items.get(stored - 512);
		return item != null && (item.maleModel0 >= 0 || item.femaleModel0 >= 0)
			? String.valueOf(stored - 512) : "empty";
	}

	// ------------------------------------------------------------------ names

	private void loadWords() throws IOException
	{
		for (String line : Files.readAllLines(wordsFile, StandardCharsets.UTF_8))
		{
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			String[] cells = line.split("\t", -1);
			words.put(cells[0], new Word(cells[1], cells.length > 2 ? cells[2] : ""));
		}
	}

	private Word word(String token)
	{
		return words.get(token);
	}

	/** The gameval name to read: a tier B one when there is one. */
	private String gamevalName(int id)
	{
		List<String> names = animNames.get(id);
		if (names == null || names.isEmpty())
		{
			return null;
		}
		for (String name : names)
		{
			if (TIER_B.matcher(name).find())
			{
				return name;
			}
		}
		return names.get(0);
	}

	/**
	 * The words of a Jagex name, each as long as the dictionary knows it:
	 * GET_UP is one word, as is WALK_F. A token the dictionary does not know
	 * is split at its letter and digit boundaries, so CHAIR1 is a chair and a
	 * 1, and SLOW01 is slow and a 1.
	 */
	private List<String> units(String gameval)
	{
		List<String> tokens = new ArrayList<>();
		for (String token : gameval.split("_"))
		{
			if (token.isEmpty())
			{
				continue;
			}
			if (words.containsKey(token) || !token.matches(".*[A-Z].*\\d.*|.*\\d.*[A-Z].*"))
			{
				tokens.add(token);
				continue;
			}
			tokens.addAll(Arrays.asList(token.split("(?<=[A-Z])(?=\\d)|(?<=\\d)(?=[A-Z])")));
		}
		List<String> out = new ArrayList<>();
		for (int i = 0; i < tokens.size(); )
		{
			int length = Math.min(3, tokens.size() - i);
			while (length > 1 && !words.containsKey(String.join("_", tokens.subList(i, i + length))))
			{
				length--;
			}
			out.add(String.join("_", tokens.subList(i, i + length)));
			i += length;
		}
		return out;
	}

	/**
	 * The name with its metals, genders and technical words left out, so the
	 * bronze and the dragon axe's chop, or a man's and a woman's read, are one.
	 */
	private String key(String gameval)
	{
		StringBuilder key = new StringBuilder();
		for (String unit : units(gameval))
		{
			Word word = word(unit);
			if (word != null && (word.kind.equals("metal") || word.kind.equals("gender")
				|| word.kind.equals("drop")))
			{
				continue;
			}
			key.append(unit).append('_');
		}
		return key.toString();
	}

	/**
	 * A Jagex name in plain words. A verb word leads, as in "Chop a tree", and
	 * the rest follow it in brackets; without one the words are read in
	 * order. A pose word - idle - is only said when there is no verb, since
	 * "Sit" says it already. A quest's or an area's code goes in brackets at
	 * the end.
	 *
	 * @param merged whether the row speaks for its metal and gender copies too,
	 *               and so leaves those words out
	 */
	String readable(String gameval, boolean merged)
	{
		String verb = null;
		List<String> rest = new ArrayList<>();
		List<String> poses = new ArrayList<>();
		List<String> places = new ArrayList<>();
		for (String unit : units(gameval))
		{
			Word word = word(unit);
			if (word == null)
			{
				// A version number - the 01 of SLOW01 - says nothing; two rows
				// it told apart are numbered at the end.
				if (!unit.matches("0\\d+"))
				{
					rest.add(unit.toLowerCase(Locale.ROOT).replace('_', ' '));
				}
				continue;
			}
			switch (word.kind)
			{
				case "drop":
					break;
				case "metal":
				case "gender":
					if (!merged && !word.text.isEmpty())
					{
						rest.add(word.text);
					}
					break;
				case "verb":
					if (verb == null)
					{
						verb = word.text;
					}
					else
					{
						rest.add(word.text.toLowerCase(Locale.ROOT));
					}
					break;
				case "pose":
					poses.add(word.text);
					rest.add(word.text);
					break;
				case "place":
					places.add(word.text);
					break;
				default:
					if (!word.text.isEmpty())
					{
						rest.add(word.text);
					}
					break;
			}
		}
		String text;
		if (verb != null)
		{
			rest.removeAll(poses);
			text = verb + (rest.isEmpty() ? "" : " (" + String.join(" ", rest) + ")");
		}
		else
		{
			text = String.join(" ", rest);
		}
		if (!places.isEmpty())
		{
			text = text + " (" + String.join(", ", places) + ")";
		}
		text = text.trim().replaceAll(" +", " ");
		return text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	static String group(String gameval)
	{
		if (gameval == null)
		{
			return "Other";
		}
		for (String[] pattern : GROUP_PATTERNS)
		{
			if (Pattern.compile(pattern[1]).matcher(gameval).find())
			{
				return pattern[0];
			}
		}
		return "Other";
	}

	// ------------------------------------------------------------------ build

	private List<Row> build() throws IOException
	{
		Map<Integer, Integer> graphics = graphics();

		// Tier A, in the order it is written.
		List<Row> tierA = new ArrayList<>();
		Map<Integer, Row> byId = new LinkedHashMap<>();
		for (String line : Files.readAllLines(namesFile, StandardCharsets.UTF_8))
		{
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("id\t"))
			{
				continue;
			}
			String[] cells = line.split("\t", -1);
			int id = Integer.parseInt(cells[0]);
			if (!onPlayerSkeleton(id))
			{
				throw new IllegalStateException("Tier A " + id + " " + cells[1] + " is not on the player skeleton");
			}
			if (byId.containsKey(id))
			{
				throw new IllegalStateException("Tier A lists " + id + " twice");
			}
			if (Arrays.asList(GROUPS).indexOf(cells[2]) < 0)
			{
				throw new IllegalStateException("Tier A " + id + " has an unknown group " + cells[2]);
			}
			Row row = row(id, 'A', graphics);
			row.name = cells[1];
			row.group = cells[2];
			tierA.add(row);
			byId.put(id, row);
		}

		// Tier B: one row for each move, the first id standing for its copies;
		// a move tier A already has is not offered twice.
		Map<String, Row> moves = new LinkedHashMap<>();
		for (Row row : tierA)
		{
			if (row.gameval != null)
			{
				moves.put(row.hold + "|" + row.key, row);
			}
		}
		List<Row> tierB = new ArrayList<>();
		List<Row> tierC = new ArrayList<>();
		Map<Row, Integer> copies = new HashMap<>();
		for (int id : seqs.keySet())
		{
			if (!onPlayerSkeleton(id) || byId.containsKey(id))
			{
				continue;
			}
			Row row = row(id, 'C', graphics);
			row.group = group(row.gameval);
			if (row.gameval != null && TIER_B.matcher(row.gameval).find())
			{
				String move = row.hold + "|" + row.key;
				Row first = moves.get(move);
				if (first == null)
				{
					row.tier = 'B';
					moves.put(move, row);
					tierB.add(row);
					continue;
				}
				copies.merge(first, 1, Integer::sum);
			}
			tierC.add(row);
		}
		for (Row row : tierB)
		{
			row.name = readable(row.gameval, copies.containsKey(row));
		}
		for (Row row : tierC)
		{
			row.name = row.gameval == null ? "Animation " + row.id : readable(row.gameval, false);
			if (row.name.isEmpty())
			{
				row.name = "Animation " + row.id;
			}
		}

		Comparator<Row> order = Comparator.<Row>comparingInt(row -> Arrays.asList(GROUPS).indexOf(row.group))
			.thenComparing(row -> row.name.toLowerCase(Locale.ROOT))
			.thenComparingInt(row -> row.id);
		tierB.sort(order);
		tierC.sort(order);
		List<Row> out = new ArrayList<>(tierA);
		out.addAll(tierB);
		out.addAll(tierC);
		number(out);
		for (Row row : out)
		{
			for (char c : row.name.toCharArray())
			{
				if (c < 0x20 || c > 0x7e)
				{
					throw new IllegalStateException(row.id + " has a name the game font cannot draw: " + row.name);
				}
			}
		}
		return out;
	}

	/**
	 * Two rows that read the same in the window are told apart by a number,
	 * whatever their tiers: Show all lists them side by side, and so does the
	 * plain list when a tier B move has the name of a tier A one. The first,
	 * which is the named one, keeps its name. A name that already ends in a
	 * number, as HUMAN_STAKE2 reads "Stake (2)", is numbered on from its stem
	 * rather than given a second number.
	 */
	static void number(List<Row> rows)
	{
		Set<String> used = new HashSet<>();
		for (Row row : rows)
		{
			String name = row.name;
			String stem = name.replaceFirst(" \\(\\d+\\)$", "");
			for (int count = 2; !used.add(row.hold + "|" + name.toLowerCase(Locale.ROOT)); count++)
			{
				name = stem + " (" + count + ")";
			}
			row.name = name;
		}
	}

	private Row row(int id, char tier, Map<Integer, Integer> graphics)
	{
		Seq seq = seqs.get(id);
		Row row = new Row();
		row.id = id;
		row.tier = tier;
		row.gameval = gamevalName(id);
		row.key = row.gameval == null ? "#" + id : key(row.gameval);
		List<String> names = animNames.getOrDefault(id, new ArrayList<>());
		row.hold = holds(seq.restart, seq.last, names);
		row.left = hand(seq.left);
		row.right = hand(seq.right);
		row.graphic = graphics.getOrDefault(id, 0);
		row.restart = seq.restart;
		row.last = seq.last;
		return row;
	}

	/**
	 * The paired graphics, each checked by both its names against the
	 * gameval, and a pair the cache makes by the graphic's own animation too.
	 */
	private Map<Integer, Integer> graphics()
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (Object[] pair : GRAPHICS)
		{
			int anim = (Integer) pair[0];
			int spot = (Integer) pair[2];
			if (!animNames.getOrDefault(anim, new ArrayList<>()).contains((String) pair[1])
				|| !spotNames.getOrDefault(spot, new ArrayList<>()).contains((String) pair[3]))
			{
				throw new IllegalStateException("The names no longer pair " + anim + " with graphic " + spot);
			}
			if (!onPlayerSkeleton(anim) || !spotanims.containsKey(spot))
			{
				throw new IllegalStateException("Graphic pair " + anim + "/" + spot + " is not in the cache");
			}
			if (CACHE_PAIRED.contains(anim) && spotanims.get(spot).animationId != anim + 1)
			{
				throw new IllegalStateException("The cache no longer pairs " + anim + " with graphic " + spot);
			}
			out.put(anim, spot);
		}
		return out;
	}

	private void write(List<Row> rows) throws IOException
	{
		StringBuilder out = new StringBuilder(HEADER).append(COLUMNS).append('\n');
		for (Row row : rows)
		{
			out.append(row.id).append('\t').append(row.name).append('\t').append(row.group).append('\t')
				.append(row.hold ? "hold" : "once").append('\t').append(row.left).append('\t')
				.append(row.right).append('\t').append(row.graphic).append('\t').append(row.tier).append('\t')
				.append(row.restart).append('\t').append(row.last).append('\n');
		}
		Files.write(outFile, out.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void summarise(List<Row> rows)
	{
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (String group : GROUPS)
		{
			counts.put(group, new int[3]);
		}
		int holds = 0;
		int props = 0;
		int lying = 0;
		for (Row row : rows)
		{
			counts.get(row.group)[row.tier - 'A']++;
			holds += row.hold ? 1 : 0;
			props += row.left.matches("\\d+") || row.right.matches("\\d+") ? 1 : 0;
			lying += row.last >= LONG_LAST_FRAME ? 1 : 0;
		}
		System.out.println("rows " + rows.size() + ", held " + holds + ", holding an item " + props
			+ ", lying still past a minute " + lying);
		System.out.printf("%-22s %5s %5s %5s%n", "group", "A", "B", "C");
		int[] total = new int[3];
		for (Map.Entry<String, int[]> entry : counts.entrySet())
		{
			int[] c = entry.getValue();
			System.out.printf("%-22s %5d %5d %5d%n", entry.getKey(), c[0], c[1], c[2]);
			for (int i = 0; i < 3; i++)
			{
				total[i] += c[i];
			}
		}
		System.out.printf("%-22s %5d %5d %5d%n", "total", total[0], total[1], total[2]);
	}

	// ------------------------------------------------------------------ check

	/** Every row of a shipped list against the cache, as the game is now. */
	List<String> check(List<String> lines)
	{
		List<String> problems = new ArrayList<>();
		for (String line : lines)
		{
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("id\t"))
			{
				continue;
			}
			String[] cells = line.split("\t", -1);
			int id = Integer.parseInt(cells[0]);
			Seq seq = seqs.get(id);
			if (seq == null)
			{
				problems.add(id + " " + cells[1] + ": not in the cache");
				continue;
			}
			if (seq.skeleton != PLAYER_SKELETON)
			{
				problems.add(id + " " + cells[1] + ": not on the player skeleton");
			}
			if (!hand(seq.left).equals(cells[4]) || !hand(seq.right).equals(cells[5]))
			{
				problems.add(id + " " + cells[1] + ": now holds " + hand(seq.left) + "/" + hand(seq.right));
			}
			if (seq.restart != Integer.parseInt(cells[8]) || seq.last != Integer.parseInt(cells[9]))
			{
				problems.add(id + " " + cells[1] + ": its loop or last frame has changed");
			}
			int graphic = Integer.parseInt(cells[6]);
			if (graphic > 0 && !spotanims.containsKey(graphic))
			{
				problems.add(id + " " + cells[1] + ": graphic " + graphic + " is gone");
			}
		}
		return problems;
	}
}
