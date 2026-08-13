package com.follower.speech;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * Evaluates every rule against every incoming event and speaks the winner.
 *
 * <p>The model is deliberately uniform: there is no separate notion of "event
 * rules" and "state rules". Every rule is evaluated on every event (including the
 * synthetic per-tick event) and fires on the rising edge of its condition. Event
 * conditions such as {@code npcSpawn} are only true while that event is being
 * processed, so they behave as edges naturally; state conditions such as
 * {@code healthBelow} stay true and therefore fire once per crossing.
 */
@Slf4j
@Singleton
public class SpeechEngine
{
	/**
	 * Callback for delivering a firing. Implemented by the plugin. {@code text}
	 * may be empty (an animation-only rule) and {@code animationId} is -1 when
	 * the rule plays nothing.
	 *
	 * <p>{@code onSaid} is the moment the line actually reaches the player. A
	 * sink that shows lines immediately runs it before returning; a sink that
	 * queues them runs it when the line takes the floor, and never for a line
	 * it drops. The engine hangs the states that OPEN something - a question,
	 * a want, a wish - on this, because each of those later puts an
	 * interaction in front of the player that only makes sense if the opening
	 * line was seen.
	 */
	public interface Sink
	{
		void speak(String text, SpeechOutput output, SpeechRule rule, int animationId,
			Runnable onSaid);
	}

	private final Client client;
	private final RuleLoader loader;

	private TriggerContext context;

	@Setter
	private Sink sink;

	/** Groups the config has switched off. Rule ids are matched too, for one-offs. */
	@Setter
	private Set<String> disabledGroups = Collections.emptySet();

	@Setter
	private SpeechOutput defaultOutput = SpeechOutput.OVERHEAD;

	/** Nothing speaks within this window of the previous line, whatever the rule. */
	private long globalCooldownMs = 3_000L;

	public void setGlobalCooldownMs(long globalCooldownMs)
	{
		this.globalCooldownMs = globalCooldownMs;
		director.setBaseGapMs(globalCooldownMs);
	}

	/**
	 * How much has been said lately, and whether it is time to stop for a bit.
	 * The gap above is a floor between two lines; this is a budget across many.
	 */
	@Getter
	private final SpeechDirector director = new SpeechDirector();

	/**
	 * How many unprompted idle remarks the current stretch of standing still
	 * has already produced, and when the last one was.
	 *
	 * <p>The director cannot see this problem. It handles BURSTS - intensity
	 * that rises faster than it decays - and a line every forty-five seconds
	 * decays completely between lines, so an idle afternoon sails under its
	 * peak forever while still producing eighty lines an hour. Measured, that
	 * is what a long AFK sounded like: the longest silence in forty minutes
	 * was two.
	 *
	 * <p>A companion does not drip at a fixed rate. It chats when you first
	 * stop, and then it runs out of things to say about standing still. So the
	 * first few remarks of a stretch are free, and each one after that needs a
	 * longer wait than the one before - the follower audibly winding down -
	 * until anything at all happens and the count starts over.
	 *
	 * <p>"Unprompted" is precise: a rule whose condition mentions {@code idle}
	 * is speaking because you are standing still, and is throttled. A reaction
	 * to something real - a cat, a passer-by, an errand - is not, because
	 * answering the world is not chatter. Occasions are exempt as everywhere.
	 */
	private int idleStretchSpoken;
	private long lastIdleRemarkMs;

	/** Idle remarks per stretch before the winding-down starts. */
	private static final int IDLE_FREE_REMARKS = 4;

	/** Each remark past the free ones waits this many gaps longer than the last. */
	private static final long TRAIL_STEP_GAPS = 30;

	/** The wind-down stops growing here: never rarer than one per this many gaps. */
	private static final long TRAIL_CAP_GAPS = 200;

	private boolean isIdleRemark(SpeechRule rule)
	{
		return rule.when != null && rule.when.usesType("idle") && !rule.isOccasion();
	}

