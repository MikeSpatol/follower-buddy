package com.follower;

import lombok.*;

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
 *
 * <p>Minimal exists because Quiet was not quiet enough for some people. Twelve
 * seconds between lines still allows five a minute, which reads as constant
 * to anybody who wanted the follower mostly for the company. Forty-five
 * seconds is about one remark a minute at most, and the idle wind-down scales
 * with it, so a long AFK on Minimal goes properly silent.
 */
@RequiredArgsConstructor
public enum Chattiness
{
	MINIMAL("Minimal", 45_000L),
	QUIET("Quiet", 12_000L),
	OCCASIONAL("Occasional", 6_000L),
	NORMAL("Normal", 3_000L),
	CHATTY("Chatty", 1_500L);

	final String label;
	@Getter
	final long gapMs;

	@Override
	public String toString()
	{
		return label;
	}
}
