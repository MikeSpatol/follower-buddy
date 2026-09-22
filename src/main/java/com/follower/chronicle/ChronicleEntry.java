package com.follower.chronicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
}