	/**
	 * The events that end an idle stretch.
	 *
	 * <p>NOT movement. The first version reset when the idle counter did, and
	 * that would have made the whole mechanism a no-op in exactly the session
	 * it was built from: a player pottering about a bank moves every few
	 * seconds, and each little stop re-arms the idle rules' rising edges -
	 * that is precisely HOW an "idle" session produces a line every forty-five
	 * seconds. Shuffling two tiles is not doing something. Fighting, stealing,
	 * skilling, killing, arriving somewhere new, or dying is.
	 */
	private static final java.util.Set<TriggerEvent.Type> REAL_ACTIVITY =
		java.util.EnumSet.of(
			TriggerEvent.Type.ANIMATION,
			TriggerEvent.Type.COMBAT_START,
			TriggerEvent.Type.THIEVING_START,
			TriggerEvent.Type.NPC_KILL,
			TriggerEvent.Type.LOOT,
			TriggerEvent.Type.LEVEL_UP,
			TriggerEvent.Type.REGION_CHANGE,
			TriggerEvent.Type.PLAYER_DEATH,
			TriggerEvent.Type.WANT_FULFILLED);

	/** How long the NEXT idle remark has to wait, given the stretch so far. */
	private long trailWaitMs()
	{
		if (idleStretchSpoken < IDLE_FREE_REMARKS)
		{
			return 0L;
		}
		long steps = idleStretchSpoken - IDLE_FREE_REMARKS + 1;
		return Math.min(TRAIL_CAP_GAPS, steps * TRAIL_STEP_GAPS) * globalCooldownMs;
	}

	@Setter
	private boolean muted;

	/**
	 * Where "now" comes from.
	 *
	 * <p>Every window in this class is wall-clock - the speech gap, the held
	 * floor, each rule's cooldown, the director's rest - which made all of them
	 * untestable together. A simulation runs ten thousand ticks a second, so it
	 * sits inside the first rest period for its entire length and proves
	 * nothing about what happens after one. Real seconds cannot be spent
	 * waiting and a fake clock is the only honest way to walk hours of them.
	 *
	 * <p>Defaults to the real one and is only ever replaced by tests.
	 */
	private java.util.function.LongSupplier clock = System::currentTimeMillis;

	public void setClock(java.util.function.LongSupplier clock)
	{
		this.clock = clock == null ? System::currentTimeMillis : clock;
	}

	private long now()
	{
		return clock.getAsLong();
	}

	@Getter
	private long lastSpokeMs;

	@Getter
	private String lastSpokenText = "";

	/**
	 * How many lines back the follower remembers having said, across every rule.
	 *
	 * <p>A shuffle bag can only keep one rule honest about itself. It cannot
	 * stop two rules that were written about the same thing - and there are
	 * plenty, since a companion notices standing still in several different
	 * ways - from handing the same observation back and forth. This is the
	 * window that does.
	 *
	 * <p>Twelve is a compromise with a real cost either way: too short and the
	 * ping-pong survives, too long and a rule with two variants finds both of
	 * them blocked and simply repeats anyway, having spent the effort.
	 */
	private static final int RECENT_LINES = 12;

	/**
	 * The last {@link #RECENT_LINES} things said, as TEMPLATES rather than as
	 * finished text. "{count}. I've been keeping track" is one line however
	 * many different numbers it has carried, and keying on the rendered version
	 * would let it through every time the count changed - which is every time.
	 */
	private final java.util.ArrayDeque<String> recentLines = new java.util.ArrayDeque<>();
	private final Set<String> recentLineSet = new java.util.HashSet<>();

	private void noteRecentLine(String template)
	{
		if (template == null || template.isEmpty() || !recentLineSet.add(template))
		{
			// Already in the window: move it to the front by dropping the old
			// entry, or a line said twice would leave the window early.
			if (template != null && !template.isEmpty())
			{
				recentLines.remove(template);
				recentLines.addLast(template);
			}
			return;
		}
		recentLines.addLast(template);
		while (recentLines.size() > RECENT_LINES)
		{
			recentLineSet.remove(recentLines.removeFirst());
		}
	}

	@Inject
	public SpeechEngine(Client client, RuleLoader loader)
	{
		this.client = client;
		this.loader = loader;
	}

	public TriggerContext getContext()
	{
		if (context == null)
		{
			context = new TriggerContext(client);
		}
		return context;
	}

