package com.follower.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One page of the chronicle: a quest, a boss, an enemy, a place, a faction
 * or a piece of the world, as the scribe has it written down.
 *
 * <p>A plain Gson shape, public fields and no constructor, like the rule
 * file. Everything human-facing in it is drawn with the game's own bitmap
 * fonts, so it is plain ASCII by law (see the integrity test).
 */
public class ChronicleEntry
{
	/** The kinds a page can be, in the order the tabs show them. */
	public static final List<String> KINDS = java.util.Arrays.asList(
		"quest", "boss", "enemy", "location", "faction", "world");

	/** Stable id, lowercase with hyphens: {@code quest-cooks-assistant}. */
	public String id;

	/** One of {@link #KINDS}. */
	public String kind;

	/** The name exactly as the game spells it. */
	public String name;

	/** What has to have happened for the page to be readable. */
	public ChronicleUnlock unlock;

	/** Two or three sentences, shown under the name. */
	public String summary;

	/** The body, in headed sections. */
	public List<Section> sections;

	/** Who was involved, a phrase each. */
	public List<String> people;

	/** Where it happened, a phrase each. */
	public List<String> places;

	/** The particulars worth knowing, a line each. */
	public List<String> details;

	/** The pictures at the head of the page, in the order they are turned. */
	public List<Plate> plates;

	/** A number of your own under the plate, when the follower has one; see {@link Tally}. */
	public Tally tally;

	/**
	 * A line under the plate with a count the follower keeps: "Goblins
	 * you've killed while I watched: 214". Shown only once the count is
	 * above nought, so a page without one simply has no line.
	 */
	public static class Tally
	{
		/** What is counted, in the scribe's words; the number follows a colon. */
		public String label;

		/**
		 * The follower's counter: {@code kill:<npc name>} for the kills it
		 * saw you make, {@code kc:<boss>} for the game's own kill count.
		 */
		public String of;
	}

	/**
	 * A picture on a page. Exactly one of {@link #npc} and {@link #sketch} is
	 * set: a creature or a person is drawn from its own model, turning; a
	 * place is sketched by the scribe when you pose for it there.
	 */
	public static class Plate
	{
		/** An NPC id, drawn from the game's own model. */
		public Integer npc;

		/**
		 * The NPC's standing animation, which the portrait plays. The API has
		 * no way to ask an NPC id for it, so it is looked up in the cache and
		 * written here; the cache dumper's runChronicleAudit checks it.
		 */
		public Integer idle;

		/** The name a sketch is filed under; pages sharing a place share it. */
		public String sketch;

		/** The map regions the sketch can be made in. */
		public List<Integer> regions;

		/** The line under the picture, in the scribe's hand. */
		public String caption;

		/**
		 * A sketch's line until it is drawn: what the scribe means to draw,
		 * and so where to pose for it.
		 */
		public String hint;
	}

	public List<Plate> platesOrEmpty()
	{
		return plates == null ? new ArrayList<>() : plates;
	}

	/** A headed run of paragraphs; paragraphs are separated by a blank line. */
	public static class Section
	{
		public String heading;
		public String body;
	}

	/**
	 * How a page unlocks. Exactly one of these is meant to be set; a page
	 * with none set is locked for good, which the integrity test refuses.
	 */
	public static class ChronicleUnlock
	{
		/** A {@code net.runelite.api.Quest} constant name, finished. */
		public String quest;

		/**
		 * A {@code net.runelite.api.Quest} constant name, started or finished:
		 * the places and creatures a quest introduces are known from the
		 * first step of it.
		 */
		public String questBegun;

		/** NPC names, exactly as the game spells them; meeting any one of them. */
		public List<String> npcs;

		/** Map region ids; standing in any one of them. */
		public List<Integer> regions;

		/** Readable from the first page: the places everyone starts. */
		public boolean always;
	}

	public String kindOrEmpty()
	{
		return kind == null ? "" : kind.toLowerCase(Locale.ROOT);
	}

	public List<Section> sectionsOrEmpty()
	{
		return sections == null ? new ArrayList<>() : sections;
	}

	public List<String> peopleOrEmpty()
	{
		return people == null ? new ArrayList<>() : people;
	}

