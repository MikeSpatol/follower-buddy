// public:file chathead
package com.follower.ui;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * {@link GouraudRasterizer} against the client's three-case port it replaced
 * (kept in {@code ui.before}), pixel for pixel.
 *
 * <p>The new walker rotates the vertices into the client's first case, so
 * the risk is in the corners the rotation could treat differently: exact ties
 * of rows and slopes, rows that only tie once clipped, and triangles hanging
 * off any edge. The small range covers every one of those exhaustively: every
 * triangle with every coordinate in -3..11 on an 8x8 canvas. Random triangles
 * then cover every size, huge coordinates, forced ties, colours over the
 * whole int range and canvases of several shapes. The buffers carry a guard
 * band, so a write past the canvas would have to land in the same place too.
 */
public class RasterDiffTest
{
	/** Random triangles per canvas shape; {@code -Drasterdiff.random=N} for a longer run. */
	private static final int RANDOM = Integer.getInteger("rasterdiff.random", 100_000);

	private static final int GUARD = 512;

	private long checked;

	private void check(int width, int height, int[] t, int[] oldPixels, int[] newPixels,
		com.follower.ui.before.GouraudRasterizer before, GouraudRasterizer after)
	{
		Arrays.fill(oldPixels, 0);
		Arrays.fill(newPixels, 0);
		String oldThrew = null;
		String newThrew = null;
		try
		{
			before.gouraudTriangle(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]);
		}
		catch (RuntimeException e)
		{
			oldThrew = e.toString();
		}
		try
		{
			after.gouraudTriangle(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]);
		}
		catch (RuntimeException e)
		{
			newThrew = e.toString();
		}
		String what = width + "x" + height + " " + Arrays.toString(t);
		assertEquals("exception for " + what, oldThrew, newThrew);
		assertArrayEquals("pixels for " + what, oldPixels, newPixels);
		checked++;
	}

	@Test
	public void everySmallTriangleFillsTheSamePixels()
	{
		int width = 8;
		int height = 8;
		int[] oldPixels = new int[width * height + GUARD];
		int[] newPixels = new int[width * height + GUARD];
		com.follower.ui.before.GouraudRasterizer before = new com.follower.ui.before.GouraudRasterizer(oldPixels, width, height);
		GouraudRasterizer after = new GouraudRasterizer(newPixels, width, height);
		int[] colours = {8000, 9000, 10000, 0, 0xFFFF, 123, 40000, 7, 65000};
		int[] t = new int[9];
		for (int xa = -3; xa <= 11; xa++)
		{
			for (int xb = -3; xb <= 11; xb++)
			{
				for (int xc = -3; xc <= 11; xc++)
				{
					for (int ya = -3; ya <= 11; ya++)
					{
						for (int yb = -3; yb <= 11; yb++)
						{
							for (int yc = -3; yc <= 11; yc++)
							{
								int k = (xa * 7 + xb * 3 + xc + ya * 5 + yb * 11 + yc) & 3;
								t[0] = xa;
								t[1] = xb;
								t[2] = xc;
								t[3] = ya;
								t[4] = yb;
								t[5] = yc;
								t[6] = colours[k];
								t[7] = colours[k + 1];
								t[8] = colours[k + 2];
								check(width, height, t, oldPixels, newPixels, before, after);
							}
						}
					}
				}
			}
		}
		assertEquals(15L * 15 * 15 * 15 * 15 * 15, checked);
	}

	@Test
	public void randomTrianglesFillTheSamePixels()
	{
		Random random = new Random(42);
		int[][] sizes = {{64, 64}, {1, 1}, {64, 1}, {1, 64}, {150, 120}, {7, 300}, {32, 48}};
		int[] t = new int[9];
		for (int[] size : sizes)
		{
			int[] oldPixels = new int[size[0] * size[1] + GUARD];
			int[] newPixels = new int[size[0] * size[1] + GUARD];
			com.follower.ui.before.GouraudRasterizer before = new com.follower.ui.before.GouraudRasterizer(oldPixels, size[0], size[1]);
			GouraudRasterizer after = new GouraudRasterizer(newPixels, size[0], size[1]);
			for (int i = 0; i < RANDOM; i++)
			{
				if (i % 2 == 0)
				{
					// Every size, from a few pixels to far off every edge.
					int range = i % 8 == 0 ? 4000 : i % 8 == 2 ? 400 : i % 8 == 4 ? 40 : 1 << 20;
					for (int j = 0; j < 6; j++)
					{
						t[j] = random.nextInt(range) - range / 2;
					}
				}
				else
				{
					// Coordinates of 4 to 25 bits, the y's sometimes pushed further up.
					int bits = 4 + random.nextInt(22);
					for (int j = 0; j < 6; j++)
					{
						t[j] = random.nextInt(1 << bits) - (1 << (bits - 1))
							+ (j >= 3 && random.nextInt(3) == 0 ? -random.nextInt(1 << bits) : 0);
					}
				}
				// Rows that tie, before or after clipping.
				int tie = random.nextInt(8);
				if (tie == 0)
				{
					t[4] = t[3];
				}
				else if (tie == 1)
				{
					t[5] = t[4];
				}
				else if (tie == 2)
				{
					t[5] = t[3];
				}
				else if (tie == 3)
				{
					t[4] = t[3];
					t[5] = t[3];
				}
				for (int j = 6; j < 9; j++)
				{
					t[j] = random.nextInt(4) == 0 ? random.nextInt() : random.nextInt(0x10000);
				}
				check(size[0], size[1], t, oldPixels, newPixels, before, after);
			}
		}

		// The unit test's extreme cases.
		int[][] extremes = {
			{Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, 0, -10, 10, 0},
			{0, 0, 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, 0},
			{-1000, 1000, 0, -1000, 1000, 0},
		};
		int[] oldPixels = new int[64 * 64 + GUARD];
		int[] newPixels = new int[64 * 64 + GUARD];
		for (int[] extreme : extremes)
		{
			System.arraycopy(extreme, 0, t, 0, 6);
			t[6] = Integer.MAX_VALUE / 2;
			t[7] = 0;
			t[8] = Integer.MIN_VALUE / 2;
			check(64, 64, t, oldPixels, newPixels,
				new com.follower.ui.before.GouraudRasterizer(oldPixels, 64, 64), new GouraudRasterizer(newPixels, 64, 64));
		}
		assertEquals(sizes.length * (long) RANDOM + extremes.length, checked);
	}
}
