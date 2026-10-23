// public:file chathead
package com.follower.ui;

/**
 * The client's own triangle rasterizer, ported from {@code Pix3D.gouraudTriangle}
 * and {@code Pix3D.gouraudRaster}.
 *
 * <p>This replaces a float barycentric fill that sampled pixel centres. On
 * triangles a few pixels across - the pupils of a chathead's eyes - the two
 * disagree visibly: a sub-pixel face can miss every pixel-centre sample and
 * vanish entirely, and adjacent faces can leave one-pixel cracks along shared
 * edges, letting the parchment behind show through as stray light pixels. The
 * game's rasterizer is an integer scanline walker with its own fill convention;
 * only running the same algorithm produces the same pixels.
 *
 * <p>Faithfulness notes:
 * <ul>
 * <li>x steps are 16.16 fixed point ({@code ((dx << 16) / dy)}), colour steps
 *     15-bit ({@code ((dc << 15) / dy)}); scanline endpoints are handed to the
 *     raster as {@code x >> 16} / {@code colour >> 7}, and the raster indexes
 *     the colour table with {@code colour >> 8} per pixel. Java's int division
 *     and shifts truncate exactly like the client's {@code | 0} arithmetic.</li>
 * <li>The client spells out three top-level cases, one per vertex that can be
 *     topmost, and they are the same code with the vertices rotated. Each edge
 *     step is the same value whichever way round it is computed, so rotating
 *     the arguments and running the first case reproduces the other two
 *     exactly. Inside that case the client splits on which vertex is in the
 *     middle and, for each, on which edge is on the left; the two orders of
 *     each pair differ only in the order the span ends are passed, which is
 *     {@link #span}'s flag. The left/right tests are the client's own, ties
 *     included.</li>
 * <li>Only the paths this plugin exercises are ported: high detail
 *     ({@code lowDetail = false}), opaque ({@code trans = 0}), with horizontal
 *     clipping always on - the client's {@code hclip = false} path is a
 *     speed-up that skips clamping, not a different fill.</li>
 * <li>Colours interpolate as PACKED palette indices and resolve through
 *     {@link GameColourTable}, the same gamma table the game uses.</li>
 * </ul>
 */
final class GouraudRasterizer
{
	private final int[] pixels;
	private final int width;
	private final int clipMaxY;

	GouraudRasterizer(int[] pixels, int width, int height)
	{
		this.pixels = pixels;
		this.width = width;
		this.clipMaxY = height;
	}

	void gouraudTriangle(int xA, int xB, int xC, int yA, int yB, int yC,
		int colourA, int colourB, int colourC)
	{
		if (yA <= yB && yA <= yC)
		{
			fromTop(0, xA, xB, xC, yA, yB, yC, colourA, colourB, colourC);
		}
		else if (yB <= yC)
		{
			fromTop(1, xB, xC, xA, yB, yC, yA, colourB, colourC, colourA);
		}
		else
		{
			fromTop(2, xC, xA, xB, yC, yA, yB, colourC, colourA, colourB);
		}
	}

	/**
	 * The client's first case, vertex A topmost, run on the vertices rotated
	 * {@code rotation} places. The client's other two cases are this code
	 * rotated, except that they leave out the tie tests that cannot fire on
	 * their unclipped rows - and so skip them on clipped rows too, which can
	 * tie. {@code tieTest} keeps that difference.
	 */
	private void fromTop(int rotation, int xA, int xB, int xC, int yA, int yB, int yC,
		int colourA, int colourB, int colourC)
	{
		int xStepAB = 0;
		int colourStepAB = 0;
		if (yB != yA)
		{
			xStepAB = ((xB - xA) << 16) / (yB - yA);
			colourStepAB = ((colourB - colourA) << 15) / (yB - yA);
		}

		int xStepBC = 0;
		int colourStepBC = 0;
		if (yC != yB)
		{
			xStepBC = ((xC - xB) << 16) / (yC - yB);
			colourStepBC = ((colourC - colourB) << 15) / (yC - yB);
		}

		int xStepAC = 0;
		int colourStepAC = 0;
		if (yC != yA)
		{
			xStepAC = ((xA - xC) << 16) / (yA - yC);
			colourStepAC = ((colourA - colourC) << 15) / (yA - yC);
		}

		if (yA >= clipMaxY)
		{
			return;
		}
		yB = Math.min(yB, clipMaxY);
		yC = Math.min(yC, clipMaxY);

		if (yB < yC)
		{
			walk(xA, xB, yA, yB, yC, colourA, colourB, xStepAB, colourStepAB,
				xStepBC, colourStepBC, xStepAC, colourStepAC, false, rotation < 2);
		}
		else
		{
			walk(xA, xC, yA, yC, yB, colourA, colourC, xStepAC, colourStepAC,
				xStepBC, colourStepBC, xStepAB, colourStepAB, true, rotation == 0);
		}
	}

