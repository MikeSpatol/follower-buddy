package com.follower.follower;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

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
public class Gestures
{
	/** What the body can do, and how long it waits before doing it again. */
	public enum Gesture
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

		Gesture(int cooldownTicks)
		{
			this.cooldownTicks = cooldownTicks;
		}
	}

	private final IntSupplier tick;
	private final BooleanSupplier grateful;
	private final Map<Gesture, Integer> lastAt = new EnumMap<>(Gesture.class);

	public Gestures(IntSupplier tick, BooleanSupplier grateful)
	{
		this.tick = tick;
		this.grateful = grateful;
	}

	/** Whether the gesture is off cooldown. Does not spend it. */
	public boolean may(Gesture gesture)
	{
		Integer last = lastAt.get(gesture);
		if (last == null)
		{
			return true;
		}
		int wait = gesture.cooldownTicks;
		if (grateful.getAsBoolean())
		{
			wait /= 2;
		}
		return tick.getAsInt() - last >= wait;
	}

	/** Marks the gesture as made now. */
	public void spent(Gesture gesture)
	{
		lastAt.put(gesture, tick.getAsInt());
	}

	/** Takes the gesture if it may be made: true means go ahead, it is spent. */
	public boolean take(Gesture gesture)
	{
		if (!may(gesture))
		{
			return false;
		}
		spent(gesture);
		return true;
	}

	/** Logout, hop: the ledger belongs to the session. */
	public void reset()
	{
		lastAt.clear();
	}
}
