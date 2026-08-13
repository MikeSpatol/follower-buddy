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
 * The wish and the gift that answers it.
 *
 * <p>The first design offered "Found you something." at all times, and play
 * testing killed it in one sentence: you don't know what you gifted or what
 * the follower even wanted. So the wanting now comes first - the follower
 * asks for a small specific thing, the gift option exists only while that
 * hope does, and every line downstream names the thing. Client-side, nothing
 * real changes hands; the concreteness of the noun is what carries it.
 */
public class GiftTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A follower that has just wished for a feather, out loud. */
	private Harness wishing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		assertTrue(h.engine.force("wish-feather"));
		assertTrue("saying the wish is what opens it",
			h.engine.getContext().isWishing());
		assertEquals("feather", h.engine.getContext().getWishLabel());
		return h;
	}

	/** The same, with an actual feather in the bag. */
	private Harness wishingAndHolding() throws IOException
	{
		Harness h = wishing();
		h.game.inventoryContaining(314);      // Feather
		return h;
	}

	@Test
	public void aBluffGetsItsDryCodaAndTheWishSurvives() throws IOException
	{
		// The catch itself happens in the dialog box now - the script
		// branches on the bag - so what the rules owe the bluff is one dry
		// coda overhead and the mood dent, not a second telling-off.
		Harness h = wishing();
		h.game.inventoryContaining();      // pockets full of nothing
		h.answers("bluff");                // what the empty-bag branch answers
		h.gameTicks(3);

		assertEquals("the coda", 1, h.firedBy("gifted-bluff").size());
		assertTrue("no thanks were given", h.firedBy("gifted-accept").isEmpty());
		assertFalse("nothing is carried out of a bluff",
			h.engine.getContext().isCarrying());
		assertTrue("and the wish survives for a real attempt",
			h.engine.getContext().isWishing());
	}

	@Test
	public void theMidBoxDropStillGetsAnHonestAnswer() throws IOException
	{
		// The race the box cannot see: the bag held the thing when the script
		// was built and not when the branch was picked. The box already said
		// "that's the one", so the rules re-check and say where it went.
		Harness h = wishing();
		h.game.inventoryContaining();      // gone by dispatch time
		h.answers("gift");                 // what the holding branch answers
		h.gameTicks(3);

		assertEquals("the follower notices the vanishing act",
			1, h.firedBy("gifted-slipped").size());
		assertTrue("and no thanks were given", h.firedBy("gifted-accept").isEmpty());
		assertTrue("the wish stays open", h.engine.getContext().isWishing());
	}

	@Test
	public void theGiftAnswersTheWishByName() throws IOException
	{
		Harness h = wishingAndHolding();
		h.answers("gift");
		h.gameTicks(3);      // the thank-you rides a two-tick delay

		assertEquals("the thank-you fired", 1, h.firedBy("gifted-accept").size());
		assertTrue("and it names the thing",
			h.firedBy("gifted-accept").get(0).text.contains("feather"));
		assertEquals("the souvenir label keeps the noun for the whole carry",
			"the feather you found me", h.engine.getContext().getSouvenir());
		assertFalse("and the wish is spent", h.engine.getContext().isWishing());
	}

	@Test
	public void oneWishAtATime() throws IOException
	{
		Harness h = wishing();
		h.engine.getContext().setWish("pot of ink", 45);

		assertEquals("a second wish cannot replace the first",
			"feather", h.engine.getContext().getWishLabel());
	}

	@Test
	public void aGiftWhileCarryingLeavesTheWishOpen() throws IOException
	{
		Harness h = wishingAndHolding();
		h.engine.getContext().pickUp("a nice flat rock", 30);
		h.answers("gift");
		h.gameTicks(3);

		assertEquals("declined, in words", 1, h.firedBy("gifted-carrying").size());
		assertTrue("the decline still names the wished thing",
			h.firedBy("gifted-carrying").get(0).text.contains("feather"));
		assertEquals("the carried souvenir survived",
			"a nice flat rock", h.engine.getContext().getSouvenir());
		assertTrue("and the wish stays open for when the pocket frees up",
			h.engine.getContext().isWishing());
	}

	@Test
	public void aLapsedWishStillGetsAGraciousWord() throws IOException
	{
		// The race: the wish expires between the box opening and the click.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.answers("gift");
		h.gameTicks(3);

		assertEquals("silence there would read as a bug",
			1, h.firedBy("gifted-late").size());
		assertFalse("and nothing is carried out of it",
			h.engine.getContext().isCarrying());
	}

	@Test
	public void theMentionRulesTalkAboutTheGiftWithoutKnowingAboutGifts()
		throws IOException
	{
		Harness h = wishingAndHolding();
		h.answers("gift");
		h.gameTicks(3);
		h.clear();

		assertTrue(h.engine.force("souvenir-mention"));
		boolean mentioned = false;
		for (Harness.Spoken s : h.spoken)
		{
			mentioned |= s.text.contains("the feather you found me");
		}
		assertTrue("a mention reads as the gift by label alone", mentioned);
	}

	@Test
	public void aGiftAnswerIsNotAYes() throws IOException
	{
		// The answered vocabulary grew a word, and the yes/no rules must not
		// hear it: a gift arriving while a want question is open must not
		// read as agreeing to the outing.
		Harness h = wishingAndHolding();
		h.engine.getContext().noteQuestion("want-outing");
		h.answers("gift");
		h.gameTicks(3);

		assertFalse("the gift landed", h.firedBy("gifted-accept").isEmpty());
		assertTrue("and no want appeared out of it",
			!h.engine.getContext().isWanting());
	}
}
