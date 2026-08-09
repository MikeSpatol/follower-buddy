package com.follower.sim;

import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechEngine;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A running speech engine with nothing behind it but {@link FakeGame}.
 *
 * <p>Loads the rules the plugin actually ships - the loader writes them from the
 * bundled resource into a scratch directory - so these tests exercise the real
 * rule set rather than a hand-written stand-in that could drift from it.
 */
public final class Harness
{
	/** One thing the engine asked the plugin to say. */
	public static final class Spoken
	{
		public final String text;
		public final SpeechOutput output;
		public final SpeechRule rule;
		public final int animationId;

		Spoken(String text, SpeechOutput output, SpeechRule rule, int animationId)
		{
			this.text = text;
			this.output = output;
			this.rule = rule;
			this.animationId = animationId;
		}

		@Override
		public String toString()
		{
			return (rule == null ? "(direct)" : rule.describe())
				+ " -> \"" + text + "\" anim=" + animationId;
		}
	}

	public final FakeGame game = new FakeGame();
	public final RuleLoader loader = new RuleLoader(new Gson());
	public final SpeechEngine engine;
	public final List<Spoken> spoken = new ArrayList<>();

	private final Path scratch;
	private int tick;

	/** Loads the rules the plugin ships with. */
	public Harness(Path scratch) throws IOException
	{
		this(scratch, null);
	}

	/**
	 * @param rulesJson the body of a phrases.json to use instead of the bundled
	 * rules, or null for the bundled set. Written to disk and read back through
	 * the real loader, so a focused test still goes through parsing and
	 * validation rather than around them.
	 */
	public Harness(Path scratch, String rulesJson) throws IOException
	{
		this.scratch = scratch;
		Files.createDirectories(scratch);
		Path file = scratch.resolve(RuleLoader.FILE_NAME);
		if (rulesJson != null)
		{
			Files.write(file, rulesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		else
		{
			Files.deleteIfExists(file);
		}

		loader.initialise(scratch);
		engine = new SpeechEngine(game.client, loader);
		engine.setSink((text, output, rule, animationId) ->
			spoken.add(new Spoken(text, output, rule, animationId)));
		// Off by default: the global window is a separate thing to test, and
		// leaving it on would silently swallow most firings in every other test.
		engine.setGlobalCooldownMs(0L);
	}

	public Path getScratch()
	{
		return scratch;
	}

	/**
	 * One game tick: advances the clock, refreshes the state snapshot and
	 * dispatches the tick heartbeat, in the order the plugin does it.
	 */
	public Harness gameTick()
	{
		game.tick(++tick);
		engine.refreshContext();
		engine.dispatch(TriggerEvent.tick());
		return this;
	}

	public Harness gameTicks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			gameTick();
		}
		return this;
	}

	/** Dispatches an event mid-tick, as the plugin's own subscribers do. */
	public Harness dispatch(TriggerEvent event)
	{
		engine.dispatch(event);
		return this;
	}

	/** The local player saying something in public chat. */
	public Harness playerSays(String message)
	{
		engine.dispatch(TriggerEvent.chat(message,
			net.runelite.api.ChatMessageType.PUBLICCHAT.getType(), "Tester"));
		return this;
	}

	/**
	 * The player picking a branch that answers, as the dialog does: the
	 * question closes and the rules are told, in that order.
	 */
	public Harness answers(String yesOrNo)
	{
		engine.getContext().noteAnswered();
		engine.dispatch(TriggerEvent.answered(yesOrNo));
		return this;
	}

	/** Somebody else in public chat. */
	public Harness someoneSays(String who, String message)
	{
		engine.dispatch(TriggerEvent.chat(message,
			net.runelite.api.ChatMessageType.PUBLICCHAT.getType(), who));
		return this;
	}

	public List<Spoken> firedBy(String ruleId)
	{
		List<Spoken> hits = new ArrayList<>();
		for (Spoken s : spoken)
		{
			if (s.rule != null && ruleId.equals(s.rule.id))
			{
				hits.add(s);
			}
		}
		return hits;
	}

	public SpeechRule rule(String id)
	{
		for (SpeechRule rule : loader.getRules())
		{
			if (id.equals(rule.id))
			{
				return rule;
			}
		}
		throw new AssertionError("no rule '" + id + "' in the bundled set");
	}

	public void clear()
	{
		spoken.clear();
	}
}