	/** Refreshes the state snapshot. Call once per game tick, before dispatching. */
	public void refreshContext()
	{
		TriggerContext context = getContext();
		context.refresh();

		// Synced here rather than pushed from wherever the count is loaded or
		// bumped, because there are several such places and the settling-in
		// damper being right depends on all of them remembering.
		director.setSessionCount(context.getSessionCount());


		// A want resolving is state becoming an event. It happens here rather
		// than in the plugin so the rules see it wherever the engine is driven
		// from, and exactly once - the poll consumes it.
		TriggerContext.WantOutcome outcome = context.pollWant();
		if (outcome != null)
		{
			// Counted as well as announced. A grudge then needs no machinery of
			// its own: "you have ignored me three times" is a tally condition,
			// and lives in phrases.json with everything else.
			context.tally(outcome == TriggerContext.WantOutcome.FULFILLED
				? "want:kept" : "want:missed");
			dispatch(TriggerEvent.want(
				outcome == TriggerContext.WantOutcome.FULFILLED
					? TriggerEvent.Type.WANT_FULFILLED
					: TriggerEvent.Type.WANT_EXPIRED,
				context.getWantLabel()));
		}

		// The souvenir going, and a prediction coming good or not. Same shape
		// as the want: state becoming an event, consumed exactly once.
		if (context.pollSouvenirLost())
		{
			dispatch(TriggerEvent.souvenirLost(context.getSouvenir()));
			context.clearSouvenir();
		}
		if (context.pollUnderfoot())
		{
			dispatch(TriggerEvent.simple(TriggerEvent.Type.UNDERFOOT));
		}

		TriggerContext.ChallengeOutcome challenge = context.pollChallenge();
		if (challenge != null)
		{
			dispatch(TriggerEvent.challenge(
				challenge == TriggerContext.ChallengeOutcome.MET
					? TriggerEvent.Type.CHALLENGE_MET
					: TriggerEvent.Type.CHALLENGE_FAILED,
				context.getChallengeAbout()));
		}

		TriggerContext.AdviceOutcome advice = context.pollAdvice();
		if (advice != null)
		{
			// Counted as well as announced, like a want, so "you never listen"
			// can be a tally condition rather than machinery of its own.
			context.tally(advice == TriggerContext.AdviceOutcome.HEEDED
				? "advice:taken" : "advice:ignored");
			dispatch(TriggerEvent.advice(
				advice == TriggerContext.AdviceOutcome.HEEDED
					? TriggerEvent.Type.ADVICE_HEEDED
					: TriggerEvent.Type.ADVICE_IGNORED,
				context.getAdviceAbout()));
		}

		TriggerContext.BetOutcome bet = context.pollBet();
		if (bet != null)
		{
			dispatch(TriggerEvent.bet(bet == TriggerContext.BetOutcome.WON
				? TriggerEvent.Type.BET_WON : TriggerEvent.Type.BET_LOST));
		}
	}

	/**
	 * Drops the held floor.
	 *
	 * <p>Must happen on a rule reload. The exemption that lets the holder speak
	 * through its own hush is identity-based, and a reload parses every rule
	 * afresh - so the holder becomes an object nothing points at any more, and
	 * the hush it left behind silences the whole file until it times out.
	 */
	public void clearFloor()
	{
		hushUntilMs = 0L;
		hushOwner = null;
	}

	/**
	 * Clears everything mid-flight but KEEPS the state snapshot.
	 *
	 * <p>For a world hop, where the scene goes and the player does not. What
	 * the follower is feeling, hoping for and counting are facts about the
	 * session rather than about the scene, and throwing them away because the
	 * world number changed is how a want quietly disappears on the way
	 * somewhere.
	 */
	public void resetForNewScene()
	{
		lastSpokeMs = 0L;
		pending.clear();
		hushUntilMs = 0L;
		hushOwner = null;
		for (SpeechRule rule : loader.getRules())
		{
			// Edges only. The scene changed; what the follower has already said
			// did not, and neither did the player's memory of hearing it.
			rule.resetEdges();
		}
	}

	public void reset()
	{
		context = null;
		lastSpokeMs = 0L;
		director.reset();
		recentLines.clear();
		recentLineSet.clear();
		idleStretchSpoken = 0;
		lastIdleRemarkMs = 0L;
		pending.clear();
		// A held floor must not outlive the thing that held it: toggling the
		// plugin mid-hush would otherwise leave the follower mute for it.
		hushUntilMs = 0L;
		hushOwner = null;
		for (SpeechRule rule : loader.getRules())
		{
			rule.reset();
		}
	}

	/** A won rule waiting out its delayTicks before speaking. */
	private static final class PendingSpeech
	{
		final SpeechRule rule;
		final TriggerEvent event;
		/** The wait as drawn at the win - kept for the log, since a ranged delay is not on the rule. */
		final int delay;
		int ticksLeft;

		PendingSpeech(SpeechRule rule, TriggerEvent event, int ticksLeft)
		{
			this.rule = rule;
			this.event = event;
			this.delay = ticksLeft;
			this.ticksLeft = ticksLeft;
		}
	}