	/**
	 * Fills from the top vertex down: the long edge runs top to bottom, the
	 * short edges top to middle and middle to bottom. {@code midIsC} is the
	 * client's second sub-case, where C is the middle vertex; it only changes
	 * which way an exact tie of slopes falls.
	 */
	private void walk(int xTop, int xMid, int yTop, int yMid, int yBottom, int colourTop, int colourMid,
		int xStepShort, int colourStepShort, int xStepLower, int colourStepLower,
		int xStepLong, int colourStepLong, boolean midIsC, boolean tieTest)
	{
		int xLong = xTop << 16;
		int xShort = xLong;
		int colourLong = colourTop << 15;
		int colourShort = colourLong;
		if (yTop < 0)
		{
			xLong -= xStepLong * yTop;
			xShort -= xStepShort * yTop;
			colourLong -= colourStepLong * yTop;
			colourShort -= colourStepShort * yTop;
			yTop = 0;
		}
		xMid <<= 16;
		colourMid <<= 15;
		if (yMid < 0)
		{
			xMid -= xStepLower * yMid;
			colourMid -= colourStepLower * yMid;
			yMid = 0;
		}

		// The client's left/right test, made on the clipped rows.
		boolean longLeft = !tieTest || yTop != yMid
			? xStepLong < xStepShort || (midIsC && xStepLong == xStepShort)
			: xStepLong > xStepLower || (midIsC && xStepLong == xStepLower);
		int off = width * yTop;
		for (int rows = yMid - yTop; rows-- > 0; off += width)
		{
			span(longLeft, xLong, xShort, colourLong, colourShort, off);
			xLong += xStepLong;
			xShort += xStepShort;
			colourLong += colourStepLong;
			colourShort += colourStepShort;
		}
		for (int rows = yBottom - yMid; rows-- > 0; off += width)
		{
			span(longLeft, xLong, xMid, colourLong, colourMid, off);
			xLong += xStepLong;
			xMid += xStepLower;
			colourLong += colourStepLong;
			colourMid += colourStepLower;
		}
	}

	/** One scanline between the long edge and a short one, in left-to-right order. */
	private void span(boolean longLeft, int xLong, int xShort, int colourLong, int colourShort, int off)
	{
		if (longLeft)
		{
			gouraudRaster(xLong >> 16, xShort >> 16, colourLong >> 7, colourShort >> 7, off);
		}
		else
		{
			gouraudRaster(xShort >> 16, xLong >> 16, colourShort >> 7, colourLong >> 7, off);
		}
	}

	/**
	 * One scanline, the client's high-detail opaque path: interpolate the packed
	 * palette index from {@code xA} (inclusive) to {@code xB} (exclusive) and
	 * resolve each pixel through the colour table. {@code off} is the row's base
	 * offset. Alpha is forced opaque for the ARGB output image - the client's
	 * canvas has no alpha channel to set.
	 */
	private void gouraudRaster(int xA, int xB, int colourA, int colourB, int off)
	{
		if (xA >= xB)
		{
			return;
		}
		int colourStep = (colourB - colourA) / (xB - xA);
		if (xB > width)
		{
			xB = width;
		}
		if (xA < 0)
		{
			colourA -= xA * colourStep;
			xA = 0;
		}
		for (off += xA; xA < xB; xA++)
		{
			pixels[off++] = 0xFF000000 | GameColourTable.rgb(colourA >> 8);
			colourA += colourStep;
		}
	}
}
