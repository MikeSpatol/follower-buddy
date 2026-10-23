// public:file chathead
package com.follower.ui;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ChatheadRenderer} against the renderer it replaced (kept in
 * {@code ui.before}), end to end: the old renderer drawing through the old
 * rasterizer and colour table, the new one through the new, image for image.
 *
 * <p>The random models carry what real ones do and what they could: render
 * priorities including the special classes 10 and 11 and values out of
 * range, flat-shaded and hidden faces, face indices past the vertex list,
 * vertices behind the camera, missing colour arrays, and posed models framed
 * by their rest pose; drawn at random sizes, angles, zooms, head fractions
 * and anchors.
 */
public class ChatheadDiffTest
{
	/** Random models; {@code -Dchatheaddiff.models=N} for a longer run. */
	private static final int MODELS = Integer.getInteger("chatheaddiff.models", 6000);

	private static Model stub(Map<String, Object> values)
	{
		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
			(proxy, method, args) ->
			{
				Object value = values.get(method.getName());
				return value == null && method.getReturnType() == int.class ? 0 : value;
			});
	}

	private static Map<String, Object> randomModel(Random random, int verts, int faces, boolean priorities)
	{
		float[] x = new float[verts];
		float[] y = new float[verts];
		float[] z = new float[verts];
		for (int i = 0; i < verts; i++)
		{
			x[i] = random.nextInt(120) - 60;
			y[i] = random.nextInt(200) - 190;
			z[i] = random.nextInt(120) - 60;
			if (random.nextInt(50) == 0)
			{
				z[i] = -random.nextInt(2000);
			}
		}
		int[] a = new int[faces];
		int[] b = new int[faces];
		int[] c = new int[faces];
		int[] colours1 = new int[faces];
		int[] colours2 = new int[faces];
		int[] colours3 = new int[faces];
		byte[] priority = new byte[faces];
		for (int i = 0; i < faces; i++)
		{
			a[i] = random.nextInt(verts + (random.nextInt(100) == 0 ? 3 : 0));
			b[i] = random.nextInt(verts);
			c[i] = random.nextInt(verts);
			colours1[i] = random.nextInt(0x10000);
			colours2[i] = random.nextInt(0x10000);
			int kind = random.nextInt(10);
			colours3[i] = kind == 0 ? -1 : kind == 1 ? -2 : random.nextInt(0x10000);
			priority[i] = (byte) (random.nextInt(14) - 1);
		}
		Map<String, Object> values = new HashMap<>();
		values.put("getVerticesX", x);
		values.put("getVerticesY", y);
		values.put("getVerticesZ", z);
		values.put("getVerticesCount", verts);
		values.put("getFaceIndices1", a);
		values.put("getFaceIndices2", b);
		values.put("getFaceIndices3", c);
		values.put("getFaceColors1", colours1);
		values.put("getFaceColors2", random.nextInt(20) == 0 ? null : colours2);
		values.put("getFaceColors3", random.nextInt(20) == 0 ? null : colours3);
		values.put("getFaceRenderPriorities", priorities ? priority : null);
		return values;
	}

	private static int[] pixels(BufferedImage image)
	{
		return image == null ? null
			: image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	@Test
	public void everyModelDrawsTheSameImage()
	{
		Random random = new Random(99);
		int drawn = 0;
		for (int i = 0; i < MODELS; i++)
		{
			int verts = 3 + random.nextInt(400);
			Map<String, Object> values = randomModel(random, verts, 1 + random.nextInt(900), random.nextInt(4) != 0);
			Model model = stub(values);
			Model frame = null;
			if (random.nextInt(3) == 0)
			{
				// A posed model framed by its rest pose: the same faces, moved vertices.
				Map<String, Object> posed = new HashMap<>(values);
				float[] y = ((float[]) values.get("getVerticesY")).clone();
				for (int j = 0; j < y.length; j++)
				{
					y[j] += random.nextInt(9) - 4;
				}
				posed.put("getVerticesY", y);
				frame = model;
				model = stub(posed);
			}
			int width = 20 + random.nextInt(140);
			int height = 20 + random.nextInt(140);
			int yaw = random.nextInt(2048);
			int pitch = random.nextInt(400) - 100;
			int zoom = 200 + random.nextInt(1500);
			double fraction = random.nextInt(4) == 0 ? 1.0 : random.nextDouble();
			int anchorX = random.nextInt(3) == 0 ? -1 : random.nextInt(width);
			int anchorY = random.nextInt(3) == 0 ? -1 : random.nextInt(height);

			int[] before = pixels(com.follower.ui.before.ChatheadRenderer.render(model, frame, width, height,
				yaw, pitch, zoom, fraction, anchorX, anchorY));
			int[] after = pixels(ChatheadRenderer.render(model, frame, width, height,
				yaw, pitch, zoom, fraction, anchorX, anchorY));
			assertArrayEquals("model " + i, before, after);
			drawn += before == null ? 0 : 1;
		}
		assertTrue("most models should draw something: " + drawn, drawn > MODELS * 3 / 4);
	}

	// public:cut dev
	@Test
	public void theCentredFormIsTheSame()
	{
		Random random = new Random(7);
		for (int i = 0; i < 200; i++)
		{
			Model model = stub(randomModel(random, 3 + random.nextInt(200), 1 + random.nextInt(400), random.nextBoolean()));
			int yaw = random.nextInt(2048);
			assertArrayEquals("model " + i,
				pixels(com.follower.ui.before.ChatheadRenderer.render(model, null, 96, 96, yaw, 40, 796, 1.0)),
				pixels(ChatheadRenderer.render(model, null, 96, 96, yaw, 40, 796, 1.0)));
		}
	}

	// public:end dev
	@Test
	public void modelsThatCannotBeDrawnStillDrawNothing()
	{
		Map<String, Object> values = randomModel(new Random(3), 10, 10, true);
		values.put("getFaceColors1", null);
		assertEquals(null, ChatheadRenderer.render(stub(values), null, 50, 50, 0, 40, 796, 1.0, -1, -1));
		assertEquals(null, com.follower.ui.before.ChatheadRenderer.render(stub(values), null, 50, 50, 0, 40, 796, 1.0, -1, -1));
		Map<String, Object> other = randomModel(new Random(4), 11, 10, true);
		Model mismatched = stub(randomModel(new Random(5), 12, 10, true));
		assertEquals(null, ChatheadRenderer.render(stub(other), mismatched, 50, 50, 0, 40, 796, 1.0, -1, -1));
		assertEquals(null, com.follower.ui.before.ChatheadRenderer.render(stub(other), mismatched, 50, 50, 0, 40, 796, 1.0, -1, -1));
	}
}
