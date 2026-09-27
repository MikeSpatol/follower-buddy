package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The earned name, proposed rather than used: asked about through Talk-to,
 * said only once agreed, struck through for good when refused, and offered
 * afresh when the play moves on to a different one.
 */
public class NicknameTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness earned(String tally, int times) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Map<String, String> names = new LinkedHashMap<>();
		names.put("deaths", "the gravedigger");
		names.put("kills", "the local menace");
		h.engine.getContext().setNicknames(names);
		h.engine.getContext().setMetOnDay(java.time.LocalDate.now().toEpochDay() - 30);
		for (int i = 0; i < times; i++)
		{
			h.engine.getContext().tally(tally);
		}
		h.gameTicks(1);
		return h;
	}

	@Test
	public void theCandidateIsAskedAboutBeforeItIsUsed() throws IOException
	{
		Harness h = earned("deaths", 30);
		TriggerContext context = h.engine.getContext();
		assertEquals("the gravedigger", context.getEarnedNickname());
		assertTrue(context.hasNicknameToOffer());
		assertFalse("not a nickname until agreed", context.hasNickname());

		// The everyday lines that say {nickname} stay quiet meanwhile.
		h.gameTicks(300);
		assertTrue(h.firedBy("nickname-earned").isEmpty());
	}

	@Test
	public void aYesSettlesItAndTheThankYouSaysTheName() throws IOException
	{
		Harness h = earned("deaths", 30);
		TriggerContext context = h.engine.getContext();
		context.noteQuestion(FollowerPlugin.NICKNAME_TREE);
		// What the plugin's answerQuestion does for this tree.
		context.acceptNickname(context.getEarnedNickname());
		h.answers("yes", FollowerPlugin.NICKNAME_TREE);
		h.gameTicks(6);
		assertEquals("the gravedigger", context.getNickname());
		assertFalse(context.hasNicknameToOffer());
		assertEquals(1, h.firedBy("nickname-accepted").size());
		assertTrue(h.firedBy("nickname-accepted").get(0).text.contains("The gravedigger"));
		assertTrue(h.firedBy("nickname-refused").isEmpty());
		assertTrue("and a yes to the name is not a yes to an outing",
			h.spoken.stream().noneMatch(spoken -> spoken.rule.id.startsWith("want-")));
	}

	@Test
	public void aNoStrikesItThroughForGood() throws IOException
	{
		Harness h = earned("deaths", 30);
		TriggerContext context = h.engine.getContext();
		context.noteQuestion(FollowerPlugin.NICKNAME_TREE);
		context.refuseNickname(context.getEarnedNickname());
		h.answers("no", FollowerPlugin.NICKNAME_TREE);
		h.gameTicks(6);
		assertFalse(context.hasNickname());
		assertFalse("never offered again", context.hasNicknameToOffer());
		assertEquals(1, h.firedBy("nickname-refused").size());
		assertTrue(h.firedBy("nickname-accepted").isEmpty());

		// A different name, earned later, is offered afresh.
		for (int i = 0; i < 60; i++)
		{
			context.tally("kills");
		}
		assertEquals("the local menace", context.getEarnedNickname());
		assertTrue(context.hasNicknameToOffer());
	}

	@Test
	public void aYesToTheOutingIsNotAYesToTheName() throws IOException
	{
		Harness h = earned("deaths", 30);
		h.engine.getContext().noteQuestion("want-outing");
		h.answers("yes", "want-outing");
		h.gameTicks(6);
		assertTrue(h.firedBy("nickname-accepted").isEmpty());
		assertFalse(h.engine.getContext().hasNickname());
	}

	@Test
	public void anAgreedNameThatThePlayHasMovedOnFromIsOfferedAgain() throws IOException
	{
		Harness h = earned("deaths", 30);
		TriggerContext context = h.engine.getContext();
		context.acceptNickname("the gravedigger");
		assertFalse(context.hasNicknameToOffer());
		for (int i = 0; i < 60; i++)
		{
			context.tally("kills");
		}
		assertEquals("the local menace", context.getEarnedNickname());
		assertTrue("the new candidate is worth asking about", context.hasNicknameToOffer());
		assertEquals("and the agreed one stands until then", "the gravedigger", context.getNickname());
	}

	@Test
	public void theAskingRuleSaysTheCandidateAndOpensTheTree() throws IOException
	{
		Harness h = earned("deaths", 30);
		assertEquals(FollowerPlugin.NICKNAME_TREE, h.rule("ask-nickname").asks);
		for (String line : h.rule("ask-nickname").say)
		{
			assertTrue("the line has to say the name, since the tree cannot: " + line,
				line.contains("{candidate}"));
		}
	}
}
