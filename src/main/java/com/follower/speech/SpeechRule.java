package com.follower.speech;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One trigger -> phrase rule, as authored in phrases.json.
 *
 * <p>A rule fires on the <em>rising edge</em> of its {@code when} block: the tick it
 * becomes true having been false. That means {@code healthBelow: 30} speaks once
 * when you drop under 30%, not every tick you spend there.
 */
public class SpeechRule
{
	/** Stable identifier; used for cooldown bookkeeping and log messages. */
	public String id;

	/** Free text for your own benefit. Ignored by the plugin. */
	public String note;

	/** Group name, so the config panel can switch whole categories off. */
	public String group = "misc";

	public Boolean enabled = Boolean.TRUE;

	/** Highest priority wins when several rules fire on the same event. */
	public int priority = 50;

	/** Minimum gap between firings of this rule. */
	public long cooldownMs = 10_000L;

	/**
	 * Game ticks to wait between the trigger and the line - a companion
	 * NOTICING something has a beat before the comment, where an instant
	 * reaction reads as scripted. The suppression checks (mute, global speech
	 * window) apply when the delay expires, not when it is queued.
	 */
	public Integer delayTicks;

	/**
	 * Upper bound for a VARIABLE delay: when set, each firing waits a fresh
	 * number of ticks drawn between {@link #delayTicks} and this, inclusive.
	 *
	 * <p>A fixed delay still lands on the same beat every time, which reads as
	 * machinery once you have seen it twice - most obvious when the follower
	 * copies an emote, where a constant offset looks like a delay line rather
	 * than someone joining in. Leave it unset for a fixed wait.
	 */
	public Integer delayTicksMax;

	/** Overrides the config default. One of overhead / chatbox / both. */
	public String output;

	/** Optional emote animation id to play alongside the phrase. */
	public Integer animation;

	/**
	 * Replay the PLAYER'S animation on the follower instead of a fixed id. Only
	 * meaningful when the rule triggers on an animation event ({@code animationSelf});
	 * with it, one rule mirrors every teleport in its id list without hardcoding
	 * which animation the follower answers with.
	 */
	public Boolean mirrorAnimation;

	/**
	 * Mirror the player's animation as a held POSE rather than a one-shot, for
	 * the emotes you can keep doing until you stop.
	 *
	 * <p>The game splits those in two: clicking Dance plays an entry clip and
	 * then loops a second animation for as long as you hold it. The entry is a
	 * one-shot and {@link #mirrorAnimation} handles it; the loop never ends, so
	 * playing it as an emote would wait forever for a finish that never comes
	 * and leave the follower stuck. A pose override loops by nature and is
	 * released when the player's animation changes, which is what "until I am
	 * done" actually means.
	 */
	public Boolean mirrorPose;

	/**
	 * A CHAIN of animation ids played back to back - for sequences authored as
	 * separate clips, like the home teleport's five stages. Takes precedence
	 * over {@link #animation} and {@link #mirrorAnimation}.
	 */
	public List<Integer> animations;

	/**
	 * Spotanim (graphic) ids paired stage-for-stage with {@link #animations}:
	 * each fires as its stage begins. -1 skips a stage. The home teleport pairs
	 * its rune-circle pieces this way.
	 */
	public List<Integer> graphics;

	/**
	 * Chain pacing: {@code true} advances each stage when the PLAYER's animation
	 * steps to its next stage - the server's own schedule - instead of when the
	 * follower's clip runs out. Teleport clips end in long hold frames the
	 * server always cuts short, so clip-length pacing freezes between stages.
	 */
	public Boolean syncToPlayer;

	/**
	 * Teleport exit: when the follower's mirrored animation ends, it VANISHES -
	 * as the player does at the end of their cast - and reappears beside them
	 * once they land. An interrupted cast (the animation gets cancelled)
	 * disarms it, keeping the follower where it stands.
	 */
	public Boolean vanishAfter;

