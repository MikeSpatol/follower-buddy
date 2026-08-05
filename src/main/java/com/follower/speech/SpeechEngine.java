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
		getContext().refresh();
	}

	public void reset()
	{
		context = null;
		lastSpokeMs = 0L;
		for (SpeechRule rule : loader.getRules())
		{
			rule.reset();
		}
	}

	/**
	 * Runs one evaluation pass. Every rule's edge state is updated regardless of
	 * whether it can speak, so cooldowns and mutes never desynchronise the edges.
	 */
	public void dispatch(TriggerEvent event)
	{
		long now = System.currentTimeMillis();
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

		if (winner == null)
		{
			return;
		}

		// The mute and the global window throttle SPEECH; a rule that only plays
		// an animation (teleport mirroring) is not chatter and skips both.
		if (winner.hasSpeech() && (muted || now - lastSpokeMs < globalCooldownMs))
		{
			// Still charge the cooldown so a suppressed rule doesn't fire the instant
			// the global window opens.
			log.info("rule '{}' won but was suppressed (muted={}, sinceLastSpoke={}ms)",
				winner.describe(), muted, now - lastSpokeMs);
			winner.markFired(now);
			return;
		}

		log.info("rule '{}' fired on {}", winner.describe(), event.getType());
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

	private void speak(SpeechRule rule, TriggerEvent event, long now)
	{
		String text = substitute(rule.pickPhrase(), event);
		Integer animation = rule.resolveAnimation(event);
		if (text.isEmpty() && animation == null && !rule.hasAnimationChain())
		{
			return;
		}

		rule.markFired(now);
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
