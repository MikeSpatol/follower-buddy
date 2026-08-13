package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The gift: the first thing the player can DO for the follower beyond taking
 * it somewhere.
 *
 * <p>Client-side, nothing real changes hands, so the gift is fictional and
 * deliberately unspecified - and then made real by the machinery it lands in:
 * it becomes an ordinary souvenir, which means the mention rules bring it up
 * unprompted, the lost rule mourns it, and the label makes every one of those
 * lines read as the gift without any of them knowing about gifts.
 */
public class GiftTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness given() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.answers("gift");
		h.gameTicks(3);      // the thank-you rides a two-tick delay
		return h;
	}

	@Test
	public void aGiftIsThankedAndCarried()
	{
		try
		{
			Harness h = given();
			assertEquals("the thank-you line", 1, h.firedBy("gifted-accept").size());
			assertTrue("and the gift is now carried",
				h.engine.getContext().isCarrying());
			assertEquals("under the label that makes every later line about it",
				"the little thing you found me", h.engine.getContext().getSouvenir());
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void aSecondGiftWhileCarryingIsDeclinedWithoutDroppingTheFirst()
		throws IOException
	{
		Harness h = given();
		h.answers("gift");
		h.gameTicks(3);

		assertEquals("declined, in words", 1, h.firedBy("gifted-carrying").size());
		assertEquals("the accept rule stayed out of it",
			1, h.firedBy("gifted-accept").size());
		assertEquals("and the first gift survived",
			"the little thing you found me", h.engine.getContext().getSouvenir());
	}

	@Test
	public void theMentionRulesTalkAboutTheGiftWithoutKnowingAboutGifts()
		throws IOException
	{
		Harness h = given();
		h.clear();

		// The mention machinery is generic; force one and read the label back
		// through the {souvenir} placeholder.
		assertTrue(h.engine.force("souvenir-mention"));
		boolean mentioned = false;
		for (Harness.Spoken s : h.spoken)
		{
			mentioned |= s.text.contains("the little thing you found me");
		}
		assertTrue("a mention should read as the gift by label alone", mentioned);
	}

	@Test
	public void aGiftAnswerIsNotAYes() throws IOException
	{
		// The answered vocabulary grew a word, and the yes/no rules must not
		// hear it: a gift arriving while a want question is open must not
		// read as agreeing to the outing.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.engine.getContext().noteQuestion("want-outing");
		h.answers("gift");
		h.gameTicks(3);

		assertFalse("the gift landed", h.firedBy("gifted-accept").isEmpty());
		assertTrue("and no want appeared out of it",
			!h.engine.getContext().isWanting());
	}
}
