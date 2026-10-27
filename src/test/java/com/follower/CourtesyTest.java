package com.follower;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An emote done AT the follower is answered rather than copied. The
 * difference between an echo and a reply is the difference between a mirror
 * and company, and it is the closest OSRS gets to petting the dog.
 */
public class CourtesyTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int BOW = 858;
	private static final int CHEER = 862;
	private static final int CLAP = 865;

	/** The player a tile north of the follower, turned to face it. */
	private Harness facingIt() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(50, 50, 0).turned(0);
		h.engine.getContext().setFollowerLocation(new WorldPoint(50, 49, 0));
		h.gameTicks(2);
		return h;
	}

	private static void fire(Harness h, int emote)
	{
		h.dispatch(TriggerEvent.animation(emote));
		h.gameTicks(5);
	}

	@Test
	public void aBowDoneAtItGetsABowBackAndNoCopy() throws IOException
	{
		Harness h = facingIt();
		fire(h, BOW);
		List<Harness.Spoken> answered = h.firedBy("answer-bow");
		assertEquals("the answer", 1, answered.size());
		assertEquals(BOW, answered.get(0).animationId);
		assertTrue("and the mimic does not play on top", h.firedBy("mimic-emotes").isEmpty());
	}

	@Test
	public void aCheerGetsAClapWhichIsAReplyNotAnEcho() throws IOException
	{
		Harness h = facingIt();
		fire(h, CHEER);
		assertEquals(CLAP, h.firedBy("answer-cheer").get(0).animationId);
	}

	@Test
	public void anEmoteDoneNearItButNotAtItIsCopiedAsBefore() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(50, 50, 0).turned(1024);
		h.engine.getContext().setFollowerLocation(new WorldPoint(50, 49, 0));
		h.gameTicks(2);
		fire(h, BOW);
		assertTrue(h.firedBy("answer-bow").isEmpty());
		assertFalse("the mimic", h.firedBy("mimic-emotes").isEmpty());

		Harness far = new Harness(folder.newFolder().toPath());
		far.game.at(50, 50, 0).turned(0);
		far.engine.getContext().setFollowerLocation(new WorldPoint(50, 47, 0));
		far.gameTicks(2);
		fire(far, BOW);
		assertTrue("three tiles off is not addressing it", far.firedBy("answer-bow").isEmpty());
	}

	@Test
	public void theDiagonalsCountAsFacing()
	{
		TriggerContext context = new TriggerContext(new FakeGame()
			.at(50, 50, 0).turned(TriggerContext.bearing(1, 1)).client);
		context.setFollowerLocation(new WorldPoint(51, 51, 0));
		assertTrue("north-east, facing north-east", context.isAddressingFollower());
		context.setFollowerLocation(new WorldPoint(51, 50, 0));
		assertTrue("east is inside the tolerance", context.isAddressingFollower());
		context.setFollowerLocation(new WorldPoint(49, 49, 0));
		assertFalse("south-west is not", context.isAddressingFollower());
		// A quarter turn off is looking past it: the emote was for somebody else.
		context.setFollowerLocation(new WorldPoint(49, 51, 0));
		assertFalse("north-west is a quarter turn off", context.isAddressingFollower());
	}

	@Test
	public void theBearingIsTheActorCircle()
	{
		assertEquals("south", 0, TriggerContext.bearing(0, -1));
		assertEquals("west", 512, TriggerContext.bearing(-1, 0));
		assertEquals("north", 1024, TriggerContext.bearing(0, 1));
		assertEquals("east", 1536, TriggerContext.bearing(1, 0));
	}

	@Test
	public void everyAnswerIsForAnEmoteTheMimicKnowsAndRepliesWithOneItCanPlay()
	{
		// The mimic list is the set of one-shots proven to play on the
		// follower; an answer outside it would be a reply nobody can see.
		Harness h;
		try
		{
			h = new Harness(folder.newFolder().toPath());
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		Set<Integer> proven = new HashSet<>(h.rule("mimic-emotes").when.ids);
		int answers = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.id == null || !rule.id.startsWith("answer-"))
			{
				continue;
			}
			answers++;
			assertEquals("mimic", rule.group);
			assertTrue(rule.id + " outranks the mimic", rule.priority > h.rule("mimic-emotes").priority);
			assertTrue(rule.id + " answers with an unproven animation", proven.contains(rule.animation));
			assertTrue(rule.id + " must ask whether it was addressed", rule.when.usesType("addressed"));
		}
		assertTrue("the answer table looks too short: " + answers, answers >= 20);
	}
}