	private final java.util.List<PendingSpeech> pending = new java.util.ArrayList<>();

	/**
	 * Set at login: evaluations record every rule's edge state without firing
	 * until a couple of ticks have passed WITH the player's composition
	 * actually readable. The world as found at spawn is baseline, not news - a
	 * rule should react to what CHANGES afterwards (gear equipped mid-session),
	 * not to whatever the player logged in wearing.
	 *
	 * <p>Two subtleties bought by bugs: the composition can lag several ticks
	 * behind login, so the countdown only consumes ticks the context reports
	 * ready; and edges rise on ANY dispatch (the login welcome chat message,
	 * an animation), so priming gates every event, not just the tick
	 * heartbeat. Delayed firings already queued (the login greeting) ride the
	 * pending path and are unaffected.
	 */
	private int primeTicksLeft;

	public void primeEdgesOnNextTick()
	{
		primeTicksLeft = 2;
	}

	/**
	 * When the floor is held, and by whom.
	 *
	 * <p>A handful of moments are worth more than whatever else the follower
	 * was about to say. Walking into the place it asked to be taken is the
	 * clearest: the region change raises an area line at the same instant, and
	 * two lines about the same arrival is one line too many - the wrong one
	 * usually winning, since the area rule has no delay and gets there first.
	 */
	private long hushUntilMs;
	private SpeechRule hushOwner;

	/**
	 * Told when a rule won its event and was held back anyway. Set by the
	 * plugin so the transcript can see the half of the story the player never
	 * does; left null everywhere else, including in tests.
	 */
	@Setter
	private java.util.function.BiConsumer<SpeechRule, String> onSuppressed;

	private void noteSuppressed(SpeechRule rule, String reason)
	{
		if (onSuppressed != null)
		{
			onSuppressed.accept(rule, reason);
		}
	}

	/**
	 * Whether this rule is allowed to say its line right now.
	 *
	 * <p>The mute and the global window throttle SPEECH; a rule that only plays
	 * an animation (teleport mirroring, the flinch) is not chatter and skips
	 * both. The rule holding the floor skips them too - it is the reason
	 * everything else is quiet, so being caught by its own hush, or held back
	 * by whatever spoke a second before it, would defeat the point.
	 */
	private boolean cannotSpeak(SpeechRule rule, long now)
	{
		return blockedBy(rule, now) != null;
	}

	/**
	 * WHY this rule cannot speak, or null if it can.
	 *
	 * <p>Same decision as {@link #cannotSpeak}, phrased so the transcript can
	 * record the reason. A held-back line never reaches the player and so never
	 * reaches an impression, which makes it exactly the evidence needed to tell
	 * a follower with little to say from one that is being throttled.
	 */
	private String blockedBy(SpeechRule rule, long now)
	{
		if (!rule.hasSpeech())
		{
			return null;
		}
		if (rule == hushOwner && now < hushUntilMs)
		{
			return muted ? "muted" : null;
		}
		if (muted)
		{
			return "muted";
		}
		// Before the gap, because when both apply the director is the honest
		// answer: the gap is three seconds and would be gone by the next tick,
		// where a relax period is most of a minute and is the reason nothing
		// else got through either.
		String directed = director.blocks(rule, now);
		if (directed != null)
		{
			return directed;
		}
		if (isIdleRemark(rule) && now - lastIdleRemarkMs < trailWaitMs())
		{
			return "trailing";
		}
		if (now - lastSpokeMs < moodScaledGap())
		{
			return "gap";
		}
		if (now < hushUntilMs)
		{
			return "hush";
		}
		return null;
	}

	/**
	 * Whether this firing says anything about WHERE it happened.
	 *
	 * <p>Two things have to be kept out of the place score, and neither is
	 * obvious from the mood value alone.
	 *
	 * <p>The first is the loop. The rules that remark on liking or disliking
	 * somewhere are themselves worth +10 and -8, so feeding those back would
	 * have the follower talk itself into an ever firmer opinion about a place
	 * on no evidence but its own earlier opinion.
	 *
	 * <p>The second is everything that is about the SESSION rather than the
	 * world. Logging in after a day away is worth +8, and it happens wherever
	 * you happened to log out - which is the same tile every time. Left in, the
	 * single most-loved place in the game would reliably be your bank.
	 */
	private boolean belongsToThisPlace(SpeechRule rule, TriggerEvent event)
	{
		if (rule.when != null && rule.when.usesType("feelsAbout"))
		{
			return false;
		}
		switch (event.getType())
		{
			// Things that happened to you, here.
			case TICK:
			case VARBIT:
			case PLAYER_DEATH:
			case NPC_KILL:
			case NPC_SPAWN:
			case LOOT:
			case LEVEL_UP:
			case DAMAGE_TAKEN:
			case COMBAT_START:
			case COMBAT_END:
			case THIEVING_START:
			case THIEVING_END:
			case REGION_CHANGE:
			case WANT_FULFILLED:
				return true;
			// Things about the session, the conversation, or the follower's
			// own belongings, which would happen the same anywhere.
			default:
				return false;
		}
	}

