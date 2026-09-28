package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The minutes are written with the scroll, which shares the hand with a
 * borrowed tool. A tool kept between trees is still in it after the work
 * stops, so a meeting then is attended and not written: the scroll would
 * take the tool's place, and putting it away would let go of both.
 */
public class MinutesLeaveTheToolInHandTest
{
	@Test
	public void aFullHandIsNeverWrittenOver()
	{
		assertFalse("however long since, and however lucky the roll",
			FollowerPlugin.minutesWritten(true, Long.MAX_VALUE, 0));
	}

	@Test
	public void anEmptyHandStillWritesNowAndThen()
	{
		assertTrue(FollowerPlugin.minutesWritten(false, Long.MAX_VALUE, 0));
		assertFalse("about one meeting in three",
			FollowerPlugin.minutesWritten(false, Long.MAX_VALUE, 35));
		assertFalse("and not two close together", FollowerPlugin.minutesWritten(false, 0, 0));
	}
}
