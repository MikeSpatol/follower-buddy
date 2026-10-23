package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ignored overtures rest.
 *
 * <p>A transcript showed fifteen outings asked into one AFK afternoon: each
 * question politely expired, and each was politely re-asked at the next
 * cooldown, which added up to nagging. Now a question that lapses unanswered
 * earns a two-hour pause, two ignored in one session end the asking, and
 * answering anything clears the ledger - a polite companion drops a hint
 * twice and lets it go. Wishes rest more gently: ninety minutes after one
 * lapses unfulfilled, and a granted wish invites the next freely.
 */
public class IgnoredOverturesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"asker\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"asks\": \"want-outing\","
		+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"a word?\"]},"
		+ "{\"id\": \"wisher\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"wish\": {\"what\": \"feather\", \"minutes\": 1, \"items\": [314]},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a feather, maybe\"]}]}";

	private Harness fresh() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(1);
		return h;
	}

	private void ask(Harness h)
	{
		// A player worth asking is one who is present: long tick jumps in
		// these tests would otherwise trip the attention damper (R15), which
		// composes with this back-off and holds everything, askers included.
		h.game.cameraMoved();
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
	}

	@Test
	public void anIgnoredQuestionIsNotFollowedByAnotherSoon() throws IOException
	{
		Harness h = fresh();
		ask(h);
		assertEquals(1, h.firedBy("asker").size());
		assertEquals("want-outing", h.engine.getContext().getAskedTree());

		// Nobody answers; the question lapses.
		h.gameTicks(201);
		assertEquals("", h.engine.getContext().getAskedTree());

		ask(h);
		assertEquals("an ignored question earns a pause, not a repeat",
			1, h.firedBy("asker").size());

		// Two hours later the pause is over (one ignored is forgivable).
		h.gameTicks(12_001);
		ask(h);
		assertEquals("the pause ends", 2, h.firedBy("asker").size());
	}

	@Test
	public void twoIgnoredQuestionsEndTheAskingForTheSession() throws IOException
	{
		Harness h = fresh();
		ask(h);
		h.gameTicks(201);              // ignored once
		h.gameTicks(12_001);           // pause served
		ask(h);
		h.gameTicks(201);              // ignored twice
		assertEquals(2, h.firedBy("asker").size());

		h.gameTicks(12_001);           // even with the pause long over
		ask(h);
		assertEquals("twice ignored is the answer; the asking stops",
			2, h.firedBy("asker").size());
	}

	@Test
	public void answeringClearsTheLedger() throws IOException
	{
		Harness h = fresh();
		ask(h);
		h.gameTicks(201);              // ignored once; two-hour pause armed

		// The player answers something - the next question, say, opened
		// through Talk-to. Engagement is what the back-off was waiting for.
		h.answers("yes", "want-outing");

		ask(h);
		assertEquals("answering forgives the ignored one and lifts the pause",
			2, h.firedBy("asker").size());
	}

	// public:cut souvenirs-wishes
	@Test
	public void aLapsedWishRestsTheWishing() throws IOException
	{
		Harness h = fresh();
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals(1, h.firedBy("wisher").size());
		assertTrue(h.engine.getContext().isWishing());

		h.gameTicks(102);              // the one-minute wish lapses unfulfilled
		assertTrue(!h.engine.getContext().isWishing());

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals("a lapsed wish rests the wishing",
			1, h.firedBy("wisher").size());

		h.gameTicks(9_001);            // ninety minutes on, the lapse is forgiven
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals("but a wish still waits its day", 1, h.firedBy("wisher").size());

		aDayPasses(h);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals(2, h.firedBy("wisher").size());
	}

	/** The day's rest after a wish, as if it were asked for a day ago. */
	private static void aDayPasses(Harness h)
	{
		h.engine.getContext().setLastWishMs(System.currentTimeMillis()
			- com.follower.speech.TriggerContext.WISH_REST_MS - 1);
		// And a tick, as there always is between two deaths, so the rule sees a new edge.
		h.gameTicks(1);
	}

	/**
	 * Play test, 8 Oct: asked several times a day, the wishes had started to
	 * feel like chores. A granted wish is not punished with the lapse's
	 * back-off, but the next one still waits its day.
	 */
	@Test
	public void aGrantedWishWaitsOnlyItsDay() throws IOException
	{
		Harness h = fresh();
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals(1, h.firedBy("wisher").size());

		// The gift: the wish is spent well before its deadline.
		h.engine.getContext().clearWish();
		h.gameTicks(5);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals("not another the same day", 1, h.firedBy("wisher").size());

		aDayPasses(h);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals("and no longer than that", 2, h.firedBy("wisher").size());
	}
	// public:end souvenirs-wishes

	/** The day's rest is kept with the memory, so a relog or a restart does not reset it. */
	@Test
	public void theDaysRestSurvivesARestart() throws IOException
	{
		Harness h = fresh();
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		com.google.gson.Gson gson = new com.google.gson.Gson();
		String saved = FollowerPlugin.snapshotMemory(h.engine.getContext(), 0, gson);

		Harness restarted = fresh();
		FollowerPlugin.restoreMemory(restarted.engine.getContext(), saved, gson, 0);
		restarted.dispatch(TriggerEvent.simple(TriggerEvent.Type.PLAYER_DEATH));
		assertEquals("the new session remembers it asked", 0, restarted.firedBy("wisher").size());
	}
}
