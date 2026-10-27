// public:file nicknames
package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.DialogTree;
import com.follower.speech.FollowerDialog;
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

	/** Answers through the plugin's own answerQuestion, which the tests above stand in for. */
	private static void answer(Harness h, String answer) throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		java.lang.reflect.Field engine = FollowerPlugin.class.getDeclaredField("speechEngine");
		engine.setAccessible(true);
		engine.set(plugin, h.engine);
		java.lang.reflect.Method door = FollowerPlugin.class.getDeclaredMethod("answerQuestion", String.class);
		door.setAccessible(true);
		door.invoke(plugin, answer);
	}

	@Test
	public void thePluginsOwnDoorSettlesTheNameOnlyForItsOwnQuestion()
		throws IOException, ReflectiveOperationException
	{
		// The tests above settle the name by hand, the way answerQuestion
		// does, so a door that settled it on any yes at all would pass every
		// one of them. This goes through the real door.
		Harness outing = earned("deaths", 30);
		TriggerContext context = outing.engine.getContext();
		context.noteQuestion("want-outing");
		answer(outing, "yes");
		assertFalse("a yes to the outing is not a yes to the name", context.hasNickname());
		assertTrue("and the name is still to be asked about", context.hasNicknameToOffer());

		Harness yes = earned("deaths", 30);
		yes.engine.getContext().noteQuestion(FollowerPlugin.NICKNAME_TREE);
		answer(yes, "yes");
		assertEquals("the gravedigger", yes.engine.getContext().getNickname());

		Harness no = earned("deaths", 30);
		no.engine.getContext().noteQuestion(FollowerPlugin.NICKNAME_TREE);
		answer(no, "no");
		assertFalse(no.engine.getContext().hasNickname());
		assertFalse("struck through", no.engine.getContext().hasNicknameToOffer());
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
			assertTrue("the line has to say the name: " + line,
				line.contains("{candidate}"));
		}
	}

	@Test
	public void theTreeCanSayTheCandidate() throws IOException
	{
		// QA 2: "Which name?" got "The one I said just now", because a tree
		// was fixed text. Built for Talk-to, its pages and options are filled.
		Harness h = earned("deaths", 30);
		DialogTree tree = new com.google.gson.Gson().fromJson("{\"id\": \"t\", \"nodes\": ["
			+ "{\"id\": \"ask\", \"says\": [\"{candidate}, then?\"],"
			+ " \"choices\": [{\"label\": \"Fine, {candidate}.\", \"next\": \"yes\"}]},"
			+ "{\"id\": \"yes\", \"you\": [\"Fine, {candidate}.\"], \"answer\": \"yes\"}]}", DialogTree.class);
		Map<String, FollowerDialog.Node> script = FollowerDialog.build(tree, answer -> { }, h.engine::fill);
		assertEquals("The gravedigger, then?", script.get("ask").getPages().get(0));
		assertEquals("Fine, the gravedigger.", script.get("ask").getOptionLabels().get(0));
		assertEquals("Fine, the gravedigger.", script.get("yes").getPages().get(0));
	}
}
