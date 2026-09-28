package com.follower.follower;

import java.lang.reflect.Proxy;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The loop-wrap lerp, against a client that poses every animated model into
 * one shared temp the way the real one does.
 *
 * <p>RuneLite 1.13 took away the Model merge the lerp used to own its output
 * with. It writes its blend into the client's per-frame temp now, which is safe
 * only while that temp is not the follower's own base model: a write there would
 * stay in the follower for good.
 */
public class WrapLerpControllerTest
{
	private static final int[] LENGTHS = {4, 4, 4};
	private static final int LAST = LENGTHS.length - 1;

	private static final float[] X = {1, 2, 3};
	private static final float[] Y = {4, 5, 6};
	private static final float[] Z = {7, 8, 9};

	/** What the client hands back for the last frame. */
	private enum LastFrame
	{
		/** The shared temp, posed, as for any frame with a transform. */
		TEMP,
		/** The base itself, as for a frame with no transform. */
		BASE,
		/** A different model that shares the base's vertex arrays. */
		SHARED,
	}

	/**
	 * A client that poses into one temp. Frame f moves every vertex 10 * (f + 1)
	 * along each axis, so the loop target (frame 0) is +10, the last frame is
	 * +30, and halfway between them is +20.
	 */
	private static final class Game
	{
		final LastFrame lastFrame;
		final Model base = model(X.clone(), Y.clone(), Z.clone(), () -> X.length);
		final Model shared = model(base.getVerticesX(), base.getVerticesY(), base.getVerticesZ(), () -> X.length);
		/** Longer than the model, as the client's are: it allocates count + 100. */
		final Model temp = model(new float[X.length + 100], new float[X.length + 100], new float[X.length + 100],
			() -> this.tempCount);
		int tempCount;
		int lastFrameArgument;

		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getAnimationInterpolationFilter":
						return (IntPredicate) id -> true;
					case "applyTransformations":
						return pose((Model) args[0], (Integer) args[2]);
					default:
						return defaultFor(method.getReturnType());
				}
			});

		Game(LastFrame lastFrame)
		{
			this.lastFrame = lastFrame;
		}

		Model pose(Model model, int packed)
		{
			lastFrameArgument = packed;
			int frame = packed < 0 ? packed & 0xFFFF : packed;
			if (frame == LAST && lastFrame == LastFrame.BASE)
			{
				return model;
			}
			if (frame == LAST && lastFrame == LastFrame.SHARED)
			{
				return shared;
			}

			tempCount = model.getVerticesCount();
			float shift = 10 * (frame + 1);
			for (int i = 0; i < tempCount; i++)
			{
				temp.getVerticesX()[i] = model.getVerticesX()[i] + shift;
				temp.getVerticesY()[i] = model.getVerticesY()[i] + shift;
				temp.getVerticesZ()[i] = model.getVerticesZ()[i] + shift;
			}
			return temp;
		}

		static Model model(float[] x, float[] y, float[] z, IntSupplier count)
		{
			return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
				(proxy, method, args) ->
				{
					switch (method.getName())
					{
						case "getVerticesX":
							return x;
						case "getVerticesY":
							return y;
						case "getVerticesZ":
							return z;
						case "getVerticesCount":
							return count.getAsInt();
						case "equals":
							return proxy == args[0];
						case "hashCode":
							return System.identityHashCode(proxy);
						default:
							return defaultFor(method.getReturnType());
					}
				});
		}
	}

	private static Animation animation()
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(), new Class<?>[]{Animation.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return 808;
					case "getFrameLengths":
						return LENGTHS;
					case "getDuration":
					case "getFrameStep":
						return LENGTHS.length;
					default:
						return defaultFor(method.getReturnType());
				}
			});
	}

	private static Object defaultFor(Class<?> type)
	{
		return type == int.class ? 0 : type == boolean.class ? false : null;
	}

	/** Parked half way through the last frame, where only the lerp draws. */
	private static WrapLerpController halfwayThroughTheLastFrame(Game game)
	{
		WrapLerpController lerp = new WrapLerpController(game.client, animation());
		lerp.setFrame(LAST);
		lerp.tick(LENGTHS[LAST] / 2);
		return lerp;
	}

	private static void assertUntouched(Model base)
	{
		for (int i = 0; i < X.length; i++)
		{
			assertEquals("base x " + i, X[i], base.getVerticesX()[i], 0f);
			assertEquals("base y " + i, Y[i], base.getVerticesY()[i], 0f);
			assertEquals("base z " + i, Z[i], base.getVerticesZ()[i], 0f);
		}
	}

	@Test
	public void theWrapIsBlendedIntoTheFrameTemp()
	{
		Game game = new Game(LastFrame.TEMP);
		WrapLerpController lerp = halfwayThroughTheLastFrame(game);

		Model drawn = lerp.animate(game.base, null);

		assertSame("the client's own temp is what gets drawn", game.temp, drawn);
		for (int i = 0; i < X.length; i++)
		{
			assertEquals("x " + i, X[i] + 20, drawn.getVerticesX()[i], 0.001f);
			assertEquals("y " + i, Y[i] + 20, drawn.getVerticesY()[i], 0.001f);
			assertEquals("z " + i, Z[i] + 20, drawn.getVerticesZ()[i], 0.001f);
		}
		assertUntouched(game.base);
		assertFalse(lerp.isDisabled());
	}

	@Test
	public void aFrameWhoseTempIsTheBaseFallsBack()
	{
		Game game = new Game(LastFrame.BASE);
		WrapLerpController lerp = halfwayThroughTheLastFrame(game);

		Model drawn = lerp.animate(game.base, null);

		assertUntouched(game.base);
		assertSame("whatever the client's own controller would draw", game.base, drawn);
		assertTrue("asked the way the client's own controller asks, packed",
			game.lastFrameArgument < 0);
		assertFalse("only this frame falls back", lerp.isDisabled());
	}

	@Test
	public void aTempSharingTheBaseVerticesIsNeverWritten()
	{
		Game game = new Game(LastFrame.SHARED);
		WrapLerpController lerp = halfwayThroughTheLastFrame(game);

		Model drawn = lerp.animate(game.base, null);

		assertSame(game.shared, drawn);
		assertUntouched(game.base);
		assertFalse(lerp.isDisabled());
	}

	@Test
	public void aModelThatNoLongerMatchesTheEndpointsSwitchesTheLerpOff()
	{
		Game game = new Game(LastFrame.TEMP);
		WrapLerpController lerp = halfwayThroughTheLastFrame(game);
		lerp.animate(game.base, null);

		// A different base under the same animation: the endpoints were taken from
		// the first one, and blending them into this one would scramble it.
		Model smaller = Game.model(new float[]{1, 2}, new float[]{4, 5}, new float[]{7, 8}, () -> 2);
		Model drawn = lerp.animate(smaller, null);

		assertTrue("the trim takes back over", lerp.isDisabled());
		assertSame(game.temp, drawn);
		assertEquals("posed by the client, not blended", 1 + 30, drawn.getVerticesX()[0], 0f);
		assertUntouched(game.base);
	}
}
