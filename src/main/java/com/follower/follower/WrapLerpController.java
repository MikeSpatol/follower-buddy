package com.follower.follower;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;

/**
 * An AnimationController that interpolates ACROSS the loop boundary.
 *
 * <p>The client's own smoothing glides every frame toward the next one - except
 * the last. RSSequenceDefinition sets {@code nextFrame = -1} when
 * {@code frame + 1} runs off the end ("the last frame is not interpolated"), so
 * the final frame of every looping animation is held statically for its full
 * duration and then snaps to the loop target. That hold is the skip. It exists
 * for real players too, but Jagex authors loops so the last pose sits close to
 * the first, which keeps it invisible on actors; the follower's poses showed it
 * plainly.
 *
 * <p>The fix: let the client do what it already does correctly - every frame
 * except the last - and take over only the final segment. Both endpoints of the
 * missing transition are obtainable as exact static poses
 * ({@code applyTransformations} with an unpacked frame index), so the wrap
 * becomes a vertex lerp between the true last pose and the true loop-target
 * pose, advanced by the same elapsed-tick clock the client uses. Adjacent
 * animation frames are small deltas, where a linear vertex blend and the
 * client's transform-level blend are visually indistinguishable.
 *
 * <p>Only active while animation smoothing accepts this animation id; with
 * smoothing off, nothing interpolates anywhere and the wrap is no different
 * from any other frame boundary.
 */
@Slf4j
public class WrapLerpController extends AnimationController
{
	private final Client client;

	/** Ticks elapsed within the current frame, approximated by observation. */
	private int elapsedInFrame;
	private int lastSeenFrame = -1;

	/**
	 * Endpoint poses snapshotted as flat xyz arrays. applyTransformations returns a
	 * model backed by the client's shared animation scratch buffer - the same arrays
	 * every call, overwritten - so holding Model references meant all "endpoints"
	 * aliased one buffer. Copying the floats out immediately sidesteps that.
	 */
	private float[] lastXyz;
	private float[] targetXyz;

	private int cachedAnimationId = -1;

	/**
	 * Set when the render temp is not safe to write; the lerp disables rather than
	 * corrupt models.
	 */
	private boolean unsafe;

	public WrapLerpController(Client client, Animation animation)
	{
		super(client, animation);
		this.client = client;
	}

	@Override
	public void tick(int ticks)
	{
		int before = getFrame();
		super.tick(ticks);

		// Track progress within the frame by observation rather than mirroring the
		// stepping algorithm: external setFrame calls and onFinished handlers can
		// move the frame in ways a mirror would drift from. The reset loses the
		// consumed remainder (a tick or two of 20+), invisible in a lerp fraction.
		if (getFrame() != before)
		{
			elapsedInFrame = 0;
			lastSeenFrame = getFrame();
		}
		else
		{
			elapsedInFrame += ticks;
		}
	}

	@Override
	public Model animate(Model model, AnimationController other)
	{
		Animation animation = getAnimation();
		if (animation == null || other != null || unsafe
			|| animation.isMayaAnim() || !smoothingAccepts(animation))
		{
			return super.animate(model, other);
		}

		int[] lengths = animation.getFrameLengths();
		int frame = getFrame();
		if (lengths == null || lengths.length < 2 || frame != lengths.length - 1)
		{
			return super.animate(model, other);
		}

		ensureEndpoints(model, animation, lengths.length);
		if (lastXyz == null || targetXyz == null)
		{
			return super.animate(model, other);
		}

		// The blend is written into the model the base controller returns: the
		// client's shared per-frame temp, which the client itself hands straight to
		// the renderer for every animated frame. Never into the base, though - a
		// frame with no transform can come back as the base itself, or sharing its
		// vertex arrays, and writing there would bend the follower's model for good.
		Model temp = super.animate(model, other);
		if (temp == null)
		{
			return null;
		}

		boolean separate = temp != model
			&& temp.getVerticesX() != model.getVerticesX()
			&& temp.getVerticesY() != model.getVerticesY()
			&& temp.getVerticesZ() != model.getVerticesZ();
		boolean sized = temp.getVerticesCount() * 3 == lastXyz.length
			&& lastXyz.length == targetXyz.length;
		if (!separate || !sized)
		{
			log.warn("Render temp is not safe to write (separate={}, sized={}); "
				+ "wrap interpolation disabled for this session", separate, sized);
			unsafe = true;
			lastXyz = null;
			targetXyz = null;
			return temp;
		}

		float t = Math.min(1f, elapsedInFrame / (float) Math.max(1, lengths[frame]));
		lerpInto(temp, lastXyz, targetXyz, t);
		return temp;
	}

	/** True when the safety guard has switched the lerp off for this session. */
	public boolean isDisabled()
	{
		return unsafe;
	}

	private boolean smoothingAccepts(Animation animation)
	{
		java.util.function.IntPredicate filter = client.getAnimationInterpolationFilter();
		return filter != null && filter.test(animation.getId());
	}

	/**
	 * The frame the default loop lands on, replicating AnimationController.loop():
	 * step back frameStep from the end, falling back to 0 when out of range.
	 */
	private int loopTargetFrame(Animation animation, int frames)
	{
		int target = frames - animation.getFrameStep();
		return target < 0 || target >= animation.getDuration() ? 0 : target;
	}

	private void ensureEndpoints(Model base, Animation animation, int frames)
	{
		if (cachedAnimationId == animation.getId())
		{
			return;
		}
		cachedAnimationId = animation.getId();
		lastXyz = null;
		targetXyz = null;

		// Snapshot each endpoint IMMEDIATELY - the next applyTransformations call
		// (ours or any animating actor's) overwrites the shared scratch buffer.
		lastXyz = snapshot(client.applyTransformations(base, animation, frames - 1, null, 0));
		targetXyz = snapshot(client.applyTransformations(base, animation,
			loopTargetFrame(animation, frames), null, 0));
	}

	/** Flattens a posed model's vertices into an owned array, or null. */
	private static float[] snapshot(Model posed)
	{
		if (posed == null || posed.getVerticesX() == null)
		{
			return null;
		}

		int count = posed.getVerticesCount();
		float[] outXyz = new float[count * 3];
		float[] x = posed.getVerticesX();
		float[] y = posed.getVerticesY();
		float[] z = posed.getVerticesZ();
		for (int i = 0; i < count; i++)
		{
			outXyz[i * 3] = x[i];
			outXyz[i * 3 + 1] = y[i];
			outXyz[i * 3 + 2] = z[i];
		}
		return outXyz;
	}

	private static void lerpInto(Model dest, float[] from, float[] to, float t)
	{
		int count = dest.getVerticesCount();
		float[] dx = dest.getVerticesX();
		float[] dy = dest.getVerticesY();
		float[] dz = dest.getVerticesZ();

		for (int i = 0; i < count; i++)
		{
			float ax = from[i * 3];
			float ay = from[i * 3 + 1];
			float az = from[i * 3 + 2];
			dx[i] = ax + (to[i * 3] - ax) * t;
			dy[i] = ay + (to[i * 3 + 1] - ay) * t;
			dz[i] = az + (to[i * 3 + 2] - az) * t;
		}
	}
}
