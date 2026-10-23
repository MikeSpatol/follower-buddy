package com.follower;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * lists.properties holds the lists the speech and the movement code test
 * words and ids against, so a slip in it must fail here rather than leave a
 * name with the wrong article or a stun unnoticed.
 *
 * <p>Each constant that reads it is held to the list it was written as in the
 * code, word for word and in order (below, as it stood); every list in the
 * file is read by one of them; and a set holds no word twice and none that is
 * empty or padded. The sets read as HashSets, so a null asked about is simply
 * not in one; every caller asks with a word or an int.
 */
public class ListsFileTest
{
	/** Words.ONES before it moved, verbatim. */
	private static final String[] ONES = new String[]{
		"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
		"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
		"seventeen", "eighteen", "nineteen",
	};

	/** Words.TENS before it moved, verbatim. */
	private static final String[] TENS = new String[]{
		"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
	};

	/** Words.ORDINAL_ONES before it moved, verbatim. */
	private static final String[] ORDINAL_ONES = new String[]{
		"zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
		"eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth",
		"fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
	};

	/** Words.ORDINAL_TENS before it moved, verbatim. */
	private static final String[] ORDINAL_TENS = new String[]{
		"", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth",
		"seventieth", "eightieth", "ninetieth",
	};

	/** Words.SAID_WITH_A before it moved, verbatim. */
	private static final String[] SAID_WITH_A = new String[]{
		"one", "once", "unic", "unif", "unit", "univ", "use", "usu", "eu",
	};

	/** Words.SAID_WITH_AN before it moved, verbatim. */
	private static final String[] SAID_WITH_AN = new String[]{"hour", "honest", "honour", "heir"};

	/** Words.HEAPS before it moved, verbatim. */
	private static final Set<String> HEAPS = Set.of(
		"clay", "charcoal", "coal", "essence", "flour", "meat", "papyrus", "sand", "wool");

	/** Words.ONE_DESPITE_THE_S before it moved, verbatim. */
	private static final String[] ONE_DESPITE_THE_S = new String[]{"ss", "us", "is", "as", "os", "cyclops", "lens"};

	/** Words.PERSONAL_NAMES before it moved, verbatim. */
	private static final Set<String> PERSONAL_NAMES = Set.of(
		"vorkath", "zulrah", "vet'ion", "callisto", "venenatis", "artio", "calvar'ion", "spindel",
		"scorpia", "cerberus", "sarachnis", "zalcano", "tempoross", "hespori", "skotizo", "obor",
		"bryophyta", "nex", "kree'arra", "k'ril tsutsaroth", "general graardor", "commander zilyana",
		"vardorvis", "duke sucellus", "sol heredit", "araxxor", "tztok-jad", "tzkal-zuk",
		"verzik vitur", "phosani's nightmare", "dagannoth rex", "dagannoth prime", "dagannoth supreme");

	/** SpeechEngine.PLAYER_NAMES before it moved, verbatim. */
	private static final Set<String> PLAYER_NAMES = Set.of("player", "name", "speaker");

	/** SpeechEngine.AS_WRITTEN before it moved, verbatim. */
	private static final Set<String> AS_WRITTEN = Set.of("player", "name", "speaker", "message");

	/** SpeechEngine.CREATURE_NAMES before it moved, verbatim. */
	private static final Set<String> CREATURE_NAMES = Set.of("npc", "foe", "boss", "memoryWho");

	/** SpeechEngine.POINTING before it moved, verbatim. */
	private static final Set<String> POINTING = Set.of("the", "that", "this", "another", "one", "first", "more");

	/** SpeechEngine.NAME_MODIFIES before it moved, verbatim. */
	private static final Set<String> NAME_MODIFIES = Set.of(
		"population", "column", "kill", "kills", "count", "fight", "pet", "drop", "drops",
		"lair", "page", "entry");

	/** TriggerContext.THIEVING_STUNS before it moved, verbatim. */
	private static final Set<Integer> THIEVING_STUNS = new HashSet<>(
		Arrays.asList(1054, 1874, 848, 14422, 14423, 14424, 10087, 10088));

	/** TriggerContext.SAFE_DEATH_REGIONS before it moved, verbatim. */
	private static final Set<Integer> SAFE_DEATH_REGIONS = Set.of(9520, 9620,
		13658, 13659, 13660, 13914, 13915, 13916, 13918, 13919, 13920, 14174, 14175, 14176,
		14430, 14431, 14432);

	/** TriggerContext.VAGUE before it moved, verbatim. */
	private static final Set<String> VAGUE = new HashSet<>(Arrays.asList(
		"how close that was", "the way that went", "that drop", "the size of that thing",
		// And what the places were marked with before they named the moment.
		// Text QA5, 17 Oct: "Same spot. This is where it went wrong for you."
		"it went wrong for you", "you got that drop", "you put that thing down",
		"you hit harder than you ever had"));

