package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower responding to the PLAYER rather than to the world.
 *
 * <p>The failure that matters here is a follower that answers everybody: a
 * "hello" in a crowded bank raises one chat event per person who says it, and a
 * companion that greets all of them is not a companion, it is a bot. Every case
 * below therefore checks the negative as well as the positive.
 */
public class AttentionTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void theFollowerGreetsYouAndNotTheStreet() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		h.someoneSays("Somebody Else", "hello");
		h.someoneSays("Another Person", "hi");
		// These rules carry a delayTicks, so a line that has WON is still only
		// pending. Asserting before the ticks pass would pass no matter what.
		h.gameTicks(6);
		assertTrue("a follower that greets the whole bank is not greeting you",
			h.firedBy("greet-back").isEmpty());

		h.playerSays("hello");
		h.gameTicks(8);
		assertFalse("your own hello, though", h.firedBy("greet-back").isEmpty());
	}

	@Test
	public void aGameMessageIsNotSomebodyTalking() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		// The game says plenty of things containing "hi" and "ty". None of them
		// are the player saying hello or thank you.
		h.dispatch(TriggerEvent.chat("You hit the target.",
			net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
		h.dispatch(TriggerEvent.chat("Oh dear, you are dead!",
			net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
		h.gameTicks(6);

		assertTrue("game messages must not be read as conversation",
			h.firedBy("greet-back").isEmpty() && h.firedBy("thanked").isEmpty());
	}

	@Test
	public void aWordInsideAnotherWordIsNotThatWord() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		h.playerSays("this city is huge");
		h.playerSays("pretty good drop");
		h.gameTicks(6);

		assertTrue("'hi' in 'this' and 'ty' in 'pretty' are not greetings or thanks",
			h.firedBy("greet-back").isEmpty() && h.firedBy("thanked").isEmpty());
	}

	@Test
	public void restingTheMouseOnItIsNoticedAndCrossingTheScreenIsNot() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setHoverTicks(2);
		h.gameTicks(3);
		assertTrue("a cursor passing over is not attention",
			h.firedBy("hovered").isEmpty());

		// The rule rolls a chance, so give it a fair number of chances.
		h.engine.getContext().setHoverTicks(30);
		for (int i = 0; i < 60 && h.firedBy("hovered").isEmpty(); i++)
		{
			h.engine.getContext().setHoverTicks(30);
			h.gameTicks(1);
		}
		assertFalse("resting on it is", h.firedBy("hovered").isEmpty());
	}

	@Test
	public void beingExaminedGetsAnAnswer() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		h.gameTicks(8);
		assertFalse("being looked up is worth noticing", h.firedBy("examined").isEmpty());
	}

	@Test
	public void theFlinchIsSilent() throws IOException
	{
		// A body that reacts before the mouth does is most of what makes
		// something look like it is watching. A line here would just be one
		// more thing said during a fight that is already noisy.
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule flinch = h.rule("flinch-big-hit");

		assertTrue("the flinch must not speak",
			flinch.say == null || flinch.say.isEmpty());
		assertFalse("but it must do something", flinch.animation == null);

		// One winner per event, so an animation-only rule is invisible for any
		// hit a higher-priority spoken rule also matches. big-hit-taken owns
		// everything from 30 up, which was the whole top of the range - the
		// biggest hits were the ones that did NOT flinch. The body language has
		// to live on whichever rule actually wins the moment.
		SpeechRule spoken = h.rule("big-hit-taken");
		assertTrue("big-hit-taken outranks the flinch and must carry it too",
			spoken.priority <= flinch.priority
				|| flinch.animation.equals(spoken.animation));
	}

	@Test
	public void anythingThatVanishesHoldsStillWhileItGoes()
	{
		// A rule that vanishes is playing a departure - a teleport cast, a
		// comedy death - and a departure has to finish where it started.
		//
		// The case that found this: the player teleports while the follower is
		// off on an idle distraction. The player animating resets the idle
		// counter, so the wander is released that same tick, and the follower
		// abandons its own cast to run back to somebody who is no longer
		// standing there. Holding still also keeps the wander logic out
		// entirely, since it treats a held emote as busy.
		Harness h;
		try
		{
			h = new Harness(folder.newFolder().toPath());
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}

		java.util.List<String> loose = new java.util.ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (Boolean.TRUE.equals(rule.vanishAfter)
				&& !Boolean.TRUE.equals(rule.holdStill))
			{
				loose.add(rule.id);
			}
		}
		assertTrue("rules that vanish without planting the follower first,"
			+ " so anything that moves it mid-animation cuts the exit short: "
			+ loose, loose.isEmpty());
	}

	@Test
	public void theBagWarningComesWhileThereIsStillRoom() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());

		h.game.inventoryUsing(24);
		h.gameTicks(3);
		assertTrue("four slots left is not a warning",
			h.firedBy("bag-nearly-full").isEmpty());

		h.game.inventoryUsing(26);
		h.gameTicks(3);
		assertFalse("two slots left is the moment to say so",
			h.firedBy("bag-nearly-full").isEmpty());

		assertEquals("and the point is that it is not full yet",
			2, h.engine.getContext().getFreeInventorySlots());
	}
}
