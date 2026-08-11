package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * The number the shuffle bag was built to move, taken off the shipped rules.
 *
 * <p>Variant counts are the one quality figure in this project that can be
 * gamed by writing more without anything getting better. What matters is how
 * many the player HEARS before one comes round again, and under uniform
 * selection those two numbers are wildly different: thirty written, eight
 * heard. This asserts they are now the same number.
 */
public class RepetitionMeasureTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void everyShippedRuleSpendsAllOfItsWritingBeforeRepeating() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());

		int checked = 0;
		StringBuilder worst = new StringBuilder();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null || rule.say.size() < 2)
			{
				continue;
			}
			checked++;

			// Two full cycles, so the refill is included: the seam between one
			// bag and the next is where a naive implementation repeats.
			int size = rule.say.size();
			Set<String> first = new HashSet<>();
			for (int i = 0; i < size; i++)
			{
				if (!first.add(rule.pickPhrase()))
				{
					worst.append("\n  ").append(rule.id)
						.append(": repeated after ").append(i)
						.append(" of ").append(size);
					break;
				}
			}
			Set<String> second = new HashSet<>();
			for (int i = 0; i < size; i++)
			{
				if (!second.add(rule.pickPhrase()))
				{
					worst.append("\n  ").append(rule.id)
						.append(": second bag repeated after ").append(i)
						.append(" of ").append(size);
					break;
				}
			}
		}

		assertTrue("rules that repeat before spending their variants:" + worst,
			worst.length() == 0);
		assertTrue("expected the shipped set to have plenty of multi-line rules",
			checked > 300);
	}

	@Test
	public void theBiggestRuleInTheFileIsHeardInFull() throws IOException
	{
		// idle-chatter is the most-written rule and the most-heard, which is
		// the combination that made uniform selection expensive. Thirty
		// variants, and the player used to meet a repeat after about eight.
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule rule = h.rule("idle-chatter");
		assertTrue("idle-chatter should still be the big one",
			rule.say.size() >= 25);

		Set<String> heard = new HashSet<>();
		for (int i = 0; i < rule.say.size(); i++)
		{
			heard.add(rule.pickPhrase());
		}
		assertTrue("all " + rule.say.size() + " should come out before any repeats,"
			+ " but only " + heard.size() + " did",
			heard.size() == rule.say.size());
	}
}
