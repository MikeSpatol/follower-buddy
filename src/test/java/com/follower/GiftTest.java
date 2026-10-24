// public:file souvenirs-wishes
package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

	/**
	 * Every thank-you names the thing, not just the one a shuffle happened to
	 * pick. The rule above tests a single firing, which is one line out of
	 * fourteen - it passed for a year and then failed the moment ten lines
	 * were added, four of which had quietly dropped the noun. The design says
	 * everything downstream says feather; this is that, asked of the pool.
	 */
	@Test
	public void everyThankYouNamesTheThing() throws IOException
	{
		Harness h = wishingAndHolding();
		java.util.List<String> bare = new java.util.ArrayList<>();
		for (com.follower.speech.SpeechRule rule : h.loader.getRules())
		{
			if ("gifted-accept".equals(rule.id) || "gifted-swap".equals(rule.id))
			{
				for (String line : rule.say)
				{
					if (!line.contains("{wish}"))
					{
						bare.add(rule.id + ": " + line);
					}
				}
			}
		}
		assertTrue("a handover that does not name what was handed over: " + bare,
			bare.isEmpty());
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
		h.engine.getContext().setWish("pot of ink", 45, null);

		assertEquals("a second wish cannot replace the first",
			"feather", h.engine.getContext().getWishLabel());
	}

	@Test
	public void aGiftWhileCarryingTradesUpOutLoud() throws IOException
	{
		// The deferral this replaces parked the gift option for the length of
		// a ninety-minute carry, which play testing read as the option
		// refusing to go away. A gift outranks a rock the follower found
		// itself, and the swap is spoken - a SILENT swap was the thing the
		// old rule existed to prevent, not the swap itself.
		Harness h = wishingAndHolding();
		h.engine.getContext().pickUp("a nice flat rock", 30);
		h.answers("gift");
		h.gameTicks(3);

		assertEquals("the trade, in words", 1, h.firedBy("gifted-swap").size());
		assertTrue("naming what goes out",
			h.firedBy("gifted-swap").get(0).text.contains("nice flat rock"));
		assertEquals("the gift took the pocket",
			"the feather you found me", h.engine.getContext().getSouvenir());
		assertFalse("and the wish is spent, so the option finally goes away",
			h.engine.getContext().isWishing());
	}

	@Test
	public void aGiftIsNotLostToASouvenirStillQueued() throws IOException
	{
		// A rock found a moment before, still waiting behind the overhead box
		// while the Talk-to box is open, is carried only once its line lands.
		// Both thank-yous read the pocket, so the guard held them back for
		// the rock - and the answer that brings them is never given again.
		// The box had already said "that's the one", and nothing followed:
		// no thanks, the wish still open, the gift option back in the box.
		Harness h = wishingAndHolding();
		List<String> handedOver = new ArrayList<>();
		List<Runnable> held = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) ->
		{
			handedOver.add(rule.id);
			held.add(onSaid);
		});
		assertTrue(h.engine.force("souvenir-rock"));
		Runnable rockLands = held.get(0);

		h.answers("gift");
		h.gameTicks(3);
		assertTrue("the gift is thanked for: " + handedOver,
			handedOver.contains("gifted-accept") || handedOver.contains("gifted-swap"));
		assertFalse("and the wish is spent", h.engine.getContext().isWishing());
		assertEquals("the gift took the pocket",
			"the feather you found me", h.engine.getContext().getSouvenir());

		rockLands.run();
		assertEquals("and the rock landing after it does not take it back",
			"the feather you found me", h.engine.getContext().getSouvenir());
	}

	@Test
	public void aSouvenirLandingBeforeTheThankYouStillGivesWayToTheGift() throws IOException
	{
		// The same, with the rock's line landing in the two ticks the
		// thank-you waits. Chosen on empty hands, the thank-you picked the
		// gift up rather than trading for it, and the pocket refuses a second
		// thing: the wish was thanked for and spent, and the rock kept.
		Harness h = wishingAndHolding();
		List<Runnable> held = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) -> held.add(onSaid));
		assertTrue(h.engine.force("souvenir-rock"));
		Runnable rockLands = held.get(0);

		h.answers("gift");
		rockLands.run();
		assertEquals("a nice flat rock", h.engine.getContext().getSouvenir());
		h.gameTicks(3);

		assertFalse("the wish is spent", h.engine.getContext().isWishing());
		assertEquals("and the gift outranks the rock",
			"the feather you found me", h.engine.getContext().getSouvenir());
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
			mentioned |= s.text.contains("feather you found me");
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