	/**
	 * The speech gap, stretched or shortened by how the follower feels.
	 *
	 * <p>Mood already decides WHAT it says. Until now it had no say in HOW MUCH,
	 * so a follower having a bad day recited sad lines at exactly the rate it
	 * recited cheerful ones - which reads as a costume rather than a mood.
	 * Somebody in a bad way talks less. Somebody in a good one talks over you.
	 *
	 * <p>Applied to the shared gap rather than to any rule, so it thins out
	 * everything evenly instead of silencing particular things: the follower
	 * still says what matters, just with more room around it.
	 */
	private long moodScaledGap()
	{
		return gapForMood(globalCooldownMs, getContext().getMoodBand());
	}

	/**
	 * The mapping itself, as a function of the band rather than of the engine,
	 * so a test can walk every band without having to make real seconds pass.
	 */
	static long gapForMood(long base, String band)
	{
		switch (band == null ? "" : band)
		{
			case "low":
				return base * 5 / 2;
			case "down":
				return base * 3 / 2;
			case "good":
				return base * 4 / 5;
			case "high":
				return base * 3 / 5;
			default:
				return base;
		}
	}

	/**
	 * Runs one evaluation pass. Every rule's edge state is updated regardless of
	 * whether it can speak, so cooldowns and mutes never desynchronise the edges.
	 */
	public void dispatch(TriggerEvent event)
	{
		long now = now();

		// An animation the player played may be them doing the thing they were
		// just told to. Offered before the rules run, so the settling and the
		// reaction land on the same tick rather than a tick apart.
		if (event.getType() == TriggerEvent.Type.ANIMATION && event.getId() != -1)
		{
			getContext().offerAct(event.getId());
		}

		// Doing something real ends the idle stretch, and the follower perks
		// back up. Checked on the event rather than on movement - see the note
		// on REAL_ACTIVITY for why a step is not enough.
		if (REAL_ACTIVITY.contains(event.getType()))
		{
			idleStretchSpoken = 0;
		}

		// Delayed firings count down on the tick heartbeat and speak through
		// the same guarded path as everything else when their beat arrives.
		if (event.getType() == TriggerEvent.Type.TICK && !pending.isEmpty())
		{
			java.util.Iterator<PendingSpeech> it = pending.iterator();
			while (it.hasNext())
			{
				PendingSpeech delayed = it.next();
				if (--delayed.ticksLeft <= 0)
				{
					it.remove();
					String held = blockedBy(delayed.rule, now);
					if (held == null)
					{
						log.debug("rule '{}' fired after its {}-tick delay",
							delayed.rule.describe(), delayed.delay);
						speak(delayed.rule, delayed.event, now);
					}
					else
					{
						noteSuppressed(delayed.rule, held);
					}
				}
			}
		}

		SpeechRule winner = null;

		for (SpeechRule rule : loader.getRules())
		{
			boolean matching;
			try
			{
				matching = rule.when.matches(getContext(), event);
			}
			catch (RuntimeException e)
			{
				log.warn("Rule '{}' threw while evaluating; disabling it", rule.describe(), e);
				rule.enabled = Boolean.FALSE;
				continue;
			}

			boolean rising = rule.risingEdge(matching);

			if (!rising || !isActive(rule) || !rule.offCooldown(now))
			{
				continue;
			}

			// Belt and braces: the loader already hands rules over sorted by
			// priority descending, so the comparison can never actually differ
			// from "first match wins". Both are kept deliberately - the sort is
			// what makes this loop cheap, the comparison is what makes the
			// invariant true independently of it. Mutating either alone leaves
			// the behaviour correct, which is worth knowing before anyone
			// "simplifies" one of them away.
			if (winner == null || rule.priority > winner.priority)
			{
				winner = rule;
			}
		}

		// While primed (just after login), edges have been recorded above but
		// nothing fires from ANY event: worn gear and standing state register
		// as already-true instead of as fresh rising edges. The countdown only
		// consumes ticks where the player composition is actually readable -
		// before that, "no equipment" is an artefact of loading, not a state.
		if (primeTicksLeft > 0)
		{
			if (event.getType() == TriggerEvent.Type.TICK && getContext().isPlayerReady())
			{
				primeTicksLeft--;
			}
			return;
		}

		if (winner == null)
		{
			return;
		}

		// Taking the floor happens at the WIN, not when the line finally comes
		// out. A rule with a delay would otherwise leave its own gap open: the
		// want-fulfilled line waits a few ticks, and the region-change line
		// that arrives later in the same tick would walk straight into it.
		if (winner.hushMs != null && winner.hushMs > 0 && winner.hasSpeech())
		{
			hushUntilMs = now + winner.hushMs;
			hushOwner = winner;
		}

		// A delayed rule queues instead of speaking now; the cooldown is
		// charged at the win so re-triggers don't stack up more firings. The
		// wait is drawn HERE, once, so a ranged delay stays put while pending.
		int delay = winner.rollDelayTicks();
		if (delay > 0)
		{
			winner.markFired(now);
			pending.add(new PendingSpeech(winner, event, delay));
			return;
		}

		String blocked = blockedBy(winner, now);
		if (blocked != null)
		{
			// Still charge the cooldown so a suppressed rule doesn't fire the instant
			// the global window opens.
			log.debug("rule '{}' won but was suppressed ({})", winner.describe(), blocked);
			noteSuppressed(winner, blocked);
			winner.markFired(now);
			return;
		}

		log.debug("rule '{}' fired on {}", winner.describe(), event.getType());
		speak(winner, event, now);
	}

