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
	 */
	public interface Sink
	{
		void speak(String text, SpeechOutput output, SpeechRule rule, int animationId);
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

	@Setter
	private boolean muted;

	@Getter
	private long lastSpokeMs;

	@Getter
	private String lastSpokenText = "";

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
			rule.reset();
		}
	}

	public void reset()
	{
		context = null;
		lastSpokeMs = 0L;
		director.reset();
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
		long now = System.currentTimeMillis();

		// An animation the player played may be them doing the thing they were
		// just told to. Offered before the rules run, so the settling and the
		// reaction land on the same tick rather than a tick apart.
		if (event.getType() == TriggerEvent.Type.ANIMATION && event.getId() != -1)
		{
			getContext().offerAct(event.getId());
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
		lastSpokeMs = System.currentTimeMillis();
		lastSpokenText = text;
		sink.speak(text, output == null ? defaultOutput : output, null, -1);
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
				long now = System.currentTimeMillis();
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
		String text = substitute(rule.pickPhrase(), event);
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

		// A question only counts as asked once it has actually been said, for
		// the same reason: a rule silenced by the mute must not leave the
		// follower waiting for an answer to something nobody heard.
		if (rule.asks != null && !rule.asks.isEmpty() && !text.isEmpty())
		{
			getContext().noteQuestion(rule.asks);
		}

		// Same rule, same reason: the follower can only be hoping for something
		// it managed to ask for out loud.
		if (rule.want != null && rule.want.region != null && !text.isEmpty())
		{
			getContext().setWant(rule.want.region, rule.want.label,
				rule.want.minutes == null ? 15 : rule.want.minutes);
		}

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
			getContext().pickUp(rule.pickUp.what,
				rule.pickUp.minutes == null ? 20 : rule.pickUp.minutes);
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
			sink.speak(text, output, rule, animation == null ? -1 : animation);
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
