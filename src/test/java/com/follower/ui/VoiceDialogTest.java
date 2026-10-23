// public:file messages-window,sharevoice
package com.follower.ui;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The Sounds window's picker holds "(no sound)", then the files in the
 * folder, then a "(missing)" entry when a line's file has gone. Only the
 * files in between are files: QA, 15 Oct, re-picking the missing entry read
 * past the end of the list and threw, and Play stayed enabled on the two
 * entries it could do nothing with.
 */
public class VoiceDialogTest
{
	private static final List<String> FILES = Arrays.asList("a.wav", "b.wav", "c.wav");

	@Test
	public void noSoundIsNoFile()
	{
		assertNull(VoiceDialog.fileAt(FILES, 0));
		assertNull("nothing selected", VoiceDialog.fileAt(FILES, -1));
	}

	@Test
	public void theFilesAreTheirOwnEntries()
	{
		assertEquals("a.wav", VoiceDialog.fileAt(FILES, 1));
		assertEquals("c.wav", VoiceDialog.fileAt(FILES, 3));
	}

	@Test
	public void theMissingEntryPastTheFilesIsNoFile()
	{
		assertNull(VoiceDialog.fileAt(FILES, 4));
		assertNull(VoiceDialog.fileAt(java.util.Collections.emptyList(), 1));
	}
}
