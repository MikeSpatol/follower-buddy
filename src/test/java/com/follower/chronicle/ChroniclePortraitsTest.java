package com.follower.chronicle;

import com.follower.ui.ChatheadRenderer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A portrait fills its plate and sits in the middle of it, whatever the
 * size of the thing drawn: a goblin and a dragon differ in size by a factor
 * of ten, and both must come out as a picture of themselves rather than a
 * speck or a close-up of a knee.
 */
public class ChroniclePortraitsTest
{
	private static final int W = ChronicleLayout.PLATE_W - 4;
	private static final int H = ChronicleLayout.PLATE_H - 4;

	/**
	 * A box standing on the ground, the model's origin at its feet as the
	 * game's are, faces wound both ways so some always face the camera.
	 */
	static Model box(float halfWidth, float height)
	{
		float[] x = {-halfWidth, halfWidth, halfWidth, -halfWidth, -halfWidth, halfWidth, halfWidth, -halfWidth};
		float[] y = {0, 0, 0, 0, -height, -height, -height, -height};
		float[] z = {-halfWidth, -halfWidth, halfWidth, halfWidth, -halfWidth, -halfWidth, halfWidth, halfWidth};
		int[][] quads = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
		int faces = quads.length * 4;
		int[] a = new int[faces];
		int[] b = new int[faces];
		int[] c = new int[faces];
		int f = 0;
		for (int[] q : quads)
		{
			int[][] tris = {{q[0], q[1], q[2]}, {q[0], q[2], q[3]}, {q[2], q[1], q[0]}, {q[3], q[2], q[0]}};
			for (int[] t : tris)
			{
				a[f] = t[0];
				b[f] = t[1];
				c[f] = t[2];
				f++;
			}
		}
		int[] colours = new int[faces];
		int[] flat = new int[faces];
		java.util.Arrays.fill(colours, 0x2233);
		java.util.Arrays.fill(flat, -1);
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
						return x.length;
					case "getFaceCount":
						return faces;
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
						return null;
				}
			});
	}

	/** minX, minY, maxX, maxY of what was drawn, or null. */
	private static int[] drawn(BufferedImage image)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
	}

	private static void fillsThePlate(Model model, String what)
	{
		fillsThePlate(model, ChroniclePortraits.fit(model, W, H), W, H, what);
	}

	private static void fillsThePlate(Model model, int[] frame, int W, int H, String what)
	{
		assertNotNull(what + " could not be framed", frame);
		for (int yaw = 0; yaw < 2048; yaw += 128)
		{
			BufferedImage image = ChatheadRenderer.render(model, null, W, H, yaw, ChroniclePortraits.PITCH,
				frame[0], 1.0, frame[1], frame[2]);
			assertNotNull(what + " drew nothing at yaw " + yaw, image);
			int[] box = drawn(image);
			assertNotNull(what + " drew nothing at yaw " + yaw, box);
			assertTrue(what + " is cut off at yaw " + yaw + ": " + java.util.Arrays.toString(box),
				box[0] > 0 && box[1] > 0 && box[2] < W - 1 && box[3] < H - 1);
			int tall = box[3] - box[1];
			assertTrue(what + " is a speck: " + tall + " of " + H, tall > H * 0.55);
			int middle = (box[1] + box[3]) / 2;
			assertTrue(what + " sits off the middle: " + middle + " of " + H, Math.abs(middle - H / 2) <= H / 6);
		}
	}

	@Test
	public void aSmallThingFillsThePlate()
	{
		fillsThePlate(box(20, 90), "a goblin-sized box");
	}

	@Test
	public void aHugeThingFillsThePlateTheSameWay()
	{
		fillsThePlate(box(400, 700), "a dragon-sized box");
	}

	@Test
	public void aWideThingFitsAcrossAtEveryAngle()
	{
		fillsThePlate(box(300, 120), "a mole-sized box");
	}

	@Test
	public void measuringWithoutDrawingFindsTheEdgesTheDrawingHas()
	{
		// The framing projects instead of drawing; it must agree with what
		// is drawn, to the pixel, or a portrait is cut off or left small.
		int w = 900;
		int h = 700;
		for (Model model : new Model[]{box(20, 90), box(300, 120), box(400, 700)})
		{
			int zoom = model.getVerticesY()[4] < -500 ? 4000 : 1500;
			for (int yaw = 0; yaw < 2048; yaw += 128)
			{
				BufferedImage image = ChatheadRenderer.render(model, null, w, h, yaw, ChroniclePortraits.PITCH,
					zoom, 1.0, w / 2, h / 2 + 150);
				int[] drawn = drawn(image);
				int[] measured = ChatheadRenderer.drawnBounds(model, yaw, ChroniclePortraits.PITCH, zoom, w / 2, h / 2 + 150);
				assertNotNull(measured);
				String at = "yaw " + yaw + ": drawn " + java.util.Arrays.toString(drawn)
					+ " measured " + java.util.Arrays.toString(measured);
				assertTrue(at, drawn[0] > 0 && drawn[1] > 0 && drawn[2] < w - 1 && drawn[3] < h - 1);
				assertTrue(at, Math.abs(drawn[0] - measured[0]) <= 1);
				assertTrue(at, Math.abs(drawn[1] - measured[1]) <= 1);
				assertTrue(at, Math.abs(drawn[2] + 1 - measured[2]) <= 1);
				assertTrue(at, Math.abs(drawn[3] + 1 - measured[3]) <= 1);
			}
		}
	}

	@Test
	public void theEnlargedPlateIsFramedForItsOwnSize()
	{
		int w = ChronicleLayout.BIG_PLATE_W - 4;
		int h = ChronicleLayout.BIG_PLATE_H - 4;
		Model model = box(400, 700);
		fillsThePlate(model, ChroniclePortraits.fit(model, w, h), w, h, "a dragon-sized box, enlarged");
	}

	@Test
	public void theFramingHoldsEveryPoseOfTheIdle()
	{
		// At rest a goblin-sized box; partway through its idle it stretches a
		// third taller, as an arm goes up or wings open. Framed at rest only,
		// the stretch runs off the plate; framed over the poses, it does not.
		Model rest = box(20, 90);
		Model stretched = box(20, 120);
		int[] restOnly = ChroniclePortraits.fit(rest, W, H);
		BufferedImage cut = ChatheadRenderer.render(stretched, null, W, H, 0, ChroniclePortraits.PITCH,
			restOnly[0], 1.0, restOnly[1], restOnly[2]);
		int[] box = drawn(cut);
		assertTrue("framed at rest, the stretch leaves the plate", box[1] <= 0 || box[3] >= H - 1);

		int[] both = ChroniclePortraits.fit(rest, java.util.Arrays.asList(() -> rest, () -> stretched), W, H);
		fillsThePlate(stretched, both, W, H, "the stretched pose");
	}

	@Test
	public void anIdleLoopsOnTheWallClockInClientCycles()
	{
		// Frames of 2, 3 and 5 cycles of 20ms: a 200ms loop.
		int[] lengths = {2, 3, 5};
		assertEquals(0, ChroniclePortraits.frameAt(false, 0, lengths, 0));
		assertEquals(0, ChroniclePortraits.frameAt(false, 0, lengths, 39));
		assertEquals(1, ChroniclePortraits.frameAt(false, 0, lengths, 40));
		assertEquals(1, ChroniclePortraits.frameAt(false, 0, lengths, 99));
		assertEquals(2, ChroniclePortraits.frameAt(false, 0, lengths, 100));
		assertEquals(2, ChroniclePortraits.frameAt(false, 0, lengths, 199));
		assertEquals("and round again", 0, ChroniclePortraits.frameAt(false, 0, lengths, 200));
		assertEquals("a frame of no length still shows", 1, ChroniclePortraits.frameAt(false, 0, new int[]{1, 0, 1}, 20));
		assertEquals("nothing to play is the first frame", 0, ChroniclePortraits.frameAt(false, 0, null, 12345));
	}

	@Test
	public void aNewerAnimationIsAddressedByTheCycle()
	{
		assertEquals(29, ChroniclePortraits.frameAt(true, 30, null, 29 * 20));
		assertEquals(0, ChroniclePortraits.frameAt(true, 30, null, 30 * 20));
		assertEquals(0, ChroniclePortraits.frameAt(true, 0, null, 500));
	}

	@Test
	public void theFramingSamplesPosesSpreadThroughTheIdle()
	{
		// An older idle has few frames, and every one is measured; a newer
		// one is counted in cycles, and a spread of them is.
		assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]", java.util.Arrays.toString(
			ChroniclePortraits.sampledFrames(false, 0, new int[12])));
		assertEquals("[0, 6, 12, 18, 25, 31, 37, 43, 50, 56, 62, 68, 75, 81, 87, 93]",
			java.util.Arrays.toString(ChroniclePortraits.sampledFrames(true, 100, null)));
		assertEquals("[0, 1]", java.util.Arrays.toString(ChroniclePortraits.sampledFrames(false, 0, new int[2])));
		assertEquals("[0]", java.util.Arrays.toString(ChroniclePortraits.sampledFrames(false, 0, null)));
	}

	@Test
	public void theTurnIsSlowAndComesRound()
	{
		assertEquals(0, ChroniclePortraits.yawAt(0));
		assertEquals(1024, ChroniclePortraits.yawAt(ChroniclePortraits.TURN_MS / 2));
		assertEquals("a full turn and back to the start", 0, ChroniclePortraits.yawAt(ChroniclePortraits.TURN_MS));
		assertTrue("slow enough to study", ChroniclePortraits.TURN_MS >= 8000);
	}

	@Test
	public void somethingNotYetSeenIsItsOutlineInInk()
	{
		Model model = box(20, 90);
		int[] frame = ChroniclePortraits.fit(model, W, H);
		BufferedImage colour = ChatheadRenderer.render(model, null, W, H, 300, ChroniclePortraits.PITCH,
			frame[0], 1.0, frame[1], frame[2]);
		BufferedImage ink = ChroniclePortraits.silhouette(colour);
		int[] shape = drawn(colour);
		assertEquals("the same shape", java.util.Arrays.toString(shape), java.util.Arrays.toString(drawn(ink)));
		for (int y = 0; y < H; y++)
		{
			for (int x = 0; x < W; x++)
			{
				int argb = ink.getRGB(x, y);
				if ((argb >>> 24) != 0)
				{
					assertEquals("every drawn pixel is the ink", ChroniclePortraits.SILHOUETTE, argb & 0xFFFFFF);
				}
			}
		}
	}
}
