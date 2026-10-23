// public:file company-extras,gestures
package com.follower.follower;

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
		assertTrue(gestures.take(Gestures.Gesture.NOTES));
		assertFalse("not twice", gestures.take(Gestures.Gesture.NOTES));
		assertTrue("a different gesture is a different clock", gestures.take(Gestures.Gesture.GLANCE));

		tick = Gestures.Gesture.NOTES.cooldownTicks - 1;
		assertFalse(gestures.may(Gestures.Gesture.NOTES));
		tick = Gestures.Gesture.NOTES.cooldownTicks;
		assertTrue(gestures.may(Gestures.Gesture.NOTES));
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
