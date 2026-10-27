package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where a line's chatbox copy comes from: "Also show lines in the chatbox"
 * and nothing else, as in 1.4.0. A rule's output decides only whether the
 * line goes overhead, so with the setting off no line reaches the chatbox,
 * whatever the rule says.
 */
public class ChatboxMirrorTest
{
	@Test
	public void theMirrorSettingSendsEveryLine()
	{
		assertTrue(FollowerPlugin.postsToChatbox(true, SpeechOutput.OVERHEAD));
		assertTrue(FollowerPlugin.postsToChatbox(true, SpeechOutput.CHATBOX));
		assertTrue(FollowerPlugin.postsToChatbox(true, SpeechOutput.BOTH));
	}

	@Test
	public void withTheMirrorOffNoLineReachesTheChatbox()
	{
		assertFalse(FollowerPlugin.postsToChatbox(false, SpeechOutput.OVERHEAD));
		assertFalse(FollowerPlugin.postsToChatbox(false, SpeechOutput.CHATBOX));
		assertFalse(FollowerPlugin.postsToChatbox(false, SpeechOutput.BOTH));
	}
}
