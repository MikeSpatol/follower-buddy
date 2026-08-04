package com.follower.speech;

public enum SpeechOutput
{
	/** Text bubble drawn above the follower. */
	OVERHEAD("Overhead"),
	/** Coloured message in the game chatbox. */
	CHATBOX("Chatbox"),
	/** Both at once. */
	BOTH("Both");

	private final String label;

	SpeechOutput(String label)
	{
		this.label = label;
	}

	public static SpeechOutput parse(String text, SpeechOutput fallback)
	{
		if (text == null)
		{
			return fallback;
		}
		for (SpeechOutput value : values())
		{
			if (value.name().equalsIgnoreCase(text.trim()))
			{
				return value;
			}
		}
		return fallback;
	}

	public boolean showsOverhead()
	{
		return this == OVERHEAD || this == BOTH;
	}

	public boolean showsChatbox()
	{
		return this == CHATBOX || this == BOTH;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
