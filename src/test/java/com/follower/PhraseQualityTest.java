package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * Whether the shipped lines read well, rather than whether they work.
 *
 * <p>Everything else about the rule file is checked for correctness. These are
 * the things that are correct and still wrong: a line so long it stacks five
 * deep over the follower's head, or the same sentence written into two rules so
 * the follower appears to repeat itself for no reason.
 *
 * <p>The current set is comfortably inside all of these - the longest line is
 * 99 characters and no two rules share a sentence - so this is a guard against
 * the next batch of lines rather than a complaint about these.
 */
public class PhraseQualityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** FollowerOverlay wraps the overhead line at this width. */
	private static final int WRAP_WIDTH = 34;

	/** Four stacked lines is about as tall as it can get before it reads as a wall. */
	private static final int MAX_LENGTH = 4 * WRAP_WIDTH;

	private List<SpeechRule> shipped() throws IOException
	{
		return new Harness(folder.newFolder().toPath()).loader.getRules();
	}

	@Test
	public void noLineIsTallEnoughToBecomeAWall() throws IOException
	{
		List<String> tooLong = new ArrayList<>();
		for (SpeechRule rule : shipped())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String line : rule.say)
			{
				if (line.length() > MAX_LENGTH)
				{
					tooLong.add(rule.id + ": " + line.length() + " chars, about "
						+ (line.length() / WRAP_WIDTH + 1) + " lines - " + line);
				}
			}
		}
		assertTrue("lines that would stack too high over the follower:\n  "
			+ String.join("\n  ", tooLong), tooLong.isEmpty());
	}

	@Test
	public void noTwoRulesShareASentence() throws IOException
	{
		// The follower saying the same thing in two situations reads as a bug
		// even when both rules are working exactly as written.
		Map<String, String> seen = new HashMap<>();
		List<String> shared = new ArrayList<>();
		for (SpeechRule rule : shipped())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String line : rule.say)
			{
				String previous = seen.put(line, rule.id);
				if (previous != null && !previous.equals(rule.id))
				{
					shared.add("\"" + line + "\" in both " + previous + " and " + rule.id);
				}
			}
		}
		assertTrue("the same sentence written into two rules:\n  "
			+ String.join("\n  ", shared), shared.isEmpty());
	}

	@Test
	public void noRuleRepeatsALineWithinItself() throws IOException
	{
		// pickPhrase avoids saying the same INDEX twice running, which does not
		// help if two indices hold the same sentence.
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shipped())
		{
			if (rule.say == null)
			{
				continue;
			}
			List<String> seen = new ArrayList<>();
			for (String line : rule.say)
			{
				if (seen.contains(line))
				{
					problems.add(rule.id + ": \"" + line + "\" twice");
				}
				seen.add(line);
			}
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void noLineIsEmptyOrJustPunctuation() throws IOException
	{
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shipped())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String line : rule.say)
			{
				if (line.trim().isEmpty())
				{
					problems.add(rule.id + ": a blank line");
				}
				else if (!line.matches(".*[A-Za-z].*"))
				{
					problems.add(rule.id + ": \"" + line + "\" has no words in it");
				}
				else if (!line.equals(line.trim()))
				{
					problems.add(rule.id + ": \"" + line + "\" has padding");
				}
			}
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	/**
	 * A group that speaks should have more than a couple of things to say.
	 *
	 * <p>Groups that do not speak at all are exempt, and are not an oversight:
	 * the mimic rules copy your emote and say nothing, which is the whole idea.
	 */
	@Test
	public void everySPEAKINGGroupHasEnoughToSayToNotSoundScripted() throws IOException
	{
		Map<String, Integer> linesPerGroup = new HashMap<>();
		Map<String, Boolean> speaks = new HashMap<>();
		for (SpeechRule rule : shipped())
		{
			linesPerGroup.merge(rule.group,
				rule.say == null ? 0 : rule.say.size(), Integer::sum);
			speaks.merge(rule.group, rule.hasSpeech(), (a, b) -> a || b);
		}

		List<String> thin = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : linesPerGroup.entrySet())
		{
			if (Boolean.TRUE.equals(speaks.get(entry.getKey())) && entry.getValue() < 3)
			{
				thin.add(entry.getKey() + ": " + entry.getValue() + " lines");
			}
		}
		assertTrue("groups with almost nothing to say: " + thin, thin.isEmpty());
	}
}
