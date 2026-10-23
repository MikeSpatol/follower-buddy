// public:file chronicle
package com.follower.chronicle;

/**
 * One input in flight at a time, applied on the next game tick.
 *
 * <p>A real interface's buttons go to the server and come back with the
 * tick: press a tab and it changes six hundred milliseconds later, and a
 * second press in the meantime is ignored. The dialog does the same with
 * its continue and its options. This is that, on its own, so the window's
 * tabs, rows and close button can share it and a test can pin it. Scrolling
 * is not gated, because in the game it is not: a scrollbar is the client's.
 */
public final class TickGate
{
	private volatile Runnable pending;

	/** Takes the action for the next tick; false if one is already waiting. */
	public boolean offer(Runnable action)
	{
		if (action == null || pending != null)
		{
			return false;
		}
		pending = action;
		return true;
	}

	public boolean busy()
	{
		return pending != null;
	}

	/** The tick: runs what was waiting, once, and frees the gate. */
	public void tick()
	{
		Runnable action = pending;
		pending = null;
		if (action != null)
		{
			action.run();
		}
	}

	/** Drops whatever was waiting without running it. */
	public void clear()
	{
		pending = null;
	}
}
