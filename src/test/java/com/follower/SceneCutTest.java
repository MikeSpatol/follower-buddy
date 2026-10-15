package com.follower;

import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A line a starting cutscene cuts off. QA, 15 Oct: the scene clears the
 * overhead on its first tick, and a once line (every quest-moment draft)
 * that had been up a tick or two was spent without being read. Such a line
 * is now said again after the scene and its quiet.
 */
public class SceneCutTest
{
	private static SpeechRule rule(Boolean once, Boolean occasion)
	{
		SpeechRule rule = new SpeechRule();
		rule.id = "probe";
		rule.once = once;
		rule.occasion = occasion;
		return rule;
	}

	@Test
	public void aOnceLineOrAnOccasionJustUpIsSaidAgain()
	{
		assertTrue(FollowerPlugin.saysAgainAfterTheScene(rule(true, null), 600));
		assertTrue(FollowerPlugin.saysAgainAfterTheScene(rule(null, true), 1_900));
	}

	@Test
	public void anOrdinaryLineOrOneAlreadyReadIsLetGo()
	{
		assertFalse("comes round again", FollowerPlugin.saysAgainAfterTheScene(rule(null, null), 600));
		assertFalse("up long enough to read",
			FollowerPlugin.saysAgainAfterTheScene(rule(true, null), FollowerPlugin.SCENE_CUT_GRACE_MS));
		assertFalse("the player's own line", FollowerPlugin.saysAgainAfterTheScene(null, 600));
	}

	/**
	 * Wherever the plugin empties the line queue for a reason other than the
	 * scene itself (a logout, a hop, a film, a profile switch), the line held
	 * over a scene goes with it.
	 */
	@Test
	public void everyQueueResetDropsTheHeldLine() throws IOException
	{
		String[] lines = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.split("\r?\n");
		int clears = 0;
		int dropped = 0;
		for (int i = 0; i < lines.length; i++)
		{
			if (!lines[i].trim().equals("speechQueue.clear();"))
			{
				continue;
			}
			clears++;
			if (lines[i + 1].trim().equals("cutByTheScene = null;"))
			{
				dropped++;
			}
		}
		// The two that keep it: the scene's own start, and the sketch's
		// reshuffle, which puts the queue straight back.
		assertEquals(clears - 2, dropped);
	}
}