	/** Speaks an arbitrary line, bypassing rules — used by the chat commands. */
	public void say(String text, SpeechOutput output)
	{
		if (sink == null || text == null || text.isEmpty())
		{
			return;
		}
		lastSpokeMs = now();
		lastSpokenText = text;
		sink.speak(text, output == null ? defaultOutput : output, null, -1, () -> { });
	}

	/**
	 * Says a rule this instant, whatever its conditions, cooldown and the mute
	 * have to say about it.
	 *
	 * <p>For testing a rule you cannot conveniently provoke. Several of them
	 * only happen on a five percent roll after a minute of standing still, or
	 * on the hundredth login, and waiting for one of those in a live client is
	 * not testing, it is hoping.
	 *
	 * <p>Everything the rule carries still happens - the animation, the mood
	 * nudge, {@code asks}, {@code want} - because the point is to exercise the
	 * whole path rather than only the words. Placeholders that come from an
	 * event ({npc}, {want}, {value}) have no event to come from here and will
	 * print literally; that is the honest cost of firing something out of
	 * nowhere.
	 *
	 * @return whether a rule by that id exists
	 */
	public boolean force(String ruleId)
	{
		for (SpeechRule rule : loader.getRules())
		{
			if (ruleId.equalsIgnoreCase(rule.id))
			{
				long now = now();
				// Including the floor, so what you see when testing is what the
				// rule really does rather than a quieter version of it.
				if (rule.hushMs != null && rule.hushMs > 0 && rule.hasSpeech())
				{
					hushUntilMs = now + rule.hushMs;
					hushOwner = rule;
				}
				speak(rule, TriggerEvent.simple(TriggerEvent.Type.MANUAL), now);
				return true;
			}
		}
		return false;
	}

