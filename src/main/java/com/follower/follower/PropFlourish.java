package com.follower.follower;

import lombok.extern.slf4j.Slf4j;

/**
 * A rule-sized version of the documenting errand: the follower stops, holds a
 * prop, plays a pose over it for a few seconds, and puts everything back.
 *
 * <p>The errand is the scheduler's moment; this is the RULE's. A line like
 * "That's going in the book" claims an act the player never sees, and the
 * whole point of the prop work is that the claim and the act can now happen
 * together. Which rules get one, with what prop and pose and for how long, is
 * authored in phrases.json - the engine stays ignorant of scrolls.
 *
 * <p>Same shape as the errand's scroll handling, and deliberately so: prop
 * and pose together on one tick, and the cleanup path is idempotent because
 * it runs from finish, abort and shutdown alike. One flourish at a time; a
 * second request while one is playing is dropped rather than queued, because
 * a follower that files a backlog of gestures stops reading as spontaneous.
 */
@Slf4j
public class PropFlourish
{
	private final FollowerEntity follower;
	private final ErrandController.Hands hands;

	private int holdTicks;
	private boolean active;

	public PropFlourish(FollowerEntity follower, ErrandController.Hands hands)
	{
		this.follower = follower;
		this.hands = hands;
	}

	public boolean isActive()
	{
		return active;
	}

	/**
	 * Starts the flourish, unless one is already playing.
	 *
	 * @return whether it started - a false means the line still says its
	 * words, just without the gesture, which is the right degradation
	 */
	public boolean start(int itemId, int poseId, int ticks)
	{
		if (active || itemId <= 0 || poseId <= 0)
		{
			return false;
		}
		active = true;
		holdTicks = Math.max(1, ticks);
		follower.stayHere();
		// Prop and pose on the same tick, and so the same rendered frame: the
		// appearance service's dump path is synchronous, so the model with the
		// prop in it is applied before hold() returns. A settle delay here
		// only ever produced the wrong order - idle hands holding a scroll.
		hands.hold(itemId);
		follower.setPoseOverride(poseId);
		log.debug("Flourish started: item {} pose {} for {} ticks", itemId, poseId, ticks);
		return true;
	}

	/** Call once per game tick, on the client thread. */
	public void tick()
	{
		if (!active)
		{
			return;
		}
		if (--holdTicks <= 0)
		{
			finish();
		}
	}

	private void finish()
	{
		putEverythingBack();
	}

	/**
	 * The interruption path: a scene change or the plugin shutting down. Same
	 * cleanup as finishing - releasing the follower to walk is harmless even
	 * when the interrupter is about to despawn it, and a path that leaves it
	 * planted is a stuck follower waiting to happen.
	 */
	public void abort()
	{
		if (!active)
		{
			return;
		}
		putEverythingBack();
	}

	private void putEverythingBack()
	{
		active = false;
		holdTicks = 0;
		follower.setPoseOverride(0);
		hands.release();
		follower.resumeFollowing();
	}
}
