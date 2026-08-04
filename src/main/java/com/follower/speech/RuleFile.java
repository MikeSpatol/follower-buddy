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

	public List<SpeechRule> rules = new ArrayList<>();
}
