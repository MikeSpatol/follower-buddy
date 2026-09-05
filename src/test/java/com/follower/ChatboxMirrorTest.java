package com.follower;

import com.follower.speech.SpeechOutput;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where a line's chatbox copy comes from. "Also show lines in the chatbox"
 * mirrors every line; a rule whose output names the chatbox lands there on
 * its own. speakNow makes one decision from the pair, so a line never
 * appears twice.
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
	public void aRuleAimedAtTheChatboxGetsThereWithoutTheMirror()
	{
		assertTrue(FollowerPlugin.postsToChatbox(false, SpeechOutput.CHATBOX));
		assertTrue(FollowerPlugin.postsToChatbox(false, SpeechOutput.BOTH));
	}

	@Test
	public void anOverheadLineStaysOutOfTheChatboxWhenTheMirrorIsOff()
	{
		assertFalse(FollowerPlugin.postsToChatbox(false, SpeechOutput.OVERHEAD));
	}
}
