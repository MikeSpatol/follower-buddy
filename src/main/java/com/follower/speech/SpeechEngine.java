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
	@Setter
	private long globalCooldownMs = 3_000L;

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

		// A want resolving is state becoming an event. It happens here rather
		// than in the plugin so the rules see it wherever the engine is driven
		// from, and exactly once - the poll consumes it.
		TriggerContext.WantOutcome outcome = context.pollWant();
		if (outcome != null)
		{
			dispatch(TriggerEvent.want(
				outcome == TriggerContext.WantOutcome.FULFILLED
					? TriggerEvent.Type.WANT_FULFILLED
					: TriggerEvent.Type.WANT_EXPIRED,
				context.getWantLabel()));
		}
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
		if (!rule.hasSpeech())
		{
			return false;
		}
		if (rule == hushOwner && now < hushUntilMs)
		{
			return muted;
		}
		return muted
			|| now - lastSpokeMs < globalCooldownMs
			|| now < hushUntilMs;
	}

	/**
	 * Runs one evaluation pass. Every rule's edge state is updated regardless of
	 * whether it can speak, so cooldowns and mutes never desynchronise the edges.
	 */
	public void dispatch(TriggerEvent event)
	{
		long now = System.currentTimeMillis();

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
					if (!cannotSpeak(delayed.rule, now))
					{
						log.debug("rule '{}' fired after its {}-tick delay",
							delayed.rule.describe(), delayed.delay);
						speak(delayed.rule, delayed.event, now);
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

		if (cannotSpeak(winner, now))
		{
			// Still charge the cooldown so a suppressed rule doesn't fire the instant
			// the global window opens.
			log.debug("rule '{}' won but was suppressed (muted={}, sinceLastSpoke={}ms)",
				winner.describe(), muted, now - lastSpokeMs);
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

		if (!text.isEmpty())
		{
			// Only actual speech resets the global window; an animation-only
			// firing should not push back the next spoken line.
			lastSpokeMs = now;
			lastSpokenText = text;
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
		if (!rule.isEnabled())
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
