package com.follower;

import lombok.*;

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
@RequiredArgsConstructor
class PropFlourish
{
	final FollowerEntity follower;
	final ErrandController.Hands hands;

	int holdTicks;
	@Getter
	boolean active;
	/** The pose it put on, the only one it takes off. */
	int poseId;

	/**
	 * Starts the flourish, unless one is already playing.
	 *
	 * @param leftItemId a thing for the other hand too - the quill of the
	 *                   writing pose - or 0 for the one prop alone
	 * @return whether it started - a false means the line still says its
	 * words, just without the gesture, which is the right degradation
	 */
	boolean start(int leftItemId, int itemId, int poseId, int ticks)
	{
		// Hands already full - a borrowed tool, most likely - and the scroll
		// would take the same slot: the gesture is skipped, the line still lands.
		if (active || itemId <= 0 || poseId <= 0 || hands.isHolding())
			return false;
		active = true;
		holdTicks = Math.max(1, ticks);
		this.poseId = poseId;
		follower.stayHere();
		// Prop and pose on the same tick, and so the same rendered frame: the
		// appearance service's dump path is synchronous, so the model with the
		// prop in it is applied before hold() returns. A settle delay here
		// only ever produced the wrong order - idle hands holding a scroll.
		if (leftItemId > 0)
			hands.hold(leftItemId, itemId);
		else
			hands.hold(itemId);
		follower.setPoseOverride(poseId);
		return true;
	}

	/** Call once per game tick, on the client thread. */
	void tick()
	{
		if (!active)
			return;
		if (--holdTicks <= 0)
			putEverythingBack();
	}

	/**
	 * The interruption path: a scene change or the plugin shutting down. Same
	 * cleanup as finishing - releasing the follower to walk is harmless even
	 * when the interrupter is about to despawn it, and a path that leaves it
	 * planted is a stuck follower waiting to happen.
	 */
	void abort()
	{
		if (!active)
			return;
		putEverythingBack();
	}

	void putEverythingBack()
	{
		active = false;
		holdTicks = 0;
		// Only its own: a rest's sit or a held emote's copy put on since
		// stays, or the follower stands while it is still meant to be at it.
		follower.clearPoseOverride(poseId);
		hands.release();
		follower.resumeFollowing();
	}
}
