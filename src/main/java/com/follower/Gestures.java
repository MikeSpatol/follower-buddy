package com.follower;

import java.util.*;
import java.util.Map;
import java.util.function.*;
import lombok.RequiredArgsConstructor;

/**
 * The ledger of body beats, so the same one cannot appear twice in a
 * stretch.
 *
 * <p>The lines have had this for a long time: a rule carries its own
 * cooldown, and the engine will not say the same sentence twice in a
 * session. The body had nothing of the kind, and a gesture that repeats is
 * worse than a line that does - when Ellie handed the player ammo every
 * minute "it devalued the act of gifting", and the fix was to put every
 * buddy behaviour behind really big timers. So every gesture the moments
 * make goes through here first, each with a cooldown of its own, and the
 * ones that are meant to be rare stay rare.
 *
 * <p>Goodwill shortens the wait. For the half hour after the player did
 * what the follower asked, every cooldown is halved: the favour is paid
 * back in attentiveness, which is the currency a companion actually has.
 */
@RequiredArgsConstructor
class Gestures
{
	/** What the body can do, and how long it waits before doing it again. */
	@RequiredArgsConstructor
	enum Gesture
	{
		/** A turn of the head toward something. */
		GLANCE(30),
		/** EMOTE_YES at the player. */
		NOD(100),
		/** The scroll out and a few lines written. */
		NOTES(500),
		/** Walking over to something to have a look at it. */
		WALK_OVER(900),
		/** Standing off and peering at something. */
		PEER(400),
		/** The wave at the logout tab. */
		WAVE(500),
		/** The check-in: a look at the player for no reason, while idle. */
		CHECK_IN(30);

		final int cooldownTicks;
	}

	final IntSupplier tick;
	final BooleanSupplier grateful;
	final Map<Gesture, Integer> lastAt = new EnumMap<>(Gesture.class);

	/** Whether the gesture is off cooldown. Does not spend it. */
	boolean may(Gesture gesture)
	{
		var last = lastAt.get(gesture);
		if (last == null)
			return true;
		int wait = gesture.cooldownTicks;
		if (grateful.getAsBoolean())
			wait /= 2;
		return tick.getAsInt() - last >= wait;
	}

	/** Marks the gesture as made now. */
	void spent(Gesture gesture)
	{
		lastAt.put(gesture, tick.getAsInt());
	}

	/** Logout, hop: the ledger belongs to the session. */
	void reset()
	{
		lastAt.clear();
	}
}