	private void speak(SpeechRule rule, TriggerEvent event, long now)
	{
		String template = rule.pickPhrase(recentLineSet,
			line -> getContext().lineWear(line));
		String text = substitute(template, event);
		Integer animation = rule.resolveAnimation(event);

		// A mirroring rule triggered by something OTHER than an animation event
		// - the repeating condition, which is how the follower joins in with
		// what the player is doing - has no id on the event to copy. The
		// player's current repeating animation is the thing being copied, and
		// only the state snapshot knows it.
		//
		// Which of the two the rule asks for matters, and the cache decides it:
		// mining, woodcutting, fishing and fletching are authored loops
		// (frameStep >= 0) and have to be held as a POSE, because playing one
		// as an emote waits forever for an end that never comes and leaves the
		// follower stuck mid-swing. Cooking, smithing, herblore and firemaking
		// are one-shots the server restarts each cycle, and mirrorAnimation
		// gives those a single honest go.
		if (animation == null
			&& (Boolean.TRUE.equals(rule.mirrorPose) || Boolean.TRUE.equals(rule.mirrorAnimation)))
		{
			int repeating = getContext().getRepeatingAnimation();
			animation = repeating > 0 ? repeating : null;
		}
		if (text.isEmpty() && animation == null && !rule.hasAnimationChain())
		{
			return;
		}

		rule.markFired(now);

		// Mood moves with what the follower ACTUALLY did. Applying it here
		// rather than at the win means a rule held back by a cooldown, a
		// disabled group or the mute does not quietly move the mood while
		// saying nothing - the state and the words stay one story.
		if (rule.mood != null && rule.mood != 0)
		{
			getContext().adjustMood(rule.mood);

			// And the place keeps its share of it. The rules already say how
			// much a moment was worth; this is only remembering where it was.
			if (belongsToThisPlace(rule, event))
			{
				getContext().notePlaceFeeling(rule.mood);
			}
		}

		// A question only counts as asked once it has actually been said - a
		// rule silenced by the mute must not leave the follower waiting for an
		// answer to something nobody heard. The same goes for the want and the
		// wish, and "said" is the sink's moment, not this method's: the plugin
		// queues lines behind the overhead box, and a queued line can still be
		// dropped - aged out, displaced by a full queue, cleared with the
		// scene. Each of these three later puts an interaction in front of the
		// player (a conversation, a thank-you, a gift option in the Talk-to
		// box), so opening them on a line nobody saw leaves that interaction
		// unexplained. They latch when the sink says the line landed.
		Runnable onSaid = () ->
		{
			// The wear ledger counts what was HEARD, not what was queued -
			// a line the queue dropped has not worn out its welcome.
			if (!text.isEmpty())
			{
				getContext().noteLineSaid(template);
			}
			if (rule.asks != null && !rule.asks.isEmpty() && !text.isEmpty())
			{
				getContext().noteQuestion(rule.asks);
			}
			if (rule.want != null && rule.want.region != null && !text.isEmpty())
			{
				getContext().setWant(rule.want.region, rule.want.label,
					rule.want.minutes == null ? 15 : rule.want.minutes);
			}
			if (rule.wish != null && rule.wish.what != null && !text.isEmpty())
			{
				getContext().setWish(rule.wish.what,
					rule.wish.minutes == null ? 45 : rule.wish.minutes,
					rule.wish.items);
			}
		};

		// An incident is filed whether or not the line was heard: the chicken
		// killed you regardless of whether the follower got a word in.
		if (rule.remember != null && rule.remember.key != null)
		{
			getContext().noteIncident(rule.remember.key, rule.remember.as);
		}

		// And the same moment against the place, when the rule says so.
		if (rule.markHere != null && !rule.markHere.isEmpty())
		{
			getContext().notePlaceMemory(rule.markHere);
		}

		// Picking something up and betting on something both need to have been
		// SAID, like the want - a souvenir nobody was told about is invisible,
		// and a silent prediction can only ever be right.
		if (rule.pickUp != null && rule.pickUp.what != null && !text.isEmpty())
		{
			// Substituted like a spoken line, so a souvenir can be named after
			// the wish it grants: "the {wish} you found me". A swap rule
			// replaces what is carried - it said the exchange out loud, which
			// is what the refuse-if-carrying guard exists to require.
			String what = substitute(rule.pickUp.what, event);
			int minutes = rule.pickUp.minutes == null ? 20 : rule.pickUp.minutes;
			if (Boolean.TRUE.equals(rule.pickUp.swap))
			{
				getContext().tradeFor(what, minutes);
			}
			else
			{
				getContext().pickUp(what, minutes);
			}
		}
		// AFTER the pickUp, which may still need the wish's name. The wish
		// closes on a SPOKEN thank-you only: the follower acknowledging the
		// gift is what spends it.
		if (Boolean.TRUE.equals(rule.grantsWish) && !text.isEmpty())
		{
			getContext().clearWish();
		}
		if (rule.bet != null && rule.bet.threshold != null && !text.isEmpty())
		{
			getContext().placeBet(Boolean.TRUE.equals(rule.bet.rich),
				rule.bet.threshold, rule.bet.minutes == null ? 5 : rule.bet.minutes);
		}
		if (rule.challenge != null && rule.challenge.tally != null && !text.isEmpty())
		{
			getContext().setChallenge(
				rule.challenge.about,
				rule.challenge.tally,
				rule.challenge.target == null ? 10 : rule.challenge.target,
				rule.challenge.minutes == null ? 5 : rule.challenge.minutes);
		}
		if (rule.advise != null && rule.advise.about != null && !text.isEmpty())
		{
			getContext().adviseOn(
				rule.advise.about,
				rule.advise.ids == null
					? java.util.Collections.emptySet()
					: new java.util.HashSet<>(rule.advise.ids),
				Boolean.TRUE.equals(rule.advise.room),
				rule.advise.minutes == null ? 1 : rule.advise.minutes);
		}

		if (!text.isEmpty())
		{
			// Only actual speech resets the global window; an animation-only
			// firing should not push back the next spoken line.
			lastSpokeMs = now;
			lastSpokenText = text;
			director.noteSpoke(rule, now);
			noteRecentLine(template);
			if (isIdleRemark(rule))
			{
				idleStretchSpoken++;
				lastIdleRemarkMs = now;
			}

			// A first is only spent once it has been heard. Marked here rather
			// than at the win for the same reason as the question and the want:
			// a line the mute swallowed was never said, and burning the one
			// chance the follower had to say it would be the worst possible
			// place to be strict.
			if (rule.isOnce())
			{
				getContext().noteSaidOnce(rule.id);
			}
		}

		SpeechOutput output = SpeechOutput.parse(rule.output, defaultOutput);
		if (sink != null)
		{
			sink.speak(text, output, rule, animation == null ? -1 : animation, onSaid);
		}
		log.debug("Rule '{}' fired: {}", rule.describe(),
			text.isEmpty() ? "(animation " + animation + ")" : text);
	}

