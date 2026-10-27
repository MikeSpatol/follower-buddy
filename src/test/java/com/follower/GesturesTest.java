package com.follower;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The ledger of body beats: each on its own clock, halved while a favour is owed. */
public class GesturesTest
{
	private int tick;
	private boolean grateful;

	@Test
	public void aGestureWaitsOutItsOwnCooldownAndNobodyElses()
	{
		Gestures gestures = new Gestures(() -> tick, () -> grateful);
		assertTrue(take(gestures, Gestures.Gesture.NOTES));
		assertFalse("not twice", take(gestures, Gestures.Gesture.NOTES));
		assertTrue("a different gesture is a different clock", take(gestures, Gestures.Gesture.GLANCE));

		tick = Gestures.Gesture.NOTES.cooldownTicks - 1;
		assertFalse(gestures.may(Gestures.Gesture.NOTES));
		tick = Gestures.Gesture.NOTES.cooldownTicks;
		assertTrue(gestures.may(Gestures.Gesture.NOTES));
	}

	/**
	 * Takes the gesture if it may be made: true means go ahead, it is spent.
	 * Gestures had this as take(); only the tests used it.
	 */
	private static boolean take(Gestures gestures, Gestures.Gesture gesture)
	{
		if (!gestures.may(gesture))
		{
			return false;
		}
		gestures.spent(gesture);
		return true;
	}

	@Test
	public void goodwillHalvesTheWait()
	{
		Gestures gestures = new Gestures(() -> tick, () -> grateful);
		gestures.spent(Gestures.Gesture.WALK_OVER);
		tick = Gestures.Gesture.WALK_OVER.cooldownTicks / 2;
		assertFalse(gestures.may(Gestures.Gesture.WALK_OVER));
		grateful = true;
		assertTrue(gestures.may(Gestures.Gesture.WALK_OVER));
	}

	@Test
	public void aResetForgetsTheSession()
	{
		Gestures gestures = new Gestures(() -> tick, () -> grateful);
		gestures.spent(Gestures.Gesture.WAVE);
		gestures.reset();
		assertTrue(gestures.may(Gestures.Gesture.WAVE));
	}
}
