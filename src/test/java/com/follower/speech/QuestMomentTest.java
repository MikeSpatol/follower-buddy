package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Lines at the steps partway through a quest. Each is a rule on the quest's
 * own progress variable - {@code questVar}, a varbit or a varp in a range -
 * said once, ever, as an occasion that waits out a cutscene. The step often
 * changes mid-conversation, so most wait for the dialogue box to close.
 *
 * <p>Built the way the drafted quest moments are: once, occasion, two ticks'
 * delay, and a cooldown long enough that a copy waiting out a scene cannot
 * be queued a second time behind it.
 */
public class QuestMomentTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Game time: 600 ms a tick, so the cooldowns and the gap behave. */
	private final long[] ms = {1_000_000L};

	private static final String NOT_IN_DIALOGUE =
		"{\"type\": \"none\", \"conditions\": [{\"type\": \"dialogueOpen\"}]}";

	/** A quest moment as the draft writes one: all of the given conditions. */
	private static String moment(String id, String... conditions)
	{
		return "{\"id\": \"" + id + "\", \"group\": \"quest\", \"priority\": 60,"
			+ " \"cooldownMs\": 600000, \"once\": true, \"occasion\": true, \"delayTicks\": 2,"
			+ " \"output\": \"overhead\", \"when\": {\"type\": \"all\", \"conditions\": ["
			+ String.join(", ", conditions) + "]}, \"say\": [\"" + id + " said\"]}";
	}

	private static String varp(int varp, int minimum, int maximum)
	{
		return "{\"type\": \"questVar\", \"varp\": " + varp
			+ ", \"minimum\": " + minimum + ", \"maximum\": " + maximum + "}";
	}

	private static String varbit(int varbit, int minimum, int maximum)
	{
		return "{\"type\": \"questVar\", \"varbit\": " + varbit
			+ ", \"minimum\": " + minimum + ", \"maximum\": " + maximum + "}";
	}

	private Harness harness(String... rules) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + String.join(",", rules) + "]}");
		h.engine.setClock(() -> ms[0]);
		ticks(h, 1);
		return h;
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	// ------------------------------------------------------------- the range

	@Test
	public void aJumpIntoTheRangeStillEntersIt() throws IOException
	{
		// Sheep Shearer counts the balls handed in. Twelve at once takes the
		// varp from 1 to 13, past the first band and into the second.
		Harness h = harness(
			moment("first-handful", varp(179, 2, 10), NOT_IN_DIALOGUE),
			moment("past-halfway", varp(179, 11, 20), NOT_IN_DIALOGUE));
		h.game.varp(179, 1);
		ticks(h, 5);
		assertTrue("nothing handed in yet", h.spoken.isEmpty());

		h.game.varp(179, 13);
		ticks(h, 5);
		assertEquals("the band it landed in", 1, h.firedBy("past-halfway").size());
		assertTrue("not the one it jumped over", h.firedBy("first-handful").isEmpty());

		h.game.varp(179, 15);
		ticks(h, 5);
		assertEquals("moving within the band is not entering it", 1, h.spoken.size());
	}

	@Test
	public void noPlayerNoReading() throws IOException
	{
		// At client start the varbit read throws inside the client, and a rule
		// that throws is disabled for the session.
		Harness h = harness(moment("the-bag", varbit(9632, 6, 6), NOT_IN_DIALOGUE));
		h.game.starting();
		ticks(h, 5);
		assertTrue(h.spoken.isEmpty());
		assertTrue("still enabled", h.rule("the-bag").isEnabled());
	}

	// ------------------------------------------------------- another quest

	private static String done(String quest)
	{
		return "{\"type\": \"questDone\", \"quest\": \"" + quest + "\"}";
	}

	private static String notDone(String quest)
	{
		return "{\"type\": \"none\", \"conditions\": [" + done(quest) + "]}";
	}

	/** The same moment as {@link #moment}, outranking it: a callback's shape. */
	private static String callback(String id, String... conditions)
	{
		return moment(id, conditions).replace("\"priority\": 60", "\"priority\": 61");
	}

	@Test
	public void aCallbackIsSaidOnlyWithTheOtherQuestDoneAndTheBaseOnlyWithout() throws IOException
	{
		// Rune Mysteries' tower, and the Duke again if his cake was baked.
		for (boolean cakeBaked : new boolean[]{false, true})
		{
			Harness h = harness(
				moment("tower", varp(63, 1, 1), notDone("COOKS_ASSISTANT"), NOT_IN_DIALOGUE),
				callback("tower-after-cake", varp(63, 1, 1), done("COOKS_ASSISTANT"), NOT_IN_DIALOGUE));
			h.game.quest(Quest.COOKS_ASSISTANT, cakeBaked ? QuestState.FINISHED : QuestState.IN_PROGRESS);
			ticks(h, 2);
			h.game.varp(63, 1);
			ticks(h, 5);
			// A late flicker of the step must not let the other one in.
			h.game.varp(63, 0);
			ticks(h, 2);
			h.game.varp(63, 1);
			ms[0] += 3_600_000L;
			ticks(h, 5);
			assertEquals(cakeBaked ? 0 : 1, h.firedBy("tower").size());
			assertEquals(cakeBaked ? 1 : 0, h.firedBy("tower-after-cake").size());
			assertEquals("one line, never both", 1, h.spoken.size());
		}
	}

	/**
	 * QA, 15 Oct (probe P1): the mugger on Rune Mysteries' street was said,
	 * Children of the Sun was finished next door, and at the same step the
	 * callback said the mugger again. A base and its callback are one moment.
	 */
	@Test
	public void aCallbackIsNotSaidAfterItsBaseNorTheBaseAfterIt() throws IOException
	{
		for (boolean baseFirst : new boolean[]{true, false})
		{
			Harness h = harness(
				moment("mugger", varp(63, 3, 4), notDone("CHILDREN_OF_THE_SUN"), NOT_IN_DIALOGUE),
				callback("mugger-after-cots", varp(63, 3, 4), done("CHILDREN_OF_THE_SUN"), NOT_IN_DIALOGUE));
			h.game.quest(Quest.CHILDREN_OF_THE_SUN, baseFirst ? QuestState.IN_PROGRESS : QuestState.FINISHED);
			ticks(h, 2);
			h.game.varp(63, 3);
			ticks(h, 5);
			assertEquals(1, h.spoken.size());

			// Twelve minutes on, at the same step, the other quest's state is
			// the other way round. Never in fact for the callback first, but the
			// key is one either way.
			h.game.quest(Quest.CHILDREN_OF_THE_SUN, baseFirst ? QuestState.FINISHED : QuestState.IN_PROGRESS);
			ms[0] += 12 * 60_000L;
			h.game.varp(63, 4);
			ticks(h, 5);
			assertEquals("one moment, said once", 1, h.spoken.size());
		}
	}

	/** Every shipped callback is spent with its base: the base is there, and a first too. */
	@Test
	public void everyShippedCallbackSharesItsBasesOnce() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		int callbacks = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.isOnce() && !rule.onceKey().equals(rule.id))
			{
				callbacks++;
				SpeechRule base = h.rule(rule.onceKey());
				assertTrue(rule.id + " has a base", base != null);
				assertTrue(rule.id + "'s base is a first too", base.isOnce());
			}
		}
		assertTrue(callbacks >= 13);
	}

	@Test
	public void questDoneIsQuietAndHarmlessWhenItCannotRead() throws IOException
	{
		// The client runs no scripts until told otherwise: the state read
		// throws, as it does before the game's scripts are ready.
		Harness unready = harness(moment("unready", varp(63, 1, 1), done("COOKS_ASSISTANT")));
		unready.game.varp(63, 1);
		ticks(unready, 5);
		assertTrue(unready.spoken.isEmpty());
		assertTrue("still enabled", unready.rule("unready").isEnabled());

		// A name that is no quest.
		Harness misspelt = harness(moment("misspelt", varp(63, 1, 1), done("COOKS_ASISTANT")));
		misspelt.game.quest(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		misspelt.game.varp(63, 1);
		ticks(misspelt, 5);
		assertTrue(misspelt.spoken.isEmpty());
		assertTrue("still enabled", misspelt.rule("misspelt").isEnabled());

		// No player: nothing is read at all.
		Harness gone = harness(moment("gone", done("COOKS_ASSISTANT")));
		gone.game.quest(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		gone.game.loggedOut();
		ticks(gone, 5);
		assertTrue(gone.spoken.isEmpty());
		assertFalse(gone.engine.getContext().isQuestDone("COOKS_ASSISTANT"));
		assertTrue("still enabled", gone.rule("gone").isEnabled());
	}

	// ----------------------------------------------------------- the dialogue

	@Test
	public void aStepTakenInConversationIsSaidAsTheBoxCloses() throws IOException
	{
		Harness h = harness(moment("the-door", varp(302, 2, 2), NOT_IN_DIALOGUE));
		h.engine.getContext().setDialogueOpen(true);
		h.game.varp(302, 2);
		ticks(h, 20);
		assertTrue("not while the voice behind the door is still talking", h.spoken.isEmpty());

		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 1);
		assertTrue("the two ticks' delay first", h.spoken.isEmpty());
		ticks(h, 3);
		assertEquals("then just after", 1, h.firedBy("the-door").size());
	}

	@Test
	public void withNoBoxUpTheStepIsSaidAtOnce() throws IOException
	{
		// The gate costs nothing when there is no conversation: a lever
		// pulled, a hole dug.
		Harness h = harness(moment("first-hole", varbit(8063, 3, 3), NOT_IN_DIALOGUE));
		h.game.varbit(8063, 3);
		ticks(h, 4);
		assertEquals(1, h.firedBy("first-hole").size());
	}

	// ---------------------------------------------------------------- a login

	@Test
	public void aStepAlreadyReachedAtLoginIsNotNews() throws IOException
	{
		// Logging in halfway through Priest in Peril: the follower was not
		// there for the king's verdict and does not pretend it was.
		Harness h = harness(
			moment("kings-verdict", varp(302, 4, 4), NOT_IN_DIALOGUE),
			moment("real-drezel", varp(302, 5, 5), NOT_IN_DIALOGUE));
		h.game.varp(302, 4);
		h.engine.primeEdgesOnNextTick();
		ticks(h, 20);
		assertTrue("the step logged in on is baseline", h.spoken.isEmpty());

		h.game.varp(302, 5);
		ticks(h, 5);
		assertEquals("the next one is news", 1, h.firedBy("real-drezel").size());
		assertTrue(h.firedBy("kings-verdict").isEmpty());
	}

	// --------------------------------------------------------------- once only

	@Test
	public void aMomentIsSaidOnceAcrossSessions() throws IOException
	{
		Harness h = harness(moment("the-orb", varbit(5619, 4, 4), NOT_IN_DIALOGUE));
		h.game.varbit(5619, 4);
		ticks(h, 5);
		assertEquals(1, h.firedBy("the-orb").size());

		// Out of the range and back in, past every cooldown.
		h.game.varbit(5619, 3);
		ticks(h, 5);
		ms[0] += 3_600_000L;
		h.game.varbit(5619, 4);
		ticks(h, 5);
		assertEquals("never twice", 1, h.firedBy("the-orb").size());

		// The next session reads the spent firsts back from disk. No
		// priming, so the rule really does rise.
		Harness next = harness(moment("the-orb", varbit(5619, 4, 4), NOT_IN_DIALOGUE));
		next.engine.getContext().restoreSpokenOnce(h.engine.getContext().getSpokenOnce());
		next.game.varbit(5619, 4);
		ticks(next, 5);
		assertTrue("nor in the next session", next.spoken.isEmpty());
	}

	// ------------------------------------------------------- once and occasion

	@Test
	public void heldByASceneAndThenSaidItIsSpent() throws IOException
	{
		Harness h = harness(moment("the-roof", varbit(9632, 16, 16), NOT_IN_DIALOGUE));
		h.engine.setSceneQuiet(true);
		h.game.varbit(9632, 16);
		ticks(h, 100);
		assertTrue("not over the scene", h.spoken.isEmpty());
		assertFalse(h.engine.getContext().hasSaidOnce("the-roof"));

		h.engine.setSceneQuiet(false);
		ticks(h, 2);
		assertEquals("said once it is over", 1, h.firedBy("the-roof").size());
		assertTrue("and that was its once", h.engine.getContext().hasSaidOnce("the-roof"));
	}

	@Test
	public void droppedByASceneItIsStillOwed() throws IOException
	{
		Harness h = harness(moment("the-roof", varbit(9632, 16, 16), NOT_IN_DIALOGUE));
		h.engine.setSceneQuiet(true);
		h.game.varbit(9632, 16);
		ticks(h, SpeechEngine.SCENE_WAIT_TICKS + 10);
		h.engine.setSceneQuiet(false);
		ticks(h, 10);
		assertTrue("old news, dropped", h.spoken.isEmpty());
		assertFalse("and not spent", h.engine.getContext().hasSaidOnce("the-roof"));

		// Its next rising edge, once the cooldown has run: the next
		// conversation closing.
		ms[0] += 600_000L;
		h.engine.getContext().setDialogueOpen(true);
		ticks(h, 3);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 4);
		assertEquals("still owed, and paid", 1, h.firedBy("the-roof").size());
	}

	@Test
	public void aConditionFlickeringInASceneQueuesItOnce() throws IOException
	{
		// A cutscene moves the player about, so a place condition can fall
		// and rise again while the first copy waits. The cooldown, charged at
		// the win, outlasts the scene's wait and keeps a second copy out.
		Harness h = harness(moment("dark-altar", varbit(5619, 4, 4),
			"{\"type\": \"inRegion\", \"regions\": [6716]}", NOT_IN_DIALOGUE));
		h.game.varbit(5619, 4);
		h.game.at(1696, 3880, 0);
		h.engine.setSceneQuiet(true);
		ticks(h, 5);
		for (int i = 0; i < 4; i++)
		{
			h.game.at(1600, 3700, 0);
			ticks(h, 3);
			h.game.at(1696, 3880, 0);
			ticks(h, 3);
		}
		h.engine.setSceneQuiet(false);
		ticks(h, 60);
		assertEquals(1, h.firedBy("dark-altar").size());
	}

	// ------------------------------------------------------- an event in a step

	@Test
	public void anEventDuringTheStepFiresAndNowhereElse() throws IOException
	{
		// The Thing escaping, end to end: the varp is a gate in the "all", so
		// the rule rises on the message, and only during the quest.
		Harness h = harness(moment("thing-escaped", varp(179, 1, 20),
			"{\"type\": \"chatMessage\", \"contains\": \"manages to get away from you\"}",
			"{\"type\": \"inRegion\", \"regions\": [12851]}"));
		h.game.at(3201, 3268, 0);
		ticks(h, 2);
		String escaped = "The... whatever it is... manages to get away from you!";
		int game = ChatMessageType.GAMEMESSAGE.getType();

		h.dispatch(TriggerEvent.chat(escaped, game, ""));
		ticks(h, 5);
		assertTrue("before the quest it is just a sheep", h.spoken.isEmpty());

		h.game.varp(179, 4);
		ticks(h, 2);
		assertTrue("the step alone says nothing", h.spoken.isEmpty());
		h.dispatch(TriggerEvent.chat(escaped, game, ""));
		ticks(h, 1);
		assertTrue("the delay", h.spoken.isEmpty());
		ticks(h, 3);
		assertEquals("the message, during the step", 1, h.firedBy("thing-escaped").size());

		ms[0] += 3_600_000L;
		h.dispatch(TriggerEvent.chat(escaped, game, ""));
		ticks(h, 5);
		assertEquals("and once", 1, h.firedBy("thing-escaped").size());
	}
}
