package com.follower;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The writing pose (Write with a quill, 13302) is authored to play once, with
 * no restart frame, and the scroll errands hold it as a pose override for many
 * seconds at a time. A pose's controller hands every finished pass to
 * loopSafely, which has to start such a clip again from its first frame: the
 * client's own loop steps back by a frame step of -1, which is past the end,
 * and a controller left there drops its clip and freezes the model mid-stroke.
 */
public class OnceClipHeldAsAPoseTest
{
	private static final int[] FRAME_LENGTHS = {4, 4, 4};

	@Test
	public void aClipWithNoRestartFrameStartsAgainFromItsFirstFrame() throws ReflectiveOperationException
	{
		// The duration as a count of frames, and as a count of cycles: the
		// client's loop lands back on the first frame for the one and leaves
		// the frame out of range for the other, and either way it plays on.
		for (int duration : new int[]{FRAME_LENGTHS.length, 12})
		{
			Animation once = once(duration);
			AnimationController controller = held(once);

			controller.tick(13);
			assertSame("still playing after its first pass", once, controller.getAnimation());
			assertEquals("from its first frame again", 0, controller.getFrame());

			controller.tick(12);
			assertSame("and after its second", once, controller.getAnimation());
			assertEquals(0, controller.getFrame());
		}
	}

	/** A controller as FollowerEntity.applyPose sets one up for a held pose. */
	private static AnimationController held(Animation animation) throws ReflectiveOperationException
	{
		Method loopSafely = FollowerEntity.class.getDeclaredMethod("loopSafely", AnimationController.class);
		loopSafely.setAccessible(true);
		AnimationController controller = new AnimationController(null, animation);
		controller.setOnFinished(finished ->
		{
			try
			{
				loopSafely.invoke(null, finished);
			}
			catch (IllegalAccessException | InvocationTargetException e)
			{
				throw new AssertionError(e);
			}
		});
		return controller;
	}

	private static Animation once(int duration)
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(), new Class<?>[]{Animation.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "isMayaAnim":
						return false;
					case "getFrameLengths":
						return FRAME_LENGTHS.clone();
					case "getFrameStep":
						return -1;
					case "getDuration":
						return duration;
					case "getId":
						return ErrandController.WRITE_SCROLL_POSE;
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});
	}
}
