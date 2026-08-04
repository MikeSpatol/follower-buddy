package com.follower.ui;

/**
 * The client's own triangle rasterizer, ported line for line from
 * {@code Pix3D.gouraudTriangle} and {@code Pix3D.gouraudRaster}.
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
 * <li>The three top-level cases order the vertices by smallest y; each splits
 *     again on edge-slope comparisons so the left/right raster arguments stay
 *     sorted without per-scanline swaps.</li>
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
	private final int sizeX;
	private final int clipMaxY;
	private final int[] scanline;

	GouraudRasterizer(int[] pixels, int width, int height)
	{
		this.pixels = pixels;
		this.width = width;
		this.sizeX = width;
		this.clipMaxY = height;
		this.scanline = new int[height];
		for (int y = 0; y < height; y++)
		{
			scanline[y] = width * y;
		}
	}

	@SuppressWarnings("java:S3776") // the client's control flow, kept verbatim
	void gouraudTriangle(int xA, int xB, int xC, int yA, int yB, int yC,
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

		if (yA <= yB && yA <= yC)
		{
			if (yA >= clipMaxY)
			{
				return;
			}
			if (yB > clipMaxY)
			{
				yB = clipMaxY;
			}
			if (yC > clipMaxY)
			{
				yC = clipMaxY;
			}

			if (yB < yC)
			{
				xC = xA <<= 16;
				colourC = colourA <<= 15;
				if (yA < 0)
				{
					xC -= xStepAC * yA;
					xA -= xStepAB * yA;
					colourC -= colourStepAC * yA;
					colourA -= colourStepAB * yA;
					yA = 0;
				}
				xB <<= 16;
				colourB <<= 15;
				if (yB < 0)
				{
					xB -= xStepBC * yB;
					colourB -= colourStepBC * yB;
					yB = 0;
				}
				if ((yA != yB && xStepAC < xStepAB) || (yA == yB && xStepAC > xStepBC))
				{
					yC -= yB;
					yB -= yA;
					yA = scanline[yA];
					while (true)
					{
						yB--;
						if (yB < 0)
						{
							while (true)
							{
								yC--;
								if (yC < 0)
								{
									return;
								}
								gouraudRaster(xC >> 16, xB >> 16, colourC >> 7, colourB >> 7, yA);
								xC += xStepAC;
								xB += xStepBC;
								colourC += colourStepAC;
								colourB += colourStepBC;
								yA += width;
							}
						}
						gouraudRaster(xC >> 16, xA >> 16, colourC >> 7, colourA >> 7, yA);
						xC += xStepAC;
						xA += xStepAB;
						colourC += colourStepAC;
						colourA += colourStepAB;
						yA += width;
					}
				}
				else
				{
					yC -= yB;
					yB -= yA;
					yA = scanline[yA];
					while (true)
					{
						yB--;
						if (yB < 0)
						{
							while (true)
							{
								yC--;
								if (yC < 0)
								{
									return;
								}
								gouraudRaster(xB >> 16, xC >> 16, colourB >> 7, colourC >> 7, yA);
								xC += xStepAC;
								xB += xStepBC;
								colourC += colourStepAC;
								colourB += colourStepBC;
								yA += width;
							}
						}
						gouraudRaster(xA >> 16, xC >> 16, colourA >> 7, colourC >> 7, yA);
						xC += xStepAC;
						xA += xStepAB;
						colourC += colourStepAC;
						colourA += colourStepAB;
						yA += width;
					}
				}
			}
			else
			{
				xB = xA <<= 16;
				colourB = colourA <<= 15;
				if (yA < 0)
				{
					xB -= xStepAC * yA;
					xA -= xStepAB * yA;
					colourB -= colourStepAC * yA;
					colourA -= colourStepAB * yA;
					yA = 0;
				}
				xC <<= 16;
				colourC <<= 15;
				if (yC < 0)
				{
					xC -= xStepBC * yC;
					colourC -= colourStepBC * yC;
					yC = 0;
				}
				if ((yA != yC && xStepAC < xStepAB) || (yA == yC && xStepBC > xStepAB))
				{
					yB -= yC;
					yC -= yA;
					yA = scanline[yA];
					while (true)
					{
						yC--;
						if (yC < 0)
						{
							while (true)
							{
								yB--;
								if (yB < 0)
								{
									return;
								}
								gouraudRaster(xC >> 16, xA >> 16, colourC >> 7, colourA >> 7, yA);
								xC += xStepBC;
								xA += xStepAB;
								colourC += colourStepBC;
								colourA += colourStepAB;
								yA += width;
							}
						}
						gouraudRaster(xB >> 16, xA >> 16, colourB >> 7, colourA >> 7, yA);
						xB += xStepAC;
						xA += xStepAB;
						colourB += colourStepAC;
						colourA += colourStepAB;
						yA += width;
					}
				}
				else
				{
					yB -= yC;
					yC -= yA;
					yA = scanline[yA];
					while (true)
					{
						yC--;
						if (yC < 0)
						{
							while (true)
							{
								yB--;
								if (yB < 0)
								{
									return;
								}
								gouraudRaster(xA >> 16, xC >> 16, colourA >> 7, colourC >> 7, yA);
								xC += xStepBC;
								xA += xStepAB;
								colourC += colourStepBC;
								colourA += colourStepAB;
								yA += width;
							}
						}
						gouraudRaster(xA >> 16, xB >> 16, colourA >> 7, colourB >> 7, yA);
						xB += xStepAC;
						xA += xStepAB;
						colourB += colourStepAC;
						colourA += colourStepAB;
						yA += width;
					}
				}
			}
		}
		else if (yB <= yC)
		{
			if (yB >= clipMaxY)
			{
				return;
			}
			if (yC > clipMaxY)
			{
				yC = clipMaxY;
			}
			if (yA > clipMaxY)
			{
				yA = clipMaxY;
			}

			if (yC < yA)
			{
				xA = xB <<= 16;
				colourA = colourB <<= 15;
				if (yB < 0)
				{
					xA -= xStepAB * yB;
					xB -= xStepBC * yB;
					colourA -= colourStepAB * yB;
					colourB -= colourStepBC * yB;
					yB = 0;
				}
				xC <<= 16;
				colourC <<= 15;
				if (yC < 0)
				{
					xC -= xStepAC * yC;
					colourC -= colourStepAC * yC;
					yC = 0;
				}
				if ((yB != yC && xStepAB < xStepBC) || (yB == yC && xStepAB > xStepAC))
				{
					yA -= yC;
					yC -= yB;
					yB = scanline[yB];
					while (true)
					{
						yC--;
						if (yC < 0)
						{
							while (true)
							{
								yA--;
								if (yA < 0)
								{
									return;
								}
								gouraudRaster(xA >> 16, xC >> 16, colourA >> 7, colourC >> 7, yB);
								xA += xStepAB;
								xC += xStepAC;
								colourA += colourStepAB;
								colourC += colourStepAC;
								yB += width;
							}
						}
						gouraudRaster(xA >> 16, xB >> 16, colourA >> 7, colourB >> 7, yB);
						xA += xStepAB;
						xB += xStepBC;
						colourA += colourStepAB;
						colourB += colourStepBC;
						yB += width;
					}
				}
				else
				{
					yA -= yC;
					yC -= yB;
					yB = scanline[yB];
					while (true)
					{
						yC--;
						if (yC < 0)
						{
							while (true)
							{
								yA--;
								if (yA < 0)
								{
									return;
								}
								gouraudRaster(xC >> 16, xA >> 16, colourC >> 7, colourA >> 7, yB);
								xA += xStepAB;
								xC += xStepAC;
								colourA += colourStepAB;
								colourC += colourStepAC;
								yB += width;
							}
						}
						gouraudRaster(xB >> 16, xA >> 16, colourB >> 7, colourA >> 7, yB);
						xA += xStepAB;
						xB += xStepBC;
						colourA += colourStepAB;
						colourB += colourStepBC;
						yB += width;
					}
				}
			}
			else
			{
				xC = xB <<= 16;
				colourC = colourB <<= 15;
				if (yB < 0)
				{
					xC -= xStepAB * yB;
					xB -= xStepBC * yB;
					colourC -= colourStepAB * yB;
					colourB -= colourStepBC * yB;
					yB = 0;
				}
				xA <<= 16;
				colourA <<= 15;
				if (yA < 0)
				{
					xA -= xStepAC * yA;
					colourA -= colourStepAC * yA;
					yA = 0;
				}
				yC -= yA;
				yA -= yB;
				yB = scanline[yB];
				if (xStepAB < xStepBC)
				{
					while (true)
					{
						yA--;
						if (yA < 0)
						{
							while (true)
							{
								yC--;
								if (yC < 0)
								{
									return;
								}
								gouraudRaster(xA >> 16, xB >> 16, colourA >> 7, colourB >> 7, yB);
								xA += xStepAC;
								xB += xStepBC;
								colourA += colourStepAC;
								colourB += colourStepBC;
								yB += width;
							}
						}
						gouraudRaster(xC >> 16, xB >> 16, colourC >> 7, colourB >> 7, yB);
						xC += xStepAB;
						xB += xStepBC;
						colourC += colourStepAB;
						colourB += colourStepBC;
						yB += width;
					}
				}
				else
				{
					while (true)
					{
						yA--;
						if (yA < 0)
						{
							while (true)
							{
								yC--;
								if (yC < 0)
								{
									return;
								}
								gouraudRaster(xB >> 16, xA >> 16, colourB >> 7, colourA >> 7, yB);
								xA += xStepAC;
								xB += xStepBC;
								colourA += colourStepAC;
								colourB += colourStepBC;
								yB += width;
							}
						}
						gouraudRaster(xB >> 16, xC >> 16, colourB >> 7, colourC >> 7, yB);
						xC += xStepAB;
						xB += xStepBC;
						colourC += colourStepAB;
						colourB += colourStepBC;
						yB += width;
					}
				}
			}
		}
		else
		{
			if (yC >= clipMaxY)
			{
				return;
			}
			if (yA > clipMaxY)
			{
				yA = clipMaxY;
			}
			if (yB > clipMaxY)
			{
				yB = clipMaxY;
			}

			if (yA < yB)
			{
				xB = xC <<= 16;
				colourB = colourC <<= 15;
				if (yC < 0)
				{
					xB -= xStepBC * yC;
					xC -= xStepAC * yC;
					colourB -= colourStepBC * yC;
					colourC -= colourStepAC * yC;
					yC = 0;
				}
				xA <<= 16;
				colourA <<= 15;
				if (yA < 0)
				{
					xA -= xStepAB * yA;
					colourA -= colourStepAB * yA;
					yA = 0;
				}
				yB -= yA;
				yA -= yC;
				yC = scanline[yC];
				if (xStepBC < xStepAC)
				{
					while (true)
					{
						yA--;
						if (yA < 0)
						{
							while (true)
							{
								yB--;
								if (yB < 0)
								{
									return;
								}
								gouraudRaster(xB >> 16, xA >> 16, colourB >> 7, colourA >> 7, yC);
								xB += xStepBC;
								xA += xStepAB;
								colourB += colourStepBC;
								colourA += colourStepAB;
								yC += width;
							}
						}
						gouraudRaster(xB >> 16, xC >> 16, colourB >> 7, colourC >> 7, yC);
						xB += xStepBC;
						xC += xStepAC;
						colourB += colourStepBC;
						colourC += colourStepAC;
						yC += width;
					}
				}
				else
				{
					while (true)
					{
						yA--;
						if (yA < 0)
						{
							while (true)
							{
								yB--;
								if (yB < 0)
								{
									return;
								}
								gouraudRaster(xA >> 16, xB >> 16, colourA >> 7, colourB >> 7, yC);
								xB += xStepBC;
								xA += xStepAB;
								colourB += colourStepBC;
								colourA += colourStepAB;
								yC += width;
							}
						}
						gouraudRaster(xC >> 16, xB >> 16, colourC >> 7, colourB >> 7, yC);
						xB += xStepBC;
						xC += xStepAC;
						colourB += colourStepBC;
						colourC += colourStepAC;
						yC += width;
					}
				}
			}
			else
			{
				xA = xC <<= 16;
				colourA = colourC <<= 15;
				if (yC < 0)
				{
					xA -= xStepBC * yC;
					xC -= xStepAC * yC;
					colourA -= colourStepBC * yC;
					colourC -= colourStepAC * yC;
					yC = 0;
				}
				xB <<= 16;
				colourB <<= 15;
				if (yB < 0)
				{
					xB -= xStepAB * yB;
					colourB -= colourStepAB * yB;
					yB = 0;
				}
				yA -= yB;
				yB -= yC;
				yC = scanline[yC];
				if (xStepBC < xStepAC)
				{
					while (true)
					{
						yB--;
						if (yB < 0)
						{
							while (true)
							{
								yA--;
								if (yA < 0)
								{
									return;
								}
								gouraudRaster(xB >> 16, xC >> 16, colourB >> 7, colourC >> 7, yC);
								xB += xStepAB;
								xC += xStepAC;
								colourB += colourStepAB;
								colourC += colourStepAC;
								yC += width;
							}
						}
						gouraudRaster(xA >> 16, xC >> 16, colourA >> 7, colourC >> 7, yC);
						xA += xStepBC;
						xC += xStepAC;
						colourA += colourStepBC;
						colourC += colourStepAC;
						yC += width;
					}
				}
				else
				{
					while (true)
					{
						yB--;
						if (yB < 0)
						{
							while (true)
							{
								yA--;
								if (yA < 0)
								{
									return;
								}
								gouraudRaster(xC >> 16, xB >> 16, colourC >> 7, colourB >> 7, yC);
								xB += xStepAB;
								xC += xStepAC;
								colourB += colourStepAB;
								colourC += colourStepAC;
								yC += width;
							}
						}
						gouraudRaster(xC >> 16, xA >> 16, colourC >> 7, colourA >> 7, yC);
						xA += xStepBC;
						xC += xStepAC;
						colourA += colourStepBC;
						colourC += colourStepAC;
						yC += width;
					}
				}
			}
		}
	}

	/**
	 * One scanline, the client's high-detail opaque path: interpolate the packed
	 * palette index from {@code xA} (inclusive) to {@code xB} (exclusive) and
	 * resolve each pixel through the colour table. {@code off} is the row's base
	 * offset (from the scanline table). Alpha is forced opaque for the ARGB
	 * output image - the client's canvas has no alpha channel to set.
	 */
	private void gouraudRaster(int xA, int xB, int colourA, int colourB, int off)
	{
		if (xA >= xB)
		{
			return;
		}
		int colourStep = (colourB - colourA) / (xB - xA);
		if (xB > sizeX)
		{
			xB = sizeX;
		}
		if (xA < 0)
		{
			colourA -= xA * colourStep;
			xA = 0;
		}
		if (xA >= xB)
		{
			return;
		}
		off += xA;
		int len = xB - xA;
		do
		{
			pixels[off++] = 0xFF000000 | GameColourTable.rgb(colourA >> 8);
			colourA += colourStep;
			len--;
		}
		while (len > 0);
	}
}