	private boolean isActive(SpeechRule rule)
	{
		if (!rule.isEnabled() || (rule.isOnce() && getContext().hasSaidOnce(rule.id)))
		{
			return false;
		}
		String group = rule.group == null ? "misc" : rule.group.toLowerCase(Locale.ROOT);
		return !disabledGroups.contains(group)
			&& !(rule.id != null && disabledGroups.contains(rule.id.toLowerCase(Locale.ROOT)));
	}

	/** Replaces {@code {placeholder}} tokens from the event and the state snapshot. */
	private String substitute(String template, TriggerEvent event)
	{
		if (template == null || template.indexOf('{') < 0)
		{
			return template == null ? "" : template;
		}

		Map<String, String> values = new HashMap<>(event.getPlaceholders());
		TriggerContext ctx = getContext();
		// Available wherever the state is: a callback line wants {memory}
		// whatever event happened to trigger it.
		values.putIfAbsent("memory", ctx.getIncidentPhrase());
		values.putIfAbsent("souvenir", ctx.getSouvenir());
		values.putIfAbsent("wish", ctx.getWishLabel());
		values.putIfAbsent("here", ctx.getPlaceMemory());
		values.putIfAbsent("nickname", ctx.getNickname());
		values.putIfAbsent("left", Integer.toString(ctx.getChallengeLeft()));
		values.putIfAbsent("days", Integer.toString(ctx.getDaysKnown()));
		values.putIfAbsent("hp", Integer.toString(ctx.getHitpoints()));
		values.putIfAbsent("maxHp", Integer.toString(ctx.getMaxHitpoints()));
		values.putIfAbsent("hpPercent", Integer.toString(ctx.getHitpointsPercent()));
		values.putIfAbsent("prayer", Integer.toString(ctx.getPrayerPoints()));
		values.putIfAbsent("maxPrayer", Integer.toString(ctx.getMaxPrayerPoints()));
		values.putIfAbsent("prayerPercent", Integer.toString(ctx.getPrayerPercent()));
		values.putIfAbsent("player", ctx.getPlayerName());
		values.putIfAbsent("region", Integer.toString(ctx.getRegionId()));

		StringBuilder out = new StringBuilder(template.length() + 16);
		int i = 0;
		while (i < template.length())
		{
			char c = template.charAt(i);
			if (c != '{')
			{
				out.append(c);
				i++;
				continue;
			}

			int close = template.indexOf('}', i);
			if (close < 0)
			{
				out.append(template.substring(i));
				break;
			}

			String key = template.substring(i + 1, close);
			String replacement = values.get(key);
			out.append(replacement == null ? template.substring(i, close + 1) : replacement);
			i = close + 1;
		}

		return out.toString();
	}
}
