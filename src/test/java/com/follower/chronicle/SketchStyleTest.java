// public:file chronicle
package com.follower.chronicle;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A frame of the game becomes a plate in the scribe's hand: the right size,
 * the place's own colours softened on warm paper, ink where the picture has
 * edges and none where it has none, and the same every time it is drawn.
 */
public class SketchStyleTest
{
	private static final int W = 120;
	private static final int H = 50;

	private static BufferedImage flat(int rgb)
	{
		BufferedImage image = new BufferedImage(300, 125, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(rgb));
		g.fillRect(0, 0, 300, 125);
		g.dispose();
		return image;
	}

	/** Dark on the left half, light on the right: one hard edge down the middle. */
	private static BufferedImage halves()
	{
		BufferedImage image = flat(0xf0f0f0);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x101010));
		g.fillRect(0, 0, 150, 125);
		g.dispose();
		return image;
	}

	private static double darkness(int rgb)
	{
		return 1 - (0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff)) / 255.0;
	}

	@Test
	public void itComesOutThePlatesSize()
	{
		BufferedImage plate = SketchStyle.draw(flat(0x7090b0), W, H, 1);
		assertEquals(W, plate.getWidth());
		assertEquals(H, plate.getHeight());
	}

	@Test
	public void thePlaceKeepsItsColoursButSofter()
	{
		// Grass is still green and a sky still blue, or it is not the place;
		// but less vivid than the screen, as a wash is.
		BufferedImage plate = SketchStyle.draw(flat(0x20c040), W, H, 7);
		int rgb = plate.getRGB(W / 2, H / 2);
		int r = (rgb >> 16) & 0xff;
		int g = (rgb >> 8) & 0xff;
		int b = rgb & 0xff;
		assertTrue("still green: " + Integer.toHexString(rgb & 0xffffff), g > r && g > b);
		double before = saturation(0x20c040);
		double after = saturation(rgb);
		assertTrue("but softer: " + after + " against " + before, after < before);
	}

	@Test
	public void theWhiteOfTheViewIsThePaper()
	{
		// Nothing on the page is whiter than the page: the paper warms it.
		int rgb = SketchStyle.draw(flat(0xffffff), W, H, 9).getRGB(W / 2, H / 2);
		int r = (rgb >> 16) & 0xff;
		int g = (rgb >> 8) & 0xff;
		int b = rgb & 0xff;
		assertTrue("warm, as paper is: " + Integer.toHexString(rgb & 0xffffff), r >= g && g >= b && r > b + 10);
	}

	private static double saturation(int rgb)
	{
		int r = (rgb >> 16) & 0xff;
		int g = (rgb >> 8) & 0xff;
		int b = rgb & 0xff;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		return max == 0 ? 0 : (max - min) / (double) max;
	}

	@Test
	public void anEdgeGetsALineAndAFlatPlaceDoesNot()
	{
		// Two similar greys side by side: their washes are much alike, so only
		// a line of ink can make the boundary darker than both.
		BufferedImage source = flat(0xb4b4b4);
		Graphics2D g = source.createGraphics();
		g.setColor(new Color(0x8c8c8c));
		g.fillRect(0, 0, 150, 125);
		g.dispose();
		BufferedImage plate = SketchStyle.draw(source, W, H, 3);
		int row = H / 2;
		double onTheEdge = 0;
		for (int x = W / 2 - 3; x <= W / 2 + 3; x++)
		{
			onTheEdge = Math.max(onTheEdge, darkness(plate.getRGB(x, row)));
		}
		double left = darkness(plate.getRGB(W / 4, row));
		double right = darkness(plate.getRGB(W * 3 / 4, row));
		assertTrue("the edge is inked: " + onTheEdge + " against " + left + " and " + right,
			onTheEdge > Math.max(left, right) + 0.15);

		BufferedImage blank = SketchStyle.draw(flat(0xc0c0c0), W, H, 3);
		double min = 1;
		double max = 0;
		for (int x = W / 4; x < W * 3 / 4; x++)
		{
			double d = darkness(blank.getRGB(x, row));
			min = Math.min(min, d);
			max = Math.max(max, d);
		}
		assertTrue("nothing to draw in a flat wall, only grain: " + (max - min), max - min < 0.08);
	}

	@Test
	public void theLightAndTheDarkStayTheLightAndTheDark()
	{
		BufferedImage plate = SketchStyle.draw(halves(), W, H, 5);
		assertTrue("the dark half is darker", darkness(plate.getRGB(W / 8, H / 2))
			> darkness(plate.getRGB(W * 7 / 8, H / 2)) + 0.3);
	}

	@Test
	public void theSameViewDrawsTheSameEveryTime()
	{
		BufferedImage a = SketchStyle.draw(halves(), W, H, 42);
		BufferedImage b = SketchStyle.draw(halves(), W, H, 42);
		for (int y = 0; y < H; y++)
		{
			for (int x = 0; x < W; x++)
			{
				assertEquals(a.getRGB(x, y), b.getRGB(x, y));
			}
		}
	}
}
