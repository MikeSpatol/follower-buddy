// public:file chathead
package com.follower.speech;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * The dialogue head is posed and rasterised once per talk frame, not once
 * per frame drawn: a talk frame is held for several overlay frames, and the
 * head was a new 150 px image, 180-300 KB, every one of them while the box
 * was open (perf audit, Oct 2026, #56).
 */
public class ChatheadFrameCacheTest
{
	private int posed;

	@Test
	public void aHeldTalkFrameIsDrawnOnce() throws Exception
	{
		// A clip of two long frames, so the frame does not move while drawn.
		Animation clip = (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (self, method, args) ->
			{
				switch (method.getName())
				{
					case "getFrameLengths":
						return new int[]{10_000, 10_000};
					case "getFrameStep":
						return -1;
					default:
						Class<?> type = method.getReturnType();
						return type == boolean.class ? false : type == int.class ? 0 : null;
				}
			});
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (self, method, args) ->
			{
				switch (method.getName())
				{
					case "loadAnimation":
						return clip;
					case "applyTransformations":
						posed++;
						return null;
					default:
						Class<?> type = method.getReturnType();
						return type == boolean.class ? false : type == int.class ? 0 : null;
				}
			});
		FollowerDialog dialog = new FollowerDialog(client, null, null, null, null, null, null, null);
		set(dialog, "chatheadBase", head());
		Constructor<FollowerDialog.Node> made =
			FollowerDialog.Node.class.getDeclaredConstructor(boolean.class, String[].class);
		made.setAccessible(true);
		set(dialog, "node", made.newInstance(false, new String[]{"Hello."}));
		Method draw = FollowerDialog.class.getDeclaredMethod("animatedHead", int.class);
		draw.setAccessible(true);

		BufferedImage first = (BufferedImage) draw.invoke(dialog, 1);
		assertNotNull(first);
		for (int i = 0; i < 20; i++)
		{
			assertSame("the frame held is the image held", first, draw.invoke(dialog, 1));
		}
		assertEquals("posed once", 1, posed);

		// Anything that moves the head draws it again.
		set(dialog, "headPitch", 200);
		assertNotSame(first, draw.invoke(dialog, 1));
		assertEquals(2, posed);
	}

	private static void set(Object on, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerDialog.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(on, value);
	}

	/** A small head-sized ball of faces. */
	private static Model head()
	{
		int rings = 6;
		int segs = 8;
		int verts = (rings + 1) * segs;
		float[] x = new float[verts];
		float[] y = new float[verts];
		float[] z = new float[verts];
		for (int r = 0; r <= rings; r++)
		{
			double phi = Math.PI * r / rings;
			for (int s = 0; s < segs; s++)
			{
				double th = 2 * Math.PI * s / segs;
				int v = r * segs + s;
				x[v] = (float) (40 * Math.sin(phi) * Math.cos(th));
				y[v] = (float) (-60 - 40 * Math.cos(phi));
				z[v] = (float) (40 * Math.sin(phi) * Math.sin(th));
			}
		}
		int faces = rings * segs * 2;
		int[] a = new int[faces];
		int[] b = new int[faces];
		int[] c = new int[faces];
		int f = 0;
		for (int r = 0; r < rings; r++)
		{
			for (int s = 0; s < segs; s++)
			{
				int p = r * segs + s;
				int q = r * segs + (s + 1) % segs;
				a[f] = p;
				b[f] = p + segs;
				c[f] = q;
				f++;
				a[f] = q;
				b[f] = p + segs;
				c[f] = q + segs;
				f++;
			}
		}
		int[] colours = new int[faces];
		int[] flat = new int[faces];
		Arrays.fill(colours, 0x2233);
		Arrays.fill(flat, -1);
		int count = verts;
		int faceCount = faces;
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
						return count;
					case "getFaceCount":
						return faceCount;
					case "getFaceIndices1":
						return a;
					case "getFaceIndices2":
						return b;
					case "getFaceIndices3":
						return c;
					case "getFaceColors1":
					case "getFaceColors2":
						return colours;
					case "getFaceColors3":
						return flat;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						if (type == boolean.class)
						{
							return false;
						}
						if (type == int.class)
						{
							return 0;
						}
						if (type == short.class)
						{
							return (short) 0;
						}
						if (type == byte.class)
						{
							return (byte) 0;
						}
						return null;
				}
			});
	}
}
