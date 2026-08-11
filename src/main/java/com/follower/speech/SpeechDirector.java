package com.follower.speech;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides when the follower has said enough for now.
 *
 * <p>The speech gap already stops two lines landing on top of each other, but a
 * gap is a floor and nothing more: at three seconds apart, a follower with
 * plenty to react to will talk for as long as things keep happening, and every
 * line lands in the same flat stream as the one before it. Nothing is ever
 * quiet, so nothing is ever loud.
 *
 * <p>This is the survival-horror director model, which exists for exactly that
 * problem. A running intensity rises with each thing said and decays with time;
 * crossing a peak forces a RELAX period during which only an occasion gets
 * through. The relax is the point. Contrast is not something a line can have on
 * its own - it is the silence either side of it - and the only way to make a
 * good line land is to have said less shortly before it.
 *
 * <p>The same object holds the settling-in damper, because it is the same
 * question asked about a longer span of time: how much should be said, given
 * what has been said lately.
 */
public class SpeechDirector
{
	/** What one spoken line adds to the running intensity. */
	private static final double RISE_PER_LINE = 1.0;

	/**
	 * Intensity at which the follower has had its say and stops for a bit.
	 *
	 * <p>Three lines close together, and not the 3.0 that implies: the third
	 * arrives two gaps after the first and the decay has already taken a slice
	 * off the two before it. Asking for a full three units would quietly mean
	 * four lines, which is a different and much longer burst.
	 */
	private static final double PEAK_AT = 2.5;

	/**
	 * How long a peak buys, in milliseconds, as a range.
	 *
	 * <p>Randomised because a constant is a metronome: a player who notices the
	 * follower goes quiet for exactly thirty seconds has found the machinery,
	 * and from then on hears a timer rather than someone choosing not to speak.
	 */
	private static final long RELAX_MIN_MS = 30_000L;
	private static final long RELAX_MAX_MS = 45_000L;

	/**
	 * Intensity decays one unit per this many speech gaps.
	 *
	 * <p>Expressed in gaps rather than seconds so the whole model scales with
	 * the chattiness setting instead of fighting it. At Normal, three lines
	 * inside forty-five seconds is a burst worth resting after; at Quiet, where
	 * the floor between lines is already twelve seconds, the same three lines
	 * are spread far enough that they never peak - which is correct, because a
	 * player who asked for quiet has already been given the contrast.
	 */
	private static final double DECAY_GAPS = 5.0;

	/**
	 * Sessions during which the follower is still settling in. The first
	 * evening and the first return.
	 */
	private static final int SETTLING_SESSIONS = 2;

	/** Groups that recite what the world is, rather than react to what happened in it. */
	private static final java.util.Set<String> LORE_GROUPS =
		new java.util.HashSet<>(java.util.Arrays.asList("area", "gear"));

	/** Gaps a settling-in follower leaves between two lore lines. */
	private static final int SETTLING_LORE_GAPS = 4;

	private double intensity;
	private long lastDecayMs;
	private long relaxUntilMs;
	private long lastLoreMs;

	/**
	 * The base speech gap, in milliseconds. Everything here is expressed as a
	 * multiple of it, so the chattiness setting moves the whole model at once.
	 *
	 * <p>Zero switches the director off entirely, which falls out of the same
	 * fact rather than being a special case bolted on: every window here is a
	 * multiple of the gap, so a gap of nothing makes all of them nothing. The
	 * chattiness setting has no such level - this is for the test harness,
	 * which wants rules evaluated without pacing in the way.
	 */
	private long baseGapMs = 3_000L;

	public void setBaseGapMs(long baseGapMs)
	{
		this.baseGapMs = Math.max(0L, baseGapMs);
	}

	public boolean isPacing()
	{
		return baseGapMs > 0L;
	}

	/**
	 * Sessions so far, this one included. Drives the settling-in damper only.
	 *
	 * <p>Zero means nobody has said yet, which is treated as NOT settling in.
	 * The damper is a claim that the follower is new, and an unanswered
	 * question is not evidence for it - defaulting the other way would have
	 * every fresh engine start out quiet about the scenery until something got
	 * round to correcting it.
	 */
	private int sessionCount;

	public void setSessionCount(int sessionCount)
	{
		this.sessionCount = sessionCount;
	}

	public boolean isSettlingIn()
	{
		return sessionCount >= 1 && sessionCount <= SETTLING_SESSIONS;
	}

	/** For the transcript and the debug command. */
	public double getIntensity()
	{
		return intensity;
	}

	public boolean isRelaxing(long now)
	{
		return now < relaxUntilMs;
	}

	public long relaxRemainingMs(long now)
	{
		return Math.max(0L, relaxUntilMs - now);
	}

	/**
	 * Why this rule should stay quiet right now, or null to let it speak.
	 *
	 * <p>Only ever consulted for rules that actually say something; an
	 * animation-only firing is movement rather than chatter and never reaches
	 * here.
	 */
	public String blocks(SpeechRule rule, long now)
	{
		if (!isPacing())
		{
			return null;
		}
		if (isRelaxing(now) && !rule.isOccasion())
		{
			return "relax";
		}
		if (isSettlingIn() && isLore(rule) && now - lastLoreMs < baseGapMs * SETTLING_LORE_GAPS)
		{
			return "settling";
		}
		return null;
	}

	static boolean isLore(SpeechRule rule)
	{
		return rule.group != null
			&& LORE_GROUPS.contains(rule.group.toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Records that a line was actually said, and starts a relax period if that
	 * was one line too many.
	 *
	 * <p>Counted at the point of SPEAKING rather than of winning, for the same
	 * reason the mood is: a line the mute swallowed cost the player no
	 * attention, and resting afterwards would be resting from nothing.
	 */
	public void noteSpoke(SpeechRule rule, long now)
	{
		if (!isPacing())
		{
			return;
		}
		decayTo(now);

		if (isLore(rule))
		{
			lastLoreMs = now;
		}

		// An occasion is the thing the quiet was being saved for. Letting it
		// also build towards the next quiet would have the follower punish
		// itself for the one line it most wanted to say.
		if (rule.isOccasion())
		{
			return;
		}

		intensity += RISE_PER_LINE;
		if (intensity >= PEAK_AT)
		{
			intensity = 0.0;
			relaxUntilMs = now + RELAX_MIN_MS
				+ (long) ThreadLocalRandom.current().nextInt((int) (RELAX_MAX_MS - RELAX_MIN_MS) + 1);
		}
	}

	private void decayTo(long now)
	{
		if (lastDecayMs == 0L)
		{
			lastDecayMs = now;
			return;
		}
		long elapsed = now - lastDecayMs;
		lastDecayMs = now;
		if (elapsed <= 0L)
		{
			return;
		}
		intensity = Math.max(0.0, intensity - elapsed / (DECAY_GAPS * baseGapMs));
	}

	public void reset()
	{
		intensity = 0.0;
		lastDecayMs = 0L;
		relaxUntilMs = 0L;
		lastLoreMs = 0L;
	}
}