	/** TriggerContext.PLAIN before it moved, verbatim. */
	private static final Set<String> PLAIN = new HashSet<>(Arrays.asList(
		"the time you died", "the time you nearly died", "your big drop", "your big kill"));

	/** FollowerEntity.RECENTRED_POSES before it moved, verbatim. */
	private static final Set<Integer> RECENTRED_POSES = new HashSet<>(Arrays.asList(179, 645, 534, 10061, 10053));

	/** FollowerEntity.EASED_STANCES before it moved, verbatim. */
	private static final Set<Integer> EASED_STANCES = new HashSet<>(Arrays.asList(
		13993, 13994, 13995, 13996, 13997, 13998, 13999, 14000, 14001));

	private static Object[][] constants()
	{
		return new Object[][]{
			{com.follower.speech.Words.class, "ONES", "ones", ONES},
			{com.follower.speech.Words.class, "TENS", "tens", TENS},
			{com.follower.speech.Words.class, "ORDINAL_ONES", "ordinalOnes", ORDINAL_ONES},
			{com.follower.speech.Words.class, "ORDINAL_TENS", "ordinalTens", ORDINAL_TENS},
			{com.follower.speech.Words.class, "SAID_WITH_A", "saidWithA", SAID_WITH_A},
			{com.follower.speech.Words.class, "SAID_WITH_AN", "saidWithAn", SAID_WITH_AN},
			{com.follower.speech.Words.class, "HEAPS", "heaps", HEAPS},
			{com.follower.speech.Words.class, "ONE_DESPITE_THE_S", "oneDespiteTheS", ONE_DESPITE_THE_S},
			{com.follower.speech.Words.class, "PERSONAL_NAMES", "personalNames", PERSONAL_NAMES},
			{com.follower.speech.SpeechEngine.class, "PLAYER_NAMES", "playerNames", PLAYER_NAMES},
			{com.follower.speech.SpeechEngine.class, "AS_WRITTEN", "asWritten", AS_WRITTEN},
			{com.follower.speech.SpeechEngine.class, "CREATURE_NAMES", "creatureNames", CREATURE_NAMES},
			{com.follower.speech.SpeechEngine.class, "POINTING", "pointing", POINTING},
			{com.follower.speech.SpeechEngine.class, "NAME_MODIFIES", "nameModifies", NAME_MODIFIES},
			{com.follower.speech.TriggerContext.class, "THIEVING_STUNS", "thievingStuns", THIEVING_STUNS},
			{com.follower.speech.TriggerContext.class, "SAFE_DEATH_REGIONS", "safeDeathRegions", SAFE_DEATH_REGIONS},
			{com.follower.speech.TriggerContext.class, "VAGUE", "vague", VAGUE},
			{com.follower.speech.TriggerContext.class, "PLAIN", "plain", PLAIN},
			{com.follower.follower.FollowerEntity.class, "RECENTRED_POSES", "recentredPoses", RECENTRED_POSES},
			{com.follower.follower.FollowerEntity.class, "EASED_STANCES", "easedStances", EASED_STANCES},
		};
	}

	private static Object read(Class<?> owner, String name) throws Exception
	{
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	@Test
	public void everyConstantReadsWhatItWasWrittenAs() throws Exception
	{
		for (Object[] row : constants())
		{
			String what = ((Class<?>) row[0]).getSimpleName() + "." + row[1];
			Object now = read((Class<?>) row[0], (String) row[1]);
			if (row[3] instanceof String[])
			{
				assertArrayEquals(what, (String[]) row[3], (String[]) now);
			}
			else
			{
				assertEquals(what, row[3], now);
				assertEquals(what + " is a HashSet", HashSet.class, now.getClass());
			}
		}
	}

	@Test
	public void everyListInTheFileIsRead() throws Exception
	{
		Properties file = Resources.properties("lists.properties");
		Set<String> read = new HashSet<>();
		for (Object[] row : constants())
		{
			assertTrue(row[2] + " is read once", read.add((String) row[2]));
		}
		assertEquals(read, file.stringPropertyNames());
	}

	@Test
	public void noSetWordIsEmptyPaddedOrTwice() throws Exception
	{
		for (Object[] row : constants())
		{
			if (row[3] instanceof String[])
			{
				continue;
			}
			List<String> words = Arrays.asList(Resources.list((String) row[2]));
			assertEquals(row[1] + " has no word twice", new HashSet<>(words).size(), words.size());
			for (String word : words)
			{
				assertTrue(row[1] + ": '" + word + "'", !word.isEmpty() && word.equals(word.trim()));
			}
		}
	}
}
