package com.follower;

import net.runelite.api.*;
import net.runelite.api.Model;

/**
 * A one-shot that starts and ends somewhere other than standing, eased in
 * from the standing pose and back out to it.
 *
 * <p>The client never blends one animation into the next: a new clip shows
 * its first frame at once. That is invisible for a real emote, which Jagex
 * authors to start and end on the idle, and plain for the HUMAN_*_IDLE mood
 * clips, which are standing stances in their own right and sit up to 104
 * units from the idle at both ends. Played as an emote, the body jumped into
 * the mood and jumped back out (QA, 15 Oct).
 *
 * <p>So for the first and last {@link #EASE_CYCLES} client cycles each vertex
 * is blended between where the idle's first frame puts it and where the clip
 * does, the same vertex lerp {@link WrapLerpController} uses across a loop's
 * seam, written into the client's per-frame temp the same way and under the
 * same guard.
 */
class EaseInOutController extends AnimationController
{
	/** A quarter of a second each way: a few frames of any clip, short of a gesture of its own. */
	static final int EASE_CYCLES = 12;

	final Client client;
	final int standingPose;

	/** Client cycles since the clip began. */
	int elapsed;

	/** The standing pose's vertices, snapshot on the first frame drawn. */
	float[] standingXyz;
	boolean unsafe;

	EaseInOutController(Client client, Animation animation, int standingPose)
	{
		super(client, animation);
		this.client = client;
		this.standingPose = standingPose;
	}

	@Override
	public void tick(int ticks)
	{
		super.tick(ticks);
		elapsed += ticks;
	}

	/** How much of the clip shows, 0 standing to 1 the clip itself. */
	static float weight(int elapsed, int duration)
	{
		int remaining = duration - elapsed;
		return Math.max(0f, Math.min(1f, Math.min(elapsed, remaining) / (float) EASE_CYCLES));
	}

	@Override
	public Model animate(Model model, AnimationController other)
	{
		var animation = getAnimation();
		if (animation == null || other != null || unsafe || animation.isMayaAnim()
			|| animation.getFrameLengths() == null)
			return super.animate(model, other);
		float weight = weight(elapsed, duration(animation));
		if (weight >= 1f)
			return super.animate(model, other);

		// Snapshot BEFORE the clip's own frame: applyTransformations hands back
		// the client's shared scratch buffer, which the next call overwrites.
		if (standingXyz == null)
		{
			var standing = client.loadAnimation(standingPose);
			standingXyz = standing == null ? null
				: WrapLerpController.snapshot(client.applyTransformations(model, standing, 0, null, 0));
			if (standingXyz == null)
			{
				unsafe = true;
				return super.animate(model, other);
			}
		}

		var temp = super.animate(model, other);
		if (temp == null)
			return null;
		if (!WrapLerpController.separate(temp, model) || temp.getVerticesCount() * 3 != standingXyz.length)
		{
			unsafe = true;
			return temp;
		}

		WrapLerpController.lerpInto(temp, standingXyz, null, weight);
		return temp;
	}

	static int duration(Animation animation)
	{
		int total = 0;
		for (int length : animation.getFrameLengths())
			total += length;
		return total;
	}
}
