package com.follower.speech;

/**
 * How much the follower talks, as a thing a player can choose.
 *
 * <p>This was a box asking for a number of milliseconds, which is a question
 * about the implementation rather than about the follower. The setting worth
 * having is the one Forspoken shipped and was widely praised for - a named
 * frequency, from barely to constantly - because "how chatty should my
 * companion be" is a preference and not a measurement.
 *
 * <p>The value is the floor between any two spoken lines. The mood scaling in
 * {@link SpeechEngine} still applies on top, so a quiet setting and a bad day
 * compound rather than fight.
 */
public enum Chattiness
{
	QUIET("Quiet", 12_000L),
	OCCASIONAL("Occasional", 6_000L),
	NORMAL("Normal", 3_000L),
	CHATTY("Chatty", 1_500L);

	private final String label;
	private final long gapMs;

	Chattiness(String label, long gapMs)
	{
		this.label = label;
		this.gapMs = gapMs;
	}

	public long getGapMs()
	{
		return gapMs;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
