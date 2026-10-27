package com.follower;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SpeechOutput
{
	/** Text bubble drawn above the follower. */
	OVERHEAD("Overhead"),
	/** Coloured message in the game chatbox. */
	CHATBOX("Chatbox"),
	/** Both at once. */
	BOTH("Both");

	final String label;

	static SpeechOutput parse(String text, SpeechOutput fallback)
	{
		if (text == null)
			return fallback;
		for (var value : values())
		{
			if (value.name().equalsIgnoreCase(text.trim()))
				return value;
		}
		return fallback;
	}

	boolean showsOverhead()
	{
		return this == OVERHEAD || this == BOTH;
	}

	boolean showsChatbox()
	{
		return this == CHATBOX || this == BOTH;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
