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
		assertTrue(FollowerPlugin.saysAgainAfterTheScene(rule(true, null), 600, 1_000));
		assertTrue(FollowerPlugin.saysAgainAfterTheScene(rule(null, true), 1_900, 1_000));
	}

	/**
	 * QA pass 2: the window's scene cut the hideout line 2.4 s into the
	 * 3.5 s it takes to read, and it was spent half read. A line cut before
	 * it could be read is said again; a short one read by then is not.
	 */
	@Test
	public void aLineCutBeforeItCouldBeReadIsSaidAgain()
	{
		assertTrue(FollowerPlugin.saysAgainAfterTheScene(rule(true, true), 2_400, 3_529));
		assertFalse("read by then",
			FollowerPlugin.saysAgainAfterTheScene(rule(true, true), 2_400, 1_176));
	}

	@Test
	public void anOrdinaryLineOrOneAlreadyReadIsLetGo()
	{
		assertFalse("comes round again", FollowerPlugin.saysAgainAfterTheScene(rule(null, null), 600, 1_000));
		assertFalse("up long enough to read",
			FollowerPlugin.saysAgainAfterTheScene(rule(true, null), FollowerPlugin.SCENE_CUT_GRACE_MS, 1_000));
		assertFalse("the player's own line", FollowerPlugin.saysAgainAfterTheScene(null, 600, 1_000));
	}

	/**
	 * The plugin judges the cut on the line's own reading time, and keeps
	 * the line through a conversation after the quiet rather than saying it
	 * into the box, where the words are dropped (QA pass 2: a runner reaches
	 * Sergeant Tobyn inside the quiet after the window's scene).
	 */
	@Test
	public void thePluginJudgesOnReadingTimeAndKeepsTheLineThroughAConversation() throws IOException
	{
		String src = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8);
		assertTrue(src.contains("timeToReadMs(onScreen.text)) ? onScreen : null;"));
		assertTrue(src.contains("if (cutByTheScene != null && (sceneQuiet() || filming || inConversation()))"));
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
