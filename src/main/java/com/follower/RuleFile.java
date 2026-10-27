package com.follower;

import java.util.*;
import java.util.List;

/** Root object of phrases.json. */
class RuleFile
{
	static final int SUPPORTED_VERSION = 1;

	int version = SUPPORTED_VERSION;

	/** Optional free text; ignored by the plugin. */
	String comment;

	/**
	 * Place names by region id, for the regions no area rule names:
	 * {@code {"11595": "the essence mine"}}. Said by {where} and {memoryWhere}
	 * as the Locations rules' names are.
	 */
	java.util.Map<Integer, String> places;

	List<SpeechRule> rules = new ArrayList<>();
}
