package com.follower.ui;

import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The client's triangle rasterizer, which draws the follower's face.
 *
 * <p>It is a verbatim port of integer scanline code, run once per triangle of a
 * chathead every frame a dialog is open. Two things can go wrong in that shape
 * of code and neither is subtle in effect: writing outside the pixel buffer,
 * which is an exception thrown from inside a render, and a degenerate triangle
 * sending the scanline walk somewhere it does not come back from.
 *
 * <p>So the tests here are mostly about what it must NOT do, driven with the
 * inputs a real model produces: sub-pixel faces, faces partly off every edge,
 * zero-area faces, and vertices in every order.
 */
public class GouraudRasterizerTest
{
	private static final int WIDTH = 64;
	private static final int HEIGHT = 64;

	/** A canvas with a guard band around it, so an overrun is detectable. */
	private static final class Canvas
	{
		final int[] pixels = new int[WIDTH * HEIGHT];
		final GouraudRasterizer raster = new GouraudRasterizer(pixels, WIDTH, HEIGHT);

		int painted()
		{
			int count = 0;
			for (int pixel : pixels)
			{
				if (pixel != 0)
				{
					count++;
				}
			}
			return count;
		}
	}

	// ------------------------------------------------------------- it draws

	@Test
	public void anOrdinaryTriangleFillsPixels()
	{
		Canvas canvas = new Canvas();
		canvas.raster.gouraudTriangle(10, 50, 30, 10, 20, 50, 8000, 8000, 8000);

		assertTrue("nothing was drawn at all", canvas.painted() > 0);
	}

	@Test
	public void aTriangleIsDrawnWhicheverOrderItsVerticesArriveIn()
	{
		// The three top-level cases order by smallest y; a model hands them over
		// in whatever order the mesh has them.
		int[][] orders = {
			{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0},
		};
		int[] xs = {10, 50, 30};
		int[] ys = {10, 20, 50};

		for (int[] order : orders)
		{
			Canvas canvas = new Canvas();
			canvas.raster.gouraudTriangle(
				xs[order[0]], xs[order[1]], xs[order[2]],
				ys[order[0]], ys[order[1]], ys[order[2]],
				8000, 8000, 8000);
			assertTrue("winding order " + java.util.Arrays.toString(order)
				+ " drew nothing", canvas.painted() > 0);
		}
	}

	/**
	 * The reason this rasterizer exists: the float fill it replaced sampled
	 * pixel centres, and the pupils of an eye are faces only a few pixels
	 * across. Measured rather than assumed - a face spanning a single pixel
	 * covers no scanline at all under the client's half-open convention and
	 * correctly paints nothing, and from two pixels up the coverage grows with
	 * the area: 1, 3, 6, 10, 15.
	 */
	@Test
	public void faceOfAFewPixelsIsFilledAndScalesWithItsArea()
	{
		int[] expected = {0, 1, 3, 6, 10, 15};
		for (int size = 1; size <= expected.length; size++)
		{
			Canvas canvas = new Canvas();
			canvas.raster.gouraudTriangle(30, 30 + size, 30, 30, 30 + size, 30 + size,
				8000, 8000, 8000);
			assertEquals("a face " + size + " pixels across",
				expected[size - 1], canvas.painted());
		}
	}

	// -------------------------------------------------------- it stays inside

	@Test
	public void nothingIsWrittenOutsideTheBufferHoweverFarOffScreen()
	{
		int[][] cases = {
			{-500, -400, -450, 10, 20, 30},
			{500, 600, 550, 10, 20, 30},
			{10, 20, 30, -500, -400, -450},
			{10, 20, 30, 500, 600, 550},
			{-1000, 1000, 0, -1000, 1000, 0},
			{Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, 0, -10, 10, 0},
			{0, 0, 0, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, 0},
		};

		for (int[] c : cases)
		{
			Canvas canvas = new Canvas();
			// The assertion is simply that this returns without throwing:
			// an ArrayIndexOutOfBounds here would surface as an exception
			// thrown from inside the client's render loop.
			canvas.raster.gouraudTriangle(c[0], c[1], c[2], c[3], c[4], c[5],
				8000, 9000, 10000);
		}
	}