	/**
	 * Plant the follower where it stands for the length of the animation, and
	 * let it follow again once the animation ends.
	 *
	 * <p>Movement always wins over an emote, so an animation played while the
	 * follower is walking is cut off the moment it starts. That is most of the
	 * time for anything triggered by a fight: it walks back to you the instant
	 * the fight is over. Without this, a celebration is a celebration nobody
	 * ever sees.
	 */
	public Boolean holdStill;

	/**
	 * How much this firing moves the follower's mood, positive or negative.
	 *
	 * <p>This is the whole mechanism by which anything affects mood: there is
	 * no list of significant events in the code. A rule that already knows how
	 * to notice a boss dying or a death or a good drop is exactly the thing
	 * that should say how much it mattered, and a new influence is a line in
	 * this file rather than a change to the engine.
	 *
	 * <p>Applied when the rule actually fires, so a rule suppressed by its
	 * cooldown or a disabled group does not move the mood either - the
	 * follower's state and what it says stay the same story.
	 */
	public Integer mood;

	/**
	 * Marks this line as a question, and names the dialog tree that answers it.
	 *
	 * <p>The value is a tree id from dialogs.json. While the question is open,
	 * right-clicking Talk-to opens THAT conversation instead of the everyday
	 * one, and picking the branch marked with an answer is what replies.
	 *
	 * <p>The answer used to be a word typed into public chat. That asked the
	 * player to know a magic word, and it asked the follower to listen to
	 * everything said near it - in a crowd, somebody else's "yes" was
	 * indistinguishable from yours. A menu of options can be neither misheard
	 * nor guessed at.
	 *
	 * <p>The window is opened by SAYING it, not by winning: a question the mute
	 * swallowed is one the player never heard.
	 */
	public String asks;

	/**
	 * Takes the floor: nothing else speaks for this many milliseconds.
	 *
	 * <p>For the handful of moments worth more than whatever the follower was
	 * otherwise about to say. Arriving where it asked to be taken is the clear
	 * case - the region change raises an area line at the same instant, and two
	 * lines about one arrival is one too many.
	 *
	 * <p>The floor is taken when the rule WINS rather than when it speaks, or a
	 * rule with a delay would leave its own gap open for exactly the line it
	 * meant to displace. The holder is also exempt from the global speech gap,
	 * since being held back by whatever spoke a second earlier is the same
	 * failure from the other end.
	 *
	 * <p>Only speech is hushed. An animation-only rule is movement rather than
	 * chatter and carries on regardless.
	 */
	public Integer hushMs;

	/**
	 * Somewhere the follower would like to go, expressed as part of saying so.
	 *
	 * <p>{@code {"region": 10553, "label": "the fishing guild", "minutes": 20}}.
	 * Going there before the deadline fulfils it; the deadline passing expires
	 * it. Either way a rule gets to react, and the {want} placeholder carries
	 * the label - so the follower can be pleased about the specific thing it
	 * asked for rather than pleased in general.
	 */
	public Want want;

	public static final class Want
	{
		public Integer region;
		public String label;
		public Integer minutes;
	}

	/**
	 * Files what just happened as the incident worth bringing up again.
	 *
	 * <p>{@code {"key": "chicken-death", "as": "that chicken"}}. The key is what
	 * a later rule recognises; the phrase is how the follower says it out loud,
	 * via {memory}.
	 *
	 * <p>This is the difference between counting and remembering. A tally makes
	 * "your four hundredth yew" possible, which is data delivered well. An
	 * incident makes "careful, I've seen what a chicken can do to you"
	 * possible, which is a shared history - and only one of those sounds like
	 * somebody who was there.
	 */
	public Incident remember;

	public static final class Incident
	{
		public String key;
		public String as;
	}

	/**
	 * Something the follower picks up and carries about for a while.
	 *
	 * <p>{@code {"what": "a nice flat rock", "minutes": 30}}. Nothing else it
	 * owns is an object: everything else is a number, a mood or a place.
	 */
	public Souvenir pickUp;

