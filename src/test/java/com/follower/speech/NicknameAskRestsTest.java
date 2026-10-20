package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The name is not nagged about. Text QA5: "the swot" was offered 63 times
 * since 20 Sep, 14 times in the four days to 20 Oct, and never answered;
 * the back-off for an ignored question lasted only the session.
 */
public class NicknameAskRestsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long HOUR = 3_600_000L;

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": []}");
		TriggerContext ctx = h.engine.getContext();
		ctx.setNicknames(Collections.singletonMap("levels", "the swot"));
		for (int i = 0; i < 40; i++)
		{
			ctx.tally("levels");
		}
		h.gameTicks(1);
		return h;
	}

	/** Asked, and the answer window let lapse. */
	private static void askedAndIgnored(Harness h)
	{
		h.engine.getContext().noteQuestion(TriggerContext.NICKNAME_TREE);
		h.gameTicks(205);
	}

	@Test
	public void theNameIsAskedAboutOnceADay() throws IOException
	{
		Harness h = harness();
		TriggerContext ctx = h.engine.getContext();
		assertTrue(ctx.hasNicknameToOffer());
		askedAndIgnored(h);
		assertFalse("not again the same day", ctx.hasNicknameToOffer());
		long now = System.currentTimeMillis();
		assertTrue(ctx.isNicknameAskResting(now + 23 * HOUR));
		assertFalse("a day on", ctx.isNicknameAskResting(now + 25 * HOUR));
	}

	@Test
	public void threeAsksUnansweredRestItAWeek() throws IOException
	{
		Harness h = harness();
		TriggerContext ctx = h.engine.getContext();
		for (int i = 0; i < 3; i++)
		{
			askedAndIgnored(h);
		}
		assertEquals(3, ctx.getNicknameAsksIgnored());
		long now = System.currentTimeMillis();
		assertTrue("six days on", ctx.isNicknameAskResting(now + 6 * 24 * HOUR));
		assertFalse("a week on", ctx.isNicknameAskResting(now + 7 * 24 * HOUR + HOUR));

		// An answer, any answer, starts the count over.
		ctx.noteQuestion(TriggerContext.NICKNAME_TREE);
		ctx.noteAnswered();
		assertEquals(0, ctx.getNicknameAsksIgnored());
	}

	@Test
	public void anotherQuestionDoesNotRestTheName() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().noteQuestion("want-outing");
		h.gameTicks(205);
		assertTrue(h.engine.getContext().hasNicknameToOffer());
	}
}