	public List<String> placesOrEmpty()
	{
		return places == null ? new ArrayList<>() : places;
	}

	public List<String> detailsOrEmpty()
	{
		return details == null ? new ArrayList<>() : details;
	}

	/**
	 * No quest before its time. A paragraph or a list item that would give a
	 * later quest away opens with {@code {done:RECIPE_FOR_DISASTER}}, several
	 * quests comma-separated, and stays off the page until every one of them
	 * is done. See docs/chronicle-voice.md.
	 */
	private static final Pattern GATE = Pattern.compile("^\\{done:([A-Z0-9_]+(?:,[A-Z0-9_]+)*)\\}\\s*");

	/** Paragraphs are separated by a blank line, as the layout splits them. */
	private static final String PARAGRAPH_BREAK = "\n\\s*\n";

	/** The quests a passage waits on, by Quest constant name; none when it waits on nothing. */
	public static List<String> gateOf(String passage)
	{
		Matcher gate = passage == null ? null : GATE.matcher(passage);
		if (gate == null || !gate.find())
		{
			return Collections.emptyList();
		}
		List<String> quests = new ArrayList<>();
		Collections.addAll(quests, gate.group(1).split(","));
		return quests;
	}

	/** The passage as it is read, without its gate. */
	public static String ungated(String passage)
	{
		return passage == null ? null : GATE.matcher(passage).replaceFirst("");
	}

	/**
	 * Every passage that can carry a gate, in page order: the summary's
	 * paragraphs, each section's paragraphs, then the people, places and
	 * particulars.
	 */
	List<String> passages()
	{
		List<String> passages = new ArrayList<>();
		addParagraphs(passages, summary);
		for (Section section : sectionsOrEmpty())
		{
			addParagraphs(passages, section.body);
		}
		passages.addAll(peopleOrEmpty());
		passages.addAll(placesOrEmpty());
		passages.addAll(detailsOrEmpty());
		return passages;
	}

	private static void addParagraphs(List<String> out, String text)
	{
		if (text != null && !text.isEmpty())
		{
			Collections.addAll(out, text.split(PARAGRAPH_BREAK));
		}
	}

	/** Every quest a passage on this page waits on. */
	public Set<String> gateQuests()
	{
		Set<String> quests = new LinkedHashSet<>();
		for (String passage : passages())
		{
			quests.addAll(gateOf(passage));
		}
		return quests;
	}

	/**
	 * The page as it reads to someone who has finished the quests
	 * {@code done} says yes to: every passage still waiting is left off, a
	 * section left with nothing is left off heading and all, and the gates
	 * are not read out. The book itself is not touched.
	 */
	public ChronicleEntry readAs(Predicate<String> done)
	{
		ChronicleEntry shown = new ChronicleEntry();
		shown.id = id;
		shown.kind = kind;
		shown.name = name;
		shown.unlock = unlock;
		shown.plates = plates;
		shown.tally = tally;
		shown.summary = shownParagraphs(summary, done);
		if (sections != null)
		{
			shown.sections = new ArrayList<>();
			for (Section section : sections)
			{
				String body = shownParagraphs(section.body, done);
				if (body != null && !body.isEmpty())
				{
					Section kept = new Section();
					kept.heading = section.heading;
					kept.body = body;
					shown.sections.add(kept);
				}
			}
		}
		shown.people = shownItems(people, done);
		shown.places = shownItems(places, done);
		shown.details = shownItems(details, done);
		return shown;
	}

	private static boolean open(String passage, Predicate<String> done)
	{
		for (String quest : gateOf(passage))
		{
			if (!done.test(quest))
			{
				return false;
			}
		}
		return true;
	}

	private static String shownParagraphs(String text, Predicate<String> done)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		List<String> kept = new ArrayList<>();
		for (String paragraph : text.split(PARAGRAPH_BREAK))
		{
			if (open(paragraph, done))
			{
				kept.add(ungated(paragraph));
			}
		}
		return String.join("\n\n", kept);
	}

	private static List<String> shownItems(List<String> items, Predicate<String> done)
	{
		if (items == null)
		{
			return null;
		}
		List<String> kept = new ArrayList<>();
		for (String item : items)
		{
			if (open(item, done))
			{
				kept.add(ungated(item));
			}
		}
		return kept;
	}
}