	public static final class Souvenir
	{
		public String what;
		public Integer minutes;
	}

	/**
	 * A prediction about what the next drop is worth.
	 *
	 * <p>{@code {"rich": false, "threshold": 50000, "minutes": 5}} is the
	 * follower betting the next drop comes in UNDER fifty thousand. Being wrong
	 * is the better half - anything that can only be right is not predicting.
	 */
	public Bet bet;

	public static final class Bet
	{
		public Boolean rich;
		public Integer threshold;
		public Integer minutes;
	}

	public Condition when;

	/**
	 * Phrases to choose from. One is picked at random, avoiding an immediate repeat.
	 * Optional when the rule plays an animation: an animation-only rule is a valid
	 * rule with no speech at all.
	 */
	public List<String> say;

	// ---- runtime state, not serialised ----
	private transient boolean lastState;
	private transient long lastFiredMs;
	private transient int lastPhraseIndex = -1;

	public boolean isEnabled()
	{
		return enabled == null || enabled;
	}

	public boolean isValid()
	{
		return when != null && (hasSpeech() || hasAnimationAction());
	}

	public boolean hasSpeech()
	{
		return say != null && !say.isEmpty();
	}

	/**
	 * How many ticks THIS firing waits: {@link #delayTicks}, or a fresh draw
	 * between it and {@link #delayTicksMax} when that is set. Rolled once per
	 * firing, so the wait is decided at the win and not re-rolled while the
	 * firing sits pending.
	 */
	public int rollDelayTicks()
	{
		int min = delayTicks == null ? 0 : Math.max(0, delayTicks);
		int max = delayTicksMax == null ? min : Math.max(min, delayTicksMax);
		return max > min
			? min + java.util.concurrent.ThreadLocalRandom.current().nextInt(max - min + 1)
			: min;
	}

	public boolean hasAnimationAction()
	{
		return animation != null
			|| Boolean.TRUE.equals(mirrorAnimation)
			|| Boolean.TRUE.equals(mirrorPose)
			|| hasAnimationChain();
	}

	public boolean hasAnimationChain()
	{
		return animations != null && !animations.isEmpty();
	}

	/**
	 * The animation this firing should play, or null for none: the fixed
	 * {@link #animation} id, unless {@link #mirrorAnimation} is set and the
	 * triggering event carries the player's animation to copy.
	 */
	public Integer resolveAnimation(TriggerEvent event)
	{
		if ((Boolean.TRUE.equals(mirrorAnimation) || Boolean.TRUE.equals(mirrorPose))
			&& event != null
			&& event.getType() == TriggerEvent.Type.ANIMATION
			&& event.getId() != -1)
		{
			return event.getId();
		}
		return animation;
	}

	/**
	 * Updates the stored edge state and reports whether this evaluation is a rising
	 * edge. Always call exactly once per evaluation pass, even when the rule is
	 * going to be skipped for cooldown, or the edge tracking drifts.
	 */
	public boolean risingEdge(boolean nowMatching)
	{
		boolean rising = nowMatching && !lastState;
		lastState = nowMatching;
		return rising;
	}

	public boolean offCooldown(long nowMs)
	{
		return nowMs - lastFiredMs >= cooldownMs;
	}

	public void markFired(long nowMs)
	{
		lastFiredMs = nowMs;
	}

	public void reset()
	{
		lastState = false;
		lastFiredMs = 0L;
		lastPhraseIndex = -1;
	}

	public String pickPhrase()
	{
		if (say == null || say.isEmpty())
		{
			return "";
		}
		if (say.size() == 1)
		{
			return say.get(0);
		}

		int index;
		do
		{
			index = ThreadLocalRandom.current().nextInt(say.size());
		}
		while (index == lastPhraseIndex);

		lastPhraseIndex = index;
		return say.get(index);
	}

	public String describe()
	{
		return id == null ? "(unnamed rule in group " + group + ")" : id;
	}
}
