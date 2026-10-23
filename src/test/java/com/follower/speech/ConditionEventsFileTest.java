package com.follower.speech;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * event-conditions.properties is behaviour, so a slip in it must fail here
 * rather than leave a rule silently never true.
 *
 * <p>Every line names a real event type, only the narrowing fields the
 * interpreter knows, and a lower-case condition type, the way Condition
 * looks it up; no type is both a line and a case of Condition's switch.
 *
 * <p>And every line still means what its case in the switch meant before it
 * moved there: {@link #before} is those cases word for word, and each type
 * is evaluated both ways against every event type, with and without each
 * narrowing.
 */
public class ConditionEventsFileTest
{
	private static final List<String> NARROWING = Arrays.asList("is", "names", "contains");

	private static Properties file() throws Exception
	{
		Properties lines = new Properties();
		try (InputStream in = Condition.class.getResourceAsStream("/com/follower/event-conditions.properties"))
		{
			assertNotNull("event-conditions.properties is bundled", in);
			lines.load(in);
		}
		return lines;
	}

	@Test
	public void everyLineIsAnEventTypeAndKnownNarrowing() throws Exception
	{
		Properties lines = file();
		assertTrue("has lines", lines.size() >= 30);
		for (String type : lines.stringPropertyNames())
		{
			assertEquals(type + " is lower case", type.toLowerCase(Locale.ROOT), type);
			List<String> spec = Arrays.asList(lines.getProperty(type).split(" "));
			TriggerEvent.Type.valueOf(spec.get(0));
			assertEquals(type + " names a narrowing once", new HashSet<>(spec).size(), spec.size());
			for (String field : spec.subList(1, spec.size()))
			{
				assertTrue(type + ": unknown field '" + field + "'", NARROWING.contains(field));
			}
		}
	}

	@Test
	public void noTypeIsBothALineAndACase() throws Exception
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/speech/Condition.java")), StandardCharsets.UTF_8);
		Set<String> cases = new HashSet<>();
		Matcher m = Pattern.compile("case \"([a-z]+)\":").matcher(source);
		while (m.find())
		{
			cases.add(m.group(1));
		}
		Set<String> both = new TreeSet<>(file().stringPropertyNames());
		both.retainAll(cases);
		assertEquals("types that are both a line and a case", Collections.emptySet(), both);
	}

	@Test
	public void everyLineMeansWhatItsCaseMeant() throws Exception
	{
		Set<String> types = file().stringPropertyNames();
		assertEquals("the same types as the cases they replaced", BEFORE, new TreeSet<>(types));

		List<TriggerEvent> events = events();
		List<String> isValues = Arrays.asList(null, "x", "valuable", "VALUABLE", "fell", "bought", "");
		List<List<String>> nameLists = Arrays.asList(null, Collections.emptyList(),
			Collections.singletonList("Goblin"), Collections.singletonList("gob*"),
			Arrays.asList("x", "*Shanty*"), Collections.singletonList("*"));
		List<String> containsValues = Arrays.asList(null, "", "star", "STAR", "nothing at all");
		int compared = 0;
		for (String type : types)
		{
			for (String is : isValues)
			{
				for (List<String> names : nameLists)
				{
					for (String contains : containsValues)
					{
						Condition now = condition(type, is, names, contains);
						Condition then = condition(type, is, names, contains);
						for (TriggerEvent event : events)
						{
							boolean expected = before(then, event);
							if (now.matches(null, event) != expected)
							{
								fail(type + " is=" + is + " names=" + names + " contains=" + contains
									+ " on " + event.getType() + " '" + event.getName() + "' '"
									+ event.getMessage() + "': expected " + expected);
							}
							compared++;
						}
					}
				}
			}
		}
		assertTrue(compared > 100_000);
	}

	private static Condition condition(String type, String is, List<String> names, String contains)
	{
		Condition c = new Condition();
		// Written as a rule file would, in any case.
		c.type = type.toUpperCase(Locale.ROOT);
		c.is = is;
		c.names = names;
		c.contains = contains;
		return c;
	}

	/** Every event type, bare and named, and the ones that carry a message, with some. */
	private static List<TriggerEvent> events()
	{
		List<String> names = Arrays.asList("", "x", "Goblin", "goblin ", " Sea Shanty 2 ",
			"valuable", "fell", "bought", "VALUABLE");
		List<String> messages = Arrays.asList("", "A shooting STAR!", "nothing at all", "star");
		List<TriggerEvent> events = new ArrayList<>();
		for (TriggerEvent.Type type : TriggerEvent.Type.values())
		{
			events.add(TriggerEvent.simple(type));
			for (String name : names)
			{
				events.add(TriggerEvent.channel(type, name));
			}
		}
		for (String name : names)
		{
			for (String message : messages)
			{
				events.add(TriggerEvent.npcShout(name, message));
				events.add(TriggerEvent.chat(message, 0, name));
				// public:cut company-extras,noticing
				events.add(TriggerEvent.noticed(name, message));
				// public:end company-extras,noticing
				// public:cut company-extras,will
				events.add(TriggerEvent.hesitated(name, message));
				// public:end company-extras,will
			}
		}
		return events;
	}

	/** The types whose cases {@link #before} keeps. */
	private static final Set<String> BEFORE = new TreeSet<>(Arrays.asList(
		// public:cut company-extras,noticing
		"noticed",
		// public:end company-extras,noticing
		// public:cut company-extras,will
		"hesitated",
		"steppedaside",
		// public:end company-extras,will
		// public:cut agility,agility-all
		"crossed",
		// public:end agility,agility-all
		// public:cut agility-all,marks
		"grace",
		// public:end agility-all,marks
		// public:cut errands
		"errandstart",
		"errandend",
		// public:end errands
		// public:cut spectate
		"combatstart",
		// public:end spectate
		// public:cut side-games
		"challengemet",
		"challengefailed",
		"betwon",
		"betlost",
		// public:end side-games
		// public:cut souvenirs-wishes
		"souvenirlost",
		// public:end souvenirs-wishes
		// public:cut chronicle
		"pagewritten",
		// public:end chronicle
		// public:cut skilling
		"workended",
		"targetdepleted",
		"bagfull",
		"baghalf",
		// public:end skilling
		"farewell", "playerdeath", "login", "thrallstart", "thrallswitch", "thrallend",
		"thievingstart", "thievingend", "underfoot", "worldhop", "gameupdated", "notesedited",
		"musicunlock", "musictrack", "collectionadd", "questcomplete", "geoffer",
		"channeljoined", "channelleft", "npcshout", "wantfulfilled", "wantexpired", "examined",
		"examinedthing", "attendedagain", "minutestaken", "personalbest"));

	/**
	 * Condition's cases for these types as they were before the file, word
	 * for word but for reading the condition's fields through {@code c}.
	 */
	private static boolean before(Condition c, TriggerEvent event)
	{
		String is = c.is;
		List<String> names = c.names;
		String contains = c.contains;
		switch (c.type.toLowerCase(Locale.ROOT))
		{
			// public:cut company-extras,noticing
			case "noticed":
				return event.getType() == TriggerEvent.Type.NOTICED
					&& (is == null || is.equalsIgnoreCase(event.getName()))
					&& (contains == null || event.getMessage().toLowerCase(Locale.ROOT)
						.contains(contains.toLowerCase(Locale.ROOT)));

			// public:end company-extras,noticing
			// public:cut company-extras,will
			case "hesitated":
				return event.getType() == TriggerEvent.Type.HESITATED
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			case "steppedaside":
				return event.getType() == TriggerEvent.Type.STEPPED_ASIDE;

			// public:end company-extras,will
			// public:cut agility,agility-all
			case "crossed":
				return event.getType() == TriggerEvent.Type.CROSSED
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			// public:end agility,agility-all
			// public:cut agility-all,marks
			case "grace":
				return event.getType() == TriggerEvent.Type.GRACE
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			// public:end agility-all,marks
			case "farewell":
				return event.getType() == TriggerEvent.Type.FAREWELL;

			case "playerdeath":
				return event.getType() == TriggerEvent.Type.PLAYER_DEATH;

			case "login":
				return event.getType() == TriggerEvent.Type.LOGIN;

			case "thrallstart":
				return event.getType() == TriggerEvent.Type.THRALL_START;
			case "thrallswitch":
				return event.getType() == TriggerEvent.Type.THRALL_SWITCH;
			case "thrallend":
				return event.getType() == TriggerEvent.Type.THRALL_END;

			// public:cut errands
			case "errandstart":
				return event.getType() == TriggerEvent.Type.ERRAND_START
					&& (names == null || matchesAnyName(names, event.getName()));
			case "errandend":
				return event.getType() == TriggerEvent.Type.ERRAND_END
					&& (names == null || matchesAnyName(names, event.getName()));

			// public:end errands
			// public:cut spectate
			case "combatstart":
				return event.getType() == TriggerEvent.Type.COMBAT_START
					&& (names == null || matchesAnyName(names, event.getName()));

			// public:end spectate
			case "thievingstart":
				return event.getType() == TriggerEvent.Type.THIEVING_START;
			case "thievingend":
				return event.getType() == TriggerEvent.Type.THIEVING_END;

			case "underfoot":
				return event.getType() == TriggerEvent.Type.UNDERFOOT;

			// public:cut side-games
			case "challengemet":
				return event.getType() == TriggerEvent.Type.CHALLENGE_MET;
			case "challengefailed":
				return event.getType() == TriggerEvent.Type.CHALLENGE_FAILED;

			// public:end side-games
			// public:cut souvenirs-wishes
			case "souvenirlost":
				return event.getType() == TriggerEvent.Type.SOUVENIR_LOST;

			// public:end souvenirs-wishes
			// public:cut side-games
			case "betwon":
				return event.getType() == TriggerEvent.Type.BET_WON;
			case "betlost":
				return event.getType() == TriggerEvent.Type.BET_LOST;

			// public:end side-games
			case "worldhop":
				return event.getType() == TriggerEvent.Type.WORLD_HOP;

			case "gameupdated":
				return event.getType() == TriggerEvent.Type.GAME_UPDATE;

			case "notesedited":
				return event.getType() == TriggerEvent.Type.NOTES_EDITED;

			case "musicunlock":
				return event.getType() == TriggerEvent.Type.MUSIC_UNLOCK;

			case "musictrack":
				return event.getType() == TriggerEvent.Type.TRACK_CHANGE
					&& (names == null || matchesAnyName(names, event.getName()));

			case "collectionadd":
				return event.getType() == TriggerEvent.Type.COLLECTION_ADD;

			case "questcomplete":
				return event.getType() == TriggerEvent.Type.QUEST_COMPLETE;

			// public:cut chronicle
			case "pagewritten":
				return event.getType() == TriggerEvent.Type.PAGE_WRITTEN
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			// public:end chronicle
			case "geoffer":
				return event.getType() == TriggerEvent.Type.GE_OFFER
					&& (is == null || is.equalsIgnoreCase(event.getName()));

			case "channeljoined":
				return event.getType() == TriggerEvent.Type.CHANNEL_JOIN;
			case "channelleft":
				return event.getType() == TriggerEvent.Type.CHANNEL_LEAVE;

			case "npcshout":
				return event.getType() == TriggerEvent.Type.NPC_SHOUT
					&& (names == null || matchesAnyName(names, event.getName()))
					&& (contains == null || event.getMessage().toLowerCase(Locale.ROOT)
						.contains(contains.toLowerCase(Locale.ROOT)));

			case "wantfulfilled":
				return event.getType() == TriggerEvent.Type.WANT_FULFILLED;
			case "wantexpired":
				return event.getType() == TriggerEvent.Type.WANT_EXPIRED;

			case "examined":
				return event.getType() == TriggerEvent.Type.EXAMINED;

			case "examinedthing":
				return event.getType() == TriggerEvent.Type.EXAMINED_THING
					&& (names == null || matchesAnyName(names, event.getName()));

			case "attendedagain":
				return event.getType() == TriggerEvent.Type.ATTENDED_AGAIN;

			case "minutestaken":
				return event.getType() == TriggerEvent.Type.MINUTES_TAKEN;

			case "personalbest":
				return event.getType() == TriggerEvent.Type.RECORD
					&& (names == null || matchesAnyName(names, event.getName()));

			// public:cut skilling
			case "workended":
				return event.getType() == TriggerEvent.Type.WORK_END
					&& (names == null || matchesAnyName(names, event.getName()));

			case "targetdepleted":
				return event.getType() == TriggerEvent.Type.TARGET_DEPLETED;

			case "bagfull":
				return event.getType() == TriggerEvent.Type.BAG_FULL;
			case "baghalf":
				return event.getType() == TriggerEvent.Type.BAG_HALF;
			// public:end skilling

			default:
				throw new IllegalArgumentException(c.type);
		}
	}

	/** Condition's name matching as it was: case-insensitive, {@code *} wildcards. */
	private static boolean matchesAnyName(List<String> names, String candidate)
	{
		if (candidate == null || names == null)
		{
			return false;
		}
		String normalised = Condition.normaliseName(candidate);
		for (String name : names)
		{
			if (Condition.compileName(name).matcher(normalised).matches())
			{
				return true;
			}
		}
		return false;
	}
}
