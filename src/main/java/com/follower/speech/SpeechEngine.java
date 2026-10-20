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
	 * line was seen. Everything else a line leaves behind hangs here too: a
	 * first spent, a souvenir, a bet, a dare, advice, a granted wish.
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

	/**
	 * How wide a line is drawn over the head, in pixels: the game's bold
	 * font, set by the plugin once the fonts are read. Null measures in
	 * characters only.
	 */
	@Setter
	private java.util.function.ToIntFunction<String> overheadWidth;

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

	/**
	 * Three minutes with camera, feet and hands all untouched before the
	 * damper (R15) decides nobody is watching. Deliberately past the
	 * gone-away jokes' own threshold, so the follower notices the absence
	 * out loud before it stops performing to it.
	 */
	private static final int UNATTENDED_DAMPER_TICKS = 300;

	/** Idle remarks per stretch before the winding-down starts. */
	private static final int IDLE_FREE_REMARKS = 4;

	/** Each remark past the free ones waits this many gaps longer than the last. */
	private static final long TRAIL_STEP_GAPS = 30;

	/** The wind-down stops growing here: never rarer than one per this many gaps. */
	private static final long TRAIL_CAP_GAPS = 200;

	/** The placeholders that hold a player's name: the player's own, whoever came or went, whoever spoke. */
	private static final Set<String> PLAYER_NAMES = Set.of("player", "name", "speaker");

	/** Someone's own name or words: the article step leaves them as written, "The" and all. */
	private static final Set<String> AS_WRITTEN = Set.of("player", "name", "speaker", "message");

	/** Placeholders that always name a creature or a boss, whatever the event. */
	private static final Set<String> CREATURE_NAMES = Set.of("npc", "foe", "boss", "memoryWho");

	/** Words that already point at the thing, so a name's own "The" goes: that Leviathan, the first Leviathan. */
	private static final Set<String> POINTING = Set.of("the", "that", "this", "another", "one", "first", "more");

	/**
	 * A unit the template wrote in the plural, after a number: "{prayer}
	 * points left". A count of one takes it singular - 1 point, 1 prayer
	 * point - since a close call or a nearly dry prayer is often exactly one.
	 */
	private static final java.util.regex.Pattern PLURAL_UNIT = java.util.regex.Pattern.compile(
		" ((?:prayer )?(?:hit)?point|day|minute|hour|time|kill|level)s\\b");

	/**
	 * Nouns a boss's name can stand in front of, the article belonging to
	 * the noun: the Vorkath population, the Zulrah column.
	 */
	private static final Set<String> NAME_MODIFIES = Set.of(
		"population", "column", "kill", "kills", "count", "fight", "pet", "drop", "drops",
		"lair", "page", "entry");

	private boolean isIdleRemark(SpeechRule rule)
	{
		return rule.when != null && rule.when.usesType("idle") && !rule.isOccasion();
	}

	/** How long after one rule brings up a memory the others wait: ten minutes. */
	static final long MEMORY_APART_MS = 600_000L;

	/** Whether a rule's lines tell the incident, by its {memory}. */
	private static boolean tellsTheIncident(SpeechRule rule)
	{
		return saysAnyOf(rule, "{memory}");
	}

	/**
	 * Whether a rule brings up something remembered: the incident, what a
	 * place holds ({here}), or the spot of the last death. Text QA5, 17 Oct:
	 * "This is where I wrote down the time you died, yesterday." and, 6 s
	 * later, "Same spot. This is where it went wrong for you."
	 */
	private static boolean bringsUpAMemory(SpeechRule rule)
	{
		return saysAnyOf(rule, "{memory}", "{here}")
			|| (rule.when != null && rule.when.usesType("nearDeathSpot"));
	}

	private static boolean saysAnyOf(SpeechRule rule, String... placeholders)
	{
		if (rule.say != null)
		{
			for (String line : rule.say)
			{
				for (String placeholder : placeholders)
				{
					if (line.contains(placeholder))
					{
						return true;
					}
				}
			}
		}
		return false;
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
			TriggerEvent.Type.WANT_FULFILLED,
			// Answering is the most direct evidence of a person there is:
			// somebody read a question and chose. Without it the damper could
			// silence the follower MID-CONVERSATION - the guessing game asks
			// you to stand still, so a player who then only clicks dialog
			// options never moves camera or feet, and the reveal of which
			// hand it was went to an "empty chair" that was answering it.
			TriggerEvent.Type.ANSWERED);

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
	 * A cutscene, or the quiet after one, set by the plugin each tick. Lines
	 * are held as under the mute, but an occasion waits it out rather than
	 * being lost to it (SCENE_WAIT_TICKS).
	 */
	@Setter
	private boolean sceneQuiet;

	/**
	 * The cutscene itself, without the quiet after it: what can move a
	 * quest's step on under a line kept waiting (see the moved-on check).
	 */
	@Setter
	private boolean inCutscene;

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

	private java.util.function.Function<String, Long> questWitness;

	@Inject
	public SpeechEngine(Client client, RuleLoader loader)
	{
		this.client = client;
		this.loader = loader;
	}

	/**
	 * Asks the book whether the follower was there for a quest. Kept here and
	 * not only on the context, which every reset throws away.
	 */
	public void setQuestWitness(java.util.function.Function<String, Long> lookup)
	{
		questWitness = lookup;
		getContext().setQuestWitness(lookup);
	}

	public TriggerContext getContext()
	{
		if (context == null)
		{
			context = new TriggerContext(client);
			context.setQuestWitness(questWitness);
		}
		return context;
	}

	/** Refreshes the state snapshot. Call once per game tick, before dispatching. */
	public void refreshContext()
	{
		TriggerContext context = getContext();
		context.refresh();

		// Cheap, and it has to survive a reload: the rules are the source of
		// the boss list, so the context follows them rather than being told
		// once at startup and going stale the next time the file is saved.
		context.setBossNames(loader.getBossNames());

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
			if (outcome == TriggerContext.WantOutcome.FULFILLED)
			{
				// A favour done is a favour owed.
				context.noteFavour();
			}
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
		// A line still waiting is let go unsaid, its cooldown given back, as a
		// line whose moment passed is: behaviour QA4, 16 Oct, Sergeant Tobyn's
		// line waited its gap across a hop and was gone for the hour, with him
		// still beside the player.
		for (PendingSpeech delayed : pending)
		{
			delayed.rule.markFired(delayed.firedBefore);
		}
		pending.clear();
		// The plugin clears its queue with the scene, so those lines never land.
		unlanded.clear();
		// An announcement waiting on the old scene's prime window belongs to
		// that scene, not to the one arriving.
		afterPriming.clear();
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
		afterPriming.clear();
		director.reset();
		recentLines.clear();
		recentLineSet.clear();
		idleStretchSpoken = 0;
		lastIdleRemarkMs = 0L;
		pending.clear();
		unlanded.clear();
		// A held floor must not outlive the thing that held it: toggling the
		// plugin mid-hush would otherwise leave the follower mute for it.
		hushUntilMs = 0L;
		hushOwner = null;
		// Edges only, as at a hop: what was said is kept through a logout,
		// cooldowns and bags. Text QA5, 17-18 Oct: every relog wiped the
		// cooldowns, so the two-hour fence book came back twelve minutes on,
		// and the nickname was asked again either side of a relog.
		for (SpeechRule rule : loader.getRules())
		{
			rule.resetEdges();
		}
	}

	/**
	 * {@link #reset}, and every rule's cooldown and bag too: for a follower
	 * whose memory is not the one these were spent in - a profile switch,
	 * Forget everything, another account logging in.
	 */
	public void forget()
	{
		reset();
		forgetWhatWasSaid();
	}

	private void forgetWhatWasSaid()
	{
		for (SpeechRule rule : loader.getRules())
		{
			rule.reset();
		}
	}

	/** The account whose cooldowns the rules hold. */
	private long account = -1L;

	/** A login: another account than the last starts its cooldowns over. */
	public void loggedInAs(long accountHash)
	{
		if (accountHash != account)
		{
			account = accountHash;
			forgetWhatWasSaid();
		}
	}

	/** Whether what a waiting line advises was done while it waited: the teleport before "Get out!". */
	private boolean answered(PendingSpeech delayed)
	{
		SpeechRule.Advice advice = delayed.rule.advise;
		return advice != null && advice.ids != null && getContext().actedSince(advice.ids, delayed.wonTick);
	}

	/** A won rule waiting out its delayTicks before speaking. */
	private static final class PendingSpeech
	{
		final SpeechRule rule;
		final TriggerEvent event;
		/** The wait as drawn at the win - kept for the log, since a ranged delay is not on the rule. */
		final int delay;
		int ticksLeft;
		/** Ticks an occasion has spent waiting out the gap after another line. */
		int waited;
		/** The line that gap is after: a new line said starts the count again. */
		long gapAfterMs;
		/** Ticks an occasion has spent waiting out a scene, its quiet and the gap after, apart from the gap's ten. */
		int sceneWaited;
		/** Ticks a waits line has spent waiting out the director's rest. */
		int rested;
		/** Held by a conversation, and not yet given its beat after it. */
		boolean heardTheBox;
		/** The rule's last firing before this win, given back if the line is let go unsaid. */
		final long firedBefore;
		/** The game tick it won on. */
		final int wonTick;
		/** A callback about the same person won meanwhile: the plain line stands aside. */
		boolean stoodAside;

		PendingSpeech(SpeechRule rule, TriggerEvent event, int ticksLeft, long firedBefore, int wonTick)
		{
			this.rule = rule;
			this.event = event;
			this.delay = ticksLeft;
			this.ticksLeft = ticksLeft;
			this.firedBefore = firedBefore;
			this.wonTick = wonTick;
		}
	}

	private final java.util.List<PendingSpeech> pending = new java.util.ArrayList<>();

	/**
	 * How long an occasion waits for the gap after another line, in ticks:
	 * six seconds. Play test, 7 Oct: Waterfall Quest's reward levelled
	 * Attack, the level line went first, and the quest-complete line two
	 * seconds behind it met the gap and was dropped. The gap is there to keep
	 * chatter apart, not to cost a quest its one line. The least it waits:
	 * see {@link #occasionWaitTicks}.
	 */
	static final int OCCASION_WAIT_TICKS = 10;

	/**
	 * How long a line that waits its turn waits for the gap now, in ticks:
	 * its gap and two more. The gap stretches with mood and chattiness - 15
	 * seconds at a low mood on Occasional, nearly two minutes on Minimal - and
	 * a fixed six seconds dropped the quest's line again in every one of those.
	 */
	int occasionWaitTicks(SpeechRule rule)
	{
		return Math.max(OCCASION_WAIT_TICKS, (int) (gapFor(rule) / 600L) + 2);
	}

	/**
	 * The longest gap an occasion keeps after another line: eight seconds.
	 * Quests QA, 16 Oct, at Minimal (45 s, the user's own setting): every
	 * quest moment waited the full gap behind any line, the sketch prompt
	 * included, and they landed a stage late, out of order or after the
	 * scroll. The user's call: the moments break through. The gap still
	 * thins out everything else.
	 */
	static final long OCCASION_GAP_MS = 8_000L;

	/**
	 * The gap this rule keeps after the last line. A quest person's line - it
	 * waits its turn, and is about someone near - keeps an occasion's: quests
	 * QA4, 16 Oct, at Minimal the 45 s gap outlasted the first talk, and the
	 * boy's intro came as he got his ball back. The user's call.
	 */
	private long gapFor(SpeechRule rule)
	{
		boolean person = Boolean.TRUE.equals(rule.waits) && rule.when != null && rule.when.usesType("npcNearby");
		return rule.isOccasion() || person ? Math.min(moodScaledGap(), OCCASION_GAP_MS) : moodScaledGap();
	}

	/** Whether a firing held up for this reason waits a tick and tries again rather than being dropped. */
	static boolean waitsOutTheGap(SpeechRule rule, String held, int waited, int limit)
	{
		return "gap".equals(held) && rule.waitsItsTurn() && waited < limit;
	}

	/**
	 * How long an occasion waits out a cutscene and the quiet after it, in
	 * ticks: five minutes. Play test, 9 Oct: a line was said over the
	 * opening of Children of the Sun's cutscene. Everything is held there
	 * now, and an occasion - a quest's end, a boss - is said once the scene
	 * is over rather than lost to it. Past this it is old news.
	 */
	static final int SCENE_WAIT_TICKS = 500;

	/**
	 * Whether a firing held up by a scene waits a tick and tries again rather
	 * than being dropped, and the gap or the floor after another line once
	 * the scene is over: two occasions held by one scene are said one after
	 * the other at any chattiness, where the ten-tick wait lost the second to
	 * a gap of 45 seconds. An NPC conversation holds it the same way, inside
	 * the same five minutes, and so does the empty chair: QA, 15 Oct, a line
	 * with one chance that came while the player was away from the keys was
	 * dropped by the damper, where it can wait for them to be back.
	 */
	static boolean waitsOutTheScene(SpeechRule rule, String held, int waited)
	{
		return rule.waitsItsTurn() && waited < SCENE_WAIT_TICKS
			&& ("scene".equals(held) || "dialogue".equals(held) || "unattended".equals(held)
				|| (waited > 0 && ("gap".equals(held) || "hush".equals(held))));
	}

	/**
	 * How long a line that waits will wait out the director's rest, in
	 * ticks: the longest rest. One rest, not a queue through every one: the
	 * rest still happens, and the line comes after it. Behaviour QA, 15 Oct:
	 * Alina and Noah's line met a rest in a busy square and was gone for good.
	 */
	static final int REST_WAIT_TICKS = 75;

	/** Whether a line held by the director's rest waits it out rather than being dropped. Not an occasion: no rest holds one. */
	static boolean waitsOutTheRest(SpeechRule rule, String held, int rested)
	{
		return "relax".equals(held) && Boolean.TRUE.equals(rule.waits) && rested < REST_WAIT_TICKS;
	}

	/**
	 * A line handed to the sink that leaves something behind when it lands,
	 * and has not landed yet. The states it will set are named by the
	 * conditions that read them.
	 */
	private static final class Unlanded
	{
		final java.util.Set<String> states;
		final long handedOverMs;

		Unlanded(java.util.Set<String> states, long handedOverMs)
		{
			this.states = states;
			this.handedOverMs = handedOverMs;
		}
	}

	private final java.util.List<Unlanded> unlanded = new java.util.ArrayList<>();

	/**
	 * The longest a line handed to the plugin can wait before it lands or is
	 * dropped as stale. Past this it never lands, so nothing waits on it. A
	 * test holds it above the plugin queue's stale window.
	 */
	private static final long LONGEST_WAIT_MS = 16_000L;

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

	/** Announcements waiting for the prime window to drain. */
	private final java.util.List<TriggerEvent> afterPriming = new java.util.ArrayList<>();

	/**
	 * Raises an event the plugin MEANT to raise - a world hop, an edited
	 * notebook - once the prime window has drained, or immediately if none is
	 * open.
	 *
	 * <p>These cannot simply be dispatched around the priming, and both ways
	 * of trying were wrong. Dispatched inside the window, the winner is
	 * discarded with everything else and the rule is silently dead. Dispatched
	 * just BEFORE arming it, the announcement lands on a rule set whose edges
	 * were cleared with the scene, so every piece of worn gear and the region
	 * underfoot rise on that same pass - and since one event has one winner
	 * and those rules outrank the announcement, the follower remarks on your
	 * gear as though you had just put it on and the hop goes unmentioned.
	 * Play report, exactly that.
	 *
	 * <p>Waiting is what makes it correct: the window baselines the state
	 * first, so by the time this lands, nothing else is rising and the
	 * announcement wins the pass it deserves to win.
	 */
	public void dispatchAfterPriming(TriggerEvent event)
	{
		if (primeTicksLeft <= 0)
		{
			dispatch(event);
			return;
		}
		afterPriming.add(event);
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
	 * Whether the follower has the floor: nothing waiting its turn, the login
	 * baseline taken, not muted or hushed, and the gap since the last line
	 * run out. For news that can wait, such as a chronicle page written. Sent
	 * at the moment it happens, it met the reaction to the same moment and
	 * lost: a region's arrival line outranks it on the same event, and a
	 * boss's own line speaks first and leaves it inside the gap.
	 *
	 * <p>Nor while the director rests after a burst. QA, 15 Oct: the gap ran
	 * out well before the rest did, so the page news went out into the rest,
	 * was held back, and was gone from the queue.
	 */
	public boolean isQuiet()
	{
		long now = now();
		return pending.isEmpty() && primeTicksLeft == 0 && !muted
			&& now - lastSpokeMs >= moodScaledGap() && now >= hushUntilMs
			&& !director.isRelaxing(now);
	}

	/**
	 * Whether the line that would mention this page is still cooling from
	 * the last one. Quests QA4, 16 Oct: Priest in Peril writes three pages at
	 * its scroll, and the second and third were spent on the quest page's
	 * line while it cooled, two pages never mentioned. The news waits for it.
	 */
	public boolean isPageLineCooling(TriggerEvent news)
	{
		long now = now();
		for (SpeechRule rule : loader.getRules())
		{
			if (!rule.offCooldown(now) && rule.when != null && rule.when.usesType("pageWritten")
				&& rule.when.matches(getContext(), news))
			{
				return true;
			}
		}
		return false;
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
	 * Whether a line that waits its turn is held for an NPC conversation:
	 * the box is up, or went under three ticks ago (the context rides out a
	 * page flip). Anything else said there is dropped by the plugin, and a
	 * line with one chance was lost with it. QA, 15 Oct (Children of the
	 * Sun): a quest's people were met by talking to them, so their lines came
	 * up as the box opened, and the moments held over a scene were released
	 * into the conversation the scene ran on into. Held, they are said once
	 * it is over.
	 *
	 * <p>Every other line is held back there too, as under the mute, rather
	 * than handed over to be hushed. Quests QA, 16 Oct: an idle line hushed
	 * in a long quest conversation still charged the gap, so at Minimal the
	 * quest's end waited 45 s behind a line nobody saw.
	 */
	private boolean heldForTheBox()
	{
		return getContext().isDialogueOpen();
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
			return muted ? "muted" : sceneQuiet ? "scene" : heldForTheBox() ? "dialogue" : null;
		}
		if (muted)
		{
			return "muted";
		}
		if (sceneQuiet)
		{
			return "scene";
		}
		if (heldForTheBox())
		{
			return "dialogue";
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
		// R15: speech scales to attention. With camera, feet and hands all
		// untouched this long, the world is playing to an empty chair -
		// reactions included, not just idle chatter. Occasions still land
		// (a warning matters the moment they glance back), and the rules
		// ABOUT the absence are exactly the ones that should speak into it.
		// Any input at all clears the state instantly.
		if (getContext().getUnattendedTicks() >= UNATTENDED_DAMPER_TICKS
			&& !rule.isOccasion()
			&& (rule.when == null || !rule.when.usesType("unattended")))
		{
			return "unattended";
		}
		// Ignored overtures rest. A question that lapsed unanswered is not
		// followed by another for a long while, and two ignored in one
		// session end the asking - a polite companion drops a hint twice and
		// lets it go, where re-asking at every cooldown reads as nagging.
		// A wish that lapsed unfulfilled rests more gently. Answering any
		// question, or granting any wish, clears the respective ledger.
		if (rule.asks != null && !rule.asks.isEmpty() && getContext().isAskBackedOff())
		{
			return "ignored";
		}
		if (rule.wish != null && getContext().isWishBackedOff())
		{
			return "ignored";
		}
		if (isIdleRemark(rule) && now - lastIdleRemarkMs < trailWaitMs())
		{
			return "trailing";
		}
		if (now - lastSpokeMs < gapFor(rule))
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
	public long moodScaledGap()
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

		// A memory restored in the old vague words is said again from its
		// particulars before anything can bring it up. Here rather than at
		// the restore, which runs before the rules and place names are in.
		if (getContext().isIncidentToRetell())
		{
			java.util.List<String> retold = retold();
			getContext().retellIncident(retold.isEmpty() ? null : retold.get(0));
		}

		// An animation the player played may be them doing the thing they were
		// just told to. Offered before the rules run, so the settling and the
		// reaction land on the same tick rather than a tick apart.
		if (event.getType() == TriggerEvent.Type.ANIMATION && event.getId() != -1)
		{
			getContext().offerAct(event.getId());
		}

		// Doing something real ends the idle stretch, and the follower perks
		// back up. Checked on the event rather than on movement - see the note
		// on REAL_ACTIVITY for why a step is not enough. It is also evidence
		// of presence: you cannot level up from the kettle.
		if (REAL_ACTIVITY.contains(event.getType()))
		{
			idleStretchSpoken = 0;
			getContext().noteAttention();
		}

		// Delayed firings count down on the tick heartbeat and speak through
		// the same guarded path as everything else when their beat arrives.
		if (event.getType() == TriggerEvent.Type.TICK && !pending.isEmpty())
		{
			// Most important first, when several come free on one tick; level
			// ones keep the order they won in. Quests QA, 16 Oct: Prince
			// Itzla's line went ahead of the bag moment it was held with.
			// Occasions go before the rest, in the order they won: quests
			// QA4, 16 Oct, the scroll's line went ahead of a moment held
			// through the hand-in, and "It's stopped. I'm giving it a moment
			// to be sure." came after "Witch's House. Nice scroll."
			pending.sort(java.util.Comparator.comparing((PendingSpeech p) -> !p.rule.isOccasion())
				.thenComparingInt(p -> p.rule.isOccasion() ? p.wonTick : -p.rule.priority));
			java.util.Iterator<PendingSpeech> it = pending.iterator();
			while (it.hasNext())
			{
				PendingSpeech delayed = it.next();
				if (delayed.stoodAside)
				{
					it.remove();
					noteSuppressed(delayed.rule, "callback");
					continue;
				}
				if (--delayed.ticksLeft <= 0)
				{
					// A cutscene that moves its quest's step on makes a line
					// about the step before old news: two scenes stale, and
					// "Questioning" said after the questioning. Let go
					// unspent. A pan that leaves the step alone does not do
					// it, nor a conversation alone: that is usually the very
					// step it moves on, and the line is still about what came
					// just before. Behaviour QA, 16 Oct: "a scene began since
					// the win" lost a line to an unrelated pan, and missed a
					// second scene that started inside the first one's quiet.
					if (inCutscene && !delayed.rule.when.questStepHolds(getContext(), delayed.event))
					{
						it.remove();
						noteSuppressed(delayed.rule, "moved-on");
						continue;
					}
					String held = blockedBy(delayed.rule, now);
					// The beat after a conversation is for the tick the box
					// goes. Held by anything else first, it waits its gap
					// like any other line, rather than jumping a moment
					// that came up since.
					if (held != null && !"dialogue".equals(held))
					{
						delayed.heardTheBox = false;
					}
					if (waitsOutTheScene(delayed.rule, held, delayed.sceneWaited))
					{
						delayed.sceneWaited++;
						delayed.heardTheBox |= "dialogue".equals(held);
						delayed.ticksLeft = 1;
						continue;
					}
					// A beat after the conversation: what its end raises - the
					// quest's step, moved on in it - goes first, and a line
					// held from before waits its gap behind it.
					if (held == null && delayed.heardTheBox)
					{
						delayed.heardTheBox = false;
						delayed.ticksLeft = 1;
						continue;
					}
					// The gap counted from the line it is behind: one said
					// while it waits starts the count again (QA, 15 Oct: the
					// second of two moments behind an area line was dropped a
					// tick before its gap ran out).
					if (delayed.gapAfterMs != lastSpokeMs)
					{
						delayed.gapAfterMs = lastSpokeMs;
						delayed.waited = 0;
					}
					if (waitsOutTheGap(delayed.rule, held, delayed.waited, occasionWaitTicks(delayed.rule)))
					{
						delayed.waited++;
						delayed.ticksLeft = 1;
						continue;
					}
					if (waitsOutTheRest(delayed.rule, held, delayed.rested))
					{
						delayed.rested++;
						delayed.ticksLeft = 1;
						continue;
					}
					it.remove();
					// Kept waiting, the moment it was about can pass: the
					// player ate, or the person walked off, or did what a
					// warning was about to advise. Let go unsaid, with its
					// cooldown given back, so it rises the next time it is true.
					if (held == null && (!delayed.rule.when.standingHolds(getContext(), delayed.event)
						|| answered(delayed)))
					{
						delayed.rule.markFired(delayed.firedBefore);
						noteSuppressed(delayed.rule, "passed");
						continue;
					}
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

			// A memory is filed by every rule that rose, not only the one that
			// gets to speak: the remember rules sit under the reaction lines
			// on purpose (the reaction is the line worth hearing), and filing
			// only from the winner meant the drop and the boss were almost
			// never remembered. Not while primed, when nothing is news yet.
			// And whatever is silenced: turning a group off quietens what the
			// follower says, as the Reminiscing setting promises, not what it
			// remembers - the Memory window is where remembering is undone.
			if (rising && rule.isEnabled() && primeTicksLeft == 0)
			{
				file(rule, event);
			}

			// Except the gift. The answer that brings it is given once, so a
			// gift held back is a gift lost; and it trades whatever the pocket
			// holds when it is thanked for, so a rock still on its way only
			// changes the words.
			if (!rising || !isActive(rule) || !rule.offCooldown(now) || isWaiting(rule)
				|| (!Boolean.TRUE.equals(rule.grantsWish) && awaitsALanding(rule, now)))
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
			// Level with the winner, a line that waits lost on file order
			// alone. It rises again on the next pass and is said behind it.
			// Behaviour QA, 15 Oct: Prince Itzla and Sergeant Tobyn came into
			// view on one tick, and Itzla's line was never said. Not one a
			// point below, which is outranked on purpose (a callback over its
			// base), nor an occasion: two of those level on one event are
			// each other's alternatives (the third death and "again").
			else if (rule.priority == winner.priority && Boolean.TRUE.equals(rule.waits))
			{
				rule.riseAgain();
			}
		}

		// Something just ENDED: open the breather window (R6). Noted AFTER the
		// evaluation loop on purpose - a boundary rule should rise on the NEXT
		// pass rather than competing with the ending's own reaction on this
		// event, where losing would consume its edge for the whole window. The
		// breather comes after the reaction, which is also the right order to
		// hear them in.
		switch (event.getType())
		{
			case COMBAT_END:
				getContext().noteBoundary("combat");
				break;
			case LEVEL_UP:
				getContext().noteBoundary("level");
				break;
			case REGION_CHANGE:
				getContext().noteBoundary("arrival");
				break;
			case THIEVING_END:
				getContext().noteBoundary("thieving");
				break;
			default:
				break;
		}

		// While primed (just after login), edges have been recorded above but
		// nothing fires from ANY event: worn gear and standing state register
		// as already-true instead of as fresh rising edges. The countdown only
		// consumes ticks where the player composition is actually readable -
		// before that, "no equipment" is an artefact of loading, not a state.
		if (primeTicksLeft > 0)
		{
			if (event.getType() == TriggerEvent.Type.TICK && getContext().isPlayerReady()
				&& --primeTicksLeft == 0 && !afterPriming.isEmpty())
			{
				// The baseline is taken; anything held back now lands on a rule
				// set where only it is rising. Drained into a copy first, so a
				// re-entrant dispatch cannot see the queue twice.
				java.util.List<TriggerEvent> held = new java.util.ArrayList<>(afterPriming);
				afterPriming.clear();
				for (TriggerEvent announcement : held)
				{
					dispatch(announcement);
				}
			}
			return;
		}

		if (winner == null)
		{
			return;
		}

		// A callback about a quest's person: a plain line about them already
		// waiting is let go now, at the win, since the two are one line.
		// Behaviour QA, 16 Oct: the person came by with a box up, the plain
		// line was held for it, the callback rose as it closed, and both
		// were said 6 s apart.
		String plain = winner.plainLineId();
		if (plain != null)
		{
			for (PendingSpeech waiting : pending)
			{
				waiting.stoodAside |= plain.equals(waiting.rule.id);
			}
		}

		// Taking the floor happens at the WIN, not when the line finally comes
		// out. A rule with a delay would otherwise leave its own gap open: the
		// want-fulfilled line waits a few ticks, and the region-change line
		// that arrives later in the same tick would walk straight into it.
		// Not at a win the scene holds: the floor of a line never said would
		// outlast the quiet and cost the occasions waiting it out.
		if (winner.hushMs != null && winner.hushMs > 0 && winner.hasSpeech() && !sceneQuiet)
		{
			hushUntilMs = now + winner.hushMs;
			hushOwner = winner;
		}

		// A delayed rule queues instead of speaking now; the cooldown is
		// charged at the win so re-triggers don't stack up more firings. The
		// wait is drawn HERE, once, so a ranged delay stays put while pending.
		int delay = winner.rollDelayTicks();
		long firedBefore = winner.lastFiredMs();
		if (delay > 0)
		{
			winner.markFired(now);
			pending.add(new PendingSpeech(winner, event, delay, firedBefore, client.getTickCount()));
			return;
		}

		String blocked = blockedBy(winner, now);
		if (waitsOutTheGap(winner, blocked, 0, occasionWaitTicks(winner)) || waitsOutTheScene(winner, blocked, 0)
			|| waitsOutTheRest(winner, blocked, 0))
		{
			winner.markFired(now);
			PendingSpeech held = new PendingSpeech(winner, event, 1, firedBefore, client.getTickCount());
			held.gapAfterMs = lastSpokeMs;
			held.heardTheBox = "dialogue".equals(blocked);
			pending.add(held);
			return;
		}
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
	 * Says a line of the follower's own that no rule wrote - the remark after
	 * a sketch - where the settings put speech, and not at all under the
	 * mute, or in a scene and the quiet after it. {@link #say} is the
	 * player's own command and speaks through the mute on purpose; a line
	 * nobody asked for has no such excuse, and went straight to the
	 * overhead, muted or not.
	 */
	public void remark(String text)
	{
		if (!muted && !sceneQuiet)
		{
			say(text, null);
		}
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
		String drawn = rule.pickPhrase(recentLineSet,
			line -> getContext().lineWear(line));
		String filled = spokenText(drawn, event);
		// Filled too long to fit over the head, a line gives way to the
		// rule's first that fits: a place's memory has one way of being
		// said, and one that will not fit "You may not remember. This is
		// where ..." still fits "Same spot. This is where ..." (behaviour
		// QA5, measured).
		if (!fitsOverhead(filled) && rule.say != null)
		{
			for (String other : rule.say)
			{
				String fits = spokenText(other, event);
				if (fitsOverhead(fits))
				{
					drawn = other;
					filled = fits;
					break;
				}
			}
		}
		String template = drawn;
		String text = filled;
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

		// A callback about a quest's person stands aside the plain line about
		// them for that line's own while. QA, 15 Oct: two Duke callbacks, then
		// "Duke Horacio himself" as if they had never met, in six minutes.
		// Its fellow callbacks about the same person stand aside too: Text
		// QA 3, the Duke's two callbacks came two minutes apart.
		String plain = rule.plainLineId();
		if (plain != null)
		{
			String fellows = rule.id.substring(0, rule.id.indexOf("-after-") + 7);
			for (SpeechRule other : loader.getRules())
			{
				if (plain.equals(other.id) || (other.id != null && other.id.startsWith(fellows)))
				{
					other.markFired(now);
				}
			}
		}

		// One thing remembered at a time: text QA4, 16 Oct, "My mind goes back
		// to the guard with the very large bag" and, 16 s later, "The notes on
		// the guard with the very large bag start here." The other rules that
		// bring up a memory wait ten minutes, or their own while if it is left.
		if (bringsUpAMemory(rule))
		{
			for (SpeechRule other : loader.getRules())
			{
				if (other != rule && bringsUpAMemory(other))
				{
					other.markFired(Math.max(other.lastFiredMs(), now - other.cooldownMs + MEMORY_APART_MS));
				}
			}
		}

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
		// scene, hushed by an NPC dialogue. Each of these three later puts an
		// interaction in front of the player (a conversation, a thank-you, a
		// gift option in the Talk-to box), so opening them on a line nobody saw
		// leaves that interaction unexplained. They latch when the sink says the
		// line landed, and so does everything else a line leaves behind.
		//
		// A souvenir is substituted like a spoken line, so it can be named after
		// the wish it grants: "the {wish} you found me". Named here, while that
		// wish is certainly still open.
		String souvenir = rule.pickUp != null && rule.pickUp.what != null && !text.isEmpty()
			? substitute(rule.pickUp.what, event) : null;
		// The gift is the one thing spent at the win. The exchange was
		// confirmed in the Talk-to box the player is reading, and a thank-you
		// the queue dropped must not leave the wish open and the gift option
		// back in the box for an item already handed over.
		boolean gift = Boolean.TRUE.equals(rule.grantsWish) && !text.isEmpty();
		if (gift)
		{
			pocket(rule, souvenir);
			// AFTER the pocketing, which may still need the wish's name.
			getContext().clearWish();
			getContext().noteFavour();
		}
		// Until it lands, a rule reading what it leaves behind waits for it.
		java.util.Set<String> leaves = text.isEmpty()
			? java.util.Collections.emptySet() : leavesBehind(rule);
		Unlanded landing = leaves.isEmpty() ? null : new Unlanded(leaves, now);
		Runnable onSaid = () ->
		{
			unlanded.remove(landing);
			// The wear ledger counts what was HEARD, not what was queued -
			// a line the queue dropped has not worn out its welcome.
			if (!text.isEmpty())
			{
				getContext().noteLineSaid(template);
				event.noteSaid();
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

			// Picking something up and betting on something both need to have
			// been SAID, like the want - a souvenir nobody was told about is
			// invisible, and a silent prediction can only ever be right.
			if (!gift)
			{
				pocket(rule, souvenir);
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

			// A first is only spent once it has been heard. A line the mute,
			// the queue or the dialogue hush swallowed was never said, and
			// burning the one chance the follower had to say it would be the
			// worst possible place to be strict. While a copy waits in the
			// queue, the rule's cooldown keeps it from winning again; a lint
			// holds every first's cooldown longer than the queue's wait.
			if (rule.isOnce() && !text.isEmpty())
			{
				getContext().noteSaidOnce(rule.onceKey());
			}
			if (isIdleRemark(rule) && tellsTheIncident(rule) && !text.isEmpty())
			{
				getContext().noteIncidentRecalled(now());
			}
		};

		// The pacing is charged at the hand-over, heard or not: it only spaces
		// the lines out. A line the plugin then drops still costs its gap, so
		// what a conversation would hush is held back above instead (QA3 #10:
		// at Minimal that gap cost the quest's end 45 s).
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
		}

		SpeechOutput output = SpeechOutput.parse(rule.output, defaultOutput);
		if (sink != null)
		{
			// Before the hand-over, since a sink with the floor free lands it at once.
			if (landing != null)
			{
				unlanded.add(landing);
			}
			sink.speak(text, output, rule, animation == null ? -1 : animation, onSaid);
		}
		log.debug("Rule '{}' fired: {}", rule.describe(),
			text.isEmpty() ? "(animation " + animation + ")" : text);
	}

	/** How recent a fight has to be for a memory filed now to be about it, in ticks: six seconds. */
	static final int RECENT_FIGHT_TICKS = 10;

	/** What a rule writes down when it rises: the incident, and the place. */
	private void file(SpeechRule rule, TriggerEvent event)
	{
		boolean marks = rule.markHere != null && !rule.markHere.isEmpty();
		if ((rule.remember == null || rule.remember.key == null) && !marks)
		{
			return;
		}
		// The particulars are filled in NOW, while the event and the moment
		// still know them; a placeholder with nothing to say drops the phrase
		// back to a plainer form rather than filing a hole. The last thing
		// fought is never forgotten by the context, so only a fight going on,
		// or just over, is this memory's. QA, 15 Oct: a quest finished an
		// hour after a ram fight was filed "with the Ram", and the next Ram
		// brought the quest up.
		boolean fighting = getContext().foughtWithin(RECENT_FIGHT_TICKS);
		// Chased rather than fighting, what was hitting the player is the
		// foe: behaviour QA5, run down in the Abyss with nothing targeted.
		String attacker = fighting ? "" : getContext().getRecentAttacker(RECENT_FIGHT_TICKS);
		if (!attacker.isEmpty())
		{
			event.getPlaceholders().putIfAbsent("foe", attacker);
		}
		boolean foeKnown = fighting || !attacker.isEmpty();

		// An incident is filed whether or not the line was heard: the chicken
		// killed you regardless of whether the follower got a word in.
		if (rule.remember != null && rule.remember.key != null)
		{
			String phrase = firstFilled(rule.remember.forms(), event, foeKnown);
			String who = event.getPlaceholders().getOrDefault("npc", "");
			if (who.isEmpty() && foeKnown)
			{
				who = fighting ? getContext().getCombatTarget() : attacker;
			}
			getContext().noteIncident(rule.remember.key, phrase, who,
				event.getPlaceholders().getOrDefault("item", ""), getContext().getHitpoints(),
				System.currentTimeMillis());
		}

		// And the same moment against the place, when the rule says so, in
		// the same way: text QA5, 17 Oct, "This is where it went wrong for
		// you." said what happened nowhere in it.
		if (marks)
		{
			java.util.List<String> forms = new java.util.ArrayList<>();
			forms.add(rule.markHere);
			if (rule.markHereOr != null)
			{
				forms.addAll(rule.markHereOr);
			}
			getContext().notePlaceMemory(firstFilled(forms, event, foeKnown));
		}
	}

	/** Each form in turn, filled: the first with nothing missing, or the last whatever it has. */
	private String firstFilled(java.util.List<String> forms, TriggerEvent event, boolean foeKnown)
	{
		String phrase = "";
		for (int i = 0; i < forms.size(); i++)
		{
			String form = forms.get(i);
			boolean[] incomplete = {false};
			phrase = substitute(form, event, incomplete, false);
			if (i == forms.size() - 1
				|| (!incomplete[0] && (foeKnown || !form.contains("{foe}"))))
			{
				break;
			}
		}
		return phrase;
	}

	private boolean isActive(SpeechRule rule)
	{
		if (!rule.isEnabled() || (rule.isOnce()
			&& (getContext().hasSaidOnce(rule.id) || getContext().hasSaidOnce(rule.onceKey()))))
		{
			return false;
		}
		// A memory that would only come out vague is not brought up at all:
		// "I was rereading the entry on the way that went" tells the player
		// nothing about which moment it was. Nor one too fresh to be a memory
		// yet, nor brought up unprompted twice in a day.
		if (tellsTheIncident(rule) && (memoryPhrases().isEmpty() || getContext().isIncidentFresh(now())
			|| (isIdleRemark(rule) && getContext().wasIncidentRecalledToday(now()))))
		{
			return false;
		}
		String group = rule.group == null ? "misc" : rule.group.toLowerCase(Locale.ROOT);
		return !disabledGroups.contains(group)
			&& !(rule.id != null && disabledGroups.contains(rule.id.toLowerCase(Locale.ROOT)));
	}

	/**
	 * Whether a firing of this rule is already waiting its turn. A hold can
	 * outlast a short cooldown - a low-health warning kept through a long
	 * conversation - and a second copy queued behind the first was said
	 * straight after it once both were let go.
	 */
	private boolean isWaiting(SpeechRule rule)
	{
		for (PendingSpeech waiting : pending)
		{
			if (waiting.rule == rule)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the rule reads a state that a line still on its way will set.
	 * Those states latch when the line lands, so judged now, "none advising"
	 * would open a dare over advice waiting in the queue. The rule is held
	 * back until the line lands or can no longer land, and gets its turn when
	 * its trigger comes round again. The gift's never does, so it is not held.
	 */
	private boolean awaitsALanding(SpeechRule rule, long now)
	{
		unlanded.removeIf(line -> now - line.handedOverMs > LONGEST_WAIT_MS);
		for (Unlanded line : unlanded)
		{
			for (String state : line.states)
			{
				if (rule.when.usesType(state))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Carries the souvenir a line names, if it names one. */
	private void pocket(SpeechRule rule, String souvenir)
	{
		if (souvenir == null)
		{
			return;
		}
		// A swap rule replaces what is carried - it said the exchange out
		// loud, which is what the refuse-if-carrying guard exists to require.
		// So does a gift, which outranks a rock the follower found itself: it
		// is spent at the win, and a rock still queued can land either side.
		int minutes = rule.pickUp.minutes == null ? 20 : rule.pickUp.minutes;
		if (Boolean.TRUE.equals(rule.pickUp.swap) || Boolean.TRUE.equals(rule.grantsWish))
		{
			getContext().tradeFor(souvenir, minutes);
		}
		else
		{
			getContext().pickUp(souvenir, minutes);
		}
	}

	/**
	 * What a line latches when it lands, named as the conditions that read
	 * it. Not the gift's pocket and wish: those are spent at the win.
	 */
	private static java.util.Set<String> leavesBehind(SpeechRule rule)
	{
		boolean gift = Boolean.TRUE.equals(rule.grantsWish);
		java.util.Set<String> states = new java.util.HashSet<>();
		if (rule.asks != null && !rule.asks.isEmpty())
		{
			states.add("asking");
		}
		if (rule.want != null && rule.want.region != null)
		{
			states.add("wanting");
		}
		if (rule.wish != null && rule.wish.what != null)
		{
			states.add("wishing");
		}
		if (!gift && rule.pickUp != null && rule.pickUp.what != null)
		{
			states.add("carrying");
		}
		if (rule.bet != null && rule.bet.threshold != null)
		{
			states.add("betting");
		}
		if (rule.challenge != null && rule.challenge.tally != null)
		{
			states.add("challenging");
		}
		if (rule.advise != null && rule.advise.about != null)
		{
			states.add("advising");
		}
		return states;
	}

	/**
	 * The longest line said over a head: the game's own limit for anything
	 * a player or NPC says there. QA, 15 Oct: a memory with its where and
	 * when ran to 92-151 characters, and fixed mode clips both ends of it.
	 */
	static final int OVERHEAD_MAX_CHARS = 80;

	/** The widest line said over a head, in pixels: wider is clipped in fixed mode. */
	static final int OVERHEAD_MAX_PX = 480;

	/** "That was {memoryWhen}." and the like: a sentence that only says when. */
	private static final java.util.regex.Pattern WHEN_SENTENCE =
		java.util.regex.Pattern.compile("\\s*[^.?!]*\\{memory(?:When|Ago)\\}[^.?!]*[.?!]");

	/** The memory being tried while a line is fitted, in place of the one filed. */
	private String memoryOverride;

	/**
	 * A template filled for saying. Over the limit, a line that tells a
	 * memory is filled again with less: first without its when, then
	 * without its where, then with the memory said a shorter way, from its
	 * rule's other forms. The fullest that fits is said; the moment itself
	 * is the point, and the particulars are what there may be no room for.
	 */
	String spokenText(String template, TriggerEvent event)
	{
		// A line that names the creature itself says the memory without it:
		// "A Hill Giant. I remember your death in Edgeville", never "... your
		// death to the Hill Giant".
		java.util.List<String> phrases = template != null && template.contains("{memory}")
			? memoryPhrases(template.contains("{memoryWho}")) : new java.util.ArrayList<>();
		if (phrases.isEmpty())
		{
			phrases.add(null);
		}
		String text = "";
		try
		{
			for (String phrase : phrases)
			{
				memoryOverride = phrase;
				for (String variant : shortenings(template))
				{
					text = substitute(variant, event, null, true);
					if (fitsOverhead(text))
					{
						return text;
					}
				}
			}
			return text;
		}
		finally
		{
			memoryOverride = null;
		}
	}

	private boolean fitsOverhead(String text)
	{
		return text.length() <= OVERHEAD_MAX_CHARS
			&& (overheadWidth == null || overheadWidth.applyAsInt(text) <= OVERHEAD_MAX_PX);
	}

	/** The template, then without its memory's when, then without its where too. */
	private static java.util.List<String> shortenings(String template)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		out.add(template);
		for (String shorter : new String[]{withoutMemoryWhen(template), withoutMemoryParticulars(template)})
		{
			if (shorter != null && !out.contains(shorter))
			{
				out.add(shorter);
			}
		}
		return out;
	}

	/**
	 * Every way the incident can be said, most particular first: as it was
	 * filed, then as its rule's forms say it from the particulars kept with
	 * it. Never a vague one, so empty when there is nothing specific to say.
	 */
	public java.util.List<String> memoryPhrases()
	{
		return memoryPhrases(false);
	}

	/**
	 * @param withoutCreature only the ways that leave out who it was with:
	 * no form with a {foe} or an {npc} in it, and not the filed phrase if it
	 * names them
	 */
	java.util.List<String> memoryPhrases(boolean withoutCreature)
	{
		java.util.List<String> phrases = new java.util.ArrayList<>();
		TriggerContext ctx = getContext();
		if (!ctx.hasIncident() || TriggerContext.isPlain(ctx.getIncidentPhrase()))
		{
			return phrases;
		}
		String filed = ctx.getIncidentPhrase();
		String who = ctx.getIncidentWho().toLowerCase(Locale.ROOT);
		if (!TriggerContext.isVague(filed)
			&& !(withoutCreature && !who.isEmpty() && filed.toLowerCase(Locale.ROOT).contains(who)))
		{
			phrases.add(filed);
		}
		for (String phrase : retold(withoutCreature))
		{
			if (!phrases.contains(phrase))
			{
				phrases.add(phrase);
			}
		}
		return phrases;
	}

	/**
	 * The incident in its rule's words, filled from the particulars kept
	 * with it: each form that has everything it needs, and is not vague.
	 */
	private java.util.List<String> retold()
	{
		return retold(false);
	}

	private java.util.List<String> retold(boolean withoutCreature)
	{
		java.util.List<String> phrases = new java.util.ArrayList<>();
		TriggerContext ctx = getContext();
		SpeechRule.Incident remember = rememberFor(ctx.getIncidentKey());
		if (remember == null)
		{
			return phrases;
		}
		TriggerEvent particulars = TriggerEvent.combat(TriggerEvent.Type.MANUAL, ctx.getIncidentWho());
		Map<String, String> values = particulars.getPlaceholders();
		values.put("foe", ctx.getIncidentWho());
		values.put("item", ctx.getIncidentItem());
		values.put("hp", ctx.getIncidentHp() < 0 ? "" : Integer.toString(ctx.getIncidentHp()));
		values.put("place", ctx.getIncidentPlace());
		values.put("where", ctx.getIncidentWhereSaid());
		for (String form : remember.forms())
		{
			if (withoutCreature && (form.contains("{foe}") || form.contains("{npc}")))
			{
				continue;
			}
			boolean[] incomplete = {false};
			String phrase = substitute(form, particulars, incomplete, false);
			if (!incomplete[0] && !TriggerContext.isVague(phrase) && !phrases.contains(phrase))
			{
				phrases.add(phrase);
			}
		}
		return phrases;
	}

	/** The remember block that files incidents under this key, or null. */
	private SpeechRule.Incident rememberFor(String key)
	{
		for (SpeechRule rule : loader.getRules())
		{
			if (rule.remember != null && key.equals(rule.remember.key))
			{
				return rule.remember;
			}
		}
		return null;
	}

	/** A page of a conversation tree, filled from the state like a spoken line: {candidate} and the rest. */
	public String fill(String template)
	{
		return substitute(template, TriggerEvent.simple(TriggerEvent.Type.MANUAL), null, true);
	}

	/**
	 * The template with {memoryWhere}, {memoryWhen} and {memoryAgo} taken
	 * out, or null when it has none or would lose the memory with them. The
	 * where brings its own " in", so it goes alone; the when goes with its
	 * comma, or with the sentence it has to itself.
	 */
	static String withoutMemoryParticulars(String template)
	{
		if (template == null || !template.contains("{memoryWhere}"))
		{
			return withoutMemoryWhen(template);
		}
		String shorter = template.replace("{memoryWhere}", "");
		if (saysWhen(shorter))
		{
			shorter = withoutMemoryWhen(shorter);
		}
		return shorter;
	}

	/** The template with only its when taken out, or null as above. */
	static String withoutMemoryWhen(String template)
	{
		if (template == null || !saysWhen(template))
		{
			return null;
		}
		String shorter = template.replace(", {memoryWhen}", "").replace(", {memoryAgo}", "");
		shorter = WHEN_SENTENCE.matcher(shorter).replaceAll("").trim();
		if (saysWhen(shorter) || (template.contains("{memory}") && !shorter.contains("{memory}")))
		{
			return null;
		}
		return shorter;
	}

	private static boolean saysWhen(String template)
	{
		return template.contains("{memoryWhen}") || template.contains("{memoryAgo}");
	}

	/** Replaces {@code {placeholder}} tokens from the event and the state snapshot. */
	private String substitute(String template, TriggerEvent event)
	{
		return substitute(template, event, null, false);
	}

	/**
	 * @param incomplete set to true at [0] when a known placeholder resolved to nothing
	 * @param spoken whether this is a line to be said, whose sentences open on a capital
	 */
	private String substitute(String template, TriggerEvent event, boolean[] incomplete, boolean spoken)
	{
		if (template == null || template.indexOf('{') < 0)
		{
			return template == null ? "" : template;
		}

		Map<String, String> values = new HashMap<>(event.getPlaceholders());
		TriggerContext ctx = getContext();
		// Available wherever the state is: a callback line wants {memory}
		// whatever event happened to trigger it.
		String memory = memoryOverride != null ? memoryOverride : ctx.getIncidentPhrase();
		values.putIfAbsent("memory", memory);
		values.putIfAbsent("memoryWhere", ctx.memoryWhereFor(memory));
		values.putIfAbsent("memoryWhen", ctx.getMemoryWhen(System.currentTimeMillis()));
		values.putIfAbsent("memoryAgo", ctx.getMemoryAgo(System.currentTimeMillis()));
		values.putIfAbsent("memoryWho", ctx.getIncidentWho());
		// Where a memory filed now happened: "Edgeville", and "in Edgeville"
		// or "near Lumbridge" ready to follow it.
		values.putIfAbsent("place", ctx.getPlaceHere());
		values.putIfAbsent("where", ctx.getWhereHere());
		values.putIfAbsent("foe", ctx.getCombatTarget());
		values.putIfAbsent("souvenir", ctx.getSouvenir());
		values.putIfAbsent("wish", ctx.getWishLabel());
		values.putIfAbsent("here", ctx.getPlaceMemory());
		values.putIfAbsent("nickname", ctx.getNickname());
		values.putIfAbsent("candidate", ctx.getEarnedNickname());
		values.putIfAbsent("lastTime", ctx.getLastTime());
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
			if (incomplete != null && (replacement == null || replacement.isEmpty()))
			{
				incomplete[0] = true;
			}
			if (replacement != null && !replacement.isEmpty() && !AS_WRITTEN.contains(key))
			{
				// "a {x} level" describes the level; "a {x}." is the x itself.
				boolean endsPhrase = close + 2 >= template.length()
					|| template.charAt(close + 1) != ' '
					|| !Character.isLetter(template.charAt(close + 2));
				// The agreed nickname is written for the middle of a sentence,
				// "the swot". Said to the player it is a name: "Hello, swot.",
				// never "Hello, the swot." A {candidate} is only talked about.
				if ("nickname".equals(key) && addressed(out, template, close))
				{
					replacement = replacement.replaceFirst("(?i)^the ", "");
				}
				// A name that brings its own "The" is a proper one.
				boolean named = replacement.regionMatches(true, 0, "the ", 0, 4);
				// Vorkath is somebody, not a kind of thing: never the Vorkath,
				// unless the article is for a noun after it.
				if (Words.isPersonalName(replacement) && !modifiesNoun(template, close))
				{
					dropArticle(out);
				}
				replacement = agreeArticle(out, replacement, endsPhrase);
				// After a pointing word a creature is a common noun: the guard,
				// a goblin, that monk of Zamorak. Bosses keep their capitals.
				if ((event.getCreatureKeys().contains(key) || "foe".equals(key) || "memoryWho".equals(key))
					&& !named
					&& afterAnArticle(out) && !ctx.isBoss(replacement,
						event.getType() == TriggerEvent.Type.NPC_KILL ? event.getValue() : 0))
				{
					replacement = Words.asCommonNoun(replacement);
				}
				// A creature's own "The", with nothing pointing at it, is lower
				// case mid-sentence: you beat the Leviathan.
				if ((event.getCreatureKeys().contains(key) || CREATURE_NAMES.contains(key))
					&& replacement.startsWith("The ") && !opensSentence(template, i))
				{
					replacement = "t" + replacement.substring(1);
				}
			}
			// The values are written for the middle of a sentence - "a feather",
			// "the local menace", "two minutes" - so one that opens a sentence
			// takes the capital the template left for it. A player's name is
			// spelled as its owner chose, so it keeps its own case.
			if (spoken && replacement != null && !replacement.isEmpty()
				&& !PLAYER_NAMES.contains(key) && opensSentence(template, i))
			{
				replacement = Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
			}
			out.append(replacement == null ? template.substring(i, close + 1) : replacement);
			i = close + 1;
			if ("1".equals(replacement))
			{
				java.util.regex.Matcher unit = PLURAL_UNIT.matcher(template).region(i, template.length());
				if (unit.lookingAt())
				{
					out.append(' ').append(unit.group(1));
					i = unit.end();
				}
			}
		}

		return out.toString();
	}

	/** Whether the template follows the placeholder ending at {@code close} with a noun it describes. */
	private static boolean modifiesNoun(String template, int close)
	{
		int start = close + 2;
		if (start >= template.length() || template.charAt(close + 1) != ' ')
		{
			return false;
		}
		int end = start;
		while (end < template.length() && Character.isLetter(template.charAt(end)))
		{
			end++;
		}
		return NAME_MODIFIES.contains(template.substring(start, end).toLowerCase(Locale.ROOT));
	}

	/** Takes a "the", "a" or "an" off the end of the text so far, keeping the space before it. */
	private static void dropArticle(StringBuilder out)
	{
		int space = out.length() - 1;
		if (space < 1 || out.charAt(space) != ' ')
		{
			return;
		}
		int start = space;
		while (start > 0 && Character.isLetter(out.charAt(start - 1)))
		{
			start--;
		}
		String word = out.substring(start, space).toLowerCase(Locale.ROOT);
		if (word.equals("the") || word.equals("a") || word.equals("an"))
		{
			out.setLength(start);
		}
	}

	/**
	 * Makes the word the template wrote before a placeholder fit what filled
	 * it. An "a" goes by sound: an Eclectic impling, an 80. When the value
	 * ends the phrase and takes no article at all - Coins, soft clay - the
	 * article goes, and its capital moves onto the value. And a name that
	 * brings its own "The" gives it up to the word already pointing at it -
	 * the Leviathan, that Leviathan, the first Leviathan, never the The
	 * Leviathan - an "a" becoming that "the". Returns the value to append.
	 */
	private static String agreeArticle(StringBuilder out, String value, boolean endsPhrase)
	{
		int space = out.length() - 1;
		if (space < 1 || out.charAt(space) != ' ')
		{
			return value;
		}
		int start = space;
		while (start > 0 && Character.isLetter(out.charAt(start - 1)))
		{
			start--;
		}
		String written = out.substring(start, space);
		boolean article = written.equalsIgnoreCase("a") || written.equalsIgnoreCase("an");
		if (!article && !POINTING.contains(written.toLowerCase(Locale.ROOT)))
		{
			return value;
		}
		boolean capital = Character.isUpperCase(written.charAt(0));
		if (value.length() > 4 && value.regionMatches(true, 0, "the ", 0, 4))
		{
			if (article)
			{
				out.replace(start, space, capital ? "The" : "the");
			}
			return value.substring(4);
		}
		if (!article)
		{
			return value;
		}
		if (endsPhrase && Words.takesNoArticle(value))
		{
			out.setLength(start);
			return capital ? Character.toUpperCase(value.charAt(0)) + value.substring(1) : value;
		}
		String fits = Words.article(value);
		out.replace(start, space, capital ? Character.toUpperCase(fits.charAt(0)) + fits.substring(1) : fits);
		return value;
	}

	/** Whether the text so far ends on a word that points at a thing: a, the, that and the rest. */
	private static boolean afterAnArticle(StringBuilder out)
	{
		int space = out.length() - 1;
		if (space < 1 || out.charAt(space) != ' ')
		{
			return false;
		}
		int start = space;
		while (start > 0 && Character.isLetter(out.charAt(start - 1)))
		{
			start--;
		}
		String word = out.substring(start, space).toLowerCase(Locale.ROOT);
		return word.equals("a") || word.equals("an") || POINTING.contains(word);
	}

	/** Whether a value set off by a comma before it and the sentence's end after it is said to someone. */
	private static boolean addressed(StringBuilder out, String template, int close)
	{
		int n = out.length();
		return n >= 2 && out.charAt(n - 2) == ',' && out.charAt(n - 1) == ' '
			&& (close + 1 >= template.length() || ".!?".indexOf(template.charAt(close + 1)) >= 0);
	}

	/**
	 * Whether the placeholder at {@code at} opens a sentence: the start of the
	 * line, or after a full stop, a question or an exclamation. Not after an
	 * ellipsis, which trails a sentence off rather than ending it.
	 */
	private static boolean opensSentence(String template, int at)
	{
		if (at == 0)
		{
			return true;
		}
		if (at < 2 || template.charAt(at - 1) != ' ')
		{
			return false;
		}
		char end = template.charAt(at - 2);
		return end == '!' || end == '?' || (end == '.' && (at < 3 || template.charAt(at - 3) != '.'));
	}
}
