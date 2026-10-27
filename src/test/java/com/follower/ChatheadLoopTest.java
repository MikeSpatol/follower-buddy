package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The dialogue head in a box left open past one pass of its clip (QA, 16
 * Oct). The game wraps a clip back by its frame step, so a chathead loops
 * only its tail; wrapped to frame 0, the follower started its line again.
 * Cache: every expression clip has frame step 12, over 40 to 106 frames.
 */
public class ChatheadLoopTest
{
	@Test
	public void aTalkClipLoopsItsTailAsTheGameDoes()
	{
		assertEquals("plays straight through", 5, FollowerDialog.nextTalkFrame(4, 40, 12));
		assertEquals("then back twelve frames, not to the top", 28, FollowerDialog.nextTalkFrame(39, 40, 12));
		assertEquals("the four-line clip the same way", 94, FollowerDialog.nextTalkFrame(105, 106, 12));
	}

	@Test
	public void aClipWithNoStepStartsAgain()
	{
		assertEquals(0, FollowerDialog.nextTalkFrame(9, 10, -1));
		assertEquals("nor one longer than the clip", 0, FollowerDialog.nextTalkFrame(9, 10, 20));
	}
}
