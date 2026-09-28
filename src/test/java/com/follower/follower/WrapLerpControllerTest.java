package com.follower.follower;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Where the loop-wrap lerp writes its blend.
 *
 * <p>The client hands every animated frame to the renderer in one shared
 * per-frame temp, and the base controller returns that temp, so the lerp writes
 * into it the same way. But a frame with no transform can come back as the
 * follower's base model itself, or as a model sharing its vertex arrays, and a
 * write there would bend the base for good. These pin both halves: the blend
 * lands in the temp, and the base is never written. Nor is a temp posed from a
 * model the snapshotted endpoints no longer fit.
 */
public class WrapLerpControllerTest
{
	private static final int FRAMES = 3;
	private static final int FRAME_LENGTH = 4;
	private static final int VERTICES = 5;

	private static final float BASE = 1f;
	private static final float LAST_POSE = 10f;
	private static final float LOOP_POSE = 20f;
	private static final float OTHER_POSE = 30f;

	private Model base;
	/** The client's shared per-frame temp: one set of arrays, reposed every call. */
	private Model temp;
	/** How many vertices the temp holds: as many as the model last posed into it. */
	private int tempCount;
	/** What the render-time call hands back instead of the temp, or null for the temp. */
	private Model renderReturns;

	private Client client;
	private Animation animation;

	@Before
	public void setUp()
	{
		base = model(filled(BASE), filled(BASE), filled(BASE));
		temp = model(new float[VERTICES], new float[VERTICES], new float[VERTICES], () -> tempCount);
		client = fake(Client.class, (name, args) ->
		{
			switch (name)
			{
				case "getAnimationInterpolationFilter":
					return (IntPredicate) id -> true;
				case "applyTransformations":
					return transform((Model) args[0], (int) args[2]);
				default:
					throw new UnsupportedOperationException(name);
			}
		});
		animation = fake(Animation.class, (name, args) ->
		{
			switch (name)
			{
				case "getId":
					return 808;
				case "isMayaAnim":
					return false;
				case "getFrameLengths":
					int[] lengths = new int[FRAMES];
					Arrays.fill(lengths, FRAME_LENGTH);
					return lengths;
				case "getFrameStep":
					return -1;
				case "getDuration":
					return FRAMES;
				default:
					throw new UnsupportedOperationException(name);
			}
		});
	}

	@Test
	public void blendLandsInTheRenderTemp()
	{
		WrapLerpController controller = onFinalFrame();
		Model drawn = controller.animate(base, null);

		assertSame("the lerp returns the client's own temp", temp, drawn);
		float halfway = (LAST_POSE + LOOP_POSE) / 2;
		assertArrayEquals(filled(halfway), drawn.getVerticesX(), 0f);
		assertArrayEquals(filled(halfway), drawn.getVerticesY(), 0f);
		assertArrayEquals(filled(halfway), drawn.getVerticesZ(), 0f);
		assertBaseUntouched();
		assertFalse(controller.isDisabled());
	}

	@Test
	public void neverWritesTheBaseWhenTheTempIsTheBase()
	{
		renderReturns = base;
		WrapLerpController controller = onFinalFrame();

		assertSame(base, controller.animate(base, null));
		assertBaseUntouched();
		assertTrue("the measured wrap trim takes over", controller.isDisabled());
	}

	@Test
	public void neverWritesTheBaseThroughSharedArrays()
	{
		Model alias = model(base.getVerticesX(), base.getVerticesY(), base.getVerticesZ());
		renderReturns = alias;
		WrapLerpController controller = onFinalFrame();

		assertSame(alias, controller.animate(base, null));
		assertBaseUntouched();
		assertTrue("the measured wrap trim takes over", controller.isDisabled());
	}

	@Test
	public void aModelThatNoLongerMatchesTheEndpointsSwitchesTheLerpOff()
	{
		WrapLerpController controller = onFinalFrame();
		controller.animate(base, null);

		// A different model under the same animation: the endpoints were taken from
		// the base, and blending them into this one would scramble it.
		Model smaller = model(new float[2], new float[2], new float[2]);
		Model drawn = controller.animate(smaller, null);

		assertSame(temp, drawn);
		assertEquals("posed by the client, not blended", LAST_POSE, drawn.getVerticesX()[0], 0f);
		assertBaseUntouched();
		assertTrue("the measured wrap trim takes over", controller.isDisabled());
	}

	/** Halfway through the final frame, so the blend differs from both endpoints. */
	private WrapLerpController onFinalFrame()
	{
		WrapLerpController controller = new WrapLerpController(client, animation);
		controller.setFrame(FRAMES - 1);
		controller.tick(FRAME_LENGTH / 2);
		return controller;
	}

	/**
	 * Stands in for Client#applyTransformations. The endpoint snapshots ask with a
	 * plain frame index; the render call arrives packed (high bit set while
	 * smoothing accepts the animation), which is where a test swaps in the base or
	 * an alias of it.
	 */
	private Model transform(Model posed, int frame)
	{
		if (frame < 0 && renderReturns != null)
		{
			return renderReturns;
		}
		int index = frame & 0xFFFF;
		float pose = index == FRAMES - 1 ? LAST_POSE : index == 0 ? LOOP_POSE : OTHER_POSE;
		tempCount = posed.getVerticesCount();
		Arrays.fill(temp.getVerticesX(), 0, tempCount, pose);
		Arrays.fill(temp.getVerticesY(), 0, tempCount, pose);
		Arrays.fill(temp.getVerticesZ(), 0, tempCount, pose);
		return temp;
	}

	private void assertBaseUntouched()
	{
		assertArrayEquals("base x", filled(BASE), base.getVerticesX(), 0f);
		assertArrayEquals("base y", filled(BASE), base.getVerticesY(), 0f);
		assertArrayEquals("base z", filled(BASE), base.getVerticesZ(), 0f);
	}

	private static float[] filled(float value)
	{
		float[] out = new float[VERTICES];
		Arrays.fill(out, value);
		return out;
	}

	private static Model model(float[] x, float[] y, float[] z)
	{
		return model(x, y, z, () -> x.length);
	}

	private static Model model(float[] x, float[] y, float[] z, IntSupplier count)
	{
		return fake(Model.class, (name, args) ->
		{
			switch (name)
			{
				case "getVerticesCount":
					return count.getAsInt();
				case "getVerticesX":
					return x;
				case "getVerticesY":
					return y;
				case "getVerticesZ":
					return z;
				default:
					throw new UnsupportedOperationException(name);
			}
		});
	}

	/** Answers only what the lerp should ask; anything else fails loudly. */
	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					case "toString":
						return type.getSimpleName() + "@fake";
					default:
						return answers.apply(method.getName(), args);
				}
			});
	}
}
