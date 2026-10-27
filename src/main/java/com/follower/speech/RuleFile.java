package com.follower.speech;

import java.util.ArrayList;
import java.util.List;

/** Root object of phrases.json. */
public class RuleFile
{
	public static final int SUPPORTED_VERSION = 1;

	public int version = SUPPORTED_VERSION;

	/** Optional free text; ignored by the plugin. */
	public String comment;
	// public:cut nicknames

	/**
	 * Names the follower may take to calling you by, keyed by the tally that
	 * earns each one: {@code {"deaths": "the gravedigger"}}.
	 *
	 * <p>Here rather than in the code because it is writing, and all the other
	 * writing is here. Whichever tally is highest, past a threshold, supplies
	 * {nickname}; a rule file that leaves this out simply has a follower that
	 * never gives you one.
	 */
	public java.util.Map<String, String> nicknames;
	// public:end nicknames

	/**
	 * Place names by region id, for the regions no area rule names:
	 * {@code {"11595": "the essence mine"}}. Said by {where} and {memoryWhere}
	 * as the Locations rules' names are.
	 */
	public java.util.Map<Integer, String> places;

	public List<SpeechRule> rules = new ArrayList<>();
}