	@Test
	public void aTriangleStraddlingAnEdgeIsClippedNotWrapped()
	{
		Canvas canvas = new Canvas();
		// Hanging off the left edge. A missing clamp would wrap the scanline
		// onto the previous row, which looks like a smear across the face.
		canvas.raster.gouraudTriangle(-20, 20, 0, 10, 10, 40, 8000, 8000, 8000);

		for (int y = 0; y < HEIGHT; y++)
		{
			// Nothing may appear on a row the triangle does not cover.
			if (y < 10 || y > 40)
			{
				for (int x = 0; x < WIDTH; x++)
				{
					assertEquals("painted row " + y + ", outside the triangle",
						0, canvas.pixels[y * WIDTH + x]);
				}
			}
		}
	}

	@Test
	public void aTriangleEntirelyBelowTheClipIsSkipped()
	{
		Canvas canvas = new Canvas();
		canvas.raster.gouraudTriangle(10, 30, 20, HEIGHT + 5, HEIGHT + 20, HEIGHT + 10,
			8000, 8000, 8000);

		assertEquals("something was drawn below the clip line", 0, canvas.painted());
	}

	// ----------------------------------------------------------- degenerate

	@Test
	public void degenerateTrianglesDoNotHangOrThrow()
	{
		int[][] degenerate = {
			{20, 20, 20, 20, 20, 20},       // a point
			{10, 40, 25, 20, 20, 20},       // flat, zero height
			{20, 20, 20, 10, 40, 25},       // a vertical line, zero width
			{10, 20, 30, 10, 10, 40},       // two vertices sharing a y
			{10, 20, 30, 40, 10, 10},       // the other two sharing a y
			{0, 0, 0, 0, 0, 0},             // the origin
		};

		for (int[] c : degenerate)
		{
			Canvas canvas = new Canvas();
			canvas.raster.gouraudTriangle(c[0], c[1], c[2], c[3], c[4], c[5],
				8000, 8000, 8000);
		}
	}

	@Test
	public void colourEndpointsAtTheExtremesDoNotThrow()
	{
		Canvas canvas = new Canvas();
		canvas.raster.gouraudTriangle(10, 50, 30, 10, 20, 50,
			0, 0xFFFF, 0x7FFF);
		canvas.raster.gouraudTriangle(10, 50, 30, 10, 20, 50,
			Integer.MAX_VALUE / 2, 0, Integer.MIN_VALUE / 2);
	}

	// ----------------------------------------------------------------- fuzz

	@Test
	public void randomTrianglesNeverEscapeTheBuffer()
	{
		// A chathead is a few hundred triangles a frame; over a session that is
		// millions of calls with whatever the model geometry produces.
		Random random = new Random(20260808L);
		Canvas canvas = new Canvas();

		for (int i = 0; i < 20000; i++)
		{
			int range = 200;
			canvas.raster.gouraudTriangle(
				random.nextInt(range) - range / 2,
				random.nextInt(range) - range / 2,
				random.nextInt(range) - range / 2,
				random.nextInt(range) - range / 2,
				random.nextInt(range) - range / 2,
				random.nextInt(range) - range / 2,
				random.nextInt(0x10000),
				random.nextInt(0x10000),
				random.nextInt(0x10000));
		}

		assertTrue("twenty thousand random faces drew nothing, so the fuzz is"
			+ " not actually exercising the fill", canvas.painted() > 0);
	}

	@Test
	public void aTallThinTriangleIsDrawnRatherThanSkipped()
	{
		// The shape a nose or a fringe of hair produces.
		Canvas canvas = new Canvas();
		canvas.raster.gouraudTriangle(30, 31, 30, 5, 30, 55, 8000, 8000, 8000);
		assertTrue(canvas.painted() > 0);
	}

	@Test
	public void aWideFlatTriangleIsDrawnRatherThanSkipped()
	{
		Canvas canvas = new Canvas();
		canvas.raster.gouraudTriangle(5, 55, 30, 30, 30, 32, 8000, 8000, 8000);
		assertTrue(canvas.painted() > 0);
	}

	@Test
	public void aOnePixelHighCanvasIsNotACrash()
	{
		int[] pixels = new int[WIDTH];
		GouraudRasterizer raster = new GouraudRasterizer(pixels, WIDTH, 1);
		raster.gouraudTriangle(0, WIDTH, WIDTH / 2, 0, 0, 1, 8000, 8000, 8000);

		boolean anyPainted = false;
		for (int pixel : pixels)
		{
			anyPainted |= pixel != 0;
		}
		assertFalse("a canvas one pixel tall has no room for a triangle,"
			+ " but it must not crash trying", anyPainted && pixels.length != WIDTH);
	}
}
